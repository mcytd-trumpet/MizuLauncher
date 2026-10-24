package com.mizulauncher.v2.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mizulauncher.compat.MotionLevel
import com.mizulauncher.compat.MotionSpec
import com.mizulauncher.skin.SkinPreview3D
import com.mizulauncher.skin.TextureState
import com.mizulauncher.ui.design.MizuCard
import com.mizulauncher.ui.design.MizuBadge
import com.mizulauncher.ui.design.SkeletonCard
import com.mizulauncher.ui.design.StateView
import com.mizulauncher.ui.design.currentMotionLevel
import com.mizulauncher.ui.design.mizuColors
import com.mizulauncher.v2.state.HomeState
import com.mizulauncher.v2.state.InstanceCard
import com.mizulauncher.v2.ui.SectionHeader
import com.mizulauncher.v2.ui.formatSize
import com.mizulauncher.v2.ui.relativeTime
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 首页：大卡片 + 版本列表。
 *
 * ## 布局决策
 *
 * 上半屏是一张**大卡片**——它回答「我现在最可能想干什么」，答案通常是"启动最近玩的版本"。
 * 这个动作占满了卡片的整个下半部分，手指不需要精确点击。
 *
 * 下半屏是**其余实例的列表**，用紧凑行而不是卡片：同样高度的空间能放 3 倍信息，
 * 而且视觉上形成"主角 + 配角"的层次，不会让人不知道先看哪。
 *
 * ## 为什么大卡片不做成轮播
 * 轮播看起来高级，但它的代价是"用户要等它停下来才能点"。
 * 启动器是工具，不是内容 App，第一优先级是快。
 */
@Composable
fun HomeScreen(
    state: HomeState,
    onSelect: (String) -> Unit,
    onLaunch: (InstanceCard) -> Unit,
    onCancelLaunch: () -> Unit,
    onOpenDownload: () -> Unit,
    onInstanceMenu: (InstanceCard) -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (state.loading) {
        HomeSkeleton(modifier)
        return
    }

    if (state.empty) {
        EmptyHome(onOpenDownload = onOpenDownload, modifier = modifier)
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ---------------- 大卡片 ----------------
        state.featured?.let { featured ->
            item(key = "featured") {
                FeatureCard(
                    card = featured,
                    launching = state.launchingId == featured.meta.id,
                    stage = state.launchStage,
                    progress = state.launchProgress,
                    onLaunch = { onLaunch(featured) },
                    onCancel = onCancelLaunch,
                    onMenu = { onInstanceMenu(featured) }
                )
            }
        }

        // ---------------- 启动错误 ----------------
        item(key = "error") {
            AnimatedVisibility(
                visible = state.error != null,
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut()
            ) {
                LaunchErrorBanner(message = state.error.orEmpty(), onDismiss = onDismissError)
            }
        }

        // ---------------- 其余实例 ----------------
        val rest = state.restOfInstances
        if (rest.isNotEmpty()) {
            item(key = "all_header") {
                SectionHeader(
                    title = "其他实例",
                    subtitle = "${rest.size} 个",
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            items(rest, key = { it.meta.id }) { card ->
                InstanceRow(
                    card = card,
                    selected = card.meta.id == state.selectedId,
                    onClick = { onSelect(card.meta.id) },
                    onLaunch = { onLaunch(card) },
                    onMenu = { onInstanceMenu(card) }
                )
            }
        }

        // 只有一个实例时，列表是空的，给一个"新建"的入口聊胜于无
        if (rest.isEmpty()) {
            item(key = "add_more") {
                Spacer(Modifier.height(4.dp))
                OutlinedAddButton(
                    text = "再装一个版本",
                    onClick = onOpenDownload
                )
            }
        }
    }
}

/**
 * 顶部大卡片。
 *
 * ## 结构
 * ```
 * ┌──────────────────────────────────┐
 * │  皮肤        版本名              │
 * │  3D 预览     1.20.4 · Fabric      │
 * │              1.2 GB · 3 天前      │
 * │                                  │
 * │  ┌────────────────────────────┐  │
 * │  │      ▶  启动游戏            │  │
 * │  └────────────────────────────┘  │
 * └──────────────────────────────────┘
 * ```
 * 皮肤预览占左侧约 1/3，它不是一个装饰：用户能一眼确认"我现在用的是哪个角色"，
 * 这在多账号场景（正版 / 外置登录）下是刚需。
 */
@Composable
private fun FeatureCard(
    card: InstanceCard,
    launching: Boolean,
    stage: String,
    progress: Float,
    onLaunch: () -> Unit,
    onCancel: () -> Unit,
    onMenu: () -> Unit
) {
    MizuCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
    ) {
        Box {
            // 背景渐变：从 primary 的极低透明度到透明，给卡片一点"光"
            Box(
                Modifier
                    .matchParentSize()
                    .clip(MaterialTheme.shapes.large)
                    .background(
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                                androidx.compose.ui.graphics.Color.Transparent,
                                androidx.compose.ui.graphics.Color.Transparent
                            )
                        )
                    )
            )

            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    // ---- 皮肤预览 ----
                    Box(
                        Modifier
                            .size(96.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center
                    ) {
                        SkinSlot(card.skin, size = 96.dp)
                    }

                    Spacer(Modifier.width(14.dp))

                    // ---- 文字信息 ----
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = card.meta.displayName,
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            MizuBadge(card.meta.versionId)
                            if (card.meta.loaderType != "vanilla") {
                                MizuBadge(
                                    text = card.meta.loaderType.replaceFirstChar(Char::uppercase),
                                    container = MaterialTheme.colorScheme.tertiaryContainer,
                                    content = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = buildString {
                                append(formatSize(card.sizeBytes))
                                append(" · ")
                                append(relativeTime(card.meta.lastPlayed))
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    IconButton(onClick = onMenu, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "更多", modifier = Modifier.size(20.dp))
                    }
                }

                Spacer(Modifier.height(16.dp))

                // ---- 启动按钮 / 启动中 ----
                // 用 AnimatedContent 在两种形态间切换，避免"按钮瞬间变成进度条"的跳变
                AnimatedContent(
                    targetState = launching,
                    transitionSpec = {
                        fadeIn(tween(MotionSpec.Short)) togetherWith fadeOut(tween(MotionSpec.Short))
                    },
                    label = "launchButton"
                ) { isLaunching ->
                    if (isLaunching) {
                        LaunchingBlock(stage = stage, progress = progress, onCancel = onCancel)
                    } else {
                        androidx.compose.material3.Button(
                            onClick = onLaunch,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("启动游戏", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }
    }
}

/** 启动中的形态：铺满卡片底部，显示阶段文案与进度 */
@Composable
private fun LaunchingBlock(stage: String, progress: Float, onCancel: () -> Unit) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(MotionSpec.Short, easing = MotionSpec.Standard),
        label = "launchProgress"
    )

    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = stage.ifBlank { "正在启动…" },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            FilledTonalIconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Rounded.Stop, contentDescription = "取消", modifier = Modifier.size(16.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { animated },
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
        )
    }
}

/**
 * 皮肤槽位。
 *
 * 五种状态各有各的画法——这正是旧版最缺的东西（旧版无论什么状态都是空白方块）：
 *  - [TextureState.Ready]：真的 3D 渲染，会缓慢自转；
 *  - [TextureState.Loading]：呼吸的骨架；
 *  - [TextureState.Absent]：默认皮肤剪影（告诉用户"这个人没设皮肤"）；
 *  - [TextureState.Failed]：一个破损图标 + 点一下重试；
 *  - [TextureState.Idle]：不画任何东西（还没请求过，别吓人）。
 */
@Composable
fun SkinSlot(state: TextureState, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    when (state) {
        // 3D 预览用 CPU 光栅化（见 core:skin/SkinPreview3D 的说明）：
        // 低端机建 GL 上下文的成本比"逐帧画 36 个四边形"高得多，而皮肤本身只有几百个像素
        is TextureState.Ready -> SkinPreview3D(
            skin = state.image,
            slim = state.slim,
            modifier = modifier.size(size),
            motionLevel = currentMotionLevel
        )
        TextureState.Loading -> com.mizulauncher.ui.design.SkeletonBlock(
            modifier = modifier.size(size * 0.7f),
            shape = RoundedCornerShape(12.dp)
        )
        TextureState.Absent, TextureState.Idle -> DefaultSkinSilhouette(modifier.size(size))
        is TextureState.Failed -> Box(modifier.size(size), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = androidx.compose.material.icons.Icons.Rounded.AccountCircle,
                contentDescription = state.message,
                modifier = Modifier.size(size * 0.55f),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
        }
    }
}

/** 默认皮肤剪影：用两个简单形状拼一个"人"，比显示空白更友好 */
@Composable
private fun DefaultSkinSilhouette(modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(Modifier.size(26.dp).clip(RoundedCornerShape(6.dp)).background(c))
        Spacer(Modifier.height(3.dp))
        Box(Modifier.width(34.dp).height(28.dp).clip(RoundedCornerShape(6.dp)).background(c.copy(alpha = 0.75f)))
    }
}

/** 列表行：比卡片紧凑，信息密度是卡片的三倍 */
@Composable
private fun InstanceRow(
    card: InstanceCard,
    selected: Boolean,
    onClick: () -> Unit,
    onLaunch: () -> Unit,
    onMenu: () -> Unit
) {
    MizuCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        containerColor = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center
            ) {
                SkinSlot(card.skin, size = 44.dp)
            }

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    text = card.meta.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${card.meta.versionId} · ${formatSize(card.sizeBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }

            IconButton(onClick = onLaunch, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Rounded.PlayArrow,
                    contentDescription = "启动",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(onClick = onMenu, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Rounded.MoreVert,
                    contentDescription = "更多",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/** 启动失败的横幅。信息要具体：说清是哪一步失败的 */
@Composable
private fun LaunchErrorBanner(message: String, onDismiss: () -> Unit) {
    androidx.compose.material3.Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                androidx.compose.material.icons.Icons.Rounded.Warning,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "知道了",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .clip(MaterialTheme.shapes.small)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .clickableNoRipple(onDismiss)
            )
        }
    }
}

/** 无涟漪点击：用于"知道了"这类文字操作，涟漪在小文字上显得脏 */
fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.then(Modifier.clickable(onClick = onClick))

@Composable
private fun OutlinedAddButton(text: String, onClick: () -> Unit) {
    androidx.compose.material3.OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium
    ) {
        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text)
    }
}

/** 骨架屏：形状要和真实内容一致，否则加载完成时会有"跳一下"的感觉 */
@Composable
private fun HomeSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 大卡片骨架
        MizuCard(
            modifier = Modifier.fillMaxWidth(),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
        ) {
            Row {
                com.mizulauncher.ui.design.SkeletonBlock(
                    Modifier.size(96.dp),
                    RoundedCornerShape(16.dp)
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    com.mizulauncher.ui.design.SkeletonBlock(Modifier.fillMaxWidth(0.6f).height(22.dp))
                    Spacer(Modifier.height(10.dp))
                    com.mizulauncher.ui.design.SkeletonBlock(Modifier.fillMaxWidth(0.4f).height(14.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
            com.mizulauncher.ui.design.SkeletonBlock(Modifier.fillMaxWidth().height(52.dp))
        }
        SkeletonCard()
        SkeletonCard()
        SkeletonCard()
    }
}

@Composable
private fun EmptyHome(onOpenDownload: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        StateView(
            icon = androidx.compose.material.icons.Icons.Rounded.Add,
            title = "还没有任何游戏实例",
            description = "先去「下载」页挑一个 Minecraft 版本，装好之后这里会变成启动面板。",
            actionLabel = "去下载",
            onAction = onOpenDownload,
            tint = MaterialTheme.colorScheme.primary
        )
    }
}
