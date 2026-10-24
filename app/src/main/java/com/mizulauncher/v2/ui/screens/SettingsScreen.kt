package com.mizulauncher.v2.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.mizulauncher.download.DownloadSource
import com.mizulauncher.ui.design.MizuCard
import com.mizulauncher.ui.design.MizuBadge
import com.mizulauncher.ui.design.mizuColors
import com.mizulauncher.v2.state.DarkMode
import com.mizulauncher.v2.state.SettingsState
import com.mizulauncher.v2.ui.SectionHeader

/**
 * 设置页。
 *
 * ## 分组而不是长列表
 * 旧版设置是 20 多项平铺，滚动时完全失去上下文。这里按"我在调什么"分组：
 * 外观 / 下载 / 运行时 / 关于。每组之间留白，视觉上就有停顿。
 *
 * ## 每一项都是一行 `标题 + 副标题 + 控件`
 * 副标题不是废话，是解释"改了这个会发生什么"。比如动态取色要写明
 * "只有 Android 12 及以上支持"，否则低版本用户会以为开关坏了。
 */
@Composable
fun SettingsScreen(
    state: SettingsState,
    onDarkMode: (DarkMode) -> Unit,
    onDynamicColor: (Boolean) -> Unit,
    onSource: (DownloadSource) -> Unit,
    onOpenLog: () -> Unit,
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // ---------------- 外观 ----------------
        item("appearance_header") { SectionHeader(title = "外观") }
        item("appearance") {
            MizuCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 4.dp)) {
                DarkModeRow(state.darkMode, onDarkMode)
                HorizontalDivider(Modifier.padding(horizontal = 14.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                SwitchRow(
                    icon = Icons.Rounded.Palette,
                    title = "动态取色",
                    subtitle = "跟随壁纸生成配色。需要 Android 12 及以上",
                    checked = state.dynamicColor,
                    onCheckedChange = onDynamicColor
                )
            }
        }

        // ---------------- 下载 ----------------
        item("download_header") { SectionHeader(title = "下载") }
        item("download") {
            MizuCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 4.dp)) {
                DownloadSource.entries.forEachIndexed { index, source ->
                    SourceRow(
                        source = source,
                        selected = state.downloadSource == source.name,
                        onSelect = { onSource(source) }
                    )
                    if (index != DownloadSource.entries.lastIndex) {
                        HorizontalDivider(
                            Modifier.padding(horizontal = 14.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        )
                    }
                }
            }
        }

        // ---------------- 其他 ----------------
        item("other_header") { SectionHeader(title = "其他") }
        item("other") {
            MizuCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 4.dp)) {
                NavRow(Icons.Rounded.BugReport, "运行日志", "排查启动失败时先看这里", onOpenLog)
                HorizontalDivider(Modifier.padding(horizontal = 14.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                NavRow(Icons.Rounded.Info, "关于", "版本 ${state.appVersion.ifBlank { "2.0.0" }}", onOpenAbout)
            }
        }

        // ---------------- 环境信息 ----------------
        item("env_header") { SectionHeader(title = "运行环境") }
        item("env") {
            MizuCard(
                modifier = Modifier.fillMaxWidth(),
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                EnvLine("启动器版本", state.appVersion.ifBlank { "2.0.0" })
                Spacer(Modifier.height(6.dp))
                EnvLine("WebView 内核", state.webViewStatus.ifBlank { "检测中…" })
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "WebView 只用于显示公告等网页内容，界面本身完全由原生绘制，老设备不会因为内核缺失而无法启动。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun EnvLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(110.dp)
        )
        Text(text = value, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * 深色模式三选一。
 *
 * 用三个横排单选而不是一个开关：只有开关的话，"跟随系统"这个最常见的
 * 默认值就没有位置了。
 */
@Composable
private fun DarkModeRow(current: DarkMode, onSelect: (DarkMode) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.DarkMode,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(12.dp))
            Text("主题模式", style = MaterialTheme.typography.bodyLarge)
        }

        Spacer(Modifier.height(6.dp))

        Row(
            Modifier.fillMaxWidth().padding(start = 44.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            DarkMode.entries.forEach { mode ->
                val label = when (mode) {
                    DarkMode.System -> "跟随系统"
                    DarkMode.Light -> "浅色"
                    DarkMode.Dark -> "深色"
                }
                val selected = current == mode
                Row(
                    Modifier
                        .clip(MaterialTheme.shapes.small)
                        .selectable(selected = selected, onClick = { onSelect(mode) })
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = selected, onClick = { onSelect(mode) })
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.width(6.dp))
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SourceRow(source: DownloadSource, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(
                    if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                    else MaterialTheme.colorScheme.surfaceContainerHighest
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.Wifi,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(source.displayName, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = sourceDescription(source),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (selected) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = "当前使用",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/** 每个下载源给一句话说明。用户不懂"BMCLAPI"是什么，但懂"国内加速" */
private fun sourceDescription(source: DownloadSource): String = when (source) {
    DownloadSource.Official -> "Mojang 官方，海外或直连较快时选它"
    DownloadSource.Bmclapi -> "国内镜像，速度快、稳定，推荐"
    DownloadSource.Mcim -> "镜像质量较高，作为备用"
}

@Composable
private fun NavRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .androidxClickable(onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            Icons.Rounded.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun Modifier.androidxClickable(onClick: () -> Unit): Modifier =
    this.then(Modifier.clickable(onClick = onClick))
