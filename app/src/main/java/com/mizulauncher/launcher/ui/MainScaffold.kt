package com.mizulauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mizulauncher.ui.theme.MizuIcons

/**
 * 响应式主容器：手机底部导航 / 平板与横屏左侧导航栏（NavigationRail）。
 *
 * ZalithLauncher2 在横屏时用 40dp 顶栏 + 右侧图标按钮；
 * 本实现按 Material3 自适应规范：宽度 ≥ 600dp 用 Rail，否则用 BottomBar。
 */
enum class MainDestination(val label: String, val icon: ImageVector) {
    HOME("主页", MizuIcons.Home),
    DOWNLOAD("下载", MizuIcons.Download),
    SETTINGS("设置", MizuIcons.Settings),
    /** 以下几项不进底栏，仅由设置页跳转 */
    RUNTIME("Java 运行时", MizuIcons.Memory),
    LOGS("运行日志", MizuIcons.Log),
    ABOUT("关于", MizuIcons.Info);

    /** 是否显示在底部导航栏 / 侧边栏 */
    val isPrimary: Boolean get() = this == HOME || this == DOWNLOAD || this == SETTINGS
}

@Composable
fun MainScaffold(
    current: MainDestination,
    useRail: Boolean,
    onNavigate: (MainDestination) -> Unit,
    content: @Composable (Modifier) -> Unit
) {
    val backgroundColor = MaterialTheme.colorScheme.background

    if (useRail) {
        Row(
            Modifier
                .fillMaxSize()
                .background(backgroundColor)
        ) {
            NavigationRail(containerColor = Color.Transparent) {
                Box(Modifier.height(24.dp))
                MainDestination.entries.filter { it.isPrimary }.forEach { d ->
                    NavigationRailItem(
                        selected = current == d,
                        onClick = { onNavigate(d) },
                        icon = { Icon(d.icon, contentDescription = d.label) },
                        label = { Text(d.label, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }
            content(Modifier.weight(1f))
        }
    } else {
        Scaffold(
            containerColor = backgroundColor,
            bottomBar = {
                NavigationBar(containerColor = Color.Transparent, tonalElevation = 0.dp) {
                    MainDestination.entries.filter { it.isPrimary }.forEach { d ->
                        NavigationBarItem(
                            selected = current == d,
                            onClick = { onNavigate(d) },
                            icon = { Icon(d.icon, contentDescription = d.label) },
                            label = { Text(d.label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }
        ) { padding ->
            content(Modifier.padding(padding))
        }
    }
}

/** 启动闪屏（Zalith 风格的纯色 + 大字 logo + 版本号） */
@Composable
fun SplashContent(progress: Float = 0f, status: String = "正在初始化…") {
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.92f),
                        MaterialTheme.colorScheme.secondary.copy(alpha = 0.92f)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(84.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(Color.White.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Text("P", color = Color.White, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            }
            Box(Modifier.height(20.dp))
            Text("Mizu Launcher", color = Color.White, style = MaterialTheme.typography.headlineMedium)
            Text(
                "Minecraft Java Edition · Android",
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodyMedium
            )
            Box(Modifier.height(28.dp))
            Box(
                Modifier
                    .width(160.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.25f))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(Color.White)
                )
            }
            Box(Modifier.height(10.dp))
            Text(status, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelSmall)
        }
    }
}
