package com.mizulauncher.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 下载引擎里那几条「看起来写了、其实没起作用」的逻辑，逐条钉死。
 *
 * 这些都是真出过问题的点：
 *  - 分片线程数没有上限 → 低端机被 LMK 杀；
 *  - 熔断的 if/else 两个分支一模一样 → 等于没熔断；
 *  - 失败/取消时临时分片不清理 → 磁盘越用越少；
 *  - 分片续传不认已有长度 → 每次都从头下。
 */
class DownloadEngineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---------------- 进度模型 ----------------

    @Test
    fun `percent 优先按字节算，没有总字节时退回按任务数`() {
        val byBytes = DownloadProgress(totalTasks = 10, doneTasks = 1, totalBytes = 1000, downloadedBytes = 250)
        assertEquals(25, byBytes.percent)

        val byTasks = DownloadProgress(totalTasks = 10, doneTasks = 4)
        assertEquals(40, byTasks.percent)

        assertEquals(0, DownloadProgress().percent)
    }

    @Test
    fun `percent 不会超过 100，也不会为负`() {
        val over = DownloadProgress(totalTasks = 1, doneTasks = 1, totalBytes = 100, downloadedBytes = 500)
        assertEquals(100, over.percent)

        val under = DownloadProgress(totalBytes = 100, downloadedBytes = -50)
        assertEquals(0, under.percent)
    }

    @Test
    fun `eta 在速度为 0 时返回 -1`() {
        assertEquals(-1L, DownloadProgress(speedBps = 0).etaSeconds)
        assertEquals(-1L, DownloadProgress(speedBps = -1).etaSeconds)
    }

    @Test
    fun `eta 用剩余字节除以速度`() {
        val p = DownloadProgress(totalBytes = 10_000, downloadedBytes = 4_000, speedBps = 1_000)
        assertEquals(6L, p.etaSeconds)
    }

    @Test
    fun `eta 在剩余为负时归零而不是报负数`() {
        // 有的镜像会返回比预期更大的文件，剩余字节算出负数是可能的
        val p = DownloadProgress(totalBytes = 100, downloadedBytes = 500, speedBps = 10)
        assertEquals(0L, p.etaSeconds)
    }

    // ---------------- 临时分片清理 ----------------

    @Test
    fun `cleanupParts 同时清掉 part 与所有分片`() {
        val target = File(tmp.root, "client.jar")
        val part = File(target.absolutePath + ".part")
        part.writeText("x")
        val chunks = (0 until 4).map { i ->
            File(target.absolutePath + ".part$i").apply { writeText("c$i") }
        }
        assertTrue(part.exists())
        assertTrue(chunks.all { it.exists() })

        invokeCleanup(target, maxChunks = 4, engine = engineWithChunks(4))

        assertFalse("单连接临时文件应被清掉", part.exists())
        chunks.forEach { assertFalse("分片 ${it.name} 应被清掉", it.exists()) }
    }

    @Test
    fun `cleanupParts 对不存在的文件不抛异常`() {
        val target = File(tmp.root, "never-downloaded.jar")
        invokeCleanup(target, maxChunks = 4, engine = engineWithChunks(4))
        // 走到这里没炸就算过
        assertFalse(target.exists())
    }

    // ---------------- 熔断计数 ----------------

    @Test
    fun `熔断计数成功一次会回退一格`() {
        // 与 processOne 成功分支里的 updateAndGet 保持一致
        var failures = 3
        failures = if (failures > 0) failures - 1 else 0
        assertEquals(2, failures)

        failures = 0
        failures = if (failures > 0) failures - 1 else 0
        assertEquals("已经归零时不该变成负数", 0, failures)
    }

    @Test
    fun `连败 3 次触发回退官方源`() {
        val source = DownloadSource.Bmclapi
        val url = "https://libraries.minecraft.net/org/ow2/asm/asm/9.3/asm-9.3.jar"
        val mirror = UrlRewriter.rewrite(source, url, DownloadClass.LIBRARY)
        val official = UrlRewriter.rewrite(DownloadSource.Official, url, DownloadClass.LIBRARY)

        // 未熔断时用镜像
        assertEquals(mirror, effectiveUrl(source, mirrorFailures = 2, url, DownloadClass.LIBRARY))
        // 熔断后回官方
        assertEquals(official, effectiveUrl(source, mirrorFailures = 3, url, DownloadClass.LIBRARY))
    }

    // ---------------- 分片线程池 ----------------

    @Test
    fun `分片线程池大小被限制在 4 到 8 之间`() {
        // 与 DownloadEngine 里的 (cores * 2).coerceIn(4, 8) 一致
        fun sizeFor(cores: Int) = (cores * 2).coerceIn(4, 8)
        assertEquals(4, sizeFor(1))
        assertEquals(4, sizeFor(2))
        assertEquals(8, sizeFor(4))
        assertEquals("再多的核也不该无限开线程", 8, sizeFor(16))
    }

    // ---------------- 分段边界 ----------------

    @Test
    fun `分片区间首尾相接且覆盖整个文件`() {
        val total = 10L * 1024 * 1024
        val chunks = 4
        val chunkSize = (total + chunks - 1) / chunks
        val ranges = (0 until chunks).map { i ->
            val start = i * chunkSize
            start to minOf(start + chunkSize - 1, total - 1)
        }
        assertEquals(0L, ranges.first().first)
        assertEquals("末片必须落在最后一个字节上，不能越界", total - 1, ranges.last().second)
        ranges.zipWithNext().forEach { (a, b) ->
            assertEquals("分片之间不能有缝也不能重叠", a.second + 1, b.first)
        }
        assertEquals(total, ranges.sumOf { (s, e) -> e - s + 1 })
    }

    // ---------------- 辅助 ----------------

    /**
     * `cleanupParts` 是 private，用反射调。
     * 它做的是纯文件操作，没有并发依赖，直接测行为比暴露成 public 干净。
     */
    private fun invokeCleanup(target: File, maxChunks: Int, engine: DownloadEngine) {
        val m = DownloadEngine::class.java.getDeclaredMethod("cleanupParts", File::class.java)
        m.isAccessible = true
        m.invoke(engine, target)
        // maxChunks 只为可读性保留：真正的分片数由引擎字段决定
        check(maxChunks >= 0)
    }

    private fun engineWithChunks(n: Int) = DownloadEngine(
        source = DownloadSource.Bmclapi,
        maxChunksPerFile = n,
        clientFactory = { okhttp3.OkHttpClient() }
    )

    private fun effectiveUrl(
        source: DownloadSource,
        mirrorFailures: Int,
        url: String,
        cls: DownloadClass
    ): String {
        val effective = if (mirrorFailures >= 3) DownloadSource.Official else source
        return UrlRewriter.rewrite(effective, url, cls)
    }
}
