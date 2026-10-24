package com.mizulauncher.v2.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mizulauncher.ui.design.StateView

/**
 * 日志页。
 *
 * ## 为什么日志要能搜索
 * 一次启动失败动辄几千行（库下载、参数拼接、JVM 输出），用户不可能肉眼找到
 * 那唯一的 `Caused by`。搜索框是这页最核心的功能，不是附加项。
 *
 * ## 为什么用等宽字体
 * 日志是"列对齐"的内容，比例字体下时间戳和缩进会乱，看起来像一堆字。
 *
 * ## 自动滚到底
 * 用户进这页通常是在"正在发生故障"的时候，最新的一条最有用。
 * 但如果用户往回滚了（去看上下文），就不能再抢他的滚动位置——
 * 所以只在列表本身就停在底部时才自动跟随。
 */
@Composable
fun LogScreen(
    lines: List<String>,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    var query by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    val filtered = remember(lines, query) {
        if (query.isBlank()) lines
        else lines.filter { it.contains(query, ignoreCase = true) }
    }

    // 只在用户没有往回滚的时候跟随
    LaunchedEffect(filtered.size) {
        if (filtered.isNotEmpty()) {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val nearBottom = last >= listState.layoutInfo.totalItemsCount - 3
            if (nearBottom) listState.animateScrollToItem(filtered.lastIndex)
        }
    }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("搜索日志内容") },
                leadingIcon = { Icon(Icons.Rounded.Search, null, Modifier.size(18.dp)) },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                textStyle = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onClear) { Text("清空") }
        }

        if (filtered.isEmpty()) {
            StateView(
                icon = Icons.Rounded.BugReport,
                title = if (lines.isEmpty()) "还没有日志" else "没有匹配「$query」的行",
                description = if (lines.isEmpty()) {
                    "启动游戏后，这里的输出会实时出现。"
                } else {
                    "试试搜索 Caused by、Exception 或某个类名。"
                },
                modifier = Modifier.weight(1f)
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(filtered) { line ->
                    LogLine(line, query)
                }
            }
        }
    }
}

/**
 * 单行日志。
 *
 * 按关键字着色：ERROR/FATAL 红、WARN 黄、Exception/Caused by 橙。
 * 这不是为了好看——几千行里，颜色是唯一能让眼睛快速跳转的锚点。
 */
@Composable
private fun LogLine(line: String, query: String) {
    val color = when {
        line.contains("FATAL") || line.contains(" E/") || line.startsWith("E/") ->
            MaterialTheme.colorScheme.error
        line.contains("WARN") || line.contains(" W/") || line.startsWith("W/") ->
            androidx.compose.ui.graphics.Color(0xFFD97706)
        line.contains("Exception") || line.contains("Caused by") ->
            androidx.compose.ui.graphics.Color(0xFFEA580C)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Text(
        text = line,
        style = MaterialTheme.typography.bodySmall.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 15.sp
        ),
        color = color,
        softWrap = true,
        maxLines = 40,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth()
    )
}
