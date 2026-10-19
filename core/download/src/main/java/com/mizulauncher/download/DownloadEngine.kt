package com.mizulauncher.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** 单个下载任务 */
data class DownloadTask(
    val url: String,
    val target: File,
    val sha1: String? = null,
    val size: Long = -1L,
    val downloadClass: DownloadClass = DownloadClass.LIBRARY,
    /** 失败是否致命 */
    val fatal: Boolean = true
)

enum class TaskOutcome { DOWNLOADED, VERIFIED, SKIPPED, FAILED }

data class DownloadProgress(
    val totalTasks: Int = 0,
    val doneTasks: Int = 0,
    val failedTasks: Int = 0,
    val totalBytes: Long = 0L,
    val downloadedBytes: Long = 0L,
    val networkBytes: Long = 0L,
    val speedBps: Long = 0L,
    val currentFile: String = "",
    val stage: String = "",
    val finished: Boolean = false
) {
    val percent: Int
        get() = when {
            totalBytes > 0L -> ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
            totalTasks > 0 -> (doneTasks * 100) / totalTasks
            else -> 0
        }

    val etaSeconds: Long
        get() {
            if (speedBps <= 0) return -1
            val remain = (totalBytes - downloadedBytes).coerceAtLeast(0)
            return remain / speedBps
        }
}

/**
 * 高速下载引擎（PCL2 风格）
 *
 * 特性：
 *  1. 并发文件下载（`maxParallelFiles`），每个文件独立重试 3 次；
 *  2. **镜像熔断**：同一镜像连续失败 3 次后，本次任务回退官方源；
 *  3. **断点续传**：写入 `<file>.part`，HTTP Range 续传；
 *  4. **SHA1 校验**：已存在且校验通过的文件直接跳过（零流量）；
 *  5. 多连接分段下载（`maxChunksPerFile > 1` 时对大文件生效），并行 Range 拉取后合并；
 *  6. 进度聚合到 `progress: StateFlow<DownloadProgress>`。
 */
class DownloadEngine(
    private val source: DownloadSource = DownloadSource.Bmclapi,
    private val maxParallelFiles: Int = 6,
    private val maxChunksPerFile: Int = 4,
    /** 超过该大小才启用多连接 */
    private val chunkThreshold: Long = 8L * 1024 * 1024,
    private val clientFactory: () -> okhttp3.OkHttpClient = { defaultClient() }
) {
    private val client by lazy(clientFactory)
    private val _progress = MutableStateFlow(DownloadProgress())
    val progress: StateFlow<DownloadProgress> = _progress.asStateFlow()

    private val meter = SpeedMeter()
    private val cancelFlag = java.util.concurrent.atomic.AtomicBoolean(false)
    private val mirrorFailures = AtomicInteger(0)

    private val doneCounter = AtomicLong(0)
    private val failedCounter = AtomicLong(0)
    private val downloadedCounter = AtomicLong(0)
    private val networkCounter = AtomicLong(0)
    private val totalBytes = AtomicLong(0)

    fun cancel() {
        cancelFlag.set(true)
    }

    /**
     * 执行一批下载。返回失败数量。
     */
    suspend fun run(
        tasks: List<DownloadTask>,
        stage: String,
        onTaskDone: ((DownloadTask, TaskOutcome) -> Unit)? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        cancelFlag.set(false)
        mirrorFailures.set(0)
        doneCounter.set(0)
        failedCounter.set(0)
        downloadedCounter.set(0)
        networkCounter.set(0)
        totalBytes.set(tasks.sumOf { if (it.size > 0) it.size else 0L })
        meter.reset()

        _progress.value = DownloadProgress(
            totalTasks = tasks.size,
            totalBytes = totalBytes.get(),
            stage = stage,
            currentFile = ""
        )

        val semaphore = Semaphore(maxParallelFiles)
        val lock = Mutex()

        try {
            coroutineScope {
                val reporter = launch {
                    while (true) {
                        kotlinx.coroutines.delay(300)
                        publish(stage)
                    }
                }

                val jobs = tasks.map { task ->
                    async {
                        semaphore.withPermit {
                            if (cancelFlag.get()) return@withPermit
                            _progress.value = _progress.value.copy(currentFile = task.target.name)
                            val outcome = processOne(task)
                            lock.withLock {
                                when (outcome) {
                                    TaskOutcome.DOWNLOADED, TaskOutcome.VERIFIED, TaskOutcome.SKIPPED -> {
                                        doneCounter.incrementAndGet()
                                        downloadedCounter.addAndGet(if (task.size > 0) task.size else task.target.length())
                                    }
                                    TaskOutcome.FAILED -> failedCounter.incrementAndGet()
                                }
                            }
                            onTaskDone?.invoke(task, outcome)
                        }
                    }
                }
                jobs.awaitAll()
                reporter.cancel()
            }
            publish(stage, finished = true)
            if (failedCounter.get() > 0) {
                Result.failure(DownloadException("${failedCounter.get()} 个文件下载失败"))
            } else {
                Result.success(Unit)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun publish(stage: String, finished: Boolean = false) {
        val speed = meter.feed()
        _progress.value = _progress.value.copy(
            doneTasks = doneCounter.get().toInt(),
            failedTasks = failedCounter.get().toInt(),
            downloadedBytes = downloadedCounter.get(),
            networkBytes = networkCounter.get(),
            speedBps = speed,
            stage = stage,
            finished = finished
        )
    }

    private fun processOne(task: DownloadTask): TaskOutcome {
        if (cancelFlag.get()) return TaskOutcome.SKIPPED

        // 已存在且校验通过 → 跳过
        if (task.target.isFile && task.sha1 != null && verifySha1(task.target, task.sha1)) {
            return TaskOutcome.VERIFIED
        }
        if (task.target.isFile && task.sha1 == null && task.size > 0 && task.target.length() == task.size) {
            return TaskOutcome.SKIPPED
        }

        var lastError: Exception? = null
        repeat(3) {
            if (cancelFlag.get()) return TaskOutcome.SKIPPED
            try {
                val url = resolveUrl(task)
                downloadFile(url, task)
                if (task.sha1 != null && !verifySha1(task.target, task.sha1)) {
                    task.target.delete()
                    throw DownloadException("SHA1 校验失败：${task.target.name}")
                }
                return TaskOutcome.DOWNLOADED
            } catch (e: Exception) {
                lastError = e
                if (e is DownloadException && e.message?.contains("404") == true) {
                    mirrorFailures.incrementAndGet()
                } else {
                    mirrorFailures.incrementAndGet()
                }
            }
        }
        task.target.delete()
        if (lastError is DownloadException) {
            android.util.Log.w("PCLDownload", "跳过 ${task.url}: ${lastError.message}")
        }
        return if (task.fatal) TaskOutcome.FAILED else TaskOutcome.SKIPPED
    }

    private fun resolveUrl(task: DownloadTask): String {
        if (!task.url.startsWith("http")) return task.url
        val effective = if (mirrorFailures.get() >= 3) DownloadSource.Official else source
        return UrlRewriter.rewrite(effective, task.url, task.downloadClass)
    }

    /**
     * 下载单个文件：
     *  - 小文件 / 不支持 Range → 单连接断点续传
     *  - 大文件且服务器支持 → 多连接分段
     */
    private fun downloadFile(url: String, task: DownloadTask) {
        task.target.parentFile?.mkdirs()
        // 多连接分段（仅当已知大小且超过阈值）
        if (maxChunksPerFile > 1 && task.size >= chunkThreshold) {
            runCatching { downloadChunked(url, task) }
                .onSuccess { return }
                .onFailure { task.target.delete() }
        }
        downloadSingle(url, task)
    }

    private fun downloadSingle(url: String, task: DownloadTask) {
        val part = File(task.target.absolutePath + ".part")
        var existing = if (part.exists()) part.length() else 0L
        val request = okhttp3.Request.Builder().url(url).apply {
            if (existing > 0) header("Range", "bytes=$existing-")
        }.build()

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw DownloadException("HTTP ${resp.code} @ $url")
            val append = resp.code == 206 && existing > 0
            if (!append) existing = 0L
            val out = if (append) java.io.FileOutputStream(part, true) else java.io.FileOutputStream(part, false)
            out.use { os ->
                val input = resp.body?.byteStream() ?: throw DownloadException("空响应 @ $url")
                input.use { ins ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelFlag.get()) throw DownloadException("已取消")
                        val n = ins.read(buf)
                        if (n < 0) break
                        os.write(buf, 0, n)
                        os.flush()
                        networkCounter.addAndGet(n.toLong())
                        meter.add(n.toLong())
                    }
                }
            }
        }
        finalizeFile(part, task.target)
    }

    private fun downloadChunked(url: String, task: DownloadTask) {
        val total = task.size
        val chunkSize = (total + maxChunksPerFile - 1) / maxChunksPerFile
        val parts = (0 until maxChunksPerFile).map { i ->
            val start = i * chunkSize
            val end = minOf(start + chunkSize - 1, total - 1)
            File(task.target.absolutePath + ".part$i") to (start to end)
        }

        // 并行拉取（阻塞式，由外层 IO 线程池承载）
        parts.map { (file, range) ->
            Thread {
                try {
                    fetchRange(url, file, range.first, range.second)
                } catch (e: Exception) {
                    android.util.Log.w("PCLDownload", "分片失败 ${file.name}: ${e.message}")
                }
            }.apply { start() }
        }.forEach { it.join() }

        // 校验所有分片齐全后合并
        val expected = parts.sumOf { (f, r) -> r.second - r.first + 1 }
        val actual = parts.sumOf { (f, _) -> if (f.exists()) f.length() else 0L }
        if (actual != expected) throw DownloadException("分片不完整 @ $url")

        task.target.parentFile?.mkdirs()
        java.io.FileOutputStream(task.target, false).use { out ->
            parts.forEach { (f, _) ->
                f.inputStream().use { it.copyTo(out) }
                f.delete()
            }
        }
    }

    private fun fetchRange(url: String, file: File, start: Long, end: Long) {
        val request = okhttp3.Request.Builder().url(url).header("Range", "bytes=$start-$end").build()
        client.newCall(request).execute().use { resp ->
            if (resp.code != 206) throw DownloadException("服务器不支持 Range（${resp.code}）")
            file.outputStream().use { os ->
                resp.body?.byteStream()?.use { ins ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelFlag.get()) throw DownloadException("已取消")
                        val n = ins.read(buf)
                        if (n < 0) break
                        os.write(buf, 0, n)
                        networkCounter.addAndGet(n.toLong())
                        meter.add(n.toLong())
                    }
                }
            }
        }
    }

    private fun finalizeFile(part: File, target: File) {
        if (!part.exists()) throw DownloadException("临时文件丢失: ${part.name}")
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            part.delete()
        }
    }

    companion object {
        fun defaultClient(): okhttp3.OkHttpClient = okhttp3.OkHttpClient.Builder()
            .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(40, java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .build()

        fun sha1Of(file: File): String {
            val digest = MessageDigest.getInstance("SHA-1")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        fun verifySha1(file: File, expected: String): Boolean =
            file.isFile && file.length() > 0 && sha1Of(file).equals(expected, ignoreCase = true)
    }
}

class DownloadException(message: String) : Exception(message)

/** 滑动窗口测速 */
class SpeedMeter(private val windowMs: Long = 400L, private val windowCount: Int = 5) {
    private val pending = AtomicLong(0)
    private var lastTime = System.currentTimeMillis()
    private val samples = ArrayDeque<Long>()

    fun reset() {
        pending.set(0)
        lastTime = System.currentTimeMillis()
        samples.clear()
    }

    fun add(n: Long) {
        pending.addAndGet(n)
    }

    fun feed(): Long {
        val now = System.currentTimeMillis()
        val dt = now - lastTime
        if (dt >= windowMs) {
            val bytes = pending.getAndSet(0)
            samples.addLast(bytes * 1000 / dt)
            while (samples.size > windowCount) samples.removeFirst()
            lastTime = now
        }
        return if (samples.isEmpty()) 0L else samples.average().toLong()
    }
}
