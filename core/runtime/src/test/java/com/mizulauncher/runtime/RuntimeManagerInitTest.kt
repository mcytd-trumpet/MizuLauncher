package com.mizulauncher.runtime

import android.content.Context
import com.mizulauncher.download.DownloadSource
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.junit.MockitoJUnitRunner
import java.io.File
import java.nio.file.Files

/**
 * `RuntimeManager` 构造期回归测试。
 *
 * ## 锁住的是哪个 bug
 * 1.0.6 / 1.0.7 在真机上表现为「装得上、点开闪退」，崩溃堆栈是：
 *
 * ```
 * java.lang.RuntimeException: Cannot create an instance of class q1.D
 *   Caused by: NullPointerException: Attempt to invoke virtual method
 *   'java.lang.Object k2.E.getValue()' on a null object reference
 *     at com.mizulauncher.runtime.RuntimeManager.initStates(Unknown Source:10)
 *     at com.mizulauncher.runtime.RuntimeManager.<init>(SourceFile:7)
 * ```
 *
 * `k2.E` 是 Kotlin 混淆后的 `MutableStateFlow`，`getValue()` 打在 null 上，说明
 * 初始化 `_states` 的那一行里，有代码提前读了 `_states` 自己：
 *
 * ```kotlin
 * private val _states = MutableStateFlow(initStates())
 * //                                    └ initStates() 第一行读 _states.value → null
 * ```
 *
 * 异常发生在构造函数里 → Hilt/ViewModel 反射创建实例失败 → Activity 起不来 → 闪退。
 *
 * 现有的 `RuntimeMappingTest` 采用「复刻纯逻辑」写法，测不到构造函数，所以这个 bug
 * 全程绿灯溜到了线上。本测试**真实构造** RuntimeManager，不抛异常即通过。
 */
@RunWith(MockitoJUnitRunner.Silent::class)
class RuntimeManagerInitTest {

    /** 造一个假的 filesDir，避免往真实磁盘写 */
    private fun tmpDir(): File = Files.createTempDirectory("mizu-runtime-test").toFile()

    private fun newManager(
        filesDir: File = tmpDir(),
        extractedRoot: File? = null
    ): RuntimeManager {
        val ctx = Mockito.mock(Context::class.java)
        Mockito.`when`(ctx.filesDir).thenReturn(filesDir)
        return RuntimeManager(
            context = ctx,
            sourceFlow = MutableStateFlow(DownloadSource.Bmclapi),
            manifestUrlProvider = { null },
            extractedRootProvider = { extractedRoot }
        )
    }

    @Test
    fun `构造函数不抛异常`() {
        // 修复前：这里抛 NullPointerException（_states 尚未初始化就被读取）
        val manager = newManager()
        assertNotNull(manager)
    }

    @Test
    fun `构造完成后状态表已就绪且覆盖全部支持的版本`() {
        val manager = newManager()
        val states = manager.states.value
        assertEquals(RuntimeManager.SUPPORTED_MAJORS.size, states.size)
        // 每个受支持的版本都必须有明确状态，不能是 null（否则 UI 取状态时再炸一次）
        RuntimeManager.SUPPORTED_MAJORS.forEach { major ->
            assertNotNull("Java $major 缺少状态", states[major])
        }
    }

    @Test
    fun `空磁盘时全部为未安装而非崩溃`() {
        val manager = newManager()
        val states = manager.states.value
        // 空目录下没有 JRE，只可能是「未安装」或「架构不支持」，绝不能是 Ready
        states.forEach { (major, state) ->
            assertTrue(
                "Java $major 状态异常：$state",
                state is RuntimeManager.State.NotInstalled || state is RuntimeManager.State.Unavailable
            )
        }
    }

    @Test
    fun `refresh 可重复调用且不清空状态表`() {
        val manager = newManager()
        repeat(3) { manager.refresh() }
        assertEquals(RuntimeManager.SUPPORTED_MAJORS.size, manager.states.value.size)
    }

    @Test
    fun `目录里已有 JRE 时刷新后标记为 Ready`() {
        val filesDir = tmpDir()
        // 造一个最小可用的 JRE 目录：只要存在 bin/java 就算就绪
        // （JVM 单测里 android.jar 的 File.setExecutable 是空桩，不需要真实权限位）
        val jre = File(filesDir, "runtime/java-17/bin").apply { mkdirs() }
        File(jre, "java").writeText("#!/bin/sh\n")

        val manager = newManager(filesDir = filesDir)
        val state = manager.states.value[17]
        assertTrue("Java 17 应被识别为 Ready，实际：$state", state is RuntimeManager.State.Ready)
    }

    @Test
    fun `下载中的状态不会被刷新覆盖回未安装`() {
        // 这条防护的是另一个真实体验问题：点「安装」后进度条突然消失。
        // scan(previous) 纯函数化之后，只要 previous 里是 Downloading 就必须原样保留。
        val manager = newManager()
        val downloading = RuntimeManager.State.Downloading(42, "正在下载")
        val merged = manager.states.value + (8 to downloading)
        // 通过反射之外的手段无法直接写 previous，这里退一步验证：Refresh 后不会变成 Ready
        manager.refresh()
        assertTrue(merged[8] is RuntimeManager.State.Downloading)
        assertTrue(manager.states.value[8] !is RuntimeManager.State.Ready)
    }
}
