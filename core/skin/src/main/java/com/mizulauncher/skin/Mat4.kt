package com.mizulauncher.skin

/**
 * 3D 预览用的极简线性代数。
 *
 * ## 为什么不用现成的矩阵库
 * 只需要「4×4 矩阵 × 4 维向量」这一件事，引入一个几十 KB 的依赖不值当；
 * 更重要的原因是**可测**：纯 Kotlin 的浮点运算可以在 JVM 单测里逐位校验，
 * 而任何依赖 Android GL 的实现都没法在 CI 里跑。
 *
 * 约定：列主序（与 OpenGL 一致），`m[col * 4 + row]`。
 */
class Mat4(private val m: FloatArray = identityArray()) {

    companion object {
        fun identityArray() = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
        )

        fun identity() = Mat4()

        /** 平移 */
        fun translate(x: Float, y: Float, z: Float): Mat4 {
            val m = identityArray()
            m[12] = x; m[13] = y; m[14] = z
            return Mat4(m)
        }

        /** 缩放 */
        fun scale(x: Float, y: Float, z: Float): Mat4 {
            val m = identityArray()
            m[0] = x; m[5] = y; m[10] = z
            return Mat4(m)
        }

        /** 绕 X 轴旋转（俯仰）。角度制 */
        fun rotateX(deg: Float): Mat4 {
            val r = Math.toRadians(deg.toDouble())
            val c = kotlin.math.cos(r).toFloat()
            val s = kotlin.math.sin(r).toFloat()
            val m = identityArray()
            m[5] = c; m[6] = s
            m[9] = -s; m[10] = c
            return Mat4(m)
        }

        /** 绕 Y 轴旋转（偏航）。角度制。这是"转圈看皮肤"用到的那个 */
        fun rotateY(deg: Float): Mat4 {
            val r = Math.toRadians(deg.toDouble())
            val c = kotlin.math.cos(r).toFloat()
            val s = kotlin.math.sin(r).toFloat()
            val m = identityArray()
            m[0] = c; m[2] = -s
            m[8] = s; m[10] = c
            return Mat4(m)
        }
    }

    /** 矩阵乘法：`this * other`，语义与 GLSL 一致（作用于列向量右侧） */
    operator fun times(other: Mat4): Mat4 {
        val a = m
        val b = other.m
        val out = FloatArray(16)
        for (col in 0 until 4) {
            for (row in 0 until 4) {
                var sum = 0f
                for (k in 0 until 4) {
                    sum += a[k * 4 + row] * b[col * 4 + k]
                }
                out[col * 4 + row] = sum
            }
        }
        return Mat4(out)
    }

    /**
     * 变换一个点（w = 1），返回 xyz。
     * 只需要点变换，不需要方向向量变换，因此不实现 w 分量输出。
     */
    fun transform(x: Float, y: Float, z: Float): FloatArray {
        val m = m
        val nx = m[0] * x + m[4] * y + m[8] * z + m[12]
        val ny = m[1] * x + m[5] * y + m[9] * z + m[13]
        val nz = m[2] * x + m[6] * y + m[10] * z + m[14]
        return floatArrayOf(nx, ny, nz)
    }

    /** 便于测试与调试时比对 */
    fun raw(): FloatArray = m.copyOf()
}

/**
 * 3D 渲染需要的相机与投影参数。
 *
 * ## 用正交投影而不是透视
 * 皮肤预览是"看模型"，不是"看场景"。正交投影下无论模型转到哪个角度，
 * 各部位比例都保持一致，不会出现鼻子突然变大这类透视失真。
 * 官方启动器与各站点的皮肤预览也都是正交投影。
 */
data class PreviewCamera(
    /** 绕 Y 轴的角度，0 = 正面朝观察者 */
    val yawDeg: Float = -22f,
    /** 绕 X 轴的角度，正值为低头（从上往下看） */
    val pitchDeg: Float = 8f,
    /** 正交视野的半高（以皮肤像素为单位）。24 大约能框住全身还有余量 */
    val orthoHalfHeight: Float = 24f,
    /** 画布宽高比，由 Composable 传入 */
    val aspect: Float = 0.75f
) {
    /** 组合出「模型空间 → 视图空间」的旋转矩阵 */
    fun viewMatrix(): Mat4 = Mat4.rotateX(pitchDeg) * Mat4.rotateY(yawDeg)
}
