package com.mizulauncher.download

/**
 * PCL2 风格下载源定义。
 *
 * 每个源负责把 *官方 URL* 重写成 *镜像 URL*，官方源则原样返回。
 * 参考 PCL2 的映射规则（PCL2/Net/MirrorLoader.cs）：
 *  - 元数据（version_manifest / version json）→ {mirror}/mc/game/... 或直接 /{path}
 *  - 库文件（libraries.minecraft.net / repo1.maven.org）→ {mirror}/maven/...
 *  - 资源文件（resources.download.minecraft.net）→ {mirror}/assets/...
 *  - Forge / NeoForge / Fabric / Quilt / OptiFine 均各有镜像前缀
 *
 * 设计原则：**镜像失败自动回退官方**，并且镜像返回的内容会做 SHA1 校验，
 * 校验失败则丢弃并从官方重新下载（对应 PCL2 的「镜像校验」）。
 */
enum class DownloadSource(
    val displayName: String,
    /** 主站根地址，null 表示官方 */
    val root: String?,
    /** 是否参与「自动择优」 */
    val autoSelectable: Boolean = true
) {
    /** 官方 Mojang（默认，海外/直连较快时选它） */
    Official("官方源 (Mojang)", null),

    /** BMCLAPI，国内首选 */
    Bmclapi("BMCLAPI（bangbang93）", "https://bmclapi2.bangbang93.com"),

    /** 微软 MCIM，镜像质量较高 */
    Mcim("MCIM 镜像", "https://mod.mcimirror.top");

    companion object {
        fun fromName(name: String?): DownloadSource =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: Bmclapi
    }
}

/** 下载类别，决定 URL 改写规则与并发优先级 */
enum class DownloadClass {
    /** 版本清单 / 版本 JSON / 资源索引等小文件 */
    METADATA,

    /** 库文件（.jar） */
    LIBRARY,

    /** 游戏资源（assets objects） */
    ASSET,

    /** 客户端 jar / 服务端 jar */
    CLIENT,

    /** 加载器安装包（Forge/NeoForge installer 等） */
    MODLOADER
}

/**
 * URL 重写器：把 Mojang/Forge 官方地址映射到镜像地址。
 *
 * 所有规则都在这里集中，方便单测覆盖（见 `UrlRewriterTest`）。
 */
object UrlRewriter {

    private const val MOJANG_PISTON_META = "https://piston-meta.mojang.com"
    private const val MOJANG_PISTON_DATA = "https://piston-data.mojang.com"
    private const val MOJANG_LAUNCHERMETA = "https://launchermeta.mojang.com"
    private const val MOJANG_LAUNCHER = "https://launcher.mojang.com"
    private const val MOJANG_RESOURCES = "https://resources.download.minecraft.net"
    private const val MOJANG_LIBRARIES = "https://libraries.minecraft.net"
    private const val MAVEN_CENTRAL = "https://repo1.maven.org/maven2"
    private const val FORGE_MAVEN = "https://maven.minecraftforge.net"
    private const val NEOFORGE_MAVEN = "https://maven.neoforged.net/releases"
    private const val FABRIC_META = "https://meta.fabricmc.net"
    private const val QUILT_META = "https://meta.quiltmc.org"
    private const val OPTIFINE = "https://optifine.net"

    /**
     * 将官方 URL 改写为镜像 URL。若当前为官方源，直接返回原 URL。
     * 若 URL 不属于已知域，也返回原 URL（镜像站无法代理）。
     */
    fun rewrite(source: DownloadSource, originalUrl: String, downloadClass: DownloadClass): String {
        // 官方源：仅把不安全的 http 升级为 https，其余原样返回
        val root = source.root ?: return upgradeHttps(originalUrl)
        // 已经是镜像地址（例如加载器 JSON 里写死的），保持不动
        if (originalUrl.startsWith(root)) return originalUrl

        // 先把 http 归一化为 https，后续规则只处理 https 前缀
        val normalized = upgradeHttps(originalUrl)
        val stripped = normalized.substringAfter("://")

        val path = stripped.substringAfter('/', "")
        // 后续所有匹配均基于归一化后的地址
        val originalUrl = normalized

        return when {
            // ---- 资源文件：resources.download.minecraft.net/<xx>/<hash> ----
            originalUrl.startsWith(MOJANG_RESOURCES) ->
                "$root/assets/" + originalUrl.removePrefix("$MOJANG_RESOURCES/")

            // ---- 库文件：libraries.minecraft.net / repo1.maven.org ----
            originalUrl.startsWith(MOJANG_LIBRARIES) ->
                "$root/maven/" + originalUrl.removePrefix("$MOJANG_LIBRARIES/")
            originalUrl.startsWith(MAVEN_CENTRAL) ->
                "$root/maven/" + originalUrl.removePrefix("$MAVEN_CENTRAL/")

            // ---- 元数据 ----
            originalUrl.startsWith(MOJANG_LAUNCHERMETA) -> when {
                path.startsWith("v1/packages/") -> "$root/mc/game/" + path.removePrefix("v1/packages/")
                path == "mc/game/version_manifest.json" || path == "mc/game/version_manifest_v2.json" ->
                    "$root/mc/game/version_manifest_v2.json"
                else -> "$root/" + path
            }
            originalUrl.startsWith(MOJANG_PISTON_META) ->
                // piston-meta/v1/packages/<sha1>/<version>.json  ->  /mc/game/<version>.json
                if (path.startsWith("v1/packages/")) {
                    val file = path.substringAfterLast('/')
                    "$root/mc/game/$file"
                } else "$root/" + path
            originalUrl.startsWith(MOJANG_PISTON_DATA) ->
                // piston-data/v1/objects/<sha1>/<name> -> /mc/game/... 无法映射，交给 maven 代理
                "$root/" + path
            originalUrl.startsWith(MOJANG_LAUNCHER) ->
                // launcher.mojang.com/v1/objects/<sha1>/<name>
                if (path.startsWith("v1/objects/")) {
                    val file = path.substringAfterLast('/')
                    // client.jar 走官方 CDN 镜像前缀
                    "$root/mc/game/$file"
                } else "$root/" + path

            // ---- 加载器 ----
            originalUrl.startsWith(FORGE_MAVEN) ->
                "$root/maven/" + originalUrl.removePrefix("$FORGE_MAVEN/")
            originalUrl.startsWith(NEOFORGE_MAVEN) ->
                "$root/maven/" + originalUrl.removePrefix("$NEOFORGE_MAVEN/")
            originalUrl.startsWith(FABRIC_META) ->
                "$root/fabric-meta/" + originalUrl.removePrefix("$FABRIC_META/")
            originalUrl.startsWith(QUILT_META) ->
                "$root/quilt-meta/" + originalUrl.removePrefix("$QUILT_META/")
            originalUrl.startsWith(OPTIFINE) ->
                "$root/optifine/" + originalUrl.removePrefix("$OPTIFINE/")

            else -> upgradeHttps(originalUrl)
        }
    }

    /** 把 http:// 升级为 https://，其它协议保持不变 */
    private fun upgradeHttps(url: String): String =
        if (url.startsWith("http://")) "https://" + url.removePrefix("http://") else url
}
