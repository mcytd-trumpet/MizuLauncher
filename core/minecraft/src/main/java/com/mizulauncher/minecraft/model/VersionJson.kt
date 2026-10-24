package com.mizulauncher.minecraft.model

import com.google.gson.annotations.SerializedName

/**
 * Mojang 版本清单（version_manifest_v2.json）
 */
data class VersionManifest(
    @SerializedName("latest") val latest: Latest = Latest(),
    @SerializedName("versions") val versions: List<VersionEntry> = emptyList()
) {
    data class Latest(
        @SerializedName("release") val release: String = "",
        @SerializedName("snapshot") val snapshot: String = ""
    )
}

data class VersionEntry(
    @SerializedName("id") val id: String,
    @SerializedName("type") val type: String,
    @SerializedName("url") val url: String,
    @SerializedName("sha1") val sha1: String? = null,
    @SerializedName("releaseTime") val releaseTime: String? = null,
    @SerializedName("time") val time: String? = null,
    @SerializedName("complianceLevel") val complianceLevel: Int = 0
) {
    val isRelease: Boolean get() = type == "release"
    val isSnapshot: Boolean get() = type == "snapshot"
    val isOldAlphaBeta: Boolean get() = type == "old_beta" || type == "old_alpha"

    /** 大版本号，用于分组展示：1.20.1 -> "1.20" */
    val major: String
        get() {
            if (!id.startsWith("1.")) return id
            val parts = id.split('.')
            return if (parts.size >= 2) parts[0] + "." + parts[1] else id
        }
}

/**
 * 版本 JSON（<version>.json），兼容 Forge/Fabric 的 inheritsFrom 继承结构。
 */
data class VersionJson(
    @SerializedName("id") val id: String = "",
    @SerializedName("type") val type: String? = null,
    @SerializedName("inheritsFrom") val inheritsFrom: String? = null,
    @SerializedName("mainClass") val mainClass: String? = null,
    @SerializedName("minecraftArguments") val minecraftArguments: String? = null,
    @SerializedName("arguments") val arguments: Arguments? = null,
    @SerializedName("assetIndex") val assetIndex: AssetIndexRef? = null,
    @SerializedName("assets") val assets: String? = null,
    @SerializedName("downloads") val downloads: Map<String, DownloadInfo> = emptyMap(),
    @SerializedName("javaVersion") val javaVersion: JavaVersion? = null,
    @SerializedName("libraries") val libraries: List<Library> = emptyList(),
    @SerializedName("logging") val logging: Logging? = null,
    @SerializedName("releaseTime") val releaseTime: String? = null,
    @SerializedName("time") val time: String? = null,
    @SerializedName("minimumLauncherVersion") val minimumLauncherVersion: Int = 0
) {
    data class JavaVersion(
        @SerializedName("component") val component: String? = null,
        @SerializedName("majorVersion") val majorVersion: Int = 8
    )

    data class AssetIndexRef(
        @SerializedName("id") val id: String = "",
        @SerializedName("sha1") val sha1: String? = null,
        @SerializedName("size") val size: Long = -1,
        @SerializedName("totalSize") val totalSize: Long = 0,
        @SerializedName("url") val url: String = ""
    )

    data class Arguments(
        @SerializedName("game") val game: List<Any> = emptyList(),
        @SerializedName("jvm") val jvm: List<Any> = emptyList()
    )

    data class Logging(
        @SerializedName("client") val client: LoggingClient? = null
    ) {
        data class LoggingClient(
            @SerializedName("argument") val argument: String? = null,
            @SerializedName("file") val file: DownloadInfo? = null,
            @SerializedName("type") val type: String? = null
        )
    }
}

data class DownloadInfo(
    @SerializedName("id") val id: String? = null,
    @SerializedName("path") val path: String? = null,
    @SerializedName("sha1") val sha1: String? = null,
    @SerializedName("size") val size: Long = -1,
    @SerializedName("url") val url: String = ""
)

/**
 * 依赖库。`name` 为 Maven 坐标 group:artifact:version[:classifier][@ext]
 */
data class Library(
    @SerializedName("name") val name: String,
    @SerializedName("downloads") val downloads: LibraryDownloads? = null,
    @SerializedName("natives") val natives: Map<String, String>? = null,
    @SerializedName("extract") val extract: Extract? = null,
    @SerializedName("rules") val rules: List<Rule>? = null,
    @SerializedName("url") val url: String? = null
) {
    data class LibraryDownloads(
        @SerializedName("artifact") val artifact: DownloadInfo? = null,
        @SerializedName("classifiers") val classifiers: Map<String, DownloadInfo>? = null
    )

    data class Extract(
        @SerializedName("exclude") val exclude: List<String>? = null
    )

    /** Maven 坐标解析结果 */
    val group: String get() = name.split(':').getOrElse(0) { "" }
    val artifact: String get() = name.split(':').getOrElse(1) { "" }
    val version: String get() = name.split(':').getOrElse(2) { "" }
    val classifier: String? get() = name.split(':').getOrNull(3)?.substringBefore('@')
    val extension: String get() = name.substringAfter('@', "jar")

    /** 转换为 maven 相对路径：group/artifact/version/artifact-version[-classifier].ext */
    fun mavenPath(): String {
        val g = group.replace('.', '/')
        val suffix = if (classifier != null) "-$classifier" else ""
        return "$g/$artifact/$version/$artifact-$version$suffix.$extension"
    }

    /** 是否为 natives 库（Android 上通常跳过原生 LWJGL，由 Pojav 自带实现替换） */
    val isNative: Boolean get() = natives != null || classifier == "natives-linux" || classifier == "natives-android"
}

/**
 * rules 规则，判断库在当前平台是否适用。
 */
data class Rule(
    @SerializedName("action") val action: String? = "allow",
    @SerializedName("os") val os: OsRule? = null,
    @SerializedName("features") val features: Map<String, Boolean>? = null
) {
    data class OsRule(
        @SerializedName("name") val name: String? = null,
        @SerializedName("arch") val arch: String? = null,
        @SerializedName("version") val version: String? = null
    )
}

/**
 * 资源索引（assets/indexes/<id>.json）
 */
data class AssetIndex(
    @SerializedName("objects") val objects: Map<String, AssetObject> = emptyMap(),
    @SerializedName("virtual") val virtual: Boolean = false,
    @SerializedName("map_to_resources") val mapToResources: Boolean = false
) {
    data class AssetObject(
        @SerializedName("hash") val hash: String,
        @SerializedName("size") val size: Long = 0
    ) {
        /** assets/objects/<前两位>/<hash> */
        val hashedPath: String get() = "${hash.substring(0, 2)}/$hash"
    }
}
