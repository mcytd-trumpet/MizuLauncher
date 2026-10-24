package com.mizulauncher.setup

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * 内置组件的**解压安装器**。
 *
 * 对应 ZalithLauncher2 首启那个「需要解压以下组件」的提示框：
 * 组件本来就在 APK 里，这一步**不联网**，只是把 `assets` 里的 zip 铺到私有目录。
 * 之所以不在 `Application.onCreate` 里偷偷解，是因为：
 *  1. 好几百兆的解压要几十秒，用户会以为「卡住了」；
 *  2. 让用户看到在装什么，才明白为什么要等。
 *
 * ## 装完之后
 * 每个组件装成功都会写一条 `key=version@fingerprint` 到 [BundleLedgerStore]，
 * 下次启动比对指纹，一致就跳过。升级 APK 若带来新版组件，指纹变化 → 再次提示。
 */
class BundleInstaller(private val context: Context) {

    companion object {
        private const val TAG = "BundleInstaller"
    }

    /** 单个组件的安装状态 */
    sealed interface ItemState {
        data object Pending : ItemState
        data class Installing(val percent: Int, val detail: String = "") : ItemState
        data object Done : ItemState
        data class Failed(val message: String) : ItemState
    }

    /** 整体流程 */
    sealed interface Phase {
        /** 还没开始，等待用户点「安装」 */
        data object Idle : Phase
        data class Running(val currentLabel: String, val percent: Int) : Phase
        data object Finished : Phase
        data class Failed(val message: String) : Phase
    }

    private val _items = MutableStateFlow<List<BundleCatalog.Item>>(emptyList())
    val items: StateFlow<List<BundleCatalog.Item>> = _items.asStateFlow()

    private val _pending = MutableStateFlow<List<BundleCatalog.Item>>(emptyList())

    /** 待安装（含已装但版本对不上）的组件，非空 → 应弹窗提示 */
    val pending: StateFlow<List<BundleCatalog.Item>> = _pending.asStateFlow()

    private val _states = MutableStateFlow<Map<String, ItemState>>(emptyMap())
    val states: StateFlow<Map<String, ItemState>> = _states.asStateFlow()

    private val _phase = MutableStateFlow<Phase>(Phase.Idle)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private val _version = MutableStateFlow(0)

    /** 每次扫描/安装后 +1，UI 用它判断是否要重新渲染 */
    val version: StateFlow<Int> = _version.asStateFlow()

    private val ledger = BundleLedgerStore(context)

    /** 解压根目录：应用私有 files/（不是 externalFilesDir，避免被文件管理器误删） */
    val installRoot: File get() = File(context.filesDir, "bundles").apply { mkdirs() }

    fun dirOf(item: BundleCatalog.Item): File = File(installRoot, item.targetDir)

    /** Java 运行时的可执行目录（`bin/java` 所在目录） */
    fun javaHomeOf(major: Int): File = File(installRoot, "runtime/java-$major")

    // ---------------- 扫描 ----------------

    /**
     * 扫一遍 assets，和已安装记录比对，算出待安装列表。
     *
     * 这一步**很快**（只读资产目录与 zip 中央目录），可以在启动时直接调。
     */
    fun scan(abi: String = primaryAbi()): List<BundleCatalog.Item> {
        // 先把上次被系统杀掉时留下的半截目录清掉，否则会被误判成已安装
        recoverInterrupted()
        val all = BundleCatalog.discover(listAssets(""), abi) { path ->
            // 未压缩存储的资产能直接取长度；压缩过的返回 -1，UI 上按「未知大小」展示
            runCatching { context.assets.openFd(path).use { it.length } }.getOrDefault(-1L)
        }
        val records = BundleCatalog.Ledger.decode(ledger.raw())
        // 指纹依赖 zip 文件本身，先把每个内置 zip 落到缓存目录再过一遍 CRC
        val fpCache = HashMap<String, String>()
        val pending = BundleCatalog.Ledger.pending(
            items = all,
            records = records,
            fingerprintOf = { item ->
                fpCache.getOrPut(item.key) {
                    BundleCatalog.fingerprint(item, cacheAsset(item.assetPath))
                }
            },
            present = { item -> isInstalled(item) }
        )
        _items.value = all
        _pending.value = pending
        _version.value = _version.value + 1
        Log.i(TAG, "扫描内置组件：共 ${all.size} 项，待安装 ${pending.size} 项")
        return pending
    }

    /** 目录里是否有真实内容（不只看记录，防止用户清过数据） */
    fun isInstalled(item: BundleCatalog.Item): Boolean {
        val dir = dirOf(item)
        if (!dir.isDirectory) return false
        return when (item.kind) {
            BundleCatalog.KIND_JAVA -> hasJvm(dir)
            else -> (dir.listFiles()?.isNotEmpty() == true)
        }
    }

    private fun hasJvm(dir: File): Boolean = listOf(
        "bin/java",
        "lib/server/libjvm.so",
        "lib/client/libjvm.so",
        "jre/bin/java",
        "jre/lib/server/libjvm.so"
    ).any { File(dir, it).exists() }

    // ---------------- 安装 ----------------

    /**
     * 依次解压所有待安装组件。
     *
     * 串行执行而不是并发：解压是纯 IO + CPU 密集，并发只会让闪存更慢，
     * 而且用户看着「一项一项完成」比看五个进度条同时乱跳安心。
     */
    suspend fun installAll(targets: List<BundleCatalog.Item> = _pending.value): Boolean =
        withContext(Dispatchers.IO) {
            if (targets.isEmpty()) {
                _phase.value = Phase.Finished
                return@withContext true
            }
            _phase.value = Phase.Running("准备安装", 0)
            markInProgress(targets)

            val records = BundleCatalog.Ledger.decode(ledger.raw()).toMutableMap()
            for ((index, item) in targets.withIndex()) {
                currentCoroutineContext().ensureActive()
                _phase.value = Phase.Running(item.label, (index * 100) / targets.size)
                setState(item, ItemState.Installing(0, "正在解压"))

                val ok = runCatching { extract(item) { pct ->
                    setState(item, ItemState.Installing(pct, "正在解压"))
                } }.getOrElse { e ->
                    Log.e(TAG, "解压 ${item.assetPath} 失败", e)
                    setState(item, ItemState.Failed(e.message ?: "解压失败"))
                    _phase.value = Phase.Failed("${item.label} 安装失败：${e.message}")
                    // 失败时把半截目录删掉，否则下次扫描会误判为「已安装」
                    runCatching { dirOf(item).deleteRecursively() }
                    clearInProgress()
                    return@withContext false
                }

                if (!ok) return@withContext false

                records[item.key] = BundleCatalog.Ledger.expected(
                    item,
                    BundleCatalog.fingerprint(item, cacheAsset(item.assetPath))
                )
                ledger.save(BundleCatalog.Ledger.encode(records))
                setState(item, ItemState.Done)
                _phase.value = Phase.Running(item.label, ((index + 1) * 100) / targets.size)
            }

            clearInProgress()
            _pending.value = emptyList()
            _phase.value = Phase.Finished
            _version.value = _version.value + 1
            true
        }

    // ---------------- 中断恢复 ----------------

    /**
     * 记录「正在安装」的组件清单。
     *
     * 用户在解压 200MB 的 JRE 时切后台，系统很可能直接杀进程。
     * 这时候目录里是半截文件，但账本还没写入——下次启动如果不清理，
     * 会出现「显示已就绪，一启动就崩」的最坏情况。所以留一个标记，启动时据此清场。
     */
    private fun markInProgress(items: List<BundleCatalog.Item>) {
        runCatching {
            File(installRoot, ".installing").writeText(items.joinToString("\n") { it.key })
        }
    }

    private fun clearInProgress() {
        runCatching { File(installRoot, ".installing").delete() }
    }

    /**
     * 清理上次被中断的残留目录。必须在 [scan] 之前调用。
     */
    fun recoverInterrupted() {
        val marker = File(installRoot, ".installing")
        if (!marker.isFile) return
        val keys = runCatching { marker.readLines().filter { it.isNotBlank() } }.getOrDefault(emptyList())
        val all = BundleCatalog.discover(listAssets(""), primaryAbi()) { -1L }
        keys.forEach { key ->
            all.firstOrNull { it.key == key }?.let { item ->
                Log.w(TAG, "清理上次中断的安装：${item.key}")
                runCatching { dirOf(item).deleteRecursively() }
            }
        }
        clearInProgress()
    }

    /**
     * 解压单个组件。
     *
     * @return true 表示成功
     */
    private fun extract(item: BundleCatalog.Item, onProgress: (Int) -> Unit): Boolean {
        val target = dirOf(item)
        // 重装前清干净：残留的旧版本文件混在新版本里，最容易出「找不到符号」这类玄学问题
        if (target.exists()) target.deleteRecursively()
        target.mkdirs()

        val totalHint = runCatching {
            context.assets.openFd(item.assetPath).use { it.length }
        }.getOrDefault(-1L)

        context.assets.open(item.assetPath).use { input ->
            unzip(input, target, totalHint, onProgress)
        }
        flattenIfNeeded(target)

        if (item.kind == BundleCatalog.KIND_JAVA && !hasJvm(target)) {
            throw IOException("解压完成但未找到 JVM，压缩包可能损坏")
        }
        return true
    }

    private fun unzip(
        input: java.io.InputStream,
        target: File,
        totalHint: Long,
        onProgress: (Int) -> Unit
    ) {
        var done = 0L
        var lastPct = -1
        var entryIndex = 0
        ZipInputStream(input.buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val out = File(target, entry.name)
                // 防 zip slip：解压路径必须落在目标目录内
                if (!out.canonicalPath.startsWith(target.canonicalPath + File.separator)) {
                    throw SecurityException("非法压缩路径：${entry.name}")
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { zis.copyTo(it) }
                    // bin/ 下的可执行文件必须补上执行位，否则 Runtime.exec 直接 EACCES
                    if (entry.name.startsWith("bin/") || entry.name.contains("/bin/")) {
                        out.setExecutable(true, false)
                    }
                    done += entry.compressedSize.coerceAtLeast(1)
                }
                entryIndex++
                val pct = when {
                    totalHint > 0 -> ((done * 100) / totalHint).toInt()
                    // 拿不到总长度：按条目数给一个 0~95 的近似进度，剩余 5% 留给收尾
                    else -> (entryIndex % 95).coerceAtMost(95)
                }.coerceIn(0, 100)
                if (pct != lastPct) {
                    lastPct = pct
                    onProgress(pct)
                }
                zis.closeEntry()
            }
        }
        onProgress(100)
    }

    /** 有些 JRE 包内层还套一层 `jre/` 或 `jdk-xx/`，解压后上提一层 */
    private fun flattenIfNeeded(target: File) {
        if (hasJvm(target)) return
        val children = target.listFiles()?.filter { it.isDirectory } ?: return
        if (children.size != 1) return
        val inner = children.first()
        if (!File(inner, "bin").exists() && !File(inner, "lib").exists()) return
        inner.listFiles()?.forEach { f -> f.renameTo(File(target, f.name)) }
        inner.delete()
    }

    // ---------------- 缓存内置 zip ----------------

    /**
     * 把资产里的 zip 复制到缓存目录。
     *
     * 为什么需要：`assets.openFd()` 依赖 APK 内的未压缩 entries，
     * 不同 AGP 版本 / universal 包的路径各不相同，直接用 `ZipFile` 打开 APK 读中央目录最稳。
     * 复制一份还能让 CRC 校验和后续解压复用同一个文件句柄。
     */
    private fun cacheAsset(assetPath: String): File? {
        // 缓存目录带上 APK 的 versionCode：升级后同一路径下的资产其实已经是新文件，
        // 若沿用旧缓存，算出来的指纹不变 → 永远不提示补全 → 用户用着旧 JRE 报错。
        val cache = File(context.cacheDir, "bundle-index/${apkVersionTag()}").apply { mkdirs() }
        val f = File(cache, assetPath.replace('/', '_'))
        if (f.isFile && f.length() > 0) return f
        return runCatching {
            context.assets.open(assetPath).use { input ->
                f.outputStream().use { input.copyTo(it) }
            }
            f
        }.getOrNull()
    }

    /** 装完之后清掉索引缓存，省几十兆临时空间（会连旧版本的缓存一起清） */
    fun clearIndexCache() {
        runCatching { File(context.cacheDir, "bundle-index").deleteRecursively() }
    }

    /** 当前 APK 的版本标记；取不到就用包的最后更新时间兜底 */
    private fun apkVersionTag(): String = runCatching {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionCode}_${info.lastUpdateTime}"
    }.getOrDefault("unknown")

    private fun setState(item: BundleCatalog.Item, state: ItemState) {
        _states.value = _states.value + (item.key to state)
        _version.value = _version.value + 1
    }

    // ---------------- 工具 ----------------

    /** 递归列出 assets 下所有文件路径（相对 assets 根） */
    private fun listAssets(prefix: String): List<String> {
        val out = ArrayList<String>()
        fun walk(path: String) {
            val children = runCatching { context.assets.list(path) }.getOrNull() ?: return
            if (children.isEmpty()) {
                if (path.isNotEmpty()) out += path
                return
            }
            children.forEach { child ->
                val next = if (path.isEmpty()) child else "$path/$child"
                walk(next)
            }
        }
        walk(prefix)
        return out.filter { it.endsWith(".zip") }
    }

    /** 当前设备首选 ABI（与 RuntimeManager 保持一致） */
    fun primaryAbi(): String = runCatching {
        android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
    }.getOrDefault("arm64-v8a")

    /** 某项是否正在装（UI 判断要不要画进度条） */
    fun stateOf(item: BundleCatalog.Item): ItemState =
        _states.value[item.key] ?: ItemState.Pending
}

/** 已安装组件账本，落在 SharedPreferences（体积极小，只存 key=version@fp） */
class BundleLedgerStore(context: Context) {
    private val prefs = context.getSharedPreferences("mizu_bundles", Context.MODE_PRIVATE)

    fun raw(): String? = prefs.getString(KEY, null)

    fun save(encoded: String) = prefs.edit().putString(KEY, encoded).apply()

    fun clear() = prefs.edit().remove(KEY).apply()

    private companion object {
        const val KEY = "ledger"
    }
}
