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
     * - 续期失败且**凭据确实作废** → 清空并返回 null，让上层引导重新登录
     * - 续期失败但只是没网 → 保留档案，返回 null，本次按离线启动
     *
     * 最后一条是之前漏掉的：`runCatching { }.getOrElse { store.save(null) }` 不分原因，
     * 用户在地铁里断一下网，回桌面就发现正版账号被踢下线、要重新念一遍设备码。
     */
    fun valid(): MinecraftProfile? {
        val saved = store.load() ?: return null
        if (!isExpired(saved)) return saved

        if (saved.refreshToken.isBlank()) {
            store.save(null)
            return null
        }
        return try {
            val ms = auth.refresh(saved.refreshToken)
            val refreshed = auth.loginWithXbox(ms).copy(msClientId = saved.msClientId)
            store.save(refreshed)
            refreshed
        } catch (e: Exception) {
            if (isCredentialDead(e)) {
                store.save(null)
            }
            // 网络类失败：档案留着，下次有网还能接着续
            null
        }
    }

    /**
     * 判断这次失败是不是「refresh_token 真的不能用了」。
     *
     * 只有明确的鉴权错误才算。超时、DNS 失败、连接被重置、5xx 都不算——
     * 那些情况下凭据还是好的，删掉纯属误伤。
     */
    private fun isCredentialDead(e: Throwable): Boolean = isCredentialDeadError(e)

    fun save(profile: MinecraftProfile) = store.save(profile)

    fun logout() = store.save(null)

    private fun isExpired(p: MinecraftProfile): Boolean {
        if (p.expiresAtEpochMs <= 0L) return false
        // 提前 5 分钟视为过期，避免启动中途掉线
        return now() > p.expiresAtEpochMs - 5 * 60_000
    }
}

/** 档案 → 内核 Account */fun MinecraftProfile.toAccount(): Account = Account(
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

/**
 * 这次续期失败，是不是「refresh_token 真的作废了」？
 *
 * 判据只看 OAuth 的**错误码**，不看 HTTP 状态码：
 *  - `invalid_grant`：refresh_token 过期/被撤销/被改密码顶掉 → 只能重新登录
 *  - `invalid_client`：client_id 配置错了 → 重试也没用
 *  - `interaction_required`：需要用户交互 → 同样得走登录流程
 * 其余（超时、DNS、连接重置、5xx、429）都是暂时的，凭据应该保留。
 */
internal fun isCredentialDeadError(e: Throwable): Boolean {
    val auth = e as? AuthException ?: return false
    if (auth.retryable) return false
    val msg = auth.message.orEmpty()
    return msg.contains("invalid_grant", ignoreCase = true) ||
        msg.contains("invalid_client", ignoreCase = true) ||
        msg.contains("interaction_required", ignoreCase = true)
}
