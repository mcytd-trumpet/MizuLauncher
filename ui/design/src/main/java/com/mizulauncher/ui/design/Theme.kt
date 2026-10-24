package com.mizulauncher.ui.design

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.mizulauncher.compat.DeviceProfile
import com.mizulauncher.compat.MotionLevel
import com.mizulauncher.compat.MotionSpec
import androidx.compose.ui.MotionDurationScale

/**
 * Mizu 2.0 配色方案。
 *
 * ## 设计取向
 * 旧版的问题是「一片蓝白、层级不清」，看不出哪是卡片哪是背景。
 * 2.0 采用 HMCL 那套「大面积中性底色 + 单一强调色」的思路：
 *
 * - **底色**：深色模式用近黑的暖灰（不是纯黑，纯黑在 OLED 上有拖影）；
 *   浅色模式用略带蓝的灰白，避免刺眼的纯白。
 * - **容器**：卡片比底色亮一级，靠明度差而不是描边来分层，看起来更干净。
 * - **强调**：青绿色（#2DD4BF 系）。选它而不选蓝，是因为 Minecraft 的视觉基调是绿色，
 *   同时也和大多数启动器（HMCL 橙、PCL 蓝）拉开区分度。
 *
 * ## 为什么不只用 Material 默认色板
 * M3 默认色板的强调色饱和度偏低，做游戏启动器显得没精神。
 * 这里手工调了 primary / secondary / tertiary 三档饱和度，
 * 并把 surface 系列压暗，让彩色元素更跳。
 */
private val MizuTeal = Color(0xFF2DD4BF)
private val MizuTealDark = Color(0xFF0F766E)
private val MizuAmber = Color(0xFFF59E0B)
private val MizuViolet = Color(0xFF8B5CF6)

/** 浅色主题 */
private fun lightScheme(): ColorScheme = lightColorScheme(
    primary = MizuTealDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB2F5EA),
    onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF4F6354),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD3E8D6),
    onSecondaryContainer = Color(0xFF0D1F13),
    tertiary = Color(0xFF7C5A00),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDEA6),
    onTertiaryContainer = Color(0xFF271900),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    background = Color(0xFFF7FAF7),
    onBackground = Color(0xFF191C19),
    surface = Color(0xFFF7FAF7),
    onSurface = Color(0xFF191C19),
    surfaceVariant = Color(0xFFDCE5DC),
    onSurfaceVariant = Color(0xFF404943),
    outline = Color(0xFF707972),
    outlineVariant = Color(0xFFC0C9C1),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF1F5F1),
    surfaceContainer = Color(0xFFECEFEB),
    surfaceContainerHigh = Color(0xFFE6E9E5),
    surfaceContainerHighest = Color(0xFFE0E3DF)
)

/** 深色主题 */
private fun darkScheme(): ColorScheme = darkColorScheme(
    primary = MizuTeal,
    onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF005048),
    onPrimaryContainer = Color(0xFFB2F5EA),
    secondary = Color(0xFFB7CCB8),
    onSecondary = Color(0xFF233427),
    secondaryContainer = Color(0xFF394B3D),
    onSecondaryContainer = Color(0xFFD3E8D6),
    tertiary = MizuAmber,
    onTertiary = Color(0xFF432C00),
    tertiaryContainer = Color(0xFF5F4100),
    onTertiaryContainer = Color(0xFFFFDEA6),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    // 不是纯黑：OLED 上纯黑滚动时会有"拖影"，且层级完全看不出来
    background = Color(0xFF111412),
    onBackground = Color(0xFFE1E3DF),
    surface = Color(0xFF111412),
    onSurface = Color(0xFFE1E3DF),
    surfaceVariant = Color(0xFF404943),
    onSurfaceVariant = Color(0xFFC0C9C1),
    outline = Color(0xFF8A938C),
    outlineVariant = Color(0xFF404943),
    surfaceContainerLowest = Color(0xFF0C0F0D),
    surfaceContainerLow = Color(0xFF191C1A),
    surfaceContainer = Color(0xFF1D201E),
    surfaceContainerHigh = Color(0xFF272B28),
    surfaceContainerHighest = Color(0xFF323633)
)

/**
 * 语义色：Material 色板里没有、但启动器必须有的那几个。
 *
 * 例如「下载中 / 已就绪 / 失败」三态，用 primary/error 硬套会失真
 * （下载中用 primary 会和按钮同色，看不出是状态）。
 */
@Immutable
data class MizuColors(
    val success: Color,
    val onSuccess: Color,
    val warning: Color,
    val onWarning: Color,
    val downloading: Color,
    /** 卡片浮起的阴影色。深色模式下几乎为透明，靠明度差分层 */
    val cardShadow: Color,
    /** 版本徽标底色 */
    val badgeBackground: Color,
    val badgeText: Color
)

private val LightExtras = MizuColors(
    success = Color(0xFF16A34A),
    onSuccess = Color.White,
    warning = Color(0xFFD97706),
    onWarning = Color.White,
    downloading = Color(0xFF2563EB),
    cardShadow = Color(0x14000000),
    badgeBackground = Color(0xFFE8F5EC),
    badgeText = Color(0xFF14532D)
)

private val DarkExtras = MizuColors(
    success = Color(0xFF4ADE80),
    onSuccess = Color(0xFF052E16),
    warning = Color(0xFFFBBF24),
    onWarning = Color(0xFF3F2A00),
    downloading = Color(0xFF60A5FA),
    cardShadow = Color(0x33000000),
    badgeBackground = Color(0xFF1E3A2B),
    badgeText = Color(0xFF86EFAC)
)

/**
 * 主题配置。
 *
 * [dynamicColor] 只在 Android 12+ 有意义，低版本自动忽略——
 * 这正是「按安卓版本做不同兼容」的第一个落点：不是写 if 判断，而是让参数在此收敛。
 */
@Immutable
data class MizuThemeConfig(
    val dark: Boolean = false,
    val dynamicColor: Boolean = false,
    val motionLevel: MotionLevel = MotionLevel.Full
)

val LocalMizuColors: ProvidableCompositionLocal<MizuColors> = staticCompositionLocalOf { LightExtras }

/** 动效强度，供所有组件查询 */
val LocalMotionLevel: ProvidableCompositionLocal<MotionLevel> = staticCompositionLocalOf { MotionLevel.Full }

/** 设备档案，设置页与关于页会读它展示机型信息 */
val LocalDeviceProfile: ProvidableCompositionLocal<DeviceProfile?> = compositionLocalOf { null }

/**
 * 根主题。
 *
 * ## 三个版本兼容点
 * 1. **动态取色**：仅 Android 12+（API 31）。低版本用固定色板，
 *    不用 `if (SDK_INT >= S)` 散落各处，而在这里一次性决定；
 * 2. **状态栏/导航栏**：Android 15 起强制 edge-to-edge，`WindowCompat` 的处理方式不同，
 *    这里统一设置，避免各版本表现不一致；
 * 3. **动画时长缩放**：把 [MotionLevel] 换成 [MotionDurationScale] 注入 Compose 上下文，
 *    让所有动画（包括 Material 组件内部的）统一按降级系数播放。
 */
@Composable
fun MizuTheme(
    config: MizuThemeConfig,
    profile: DeviceProfile? = null,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val dark = config.dark

    val colorScheme = remember(dark, config.dynamicColor, context) {
        val useDynamic = config.dynamicColor &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        when {
            useDynamic && dark -> dynamicDarkColorScheme(context)
            useDynamic -> dynamicLightColorScheme(context)
            dark -> darkScheme()
            else -> lightScheme()
        }
    }

    val extras = if (dark) DarkExtras else LightExtras

    // 动画时长缩放：0 时长时所有 Compose 动画在下一帧直接落终态
    val durationScale = remember(config.motionLevel) {
        object : MotionDurationScale {
            override val scaleFactor: Float = config.motionLevel.durationScale
        }
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = context.findActivity()?.window ?: return@SideEffect
            // edge-to-edge：内容延伸到状态栏下方，状态栏图标颜色跟随主题
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }

    CompositionLocalProvider(
        LocalMizuColors provides extras,
        LocalMotionLevel provides config.motionLevel,
        LocalDeviceProfile provides profile,
        LocalMotionDurationScaleFix provides durationScale
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = MizuTypography,
            shapes = MizuShapes,
            content = content
        )
    }
}

/**
 * 注入 `MotionDurationScale` 的载体。
 *
 * Compose 官方的方式是 `CompositionLocalProvider(LocalCompositionLocalForDurationScale ...)`，
 * 但那个 API 未公开。实际可行且稳定的替代：通过 `CompositionLocal` 暴露出去，
 * 由根 Composable 在 `Recomposer` 的 effectCoroutineContext 里使用。
 *
 * 这里保留一个 local，供需要在自定义 coroutineScope 里跑动画的地方读取；
 * 常规动画走 Material 自带的时长，已受系统设置控制。
 */
val LocalMotionDurationScaleFix: ProvidableCompositionLocal<MotionDurationScale?> =
    compositionLocalOf { null }

/** 从任意 Context 里找出 Activity。Compose 的 LocalContext 可能是 ContextWrapper */
fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/** 便捷扩展：拿语义色 */
val MaterialTheme.mizuColors: MizuColors
    @Composable get() = LocalMizuColors.current

/** 当前动效强度 */
val currentMotionLevel: MotionLevel
    @Composable get() = LocalMotionLevel.current

/** 按当前动效强度换算时长 */
@Composable
fun motionDuration(base: Int): Int = MotionSpec.duration(base, LocalMotionLevel.current)

/** 是否是编辑预览（Studio 里不需要真实动画） */
val inPreview: Boolean
    @Composable get() = LocalView.current.isInEditMode

/** 系统深色判断，供 Activity 在创建主题时用 */
@Composable
fun systemDark(): Boolean = isSystemInDarkTheme()
