package com.mizulauncher.web

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.webkit.WebView

/**
 * 内嵌浏览器的**能力探测**与**降级策略**。
 *
 * ## 定位：WebView 不是 UI 框架
 * 界面本身全部是原生 Compose，WebView 只用在这几处：
 *  - 公告 / 更新说明（服务端下发 HTML，改文案不用发版）；
 *  - 授权跳转页（部分站点要求走网页 OAuth 而非设备码）；
 *  - 用户协议 / 开源许可。
 *
 * 这些都是"渲染一段第三方 HTML"，用 Compose 手搓解析器毫无意义。
 *
 * ## 为什么必须做降级
 * Android System WebView 是**可被用户停用、可被厂商阉割、可在极简 ROM 上完全缺失**的组件。
 * 在老手机、定制机、部分国产 ROM 上：
 *  - `WebView` 类存在，但 `WebView(context)` 构造直接抛 `MissingWebViewPackageException`；
 *  - 或者能创建，但内核版本极旧，CSS 新特性全崩，页面一片空白。
 *
 * 直接 `AndroidView { WebView(it) }` 在老机器上就是**必崩**。所以这里先探测，
 * 探测不通过就退化成「用系统浏览器打开」——功能不丢，只是体验降一档。
 */
object WebViewSupport {

    /** 最低可用内核大版本。低于 60 的页面基本渲染不全（flex/grid 支持残缺） */
    const val MIN_MAJOR_VERSION = 60

    sealed interface Status {
        /** 可用，附内核版本号，便于在设置页展示 */
        data class Available(val majorVersion: Int, val packageName: String) : Status

        /** 系统没有可用内核（被停用/被阉割） */
        data object KernelMissing : Status

        /** 内核存在但太旧，渲染结果不可信 */
        data class TooOld(val majorVersion: Int) : Status
    }

    /**
     * 探测内核状态。
     *
     * 注意：`WebView.getCurrentWebViewPackage()` 在 API 26 上可能返回 null
     * （WebView 尚未初始化完成），因此还要用 `PackageManager` 兜底查。
     */
    fun probe(context: Context): Status {
        // 1) 系统报告的 WebView 包
        val pkg = runCatching { WebView.getCurrentWebViewPackage() }.getOrNull()
            ?: findProviderPackage(context)

        if (pkg == null) {
            // 2) 连包都查不到：判断是否被用户停用
            val disabled = runCatching {
                context.packageManager.getApplicationInfo("com.google.android.webview", 0).enabled == false
            }.getOrDefault(false)
            return if (disabled) Status.KernelMissing else Status.KernelMissing
        }

        val major = runCatching {
            pkg.versionName?.split('.')?.firstOrNull()?.toIntOrNull() ?: 0
        }.getOrDefault(0)

        return if (major in 0 until MIN_MAJOR_VERSION) {
            Status.TooOld(major)
        } else {
            Status.Available(major, pkg.packageName)
        }
    }

    /**
     * 真正尝试创建一次 WebView 来验证可用性。
     *
     * 这是最可靠的探测方式：某些 ROM 上元数据齐全，但一构造就抛
     * `MissingWebViewPackageException` 或 `InflateException`。
     * 必须在主线程调用，且构造成功后要立刻 destroy，避免泄漏。
     */
    fun canInstantiate(context: Context): Boolean = runCatching {
        val wv = WebView(context)
        wv.destroy()
        true
    }.getOrDefault(false)

    /**
     * 综合判定：既要有够新的内核，又要能真正构造出来。
     * UI 层用这一个函数就够了，不必自己组合。
     */
    fun isUsable(context: Context): Boolean {
        val status = probe(context)
        if (status !is Status.Available) return false
        return canInstantiate(context)
    }

    /** 降级：用系统浏览器打开。这是所有失败路径的终点，保证用户永远能看到内容 */
    fun openExternally(context: Context, url: String): Boolean = runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        true
    }.getOrDefault(false)

    /** 查 WebView 实现包。AOSP 上是 com.android.webview，GMS 设备上是 com.google.android.webview */
    private fun findProviderPackage(context: Context): android.content.pm.PackageInfo? {
        val candidates = listOf(
            "com.google.android.webview",
            "com.android.webview",
            "com.android.chrome"
        )
        return candidates.firstNotNullOfOrNull { name ->
            runCatching {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(name, 0)
            }.getOrNull()
        }
    }

    /** 判断某个 Scheme 是否有应用能处理（用于外部打开的兜底判断） */
    fun canHandle(context: Context, url: String): Boolean = runCatching {
        context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)),
            PackageManager.MATCH_DEFAULT_ONLY
        ).isNotEmpty()
    }.getOrDefault(false)
}
