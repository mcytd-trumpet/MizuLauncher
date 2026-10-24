package com.mizulauncher.instance

import java.io.File


/**
 * 游戏启动指令构造器（纯 JVM，可单测）。
 *
 * 输出 PojavLauncher 内核所需的字段——注意**不直接执行游戏**，
 * 真正的启动由 Pojav 的 `JREUtils.launchJavaVM()` 完成（见 launcher-bridge 模块）。
 *
 * 这样分层的好处：参数拼接逻辑可以脱离 Android/JNI 独立测试，
 * 也是替换/升级 Pojav 内核时唯一需要对齐的契约。
 */
data class LaunchRequest(
    val instanceId: String,
    val versionId: String,
    val gameDir: String,
    val assetIndexId: String,
    val assetsDir: String,
    val librariesDir: String,
    val nativesDir: String,
    val mainClass: String,
    /** 未替换占位符的原始参数列表，交给 Pojav 再做平台相关替换 */
    val jvmArgs: List<String>,
    val gameArgs: List<String>,
    val javaMajor: Int,
    val classpath: List<String>,
    val versionJsonPath: String,
    val width: Int = 1280,
    val height: Int = 720
)

data class Account(
    val username: String,
    val uuid: String = offlineUuid(username),
    val accessToken: String = "0",
    val userType: String = "legacy",
    val type: String = "offline"
) {
    companion object {
        /** 离线模式 UUID：与官方 OfflinePlayer 算法一致（MD5 v3） */
        fun offlineUuid(name: String): String {
            val md = java.security.MessageDigest.getInstance("MD5")
            val bytes = md.digest("OfflinePlayer:$name".toByteArray(Charsets.UTF_8))
            bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x30).toByte()
            bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
            val hex = bytes.joinToString("") { "%02x".format(it) }
            return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
                "${hex.substring(16, 20)}-${hex.substring(20)}"
        }

        fun random(): Account = Account("Player" + (1000..9999).random())
    }
}

/**
 * 参数模板替换器。Mojang 版本 JSON 里的 `${...}` 占位符在此统一替换。
 */
object ArgumentBuilder {

    private val defaultReplacements = mapOf(
        "auth_player_name" to "Player",
        "version_name" to "",
        "game_directory" to "",
        "assets_root" to "",
        "assets_index_name" to "",
        "auth_uuid" to "",
        "auth_access_token" to "0",
        "clientid" to "",
        "auth_xuid" to "",
        "user_type" to "legacy",
        "version_type" to "release",
        "natives_directory" to "",
        "launcher_name" to "MizuLauncher",
        "launcher_version" to "1.0.0",
        "classpath" to "",
        "classpath_separator" to ":",
        "library_directory" to ""
    )

    fun build(
        versionId: String,
        merged: com.mizulauncher.minecraft.model.VersionJson,
        layout: com.mizulauncher.minecraft.DownloadPlanBuilder.ClientLayout,
        account: Account,
        instanceId: String,
        resolution: Pair<Int, Int> = 1280 to 720,
        extraJvmArgs: List<String> = emptyList()
    ): LaunchRequest {
        val replacements = defaultReplacements + mapOf(
            "auth_player_name" to account.username,
            "version_name" to versionId,
            "game_directory" to layout.gameDir,
            "assets_root" to layout.assetsDir,
            "assets_index_name" to (merged.assets ?: "legacy"),
            "auth_uuid" to account.uuid.replace("-", ""),
            "auth_access_token" to account.accessToken,
            "user_type" to account.userType,
            "version_type" to (merged.type ?: "release"),
            "natives_directory" to layout.nativesDir,
            "library_directory" to layout.librariesDir,
            "resolution_width" to resolution.first.toString(),
            "resolution_height" to resolution.second.toString()
        )

        val libs = com.mizulauncher.minecraft.VersionRules.effectiveLibraries(merged)
            .filter { !it.name.startsWith("org.lwjgl") && !it.isNative }
            .map { File(layout.librariesDir, it.mavenPath()).absolutePath }

        val clientJar = File(layout.versionsDir, "$versionId/$versionId.jar").absolutePath
        val classpath = (libs + clientJar).joinToString(File.pathSeparator)

        val jvm = flattenArgs(merged.arguments?.jvm, replacements, isJvm = true)
            .filterNot { it.startsWith("-Xmx") || it.startsWith("-Xms") }
        val game = flattenArgs(merged.arguments?.game, replacements, isJvm = false)
            .ifEmpty {
                // 1.12 及更早使用 minecraftArguments（空格分隔）
                splitLegacyArgs(merged.minecraftArguments ?: "", replacements)
            }

        return LaunchRequest(
            instanceId = instanceId,
            versionId = versionId,
            gameDir = layout.gameDir,
            assetIndexId = merged.assets ?: "legacy",
            assetsDir = layout.assetsDir,
            librariesDir = layout.librariesDir,
            nativesDir = layout.nativesDir,
            mainClass = merged.mainClass ?: "net.minecraft.client.main.Main",
            jvmArgs = jvm + extraJvmArgs,
            gameArgs = game,
            javaMajor = com.mizulauncher.minecraft.VersionRules.requiredJavaMajor(merged),
            classpath = libs + clientJar,
            versionJsonPath = File(layout.versionsDir, "$versionId/$versionId.json").absolutePath,
            width = resolution.first,
            height = resolution.second
        )
    }

    /**
     * 处理 Object[] 形式的参数数组（字符串 或 {rules, value} 对象）。
     */
    internal fun flattenArgs(
        raw: List<Any>?,
        replacements: Map<String, String>,
        isJvm: Boolean
    ): List<String> {
        if (raw == null) return emptyList()
        val out = mutableListOf<String>()
        val ctx = com.mizulauncher.minecraft.PlatformContext()
        for (item in raw) {
            when (item) {
                is String -> out += replace(item, replacements)
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    val rules = (item as Map<String, Any?>)["rules"] as? List<Map<String, Any?>>
                    if (rules != null && !rulesMatch(rules, ctx)) continue
                    when (val value = item["value"]) {
                        is String -> out += replace(value, replacements)
                        is List<*> -> value.forEach { if (it is String) out += replace(it, replacements) }
                    }
                }
                is com.google.gson.JsonElement -> {
                    // Gson 直接解析 List<Any> 时会得到 LinkedTreeMap，这里兜底处理 JsonObject
                    if (item.isJsonPrimitive) {
                        out += replace(item.asString, replacements)
                    } else if (item.isJsonObject) {
                        val obj = item.asJsonObject
                        val rules = obj.getAsJsonArray("rules")
                        if (rules != null) {
                            val ruleList = rules.map { r ->
                                @Suppress("UNCHECKED_CAST")
                                val m = com.google.gson.Gson().fromJson(r, Map::class.java) as Map<String, Any?>
                                m
                            }
                            if (!rulesMatch(ruleList, ctx)) continue
                        }
                        val v = obj.get("value") ?: continue
                        if (v.isJsonArray) v.asJsonArray.forEach { out += replace(it.asString, replacements) }
                        else out += replace(v.asString, replacements)
                    }
                }
            }
        }
        return out
    }

    private fun rulesMatch(rules: List<Map<String, Any?>>, ctx: com.mizulauncher.minecraft.PlatformContext): Boolean {
        val gson = com.google.gson.Gson()
        val ruleObjs = rules.map { r ->
            gson.fromJson(gson.toJson(r), com.mizulauncher.minecraft.model.Rule::class.java)
        }
        return com.mizulauncher.minecraft.VersionRules.rulesAllow(ruleObjs, ctx)
    }

    private fun splitLegacyArgs(raw: String, replacements: Map<String, String>): List<String> {
        // 老版本参数里可能有带引号的路径，做个简单切分
        val regex = Regex("\"([^\"]*)\"|(\\S+)")
        return regex.findAll(raw).map { m ->
            replace(m.groupValues[1].ifEmpty { m.groupValues[2] }, replacements)
        }.filter { it.isNotBlank() }.toList()
    }

    internal fun replace(s: String, map: Map<String, String>): String {
        var out = s
        map.forEach { (k, v) -> out = out.replace("\${$k}", v) }
        return out
    }

    fun offlineAccount(name: String): Account = Account(username = name, uuid = Account.offlineUuid(name))
}
