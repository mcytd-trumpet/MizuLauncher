package com.mizulauncher.ui.screens.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mizulauncher.bridge.BridgeInfo
import com.mizulauncher.ui.components.GlassCard
import com.mizulauncher.ui.components.SectionHeader
import com.mizulauncher.ui.components.SettingRow
import com.mizulauncher.ui.components.Tag
import com.mizulauncher.ui.theme.MizuIcons
import com.mizulauncher.minecraft.VersionRepository

/**
 * 关于页。
 *
 * 这里除了版本号，还刻意把 **许可证与上游项目** 摆出来：
 * PojavLauncher 是 GPLv3，本项目作为其衍生分发时必须让用户能拿到源码与许可声明
 *（GPLv3 §5、§6）。这几个字段不是装饰，是合规要求。
 */
@Composable
fun AboutScreen(
    versionName: String,
    versionCode: Int,
    bridgeInfo: BridgeInfo?,
    onOpenSource: (String) -> Unit,
    /** 检查中时禁用按钮，避免连点打一串接口 */
    updateChecking: Boolean = false,
    updateStatus: String? = null,
    onCheckUpdate: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp)
    ) {
        // 头部
        Column(
            Modifier.fillMaxWidth().padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Mizu Launcher", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "v$versionName ($versionCode)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Tag("Pojav 内核")
                Tag("PCL2 下载")
                Tag("Zalith UI")
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "手机上的 Java 版 Minecraft 启动器",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }

        SectionHeader("版本")
        GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column {
                SettingRow(
                    title = "检查更新",
                    subtitle = updateStatus ?: "从 GitHub Releases 获取最新安装包",
                    icon = MizuIcons.Refresh,
                    trailing = {
                        androidx.compose.material3.TextButton(
                            onClick = onCheckUpdate,
                            enabled = !updateChecking
                        ) { Text(if (updateChecking) "检查中…" else "检查") }
                    }
                )
                SettingRow(
                    title = "项目主页",
                    subtitle = "github.com/mcytd-trumpet/MizuLauncher",
                    icon = MizuIcons.Info,
                    onClick = { onOpenSource("https://github.com/mcytd-trumpet/MizuLauncher") }
                )
                SettingRow(
                    title = "问题反馈",
                    subtitle = "遇到闪退请附上运行日志",
                    icon = MizuIcons.Bug,
                    onClick = { onOpenSource("https://github.com/mcytd-trumpet/MizuLauncher/issues") }
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionHeader("运行内核")
        GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column {
                SettingRow(
                    title = bridgeInfo?.name ?: "未集成",
                    subtitle = if (bridgeInfo != null) {
                        "版本 ${bridgeInfo.version} · ${bridgeInfo.license}"
                    } else {
                        "按 README 将 PojavLauncher 作为 submodule 引入后即可启动游戏"
                    },
                    icon = MizuIcons.Memory
                )
                if (bridgeInfo != null) {
                    SettingRow(
                        title = "内核主页",
                        subtitle = bridgeInfo.homepage,
                        icon = MizuIcons.Info,
                        onClick = { onOpenSource(bridgeInfo.homepage) }
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionHeader("数据来源")
        GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column {
                SettingRow(
                    title = "Mojang 版本清单",
                    subtitle = VersionRepository.OFFICIAL_MANIFEST,
                    icon = MizuIcons.Download,
                    onClick = { onOpenSource(VersionRepository.OFFICIAL_MANIFEST) }
                )
                SettingRow(
                    title = "BMCLAPI 镜像",
                    subtitle = "https://bmclapi2.bangbang93.com",
                    icon = MizuIcons.Speed,
                    onClick = { onOpenSource("https://bmclapi2.bangbang93.com") }
                )
                SettingRow(
                    title = "MCIM 镜像",
                    subtitle = "https://mod.mcimirror.top",
                    icon = MizuIcons.Wifi,
                    onClick = { onOpenSource("https://mod.mcimirror.top") }
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionHeader("开源与许可")
        GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column {
                SettingRow(
                    title = "PojavLauncher",
                    subtitle = "GPLv3 · 启动内核与 JRE/natives 运行时",
                    icon = MizuIcons.Extension,
                    onClick = { onOpenSource("https://github.com/PojavLauncherTeam/PojavLauncher") }
                )
                SettingRow(
                    title = "PCL2 (Plain Craft Launcher 2)",
                    subtitle = "下载策略与镜像映射的参考实现",
                    icon = MizuIcons.Folder,
                    onClick = { onOpenSource("https://github.com/Hex-Dragon/PCL2") }
                )
                SettingRow(
                    title = "ZalithLauncher2",
                    subtitle = "UI 交互与控制器布局的参考实现",
                    icon = MizuIcons.Grid,
                    onClick = { onOpenSource("https://github.com/ZalithLauncher/ZalithLauncher2") }
                )
                SettingRow(
                    title = "本项目",
                    subtitle = "本启动器外壳以 GPLv3 发布（因链接 Pojav 内核）",
                    icon = MizuIcons.Info
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "Minecraft 是 Mojang Studios 的商标。本项目与 Mojang、Microsoft 无任何关联。\n" +
                "启动器本身不包含任何游戏资源，所有游戏文件均从官方或镜像源按需下载。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp),
            textAlign = TextAlign.Center
        )
    }
}
