package com.mizulauncher.debugapi

/**
 * 调试 API 的能力边界。
 *
 * `core:debug-api` 只懂 HTTP 与路由，真正干活的是 App 进程里的实现。
 * 用接口隔开有三个原因：
 *  1. 这个模块能独立编译、独立单测，不必拖着 app 的 Compose 依赖；
 *  2. 契约写在这里，谁实现都行（真机 App、假数据、将来搬到服务端）；
 *  3. 一眼能看出调试 API 到底暴露了什么，不会偷偷多出接口。
 *
 * ## 三条设计约定
 *
 * 1. **不阻塞**。任何方法都应当快速返回，绝不在这里等游戏下载完。
 *    耗时操作走「触发 + 查状态」两段式（[prepareInstance] + [taskStatus]）。
 * 2. **不抛 Android 异常**。实现内部要自己 try/catch，把失败表达成
 *    [ApiException] 或者数据结构里的 `ok=false` 字段。
 * 3. **字段用 Snapshot 表达**。返回 `Map<String, Any?>` 而不是定义一堆 data class，
 *    是为了让实现方能自由加字段而不改变契约——调试接口应该是宽松的。
 */
interface LauncherFacade {

    // ------------------------------------------------------------ 诊断

    /** App 版本、ABI、运行时状态、依赖状态、当前任务。整个 API 的「总览」 */
    fun diagnostics(): Map<String, Any?>

    // ------------------------------------------------------------ 实例

    fun listInstances(): List<Map<String, Any?>>

    /** 找不到返回 null，路由层会转成 404 */
    fun getInstance(id: String): Map<String, Any?>?

    /**
     * 创建实例。
     *
     * 必填：`versionId`。可选：`name` `loaderType` `loaderVersion`
     * `javaMajor` `isolated` `shareLibsAndAssets`。
     */
    fun createInstance(body: Map<String, Any?>): Map<String, Any?>

    /** 部分更新。只改 body 里出现的字段，没出现的保持原样 */
    fun updateInstance(id: String, body: Map<String, Any?>): Map<String, Any?>

    /** @return 是否真的删掉了。deleteFiles=false 时只从列表移除 */
    fun deleteInstance(id: String, deleteFiles: Boolean): Boolean

    /** 启动前的文件准备。立即返回，进度查 [taskStatus] */
    fun prepareInstance(id: String): Boolean

    /** 拉起游戏。成功返回 {launched:true, ...}，失败返回 {launched:false, reason:...} */
    fun launchInstance(id: String): Map<String, Any?>

    // ------------------------------------------------------------ 任务

    /** 当前/最近一次任务的状态与进度 */
    fun taskStatus(): Map<String, Any?>

    /** 取消正在跑的任务。没有任务时返回 false */
    fun cancelTask(): Boolean

    // ------------------------------------------------------------ 版本与下载源

    /** 版本清单。force=true 时绕过缓存重新拉 */
    fun listVersions(force: Boolean): List<Map<String, Any?>>

    /** 可用的下载源 */
    fun listSources(): List<Map<String, Any?>>

    /** 切换下载源。返回切换后的源 */
    fun setSource(id: String): Map<String, Any?>

    // ------------------------------------------------------------ 运行依赖

    /** 扫描内置组件（JRE / LWJGL / 渲染器等），返回每项状态 */
    fun scanBundles(): Map<String, Any?>

    /** 触发补全缺失组件。立即返回，进度查 [taskStatus] */
    fun installBundles(): Boolean

    /** 运行时（Java）状态 */
    fun listRuntimes(): List<Map<String, Any?>>

    // ------------------------------------------------------------ 账户

    /** 当前登录状态。未登录返回 {loggedIn:false} */
    fun accountStatus(): Map<String, Any?>

    /**
     * 开始设备码登录。
     *
     * 立即返回 `{userCode, verificationUri, expiresIn}`，
     * 之后由调用方轮询 [accountStatus] 看是否登录成功。
     */
    fun startLogin(): Map<String, Any?>

    fun cancelLogin(): Boolean

    fun logout(): Boolean

    /** 设置离线模式用户名 */
    fun setOfflineName(name: String): Boolean

    // ------------------------------------------------------------ 更新

    /** 检查更新。返回 {hasUpdate, currentVersion, latest, asset...} */
    fun checkUpdate(): Map<String, Any?>

    // ------------------------------------------------------------ 设置

    fun getSettings(): Map<String, Any?>

    /** 部分更新设置，只改出现的字段 */
    fun updateSettings(body: Map<String, Any?>): Map<String, Any?>

    // ------------------------------------------------------------ 日志

    /** 最近 N 行日志（来自应用日志缓冲） */
    fun recentLogs(lines: Int): Map<String, Any?>
}
