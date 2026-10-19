package com.mizulauncher.minecraft

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.mizulauncher.download.DownloadClass
import com.mizulauncher.download.DownloadEngine
import com.mizulauncher.download.DownloadSource
import com.mizulauncher.download.DownloadTask
import com.mizulauncher.download.UrlRewriter
import com.mizulauncher.minecraft.model.AssetIndex
import com.mizulauncher.minecraft.model.VersionEntry
import com.mizulauncher.minecraft.model.VersionJson
import com.mizulauncher.minecraft.model.VersionManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * 版本清单仓库：拉取 version_manifest、按需拉取单个版本 JSON，并缓存到本地。
 * 对应 PCL2 的 `DlVersionList`。
 */
class VersionRepository(
    private val sourceFlow: StateFlow<DownloadSource>,
    private val cacheDir: File,
    private val client: OkHttpClient = DownloadEngine.defaultClient()
) {
    private val gson: Gson = GsonBuilder().setLenient().create()

    private val _manifest = MutableStateFlow<VersionManifest?>(null)
    val manifest: StateFlow<VersionManifest?> = _manifest.asStateFlow()

    /** 拉取版本清单（优先本地缓存，超过 1 小时则刷新） */
    suspend fun loadManifest(force: Boolean = false): Result<VersionManifest> = withContext(Dispatchers.IO) {
        runCatching {
            val cache = File(cacheDir, "version_manifest_v2.json")
            if (!force && cache.isFile && System.currentTimeMillis() - cache.lastModified() < 3600_000L) {
                gson.fromJson(cache.readText(), VersionManifest::class.java)?.let {
                    _manifest.value = it
                    return@runCatching it
                }
            }
            val url = UrlRewriter.rewrite(sourceFlow.value, OFFICIAL_MANIFEST, DownloadClass.METADATA)
            val body = fetchText(url)
            val parsed = gson.fromJson(body, VersionManifest::class.java)
            cache.parentFile?.mkdirs()
            cache.writeText(body)
            _manifest.value = parsed
            parsed
        }
    }

    /** 拉取并解析单个版本 JSON（不含继承处理） */
    suspend fun loadVersionJson(entry: VersionEntry): Result<VersionJson> = withContext(Dispatchers.IO) {
        runCatching {
            val cache = File(cacheDir, "versions/${entry.id}.json")
            if (cache.isFile && (entry.sha1 == null || DownloadEngine.verifySha1(cache, entry.sha1))) {
                return@runCatching gson.fromJson(cache.readText(), VersionJson::class.java)
            }
            val url = UrlRewriter.rewrite(sourceFlow.value, entry.url, DownloadClass.METADATA)
            val body = fetchText(url)
            val json = gson.fromJson(body, VersionJson::class.java)
            cache.parentFile?.mkdirs()
            cache.writeText(body)
            json
        }
    }

    /** 递归解析 inheritsFrom 链，返回合并后的最终 JSON */
    suspend fun resolveWithInheritance(entry: VersionEntry): Result<VersionJson> = withContext(Dispatchers.IO) {
        runCatching {
            val chain = mutableListOf<VersionJson>()
            var current: VersionEntry? = entry
            while (current != null) {
                val json = loadVersionJson(current).getOrThrow()
                chain += json
                val parentId = json.inheritsFrom ?: break
                current = _manifest.value?.versions?.firstOrNull { it.id == parentId }
                    ?: VersionEntry(parentId, "release", current.url)
            }
            // 由父到子依次合并
            chain.reversed().reduce { parent, child -> VersionRules.merge(parent, child) }
        }
    }

    suspend fun loadAssetIndex(ref: VersionJson.AssetIndexRef): Result<AssetIndex> = withContext(Dispatchers.IO) {
        runCatching {
            val cache = File(cacheDir, "assets/indexes/${ref.id}.json")
            if (cache.isFile && DownloadEngine.verifySha1(cache, ref.sha1 ?: "")) {
                return@runCatching gson.fromJson(cache.readText(), AssetIndex::class.java)
            }
            val url = UrlRewriter.rewrite(sourceFlow.value, ref.url, DownloadClass.METADATA)
            val body = fetchText(url)
            val index = gson.fromJson(body, AssetIndex::class.java)
            cache.parentFile?.mkdirs()
            cache.writeText(body)
            index
        }
    }

    private fun fetchText(url: String): String {
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code} @ $url")
            return resp.body?.string() ?: ""
        }
    }

    companion object {
        const val OFFICIAL_MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"

        /** 把 PlanItem 转换为下载器任务 */
        fun toDownloadTasks(
            plan: List<DownloadPlanBuilder.PlanItem>,
            rootDir: File
        ): List<DownloadTask> = plan.map { item ->
            DownloadTask(
                url = item.url,
                target = File(rootDir, item.relativePath),
                sha1 = item.sha1,
                size = item.size,
                downloadClass = item.downloadClass,
                fatal = item.fatal
            )
        }
    }
}
