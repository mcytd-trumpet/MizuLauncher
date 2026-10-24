package com.mizulauncher.v2.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mizulauncher.compat.MotionSpec
import com.mizulauncher.runtime.RuntimeManager
import com.mizulauncher.ui.design.MizuBadge
import com.mizulauncher.ui.design.MizuCard
import com.mizulauncher.ui.design.mizuColors
import com.mizulauncher.v2.state.RuntimeState
import com.mizulauncher.v2.ui.SectionHeader
import com.mizulauncher.v2.ui.formatSize

/**
 * 运行时（Java）管理页。
 *
 * ## 1.0.7 在这里出过一次严重的显示 bug
 * 截图上是四个 Java 版本全部显示「准备」状态 + 底部红字提示缺 Java 8
 * ——状态自相矛盾。根因是「扫描结果」和「内置清单」两套数据各说各话。
 *
 * 2.0 的做法：**只认 [`RuntimeManager.State`] 这一份事实**。
 * 磁盘上有可执行的 java 才是 Ready，其余都是 NotInstalled / Unavailable / Failed，
 * 每种状态都有明确的、互不重叠的视觉表现和一句人话解释。
 *
 * ## 为什么要显示 ABI
 * 32 位设备装不了 Java 17+（没有对应构建）。用户看到"Java 21 不可用"时，
 * 第一反应是"启动器坏了"。把 ABI 和原因写出来，他能自己去理解。
 */
@Composable
fun RuntimeScreen(
    state: RuntimeState,
    onInstall: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item("header") {
            SectionHeader(
                title = "Java 运行时",
                subtitle = "已就绪 ${state.readyCount} / ${state.states.size} 个 · ${state.abi}",
                trailing = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "重新扫描", modifier = Modifier.size(20.dp))
                    }
                }
            )
        }

        item("notice") {
            NoticeCard(state)
        }

        items(4) { index ->
            val major = listOf(8, 17, 21, 25)[index]
            val runtimeState = state.states[major]
            RuntimeRow(
                major = major,
                state = runtimeState,
                busy = state.busy[major] == true,
                onInstall = { onInstall(major) },
                onRemove = { onRemove(major) }
            )
        }
    }
}

/**
 * 顶部提示条。
 *
 * 只在"有问题"时出现，正常时不占空间——避免出现永远挂着一条黄条、
 * 用户看久了就当它不存在的情况。
 */
@Composable
private fun NoticeCard(state: RuntimeState) {
    val missing = listOf(8, 17, 21, 25).filter { state.states[it] !is RuntimeManager.State.Ready }
    val unavailable = listOf(8, 17, 21, 25).filter { state.states[it] is RuntimeManager.State.Unavailable }

    if (missing.isEmpty()) {
        MizuCard(
            modifier = Modifier.fillMaxWidth(),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Check,
                    contentDescription = null,
                    tint = MaterialTheme.mizuColors.success,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "全部运行时都可用，可以启动任何版本的 Minecraft。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        return
    }

    MizuCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Rounded.Warning,
                contentDescription = null,
                tint = MaterialTheme.mizuColors.warning,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    text = "还有 ${missing.size} 个运行时未就绪",
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = if (unavailable.isNotEmpty()) {
                        "Java ${unavailable.joinToString("/")} 在当前设备（${state.abi}）上没有可用构建，" +
                            "这是架构限制，不是安装失败。"
                    } else {
                        "点下面的「安装」补齐。内置包会优先使用，只有缺失时才走网络下载。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 单个 Java 版本行。
 *
 * 五种状态的视觉语言：
 * | 状态 | 图标色 | 右侧动作 |
 * |---|---|---|
 * | Ready | 绿勾 | 删除 |
 * | NotInstalled | 灰 | 安装 |
 * | Downloading/Extracting | 进度环 | — |
 * | Unavailable | 黄警告 | —（说明原因） |
 * | Failed | 红警告 | 重试 |
 */
@Composable
private fun RuntimeRow(
    major: Int,
    state: RuntimeManager.State?,
    busy: Boolean,
    onInstall: () -> Unit,
    onRemove: () -> Unit
) {
    val ready = state is RuntimeManager.State.Ready
    val inProgress = state is RuntimeManager.State.Downloading || state is RuntimeManager.State.Extracting || busy

    MizuCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = if (ready) {
            MaterialTheme.colorScheme.surfaceContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 左侧图标：圆角方块里放芯片图标，颜色反映状态
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        when {
                            ready -> MaterialTheme.mizuColors.success.copy(alpha = 0.16f)
                            state is RuntimeManager.State.Failed -> MaterialTheme.colorScheme.errorContainer
                            state is RuntimeManager.State.Unavailable -> MaterialTheme.mizuColors.warning.copy(alpha = 0.16f)
                            else -> MaterialTheme.colorScheme.surfaceContainerHighest
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (ready) Icons.Rounded.Check else Icons.Rounded.Memory,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = when {
                        ready -> MaterialTheme.mizuColors.success
                        state is RuntimeManager.State.Failed -> MaterialTheme.colorScheme.error
                        state is RuntimeManager.State.Unavailable -> MaterialTheme.mizuColors.warning
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Java $major", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.width(6.dp))
                    MizuBadge(
                        text = when {
                            major <= 8 -> "旧版本"
                            major <= 17 -> "1.17 – 1.19"
                            major == 21 -> "1.20.5+"
                            else -> "最新"
                        }
                    )
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    text = stateDescription(state),
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        state is RuntimeManager.State.Failed -> MaterialTheme.colorScheme.error
                        state is RuntimeManager.State.Unavailable -> MaterialTheme.mizuColors.warning
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.width(8.dp))

            when {
                inProgress -> ProgressPill(state)
                ready -> IconButton(onClick = onRemove, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Rounded.Delete,
                        contentDescription = "删除",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                state is RuntimeManager.State.Unavailable -> Unit
                else -> FilledTonalButton(
                    onClick = onInstall,
                    modifier = Modifier.heightIn(min = 36.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp)
                ) {
                    Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("安装", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        // 下载/解压进度条
        AnimatedVisibility(
            visible = inProgress,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            val percent = when (state) {
                is RuntimeManager.State.Downloading -> state.percent / 100f
                is RuntimeManager.State.Extracting -> state.percent / 100f
                else -> 0f
            }
            val animated by animateFloatAsState(
                targetValue = percent,
                animationSpec = tween(MotionSpec.Short),
                label = "runtimeProgress"
            )
            LinearProgressIndicator(
                progress = { animated },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
            )
        }
    }
}

@Composable
private fun ProgressPill(state: RuntimeManager.State?) {
    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/**
 * 状态 → 人话。
 *
 * 这是整个页面最重要的一段代码。1.0 版把内部枚举 `toString()` 直接显示，
 * 用户看到的是 `Ready` / `NotInstalled` / `Unavailable` 这种单词。
 * 这里每一种都翻译成"发生了什么 + 我该怎么办"。
 */
private fun stateDescription(state: RuntimeManager.State?): String = when (state) {
    null -> "尚未扫描"
    is RuntimeManager.State.Ready -> "已就绪 · ${formatSize(state.sizeBytes)}"
    RuntimeManager.State.NotInstalled -> "未安装，点右侧「安装」"
    is RuntimeManager.State.Downloading -> "正在下载 ${state.percent}% · ${state.detail}"
    is RuntimeManager.State.Extracting -> "正在解压 ${state.percent}%"
    is RuntimeManager.State.Unavailable -> state.reason
    is RuntimeManager.State.Failed -> "安装失败：${state.message}"
}
