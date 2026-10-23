package com.mizulauncher.bridge

import android.content.Context
import com.mizulauncher.bridge.BridgeInfo
import com.mizulauncher.bridge.CompatibilityNotice
import com.mizulauncher.bridge.LaunchOptions
import com.mizulauncher.bridge.LaunchResult
import com.mizulauncher.bridge.LauncherBridge
import com.mizulauncher.instance.Account
import com.mizulauncher.instance.LaunchRequest
import java.io.File

/**
 * PojavLauncher 内核桥接实现。
 *
 * ## 为什么用反射而不是直接 import
 * 本工程**默认不携带** PojavLauncher 源码（用户可能只想编 UI 与下载层）。
 * 直接 `import net.kdt.pojavlaunch.JREUtils` 会让整个 `:app` 无法编译。
 * 因此这里用反射对接，效果等同：
 *
 *  - submodule 引入后，`JREUtils.launchJavaVM(MainActivity, StringBuilder)` 可用；
 *  - 没引入时，[isReady] 返回 false，UI 给出明确提示而不是崩溃。
 *
 * 如果你希望**强类型**对接（重构时有编译期保护），把 `settings.gradle.kts`
 * 里的 `include(":app_pojavlauncher")` 打开，然后删掉本文件里的反射块，
 * 换成直接调用即可（`README.md` 有逐步说明）。
 *
 * ## 关键约定
 * Pojav 的 `launchJavaVM` 需要一个 `MainActivity`（它要拿 Surface 与输入），
 * 所以启动必须发生在 `PojavMainActivity` 里；本类只负责**把参数摆好**并向内核发起意图。
 */
class PojavBridgeImpl(private val context: Context) : LauncherBridge {

    override val info: BridgeInfo = BridgeInfo(
        name = if (isKernelPresent()) "PojavLauncher" else "未集成",
        version = kernelVersion() ?: "-",
        license = "GPLv3",
        homepage = "https://github.com/PojavLauncherTeam/PojavLauncher"
    )

    override fun isReady(): Boolean = isKernelPresent() && runtimeDir()?.isDirectory == true

    override fun kill() {
        runCatching {
            val cls = Class.forName("net.kdt.pojavlaunch.JMinecraftVersionList")
            // 内核没有统一 kill 接口；进程退出由 PojavMainActivity.finish 处理
            cls.name // 仅用于确认内核在
        }
    }

    override suspend fun launch(
        request: LaunchRequest,
        account: Account,
        options: LaunchOptions,
        onCompatibility: (CompatibilityNotice) -> Unit
    ): LaunchResult {
        if (!isKernelPresent()) {
            return LaunchResult.Failed(
                "未找到 PojavLauncher 内核。请在 settings.gradle.kts 中启用 app_pojavlauncher 子模块，" +
                    "详见 README「集成 PojavLauncher」章节。"
            )
        }
        return runCatching {
            // 把我们的参数写成 Pojav 认识的「启动配置」文件，再由 PojavMainActivity 读取。
            // 这样跨进程/跨 Activity 都不会丢参数。
            val configFile = writeLaunchConfig(request, account, options)
            startKernelGameActivity(configFile.absolutePath)
            LaunchResult.Started
        }.getOrElse { e ->
            LaunchResult.Failed("启动失败：${e.javaClass.simpleName} ${e.message}", e)
        }
    }

    override suspend fun ensureRuntime(javaMajor: Int): Result<Unit> = runCatching {
        // Pojav 的 JRE 是打包在 assets 里的，安装到 files/runtime/<component>/
        val dir = File(context.filesDir, "runtime/java-${javaMajor}")
        if (dir.isDirectory && dir.listFiles()?.isNotEmpty() == true) return@runCatching
        if (!isKernelPresent()) {
            throw IllegalStateException("未集成 PojavLauncher，无法提供 Java ${javaMajor} 运行时")
        }
        // 内核引入后由 JREUtils.installJRE() 完成；未引入时仅提示
        throw IllegalStateException(
            "需要 PojavLauncher 内核提供的 Java ${javaMajor} 运行时（见 README 的 runtime 打包说明）"
        )
    }

    // ---------------- 内部 ----------------

    /**
     * 拉起内核的游戏 Activity。
     *
     * 类名用反射拼接而不是直接 import：`net.kdt.pojavlaunch.MainActivity` 只有引入 submodule 后才存在，
     * 直接引用会让本工程在「未集成内核」时无法编译。
     */
    private fun startKernelGameActivity(configPath: String) {
        val intent = android.content.Intent().apply {
            setClassName(
                context.packageName,
                "net.kdt.pojavlaunch.MainActivity"
            )
            putExtra(EXTRA_LAUNCH_CONFIG, configPath)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    companion object {
        /**
         * 启动配置的 Intent extra 名。Pojav 侧需要在 `MainActivity.onCreate` 里读取该路径
         * 并转成自己的 `JMinecraftVersionList.Version` + `String[] args`（见 README 的补丁片段）。
         */
        const val EXTRA_LAUNCH_CONFIG = "pcl.launch_config"
    }

    private fun isKernelPresent(): Boolean = runCatching {
        Class.forName("net.kdt.pojavlaunch.JREUtils")
        true
    }.getOrDefault(false)

    private fun kernelVersion(): String? = runCatching {
        val cls = Class.forName("net.kdt.pojavlaunch.Tools")
        cls.getField("VERSION_NAME").get(null) as? String
    }.getOrNull()

    private fun runtimeDir(): File? = File(context.filesDir, "runtime").takeIf { it.exists() }

    /**
     * 写启动配置。格式与 Pojav 的 `LaunchGameActivity` 读取的字段对齐；
     * 用 JSON 便于调参与日志排查（比 Pojav 原生的 `String[]` 更可读）。
     */
    private fun writeLaunchConfig(
        request: LaunchRequest,
        account: Account,
        options: LaunchOptions
    ): File {
        val dir = File(context.filesDir, "launch").apply { mkdirs() }
        val file = File(dir, "launch-${System.currentTimeMillis()}.json")
        val json = buildString {
            append("{")
            append("\"instanceId\":").append(q(request.instanceId)).append(",")
            append("\"versionId\":").append(q(request.versionId)).append(",")
            append("\"gameDir\":").append(q(request.gameDir)).append(",")
            append("\"assetIndex\":").append(q(request.assetIndexId)).append(",")
            append("\"assetsDir\":").append(q(request.assetsDir)).append(",")
            append("\"librariesDir\":").append(q(request.librariesDir)).append(",")
            append("\"nativesDir\":").append(q(request.nativesDir)).append(",")
            append("\"mainClass\":").append(q(request.mainClass)).append(",")
            append("\"javaMajor\":").append(request.javaMajor).append(",")
            append("\"width\":").append(options.resolution.first).append(",")
            append("\"height\":").append(options.resolution.second).append(",")
            append("\"memoryMb\":").append(options.memoryMb).append(",")
            append("\"renderer\":").append(q(options.renderer.name)).append(",")
            append("\"rendererHint\":").append(q(options.renderer.jvmHint)).append(",")
            append("\"mouseMode\":").append(q(options.mouseMode.name)).append(",")
            append("\"customControls\":").append(options.customControls).append(",")
            append("\"controlsLayout\":").append(q(options.controlsLayout)).append(",")
            append("\"username\":").append(q(account.username)).append(",")
            append("\"uuid\":").append(q(account.uuid)).append(",")
            append("\"accessToken\":").append(q(account.accessToken)).append(",")
            append("\"userType\":").append(q(account.userType)).append(",")
            append("\"classpath\":[").append(request.classpath.joinToString(",") { q(it) }).append("],")
            append("\"jvmArgs\":[").append(request.jvmArgs.joinToString(",") { q(it) }).append("],")
            append("\"gameArgs\":[").append(request.gameArgs.joinToString(",") { q(it) }).append("]")
            append("}")
        }
        file.writeText(json)
        return file
    }

    private fun q(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
