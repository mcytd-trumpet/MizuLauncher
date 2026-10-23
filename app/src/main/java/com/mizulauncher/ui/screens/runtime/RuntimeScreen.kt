package com.mizulauncher.ui.screens.runtime

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mizulauncher.ui.components.GlassCard
import com.mizulauncher.ui.components.Tag
import com.mizulauncher.ui.theme.MizuIcons
import com.mizulauncher.runtime.RuntimeManager
import java.util.Locale

/**
 * Java 运行时管理页。
 *
 * 这里做的是 ZalithLauncher2 那种「装好即用 + 缺了能补」的体验：
 *  - 列出 8 / 17 / 21 / 25 四个大版本；
 *  - 已就绪的显示占用体积，可删除；
 *  - 未安装的点一下就走「内置解压 → 远端下载」；
 *  - 架构不支持的（32 位机器上的 Java 25）直接灰掉并说明原因，而不是点了报错。
 */
@Composable
fun RuntimeScreen(
    states: Map<Int, RuntimeManager.State>,
    abi: String,
    onInstall: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val majors = RuntimeManager.SUPPORTED_MAJORS

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
                            MizuIcons.Memory,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("Java 运行时", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.width(8.dp))
                        Tag(abi, color = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "启动器会按游戏版本自动挑选并准备对应的 Java，你也可以在这里提前装好，避免首次启动时等待。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        items(majors.size) { index ->
            val major = majors[index]
            val state = states[major] ?: RuntimeManager.State.NotInstalled
            RuntimeRow(
                major = major,
                state = state,
                onInstall = { onInstall(major) },
                onRemove = { onRemove(major) }
            )
        }

        item {
            Text(
                "提示：Java 8 用于 1.16.5 及更早，17 用于 1.17~1.20.4，21 用于 1.20.5 及以后，" +
                    "25 用于最新快照。32 位设备没有 Java 25 的可用移植。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}

@Composable
private fun RuntimeRow(
    major: Int,
    state: RuntimeManager.State,
    onInstall: () -> Unit,
    onRemove: () -> Unit
) {
    val (title, subtitle, color) = when (state) {
        is RuntimeManager.State.Ready ->
            Triple("Java $major", humanSize(state.sizeBytes), MaterialTheme.colorScheme.primary)
        is RuntimeManager.State.Downloading ->
            Triple("Java $major", "${state.detail} · ${state.percent}%", MaterialTheme.colorScheme.tertiary)
        is RuntimeManager.State.Extracting ->
            Triple("Java $major", "正在解压 · ${state.percent}%", MaterialTheme.colorScheme.tertiary)
        is RuntimeManager.State.NotInstalled ->
            Triple("Java $major", "未安装，点右侧按钮准备", MaterialTheme.colorScheme.onSurfaceVariant)
        is RuntimeManager.State.Unavailable ->
            Triple("Java $major", state.reason, MaterialTheme.colorScheme.error)
        is RuntimeManager.State.Failed ->
            Triple("Java $major", state.message, MaterialTheme.colorScheme.error)
    }

    GlassCard(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(color.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "$major",
                        style = MaterialTheme.typography.titleMedium,
                        color = color
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(8.dp))
                when (state) {
                    is RuntimeManager.State.Ready -> TextButton(onClick = onRemove) { Text("删除") }
                    is RuntimeManager.State.Downloading, is RuntimeManager.State.Extracting ->
                        Text("处理中…", style = MaterialTheme.typography.labelSmall)
                    is RuntimeManager.State.Unavailable ->
                        Text("不支持", style = MaterialTheme.typography.labelSmall)
                    else -> OutlinedButton(onClick = onInstall) { Text("准备") }
                }
            }

            val progress = when (state) {
                is RuntimeManager.State.Downloading -> state.percent / 100f
                is RuntimeManager.State.Extracting -> state.percent / 100f
                else -> null
            }
            if (progress != null) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                )
            }
        }
    }
}

private fun humanSize(bytes: Long): String {
    if (bytes <= 0) return "已就绪"
    var v = bytes.toDouble()
    val units = arrayOf("B", "KB", "MB", "GB")
    var i = 0
    while (v >= 1024 && i < units.lastIndex) {
        v /= 1024
        i++
    }
    return String.format(Locale.US, "已就绪 · %.1f %s", v, units[i])
}
