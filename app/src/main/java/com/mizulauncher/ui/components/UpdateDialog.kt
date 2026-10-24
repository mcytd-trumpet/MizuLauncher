package com.mizulauncher.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mizulauncher.update.UpdateChecker

/**
 * 更新提示对话框。
 *
 * 三种状态共用同一个弹窗，避免用户看到「下载」按钮点下去又蹦出第二个框：
 *  - 有新版未下载 → 显示版本号 + 更新说明 + 「下载」
 *  - 下载中       → 进度条 + 「取消」
 *  - 下载完成     → 「立即安装」
 */
@Composable
fun UpdateDialog(
    info: UpdateChecker.UpdateInfo,
    downloading: Boolean,
    progress: Int,
    readyToInstall: Boolean,
    status: String?,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onInstall: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!downloading) onDismiss() },
        title = {
            Column {
                Text("发现新版本 ${info.versionName}", fontWeight = FontWeight.SemiBold)
                Text(
                    "当前版本较低",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp)) {
                if (info.notes.isNotBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            info.notes.trim(),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .heightIn(max = 220.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(12.dp)
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }

                // 明确告诉用户会装哪个包、为什么是这个包，避免「为什么给我下 universal」的疑惑
                info.asset?.let { a ->
                    Text(
                        "安装包：${a.name}" + if (a.sizeBytes > 0) " · ${a.sizeText}" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "已按本机 ${UpdateChecker.abiLabel(info.deviceAbi)} 匹配",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (info.asset == null) {
                    Text(
                        "这个版本没有提供适配 ${info.deviceAbi} 的安装包。请到 GitHub Release 页面手动挑选，不要装错架构——装错会直接闪退。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                if (downloading) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (progress >= 100) "校验安装包…" else "$progress%",
                        style = MaterialTheme.typography.labelSmall
                    )
                } else if (status != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (status.startsWith("下载失败")) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            when {
                // 顺序很重要：下载中要优先于「可安装」，否则断点续传时按钮会变成「立即安装」
                downloading -> TextButton(onClick = onCancelDownload) { Text("取消下载") }
                readyToInstall -> Button(onClick = onInstall) { Text("立即安装") }
                info.asset != null -> Button(onClick = onDownload) { Text("下载更新") }
                else -> TextButton(onClick = onDismiss) { Text("知道了") }
            }
        },
        dismissButton = {
            if (!downloading && !readyToInstall) {
                TextButton(onClick = onDismiss) { Text("以后再说") }
            }
        }
    )
}

/**
 * 完整的更新入口，含「检查更新」的瞬时反馈。
 *
 * 单独抽出来是因为「检查中 / 已是最新 / 检查失败」这三种结果
 * 在设置页里只是短暂出现一行字，没必要各自写一遍。
 */
@Composable
fun UpdateCheckRow(
    checking: Boolean,
    status: String?,
    onCheck: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("检查更新", style = MaterialTheme.typography.bodyLarge)
            if (status != null) {
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (checking) {
            TextButton(onClick = {}) { Text("检查中…") }
        } else {
            TextButton(onClick = onCheck) { Text("检查") }
        }
    }
}
