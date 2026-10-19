package com.mizulauncher.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mizulauncher.bridge.Renderer
import com.mizulauncher.download.DownloadSource
import com.mizulauncher.ui.components.GlassCard
import com.mizulauncher.ui.components.SectionHeader
import com.mizulauncher.ui.components.SettingRow
import com.mizulauncher.ui.theme.MizuIcons

/**
 * 设置页：分组卡片（下载 / 游戏 / 外观 / 关于），参考 ZalithLauncher2 的设置分组。
 */
@Composable
fun SettingsScreen(
    source: DownloadSource,
    threads: Int,
    memoryMb: Int,
    renderer: Renderer,
    darkMode: Boolean?,
    backgroundPath: String?,
    runtimeManifestUrl: String,
    accountName: String,
    onSourceChange: (DownloadSource) -> Unit,
    onThreadsChange: (Int) -> Unit,
    onMemoryChange: (Int) -> Unit,
    onRendererChange: (Renderer) -> Unit,
    onThemeChange: (Boolean?) -> Unit,
    onPickBackground: () -> Unit,
    onClearBackground: () -> Unit,
    onRuntimeManifestChange: (String) -> Unit,
    onAccountChange: (String) -> Unit,
    onOpenControlEditor: () -> Unit,
    onOpenRuntime: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier
) {
    var accountInput by remember(accountName) { mutableStateOf(accountName) }
    var runtimeManifestInput by remember(runtimeManifestUrl) { mutableStateOf(runtimeManifestUrl) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ---------- 下载 ----------
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 8.dp)) {
                    SectionHeader("下载", Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                    SettingRow(
                        title = "下载源",
                        subtitle = "镜像失效会自动回退官方，并做 SHA1 校验",
                        icon = MizuIcons.Wifi
                    )
                    Row(
                        Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        DownloadSource.entries.forEach { s ->
                            FilterChip(
                                selected = source == s,
                                onClick = { onSourceChange(s) },
                                label = { Text(s.displayName, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    SettingRow(
                        title = "并发线程数",
                        subtitle = "$threads 线程 · 建议 4~8（过多反而更慢）",
                        icon = MizuIcons.Speed
                    )
                    Slider(
                        value = threads.toFloat(),
                        onValueChange = { onThreadsChange(it.toInt()) },
                        valueRange = 1f..16f,
                        steps = 14,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }
        }

        // ---------- 游戏 ----------
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 8.dp)) {
                    SectionHeader("游戏", Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                    SettingRow(
                        title = "内存分配",
                        subtitle = "$memoryMb MB（过大易被系统杀后台）",
                        icon = MizuIcons.Memory
                    )
                    Slider(
                        value = memoryMb.toFloat(),
                        onValueChange = { onMemoryChange((it / 128).toInt() * 128) },
                        valueRange = 512f..8192f,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    SettingRow(
                        title = "渲染器",
                        subtitle = renderer.display,
                        icon = MizuIcons.Texture
                    )
                    Row(
                        Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Renderer.entries.filter { it != Renderer.CUSTOM }.forEach { r ->
                            FilterChip(
                                selected = renderer == r,
                                onClick = { onRendererChange(r) },
                                label = { Text(r.display.substringBefore(' '), style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                    SettingRow(
                        title = "运行时清单地址",
                        subtitle = "留空则只用 APK 内置的 Java；填 jre-index.json 的 URL 可补齐未内置版本",
                        icon = MizuIcons.Storage,
                        trailing = {
                            androidx.compose.material3.OutlinedTextField(
                                value = runtimeManifestInput,
                                onValueChange = {
                                    runtimeManifestInput = it
                                    onRuntimeManifestChange(it)
                                },
                                singleLine = true,
                                modifier = Modifier.width(150.dp),
                                textStyle = MaterialTheme.typography.bodySmall,
                                shape = RoundedCornerShape(10.dp)
                            )
                        }
                    )
                    SettingRow(
                        title = "按键布局",
                        subtitle = "可视化编辑摇杆、按键、视角区域",
                        icon = MizuIcons.Control,
                        onClick = onOpenControlEditor
                    )
                }
            }
        }

        // ---------- 账号 ----------
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 8.dp)) {
                    SectionHeader("账号", Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                    SettingRow(
                        title = "离线用户名",
                        subtitle = "UUID 按 OfflinePlayer 规则生成",
                        icon = MizuIcons.Person,
                        trailing = {
                            androidx.compose.material3.OutlinedTextField(
                                value = accountInput,
                                onValueChange = {
                                    accountInput = it
                                    onAccountChange(it)
                                },
                                singleLine = true,
                                modifier = Modifier.width(140.dp),
                                textStyle = MaterialTheme.typography.bodyMedium,
                                shape = RoundedCornerShape(10.dp)
                            )
                        }
                    )
                }
            }
        }

        // ---------- 外观 ----------
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 8.dp)) {
                    SectionHeader("外观", Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                    SettingRow(
                        title = "深色模式",
                        subtitle = when (darkMode) {
                            true -> "深色"
                            false -> "浅色"
                            null -> "跟随系统"
                        },
                        icon = if (darkMode == true) MizuIcons.Dark else MizuIcons.Light,
                        trailing = {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                FilterChip(selected = darkMode == null, onClick = { onThemeChange(null) }, label = { Text("自动", style = MaterialTheme.typography.labelSmall) })
                                FilterChip(selected = darkMode == false, onClick = { onThemeChange(false) }, label = { Text("浅色", style = MaterialTheme.typography.labelSmall) })
                                FilterChip(selected = darkMode == true, onClick = { onThemeChange(true) }, label = { Text("深色", style = MaterialTheme.typography.labelSmall) })
                            }
                        }
                    )
                    SettingRow(
                        title = "背景图片",
                        subtitle = backgroundPath?.substringAfterLast('/') ?: "未设置",
                        icon = MizuIcons.Palette,
                        trailing = {
                            androidx.compose.material3.TextButton(onClick = onPickBackground) { Text("选择") }
                        }
                    )
                    if (backgroundPath != null) {
                        SettingRow(
                            title = "清除背景",
                            icon = MizuIcons.Delete,
                            onClick = onClearBackground
                        )
                    }
                }
            }
        }

        // ---------- 其他 ----------
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 8.dp)) {
                    SectionHeader("其他", Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                    SettingRow(
                        title = "Java 运行时",
                        subtitle = "预装 / 下载 Java 8、17、21、25",
                        icon = MizuIcons.Memory,
                        onClick = onOpenRuntime
                    )
                    SettingRow(title = "运行日志", icon = MizuIcons.Log, onClick = onOpenLogs)
                    SettingRow(title = "关于 Mizu Launcher", icon = MizuIcons.Info, onClick = onOpenAbout)
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
