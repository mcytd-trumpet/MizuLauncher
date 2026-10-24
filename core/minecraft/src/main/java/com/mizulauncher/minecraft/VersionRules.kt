package com.mizulauncher.minecraft

import com.mizulauncher.minecraft.model.AssetIndex
import com.mizulauncher.minecraft.model.Library
import com.mizulauncher.minecraft.model.Rule
import com.mizulauncher.minecraft.model.VersionEntry
import com.mizulauncher.minecraft.model.VersionJson

/**
 * 平台判定上下文：Android 上我们伪装成 Linux（Pojav 的 JVM 运行在 Android/Linux 之上）。
 */
data class PlatformContext(
    val osName: String = "linux",
    val osArch: String = "aarch64",
    val osVersion: String = "0",
    val features: Map<String, Boolean> = mapOf(
        "is_demo_user" to false,
        "has_custom_resolution" to true,
        "has_quick_plays_support" to false,
        "is_quick_play_singleplayer" to false,
        "is_quick_play_multiplayer" to false,
        "is_quick_play_realms" to false
    )
)

/**
 * 版本规则引擎：按 Mojang 官方规则筛选库、拼接启动参数、合并继承版本。
 *
 * 这里是纯 JVM 逻辑（无 Android 依赖），因此可以直接写成单元测试
 *（见 `VersionRulesTest`），这也是「可编译可验证」的关键点之一。
 */
object VersionRules {

    /**
     * 判断一组 rules 在当前平台是否放行（Mojang 官方语义）：
     *  - 无 rules → 允许；
     *  - 取第一条匹配的规则，由它的 action 决定；
     *  - 全都不匹配 → 拒绝（rules 即白名单）。
     *
     * 实现集中在 [RuleMatcher]（Java），保证与启动参数拼装共用同一套判定。
     */
    fun rulesAllow(rules: List<Rule>?, ctx: PlatformContext): Boolean = RuleMatcher.allows(rules, ctx)

    /** 过滤出当前平台实际需要的库 */
    fun effectiveLibraries(json: VersionJson, ctx: PlatformContext = PlatformContext()): List<Library> {
        val out = ArrayList<Library>(json.libraries.size)
        for (lib in json.libraries) {
            if (RuleMatcher.allows(lib.rules, ctx)) {
                out.add(lib)
            }
        }
        return out
    }

    /**
     * 用 child 覆盖 parent，得到合并后的版本（Fabric/Forge/Quilt 都依赖 `inheritsFrom`）。
     *
     * 合并规则（与官方/PCL2 一致）：
     *  - mainClass 子优先
     *  - libraries 取并集，同 name 以子版本为准
     *  - arguments 子在前、父在后（game）；jvm 同理
     *  - assetIndex / assets / downloads 子优先（子没有才用父）
     */
    fun merge(parent: VersionJson, child: VersionJson): VersionJson {
        val libs = LinkedHashMap<String, Library>()
        child.libraries.forEach { libs[it.name] = it }
        parent.libraries.forEach { lib -> if (libs.keys.none { sameArtifact(it, lib.name) }) libs[lib.name] = lib }

        val gameArgs = (child.arguments?.game ?: emptyList()) + (parent.arguments?.game ?: emptyList())
        val jvmArgs = (child.arguments?.jvm ?: emptyList()) + (parent.arguments?.jvm ?: emptyList())

        return VersionJson(
            id = child.id,
            type = child.type ?: parent.type,
            inheritsFrom = null,
            mainClass = child.mainClass ?: parent.mainClass,
            minecraftArguments = child.minecraftArguments ?: parent.minecraftArguments,
            arguments = VersionJson.Arguments(gameArgs, jvmArgs),
            assetIndex = child.assetIndex ?: parent.assetIndex,
            assets = child.assets ?: parent.assets,
            downloads = parent.downloads + child.downloads,
            javaVersion = child.javaVersion ?: parent.javaVersion,
            libraries = libs.values.toList(),
            logging = child.logging ?: parent.logging,
            releaseTime = child.releaseTime ?: parent.releaseTime,
            time = child.time ?: parent.time,
            minimumLauncherVersion = maxOf(child.minimumLauncherVersion, parent.minimumLauncherVersion)
        )
    }

    /** group:artifact 相同即视为同一库（版本可不同） */
    private fun sameArtifact(nameA: String, nameB: String): Boolean {
        val a = nameA.split(':')
        val b = nameB.split(':')
        return a.size >= 2 && b.size >= 2 && a[0] == b[0] && a[1] == b[1]
    }

    /**
     * 判断该版本需要 Java 几。Pojav 内置 JRE 8 / 17 / 21 / 25，按需选择。
     */
    fun requiredJavaMajor(json: VersionJson): Int {
        json.javaVersion?.majorVersion?.let { return it }
        // 老版本没有 javaVersion 字段
        val id = json.id
        return when {
            id.startsWith("1.") && id.split('.').getOrNull(1)?.toIntOrNull()?.let { it <= 16 } == true -> 8
            else -> 17
        }
    }

    /** 用于 UI 的版本号数字比较：1.20.1 > 1.9 */
    fun compareVersionId(a: String, b: String): Int {
        fun parts(s: String) = s.split('.', '-', '_').mapNotNull { it.toIntOrNull() }
        val pa = parts(a)
        val pb = parts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        return 0
    }
}

/**
 * 把版本 JSON 展开成「待下载清单」。
 *
 * 这一层刻意与网络解耦：输入是 JSON 对象 + 目录布局，输出是 List<DownloadTask>，
 * 便于单测与离线校验。也是替换 Pojav 原 `MinecraftDownloader` 的接缝。
 */
object DownloadPlanBuilder {

    data class ClientLayout(
        val gameDir: String,
        val assetsDir: String,
        val librariesDir: String,
        val versionsDir: String
    ) {
        val nativesDir: String get() = "$versionsDir/natives"
        val assetIndexDir: String get() = "$assetsDir/indexes"
        val assetObjectsDir: String get() = "$assetsDir/objects"
        val legacyAssetsDir: String get() = "$assetsDir/virtual/legacy"
    }

    /**
     * 构建下载计划。
     * @param versionId 实例对应的版本 id（用于 jar/json 落盘路径）
     * @param assetIndex 资源索引对象（可能为 null，表示该版本无资源）
     * @param clientJarUrl 客户端 jar 地址（来自 downloads.client.url），可空（Forge 继承版本为 null）
     */
    fun build(
        versionId: String,
        json: VersionJson,
        layout: ClientLayout,
        assetIndex: AssetIndex?,
        clientJarUrl: String? = null
    ): List<PlanItem> {
        val items = mutableListOf<PlanItem>()
        val ctx = PlatformContext()

        // 1. 客户端 jar
        if (!clientJarUrl.isNullOrBlank()) {
            val info = json.downloads["client"]
            items += PlanItem(
                url = clientJarUrl,
                relativePath = "${versionId}/${versionId}.jar",
                sha1 = info?.sha1,
                size = info?.size ?: -1,
                downloadClass = com.mizulauncher.download.DownloadClass.CLIENT,
                fatal = true
            )
        }

        // 2. 依赖库（跳过 LWJGL / 原生库：Pojav 内核自带 Android 版实现）
        for (lib in VersionRules.effectiveLibraries(json, ctx)) {
            if (lib.name.startsWith("org.lwjgl")) continue
            if (lib.isNative) continue
            val artifact = lib.downloads?.artifact
            val relative = "libraries/" + lib.mavenPath()
            val url = artifact?.url ?: run {
                val base = (lib.url ?: "https://libraries.minecraft.net/").replace("http://", "https://")
                if (!base.endsWith("/")) "$base/" else base
            }.let { base ->
                if (artifact != null) base else base + lib.mavenPath()
            }
            items += PlanItem(
                url = url,
                relativePath = relative,
                sha1 = artifact?.sha1,
                size = artifact?.size ?: -1,
                downloadClass = com.mizulauncher.download.DownloadClass.LIBRARY,
                // 没有 sha1 的老库允许失败（与 Pojav 行为一致）
                fatal = artifact != null
            )
        }

        // 3. 资源文件
        // 全部对象统一落到 assets/objects/<xx>/<hash>（磁盘只存一份，按 hash 去重）；
        // virtual / map_to_resources 只是「运行期展开」的索引，由启动前 buildVirtualAssets() 生成，
        // 不参与下载，否则会把同一份资源重复下载到 indexes 目录之外。
        assetIndex?.objects?.values?.forEach { obj ->
            items += PlanItem(
                url = "https://resources.download.minecraft.net/${obj.hashedPath}",
                relativePath = "assets/objects/${obj.hashedPath}",
                sha1 = obj.hash,
                size = obj.size,
                downloadClass = com.mizulauncher.download.DownloadClass.ASSET,
                fatal = false
            )
        }

        // 4. log4j 配置（仅 1.12~1.16 有）
        json.logging?.client?.file?.let { cfg ->
            items += PlanItem(
                url = cfg.url,
                relativePath = "${versionId}/${cfg.id ?: "client.xml"}",
                sha1 = cfg.sha1,
                size = cfg.size,
                downloadClass = com.mizulauncher.download.DownloadClass.METADATA,
                fatal = false
            )
        }

        return items
    }

    /** 计划项：相对路径由调用方与根目录拼接（便于实例隔离） */
    data class PlanItem(
        val url: String,
        val relativePath: String,
        val sha1: String?,
        val size: Long,
        val downloadClass: com.mizulauncher.download.DownloadClass,
        val fatal: Boolean
    )
}
