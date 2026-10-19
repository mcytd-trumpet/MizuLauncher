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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mizulauncher.ui.MainDestination
import com.mizulauncher.ui.MainScaffold
import com.mizulauncher.ui.SplashContent
import com.mizulauncher.ui.components.GlassCard
import com.mizulauncher.ui.components.ProgressBlock
import com.mizulauncher.ui.screens.about.AboutScreen
import com.mizulauncher.ui.screens.control.ControlEditorCanvas
import com.mizulauncher.ui.screens.control.ControlLayout
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

    var darkSetting by remember { mutableStateOf<Boolean?>(null) }
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
            AppContent(onThemeChange = { darkSetting = it })
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
private fun AppContent(onThemeChange: (Boolean?) -> Unit) {
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
    val runtimeStates by vm.runtimeStates.collectAsStateWithLifecycle()

    var destination by remember { mutableStateOf(MainDestination.HOME) }
    var currentSource by remember { mutableStateOf(settings.downloadSource) }
    var accountName by remember { mutableStateOf(settings.lastAccount) }
    var backgroundVersion by remember { mutableStateOf(0) }

    // 安装完成 → 自动回主页并选中新实例（避免用户以为「没反应」）
    val installDone by vm.installDone.collectAsStateWithLifecycle()
    LaunchedEffect(installDone) {
        if (installDone) {
            destination = MainDestination.HOME
            vm.consumeInstallDone()
        }
    }

    // 控制编辑器（纯 UI 态，不需要跨页面存活）
    var showControlEditor by remember { mutableStateOf(false) }
    var layout by remember { mutableStateOf(ControlLayout()) }
    var selectedWidget by remember { mutableStateOf<String?>(null) }

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
                    accountName = accountName,
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
                        destination = MainDestination.SETTINGS
                    },
                    onOpenAccount = { destination = MainDestination.SETTINGS },
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
                    darkMode = null,
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
                    onOpenRuntime = { destination = MainDestination.RUNTIME },
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
                    layout = layout.copy(
                        layers = layout.layers.map { layer ->
                            layer.copy(widgets = layer.widgets.map {
                                if (it.id == id) it.copy(x = x, y = y) else it
                            })
                        }
                    )
                },
                onClose = { showControlEditor = false }
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
                            speedText = formatSpeed(progress.speedBps)
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
    }
}
