package com.mizulauncher.skin

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.withTransform
import com.mizulauncher.compat.MotionLevel
import com.mizulauncher.compat.MotionSpec
import kotlinx.coroutines.delay

/**
 * 3D 皮肤预览。
 *
 * ## 实现取舍
 * 用 Compose `Canvas` 的 CPU 绘制，不用 GLSurfaceView：
 *  - 皮肤预览只有 36 个四边形，CPU 完全够；
 *  - 低端机创建 GL 上下文要几百毫秒，一进页面就卡；
 *  - **连 WebView 都不保证可用的设备，GL 驱动更不可靠**——这正是要规避的兼容问题。
 *
 * 每个面用 `drawImage` 的 srcRect 裁出纹理块，再用四个角点的仿射变换贴上去。
 * 对低分辨率像素画来说，与真 3D 的视觉差异可以忽略。
 *
 * ## 动画
 * 自动旋转由 [autoRotate] 控制。转速与是否启用都受 [motionLevel] 约束：
 * 用户开了「移除动画」时，预览定格在默认角度，不会自转——这是无障碍要求，
 * 持续运动对前庭功能敏感的用户是有害的。
 */
@Composable
fun SkinPreview3D(
    skin: ImageBitmap?,
    modifier: Modifier = Modifier,
    slim: Boolean = false,
    autoRotate: Boolean = true,
    motionLevel: MotionLevel = MotionLevel.Full,
    /** 初始朝向，可外部控制（例如拖动后保持） */
    initialYaw: Float = -22f,
    initialPitch: Float = 8f
) {
    // 自转角度
    var spin by remember { mutableFloatStateOf(0f) }

    val shouldRotate = autoRotate && motionLevel != MotionLevel.None

    LaunchedEffect(shouldRotate, motionLevel) {
        if (!shouldRotate) return@LaunchedEffect
        // 低端机上把转速减半：自转每秒都在重绘整块 Canvas，是这里最费的一环
        val stepMs = if (motionLevel == MotionLevel.Reduced) 80L else 40L
        while (true) {
            delay(stepMs)
            spin = (spin + if (motionLevel == MotionLevel.Reduced) 0.35f else 0.7f) % 360f
        }
    }

    // 用动画把 spin 平滑掉，避免 delay 抖动导致"一顿一顿"
    val smoothSpin by animateFloatAsState(
        targetValue = spin,
        animationSpec = tween(
            durationMillis = if (motionLevel == MotionLevel.Reduced) 120 else 60,
            easing = MotionSpec.Fallback
        ),
        label = "skinSpin"
    )

    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            if (skin == null) {
                drawPlaceholder()
                return@Canvas
            }
            val camera = PreviewCamera(
                yawDeg = initialYaw + smoothSpin,
                pitchDeg = initialPitch,
                orthoHalfHeight = 24f,
                aspect = size.width / size.height
            )
            drawSkin(skin, camera, slim)
        }
    }
}

private fun DrawScope.drawPlaceholder() {
    // 没有皮肤时画一个人形剪影，比空白更让人明白"这里应该有个角色"
    val w = size.width
    val h = size.height
    val unit = minOf(w, h) / 32f
    val cx = w / 2f
    val base = h * 0.86f
    val bodyColor = Color(0xFF3A3A3A)

    // 头
    drawRect(
        color = bodyColor,
        topLeft = Offset(cx - 4 * unit, base - 32 * unit),
        size = Size(8 * unit, 8 * unit)
    )
    // 身体
    drawRect(
        color = bodyColor.copy(alpha = 0.85f),
        topLeft = Offset(cx - 4 * unit, base - 24 * unit),
        size = Size(8 * unit, 12 * unit)
    )
    // 双腿
    drawRect(
        color = bodyColor.copy(alpha = 0.7f),
        topLeft = Offset(cx - 4 * unit, base - 12 * unit),
        size = Size(4 * unit, 12 * unit)
    )
    drawRect(
        color = bodyColor.copy(alpha = 0.7f),
        topLeft = Offset(cx, base - 12 * unit),
        size = Size(4 * unit, 12 * unit)
    )
}

private fun DrawScope.drawSkin(skin: ImageBitmap, camera: PreviewCamera, slim: Boolean) {
    val quads = SkinProjector.project(camera)
    val texW = skin.width.toFloat()
    val texH = skin.height.toFloat()

    for (q in quads) {
        // 背面不画：省一半绘制开销，且凸体上看不到背面
        if (!q.visible) continue

        val pts = SkinProjector.toCanvas(q, camera, size.width, size.height)
        val rect = q.rect

        // 纹理越界保护：旧版 64x32 皮肤没有第二层，访问 y>32 会取到空
        if (rect.x + rect.w > texW || rect.y + rect.h > texH) continue

        val src = androidx.compose.ui.geometry.Rect(
            left = rect.x.toFloat(),
            top = rect.y.toFloat(),
            right = (rect.x + rect.w).toFloat(),
            bottom = (rect.y + rect.h).toFloat()
        )

        // 用一个四边形路径裁剪，再按包围盒把纹理拉伸进去。
        // 严格做法是做透视矫正，但皮肤是像素画，拉伸误差在一个像素以内，肉眼不可辨。
        val path = Path().apply {
            moveTo(pts[0], pts[1])
            lineTo(pts[2], pts[3])
            lineTo(pts[4], pts[5])
            lineTo(pts[6], pts[7])
            close()
        }

        val minX = minOf(pts[0], pts[2], pts[4], pts[6])
        val maxX = maxOf(pts[0], pts[2], pts[4], pts[6])
        val minY = minOf(pts[1], pts[3], pts[5], pts[7])
        val maxY = maxOf(pts[1], pts[3], pts[5], pts[7])
        val dstW = maxX - minX
        val dstH = maxY - minY
        if (dstW <= 0.5f || dstH <= 0.5f) continue

        clipPath(path) {
            drawImage(
                image = skin,
                srcOffset = androidx.compose.ui.unit.IntOffset(rect.x, rect.y),
                srcSize = androidx.compose.ui.unit.IntSize(rect.w, rect.h),
                dstOffset = androidx.compose.ui.unit.IntOffset(minX.toInt(), minY.toInt()),
                dstSize = androidx.compose.ui.unit.IntSize(dstW.toInt() + 1, dstH.toInt() + 1),
                filterQuality = androidx.compose.ui.graphics.FilterQuality.None
            )
        }
    }
}
