package com.mizulauncher.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 内置组件扫描与版本比对单测。
 *
 * 这几个断言直接对应线上会翻车的地方：
 *  - 把 arm64 的 JRE 解到 32 位机器上 → 启动必崩；
 *  - 升级后没提示补全 → 用的是旧 JRE，`UnsupportedClassVersionError`；
 *  - 没升级却每次启动都提示 → 用户烦到关掉通知。
 */
class BundleCatalogTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun zipWith(name: String, content: String): File {
        val f = File(tmp.root, name)
        ZipOutputStream(f.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("marker.txt"))
            zos.write(content.toByteArray())
            zos.closeEntry()
        }
        return f
    }

    // ---------------- 发现 ----------------

    @Test
    fun `discovers builtin java runtimes for the device abi only`() {
        val paths = listOf(
            "runtime/arm64-v8a/jre-8.zip",
            "runtime/arm64-v8a/jre-17.zip",
            "runtime/arm64-v8a/jre-21.zip",
            "runtime/arm64-v8a/jre-25.zip",
            "runtime/armeabi-v7a/jre-8.zip",
            "runtime/x86_64/jre-21.zip",
            "bundle/lwjgl/lwjgl-3.3.3.zip",
            "bundle/renderer/zink.zip"
        )
        val items = BundleCatalog.discover(paths, "arm64-v8a")

        assertEquals(listOf("java-8", "java-17", "java-21", "java-25"), items.filter { it.kind == "java" }.map { it.name })
        // 通用组件不分架构，必须保留
        assertTrue(items.any { it.name == "lwjgl-3.3.3" })
        assertTrue(items.any { it.name == "zink" })
        // 别的架构的包一个都不能进来
        assertFalse(items.any { it.assetPath.contains("armeabi-v7a") })
        assertFalse(items.any { it.assetPath.contains("x86_64") })
    }

    @Test
    fun `arm32 device does not get java 25`() {
        val paths = listOf(
            "runtime/armeabi-v7a/jre-8.zip",
            "runtime/armeabi-v7a/jre-17.zip",
            "runtime/armeabi-v7a/jre-21.zip"
        )
        val items = BundleCatalog.discover(paths, "armeabi-v7a")
        assertEquals(3, items.size)
        assertFalse(items.any { it.name == "java-25" })
    }

    @Test
    fun `empty assets yields empty list instead of a fake manifest`() {
        assertTrue(BundleCatalog.discover(emptyList(), "arm64-v8a").isEmpty())
        // 目录名对但文件不是 zip 的，也不算组件
        assertTrue(BundleCatalog.discover(listOf("runtime/arm64-v8a/readme.txt"), "arm64-v8a").isEmpty())
    }

    @Test
    fun `abnormal asset names are ignored`() {
        val paths = listOf(
            "runtime/arm64-v8a/jre-abc.zip",   // 不带数字
            "runtime/arm64-v8a/jre-17.zip.bak", // 后缀不对
            "bundle/lwjgl/nested/lwjgl.zip"     // 多一层目录
        )
        assertTrue(BundleCatalog.discover(paths, "arm64-v8a").isEmpty())
    }

    @Test
    fun `items are ordered stably`() {
        val paths = listOf(
            "runtime/arm64-v8a/jre-25.zip",
            "bundle/renderer/zink.zip",
            "runtime/arm64-v8a/jre-8.zip",
            "bundle/lwjgl/lwjgl.zip"
        )
        val items = BundleCatalog.discover(paths, "arm64-v8a")
        assertEquals("zink", items[0].name)
        assertEquals("lwjgl", items[1].name)
        assertEquals("java-8", items[2].name)
        assertEquals("java-25", items[3].name)
    }

    @Test
    fun `universal device abi matches arm64 assets`() {
        // 某些 universal 包会把 assets 放在 runtime/arm64/ 这种短名下
        assertTrue(BundleCatalog.abiMatches("arm64-v8a", "arm64"))
        assertTrue(BundleCatalog.abiMatches("armeabi-v7a", "arm32"))
        assertTrue(BundleCatalog.abiMatches("x86_64", "x64"))
        assertFalse(BundleCatalog.abiMatches("arm64-v8a", "x86_64"))
    }

    // ---------------- 指纹 ----------------

    @Test
    fun `fingerprint changes when the archive changes`() {
        val paths = listOf("runtime/arm64-v8a/jre-17.zip")
        val item = BundleCatalog.discover(paths, "arm64-v8a").single()

        val old = zipWith("a.zip", "v1")
        val fresh = zipWith("b.zip", "v2")
        val fpOld = BundleCatalog.fingerprint(item, old)
        val fpNew = BundleCatalog.fingerprint(item, fresh)

        assertNotEquals("内容变了指纹必须变", fpOld, fpNew)
        assertEquals("同一份文件指纹要稳定", fpOld, BundleCatalog.fingerprint(item, old))
    }

    @Test
    fun `fingerprint without archive still stable`() {
        val item = BundleCatalog.discover(listOf("bundle/lwjgl/lwjgl.zip"), "arm64-v8a").single()
        assertEquals(
            BundleCatalog.fingerprint(item, null),
            BundleCatalog.fingerprint(item, File(tmp.root, "does-not-exist.zip"))
        )
    }

    // ---------------- 待安装判定 ----------------

    @Test
    fun `fresh install has everything pending`() {
        val items = BundleCatalog.discover(
            listOf("runtime/arm64-v8a/jre-8.zip", "runtime/arm64-v8a/jre-17.zip"),
            "arm64-v8a"
        )
        val pending = BundleCatalog.Ledger.pending(
            items = items,
            records = emptyMap(),
            fingerprintOf = { "fp" },
            present = { false }
        )
        assertEquals(2, pending.size)
    }

    @Test
    fun `nothing pending after successful install`() {
        val items = BundleCatalog.discover(listOf("runtime/arm64-v8a/jre-17.zip"), "arm64-v8a")
        val item = items.single()
        val records = mapOf(item.key to BundleCatalog.Ledger.expected(item, "fp"))
        val pending = BundleCatalog.Ledger.pending(
            items = items,
            records = records,
            fingerprintOf = { "fp" },
            present = { true }
        )
        assertTrue("装过了就不该再提示", pending.isEmpty())
    }

    @Test
    fun `pending again when fingerprint changes after update`() {
        val items = BundleCatalog.discover(listOf("runtime/arm64-v8a/jre-17.zip"), "arm64-v8a")
        val item = items.single()
        val records = mapOf(item.key to BundleCatalog.Ledger.expected(item, "old-fp"))
        val pending = BundleCatalog.Ledger.pending(
            items = items,
            records = records,
            fingerprintOf = { "new-fp" },
            present = { true }
        )
        assertEquals("升级带来新版本组件必须重新提示", listOf(item), pending)
    }

    @Test
    fun `pending again when directory was wiped but ledger remains`() {
        val items = BundleCatalog.discover(listOf("runtime/arm64-v8a/jre-21.zip"), "arm64-v8a")
        val item = items.single()
        val records = mapOf(item.key to BundleCatalog.Ledger.expected(item, "fp"))
        val pending = BundleCatalog.Ledger.pending(
            items = items,
            records = records,
            fingerprintOf = { "fp" },
            present = { false }
        )
        assertEquals("目录被清掉了要能自己发现", listOf(item), pending)
    }

    // ---------------- 账本编解码 ----------------

    @Test
    fun `ledger round trip`() {
        val records = mapOf(
            "java:java-17" to "jre-17@abc",
            "lwjgl:lwjgl-3.3.3" to "lwjgl-3.3.3@def"
        )
        val encoded = BundleCatalog.Ledger.encode(records)
        assertEquals(records, BundleCatalog.Ledger.decode(encoded))
    }

    @Test
    fun `ledger tolerates junk`() {
        assertTrue(BundleCatalog.Ledger.decode(null).isEmpty())
        assertTrue(BundleCatalog.Ledger.decode("  ").isEmpty())
        val decoded = BundleCatalog.Ledger.decode("no-equals-sign\njava:java-8=jre-8@1\n=empty-key")
        assertEquals(mapOf("java:java-8" to "jre-8@1"), decoded)
    }

    @Test
    fun `java usage text covers all majors`() {
        listOf(8, 17, 21, 25).forEach { major ->
            assertTrue(BundleCatalog.javaUsage(major).isNotBlank())
        }
    }
}
