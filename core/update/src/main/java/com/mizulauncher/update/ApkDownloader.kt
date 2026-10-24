package com.mizulauncher.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * APK 下载器。
 *
 * 不放进 `core:download` 是有意的：那个模块是给游戏文件用的，
 * 带镜像重写、分段并发、熔断，对单个 APK 来说纯属杀鸡用牛刀，
 * 而且会把更新逻辑和游戏下载的失败处理纠缠在一起。
 */
class ApkDownloader(private val http: OkHttpClient = UpdateChecker.defaultClient()) {

    data class Progress(val received: Long, val total: Long) {
        val percent: Int
            get() = if (total > 0) ((received * 100) / total).toInt().coerceIn(0, 100) else 0
    }

    /**
     * 下载到 [target]。
     *
     * 支持 HTTP Range 续传：更新包动辄几十兆，手机流量环境下断一次就重下太浪费。
     */
    suspend fun download(
        url: String,
        target: File,
        onProgress: (Progress) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            target.parentFile?.mkdirs()
            var existing = if (target.exists()) target.length() else 0L

            val builder = Request.Builder().url(url)
            if (existing > 0) builder.header("Range", "bytes=$existing-")
            val resp = http.newCall(builder.build()).execute()

            if (existing > 0 && resp.code == 416) {
                // 服务端说范围越界：本地文件多半是坏的不完整包，删掉重下一次
                target.delete()
                resp.close()
                val req = Request.Builder().url(url).build()
                http.newCall(req).execute().use { r2 ->
                    if (!r2.isSuccessful) throw java.io.IOException("下载失败：HTTP ${r2.code}")
                    writeBody(r2, target, append = false, onProgress)
                }
                return@runCatching target
            }
            if (!resp.isSuccessful) {
                resp.close()
                throw java.io.IOException("下载失败：HTTP ${resp.code}")
            }

            resp.use { r -> writeBody(r, target, append = existing > 0, onProgress, existing) }
            target
        }
    }

    private suspend fun writeBody(
        resp: okhttp3.Response,
        target: File,
        append: Boolean,
        onProgress: (Progress) -> Unit,
        existing: Long = 0L
    ) {
        val body = resp.body ?: throw java.io.IOException("响应为空")
        val total = if (resp.code == 206) body.contentLength() + existing else body.contentLength()
        var received = existing
        body.byteStream().use { input ->
            java.io.FileOutputStream(target, append).use { out ->
                val buf = ByteArray(64 * 1024)
                var lastPct = -1
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    received += n
                    val p = Progress(received, total)
                    if (p.percent != lastPct) {
                        lastPct = p.percent
                        onProgress(p)
                    }
                }
            }
        }
        onProgress(Progress(received, if (total > 0) total else received))
    }
}
