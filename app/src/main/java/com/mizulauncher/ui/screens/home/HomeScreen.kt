package com.mizulauncher.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mizulauncher.instance.InstanceMeta
import com.mizulauncher.ui.components.GlassCard
import com.mizulauncher.ui.components.GradientActionCard
import com.mizulauncher.ui.components.Tag
import com.mizulauncher.ui.theme.MizuIcons
import com.mizulauncher.ui.theme.PclShapes

/**
 * 首页：ZalithLauncher2 的卡片流布局
 *
 * ```
 * ┌───────────────────────────────┐
 * │ Mizu Launcher        [下载][设置] │   ← 顶栏（40dp，胶囊按钮）
 * ├───────────────────────────────┤
 * │ ┌─────┐  Steve                │   ← 账号卡（头像 + 名称 + 类型）
 * │ │ 头  │  Microsoft            │
 * │ └─────┘                      │
 * │ ╔═══════════════════════════╗ │
 * │ ║ ▶ 开始游戏  1.20.1-forge  ║ │   ← 主操作渐变卡（当前实例）
 * │ ╚═══════════════════════════╝ │
 * │ 我的实例            [全部 →]  │
 * │ ┌──────────┐ ┌──────────┐    │   ← 2 列网格（平板 3~4 列）
 * │ │ 实例卡 1 │ │ 实例卡 2 │    │
 * │ └──────────┘ └──────────┘    │
 * └───────────────────────────────┘
 * ```
 */
@Composable
fun HomeScreen(
    instances: List<InstanceMeta>,
    selected: InstanceMeta?,
    accountName: String,
    columns: Int,
    onSelectInstance: (InstanceMeta) -> Unit,
    onLaunch: () -> Unit,
    onOpenDownload: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenInstanceMenu: (InstanceMeta) -> Unit,
    onOpenAccount: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            AccountHeaderCard(accountName, onOpenAccount)
        }
        item {
            GradientActionCard(
                title = "开始游戏",
                subtitle = selected?.displayName ?: "还没有实例，先去下载一个吧",
                icon = MizuIcons.Play,
                enabled = selected != null,
                onClick = onLaunch,
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Text("我的实例", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    "${instances.size} 个",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }
        // 网格：用行分块实现，避免引入额外依赖
        items(instances.chunked(columns)) { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                rowItems.forEach { inst ->
                    InstanceCard(
                        instance = inst,
                        selected = inst.id == selected?.id,
                        modifier = Modifier.weight(1f),
                        onClick = { onSelectInstance(inst) },
                        onLongClick = { onOpenInstanceMenu(inst) }
                    )
                }
                repeat(columns - rowItems.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (instances.isEmpty()) {
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(24.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("暂无实例", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "去下载页选择任意 Minecraft 版本，会自动创建实例",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(14.dp))
                        GradientActionCard(
                            title = "下载游戏",
                            subtitle = "支持 1.7.10 ~ 最新版 + 所有主流加载器",
                            icon = MizuIcons.Download,
                            onClick = onOpenDownload,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
        item { Spacer(Modifier.height(40.dp)) }
    }
}

@Composable
private fun AccountHeaderCard(accountName: String, onClick: () -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 头像：用名字首字母生成渐变色块（离线账号没有皮肤，避免加载网络图）
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.85f), MaterialTheme.colorScheme.secondary.copy(alpha = 0.85f))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    accountName.take(1).uppercase(),
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(accountName, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Tag("离线登录", color = MaterialTheme.colorScheme.tertiary)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "点击切换账号",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Icon(MizuIcons.Person, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun InstanceCard(
    instance: InstanceMeta,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val border = if (selected)
        androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
    else
        androidx.compose.foundation.BorderStroke(1.dp, com.mizulauncher.ui.theme.LocalPclColors.current.cardBorder)

    GlassCard(modifier = modifier, border = border, onClick = onClick) {
        Column(Modifier.padding(12.dp)) {
            // 图标区：版本号大字 + 加载器小标签
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.45f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                iconColorFor(instance.versionId).copy(alpha = 0.22f),
                                iconColorFor(instance.versionId).copy(alpha = 0.06f)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    instance.versionId.takeWhile { it != '-' }.ifBlank { "?" },
                    style = MaterialTheme.typography.displaySmall,
                    color = iconColorFor(instance.versionId),
                    fontWeight = FontWeight.Bold
                )
                if (instance.loaderType != "vanilla") {
                    Box(Modifier.align(Alignment.TopEnd).padding(6.dp)) {
                        Tag(instance.loaderType.uppercase(), color = iconColorFor(instance.versionId), filled = true)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                instance.displayName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                when {
                    instance.lastPlayed == 0L -> "从未启动"
                    else -> "上次：${formatRelative(instance.lastPlayed)}"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 根据版本号生成稳定的识别色（PCL2 也有类似的版本图标配色） */
internal fun iconColorFor(versionId: String): Color {
    val palette = listOf(
        Color(0xFF2F7BF6), Color(0xFF00A88F), Color(0xFFF2813D),
        Color(0xFF9A5BFF), Color(0xFFE5484D), Color(0xFF1F9D55)
    )
    val idx = (versionId.hashCode() % palette.size + palette.size) % palette.size
    return palette[idx]
}

private fun formatRelative(ts: Long): String {
    val diff = System.currentTimeMillis() - ts
    val min = diff / 60000
    return when {
        min < 1 -> "刚刚"
        min < 60 -> "${min} 分钟前"
        min < 1440 -> "${min / 60} 小时前"
        else -> "${min / 1440} 天前"
    }
}
