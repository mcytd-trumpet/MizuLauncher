package com.mizulauncher.debugapi

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import javax.crypto.AEADBadTagException

/**
 * 加密通道测试。
 *
 * 这些用例覆盖的是「对方写错一个字节就全崩」的部分，最值得钉死：
 *  - 同参数派生出同密钥（客户端/服务端能对上）
 *  - token 不同 → 密钥不同 → 解不开
 *  - 密文改一个字节 → 认证明失败
 *  - AAD 不同（换接口/换序号）→ 解不开，防重放
 *  - 序号不同 → nonce 不同，密文也不同
 */
class SecureChannelTest {

    private val token = "9f3a1c8e5b2d7046ae18c3f92b5d4e07"
    private val salt = ByteArray(16) { it.toByte() }
    private val ts = 1761283200000L

    /**
     * 业务 AAD：`METHOD || '|' || path`。
     *
     * 序号由 [SecureChannel.encrypt] / [SecureChannel.decrypt] 内部统一加前缀，
     * 这里**绝不**再放一遍——放了就变成 `seq || seq || METHOD || path`，
     * 与 Python 参考实现立刻不一致（这是真实踩过的坑，见本文件末尾的互验用例）。
     *
     * 这里刻意不调用服务端那个 private 方法而是照抄一份：如果测试直接复用实现，
     * 实现改了测试也跟着改，就永远测不出协议漂移。
     */
    private fun aad(seq: Long, method: String, path: String): ByteArray =
        "$method|$path".toByteArray(Charsets.UTF_8)

    // ---------------------------------------------------------------- 密钥派生

    @Test
    fun `相同参数派生相同密钥`() {
        val a = SecureChannel.deriveKeys(token, salt, ts)
        val b = SecureChannel.deriveKeys(token, salt, ts)
        assertArrayEquals(a.encKey, b.encKey)
        assertArrayEquals(a.nonceKey, b.nonceKey)
    }

    @Test
    fun `token 不同则密钥不同`() {
        val a = SecureChannel.deriveKeys(token, salt, ts)
        val b = SecureChannel.deriveKeys("wrong-token", salt, ts)
        assertFalse("换了 token 必须换密钥，否则 token 就没意义了",
            a.encKey.contentEquals(b.encKey))
    }

    @Test
    fun `salt 不同则密钥不同`() {
        val salt2 = ByteArray(16) { (it + 1).toByte() }
        val a = SecureChannel.deriveKeys(token, salt, ts)
        val b = SecureChannel.deriveKeys(token, salt2, ts)
        assertFalse(a.encKey.contentEquals(b.encKey))
    }

    @Test
    fun `时间戳不同则密钥不同`() {
        val a = SecureChannel.deriveKeys(token, salt, ts)
        val b = SecureChannel.deriveKeys(token, salt, ts + 1)
        assertFalse("时间戳参与派生，所以旧报文的时间戳算不出当前密钥",
            a.encKey.contentEquals(b.encKey))
    }

    @Test
    fun `加密密钥与 nonce 密钥必须不同`() {
        val k = SecureChannel.deriveKeys(token, salt, ts)
        assertFalse(
            "同一密钥既加密又产 IV 会导致 nonce 复用，GCM 下等于明文泄漏",
            k.encKey.contentEquals(k.nonceKey)
        )
    }

    @Test
    fun `密钥长度是 AES-256`() {
        val k = SecureChannel.deriveKeys(token, salt, ts)
        assertEquals(32, k.encKey.size)
        assertEquals(32, k.nonceKey.size)
    }

    @Test
    fun `salt 长度不对要拒绝`() {
        try {
            SecureChannel.deriveKeys(token, ByteArray(8), ts)
            fail("8 字节 salt 应该被拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("16"))
        }
    }

    // ---------------------------------------------------------------- 加解密

    @Test
    fun `往返一致`() {
        val k = SecureChannel.deriveKeys(token, salt, ts)
        val plain = """{"versionId":"1.20.1","name":"测试"}""".toByteArray()
        val a = aad(1, "POST", "/api/v1/instances")
        val cipher = SecureChannel.encrypt(k, 1, a, plain)
        val back = SecureChannel.decrypt(k, 1, a, cipher)
        assertArrayEquals(plain, back)
        assertFalse("密文不该和明文一样", cipher.contentEquals(plain))
    }

    @Test
    fun `密文比明文长 16 字节的 GCM tag`() {
        val k = SecureChannel.deriveKeys(token, salt, ts)
        val plain = ByteArray(100)
        val cipher = SecureChannel.encrypt(k, 1, aad(1, "GET", "/x"), plain)
        assertEquals(116, cipher.size)
    }

    @Test
    fun `空明文也能加密`() {
        val k = SecureChannel.deriveKeys(token, salt, ts)
        val cipher = SecureChannel.encrypt(k, 1, aad(1, "GET", "/x"), ByteArray(0))
        assertEquals(16, cipher.size)
        assertArrayEquals(ByteArray(0), SecureChannel.decrypt(k, 1, aad(1, "GET", "/x"), cipher))
    }

    @Test
    fun `中文与 emoji 往返正确`() {
        val k = SecureChannel.deriveKeys(token, salt, ts)
        val text = "机械动力 · 存档名 🎮 · \\n 换行"
        val cipher = SecureChannel.encrypt(k, 1, aad(1, "GET", "/x"), text.toByteArray(Charsets.UTF_8))
        val back = String(SecureChannel.decrypt(k, 1, aad(1, "GET", "/x"), cipher), Charsets.UTF_8)
        assertEquals(text, back)
    }

    // ---------------------------------------------------------------- 篡改检测

    @Test
    fun `密文改一个字节就解不开`() {
        val k = SecureChannel.deriveKeys(token, salt, ts)
        val cipher = SecureChannel.encrypt(k, 1, aad(1, "GET", "/x"), "hello".toByteArray())
        cipher[0] = (cipher[0].toInt() xor 0x01).toByte()
        try {
            SecureChannel.decrypt(k, 1, aad(1, "GET", "/x"), cipher)
            fail("篡改后的密文必须解密失败")
        } catch (e: AEADBadTagException) {
            // 预期
        }
    }

    @Test
    fun `换一个 token 解不开`() {
        val a = SecureChannel.deriveKeys(token, salt, ts)
        val b = SecureChannel.deriveKeys("attacker-token", salt, ts)
        val cipher = SecureChannel.encrypt(a, 1, aad(1, "GET", "/x"), "secret".toByteArray())
        try {
            SecureChannel.decrypt(b, 1, aad(1, "GET", "/x"), cipher)
            fail("没有正确 token 就不该解出明文")
        } catch (e: AEADBadTagException) {
            // 预期
        }
    }

    @Test
    fun `换接口路径解不开（防跨接口重放）`() {
        val k = SecureChannel.deriveKeys(token, salt, ts)
        val cipher = SecureChannel.encrypt(k, 1, aad(1, "DELETE", "/api/v1/instances/abc"), "x".toByteArray())
        try {
            // 把删实例的密文挪去查实例
            SecureChannel.decrypt(k, 1, aad(1, "GET", "/api/v1/instances/abc"), cipher)
            fail("AAD 绑定了方法+路径，挪用必须失败")
        } catch (e: AEADBadTagException) {
            // 预期
        }
    }

    @Test
    fun `换序号解不开（防重放）`() {
        val k = SecureChannel.deriveKeys(token, salt, ts)
        val cipher = SecureChannel.encrypt(k, 5, aad(5, "GET", "/x"), "x".toByteArray())
        try {
            SecureChannel.decrypt(k, 6, aad(6, "GET", "/x"), cipher)
            fail("序号参与 nonce + AAD，换号必须失败")
        } catch (e: AEADBadTagException) {
            // 预期
        }
    }

    // ---------------------------------------------------------------- nonce 唯一性

    @Test
    fun `不同序号产生不同密文`() {
        val k = SecureChannel.deriveKeys(token, salt, ts)
        val plain = "same content".toByteArray()
        val c1 = SecureChannel.encrypt(k, 1, aad(1, "GET", "/x"), plain)
        val c2 = SecureChannel.encrypt(k, 2, aad(2, "GET", "/x"), plain)
        assertFalse("相同明文在不同序号下必须是不同密文", c1.contentEquals(c2))
    }

    @Test
    fun `相同明文相同序号密文稳定`() {
        // GCM 是流模式，nonce 定了密文就定了。这保证了「同一请求重发结果一致」，
        // 便于对比调试。安全性由 nonce 不复用保证。
        val k = SecureChannel.deriveKeys(token, salt, ts)
        val plain = "abc".toByteArray()
        val c1 = SecureChannel.encrypt(k, 7, aad(7, "GET", "/x"), plain)
        val c2 = SecureChannel.encrypt(k, 7, aad(7, "GET", "/x"), plain)
        assertArrayEquals(c1, c2)
    }

    // ---------------------------------------------------------------- 编解码工具

    @Test
    fun `十六进制往返`() {
        val bytes = byteArrayOf(0x00, 0x0F, 0x7F, -1, -128, 0x1A)
        val hex = SecureChannel.bytesToHex(bytes)
        assertEquals("000f7fff801a", hex)
        assertArrayEquals(bytes, SecureChannel.hexToBytes(hex))
    }

    @Test
    fun `非法十六进制返回 null`() {
        assertEquals(null, SecureChannel.hexToBytes("abc"))   // 奇数长度
        assertEquals(null, SecureChannel.hexToBytes("zz"))    // 非十六进制字符
    }

    @Test
    fun `大端 64 位编码正确`() {
        assertArrayEquals(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 1), SecureChannel.be64(1))
        assertArrayEquals(
            byteArrayOf(0x00, 0x00, 0x01, 0x9A.toByte(), 0x14, 0xA8.toByte(), 0xD4.toByte(), 0x00),
            SecureChannel.be64(1761283200000L)
        )
    }

    @Test
    fun `定长比较`() {
        assertTrue(SecureChannel.constantTimeEquals(
            byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3)))
        assertFalse(SecureChannel.constantTimeEquals(
            byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 4)))
        assertFalse(SecureChannel.constantTimeEquals(
            byteArrayOf(1, 2), byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `新盐每次不同`() {
        val a = SecureChannel.newSalt()
        val b = SecureChannel.newSalt()
        assertEquals(16, a.size)
        assertNotEquals(SecureChannel.bytesToHex(a), SecureChannel.bytesToHex(b))
    }

    // ---------------------------------------------------------------- 端到端模拟

    @Test
    fun `模拟一次完整的加密请求响应`() {
        // 服务端持有的 token
        val serverToken = token

        // ---- 客户端 ----
        val clientSalt = SecureChannel.newSalt()
        val clientTs = System.currentTimeMillis()
        val seq = 1L
        val method = "POST"
        val path = "/api/v1/instances"
        val requestBody = """{"versionId":"1.20.1"}"""

        val clientKeys = SecureChannel.deriveKeys(serverToken, clientSalt, clientTs)
        val requestCipher = SecureChannel.encrypt(
            clientKeys, seq, aad(seq, method, path), requestBody.toByteArray()
        )

        // ---- 服务端 ----
        val serverKeys = SecureChannel.deriveKeys(serverToken, clientSalt, clientTs)
        val serverAad = aad(seq, method, path)
        val serverPlain = String(
            SecureChannel.decrypt(serverKeys, seq, serverAad, requestCipher), Charsets.UTF_8
        )
        assertEquals("服务端必须解出原始请求体", requestBody, serverPlain)

        // 服务端构造响应并加密
        val responseBody = """{"ok":true,"data":{"id":"abc"}}"""
        val responseCipher = SecureChannel.encrypt(
            serverKeys, seq, aad(seq, method, path), responseBody.toByteArray()
        )

        // ---- 客户端解密 ----
        val clientPlain = String(
            SecureChannel.decrypt(clientKeys, seq, aad(seq, method, path), responseCipher),
            Charsets.UTF_8
        )
        assertEquals("客户端必须解出原始响应体", responseBody, clientPlain)
    }

    // ------------------------------------------------- Python 参考实现互验
    //
    // 下面这组常量是用标准库（hmac/hashlib/struct）+ cryptography 的 AESGCM
    // 独立算出来的，见 scripts/mizu_api.py。它们的作用不是「测 Kotlin 写得对不对」
    // ——自己测自己没意义——而是**钉死跨语言互通**。
    //
    // 一旦有人改了 be64 的字节序、把标签从 "mizu-enc" 改成别的、或者顺手把
    // AAD 里的分隔符从 '|' 换成 ','，这组用例会立刻红。没有它，协议漂移要等
    // 到用户拿 Python 脚本连不上才发现，那时候排查成本高得多。

    @Test
    fun `与 Python 参考实现派生出一致的密钥`() {
        val k = SecureChannel.deriveKeys(token, salt, ts)
        assertEquals(
            "encKey 必须与 Python 实现逐字节一致",
            "f4b673a642c111ae0fbc85bfd33cc8c75880a3f1329c37d5d668b83ff83a7c78",
            SecureChannel.bytesToHex(k.encKey)
        )
        assertEquals(
            "nonceKey 必须与 Python 实现逐字节一致",
            "324f1b34e376135ee7f3eb7fb9212cc3ab072ea7242944c4d0fca4d7793b3ffd",
            SecureChannel.bytesToHex(k.nonceKey)
        )
    }

    @Test
    fun `与 Python 参考实现产生一致的密文`() {
        val k = SecureChannel.deriveKeys(token, salt, ts)

        // seq=1, GET /api/v1/health, 明文 {"ok":true}
        val ct1 = SecureChannel.encrypt(
            k, 1L, aad(1L, "GET", "/api/v1/health"), """{"ok":true}""".toByteArray()
        )
        assertEquals(
            "同明文同序号同 AAD 必须得到同密文（GCM 是确定性的）",
            "75407c505b3aaa54a8b8d143a9c21e9bc8cdf816c0a6f7b834aaeb",
            SecureChannel.bytesToHex(ct1)
        )

        // seq=7, POST /api/v1/instances, 含中文
        val ct2 = SecureChannel.encrypt(
            k, 7L, aad(7L, "POST", "/api/v1/instances"), """{"name":"测试"}""".toByteArray()
        )
        assertEquals(
            "中文（UTF-8 多字节）也必须与 Python 一致",
            "fa6b0440d9ad81e4a5e66e64c8f03eb3d45abc11a7c67ef8cef04ef944e44d991f",
            SecureChannel.bytesToHex(ct2)
        )
    }

    @Test
    fun `能解开 Python 生成的密文`() {
        val k = SecureChannel.deriveKeys(token, salt, ts)
        val cipher = SecureChannel.hexToBytes(
            "fa6b0440d9ad81e4a5e66e64c8f03eb3d45abc11a7c67ef8cef04ef944e44d991f"
        )!!
        val plain = String(
            SecureChannel.decrypt(k, 7L, aad(7L, "POST", "/api/v1/instances"), cipher),
            Charsets.UTF_8
        )
        assertEquals("必须能解开 Python 加密的报文", """{"name":"测试"}""", plain)
    }

    @Test
    fun `AAD 分隔符改掉就解不开`() {
        // 防的是「有人觉得 '|' 不好看换成 ','」这类重构，或者某个客户端语言
        // 自己拼了 `METHOD + path`（漏掉分隔符）。
        // 分隔符一变 AAD 就变，跨语言立刻不兼容，这里先把它拦住。
        val k = SecureChannel.deriveKeys(token, salt, ts)
        val cipher = SecureChannel.hexToBytes(
            "75407c505b3aaa54a8b8d143a9c21e9bc8cdf816c0a6f7b834aaeb"
        )!!
        try {
            SecureChannel.decrypt(
                k, 1L, "GET,/api/v1/health".toByteArray(), cipher
            )
            fail("AAD 分隔符不同必须解不开")
        } catch (e: AEADBadTagException) {
            // 预期
        }
    }

    @Test
    fun `AAD 缺失分隔符就解不开`() {
        // 另一种常见写错：把 method 和 path 直接相连
        val k = SecureChannel.deriveKeys(token, salt, ts)
        val cipher = SecureChannel.hexToBytes(
            "75407c505b3aaa54a8b8d143a9c21e9bc8cdf816c0a6f7b834aaeb"
        )!!
        try {
            SecureChannel.decrypt(
                k, 1L, "GET/api/v1/health".toByteArray(), cipher
            )
            fail("漏掉分隔符必须解不开")
        } catch (e: AEADBadTagException) {
            // 预期
        }
    }
}
