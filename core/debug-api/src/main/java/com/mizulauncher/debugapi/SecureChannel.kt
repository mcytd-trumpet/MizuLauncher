package com.mizulauncher.debugapi

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 端到端加密层。
 *
 * ## 为什么不是 Diffie-Hellman
 *
 * 通道是回环地址，窃听威胁来自**同机的其他进程**，不是链路。DH 的安全性依赖
 * 「攻击者无法同时冒充两端」，但在这里攻击者可以直接扮演客户端去和服务端握手，
 * 从而自己拿到一份会话密钥。所以 DH 在这个场景下不解决真问题。
 *
 * 真正该做的是：**让 token 成为派生密钥的必要输入**。没有 token 就算不出密钥，
 * 攻击者既读不了也伪造不了。
 *
 * ## 密钥协商
 *
 * 客户端不传密钥，双方各自用同一份材料算：
 *
 * ```
 * salt      = 16 字节随机，客户端生成，明文随请求带上
 * timestamp = 毫秒，明文带上（用于拒绝过期会话）
 * prk       = HMAC-SHA256(key = token, msg = salt || timestamp_be64)
 * encKey    = HMAC-SHA256(key = prk, msg = "mizu-enc" || salt)
 * nonceKey  = HMAC-SHA256(key = prk, msg = "mizu-nonce" || salt)
 * ```
 *
 * 三段派生（encKey 加密、nonceKey 生成 nonce）是为了避免同一密钥既加密又产出 IV——
 * IV 复用是 GCM 的致命错误，一旦发生就等于把明文异或出去了。
 *
 * ## 报文格式（AES-256-GCM）
 *
 * ```
 * nonce      = 12 字节 = HMAC(nonceKey, seq_be64) 的前 12 字节
 * final_aad  = seq_be64 || business_aad
 * ciphertext = AES-GCM(encKey, nonce, plaintext, aad = final_aad)
 * ```
 *
 * **调用方只提供 `business_aad`（业务上下文，如 `"GET|/api/v1/health"`），
 * 序号前缀由本对象内部统一添加**。不要在 business_aad 里再放一遍序号——
 * 那会让协议多出一个「到底加了几次前缀」的隐性约定，跨语言对接时几乎必然踩坑
 * （本项目就为此真实踩过一次）。
 *
 * AAD 里绑定了序号、方法和路径，因此攻击者**无法**把 A 请求的密文重放到 B 请求上，
 * 也无法把 GET 的响应挪去冒充 POST。重放旧报文会因序号不符直接认证失败。
 */
object SecureChannel {

    const val SALT_LEN = 16
    const val NONCE_LEN = 12
    const val TAG_BITS = 128

    /** 会话有效期：超过这个时间差的请求直接拒，防止重放陈年报文 */
    const val MAX_CLOCK_SKEW_MS = 60_000L

    private const val LABEL_ENC = "mizu-enc"
    private const val LABEL_NONCE = "mizu-nonce"

    private val random = SecureRandom()

    fun newSalt(): ByteArray = ByteArray(SALT_LEN).also { random.nextBytes(it) }

    /** 一次会话的两把子密钥 */
    data class Keys(val encKey: ByteArray, val nonceKey: ByteArray)

    /**
     * 用 token + salt + 时间戳派生出会话密钥。
     *
     * 同一组 (token, salt, timestamp) 永远得到同一个结果，所以客户端算一次、
     * 服务端算一次，不需要任何密钥交换报文。
     */
    fun deriveKeys(token: String, salt: ByteArray, timestampMs: Long): Keys {
        require(salt.size == SALT_LEN) { "salt 必须是 $SALT_LEN 字节" }
        val tokenBytes = token.toByteArray(Charsets.UTF_8)

        val prk = hmac(tokenBytes, salt + be64(timestampMs))
        return Keys(
            encKey = hmac(prk, LABEL_ENC.toByteArray() + salt),
            nonceKey = hmac(prk, LABEL_NONCE.toByteArray() + salt)
        )
    }

    /**
     * 加密。
     *
     * @param aad 业务上下文，例如 `"GET|/api/v1/health".toByteArray()`。
     *   **不要**自己加序号前缀，[encrypt] 内部会加。
     */
    fun encrypt(keys: Keys, seq: Long, aad: ByteArray, plaintext: ByteArray): ByteArray {
        val nonce = nonceOf(keys.nonceKey, seq)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(keys.encKey, "AES"),
            GCMParameterSpec(TAG_BITS, nonce)
        )
        cipher.updateAAD(aadBytes(seq, aad))
        return cipher.doFinal(plaintext)
    }

    /**
     * 解密。认证失败会抛 [javax.crypto.AEADBadTagException]，
     * 调用方必须当成「拒绝服务」处理，**不要**透传异常信息（会泄露明文相关线索）。
     */
    fun decrypt(keys: Keys, seq: Long, aad: ByteArray, ciphertext: ByteArray): ByteArray {
        val nonce = nonceOf(keys.nonceKey, seq)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(keys.encKey, "AES"),
            GCMParameterSpec(TAG_BITS, nonce)
        )
        cipher.updateAAD(aadBytes(seq, aad))
        return cipher.doFinal(ciphertext)
    }

    private fun nonceOf(nonceKey: ByteArray, seq: Long): ByteArray =
        hmac(nonceKey, be64(seq)).copyOf(NONCE_LEN)

    private fun aadBytes(seq: Long, aad: ByteArray): ByteArray = be64(seq) + aad

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /** 大端 8 字节。两端必须一致，用大端是因为它是网络序，人读起来也顺 */
    fun be64(v: Long): ByteArray {
        val out = ByteArray(8)
        for (i in 0 until 8) out[i] = ((v ushr (56 - 8 * i)) and 0xFF).toByte()
        return out
    }

    fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    fun hexToBytes(hex: String): ByteArray? {
        val clean = hex.trim()
        if (clean.length % 2 != 0) return null
        return runCatching {
            ByteArray(clean.length / 2) { i ->
                clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }.getOrNull()
    }

    /** 定长比较，避免按字节提前返回 */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }
}
