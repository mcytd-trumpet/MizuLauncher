package com.mizulauncher

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mizulauncher.bridge.LaunchOptions
import com.mizulauncher.bridge.LaunchResult
import com.mizulauncher.bridge.Renderer
import com.mizulauncher.download.DownloadProgress
import com.mizulauncher.download.DownloadSource
import com.mizulauncher.instance.InstanceMeta
import com.mizulauncher.minecraft.model.VersionEntry
import com.mizulauncher.runtime.RuntimeManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 全局状态容器。
 *
 * ## 为什么必须放在 ViewModel
 * 之前把状态写在 `@Composable` 里用 `remember` 保存，导致 `remember { instanceManager.list() }`
 * 只在首次组合时求值一次。装完新版本后列表不会更新，用户必须切到别的页面再切回来触发重建
 * —— 这是一个真实的 bug，不是错觉。
 *
 * 现在所有可变状态都放这里：
 *  - 存活周期跟随 ViewModel（横竖屏切换不丢）；
 *  - 数据流用 [StateFlow]，UI 用 `collectAsStateWithLifecycle()` 订阅，改了立刻反映；
 *  - 每个写操作结束时**主动** [refreshInstances]，不依赖组合时机。
 */
class LauncherViewModel(app: Application) : AndroidViewModel(app) {

    private val application = app as MizuApplication
    private val container = application.container
    val settings: SettingsStore get() = container.settings

    val orchestrator = LaunchOrchestrator(app)
    val runtimeManager: RuntimeManager = RuntimeManager(
        context = app,
        sourceFlow = container.sourceFlow,
        manifestUrlProvider = { container.settings.runtimeManifestUrl }
    )

    // ---------------- 实例 ----------------

    private val _instances = MutableStateFlow<List<InstanceMeta>>(emptyList())
    val instances: StateFlow<List<InstanceMeta>> = _instances.asStateFlow()

    private val _selectedId = MutableStateFlow<String?>(null)
    val selectedId: StateFlow<String?> = _selectedId.asStateFlow()

    /** 从磁盘重新读取实例列表。任何「会改动实例」的操作结束后都要调 */
    fun refreshInstances() {
        val list = container.instanceManager.list()
        _instances.value = list
        // 选中的实例被删掉时，自动选第一个
        if (list.none { it.id == _selectedId.value }) {
            _selectedId.value = list.firstOrNull()?.id
        }
    }

    fun selectInstance(id: String?) {
        _selectedId.value = id
    }

    val selectedInstance: InstanceMeta?
        get() = _instances.value.firstOrNull { it.id == _selectedId.value }

    // ---------------- 版本清单 ----------------

    private val _versions = MutableStateFlow<List<VersionEntry>>(emptyList())
    val versions: StateFlow<List<VersionEntry>> = _versions.asStateFlow()

    private val _loadingVersions = MutableStateFlow(false)
    val loadingVersions: StateFlow<Boolean> = _loadingVersions.asStateFlow()

    private val _versionError = MutableStateFlow<String?>(null)
    val versionError: StateFlow<String?> = _versionError.asStateFlow()

    /** 拉取版本列表。已在加载或已有数据时不会重复请求（除非 force） */
    fun loadVersions(force: Boolean = false) {
        if (_loadingVersions.value) return
        if (!force && _versions.value.isNotEmpty()) return
        viewModelScope.launch {
            _loadingVersions.value = true
            _versionError.value = null
            orchestrator.repository.loadManifest()
                .onSuccess { _versions.value = it.versions }
                .onFailure { _versionError.value = it.message ?: "获取版本列表失败" }
            _loadingVersions.value = false
        }
    }

    fun setSource(source: DownloadSource) {
        if (settings.downloadSource == source) return
        settings.downloadSource = source
        container.sourceFlow.value = source
        orchestrator.setSource(source)
        // 换源后清单必须重取，否则还是旧镜像的数据
        _versions.value = emptyList()
        loadVersions(force = true)
    }

    // ---------------- 下载进度 ----------------

    val downloadProgress: StateFlow<DownloadProgress> = orchestrator.downloadEngine.progress

    private val _installing = MutableStateFlow(false)
    val installing: StateFlow<Boolean> = _installing.asStateFlow()

    /** 安装成功后置为 true，UI 消费后应由 [consumeInstallDone] 复位 */
    private val _installDone = MutableStateFlow(false)
    val installDone: StateFlow<Boolean> = _installDone.asStateFlow()

    fun consumeInstallDone() {
        _installDone.value = false
    }

    private val _stage = MutableStateFlow("")
    val stage: StateFlow<String> = _stage.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun clearError() {
        _error.value = null
    }

    // ---------------- 运行时 ----------------

    val runtimeStates: StateFlow<Map<Int, RuntimeManager.State>> = runtimeManager.states

    init {
        refreshInstances()
        runtimeManager.refresh()
    }

    /** 下载/解压某个 Java 运行时 */
    fun installRuntime(major: Int) {
        viewModelScope.launch {
            runtimeManager.ensure(major).onFailure {
                _error.value = it.message ?: "Java $major 安装失败"
            }
        }
    }

    fun removeRuntime(major: Int) {
        runtimeManager.remove(major)
    }

    fun refreshRuntimes() {
        runtimeManager.refresh()
    }

    // ---------------- 安装 & 启动 ----------------

    /** 从下载页安装一个版本（可选加载器），成功后自动刷新实例列表 */
    fun installVersion(entry: VersionEntry, loader: String, loaderVersion: String, customName: String) {
        if (_installing.value) return
        viewModelScope.launch {
            _installing.value = true
            _stage.value = "准备安装"
            val meta = InstanceMeta(
                name = customName.ifBlank {
                    if (loader == "vanilla" || loader.isBlank()) entry.id
                    else "${entry.id}-$loader"
                },
                versionId = entry.id,
                loaderType = loader.ifBlank { "vanilla" },
                loaderVersion = loaderVersion
            )
            val result = orchestrator.prepare(meta) { s -> _stage.value = s }
            _installing.value = false
            result
                .onSuccess {
                    // 先刷列表再切页面，避免主页拿到旧数据
                    refreshInstances()
                    _selectedId.value = meta.id
                    runtimeManager.refresh()
                    _installDone.value = true
                }
                .onFailure { _error.value = it.message ?: "安装失败" }
        }
    }

    /** 启动选中实例：先补运行时 → 再准备文件 → 最后拉起内核 */
    fun launch(meta: InstanceMeta) {
        if (_installing.value) return
        viewModelScope.launch {
            _installing.value = true

            _stage.value = "检查 Java 运行时"
            val major = if (meta.javaMajor > 0) meta.javaMajor else runtimeManager.requiredMajorFor(meta.versionId)
            val runtime = runtimeManager.ensure(major)
            if (runtime.isFailure) {
                _installing.value = false
                _error.value = runtime.exceptionOrNull()?.message ?: "Java $major 不可用"
                return@launch
            }

            _stage.value = "检查游戏文件"
            val prepared = orchestrator.prepare(meta) { s -> _stage.value = s }
            _installing.value = false

            prepared
                .onSuccess { request ->
                    refreshInstances()
                    val result = orchestrator.launch(
                        request,
                        LaunchOptions(
                            renderer = runCatching { Renderer.valueOf(settings.renderer) }
                                .getOrDefault(Renderer.VIRGL),
                            memoryMb = settings.maxMemoryMb
                        )
                    )
                    if (result is LaunchResult.Failed) _error.value = result.reason
                }
                .onFailure { _error.value = it.message ?: "准备启动文件失败" }
        }
    }

    fun cancelDownload() {
        orchestrator.downloadEngine.cancel()
        _installing.value = false
    }
}
