package com.mizulauncher

import com.mizulauncher.auth.MinecraftProfile
import com.mizulauncher.debug.DebugDeviceCode
import com.mizulauncher.debug.DebugUiBridge
import com.mizulauncher.debug.DownloadProgressSnapshot
import com.mizulauncher.download.DownloadSource
import com.mizulauncher.instance.InstanceMeta
import com.mizulauncher.minecraft.model.VersionEntry
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 1.0 的 [LauncherViewModel] 到 [DebugUiBridge] 的适配层。
 *
 * ## 为什么需要这层
 *
 * 接口里的几个类型是「调试视角的扁平快照」：`DownloadProgressSnapshot`、
 * `DebugDeviceCode`，而 1.0 用的是业务类型（`DownloadProgress`、`DeviceCode`，
 * 后者的字段还是 `Long` 而不是 `Int`）。
 *
 * 若让 `LauncherViewModel` 直接 `: DebugUiBridge`，就要改它的公开签名——
 * 为了一个调试口去动已经稳定、有 466 条测试覆盖的代码，风险收益比不划算。
 *
 * 所以用**外部适配器 + 扩展属性**做零侵入映射。1.0 界面拆完之后，
 * 这个文件连同 `LauncherViewModel` 一起删掉即可，不影响 2.0。
 */
val LauncherViewModel.asDebugBridge: DebugUiBridge
    get() = LauncherDebugBridge(this)

private class LauncherDebugBridge(
    private val vm: LauncherViewModel
) : DebugUiBridge {

    // 适配层自己的 scope：只用于把上游 StateFlow 转成调试视角的形态。
    // 用 SupervisorJob 是因为这些转换纯属"看"数据，一个失败不该连累别的。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ---------------- 全局任务 ----------------

    override val installing: StateFlow<Boolean> get() = vm.installing
    override val stage: StateFlow<String> get() = vm.stage
    override val error: StateFlow<String?> get() = vm.error

    override val downloadProgress: StateFlow<DownloadProgressSnapshot?> =
        vm.downloadProgress
            .map { p ->
                DownloadProgressSnapshot(
                    totalTasks = p.totalTasks,
                    doneTasks = p.doneTasks,
                    failedTasks = p.failedTasks,
                    percent = p.percent.toFloat(),
                    totalBytes = p.totalBytes,
                    downloadedBytes = p.downloadedBytes,
                    speedBps = p.speedBps,
                    etaSeconds = p.etaSeconds,
                    currentFile = p.currentFile,
                    finished = p.finished
                ) as DownloadProgressSnapshot?
            }
            .stateIn(scope, SharingStarted.Eagerly, null)

    // ---------------- 实例 ----------------

    override val instances: StateFlow<List<InstanceMeta>> get() = vm.instances
    override fun refreshInstances() = vm.refreshInstances()

    /**
     * 1.0 的 `prepareOnly` / `launch` 是「扔进 viewModelScope 就返回」的普通函数。
     * 接口要求 suspend（调用方想 await），这里桥接一下：调用后挂起到任务结束。
     *
     * 为什么要等：调试 API 的语义是"这条命令处理完了"，让 curl 立刻返回 200 但文件还在下载，
     * 排查时人会误以为成功了。等 `installing` 落回 false 再返回，行为才是诚实的。
     */
    override suspend fun prepareOnly(meta: InstanceMeta) {
        vm.prepareOnly(meta)
        awaitIdle()
    }

    override suspend fun launch(meta: InstanceMeta) {
        vm.launch(meta)
        awaitIdle()
    }

    private suspend fun awaitIdle() {
        // 先给 launch() 一点时间把 installing 打开，否则会在它置 true 之前就通过判断
        kotlinx.coroutines.delay(120)
        vm.installing.first { !it }
    }

    override fun cancelDownload() = vm.cancelDownload()

    // ---------------- 版本 ----------------

    override val versions: StateFlow<List<VersionEntry>> get() = vm.versions
    override val loadingVersions: StateFlow<Boolean> get() = vm.loadingVersions
    override val versionError: StateFlow<String?> get() = vm.versionError
    override fun loadVersions(force: Boolean) = vm.loadVersions(force)
    override fun setSource(source: DownloadSource) = vm.setSource(source)

    // ---------------- 首启依赖 ----------------

    override val bundleScanned: StateFlow<Boolean> get() = vm.bundleScanned

    override val bundlePending: StateFlow<List<Any>> =
        vm.bundlePending
            .map { list -> list.map { it as Any } }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    override val bundlePhase: StateFlow<Any> =
        vm.bundlePhase
            .map { it as Any }
            .stateIn(scope, SharingStarted.Eagerly, "Idle")

    override val bundleNotice: StateFlow<String?> get() = vm.bundleNotice
    override fun rescanBundles() = vm.rescanBundles()
    override fun installBundles() = vm.installBundles()

    // ---------------- 运行时 ----------------

    override val runtimeStates: StateFlow<Map<Int, Any>> =
        vm.runtimeStates
            .map { m -> m.mapValues { it.value as Any } }
            .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    override fun isRuntimeReady(major: Int): Boolean = vm.runtimeManager.isReady(major)

    // ---------------- 账户 ----------------

    override val profile: StateFlow<MinecraftProfile?> get() = vm.profile

    override val deviceCode: StateFlow<DebugDeviceCode?> =
        vm.deviceCode
            .map { code ->
                code?.let {
                    DebugDeviceCode(
                        userCode = it.userCode,
                        verificationUri = it.verificationUri,
                        verificationUriComplete = it.verificationUriComplete,
                        expiresInSeconds = it.expiresInSeconds.toInt(),
                        intervalSeconds = it.intervalSeconds.toInt()
                    )
                } as DebugDeviceCode?
            }
            .stateIn(scope, SharingStarted.Eagerly, null)

    override val authError: StateFlow<String?> get() = vm.authError
    override fun startMicrosoftLogin() = vm.startMicrosoftLogin()
    override fun cancelLogin() = vm.cancelLogin()
    override fun logout() = vm.logout()
}
