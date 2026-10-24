package com.mizulauncher.v2

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mizulauncher.MizuApplication
import com.mizulauncher.auth.MicrosoftAuth
import com.mizulauncher.auth.MinecraftProfile
import com.mizulauncher.auth.yggdrasil.AccountStore
import com.mizulauncher.auth.yggdrasil.CodedException
import com.mizulauncher.auth.yggdrasil.YggdrasilClient
import com.mizulauncher.auth.yggdrasil.YggdrasilSpec
import com.mizulauncher.bridge.LaunchOptions
import com.mizulauncher.bridge.LaunchResult
import com.mizulauncher.compat.AuthlibInjector
import com.mizulauncher.compat.DeviceProfile
import com.mizulauncher.compat.DeviceProfiles
import com.mizulauncher.debug.DebugApiHost
import com.mizulauncher.debug.DebugDeviceCode
import com.mizulauncher.debug.DebugUiBridge
import com.mizulauncher.debug.DownloadProgressSnapshot
import com.mizulauncher.download.DownloadSource
import com.mizulauncher.instance.InstanceMeta
import com.mizulauncher.minecraft.model.VersionEntry
import com.mizulauncher.runtime.RuntimeManager
import com.mizulauncher.skin.SkinLoader
import com.mizulauncher.skin.TextureState
import com.mizulauncher.v2.state.AccountState
import com.mizulauncher.v2.state.DarkMode
import com.mizulauncher.v2.state.HomeState
import com.mizulauncher.v2.state.InstanceCard
import com.mizulauncher.v2.state.InstallTask
import com.mizulauncher.v2.state.LoginKind
import com.mizulauncher.v2.state.RuntimeState
import com.mizulauncher.v2.state.SettingsState
import com.mizulauncher.v2.state.SetupState
import com.mizulauncher.v2.state.UiState
import com.mizulauncher.v2.state.YggdrasilAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 2.0 主 ViewModel。
 *
 * ## 与旧版的根本差别
 * 旧版是「二十多个 StateFlow + UI 里各自收集」，2.0 是「一棵状态树 + UI 只读」：
 *
 * ```
 *        用户操作            意图方法                状态更新
 *   UI ──────────► (onLaunch) ──────► ViewModel ──────► _state.update { ... }
 *    ▲                                                        │
 *    └──────────────── collectAsState ◄───────────────────────┘
 * ```
 *
 * 所有写操作都必须走 [_state.update]，没有例外。这样：
 *  - 任何时刻只有一份状态快照，不存在"两个流不同步"造成的视觉跳变；
 *  - 想在日志里复现用户看到什么，打印一份 [UiState] 就够了。
 *
 * ## 启动不阻塞主线程
 * 构造函数里**只做赋值**，不扫盘、不联网、不读文件。所有 IO 都进
 * `viewModelScope.launch(Dispatchers.IO)`。这是对 1.0.6/1.0.7 白屏闪退的直接修补：
 * 那两版在构造期同步遍历 JRE 目录，几千个文件的 stat 直接把主线程卡死。
 */
class MizuViewModel(app: Application) : AndroidViewModel(app), DebugUiBridge {

    private val application = app as MizuApplication
    private val container = application.container

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    // ============================================================ DebugUiBridge
    //
    // 调试 API 只关心"有没有在装、装到哪、装好了没"，不关心 2.0 的状态树怎么分层。
    // 所以这里把几个子状态**映射**出去，而不是把整棵树暴露给调试层：
    // 调试层拿到的是稳定的小接口，以后 UI 再改结构，它不用跟着改。

    override val installing: StateFlow<Boolean> = state
        .map { it.home.launchingId != null || it.download.activeTaskCount > 0 }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    override val stage: StateFlow<String> = state
        .map { s -> s.home.launchStage.ifBlank { s.download.tasks.values.firstOrNull { it.phase.isActive }?.phase?.label.orEmpty() } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    override val error: StateFlow<String?> = state
        .map { it.fatal?.detail ?: it.home.error }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    override val downloadProgress: StateFlow<DownloadProgressSnapshot?> = state
        .map { s ->
            val active = s.download.tasks.values.firstOrNull { it.phase.isActive }
                ?: s.download.tasks.values.lastOrNull()
                ?: return@map null
            DownloadProgressSnapshot(
                totalTasks = s.download.tasks.size,
                doneTasks = s.download.tasks.values.count { it.phase == InstallTask.Phase.Done },
                failedTasks = s.download.tasks.values.count { it.phase == InstallTask.Phase.Failed },
                percent = active.progress * 100f,
                totalBytes = 0L,
                downloadedBytes = 0L,
                speedBps = orchestrator.downloadEngine.progress.value.speedBps,
                etaSeconds = -1L,
                currentFile = active.detail,
                finished = !active.phase.isActive
            ) as DownloadProgressSnapshot?
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    override val instances: StateFlow<List<InstanceMeta>> = state
        .map { it.home.instances.map(InstanceCard::meta) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    override val versions: StateFlow<List<VersionEntry>> = state
        .map { it.download.versions }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    override val loadingVersions: StateFlow<Boolean> = state
        .map { it.download.loading }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    override val versionError: StateFlow<String?> = state
        .map { it.download.error }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    override val bundleScanned: StateFlow<Boolean> = container.bundleInstaller.scanned

    override val bundlePending: StateFlow<List<Any>> = container.bundleInstaller.pending
        .map { list -> list.map { it as Any } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    override val bundlePhase: StateFlow<Any> = container.bundleInstaller.phase
        .map { it as Any }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "Idle")

    override val bundleNotice: StateFlow<String?> = state
        .map { if (it.setup.pendingCount > 0) "还有 ${it.setup.pendingCount} 个运行组件待补全" else null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    override val runtimeStates: StateFlow<Map<Int, Any>> = state
        .map { s -> s.runtime.states.mapValues { it.value as Any } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    override val profile: StateFlow<MinecraftProfile?> = state
        .map { it.account.microsoft }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    override val deviceCode: StateFlow<DebugDeviceCode?> = state
        .map { s ->
            s.account.deviceCode?.let {
                DebugDeviceCode(userCode = it.userCode, verificationUri = it.verificationUri)
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    override val authError: StateFlow<String?> = state
        .map { it.account.error }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 一次性事件（导航、外部跳转），不放进 UiState，避免重复消费 */
    private val _events = Channel<Event>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    sealed interface Event {
        /** 需要跳出去到别的 Activity（游戏、系统浏览器） */
        data class LaunchGame(val instanceId: String) : Event

        /** 打开外部网页 */
        data class OpenUrl(val url: String) : Event

        /** 请求通知权限等需要 Activity 参与的动作 */
        data object RequestNotificationPermission : Event
    }

    /** 设备档案：一次读取，全局共享 */
    val device: DeviceProfile = DeviceProfiles.load(app)

    /**
     * 安装/启动编排器。
     *
     * 不从容器里拿：`LaunchOrchestrator` 持有 downloadEngine 的状态流，
     * 容器是进程级单例，多个 Activity 重建时会共享同一个引擎，
     * 反而容易出现"两个界面同时显示同一个进度条但各自 cancel"的混乱。
     * 由 ViewModel 持有，生命周期就跟着界面走。
     */
    private val orchestrator = com.mizulauncher.LaunchOrchestrator(app)

    private val yggdrasil = YggdrasilClient()
    private val accountStore = AccountStore(app)

    private val auth = MicrosoftAuth()

    init {
        // 设备档案是纯读属性，毫秒级，可以直接放进初始状态，
        // 免得关于页打开时还要等一次异步
        _state.value = UiState(device = device)

        // 调试 API 最先起：只做 socket bind，毫秒级，不阻塞
        runCatching { DebugApiHost.attach(this, application) }

        viewModelScope.launch(Dispatchers.IO) { bootstrap() }
    }

    // ================================================================ 启动引导

    /**
     * 首屏引导。按"能最快出画面"的顺序加载：
     * 1. 设置（读一个 DataStore，最快）
     * 2. 实例列表（扫目录 + 读 JSON）
     * 3. 运行时状态（扫 JRE，最慢，放最后）
     *
     * 每一步都先更新状态再继续，用户能先看到实例列表，而不是等全部加载完才出画面。
     */
    private suspend fun bootstrap() {
        // 1. 设置
        runCatching { loadSettings() }

        // 2. 实例
        runCatching { refreshInstancesInternal() }

        // 3. 运行时
        runCatching { refreshRuntimeInternal() }

        // 4. 账号
        runCatching { loadAccountsInternal() }

        // 5. 首启依赖检查
        runCatching { scanBundlesInternal() }

        _state.update { it.copy(bootstrapping = false) }
    }

    // ================================================================ 实例

    private suspend fun refreshInstancesInternal() {
        val list = withContext(Dispatchers.IO) {
            container.instanceManager.list()
        }

        val cards = list.map { meta ->
            InstanceCard(meta = meta, skin = skinCache[meta.id] ?: TextureState.Idle)
        }

        _state.update { s ->
            s.copy(
                home = s.home.copy(
                    instances = cards,
                    loading = false,
                    empty = cards.isEmpty()
                )
            )
        }

        // 体积与皮肤在后台慢慢补，不阻塞首屏
        viewModelScope.launch(Dispatchers.IO) { measureAllSizes(cards) }
    }

    /** 逐个测量实例体积。每个算完就更新，不攒到最后一起刷新 */
    private suspend fun measureAllSizes(cards: List<InstanceCard>) {
        for (card in cards) {
            val size = runCatching {
                withContext(Dispatchers.IO) {
                    val dir = container.instanceManager.gameDirOf(card.meta)
                    // 只统计两层以内的文件数，避免在超大整合包上耗时过久
                    dir.walkTopDown().maxDepth(3).filter { it.isFile }.sumOf { it.length() }
                }
            }.getOrDefault(0L)

            _state.update { s ->
                s.copy(
                    home = s.home.copy(
                        instances = s.home.instances.map {
                            if (it.meta.id == card.meta.id) it.copy(sizeBytes = size) else it
                        }
                    )
                )
            }
        }
    }

    override fun refreshInstances() {
        viewModelScope.launch(Dispatchers.IO) { refreshInstancesInternal() }
    }

    /** 打开实例的 .minecraft 目录（供 UI 打开文件夹用） */
    fun gameDirOf(meta: InstanceMeta): File = container.instanceManager.gameDirOf(meta)

    /** 切换底部导航。放状态树里，让 UI 和调试层走同一条路径 */
    fun selectTab(route: String) {
        _state.update { it.copy(tab = route) }
    }

    fun selectInstance(id: String) {
        _state.update { it.copy(home = it.home.copy(selectedId = id)) }
        viewModelScope.launch(Dispatchers.IO) { loadSkinFor(id) }
    }

    fun renameInstance(meta: InstanceMeta, newName: String) {
        if (newName.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { container.instanceManager.update(meta.copy(name = newName.trim())) }
            refreshInstancesInternal()
            toast("已重命名为「${newName.trim()}」")
        }
    }

    fun deleteInstance(meta: InstanceMeta) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = runCatching { container.instanceManager.delete(meta.id) }.getOrDefault(false)
            skinCache.remove(meta.id)
            refreshInstancesInternal()
            toast(if (ok) "已删除「${meta.displayName}」" else "删除失败，文件可能被占用", isError = !ok)
        }
    }

    // ================================================================ 启动游戏

    /**
     * 启动实例。
     *
     * 启动前检查链（任何一环失败都给出**具体**原因，而不是"启动失败"）：
     * 1. 运行时是否就绪；
     * 2. 资源文件是否完整（首次启动要下载）；
     * 3. 账号是否有效（微软账户还需刷新令牌）。
     */
    override suspend fun launch(meta: InstanceMeta) {
        if (_state.value.home.launchingId != null) {
            toast("已有任务正在启动，请稍候")
            return
        }

        withContext(Dispatchers.IO) {
            _state.update {
                it.copy(home = it.home.copy(launchingId = meta.id, launchStage = "正在准备", launchProgress = 0f, error = null))
            }

            try {
                // 1) 运行时
                val major = meta.javaMajor
                val runtimeState = container.runtimeManager.states.value[major]
                if (runtimeState !is RuntimeManager.State.Ready) {
                    failLaunch("Java $major 尚未就绪，请先到「运行时」页面安装")
                    return@withContext
                }

                // 2) 资源完整性 + 启动参数
                stage("正在校验游戏文件", 0.15f)
                val prepared = orchestrator.prepare(meta) { stageText ->
                    stage(stageText, 0.4f)
                }

                val request = prepared.getOrElse {
                    failLaunch(it.message ?: "准备启动文件失败")
                    return@withContext
                }

                stage("正在启动 JVM", 0.9f)

                // 3) 外置登录注入
                val ygg = currentYggdrasilSession()

                val result = orchestrator.launch(
                    request = request,
                    options = LaunchOptions(
                        renderer = rendererOf(container.settings.renderer),
                        memoryMb = container.settings.maxMemoryMb,
                        // 外置登录需要注入 authlib-injector
                        extraJvmArgs = yggExtraArgs(ygg)
                    )
                )

                when (result) {
                    is LaunchResult.Started -> {
                        _state.update {
                            it.copy(home = it.home.copy(launchingId = null, launchStage = "", launchProgress = 1f))
                        }
                        // 记一次游玩时间，影响首页排序
                        runCatching {
                            container.instanceManager.update(
                                meta.copy(lastPlayed = System.currentTimeMillis())
                            )
                        }
                        refreshInstancesInternal()
                        _events.send(Event.LaunchGame(meta.id))
                    }
                    is LaunchResult.Failed -> {
                        appendLog("内核返回失败：${result.reason}")
                        failLaunch(result.reason)
                    }
                    else -> {
                        _state.update { it.copy(home = it.home.copy(launchingId = null)) }
                    }
                }
            } catch (t: Throwable) {
                appendLog("启动失败：${t.javaClass.simpleName}: ${t.message}")
                failLaunch(t.message ?: "启动过程出现未知错误")
            }
        }
    }

    private fun failLaunch(reason: String) {
        _state.update {
            it.copy(home = it.home.copy(launchingId = null, launchStage = "", error = reason))
        }
    }

    private fun stage(text: String, progress: Float) {
        _state.update {
            it.copy(home = it.home.copy(launchStage = text, launchProgress = progress))
        }
    }

    fun clearLaunchError() {
        _state.update { it.copy(home = it.home.copy(error = null)) }
    }

    // ================================================================ 外置登录

    /** 当前生效的外置登录会话，null 表示用微软/离线账号 */
    private var yggdrasilSession: YggdrasilSpec.Session? = null

    private fun currentYggdrasilSession(): YggdrasilSpec.Session? = yggdrasilSession

    /**
     * 构造 authlib-injector 的 JVM 参数。
     *
     * 这一步是**外置登录能生效的唯一条件**：没有 agent，游戏只会去连 Mojang，
     * 表现为"登录成功了但游戏里是默认皮肤、进不去需要正版验证的服务器"。
     * 所以拿不到 jar 时必须明确告诉用户，而不是静默跳过。
     */
    private suspend fun yggExtraArgs(session: YggdrasilSpec.Session?): List<String> {
        if (session == null) return emptyList()
        val dir = File(application.filesDir, "bundles/authlib")
        val jar = withContext(Dispatchers.IO) { AuthlibInjector.ensure(dir) }
            ?: run {
                toast("未能获取 authlib-injector，外置皮肤可能不生效。请检查网络后重试", isError = true)
                return emptyList()
            }
        return listOf(AuthlibInjector.javaAgentArg(jar, session.apiRoot))
    }

    // ---------------- 表单输入 ----------------

    fun setYggServer(server: String) = updateForm { it.copy(server = server, apiRoot = null, error = null) }
    fun setYggEmail(email: String) = updateForm { it.copy(email = email, error = null) }
    fun setYggPassword(pw: String) = updateForm { it.copy(password = pw, error = null) }

    private fun updateForm(block: (com.mizulauncher.v2.state.YggdrasilForm) -> com.mizulauncher.v2.state.YggdrasilForm) {
        _state.update { it.copy(account = it.account.copy(form = block(it.account.form))) }
    }

    /** 探测服务器地址。用户填完地址后自动触发，也可以手动点 */
    fun discoverServer() {
        val form = _state.value.account.form
        if (form.server.isBlank()) return

        updateForm { it.copy(discovering = true, error = null, hint = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val r = yggdrasil.discoverApiRoot(form.server)
            r.fold(
                onSuccess = { root ->
                    updateForm {
                        it.copy(discovering = false, apiRoot = root, hint = "已识别：${YggdrasilSpec.hostOf(root)}")
                    }
                },
                onFailure = { e ->
                    val f = e.toYggFailure()
                    updateForm { it.copy(discovering = false, apiRoot = null, error = f.message, hint = f.hint) }
                }
            )
        }
    }

    /** 登录 */
    fun loginYggdrasil() {
        val form = _state.value.account.form
        if (!form.canSubmit) return

        _state.update { it.copy(account = it.account.copy(busy = true, error = null)) }

        viewModelScope.launch(Dispatchers.IO) {
            // 没探测过就先探测，避免把网站首页当 API 地址去登录
            var root = form.apiRoot
            if (root == null) {
                val d = yggdrasil.discoverApiRoot(form.server)
                if (d.isFailure) {
                    val f = d.exceptionOrNull()!!.toYggFailure()
                    _state.update { s ->
                        s.copy(account = s.account.copy(busy = false, error = f.message,
                            form = s.account.form.copy(hint = f.hint)))
                    }
                    return@launch
                }
                root = d.getOrNull()!!
            }

            val r = yggdrasil.authenticate(root, form.email.trim(), form.password)
            r.fold(
                onSuccess = { session ->
                    yggdrasilSession = session
                    // 立刻落盘（不含密码），下次启动不用重新登录
                    runCatching { accountStore.save(session.toStored()) }

                    val accounts = loadYggAccounts()
                    _state.update { s ->
                        s.copy(
                            account = s.account.copy(
                                busy = false,
                                kind = LoginKind.Yggdrasil,
                                yggdrasilAccounts = accounts,
                                // 密码从内存里抹掉，不留在状态树里
                                form = s.account.form.copy(password = "", error = null, hint = "登录成功"),
                                previewUuid = session.selectedProfile?.id
                            )
                        )
                    }
                    session.selectedProfile?.let { p ->
                        loadSkin(p.id, p.model == "slim")
                    }
                },
                onFailure = { e ->
                    val f = e.toYggFailure()
                    _state.update { s ->
                        s.copy(
                            account = s.account.copy(
                                busy = false,
                                error = f.message,
                                form = s.account.form.copy(password = "", hint = f.hint)
                            )
                        )
                    }
                }
            )
        }
    }

    fun logoutYggdrasil() {
        val s = yggdrasilSession
        viewModelScope.launch(Dispatchers.IO) {
            if (s != null) {
                runCatching { yggdrasil.invalidate(s.apiRoot, s.accessToken, s.clientToken) }
            }
            yggdrasilSession = null
            _state.update { st ->
                st.copy(account = st.account.copy(kind = LoginKind.None, error = null, previewUuid = null))
            }
            toast("已退出外置登录")
        }
    }

    private suspend fun loadYggAccounts(): List<YggdrasilAccount> {
        val stored = runCatching { accountStore.loadAll() }.getOrDefault(emptyList())
        return stored.map {
            YggdrasilAccount(
                apiRoot = it.apiRoot,
                displayName = it.displayName.ifBlank { it.profileName.orEmpty() },
                profileId = it.profileId,
                profileName = it.profileName
            )
        }
    }

    // ================================================================ 皮肤

    private val skinCache = mutableMapOf<String, TextureState>()

    fun loadSkin(uuid: String, slim: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) { loadSkinInternal(uuid, slim) }
    }

    private suspend fun loadSkinInternal(uuid: String, slim: Boolean) {
        val session = yggdrasilSession ?: return
        skinCache[uuid] = TextureState.Loading
        publishSkin(uuid, TextureState.Loading)

        val detail = yggdrasil.fetchProfile(session.apiRoot, uuid).getOrNull()
        val url = detail?.skinUrl

        if (url == null) {
            skinCache[uuid] = TextureState.Absent
            publishSkin(uuid, TextureState.Absent)
            return
        }

        val state = withContext(Dispatchers.IO) { downloadSkin(url, slim) }
        skinCache[uuid] = state
        publishSkin(uuid, state)
    }

    private fun downloadSkin(url: String, slim: Boolean): TextureState = runCatching {
        val req = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", "MizuLauncher/2.0")
            .build()
        val bytes = java.net.URL(url).openStream().use { it.readBytes() }
        when (val v = SkinLoader.validate(bytes)) {
            is SkinLoader.Validation.Valid -> {
                val bmp = SkinLoader.decodeOfSize(bytes, 64)
                if (bmp == null) {
                    TextureState.Failed("皮肤图片解码失败")
                } else {
                    TextureState.Ready(bmp, slim || v.legacy.not())
                }
            }
            is SkinLoader.Validation.BadSize ->
                TextureState.Failed("皮肤尺寸不合规（${v.width}×${v.height}）")
            SkinLoader.Validation.NotAnImage -> TextureState.Failed("下载到的不是图片")
        }
    }.getOrElse { TextureState.Failed("皮肤下载失败：${it.message ?: "网络错误"}") }

    private fun publishSkin(uuid: String, state: TextureState) {
        _state.update { s ->
            s.copy(
                home = s.home.copy(
                    instances = s.home.instances.map { card ->
                        if (card.meta.id == uuid) card.copy(skin = state) else card
                    }
                ),
                account = s.account.copy(
                    yggdrasilAccounts = s.account.yggdrasilAccounts.map { acc ->
                        if (acc.profileId == uuid) acc.copy(skin = state) else acc
                    }
                )
            )
        }
    }

    private suspend fun loadSkinFor(instanceId: String) {
        // 首页卡片的皮肤取自当前登录账号，不按实例区分（同一角色跨实例复用）
        val uuid = yggdrasilSession?.selectedProfile?.id ?: return
        if (skinCache[uuid] is TextureState.Ready) return
        loadSkinInternal(uuid, false)
    }

    // ================================================================ 运行时

    private suspend fun refreshRuntimeInternal() {
        // 关键：refresh() 会遍历 JRE 目录，必须在 IO 线程
        withContext(Dispatchers.IO) { container.runtimeManager.refresh() }
        val states = container.runtimeManager.states.value
        _state.update {
            it.copy(runtime = it.runtime.copy(states = states, abi = device.abi))
        }
    }

    fun refreshRuntime() {
        viewModelScope.launch(Dispatchers.IO) { refreshRuntimeInternal() }
    }

    fun installRuntime(major: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            setRuntimeBusy(major, true)
            // ensure() 的语义是「就绪即返回，否则下载解压」。
            // 内置 JRE 已由首启流程解压到 bundles/runtime 时，这里会直接命中 isReady 返回，
            // 不会产生任何流量。
            container.runtimeManager.ensure(major) { state ->
                // 进度回推到状态树，UI 才能画出下载百分比
                _state.update { s ->
                    s.copy(runtime = s.runtime.copy(states = s.runtime.states + (major to state)))
                }
            }.onFailure {
                toast("Java $major 安装失败：${it.message ?: "未知错误"}", isError = true)
            }
            setRuntimeBusy(major, false)
            refreshRuntimeInternal()
        }
    }

    fun removeRuntime(major: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            setRuntimeBusy(major, true)
            runCatching { container.runtimeManager.remove(major) }
                .onFailure { toast("删除失败：${it.message}", isError = true) }
            setRuntimeBusy(major, false)
            refreshRuntimeInternal()
        }
    }

    private fun setRuntimeBusy(major: Int, busy: Boolean) {
        _state.update {
            it.copy(runtime = it.runtime.copy(busy = it.runtime.busy + (major to busy)))
        }
    }

    // ================================================================ 下载

    override fun loadVersions(force: Boolean) {
        refreshVersions(force)
    }

    private fun refreshVersions(force: Boolean) {
        val cur = _state.value.download
        if (cur.loading) return
        if (!force && cur.versions.isNotEmpty()) return

        _state.update { it.copy(download = it.download.copy(loading = true, error = null)) }

        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                orchestrator.repository.loadManifest(force)
            }.fold(
                onSuccess = { manifest ->
                    _state.update {
                        it.copy(download = it.download.copy(
                            versions = (manifest as com.mizulauncher.minecraft.model.VersionManifest).versions,
                            loading = false
                        ))
                    }
                },
                onFailure = { e ->
                    _state.update {
                        it.copy(download = it.download.copy(loading = false, error = e.message ?: "获取版本列表失败"))
                    }
                }
            )
        }
    }

    fun setVersionQuery(q: String) {
        _state.update { it.copy(download = it.download.copy(query = q)) }
    }

    fun setVersionFilter(f: com.mizulauncher.v2.state.VersionFilter) {
        _state.update { it.copy(download = it.download.copy(filter = f)) }
    }

    fun expandVersion(id: String?) {
        _state.update { it.copy(download = it.download.copy(expandedId = id)) }
    }

    fun installVersion(
        entry: VersionEntry,
        loader: String = "vanilla",
        loaderVersion: String = "",
        customName: String = ""
    ) {
        if (_state.value.download.tasks[entry.id]?.phase?.isActive == true) {
            toast("该版本正在安装中")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            updateTask(entry.id) { InstallTask(entry.id, InstallTask.Phase.Queued) }

            runCatching {
                installVersionInternal(entry, loader, loaderVersion, customName)
            }.fold(
                onSuccess = {
                    updateTask(entry.id) { InstallTask(entry.id, InstallTask.Phase.Done, 1f, "已完成") }
                    refreshInstancesInternal()
                    toast("「${customName.ifBlank { entry.id }}」安装完成")
                },
                onFailure = { e ->
                    updateTask(entry.id) {
                        InstallTask(entry.id, InstallTask.Phase.Failed, detail = e.message ?: "安装失败")
                    }
                    toast("安装失败：${e.message ?: "未知错误"}", isError = true)
                }
            )
        }
    }

    private fun updateTask(id: String, block: (InstallTask) -> InstallTask) {
        _state.update { s ->
            val existing = s.download.tasks[id] ?: InstallTask(id, InstallTask.Phase.Queued)
            s.copy(download = s.download.copy(tasks = s.download.tasks + (id to block(existing))))
        }
    }

    fun dismissTask(id: String) {
        _state.update { s ->
            s.copy(download = s.download.copy(tasks = s.download.tasks - id))
        }
    }

    // ================================================================ 设置

    private suspend fun loadSettings() {
        val settings = container.settings
        _state.update {
            it.copy(
                settings = SettingsState(
                    darkMode = when (settings.themeMode) {
                        "light" -> DarkMode.Light
                        "dark" -> DarkMode.Dark
                        else -> DarkMode.System
                    },
                    dynamicColor = settings.dynamicColor,
                    downloadSource = container.sourceFlow.value.name,
                    runtimeManifestUrl = settings.runtimeManifestUrl.orEmpty(),
                    appVersion = com.mizulauncher.BuildConfig.VERSION_NAME.orEmpty()
                )
            )
        }
    }

    fun setDarkMode(mode: DarkMode) {
        viewModelScope.launch(Dispatchers.IO) {
            container.settings.themeMode = when (mode) {
                DarkMode.System -> "system"
                DarkMode.Light -> "light"
                DarkMode.Dark -> "dark"
            }
            _state.update { it.copy(settings = it.settings.copy(darkMode = mode)) }
        }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            container.settings.dynamicColor = enabled
            _state.update { it.copy(settings = it.settings.copy(dynamicColor = enabled)) }
        }
    }

    fun setDownloadSource(source: DownloadSource) {
        viewModelScope.launch(Dispatchers.IO) {
            container.sourceFlow.value = source
            _state.update { it.copy(settings = it.settings.copy(downloadSource = source.name)) }
        }
    }

    // ================================================================ 首启依赖

    private suspend fun scanBundlesInternal() {
        val installer = container.bundleInstaller
        withContext(Dispatchers.IO) { installer.scan() }

        val pending = installer.pending.value
        val states = installer.states.value
        val phase = installer.phase.value

        _state.update {
            it.copy(
                setup = SetupState(
                    visible = pending.isNotEmpty() && !it.setup.dismissed,
                    items = states,
                    phase = phase,
                    dismissed = it.setup.dismissed
                )
            )
        }
    }

    override fun installBundles() {
        viewModelScope.launch(Dispatchers.IO) {
            container.bundleInstaller.installAll()
            scanBundlesInternal()
            refreshRuntimeInternal()
            toast("依赖补全完成")
        }
    }

    fun dismissSetup() {
        _state.update {
            it.copy(setup = it.setup.copy(visible = false, dismissed = true))
        }
    }

    // ================================================================ 账号（微软）

    private suspend fun loadAccountsInternal() {
        val profile = runCatching { container.accountSession.current() }.getOrNull()
        val ygg = loadYggAccounts()
        _state.update {
            it.copy(
                account = it.account.copy(
                    microsoft = profile,
                    kind = when {
                        profile != null -> LoginKind.Microsoft
                        ygg.isNotEmpty() -> LoginKind.Yggdrasil
                        else -> LoginKind.None
                    },
                    yggdrasilAccounts = ygg
                )
            )
        }
    }

    /** 登录任务句柄。用户点「取消」时 cancel 它，避免后台还在轮询 */
    private var authJob: kotlinx.coroutines.Job? = null

    /**
     * 微软设备码登录。
     *
     * 三段式：拿设备码 → 轮询是否授权 → 换取 Minecraft 档案。
     *
     * **为什么必须按服务端给的 interval 轮询**：`slow_down` 是 OAuth 设备码流程里
     * 服务端在惩罚"你来太快了"，正确响应是**加大间隔继续等**，而不是报错退出。
     * 1.0 的代码在这里处理对了，这里保持同样语义。
     */
    override fun startMicrosoftLogin() {
        if (_state.value.account.busy) return
        authJob?.cancel()
        authJob = viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(account = it.account.copy(busy = true, error = null, deviceCode = null)) }

            val code = runCatching { auth.requestDeviceCode() }.getOrElse {
                _state.update { s ->
                    s.copy(account = s.account.copy(busy = false, error = "获取验证码失败：${it.message ?: "网络错误"}"))
                }
                return@launch
            }

            _state.update {
                it.copy(account = it.account.copy(
                    deviceCode = AccountState.DeviceCodeState(code.userCode, code.verificationUri)
                ))
            }
            // 打开浏览器让用户输入验证码。用系统浏览器而不是内嵌 WebView：
            // 微软的登录页对老 WebView 内核不友好，且这里本来也不该接管凭据输入。
            _events.send(Event.OpenUrl(code.verificationUriComplete.ifBlank { code.verificationUri }))

            var interval = code.intervalSeconds.coerceAtLeast(1L)
            val deadline = System.currentTimeMillis() + code.expiresInSeconds * 1000

            while (System.currentTimeMillis() < deadline) {
                kotlinx.coroutines.delay(interval * 1000)
                if (!_state.value.account.busy) return@launch   // 用户取消

                when (val poll = runCatching { auth.pollDeviceToken(code.deviceCode) }
                    .getOrElse { com.mizulauncher.auth.TokenPoll.Failed(it.message ?: "网络错误") }) {
                    com.mizulauncher.auth.TokenPoll.Pending -> Unit
                    com.mizulauncher.auth.TokenPoll.SlowDown -> interval += 2
                    com.mizulauncher.auth.TokenPoll.Expired ->
                        return@launch finishMicrosoftLogin("验证码已过期，请重新登录")
                    com.mizulauncher.auth.TokenPoll.Declined ->
                        return@launch finishMicrosoftLogin("你在浏览器中拒绝了授权")
                    is com.mizulauncher.auth.TokenPoll.Failed ->
                        return@launch finishMicrosoftLogin(poll.message)
                    is com.mizulauncher.auth.TokenPoll.Done -> {
                        runCatching { auth.loginWithXbox(poll.token) }
                            .onSuccess { profile ->
                                container.accountSession.save(profile)
                                _state.update { s ->
                                    s.copy(account = s.account.copy(
                                        busy = false,
                                        deviceCode = null,
                                        microsoft = profile,
                                        kind = LoginKind.Microsoft,
                                        error = null
                                    ))
                                }
                                toast("已登录：${profile.name}")
                            }
                            .onFailure { finishMicrosoftLogin(it.message ?: "正版验证失败") }
                        return@launch
                    }
                }
            }
            finishMicrosoftLogin("验证码已过期，请重新登录")
        }
    }

    private fun finishMicrosoftLogin(message: String?) {
        _state.update {
            it.copy(account = it.account.copy(busy = false, deviceCode = null, error = message))
        }
    }

    fun cancelMicrosoftLogin() {
        authJob?.cancel()
        _state.update { it.copy(account = it.account.copy(busy = false, deviceCode = null)) }
    }

    // ================================================================ DebugUiBridge 的动作
    //
    // 这几个是「调试 API 直接调」的入口。UI 自己走上面的意图方法，
    // 但两者最终落到同一套内部实现，所以 curl 触发的下载在手机上能看到进度条在动。

    /** 只准备文件不启动。和 [launch] 共用前置检查，避免"UI 不让过、API 能过" */
    override suspend fun prepareOnly(meta: InstanceMeta) {
        withContext(Dispatchers.IO) {
            _state.update {
                it.copy(home = it.home.copy(launchingId = meta.id, launchStage = "正在准备文件", launchProgress = 0f, error = null))
            }
            val major = meta.javaMajor
            container.runtimeManager.ensure(major).getOrElse {
                failLaunch("Java $major 不可用：${it.message ?: "未知错误"}")
                return@withContext
            }
            val r = orchestrator.prepare(meta) { text -> stage(text, 0.4f) }
            r.fold(
                onSuccess = {
                    _state.update { s ->
                        s.copy(home = s.home.copy(launchingId = null, launchStage = "", launchProgress = 1f))
                    }
                },
                onFailure = { failLaunch(it.message ?: "准备文件失败") }
            )
        }
    }

    override fun cancelDownload() {
        orchestrator.downloadEngine.cancel()
        _state.update { s ->
            s.copy(
                home = s.home.copy(launchingId = null, launchStage = ""),
                download = s.download.copy(
                    tasks = s.download.tasks.mapValues { (_, t) ->
                        if (t.phase.isActive) t.copy(phase = InstallTask.Phase.Failed, error = "已取消") else t
                    }
                )
            )
        }
    }

    override fun setSource(source: DownloadSource) = setDownloadSource(source)

    override fun rescanBundles() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { scanBundlesInternal() }
        }
    }

    override fun isRuntimeReady(major: Int): Boolean = container.runtimeManager.isReady(major)

    override fun cancelLogin() = cancelMicrosoftLogin()

    override fun logout() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { container.accountSession.logout() }
            _state.update { it.copy(account = it.account.copy(kind = LoginKind.None, microsoft = null)) }
        }
    }

    // ================================================================ 日志

    /**
     * 界面侧的日志缓冲。
     *
     * 为什么不直接读 logcat：Android 11 起普通应用读不到别的进程的 logcat，
     * 而且用户也不会用 `adb logcat`。这里自己维护一份环形缓冲，
     * 只收启动器产生的关键事件（启动参数、异常、下载失败），
     * 让用户能"复制日志"发给别人排查。
     */
    private val logLines = mutableListOf<String>()

    private fun appendLog(line: String) {
        val stamped = "${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())}  $line"
        logLines += stamped
        // 环形缓冲：只留最近 2000 行。再多对用户没意义，反而难搜
        while (logLines.size > 2000) logLines.removeAt(0)
        _state.update { it.copy(logLines = logLines.toList()) }
    }

    fun clearLog() {
        logLines.clear()
        _state.update { it.copy(logLines = emptyList()) }
    }

    // ================================================================ 提示

    fun toast(message: String, isError: Boolean = false) {
        _state.update { it.copy(toast = UiState.Toast(message, isError = isError)) }
    }

    fun consumeToast() {
        _state.update { it.copy(toast = null) }
    }


    /**
     * 真正执行安装。
     *
     * 复用 [LaunchOrchestrator.prepare]：它的语义本来就是「确保文件齐全」，
     * 和"安装"是同一件事。区别只在于我们要把进度映射成 UI 能懂的阶段，
     * 而不是把 `prepare` 内部的中文阶段字符串原样透出——那样用户会看到
     * "获取版本清单""下载 libraries"这类开发者术语。
     */
    private suspend fun installVersionInternal(
        entry: VersionEntry,
        loader: String,
        loaderVersion: String,
        customName: String
    ): Unit = withContext(Dispatchers.IO) {
        val major = container.runtimeManager.requiredMajorFor(entry.id)

        val meta = InstanceMeta(
            name = customName.ifBlank { entry.id },
            versionId = entry.id,
            loaderType = loader,
            loaderVersion = loaderVersion,
            javaMajor = major
        )

        updateTask(entry.id) { InstallTask(entry.id, InstallTask.Phase.Resolving, 0.05f) }

        val r = orchestrator.prepare(meta) { stageText ->
            // 把内部阶段映射成有限枚举，UI 才能据此切换图标与文案
            val phase = when {
                stageText.contains("清单") || stageText.contains("版本") -> InstallTask.Phase.Resolving
                stageText.contains("加载器") -> InstallTask.Phase.Installing
                stageText.contains("资源") || stageText.contains("库") || stageText.contains("下载") ->
                    InstallTask.Phase.Downloading
                stageText.contains("解压") || stageText.contains("natives") -> InstallTask.Phase.Extracting
                stageText.contains("校验") -> InstallTask.Phase.Verifying
                else -> InstallTask.Phase.Downloading
            }
            updateTask(entry.id) { InstallTask(entry.id, phase, detail = stageText) }
        }

        r.getOrThrow()
        Unit
    }

    /** 设置里的渲染器字符串 → 枚举。未知值回落到 VirGL（兼容性最好） */
    private fun rendererOf(name: String): com.mizulauncher.bridge.Renderer =
        com.mizulauncher.bridge.Renderer.entries.firstOrNull {
            it.name.equals(name, ignoreCase = true)
        } ?: com.mizulauncher.bridge.Renderer.VIRGL

    fun clearAccountError() {
        _state.update { it.copy(account = it.account.copy(error = null)) }
    }
}

/** 把任意异常转成结构化失败信息 */
private fun Throwable.toYggFailure(): YggdrasilSpec.Failure = when (this) {
    is CodedException -> failure
    else -> YggdrasilSpec.Failure(
        YggdrasilSpec.FailureKind.PROTOCOL,
        message ?: "未知错误"
    )
}
