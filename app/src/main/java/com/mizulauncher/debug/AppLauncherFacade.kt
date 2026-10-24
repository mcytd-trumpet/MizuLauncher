package com.mizulauncher.debug

import android.os.Build
import com.mizulauncher.LauncherViewModel
import com.mizulauncher.MizuApplication
import com.mizulauncher.debugapi.ApiException
import com.mizulauncher.debugapi.LauncherFacade
import com.mizulauncher.download.DownloadSource
import com.mizulauncher.instance.InstanceMeta
import com.mizulauncher.update.UpdateChecker
import com.mizulauncher.update.UpdateChecker.UpdateInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [LauncherFacade] 的真实实现：把调试 API 接到 App 的实际逻辑上。
 *
 * ## 为什么要持有 ViewModel
 *
 * 安装、启动这些流程的状态都在 [LauncherViewModel] 里（`installing`、`stage`、`error`、
 * `downloadProgress`）。如果这里另起一套，就会出现「API 触发的安装」和「UI 触发的安装」
 * 互不知情的情况——两边都能启动一次下载，互相打架。
 *
 * 所以调试 API **复用同一个 ViewModel 实例**，等于和 UI 共用一条控制链路。
 * 这样你用 curl 触发的启动，在手机上也能看到进度条在动，不会出现「黑箱」。
 *
 * ## 超时控制
 *
 * 有些 ViewModel 方法是挂起函数（内部会等下载完），但 HTTP 请求不能挂上十分钟。
 * 这里统一用 [quick] 包一层：最多等 3 秒，超时就返回「已开始，去查 /task」。
 * 真正的长任务在 ViewModel 的 scope 里继续跑。
 */
class AppLauncherFacade(
    private val app: MizuApplication,
    /** 由 Activity 注入。为 null 时所有需要界面状态的能力会返回 503 */
    private val viewModelProvider: () -> LauncherViewModel?
) : LauncherFacade {

    private val container get() = app.container
    private val settings get() = container.settings

    /** 用于「触发了就返回」的异步任务（下载、解压等） */
    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val vm: LauncherViewModel?
        get() = viewModelProvider()

    private fun requireVm(): LauncherViewModel =
        vm ?: throw ApiException(503, "ui_not_ready",
            "启动器界面还没起来（ViewModel 未初始化）。请先打开 App 一次，再调这个接口。")

    // ------------------------------------------------------------ 诊断

    override fun diagnostics(): Map<String, Any?> {
        val v = vm
        return mapOf(
            "app" to mapOf(
                "packageName" to app.packageName,
                "versionName" to appVersionName(),
                "versionCode" to appVersionCode(),
                "minSdk" to 26
            ),
            "device" to mapOf(
                "abi" to Build.SUPPORTED_ABIS.firstOrNull(),
                "abis" to Build.SUPPORTED_ABIS.toList(),
                "sdk" to Build.VERSION.SDK_INT,
                "model" to Build.MODEL,
                "brand" to Build.BRAND
            ),
            "state" to mapOf(
                "uiReady" to (v != null),
                "installing" to (v?.installing?.value ?: false),
                "stage" to (v?.stage?.value ?: ""),
                "error" to v?.error?.value,
                "instanceCount" to (v?.instances?.value?.size ?: container.instanceManager.list().size),
                "bundlesScanned" to (v?.bundleScanned?.value ?: false),
                "bundlesPending" to (v?.bundlePending?.value?.size ?: 0),
                "loggedIn" to (v?.profile?.value != null)
            ),
            "paths" to mapOf(
                "root" to container.rootDir.absolutePath,
                "cache" to container.cacheDir.absolutePath,
                "bundles" to java.io.File(app.filesDir, "bundles").absolutePath
            ),
            "settings" to getSettings()
        )
    }

    // ------------------------------------------------------------ 实例

    override fun listInstances(): List<Map<String, Any?>> {
        // 优先用 ViewModel 的实时列表；界面没起来时直接读磁盘
        val list = vm?.instances?.value ?: container.instanceManager.list()
        return list.map { it.toMap() }
    }

    override fun getInstance(id: String): Map<String, Any?>? =
        container.instanceManager.list().firstOrNull { it.id == id }?.toMap()

    override fun createInstance(body: Map<String, Any?>): Map<String, Any?> {
        val versionId = body["versionId"] as? String
        if (versionId.isNullOrBlank()) {
            throw ApiException(400, "bad_request", "缺少必填字段 versionId")
        }
        val loaderType = (body["loaderType"] as? String)?.ifBlank { "vanilla" } ?: "vanilla"
        val meta = InstanceMeta(
            name = (body["name"] as? String).orEmpty(),
            versionId = versionId,
            loaderType = loaderType,
            loaderVersion = (body["loaderVersion"] as? String).orEmpty(),
            javaMajor = (body["javaMajor"] as? Number)?.toInt() ?: 0,
            isolated = body["isolated"] as? Boolean ?: true,
            shareLibsAndAssets = body["shareLibsAndAssets"] as? Boolean ?: true
        )
        val created = container.instanceManager.create(meta)
        vm?.refreshInstances()
        return created.toMap()
    }

    override fun updateInstance(id: String, body: Map<String, Any?>): Map<String, Any?> {
        val mgr = container.instanceManager
        val old = mgr.list().firstOrNull { it.id == id }
            ?: throw ApiException(404, "instance_not_found", "实例 $id 不存在")

        // 只改 body 里出现的字段，其余保持原样
        val merged = old.copy(
            name = (body["name"] as? String) ?: old.name,
            versionId = (body["versionId"] as? String) ?: old.versionId,
            loaderType = (body["loaderType"] as? String) ?: old.loaderType,
            loaderVersion = (body["loaderVersion"] as? String) ?: old.loaderVersion,
            javaMajor = (body["javaMajor"] as? Number)?.toInt() ?: old.javaMajor,
            isolated = body["isolated"] as? Boolean ?: old.isolated,
            shareLibsAndAssets = body["shareLibsAndAssets"] as? Boolean ?: old.shareLibsAndAssets,
            jvmArgs = (body["jvmArgs"] as? String) ?: old.jvmArgs,
            iconKey = (body["iconKey"] as? String) ?: old.iconKey
        )
        mgr.update(merged)
        vm?.refreshInstances()
        return merged.toMap()
    }

    override fun deleteInstance(id: String, deleteFiles: Boolean): Boolean {
        val ok = container.instanceManager.delete(id, deleteFiles)
        if (ok) vm?.refreshInstances()
        return ok
    }

    override fun prepareInstance(id: String): Boolean {
        val v = requireVm()
        if (v.installing.value) {
            throw ApiException(409, "busy", "已有任务在跑，先等它结束或调 /task/cancel")
        }
        val meta = container.instanceManager.list().firstOrNull { it.id == id }
            ?: throw ApiException(404, "instance_not_found", "实例 $id 不存在")
        // 不等待：prepare 可能要下载几百 MB，HTTP 请求不能挂在那儿。
        // 和 UI 走同一条 ViewModel 链路，手机上能看到同样的进度。
        bg.launch { runCatching { v.prepareOnly(meta) } }
        return true
    }

    override fun launchInstance(id: String): Map<String, Any?> {
        val v = requireVm()
        if (v.installing.value) {
            throw ApiException(409, "busy", "已有任务在跑，先等它结束或调 /task/cancel")
        }
        val meta = container.instanceManager.list().firstOrNull { it.id == id }
            ?: throw ApiException(404, "instance_not_found", "实例 $id 不存在")
        bg.launch { v.launch(meta) }
        return mapOf(
            "launched" to true,
            "id" to id,
            "hint" to "启动是异步的，用 GET /task 与 GET /logs 跟踪"
        )
    }

    // ------------------------------------------------------------ 任务

    override fun taskStatus(): Map<String, Any?> {
        val v = vm
        val p = v?.downloadProgress?.value
        return mapOf(
            "installing" to (v?.installing?.value ?: false),
            "stage" to (v?.stage?.value ?: ""),
            "error" to v?.error?.value,
            "download" to mapOf(
                "totalTasks" to (p?.totalTasks ?: 0),
                "doneTasks" to (p?.doneTasks ?: 0),
                "failedTasks" to (p?.failedTasks ?: 0),
                "percent" to (p?.percent ?: 0),
                "totalBytes" to (p?.totalBytes ?: 0L),
                "downloadedBytes" to (p?.downloadedBytes ?: 0L),
                "speedBps" to (p?.speedBps ?: 0L),
                "etaSeconds" to (p?.etaSeconds ?: -1L),
                "currentFile" to (p?.currentFile ?: ""),
                "finished" to (p?.finished ?: false)
            ),
            "bundles" to mapOf(
                "scanned" to (v?.bundleScanned?.value ?: false),
                "pending" to (v?.bundlePending?.value?.size ?: 0),
                "phase" to (v?.bundlePhase?.value?.toString() ?: "Idle"),
                "notice" to v?.bundleNotice?.value
            )
        )
    }

    override fun cancelTask(): Boolean {
        val v = vm ?: return false
        if (!v.installing.value) return false
        v.cancelDownload()
        return true
    }

    // ------------------------------------------------------------ 版本与下载源

    override fun listVersions(force: Boolean): List<Map<String, Any?>> {
        val v = requireVm()
        v.loadVersions(force)
        // 拉清单是网络请求，给它 3 秒；超时就把已有的返回去
        val versions = runBlocking {
            withTimeoutOrNull(3_000) {
                // 轮询直到不再 loading
                while (v.loadingVersions.value) kotlinx.coroutines.delay(100)
                v.versions.value
            }
        } ?: v.versions.value
        v.versionError.value?.let { throw ApiException(502, "manifest_failed", it) }
        return versions.map { mapOf("id" to it.id, "type" to it.type, "url" to it.url) }
    }

    override fun listSources(): List<Map<String, Any?>> =
        DownloadSource.entries.map { s ->
            mapOf(
                "id" to s.name,
                "name" to s.displayName,
                "current" to (settings.downloadSource == s)
            )
        }

    override fun setSource(id: String): Map<String, Any?> {
        val source = DownloadSource.entries.firstOrNull { it.name.equals(id, ignoreCase = true) }
            ?: throw ApiException(400, "bad_request",
                "未知下载源 $id，可选：" + DownloadSource.entries.joinToString { it.name })
        vm?.setSource(source) ?: run {
            settings.downloadSource = source
            container.sourceFlow.value = source
        }
        return mapOf("id" to source.name, "current" to true)
    }

    // ------------------------------------------------------------ 运行依赖

    override fun scanBundles(): Map<String, Any?> {
        val v = vm ?: throw ApiException(503, "ui_not_ready", "启动器界面还没起来")
        // scan 是同步的（纯文件操作），可以直接等
        runCatching { v.rescanBundles() }
        val items = v.bundleItems.value
        val pending = v.bundlePending.value
        return mapOf(
            "scanned" to v.bundleScanned.value,
            "pending" to pending.size,
            "items" to items.map { item ->
                mapOf(
                    "key" to item.key,
                    "kind" to item.kind,
                    "name" to item.name,
                    "label" to item.label,
                    "sizeBytes" to item.sizeBytes,
                    "state" to (v.bundleStates.value[item.key]?.toString() ?: "Unknown")
                )
            }
        )
    }

    override fun installBundles(): Boolean {
        val v = vm ?: throw ApiException(503, "ui_not_ready", "启动器界面还没起来")
        if (v.bundlePending.value.isEmpty()) return false
        bg.launch { runCatching { v.installBundles() } }
        return true
    }

    override fun listRuntimes(): List<Map<String, Any?>> =
        (8..25).filter { it in listOf(8, 17, 21, 25) }.map { major ->
            val state = vm?.runtimeStates?.value?.get(major)
            mapOf(
                "major" to major,
                "state" to (state?.toString() ?: "Unknown"),
                "ready" to (vm?.runtimeManager?.isReady(major) ?: false)
            )
        }

    // ------------------------------------------------------------ 账户

    override fun accountStatus(): Map<String, Any?> {
        val p = vm?.profile?.value
        return if (p == null) {
            mapOf("loggedIn" to false, "offlineName" to settings.lastAccount)
        } else {
            mapOf(
                "loggedIn" to true,
                "name" to p.name,
                "uuid" to p.uuid,
                // 令牌本身绝不返回：调试接口不该成为凭据泄漏点
                "tokenExpiresAt" to p.expiresAtEpochMs,
                "type" to "msa"
            )
        }
    }

    override fun startLogin(): Map<String, Any?> {
        val v = requireVm()
        v.startMicrosoftLogin()
        // 设备码是异步拿的，等它最多 5 秒
        val code = runBlocking {
            withTimeoutOrNull(5_000) {
                while (v.deviceCode.value == null) kotlinx.coroutines.delay(100)
                v.deviceCode.value
            }
        } ?: throw ApiException(502, "login_failed",
            "拿不到设备码：${v.authError.value ?: "网络超时"}")
        return mapOf(
            "userCode" to code.userCode,
            "verificationUri" to code.verificationUri,
            "verificationUriComplete" to code.verificationUriComplete,
            "expiresIn" to code.expiresInSeconds,
            "pollInterval" to code.intervalSeconds,
            "hint" to "让用户在浏览器打开链接输入 userCode，然后轮询 GET /account 看 loggedIn"
        )
    }

    override fun cancelLogin(): Boolean {
        vm?.cancelLogin() ?: return false
        return true
    }

    override fun logout(): Boolean {
        vm?.logout() ?: run { container.accountSession.logout() }
        return true
    }

    override fun setOfflineName(name: String): Boolean {
        settings.lastAccount = name
        return true
    }

    // ------------------------------------------------------------ 更新

    override fun checkUpdate(): Map<String, Any?> {
        val checker = UpdateChecker()
        val info: UpdateInfo? = runCatching {
            checker.checkForUpdate(appVersionCode(), appVersionName(), Build.SUPPORTED_ABIS.toList())
        }.getOrElse {
            throw ApiException(502, "update_check_failed", it.message ?: "检查更新失败")
        }
        return if (info == null) {
            mapOf(
                "hasUpdate" to false,
                "currentVersion" to appVersionName(),
                "currentVersionCode" to appVersionCode()
            )
        } else {
            mapOf(
                "hasUpdate" to true,
                "currentVersion" to appVersionName(),
                "currentVersionCode" to appVersionCode(),
                "latestVersion" to info.versionName,
                "latestVersionCode" to info.versionCode,
                "tag" to info.tag,
                "publishedAt" to info.publishedAt,
                "notes" to info.notes,
                "deviceAbi" to info.deviceAbi,
                "asset" to info.asset?.let {
                    mapOf("name" to it.name, "url" to it.url, "sizeBytes" to it.sizeBytes,
                        "sizeText" to it.sizeText, "abi" to it.abi)
                },
                "allAssets" to info.allAssets.map {
                    mapOf("name" to it.name, "url" to it.url, "sizeBytes" to it.sizeBytes, "abi" to it.abi)
                }
            )
        }
    }

    // ------------------------------------------------------------ 设置

    override fun getSettings(): Map<String, Any?> = mapOf(
        "downloadSource" to settings.downloadSource.name,
        "downloadThreads" to settings.downloadThreads,
        "maxMemoryMb" to settings.maxMemoryMb,
        "renderer" to settings.renderer,
        "lastAccount" to settings.lastAccount,
        "themeMode" to settings.themeMode,
        "runtimeManifestUrl" to settings.runtimeManifestUrl
    )

    override fun updateSettings(body: Map<String, Any?>): Map<String, Any?> {
        (body["downloadThreads"] as? Number)?.let { settings.downloadThreads = it.toInt() }
        (body["maxMemoryMb"] as? Number)?.let { settings.maxMemoryMb = it.toInt() }
        (body["renderer"] as? String)?.let { settings.renderer = it }
        (body["themeMode"] as? String)?.let { settings.themeMode = it }
        (body["lastAccount"] as? String)?.let { settings.lastAccount = it }
        (body["runtimeManifestUrl"] as? String)?.let { settings.runtimeManifestUrl = it }
        (body["downloadSource"] as? String)?.let { setSource(it) }
        return getSettings()
    }

    // ------------------------------------------------------------ 日志

    override fun recentLogs(lines: Int): Map<String, Any?> = mapOf(
        "lines" to DebugLogBuffer.tail(lines),
        "capacity" to DebugLogBuffer.capacity(),
        "hint" to "只有在 App 里打开过调试日志采集才会有内容"
    )

    // ------------------------------------------------------------ 工具

    private fun InstanceMeta.toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "name" to name,
        "displayName" to displayName,
        "versionId" to versionId,
        "loaderType" to loaderType,
        "loaderVersion" to loaderVersion,
        "javaMajor" to javaMajor,
        "isolated" to isolated,
        "shareLibsAndAssets" to shareLibsAndAssets,
        "lastPlayed" to lastPlayed,
        "pinned" to pinned,
        "jvmArgs" to jvmArgs,
        "iconKey" to iconKey,
        "createdAt" to createdAt,
        "gameDir" to container.instanceManager.gameDirOf(this).absolutePath
    )

    private fun appVersionName(): String = runCatching {
        app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "unknown"
    }.getOrDefault("unknown")

    @Suppress("DEPRECATION")
    private fun appVersionCode(): Int = runCatching {
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else info.versionCode
    }.getOrDefault(0)
}
