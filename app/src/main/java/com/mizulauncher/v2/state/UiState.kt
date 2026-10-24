package com.mizulauncher.v2.state

import com.mizulauncher.auth.MinecraftProfile
import com.mizulauncher.bridge.BridgeInfo
import com.mizulauncher.download.DownloadProgress
import com.mizulauncher.instance.InstanceMeta
import com.mizulauncher.minecraft.model.VersionEntry
import com.mizulauncher.runtime.RuntimeManager
import com.mizulauncher.setup.BundleInstaller
import com.mizulauncher.skin.TextureState

/**
 * 2.0 的界面状态。
 *
 * ## 为什么要重新定义，而不是继续用旧 ViewModel 的那堆 StateFlow
 * 旧版把状态摊成二十多个独立的 `StateFlow`（instances / selectedId / versions /
 * loadingVersions / versionError / installing / stage / error / ...）。
 * 这带来两个直接后果：
 *
 *  1. **组合态无法表达**。例如"正在安装且已到 60% 但用户切走了页面"，
 *     在 UI 上到底是显示还是隐藏？二十个独立流组合出来的中间态有几百种，
 *     每一种都可能是一个没被测过的画面。
 *  2. **改一处要动三处**。加一个状态就要加一个流、一个 getter、一个 UI 收集点。
 *
 * 2.0 改成**单一状态树**：一个不可变的 [UiState]，所有界面从同一份快照渲染。
 * 好处是同一时刻只有一种画面，不存在中间态；代价是每次更新要拷贝整个对象——
 * 但对几十个字段的数据类来说，一次拷贝是纳秒级，远小于一帧 16ms 的预算。
 *
 * ## 分层的组织方式
 * 不把全部东西塞进一个 200 字段的巨型 data class，而是按页面切分：
 * `home` / `download` / `account` / `runtime` / `settings` 各一个子状态。
 * 根状态只持有它们，页面只订阅自己那一块。
 */
data class UiState(
    val bootstrapping: Boolean = true,
    val home: HomeState = HomeState(),
    val download: DownloadState = DownloadState(),
    val account: AccountState = AccountState(),
    val runtime: RuntimeState = RuntimeState(),
    val settings: SettingsState = SettingsState(),
    val setup: SetupState = SetupState(),
    /** 当前底部导航所在的 tab（route 字符串，见 MizuTab） */
    val tab: String = "home",
    /** 设备档案快照，关于页与设置页要展示 */
    val device: com.mizulauncher.compat.DeviceProfile? = null,
    /** 界面日志（环形缓冲，最多 2000 行） */
    val logLines: List<String> = emptyList(),
    /** 全局一次性提示（Snackbar） */
    val toast: Toast? = null,
    /** 全局阻断式错误（对话框） */
    val fatal: FatalError? = null
) {
    data class Toast(val message: String, val id: Long = System.nanoTime(), val isError: Boolean = false)

    /**
     * 底部导航所在的 tab。
     *
     * 存在状态树里而不是 Composable 的 remember 里，是为了让「调试 API 触发的跳转」
     * 和「用户点击的跳转」走同一条路径——否则会出现"API 说切到下载页了，界面没动"。
     */
    fun currentTab(): String = tab
    data class FatalError(val title: String, val detail: String, val hint: String? = null)
}

/**
 * 首页：一大卡片 + 版本列表。
 *
 * [featured] 是顶部大卡片展示的实例（最近玩的或置顶的），
 * 与 [instances] 里的同一对象不重复渲染——列表里会把它排除。
 */
data class HomeState(
    val instances: List<InstanceCard> = emptyList(),
    val loading: Boolean = true,
    /** 首次进入且一个实例都没有时为 true，展示引导页而不是空列表 */
    val empty: Boolean = false,
    val selectedId: String? = null,
    /** 启动中的实例 id。非空时大卡片切换为"启动中"形态 */
    val launchingId: String? = null,
    val launchStage: String = "",
    val launchProgress: Float = 0f,
    val error: String? = null
) {
    /** 顶部大卡片：优先当前选中，其次最近游玩 */
    val featured: InstanceCard?
        get() = instances.firstOrNull { it.meta.id == selectedId }
            ?: instances.maxByOrNull { it.meta.lastPlayed }

    /** 列表里要排除的 id（大卡片已经展示过的那个） */
    val restOfInstances: List<InstanceCard>
        get() {
            val f = featured ?: return instances
            return instances.filter { it.meta.id != f.meta.id }
        }
}

/**
 * 一个实例在界面上的全部信息。
 *
 * 把"元数据 + 体积 + 皮肤纹理状态"打包在一起，是因为它们总是一起变化：
 * 装完版本后体积变了、皮肤也可能刚拉到。分成三个流会让 UI 短暂显示
 * "新体积 + 旧皮肤"，出现视觉跳变。
 */
data class InstanceCard(
    val meta: InstanceMeta,
    /** 磁盘占用，0 表示尚未测量 */
    val sizeBytes: Long = 0L,
    /** 是否需要更新 Java（版本要求的 major 与实例配置不一致） */
    val javaMismatch: Boolean = false,
    /** 该实例角色的皮肤纹理 */
    val skin: TextureState = TextureState.Idle
)

/** 下载页 */
data class DownloadState(
    val versions: List<VersionEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val query: String = "",
    val filter: VersionFilter = VersionFilter.Release,
    /** 正在下载/安装的版本 id → 进度 */
    val tasks: Map<String, InstallTask> = emptyMap(),
    /** 展开的版本（点击卡片后显示加载器选择） */
    val expandedId: String? = null
) {
    val visibleVersions: List<VersionEntry>
        get() {
            val filtered = when (filter) {
                VersionFilter.Release -> versions.filter { it.type == "release" }
                VersionFilter.Snapshot -> versions.filter { it.type == "snapshot" }
                VersionFilter.All -> versions
            }
            if (query.isBlank()) return filtered
            val q = query.trim().lowercase()
            return filtered.filter { it.id.lowercase().contains(q) }
        }

    val activeTaskCount: Int get() = tasks.count { it.value.phase.isActive }
}

enum class VersionFilter { Release, Snapshot, All }

/**
 * 一次安装任务。
 *
 * phase 是有限的几个枚举而不是任意字符串：
 * 旧版用 `MutableStateFlow<String>` 存阶段描述，导致 UI 无法据此改变布局，
 * 只能把字符串原样显示出来——用户看到的是"正在下载 libraries"这种开发者术语。
 */
data class InstallTask(
    val versionId: String,
    val phase: Phase,
    val progress: Float = 0f,
    val detail: String = "",
    val error: String? = null
) {
    enum class Phase {
        Queued,
        Resolving,      // 解析版本 JSON
        Downloading,    // 下载资源
        Extracting,     // 解压 natives
        Installing,     // 安装加载器
        Verifying,      // 校验文件
        Done,
        Failed;

        val isActive: Boolean
            get() = this != Done && this != Failed

        /** 给用户看的中文描述，**不是**开发者术语 */
        val label: String
            get() = when (this) {
                Queued -> "排队中"
                Resolving -> "正在获取版本信息"
                Downloading -> "正在下载游戏文件"
                Extracting -> "正在解压运行库"
                Installing -> "正在安装模组加载器"
                Verifying -> "正在校验文件完整性"
                Done -> "安装完成"
                Failed -> "安装失败"
            }
    }
}

/** 账号页 */
data class AccountState(
    /** 当前登录方式 */
    val kind: LoginKind = LoginKind.None,
    /** 微软账户档案 */
    val microsoft: MinecraftProfile? = null,
    /** 外置登录账号列表 */
    val yggdrasilAccounts: List<YggdrasilAccount> = emptyList(),
    val busy: Boolean = false,
    /** 设备码流程的中间态 */
    val deviceCode: DeviceCodeState? = null,
    /** 外置登录表单 */
    val form: YggdrasilForm = YggdrasilForm(),
    val error: String? = null,
    /** 当前展示皮肤预览的角色 */
    val previewUuid: String? = null
) {
    data class DeviceCodeState(val userCode: String, val verificationUri: String)
}

enum class LoginKind { None, Microsoft, Yggdrasil, Offline }

data class YggdrasilAccount(
    val apiRoot: String,
    val displayName: String,
    val profileId: String?,
    val profileName: String?,
    val slim: Boolean = false,
    val skin: TextureState = TextureState.Idle
)

data class YggdrasilForm(
    val server: String = "https://littleskin.cn",
    val email: String = "",
    val password: String = "",
    val discovering: Boolean = false,
    /** 探测成功后展示的真实 API 地址。为空表示尚未探测 */
    val apiRoot: String? = null,
    val error: String? = null,
    val hint: String? = null
) {
    val canSubmit: Boolean
        get() = email.isNotBlank() && password.isNotEmpty() && !discovering
}

/** 运行时页 */
data class RuntimeState(
    val states: Map<Int, RuntimeManager.State> = emptyMap(),
    val abi: String = "arm64-v8a",
    val busy: Map<Int, Boolean> = emptyMap()
) {
    val readyCount: Int
        get() = states.values.count { it is RuntimeManager.State.Ready }

    /** 是否有任何一个版本不可用。用于首页顶部的健康提示 */
    val hasUnavailable: Boolean
        get() = states.values.any { it is RuntimeManager.State.Unavailable }
}

/** 设置页 */
data class SettingsState(
    val darkMode: DarkMode = DarkMode.System,
    val dynamicColor: Boolean = false,
    val downloadSource: String = "",
    val runtimeManifestUrl: String = "",
    val appVersion: String = "",
    /** WebView 内核状态，供用户排查"公告页打不开" */
    val webViewStatus: String = ""
)

enum class DarkMode { System, Light, Dark }

/** 首启「补全依赖」 */
data class SetupState(
    val visible: Boolean = false,
    /** 待安装组件（Key 是组件标识，Value 是它在磁盘上的状态） */
    val items: Map<String, BundleInstaller.ItemState> = emptyMap(),
    val phase: BundleInstaller.Phase = BundleInstaller.Phase.Idle,
    val dismissed: Boolean = false
) {
    /** 还没装好的数量。用于决定是否还需要显示这个弹窗 */
    val pendingCount: Int
        get() = items.values.count { it is BundleInstaller.ItemState.Pending }

    val runningLabel: String
        get() = (phase as? BundleInstaller.Phase.Running)?.currentLabel.orEmpty()

    val runningPercent: Int
        get() = (phase as? BundleInstaller.Phase.Running)?.percent ?: 0
}
