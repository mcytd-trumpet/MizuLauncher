package com.mizulauncher

import android.content.Context
import com.mizulauncher.bridge.LaunchOptions
import com.mizulauncher.bridge.LaunchResult
import com.mizulauncher.bridge.LauncherBridge
import com.mizulauncher.download.DownloadEngine
import com.mizulauncher.download.DownloadTask
import com.mizulauncher.auth.toAccount
import com.mizulauncher.instance.Account
import com.mizulauncher.instance.ArgumentBuilder
import com.mizulauncher.instance.InstanceMeta
import com.mizulauncher.instance.LaunchRequest
import com.mizulauncher.minecraft.DownloadPlanBuilder
import com.mizulauncher.minecraft.VersionRepository
import com.mizulauncher.minecraft.model.VersionEntry
import com.mizulauncher.minecraft.model.VersionJson
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 安装 & 启动编排器。
 *
 * 把「版本清单 → 合并 inheritsFrom → 生成下载计划 → 多线程下载 → 拼启动参数 → 交给内核」串成一条链。
 * UI 只订阅 [installing] / `downloadEngine.progress` / [stageFlow]，不关心内部细节。
 *
 * 关键约定：
 *  - 所有落盘路径都必须 **绝对路径**，因为参数会被 Pojav 的 JVM 直接 `new File(...)` 使用；
 *  - 版本 JSON 必须写进 `versions/<id>/<id>.json`，Forge/Fabric 的启动器会回读它；
 *  - 下载计划里的相对路径以 `gameDir` 为根，与 `ArgumentBuilder` 里的布局保持一致。
 */
class LaunchOrchestrator(context: Context) {

    private val app = context.applicationContext as MizuApplication
    private val container = app.container

    private val sourceFlow = MutableStateFlow(container.settings.downloadSource)

    val repository = VersionRepository(sourceFlow, container.cacheDir)

    /** 加载器安装器（Fabric/Quilt/Forge/NeoForge） */
    val loaderInstaller = com.mizulauncher.minecraft.LoaderInstaller(sourceFlow)

    val downloadEngine = DownloadEngine(
        source = container.settings.downloadSource,
        maxParallelFiles = container.settings.downloadThreads
    )

    /** 内核（尚未集成时为空，UI 需要提示用户去看 README） */
    val bridge: LauncherBridge? = PojavBridgeProvider.create(context)

    private val _installing = MutableStateFlow(false)
    val installing: StateFlow<Boolean> = _installing.asStateFlow()

    private val _stage = MutableStateFlow("")
    val stage: StateFlow<String> = _stage.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    fun setSource(source: com.mizulauncher.download.DownloadSource) {
        sourceFlow.value = source
        container.settings.downloadSource = source
    }

    /**
     * 确保该实例所需文件齐全，并返回可直接交给内核的启动请求。
     *
     * 已存在的文件按 SHA1 校验跳过（零流量），因此重复调用等价于「修复安装」。
     */
    suspend fun prepare(
        meta: InstanceMeta,
        onStage: (String) -> Unit = {}
    ): Result<LaunchRequest> = withContext(Dispatchers.IO) {
        _installing.value = true
        _lastError.value = null
        try {
            fun stage(text: String) {
                _stage.value = text
                onStage(text)
            }

            stage("获取版本清单")
            val manifest = repository.loadManifest().getOrThrow()

            val im = container.instanceManager
            // 以磁盘上的元数据为准。传进来的 meta 可能来自主页那份缓存列表，
            // 里面缺了别处刚写进去的字段（javaMajor / lastPlayed / 重命名后的 name）。
            val stored = im.create(meta)
            var current = stored.copy(
                // 传进来的这几个决定「装什么」，必须是调用方的意图
                versionId = meta.versionId,
                loaderType = meta.loaderType,
                loaderVersion = meta.loaderVersion,
                name = meta.name.ifBlank { stored.name },
                javaMajor = if (meta.javaMajor > 0) meta.javaMajor else stored.javaMajor
            )
            im.ensureSkeleton(current)
            val gameDir = im.gameDirOf(current)
            val versionsDir = File(gameDir, "versions")

            // 先把加载器装好（它会在 versions/ 下写出带 inheritsFrom 的 profile），
            // 否则后面按 versionId 查清单会找不到（例如 1.20.1-fabric-0.15.11）。
            if (current.loaderType != "vanilla" && current.loaderType.isNotBlank()) {
                val profileJson = File(versionsDir, "${current.versionId}/${current.versionId}.json")
                if (!profileJson.isFile) {
                    stage("安装 ${current.loaderType} 加载器")
                    when (val r = loaderInstaller.install(
                        gameVersion = current.versionId.substringBefore('-', current.versionId)
                            .let { if (it.count { c -> c == '.' } >= 1) it else current.versionId },
                        loaderType = current.loaderType,
                        loaderVersion = current.loaderVersion,
                        versionsDir = versionsDir,
                        librariesDir = im.sharedLibsDir(current),
                        onStage = { stage(it) }
                    )) {
                        is com.mizulauncher.minecraft.LoaderInstaller.Outcome.Success -> Unit
                        is com.mizulauncher.minecraft.LoaderInstaller.Outcome.NeedManual ->
                            return@withContext fail(r.reason)
                        is com.mizulauncher.minecraft.LoaderInstaller.Outcome.Failure ->
                            return@withContext fail("加载器安装失败：${r.message}")
                    }
                }
            }

            // 加载器 profile 是自己写的文件，清单里没有它，需要单独构造 entry
            val localProfile = File(versionsDir, "${current.versionId}/${current.versionId}.json")
            val inManifest = manifest.versions.firstOrNull { it.id == current.versionId }
            val entry = if (inManifest != null) {
                inManifest
            } else if (localProfile.isFile) {
                VersionEntry(current.versionId, "release", localProfile.toURI().toString())
            } else {
                return@withContext fail("版本清单中找不到 ${current.versionId}，且本地无版本配置")
            }

            stage("解析版本 JSON（含加载器继承）")
            val merged = if (localProfile.isFile) {
                resolveLocalProfileSupport(current, entry, localProfile)
            } else {
                repository.resolveWithInheritance(entry).getOrThrow()
            }
            // 解析出的版本可能带加载器信息，回填到实例元数据
            val actualJava = com.mizulauncher.minecraft.VersionRules.requiredJavaMajor(merged)
            if (actualJava > 0 && actualJava != current.javaMajor) {
                // 必须写回 current，否则后面那次 update(lastPlayed) 会用旧值时把这里覆盖掉，
                // 表现为每次启动都重新探测一遍 Java 版本。
                current = current.copy(javaMajor = actualJava)
                container.instanceManager.update(current)
            }

            val versionDir = File(versionsDir, current.versionId).apply { mkdirs() }

            stage("下载资源索引")
            val assetIndex = merged.assetIndex?.let { ref ->
                repository.loadAssetIndex(ref).getOrThrow()
            }

            // 资源索引本体也要落盘（老版本启动时直接读文件，不动网络）
            merged.assetIndex?.let { ref ->
                val cached = File(container.cacheDir, "assets/indexes/${ref.id}.json")
                if (cached.isFile) {
                    val dest = File(gameDir, "assets/indexes/${ref.id}.json")
                    dest.parentFile?.mkdirs()
                    cached.copyTo(dest, overwrite = true)
                }
            }

            val layout = DownloadPlanBuilder.ClientLayout(
                gameDir = gameDir.absolutePath,
                assetsDir = im.sharedAssetsDir(current).absolutePath,
                librariesDir = im.sharedLibsDir(current).absolutePath,
                versionsDir = versionsDir.absolutePath
            )

            val clientUrl = merged.downloads["client"]?.url
            val plan = DownloadPlanBuilder.build(current.versionId, merged, layout, assetIndex, clientUrl)

            stage("准备文件（${plan.size} 项）")
            val tasks = ArrayList<DownloadTask>(plan.size + 2)
            tasks += VersionRepository.toDownloadTasks(plan, gameDir)
            // 版本 JSON：Forge/Fabric 等启动器会回读，必须写
            tasks += DownloadTask(
                url = entry.url,
                target = File(versionDir, "${current.versionId}.json"),
                sha1 = entry.sha1,
                downloadClass = com.mizulauncher.download.DownloadClass.METADATA,
                fatal = true
            )

            val unique = tasks.distinctBy { it.target.absolutePath }
            downloadEngine.run(unique, "下载游戏文件").getOrThrow()

            stage("生成启动参数")
            // 有正版档案就用正版身份，否则退回离线模式（名字取设置里的）
            val account = resolveAccount()
            pendingAccount = account
            val request = ArgumentBuilder.build(
                versionId = current.versionId,
                merged = merged,
                layout = layout,
                account = account,
                instanceId = current.id
            )
            stage("准备完成")

            // 记录一次「最近活动」，列表按此排序
            container.instanceManager.update(current.copy(lastPlayed = System.currentTimeMillis()))
            Result.success(request)
        } catch (e: Exception) {
            fail(e.message ?: e.javaClass.simpleName)
        } finally {
            _installing.value = false
        }
    }

    /** 直接用已准备好的请求启动游戏 */
    suspend fun launch(
        request: LaunchRequest,
        options: LaunchOptions = LaunchOptions()
    ): LaunchResult = withContext(Dispatchers.IO) {
        val b = bridge
            ?: return@withContext LaunchResult.Failed(
                "未集成 Pojav 内核。请按 README 把 PojavLauncher 作为 submodule 引入，并实现 PojavBridgeImpl。"
            )
        runCatching { b.ensureRuntime(request.javaMajor).getOrThrow() }
            .onFailure { return@withContext LaunchResult.Failed("下载 Java ${request.javaMajor} 运行时失败：${it.message}") }
        val account = pendingAccount ?: resolveAccount()
        b.launch(request, account, options)
    }

    /** prepare() 中解析出的账户，launch() 复用同一个，避免两次续期拿到不同令牌 */
    @Volatile
    private var pendingAccount: Account? = null

    /** 启动时实际使用的账户。令牌过期会在这里静默续期一次 */
    private suspend fun resolveAccount(): Account = withContext(Dispatchers.IO) {
        container.accountSession.valid()?.toAccount()
            ?: Account(container.settings.lastAccount)
    }

    /** 一步到位：装好再启动 */
    suspend fun installAndLaunch(
        meta: InstanceMeta,
        options: LaunchOptions = LaunchOptions()
    ): LaunchResult {
        val request = prepare(meta).getOrElse {
            return LaunchResult.Failed(it.message ?: "安装失败")
        }
        return launch(request, options)
    }

    /**
     * 解析本地加载器 profile：它自己的 JSON 从磁盘读，父版本走仓库（可能已缓存）。
     * 这样 Fabric/Forge 的 `inheritsFrom` 链能正确合并，不用把 profile 塞进官方清单。
     */
    private suspend fun resolveLocalProfileSupport(
        meta: InstanceMeta,
        entry: VersionEntry,
        profileFile: File
    ): VersionJson {
        val gson = Gson()
        val child = gson.fromJson(profileFile.readText(), VersionJson::class.java)
        val parentId = child.inheritsFrom
            ?: return child // 无继承，直接用
        val parentEntry = repository.manifest.value?.versions?.firstOrNull { it.id == parentId }
            ?: VersionEntry(parentId, "release", "")
        if (parentEntry.url.isBlank()) {
            // 清单还没加载过父版本，先确保加载
            repository.loadManifest()
        }
        val resolvedParent = repository.manifest.value?.versions?.firstOrNull { it.id == parentId }
            ?: throw IllegalStateException("加载器 profile 依赖的原版 $parentId 不在版本清单中")
        val parent = repository.resolveWithInheritance(resolvedParent).getOrThrow()
        return com.mizulauncher.minecraft.VersionRules.merge(parent, child.copy(inheritsFrom = null))
    }

    private fun fail(message: String): Result<LaunchRequest> {
        _lastError.value = message
        _stage.value = "失败"
        return Result.failure(IllegalStateException(message))
    }
}

/**
 * 内核提供者。`PojavBridgeImpl` 在 `PojavLauncher` submodule 引入后注册到这里；
 * 未引入时保持为 null，UI 会优雅提示而不是崩溃。
 */
object PojavBridgeProvider {
    @Volatile
    var factory: ((Context) -> LauncherBridge?)? = null

    fun create(context: Context): LauncherBridge? =
        factory?.let { runCatching { it(context) }.getOrNull() }
}
