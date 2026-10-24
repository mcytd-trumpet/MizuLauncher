package com.mizulauncher.v2

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mizulauncher.v2.state.DarkMode
import com.mizulauncher.v2.ui.MizuRoot
import com.mizulauncher.v2.ui.MizuTab
import com.mizulauncher.ui.design.MizuTheme
import com.mizulauncher.ui.design.MizuThemeConfig
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 2.0 的主界面。
 *
 * ## 相比 1.x 的结构性变化
 * 1.x 的 MainActivity 里塞了导航、权限请求、安装监听、更新检查、控制布局……近千行。
 * 2.0 把它削到只剩三件事：
 *  1. 决定主题参数（深色/动态色/动效档位）；
 *  2. 把 ViewModel 的状态转成 UI 需要的回调；
 *  3. 处理"必须由 Activity 做的事"（打开浏览器）。
 *
 * 其余全部下沉到 ViewModel 与 Compose 层。这样 Activity 重建（旋转屏幕、
 * 切换深色模式）不会丢任何状态——因为状态本来就不在 Activity 里。
 *
 * ## 关于 edge-to-edge
 * `enableEdgeToEdge()` 在 Android 15 上是必须的（系统强制），在低版本上主动开启
 * 才能让底栏颜色延伸到导航条下面。统一开启，再用 `WindowInsets` 在各处避让，
 * 比"按版本写不同分支"稳得多。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val vm: MizuViewModel = viewModel()
            val state by vm.state.collectAsStateWithLifecycle()
            val context = LocalContext.current
            // launch/prepareOnly 是 suspend 的（调试 API 要 await），UI 侧不能阻塞组合，
            // 用 rememberCoroutineScope 在点击时异步触发
            val uiScope = rememberCoroutineScope()

            // 深色模式：跟随系统时要真的跟随——用 isSystemInDarkTheme() 而不是读一次配置
            val systemDark = isSystemInDarkTheme()
            val dark = when (state.settings.darkMode) {
                DarkMode.System -> systemDark
                DarkMode.Light -> false
                DarkMode.Dark -> true
            }

            val config = MizuThemeConfig(
                dark = dark,
                dynamicColor = state.settings.dynamicColor,
                motionLevel = vm.device.motionLevel
            )

            MizuTheme(config = config, profile = vm.device) {
                // 一次性事件：打开外部链接等必须由 Activity 完成的动作
                LaunchedEffect(Unit) {
                    vm.events.collectLatest { event ->
                        when (event) {
                            is MizuViewModel.Event.OpenUrl -> openUrlSafely(context, event.url)
                            else -> Unit
                        }
                    }
                }

                MizuRoot(
                    state = state,
                    deviceAnimations = vm.device.animationsEnabled,
                    onTabSelected = { vm.selectTab(it.route) },
                    onLaunch = { card -> uiScope.launch { vm.launch(card.meta) } },
                    onCancelLaunch = { vm.cancelDownload() },
                    onSelectInstance = { vm.selectInstance(it) },
                    onRenameInstance = { meta, name -> vm.renameInstance(meta, name) },
                    onDeleteInstance = { vm.deleteInstance(it) },
                    onOpenFolder = { card ->
                        // 打开 .minecraft 目录。用系统的文件管理器，
                        // 不自建文件浏览器——那是另一个 App 的活
                        runCatching {
                            val dir = vm.gameDirOf(card.meta)
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(Uri.parse("file://${dir.absolutePath}"), "resource/folder")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        }.onFailure {
                            vm.toast("没有可用的文件管理器", isError = true)
                        }
                    },
                    onLoadVersions = { force -> vm.loadVersions(force) },
                    onVersionQuery = { vm.setVersionQuery(it) },
                    onVersionFilter = { vm.setVersionFilter(it) },
                    onExpandVersion = { vm.expandVersion(it) },
                    onInstallVersion = { entry, loader, ver -> vm.installVersion(entry, loader, ver) },
                    onDismissTask = { vm.dismissTask(it) },
                    onMicrosoftLogin = { vm.startMicrosoftLogin() },
                    onCancelMicrosoftLogin = { vm.cancelMicrosoftLogin() },
                    onYggServer = { vm.setYggServer(it) },
                    onYggEmail = { vm.setYggEmail(it) },
                    onYggPassword = { vm.setYggPassword(it) },
                    onYggDiscover = { vm.discoverServer() },
                    onYggLogin = { vm.loginYggdrasil() },
                    onYggLogout = { vm.logoutYggdrasil() },
                    onLogoutAll = { vm.logout() },
                    onRuntimeInstall = { vm.installRuntime(it) },
                    onRuntimeRemove = { vm.removeRuntime(it) },
                    onRuntimeRefresh = { vm.refreshRuntime() },
                    onDarkMode = { vm.setDarkMode(it) },
                    onDynamicColor = { vm.setDynamicColor(it) },
                    onSource = { vm.setDownloadSource(it) },
                    onInstallBundles = { vm.installBundles() },
                    onDismissSetup = { vm.dismissSetup() },
                    onConsumeToast = { vm.consumeToast() },
                    onDismissError = { vm.clearLaunchError() },
                    logLines = state.logLines,
                    onClearLog = { vm.clearLog() }
                )
            }
        }
    }

    /**
     * 打开外部链接，失败时给出可读提示而不是崩溃。
     *
     * 老设备上 `ActivityNotFoundException` 很常见（没装浏览器、或用的是
     * 精简 ROM 的裸 WebView）。这里必须兜住——否则点一次"微软登录"就闪退。
     */
    private fun openUrlSafely(context: Context, url: String) {
        if (url.isBlank()) return
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
