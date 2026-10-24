package com.mizulauncher.compat

import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * 设备能力档案 —— 全应用**唯一**的「这台机器能做什么」事实源。
 *
 * ## 为什么要有这个类
 * 老手机上最常见的一类玄学 bug，是代码「以为」某个能力一定存在：
 * 直接调 API 31 才有的窗口模糊、假定系统一定播放转场动画、假定 WebView 一定能用。
 * 一旦设备不满足，轻则视觉错乱，重则整页白屏。
 *
 * 这里把「系统版本 → 能力」的映射集中成一张表，UI 只认 [DeviceProfile] 的布尔值，
 * 不再到处写 `if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.XXX)`——
 * 分散的版本判断是遗漏的温床，集中一处才能一次改对。
 *
 * ## 分级依据
 * 下表全部取自 Android 官方 API 引入版本，不是拍脑袋的经验值：
 *
 * | API | Android | 本工程用到的能力 |
 * |---|---|---|
 * | 26 | 8.0 | minSdk；自适应图标、通知渠道、`File` 权限收紧 |
 * | 29 | 10 | 分区存储、系统深色模式 `isSystemInDarkTheme()` |
 * | 31 | 12 | **动态取色 Material You**（`dynamicLightColorScheme`） |
 * | 33 | 13 | 预测性返回手势（`enableOnBackInvokedCallback`）、按应用语言 |
 * | 34 | 14 | 预测性返回动画正式可用；`ForegroundServiceType` 强制 |
 * | 35 | 15 | 系统级 back-to-home / cross-activity 动画默认开启 |
 * | 36 | 16 | 目标 36+ 时 `onBackPressed` 不再回调，必须走 `OnBackInvoked` |
 *
 * ## 动画为什么在某些机器上"没有了"
 * 不是代码没写，而是 Compose 会自动读取系统的 `ANIMATOR_DURATION_SCALE`：
 * 用户开了「移除动画」（无障碍设置）或开发者选项把动画缩放调成 0，
 * 所有 `animate*AsState` / `AnimatedVisibility` 都会在下一帧直接跳到终态。
 * 这是**正确的无障碍行为**，不应该绕过，但需要在代码里显式知道它，
 * 否则排查时会被误判成"动画没实现"。[animationsEnabled] 就是这个信号。
 */
data class DeviceProfile(
    val sdkInt: Int,
    val manufacturer: String,
    val model: String,
    val abi: String,
    /** 系统是否允许播放动画（false = 用户开了「移除动画」或动画缩放为 0） */
    val animationsEnabled: Boolean,
    /** 是否低内存设备（系统 `isLowRamDevice`），决定动效强度与列表缓存策略 */
    val isLowRam: Boolean
) {
    /** Android 8.0+（minSdk 保证恒为 true，保留语义便于阅读） */
    val supportsAdaptiveIcon: Boolean get() = sdkInt >= Build.VERSION_CODES.O

    /** Android 10+：系统深色模式 */
    val supportsDarkTheme: Boolean get() = sdkInt >= Build.VERSION_CODES.Q

    /** Android 12+：Material You 动态取色（从壁纸提取主题色） */
    val supportsDynamicColor: Boolean get() = sdkInt >= Build.VERSION_CODES.S

    /** Android 13+：预测性返回手势 */
    val supportsPredictiveBackGesture: Boolean get() = sdkInt >= Build.VERSION_CODES.TIRAMISU

    /**
     * Android 14+：预测性返回**动画**。
     * 13 上只是手势回调，动画要 14 才跟手，所以这两个要分开判断。
     */
    val supportsPredictiveBackAnimation: Boolean get() = sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    /** Android 15+：back-to-home / cross-activity 系统动画默认开启 */
    val supportsSystemBackAnimation: Boolean get() = sdkInt >= 35

    /** 是否 32 位 ABI（决定能否装 Java 25 运行时） */
    val is32Bit: Boolean
        get() = abi == "armeabi-v7a" || abi == "x86" || abi == "arm" || abi == "arm32"

    /**
     * 动效强度。
     *
     * 三档而不是两档：低端机不是"不能动"，而是"只能动一点点"。
     * 直接全关会让界面显得更生硬（正是之前被吐槽的问题），
     * 只降级强度才能在流畅与观感之间取平衡。
     */
    val motionLevel: MotionLevel
        get() = when {
            !animationsEnabled -> MotionLevel.None
            isLowRam -> MotionLevel.Reduced
            sdkInt < Build.VERSION_CODES.Q -> MotionLevel.Reduced
            else -> MotionLevel.Full
        }
}

/**
 * 动效强度分级。
 *
 * - [Full]：完整动效。转场 + 入场编排 + 共享元素 + 弹性回弹。
 * - [Reduced]：保留反馈，去掉"位移大、层次深"的部分。**不是关闭动画**——
 *   关掉动画会让界面变硬，这正是旧版被吐槽的点。低端机上保留淡入与短距位移，
 *   既有一点点"活"的感觉，又不会掉帧。
 * - [None]：用户明确要求移除动画（无障碍）。此时只保留**状态变化的即时反馈**，
 *   不做运动。这是我们能做的最大尊重：系统设置的优先级高于设计偏好。
 */
enum class MotionLevel {
    Full,
    Reduced,
    None;

    /** 时长缩放系数，供 [com.mizulauncher.compat.MotionSpec] 乘算 */
    val durationScale: Float
        get() = when (this) {
            Full -> 1f
            Reduced -> 0.6f
            None -> 0f
        }

    /** 是否允许位移类动画（水平/垂直滑动、共享轴） */
    val allowsTranslation: Boolean get() = this == Full

    /** 是否允许弹性动画（spring），低端机上弹簧曲线在掉帧时会"抖" */
    val allowsSpring: Boolean get() = this == Full
}

object DeviceProfiles {

    /**
     * 读取当前设备档案。
     *
     * 该方法只读系统属性，开销极小（毫秒级以下），可以在 `onCreate` 里同步调用；
     * 但为避免任何阻塞主线程的可能，调用方仍建议放到 `Dispatchers.IO` 或 Application 初始化早期。
     */
    fun load(context: Context): DeviceProfile {
        val resolver = context.contentResolver

        // 动画缩放：0 表示用户开启了「移除动画」。
        // 注意 Settings.Global 的读取在某些精简 ROM 上会抛异常，必须兜底。
        val animScale = runCatching {
            Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f)

        // 过渡动画缩放也是独立开关，两个都为 0 才算彻底关掉
        val transitionScale = runCatching {
            Settings.Global.getFloat(resolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f)
        }.getOrDefault(1f)

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager

        return DeviceProfile(
            sdkInt = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a",
            animationsEnabled = animScale > 0f && transitionScale > 0f,
            isLowRam = am?.isLowRamDevice == true
        )
    }

    /** 给日志/关于页用的可读摘要 */
    fun describe(p: DeviceProfile): String = buildString {
        append(p.manufacturer).append(' ').append(p.model)
        append(" · Android ").append(p.sdkInt)
        append(" · ").append(p.abi)
        if (p.isLowRam) append(" · 低内存")
        if (!p.animationsEnabled) append(" · 系统动画已关闭")
    }
}
