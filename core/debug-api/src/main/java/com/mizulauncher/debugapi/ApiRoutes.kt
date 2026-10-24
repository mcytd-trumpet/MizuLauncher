package com.mizulauncher.debugapi

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * 把 [LauncherFacade] 的能力挂到 HTTP 路径上。
 *
 * 所有路由集中在这一个文件里，是为了让「文档」和「代码」不会各说各话：
 * 想加接口只能改这里，改完文档对照一遍即可，不会出现文档里有、代码里没有的幽灵接口。
 *
 * 路径风格：`/api/v1/<资源>`，复数名词，动作用子路径表达
 * （`POST /instances/{id}/launch`）。这样比 `/launchInstance` 更像 REST，
 * 也方便以后按资源做权限。
 */
object ApiRoutes {

    const val PREFIX = "/api/v1"

    fun build(facade: LauncherFacade): Router {
        val routes = HashMap<Router.RouteKey, Router.Handler>()

        fun get(path: String, block: (Router.Context) -> Any?) {
            routes[Router.RouteKey("GET", PREFIX + path)] = Router.Handler { ctx ->
                Router.toJson(block(ctx))
            }
        }

        fun post(path: String, block: (Router.Context) -> Any?) {
            routes[Router.RouteKey("POST", PREFIX + path)] = Router.Handler { ctx ->
                Router.toJson(block(ctx))
            }
        }

        fun patch(path: String, block: (Router.Context) -> Any?) {
            routes[Router.RouteKey("PATCH", PREFIX + path)] = Router.Handler { ctx ->
                Router.toJson(block(ctx))
            }
        }

        fun delete(path: String, block: (Router.Context) -> Any?) {
            routes[Router.RouteKey("DELETE", PREFIX + path)] = Router.Handler { ctx ->
                Router.toJson(block(ctx))
            }
        }

        // ------------------------------------------------------------ 诊断

        /** 服务是否活着。不需要 token，用来探活 */
        get("/health") {
            mapOf("ok" to true, "service" to "mizulauncher-debug-api", "version" to 1)
        }

        get("/diagnostics") { facade.diagnostics() }

        get("/routes") { describe() }

        // ------------------------------------------------------------ 实例

        get("/instances") { facade.listInstances() }

        get("/instances/{id}") { ctx ->
            facade.getInstance(ctx.path("id"))
                ?: throw ApiException(404, "instance_not_found", "实例 ${ctx.path("id")} 不存在")
        }

        post("/instances") { ctx ->
            facade.createInstance(bodyMap(ctx))
        }

        patch("/instances/{id}") { ctx ->
            facade.updateInstance(ctx.path("id"), bodyMap(ctx))
        }

        delete("/instances/{id}") { ctx ->
            val id = ctx.path("id")
            // 默认连文件一起删；?deleteFiles=false 只从列表移除
            val deleteFiles = ctx.queryBool("deleteFiles", true)
            if (!facade.deleteInstance(id, deleteFiles)) {
                throw ApiException(404, "instance_not_found", "实例 $id 不存在或删除失败")
            }
            mapOf("deleted" to true, "id" to id, "deleteFiles" to deleteFiles)
        }

        post("/instances/{id}/prepare") { ctx ->
            val id = ctx.path("id")
            if (facade.getInstance(id) == null) {
                throw ApiException(404, "instance_not_found", "实例 $id 不存在")
            }
            mapOf("started" to facade.prepareInstance(id), "id" to id,
                "hint" to "用 GET /task 查进度")
        }

        post("/instances/{id}/launch") { ctx ->
            val id = ctx.path("id")
            if (facade.getInstance(id) == null) {
                throw ApiException(404, "instance_not_found", "实例 $id 不存在")
            }
            facade.launchInstance(id)
        }

        // ------------------------------------------------------------ 任务

        get("/task") { facade.taskStatus() }

        post("/task/cancel") { mapOf("cancelled" to facade.cancelTask()) }

        // ------------------------------------------------------------ 版本

        get("/versions") { ctx ->
            facade.listVersions(ctx.queryBool("force", false))
        }

        // ------------------------------------------------------------ 下载源

        get("/sources") { facade.listSources() }

        post("/sources") { ctx ->
            facade.setSource(ctx.bodyString("id"))
        }

        // ------------------------------------------------------------ 运行依赖

        get("/bundles") { facade.scanBundles() }

        post("/bundles/install") { mapOf("started" to facade.installBundles(),
            "hint" to "用 GET /task 查进度") }

        get("/runtimes") { facade.listRuntimes() }

        // ------------------------------------------------------------ 账户

        get("/account") { facade.accountStatus() }

        post("/account/login") { facade.startLogin() }

        post("/account/login/cancel") { mapOf("cancelled" to facade.cancelLogin()) }

        post("/account/logout") { mapOf("loggedOut" to facade.logout()) }

        post("/account/offline") { ctx ->
            val name = ctx.bodyString("name")
            if (name.isBlank()) throw ApiException(400, "bad_request", "用户名不能为空")
            mapOf("ok" to facade.setOfflineName(name), "name" to name)
        }

        // ------------------------------------------------------------ 更新

        get("/update") { facade.checkUpdate() }

        // ------------------------------------------------------------ 设置

        get("/settings") { facade.getSettings() }

        patch("/settings") { ctx -> facade.updateSettings(bodyMap(ctx)) }

        // ------------------------------------------------------------ 日志

        get("/logs") { ctx ->
            val n = ctx.queryInt("lines", 200).coerceIn(1, 2000)
            facade.recentLogs(n)
        }

        return Router(routes)
    }

    /** 把请求体解析成字段映射。空 body 视为空对象，方便 PATCH 什么都不改 */
    @Suppress("UNCHECKED_CAST")
    private fun bodyMap(ctx: Router.Context): Map<String, Any?> {
        if (ctx.req.body.isBlank()) return emptyMap()
        val obj = ctx.bodyObject()
        val out = HashMap<String, Any?>()
        for ((k, v) in obj.entrySet()) {
            out[k] = when {
                v.isJsonNull -> null
                v.isJsonPrimitive -> {
                    val p = v.asJsonPrimitive
                    when {
                        p.isBoolean -> p.asBoolean
                        p.isNumber -> p.asDouble
                        else -> p.asString
                    }
                }
                v.isJsonArray -> v.asJsonArray.map { item ->
                    if (item.isJsonPrimitive) item.asString else item.toString()
                }
                else -> v.asString
            }
        }
        return out
    }

    /**
     * 接口清单导出。调试时很有用：不翻源码就知道这台设备开了哪些接口，
     * 也能拿它反过来校验文档有没有写漏。
     */
    fun describe(): JsonObject {
        val arr = JsonArray()
        ENDPOINTS.forEach { (method, path, desc) ->
            arr.add(JsonObject().apply {
                addProperty("method", method)
                addProperty("path", path)
                addProperty("description", desc)
            })
        }
        return JsonObject().apply {
            addProperty("count", ENDPOINTS.size)
            add("endpoints", arr)
        }
    }

    /**
     * 接口清单（同时也是文档里的表格来源）。
     * 三列：方法、路径、说明。
     */
    val ENDPOINTS: List<Triple<String, String, String>> = listOf(
        Triple("GET", "$PREFIX/health", "探活，不需要 token"),
        Triple("GET", "$PREFIX/routes", "列出所有接口"),
        Triple("GET", "$PREFIX/diagnostics", "设备/版本/运行时/任务总览"),
        Triple("GET", "$PREFIX/instances", "实例列表"),
        Triple("GET", "$PREFIX/instances/{id}", "单个实例详情"),
        Triple("POST", "$PREFIX/instances", "创建实例"),
        Triple("PATCH", "$PREFIX/instances/{id}", "部分更新实例"),
        Triple("DELETE", "$PREFIX/instances/{id}", "删除实例"),
        Triple("POST", "$PREFIX/instances/{id}/prepare", "准备文件（异步）"),
        Triple("POST", "$PREFIX/instances/{id}/launch", "启动游戏"),
        Triple("GET", "$PREFIX/task", "当前任务进度"),
        Triple("POST", "$PREFIX/task/cancel", "取消任务"),
        Triple("GET", "$PREFIX/versions", "版本清单"),
        Triple("GET", "$PREFIX/sources", "下载源列表"),
        Triple("POST", "$PREFIX/sources", "切换下载源"),
        Triple("GET", "$PREFIX/bundles", "运行依赖扫描结果"),
        Triple("POST", "$PREFIX/bundles/install", "补全运行依赖（异步）"),
        Triple("GET", "$PREFIX/runtimes", "Java 运行时状态"),
        Triple("GET", "$PREFIX/account", "账户状态"),
        Triple("POST", "$PREFIX/account/login", "开始设备码登录"),
        Triple("POST", "$PREFIX/account/login/cancel", "取消登录"),
        Triple("POST", "$PREFIX/account/logout", "退出登录"),
        Triple("POST", "$PREFIX/account/offline", "设置离线用户名"),
        Triple("GET", "$PREFIX/update", "检查应用更新"),
        Triple("GET", "$PREFIX/settings", "读取设置"),
        Triple("PATCH", "$PREFIX/settings", "修改设置"),
        Triple("GET", "$PREFIX/logs", "最近日志")
    )
}
