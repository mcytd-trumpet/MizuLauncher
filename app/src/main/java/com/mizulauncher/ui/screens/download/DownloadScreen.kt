package com.mizulauncher.ui.screens.download

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mizulauncher.download.DownloadProgress
import com.mizulauncher.download.DownloadSource
import com.mizulauncher.ui.components.GlassCard
import com.mizulauncher.ui.components.Tag
import com.mizulauncher.ui.theme.MizuIcons
import com.mizulauncher.minecraft.model.VersionEntry

/**
 * 下载页：版本列表 + 加载器选择 + 下载进度。
 *
 * 交互参考 PCL2 的「下载 → 游戏本体」：
 *   版本卡片 → 点开右侧抽屉 → 选加载器（原版/Fabric/Quilt/Forge/NeoForge/OptiFine）→ 创建实例并下载
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadScreen(
    versions: List<VersionEntry>,
    loading: Boolean,
    currentSource: DownloadSource,
    progress: DownloadProgress?,
    installing: Boolean,
    onSourceChange: (DownloadSource) -> Unit,
    onRefresh: () -> Unit,
    onDownload: (VersionEntry, LoaderChoice) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(Filter.ALL) }
    var detail by remember { mutableStateOf<VersionEntry?>(null) }

    val filtered = remember(versions, query, filter) {
        versions.filter { v ->
            (query.isBlank() || v.id.contains(query, ignoreCase = true)) &&
                when (filter) {
                    Filter.ALL -> true
                    Filter.RELEASE -> v.isRelease
                    Filter.SNAPSHOT -> v.isSnapshot
                    Filter.OLD -> v.isOldAlphaBeta
                }
        }
    }

    Column(modifier.fillMaxSize()) {
        // 工具栏：搜索 + 源选择
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("搜索版本号", style = MaterialTheme.typography.bodyMedium) },
                leadingIcon = { Icon(MizuIcons.Search, null, modifier = Modifier.size(18.dp)) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp)
            )
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    .clickable(onClick = onRefresh)
                    .padding(12.dp)
            ) {
                Icon(MizuIcons.Refresh, null, modifier = Modifier.size(20.dp))
            }
        }

        // 过滤 chips + 源
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Filter.entries.forEach { f ->
                FilterChip(
                    selected = filter == f,
                    onClick = { filter = f },
                    label = { Text(f.label, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            DownloadSource.entries.forEach { s ->
                FilterChip(
                    selected = currentSource == s,
                    onClick = { onSourceChange(s) },
                    label = { Text(s.displayName, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        // 进度条（下载中显示在顶部，始终可见）
        if (progress != null && (installing || !progress.finished)) {
            GlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(progress.stage, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = onCancel) { Text("取消") }
                    }
                    LinearProgressIndicator(
                        progress = { progress.percent / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                    )
                    Spacer(Modifier.height(6.dp))
                    Row {
                        Text(
                            "${progress.doneTasks}/${progress.totalTasks} · ${formatBytes(progress.downloadedBytes)}/${formatBytes(progress.totalBytes)}",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(formatSpeed(progress.speedBps), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        if (loading) {
            com.mizulauncher.ui.components.LoadingBox(text = "正在获取版本列表…")
        } else {
            val listState = rememberLazyListState()
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(items = filtered, key = { v -> v.id }) { v ->
                    VersionRow(v) { detail = v }
                }
            }
        }
    }

    detail?.let { entry ->
        LoaderSheet(
            entry = entry,
            onDismiss = { detail = null },
            onConfirm = { choice ->
                detail = null
                onDownload(entry, choice)
            }
        )
    }
}

private enum class Filter(val label: String) {
    ALL("全部"), RELEASE("正式版"), SNAPSHOT("快照"), OLD("远古版")
}

@Composable
private fun VersionRow(v: VersionEntry, onClick: () -> Unit) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        when {
                            v.isRelease -> MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                            v.isSnapshot -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f)
                            else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.14f)
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    v.id.take(1),
                    style = MaterialTheme.typography.titleMedium,
                    color = when {
                        v.isRelease -> MaterialTheme.colorScheme.primary
                        v.isSnapshot -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(v.id, style = MaterialTheme.typography.titleMedium)
                Text(
                    v.releaseTime?.take(10) ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            when {
                v.isRelease -> Tag("正式", color = MaterialTheme.colorScheme.primary)
                v.isSnapshot -> Tag("快照", color = MaterialTheme.colorScheme.tertiary)
                else -> Tag("远古", color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

data class LoaderChoice(
    val loader: String = "vanilla",
    val loaderVersion: String = "",
    val customName: String = ""
) {
    companion object {
        val LOADERS = listOf("vanilla", "fabric", "quilt", "forge", "neoforge", "optifine")
        fun displayName(loader: String) = when (loader) {
            "vanilla" -> "原版"
            "fabric" -> "Fabric"
            "quilt" -> "Quilt"
            "forge" -> "Forge"
            "neoforge" -> "NeoForge"
            "optifine" -> "OptiFine"
            else -> loader
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoaderSheet(entry: VersionEntry, onDismiss: () -> Unit, onConfirm: (LoaderChoice) -> Unit) {
    var loader by remember { mutableStateOf("vanilla") }
    var loaderVersion by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text(entry.id, style = MaterialTheme.typography.headlineMedium)
            Text(
                "选择加载器；加载器版本可留空使用推荐版本",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                LoaderChoice.LOADERS.forEach { l ->
                    FilterChip(
                        selected = loader == l,
                        onClick = { loader = l },
                        label = { Text(LoaderChoice.displayName(l), style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }
            if (loader != "vanilla") {
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = loaderVersion,
                    onValueChange = { loaderVersion = it },
                    label = { Text("${LoaderChoice.displayName(loader)} 版本（可空）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                )
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("实例名称（可空，默认与版本同名）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            )
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = { onConfirm(LoaderChoice(loader, loaderVersion.trim(), name.trim())) },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(MizuIcons.Download, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("开始安装")
            }
        }
    }
}

internal fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var v = bytes.toDouble()
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return String.format("%.1f %s", v, units[i])
}

internal fun formatSpeed(bps: Long): String = if (bps <= 0) "" else formatBytes(bps) + "/s"
