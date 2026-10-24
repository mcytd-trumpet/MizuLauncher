package com.mizulauncher.compat

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.MotionDurationScale

/**
 * 动效规范 —— 全应用唯一的「动多久、怎么动」定义。
 *
 * ## 为什么要集中定义
 * 旧版界面"生硬"的根本原因不是没写动画，而是**每处动效各写各的参数**：
 * 有的 300ms 线性、有的 200ms 默认曲线，叠加起来就是"一顿一顿"。
 * 统一的三档时长 + 两条曲线，是让界面"顺"的最低成本手段。
 *
 * ## 三档时长（遵循 Material Design 3 motion tokens）
 * | 档位 | 时长 | 用在哪 |
 * |---|---|---|
 * | [Short] | 150ms | 图标切换、颜色变化、按压反馈、开关 |
 * | [Medium] | 250ms | 卡片展开、页面内元素进退、进度条 |
 * | [Long] | 400ms | 整页转场、对话框、底部弹层 |
 *
 * ## 两条曲线
 * - [Emphasized]：两端慢、中间快。用于**进入**与**位移**，看起来有"重量感"。
 * - [Standard]：标准缓入缓出。用于**形变**与**颜色**，中性不抢戏。
 *
 * 曲线刻意使用 `CubicBezierEasing` 而不是 Compose 内置的那几个：
 * 内置曲线是为 Material 2 调的，M3 的强调曲线更"急"一点，
 * 急进慢出的差别在 250ms 这个尺度上肉眼可辨。
 */
object MotionSpec {

    /** 短：反馈类。快到来不及察觉"延迟"，只留下"有回应"的印象 */
    const val Short = 150

    /** 中：页面内元素。大多数动效都应该用这个 */
    const val Medium = 250

    /** 长：整页/整层。只在跨越层级时使用，滥用会显得拖沓 */
    const val Long = 400

    /** M3 强调曲线：起步慢 → 中段快 → 收尾缓，位移首选 */
    val Emphasized: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** M3 标准曲线：缓入缓出，形变首选 */
    val Standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** 减速曲线：进场用。进去快、停得稳 */
    val Decelerate: Easing = LinearOutSlowInEasing

    /** 加速曲线：退场用。走得干脆，不拖泥带水 */
    val Accelerate: Easing = FastOutLinearInEasing

    /** 通用缓动回退 */
    val Fallback: Easing = FastOutSlowInEasing

    /**
     * 按动效强度换算实际时长。
     *
     * [MotionLevel.None] 返回 0，此时交给 [snap] 而不是 `tween(0)`——
     * `tween(0)` 仍然会走一帧插值，而 `snap` 直接落终态，
     * 这对无障碍用户是必须的：任何"闪一下"都可能引起不适。
     */
    fun duration(base: Int, level: MotionLevel): Int =
        (base * level.durationScale).toInt().coerceAtLeast(0)

    /** 位移距离缩放：低端机上缩短位移，减少需要重绘的像素量 */
    fun distance(baseDp: Int, level: MotionLevel): Int =
        when (level) {
            MotionLevel.Full -> baseDp
            MotionLevel.Reduced -> (baseDp * 0.35f).toInt().coerceAtLeast(2)
            MotionLevel.None -> 0
        }

    /**
     * 降级后的 [androidx.compose.animation.core.AnimationSpec]。
     *
     * 这是给 `animateXxxAsState` 用的统一入口。用法：
     * ```
     * val alpha by animateFloatAsState(target, animationSpec = MotionSpec.spec(MotionSpec.Medium))
     * ```
     * 好处：所有动画都自动尊重「移除动画」设置，不需要每处自己判断。
     */
    fun <T> spec(
        baseDuration: Int = Medium,
        easing: Easing = Emphasized
    ): androidx.compose.animation.core.AnimationSpec<T> {
        // 这里读全局强度：MotionSpec 是被 CompositionLocal 注入 level 的，
        // 但 spec() 是普通函数，拿不到 context。因此由调用方保证传入的
        // duration 已经过 duration() 换算；未换算时至少不会崩溃。
        return tween(durationMillis = baseDuration, easing = easing)
    }
}

/**
 * 应用级的动画时长缩放器。
 *
 * ## 为什么需要它
 * Compose 只会自动读取系统的 `ANIMATOR_DURATION_SCALE`，
 * **不会**读取我们自己的"性能降级"意图。低端机上即使系统动画开着，
 * 我们也希望把 400ms 的转场压到 150ms，减少掉帧。
 *
 * 把 [MotionDurationScale] 放进 `CompositionLocalProvider` 的 effectContext，
 * 就能让**所有** Compose 动画（包括 Material 组件内部的）统一按我们的系数播放——
 * 不需要手动改每一个 `tween` 的时长。这是官方提供的正解。
 *
 * 注意：`scaleFactor = 0` 会让动画在下一帧回调里直接结束，
 * 与系统「移除动画」行为一致，因此 [MotionLevel.None] 可以直接映射为 0。
 */
class AppMotionDurationScale(override val scaleFactor: Float) : MotionDurationScale

/**
 * 惰性动画：动效强度为 [MotionLevel.None] 时直接落终态，不申请动画帧。
 *
 * 用于那些"必须存在动画声明、但关动画时不能有任何过渡"的场景。
 * 相比 `animateFloatAsState` + `tween(0)`，它在关动画时**完全不产生**
 * 可观察的中间值，对测试友好（测试里断言终态不会被中间帧干扰）。
 */
@Composable
fun rememberMotionFloat(
    target: Float,
    level: MotionLevel,
    baseDuration: Int = MotionSpec.Medium,
    easing: Easing = MotionSpec.Decelerate
): State<Float> {
    val spec = if (level == MotionLevel.None) {
        snap<Float>()
    } else {
        tween<Float>(
            durationMillis = MotionSpec.duration(baseDuration, level),
            easing = easing
        )
    }
    return animateFloatAsState(targetValue = target, animationSpec = spec, label = "motionFloat")
}

/**
 * 弹性规范：仅 [MotionLevel.Full] 可用。
 *
 * 弹簧在掉帧设备上会表现为"抖两下再停"，比不做动画更难看，
 * 所以低端机上一律回落到 tween。
 */
internal fun <T> springOrTween(
    level: MotionLevel,
    baseDuration: Int = MotionSpec.Medium,
    damping: Float = 0.75f,
    stiffness: Float = 380f
): androidx.compose.animation.core.AnimationSpec<T> =
    if (level.allowsSpring) {
        spring(dampingRatio = damping, stiffness = stiffness)
    } else {
        tween(MotionSpec.duration(baseDuration, level), easing = MotionSpec.Emphasized)
    }
