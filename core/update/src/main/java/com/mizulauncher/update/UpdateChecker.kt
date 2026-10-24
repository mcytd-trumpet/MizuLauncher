package com.mizulauncher.update

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 应用内更新：查 GitHub Release → 挑对架构的 APK → 下载。
 *
 * 版本比较用的是 app 自己的 `versionCode`，而不是 tag 名字符串。
 * 原因：tag 可能是 `v1.2.0`、`1.2`、`v1.2.0-beta` 各种写法，
 * 字符串比大小会踩 `v1.10 < v1.9` 这种坑；versionCode 是单调整数，
 * 最可靠。Release 资源名里会带上 versionCode（见 CI），解析不出来才退回比 tag。
 */
class UpdateChecker(
    private val owner: String = "mcytd-trumpet",
    private val repo: String = "MizuLauncher",
    private val http: OkHttpClient = defaultClient()
) {

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        /** APK 文件名里的架构标记，顺序即优先级（注意 x64 必须排在 x86 前面） */
        val ABI_TAGS = listOf("arm64", "arm32", "x64", "x86", "universal")

        /** 架构的中文说明，用于「下载哪个包」的提示 */
        fun abiLabel(abi: String): String = when (abi) {
            "arm64" -> "ARM64（绝大多数手机）"
            "arm32" -> "ARM32（老旧 32 位设备）"
            "x64" -> "x86_64（模拟器 / Intel 平板）"
            "x86" -> "x86（32 位模拟器）"
            "universal" -> "全架构通用包"
            else -> abi
        }

        /**
         * 从 APK 文件名里取 versionCode（CI 会编进名字：`MizuLauncher-1.2.3-10203-arm64.apk`）。
         *
         * 比 tag 更可信：tag 可能手滑写成 `v1.2.3.4` 或漏写 `v`，
         * 而 versionCode 是打包时 Gradle 用的同一个数。
         */
        fun versionCodeFromName(name: String): Int? =
            Regex("""-(\d{3,})-(?:arm32|arm64|x86|x64|universal)\.apk$""")
                .find(name)?.groupValues?.get(1)?.toIntOrNull()

        /**
         * 设备该装哪个包。
         *
         * 与 `Build.SUPPORTED_ABIS` 对齐：64 位设备通常同时声明 arm64 和 arm32，
         * 优先取第一个命中的（系统把最合适的排最前）。全是未知时退到 universal。
         */
        fun pickAbiFor(abis: List<String>): String {
            for (abi in abis) {
                when {
                    abi.startsWith("arm64") -> return "arm64"
                    abi.startsWith("armeabi") -> return "arm32"
                    abi.startsWith("x86_64") -> return "x64"
                    abi.startsWith("x86") -> return "x86"
                }
            }
            return "universal"
        }

        /** 从 `.../MizuLauncher-v1.0.0-arm64.apk` 这类名字里取架构标记 */
        fun abiOfAsset(name: String): String? {
            val lower = name.lowercase()
            if (!lower.endsWith(".apk")) return null
            return ABI_TAGS.firstOrNull { lower.contains("-$it.") || lower.contains("_$it.") }
        }

        /** 从 tag 里取 semantic 版本，取不到返回 null */
        fun versionFromTag(tag: String): String? {
            val m = Regex("""v?(\d+(?:\.\d+){0,3})""").find(tag.trim()) ?: return null
            return m.groupValues[1]
        }

        /** `1.2.10` vs `1.2.9` → 正数表示前者更新。支持长度不等 */
        fun compareVersions(a: String, b: String): Int {
            val pa = a.split('.').map { it.toIntOrNull() ?: 0 }
            val pb = b.split('.').map { it.toIntOrNull() ?: 0 }
            val n = maxOf(pa.size, pb.size)
            for (i in 0 until n) {
                val x = pa.getOrElse(i) { 0 }
                val y = pb.getOrElse(i) { 0 }
                if (x != y) return x - y
            }
            return 0
        }
    }

    data class ReleaseAsset(val name: String, val url: String, val sizeBytes: Long) {
        /** 这个包对应的架构标记，识别不出返回 null */
        val abi: String? get() = abiOfAsset(name)
        /** 文件大小（人类可读） */
        val sizeText: String
            get() = when {
                sizeBytes <= 0 -> "未知大小"
                sizeBytes >= 1L shl 20 -> "%.1f MB".format(sizeBytes / 1024.0 / 1024.0)
                else -> "%d KB".format(sizeBytes / 1024)
            }
    }

    data class UpdateInfo(
        val tag: String,
        val versionName: String,
        val versionCode: Int,
        val notes: String,
        val publishedAt: String,
        /** 当前设备应该下载的那个包 */
        val asset: ReleaseAsset?,
        /** 这个版本提供的全部 APK，用于架构识别失败时的兜底展示 */
        val allAssets: List<ReleaseAsset>,
        /** 本机已下载过的资源名（与 [asset] 同名时可跳过下载直接装） */
        val cachedAssetName: String? = null,
        /** 本机判定出的架构标记，用于把「为什么选了这个包」讲清楚 */
        val deviceAbi: String = "universal"
    ) {
        /** 已有同版本安装包可复用 */
        val reuseCached: Boolean get() = asset != null && asset.name == cachedAssetName
    }

    /**
     * 查最新 Release。
     *
     * @return null 表示「已是最新」或「没有可用的 Release」
     */
    fun checkForUpdate(
        currentVersionCode: Int,
        currentVersionName: String,
        deviceAbis: List<String>
    ): UpdateInfo? {
        val raw = fetchLatestReleaseJson() ?: return null
        return parseRelease(raw, currentVersionCode, currentVersionName, deviceAbis)
    }

    /**
     * 纯函数版的解析与判定：给定 Release 的 JSON，算出要不要更新、该下哪个包。
     *
     * 抽出来是为了能脱离网络测试——版本比较和架构匹配都是易错点，
     * 靠真打接口测既慢又不稳定。
     */
    fun parseRelease(
        releaseJson: String,
        currentVersionCode: Int,
        currentVersionName: String,
        deviceAbis: List<String>,
        /** 本机已下载过的资源名；与远端一致时可直接复用，不必重下 */
        cachedAssetName: String? = null
    ): UpdateInfo? {
        val release = runCatching { JsonParser.parseString(releaseJson).asJsonObject }
            .getOrNull() ?: return null
        val tag = release["tag_name"]?.asString ?: return null
        val assets = parseAssets(release)

        val remoteCode = versionCodeFromAssets(assets)
            ?: versionCodeFromTag(tag)
            ?: 0
        val remoteName = versionFromTag(tag) ?: tag

        // 双保险：versionCode 能比就比 versionCode，比不出来再比版本号字符串
        val newer = if (remoteCode > 0 && currentVersionCode > 0) {
            remoteCode > currentVersionCode
        } else {
            compareVersions(remoteName, currentVersionName) > 0
        }
        if (!newer) return null

        val wantAbi = pickAbiFor(deviceAbis)
        val exact = assets.firstOrNull { abiOfAsset(it.name) == wantAbi }
        // 没有精确匹配就用 universal 兜底。绝不给别的架构的包：
        // 往 x86 设备塞 arm64 包装完必闪退，用户只会说「更新后打不开了」。
        val asset = exact ?: assets.firstOrNull { abiOfAsset(it.name) == "universal" }

        return UpdateInfo(
            tag = tag,
            versionName = remoteName,
            versionCode = remoteCode,
            notes = release["body"]?.asString?.takeIf { !it.isNullOrBlank() } ?: "",
            publishedAt = release["published_at"]?.asString ?: "",
            // null 表示「有新版但没有适配本机的包」，UI 会引导去网页手动挑
            asset = asset,
            allAssets = assets,
            cachedAssetName = cachedAssetName,
            deviceAbi = wantAbi
        )
    }

    /**
     * 拉 Release JSON 的公开入口。
     *
     * 单独暴露是为了让调用方能把原文缓存下来：手机在电梯里点「检查更新」时，
     * 拿上次的 JSON 重新解析一遍，总比直接甩出「检查失败」有用。
     */
    fun fetchLatestReleaseJsonPublic(): String? = fetchLatestReleaseJson()

    private fun fetchLatestReleaseJson(): String? {
        val req = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .get()
            .build()
        return runCatching {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.string()
            }
        }.getOrNull()
    }

    private fun parseAssets(release: JsonObject): List<ReleaseAsset> {
        val arr = release.getAsJsonArray("assets") ?: return emptyList()
        return arr.mapNotNull { el ->
            if (!el.isJsonObject) return@mapNotNull null
            val o = el.asJsonObject
            val name = o["name"]?.asString ?: return@mapNotNull null
            val url = o["browser_download_url"]?.asString ?: return@mapNotNull null
            val size = runCatching { o["size"]?.asLong ?: 0L }.getOrDefault(0L)
            ReleaseAsset(name, url, size)
        }
    }

    /** CI 把 versionCode 编进文件名：MizuLauncher-1.0.0-10000-arm64.apk */
    private fun versionCodeFromAssets(assets: List<ReleaseAsset>): Int? {
        for (a in assets) {
            versionCodeFromName(a.name)?.let { return it }
        }
        return null
    }

    private fun versionCodeFromTag(tag: String): Int? {
        // 兜底：tag 形如 v1.2.3 → 取主版本*10000 + 次*100 + 修订，与 CI 的算法保持一致
        val v = versionFromTag(tag) ?: return null
        val parts = v.split('.').map { it.toIntOrNull() ?: 0 }
        val major = parts.getOrElse(0) { 0 }
        val minor = parts.getOrElse(1) { 0 }
        val patch = parts.getOrElse(2) { 0 }
        return major * 10_000 + minor * 100 + patch
    }
}
