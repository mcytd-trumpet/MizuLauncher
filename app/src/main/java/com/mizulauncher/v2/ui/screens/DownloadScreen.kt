package com.mizulauncher.v2.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mizulauncher.compat.MotionLevel
import com.mizulauncher.compat.MotionSpec
import com.mizulauncher.minecraft.model.VersionEntry
import com.mizulauncher.ui.design.MizuBadge
import com.mizulauncher.ui.design.MizuCard
import com.mizulauncher.ui.design.StateView
import com.mizulauncher.ui.design.currentMotionLevel
import com.mizulauncher.ui.design.mizuColors
import com.mizulauncher.v2.state.DownloadState
import com.mizulauncher.v2.state.InstallTask
import com.mizulauncher.v2.state.VersionFilter
import com.mizulauncher.v2.ui.SectionHeader

/**
 * 下载页。
 *
 * ## 交互主线的设计
 * 「找版本 → 选加载器 → 点安装」是这个页面唯一要做的事，所以：
 *
 * 1. **搜索框常驻顶部**，不藏在图标后面。用户 90% 的情况是来装某个特定版本，
 *    让他先点一次放大镜纯属浪费；
 * 2. **筛选做成 chip**（正式版/快照/全部），一眼可见当前范围，切换只要一次点击；
 * 3. **点版本行就地展开**加载器选择，不用跳新页面——因为"装哪个加载器"
 *    是在"我要装这个版本"之后才产生的问题，跳页会打断语义。
 * 4. **装完之后原地变成进度条**，用户不用去别处找进度。
 *
 * ## 为什么不做“下载队列”这种复杂东西
 * 手机上同时装 3 个版本是很罕见的场景，做了反而让界面变复杂。
 * 当前限制：同一时刻只允许一个任务在跑，其它排队——状态树上已经表达了（Queued）。
 */
@Composable
fun DownloadScreen(
    state: DownloadState,
    onQuery: (String) -> Unit,
    onFilter: (VersionFilter) -> Unit,
    onExpand: (String?) -> Unit,
    onInstall: (VersionEntry, String, String) -> Unit,
    onDismissTask: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxSize()) {
        // ---------------- 搜索 + 筛选 ----------------
        Column(Modifier.padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.query,
                onValueChange = onQuery,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("搜索版本，如 1.20.4") },
                leadingIcon = {
                    Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(20.dp))
                },
                trailingIcon = {
                    AnimatedVisibility(state.query.isNotEmpty()) {
                        IconButton(onClick = { onQuery("") }) {
                            Icon(Icons.Rounded.Close, contentDescription = "清空", modifier = Modifier.size(18.dp))
                        }
                    }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { })
            )

            Spacer(Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.filter == VersionFilter.Release,
                    onClick = { onFilter(VersionFilter.Release) },
                    label = { Text("正式版") }
                )
                FilterChip(
                    selected = state.filter == VersionFilter.Snapshot,
                    onClick = { onFilter(VersionFilter.Snapshot) },
                    label = { Text("快照") }
                )
                FilterChip(
                    selected = state.filter == VersionFilter.All,
                    onClick = { onFilter(VersionFilter.All) },
                    label = { Text("全部") }
                )
            }

            Spacer(Modifier.height(12.dp))
            SectionHeader(
                title = "可用版本",
                subtitle = if (state.loading) "正在获取版本清单…" else "${state.visibleVersions.size} 个"
            )
            Spacer(Modifier.height(8.dp))
        }

        // ---------------- 列表 ----------------
        when {
            state.loading -> LoadingList()
            state.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                StateView(
                    icon = Icons.Rounded.Wifi,
                    title = "获取版本列表失败",
                    description = state.error,
                    actionLabel = "重试",
                    onAction = onRetry,
                    tint = MaterialTheme.colorScheme.error
                )
            }
            state.visibleVersions.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                StateView(
                    icon = Icons.Rounded.Search,
                    title = if (state.query.isBlank()) "这个分类下没有版本" else "没有匹配「${state.query}」的版本",
                    description = if (state.query.isBlank()) {
                        "试试切换到「全部」看看快照版本。"
                    } else {
                        "检查一下版本号拼写，或者换个关键词。"
                    }
                )
            }
            else -> LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp, end = 16.dp, bottom = 96.dp
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.visibleVersions, key = { it.id }) { entry ->
                    VersionRow(
                        entry = entry,
                        task = state.tasks[entry.id],
                        expanded = state.expandedId == entry.id,
                        onToggle = { onExpand(if (state.expandedId == entry.id) null else entry.id) },
                        onInstall = { loader, loaderVersion -> onInstall(entry, loader, loaderVersion) },
                        onDismissTask = { onDismissTask(entry.id) }
                    )
                }
            }
        }
    }
}

/**
 * 一个版本行。
 *
 * 三种形态：
 *  - **未展开**：版本号 + 类型徽标 + 下载图标；
 *  - **展开**：多出加载器选择区（原版/Fabric/Quilt/Forge/NeoForge）；
 *  - **安装中**：底部出现进度条，点击图标可取消。
 *    用 `animateContentSize` 让高度变化有过渡，而不是"啪"地跳一下。
 */
@Composable
private fun VersionRow(
    entry: VersionEntry,
    task: InstallTask?,
    expanded: Boolean,
    onToggle: () -> Unit,
    onInstall: (String, String) -> Unit,
    onDismissTask: () -> Unit
) {
    val level = currentMotionLevel
    val installing = task?.phase?.isActive == true

    MizuCard(
        modifier = Modifier.fillMaxWidth().animateContentSize(
            animationSpec = tween(MotionSpec.duration(MotionSpec.Medium, level)),
            alignment = Alignment.TopStart
        ),
        onClick = if (installing) null else onToggle,
        containerColor = if (expanded) {
            MaterialTheme.colorScheme.surfaceContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 版本类型色点：正式版绿、快照黄、远古版灰
            Box(
                Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        when (entry.type) {
                            "release" -> MaterialTheme.mizuColors.success
                            "snapshot" -> MaterialTheme.mizuColors.warning
                            else -> MaterialTheme.colorScheme.outline
                        }
                    )
            )
            Spacer(Modifier.width(10.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    text = entry.id,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MizuBadge(
                        text = when (entry.type) {
                            "release" -> "正式版"
                            "snapshot" -> "快照"
                            else -> "远古"
                        }
                    )
                    entry.releaseTime?.take(10)?.let { MizuBadge(text = it) }
                }
            }

            Spacer(Modifier.width(8.dp))

            // 右侧动作区，三态互斥
            when {
                installing -> CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
                task?.phase == InstallTask.Phase.Done -> Icon(
                    Icons.Rounded.Check,
                    contentDescription = "已安装",
                    tint = MaterialTheme.mizuColors.success,
                    modifier = Modifier.size(22.dp)
                )
                task?.phase == InstallTask.Phase.Failed -> IconButton(
                    onClick = onDismissTask,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "安装失败，点击关闭",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                }
                else -> Icon(
                    Icons.Rounded.Download,
                    contentDescription = "安装",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        // ---------------- 安装进度 ----------------
        AnimatedVisibility(
            visible = installing,
            enter = expandVertically(tween(MotionSpec.duration(MotionSpec.Medium, level))) + fadeIn(),
            exit = shrinkVertically(tween(MotionSpec.duration(MotionSpec.Short, level))) + fadeOut()
        ) {
            Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                LinearProgressIndicator(
                    progress = { task?.progress ?: 0f },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = task?.detail?.ifBlank { task.phase.label } ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // 失败原因就地展示，不用弹窗打断
        AnimatedVisibility(visible = task?.phase == InstallTask.Phase.Failed) {
            Text(
                text = task?.error ?: "安装失败",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        // ---------------- 加载器选择 ----------------
        AnimatedVisibility(
            visible = expanded && !installing,
            enter = expandVertically(tween(MotionSpec.duration(MotionSpec.Medium, level))) + fadeIn(),
            exit = shrinkVertically(tween(MotionSpec.duration(MotionSpec.Short, level))) + fadeOut()
        ) {
            LoaderPicker(versionId = entry.id, onInstall = onInstall)
        }
    }
}

/**
 * 加载器选择。
 *
 * 只列真正支持"装上就能用"的几个。OptiFine 需要下载 jar 再注入，
 * 路径和其它几个完全不同，暂时不给入口——给一个点了会失败的按钮是最糟的设计。
 */
@Composable
private fun LoaderPicker(versionId: String, onInstall: (String, String) -> Unit) {
    val loaders = remember {
        listOf(
            "vanilla" to "原版",
            "fabric" to "Fabric",
            "quilt" to "Quilt",
            "forge" to "Forge",
            "neoforge" to "NeoForge"
        )
    }
    var selected by remember(versionId) { mutableStateOf("vanilla") }

    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(
            text = "选择模组加载器",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        // 横向可滚：五个 chip 在一排放不下，硬塞会挤成两行更难看
        Row(
            Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            loaders.forEach { (id, label) ->
                FilterChip(
                    selected = selected == id,
                    onClick = { selected = id },
                    label = { Text(label) }
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        androidx.compose.material3.Button(
            onClick = { onInstall(selected, "") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
            shape = MaterialTheme.shapes.medium
        ) {
            Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("安装 $versionId")
        }
    }
}

/** 加载骨架：与真实行同高，加载完成后不跳动 */
@Composable
private fun LoadingList() {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        repeat(8) {
            com.mizulauncher.ui.design.SkeletonCard()
        }
    }
}
