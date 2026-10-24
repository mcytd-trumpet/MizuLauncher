package com.mizulauncher.debugapi

import android.content.Context
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 调试 API 服务。
 *
 * ## 为什么自己撸 HTTP 而不是引框架
 *
 * `com.sun.net.httpserver` 在 android.jar 里**不存在**（编译期就过不去），
 * OkHttp 能起服务但要多引一个依赖且 API 是给客户端设计的，
 * NanoHTTPD/Ktor 都要整个框架。调试旁路只需要 GET/POST + JSON，
 * 手写一个「读请求行 + 读头 + 按 Content-Length 读体」的两百行服务最合适：
 * 零依赖、不受隐藏 API 限制影响、出问题也好查。
 *
 * ## 安全边界（别改成 0.0.0.0）
 *
 *  - 只绑 `127.0.0.1`：外部网络连不进来，必须先在本机做端口转发或直接在本机 shell curl。
 *  - 每次启动**重新生成** 128 位随机 token，进程退出即失效，没有持久凭据可偷。
 *  - token 只写进私有目录 `files/mizu-debug-api.json`，**不打 logcat**，避免被别的应用扫到。
 */
class DebugApiServer(
    private val context: Context,
    private val facade: LauncherFacade,
    /** 0 = 让系统随机分配，避免和其他应用抢端口 */
    private val port: Int = 0
) {

    private var serverSocket: ServerSocket? = null
    private var acceptor: Thread? = null
    private var pool: java.util.concurrent.ExecutorService? = null
    private val running = AtomicBoolean(false)
    private val router = ApiRoutes.build(facade)

    /** 本次进程唯一有效的 token */
    val token: String = generateToken()

    @Volatile
    var boundPort: Int = -1
        private set

    val isRunning: Boolean get() = running.get()

    @Synchronized
    fun start(): Boolean {
        if (running.get()) return true
        return try {
            // 明确绑回环地址。用 InetSocketAddress(port) 在部分 ROM 上会绑到 0.0.0.0，
            // 那等于把调试口开给整个局域网。
            val ss = ServerSocket(port, 16, InetAddress.getByName("127.0.0.1"))
            serverSocket = ss
            boundPort = ss.localPort
            pool = Executors.newFixedThreadPool(4) { r ->
                Thread(r, "mizu-debug-api").apply { isDaemon = true }
            }
            running.set(true)
            acceptor = Thread({ acceptLoop(ss) }, "mizu-debug-accept").apply {
                isDaemon = true
                start()
            }
            writeEndpointFile()
            Log.i(TAG, "调试 API 已启动：http://127.0.0.1:$boundPort${ApiRoutes.PREFIX}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "调试 API 启动失败", e)
            runCatching { serverSocket?.close() }
            serverSocket = null
            pool = null
            running.set(false)
            false
        }
    }

    @Synchronized
    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        runCatching { acceptor?.interrupt() }
        runCatching { pool?.shutdownNow() }
        serverSocket = null
        acceptor = null
        pool = null
        boundPort = -1
        runCatching { endpointFile().delete() }
        Log.i(TAG, "调试 API 已停止")
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running.get()) {
            val socket = try {
                ss.accept()
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "accept 失败：${e.message}")
                break
            }
            runCatching { pool?.execute { serve(socket) } }
                .onFailure { runCatching { socket.close() } }
        }
    }

    // ---------------------------------------------------------------- 单个连接

    private fun serve(socket: Socket) {
        socket.use {
            socket.soTimeout = 15_000
            val startedAt = System.currentTimeMillis()
            try {
                val input = socket.getInputStream().buffered()
                val head = readHead(input) ?: return
                val requestLine = head.first().trim()
                if (requestLine.isEmpty()) return
                val parts = requestLine.split(" ")
                if (parts.size < 2) {
                    writeResponse(socket.getOutputStream(), Router.error(400, "bad_request", "请求行不合法"))
                    return
                }
                val method = parts[0].uppercase()
                val target = parts[1]
                val path = target.substringBefore('?')
                val query = parseQuery(target.substringAfter('?', "").takeIf { '?' in target })
                val headers = head.drop(1).mapNotNull { line ->
                    val idx = line.indexOf(':')
                    if (idx <= 0) null
                    else line.substring(0, idx).trim().lowercase() to line.substring(idx + 1).trim()
                }.toMap()

                val rawBody = readBodyRaw(input, headers)

                // 探活不需要 token：脚本可以先 ping 再决定要不要读凭据
                val needsAuth = path.trimEnd('/') != "${ApiRoutes.PREFIX}/health"

                val encrypted = headers["x-mizu-encrypted"]?.equals("1", true) == true
                if (needsAuth && !authOk(headers, query)) {
                    writeResponse(socket.getOutputStream(), Router.error(401, "unauthorized",
                        "缺少或错误的 token。请求头加 Authorization: Bearer <token>"))
                    return
                }

                // 加密通道：解密 body、带外传的 seq 走同一个 AAD，响应也加密
                val ctx = if (encrypted) openSecure(method, path, headers, rawBody) else null
                val body = ctx?.plainBody ?: rawBody.toString(Charsets.UTF_8)

                val req = Router.Request(method, path, query, body, headers)
                var resp = router.dispatch(req)

                // 只有请求是加密的，响应才加密。这样 curl 调试（明文）不会拿到看不懂的密文。
                val encryptedResponse = ctx != null && method != "HEAD"
                if (encryptedResponse) {
                    resp = ctx!!.sealResponse(resp)
                }

                if (method == "HEAD") {
                    writeResponse(socket.getOutputStream(), Router.Response(resp.code, ""))
                } else {
                    writeResponse(socket.getOutputStream(), resp, encryptedResponse)
                }
            } catch (e: SecureChannelException) {
                // 认证失败一律 400 且不回具体原因：回显「tag mismatch」之类会变成侧信道
                runCatching {
                    writeResponse(socket.getOutputStream(),
                        Router.error(400, "secure_channel_failed", e.publicMessage))
                }
            } catch (e: Exception) {
                runCatching {
                    writeResponse(socket.getOutputStream(),
                        Router.error(500, "internal_error", e.message ?: "未知错误"))
                }
            } finally {
                val cost = System.currentTimeMillis() - startedAt
                if (cost > 2000) Log.w(TAG, "慢请求 耗时 ${cost}ms")
            }
        }
    }

    /**
     * 处理一次加密请求。
     *
     * 明文部分（header）只放三样东西：盐、时间戳、序号。
     * 这三样都不敏感——盐是随机的，时间戳和序号本来就是公开的。
     * **token 本身不出现在任何报文里**，它只参与密钥派生。
     */
    private fun openSecure(
        method: String,
        path: String,
        headers: Map<String, String>,
        body: ByteArray
    ): SecureContext {
        val saltHex = headers["x-mizu-salt"]
            ?: throw SecureChannelException("缺少 x-mizu-salt")
        val tsStr = headers["x-mizu-ts"]
            ?: throw SecureChannelException("缺少 x-mizu-ts")
        val seqStr = headers["x-mizu-seq"]
            ?: throw SecureChannelException("缺少 x-mizu-seq")

        val salt = SecureChannel.hexToBytes(saltHex)
            ?: throw SecureChannelException("x-mizu-salt 不是合法十六进制")
        if (salt.size != SecureChannel.SALT_LEN) {
            throw SecureChannelException("x-mizu-salt 长度必须是 ${SecureChannel.SALT_LEN} 字节")
        }
        val ts = tsStr.toLongOrNull() ?: throw SecureChannelException("x-mizu-ts 不是合法时间戳")
        val seq = seqStr.toLongOrNull() ?: throw SecureChannelException("x-mizu-seq 不是合法序号")

        // 时钟偏移检查：拒绝过期报文，防止重放一周前抓到的包
        val skew = kotlin.math.abs(System.currentTimeMillis() - ts)
        if (skew > SecureChannel.MAX_CLOCK_SKEW_MS) {
            throw SecureChannelException(
                "时间戳偏差 ${skew}ms 超过 ${SecureChannel.MAX_CLOCK_SKEW_MS}ms，请校准设备时间"
            )
        }

        val keys = SecureChannel.deriveKeys(token, salt, ts)
        // AAD 绑定「这是哪个请求」，所以密文不能被挪到别的接口上重放
        val aad = secureAad(seq, method, path)
        val plain = try {
            SecureChannel.decrypt(keys, seq, aad, body)
        } catch (e: Exception) {
            throw SecureChannelException("解密失败（token 不对，或报文被篡改/重放）")
        }
        return SecureContext(keys, seq, method, path, plain.toString(Charsets.UTF_8))
    }

    /**
     * 业务 AAD = `METHOD || "|" || path`。序号前缀由 [SecureChannel] 内部添加。
     *
     * 两件事必须被绑定，否则真的会被重放：
     *  - **方法**：挡住「GET 的密文拿去当 POST 用」
     *  - **路径**：挡住「/health 的密文挪到 /instances 上」——后者会执行写入，危害最大
     *  - **序号**（由 SecureChannel 加）：挡住「把上一个请求的密文原样再发一次」
     *
     * 注意 path **不含 query string**：query 在服务端被解析成 Map 后顺序不定，
     * 不同客户端对参数排序、百分号编码的处理不一致，纳入 AAD 只会制造互通故障。
     */
    private fun secureAad(seq: Long, method: String, path: String): ByteArray =
        method.toByteArray(Charsets.UTF_8) +
            '|'.code.toByte() +
            path.toByteArray(Charsets.UTF_8)

    /** 一次加密请求的上下文，负责把响应也加密回去 */
    private inner class SecureContext(
        val keys: SecureChannel.Keys,
        val seq: Long,
        val method: String,
        val path: String,
        val plainBody: String
    ) {
        fun sealResponse(resp: Router.Response): Router.Response {
            val cipher = SecureChannel.encrypt(
                keys, seq, secureAad(seq, method, path),
                resp.body.toByteArray(Charsets.UTF_8)
            )
            // 响应体是二进制密文，用 hex 编码保证走纯文本 HTTP 不坏字节
            return Router.Response(
                resp.code,
                """{"cipher":"${SecureChannel.bytesToHex(cipher)}"}"""
            )
        }
    }

    /** 读到空行为止，返回请求行 + 头行 */
    private fun readHead(input: InputStream): List<String>? {
        val lines = ArrayList<String>()
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (lines.isEmpty() && sb.isEmpty()) null else lines + sb.toString()
            if (b == '\n'.code) {
                val line = sb.toString().trimEnd('\r')
                sb.setLength(0)
                if (line.isEmpty() && lines.isNotEmpty()) return lines
                lines.add(line)
                if (lines.size > 100) return lines // 防御超大请求头
            } else {
                sb.append(b.toChar())
                // 单行过长直接判死，别让畸形请求吃掉内存
                if (sb.length > 8192) throw IllegalArgumentException("请求头过长")
            }
        }
    }

    private fun readBodyRaw(input: InputStream, headers: Map<String, String>): ByteArray {
        val len = headers["content-length"]?.toIntOrNull() ?: return ByteArray(0)
        if (len <= 0) return ByteArray(0)
        if (len > MAX_BODY) throw IllegalArgumentException("请求体过大（上限 ${MAX_BODY / 1024 / 1024}MB）")
        val buf = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = input.read(buf, read, len - read)
            if (n < 0) break
            read += n
        }
        return if (read == len) buf else buf.copyOf(read)
    }

    private fun writeResponse(out: OutputStream, resp: Router.Response, encrypted: Boolean = false) {
        val bytes = resp.body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 ${resp.code} ${reason(resp.code)}\r\n")
            append("Content-Type: ${resp.contentType}\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            // 客户端靠这个头判断响应体是不是密文
            if (encrypted) append("X-Mizu-Encrypted: 1\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }.toByteArray(Charsets.UTF_8)
        val bos = BufferedOutputStream(out)
        bos.write(head)
        bos.write(bytes)
        bos.flush()
    }

    private fun reason(code: Int): String = when (code) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        500 -> "Internal Server Error"
        else -> "OK"
    }

    private fun authOk(headers: Map<String, String>, query: Map<String, String>): Boolean {
        val auth = headers["authorization"].orEmpty()
        val bearer = auth.removePrefix("Bearer ").removePrefix("bearer ").trim()
        // 也允许 ?token= 查询参数，方便在浏览器里直接点开链接
        val provided = bearer.ifBlank { query["token"].orEmpty() }
        if (provided.isBlank()) return false
        return constantTimeEquals(provided, token)
    }

    /** 定长比较，避免按字节提前返回泄露 token 前缀 */
    private fun constantTimeEquals(a: String, b: String): Boolean {
        val x = a.toByteArray(Charsets.UTF_8)
        val y = b.toByteArray(Charsets.UTF_8)
        if (x.size != y.size) return false
        var diff = 0
        for (i in x.indices) diff = diff or (x[i].toInt() xor y[i].toInt())
        return diff == 0
    }

    private fun parseQuery(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split("&").mapNotNull { pair ->
            if (pair.isBlank()) return@mapNotNull null
            val k = pair.substringBefore('=')
            val v = pair.substringAfter('=', "")
            runCatching {
                URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
            }.getOrNull()
        }.toMap()
    }

    // ---------------------------------------------------------------- 端点信息落盘

    /**
     * 把端口和 token 写到 `files/mizu-debug-api.json`。
     *
     * 外部脚本读这个文件就能知道怎么连。文件在 App 私有目录，
     * 同 UID 的 shell（含 rish / adb run-as）读得到，其它应用读不到。
     */
    private fun writeEndpointFile() {
        val f = endpointFile()
        f.parentFile?.mkdirs()
        val json = """
            {
              "port": $boundPort,
              "host": "127.0.0.1",
              "token": "$token",
              "basePath": "${ApiRoutes.PREFIX}",
              "healthUrl": "http://127.0.0.1:$boundPort${ApiRoutes.PREFIX}/health",
              "pid": ${android.os.Process.myPid()},
              "startedAt": ${System.currentTimeMillis()}
            }
        """.trimIndent()
        f.writeText(json)
        runCatching { f.setReadable(false, false); f.setReadable(true, true) }
    }

    fun endpointFile(): File = File(context.filesDir, "mizu-debug-api.json")

    companion object {
        private const val TAG = "MizuDebugApi"

        /** 请求体上限。加密后的密文会比明文大约 28 字节（nonce 由头传，只有 GCM tag） */
        private const val MAX_BODY = 4 * 1024 * 1024

        /** 128 位随机十六进制。用 SecureRandom，别用 Random */
        fun generateToken(): String {
            val bytes = ByteArray(16)
            java.security.SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        /** 读上次写下的端点信息，外部脚本一次调用就能拿到 port + token */
        fun readEndpoint(context: Context): Map<String, Any?>? {
            val f = File(context.filesDir, "mizu-debug-api.json")
            if (!f.isFile) return null
            return runCatching {
                val obj = com.google.gson.JsonParser.parseString(f.readText()).asJsonObject
                obj.entrySet().associate { (k, v) ->
                    k to (if (v.isJsonPrimitive && v.asJsonPrimitive.isNumber) v.asInt else v.asString)
                }
            }.getOrNull()
        }
    }
}

/**
 * 加密通道失败。
 *
 * [publicMessage] 是**可以回给客户端**的说明；真正的失败原因（比如 GCM tag 不匹配）
 * 不能回显——攻击者能借此逐字节猜密钥。所以这里只保留一句概括，
 * 详细原因由服务端打印到自己的日志里（如果将来需要）。
 */
class SecureChannelException(val publicMessage: String) : Exception(publicMessage)
