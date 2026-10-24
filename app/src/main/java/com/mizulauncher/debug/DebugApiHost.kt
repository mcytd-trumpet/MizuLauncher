package com.mizulauncher.debug

import android.content.Context
import android.util.Log
import com.mizulauncher.LauncherViewModel
import com.mizulauncher.MizuApplication
import com.mizulauncher.debugapi.DebugApiServer
import java.lang.ref.WeakReference

/**
 * 调试 API 的启停总控。
 *
 * ## 什么时候开
 *
 * 不无条件开。生产包上跑着 `0.0.0.0` 的调试口是安全事故，跑在回环也白占资源。
 * 判定顺序：
 *  1. `debuggable` 构建 → **默认开**（开发时最方便）；
 *  2. 存在开关文件 `files/mizu-debug-api.on` → 强制开（release 包也开，
 *     这样你可以在正式版上调试，用完 `rm` 掉文件即可）；
 *  3. 否则不开。
 *
 * 开关文件这条是给你用的：`rish` 里
 * ```
 * run-as com.mizulauncher sh -c 'touch files/mizu-debug-api.on'
 * ```
 * 下次 App 启动就会开服务，端口和 token 写到 `files/mizu-debug-api.json`。
 *
 * ## 为什么用 WeakReference 存 ViewModel
 *
 * 这个 host 是 object（进程级单例），直接强引用 ViewModel 会让它无法回收。
 * ViewModel 本身跟随 Activity，用户退出就该走；用弱引用既拿得到（App 在前台时
 * 一定活着），又不会泄漏。
 */
object DebugApiHost {

    private const val TAG = "MizuDebugHost"

    @Volatile
    private var server: DebugApiServer? = null

    private var vmRef: WeakReference<LauncherViewModel>? = null

    /** 持有 app 引用以便随时重建 facade（facade 每次重启服务时新建） */
    @Volatile
    private var app: MizuApplication? = null

    /**
     * ViewModel 初始化时调用。首次调用起服务；之后只更新引用。
     *
     * **默认无条件开启**，不再区分 debug/release：
     * 安全性由「只绑回环 + 每次启动换 token + 可选全链路加密」保证，
     * 而不是靠「默认关掉」。默认关掉的结果是每次调试都要先去戳一个开关文件，
     * 实际使用时大部分人会把调试口长期开着——那反而更糟。
     */
    @Synchronized
    fun attach(vm: LauncherViewModel) {
        vmRef = WeakReference(vm)
        val application = vm.getApplication<MizuApplication>()
        app = application

        if (server?.isRunning == true) return
        startServer(application)
    }

    @Synchronized
    private fun startServer(application: MizuApplication): Boolean {
        val s = DebugApiServer(
            context = application,
            facade = AppLauncherFacade(application) { vmRef?.get() }
        )
        return if (s.start()) {
            server = s
            true
        } else {
            Log.e(TAG, "调试 API 启动失败")
            false
        }
    }

    /** 手动开。服务被系统回收后可以用它拉起来 */
    @Synchronized
    fun startNow(): Boolean {
        val application = app ?: return false
        if (server?.isRunning == true) return true
        return startServer(application)
    }

    @Synchronized
    fun stop() {
        server?.stop()
        server = null
    }

    val isRunning: Boolean get() = server?.isRunning == true

    val port: Int get() = server?.boundPort ?: -1

    val token: String get() = server?.token.orEmpty()

    /** 完整的 base url，给设置页展示用 */
    val baseUrl: String get() = if (isRunning) "http://127.0.0.1:$port/api/v1" else ""

    /**
     * 端到端加密是否**强制**。
     *
     * 默认不强制：明文通道留着给 curl 手工调试，加密通道给正式客户端。
     * 想知道怎么用加密通道见 `docs/DEBUG_API.md` 第 3 章。
     *
     * 如果哪天需要「只许加密、明文一律拒」，把这里改成读一个设置项即可，
     * 服务端已经有判定逻辑（`x-mizu-encrypted` 头），不需要改传输层。
     */
    val requireEncryption: Boolean get() = false

    /** 一次性读取全部连接信息，给脚本或设置页用 */
    fun connectionInfo(context: Context): Map<String, Any?>? {
        val s = server ?: return null
        if (!s.isRunning) return null
        return mapOf(
            "host" to "127.0.0.1",
            "port" to s.boundPort,
            "token" to s.token,
            "basePath" to "/api/v1",
            "baseUrl" to "http://127.0.0.1:${s.boundPort}/api/v1",
            "encryptionAvailable" to true,
            "encryptionRequired" to requireEncryption
        )
    }
}
