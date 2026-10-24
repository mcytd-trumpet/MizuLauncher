package com.mizulauncher.bridge

import com.mizulauncher.instance.Account
import com.mizulauncher.instance.InstanceMeta
import com.mizulauncher.instance.LaunchRequest

/**
 * 启动内核抽象层 —— 这是整个启动器与 PojavLauncher 之间的唯一契约。
 *
 * ## 为什么要有这一层
 * PojavLauncher 采用 **LGPL-3.0**（注意不是 GPLv3，早期版本曾用 GPLv3，2.x 起改为 LGPL），
 * 这意味着本工程可以链接它而不必整体开源；但一旦修改了 Pojav 自身的源码，改动部分仍须以 LGPL 公开。
 * 引入方式有两种：
 *  1. 作为 git submodule 引入上游 `app_pojavlauncher` 源码，并把本接口实现为
 *     `PojavBridgeImpl`（推荐，见 `:app` 模块的 `PojavBridgeImpl.kt` 与 README 的集成步骤）；
 *  2. 作为独立进程/服务，通过 Intent + 文件交换参数（隔离性更好，但定制能力弱）。
 *
 * 无论哪种方式，UI 与业务层只依赖本接口，因此**内核可替换**（Pojav / 后续社区分支）。
 */
interface LauncherBridge {

    /** 内核信息，用于「关于」页与兼容性判断 */
    val info: BridgeInfo

    /** 内核是否可用（JRE 与 natives 是否就绪） */
    fun isReady(): Boolean

    /**
     * 启动游戏。
     * @param request 参数已完全拼好的启动请求
     * @param account 账号（离线/微软）
     * @param onCompatibility 兼容性提示回调（例如 GL 驱动建议、Java 版本不匹配）
     */
    suspend fun launch(
        request: LaunchRequest,
        account: Account,
        options: LaunchOptions = LaunchOptions(),
        onCompatibility: (CompatibilityNotice) -> Unit = {}
    ): LaunchResult

    /** 强制停止当前游戏进程 */
    fun kill()

    /** 预览/下载 JRE（Pojav 的 jre-8 / jre-17 / jre-21 / jre-25） */
    suspend fun ensureRuntime(javaMajor: Int): Result<Unit>
}

data class BridgeInfo(
    val name: String,
    val version: String,
    val license: String,
    val homepage: String
)

/**
 * 启动选项：对应 Pojav 的渲染器 / 输入方式配置。
 */
data class LaunchOptions(
    /** 渲染器：opengl(virgl) / opengl(gl4es) / zink / vulkan(zink) / custom(ANGLE) */
    val renderer: Renderer = Renderer.VIRGL,
    /** 打开方式：Activity 内嵌 / 独立 Surface */
    val openMode: OpenMode = OpenMode.INLINE,
    /** 是否启用自定义按键布局 */
    val customControls: Boolean = true,
    /** 自定义布局 id（对应 LayoutStore 中的名字） */
    val controlsLayout: String = "default.json",
    /** 鼠标模式：触摸模拟 / 直接 */
    val mouseMode: MouseMode = MouseMode.TOUCH,
    /** 内存分配（MB） */
    val memoryMb: Int = 1024,
    /** 额外 JVM 参数（例如 -Dfml.ignorePatchDiscrepancies=true） */
    val extraJvmArgs: List<String> = emptyList(),
    /** 屏幕分辨率（游戏内虚拟分辨率） */
    val resolution: Pair<Int, Int> = 1280 to 720
)

enum class Renderer(val display: String, val jvmHint: String) {
    VIRGL("OpenGL (VirGL)", "-Dpojav.virgl=1"),
    GL4ES("OpenGL (GL4ES)", "-Dpojav.gl4es=1"),
    ZINK("Zink (OpenGL on Vulkan)", "-Dpojav.zink=1"),
    ANGLE("ANGLE (Vulkan)", "-Dpojav.angle=1"),
    CUSTOM("自定义", "")
}

enum class OpenMode(val display: String) {
    INLINE("内嵌显示"),
    SURFACE("独立界面")
}

enum class MouseMode(val display: String) {
    TOUCH("触摸模拟鼠标"),
    DIRECT("直接点击"),
    CAPTURE("指针锁定")
}

/** 兼容性提示：与 PCL2 的「问题检测」对应 */
data class CompatibilityNotice(
    val level: Level,
    val title: String,
    val detail: String,
    val actionLabel: String? = null
) {
    enum class Level { INFO, WARNING, ERROR }
}

sealed interface LaunchResult {
    data object Started : LaunchResult
    data class Failed(val reason: String, val throwable: Throwable? = null) : LaunchResult
    data class NotInstalled(val missing: List<String>) : LaunchResult
}

/**
 * 兼容性检测器：在启动前检查常见问题（PCL2 的「启动前检查」精简版）。
 * 纯逻辑，可在 Android 端注入实际数据。
 */
object LaunchPreflight {

    data class DeviceProfile(
        val totalMemoryMb: Long,
        val freeStorageMb: Long,
        val glesMajor: Int,
        val supportsVulkan: Boolean,
        val abi: String,
        val is64Bit: Boolean
    )

    fun check(
        request: LaunchRequest,
        instance: InstanceMeta,
        options: LaunchOptions,
        device: DeviceProfile
    ): List<CompatibilityNotice> {
        val out = mutableListOf<CompatibilityNotice>()

        if (!device.is64Bit) {
            out += CompatibilityNotice(
                CompatibilityNotice.Level.ERROR,
                "设备架构不受支持",
                "当前为 32 位 ABI（${device.abi}），无法运行 Java 版 Minecraft。请使用 64 位系统。",
                "了解详情"
            )
        }
        if (options.memoryMb > device.totalMemoryMb * 0.6) {
            out += CompatibilityNotice(
                CompatibilityNotice.Level.WARNING,
                "内存分配过高",
                "分配 ${options.memoryMb}MB 内存，超过设备总量的 60%（共 ${device.totalMemoryMb}MB），可能导致系统杀后台。",
                "自动调整"
            )
        }
        if (request.javaMajor >= 17 && device.totalMemoryMb < 3000) {
            out += CompatibilityNotice(
                CompatibilityNotice.Level.WARNING,
                "低内存设备运行现代版本",
                "1.18+ 版本建议至少 4GB 内存，当前设备可能频繁卡顿。"
            )
        }
        if (options.renderer == Renderer.ZINK && !device.supportsVulkan) {
            out += CompatibilityNotice(
                CompatibilityNotice.Level.ERROR,
                "Zink 需要 Vulkan",
                "当前设备不支持 Vulkan 1.1，请改用 VirGL 或 GL4ES 渲染器。",
                "切换渲染器"
            )
        }
        if (instance.loaderType == "forge" && request.javaMajor != 8 && instance.versionId.startsWith("1.1")) {
            val minor = instance.versionId.split('.').getOrNull(1)?.toIntOrNull() ?: 0
            if (minor <= 12 && request.javaMajor > 8) {
                out += CompatibilityNotice(
                    CompatibilityNotice.Level.ERROR,
                    "Forge 版本与 Java 不匹配",
                    "1.12.2 及更早的 Forge 需要 Java 8，当前选择了 Java ${request.javaMajor}。",
                    "切换 Java 8"
                )
            }
        }
        if (device.freeStorageMb < 2048) {
            out += CompatibilityNotice(
                CompatibilityNotice.Level.WARNING,
                "存储空间不足",
                "剩余 ${device.freeStorageMb}MB，安装资源文件可能需要 1GB 以上。"
            )
        }
        if (device.glesMajor < 3) {
            out += CompatibilityNotice(
                CompatibilityNotice.Level.WARNING,
                "OpenGL ES 版本较低",
                "设备仅支持 OpenGL ES ${device.glesMajor}，部分光影/模组可能无法渲染。"
            )
        }
        return out
    }
}
