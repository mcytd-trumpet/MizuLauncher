package com.mizulauncher.v2.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mizulauncher.compat.MotionLevel
import com.mizulauncher.v2.state.InstanceCard
import com.mizulauncher.v2.state.UiState
import com.mizulauncher.v2.ui.components.InstanceActionsSheet
import com.mizulauncher.v2.ui.components.SetupDialog
import com.mizulauncher.v2.ui.screens.AboutScreen
import com.mizulauncher.v2.ui.screens.AccountScreen
import com.mizulauncher.v2.ui.screens.DownloadScreen
import com.mizulauncher.v2.ui.screens.HomeScreen
import com.mizulauncher.v2.ui.screens.LogScreen
import com.mizulauncher.v2.ui.screens.RuntimeScreen
import com.mizulauncher.v2.ui.screens.SettingsScreen
import com.mizulauncher.ui.design.MizuTransitions
import com.mizulauncher.ui.design.currentMotionLevel

/**
 * 2.0 的根界面。
 *
 * ## 导航方式
 * 不用 Navigation-Compose：这套应用只有"五个 tab + 三个二级页"，
 * 层级非常浅，用 Navigation 反而要引入 route 字符串、参数序列化、返回栈状态恢复
 * 这一整套东西（还经常在 process death 后恢复出错）。
 *
 * 这里用**两级状态 + 手动返回栈**：
 * ```
 * selectedTab  ← 底部导航
 * overlay      ← 可空，非空时盖住 tab 内容（日志/关于/实例详情）
 * ```
 * 只有一层 overlay，按返回就关掉它，再按一次退出——行为完全可预测。
 * 对这么浅的层级来说，这是最稳的实现。
 *
 * ## 动效分工
 * - 底部导航切页：`tabEnter/tabExit`（淡入 + 微上浮，不暗示层级）
 * - 打开二级页：`forwardEnter/forwardExit`（右侧滑入）
 * - 关闭二级页：`backEnter/backExit`（镜像）
 * 三者都按 [MotionLevel] 自动降级。
 */
@Composable
fun MizuRoot(
    state: UiState,
    deviceAnimations: Boolean,
    onTabSelected: (MizuTab) -> Unit,
    onLaunch: (InstanceCard) -> Unit,
    onCancelLaunch: () -> Unit,
    onSelectInstance: (String) -> Unit,
    onRenameInstance: (com.mizulauncher.instance.InstanceMeta, String) -> Unit,
    onDeleteInstance: (com.mizulauncher.instance.InstanceMeta) -> Unit,
    onOpenFolder: (InstanceCard) -> Unit,
    onLoadVersions: (Boolean) -> Unit,
    onVersionQuery: (String) -> Unit,
    onVersionFilter: (com.mizulauncher.v2.state.VersionFilter) -> Unit,
    onExpandVersion: (String?) -> Unit,
    onInstallVersion: (com.mizulauncher.minecraft.model.VersionEntry, String, String) -> Unit,
    onDismissTask: (String) -> Unit,
    onMicrosoftLogin: () -> Unit,
    onCancelMicrosoftLogin: () -> Unit,
    onYggServer: (String) -> Unit,
    onYggEmail: (String) -> Unit,
    onYggPassword: (String) -> Unit,
    onYggDiscover: () -> Unit,
    onYggLogin: () -> Unit,
    onYggLogout: () -> Unit,
    onLogoutAll: () -> Unit,
    onRuntimeInstall: (Int) -> Unit,
    onRuntimeRemove: (Int) -> Unit,
    onRuntimeRefresh: () -> Unit,
    onDarkMode: (com.mizulauncher.v2.state.DarkMode) -> Unit,
    onDynamicColor: (Boolean) -> Unit,
    onSource: (com.mizulauncher.download.DownloadSource) -> Unit,
    onInstallBundles: () -> Unit,
    onDismissSetup: () -> Unit,
    onConsumeToast: () -> Unit,
    onDismissError: () -> Unit,
    logLines: List<String>,
    onClearLog: () -> Unit,
    modifier: Modifier = Modifier
) {
    var overlay by rememberSaveable { mutableStateOf<String?>(null) }
    var menuCard by remember { mutableStateOf<InstanceCard?>(null) }

    val currentTab = MizuTab.ofRoute(state.currentTab())

    // ---- 返回键：先关 overlay，再交给系统 ----
    BackHandler(enabled = overlay != null) { overlay = null }

    // ---- Snackbar ----
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.toast?.id) {
        val toast = state.toast ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(toast.message)
        onConsumeToast()
    }

    // 首次拉版本清单（下载页一打开就要有数据）
    LaunchedEffect(Unit) { onLoadVersions(false) }

    val level = currentMotionLevel

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        bottomBar = {
            // 二级页盖住底部导航：它们是"深入"，不是"并列"
            AnimatedVisibility(
                visible = overlay == null,
                enter = if (level == MotionLevel.None) androidx.compose.animation.EnterTransition.None
                else slideInVertically { it } + fadeIn(),
                exit = if (level == MotionLevel.None) androidx.compose.animation.ExitTransition.None
                else slideOutVertically { it } + fadeOut()
            ) {
                MizuBottomBar(current = currentTab, onSelect = onTabSelected)
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
            // 顶部状态栏避让。用 windowInsetsPadding 而不是固定高度，
            // 刘海屏/灵动岛/折叠屏的内缩量各不相同，写死会错位
            Box(Modifier.windowInsetsPadding(WindowInsets.statusBars))

            // 全局任务条：切页时也能看到"还在装"
            androidx.compose.animation.AnimatedVisibility(
                visible = state.download.activeTaskCount > 0 && overlay == null,
                enter = slideInVertically { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut()
            ) {
                val active = state.download.tasks.values.firstOrNull { it.phase.isActive }
                GlobalTaskBar(
                    text = active?.let { "${it.phase.label} · ${it.detail.ifBlank { it.versionId }}" } ?: "正在处理",
                    progress = active?.progress ?: 0f
                )
            }

            Box(Modifier.fillMaxSize()) {
                // ---------------- 一级页面（tab） ----------------
                // transitionSpec 是 @Composable 作用域，必须先取出转场再交给 AnimatedContent
                val tabEnter = MizuTransitions.tabEnter()
                val tabExit = MizuTransitions.tabExit()
                AnimatedContent(
                    targetState = currentTab,
                    transitionSpec = { tabEnter togetherWith tabExit },
                    label = "tabContent"
                ) { tab ->
                    when (tab) {
                        MizuTab.Home -> HomeScreen(
                            state = state.home,
                            onSelect = onSelectInstance,
                            onLaunch = onLaunch,
                            onCancelLaunch = onCancelLaunch,
                            onOpenDownload = { onTabSelected(MizuTab.Download) },
                            onInstanceMenu = { menuCard = it },
                            onDismissError = onDismissError
                        )

                        MizuTab.Download -> DownloadScreen(
                            state = state.download,
                            onQuery = onVersionQuery,
                            onFilter = onVersionFilter,
                            onExpand = onExpandVersion,
                            onInstall = { entry, loader, ver -> onInstallVersion(entry, loader, ver) },
                            onDismissTask = onDismissTask,
                            onRetry = { onLoadVersions(true) }
                        )

                        MizuTab.Account -> AccountScreen(
                            state = state.account,
                            onMicrosoftLogin = onMicrosoftLogin,
                            onCancelMicrosoftLogin = onCancelMicrosoftLogin,
                            onServerChange = onYggServer,
                            onEmailChange = onYggEmail,
                            onPasswordChange = onYggPassword,
                            onDiscover = onYggDiscover,
                            onYggLogin = onYggLogin,
                            onYggLogout = onYggLogout,
                            onLogoutAll = onLogoutAll
                        )

                        MizuTab.Runtime -> RuntimeScreen(
                            state = state.runtime,
                            onInstall = onRuntimeInstall,
                            onRemove = onRuntimeRemove,
                            onRefresh = onRuntimeRefresh
                        )

                        MizuTab.Settings -> SettingsScreen(
                            state = state.settings,
                            onDarkMode = onDarkMode,
                            onDynamicColor = onDynamicColor,
                            onSource = onSource,
                            onOpenLog = { overlay = MizuRoutes.LOG },
                            onOpenAbout = { overlay = MizuRoutes.ABOUT }
                        )
                    }
                }

                // ---------------- 二级页面（overlay） ----------------
                androidx.compose.animation.AnimatedVisibility(
                    visible = overlay != null,
                    enter = MizuTransitions.forwardEnter(),
                    exit = MizuTransitions.forwardExit()
                ) {
                    androidx.compose.material3.Surface(
                        color = MaterialTheme.colorScheme.background,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        when (overlay) {
                            MizuRoutes.LOG -> Column {
                                SimpleTopBar(title = "运行日志", onBack = { overlay = null })
                                LogScreen(lines = logLines, onClear = onClearLog)
                            }
                            MizuRoutes.ABOUT -> Column {
                                SimpleTopBar(title = "关于", onBack = { overlay = null })
                                AboutScreen(
                                    versionName = state.settings.appVersion,
                                    device = state.device,
                                    onOpenLicense = { }
                                )
                            }
                            else -> {
                                // overlay 被清空但 AnimatedVisibility 还在播退出动画
                            }
                        }
                    }
                }
            }
        }
    }

    // ---------------- 弹层 ----------------
    menuCard?.let { card ->
        InstanceActionsSheet(
            card = card,
            onDismiss = { menuCard = null },
            onLaunch = { onLaunch(card) },
            onRename = { onRenameInstance(card.meta, it) },
            onDelete = { onDeleteInstance(card.meta) },
            onOpenFolder = { onOpenFolder(card) }
        )
    }

    SetupDialog(
        state = state.setup,
        onInstall = onInstallBundles,
        onDismiss = onDismissSetup
    )
}
