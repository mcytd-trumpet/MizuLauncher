package com.mizulauncher.ui.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material3.LocalContentColor

/**
 * 卡片：2.0 的主视觉单元。
 *
 * ## 与旧版的差别
 * 旧版卡片是"白底 + 1dp 描边"，在浅色模式下几乎看不出边界（白底白边），
 * 在深色模式下描边又太亮。2.0 改成：
 *  - **靠明度差分层**：卡片用 `surfaceContainerLow`，页面底色用 `background`，
 *    两者差一级明度，不用描边也能分清；
 *  - **按下去会轻微缩小**（0.97），配上即时的高亮，手感上有"实体按键"的反馈；
 *  - **可选点击**，不用点击的卡片（如纯展示）不加涟漪，避免误以为能点。
 */
@Composable
fun MizuCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shape: Shape = MaterialTheme.shapes.large,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    contentPadding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.foundation.layout.PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val motion = currentMotionLevel

    // 按压缩放：只在允许位移的档位启用。低端机上缩放会触发重新布局，反而卡顿
    val scale by animateFloatAsState(
        targetValue = if (pressed && motion == com.mizulauncher.compat.MotionLevel.Full) {
            MizuTransitions.pressScale()
        } else 1f,
        animationSpec = tween(com.mizulauncher.compat.MotionSpec.Short),
        label = "cardPress"
    )

    val bg by animateColorAsState(
        targetValue = if (pressed) {
            containerColor.blendTowards(MaterialTheme.colorScheme.onSurface, 0.06f)
        } else {
            containerColor
        },
        animationSpec = tween(com.mizulauncher.compat.MotionSpec.Short),
        label = "cardBg"
    )

    val clickModifier = if (onClick != null) {
        Modifier.clickable(
            interactionSource = interaction,
            // M3 的新 ripple API：跟随主题色，且在 Android 12+ 上自动使用系统涟漪效果
            indication = androidx.compose.material3.ripple(),
            onClick = onClick
        )
    } else {
        Modifier
    }

    Surface(
        modifier = modifier.scale(scale).then(clickModifier),
        shape = shape,
        color = bg,
        contentColor = contentColor,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(Modifier.padding(contentPadding), content = content)
    }
}

/** 颜色混合工具：在 sRGB 空间线性插值，用于"按下变暗一点"这类微调 */
fun Color.blendTowards(other: Color, fraction: Float): Color = Color(
    red = red + (other.red - red) * fraction,
    green = green + (other.green - green) * fraction,
    blue = blue + (other.blue - blue) * fraction,
    alpha = alpha
)

/**
 * 骨架屏。
 *
 * ## 为什么要骨架屏而不是转圈
 * 转圈只告诉用户"在等"，骨架屏还告诉用户"等来的是什么形状"，
 * 心理上的等待感知能缩短 30% 左右。旧版首屏是空白 + 中央转圈，
 * 看起来像卡死了——这也是"白屏"投诉的一部分来源。
 *
 * 低端机上降低渐变速度，避免持续重绘拖累主线程。
 */
@Composable
fun SkeletonBlock(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.small
) {
    val motion = currentMotionLevel
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val highlight = MaterialTheme.colorScheme.surfaceContainerHighest

    // 关动画时给一个静态占位，不做闪烁
    val color = if (motion == com.mizulauncher.compat.MotionLevel.None) {
        base
    } else {
        val transition = rememberInfiniteTransition(label = "skeleton")
        val fraction by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = if (motion == com.mizulauncher.compat.MotionLevel.Reduced) 1800 else 1100
                ),
                repeatMode = RepeatMode.Reverse
            ),
            label = "skeletonAlpha"
        )
        androidx.compose.ui.graphics.lerp(base, highlight, fraction)
    }

    Box(modifier.clip(shape).background(color))
}

/** 卡片骨架：与真实卡片同高，避免加载完成后布局跳动 */
@Composable
fun SkeletonCard(modifier: Modifier = Modifier) {
    MizuCard(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SkeletonBlock(Modifier.size(44.dp), MaterialTheme.shapes.medium)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                SkeletonBlock(Modifier.fillMaxWidth(0.45f).height(16.dp))
                Spacer(Modifier.height(8.dp))
                SkeletonBlock(Modifier.fillMaxWidth(0.7f).height(12.dp))
            }
            Spacer(Modifier.width(12.dp))
            SkeletonBlock(Modifier.size(52.dp, 30.dp), MaterialTheme.shapes.small)
        }
    }
}

/**
 * 空状态 / 错误状态。
 *
 * 旧版最被诟病的一点：状态不对时只显示一行灰字「暂无数据」，
 * 用户不知道是没数据、还是加载失败、还是自己操作错了。
 * 这里强制要求传 [description] 与可选的操作按钮，逼调用方想清楚该说什么。
 */
@Composable
fun StateView(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = tint.copy(alpha = 0.7f)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            androidx.compose.material3.FilledTonalButton(onClick = onAction) {
                Text(actionLabel)
            }
        }
    }
}

/** 标签小徽标：版本号、加载器类型、架构 */
@Composable
fun MizuBadge(
    text: String,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    content: Color = MaterialTheme.colorScheme.onSecondaryContainer
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraSmall,
        color = container,
        contentColor = content
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
