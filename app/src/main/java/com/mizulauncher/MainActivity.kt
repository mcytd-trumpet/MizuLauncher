package com.mizulauncher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mizulauncher.ui.MainDestination
import com.mizulauncher.ui.MainScaffold
import com.mizulauncher.ui.SplashContent
import com.mizulauncher.ui.components.DownloadTaskCard
import com.mizulauncher.ui.components.GlassCard
import com.mizulauncher.ui.components.InstanceActionSheet
import com.mizulauncher.ui.components.formatEta
import com.mizulauncher.ui.components.ProgressBlock
import com.mizulauncher.ui.components.SetupDialog
import com.mizulauncher.ui.components.UpdateDialog
import com.mizulauncher.ui.screens.about.AboutScreen
import com.mizulauncher.ui.screens.account.AccountScreen
import com.mizulauncher.ui.screens.control.ControlEditorCanvas
import com.mizulauncher.ui.screens.control.ControlLayout
import com.mizulauncher.ui.screens.control.LayoutStore
import com.mizulauncher.ui.screens.dependencies.DependenciesScreen
import com.mizulauncher.ui.screens.download.DownloadScreen
import com.mizulauncher.ui.screens.download.LoaderChoice
import com.mizulauncher.ui.screens.download.formatSpeed
import com.mizulauncher.ui.screens.home.HomeScreen
import com.mizulauncher.ui.screens.log.LogScreen
import com.mizulauncher.ui.screens.runtime.RuntimeScreen
import com.mizulauncher.ui.screens.settings.SettingsScreen
import com.mizulauncher.ui.theme.MizuTheme
import com.mizulauncher.runtime.RuntimeManager

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { App() }
    }
}

@Composable
fun App() {
    var showSplash by remember { mutableStateOf(true) }
    var splashProgress by remember { mutableStateOf(0f) }
    var splashText by remember { mutableStateOf("正在扫描实例目录…") }

    // 主题偏好必须持久化，否则用户在设置里选的深色模式重启就回到跟随系统
    val prefs = (LocalContext.current.applicationContext as MizuApplication).container.settings
    var darkSetting by remember {
        mutableStateOf(
            when (prefs.themeMode) {
                "dark" -> true
                "light" -> false
                else -> null
            }
        )
    }
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val dark = darkSetting ?: systemDark

    LaunchedEffect(Unit) {
        splashText = "正在扫描实例目录"
        splashProgress = 0.3f
        kotlinx.coroutines.delay(350)
        splashProgress = 1f
        splashText = "准备就绪"
        kotlinx.coroutines.delay(250)
        showSplash = false
    }

    MizuTheme(dark = dark) {
        if (showSplash) {
            SplashContent(progress = splashProgress, status = splashText)
        } else {
            AppContent(
                darkMode = darkSetting,
                onThemeChange = { value ->
                    darkSetting = value
                    prefs.themeMode = when (value) {
                        true -> "dark"
                        false -> "light"
                        null -> "system"
                    }
                }
            )
        }
    }
}

/**
 * 主界面。
 *
 * ## 状态全部来自 LauncherViewModel
 * 上一版把状态写在 `remember` 里，`remember { instanceManager.list() }` 只在首次组合求值，
 * 于是「装完版本 → 列表不更新 → 切页面再切回来才有」成了必然。现在改成订阅 StateFlow，
 * 任何写操作结束都会主动 [LauncherViewModel.refreshInstances]，UI 立刻反映。
 */
@Composable
private fun AppContent(darkMode: Boolean?, onThemeChange: (Boolean?) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val container = (context as MizuApplication).container
    val config = LocalConfiguration.current
    val widthDp = config.screenWidthDp
    val useRail = widthDp >= 600

    val vm: LauncherViewModel = viewModel()
    val settings = vm.settings

    // ---------- 订阅状态 ----------
    val instances by vm.instances.collectAsStateWithLifecycle()
    val selectedId by vm.selectedId.collectAsStateWithLifecycle()
    val versions by vm.versions.collectAsStateWithLifecycle()
    val loadingVersions by vm.loadingVersions.collectAsStateWithLifecycle()
    val progress by vm.downloadProgress.collectAsStateWithLifecycle()
    val installing by vm.installing.collectAsStateWithLifecycle()
    val stage by vm.stage.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val updateInfo by vm.update.collectAsStateWithLifecycle()
    val updateChecking by vm.updateChecking.collectAsStateWithLifecycle()
    val updateProgress by vm.updateProgress.collectAsStateWithLifecycle()
    val updateStatus by vm.updateStatus.collectAsStateWithLifecycle()
    val updateDownloading by vm.updateDownloading.collectAsStateWithLifecycle()
    val updateToast by vm.updateToast.collectAsStateWithLifecycle()
    val downloadedApk by vm.downloadedApk.collectAsStateWithLifecycle()
    val bundleItems by vm.bundleItems.collectAsStateWithLifecycle()
    val bundlePending by vm.bundlePending.collectAsStateWithLifecycle()
    val bundleStates by vm.bundleStates.collectAsStateWithLifecycle()
    val bundlePhase by vm.bundlePhase.collectAsStateWithLifecycle()
    val setupDismissed by vm.setupDismissed.collectAsStateWithLifecycle()
    val bundleNotice by vm.bundleNotice.collectAsStateWithLifecycle()
    val instanceSizes by vm.instanceSizes.collectAsStateWithLifecycle()
    val runtimeStates by vm.runtimeStates.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()

    // 启动时静默续期一次：令牌快过期就刷新，失败不打扰用户
    LaunchedEffect(Unit) { vm.refreshAccountIfNeeded() }
    // 扫描内置组件：首启或升级后若有未解压的，弹窗让用户补全
    LaunchedEffect(Unit) { vm.rescanBundles() }

    // 一次性提示（依赖补全结果 / 检查更新结果）走 Toast，不打断操作
    LaunchedEffect(bundleNotice) {
        bundleNotice?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show()
            vm.consumeBundleNotice()
        }
    }
    LaunchedEffect(updateToast) {
        updateToast?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show()
            vm.consumeUpdateToast()
        }
    }
    // 启动时静默查一次更新；有新版才弹窗，没网/查失败不打扰
    LaunchedEffect(Unit) { vm.checkForUpdate(manual = false) }
    val deviceCode by vm.deviceCode.collectAsStateWithLifecycle()
    val authBusy by vm.authBusy.collectAsStateWithLifecycle()
    val authStatus by vm.authStatus.collectAsStateWithLifecycle()
    val authError by vm.authError.collectAsStateWithLifecycle()

    var destination by remember { mutableStateOf(MainDestination.HOME) }
    /** 长按实例卡片时打开操作面板的目标；null 表示面板关闭 */
    var actionTarget by remember { mutableStateOf<com.mizulauncher.instance.InstanceMeta?>(null) }
    var currentSource by remember { mutableStateOf(settings.downloadSource) }
    /**
     * 离线显示名。
     *
     * 正版登录时 `settings.lastAccount` 会被写成正版名，但这里是 `remember` 的本地态，
     * 不会自动跟着变 —— 结果就是「退出正版后，账户页显示的是上次正版的名字」。
     * 跟着 `profile` 的变化同步一次即可。
     */
    var accountName by remember { mutableStateOf(settings.lastAccount) }
    LaunchedEffect(profile?.name) {
        val live = profile?.name
        if (live != null && live != accountName) accountName = live
        else if (live == null && accountName != settings.lastAccount) accountName = settings.lastAccount
    }
    var backgroundVersion by remember { mutableStateOf(0) }

    // 安装完成 → 自动回主页并选中新实例（避免用户以为「没反应」）
    val installDone by vm.installDone.collectAsStateWithLifecycle()
    LaunchedEffect(installDone) {
        if (installDone) {
            destination = MainDestination.HOME
            vm.consumeInstallDone()
        }
    }

    // 控制编辑器。布局必须落盘：以前只放 remember 里，退出就丢
    val orientationKey = if (config.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"
    val layoutStore = remember { LayoutStore(container.rootDir) }
    var showControlEditor by remember { mutableStateOf(false) }
    var layout by remember(orientationKey) { mutableStateOf(layoutStore.load(orientationKey)) }
    var selectedWidget by remember { mutableStateOf<String?>(null) }

    /** 任何改动都立刻落盘，避免「编辑半天、切个页面就没了」 */
    fun persistLayout(next: ControlLayout) {
        layout = next
        layoutStore.save(orientationKey, next)
    }

    val selected = instances.firstOrNull { it.id == selectedId }

    val clipboard: androidx.compose.ui.platform.ClipboardManager =
        androidx.compose.ui.platform.LocalClipboardManager.current

    // 背景图选择：用系统文档选择器，拿到 content:// 后复制进应用私有目录，
    // 否则重启后权限失效会变成黑屏。
    val pickBackground = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val dest = java.io.File(container.rootDir, "background.img")
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
            }.onSuccess {
                settings.backgroundPath = dest.absolutePath
                backgroundVersion++
            }
        }
    }

    // 进入下载页时拉取版本列表。force = false：已有数据就不重复打接口
    LaunchedEffect(destination) {
        if (destination == MainDestination.DOWNLOAD) vm.loadVersions()
    }
    // 进入运行时页时重新扫一次磁盘，保证显示的是最新的
    LaunchedEffect(destination) {
        if (destination == MainDestination.RUNTIME) vm.refreshRuntimes()
    }
    LaunchedEffect(destination) {
        if (destination == MainDestination.DEPENDENCIES) vm.rescanBundles()
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        MainScaffold(
            current = destination,
            useRail = useRail,
            onNavigate = { destination = it }
        ) { modifier ->
            when (destination) {
                MainDestination.HOME -> HomeScreen(
                    instances = instances,
                    selected = selected,
                    accountName = profile?.name ?: accountName,
                    columns = if (widthDp >= 840) 4 else if (widthDp >= 600) 3 else 2,
                    onSelectInstance = { vm.selectInstance(it.id) },
                    onLaunch = {
                        val inst = selected ?: return@HomeScreen
                        vm.launch(inst)
                    },
                    onOpenDownload = { destination = MainDestination.DOWNLOAD },
                    onOpenSettings = { destination = MainDestination.SETTINGS },
                    onOpenInstanceMenu = { inst ->
                        vm.selectInstance(inst.id)
                        vm.measureInstance(inst)
                        actionTarget = inst
                    },
                    onOpenAccount = { destination = MainDestination.ACCOUNT },
                    modifier = modifier
                )

                MainDestination.DOWNLOAD -> DownloadScreen(
                    versions = versions,
                    loading = loadingVersions,
                    currentSource = currentSource,
                    progress = progress,
                    installing = installing,
                    onSourceChange = {
                        currentSource = it
                        vm.setSource(it)
                    },
                    onRefresh = { vm.loadVersions(force = true) },
                    onDownload = { entry, choice ->
                        vm.installVersion(
                            entry = entry,
                            loader = choice.loader,
                            loaderVersion = choice.loaderVersion,
                            customName = choice.customName
                        )
                    },
                    onCancel = { vm.cancelDownload() },
                    modifier = modifier
                )

                MainDestination.ACCOUNT -> AccountScreen(
                    profile = profile,
                    deviceCode = deviceCode,
                    busy = authBusy,
                    status = authStatus,
                    error = authError,
                    offlineName = accountName,
                    onStartLogin = { vm.startMicrosoftLogin() },
                    onCancelLogin = { vm.cancelLogin() },
                    onLogout = { vm.logout() },
                    onOfflineNameChange = {
                        accountName = it
                        vm.setOfflineName(it)
                    },
                    modifier = modifier
                )

                MainDestination.DEPENDENCIES -> DependenciesScreen(
                    items = bundleItems,
                    pending = bundlePending,
                    states = bundleStates,
                    phase = bundlePhase,
                    abi = RuntimeManager.primaryAbi(),
                    onInstall = { vm.installBundles() },
                    onRescan = { vm.rescanBundles() },
                    modifier = modifier
                )

                MainDestination.RUNTIME -> RuntimeScreen(
                    states = runtimeStates,
                    abi = RuntimeManager.primaryAbi(),
                    onInstall = { vm.installRuntime(it) },
                    onRemove = { vm.removeRuntime(it) },
                    modifier = modifier
                )

                MainDestination.SETTINGS -> SettingsScreen(
                    source = currentSource,
                    threads = settings.downloadThreads,
                    memoryMb = settings.maxMemoryMb,
                    renderer = com.mizulauncher.bridge.Renderer.valueOf(settings.renderer),
                    darkMode = darkMode,
                    backgroundPath = settings.backgroundPath,
                    runtimeManifestUrl = settings.runtimeManifestUrl ?: "",
                    accountName = accountName,
                    onSourceChange = {
                        currentSource = it
                        vm.setSource(it)
                    },
                    onThreadsChange = { settings.downloadThreads = it },
                    onMemoryChange = { settings.maxMemoryMb = it },
                    onRendererChange = { settings.renderer = it.name },
                    onThemeChange = onThemeChange,
                    onPickBackground = { pickBackground.launch(arrayOf("image/*")) },
                    onClearBackground = {
                        settings.backgroundPath = null
                        backgroundVersion++
                    },
                    onRuntimeManifestChange = { settings.runtimeManifestUrl = it },
                    onAccountChange = {
                        accountName = it
                        settings.lastAccount = it
                    },
                    onOpenControlEditor = { showControlEditor = true },
                    onOpenAccount = { destination = MainDestination.ACCOUNT },
                    onOpenRuntime = { destination = MainDestination.RUNTIME },
                    dependenciesSummary = when {
                        bundleItems.isEmpty() -> "当前安装包无内置组件"
                        bundlePending.isEmpty() -> "全部就绪（${bundleItems.size} 项）"
                        else -> "${bundlePending.size} 项待补全"
                    },
                    onOpenDependencies = { destination = MainDestination.DEPENDENCIES },
                    updateChecking = updateChecking,
                    updateStatus = updateStatus,
                    onCheckUpdate = { vm.checkForUpdate(manual = true) },
                    onOpenLogs = { destination = MainDestination.LOGS },
                    onOpenAbout = { destination = MainDestination.ABOUT },
                    modifier = modifier
                )

                MainDestination.LOGS -> LogScreen(
                    logsRoot = container.rootDir,
                    onCopy = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(it)) },
                    modifier = modifier
                )

                MainDestination.ABOUT -> AboutScreen(
                    versionName = BuildConfig.VERSION_NAME,
                    versionCode = BuildConfig.VERSION_CODE,
                    bridgeInfo = vm.orchestrator.bridge?.info,
                    updateChecking = updateChecking,
                    updateStatus = updateStatus,
                    onCheckUpdate = { vm.checkForUpdate(manual = true) },
                    onOpenSource = { url ->
                        runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(url)
                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    },
                    modifier = modifier
                )
            }
        }

        // 全局下载任务卡：离开下载页后仍能看到进度、随时取消
        // 只在真有下载任务时显示：启动游戏也会把 installing 置位，
        // 但那时候没有 totalTasks，弹一张「0% 准备中」的卡片纯属误导
        val hasDownloadTask = installing && progress.totalTasks > 0
        if (hasDownloadTask && destination != MainDestination.DOWNLOAD) {
            DownloadTaskCard(
                title = selected?.name ?: "正在准备",
                percent = progress.percent,
                stage = progress.stage.ifBlank { stage.ifBlank { "准备中" } },
                detail = progress.currentFile,
                speedText = if (progress.speedBps > 0) formatSpeed(progress.speedBps) else null,
                etaText = formatEta(progress.etaSeconds),
                fileCountText = if (progress.totalTasks > 0) "${progress.doneTasks}/${progress.totalTasks}" else null,
                onCancel = { vm.cancelDownload() },
                onOpen = { destination = MainDestination.DOWNLOAD },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp)
            )
        }

        // 更新提示：三种状态共用一个弹窗
        updateInfo?.let { info ->
            UpdateDialog(
                info = info,
                downloading = updateDownloading,
                progress = updateProgress,
                readyToInstall = downloadedApk != null,
                status = updateStatus,
                onDownload = { vm.downloadUpdate() },
                onCancelDownload = { vm.cancelUpdateDownload() },
                onInstall = {
                    downloadedApk?.let { installApk(context, it) }
                },
                onDismiss = { vm.dismissUpdate() }
            )
        }

        // 实例操作：改名 / 打开目录 / 删除
        actionTarget?.let { inst ->
            InstanceActionSheet(
                instance = inst,
                sizeBytes = instanceSizes[inst.id],
                onRename = { vm.renameInstance(inst, it) },
                onDelete = {
                    vm.deleteInstance(inst)
                    actionTarget = null
                },
                onOpenFolder = {
                    actionTarget = null
                    openFolder(context, vm.gameDirOf(inst))
                },
                onDismiss = { actionTarget = null }
            )
        }

        // 补全运行依赖：首启 / 升级后 / 点启动被拦下时出现
        if (bundlePending.isNotEmpty() && !setupDismissed) {
            SetupDialog(
                items = bundlePending,
                states = bundleStates,
                phase = bundlePhase,
                onInstall = { vm.installBundles() },
                onDismiss = { vm.dismissSetup() }
            )
        }

        // 失败/提示：把真实原因摊开给用户，而不是静默失败
        error?.let { msg ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { vm.clearError() },
                title = { Text("出错了") },
                text = {
                    Column {
                        Text(msg)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "提示：如果提示未集成内核，请查看 README 的 PojavLauncher 集成章节。",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { vm.clearError() }) { Text("知道了") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        vm.clearError()
                        destination = MainDestination.LOGS
                    }) { Text("看日志") }
                }
            )
        }

        // 控制布局编辑器（全屏浮层）
        if (showControlEditor) {
            ControlEditorFullscreen(
                layout = layout,
                selectedId = selectedWidget,
                onSelect = { selectedWidget = it },
                onMove = { id, x, y ->
                    persistLayout(
                        layout.copy(
                            layers = layout.layers.map { layer ->
                                layer.copy(widgets = layer.widgets.map {
                                    if (it.id == id) it.copy(x = x, y = y) else it
                                })
                            }
                        )
                    )
                },
                onReset = { persistLayout(layoutStore.reset(orientationKey)) },
                onClose = {
                    showControlEditor = false
                    layoutStore.save(orientationKey, layout)
                }
            )
        }

        // 进度遮罩：下载/安装/准备时盖在最上层
        if (installing) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center
            ) {
                GlassCard(
                    Modifier.padding(28.dp),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Box(Modifier.padding(20.dp)) {
                        ProgressBlock(
                            percent = if (progress.percent > 0) progress.percent else 0,
                            stage = progress.stage.ifBlank { stage.ifBlank { "准备中" } },
                            detail = progress.currentFile,
                            speedText = if (progress.speedBps > 0) formatSpeed(progress.speedBps) else null,
                            etaText = formatEta(progress.etaSeconds),
                            fileCountText = if (progress.totalTasks > 0) "${progress.doneTasks}/${progress.totalTasks}" else null
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ControlEditorFullscreen(
    layout: ControlLayout,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onMove: (String, Float, Float) -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit
) {
    Box(Modifier.fillMaxSize().background(Color(0xFF0E1013))) {
        ControlEditorCanvas(
            layout = layout,
            selectedId = selectedId,
            editing = true,
            onSelect = onSelect,
            onMove = onMove,
            modifier = Modifier.fillMaxSize()
        )
        androidx.compose.material3.FilledTonalButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
        ) { Text("完成") }
        androidx.compose.material3.OutlinedButton(
            onClick = onReset,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp)
        ) { Text("恢复默认布局") }
    }
}

/**
 * 用系统文件管理器打开应用私有目录。
 *
 * Android 11+ 不允许随便把 `file://` 分享给别的 App，这里走 DocumentsProvider 暴露的
 * 目录 URI。若系统文件管理器不支持，退化成提示路径 —— 总比静默失败好。
 */
private fun openFolder(context: android.content.Context, dir: java.io.File) {
    runCatching {
        dir.mkdirs()
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(
                android.net.Uri.parse("content://com.android.externalstorage.documents/root/primary"),
                "vnd.android.document/root"
            )
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }.onFailure {
        android.widget.Toast.makeText(
            context,
            "目录：${dir.absolutePath}",
            android.widget.Toast.LENGTH_LONG
        ).show()
    }
}

/**
 * 拉起系统安装器。
 *
 * 走 FileProvider 给 content:// —— Android 7.0 起直接传 file:// 会抛
 * FileUriExposedException。Android 8.0+ 还需要用户先允许「安装未知应用」，
 * 系统会自己弹授权页，这里不用额外处理。
 */
private fun installApk(context: android.content.Context, apk: java.io.File) {
    runCatching {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }.onFailure {
        android.widget.Toast.makeText(
            context,
            "无法启动安装器：${it.message}",
            android.widget.Toast.LENGTH_LONG
        ).show()
    }
}
