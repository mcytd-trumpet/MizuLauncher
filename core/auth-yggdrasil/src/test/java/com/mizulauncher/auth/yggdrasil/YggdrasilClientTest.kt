package com.mizulauncher.auth.yggdrasil

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Yggdrasil 协议测试。
 *
 * 用注入的 `transport` 伪造服务端响应，不联网。
 * 重点覆盖三类真实踩坑点：
 *  1. 地址规范化（用户填 `littleskin.cn` 也要能用）；
 *  2. 错误分类（密码错 / 网络错 / 地址错 要给不同提示）；
 *  3. 材质 base64 解码（皮肤 URL 藏在 base64 的 JSON 里）。
 */
class YggdrasilClientTest {

    private fun client(handler: (String) -> YggdrasilClient.HttpResp): YggdrasilClient =
        YggdrasilClient(transport = { req ->
            val url = req.url.toString()
            val body = req.body?.let { b ->
                val okio = okio.Buffer()
                b.writeTo(okio)
                okio.readUtf8()
            }.orEmpty()
            handler("$url|$body")
        })

    // ---------------- 地址规范化 ----------------

    @Test
    fun `裸域名自动补 https`() {
        assertEquals("https://littleskin.cn", YggdrasilSpec.normalizeInput("littleskin.cn"))
        assertEquals("https://littleskin.cn", YggdrasilSpec.normalizeInput("  littleskin.cn/  "))
    }

    @Test
    fun `已带协议不改动只去尾斜杠`() {
        assertEquals(
            "https://littleskin.cn/api/yggdrasil",
            YggdrasilSpec.normalizeInput("https://littleskin.cn/api/yggdrasil/")
        )
        assertEquals(
            "http://192.168.1.10:8080/api/yggdrasil",
            YggdrasilSpec.normalizeInput("http://192.168.1.10:8080/api/yggdrasil")
        )
    }

    @Test
    fun `空输入保持为空以便上层报错`() {
        assertEquals("", YggdrasilSpec.normalizeInput("   "))
    }

    @Test
    fun `主机名提取用于展示`() {
        assertEquals("littleskin.cn", YggdrasilSpec.hostOf("https://littleskin.cn/api/yggdrasil"))
    }

    // ---------------- ALI 探测 ----------------

    @Test
    fun `通过 ALI 响应头发现 API 根地址`() {
        val c = YggdrasilClient(transport = { req ->
            YggdrasilClient.HttpResp(
                code = 200,
                body = "<html>站点首页</html>",
                headers = mapOf("X-Authlib-Injector-API-Location" to "/api/yggdrasil")
            )
        })
        val r = c.discoverApiRoot("littleskin.cn")
        assertTrue(r.isSuccess)
        assertEquals("https://littleskin.cn/api/yggdrasil", r.getOrNull())
    }

    @Test
    fun `ALI 为完整地址时原样使用`() {
        val c = YggdrasilClient(transport = {
            YggdrasilClient.HttpResp(
                200, "", mapOf("x-authlib-injector-api-location" to "https://skin.example.com/api/yggdrasil/")
            )
        })
        assertEquals("https://skin.example.com/api/yggdrasil", c.discoverApiRoot("example.com").getOrNull())
    }

    @Test
    fun `已填 API 根地址时识别元信息 JSON`() {
        val c = YggdrasilClient(transport = {
            YggdrasilClient.HttpResp(200, """{"signaturePublickey":"-----BEGIN PUBLIC KEY-----"}""")
        })
        assertEquals(
            "https://littleskin.cn/api/yggdrasil",
            c.discoverApiRoot("https://littleskin.cn/api/yggdrasil").getOrNull()
        )
    }

    @Test
    fun `非 Yggdrasil 地址返回明确错误而不是静默失败`() {
        val c = YggdrasilClient(transport = {
            YggdrasilClient.HttpResp(404, "<html>404 Not Found</html>")
        })
        val r = c.discoverApiRoot("https://example.com")
        assertTrue(r.isFailure)
        assertEquals(
            YggdrasilSpec.FailureKind.NOT_YGGDRASIL,
            r.exceptionOrNull()!!.toFailure().kind
        )
    }

    // ---------------- 登录 ----------------

    private val okAuthBody = """
        {
          "accessToken": "tok_abc",
          "clientToken": "cli_123",
          "availableProfiles": [
            {"id": "1111", "name": "Steve", "model": "default"},
            {"id": "2222", "name": "Alex", "model": "slim"}
          ],
          "selectedProfile": {"id": "1111", "name": "Steve", "model": "default"}
        }
    """.trimIndent()

    @Test
    fun `登录成功解析出会话`() {
        val c = client { YggdrasilClient.HttpResp(200, okAuthBody) }
        val s = c.authenticate("https://littleskin.cn/api/yggdrasil", "a@b.c", "pw").getOrThrow()
        assertEquals("tok_abc", s.accessToken)
        assertEquals("cli_123", s.clientToken)
        assertEquals("Steve", s.selectedProfile?.name)
        assertEquals(2, s.availableProfiles.size)
        // 第二个角色是纤细模型
        assertEquals("slim", s.availableProfiles[1].model)
    }

    @Test
    fun `请求体里 agent 是对象而不是字符串`() {
        var captured = ""
        val c = client { payload ->
            captured = payload
            YggdrasilClient.HttpResp(200, okAuthBody)
        }
        c.authenticate("https://x/api/yggdrasil", "u", "p")
        // 写成 "agent":"Minecraft" 会被服务端 400 拒绝，必须带 version 字段
        assertTrue("agent 必须是对象", captured.contains("\"agent\":{\"name\":\"Minecraft\",\"version\":1}"))
    }

    @Test
    fun `密码错误给出可执行的提示`() {
        val c = client {
            YggdrasilClient.HttpResp(
                403,
                """{"error":"ForbiddenOperationException","errorMessage":"Invalid credentials. Invalid username or password."}"""
            )
        }
        val r = c.authenticate("https://x/api/yggdrasil", "u", "wrong")
        assertTrue(r.isFailure)
        val f = r.exceptionOrNull()!!.toFailure()
        assertEquals(YggdrasilSpec.FailureKind.BAD_CREDENTIALS, f.kind)
        assertTrue("提示里要说明账号是邮箱", f.hint.contains("邮箱"))
    }

    @Test
    fun `token 失效与密码错误要分开`() {
        val c = client {
            YggdrasilClient.HttpResp(
                403,
                """{"error":"ForbiddenOperationException","errorMessage":"Invalid token."}"""
            )
        }
        val f = c.refresh("https://x/api/yggdrasil", "old", "cli").exceptionOrNull()!!.toFailure()
        assertEquals(YggdrasilSpec.FailureKind.TOKEN_EXPIRED, f.kind)
    }

    @Test
    fun `返回网页而不是 JSON 时提示地址填错`() {
        val c = client { YggdrasilClient.HttpResp(200, "<!DOCTYPE html><html>首页</html>") }
        val f = c.authenticate("https://x", "u", "p").exceptionOrNull()!!.toFailure()
        assertEquals(YggdrasilSpec.FailureKind.PROTOCOL, f.kind)
    }

    @Test
    fun `限流要提示稍后再试`() {
        val c = client { YggdrasilClient.HttpResp(429, "") }
        val f = c.authenticate("https://x", "u", "p").exceptionOrNull()!!.toFailure()
        assertTrue("限流提示应含'稍后'", f.message.contains("稍后"))
    }

    @Test
    fun `服务器 5xx 归类为网络问题`() {
        val c = client { YggdrasilClient.HttpResp(503, "") }
        val f = c.authenticate("https://x", "u", "p").exceptionOrNull()!!.toFailure()
        assertEquals(YggdrasilSpec.FailureKind.NETWORK, f.kind)
    }

    @Test
    fun `空用户名不发请求直接报错`() {
        var called = false
        val c = client { called = true; YggdrasilClient.HttpResp(200, okAuthBody) }
        assertTrue(c.authenticate("https://x", "  ", "p").isFailure)
        assertFalse("空用户名不应该浪费一次网络请求", called)
    }

    // ---------------- 校验与作废 ----------------

    @Test
    fun `validate 根据状态码判断`() {
        assertTrue(client { YggdrasilClient.HttpResp(204, "") }
            .validate("https://x", "t", "c"))
        assertFalse(client { YggdrasilClient.HttpResp(403, "") }
            .validate("https://x", "t", "c"))
    }

    // ---------------- 角色档案与材质 ----------------

    @Test
    fun `正确解码 base64 材质拿到皮肤与披风地址`() {
        val texturesJson = """
            {"textures":{"SKIN":{"url":"https://s.example/skin/abc"},"CAPE":{"url":"https://s.example/cape/def"}}}
        """.trimIndent()
        val b64 = Base64.getEncoder().encodeToString(texturesJson.toByteArray())

        val body = """
            {"id":"1111","name":"Steve","properties":[
              {"name":"textures","value":"$b64"}
            ]}
        """.trimIndent()

        val c = client { YggdrasilClient.HttpResp(200, body) }
        val d = c.fetchProfile("https://x/api/yggdrasil", "1111").getOrThrow()
        assertEquals("Steve", d.name)
        assertEquals("https://s.example/skin/abc", d.skinUrl)
        assertEquals("https://s.example/cape/def", d.capeUrl)
    }

    @Test
    fun `未设置材质时返回空材质而不是失败`() {
        // 规范里这种角色返回 204 空体，属于正常情况
        val c = client { YggdrasilClient.HttpResp(204, "") }
        val d = c.fetchProfile("https://x", "1111").getOrThrow()
        assertEquals(null, d.skinUrl)
        assertEquals("1111", d.id)
    }

    @Test
    fun `带连字符的 uuid 会被清理`() {
        var url = ""
        val c = client { payload ->
            url = payload.substringBefore('|')
            YggdrasilClient.HttpResp(204, "")
        }
        c.fetchProfile("https://x/api/yggdrasil", "550e8400-e29b-41d4-a716-446655440000")
        assertTrue("URL 里不能带连字符", url.endsWith("/550e8400e29b41d4a716446655440000"))
    }

    // ---------------- 落盘结构 ----------------

    @Test
    fun `存档结构不包含密码`() {
        val s = YggdrasilSpec.Session(
            apiRoot = "https://x",
            accessToken = "t",
            clientToken = "c",
            selectedProfile = YggdrasilSpec.Profile("1111", "Steve"),
            availableProfiles = emptyList(),
            displayName = "a@b.c"
        )
        val stored = s.toStored()
        assertEquals("t", stored.accessToken)
        assertEquals("Steve", stored.profileName)
        // StoredSession 只有这六个字段，结构上就没有 password 的位置
        assertEquals(6, YggdrasilSpec.StoredSession::class.java.declaredFields.size)
    }
}
