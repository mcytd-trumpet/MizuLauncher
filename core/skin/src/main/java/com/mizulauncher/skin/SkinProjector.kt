package com.mizulauncher.skin

import kotlin.math.abs

/**
 * 把 3D 模型投影成 2D 多边形序列，供 Compose Canvas 直接绘制。
 *
 * ## 为什么不直接用 GLSurfaceView
 * 皮肤预览是几十个四边形，用 OpenGL 是杀鸡用牛刀，还会带来三个实际麻烦：
 *  1. 需要额外一个 Surface，嵌在列表里滚动时会和其他 Surface 抢图层；
 *  2. 低端机上的 GL 上下文创建是几百毫秒，一进页面就卡；
 *  3. **WebView 都不能保证可用的老设备，GL 驱动更不可靠**。
 *
 * Compose Canvas 是纯 CPU 的 2D 绘制，在任何 API 26 设备上都稳定。
 * 每个面用四条边做仿射近似（把四边形拆成两个三角形，各自贴一个纹理矩阵），
 * 视觉上与真 3D 几乎无差别，因为皮肤纹理本身就是低分辨率像素画。
 *
 * ## 画家算法
 * 没有深度缓冲，所以必须**从远到近**排序后绘制。按面中心在视图空间的 z 值排序即可：
 * 立方体是凸的，凸体上这种排序是精确的（不会出现互相穿插）。
 */
object SkinProjector {

    /** 投影后的一个面 */
    data class FaceQuad(
        val part: SkinLayout.Part,
        val face: SkinLayout.Face,
        /** 视图空间中的四个角点，顺序：左上、右上、右下、左下 */
        val points: FloatArray,
        /** 该面中心的视图空间 z（越大越近，用于排序） */
        val depth: Float,
        /** 纹理矩形 */
        val rect: SkinLayout.Rect,
        /** 是否朝向观察者。背面可以跳过绘制，省一半计算 */
        val visible: Boolean
    )

    /**
     * 生成整个角色的所有面。
     *
     * 坐标系：以**脚底中心**为原点，y 向上，x 向右，z 朝向观察者。
     * 这样模型自然"站"在画布中央偏下，符合人对角色预览的预期。
     */
    fun project(camera: PreviewCamera): List<FaceQuad> {
        val view = camera.viewMatrix()
        val out = ArrayList<FaceQuad>(36)

        // 各部位方块中心在模型空间的位置。
        // 从左到右：右臂(-6) 右腿(-2) 身体/头(0) 左腿(2) 左臂(6)。
        // 注意这里是"角色的右手"，站在观察者视角看是屏幕左侧——与游戏内视角一致。
        for (part in SkinLayout.Part.entries) {
            val center = when (part) {
                SkinLayout.Part.HEAD -> floatArrayOf(0f, 28f, 0f)
                SkinLayout.Part.BODY -> floatArrayOf(0f, 18f, 0f)
                SkinLayout.Part.ARM_RIGHT -> floatArrayOf(-6f, 18f, 0f)
                SkinLayout.Part.ARM_LEFT -> floatArrayOf(6f, 18f, 0f)
                SkinLayout.Part.LEG_RIGHT -> floatArrayOf(-2f, 6f, 0f)
                SkinLayout.Part.LEG_LEFT -> floatArrayOf(2f, 6f, 0f)
            }
            val box = part.box
            val faces = SkinLayout.faces(box, part.ox, part.oy)
            for ((face, rect) in faces) {
                val corners = faceCorners(box, center, face)
                val projected = FloatArray(8)
                var sumZ = 0f
                for (i in 0 until 4) {
                    val p = view.transform(corners[i * 3], corners[i * 3 + 1], corners[i * 3 + 2])
                    projected[i * 2] = p[0]
                    projected[i * 2 + 1] = p[1]
                    sumZ += p[2]
                }
                // 法线方向决定是否朝向观察者：用投影后的顶点绕序判断（屏幕空间叉积）
                val area = signedArea(projected)
                out += FaceQuad(
                    part = part,
                    face = face,
                    points = projected,
                    depth = sumZ / 4f,
                    rect = rect,
                    visible = area < -0.01f || abs(area) <= 0.01f
                )
            }
        }

        // 从远到近
        out.sortBy { it.depth }
        return out
    }

    /**
     * 计算立方体某个面在模型空间的四个角点。
     *
     * 顺序统一为：**从外部看**的左上 → 右上 → 右下 → 左下。
     * 这个顺序与纹理矩形的 UV 对应关系直接绑定，写错了皮肤就会镜像或上下颠倒。
     */
    private fun faceCorners(box: SkinLayout.Box, center: FloatArray, face: SkinLayout.Face): FloatArray {
        val hw = box.width / 2f
        val hh = box.height / 2f
        val hd = box.depth / 2f
        val cx = center[0]
        val cy = center[1]
        val cz = center[2]

        // 每个面给出 (左, 上, 右, 下) 四个角
        return when (face) {
            SkinLayout.Face.FRONT -> floatArrayOf(
                cx - hw, cy + hh, cz + hd,
                cx + hw, cy + hh, cz + hd,
                cx + hw, cy - hh, cz + hd,
                cx - hw, cy - hh, cz + hd
            )
            SkinLayout.Face.BACK -> floatArrayOf(
                // 背面从外看时左右是反的
                cx + hw, cy + hh, cz - hd,
                cx - hw, cy + hh, cz - hd,
                cx - hw, cy - hh, cz - hd,
                cx + hw, cy - hh, cz - hd
            )
            SkinLayout.Face.RIGHT -> floatArrayOf(
                cx + hw, cy + hh, cz + hd,
                cx + hw, cy + hh, cz - hd,
                cx + hw, cy - hh, cz - hd,
                cx + hw, cy - hh, cz + hd
            )
            SkinLayout.Face.LEFT -> floatArrayOf(
                cx - hw, cy + hh, cz - hd,
                cx - hw, cy + hh, cz + hd,
                cx - hw, cy - hh, cz + hd,
                cx - hw, cy - hh, cz - hd
            )
            SkinLayout.Face.TOP -> floatArrayOf(
                cx - hw, cy + hh, cz - hd,
                cx + hw, cy + hh, cz - hd,
                cx + hw, cy + hh, cz + hd,
                cx - hw, cy + hh, cz + hd
            )
            SkinLayout.Face.BOTTOM -> floatArrayOf(
                cx - hw, cy - hh, cz + hd,
                cx + hw, cy - hh, cz + hd,
                cx + hw, cy - hh, cz - hd,
                cx - hw, cy - hh, cz - hd
            )
        }
    }

    /** 有符号面积：符号表示绕序，用来判断朝向 */
    private fun signedArea(p: FloatArray): Float {
        var sum = 0f
        for (i in 0 until 4) {
            val x1 = p[i * 2]
            val y1 = p[i * 2 + 1]
            val x2 = p[((i + 1) % 4) * 2]
            val y2 = p[((i + 1) % 4) * 2 + 1]
            sum += x1 * y2 - x2 * y1
        }
        return sum / 2f
    }

    /**
     * 把视图空间坐标映射到画布像素坐标。
     *
     * 正交投影：视图空间的 x/y 直接线性映射，不需要除以 z。
     */
    fun toCanvas(quad: FaceQuad, camera: PreviewCamera, canvasW: Float, canvasH: Float): FloatArray {
        val scale = canvasH / (camera.orthoHalfHeight * 2f)
        val out = FloatArray(8)
        for (i in 0 until 4) {
            out[i * 2] = canvasW / 2f + quad.points[i * 2] * scale
            // 视图空间 y 向上，画布 y 向下，且模型以脚底为原点，需要上移半个身高
            out[i * 2 + 1] = canvasH / 2f - (quad.points[i * 2 + 1] - 16f) * scale
        }
        return out
    }
}
