package com.mizulauncher.auth.yggdrasil

/**
 * Yggdrasil 外置认证的数据模型。
 *
 * ## 协议背景
 * Yggdrasil 是 Mojang 早期那套认证服务端协议的社区实现，Blessing Skin（LittleSkin 的底座）
 * 完整兼容它。协议本身只有 6 个端点：
 *
 * | 端点 | 作用 |
 * |---|---|
 * | `POST /authserver/authenticate` | 用户名密码换 accessToken |
 * | `POST /authserver/refresh` | 用过期 token 换新的 |
 * | `POST /authserver/validate` | 校验 token 是否仍有效 |
 * | `POST /authserver/invalidate` | 主动作废 token |
 * | `POST /sessionserver/session/minecraft/join` | 进服时上报 |
 * | `GET /sessionserver/session/minecraft/profile/{uuid}` | 取角色档案与材质 |
 *
 * 以及两个元信息端点（不在标准里，但所有实现都有）：
 * `GET /` 返回站点信息（含 `signaturePublickey`），`GET /api/yggdrasil` 同理。
 *
 * ## 容易踩的坑
 * 1. **`/authserver/authenticate` 的 `Content-Type` 必须是 `application/json`**，
 *    用表单编码会得到 415 或 400，且错误信息是模糊的 "Bad Request"；
 * 2. **`selectedProfile` 的 id 是去掉连字符或带连字符都能接受**，
 *    但返回的 `availableProfiles[].id` 一定是不带连字符的 32 位十六进制；
 * 3. **`accessToken` 有效期极长但会失效**（改密码、管理员吊销、站点重建库），
 *    所以每次启动都要先 `validate`，不要相信本地缓存的"未过期"。
 */
object YggdrasilSpec {

    /** LittleSkin 的 Yggdrasil 根地址 */
    const val LITTLESKIN_ROOT = "https://littleskin.cn/api/yggdrasil"

    /** 常用站点：显示名 → 根地址。用户也可以填任意自定义地址 */
    val PRESETS: List<Pair<String, String>> = listOf(
        "LittleSkin" to LITTLESKIN_ROOT,
        "Blessing Skin 官方社区" to "https://skin.prinzeugen.net/api/yggdrasil"
    )

    /**
     * 规范化用户输入的地址。
     *
     * 用户大概率会直接粘贴 `https://littleskin.cn` 或者干脆只写 `littleskin.cn`。
     * 站点启用了 **ALI（API Location Indication）**，会在根路径的响应头
     * `X-Authlib-Injector-API-Location` 里给出真实 API 地址，所以这里**不猜路径**，
     * 只负责补全协议并去掉多余的 `/`。真正的路径由 [YggdrasilClient.discoverApiRoot] 探测。
     *
     * 为什么不在这里直接把 `/api/yggdrasil` 拼上去：
     * 不同站点的路径不同（有的就是 `/api/yggdrasil`，有的是 `/apiserver`），
     * 硬拼会导致一半站点用不了。
     */
    fun normalizeInput(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return s
        // 用户直接输入域名（含点）或 localhost 时补 https
        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            s = "https://$s"
        }
        return s.trimEnd('/')
    }

    /** 从完整 API 根地址里取出站点展示名（域名部分） */
    fun hostOf(apiRoot: String): String =
        runCatching { java.net.URI(apiRoot).host ?: apiRoot }.getOrDefault(apiRoot)

    /** 标准建档：微软正版账户也用这个结构，便于 UI 统一渲染 */
    data class Profile(
        val id: String,
        val name: String,
        val model: String = "default"
    )

    /** 一次成功认证后的会话 */
    data class Session(
        val apiRoot: String,
        val accessToken: String,
        /** 客户端令牌：同一客户端多次登录应复用，减少站点的会话记录 */
        val clientToken: String,
        val selectedProfile: Profile?,
        val availableProfiles: List<Profile>,
        /** 人类可读的账户标识（邮箱或角色名），仅用于展示 */
        val displayName: String
    ) {
        /** 存档用的紧凑结构，不含明文密码 */
        fun toStored(): StoredSession = StoredSession(
            apiRoot = apiRoot,
            accessToken = accessToken,
            clientToken = clientToken,
            profileId = selectedProfile?.id,
            profileName = selectedProfile?.name,
            displayName = displayName
        )
    }

    /** 落盘结构：**绝不存密码** */
    data class StoredSession(
        val apiRoot: String,
        val accessToken: String,
        val clientToken: String,
        val profileId: String?,
        val profileName: String?,
        val displayName: String
    )

    /**
     * 认证失败的分类。
     *
     * 分类到这一步，UI 才能给出有用的提示而不是"登录失败"四个字：
     * 密码错要提示"检查密码"，网络错要提示"检查网络"，站点填错要提示"换个地址"。
     * 三者用户要做的事完全不同，混在一起说等于没说。
     */
    enum class FailureKind {
        /** 用户名或密码错误（HTTP 403 + error=ForbiddenOperationException） */
        BAD_CREDENTIALS,

        /** 账户被站点封禁 / 需要验证邮箱 */
        ACCOUNT_UNUSABLE,

        /** token 已失效，需要重新登录 */
        TOKEN_EXPIRED,

        /** 网络不可达（DNS / 超时 / 连接被拒） */
        NETWORK,

        /** 服务端返回了非预期内容（HTML 错误页、空响应） */
        PROTOCOL,

        /** 站点地址填错，或该地址不是 Yggdrasil 服务 */
        NOT_YGGDRASIL
    }

    /** 面向用户的错误。携带 kind 与可直接展示的中文说明 */
    data class Failure(val kind: FailureKind, val message: String) {
        /** 给 UI 用的一句话建议，比 message 更具体 */
        val hint: String
            get() = when (kind) {
                FailureKind.BAD_CREDENTIALS -> "请检查邮箱与密码；LittleSkin 的账号是注册邮箱"
                FailureKind.ACCOUNT_UNUSABLE -> "账户当前不可用，请登录站点查看是否需要验证邮箱"
                FailureKind.TOKEN_EXPIRED -> "登录已过期，请重新登录"
                FailureKind.NETWORK -> "无法连接认证服务器，请检查网络或稍后再试"
                FailureKind.PROTOCOL -> "服务器返回了无法识别的内容，请稍后重试"
                FailureKind.NOT_YGGDRASIL -> "这个地址不是 Yggdrasil 认证服务器，请检查是否填写了完整的 API 地址"
            }
    }
}
