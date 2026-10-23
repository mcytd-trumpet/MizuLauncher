package com.mizulauncher.auth

import com.google.gson.Gson
import com.mizulauncher.instance.Account

/**
 * 账户会话：负责「持久化 + 过期前自动续期 + 转成内核要的 Account」。
 *
 * 存储层抽成接口，Android 上落到 SharedPreferences，测试里换成内存实现。
 */
class AccountSession(
    private val store: ProfileStore,
    private val auth: MicrosoftAuth = MicrosoftAuth(),
    private val now: () -> Long = System::currentTimeMillis
) {

    interface ProfileStore {
        fun load(): MinecraftProfile?
        fun save(profile: MinecraftProfile?)
    }

    /** 当前缓存的档案，启动时读一次即可 */
    fun current(): MinecraftProfile? = store.load()

    /**
     * 返回一个可用的档案：
     * - 没登录 → null
     * - 未过期 → 原样返回
     * - 已过期但还有 refresh_token → 续期后返回（并写回）
     * - 续期失败 → 清空并返回 null，让上层引导重新登录
     */
    fun valid(): MinecraftProfile? {
        val saved = store.load() ?: return null
        if (!isExpired(saved)) return saved

        if (saved.refreshToken.isBlank()) {
            store.save(null)
            return null
        }
        return runCatching {
            val ms = auth.refresh(saved.refreshToken)
            val refreshed = auth.loginWithXbox(ms).copy(msClientId = saved.msClientId)
            store.save(refreshed)
            refreshed
        }.getOrElse {
            store.save(null)
            null
        }
    }

    fun save(profile: MinecraftProfile) = store.save(profile)

    fun logout() = store.save(null)

    private fun isExpired(p: MinecraftProfile): Boolean {
        if (p.expiresAtEpochMs <= 0L) return false
        // 提前 5 分钟视为过期，避免启动中途掉线
        return now() > p.expiresAtEpochMs - 5 * 60_000
    }
}

/** 档案 → 内核 Account */
fun MinecraftProfile.toAccount(): Account = Account(
    username = name,
    uuid = uuid,
    accessToken = accessToken,
    userType = userType,
    type = "msa"
)

/**
 * 档案的 JSON 编解码。单独抽出来是为了能在纯 JVM 单测里验证格式稳定，
 * 也避免 SharedPreferences 存散装字段后改一个忘一个。
 */
object ProfileCodec {
    private val gson = Gson()

    fun encode(p: MinecraftProfile): String = gson.toJson(p)

    fun decode(raw: String?): MinecraftProfile? {
        if (raw.isNullOrBlank()) return null
        return runCatching { gson.fromJson(raw, MinecraftProfile::class.java) }
            .getOrNull()
            ?.takeIf { it.name.isNotBlank() && it.accessToken.isNotBlank() }
    }
}
