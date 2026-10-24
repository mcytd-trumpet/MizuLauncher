package com.mizulauncher.ui.screens.dependencies

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mizulauncher.setup.BundleCatalog
import com.mizulauncher.setup.BundleInstaller
import com.mizulauncher.ui.components.EmptyState
import com.mizulauncher.ui.components.GlassCard
import com.mizulauncher.ui.components.Tag
import com.mizulauncher.ui.components.humanSize
import com.mizulauncher.ui.theme.MizuIcons

/**
 * 运行依赖总览。
 *
 * 首启弹窗只会在「有东西没装」时出现，用户点掉之后就找不到入口了。
 * 这一页是常驻的：能看到装了哪些、占多大、哪些待补，也能手动重新扫描。
 * 排查「为什么启动报找不到 java」时，先看这里就对了。
 */
@Composable
fun DependenciesScreen(
    items: List<BundleCatalog.Item>,
    pending: List<BundleCatalog.Item>,
    states: Map<String, BundleInstaller.ItemState>,
    phase: BundleInstaller.Phase,
    abi: String,
    onInstall: () -> Unit,
    onRescan: () -> Unit,
    modifier: Modifier = Modifier
) {
    val running = phase is BundleInstaller.Phase.Running
    val pendingKeys = pending.map { it.key }.toSet()
    val installedBytes = items
        .filter { it.key !in pendingKeys && states[it.key] !is BundleInstaller.ItemState.Failed }
        .sumOf { if (it.sizeBytes > 0) it.sizeBytes else 0L }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            MizuIcons.Extension,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("运行依赖", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Tag(abi, color = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "这些组件打包在安装包里，首次使用或更新后需要解压到应用目录。解压不消耗流量。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                when {
                                    items.isEmpty() -> "当前安装包没有内置组件"
                                    pending.isEmpty() -> "已全部就绪（${items.size} 项）"
                                    else -> "${pending.size} 项待补全 / 共 ${items.size} 项"
                                },
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (installedBytes > 0) {
                                Text(
                                    "已解压约 ${humanSize(installedBytes)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        OutlinedButton(onClick = onRescan, enabled = !running) { Text("重新扫描") }
                        if (pending.isNotEmpty()) {
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = onInstall, enabled = !running) {
                                Text(if (running) "解压中…" else "补全")
                            }
                        }
                    }

                    if (phase is BundleInstaller.Phase.Running) {
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { phase.percent / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "${phase.currentLabel} · ${phase.percent}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (phase is BundleInstaller.Phase.Failed) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            phase.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        if (items.isEmpty()) {
            item {
                EmptyState(
                    icon = MizuIcons.Storage,
                    title = "没有可解压的组件",
                    description = "这个 APK 未内置 Java 运行时，首次启动会按需从网络下载。",
                    modifier = Modifier.fillMaxWidth().height(220.dp)
                )
            }
        } else {
            items(items, key = { it.key }) { item ->
                DependencyRow(
                    item = item,
                    pending = item.key in pendingKeys,
                    state = states[item.key]
                )
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun DependencyRow(
    item: BundleCatalog.Item,
    pending: Boolean,
    state: BundleInstaller.ItemState?
) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                val (text, color) = when (state) {
                    is BundleInstaller.ItemState.Installing ->
                        "${state.percent}%" to MaterialTheme.colorScheme.tertiary
                    is BundleInstaller.ItemState.Done ->
                        "已就绪" to MaterialTheme.colorScheme.primary
                    is BundleInstaller.ItemState.Failed ->
                        "失败" to MaterialTheme.colorScheme.error
                    else -> if (pending) "待补全" to MaterialTheme.colorScheme.tertiary
                    else "已就绪" to MaterialTheme.colorScheme.primary
                }
                Tag(text, color = color)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                item.description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(2.dp))
            Row {
                Text(
                    item.assetPath,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                val size = humanSize(item.sizeBytes)
                if (size.isNotEmpty()) {
                    Text(
                        size,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (state is BundleInstaller.ItemState.Failed) {
                Spacer(Modifier.height(6.dp))
                Text(
                    state.message,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
