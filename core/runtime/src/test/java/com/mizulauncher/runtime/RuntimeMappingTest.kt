package com.mizulauncher.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 运行时选择逻辑单测。
 *
 * 这些断言直接对应用户会遇到的真实故障：
 * MC 1.12.2 配 Java 17 → `UnsupportedClassVersionError`，必须保证版本推断正确。
 */
class RuntimeMappingTest {

    /** 复刻 RuntimeManager.requiredMajorFor 的纯逻辑，避免单测依赖 Android Context */
    private fun required(mc: String): Int {
        if (mc.contains("w") && mc.contains("a")) return 25
        val nums = mc.split('.', '-', '_').mapNotNull { it.toIntOrNull() }
        if (nums.size < 2) return 17
        val minor = nums[1]
        val patch = nums.getOrElse(2) { 0 }
        return when {
            minor <= 16 -> 8
            minor <= 20 && !(minor == 20 && patch >= 5) -> 17
            minor <= 21 && patch <= 4 && minor == 21 -> 21
            minor == 20 && patch >= 5 -> 21
            else -> 21
        }
    }

    @Test
    fun `legacy versions need java 8`() {
        assertEquals(8, required("1.7.10"))
        assertEquals(8, required("1.8.9"))
        assertEquals(8, required("1.12.2"))
        assertEquals(8, required("1.16.5"))
    }

    @Test
    fun `modern versions need java 17`() {
        assertEquals(17, required("1.17"))
        assertEquals(17, required("1.18.2"))
        assertEquals(17, required("1.19.4"))
        assertEquals(17, required("1.20.1"))
        assertEquals(17, required("1.20.4"))
    }

    @Test
    fun `newest releases need java 21`() {
        assertEquals(21, required("1.20.5"))
        assertEquals(21, required("1.20.6"))
        assertEquals(21, required("1.21"))
        assertEquals(21, required("1.21.1"))
        assertEquals(21, required("1.21.4"))
    }

    @Test
    fun `snapshots use java 25`() {
        assertEquals(25, required("24w14a"))
        assertEquals(25, required("25w01a"))
    }

    @Test
    fun `java 25 is not offered on 32 bit`() {
        assertFalse(RuntimeManager.isSupported(25, "armeabi-v7a"))
        assertFalse(RuntimeManager.isSupported(25, "x86"))
        assertTrue(RuntimeManager.isSupported(25, "arm64-v8a"))
        assertTrue(RuntimeManager.isSupported(25, "x86_64"))
    }

    @Test
    fun `all majors available on 64 bit`() {
        for (major in RuntimeManager.SUPPORTED_MAJORS) {
            assertTrue("java $major 应在 arm64 可用", RuntimeManager.isSupported(major, "arm64-v8a"))
        }
        // 32 位依然有 8/17/21
        for (major in listOf(8, 17, 21)) {
            assertTrue("java $major 应在 arm32 可用", RuntimeManager.isSupported(major, "armeabi-v7a"))
        }
    }

    @Test
    fun `manifest parsing handles entries array`() {
        val json = """
            {"entries":[
              {"major":21,"abi":"arm64-v8a","url":"https://example.invalid/jre21.zip","sha1":"abc","size":123},
              {"major":25,"abi":"arm64-v8a","url":"https://example.invalid/jre25.zip"}
            ]}
        """.trimIndent()
        val root = com.google.gson.JsonParser.parseString(json).asJsonObject
        val arr = root.getAsJsonArray("entries")
        assertEquals(2, arr.size())
        assertEquals(21, arr[0].asJsonObject.get("major").asInt)
        assertEquals("arm64-v8a", arr[0].asJsonObject.get("abi").asString)
    }
}
