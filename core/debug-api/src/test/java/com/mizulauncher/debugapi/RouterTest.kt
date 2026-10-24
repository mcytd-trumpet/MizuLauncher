package com.mizulauncher.debugapi

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 路由层测试。
 *
 * 这一层的价值在于：**文档里列出的每个接口，都要能在这里打到**。
 * 之前吃过「文档写了、代码没有」的亏，用测试把两边钉在一起。
 */
class RouterTest {

    // ---------------------------------------------------------------- 假实现

    private class FakeFacade : LauncherFacade {
        val calls = mutableListOf<String>()
        var lastBody: Map<String, Any?> = emptyMap()
        var lastId: String? = null
        var lastDeleteFiles: Boolean = true
        var lastSourceId: String? = null
        var lastOfflineName: String? = null
        var lastLogLines: Int = 0
        var lastForce: Boolean = false

        override fun diagnostics() = mapOf<String, Any?>("versionName" to "1.0.1", "abi" to "arm64-v8a")

        override fun listInstances() = listOf(
            mapOf<String, Any?>("id" to "abc", "name" to "1.20.1", "versionId" to "1.20.1")
        )

        override fun getInstance(id: String): Map<String, Any?>? {
            lastId = id
            return if (id == "abc") mapOf<String, Any?>("id" to "abc", "name" to "1.20.1") else null
        }

        override fun createInstance(body: Map<String, Any?>): Map<String, Any?> {
            calls += "createInstance"
            lastBody = body
            return mapOf<String, Any?>("id" to "new", "versionId" to body["versionId"])
        }

        override fun updateInstance(id: String, body: Map<String, Any?>): Map<String, Any?> {
            calls += "updateInstance"
            lastId = id
            lastBody = body
            return mapOf<String, Any?>("id" to id)
        }

        override fun deleteInstance(id: String, deleteFiles: Boolean): Boolean {
            lastId = id
            lastDeleteFiles = deleteFiles
            return id == "abc"
        }

        override fun prepareInstance(id: String): Boolean {
            lastId = id
            return true
        }

        override fun launchInstance(id: String): Map<String, Any?> {
            lastId = id
            return mapOf<String, Any?>("launched" to true)
        }

        override fun taskStatus() = mapOf<String, Any?>("running" to false)

        override fun cancelTask() = true

        override fun listVersions(force: Boolean): List<Map<String, Any?>> {
            lastForce = force
            return listOf(mapOf<String, Any?>("id" to "1.20.1", "type" to "release"))
        }

        override fun listSources() = listOf(mapOf<String, Any?>("id" to "bmclapi", "name" to "BMCLAPI"))

        override fun setSource(id: String): Map<String, Any?> {
            lastSourceId = id
            return mapOf<String, Any?>("id" to id)
        }

        override fun scanBundles() = mapOf<String, Any?>("pending" to 0, "items" to emptyList<Any>())

        override fun installBundles() = true

        override fun listRuntimes() = listOf(mapOf<String, Any?>("major" to 17, "ready" to true))

        override fun accountStatus() = mapOf<String, Any?>("loggedIn" to false)

        override fun startLogin() = mapOf<String, Any?>("userCode" to "ABCD-EFGH", "expiresIn" to 900)

        override fun cancelLogin() = true

        override fun logout() = true

        override fun setOfflineName(name: String): Boolean {
            lastOfflineName = name
            return true
        }

        override fun checkUpdate() = mapOf<String, Any?>("hasUpdate" to false)

        override fun getSettings() = mapOf<String, Any?>("maxMemoryMb" to 2048)

        override fun updateSettings(body: Map<String, Any?>): Map<String, Any?> {
            lastBody = body
            return body
        }

        override fun recentLogs(lines: Int): Map<String, Any?> {
            lastLogLines = lines
            return mapOf<String, Any?>("lines" to emptyList<String>())
        }
    }

    private val facade = FakeFacade()
    private val router = ApiRoutes.build(facade)

    private fun call(
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        body: String = ""
    ): Router.Response = router.dispatch(Router.Request(method, ApiRoutes.PREFIX + path, query, body))

    private fun data(resp: Router.Response): String {
        assertEquals("期望 200，实际 ${resp.code}：${resp.body}", 200, resp.code)
        return resp.body
    }

    // ---------------------------------------------------------------- 契约一致性

    @Test
    fun `文档里列出的每个接口都真的存在`() {
        // 这是本测试最重要的断言：防止文档和实现脱节
        ApiRoutes.ENDPOINTS.forEach { (method, path, _) ->
            val relative = path.removePrefix(ApiRoutes.PREFIX)
            // 把 {id} 换成占位值
            val concrete = relative.replace(Regex("\\{[^}]+}"), "abc")
            val resp = router.dispatch(Router.Request(method, ApiRoutes.PREFIX + concrete))
            assertFalse(
                "接口 $method $path 在路由表里不存在（返回 ${resp.code}）",
                resp.code == 404
            )
            assertFalse(
                "接口 $method $path 方法不允许（返回 405，说明路径有但方法没挂）",
                resp.code == 405
            )
        }
    }

    @Test
    fun `接口数量与清单一致`() {
        assertEquals(27, ApiRoutes.ENDPOINTS.size)
        val desc = ApiRoutes.describe()
        assertEquals(27, desc.get("count").asInt)
        assertEquals(27, desc.getAsJsonArray("endpoints").size())
    }

    @Test
    fun `清单里没有重复的 方法加路径`() {
        val keys = ApiRoutes.ENDPOINTS.map { it.first to it.second }
        assertEquals("存在重复注册", keys.size, keys.distinct().size)
    }

    // ---------------------------------------------------------------- 探活与自描述

    @Test
    fun `health 返回服务标识`() {
        val body = data(call("GET", "/health"))
        assertTrue(body.contains("\"ok\":true"))
        assertTrue(body.contains("mizulauncher-debug-api"))
    }

    @Test
    fun `routes 返回完整清单`() {
        val body = data(call("GET", "/routes"))
        assertTrue(body.contains("\"count\":27"))
    }

    // ---------------------------------------------------------------- 实例 CRUD

    @Test
    fun `列出实例`() {
        val body = data(call("GET", "/instances"))
        assertTrue(body.contains("\"id\":\"abc\""))
    }

    @Test
    fun `查询不存在的实例返回 404`() {
        val resp = call("GET", "/instances/not-exist")
        assertEquals(404, resp.code)
        assertTrue(resp.body.contains("instance_not_found"))
    }

    @Test
    fun `创建实例会把 body 交给实现`() {
        val body = data(call("POST", "/instances", body = """{"versionId":"1.20.1","name":"我的世界"}"""))
        assertTrue(body.contains("\"versionId\":\"1.20.1\""))
        assertEquals("1.20.1", facade.lastBody["versionId"])
        assertEquals("我的世界", facade.lastBody["name"])
    }

    @Test
    fun `创建实例时数字字段解析成数字`() {
        data(call("POST", "/instances", body = """{"versionId":"1.20.1","javaMajor":21}"""))
        assertEquals(21.0, facade.lastBody["javaMajor"])
    }

    @Test
    fun `部分更新实例`() {
        data(call("PATCH", "/instances/abc", body = """{"name":"改名了"}"""))
        assertEquals("abc", facade.lastId)
        assertEquals("改名了", facade.lastBody["name"])
        assertFalse("未出现的字段不该被填充", facade.lastBody.containsKey("versionId"))
    }

    @Test
    fun `空 body 的 PATCH 等于什么都不改`() {
        data(call("PATCH", "/instances/abc"))
        assertTrue(facade.lastBody.isEmpty())
    }

    @Test
    fun `删除实例默认连文件一起删`() {
        data(call("DELETE", "/instances/abc"))
        assertTrue(facade.lastDeleteFiles)
    }

    @Test
    fun `删除实例可以只移除列表`() {
        data(call("DELETE", "/instances/abc", query = mapOf("deleteFiles" to "false")))
        assertFalse(facade.lastDeleteFiles)
    }

    @Test
    fun `删除不存在的实例返回 404`() {
        assertEquals(404, call("DELETE", "/instances/zzz").code)
    }

    // ---------------------------------------------------------------- 启动链路

    @Test
    fun `准备实例前会先校验存在性`() {
        val resp = call("POST", "/instances/zzz/prepare")
        assertEquals("不存在的实例不该进入下载流程", 404, resp.code)
    }

    @Test
    fun `准备实例返回 started`() {
        val body = data(call("POST", "/instances/abc/prepare"))
        assertTrue(body.contains("\"started\":true"))
    }

    @Test
    fun `启动实例`() {
        val body = data(call("POST", "/instances/abc/launch"))
        assertTrue(body.contains("\"launched\":true"))
        assertEquals("abc", facade.lastId)
    }

    @Test
    fun `启动不存在的实例返回 404`() {
        assertEquals(404, call("POST", "/instances/zzz/launch").code)
    }

    @Test
    fun `任务状态与取消`() {
        assertTrue(data(call("GET", "/task")).contains("\"running\":false"))
        assertTrue(data(call("POST", "/task/cancel")).contains("\"cancelled\":true"))
    }

    // ---------------------------------------------------------------- 版本与源

    @Test
    fun `版本清单默认走缓存`() {
        data(call("GET", "/versions"))
        assertFalse(facade.lastForce)
    }

    @Test
    fun `force=true 时绕过缓存`() {
        data(call("GET", "/versions", query = mapOf("force" to "true")))
        assertTrue(facade.lastForce)
    }

    @Test
    fun `切换下载源`() {
        data(call("POST", "/sources", body = """{"id":"official"}"""))
        assertEquals("official", facade.lastSourceId)
    }

    @Test
    fun `切换下载源缺少 id 返回 400`() {
        val resp = call("POST", "/sources", body = """{}""")
        assertEquals(400, resp.code)
        assertTrue(resp.body.contains("bad_request"))
    }

    // ---------------------------------------------------------------- 依赖与运行时

    @Test
    fun `依赖扫描与补全`() {
        assertTrue(data(call("GET", "/bundles")).contains("\"pending\":0"))
        assertTrue(data(call("POST", "/bundles/install")).contains("\"started\":true"))
    }

    @Test
    fun `运行时列表`() {
        assertTrue(data(call("GET", "/runtimes")).contains("\"major\":17"))
    }

    // ---------------------------------------------------------------- 账户

    @Test
    fun `设备码登录返回验证码`() {
        val body = data(call("POST", "/account/login"))
        assertTrue(body.contains("ABCD-EFGH"))
    }

    @Test
    fun `离线用户名不能为空`() {
        val resp = call("POST", "/account/offline", body = """{"name":"  "}""")
        assertEquals(400, resp.code)
        assertTrue(resp.body.contains("bad_request"))
    }

    @Test
    fun `设置离线用户名`() {
        data(call("POST", "/account/offline", body = """{"name":"Steve"}"""))
        assertEquals("Steve", facade.lastOfflineName)
    }

    // ---------------------------------------------------------------- 设置与日志

    @Test
    fun `修改设置`() {
        data(call("PATCH", "/settings", body = """{"maxMemoryMb":4096}"""))
        assertEquals(4096.0, facade.lastBody["maxMemoryMb"])
    }

    @Test
    fun `日志行数有上限`() {
        data(call("GET", "/logs", query = mapOf("lines" to "99999")))
        assertEquals("上限 2000 行，防止一次拉爆内存", 2000, facade.lastLogLines)

        data(call("GET", "/logs", query = mapOf("lines" to "50")))
        assertEquals(50, facade.lastLogLines)

        data(call("GET", "/logs"))
        assertEquals(200, facade.lastLogLines)
    }

    // ---------------------------------------------------------------- 错误处理

    @Test
    fun `未知路径返回 404`() {
        val resp = call("GET", "/nope")
        assertEquals(404, resp.code)
        assertTrue(resp.body.contains("not_found"))
    }

    @Test
    fun `方法不对返回 405 并提示可用方法`() {
        val resp = call("DELETE", "/versions")
        assertEquals(405, resp.code)
        assertTrue("要告诉调用方 GET 才是对的", resp.body.contains("GET"))
    }

    @Test
    fun `HEAD 复用 GET`() {
        val resp = call("HEAD", "/health")
        assertEquals(200, resp.code)
    }

    @Test
    fun `多余斜杠也能匹配`() {
        assertEquals(200, call("GET", "//health/").code)
    }

    @Test
    fun `非法 JSON body 返回 400`() {
        val resp = call("POST", "/sources", body = "not a json")
        assertEquals(400, resp.code)
    }

    @Test
    fun `实现里抛异常会变成 500 且不泄漏堆栈`() {
        val boom = object : LauncherFacade by FakeFacade() {
            override fun diagnostics(): Map<String, Any?> = throw IllegalStateException("内部炸了")
        }
        val r = ApiRoutes.build(boom).dispatch(
            Router.Request("GET", ApiRoutes.PREFIX + "/diagnostics")
        )
        assertEquals(500, r.code)
        assertTrue(r.body.contains("内部炸了"))
        assertFalse("不该把堆栈塞进响应", r.body.contains("at com.mizulauncher"))
    }

    @Test
    fun `错误响应结构固定`() {
        val resp = call("GET", "/nope")
        val obj = JsonParser.parseString(resp.body).asJsonObject
        assertFalse(obj.get("ok").asBoolean)
        assertNotNull(obj.getAsJsonObject("error"))
        assertNotNull(obj.getAsJsonObject("error").get("code"))
        assertNotNull(obj.getAsJsonObject("error").get("message"))
    }

    @Test
    fun `成功响应统一包在 data 里`() {
        val body = call("GET", "/health").body
        val obj = JsonParser.parseString(body).asJsonObject
        assertTrue(obj.get("ok").asBoolean)
        assertNotNull(obj.get("data"))
    }
}
