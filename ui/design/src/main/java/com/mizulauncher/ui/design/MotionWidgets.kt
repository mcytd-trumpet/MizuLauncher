package com.mizulauncher.ui.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mizulauncher.compat.MotionLevel
import com.mizulauncher.compat.MotionSpec
import kotlin.math.roundToInt

/**
 * 滚动数字。
 *
 * ## 为什么这个细节重要
 * 下载进度从 45% 跳到 46% 时，如果是直接替换文本，眼睛会看到"闪一下"。
 * 数字连续滚动则让变化"有过程"，心理上觉得更流畅——这是 UI 里性价比最高的动画之一。
 *
 * 实现上不逐位做数字滚轮（那样中文环境里字宽不一致会抖），
 * 而是把整个数值做插值，配合**等宽字体**，字宽恒定，滚动时不会左右晃。
 */
@Composable
fun AnimatedNumber(
    value: Float,
    modifier: Modifier = Modifier,
    style: TextStyle = MonoLargeStyle,
    color: Color = MaterialTheme.colorScheme.onSurface,
    suffix: String = "",
    decimals: Int = 0
) {
    val level = currentMotionLevel
    val animated by animateFloatAsState(
        targetValue = value,
        animationSpec = if (level == MotionLevel.None) {
            tween(0)
        } else {
            tween(
                durationMillis = MotionSpec.duration(MotionSpec.Medium, level),
                easing = MotionSpec.Decelerate
            )
        },
        label = "animatedNumber"
    )

    val text = if (decimals == 0) {
        animated.roundToInt().toString() + suffix
    } else {
        String.format(java.util.Locale.US, "%.${decimals}f", animated) + suffix
    }

    Text(
        text = text,
        modifier = modifier,
        style = style,
        color = color,
        textAlign = TextAlign.End,
        maxLines = 1
    )
}

/**
 * 进度环。
 *
 * 与 [AnimatedNumber] 配合使用：环平滑增长，中心数字平滑滚动。
 * 关键点：**传入的 progress 必须是 0f..1f 且单调不减**，
 * 否则会出现"环倒着转"——下载任务重试时进度从 80% 回到 0% 就是这种观感。
 * 调用方应当把重试视为一个新任务（进度归零并重置 key）。
 */
@Composable
fun MizuProgressRing(
    progress: Float,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 44.dp,
    strokeWidth: androidx.compose.ui.unit.Dp = 4.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    content: (@Composable () -> Unit)? = null
) {
    val level = currentMotionLevel
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = if (level == MotionLevel.None) {
            tween(0)
        } else {
            tween(
                durationMillis = MotionSpec.duration(MotionSpec.Medium, level),
                easing = MotionSpec.Standard
            )
        },
        label = "progressRing"
    )

    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        // 底层轨道：始终画满，让用户知道总量
        CircularProgressIndicator(
            progress = { 1f },
            modifier = Modifier.size(size),
            strokeWidth = strokeWidth,
            color = trackColor
        )
        CircularProgressIndicator(
            progress = { animated },
            modifier = Modifier.size(size),
            strokeWidth = strokeWidth,
            color = color,
            strokeCap = androidx.compose.material3.ProgressIndicatorDefaults.LinearStrokeCap
        )
        content?.invoke()
    }
}

/**
 * 文件大小格式化。
 *
 * 下载页每秒刷新，如果每次都新建 Formatter 会有额外开销；
 * 这里纯字符串拼接，无对象分配。
 */
object SizeFormat {
    private val units = arrayOf("B", "KB", "MB", "GB")

    fun human(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        var v = bytes.toDouble()
        var i = 0
        while (v >= 1024 && i < units.size - 1) {
            v /= 1024
            i++
        }
        return if (v >= 100) {
            "${v.roundToInt()} ${units[i]}"
        } else {
            String.format(java.util.Locale.US, "%.1f %s", v, units[i])
        }
    }

    /** 带总大小：「12.4 MB / 128 MB」 */
    fun ratio(done: Long, total: Long): String =
        if (total <= 0) human(done) else "${human(done)} / ${human(total)}"

    /** 速度：「1.2 MB/s」 */
    fun speed(bytesPerSec: Long): String = "${human(bytesPerSec)}/s"

    /** 剩余时间估算。小于 1 秒不显示，超过 1 小时显示「超过 1 小时」 */
    fun eta(remainBytes: Long, bytesPerSec: Long): String? {
        if (bytesPerSec <= 0 || remainBytes <= 0) return null
        val sec = remainBytes / bytesPerSec
        return when {
            sec < 1 -> null
            sec < 60 -> "${sec} 秒"
            sec < 3600 -> "${sec / 60} 分 ${sec % 60} 秒"
            else -> "超过 1 小时"
        }
    }
}

/**
 * 数字变化时的轻微放大脉冲。
 *
 * 用在「账户余额」「实例数量」这类不常变、但变了需要被注意到的数字上。
 * 不要用在进度条上——每秒都在变的东西跟着脉冲会非常吵。
 */
@Composable
fun NumberPulse(
    value: Number,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val level = currentMotionLevel
    val key = remember(value) { value }

    // 只做一次性脉冲，不做循环。None 档位直接跳过
    val scale = remember(key) { androidx.compose.runtime.mutableFloatStateOf(1f) }
    androidx.compose.runtime.LaunchedEffect(key) {
        if (level == MotionLevel.None) return@LaunchedEffect
        androidx.compose.animation.core.Animatable(1f).run {
            snapTo(1f)
            animateTo(1.08f, tween(MotionSpec.duration(MotionSpec.Short, level)))
            animateTo(1f, tween(MotionSpec.duration(MotionSpec.Short, level)))
        }
    }

    Box(modifier.scale(scale.floatValue)) { content() }
}

/** 行内指标：图标 + 数值 + 单位，用于统计条 */
@Composable
fun MetricRow(
    items: List<Pair<String, String>>,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEach { (label, value) ->
            androidx.compose.foundation.layout.Column {
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
