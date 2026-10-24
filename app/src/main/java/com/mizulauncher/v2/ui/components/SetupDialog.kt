package com.mizulauncher.v2.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mizulauncher.setup.BundleInstaller
import com.mizulauncher.ui.design.MizuBadge
import com.mizulauncher.ui.design.mizuColors
import com.mizulauncher.v2.state.SetupState
import com.mizulauncher.v2.ui.formatSize

/**
 * 首启「补全运行依赖」对话框。
 *
 * ## 为什么这个必须存在
 * 游戏要跑起来依赖三样东西：JRE、authlib-injector、natives。
 * 缺任何一样，启动失败的表现都是「闪一下就退」，用户完全无从下手。
 *
 * 1.0 的做法是启动时才检查、失败才提示——那时用户已经在"等游戏"的心流里，
 * 突然告诉他"先去补依赖"，体验极差。
 *
 * 2.0 在**首启就一次性说清楚**：缺什么、多大、要多久，一次点完。
 *
 * ## 为什么不可取消
 * 点"稍后"是允许的（用户可能想先看看界面），但不能通过点外部区域关掉——
 * 那会让人误以为已经处理了。
 */
@Composable
fun SetupDialog(
    state: SetupState,
    onInstall: () -> Unit,
    onDismiss: () -> Unit
) {
    if (!state.visible) return

    Dialog(
        onDismissRequest = { /* 必须显式点按钮 */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = true
        )
    ) {
        androidx.compose.material3.Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column(Modifier.padding(24.dp)) {
                Text(
                    text = "首次启动，需要补齐运行组件",
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "这些是玩游戏必需的东西。APK 里已经内置了大部分，" +
                        "这里只是把它们放到正确的位置，不会消耗流量。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(20.dp))

                // 组件列表
                state.items.entries.take(6).forEach { (key, itemState) ->
                    ComponentRow(name = displayNameOf(key), state = itemState)
                    Spacer(Modifier.height(10.dp))
                }

                if (state.phase is BundleInstaller.Phase.Running) {
                    Spacer(Modifier.height(6.dp))
                    val progress by animateFloatAsState(
                        targetValue = state.runningPercent / 100f,
                        animationSpec = tween(200),
                        label = "setupProgress"
                    )
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = state.runningLabel.ifBlank { "正在处理…" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (state.phase is BundleInstaller.Phase.Failed) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = (state.phase as BundleInstaller.Phase.Failed).message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(Modifier.height(20.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        enabled = state.phase !is BundleInstaller.Phase.Running
                    ) {
                        Text("稍后")
                    }
                    androidx.compose.material3.Button(
                        onClick = onInstall,
                        modifier = Modifier.weight(1.6f).heightIn(min = 46.dp),
                        enabled = state.phase !is BundleInstaller.Phase.Running
                    ) {
                        if (state.phase !is BundleInstaller.Phase.Running) {
                            androidx.compose.material3.Icon(
                                Icons.Rounded.Download,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(if (state.phase is BundleInstaller.Phase.Running) "正在处理…" else "开始补全")
                    }
                }
            }
        }
    }
}

/** 单行组件：名称 + 大小 + 状态图标 */
@Composable
private fun ComponentRow(name: String, state: BundleInstaller.ItemState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(
                    when (state) {
                        BundleInstaller.ItemState.Done -> MaterialTheme.mizuColors.success.copy(alpha = 0.16f)
                        is BundleInstaller.ItemState.Failed -> MaterialTheme.colorScheme.errorContainer
                        else -> MaterialTheme.colorScheme.surfaceContainerHighest
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.material3.Icon(
                imageVector = when (state) {
                    BundleInstaller.ItemState.Done -> Icons.Rounded.Check
                    is BundleInstaller.ItemState.Failed -> Icons.Rounded.Warning
                    else -> Icons.Rounded.Download
                },
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = when (state) {
                    BundleInstaller.ItemState.Done -> MaterialTheme.mizuColors.success
                    is BundleInstaller.ItemState.Failed -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyMedium)
            if (state is BundleInstaller.ItemState.Installing) {
                Text(
                    text = state.detail.ifBlank { "${state.percent}%" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        when (state) {
            BundleInstaller.ItemState.Done -> MizuBadge(
                text = "就绪",
                container = MaterialTheme.mizuColors.success.copy(alpha = 0.16f),
                content = MaterialTheme.mizuColors.success
            )
            BundleInstaller.ItemState.Pending -> MizuBadge(text = "待安装")
            is BundleInstaller.ItemState.Failed -> MizuBadge(
                text = "失败",
                container = MaterialTheme.colorScheme.errorContainer,
                content = MaterialTheme.colorScheme.onErrorContainer
            )
            is BundleInstaller.ItemState.Installing -> MizuBadge(text = "${state.percent}%")
        }
    }
}

/** 组件 key → 用户能懂的名字 */
private fun displayNameOf(key: String): String = when {
    key.contains("jre-8") || key.contains("java8") -> "Java 8 运行时"
    key.contains("jre-17") || key.contains("java17") -> "Java 17 运行时"
    key.contains("jre-21") || key.contains("java21") -> "Java 21 运行时"
    key.contains("jre-25") || key.contains("java25") -> "Java 25 运行时"
    key.contains("authlib") -> "外置登录支持库"
    key.contains("natives") -> "图形库（natives）"
    key.startsWith("jre") -> "Java 运行时"
    else -> key
}
