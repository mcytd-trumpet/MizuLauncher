package com.mizulauncher

import android.app.Application
import android.content.Context
import com.mizulauncher.auth.AccountSession
import com.mizulauncher.auth.MinecraftProfile
import com.mizulauncher.auth.ProfileCodec
import com.mizulauncher.download.DownloadSource
import com.mizulauncher.instance.InstanceManager
import java.io.File

/**
 * 全局单例容器（不引入 DI 框架，保持依赖简单、构建稳定）。
 *
 * 目录布局（PCL2 风格 + 实例隔离）：
 * ```
 * <externalFilesDir>/MizuLauncher/
 *   ├── instances/<id>/.minecraft/   实例隔离目录
 *   ├── common/libraries|assets/     共享库（省空间）
 *   └── cache/                       版本清单缓存 / 下载临时文件
 * ```
 */
class AppContainer(context: Context) {

    val rootDir: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "MizuLauncher").apply { mkdirs() }
    val cacheDir: File = File(rootDir, "cache").apply { mkdirs() }

    val instanceManager: InstanceManager = InstanceManager(rootDir)

    /** 全局设置（MMKV 风格的 SharedPreferences 简化实现） */
    val settings: SettingsStore = SettingsStore(context)

    /**
     * 当前下载源。多个组件（版本仓库、下载引擎、运行时管理器）都要读它，
     * 放在容器里统一持有，避免各自维护一份导致换源后行为不一致。
     */
    val sourceFlow = kotlinx.coroutines.flow.MutableStateFlow(settings.downloadSource)

    /** 账户会话：微软正版登录 + 令牌续期 + 持久化 */
    val accountSession: AccountSession = AccountSession(
        store = object : AccountSession.ProfileStore {
            override fun load() = ProfileCodec.decode(settings.accountJson)
            override fun save(profile: MinecraftProfile?) {
                settings.accountJson = profile?.let(ProfileCodec::encode)
                // 同步离线显示名，首页头像不至于空着
                if (profile != null) settings.lastAccount = profile.name
            }
        }
    )
}

/**
 * 设置存储。生产环境可替换为 MMKV / DataStore；此处用 SharedPreferences 保证零依赖可编译。
 */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("mizu_settings", Context.MODE_PRIVATE)

    var downloadSource: DownloadSource
        get() = DownloadSource.fromName(prefs.getString(KEY_SOURCE, DownloadSource.Bmclapi.name))
        set(value) = prefs.edit().putString(KEY_SOURCE, value.name).apply()

    var downloadThreads: Int
        get() = prefs.getInt(KEY_THREADS, 6)
        set(value) = prefs.edit().putInt(KEY_THREADS, value.coerceIn(1, 32)).apply()

    var maxMemoryMb: Int
        get() = prefs.getInt(KEY_MEMORY, 1024)
        set(value) = prefs.edit().putInt(KEY_MEMORY, value).apply()

    var renderer: String
        get() = prefs.getString(KEY_RENDERER, "VIRGL") ?: "VIRGL"
        set(value) = prefs.edit().putString(KEY_RENDERER, value).apply()

    var lastAccount: String
        get() = prefs.getString(KEY_ACCOUNT, "Player") ?: "Player"
        set(value) = prefs.edit().putString(KEY_ACCOUNT, value).apply()

    var themeMode: String
        get() = prefs.getString(KEY_THEME, "system") ?: "system"
        set(value) = prefs.edit().putString(KEY_THEME, value).apply()

    var backgroundPath: String?
        get() = prefs.getString(KEY_BG, null)
        set(value) = prefs.edit().putString(KEY_BG, value).apply()

    /**
     * Java 运行时远端清单地址（jre-index.json）。
     * 留空则只用 APK 内置的运行时；填写后可用于补齐未内置的版本（如 32 位的 Java 25）。
     */
    /** 微软账户档案的 JSON（含 refresh_token，勿外传） */
    var accountJson: String?
        get() = prefs.getString(KEY_ACCOUNT_JSON, null)
        set(value) = prefs.edit().putString(KEY_ACCOUNT_JSON, value).apply()

    var runtimeManifestUrl: String?
        get() = prefs.getString(KEY_RUNTIME_MANIFEST, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString(KEY_RUNTIME_MANIFEST, value).apply()

    private companion object {
        const val KEY_SOURCE = "download_source"
        const val KEY_THREADS = "download_threads"
        const val KEY_MEMORY = "max_memory_mb"
        const val KEY_RENDERER = "renderer"
        const val KEY_ACCOUNT = "last_account"
        const val KEY_THEME = "theme_mode"
        const val KEY_BG = "background_path"
        const val KEY_RUNTIME_MANIFEST = "runtime_manifest_url"
        const val KEY_ACCOUNT_JSON = "account_json"
    }
}

class MizuApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        instance = this
        // 注册 Pojav 内核桥接：未引入 submodule 时它自身会判定为不可用，不影响其余功能
        PojavBridgeProvider.factory = { ctx ->
            com.mizulauncher.bridge.PojavBridgeImpl(ctx)
        }
    }

    companion object {
        lateinit var instance: MizuApplication
            private set
    }
}

/** 便捷访问 */
val Context.container: AppContainer get() = MizuApplication.instance.container
