package com.mizulauncher.skin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 皮肤几何与投影测试。
 *
 * 这些断言锁住的是**皮肤预览错一像素就看得出来**的那些细节：
 * 头盔画到脸上、左右手反了、背面镜像。
 */
class SkinLayoutTest {

    @Test
    fun `头部六面位置符合官方皮肤格式`() {
        val f = SkinLayout.faces(SkinLayout.HeadBox, SkinLayout.HEAD_X, SkinLayout.HEAD_Y)
        // 头部是 8x8x8，展开后每个面 8x8
        assertEquals(SkinLayout.Rect(8, 0, 8, 8), f[SkinLayout.Face.TOP])
        assertEquals(SkinLayout.Rect(16, 0, 8, 8), f[SkinLayout.Face.BOTTOM])
        assertEquals(SkinLayout.Rect(0, 8, 8, 8), f[SkinLayout.Face.RIGHT])
        assertEquals(SkinLayout.Rect(8, 8, 8, 8), f[SkinLayout.Face.FRONT])
        assertEquals(SkinLayout.Rect(16, 8, 8, 8), f[SkinLayout.Face.LEFT])
        assertEquals(SkinLayout.Rect(24, 8, 8, 8), f[SkinLayout.Face.BACK])
    }

    @Test
    fun `身体六面位置符合官方皮肤格式`() {
        val f = SkinLayout.faces(SkinLayout.BodyBox, SkinLayout.BODY_X, SkinLayout.BODY_Y)
        // 身体 8 宽 12 高 4 深，从 (16,16) 开始
        assertEquals(SkinLayout.Rect(20, 16, 8, 4), f[SkinLayout.Face.TOP])
        assertEquals(SkinLayout.Rect(28, 16, 8, 4), f[SkinLayout.Face.BOTTOM])
        assertEquals(SkinLayout.Rect(16, 20, 4, 12), f[SkinLayout.Face.RIGHT])
        assertEquals(SkinLayout.Rect(20, 20, 8, 12), f[SkinLayout.Face.FRONT])
        assertEquals(SkinLayout.Rect(28, 20, 4, 12), f[SkinLayout.Face.LEFT])
        assertEquals(SkinLayout.Rect(32, 20, 4, 12), f[SkinLayout.Face.BACK])
    }

    @Test
    fun `右臂与左臂在纹理上不重叠`() {
        val right = SkinLayout.Part.ARM_RIGHT.faces.values
        val left = SkinLayout.Part.ARM_LEFT.faces.values
        // 任何一个右臂矩形都不能与左臂矩形相交，否则手臂纹理互相污染
        for (r in right) {
            for (l in left) {
                val overlapX = maxOf(0, minOf(r.x + r.w, l.x + l.w) - maxOf(r.x, l.x))
                val overlapY = maxOf(0, minOf(r.y + r.h, l.y + l.h) - maxOf(r.y, l.y))
                assertEquals("右臂 ${r} 与左臂 ${l} 重叠", 0, overlapX * overlapY)
            }
        }
    }

    @Test
    fun `腿部纹理不与身体重叠`() {
        val legs = SkinLayout.Part.LEG_RIGHT.faces.values + SkinLayout.Part.LEG_LEFT.faces.values
        val body = SkinLayout.Part.BODY.faces.values
        for (l in legs) {
            for (b in body) {
                val overlapX = maxOf(0, minOf(l.x + l.w, b.x + b.w) - maxOf(l.x, b.x))
                val overlapY = maxOf(0, minOf(l.y + l.h, b.y + b.h) - maxOf(l.y, b.y))
                assertEquals("腿部 $l 与身体 $b 重叠", 0, overlapX * overlapY)
            }
        }
    }

    @Test
    fun `所有面都在六十四像素范围内`() {
        // 超出范围意味着读到了不存在的位置，运行时会取到透明像素
        for (part in SkinLayout.Part.entries) {
            for ((face, rect) in part.faces) {
                assertTrue("$part/$face 越界：$rect", rect.x >= 0 && rect.y >= 0)
                assertTrue("$part/$face 越界：$rect", rect.x + rect.w <= SkinLayout.SIZE_MODERN)
                assertTrue("$part/$face 越界：$rect", rect.y + rect.h <= SkinLayout.SIZE_MODERN)
            }
        }
    }

    @Test
    fun `旧版皮肤识别`() {
        assertTrue(SkinLayout.isLegacy(64, 32))
        assertFalse(SkinLayout.isLegacy(64, 64))
    }

    @Test
    fun `纤细手臂比经典手臂窄一像素`() {
        assertEquals(4, SkinLayout.armBox(slim = false).width)
        assertEquals(3, SkinLayout.armBox(slim = true).width)
        // 高度深度不变，只有宽度不同
        assertEquals(SkinLayout.armBox(false).height, SkinLayout.armBox(true).height)
    }
}

class SkinProjectorTest {

    @Test
    fun `全身六个部位共三十六个面`() {
        val quads = SkinProjector.project(PreviewCamera())
        assertEquals(6 * 6, quads.size)
    }

    @Test
    fun `按深度从远到近排序`() {
        // 画家算法要求：先画远的，后画近的。排序错了会出现"后脑勺盖住脸"
        val quads = SkinProjector.project(PreviewCamera(yawDeg = 30f, pitchDeg = 10f))
        for (i in 1 until quads.size) {
            assertTrue(
                "第 $i 个面比前一个更远，排序错误",
                quads[i].depth >= quads[i - 1].depth - 0.001f
            )
        }
    }

    @Test
    fun `正面朝向观察者时能看到脸`() {
        // yaw=0 表示正面朝观察者（正面朝 +z）
        val quads = SkinProjector.project(PreviewCamera(yawDeg = 0f, pitchDeg = 0f))
        val headFront = quads.first { it.part == SkinLayout.Part.HEAD && it.face == SkinLayout.Face.FRONT }
        assertTrue("正面朝前时头部的 FRONT 面必须可见", headFront.visible)
    }

    @Test
    fun `转到背面时正面的脸被判定为不可见`() {
        // yaw=180 时看到的应该是后脑勺，脸部应该被判为背面
        val quads = SkinProjector.project(PreviewCamera(yawDeg = 180f, pitchDeg = 0f))
        val headFront = quads.first { it.part == SkinLayout.Part.HEAD && it.face == SkinLayout.Face.FRONT }
        val headBack = quads.first { it.part == SkinLayout.Part.HEAD && it.face == SkinLayout.Face.BACK }
        assertFalse("转到背面后，脸部不应可见", headFront.visible)
        assertTrue("转到背面后，后脑勺必须可见", headBack.visible)
    }

    @Test
    fun `左右旋转互为镜像但面集一致`() {
        val left = SkinProjector.project(PreviewCamera(yawDeg = -30f))
        val right = SkinProjector.project(PreviewCamera(yawDeg = 30f))
        // 面数量与部位构成必须完全一致，只是点的位置镜像
        assertEquals(left.size, right.size)
        assertEquals(
            left.map { it.part to it.face }.toSet(),
            right.map { it.part to it.face }.toSet()
        )
    }

    @Test
    fun `画布坐标落在给定区域内`() {
        val camera = PreviewCamera(orthoHalfHeight = 24f, aspect = 0.75f)
        val quads = SkinProjector.project(camera)
        val w = 300f
        val h = 400f
        for (q in quads) {
            val pts = SkinProjector.toCanvas(q, camera, w, h)
            for (i in 0 until 4) {
                val x = pts[i * 2]
                val y = pts[i * 2 + 1]
                // 允许少量越界（模型比视野大时），但不能离谱，否则说明单位算错了
                assertTrue("x=$x 超出画布过多", x > -w && x < w * 2)
                assertTrue("y=$y 超出画布过多", y > -h && y < h * 2)
            }
        }
    }

    @Test
    fun `头部在身体上方`() {
        val camera = PreviewCamera()
        val quads = SkinProjector.project(camera)
        fun centerY(part: SkinLayout.Part): Float {
            val q = quads.first { it.part == part }
            return (q.points[1] + q.points[3] + q.points[5] + q.points[7]) / 4f
        }
        // 视图空间 y 向上，所以头部 y 必须大于身体
        assertTrue("头部应在身体上方", centerY(SkinLayout.Part.HEAD) > centerY(SkinLayout.Part.BODY))
        assertTrue("身体应在腿部上方", centerY(SkinLayout.Part.BODY) > centerY(SkinLayout.Part.LEG_RIGHT))
    }
}

class Mat4Test {

    @Test
    fun `单位矩阵不改变点`() {
        val p = Mat4.identity().transform(1f, 2f, 3f)
        assertEquals(1f, p[0], 0.0001f)
        assertEquals(2f, p[1], 0.0001f)
        assertEquals(3f, p[2], 0.0001f)
    }

    @Test
    fun `平移矩阵按预期移动点`() {
        val p = Mat4.translate(10f, 20f, 30f).transform(1f, 1f, 1f)
        assertEquals(11f, p[0], 0.0001f)
        assertEquals(21f, p[1], 0.0001f)
        assertEquals(31f, p[2], 0.0001f)
    }

    @Test
    fun `绕 y 轴旋转三百六十度回到原点`() {
        val p = Mat4.rotateY(360f).transform(5f, 0f, 0f)
        assertEquals(5f, p[0], 0.001f)
        assertEquals(0f, p[1], 0.001f)
        assertEquals(0f, p[2], 0.001f)
    }

    @Test
    fun `绕 y 轴旋转九十度把手性换到深度上`() {
        // 绕 y 转 90 度：+x 方向应转到 -z（右手系，从 +y 俯视顺时针）
        val p = Mat4.rotateY(90f).transform(1f, 0f, 0f)
        assertEquals(0f, p[0], 0.001f)
        assertEquals(0f, p[1], 0.001f)
        assertEquals(-1f, p[2], 0.001f)
    }

    @Test
    fun `矩阵乘法顺序与 GLSL 一致`() {
        // 先平移后旋转 != 先旋转后平移，验证顺序没写反
        val translateThenRotate = Mat4.rotateY(90f) * Mat4.translate(1f, 0f, 0f)
        val a = translateThenRotate.transform(0f, 0f, 0f)
        assertEquals(0f, a[0], 0.001f)
        assertEquals(-1f, a[2], 0.001f)

        val rotateThenTranslate = Mat4.translate(1f, 0f, 0f) * Mat4.rotateY(90f)
        val b = rotateThenTranslate.transform(0f, 0f, 0f)
        assertEquals(1f, b[0], 0.001f)
        assertEquals(0f, b[2], 0.001f)
    }
}

class PreviewCameraTest {

    @Test
    fun `默认相机略偏向一侧以便看出立体感`() {
        val c = PreviewCamera()
        // 完全正面(yaw=0)看不出厚度，完全侧面(yaw=90)看不到脸，取中间值
        assertTrue("默认视角应带一点偏转", c.yawDeg != 0f)
        assertTrue("偏转不应超过 45 度", kotlin.math.abs(c.yawDeg) < 45f)
        assertNotNull(c.viewMatrix())
    }

    @Test
    fun `视野半高能框住全身`() {
        // 角色高度约 32 皮肤像素（头 8 + 身体 12 + 腿 12），半高 24 的视野直径 48，足够
        val c = PreviewCamera()
        assertTrue("正交视野太小会裁掉头部或脚", c.orthoHalfHeight >= 20f)
    }
}
