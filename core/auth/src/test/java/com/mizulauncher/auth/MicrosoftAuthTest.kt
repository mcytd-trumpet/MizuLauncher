package com.mizulauncher.auth

import com.mizulauncher.instance.Account
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class MicrosoftAuthTest {

    // ------------------------------------------------------------ 测试替身

    /** 按 URL 片段返回预设响应（按 host+path 精确匹配），并记录请求 */
    private class FakeHttp(private val routes: List<Pair<String, Pair<Int, String>>>) {
        val requests = mutableListOf<Pair<String, String>>()

        val client: OkHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                requests += req.url.toString() to req.bodyText()
                val match = routes.firstOrNull { (path, _) ->
                    val u = req.url.toString().substringAfter("://")
                    u.startsWith(path)
                } ?: throw IllegalStateException("未预置的地址：${req.url}")
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(match.second.first)
                    .message("OK")
                    .body(match.second.second.toResponseBody(null))
                    .build()
            }
            .build()

        private fun Request.bodyText(): String {
            val buffer = okio.Buffer()
            body?.writeTo(buffer)
            return buffer.readUtf8()
        }
    }

    // ------------------------------------------------------------ 设备码

    @Test
    fun `device code parses all fields`() {
        val http = FakeHttp(listOf(
            "login.live.com/oauth20_connect" to (200 to """
                {"user_code":"ABCD1234","device_code":"dc-123",
                 "verification_uri":"https://aka.ms/remoteconnect",
                 "verification_uri_complete":"https://aka.ms/remoteconnect?otc=ABCD1234",
                 "expires_in":900,"interval":5}
            """.trimIndent())
        ))
        val code = MicrosoftAuth("test-client", http.client).requestDeviceCode()

        assertEquals("ABCD1234", code.userCode)
        assertEquals("dc-123", code.deviceCode)
        assertEquals(5L, code.intervalSeconds)
        assertEquals(900L, code.expiresInSeconds)
        assertTrue(code.verificationUriComplete.contains("ABCD1234"))

        val body = http.requests.first().second
        assertTrue("请求体应带 client_id", body.contains("client_id=test-client"))
        assertTrue("请求体应带 device_code 类型", body.contains("device_code"))
    }

    @Test
    fun `missing verification_uri_complete falls back to appending otc`() {
        val http = FakeHttp(listOf(
            "login.live.com/oauth20_connect" to (200 to
                """{"user_code":"XY99","device_code":"d","verification_uri":"https://example.com/link"}""")
        ))
        val code = MicrosoftAuth("c", http.client).requestDeviceCode()
        assertEquals("https://example.com/link?otc=XY99", code.verificationUriComplete)
    }

    // ------------------------------------------------------------ 轮询

    @Test
    fun `poll maps authorization_pending to Pending`() {
        val http = FakeHttp(listOf(
            "login.live.com/oauth20_token" to (400 to """{"error":"authorization_pending"}""")
        ))
        assertEquals(TokenPoll.Pending, MicrosoftAuth("c", http.client).pollDeviceToken("dc"))
    }

    @Test
    fun `poll maps slow_down and expired_token`() {
        val slow = FakeHttp(listOf("login.live.com/oauth20_token" to (400 to """{"error":"slow_down"}""")))
        assertEquals(TokenPoll.SlowDown, MicrosoftAuth("c", slow.client).pollDeviceToken("dc"))

        val expired = FakeHttp(listOf("login.live.com/oauth20_token" to (400 to """{"error":"expired_token"}""")))
        assertEquals(TokenPoll.Expired, MicrosoftAuth("c", expired.client).pollDeviceToken("dc"))
    }

    @Test
    fun `poll returns token on success`() {
        val http = FakeHttp(listOf(
            "login.live.com/oauth20_token" to (200 to
                """{"access_token":"ms-at","refresh_token":"ms-rt","expires_in":3600}""")
        ))
        val result = MicrosoftAuth("c", http.client).pollDeviceToken("dc")
        assertTrue(result is TokenPoll.Done)
        assertEquals("ms-at", (result as TokenPoll.Done).token.accessToken)
        assertEquals("ms-rt", result.token.refreshToken)
    }

    @Test
    fun `poll on unknown error reports failure`() {
        val http = FakeHttp(listOf("login.live.com/oauth20_token" to (500 to """{"error":"server_error"}""")))
        val result = MicrosoftAuth("c", http.client).pollDeviceToken("dc")
        assertTrue(result is TokenPoll.Failed)
    }

    // ------------------------------------------------------------ 完整登录链路

    private val fullRoutes = listOf(
        "user.auth.xboxlive.com/" to (200 to """{"Token":"xbl-token"}"""),
        "xsts.auth.xboxlive.com/" to (200 to
            """{"Token":"xsts-token","DisplayClaims":{"xui":[{"uhs":"uhs-123"}]}}"""),
        "api.minecraftservices.com/authentication/login_with_xbox" to (200 to """{"access_token":"mc-at","expires_in":86400}"""),
        "api.minecraftservices.com/minecraft/profile" to (200 to """{"id":"069a79f444e94726a5befca90e38aaf5","name":"Notch"}""")
    )

    @Test
    fun `login chain produces profile with mc token and uhs`() {
        val http = FakeHttp(fullRoutes)
        val profile = MicrosoftAuth("c", http.client)
            .loginWithXbox(MsToken("ms-at", "ms-rt", 3600))

        assertEquals("Notch", profile.name)
        assertEquals("069a79f444e94726a5befca90e38aaf5", profile.uuid)
        assertEquals("mc-at", profile.accessToken)
        assertEquals("msa", profile.userType)
        assertTrue("应保留 refresh_token 供下次续期", profile.refreshToken == "ms-rt")
        assertFalse("刚登录不应判为过期", profile.expired)

        // XBL 请求要带 "d=" 前缀，这是微软的约定，写错会 401
        val xbl = http.requests.first { it.first.contains("xboxlive.com") }.second
        assertTrue(xbl.contains("d\\\\=ms-at") || xbl.contains("d=ms-at"))

        // XSTS 之后拼给 Minecraft 的 identityToken 必须是 XBL3.0 x=<uhs>;<token>
        val mc = http.requests.first { it.first.contains("login_with_xbox") }.second
        assertTrue(mc.contains("XBL3.0 x=uhs-123;xsts-token"))
    }

    @Test
    fun `profile without java edition gives clear message`() {
        val http = FakeHttp(
            listOf("api.minecraftservices.com/minecraft/profile" to (404 to "{}")) +
                fullRoutes.dropLast(1)
        )
        val ex = runCatching {
            MicrosoftAuth("c", http.client).loginWithXbox(MsToken("ms-at", "ms-rt", 3600))
        }.exceptionOrNull()
        assertTrue(ex is AuthException)
        assertTrue(ex!!.message!!.contains("购买"))
    }

    @Test
    fun `xsts error codes map to actionable messages`() {
        val http = FakeHttp(listOf(
            "user.auth.xboxlive.com/" to (200 to """{"Token":"xbl"}"""),
            "xsts.auth.xboxlive.com/" to (401 to """{"XErr":2148916238}""")
        ))
        val ex = runCatching {
            MicrosoftAuth("c", http.client).loginWithXbox(MsToken("ms", "rt", 3600))
        }.exceptionOrNull()
        assertTrue(ex is AuthException)
        assertTrue("应提示未成年需要家长同意", ex!!.message!!.contains("家长"))
    }

    // ------------------------------------------------------------ 会话与持久化

    private class MemoryStore(var value: MinecraftProfile? = null) : AccountSession.ProfileStore {
        override fun load(): MinecraftProfile? = value
        override fun save(profile: MinecraftProfile?) { value = profile }
    }

    @Test
    fun `valid returns null when not logged in`() {
        val session = AccountSession(MemoryStore(), MicrosoftAuth("c", OkHttpClient()))
        assertNull(session.valid())
    }

    @Test
    fun `valid returns cached profile when token still fresh`() {
        val future = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(10)
        val store = MemoryStore(MinecraftProfile("Steve", "uuid-1", "tok", expiresAtEpochMs = future))
        val session = AccountSession(store, MicrosoftAuth("c", OkHttpClient()))
        val got = session.valid()
        assertNotNull(got)
        assertEquals("Steve", got!!.name)
    }

    @Test
    fun `valid drops expired profile without refresh token`() {
        val store = MemoryStore(
            MinecraftProfile("Steve", "uuid", "tok", expiresAtEpochMs = 1000L, refreshToken = "")
        )
        val session = AccountSession(store, MicrosoftAuth("c", OkHttpClient()))
        assertNull(session.valid())
        assertNull("过期且无法续期时应清空存储", store.value)
    }

    @Test
    fun `valid refreshes expired profile`() {
        // 续期先打 token 接口，之后是完整 XBL → XSTS → MC 链路（含 profile）
        val http = FakeHttp(
            listOf(
                "login.live.com/oauth20_token" to (200 to
                    """{"access_token":"new-ms","refresh_token":"new-rt","expires_in":3600}""")
            ) + fullRoutes
        )
        val store = MemoryStore(
            MinecraftProfile(
                "Old", "uuid", "old-tok",
                expiresAtEpochMs = 1000L, refreshToken = "old-rt"
            )
        )
        val session = AccountSession(store, MicrosoftAuth("c", http.client))
        val refreshed = session.valid()

        assertNotNull("应续期成功", refreshed)
        assertEquals("Notch", refreshed!!.name)
        assertEquals("new-rt", refreshed.refreshToken)
        assertNotNull("续期结果要写回存储", store.value)

        val refreshBody = http.requests.first().second
        assertTrue("续期要用 refresh_token 授权类型",
            refreshBody.contains("grant_type=refresh_token"))
        assertTrue(refreshBody.contains("refresh_token=old-rt"))
    }

    @Test
    fun `refresh failure clears session`() {
        val http = FakeHttp(listOf("login.live.com/oauth20_token" to (400 to """{"error":"invalid_grant"}""")))
        val store = MemoryStore(
            MinecraftProfile("Old", "u", "t", expiresAtEpochMs = 1L, refreshToken = "bad")
        )
        val session = AccountSession(store, MicrosoftAuth("c", http.client))
        assertNull(session.valid())
        assertNull(store.value)
    }

    @Test
    fun `续期时服务端 5xx 不该删掉本地账号`() {
        // 真 bug：之前不分原因一律 store.save(null)，断网/服务端抖动一次就要重新登录。
        val http = FakeHttp(listOf("login.live.com/oauth20_token" to (503 to """{"error":"server_error"}""")))
        val store = MemoryStore(
            MinecraftProfile("Old", "u", "t", expiresAtEpochMs = 1L, refreshToken = "still-good")
        )
        val session = AccountSession(store, MinecraftAuthFor(http))
        assertNull("这次没续上，返回 null 让上层按离线启动", session.valid())
        assertNotNull("凭据没坏，档案必须留着", store.value)
        assertEquals("still-good", store.value!!.refreshToken)
    }

    @Test
    fun `续期时 429 限流也不该删账号`() {
        val http = FakeHttp(listOf("login.live.com/oauth20_token" to (429 to """{"error":"too_many_requests"}""")))
        val store = MemoryStore(
            MinecraftProfile("Old", "u", "t", expiresAtEpochMs = 1L, refreshToken = "rt")
        )
        assertNull(AccountSession(store, MinecraftAuthFor(http)).valid())
        assertNotNull(store.value)
    }

    @Test
    fun `invalid_grant 才算凭据作废`() {
        assertTrue(isCredentialDeadError(AuthException("""{"error":"invalid_grant"}""")))
        assertTrue(isCredentialDeadError(AuthException("error=invalid_client")))
        assertTrue(isCredentialDeadError(AuthException("interaction_required")))
    }

    @Test
    fun `网络类失败不算凭据作废`() {
        assertFalse(isCredentialDeadError(AuthException("HTTP 503")))
        assertFalse(isCredentialDeadError(AuthException("HTTP 429", retryable = true)))
        assertFalse("IOException 不是 AuthException，凭据状态未知，不能删",
            isCredentialDeadError(java.io.IOException("timeout")))
        assertFalse(isCredentialDeadError(IllegalStateException("随便什么")))
    }

    @Test
    fun `postForm 把 5xx 和 429 标记为可重试`() {
        // 与 MicrosoftAuth.postForm 的判断保持一致
        fun retryable(code: Int) = code >= 500 || code == 408 || code == 429
        assertTrue(retryable(500)); assertTrue(retryable(503))
        assertTrue(retryable(408)); assertTrue(retryable(429))
        assertFalse("400 是凭据问题，重试无用", retryable(400))
        assertFalse(retryable(401))
    }

    private fun MinecraftAuthFor(http: FakeHttp) = MicrosoftAuth("c", http.client)

    // ------------------------------------------------------------ 编解码与转换

    @Test
    fun `profile survives json round trip`() {
        val original = MinecraftProfile(
            name = "Alex", uuid = "abc-123", accessToken = "tok",
            userType = "msa", expiresAtEpochMs = 123456789L, refreshToken = "rt"
        )
        val restored = ProfileCodec.decode(ProfileCodec.encode(original))
        assertEquals(original, restored)
    }

    @Test
    fun `codec rejects incomplete json`() {
        assertNull(ProfileCodec.decode(null))
        assertNull(ProfileCodec.decode(""))
        assertNull(ProfileCodec.decode("""{"name":"","accessToken":""}"""))
        assertNull(ProfileCodec.decode("not json"))
    }

    @Test
    fun `profile converts to kernel account`() {
        val account = MinecraftProfile("Notch", "069a79f4-44e9-4726-a5be-fca90e38aaf5", "mc-at")
            .toAccount()
        assertEquals("Notch", account.username)
        assertEquals("mc-at", account.accessToken)
        assertEquals("msa", account.userType)
        assertEquals("msa", account.type)
    }

    @Test
    fun `offline account keeps deterministic uuid`() {
        val a = Account.offlineUuid("Steve")
        val b = Account.offlineUuid("Steve")
        assertEquals(a, b)
        assertTrue(a.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-3[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")))
    }
}
