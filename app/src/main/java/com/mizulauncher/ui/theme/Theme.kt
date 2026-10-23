package com.mizulauncher.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 视觉体系：ZalithLauncher2 的「毛玻璃卡片流 + 大字导航」 × PCL2 的「高对比强调色 / 圆角图标」
 *
 * 关键点：
 *  - **叠加半透明层**：背景图之上统一压一层 scrim，保证文字对比度（Zalith 的做法）
 *  - **卡片不是纯白**：卡片色带 4%~8% 的主色，暗色下更"雾"
 *  - **圆角分级**：小控件 12，卡片 20，浮层 28
 */
private val PclPrimary = Color(0xFF2F7BF6)
private val PclSecondary = Color(0xFF00C2A8)
private val PclTertiary = Color(0xFFFF8A3D)

private val LightScheme = lightColorScheme(
    primary = PclPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE8FF),
    onPrimaryContainer = Color(0xFF0B2B60),
    secondary = PclSecondary,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD6F5F0),
    onSecondaryContainer = Color(0xFF00382F),
    tertiary = PclTertiary,
    onTertiary = Color.White,
    background = Color(0xFFF4F6FA),
    onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFE9EDF5),
    onSurfaceVariant = Color(0xFF5A6070),
    outline = Color(0xFFC6CBD8)
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF7FAEFF),
    onPrimary = Color(0xFF00285E),
    primaryContainer = Color(0xFF1D3F73),
    onPrimaryContainer = Color(0xFFD8E5FF),
    secondary = Color(0xFF4FD9C4),
    onSecondary = Color(0xFF00382F),
    secondaryContainer = Color(0xFF0F4F46),
    tertiary = Color(0xFFFFB27A),
    background = Color(0xFF0E1013),
    onBackground = Color(0xFFE6E8EC),
    surface = Color(0xFF161A1F),
    onSurface = Color(0xFFE6E8EC),
    surfaceVariant = Color(0xFF20262E),
    onSurfaceVariant = Color(0xFFA5ADBB),
    outline = Color(0xFF3A424E)
)

/** 额外的语义色（M3 没覆盖到的） */
data class PclColors(
    val cardBackground: Color,
    val cardBorder: Color,
    val scrimTop: Color,
    val scrimBottom: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val joystickRing: Color,
    val joystickKnob: Color,
    val keyCap: Color
)

val LocalPclColors = staticCompositionLocalOf {
    PclColors(
        cardBackground = Color.White,
        cardBorder = Color(0x14202020),
        scrimTop = Color.Transparent,
        scrimBottom = Color.Transparent,
        success = Color(0xFF22A06B),
        warning = Color(0xFFE9A23B),
        danger = Color(0xFFE5484D),
        joystickRing = Color.White,
        joystickKnob = Color.White,
        keyCap = Color(0x33FFFFFF)
    )
}

object PclShapes {
    val card = RoundedCornerShape(20.dp)
    val cardSmall = RoundedCornerShape(14.dp)
    val sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val pill = RoundedCornerShape(percent = 50)
}

private val PclTypography = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium)
)

@Composable
fun MizuTheme(dark: Boolean, content: @Composable () -> Unit) {
    val scheme = if (dark) DarkScheme else LightScheme
    val extras = if (dark) {
        PclColors(
            cardBackground = Color(0x1FFFFFFF),
            cardBorder = Color(0x1AFFFFFF),
            scrimTop = Color(0x33000000),
            scrimBottom = Color(0xCC000000),
            success = Color(0xFF57D9A3),
            warning = Color(0xFFF5C06B),
            danger = Color(0xFFFF7A85),
            joystickRing = Color(0x66FFFFFF),
            joystickKnob = Color(0xCCFFFFFF),
            keyCap = Color(0x33FFFFFF)
        )
    } else {
        PclColors(
            cardBackground = Color(0xF7FFFFFF),
            cardBorder = Color(0x12000000),
            scrimTop = Color(0x14FFFFFF),
            scrimBottom = Color(0x99000000),
            success = Color(0xFF22A06B),
            warning = Color(0xFFE9A23B),
            danger = Color(0xFFE5484D),
            joystickRing = Color(0x44FFFFFF),
            joystickKnob = Color(0xDDFFFFFF),
            keyCap = Color(0x2EFFFFFF)
        )
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalPclColors provides extras) {
        MaterialTheme(
            colorScheme = scheme,
            typography = PclTypography,
            shapes = Shapes(
                extraSmall = RoundedCornerShape(8.dp),
                small = RoundedCornerShape(12.dp),
                medium = PclShapes.cardSmall,
                large = PclShapes.card,
                extraLarge = PclShapes.sheet
            )
        ) { content() }
    }
}
