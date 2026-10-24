package com.mizulauncher.v2.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.abs

/**
 * 区块标题。
 *
 * 单独抽出来是因为旧版每处标题的间隔都不一样（有的是 8dp 有的是 14dp），
 * 整个界面看起来"没对齐"。统一成一个组件后，间距就成了规范而不是手写。
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

/**
 * 文件大小格式化。
 *
 * ## 为什么不用 `Formatter.formatShortFileSize`
 * 系统那个 API 依赖 locale 的 `formatFileSize` 实现，部分国产 ROM 上会被改写成
 * "1.2GB"、"1.2 GB"、"1.2ＧＢ" 各种样子，也会因为 locale 而变成中文单位。
 * 这里自己算，保证全应用一致，也方便单测。
 *
 * 规则：
 *  - 小于 1 KB → 显示 B；
 *  - 小于 10 的数值保留两位小数（1.25 GB 比 1.3 GB 更有信息量）；
 *  - 其余保留一位（128.4 MB 就够了）。
 */
fun formatSize(bytes: Long): String {
    if (bytes <= 0L) return "—"
    if (bytes < 1024L) return "$bytes B"

    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024.0
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex++
    }

    val pattern = if (value < 10) "%.2f" else "%.1f"
    return String.format(Locale.US, pattern, value) + " " + units[unitIndex]
}

/** 速度格式化：恒一位小数，避免刷新时字宽跳动 */
fun formatSpeed(bytesPerSecond: Long): String =
    if (bytesPerSecond <= 0L) "—" else formatSize(bytesPerSecond) + "/s"

/**
 * 相对时间。
 *
 * 首页列表上写"3 天前"比写"2024-05-12 18:33"更有用——用户关心的是"多久没玩"，
 * 而不是精确时刻。超过 30 天就退化成日期，因为那时"37 天前"已经没意义了。
 */
fun relativeTime(epochMs: Long): String {
    if (epochMs <= 0L) return "从未游玩"
    val delta = System.currentTimeMillis() - epochMs
    if (delta < 0) return "刚刚"

    val minute = 60_000L
    val hour = 60 * minute
    val day = 24 * hour

    return when {
        delta < minute -> "刚刚"
        delta < hour -> "${delta / minute} 分钟前"
        delta < day -> "${delta / hour} 小时前"
        delta < 30 * day -> "${delta / day} 天前"
        else -> java.text.SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            .format(java.util.Date(epochMs))
    }
}

/** 剩余时间：秒数 → "1 分 20 秒" */
fun formatEta(seconds: Long): String {
    if (seconds <= 0L) return "—"
    return when {
        seconds < 60 -> "${seconds} 秒"
        seconds < 3600 -> "${seconds / 60} 分 ${seconds % 60} 秒"
        else -> "${seconds / 3600} 小时 ${(seconds % 3600) / 60} 分"
    }
}

/** 进度百分比：整数，用于按钮和标签。abs 是为了处理浮点误差导致的 -0 */
fun formatPercent(ratio: Float): String = "${(abs(ratio) * 100).toInt().coerceIn(0, 100)}%"
