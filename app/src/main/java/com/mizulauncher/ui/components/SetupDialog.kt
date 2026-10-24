package com.mizulauncher.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mizulauncher.setup.BundleCatalog
import com.mizulauncher.setup.BundleInstaller
import com.mizulauncher.ui.theme.MizuIcons
import java.util.Locale

/**
 * 首启 / 更新后的「补全运行依赖」对话框。
 *
 * ## 为什么必须有这一步
 * APK 里带着 Java 运行时、LWJGL 之类的大文件（都是 zip），必须解压到私有目录才能用。
 * 如果不在用户眼皮底下做，第一次点「开始游戏」就会看到一堆
 * `Cannot run program "java"`、`liblwjgl.so not found`，用户只会认为「这启动器是坏的」。
 *
 * ZalithLauncher2 的做法是把清单摊开、让用户点一次 Install，这里保持一致：
 *  - 列出**每一项**组件与体积（不联网，都是包里自带的）；
 *  - 点「安装」后逐项显示解压进度，看得见就不会觉得卡死；
 *  - 装完写入版本指纹，下次启动不再打扰；
 *  - 升级 APK 带来新版本组件时，指纹变化 → 再次弹出（这正是 ZL2 的行为）。
 */
@Composable
fun SetupDialog(
    items: List<BundleCatalog.Item>,
    states: Map<String, BundleInstaller.ItemState>,
    phase: BundleInstaller.Phase,
    onInstall: () -> Unit,
    onDismiss: () -> Unit
) {
    val running = phase is BundleInstaller.Phase.Running
    val totalBytes = items.sumOf { if (it.sizeBytes > 0) it.sizeBytes else 0L }
    val totalText = humanSize(totalBytes)
    val doneCount = items.count { states[it.key] is BundleInstaller.ItemState.Done }

    androidx.compose.material3.AlertDialog(
        // 解压过程中不允许随手点外面关掉：中途退出会留下半截目录
        onDismissRequest = { if (!running) onDismiss() },
        title = {
            Column {
                Text(
                    if (items.isEmpty()) "依赖已补全" else "需要解压运行组件",
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    if (totalText.isEmpty()) "共 ${items.size} 项" else "共 ${items.size} 项 · 约 $totalText",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(Modifier.heightIn(max = 420.dp)) {
                Text(
                    "这些文件已经打包在安装包里，无需下载，只需解压到应用目录后才能启动游戏。" +
                        "首次使用和每次更新后可能需要做一次。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))

                Column(
                    Modifier
                        .heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items.forEach { item ->
                        SetupItemRow(item = item, state = states[item.key])
                    }

                    if (items.isEmpty()) {
                        Text(
                            "当前安装包没有内置需要解压的组件。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (phase is BundleInstaller.Phase.Running) {
                    Spacer(Modifier.height(14.dp))
                    LinearProgressIndicator(
                        progress = { phase.percent / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "正在解压 ${phase.currentLabel} · ${phase.percent}%（$doneCount/${items.size}）",
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
        },
        confirmButton = {
            when {
                running -> Button(onClick = {}, enabled = false) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("解压中…")
                    }
                }
                items.isEmpty() -> Button(onClick = onDismiss) { Text("完成") }
                else -> Button(onClick = onInstall) { Text("安装") }
            }
        },
        dismissButton = {
            // 解压中不给「稍后」：半途退出最容易得到坏掉的运行时
            if (!running && items.isNotEmpty()) {
                TextButton(onClick = onDismiss) { Text("稍后") }
            } else if (items.isEmpty()) {
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
    )
}

@Composable
private fun SetupItemRow(item: BundleCatalog.Item, state: BundleInstaller.ItemState?) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                when (state) {
                    is BundleInstaller.ItemState.Done -> Icon(
                        MizuIcons.Check,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    is BundleInstaller.ItemState.Failed -> Icon(
                        MizuIcons.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    is BundleInstaller.ItemState.Installing -> CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp
                    )
                    else -> Icon(
                        MizuIcons.Download,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(Modifier.width(10.dp))

            Column(Modifier.weight(1f)) {
                Text(item.label, style = MaterialTheme.typography.bodyMedium)
                Text(
                    when (state) {
                        is BundleInstaller.ItemState.Installing ->
                            if (state.percent >= 100) "写入中…" else "解压中 ${state.percent}%"
                        is BundleInstaller.ItemState.Failed -> state.message
                        is BundleInstaller.ItemState.Done -> "已就绪"
                        else -> item.description
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = when (state) {
                        is BundleInstaller.ItemState.Failed -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 2
                )
            }

            val size = humanSize(item.sizeBytes)
            if (size.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    size,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 1520000 → "1.5 MB"；负值（拿不到长度）返回空串，UI 上直接不显示 */
internal fun humanSize(bytes: Long): String {
    if (bytes <= 0) return ""
    val mb = bytes / 1024.0 / 1024.0
    return when {
        mb >= 1024 -> String.format(Locale.US, "%.2f GB", mb / 1024)
        mb >= 1 -> String.format(Locale.US, "%.1f MB", mb)
        else -> "${bytes / 1024} KB"
    }
}
