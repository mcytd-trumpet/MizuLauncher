package com.mizulauncher.ui.screens.log

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mizulauncher.ui.components.EmptyState
import com.mizulauncher.ui.components.GlassCard
import com.mizulauncher.ui.components.SectionHeader
import com.mizulauncher.ui.components.SettingRow
import com.mizulauncher.ui.theme.MizuIcons
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 运行日志页。
 *
 * 数据源是实例目录下的 `logs/latest.log`（Pojav 内核与 MC 都写这里）。
 * 手机上没有终端，出错时用户唯一能提供的信息就是这几百行，
 * 所以这里提供「选文件 / 复制全部」，方便直接贴给别人排查。
 *
 * 只读最后 2000 行：日志动辄几十 MB，全量渲染会让低端机直接卡死。
 */
@Composable
fun LogScreen(
    logsRoot: File,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var files by remember(logsRoot) { mutableStateOf(listLogs(logsRoot)) }
    var selected by remember { mutableStateOf(files.firstOrNull()) }
    var content by remember { mutableStateOf("") }

    LaunchedEffect(selected) {
        content = selected?.let { runCatching { it.readText() }.getOrDefault("") } ?: ""
    }

    Column(modifier.fillMaxSize()) {
        SectionHeader(
            "运行日志",
            actionText = "刷新",
            onAction = {
                files = listLogs(logsRoot)
                if (selected == null) selected = files.firstOrNull()
            }
        )

        if (files.isEmpty()) {
            EmptyState(
                icon = MizuIcons.Log,
                title = "还没有日志",
                description = "启动一次游戏后，这里会出现 logs/latest.log"
            )
            return
        }

        GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column {
                files.take(6).forEach { f ->
                    SettingRow(
                        title = f.name,
                        subtitle = "${(f.length() / 1024).coerceAtLeast(1)} KB · ${formatTime(f.lastModified())}",
                        icon = if (f == selected) MizuIcons.Check else MizuIcons.Log,
                        onClick = { selected = f }
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "${content.lineSequence().count()} 行",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { onCopy(content) }) { Text("复制全部") }
        }

        LogBody(content, Modifier.weight(1f))
    }
}

@Composable
private fun LogBody(text: String, modifier: Modifier = Modifier) {
    val lines = remember(text) { text.lines().takeLast(2000) }
    val listState = rememberLazyListState()
    // 打开时直接定位到末尾：崩溃堆栈永远在最后
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }
    LazyColumn(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
        state = listState,
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        items(lines.size) { i ->
            val line = lines[i]
            Box(Modifier.fillMaxWidth()) {
                Text(
                    line,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    softWrap = true,
                    color = colorFor(line)
                )
            }
        }
    }
}

private fun colorFor(line: String): Color = when {
    line.contains("/ERROR") || line.startsWith("E/") -> Color(0xFFE57373)
    line.contains("/WARN") || line.startsWith("W/") -> Color(0xFFFFB74D)
    line.contains("Exception") || line.startsWith("\tat ") -> Color(0xFFFF8A80)
    else -> Color(0xFF90A4AE)
}

/** 收集日志文件：实例 lines 目录 + 启动器根目录，按修改时间倒序 */
private fun listLogs(root: File): List<File> {
    if (!root.exists()) return emptyList()
    return root.walkTopDown()
        .maxDepth(5)
        .filter { it.isFile && it.name.endsWith(".log") }
        .sortedByDescending { it.lastModified() }
        .take(20)
        .toList()
}

private fun formatTime(ts: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
