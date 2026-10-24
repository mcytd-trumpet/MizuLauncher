package com.mizulauncher.compat

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * authlib-injector 组件管理器。
 *
 * ## 它是干什么的
 * Minecraft 原版只认 Mojang 的会话服务器。想在游戏里用 LittleSkin 这类外置皮肤站，
 * 必须在 JVM 启动前挂一个 `-javaagent`，由它把游戏内所有对 Mojang API 的请求
 * 重定向到外置站点。这个 agent 就是 **authlib-injector**（Blessing Skin 生态的事实标准）。
 *
 * ## 为什么必须自己下载而不是打包进 APK
 * 1. 它是一份不到 1 MB 的 jar，但**版本必须与游戏版本大致匹配**，
 *    打进 APK 就锁死了，将来游戏更新就得跟着发版；
 * 2. GPLv2 组件打进 APK 会带来额外分发义务，单独下载则只是"用户自行获取的运行时资源"。
 *
 * ## 离线可用性
 * 下载过的 jar 会留在本地，下次直接用，不再联网。下载失败时 [ensure] 返回失败，
 * 由调用方决定是"降级为正版登录"还是"提示用户重试"——**绝不静默忽略**，
 * 否则用户会遇到"登录了但游戏里还是默认皮肤"的灵异现象。
 */
object AuthlibInjector {

    /** 官方发布页（yushi 的镜像站，authlib-injector 上游指定的下载源） */
    private const val VERSION_URL = "https://authlib-injector.yushi.moe/artifact/latest.json"

    /** 兜底直链：拿不到版本元数据时用固定版本号 */
    private const val FALLBACK_VERSION = "1.2.5"
    private const val FALLBACK_URL =
        "https://authlib-injector.yushi.moe/artifact/$FALLBACK_VERSION/authlib-injector-$FALLBACK_VERSION.jar"

    data class Info(val version: String, val downloadUrl: String)

    /**
     * 确保本地存在可用的 authlib-injector.jar。
     *
     * @param dir 存放目录（通常是 `files/bundles/authlib`）
     * @param fetcher 网络取文本的钩子，便于单测注入
     * @return jar 文件；失败返回 null，调用方需据此提示用户
     */
    fun ensure(dir: File, fetcher: ((String) -> String?)? = null): File? {
        dir.mkdirs()
        // 已存在且非空 —— 直接复用，不检查版本。理由：换版本对 MC 的影响远小于
        // 每次启动都联网检查带来的失败面（弱网、被墙、站点抖动）。
        dir.listFiles { f -> f.name.startsWith("authlib-injector") && f.length() > 100_000 }
            ?.maxByOrNull { it.lastModified() }
            ?.let { return it }

        val info = runCatching { resolveLatest(fetcher) }.getOrNull()
        val url = info?.downloadUrl ?: FALLBACK_URL
        val version = info?.version ?: FALLBACK_VERSION
        val target = File(dir, "authlib-injector-$version.jar")

        return runCatching {
            download(url, target)
            // 粗校验：jar 必须是 zip（PK 头），否则说明下到的是错误页 HTML
            val head = target.inputStream().use { it.readNBytes(2) }
            require(head.size == 2 && head[0] == 0x50.toByte() && head[1] == 0x4B.toByte()) {
                "下载到的不是有效 jar（可能是错误页）"
            }
            // 顺手做一次摘要，便于日志排查
            sha256(target)
            target
        }.getOrNull()
    }

    /** 构造 `-javaagent` 参数。路径含空格时 JVM 会解析失败，因此必须走 file URI 且不转义 */
    fun javaAgentArg(jar: File, apiRoot: String): String {
        val normalized = apiRoot.trimEnd('/')
        return "-javaagent:${jar.absolutePath}=$normalized"
    }

    private fun resolveLatest(fetcher: ((String) -> String?)?): Info? {
        val json = fetcher?.invoke(VERSION_URL) ?: httpGet(VERSION_URL) ?: return null
        // 极简解析：只取两个字段，避免为一个 200 字节的 JSON 引入序列化依赖
        val version = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)
            ?: return null
        val downloadUrl = Regex("\"download_url\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)
            ?: return null
        return Info(version, downloadUrl)
    }

    private fun httpGet(url: String): String? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "MizuLauncher/2.0")
        }
        if (conn.responseCode !in 200..299) return null
        conn.inputStream.bufferedReader().use { it.readText() }
    }.getOrNull()

    @Throws(IOException::class)
    private fun download(url: String, target: File) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "MizuLauncher/2.0")
        }
        require(conn.responseCode in 200..299) { "HTTP ${conn.responseCode}" }
        // 先写临时文件再原子改名：中途断网不会留下半截 jar 被下次启动误认为"已就绪"
        val tmp = File(target.parentFile, "${target.name}.part")
        conn.inputStream.use { input ->
            tmp.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
        }
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
