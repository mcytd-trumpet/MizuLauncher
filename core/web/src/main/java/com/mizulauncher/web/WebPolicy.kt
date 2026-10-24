package com.mizulauncher.web

/**
 * 内嵌网页的**安全策略**。
 *
 * ## 为什么单独抽出来
 * WebView 是最容易被误配置的组件。默认配置里有一串危险的开关，
 * 一旦为了"让页面能显示"而随手全开，就是把用户设备敞开给第三方页面：
 *
 * | 配置项 | 默认风险 | 这里的策略 |
 * |---|---|---|
 * | `allowFileAccess` | 老版本默认 true，页面能读本地文件 | **恒 false** |
 * | `allowContentAccess` | 能读其他应用通过 ContentProvider 暴露的数据 | **恒 false** |
 * | `allowFileAccessFromFileURLs` | file:// 页面可读任意本地文件（同源绕过） | **恒 false** |
 * | `allowUniversalAccessFromFileURLs` | 同上，且更宽 | **恒 false** |
 * | `javaScriptEnabled` | 默认 false | **按用途开启**，但禁止 JS 调用本地接口除非显式注册 |
 * | `domStorageEnabled` | 默认 false | 公告/授权页需要，开启 |
 * | `savePassword` | 会保存到密码管理器 | **恒 false**，这是第三方页面，不该存 |
 * | `mixedContentMode` | 允许 https 页面加载 http 资源 | **Never**，避免降级攻击 |
 *
 * ## 白名单
 * 只有白名单域名允许在应用内打开，其余一律外部浏览器。
 * 这样即使用户点了页面上的广告链接，也不会被困在一个没有地址栏的 WebView 里。
 */
data class WebPolicy(
    /** 允许应用内打开的域名（不含 scheme，子域名自动匹配） */
    val allowedHosts: Set<String> = emptySet(),
    /** 是否开启 JavaScript */
    val javaScript: Boolean = true,
    /** 是否允许外部链接在应用内继续打开。false 时一律交给系统浏览器 */
    val keepExternalInApp: Boolean = false
) {
    /**
     * 判断该 URL 是否应在应用内打开。
     *
     * 规则：
     * 1. 只认 http/https。`intent://`、`market://`、`tel:` 这类一律外部处理，
     *    否则会踩到 Android 的 Intent 劫持面；
     * 2. 白名单为空时视为「允许打开初始页面所在域名」的宽松模式，
     *    由调用方在 [WebViewSupport] 里给初始 URL 的域名兜底；
     * 3. 匹配同时允许精确域名与子域名（`littleskin.cn` 匹配 `skin.littleskin.cn`）。
     */
    fun shouldOpenInApp(url: String): Boolean {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host?.lowercase() ?: return false

        if (allowedHosts.isEmpty()) return true

        return allowedHosts.any { allowed ->
            val a = allowed.lowercase().removePrefix("www.")
            host == a || host.endsWith(".$a")
        }
    }

    companion object {
        /** 公告 / 帮助页：只允许自家域名，JS 关闭（纯静态文案，关掉攻击面） */
        val StaticContent = WebPolicy(
            allowedHosts = emptySet(),
            javaScript = false,
            keepExternalInApp = false
        )

        /** 授权页：站点需要 JS，但只允许认证站点本身 */
        fun auth(apiHost: String) = WebPolicy(
            allowedHosts = setOf(apiHost),
            javaScript = true,
            keepExternalInApp = false
        )
    }
}
