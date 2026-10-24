package com.mizulauncher.debugapi

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 调试 API 的路由表与分发器。
 *
 * 这一层**不碰 Android、不碰网络**：只负责「路径 + 方法 + 参数 → 调用哪个 handler」。
 * 好处是整张路由表能在纯 JVM 单测里跑（见 `RouterTest`），
 * 不用起真机、不用开端口。
 *
 * 路径参数用 `{name}` 占位，匹配时按段对齐；`/instances/{id}/mods` 这种多段路径同样支持。
 */
class Router(
    private val handlers: Map<RouteKey, Handler>
) {

    /** 一次请求的全部输入 */
    data class Request(
        val method: String,
        val path: String,
        val query: Map<String, String> = emptyMap(),
        val body: String = "",
        val headers: Map<String, String> = emptyMap()
    ) {
        /** 从 JSON body 里取一个字段；body 不是对象时返回 null */
        fun jsonField(name: String): String? = runCatching {
            JsonParser.parseString(body).asJsonObject.get(name)?.takeIf { !it.isJsonNull }?.asString
        }.getOrNull()
    }

    data class Response(
        val code: Int,
        val body: String,
        val contentType: String = "application/json; charset=utf-8"
    )

    /** handler 拿到的上下文：已解析好的路径参数 + 原始请求 */
    class Context(
        val req: Request,
        val pathParams: Map<String, String>
    ) {
        fun path(name: String): String = pathParams[name]
            ?: throw ApiException(400, "bad_request", "缺少路径参数 $name")

        fun query(name: String, default: String? = null): String? =
            req.query[name] ?: default

        fun queryInt(name: String, default: Int): Int =
            req.query[name]?.toIntOrNull() ?: default

        fun queryBool(name: String, default: Boolean): Boolean =
            req.query[name]?.let { it == "true" || it == "1" } ?: default

        /** 读取 JSON body 的必填字符串字段 */
        fun bodyString(name: String): String =
            req.jsonField(name) ?: throw ApiException(400, "bad_request", "请求体缺少字段 $name")

        fun bodyStringOrNull(name: String): String? = req.jsonField(name)

        fun bodyObject(): JsonObject = runCatching {
            JsonParser.parseString(req.body).asJsonObject
        }.getOrElse { throw ApiException(400, "bad_request", "请求体不是合法的 JSON 对象") }

        fun bodyInt(name: String, default: Int): Int =
            req.jsonField(name)?.toIntOrNull() ?: default

        fun bodyBool(name: String, default: Boolean): Boolean =
            req.jsonField(name)?.let { it == "true" || it == "1" } ?: default
    }

    fun interface Handler {
        fun handle(ctx: Context): JsonElement
    }

    data class RouteKey(val method: String, val pattern: String) {
        /** 把 `{id}` 换成捕获组，返回值用来反解路径参数 */
        val regex: Regex = Regex(
            "^" + pattern.split("/").joinToString("/") { seg ->
                if (seg.startsWith("{") && seg.endsWith("}")) "([^/]+)" else Regex.escape(seg)
            } + "$"
        )
        val paramNames: List<String> = pattern.split("/")
            .filter { it.startsWith("{") && it.endsWith("}") }
            .map { it.trim('{', '}') }
    }

    /**
     * 分发一次请求。
     *
     * HEAD 复用 GET（HTTP 语义），其余方法必须精确匹配。
     * 路径存在但方法不对时返回 405 而不是 404 —— 这能让调用方立刻发现是自己写错了方法，
     * 而不是怀疑路径拼错。
     */
    fun dispatch(req: Request): Response {
        val method = req.method.uppercase()
        val path = normalize(req.path)

        val pathExists = handlers.keys.any { it.regex.matches(path) }
        if (!pathExists) {
            return error(404, "not_found", "没有这个接口：$method $path")
        }

        // 先找精确方法，再让 HEAD 回退到 GET
        val candidates = if (method == "HEAD") listOf("HEAD", "GET") else listOf(method)
        for (m in candidates) {
            for ((key, handler) in handlers) {
                if (key.method != m) continue
                val match = key.regex.matchEntire(path) ?: continue
                val params = key.paramNames.mapIndexed { i, name ->
                    name to (match.groupValues[i + 1])
                }.toMap()
                return try {
                    val result = handler.handle(Context(req, params))
                    Response(200, GSON.toJson(okEnvelope(result)))
                } catch (e: ApiException) {
                    error(e.status, e.code, e.message)
                } catch (e: Exception) {
                    error(500, "internal_error", e.message ?: e.javaClass.simpleName)
                }
            }
        }
        return error(405, "method_not_allowed", "$path 不支持 $method，允许的方法：" +
            handlers.keys.filter { it.regex.matches(path) }.joinToString { it.method })
    }

    /** `/a//b/` 这种畸形路径统一成 `/a/b`，避免因为多余斜杠匹配不上 */
    private fun normalize(path: String): String {
        val p = path.substringBefore('?').trim()
        val cleaned = p.split("/").filter { it.isNotEmpty() }.joinToString("/")
        return "/$cleaned"
    }

    companion object {
        private val GSON = Gson()

        /** 统一成功响应：`{"ok":true,"data":...}` */
        fun okEnvelope(data: JsonElement): JsonObject = JsonObject().apply {
            addProperty("ok", true)
            add("data", data)
        }

        fun error(status: Int, code: String, message: String): Response {
            val obj = JsonObject().apply {
                addProperty("ok", false)
                add("error", JsonObject().apply {
                    addProperty("code", code)
                    addProperty("message", message)
                })
            }
            return Response(status, GSON.toJson(obj))
        }

        /** 把任意对象（含 null）转成 JsonElement，方便 handler 直接 return */
        fun toJson(value: Any?): JsonElement = GSON.toJsonTree(value)
    }
}

/**
 * 业务层主动抛的异常。`status` 直接映射 HTTP 状态码。
 */
class ApiException(
    val status: Int,
    val code: String,
    override val message: String
) : Exception(message)
