package com.mizulauncher.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    // ---------------------------------------------------------------- 版本比较

    @Test
    fun `compareVersions handles numeric ordering`() {
        assertTrue("1.10 应大于 1.9", UpdateChecker.compareVersions("1.10.0", "1.9.0") > 0)
        assertTrue(UpdateChecker.compareVersions("2.0.0", "1.99.99") > 0)
        assertEquals(0, UpdateChecker.compareVersions("1.2.3", "1.2.3"))
        assertTrue(UpdateChecker.compareVersions("1.2.3", "1.2.4") < 0)
    }

    @Test
    fun `compareVersions tolerates different segment counts`() {
        assertEquals(0, UpdateChecker.compareVersions("1.2", "1.2.0"))
        assertTrue(UpdateChecker.compareVersions("1.2.1", "1.2") > 0)
        assertTrue(UpdateChecker.compareVersions("2", "1.9.9.9") > 0)
    }

    @Test
    fun `versionFromTag strips prefix and suffix`() {
        assertEquals("1.0.0", UpdateChecker.versionFromTag("v1.0.0"))
        assertEquals("1.2.3", UpdateChecker.versionFromTag("1.2.3"))
        assertEquals("2.0.0", UpdateChecker.versionFromTag("v2.0.0-beta.1"))
        assertNull(UpdateChecker.versionFromTag("nightly"))
    }

    // ---------------------------------------------------------------- 架构选择

    @Test
    fun `pickAbi follows device abi priority`() {
        assertEquals("arm64", UpdateChecker.pickAbiFor(listOf("arm64-v8a", "armeabi-v7a")))
        assertEquals("arm32", UpdateChecker.pickAbiFor(listOf("armeabi-v7a", "armeabi")))
        assertEquals("x64", UpdateChecker.pickAbiFor(listOf("x86_64", "x86")))
        assertEquals("x86", UpdateChecker.pickAbiFor(listOf("x86")))
        assertEquals("universal", UpdateChecker.pickAbiFor(emptyList()))
        assertEquals("universal", UpdateChecker.pickAbiFor(listOf("mips")))
    }

    @Test
    fun `arm64 device does not get arm32 build`() {
        // 这是实际会踩的坑：64 位设备同时支持 arm32，
        // 若按「支持的架构逐个匹配」就会装错包
        assertEquals("arm64", UpdateChecker.pickAbiFor(listOf("arm64-v8a", "armeabi-v7a", "armeabi")))
    }

    @Test
    fun `abiOfAsset parses release file names`() {
        assertEquals("arm64", UpdateChecker.abiOfAsset("MizuLauncher-1.0.0-10000-arm64.apk"))
        assertEquals("arm32", UpdateChecker.abiOfAsset("MizuLauncher-v1.0.0-arm32.apk"))
        assertEquals("x64", UpdateChecker.abiOfAsset("MizuLauncher-v1.0.0-x64.apk"))
        assertEquals("x86", UpdateChecker.abiOfAsset("MizuLauncher-v1.0.0-x86.apk"))
        assertEquals("universal", UpdateChecker.abiOfAsset("MizuLauncher-v1.0.0-universal.apk"))
        assertNull("不是 apk 就不要", UpdateChecker.abiOfAsset("SHA256SUMS.txt"))
        assertNull(UpdateChecker.abiOfAsset("MizuLauncher-source.zip"))
    }

    @Test
    fun `abiOfAsset does not confuse x86 with x64`() {
        // "-x86.apk" 里也包含 "x86"，但 x64 的包名是 "-x64.apk"，
        // 匹配要带分隔符，否则 x64 包会被误判成 x86
        assertEquals("x64", UpdateChecker.abiOfAsset("MizuLauncher-1.0.0-x64.apk"))
        assertEquals("x86", UpdateChecker.abiOfAsset("MizuLauncher-1.0.0-x86.apk"))
    }

    // ---------------------------------------------------------------- 版本来源

    @Test
    fun `versionCode parsed from tagged asset name`() {
        // CI 会把 versionCode 编进文件名，这里是权威来源
        val info = fakeCheck(
            tagName = "v9.9.9",
            assetNames = listOf("MizuLauncher-9.9.9-10000-arm64.apk", "SHA256SUMS.txt"),
            currentCode = 9999,
            deviceAbis = listOf("arm64-v8a")
        )
        assertEquals(10000, info?.versionCode)
        assertEquals("MizuLauncher-9.9.9-10000-arm64.apk", info?.asset?.name)
    }

    @Test
    fun `falls back to tag when asset has no version code`() {
        val info = fakeCheck(
            tagName = "v1.2.3",
            assetNames = listOf("MizuLauncher-v1.2.3-arm64.apk"),
            currentCode = 10000,
            deviceAbis = listOf("arm64-v8a")
        )
        // tag 1.2.3 → 1*10000 + 2*100 + 3 = 10203 > 10000
        assertEquals(10203, info?.versionCode)
    }

    @Test
    fun `no update when remote is not newer`() {
        val none = fakeCheck(
            tagName = "v1.0.0",
            assetNames = listOf("MizuLauncher-1.0.0-10000-arm64.apk"),
            currentCode = 10000,
            deviceAbis = listOf("arm64-v8a")
        )
        assertNull("同版本不该提示更新", none)
    }

    @Test
    fun `update detected when remote code is higher`() {
        val info = fakeCheck(
            tagName = "v1.0.1",
            assetNames = listOf("MizuLauncher-1.0.1-10001-arm64.apk"),
            currentCode = 10000,
            deviceAbis = listOf("arm64-v8a")
        )
        assertEquals("1.0.1", info?.versionName)
        assertEquals("v1.0.1", info?.tag)
    }

    @Test
    fun `missing matching abi falls back to universal`() {
        val info = fakeCheck(
            tagName = "v2.0.0",
            assetNames = listOf("MizuLauncher-2.0.0-20000-universal.apk"),
            currentCode = 10000,
            deviceAbis = listOf("arm64-v8a")
        )
        assertEquals("MizuLauncher-2.0.0-20000-universal.apk", info?.asset?.name)
    }

    @Test
    fun `no downloadable asset when release has none`() {
        val info = fakeCheck(
            tagName = "v2.0.0",
            assetNames = listOf("SHA256SUMS.txt", "source.tar.gz"),
            currentCode = 10000,
            deviceAbis = listOf("arm64-v8a")
        )
        assertNull("没有可下载 APK 时 asset 应为 null，UI 据此不给安装按钮", info?.asset)
    }

    @Test
    fun `x86 device never gets an arm package`() {
        val info = fakeCheck(
            tagName = "v2.0.0",
            assetNames = listOf(
                "MizuLauncher-2.0.0-20000-arm64.apk",
                "MizuLauncher-2.0.0-20000-x86.apk"
            ),
            currentCode = 10000,
            deviceAbis = listOf("x86")
        )
        assertEquals("MizuLauncher-2.0.0-20000-x86.apk", info?.asset?.name)
    }

    @Test
    fun `device abi reported so ui can explain the choice`() {
        val info = fakeCheck(
            tagName = "v2.0.0",
            assetNames = listOf("MizuLauncher-2.0.0-20000-arm64.apk"),
            currentCode = 10000,
            deviceAbis = listOf("arm64-v8a", "armeabi-v7a")
        )
        assertEquals("arm64", info?.deviceAbi)
        assertEquals("arm64", info?.asset?.abi)
    }

    @Test
    fun `version code is read from asset name instead of tag`() {
        // tag 写错成 2.0.0 但 versionCode 是 20005：以文件名里的为准确
        assertEquals(20005, UpdateChecker.versionCodeFromName("MizuLauncher-2.0.0-20005-arm64.apk"))
        assertEquals(10000, UpdateChecker.versionCodeFromName("MizuLauncher-1.0.0-10000-universal.apk"))
        assertNull(UpdateChecker.versionCodeFromName("MizuLauncher-arm64.apk"))
        assertNull(UpdateChecker.versionCodeFromName("SHA256SUMS.txt"))
    }

    @Test
    fun `cached asset is reused when remote matches`() {
        val json = """
            {"tag_name":"v2.0.0","body":"","published_at":"","assets":[
              {"name":"MizuLauncher-2.0.0-20000-arm64.apk","browser_download_url":"https://e/a.apk","size":1}
            ]}
        """.trimIndent()
        val checker = UpdateChecker()
        val hit = checker.parseRelease(json, 10000, "1.0.0", listOf("arm64-v8a"), "MizuLauncher-2.0.0-20000-arm64.apk")
        assertTrue("同名包已在本机，应直接复用而不是重下", hit!!.reuseCached)

        val miss = checker.parseRelease(json, 10000, "1.0.0", listOf("arm64-v8a"), "MizuLauncher-1.9.0-19900-arm64.apk")
        assertFalse("旧包不能复用", miss!!.reuseCached)
    }

    @Test
    fun `asset size is human readable`() {
        val big = UpdateChecker.ReleaseAsset("a.apk", "u", 55L * 1024 * 1024)
        val small = UpdateChecker.ReleaseAsset("b.apk", "u", 800L * 1024)
        val unknown = UpdateChecker.ReleaseAsset("c.apk", "u", 0)
        assertEquals("55.0 MB", big.sizeText)
        assertEquals("800 KB", small.sizeText)
        assertEquals("未知大小", unknown.sizeText)
    }

    // ---------------------------------------------------------------- 测试辅助

    /** 用预置 JSON 走一遍真实的解析路径（不打网络） */
    private fun fakeCheck(
        tagName: String,
        assetNames: List<String>,
        currentCode: Int,
        deviceAbis: List<String>,
        body: String = "更新说明"
    ): UpdateChecker.UpdateInfo? {
        val assetsJson = assetNames.joinToString(",") { name ->
            """{"name":"$name","browser_download_url":"https://example.com/$name","size":12345}"""
        }
        val json = """
            {"tag_name":"$tagName","body":"$body","published_at":"2026-10-24T00:00:00Z",
             "assets":[$assetsJson]}
        """.trimIndent()

        return UpdateChecker().parseRelease(json, currentCode, "1.0.0", deviceAbis)
    }
}
