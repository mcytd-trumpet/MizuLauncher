package com.mizulauncher.v2.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mizulauncher.compat.DeviceProfile
import com.mizulauncher.ui.design.MizuCard
import com.mizulauncher.ui.design.MizuBadge
import com.mizulauncher.v2.ui.SectionHeader

/**
 * 关于页。
 *
 * ## 这里必须写清楚的两件事
 * 1. **本项目包含 PojavLauncher 代码**（GPLv3）。分发时必须保留其许可与来源说明，
 *    否则是许可证违规。这不是"礼貌"问题。
 * 2. **本项目的 UI 是原创的**。HMCL 是 GPLv3 + 附加条款，修改/再分发必须改名并标注非官方。
 *    我们是吸收了它的设计语言（大卡片、清晰层级），没有复制它的界面。
 */
@Composable
fun AboutScreen(
    versionName: String,
    device: DeviceProfile?,
    onOpenLicense: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item("hero") {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .clip(MaterialTheme.shapes.large)
                    .background(
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "MizuLauncher",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(6.dp))
                    MizuBadge(
                        text = versionName.ifBlank { "2.0.0" },
                        container = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                        content = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "为手机做的 Minecraft 启动器",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item("device_header") { SectionHeader(title = "这台设备") }
        item("device") {
            MizuCard(modifier = Modifier.fillMaxWidth(), containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                if (device == null) {
                    Text("无法读取设备信息", style = MaterialTheme.typography.bodySmall)
                } else {
                    InfoRow("系统版本", "Android ${device.sdkInt}")
                    InfoRow("架构", device.abi)
                    InfoRow("机型", "${device.manufacturer} ${device.model}")
                    InfoRow("动画", if (device.animationsEnabled) "系统已开启" else "系统已关闭（无障碍设置）")
                    InfoRow("内存档位", if (device.isLowRam) "低内存设备" else "标准")
                }
            }
        }

        item("oss_header") { SectionHeader(title = "开源与致谢") }
        item("oss") {
            MizuCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "本启动器基于以下开源项目构建。它们的许可证要求保留署名，请勿移除。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                CreditItem(
                    name = "PojavLauncher",
                    license = "GPLv3",
                    note = "游戏内核与 JVM 启动层。本项目以子模块方式集成其代码。"
                )
                Spacer(Modifier.height(10.dp))
                CreditItem(
                    name = "authlib-injector",
                    license = "AGPLv3",
                    note = "外置登录 / 第三方皮肤的核心机制。运行时动态下载，不打包进 APK。"
                )
                Spacer(Modifier.height(10.dp))
                CreditItem(
                    name = "Jetpack Compose",
                    license = "Apache-2.0",
                    note = "整个界面由它绘制，不是 WebView。"
                )
            }
        }

        item("license_note") {
            MizuCard(
                modifier = Modifier.fillMaxWidth(),
                containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f)
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        Icons.Rounded.Info,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("关于界面风格", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "本启动器的界面为原创实现，在信息层级与交互上参考了 HMCL 的设计思路。" +
                                "HMCL 采用 GPLv3 附加条款授权，其界面不可直接复用；" +
                                "本项目与其无隶属关系，也不代表 HMCL 团队。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        item("footer") {
            Column(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Rounded.Favorite,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Minecraft 是 Mojang Studios 的商标。本项目与 Mojang 及微软无关联。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp)
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CreditItem(name: String, license: String, note: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = name.first().toString(),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.width(6.dp))
                MizuBadge(text = license)
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
