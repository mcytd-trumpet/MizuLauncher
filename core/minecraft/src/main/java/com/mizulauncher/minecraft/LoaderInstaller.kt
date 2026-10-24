package com.mizulauncher.minecraft

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mizulauncher.download.DownloadClass
import com.mizulauncher.download.DownloadEngine
import com.mizulauncher.download.DownloadSource
import com.mizulauncher.download.UrlRewriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.zip.ZipFile

/**
 * 加载器安装器（Fabric / Quilt / Forge / NeoForge / OptiFine）。
 *
 * ## 两种截然不同的安装方式
 *
 * | 加载器 | 方式 | 手机端可行性 |
 * |---|---|---|
 * | Fabric / Quilt | 元数据接口直接返回完整 profile JSON，落到 `versions/<id>/<id>.json` 即可 | 完全可行 |
 * | Forge / NeoForge | 官方 installer 需要在桌面 JVM 跑 install 流程；手机端改为解包 `version.json` + 内嵌 `maven/` 目录 | 可行（Pojav 同款做法） |
 * | OptiFine | 许可禁止启动器再分发，只能引导用户手动下载 | 需手动 |
 *
 * Forge 的 processors（反编译打补丁）在手机上不执行：Pojav 内核会在运行时处理，
 * 我们只需保证 profile JSON 与 libraries 齐全。
 */
class LoaderInstaller(
    private val sourceFlow: StateFlow<DownloadSource>,
    private val client: OkHttpClient = DownloadEngine.defaultClient(),
    private val gson: Gson = GsonBuilder().setLenient().create()
) {

    data class InstalledLoader(
        val versionId: String,
        val mainClass: String,
        val profileJsonPath: String,
        val loaderType: String,
        val loaderVersion: String
    )

    sealed interface Outcome {
        data class Success(val loader: InstalledLoader) : Outcome
        data class NeedManual(val reason: String, val downloadPage: String? = null) : Outcome
        data class Failure(val message: String) : Outcome
    }

    /**
     * 安装加载器。
     *
     * @param gameVersion 原版版本号，如 1.20.1
     * @param loaderType fabric / quilt / forge / neoforge / optifine
     * @param loaderVersion 空则自动选推荐版本
     * @param versionsDir 实例的 versions 目录（绝对路径）
     * @param librariesDir 实例的 libraries 目录（绝对路径）
     */
    suspend fun install(
        gameVersion: String,
        loaderType: String,
        loaderVersion: String,
        versionsDir: File,
        librariesDir: File,
        onStage: (String) -> Unit = {}
    ): Outcome = withContext(Dispatchers.IO) {
        runCatching {
            when (loaderType.lowercase()) {
                "vanilla", "" -> Outcome.Failure("原版无需安装加载器")
                "fabric" -> installFabric(gameVersion, loaderVersion, versionsDir, onStage)
                "quilt" -> installQuilt(gameVersion, loaderVersion, versionsDir, onStage)
                "forge" -> installForge(gameVersion, loaderVersion, versionsDir, librariesDir, onStage)
                "neoforge" -> installNeoForge(gameVersion, loaderVersion, versionsDir, librariesDir, onStage)
                "optifine" -> Outcome.NeedManual(
                    "OptiFine 的许可协议不允许启动器自动下载与再分发安装包。\n" +
                        "请从 optifine.net 手动下载后放进实例的 mods/ 目录，或按官方说明安装。",
                    "https://optifine.net/downloads"
                )
                else -> Outcome.Failure("未知加载器：$loaderType")
            }
        }.getOrElse { e ->
            Outcome.Failure("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // ---------------- Fabric ----------------

    private fun installFabric(
        gameVersion: String,
        wanted: String,
        versionsDir: File,
        onStage: (String) -> Unit
    ): Outcome {
        onStage("获取 Fabric 加载器版本")
        val loaderList = fetchArray("https://meta.fabricmc.net/v2/versions/loader")
        val version = wanted.ifBlank {
            val stable = loaderList.mapNotNull { it.asObjectOrNull() }
                .firstOrNull { it.boolOr("stable") }
                ?: loaderList.firstOrNull()?.asObjectOrNull()
            stable?.stringOr("version") ?: throw IllegalStateException("Fabric 元数据为空")
        }

        onStage("下载 Fabric $version 配置")
        val profile = fetchText(
            "https://meta.fabricmc.net/v2/versions/loader/$gameVersion/$version/profile/json"
        )
        val id = "$gameVersion-fabric-$version"
        val dir = File(versionsDir, id).apply { mkdirs() }
        File(dir, "$id.json").writeText(profile)

        val main = JsonParser.parseString(profile).asJsonObject.stringOr("mainClass")
            ?: "net.fabricmc.loader.impl.launch.knot.KnotClient"
        return Outcome.Success(
            InstalledLoader(id, main, File(dir, "$id.json").absolutePath, "fabric", version)
        )
    }

    // ---------------- Quilt ----------------

    private fun installQuilt(
        gameVersion: String,
        wanted: String,
        versionsDir: File,
        onStage: (String) -> Unit
    ): Outcome {
        onStage("获取 Quilt 加载器版本")
        val loaderList = fetchArray("https://meta.quiltmc.org/v3/versions/loader")
        val version = wanted.ifBlank {
            loaderList.firstOrNull()?.asObjectOrNull()?.stringOr("version")
                ?: throw IllegalStateException("Quilt 元数据为空")
        }

        onStage("下载 Quilt $version 配置")
        val profile = fetchText(
            "https://meta.quiltmc.org/v3/versions/loader/$gameVersion/$version/profile/json"
        )
        val id = "$gameVersion-quilt-$version"
        val dir = File(versionsDir, id).apply { mkdirs() }
        File(dir, "$id.json").writeText(profile)

        val main = JsonParser.parseString(profile).asJsonObject.stringOr("mainClass")
            ?: "org.quiltmc.loader.impl.launch.knot.KnotClient"
        return Outcome.Success(
            InstalledLoader(id, main, File(dir, "$id.json").absolutePath, "quilt", version)
        )
    }

    // ---------------- Forge ----------------

    private fun installForge(
        gameVersion: String,
        wanted: String,
        versionsDir: File,
        librariesDir: File,
        onStage: (String) -> Unit
    ): Outcome {
        onStage("查询 Forge 版本列表")
        val root = JsonParser.parseString(
            fetchText("https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json")
        )
        val promos = root.asJsonObject.getAsJsonObject("promos")
            ?: return Outcome.Failure("Forge 版本元数据格式异常")

        val version = wanted.ifBlank {
            promos.stringOr("$gameVersion-recommended")
                ?: promos.stringOr("$gameVersion-latest")
                ?: throw IllegalStateException(
                    "Forge 没有为 $gameVersion 发布版本。\n" +
                        "注意：Forge 与原版版本号强绑定，1.20.1 的 Forge 不能用于 1.21。"
                )
        }
        val full = "$gameVersion-$version"
        return unpackInstaller(
            loaderName = "Forge",
            installerUrl = "https://maven.minecraftforge.net/net/minecraftforge/forge/$full/forge-$full-installer.jar",
            inheritsFrom = gameVersion,
            versionId = "$gameVersion-forge-$version",
            loaderVersion = version,
            versionsDir = versionsDir,
            librariesDir = librariesDir,
            onStage = onStage
        )
    }

    // ---------------- NeoForge ----------------

    private fun installNeoForge(
        gameVersion: String,
        wanted: String,
        versionsDir: File,
        librariesDir: File,
        onStage: (String) -> Unit
    ): Outcome {
        onStage("查询 NeoForge 版本列表")
        val prefix = neoForgePrefix(gameVersion)
            ?: return Outcome.Failure("NeoForge 只支持 1.20.2 及以后的版本")

        val version = wanted.ifBlank {
            val root = JsonParser.parseString(
                fetchText("https://maven.neoforged.net/api/maven/versions/releases/net/neoforged/neoforge")
            ).asJsonObject
            val all = root.getAsJsonArray("versions")
                ?.mapNotNull { it.asStringOrNull() }
                ?: return Outcome.Failure("NeoForge 版本元数据格式异常")
            all.filter { it.startsWith(prefix) }.maxByOrNull { it }
                ?: return Outcome.Failure("找不到 $gameVersion 对应的 NeoForge 版本")
        }
        return unpackInstaller(
            loaderName = "NeoForge",
            installerUrl = "https://maven.neoforged.net/releases/net/neoforged/neoforge/$version/neoforge-$version-installer.jar",
            inheritsFrom = gameVersion,
            versionId = "$gameVersion-neoforge-$version",
            loaderVersion = version,
            versionsDir = versionsDir,
            librariesDir = librariesDir,
            onStage = onStage
        )
    }

    /** 1.20.2 → "20.2."，1.21.1 → "21.1."；1.20.1 及更早返回 null（当时的 NeoForge 还不存在） */
    private fun neoForgePrefix(gameVersion: String): String? {
        val parts = gameVersion.removePrefix("1.").split('.')
        if (parts.size < 2) return null
        val minor = parts[0].toIntOrNull() ?: return null
        val patch = parts[1].toIntOrNull() ?: return null
        if (minor == 20 && patch < 2) return null
        return "$minor.$patch."
    }

    // ---------------- Forge / NeoForge 共用 ----------------

    /**
     * 解包 installer.jar：
     *  1. 取 `version.json`（新版）或 `install_profile.json` 里的 `versionInfo`（1.12 时代）；
     *  2. 补 `inheritsFrom`，落到 `versions/<id>/<id>.json`；
     *  3. 把内嵌 `maven/` 目录 的 jar 展开到 libraries。
     */
    private fun unpackInstaller(
        loaderName: String,
        installerUrl: String,
        inheritsFrom: String,
        versionId: String,
        loaderVersion: String,
        versionsDir: File,
        librariesDir: File,
        onStage: (String) -> Unit
    ): Outcome {
        onStage("下载 $loaderName 安装包")
        val work = File(versionsDir.parentFile ?: versionsDir, ".loader-cache").apply { mkdirs() }
        val installer = File(work, "${loaderName.lowercase()}-installer.jar")
        downloadTo(installerUrl, installer)

        onStage("解包 $loaderName 配置")
        ZipFile(installer).use { zip ->
            fun entryText(name: String): String? = zip.getEntry(name)?.let { e ->
                zip.getInputStream(e).use { it.readBytes().toString(Charsets.UTF_8) }
            }

            var versionJson = entryText("version.json")
            if (versionJson == null) {
                val profileJson = entryText("install_profile.json")
                if (profileJson != null) {
                    val obj = JsonParser.parseString(profileJson).asJsonObject
                    versionJson = (obj.get("versionInfo") ?: obj.get("version"))?.toString()
                }
            }
            if (versionJson == null) {
                return Outcome.Failure("$loaderName 安装包里没有版本配置，可能安装包格式已变更，请反馈日志。")
            }

            val obj = JsonParser.parseString(versionJson).asJsonObject
            if (obj.get("inheritsFrom") == null || obj.get("inheritsFrom").isJsonNull) {
                obj.addProperty("inheritsFrom", inheritsFrom)
            }

            val dir = File(versionsDir, versionId).apply { mkdirs() }
            File(dir, "$versionId.json").writeText(gson.toJson(obj))

            onStage("展开 $loaderName 内嵌库")
            var count = 0
            zip.entries().asSequence()
                .filter { it.name.startsWith("maven/") && it.name.endsWith(".jar") }
                .forEach { e ->
                    val dest = File(librariesDir, e.name.removePrefix("maven/"))
                    dest.parentFile?.mkdirs()
                    zip.getInputStream(e).use { input -> dest.outputStream().use { input.copyTo(it) } }
                    count++
                }

            val main = obj.stringOr("mainClass") ?: "$loaderName 主类未知"
            onStage("$loaderName 安装完成（展开 $count 个库）")
            return Outcome.Success(
                InstalledLoader(
                    versionId = versionId,
                    mainClass = main,
                    profileJsonPath = File(dir, "$versionId.json").absolutePath,
                    loaderType = loaderName.lowercase(),
                    loaderVersion = loaderVersion
                )
            )
        }
    }

    // ---------------- HTTP ----------------

    private fun downloadTo(url: String, dest: File) {
        val rewritten = UrlRewriter.rewrite(sourceFlow.value, url, DownloadClass.LIBRARY)
        val req = Request.Builder().url(rewritten).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code} @ $rewritten")
            val body = resp.body ?: throw java.io.IOException("空响应 @ $rewritten")
            dest.parentFile?.mkdirs()
            dest.outputStream().use { body.byteStream().copyTo(it) }
        }
    }

    private fun fetchText(url: String): String {
        val rewritten = UrlRewriter.rewrite(sourceFlow.value, url, DownloadClass.METADATA)
        val req = Request.Builder().url(rewritten).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code} @ $rewritten")
            return resp.body?.string() ?: ""
        }
    }

    private fun fetchArray(url: String): JsonArray =
        JsonParser.parseString(fetchText(url)).asJsonArray
}

// ---------------- Gson 取值小工具（避免到处写 null 判断） ----------------

private fun JsonElement.asObjectOrNull(): JsonObject? = if (isJsonObject) asJsonObject else null

private fun JsonObject.stringOr(key: String): String? {
    val e = get(key) ?: return null
    return if (e.isJsonPrimitive) e.asString else null
}

private fun JsonObject.boolOr(key: String): Boolean {
    val e = get(key) ?: return false
    return e.isJsonPrimitive && runCatching { e.asBoolean }.getOrDefault(false)
}

private fun JsonElement.asStringOrNull(): String? =
    if (isJsonPrimitive) asString else null
