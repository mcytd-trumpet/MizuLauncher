package com.mizulauncher.auth.yggdrasil

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Yggdrasil 客户端。
 *
 * 设计原则：**只做协议，不做状态**。所有失败都通过 `Result.failure(Failure)` 返回，
 * 不抛异常、不读写文件、不依赖 Android Context —— 因此能在 JVM 单测里被完整覆盖。
 * 会话持久化交给上层 AccountStore，UI 状态编排交给 ViewModel。
 */
class YggdrasilClient(
    private val client: OkHttpClient = defaultClient(),
    /** 便于测试注入：给定请求，返回响应 */
    private val transport: ((Request) -> HttpResp)? = null
) {

    companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        const val USER_AGENT = "MizuLauncher/2.0 (Yggdrasil)"

        /** Minecraft 的 agent 描述，所有请求体都要带 */
        private fun agent(): JsonObject = JsonObject().apply {
            addProperty("name", "Minecraft")
            addProperty("version", 1)
        }
    }

    data class HttpResp(val code: Int, val body: String, val headers: Map<String, String> = emptyMap())

    // ---------------------------------------------------------------- 地址探测

    /**
     * 探测真实的 API 根地址。
     *
     * 站点启用 ALI 时会返回 `X-Authlib-Injector-API-Location` 响应头，
     * 值是相对路径或绝对地址。这是唯一可靠的探测方式：
     *  - 用户填 `https://littleskin.cn` → 头里给 `/api/yggdrasil`；
     *  - 用户已填 `/api/yggdrasil` → 头里通常不再返回，此时原样使用。
     */
    fun discoverApiRoot(input: String): Result<String> {
        val normalized = YggdrasilSpec.normalizeInput(input)
        if (normalized.isEmpty()) {
            return fail(YggdrasilSpec.FailureKind.NOT_YGGDRASIL, "请填写认证服务器地址")
        }

        val probe = execute(
            Request.Builder()
                .url(normalized)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .get()
                .build()
        ) ?: return fail(YggdrasilSpec.FailureKind.NETWORK, "无法连接 $normalized")

        // 1) ALI 响应头
        probe.headers.entries
            .firstOrNull { it.key.equals("X-Authlib-Injector-API-Location", ignoreCase = true) }
            ?.value
            ?.let { return Result.success(resolveLocation(normalized, it)) }

        // 2) 返回体本身已是 Yggdrasil 元信息
        if (probe.body.contains("signaturePublickey")) {
            return Result.success(normalized)
        }

        // 3) 试探 Blessing Skin 系的标准路径
        val guess = "$normalized/api/yggdrasil"
        val guessProbe = execute(
            Request.Builder()
                .url(guess)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .get()
                .build()
        )
        if (guessProbe != null && (
                guessProbe.headers.keys.any { it.equals("X-Authlib-Injector-API-Location", true) } ||
                    guessProbe.body.contains("signaturePublickey")
                )
        ) {
            return Result.success(guess)
        }

        return fail(
            YggdrasilSpec.FailureKind.NOT_YGGDRASIL,
            "未在 $normalized 找到 Yggdrasil API 位置指示"
        )
    }

    private fun resolveLocation(base: String, location: String): String {
        if (location.startsWith("http://") || location.startsWith("https://")) {
            return location.trimEnd('/')
        }
        return runCatching {
            val uri = java.net.URI(base)
            val path = if (location.startsWith("/")) location else "/$location"
            "${uri.scheme}://${uri.authority}$path".trimEnd('/')
        }.getOrDefault("$base/${location.trimStart('/')}".trimEnd('/'))
    }

    // ---------------------------------------------------------------- 登录

    /**
     * 用户名密码登录。
     *
     * @param clientToken 复用已有客户端令牌；不传则新建。同一客户端复用可以减少站点的会话记录。
     */
    fun authenticate(
        apiRoot: String,
        username: String,
        password: String,
        clientToken: String = newClientToken()
    ): Result<YggdrasilSpec.Session> {
        if (username.isBlank() || password.isEmpty()) {
            return fail(YggdrasilSpec.FailureKind.BAD_CREDENTIALS, "请填写账号与密码")
        }

        val body = JsonObject().apply {
            add("agent", agent())
            addProperty("username", username)
            addProperty("password", password)
            addProperty("clientToken", clientToken)
            addProperty("requestUser", false)
        }

        val resp = post("$apiRoot/authserver/authenticate", body.toString())
            ?: return fail(YggdrasilSpec.FailureKind.NETWORK, "无法连接认证服务器")
        if (resp.code !in 200..299) {
            return Result.failure(CodedException(classify(resp.code, resp.body)))
        }
        return parseSession(apiRoot, resp.body, clientToken, username)
    }

    /** 续期。站点可能签发新 token，必须用返回值覆盖本地 */
    fun refresh(apiRoot: String, accessToken: String, clientToken: String): Result<YggdrasilSpec.Session> {
        val body = JsonObject().apply {
            add("agent", agent())
            addProperty("accessToken", accessToken)
            addProperty("clientToken", clientToken)
            addProperty("requestUser", false)
        }
        val resp = post("$apiRoot/authserver/refresh", body.toString())
            ?: return fail(YggdrasilSpec.FailureKind.NETWORK, "无法连接认证服务器")
        if (resp.code !in 200..299) {
            return Result.failure(CodedException(classify(resp.code, resp.body)))
        }
        return parseSession(apiRoot, resp.body, clientToken, "")
    }

    /** 校验 token 是否仍有效 */
    fun validate(apiRoot: String, accessToken: String, clientToken: String): Boolean {
        val body = JsonObject().apply {
            addProperty("accessToken", accessToken)
            addProperty("clientToken", clientToken)
        }
        val resp = post("$apiRoot/authserver/validate", body.toString())
        return resp != null && resp.code in 200..299
    }

    /** 主动作废 token。失败也应继续清理本地，因此不返回结果 */
    fun invalidate(apiRoot: String, accessToken: String, clientToken: String) {
        val body = JsonObject().apply {
            addProperty("accessToken", accessToken)
            addProperty("clientToken", clientToken)
        }
        post("$apiRoot/authserver/invalidate", body.toString())
    }

    /**
     * 拉取角色档案（含材质地址）。
     *
     * 材质 URL 带 `uploadable` 签名会过期（通常 48 小时），所以每次都要重新拉，不能长期缓存。
     */
    fun fetchProfile(apiRoot: String, uuid: String): Result<ProfileDetail> {
        val clean = uuid.replace("-", "")
        val resp = execute(
            Request.Builder()
                .url("$apiRoot/sessionserver/session/minecraft/profile/$clean")
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
        ) ?: return fail(YggdrasilSpec.FailureKind.NETWORK, "无法连接认证服务器")

        if (resp.code == 204) {
            // 未设置材质时按规范返回 204 空体
            return Result.success(ProfileDetail(clean, "", null, null))
        }
        if (resp.code !in 200..299) {
            return Result.failure(CodedException(classify(resp.code, resp.body)))
        }

        val root = runCatching { JsonParser.parseString(resp.body).asJsonObject }
            .getOrElse {
                return fail(YggdrasilSpec.FailureKind.PROTOCOL, "角色档案解析失败")
            }

        val name = root.get("name")?.asString.orEmpty()
        var skin: String? = null
        var cape: String? = null

        root.getAsJsonArray("properties")?.forEach { el ->
            val obj = el.asJsonObject
            if (obj.get("name")?.asString == "textures") {
                // value 是 base64 编码的 JSON。
                // 用 JDK 的 Base64 而非 android.util.Base64：后者在 JVM 单测里是空桩（返回 null），
                // 会让这段逻辑无法被测试覆盖。
                runCatching {
                    val decoded = String(
                        java.util.Base64.getDecoder().decode(obj.get("value").asString)
                    )
                    val textures = JsonParser.parseString(decoded)
                        .asJsonObject.getAsJsonObject("textures")
                    skin = textures.getAsJsonObject("SKIN")?.get("url")?.asString
                    cape = textures.getAsJsonObject("CAPE")?.get("url")?.asString
                }
            }
        }

        return Result.success(ProfileDetail(clean, name, skin, cape))
    }

    data class ProfileDetail(
        val id: String,
        val name: String,
        val skinUrl: String?,
        val capeUrl: String?
    )

    // ---------------------------------------------------------------- 内部

    fun newClientToken(): String = UUID.randomUUID().toString().replace("-", "")

    private fun parseSession(
        apiRoot: String,
        body: String,
        clientToken: String,
        displayName: String
    ): Result<YggdrasilSpec.Session> {
        val root = runCatching { JsonParser.parseString(body).asJsonObject }
            .getOrElse { return fail(YggdrasilSpec.FailureKind.PROTOCOL, "登录响应不是有效 JSON") }

        val token = root.get("accessToken")?.asString
            ?: return fail(YggdrasilSpec.FailureKind.PROTOCOL, "响应缺少 accessToken")

        fun parseProfile(o: JsonObject) = YggdrasilSpec.Profile(
            id = o.get("id")?.asString.orEmpty(),
            name = o.get("name")?.asString.orEmpty(),
            model = o.get("model")?.asString ?: "default"
        )

        val selected = root.getAsJsonObject("selectedProfile")?.let { parseProfile(it) }
        val available = root.getAsJsonArray("availableProfiles")
            ?.map { parseProfile(it.asJsonObject) }
            .orEmpty()

        return Result.success(
            YggdrasilSpec.Session(
                apiRoot = apiRoot,
                accessToken = token,
                clientToken = root.get("clientToken")?.asString ?: clientToken,
                selectedProfile = selected,
                availableProfiles = available,
                displayName = displayName.ifBlank { selected?.name.orEmpty() }
            )
        )
    }

    private fun post(url: String, json: String): HttpResp? = execute(
        Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .post(json.toRequestBody(JSON))
            .build()
    )

    private fun execute(req: Request): HttpResp? {
        transport?.let { hook -> return runCatching { hook(req) }.getOrNull() }
        return runCatching {
            client.newCall(req).execute().use { r ->
                HttpResp(
                    code = r.code,
                    body = r.body?.string().orEmpty(),
                    headers = r.headers.toMap()
                )
            }
        }.getOrNull()
    }

    /**
     * 把 HTTP 错误翻译成人话。
     *
     * Yggdrasil 约定：
     *  - 401/403 + `ForbiddenOperationException`：账号密码错，或 token 失效；
     *  - 400 + `IllegalArgumentException`：参数不合法（多为空用户名）；
     *  - 429：触发限流（LittleSkin 对连续失败有冷却）。
     */
    private fun classify(code: Int, body: String): YggdrasilSpec.Failure {
        val errorName = Regex("\"error\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
        val errorMsg = Regex("\"errorMessage\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)

        return when {
            code == 401 || code == 403 -> {
                when {
                    errorName == "ForbiddenOperationException" &&
                        errorMsg?.contains("token", true) == true ->
                        YggdrasilSpec.Failure(YggdrasilSpec.FailureKind.TOKEN_EXPIRED, "登录凭证已失效")

                    else -> YggdrasilSpec.Failure(
                        YggdrasilSpec.FailureKind.BAD_CREDENTIALS,
                        errorMsg ?: "账号或密码错误"
                    )
                }
            }
            code == 400 -> YggdrasilSpec.Failure(
                YggdrasilSpec.FailureKind.BAD_CREDENTIALS,
                errorMsg ?: "请求参数不合法"
            )
            code == 429 -> YggdrasilSpec.Failure(
                YggdrasilSpec.FailureKind.PROTOCOL,
                "操作过于频繁，请稍后再试"
            )
            code in 500..599 -> YggdrasilSpec.Failure(
                YggdrasilSpec.FailureKind.NETWORK,
                "认证服务器暂时不可用（HTTP $code）"
            )
            // 返回 HTML 说明填的地址不是 API，而是网站首页
            body.trimStart().startsWith("<") -> YggdrasilSpec.Failure(
                YggdrasilSpec.FailureKind.NOT_YGGDRASIL,
                "服务器返回了网页而不是数据，地址可能填错了"
            )
            else -> YggdrasilSpec.Failure(
                YggdrasilSpec.FailureKind.PROTOCOL,
                errorMsg ?: "认证失败（HTTP $code）"
            )
        }
    }

    private fun <T> fail(kind: YggdrasilSpec.FailureKind, msg: String): Result<T> =
        Result.failure(CodedException(YggdrasilSpec.Failure(kind, msg)))
}

/**
 * Result 只能携带 Throwable，所以把结构化的 [YggdrasilSpec.Failure] 包一层。
 * 调用方用 [toFailure] 取回结构化信息给 UI 展示。
 */
class CodedException(val failure: YggdrasilSpec.Failure) : Exception(failure.message)

/** 从任意异常里提取结构化失败信息；识别不出就归为协议错误 */
fun Throwable.toFailure(): YggdrasilSpec.Failure = when (this) {
    is CodedException -> failure
    else -> YggdrasilSpec.Failure(
        YggdrasilSpec.FailureKind.PROTOCOL,
        message ?: "未知错误"
    )
}
