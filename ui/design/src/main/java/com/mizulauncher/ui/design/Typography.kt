package com.mizulauncher.ui.design

import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 字体与排版。
 *
 * ## 为什么不用自定义字体文件
 * 一个中文字体子集动辄 2~5 MB，而启动器的主要文本是版本号、模组名、日志——
 * 这些用系统字体就够。中文手机上的系统字体（思源黑/苹方）质量已经很高。
 *
 * ## 但拉丁字符换了无衬线等宽感更强的字重
 * 版本号（1.20.4）、文件大小（128 MB）、日志时间戳这类内容，
 * 用 `FontFamily.Monospace` 对齐更整齐，数字不会因字宽变化而跳动。
 * 这一点在"下载进度百分比秒级刷新"时特别明显：比例字体会让数字左右抖。
 */
val MizuTypography: Typography = run {
    val base = Typography()

    // 统一行高策略：中文行高按字形顶部裁切，避免"上下留白过多"的默认行为
    val trim = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.None
    )

    fun TextStyle.tight() = copy(lineHeightStyle = trim)

    Typography(
        displayLarge = base.displayLarge.tight(),
        displayMedium = base.displayMedium.tight(),
        displaySmall = base.displaySmall.tight(),
        headlineLarge = base.headlineLarge.tight(),
        headlineMedium = base.headlineMedium.tight().copy(fontWeight = FontWeight.SemiBold),
        headlineSmall = base.headlineSmall.tight().copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.tight().copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.tight().copy(fontWeight = FontWeight.Medium),
        titleSmall = base.titleSmall.tight().copy(fontWeight = FontWeight.Medium),
        bodyLarge = base.bodyLarge.tight(),
        bodyMedium = base.bodyMedium.tight(),
        bodySmall = base.bodySmall.tight(),
        labelLarge = base.labelLarge.tight().copy(fontWeight = FontWeight.Medium),
        labelMedium = base.labelMedium.tight(),
        labelSmall = base.labelSmall.tight()
    )
}

/** 等宽数字样式：版本号、大小、百分比、时间戳统一用它 */
val MonoNumberStyle: TextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Medium,
    fontSize = 13.sp,
    letterSpacing = 0.sp
)

/** 大号等宽：下载页的百分比 */
val MonoLargeStyle: TextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.SemiBold,
    fontSize = 28.sp,
    letterSpacing = (-0.5).sp
)

/**
 * 圆角规范。
 *
 * 偏向大圆角（16~28dp）是有意的：M3 的视觉语言里圆角越大越"软"，
 * 而旧版的 8dp 直角感正是"生硬"的来源之一。
 */
val MizuShapes: Shapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp)
)

/** 让文本选中高亮使用主题色，而不是系统默认的荧光蓝 */
@Composable
fun MizuSelectionColors(content: @Composable () -> Unit) {
    val scheme = androidx.compose.material3.MaterialTheme.colorScheme
    CompositionLocalProvider(
        LocalTextSelectionColors provides TextSelectionColors(
            handleColor = scheme.primary,
            backgroundColor = scheme.primary.copy(alpha = 0.28f)
        ),
        content = content
    )
}

/**
 * 间距常量。用固定的 4dp 栅格，避免各页面各写各的 13dp、17dp。
 */
object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp

    /** 页面左右边距。手机上 16dp 是普遍标准 */
    val pageHorizontal = 16.dp

    /** 列表项之间的间隔 */
    val itemGap = 10.dp

    /** 底部导航栏占位高度（内容需要 padding 以免被盖住） */
    val bottomBarPadding = 80.dp
}

/** 通用透明度档位，避免到处写 0.12f / 0.38f 这类魔法数字 */
object Alpha {
    const val Disabled = 0.38f
    const val Medium = 0.6f
    const val Subtle = 0.12f
    const val Hairline = 0.08f
}
