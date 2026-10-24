package com.mizulauncher.setup

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * APK 内置组件的**目录扫描**。
 *
 * ## 为什么要「扫描」而不是写死清单
 * ZalithLauncher2 首启时列出的是「这个 APK 里实际带了哪些可解压组件」，
 * 而不是一张固定表：
 *  - ARM64 的包里可能有 Java 8/17/21/25，ARM32 的包里没有 25；
 *  - CI 上没配 `JRE_ARCHIVES_URL` 时，打包出来的 APK 一个 JRE 都没有；
 *  - 以后往 `assets/` 里加 LWJGL、渲染器、内核 so，只要路径对就自动被识别。
 *
 * 写死清单会导致「UI 让用户解压 Java 25，可包里根本没有」，用户点了只看到报错。
 * 所以这里**只认磁盘上真实存在的东西**。
 *
 * ## 版本指纹
 * 只有「版本变了」才需要重新解压。指纹取 zip 的 CRC 与文件名长度，
 * 读一次中央目录即可，不用把几十兆的数据全解一遍。
 * 升级时若同类组件换了版本，指纹必然变化 → 再次提示补全 —— 这正是 ZL2 的行为。
 */
object BundleCatalog {

    const val KIND_JAVA = "java"
    const val KIND_LWJGL = "lwjgl"
    const val KIND_RENDERER = "renderer"
    const val KIND_GAME = "game"

    /** `assets/runtime/<abi>/` 下的每个 jre-*.zip 都算一个 Java 运行时 */
    private val JAVA_ASSET = Regex("""^runtime/([^/]+)/jre-(\d+)\.zip$""")

    /** 其余组件的约定路径：`assets/bundle/<kind>/<name>.zip` */
    private val BUNDLE_ASSET = Regex("""^bundle/([^/]+)/([^/]+)\.zip$""")

    private val KIND_LABELS = mapOf(
        KIND_GAME to "游戏运行组件",
        KIND_JAVA to "Java 运行时",
        KIND_LWJGL to "LWJGL 图形库",
        KIND_RENDERER to "渲染器组件"
    )

    /** 一个「可以解压到本地」的组件 */
    data class Item(
        val kind: String,
        val name: String,
        val label: String,
        val description: String,
        val assetPath: String,
        /** 解压目标相对应用私有目录的路径 */
        val targetDir: String,
        /** 压缩包字节数；-1 表示拿不到（压缩进 APK 的资产无法取长度） */
        val sizeBytes: Long,
        val version: String,
        /** 是否随所有架构通用（Java 是按架构分的，其余组件通用） */
        val archSpecific: Boolean = false
    ) {
        /** 已安装记录的键 */
        val key: String get() = "$kind:$name"
    }

    /**
     * 从资产路径列表里挑出所有可安装组件。
     *
     * 纯函数：入参就是 `assets.list()` 递归出来的路径，单测可以直接喂假数据。
     *
     * @param sizeOf 资产字节数查询；压缩存储的资产拿不到，返回 -1 即可
     */
    fun discover(
        assetPaths: List<String>,
        abi: String,
        sizeOf: (String) -> Long = { -1L }
    ): List<Item> {
        val items = ArrayList<Item>()

        assetPaths.forEach { path ->
            JAVA_ASSET.find(path)?.let { m ->
                val assetAbi = m.groupValues[1]
                val major = m.groupValues[2].toIntOrNull() ?: return@let
                // 只装本机架构的：把 arm64 的 JRE 解到 x86 手机上必然崩
                if (!abiMatches(abi, assetAbi)) return@let
                items += Item(
                    kind = KIND_JAVA,
                    name = "java-$major",
                    label = "Java $major",
                    description = "供 ${javaUsage(major)} 使用",
                    assetPath = path,
                    targetDir = "runtime/java-$major",
                    sizeBytes = sizeOf(path),
                    version = "jre-$major",
                    archSpecific = true
                )
            } ?: BUNDLE_ASSET.find(path)?.let { m ->
                val kind = m.groupValues[1]
                val name = m.groupValues[2]
                items += Item(
                    kind = kind,
                    name = name,
                    label = labelOf(kind, name),
                    description = descriptionOf(kind, name),
                    assetPath = path,
                    targetDir = "bundle/$kind/$name",
                    sizeBytes = sizeOf(path),
                    version = name
                )
            }
        }

        // 去重 + 稳定排序：Java 按大版本升序，其余按种类、名称
        return items
            .distinctBy { it.key }
            .sortedWith(
                compareBy<Item> {
                    when (it.kind) {
                        KIND_GAME -> 0
                        KIND_RENDERER -> 1
                        KIND_LWJGL -> 2
                        KIND_JAVA -> 3
                        else -> 4
                    }
                }.thenBy { if (it.kind == KIND_JAVA) it.name.removePrefix("java-").toIntOrNull() ?: 0 else 0 }
                    .thenBy { it.name }
            )
    }

    /** 目录（扁平化后的资产列表）→ 组件 */
    fun discoverFromDir(root: File, abi: String, sizes: Map<String, Long> = emptyMap()): List<Item> {
        if (!root.isDirectory) return emptyList()
        val paths = root.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .toList()
        return discover(paths, abi) { sizes[it] ?: -1L }
    }

    /**
     * 设备 ABI 与资产目录 ABI 是否匹配。
     *
     * universal 包会把四个架构的 JRE 都塞进来，此时按设备首选 ABI 取一份就够，
     * 不能把所有架构的都解压出来（多占几百兆）。
     */
    fun abiMatches(deviceAbi: String, assetAbi: String): Boolean {
        if (deviceAbi == assetAbi) return true
        return normalizeAbi(deviceAbi) == normalizeAbi(assetAbi)
    }

    fun normalizeAbi(abi: String): String = when {
        abi.startsWith("arm64") -> "arm64"
        abi.startsWith("armeabi") || abi == "arm" -> "arm32"
        abi.startsWith("x86_64") || abi == "x64" -> "x64"
        abi.startsWith("x86") -> "x86"
        else -> abi
    }

    fun javaUsage(major: Int): String = when (major) {
        8 -> "1.16.5 及更早版本"
        17 -> "1.17 ~ 1.20.4"
        21 -> "1.20.5 ~ 1.21.x"
        25 -> "1.21.5+ 与最新快照"
        else -> "部分版本"
    }

    private fun labelOf(kind: String, name: String): String = when (kind) {
        KIND_JAVA -> "Java ${name.removePrefix("java-")}"
        KIND_LWJGL -> "LWJGL ${name.removePrefix("lwjgl-")}"
        KIND_RENDERER -> "${name.replaceFirstChar { it.uppercase() }} 渲染器"
        KIND_GAME -> if (name == "assets" || name == "authlib" || name == "mclauncher") {
            "启动核心 ${name.replaceFirstChar { it.uppercase() }}"
        } else {
            name.replaceFirstChar { it.uppercase() }
        }
        else -> name
    }

    private fun descriptionOf(kind: String, name: String): String = when (kind) {
        KIND_LWJGL -> "游戏窗口与 OpenGL 绑定，缺失会在启动时崩溃"
        KIND_RENDERER -> "渲染后端原生库"
        KIND_GAME -> "启动器附带的游戏运行组件"
        else -> KIND_LABELS[kind] ?: "内置组件"
    }

    // ---------------- 指纹 ----------------

    /**
     * 计算资产的版本指纹：`sha1(路径 + 大小 + 首条与末条 CRC)`。
     *
     * 换成新版本的 zip，CRC 一定变；同一版本重复安装则指纹一致，
     * 不会每次启动都提示「需要补全」。
     */
    fun fingerprint(item: Item, zip: File?): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update(item.assetPath.toByteArray())
        md.update(item.sizeBytes.toString().toByteArray())
        if (zip != null && zip.isFile) {
            runCatching {
                ZipFile(zip).use { zf ->
                    val entries = zf.entries()
                    var last: java.util.zip.ZipEntry? = null
                    var count = 0
                    while (entries.hasMoreElements()) {
                        last = entries.nextElement()
                        count++
                    }
                    md.update(count.toString().toByteArray())
                    md.update((last?.crc ?: 0L).toString().toByteArray())
                }
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }.take(16)
    }

    // ---------------- 已安装记录 ----------------

    /**
     * 已安装清单的编解码。
     *
     * 格式刻意朴素（`key=version@fingerprint` 每行一条），
     * 因为这个文件只在首启/升级时读一次，没必要上数据库。
     */
    object Ledger {
        fun encode(records: Map<String, String>): String =
            records.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}=${it.value}" }

        fun decode(raw: String?): Map<String, String> {
            if (raw.isNullOrBlank()) return emptyMap()
            return raw.lineSequence()
                .mapNotNull { line ->
                    val i = line.indexOf('=')
                    if (i <= 0) null else line.substring(0, i) to line.substring(i + 1)
                }
                .toMap()
        }

        /** 组件当前应记录的版本串 */
        fun expected(item: Item, fingerprint: String): String = "${item.version}@$fingerprint"

        /** 该组件是否已按当前版本解压过 */
        fun satisfied(records: Map<String, String>, item: Item, fingerprint: String): Boolean =
            records[item.key] == expected(item, fingerprint)

        /**
         * 算出「还需要装哪些」。
         *
         * @param present 目标目录是否真实存在且具备可用文件（防止记录还在、目录被清理）
         */
        fun pending(
            items: List<Item>,
            records: Map<String, String>,
            fingerprintOf: (Item) -> String,
            present: (Item) -> Boolean
        ): List<Item> = items.filter { item ->
            val fp = fingerprintOf(item)
            !satisfied(records, item, fp) || !present(item)
        }
    }
}
