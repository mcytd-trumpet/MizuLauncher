package com.mizulauncher

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mizulauncher.debug.DebugApiHost
import com.mizulauncher.auth.DeviceCode
import com.mizulauncher.auth.MicrosoftAuth
import com.mizulauncher.auth.MinecraftProfile
import com.mizulauncher.auth.TokenPoll
import com.mizulauncher.bridge.LaunchOptions
import com.mizulauncher.bridge.LaunchResult
import com.mizulauncher.bridge.Renderer
import java.io.File
import com.mizulauncher.update.ApkDownloader
import com.mizulauncher.update.UpdateChecker
import com.mizulauncher.download.DownloadProgress
import com.mizulauncher.download.DownloadSource
import com.mizulauncher.instance.InstanceMeta
import com.mizulauncher.minecraft.model.VersionEntry
import com.mizulauncher.runtime.RuntimeManager
import com.mizulauncher.setup.BundleCatalog
import com.mizulauncher.setup.BundleInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private val auth = MicrosoftAuth()
    /** 内置组件解压器（首启「补全依赖」） */
    val bundleInstaller: BundleInstaller = BundleInstaller(app)

    val runtimeManager: RuntimeManager = RuntimeManager(
        context = app,
        sourceFlow = container.sourceFlow,
        manifestUrlProvider = { container.settings.runtimeManifestUrl },
        // 首启解压出来的 JRE 直接给运行时管理器用，避免同一份再解一遍
        extractedRootProvider = { java.io.File(bundleInstaller.installRoot, "runtime") }
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

    /** 实例占用空间（字节）；统计要遍历目录，放到 IO 线程算 */
    private val _instanceSizes = MutableStateFlow<Map<String, Long>>(emptyMap())
    val instanceSizes: StateFlow<Map<String, Long>> = _instanceSizes.asStateFlow()

    fun measureInstance(meta: InstanceMeta) {
        if (_instanceSizes.value.containsKey(meta.id)) return
        viewModelScope.launch(Dispatchers.IO) {
            val size = runCatching { container.instanceManager.sizeOf(meta) }.getOrDefault(0L)
            _instanceSizes.value = _instanceSizes.value + (meta.id to size)
        }
    }

    /** 重命名实例。空白名直接忽略，避免主页出现一张没有标题的卡片 */
    fun renameInstance(meta: InstanceMeta, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty() || trimmed == meta.name) return
        runCatching {
            container.instanceManager.update(meta.copy(name = trimmed))
            refreshInstances()
        }.onFailure { _error.value = "重命名失败：${it.message}" }
    }

    /**
     * 删除实例。
     *
     * 默认连文件一起删——PCL2 也是这个行为，但必须让用户确认，
     * 因为一个实例的 libraries + assets 动辄几个 GB，删错了没法恢复。
     */
    fun deleteInstance(meta: InstanceMeta) {
        runCatching {
            container.instanceManager.delete(meta.id, deleteFiles = true)
            _instanceSizes.value = _instanceSizes.value - meta.id
            refreshInstances()
            _bundleNotice.value = "已删除实例「${meta.name.ifBlank { meta.versionId }}」"
        }.onFailure { _error.value = "删除失败：${it.message}" }
    }

    /** 实例的游戏目录（用于「打开文件夹」） */
    fun gameDirOf(meta: InstanceMeta): File = container.instanceManager.gameDirOf(meta)

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
        // 让调试 API 拿到同一个 ViewModel 实例：UI 和 API 走同一条控制链路，
        // 否则会出现「两边都能触发安装、互相打架」的情况。
        DebugApiHost.attach(this)
    }

    // ---------------- 内置组件补全（首启 / 更新后） ----------------

    val bundleItems: StateFlow<List<BundleCatalog.Item>> = bundleInstaller.items
    val bundlePending: StateFlow<List<BundleCatalog.Item>> = bundleInstaller.pending
    val bundleStates: StateFlow<Map<String, BundleInstaller.ItemState>> = bundleInstaller.states
    val bundlePhase: StateFlow<BundleInstaller.Phase> = bundleInstaller.phase
    val bundleScanned: StateFlow<Boolean> = bundleInstaller.scanned

    private val _setupDismissed = MutableStateFlow(false)

    /** 用户选了「稍后」——本次启动不再弹，但启动游戏前仍会拦一次 */
    val setupDismissed: StateFlow<Boolean> = _setupDismissed.asStateFlow()

    fun dismissSetup() {
        _setupDismissed.value = true
    }

    /** 重新扫描内置组件（升级后 / 清过数据后） */
    fun rescanBundles() {
        viewModelScope.launch(Dispatchers.IO) {
            bundleInstaller.scan()
            runtimeManager.refresh()
        }
    }

    /** 开始解压待安装组件；完成后刷新运行时状态 */
    fun installBundles() {
        viewModelScope.launch {
            val ok = bundleInstaller.installAll()
            runtimeManager.refresh()
            bundleInstaller.clearIndexCache()
            if (ok) {
                _setupDismissed.value = false
                _bundleNotice.value = "依赖已补全"
            } else {
                val phase = bundleInstaller.phase.value
                _bundleNotice.value = (phase as? BundleInstaller.Phase.Failed)?.message ?: "依赖安装失败"
            }
        }
    }

    private val _bundleNotice = MutableStateFlow<String?>(null)

    /** 一次性的提示文案（装完了 / 装失败），UI 弹个 toast 后清掉 */
    val bundleNotice: StateFlow<String?> = _bundleNotice.asStateFlow()

    fun consumeBundleNotice() {
        _bundleNotice.value = null
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

    /**
     * 启动选中实例：先补运行时 → 再准备文件 → 最后拉起内核。
     *
     * 启动前多一道内置组件检查：JRE 还没解压就开游戏，只会得到一句
     * `Cannot run program "java"`，用户根本不知道要先去补依赖。
     */
    fun launch(meta: InstanceMeta) {
        if (_installing.value) return
        if (!bundleInstaller.scanned.value) {
            // 首启扫描还没结束，先别放行，否则会绕过依赖检查
            _bundleNotice.value = "正在检查运行依赖，请稍候…"
            return
        }
        if (bundleInstaller.pending.value.isNotEmpty()) {
            // 把补全弹窗重新叫出来（用户可能点过「稍后」）
            _setupDismissed.value = false
            _bundleNotice.value = "请先补全运行依赖，再启动游戏"
            return
        }
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

    /**
     * 只准备文件，不启动游戏。
     *
     * 用途：调试 API 的 `POST /instances/{id}/prepare`，以及以后做「预下载」按钮。
     * 和 [launch] 共享同一套前置检查（依赖补全、Java 运行时），保证两条路径行为一致——
     * 否则会出现「UI 点启动不让过、API 却能过」这种自相矛盾。
     */
    fun prepareOnly(meta: InstanceMeta) {
        if (_installing.value) return
        if (!bundleInstaller.scanned.value) {
            _bundleNotice.value = "正在检查运行依赖，请稍候…"
            return
        }
        if (bundleInstaller.pending.value.isNotEmpty()) {
            _setupDismissed.value = false
            _bundleNotice.value = "请先补全运行依赖，再准备游戏文件"
            return
        }
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
                .onSuccess {
                    refreshInstances()
                    _stage.value = "准备完成"
                    _installDone.value = true
                }
                .onFailure { _error.value = it.message ?: "准备文件失败" }
        }
    }

    // ---------------- 账户 ----------------

    private val _profile = MutableStateFlow(container.accountSession.current())
    val profile: StateFlow<MinecraftProfile?> = _profile.asStateFlow()

    private val _deviceCode = MutableStateFlow<DeviceCode?>(null)
    val deviceCode: StateFlow<DeviceCode?> = _deviceCode.asStateFlow()

    private val _authBusy = MutableStateFlow(false)
    val authBusy: StateFlow<Boolean> = _authBusy.asStateFlow()

    private val _authStatus = MutableStateFlow<String?>(null)
    val authStatus: StateFlow<String?> = _authStatus.asStateFlow()

    private val _authError = MutableStateFlow<String?>(null)
    val authError: StateFlow<String?> = _authError.asStateFlow()

    private var authJob: Job? = null

    /**
     * 启动微软登录。
     *
     * 先拿设备码展示给用户，再按服务端给的 interval 轮询。
     * `slow_down` 时把间隔加大一点点，这是 RFC 8628 的要求，
     * 忽略它会被服务端持续拒绝。
     */
    fun startMicrosoftLogin() {
        if (_authBusy.value) return
        authJob?.cancel()
        authJob = viewModelScope.launch {
            _authBusy.value = true
            _authError.value = null
            _authStatus.value = "正在获取验证码"

            val code = runCatching { auth.requestDeviceCode() }
                .getOrElse {
                    _authBusy.value = false
                    _authStatus.value = null
                    _authError.value = "获取验证码失败：${it.message}"
                    return@launch
                }
            _deviceCode.value = code
            _authStatus.value = "等待你完成登录…"

            var interval = code.intervalSeconds.coerceAtLeast(1L)
            val deadline = System.currentTimeMillis() + code.expiresInSeconds * 1000
            while (System.currentTimeMillis() < deadline) {
                delay(interval * 1000)
                when (val poll = runCatching { auth.pollDeviceToken(code.deviceCode) }
                    .getOrElse { TokenPoll.Failed(it.message ?: "网络错误") }) {
                    TokenPoll.Pending -> _authStatus.value = "等待你完成登录…"
                    TokenPoll.SlowDown -> interval += 2
                    TokenPoll.Expired -> {
                        finishAuth("验证码已过期，请重新登录")
                        return@launch
                    }
                    TokenPoll.Declined -> {
                        finishAuth("你在浏览器中拒绝了授权")
                        return@launch
                    }
                    is TokenPoll.Failed -> {
                        finishAuth(poll.message)
                        return@launch
                    }
                    is TokenPoll.Done -> {
                        _authStatus.value = "登录成功，正在验证正版…"
                        runCatching { auth.loginWithXbox(poll.token) }
                            .onSuccess { p ->
                                container.accountSession.save(p)
                                _profile.value = p
                                settings.lastAccount = p.name
                                finishAuth(null)
                            }
                            .onFailure {
                                finishAuth(it.message ?: "正版验证失败")
                            }
                        return@launch
                    }
                }
            }
            finishAuth("验证码已过期，请重新登录")
        }
    }

    private fun finishAuth(message: String?) {
        _deviceCode.value = null
        _authBusy.value = false
        _authStatus.value = null
        if (message != null) _authError.value = message
    }

    fun cancelLogin() {
        authJob?.cancel()
        _deviceCode.value = null
        _authBusy.value = false
        _authStatus.value = null
    }

    fun logout() {
        container.accountSession.logout()
        _profile.value = null
        _authError.value = null
    }

    fun setOfflineName(name: String) {
        settings.lastAccount = name
    }

    /** 启动时顺手续期：令牌快过期就静默刷新，失败就给个提示但不打扰 */
    fun refreshAccountIfNeeded() {
        viewModelScope.launch {
            val valid = withContext(Dispatchers.IO) { container.accountSession.valid() }
            _profile.value = valid
        }
    }


    // ---------------- 应用更新 ----------------

    private val updateChecker = UpdateChecker(http = okhttp3.OkHttpClient.Builder().build())
    private val apkDownloader = ApkDownloader()
    private val releaseCache = File(application.cacheDir, "update-release.json")

    private val _update = MutableStateFlow<UpdateChecker.UpdateInfo?>(null)
    val update: StateFlow<UpdateChecker.UpdateInfo?> = _update.asStateFlow()

    private val _updateChecking = MutableStateFlow(false)
    val updateChecking: StateFlow<Boolean> = _updateChecking.asStateFlow()

    /**
     * 是否正在下载安装包。
     *
     * 独立成一个布尔值，而不是让 UI 去比对状态文案。
     * 之前用 `updateStatus == "正在下载…"` 判断，文案一改按钮就失灵——
     * 这种「靠字符串传递状态」的写法是纯粹的定时炸弹。
     */
    private val _updateDownloading = MutableStateFlow(false)
    val updateDownloading: StateFlow<Boolean> = _updateDownloading.asStateFlow()

    private val _updateProgress = MutableStateFlow(0)
    val updateProgress: StateFlow<Int> = _updateProgress.asStateFlow()

    private val _updateStatus = MutableStateFlow<String?>(null)
    val updateStatus: StateFlow<String?> = _updateStatus.asStateFlow()

    /** 一次性的提示（「已是最新版本」这类），UI 用 Snackbar/Toast 消费后清掉 */
    private val _updateToast = MutableStateFlow<String?>(null)
    val updateToast: StateFlow<String?> = _updateToast.asStateFlow()

    fun consumeUpdateToast() {
        _updateToast.value = null
    }

    private val _downloadedApk = MutableStateFlow<java.io.File?>(null)
    val downloadedApk: StateFlow<java.io.File?> = _downloadedApk.asStateFlow()

    private var updateJob: Job? = null

    /** 上次成功查到的 Release JSON，没网时用来兜底 */
    private var cachedReleaseJson: String? = null
    /** 本机已下载的资源名（复用判断用） */
    private var downloadedAssetName: String? = null

    /**
     * 检查更新。
     *
     * 三件事容易出错，这里都做了兜底：
     *  1. **没网/接口挂了**：如果之前查到过新版，就用手上的缓存继续提示，
     *     而不是让用户点「检查更新」得到一个「检查失败」然后什么也做不了；
     *  2. **versionCode 比对**：取 Release 资源名里的数字（打包时 Gradle 用的同一个值），
     *     比 tag 字符串可靠得多；
     *  3. **架构匹配**：只给本机能装的包，匹配不到就明说，不装错包。
     */
    fun checkForUpdate(manual: Boolean = false) {
        if (_updateChecking.value) return
        viewModelScope.launch {
            _updateChecking.value = true
            if (manual) _updateStatus.value = "正在检查更新…"

            val fetched = withContext(Dispatchers.IO) { updateChecker.fetchLatestReleaseJsonPublic() }
            if (fetched != null) {
                cachedReleaseJson = fetched
                withContext(Dispatchers.IO) {
                    runCatching { releaseCache.writeText(fetched) }
                }
            } else if (cachedReleaseJson == null) {
                // 冷启动 + 没网：读上次落盘的那份，让弹窗仍能出现
                cachedReleaseJson = withContext(Dispatchers.IO) {
                    runCatching { releaseCache.takeIf { it.isFile }?.readText() }.getOrNull()
                }
            }

            val json = cachedReleaseJson
            if (json == null) {
                _updateChecking.value = false
                if (manual) _updateToast.value = "检查更新失败：网络不可用"
                return@launch
            }

            val info = runCatching {
                updateChecker.parseRelease(
                    releaseJson = json,
                    currentVersionCode = BuildConfig.VERSION_CODE,
                    currentVersionName = BuildConfig.VERSION_NAME,
                    deviceAbis = android.os.Build.SUPPORTED_ABIS.toList(),
                    cachedAssetName = downloadedAssetName
                )
            }.getOrNull()
            _updateChecking.value = false

            // 手动检查时必须给反馈，自动检查只在有新版本时打扰
            val silent = !manual
            if (info == null) {
                _update.value = null
                _updateStatus.value = null
                if (!silent) _updateToast.value = "已是最新版本 ${BuildConfig.VERSION_NAME}"
                return@launch
            }

            _update.value = info
            if (info.asset == null) {
                _updateStatus.value =
                    "这个版本没有适配本机（${info.deviceAbi}）的安装包，请到 Release 页面手动选择"
            } else if (info.reuseCached) {
                // 同版本的包已经下过了，直接给「立即安装」，别再让用户白等一次
                val file = apkFileOf(info)
                if (file.isFile && file.length() > 0) {
                    _downloadedApk.value = file
                    _updateStatus.value = "安装包已就绪"
                }
            }
        }
    }

    /** 下载更新包，完成后 UI 调 [installApk] 拉起系统安装器 */
    fun downloadUpdate() {
        val info = _update.value ?: return
        val asset = info.asset ?: return
        if (_updateDownloading.value) return
        updateJob?.cancel()
        updateJob = viewModelScope.launch {
            _updateDownloading.value = true
            _updateProgress.value = 0
            _updateStatus.value = null
            val target = apkFileOf(info)
            // 换了版本就把旧包清掉，免得缓存里堆一堆几十兆的无用文件
            runCatching {
                target.parentFile?.listFiles()?.filter { it.name != target.name }?.forEach { it.delete() }
            }

            val result = apkDownloader.download(asset.url, target) { p ->
                _updateProgress.value = p.percent
            }

            _updateDownloading.value = false
            result.onSuccess { file ->
                // 长度为 0 说明响应体是空的，装上去必然解析失败
                if (file.length() <= 0) {
                    _updateStatus.value = "下载失败：安装包为空"
                    _updateProgress.value = 0
                    return@onSuccess
                }
                downloadedAssetName = asset.name
                _downloadedApk.value = file
                _updateProgress.value = 100
                _updateStatus.value = "下载完成，可以安装了"
            }.onFailure {
                _updateStatus.value = "下载失败：${it.message ?: "网络中断"}"
                _updateProgress.value = 0
            }
        }
    }

    fun cancelUpdateDownload() {
        updateJob?.cancel()
        _updateDownloading.value = false
        _updateProgress.value = 0
        _updateStatus.value = null
    }

    /** 关闭更新弹窗。已下载的包**保留**，下次检查到同一版本还能直接装 */
    fun dismissUpdate() {
        _update.value = null
        _updateStatus.value = null
        _updateProgress.value = 0
        // 注意：不复位 _downloadedApk，否则用户关掉弹窗再打开就得重下
    }

    /** 安装包落盘路径：文件名带版本号与架构，便于断点续传与复用判断 */
    private fun apkFileOf(info: UpdateChecker.UpdateInfo): java.io.File {
        val dir = java.io.File(application.cacheDir, "updates").apply { mkdirs() }
        val name = info.asset?.name ?: "MizuLauncher-${info.versionName}.apk"
        return java.io.File(dir, name)
    }

    fun cancelDownload() {
        orchestrator.downloadEngine.cancel()
        _installing.value = false
    }
}
