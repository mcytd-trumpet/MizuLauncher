package com.mizulauncher.v2.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mizulauncher.v2.state.InstanceCard
import com.mizulauncher.v2.ui.formatSize
import com.mizulauncher.v2.ui.relativeTime
import kotlinx.coroutines.launch

/**
 * 实例操作面板。
 *
 * ## 为什么用底部弹层而不是长按菜单
 * 长按菜单（ContextMenu）在手机上很难被发现，而且位置跟着手指跑，
 * 有时候一半在屏幕外。底部弹层固定在拇指区，且能放下完整的说明文字。
 *
 * ## 为什么删除要二次确认
 * 一个实例连同 libraries + assets 动辄几个 GB，删了之后重新下载要半小时。
 * 但也不弹一个吓人的系统对话框——在面板内部切换成"确认删除"形态，
 * 视觉上连续，用户不会觉得被打断。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun InstanceActionsSheet(
    card: InstanceCard,
    onDismiss: () -> Unit,
    onLaunch: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onOpenFolder: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    // 三种内部形态：主菜单 / 重命名 / 确认删除
    var mode by remember { mutableStateOf(SheetMode.Menu) }
    var newName by remember { mutableStateOf(card.meta.displayName) }

    fun close(after: () -> Unit) {
        scope.launch {
            sheetState.hide()
            after()
            onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
        ) {
            // 标题区
            Text(
                text = card.meta.displayName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${card.meta.versionId} · ${formatSize(card.sizeBytes)} · ${relativeTime(card.meta.lastPlayed)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))

            when (mode) {
                SheetMode.Menu -> {
                    SheetAction(
                        icon = Icons.Rounded.PlayArrow,
                        title = "启动游戏",
                        subtitle = "检查文件完整性后直接进入",
                        tint = MaterialTheme.colorScheme.primary
                    ) { close { onLaunch() } }

                    SheetAction(
                        icon = Icons.Rounded.Edit,
                        title = "重命名",
                        subtitle = "只改显示名，不影响版本"
                    ) { mode = SheetMode.Rename }

                    SheetAction(
                        icon = Icons.Rounded.FolderOpen,
                        title = "打开游戏目录",
                        subtitle = ".minecraft 文件夹"
                    ) { close { onOpenFolder() } }

                    SheetAction(
                        icon = Icons.Rounded.Delete,
                        title = "删除实例",
                        subtitle = "同时删除所有游戏文件，不可恢复",
                        tint = MaterialTheme.colorScheme.error
                    ) { mode = SheetMode.ConfirmDelete }
                }

                SheetMode.Rename -> {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("新的名称") },
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { mode = SheetMode.Menu }, modifier = Modifier.weight(1f)) {
                            Text("取消")
                        }
                        androidx.compose.material3.Button(
                            onClick = { close { onRename(newName) } },
                            modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                            enabled = newName.isNotBlank() && newName != card.meta.displayName
                        ) {
                            Text("保存")
                        }
                    }
                }

                SheetMode.ConfirmDelete -> {
                    Text(
                        text = "确定要删除「${card.meta.displayName}」吗？",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "这会移除 ${formatSize(card.sizeBytes)} 的游戏文件，包括存档与模组。" +
                            "如果只想清理空间，建议先手动备份 saves 文件夹。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { mode = SheetMode.Menu }, modifier = Modifier.weight(1f)) {
                            Text("算了")
                        }
                        androidx.compose.material3.Button(
                            onClick = { close { onDelete() } },
                            modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            )
                        ) {
                            Text("删除")
                        }
                    }
                }
            }
        }
    }
}

private enum class SheetMode { Menu, Rename, ConfirmDelete }

@Composable
private fun SheetAction(
    icon: ImageVector,
    title: String,
    subtitle: String,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .androidxClickable(onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp), tint = tint)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = tint)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun Modifier.androidxClickable(onClick: () -> Unit): Modifier =
    this.then(Modifier.clickable(onClick = onClick))
