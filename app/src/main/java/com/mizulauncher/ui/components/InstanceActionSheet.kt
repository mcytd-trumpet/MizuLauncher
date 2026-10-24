package com.mizulauncher.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.mizulauncher.instance.InstanceMeta

/**
 * 实例操作面板（长按实例卡片打开）。
 *
 * 之前长按只会跳到设置页，用户其实**没有任何删除实例的入口** ——
 * 装错了版本只能手动去文件管理器删目录。这里补齐最基本的几件事：
 * 改名、看占用空间、打开游戏目录、删除。
 */
@Composable
fun InstanceActionSheet(
    instance: InstanceMeta,
    sizeBytes: Long?,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onOpenFolder: () -> Unit,
    onDismiss: () -> Unit
) {
    var renaming by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var nameInput by remember(instance.id) { mutableStateOf(instance.name) }

    if (renaming) {
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("重命名实例") },
            text = {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    singleLine = true,
                    label = { Text("名称") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onRename(nameInput)
                        renaming = false
                    },
                    enabled = nameInput.isNotBlank()
                ) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("取消") } }
        )
        return
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("删除实例？") },
            text = {
                Column {
                    Text("「${instance.name.ifBlank { instance.versionId }}」的存档、模组、配置都会被删除，无法恢复。")
                    if (sizeBytes != null && sizeBytes > 0) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "将释放约 ${humanSize(sizeBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmingDelete = false
                        onDelete()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("取消") } }
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(instance.name.ifBlank { instance.versionId }) },
        text = {
            Column {
                Text(
                    listOfNotNull(
                        instance.versionId,
                        instance.loaderType.takeIf { it != "vanilla" }?.let {
                            "$it ${instance.loaderVersion}".trim()
                        },
                        "Java ${instance.javaMajor}",
                        sizeBytes?.takeIf { it > 0 }?.let { humanSize(it) }
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { renaming = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("重命名") }
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.OutlinedButton(
                    onClick = onOpenFolder,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("打开游戏目录") }
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = { confirmingDelete = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("删除实例", color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "目录：${instance.id}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}
