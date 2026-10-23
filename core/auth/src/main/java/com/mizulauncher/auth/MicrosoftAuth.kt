package com.mizulauncher.auth

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 微软账户登录（设备码流程）→ Xbox Live → XSTS → Minecraft Services。
 *
 * ## 为什么选设备码流程而不是内嵌浏览器
 *
 * 设备码流程（Device Code Flow）不依赖 WebView、不需要注册重定向 URI，
 * 用户在任何有浏览器的设备上完成登录即可。这个工程明确不用 WebView，
 * 设备码是唯一干净的选择。
 *
 * ## 链路
 *
 * ```
 * 1. 拿设备码  POST /devicecode           → user_code + 让用户去 aka.ms/remoteconnect
 * 2. 轮询令牌  POST /token                → MS Access Token（含 refresh_token）
 * 3. XBL      POST user.auth.xboxlive.com  → Xbox Live Token
 * 4. XSTS     POST xsts.auth.xboxlive.com  → XSTS Token + UserHash
 * 5. MC 认证  POST api.minecraftservices.com/authentication/login_with_xbox
 *                                         → Minecraft accessToken（真正给游戏的）
 * 6. 查档案  GET  /minecraft/profile      → 正版 UUID + 用户名
 * ```
 *
 * ## 第三方客户端说明
 *
 * Minecraft Services 要求调用方带一个 client id。这里默认用 PojavLauncher
 * 长期公开使用的那个，它属于 Pojav 项目而非本项目；如果你的分发场景需要
 * 独立的 client id，在 Azure 门户注册后传进构造函数即可。
 */
class MicrosoftAuth(
    private val clientId: String = DEFAULT_CLIENT_ID,
    private val http: OkHttpClient = defaultClient()
) {

    companion object {
        /**
         * PojavLauncher 公开使用的 Azure 应用 ID。
         * 换成自己的只需 `MicrosoftAuth(clientId = "...")`。
         */
        const val DEFAULT_CLIENT_ID = "00000000402b5328"

        private const val SCOPE = "XboxLive.signin offline_access"
        private const val DEVICE_CODE_URL =
            "https://login.live.com/oauth20_connect.srf"
        private const val TOKEN_URL =
            "https://login.live.com/oauth20_token.srf"
        private const val XBL_URL =
            "https://user.auth.xboxlive.com/user/authenticate"
        private const val XSTS_URL =
            "https://xsts.auth.xboxlive.com/xsts/authorize"
        private const val MC_LOGIN_URL =
            "https://api.minecraftservices.com/authentication/login_with_xbox"
        private const val MC_PROFILE_URL =
            "https://api.minecraftservices.com/minecraft/profile"
        private const val MC_ENTITLEMENTS_URL =
            "https://api.minecraftservices.com/entitlements/mcstore"

        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    // ---------------------------------------------------------------- 步骤 1

    /**
     * 申请设备码。返回的 [DeviceCode] 里带 `userCode`（给用户念的）、
     * `verificationUri`（让用户打开的地址）和 `intervalSeconds`（轮询间隔）。
     */
    fun requestDeviceCode(): DeviceCode {
        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("scope", SCOPE)
            .add("response_type", "device_code")
            .build()

        val json = postForm(DEVICE_CODE_URL, body)
        val uri = json.str("verification_uri") ?: "https://aka.ms/remoteconnect"
        return DeviceCode(
            deviceCode = json.str("device_code") ?: error("设备码响应缺少 device_code"),
            userCode = json.str("user_code") ?: error("设备码响应缺少 user_code"),
            verificationUri = uri,
            // 微软新版返回 verification_uri_complete，直接带用户码，体验更好
            verificationUriComplete = json.str("verification_uri_complete")
                ?: "$uri?otc=${json.str("user_code")}",
            expiresInSeconds = json.longOr("expires_in", 900L),
            intervalSeconds = json.longOr("interval", 5L)
        )
    }

    // ---------------------------------------------------------------- 步骤 2

    /**
     * 轮询拿 MS 令牌。
     *
     * 调用方负责按 [DeviceCode.intervalSeconds] 的节奏重复调用；
     * `authorization_pending` 会返回 [TokenPoll.Pending]，让上层继续等。
     */
    fun pollDeviceToken(deviceCode: String): TokenPoll {
        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
            .add("device_code", deviceCode)
            .build()

        val resp = execute(TOKEN_URL, body)
        if (resp.code == 200) {
            val json = JsonParser.parseString(resp.body).asJsonObject
            return TokenPoll.Done(
                MsToken(
                    accessToken = json.str("access_token")!!,
                    refreshToken = json.str("refresh_token") ?: "",
                    expiresInSeconds = json.longOr("expires_in", 3600L)
                )
            )
        }
        // 400 + error 字段
        val err = runCatching {
            JsonParser.parseString(resp.body).asJsonObject.str("error")
        }.getOrNull()
        return when (err) {
            "authorization_pending" -> TokenPoll.Pending
            "slow_down" -> TokenPoll.SlowDown
            "expired_token" -> TokenPoll.Expired
            "authorization_declined" -> TokenPoll.Declined
            else -> TokenPoll.Failed("登录失败（HTTP ${resp.code}）：${err ?: resp.body.take(200)}")
        }
    }

    /** 用 refresh_token 续期，避免每次启动都要用户重新念验证码 */
    fun refresh(refreshToken: String): MsToken {
        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("scope", SCOPE)
            .build()
        val json = postForm(TOKEN_URL, body)
        return MsToken(
            accessToken = json.str("access_token")!!,
            refreshToken = json.str("refresh_token") ?: refreshToken,
            expiresInSeconds = json.longOr("expires_in", 3600L)
        )
    }

    // ---------------------------------------------------------------- 步骤 3~6

    /**
     * 把 MS 令牌换成能进游戏的 [MinecraftProfile]。
     * 这是完整链路，正常情况下应该在整个登录流程末尾调一次。
     */
    fun loginWithXbox(msToken: MsToken): MinecraftProfile {
        val xbl = xboxLiveAuthenticate(msToken.accessToken)
        val xsts = xstsAuthorize(xbl)
        val mc = minecraftAuthenticate(xsts)
        val profile = fetchProfile(mc.accessToken)
        return profile.copy(
            accessToken = mc.accessToken,
            expiresAtEpochMs = System.currentTimeMillis() + mc.expiresInSeconds * 1000,
            refreshToken = msToken.refreshToken,
            msClientId = clientId
        )
    }

    private fun xboxLiveAuthenticate(msAccessToken: String): String {
        val payload = JsonObject().apply {
            add("Properties", JsonObject().apply {
                addProperty("AuthMethod", "RPS")
                addProperty("SiteName", "user.auth.xboxlive.com")
                addProperty("RpsTicket", "d=$msAccessToken")
            })
            addProperty("RelyingParty", "http://auth.xboxlive.com")
            addProperty("TokenType", "JWT")
        }
        val json = postJson(XBL_URL, payload)
        return json.str("Token") ?: throw AuthException("Xbox Live 认证未返回 Token")
    }

    private fun xstsAuthorize(xblToken: String): XstsToken {
        val payload = JsonObject().apply {
            add("Properties", JsonObject().apply {
                addProperty("SandboxId", "RETAIL")
                add("UserTokens", com.google.gson.JsonArray().apply { add(xblToken) })
            })
            addProperty("RelyingParty", "rp://api.minecraftservices.com/")
            addProperty("TokenType", "JWT")
        }
        val resp = postJsonRaw(XSTS_URL, payload)
        if (resp.first != 200) {
            // 401 + XErr 是最常见的失败：账号没有 Xbox 档案 / 未成年未获家长同意
            val xerr = runCatching {
                JsonParser.parseString(resp.second).asJsonObject.longOr("XErr", 0L)
            }.getOrDefault(0L)
            throw AuthException(xstsErrorMessage(xerr))
        }
        val json = JsonParser.parseString(resp.second).asJsonObject
        val token = json.str("Token") ?: throw AuthException("XSTS 未返回 Token")
        val uhs = json.obj("DisplayClaims")
            ?.array("xui")?.firstOrNull()
            ?.str("uhs")
            ?: throw AuthException("XSTS 未返回 UserHash")
        return XstsToken(token, uhs)
    }

    private fun minecraftAuthenticate(xsts: XstsToken): McToken {
        val payload = JsonObject().apply {
            addProperty("identityToken", "XBL3.0 x=${xsts.userHash};${xsts.token}")
        }
        val resp = postJsonRaw(MC_LOGIN_URL, payload)
        if (resp.first != 200) {
            throw AuthException(
                "Minecraft 认证失败（HTTP ${resp.first}）：${resp.second.take(200)}"
            )
        }
        val json = JsonParser.parseString(resp.second).asJsonObject
        return McToken(
            accessToken = json.str("access_token") ?: throw AuthException("未返回 Minecraft accessToken"),
            expiresInSeconds = json.longOr("expires_in", 86400L)
        )
    }

    private fun fetchProfile(mcAccessToken: String): MinecraftProfile {
        val req = Request.Builder()
            .url(MC_PROFILE_URL)
            .header("Authorization", "Bearer $mcAccessToken")
            .get()
            .build()
        val (code, body) = executeRaw(req)
        if (code == 404) {
            // 有正版账号但没买 Java 版：能登录，不能进游戏
            throw AuthException("这个微软账号还没有购买 Minecraft Java 版")
        }
        if (code != 200) {
            throw AuthException("获取玩家档案失败（HTTP $code）")
        }
        val json = JsonParser.parseString(body).asJsonObject
        return MinecraftProfile(
            name = json.str("name") ?: throw AuthException("档案缺少用户名"),
            uuid = json.str("id") ?: throw AuthException("档案缺少 UUID"),
            accessToken = mcAccessToken,
            userType = "msa",
            expiresAtEpochMs = 0L,
            refreshToken = "",
            msClientId = clientId
        )
    }

    /** 判断账号是否拥有 Java 版（用于在购买前给出明确提示） */
    fun hasJavaEntitlement(mcAccessToken: String): Boolean {
        val req = Request.Builder()
            .url(MC_ENTITLEMENTS_URL)
            .header("Authorization", "Bearer $mcAccessToken")
            .get()
            .build()
        val (code, body) = executeRaw(req)
        if (code != 200) return false
        val items = runCatching {
            JsonParser.parseString(body).asJsonObject.array("items")
        }.getOrNull() ?: return false
        return items.any { it.str("name") == "product_minecraft" }
    }

    // ---------------------------------------------------------------- 内部

    private fun xstsErrorMessage(xerr: Long): String = when (xerr) {
        2148916233L -> "这个微软账号还没有 Xbox 档案，请先去 xbox.com 创建"
        2148916235L -> "你所在的地区不支持 Xbox Live"
        2148916236L -> "账号需要完成年龄验证"
        2148916237L -> "账号需要完成年龄验证"
        2148916238L -> "账号未满 18 岁，需要家长把账号加入家庭组"
        else -> "XSTS 认证失败（XErr=$xerr）"
    }

    private fun postForm(url: String, body: FormBody): JsonObject {
        val req = Request.Builder().url(url).post(body).build()
        val (code, text) = executeRaw(req)
        if (code != 200) throw AuthException("请求 $url 失败（HTTP $code）：${text.take(200)}")
        return JsonParser.parseString(text).asJsonObject
    }

    private fun postJson(url: String, payload: JsonObject): JsonObject {
        val (code, text) = postJsonRaw(url, payload)
        if (code != 200) throw AuthException("请求 $url 失败（HTTP $code）：${text.take(200)}")
        return JsonParser.parseString(text).asJsonObject
    }

    private fun postJsonRaw(url: String, payload: JsonObject): Pair<Int, String> {
        val req = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody(JSON))
            .build()
        return executeRaw(req)
    }

    private fun execute(url: String, body: FormBody): RawResponse {
        val (code, text) = executeRaw(Request.Builder().url(url).post(body).build())
        return RawResponse(code, text)
    }

    private fun executeRaw(req: Request): Pair<Int, String> {
        http.newCall(req).execute().use { resp ->
            return resp.code to (resp.body?.string() ?: "")
        }
    }

    private data class RawResponse(val code: Int, val body: String)
}

// ------------------------------------------------------------------ 数据模型

data class DeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val verificationUriComplete: String,
    val expiresInSeconds: Long,
    val intervalSeconds: Long
)

data class MsToken(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Long
)

data class XstsToken(val token: String, val userHash: String)

data class McToken(val accessToken: String, val expiresInSeconds: Long)

sealed interface TokenPoll {
    data object Pending : TokenPoll
    data object SlowDown : TokenPoll
    data object Expired : TokenPoll
    data object Declined : TokenPoll
    data class Done(val token: MsToken) : TokenPoll
    data class Failed(val message: String) : TokenPoll
}

/**
 * 登录成功后的档案。字段直接对齐 Pojav 内核需要的 `Account`。
 *
 * [refreshToken] 与 [msClientId] 是续期用的，不参与启动参数，
 * 但必须持久化，否则下次启动又要用户念一遍验证码。
 */
data class MinecraftProfile(
    // 显式无参构造：Gson 走 Unsafe 反序列化时不会执行默认值初始化，
    // 给个空构造并让字段有初值，避免读回来一堆 null
    val name: String = "",
    val uuid: String = "",
    val accessToken: String = "",
    val userType: String = "msa",
    val expiresAtEpochMs: Long = 0L,
    val refreshToken: String = "",
    val msClientId: String = MicrosoftAuth.DEFAULT_CLIENT_ID
) {
    val expired: Boolean
        get() = expiresAtEpochMs > 0 && System.currentTimeMillis() > expiresAtEpochMs - 60_000
}

class AuthException(message: String) : Exception(message)

// ------------------------------------------------------------------ Gson 小工具

internal fun JsonObject.str(key: String): String? =
    if (has(key) && !get(key).isJsonNull) get(key).asString else null

internal fun JsonObject.longOr(key: String, fallback: Long): Long =
    if (has(key) && !get(key).isJsonNull) runCatching { get(key).asLong }.getOrDefault(fallback) else fallback

internal fun JsonObject.obj(key: String): JsonObject? =
    if (has(key) && get(key).isJsonObject) get(key).asJsonObject else null

internal fun JsonObject.array(key: String): List<JsonObject>? =
    if (has(key) && get(key).isJsonArray) {
        get(key).asJsonArray.mapNotNull { if (it.isJsonObject) it.asJsonObject else null }
    } else null
