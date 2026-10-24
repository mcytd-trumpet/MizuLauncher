package com.mizulauncher.ui.design

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.TransformOrigin
import com.mizulauncher.compat.MotionLevel
import com.mizulauncher.compat.MotionSpec

/**
 * 页面转场规范。
 *
 * ## 旧版"生硬"的具体原因
 * 旧版用 Navigation Compose 的默认转场：新页面**瞬间**替换旧页面，中间什么都没有。
 * 人眼对这种"跳变"的感知是"卡了一下"，即使实际帧率是满的。
 *
 * ## 这里的三类转场
 * 1. **前进**（[forwardEnter] / [forwardExit]）：新页从右侧滑入，旧页向左退 1/4 并淡出。
 *    这是 iOS/Material 的共同做法，方向感明确——用户知道"我往深处走了"。
 * 2. **后退**（[backEnter] / [backExit]）：镜像。配合 Android 14+ 的预测性返回，
 *    用户滑动到一半就能看到上一页。
 * 3. **同层切换**（[tabEnter] / [tabExit]）：底部导航切页不做左右滑动
 *    （那样会让人以为有层级关系），只做快速淡入 + 轻微上浮。
 *
 * ## 降级
 * [MotionLevel.Reduced] 时去掉所有位移，只保留淡入淡出；
 * [MotionLevel.None] 时完全无过渡（`EnterTransition.None`），
 * 因为对开了"移除动画"的用户，任何渐变都可能引起不适。
 */
object MizuTransitions {

    /** 前进进入：从右侧滑入 + 淡入 */
    @Composable
    fun forwardEnter(): EnterTransition {
        val level = currentMotionLevel
        return when (level) {
            MotionLevel.Full -> slideInHorizontally(
                animationSpec = tween(MotionSpec.duration(MotionSpec.Long, level), easing = MotionSpec.Decelerate),
                initialOffsetX = { it / 5 }
            ) + fadeIn(tween(MotionSpec.duration(MotionSpec.Medium, level)))
            MotionLevel.Reduced -> fadeIn(tween(MotionSpec.duration(MotionSpec.Medium, level)))
            MotionLevel.None -> EnterTransition.None
        }
    }

    /** 前进时旧页退出：轻微左移 + 淡出。位移要小，否则"两页一起动"会晕 */
    @Composable
    fun forwardExit(): ExitTransition {
        val level = currentMotionLevel
        return when (level) {
            MotionLevel.Full -> slideOutHorizontally(
                animationSpec = tween(MotionSpec.duration(MotionSpec.Long, level), easing = MotionSpec.Accelerate),
                targetOffsetX = { -it / 8 }
            ) + fadeOut(tween(MotionSpec.duration(MotionSpec.Short, level)))
            MotionLevel.Reduced -> fadeOut(tween(MotionSpec.duration(MotionSpec.Short, level)))
            MotionLevel.None -> ExitTransition.None
        }
    }

    /** 返回时的进入：从左侧小位移回来 */
    @Composable
    fun backEnter(): EnterTransition {
        val level = currentMotionLevel
        return when (level) {
            MotionLevel.Full -> slideInHorizontally(
                animationSpec = tween(MotionSpec.duration(MotionSpec.Long, level), easing = MotionSpec.Decelerate),
                initialOffsetX = { -it / 8 }
            ) + fadeIn(tween(MotionSpec.duration(MotionSpec.Medium, level)))
            MotionLevel.Reduced -> fadeIn(tween(MotionSpec.duration(MotionSpec.Medium, level)))
            MotionLevel.None -> EnterTransition.None
        }
    }

    /** 返回时当前页退出：向右滑出 */
    @Composable
    fun backExit(): ExitTransition {
        val level = currentMotionLevel
        return when (level) {
            MotionLevel.Full -> slideOutHorizontally(
                animationSpec = tween(MotionSpec.duration(MotionSpec.Long, level), easing = MotionSpec.Accelerate),
                targetOffsetX = { it / 5 }
            ) + fadeOut(tween(MotionSpec.duration(MotionSpec.Medium, level)))
            MotionLevel.Reduced -> fadeOut(tween(MotionSpec.duration(MotionSpec.Short, level)))
            MotionLevel.None -> ExitTransition.None
        }
    }

    /** 底部导航切页：只做淡入 + 6dp 上浮，不暗示层级 */
    @Composable
    fun tabEnter(): EnterTransition {
        val level = currentMotionLevel
        return when (level) {
            MotionLevel.Full -> fadeIn(tween(MotionSpec.duration(MotionSpec.Short, level))) +
                slideInVertically(
                    animationSpec = tween(MotionSpec.duration(MotionSpec.Medium, level), easing = MotionSpec.Decelerate),
                    initialOffsetY = { MotionSpec.distance(12, level) * 3 }
                )
            MotionLevel.Reduced -> fadeIn(tween(MotionSpec.duration(MotionSpec.Short, level)))
            MotionLevel.None -> EnterTransition.None
        }
    }

    @Composable
    fun tabExit(): ExitTransition {
        val level = currentMotionLevel
        return when (level) {
            MotionLevel.None -> ExitTransition.None
            else -> fadeOut(tween(MotionSpec.duration(MotionSpec.Short, level)))
        }
    }

    /** 对话框 / 底部弹层：从底部升起 + 缩放 */
    @Composable
    fun sheetEnter(): EnterTransition {
        val level = currentMotionLevel
        return when (level) {
            MotionLevel.Full -> slideInVertically(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMediumLow
                ),
                initialOffsetY = { it }
            ) + fadeIn(tween(MotionSpec.duration(MotionSpec.Short, level)))
            MotionLevel.Reduced -> fadeIn(tween(MotionSpec.duration(MotionSpec.Medium, level)))
            MotionLevel.None -> EnterTransition.None
        }
    }

    @Composable
    fun sheetExit(): ExitTransition {
        val level = currentMotionLevel
        return when (level) {
            MotionLevel.None -> ExitTransition.None
            else -> slideOutVertically(
                animationSpec = tween(MotionSpec.duration(MotionSpec.Medium, level), easing = MotionSpec.Accelerate),
                targetOffsetY = { it }
            ) + fadeOut(tween(MotionSpec.duration(MotionSpec.Short, level)))
        }
    }

    /** 列表项入场：从下方 16dp 浮上来。给每个 item 加 delay 就有"逐个冒出"的效果 */
    @Composable
    fun itemEnter(index: Int = 0): EnterTransition {
        val level = currentMotionLevel
        return when (level) {
            MotionLevel.Full -> fadeIn(
                tween(MotionSpec.duration(MotionSpec.Medium, level), delayMillis = (index.coerceAtMost(6)) * 40)
            ) + slideInVertically(
                animationSpec = tween(
                    MotionSpec.duration(MotionSpec.Medium, level),
                    delayMillis = (index.coerceAtMost(6)) * 40,
                    easing = MotionSpec.Decelerate
                ),
                initialOffsetY = { MotionSpec.distance(16, level) * 3 }
            )
            MotionLevel.Reduced -> fadeIn(tween(MotionSpec.duration(MotionSpec.Short, level)))
            MotionLevel.None -> EnterTransition.None
        }
    }

    /** 卡片按下时的缩放：0.97 是"按下去"的经典值，再小就夸张了 */
    @Composable
    fun pressScale(): Float = if (currentMotionLevel == MotionLevel.Full) 0.97f else 1f

    /** 通用缓动规范，供自定义动画使用 */
    fun <T> emphasized(durationBase: Int = MotionSpec.Medium): AnimationSpec<T> =
        tween(durationBase, easing = MotionSpec.Emphasized)

    /** 弹性规范（仅在允许弹簧的档位使用） */
    @Composable
    fun <T> springy(): AnimationSpec<T> {
        val level = currentMotionLevel
        return if (level.allowsSpring) {
            spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium)
        } else {
            tween(MotionSpec.duration(MotionSpec.Medium, level), easing = MotionSpec.Emphasized)
        }
    }
}

/** 从中心放大的弹入（用于对话框内的图标、成功提示） */
@Composable
fun popIn(): EnterTransition {
    val level = currentMotionLevel
    return when (level) {
        MotionLevel.None -> EnterTransition.None
        else -> scaleIn(
            animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium),
            initialScale = 0.85f,
            transformOrigin = TransformOrigin.Center
        ) + fadeIn(tween(MotionSpec.duration(MotionSpec.Short, level)))
    }
}

@Composable
fun popOut(): ExitTransition {
    val level = currentMotionLevel
    return when (level) {
        MotionLevel.None -> ExitTransition.None
        else -> scaleOut(
            animationSpec = tween(MotionSpec.duration(MotionSpec.Short, level)),
            targetScale = 0.9f
        ) + fadeOut(tween(MotionSpec.duration(MotionSpec.Short, level)))
    }
}

/** 供 UI 层便捷使用，避免到处 import AnimatedVisibilityScope */
typealias ContentScope = AnimatedVisibilityScope
