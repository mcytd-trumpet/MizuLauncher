package com.mizulauncher.skin

/**
 * Minecraft 皮肤纹理的几何定义与切割规则。
 *
 * ## 为什么需要这个文件
 * 皮肤是一张 64×64（现代）或 64×32（旧版）的 PNG，里面以**未展开的平面图**方式
 * 塞着一个立方体人物的六个面。要渲染 3D 预览，必须知道每个部位在纹理上的精确矩形，
 * 以及它在 3D 空间中对应哪个面、朝哪个方向。
 *
 * 这些数字来自 Minecraft 官方皮肤格式，**不是随便估的**。一旦错一个像素，
 * 渲染出来的角色脸上就会出现一条错位的条纹——这是最常见的皮肤预览 bug。
 *
 * ## 布局
 * ```
 *        ┌────┬────┬────┬────┐
 *   y=0  │    │头部│    │    │
 *        ├────┼────┼────┼────┤
 *   y=8  │    │身体│右臂│    │
 *        ├────┼────┼────┼────┤
 *  y=16  │右腿│左腿│    │    │
 *        └────┴────┴────┴────┘
 *         0    16   32   48   → x
 * ```
 * 头部占 8×8，其余肢体按 4×12 或 4×4 排布，每块都按「右-正面-左-背面-顶-底」的顺序铺开。
 */
object SkinLayout {

    /** 材质边长（现代皮肤 64×64） */
    const val SIZE_MODERN = 64

    /** 旧版皮肤高度（64×32），没有手臂与腿的第二层 */
    const val SIZE_LEGACY_HEIGHT = 32

    /** 单个立方体的三维尺寸（以"皮肤像素"为单位，1 像素 = 1/16 格） */
    data class Box(val width: Int, val height: Int, val depth: Int)

    /** 纹理上的一个矩形区域 */
    data class Rect(val x: Int, val y: Int, val w: Int, val h: Int)

    /**
     * 身体各部位尺寸。
     *
     * 注意命名：这是 `val` 而不是 `Box(8,8,8)` 直接写在 enum 里的原因——
     * enum 常量初始化时不能引用另一个 enum 的成员，否则会得到
     * "Enum entry is uninitialized"。用具名常量可以绕开初始化顺序问题，
     * 同时让调用方读起来更像自然语言（`ArmClassic` 比 `Box(4,12,4)` 清楚）。
     */
    val HeadBox = Box(8, 8, 8)
    val BodyBox = Box(8, 12, 4)
    val ArmClassic = Box(4, 12, 4)
    val ArmSlim = Box(3, 12, 4)
    val LegBox = Box(4, 12, 4)

    /** 头部起始点。所有偏移都从这里推 */
    const val HEAD_X = 0
    const val HEAD_Y = 0

    const val BODY_X = 16
    const val BODY_Y = 16

    const val ARM_RIGHT_X = 40
    const val ARM_RIGHT_Y = 16

    const val ARM_LEFT_X = 32
    const val ARM_LEFT_Y = 48

    const val LEG_RIGHT_X = 0
    const val LEG_RIGHT_Y = 16

    const val LEG_LEFT_X = 16
    const val LEG_LEFT_Y = 48

    /**
     * 为一个 [Box] 计算六个面在纹理上的位置。
     *
     * 展开规则（Minecraft 官方盒式展开）：
     * - 正面在 `(x + depth, y + depth)`，尺寸 `w × h`
     * - 右面在 `(x, y + depth)`，尺寸 `depth × h`
     * - 左面在 `(x + depth + w, y + depth)`
     * - 背面在 `(x + depth + w + depth, y + depth)`
     * - 顶面在 `(x + depth, y)`，尺寸 `w × depth`
     * - 底面在 `(x + depth + w, y)`，尺寸 `w × depth`
     */
    fun faces(box: Box, ox: Int, oy: Int): Map<Face, Rect> {
        val (w, h, d) = box
        return mapOf(
            Face.TOP to Rect(ox + d, oy, w, d),
            Face.BOTTOM to Rect(ox + d + w, oy, w, d),
            Face.RIGHT to Rect(ox, oy + d, d, h),
            Face.FRONT to Rect(ox + d, oy + d, w, h),
            Face.LEFT to Rect(ox + d + w, oy + d, d, h),
            Face.BACK to Rect(ox + d + w + d, oy + d, d, h)
        )
    }

    /** 立方体的六个面，命名与纹理展开顺序一致 */
    enum class Face { TOP, BOTTOM, RIGHT, FRONT, LEFT, BACK }

    /** 身体部位 */
    enum class Part(val box: Box, val ox: Int, val oy: Int) {
        HEAD(HeadBox, HEAD_X, HEAD_Y),
        BODY(BodyBox, BODY_X, BODY_Y),
        ARM_RIGHT(ArmClassic, ARM_RIGHT_X, ARM_RIGHT_Y),
        ARM_LEFT(ArmClassic, ARM_LEFT_X, ARM_LEFT_Y),
        LEG_RIGHT(LegBox, LEG_RIGHT_X, LEG_RIGHT_Y),
        LEG_LEFT(LegBox, LEG_LEFT_X, LEG_LEFT_Y);

        val faces: Map<Face, Rect> get() = faces(box, ox, oy)
    }

    /** 第二层（帽子/外套/袖套/裤腿）相对第一层的偏移，现代皮肤才有的 0.5 像素外扩 */
    const val OVERLAY_INFLATE = 0.5f

    /**
     * 判断这张皮肤是「64×32 旧版」还是「64×64 现代」。
     *
     * 关键点：旧版皮肤的右半边（x ≥ 32）是透明的空白区，
     * 因此**不能只看尺寸**——有些工具会把 64×32 补成 64×64 再保存。
     * 这里按尺寸判断即可，因为我们是自己下载的原图。
     */
    fun isLegacy(width: Int, height: Int): Boolean = height == SIZE_LEGACY_HEIGHT

    /**
     * 手臂模型：`default` 是 Alex 的细手臂（3 像素宽），`slim` 才是经典 Steve。
     *
     * 注意官方命名反直觉：`default` = 经典 4px 宽手臂，`slim` = 纤细 3px。
     * 很多第三方实现会搞反，导致预览的手臂比例对但纹理错位。
     */
    fun armBox(slim: Boolean): Box = if (slim) ArmSlim else ArmClassic
}
