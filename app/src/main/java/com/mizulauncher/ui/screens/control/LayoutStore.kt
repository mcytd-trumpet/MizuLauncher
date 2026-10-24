package com.mizulauncher.ui.screens.control

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File

/**
 * 按键布局的**持久化**。
 *
 * ## 为什么必须落盘
 * 之前布局只活在 `remember { ControlLayout() }` 里，用户辛苦拖了十几分钟摆好的按键，
 * 一退出应用就全没了 —— 这不是「功能缺失」，是**数据丢失**。
 *
 * ## 按屏幕方向分开存
 * 横屏和竖屏的可用空间完全不同：横屏摇杆在左下、竖屏得往上挪。
 * 用一份布局覆盖两种方向，必然有一个方向是歪的。所以按 `portrait` / `landscape` 分开。
 *
 * ## 格式
 * 直接序列化 [ControlLayout]，坐标本来就是 0..1 的比例值，换机型、换分辨率都不用换算。
 */
class LayoutStore(rootDir: File) {

    private val dir = File(rootDir, "controls").apply { mkdirs() }
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    fun fileFor(orientation: String): File = File(dir, "$orientation.json")

    /** 读布局；文件不存在或内容坏掉都回落到默认布局，绝不能因为一个坏文件导致启动崩溃 */
    fun load(orientation: String): ControlLayout {
        val f = fileFor(orientation)
        if (!f.isFile) return ControlLayout(name = orientation)
        return runCatching {
            gson.fromJson(f.readText(), ControlLayout::class.java)?.let { sanitize(it, orientation) }
        }.getOrNull() ?: ControlLayout(name = orientation)
    }

    /** 保存；返回 false 表示写失败（磁盘满 / 权限），由 UI 决定是否提示 */
    fun save(orientation: String, layout: ControlLayout): Boolean = runCatching {
        fileFor(orientation).writeText(gson.toJson(layout.copy(name = orientation)))
        true
    }.getOrDefault(false)

    fun reset(orientation: String): ControlLayout {
        fileFor(orientation).delete()
        return ControlLayout(name = orientation)
    }

    /** 是否已经有用户自定义过的布局 */
    fun hasCustom(orientation: String): Boolean = fileFor(orientation).isFile

    /**
     * 清洗非法数据。
     *
     * 手改过 JSON、或者旧版本写入了超范围坐标，都会让控件飞到屏幕外，
     * 用户看到的是「按键没了」。这里统一夹到 [0,1] 并丢掉空层。
     */
    private fun sanitize(layout: ControlLayout, orientation: String): ControlLayout {
        val layers = layout.layers
            .filter { it.widgets.isNotEmpty() || layout.layers.size == 1 }
            .map { layer ->
                layer.copy(widgets = layer.widgets.map { w ->
                    w.copy(
                        x = w.x.coerceIn(0f, 1f),
                        y = w.y.coerceIn(0f, 1f),
                        width = w.width.coerceIn(0.02f, 1f),
                        height = w.height.coerceIn(0.02f, 1f),
                        opacity = w.opacity.coerceIn(0.1f, 1f)
                    )
                })
            }
        return if (layers.isEmpty()) ControlLayout(name = orientation) else layout.copy(name = orientation, layers = layers)
    }
}
