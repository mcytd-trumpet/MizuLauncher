package com.mizulauncher.ui.screens.control

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 触屏按键布局编辑器（PojavLauncher 的 CustomControls 可视化版 + 参考 Zalith 的层编辑器）。
 *
 * 设计要点：
 *  - **按层（Layer）组织**：主界面层 / 战斗层 / 菜单层，可切换显隐（参考 Zalith 的 LayerController）
 *  - **拖拽即所见**：控件可直接拖动、双指缩放，坐标以百分比保存（适配任意屏幕）
 *  - **事件绑定**：每个控件可绑定键盘键码、鼠标点击、摇杆轴或启动器动作
 */
data class ControlWidget(
    val id: String = java.util.UUID.randomUUID().toString().take(8),
    val type: WidgetType,
    /** 相对屏幕宽高的比例坐标 0..1 */
    val x: Float,
    val y: Float,
    val width: Float = 0.08f,
    val height: Float = 0.14f,
    val label: String = "",
    /** 绑定的键码（GLFW key code）或动作名 */
    val binding: String = "",
    val opacity: Float = 0.6f,
    val visible: Boolean = true
) {
    enum class WidgetType(val display: String) {
        BUTTON("按键"),
        TOGGLE("开关"),
        JOYSTICK("摇杆"),
        MOUSE("鼠标区域"),
        TEXT("文字"),
        DIRECTIONAL("方向键"),
        SLIDER("滑块")
    }
}

data class ControlLayer(
    val name: String = "默认层",
    val visible: Boolean = true,
    val widgets: List<ControlWidget> = emptyList()
)

data class ControlLayout(
    val name: String = "default",
    val layers: List<ControlLayer> = listOf(ControlLayer(name = "主层", widgets = defaultWidgets()))
) {
    companion object {
        /** 默认布局：左手摇杆 + 右手视角 + 常用动作键 */
        fun defaultWidgets(): List<ControlWidget> = listOf(
            ControlWidget(type = ControlWidget.WidgetType.JOYSTICK, x = 0.12f, y = 0.68f, width = 0.22f, height = 0.36f, label = "移动", binding = "axis"),
            ControlWidget(type = ControlWidget.WidgetType.MOUSE, x = 0.55f, y = 0.35f, width = 0.4f, height = 0.6f, label = "视角", binding = "mouse"),
            ControlWidget(type = ControlWidget.WidgetType.BUTTON, x = 0.86f, y = 0.30f, width = 0.07f, height = 0.10f, label = "挖", binding = "key:341"),
            ControlWidget(type = ControlWidget.WidgetType.BUTTON, x = 0.75f, y = 0.42f, width = 0.07f, height = 0.10f, label = "放", binding = "key:340"),
            ControlWidget(type = ControlWidget.WidgetType.BUTTON, x = 0.86f, y = 0.52f, width = 0.07f, height = 0.10f, label = "跳", binding = "key:32"),
            ControlWidget(type = ControlWidget.WidgetType.BUTTON, x = 0.75f, y = 0.62f, width = 0.07f, height = 0.10f, label = "潜", binding = "key:340"),
            ControlWidget(type = ControlWidget.WidgetType.TOGGLE, x = 0.94f, y = 0.06f, width = 0.05f, height = 0.07f, label = "背包", binding = "key:69"),
            ControlWidget(type = ControlWidget.WidgetType.BUTTON, x = 0.42f, y = 0.72f, width = 0.06f, height = 0.09f, label = "↑", binding = "key:87"),
            ControlWidget(type = ControlWidget.WidgetType.BUTTON, x = 0.36f, y = 0.79f, width = 0.06f, height = 0.09f, label = "←", binding = "key:65"),
            ControlWidget(type = ControlWidget.WidgetType.BUTTON, x = 0.48f, y = 0.79f, width = 0.06f, height = 0.09f, label = "→", binding = "key:68"),
            ControlWidget(type = ControlWidget.WidgetType.BUTTON, x = 0.42f, y = 0.86f, width = 0.06f, height = 0.09f, label = "↓", binding = "key:83")
        )
    }
}

/**
 * 布局预览 + 编辑画布。
 */
@Composable
fun ControlEditorCanvas(
    layout: ControlLayout,
    selectedId: String?,
    editing: Boolean,
    modifier: Modifier = Modifier,
    onSelect: (String?) -> Unit,
    onMove: (String, Float, Float) -> Unit,
    onResize: (String, Float, Float) -> Unit = { _, _, _ -> }
) {
    Box(modifier = modifier.fillMaxSize().background(Color(0xFF11151C))) {
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { onSelect(null) } }
        )
        val density = LocalDensity.current
        var canvasSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
        Box(Modifier.fillMaxSize().onGloballyPositioned { canvasSize = it.size }) {
            val w = with(density) { canvasSize.width.toDp() }
            val h = with(density) { canvasSize.height.toDp() }
            layout.layers.filter { it.visible }.flatMap { it.widgets }.forEach { widget ->
                WidgetView(
                    widget = widget,
                    selected = widget.id == selectedId,
                    editing = editing,
                    canvasW = w,
                    canvasH = h,
                    onSelect = { onSelect(widget.id) },
                    onMove = { dx, dy ->
                        onMove(
                            widget.id,
                            (widget.x + dx).coerceIn(0f, 0.95f),
                            (widget.y + dy).coerceIn(0f, 0.95f)
                        )
                    }
                )
            }
        }
        Text(
            "布局预览 · ${layout.name}",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.4f),
            modifier = Modifier.align(Alignment.TopStart).padding(10.dp)
        )
    }
}

@Composable
private fun WidgetView(
    widget: ControlWidget,
    selected: Boolean,
    editing: Boolean,
    canvasW: Dp,
    canvasH: Dp,
    onSelect: () -> Unit,
    onMove: (Float, Float) -> Unit
) {
    val sizeW = canvasW * widget.width
    val sizeH = canvasH * widget.height
    val offsetX = canvasW * widget.x
    val offsetY = canvasH * widget.y

    Box(
        modifier = Modifier
            .offset(x = offsetX, y = offsetY)
            .size(width = sizeW, height = sizeH)
            .then(
                if (editing) Modifier
                    .pointerInput(widget.id) {
                        detectDragGestures(onDragStart = { onSelect() }) { change, drag ->
                            change.consume()
                            onMove(drag.x / size.width, drag.y / size.height)
                        }
                    }
                else Modifier.pointerInput(widget.id) { detectTapGestures { onSelect() } }
            )
            .then(
                if (selected) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
                else Modifier
            )
    ) {
        when (widget.type) {
            ControlWidget.WidgetType.JOYSTICK -> JoystickPreview(widget.opacity)
            ControlWidget.WidgetType.MOUSE -> MouseAreaPreview(widget.opacity)
            ControlWidget.WidgetType.DIRECTIONAL -> DirectionalPreview(widget.opacity)
            ControlWidget.WidgetType.SLIDER -> SliderPreview(widget.opacity)
            else -> KeyCapWidget(widget)
        }
    }
}

@Composable
private fun KeyCapWidget(widget: ControlWidget) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = widget.opacity * 0.35f))
            .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            widget.label.ifBlank { widget.binding.substringAfter(':') },
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun JoystickPreview(opacity: Float) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            drawCircle(Color.White.copy(alpha = opacity * 0.18f), r, center)
            drawCircle(Color.White.copy(alpha = opacity * 0.5f), r, center, style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
            drawCircle(Color.White.copy(alpha = opacity * 0.7f), r * 0.42f, center)
        }
    }
}

@Composable
private fun MouseAreaPreview(opacity: Float) {
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(listOf(Color.White.copy(alpha = 0.06f), Color.Transparent))
            )
    )
}

@Composable
private fun DirectionalPreview(opacity: Float) {
    Box(Modifier.fillMaxSize()) {
        val items = listOf("▲" to Alignment.TopCenter, "◀" to Alignment.CenterStart, "▶" to Alignment.CenterEnd, "▼" to Alignment.BottomCenter)
        items.forEach { (t, a) ->
            Text(
                t,
                color = Color.White.copy(alpha = opacity + 0.2f),
                modifier = Modifier.align(a).padding(4.dp),
                style = MaterialTheme.typography.labelSmall
            )
        }
        Box(
            Modifier
                .align(Alignment.Center)
                .size(8.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = opacity))
        )
    }
}

@Composable
private fun SliderPreview(opacity: Float) {
    Box(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = opacity * 0.25f))
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(0.45f)
                .background(Color.White.copy(alpha = opacity * 0.6f))
        )
    }
}

/** 摇杆数据结构（供真实运行时使用，Android View 层会消费） */
data class JoystickState(
    val centerX: Float = 0f,
    val centerY: Float = 0f,
    val knobX: Float = 0f,
    val knobY: Float = 0f,
    val radius: Float = 120f
) {
    val normalizedX: Float get() = ((knobX - centerX) / radius).coerceIn(-1f, 1f)
    val normalizedY: Float get() = ((knobY - centerY) / radius).coerceIn(-1f, 1f)

    /** 角度（度），用于转换 WASD 方向 */
    val angleDeg: Float get() = (Math.toDegrees(atan2(normalizedY.toDouble(), normalizedX.toDouble()))).toFloat()

    /** 根据摇杆方向合成 WASD 按键状态 */
    fun toWasd(deadZone: Float = 0.15f): Set<String> {
        val nx = normalizedX
        val ny = normalizedY
        if (kotlin.math.abs(nx) < deadZone && kotlin.math.abs(ny) < deadZone) return emptySet()
        val a = Math.toDegrees(atan2(ny.toDouble(), nx.toDouble()))
        return buildSet {
            if (a > -135 && a <= -45) add("W")       // 上
            else if (a > -45 && a <= 22.5) { add("W"); add("D") }
            else if (a > 22.5 && a <= 67.5) add("D")
            else if (a > 67.5 && a <= 112.5) { add("S"); add("D") }
            else if (a > 112.5 && a <= 157.5) add("S")
            else if (a > 157.5 || a <= -157.5) { add("S"); add("A") }
            else if (a > -157.5 && a <= -112.5) add("A")
            else if (a > -112.5 && a <= -67.5) { add("W"); add("A") }
        }
    }

    companion object {
        /** 把摇杆位移投影成鼠标视角增量（右摇杆/视角区使用） */
        fun toLookDelta(nx: Float, ny: Float, sensitivity: Float = 1f): Pair<Float, Float> =
            (nx * 12f * sensitivity) to (ny * 12f * sensitivity)
    }
}
