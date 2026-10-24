package com.mizulauncher.runtime

import android.content.Context
import com.mizulauncher.download.DownloadClass
import com.mizulauncher.download.DownloadEngine
import com.mizulauncher.download.DownloadSource
import com.mizulauncher.download.DownloadTask
import com.mizulauncher.download.UrlRewriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Java 运行时管理器。
 *
 * ## 为什么要自己管 JRE
 * Minecraft 各版本对 Java 的要求是硬性的（class file version 不匹配直接 `UnsupportedClassVersionError`）：
 *
 * | MC 版本 | Java |
 * |---|---|
 * | ≤ 1.16.5 | 8 |
 * | 1.17 ~ 1.20.4 | 17 |
 * | 1.20.5 ~ 1.21.x | 21 |
 * | 更新的版本/快照 | 25 |
 *
 * ## 两条来源并存
 * 1. **内置**：APK `assets/runtime/<abi>/jre-<major>.zip`（CI 打包时塞进去的）。
 *    由 `core:setup` 的首启「补全依赖」流程解压到 `files/bundles/runtime/java-<major>`，
 *    本类只负责**使用**，不重复解压——同一份 JRE 铺两遍既慢又费空间；
 * 2. **按需下载**：包里没带的（比如 32 位机型里的 Java 25），走 [DownloadEngine] 从远端拉取。
 *
 * 先内置、后下载 —— 这样装机即用，又不会因为漏打一个架构就彻底不可用。
 */
class RuntimeManager(
    private val context: Context,
    private val sourceFlow: StateFlow<DownloadSource>,
    /** 远端清单地址，由设置/CI 注入；为空时只用内置 */
    private val manifestUrlProvider: () -> String?,
    private val client: OkHttpClient = DownloadEngine.defaultClient(),
    /**
     * 已由首启「补全依赖」流程解压好的运行时根目录（`files/bundles/runtime`）。
     * 新版 APK 装配后 JRE 先走这里，不必再解一次。
     */
    private val extractedRootProvider: () -> File? = { null }
) {

    /** 支持的 Java 大版本，顺序即 UI 展示顺序 */
    companion object {
        val SUPPORTED_MAJORS = listOf(8, 17, 21, 25)

        /** 32 位 ABI 不提供 Java 25（上游没有健全的 32 位移植） */
        fun isSupported(major: Int, abi: String): Boolean {
            if (major !in SUPPORTED_MAJORS) return false
            if (major >= 25 && is32Bit(abi)) return false
            return true
        }

        fun is32Bit(abi: String): Boolean =
            abi == "armeabi-v7a" || abi == "x86" || abi == "arm" || abi == "arm32"

        /** 当前设备的首选 ABI */
        fun primaryAbi(): String = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
    }

    /** 单个运行时的状态 */
    sealed interface State {
        data object NotInstalled : State
        data class Downloading(val percent: Int, val detail: String) : State
        data class Extracting(val percent: Int) : State
        data class Ready(val path: String, val sizeBytes: Long) : State
        /** 内置与远端都没有 */
        data class Unavailable(val reason: String) : State
        data class Failed(val message: String) : State
    }

    private val _states = MutableStateFlow<Map<Int, State>>(initStates())
    val states: StateFlow<Map<Int, State>> = _states.asStateFlow()

    private val runtimesDir: File
        get() = File(context.filesDir, "runtime").apply { mkdirs() }

    /**
     * Java 大版本对应的目录。
     *
     * 优先级：首启解压出来的 `bundles/runtime/java-x` → 本模块自管的 `files/runtime/java-x`。
     * 两者都存在时以前者为准，避免同一份 JRE 占两遍磁盘。
     */
    fun dirOf(major: Int): File {
        extractedRootProvider()?.let { root ->
            val shared = File(root, "java-$major")
            if (isReadyDir(shared)) return shared
        }
        return File(runtimesDir, "java-$major")
    }

    /** 本模块自管的目录（下载/解压的落点） */
    private fun managedDirOf(major: Int): File = File(runtimesDir, "java-$major")

    // ---------------- 状态刷新 ----------------

    /** 重新扫描磁盘，更新所有版次的就绪状态 */
    fun refresh() {
        _states.value = initStates()
    }

    /**
     * 扫描磁盘生成状态表。
     * 关键点：**已在下载/解压中的条目必须保留原状态**，否则用户点「安装」的瞬间
     * 一次 [refresh] 就会把 UI 上的进度条打回「未安装」。
     */
    private fun initStates(): Map<Int, State> {
        val abi = primaryAbi()
        val previous = _states.value
        val map = HashMap<Int, State>()
        for (major in SUPPORTED_MAJORS) {
            val prev = previous[major]
            map[major] = when {
                !isSupported(major, abi) ->
                    State.Unavailable("当前设备为 $abi，该架构没有 Java $major 的可用运行时")
                prev is State.Downloading || prev is State.Extracting -> prev
                isReady(major) ->
                    State.Ready(dirOf(major).absolutePath, sizeOf(dirOf(major)))
                else -> State.NotInstalled
            }
        }
        return map
    }

    /** 目录是否已是一个可用的 JRE（有 bin/java 或 libjvm.so 之一即可） */
    fun isReady(major: Int): Boolean = isReadyDir(dirOf(major))

    /** 判断任意目录是否为可用 JRE（解压后校验也用它） */
    fun isReadyDir(dir: File): Boolean {
        if (!dir.isDirectory) return false
        val markers = listOf(
            File(dir, "bin/java"),
            File(dir, "lib/server/libjvm.so"),
            File(dir, "lib/client/libjvm.so"),
            File(dir, "jre/bin/java"),
            File(dir, "jre/lib/server/libjvm.so")
        )
        return markers.any { it.exists() }
    }

    /** 按 MC 版本推断需要哪个 Java */
    fun requiredMajorFor(mcVersion: String): Int {
        // 快照统一给 25，走最新运行时
        if (mcVersion.contains("w") && mcVersion.contains("a")) return 25
        val nums = mcVersion.split('.', '-', '_').mapNotNull { it.toIntOrNull() }
        if (nums.size < 2) return 17
        val minor = nums[1]
        val patch = nums.getOrElse(2) { 0 }
        return when {
            minor <= 16 -> 8
            minor <= 20 && !(minor == 20 && patch >= 5) -> 17
            minor <= 21 && patch <= 4 && minor == 21 -> 21
            minor == 20 && patch >= 5 -> 21
            else -> 21
        }
    }

    // ---------------- 安装 ----------------

    /**
     * 确保某个 Java 版本可用：内置 → 远端 → 报错。
     * @param onProgress 进度回调（下载/解压阶段）
     */
    suspend fun ensure(major: Int, onProgress: (State) -> Unit = {}): Result<File> =
        withContext(Dispatchers.IO) {
            if (isReady(major)) {
                val dir = dirOf(major)
                return@withContext Result.success(dir)
            }

            val abi = primaryAbi()
            if (!isSupported(major, abi)) {
                return@withContext fail(major, "该架构（$abi）没有 Java $major 运行时")
            }

            // 内置资产已由 core:setup 的首启流程统一解压到 bundles/runtime，
            // 上面 isReady() 已经命中；走到这里说明包里没带，或用户手动删了，
            // 只能走远端下载。
            val manifest = fetchManifest()
            val entry = manifest?.entries?.firstOrNull { it.major == major && it.abi == abi }
                ?: return@withContext fail(
                    major,
                    "APK 未内置 Java $major，且远端清单中没有 $abi 的条目。" +
                        "请在设置中配置运行时源，或换用包含 Java $major 的安装包。"
                )

            downloadAndExtract(entry, major, onProgress)
        }

    private suspend fun downloadAndExtract(
        entry: ManifestEntry,
        major: Int,
        onProgress: (State) -> Unit
    ): Result<File> = runCatching {
        val cacheDir = File(context.cacheDir, "jre").apply { mkdirs() }
        val zipFile = File(cacheDir, "jre-$major-${entry.abi}.zip")

        val url = UrlRewriter.rewrite(sourceFlow.value, entry.url, DownloadClass.LIBRARY)
        updateState(major, State.Downloading(0, "准备下载"), onProgress)

        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw java.io.IOException("空响应")
            val total = if (body.contentLength() > 0) body.contentLength() else entry.size
            body.byteStream().use { input ->
                zipFile.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    var lastPct = -1
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        val pct = if (total > 0) ((read * 100) / total).toInt() else 0
                        if (pct != lastPct) {
                            lastPct = pct
                            updateState(
                                major,
                                State.Downloading(pct.coerceIn(0, 100), "下载 Java $major"),
                                onProgress
                            )
                        }
                    }
                }
            }
        }

        // 校验：清单给了 sha1 就必须对得上，否则解压一个半截包只会得到莫名其妙的错误
        entry.sha1?.takeIf { it.isNotBlank() }?.let { expect ->
            updateState(major, State.Extracting(0), onProgress)
            val actual = sha1Of(zipFile)
            if (!actual.equals(expect, ignoreCase = true)) {
                zipFile.delete()
                throw java.io.IOException("SHA1 校验失败（期望 $expect，实际 $actual）")
            }
        }

        val target = managedDirOf(major)
        if (target.exists()) target.deleteRecursively()
        target.mkdirs()
        zipFile.inputStream().use { input ->
            unzip(input, target, zipFile.length()) { pct ->
                updateState(major, State.Extracting(pct), onProgress)
            }
        }
        zipFile.delete()
        flattenIfNeeded(target)
        if (!isReady(major)) throw IllegalStateException("解压完成但未找到 JVM")
        refresh()
        target
    }.onFailure { updateState(major, State.Failed("安装失败：${it.message}"), onProgress) }

    /** 删除某个运行时 */
    fun remove(major: Int): Boolean {
        // 解压来源与下载来源都要删干净，否则删了还会「自动复活」
        extractedRootProvider()?.let { File(it, "java-$major").deleteRecursively() }
        val ok = managedDirOf(major).deleteRecursively()
        refresh()
        return ok
    }

    // ---------------- 内部 ----------------

    /** 有些 JRE 包内层还有一层目录（jre/ 或 jdk-xx/），解压后压平 */
    private fun flattenIfNeeded(target: File) {
        if (isReadyDir(target)) return
        val children = target.listFiles()?.filter { it.isDirectory } ?: return
        if (children.size != 1) return
        val inner = children.first()
        // 内层有 bin 或 lib 才认为需要上提
        if (!File(inner, "bin").exists() && !File(inner, "lib").exists()) return
        inner.listFiles()?.forEach { f -> f.renameTo(File(target, f.name)) }
        inner.delete()
    }

    private inline fun unzip(
        input: java.io.InputStream,
        target: File,
        totalHint: Long,
        onProgress: (Int) -> Unit
    ) {
        var done = 0L
        var lastPct = -1
        ZipInputStream(input.buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val out = File(target, entry.name)
                // 防 zip slip
                if (!out.canonicalPath.startsWith(target.canonicalPath + File.separator)) {
                    throw SecurityException("非法压缩路径：${entry.name}")
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zis.copyTo(it) }
                    // 保留可执行位（bin/java 必须可执行）
                    if (entry.name.endsWith("/bin/java") || entry.name.startsWith("bin/")) {
                        out.setExecutable(true, false)
                    }
                    done += entry.compressedSize.coerceAtLeast(0)
                }
                if (totalHint > 0) {
                    val pct = ((done * 100) / totalHint).toInt().coerceIn(0, 100)
                    if (pct != lastPct) {
                        lastPct = pct
                        onProgress(pct)
                    }
                }
                zis.closeEntry()
            }
        }
        onProgress(100)
    }

    private fun sha1Of(file: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-1")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun updateState(major: Int, state: State, cb: (State) -> Unit) {
        _states.value = _states.value + (major to state)
        cb(state)
    }

    private fun fail(major: Int, message: String): Result<File> {
        updateState(major, State.Failed(message)) {}
        return Result.failure(IllegalStateException(message))
    }

    private fun sizeOf(dir: File): Long =
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    // ---------------- 清单 ----------------

    data class ManifestEntry(
        val major: Int,
        val abi: String,
        val url: String,
        val sha1: String? = null,
        val size: Long = -1
    )

    data class Manifest(val entries: List<ManifestEntry>)

    /** 拉取远端清单；未配置或失败都返回 null（回落到「仅内置」） */
    private suspend fun fetchManifest(): Manifest? = withContext(Dispatchers.IO) {
        val url = manifestUrlProvider()?.takeIf { it.isNotBlank() } ?: return@withContext null
        runCatching {
            val req = Request.Builder().url(url).build()
            val body = client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
                resp.body?.string() ?: ""
            }
            parseManifest(body)
        }.getOrNull()
    }

    /**
     * 清单格式（与 CI 产出的 jre-index.json 一致）：
     * ```json
     * { "entries": [ { "major": 21, "abi": "arm64-v8a", "url": "https://...", "sha1": "...", "size": 123 } ] }
     * ```
     */
    internal fun parseManifest(json: String): Manifest {
        val root = com.google.gson.JsonParser.parseString(json).asJsonObject
        val arr = root.getAsJsonArray("entries") ?: return Manifest(emptyList())
        val list = arr.mapNotNull { e ->
            if (!e.isJsonObject) return@mapNotNull null
            val o = e.asJsonObject
            val major = o.get("major")?.asInt ?: return@mapNotNull null
            val abi = o.get("abi")?.asString ?: return@mapNotNull null
            val url = o.get("url")?.asString ?: return@mapNotNull null
            ManifestEntry(
                major = major,
                abi = abi,
                url = url,
                sha1 = o.get("sha1")?.takeIf { !it.isJsonNull }?.asString,
                size = o.get("size")?.takeIf { !it.isJsonNull }?.asLong ?: -1
            )
        }
        return Manifest(list)
    }
}
