package com.mizulauncher.debug

import com.mizulauncher.auth.MinecraftProfile
import com.mizulauncher.download.DownloadSource
import com.mizulauncher.instance.InstanceMeta
import com.mizulauncher.minecraft.model.VersionEntry
import kotlinx.coroutines.flow.StateFlow

/**
 * 调试 API 需要的那部分「界面侧状态」。
 *
 * ## 为什么抽这个接口
 *
 * 1.0 的 facade 直接写 `LauncherViewModel`，结果 2.0 换了 ViewModel 之后，
 * 调试 API 一行没动却全线编译失败。根本原因是**它依赖的是具体类，而不是能力**。
 *
 * 现在两边都只依赖这个接口：
 * ```
 *   MizuViewModel ──实现──► DebugUiBridge ◄──依赖── AppLauncherFacade
 *   LauncherViewModel ──实现──►  (1.0 保留，走兼容层)
 * ```
 * 以后再加第三套 UI，只需要再实现一次这个接口，调试 API 不用碰。
 *
 * ## 为什么用 StateFlow 而不是直接读快照
 *
 * 调试接口的语义是「用户此刻在手机上看到什么」。用 StateFlow 可以既拿当前值（`.value`），
 * 也能在需要时挂起等待变化（first { ... }），比轮询字段灵活。
 * 实现方若没有对应概念，返回一个恒定的 MutableStateFlow 即可。
 */
interface DebugUiBridge {

    // ---------------- 全局任务 ----------------

    val installing: StateFlow<Boolean>
    val stage: StateFlow<String>
    val error: StateFlow<String?>

    val downloadProgress: StateFlow<DownloadProgressSnapshot?>

    // ---------------- 实例 ----------------

    val instances: StateFlow<List<InstanceMeta>>
    fun refreshInstances()

    /** 只准备文件、不启动 */
    suspend fun prepareOnly(meta: InstanceMeta)

    /** 准备 + 启动 */
    suspend fun launch(meta: InstanceMeta)

    fun cancelDownload()

    // ---------------- 版本 ----------------

    val versions: StateFlow<List<VersionEntry>>
    val loadingVersions: StateFlow<Boolean>
    val versionError: StateFlow<String?>
    fun loadVersions(force: Boolean)

    fun setSource(source: DownloadSource)

    // ---------------- 首启依赖 ----------------

    val bundleScanned: StateFlow<Boolean>
    val bundlePending: StateFlow<List<Any>>
    val bundlePhase: StateFlow<Any>
    val bundleNotice: StateFlow<String?>
    fun rescanBundles()
    fun installBundles()

    // ---------------- 运行时 ----------------

    val runtimeStates: StateFlow<Map<Int, Any>>
    fun isRuntimeReady(major: Int): Boolean

    // ---------------- 账户 ----------------

    val profile: StateFlow<MinecraftProfile?>
    val deviceCode: StateFlow<DebugDeviceCode?>
    val authError: StateFlow<String?>
    fun startMicrosoftLogin()
    fun cancelLogin()
    fun logout()
}

/** 下载进度的扁平快照（调试 API 只读，不参与业务） */
data class DownloadProgressSnapshot(
    val totalTasks: Int,
    val doneTasks: Int,
    val failedTasks: Int,
    val percent: Float,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val speedBps: Long,
    val etaSeconds: Long,
    val currentFile: String,
    val finished: Boolean
)

/** 微软设备码登录中要给用户看的东西 */
data class DebugDeviceCode(
    val userCode: String,
    val verificationUri: String,
    val verificationUriComplete: String = verificationUri,
    val expiresInSeconds: Int = 900,
    val intervalSeconds: Int = 5
)
