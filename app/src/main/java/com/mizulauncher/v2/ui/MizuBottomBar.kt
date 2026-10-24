package com.mizulauncher.v2.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp
import com.mizulauncher.compat.MotionLevel
import com.mizulauncher.compat.MotionSpec
import com.mizulauncher.ui.design.currentMotionLevel
import com.mizulauncher.ui.design.mizuColors

/**
 * 底部导航。
 *
 * ## 选中态的动画
 * 旧版的底部导航只有一个文字变色，切换时几乎看不出反应。这里做了三件事：
 *
 * 1. **图标放大 1.15 倍**（Spring 风格的高亮，但不弹跳——弹跳在导航栏上显得廉价）；
 * 2. **选中项出现一个圆形"药丸"底**，从 0.8 倍放大到 1 倍并淡入。
 *    这是 M3 的标准做法，给用户"这个被按下了"的确定感；
 * 3. **文字加粗**。中文在 l12sp 这个尺寸下，靠颜色区分层级不够，
 *    字重变化才是真正可见的。
 *
 * ## 降级
 * [MotionLevel.Reduced] 时去掉缩放，只保留淡入（缩放会触发重排，低端机上掉帧明显）；
 * [MotionLevel.None] 时全部瞬时切换。
 */
@Composable
fun MizuBottomBar(
    current: MizuTab,
    onSelect: (MizuTab) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 0.dp
    ) {
        NavigationBar(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.navigationBars)
                .height(64.dp),
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            tonalElevation = 0.dp,
            windowInsets = WindowInsets(0)
        ) {
            MizuTab.entries.forEach { tab ->
                    val selected = tab == current
                    NavigationBarItem(
                        selected = selected,
                        onClick = { if (!selected) onSelect(tab) },
                        icon = { TabIcon(tab, selected) },
                        label = {
                            Text(
                                text = tab.label,
                                style = if (selected) {
                                    MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                                    )
                                } else {
                                    MaterialTheme.typography.labelMedium
                                },
                                maxLines = 1
                            )
                        },
                        alwaysShowLabel = true,
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            selectedTextColor = MaterialTheme.colorScheme.onSurface,
                            indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
            }
        }
    }
}

@Composable
private fun TabIcon(tab: MizuTab, selected: Boolean) {
    val level = currentMotionLevel
    val scale by animateFloatAsState(
        targetValue = if (selected && level == MotionLevel.Full) 1.12f else 1f,
        animationSpec = tween(MotionSpec.duration(MotionSpec.Medium, level), easing = MotionSpec.Emphasized),
        label = "tabIconScale"
    )

    Box(contentAlignment = Alignment.Center) {
        // 选中药丸：AnimatedVisibility 让它有"长出来"的过程
        AnimatedVisibility(
            visible = selected,
            enter = if (level == MotionLevel.None) {
                androidx.compose.animation.EnterTransition.None
            } else {
                fadeIn(tween(MotionSpec.duration(MotionSpec.Short, level))) +
                    scaleIn(
                        tween(MotionSpec.duration(MotionSpec.Medium, level), easing = MotionSpec.Emphasized),
                        initialScale = 0.7f
                    )
            },
            exit = if (level == MotionLevel.None) {
                androidx.compose.animation.ExitTransition.None
            } else {
                fadeOut(tween(MotionSpec.duration(MotionSpec.Short, level))) +
                    scaleOut(tween(MotionSpec.duration(MotionSpec.Short, level)), targetScale = 0.7f)
            }
        ) {
            Box(
                Modifier
                    .size(40.dp, 28.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer)
            )
        }

        Icon(
            imageVector = tab.icon,
            contentDescription = tab.label,
            modifier = Modifier.size(22.dp).scale(scale)
        )
    }
}

/**
 * 全局「正在安装」指示条。
 *
 * 用户切到别的页面时，安装仍在后台跑。如果没有全局提示，
 * 他可能会以为任务被取消了，或者忘了还在下载就退出 App。
 *
 * 这条不是进度条（进度条在下载页有），而是一个**存在性提示**：
 * 细条 + 一行小字，不抢注意力但一直可见。
 */
@Composable
fun GlobalTaskBar(
    text: String,
    progress: Float,
    modifier: Modifier = Modifier
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(MotionSpec.Short),
        label = "taskBarProgress"
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
    ) {
        Box(Modifier.fillMaxWidth()) {
            androidx.compose.foundation.layout.Column {
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Build,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
                    Text(
                        text = text,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
                Box(
                    Modifier
                        .fillMaxWidth(animatedProgress)
                        .height(2.dp)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
    }
}
