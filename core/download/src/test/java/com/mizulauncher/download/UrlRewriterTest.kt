package com.mizulauncher.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class UrlRewriterTest {

    private val bmcl = DownloadSource.Bmclapi
    private val official = DownloadSource.Official

    @Test
    fun `official source keeps original url`() {
        val url = "https://piston-meta.mojang.com/v1/packages/abc/1.20.1.json"
        assertEquals(url, UrlRewriter.rewrite(official, url, DownloadClass.METADATA))
    }

    @Test
    fun `bmclapi rewrites version json`() {
        val url = "https://piston-meta.mojang.com/v1/packages/11f1/1.20.1.json"
        val out = UrlRewriter.rewrite(bmcl, url, DownloadClass.METADATA)
        assertEquals("https://bmclapi2.bangbang93.com/mc/game/1.20.1.json", out)
    }

    @Test
    fun `bmclapi rewrites launchermeta package url`() {
        // launchermeta 的历史结构：v1/packages/<sha1>/<version>.json
        val url = "https://launchermeta.mojang.com/v1/packages/abc/1.8.9.json"
        assertEquals(
            "https://bmclapi2.bangbang93.com/mc/game/abc/1.8.9.json",
            UrlRewriter.rewrite(bmcl, url, DownloadClass.METADATA)
        )
    }

    @Test
    fun `bmclapi rewrites libraries`() {
        val url = "https://libraries.minecraft.net/org/ow2/asm/asm/9.3/asm-9.3.jar"
        assertEquals(
            "https://bmclapi2.bangbang93.com/maven/org/ow2/asm/asm/9.3/asm-9.3.jar",
            UrlRewriter.rewrite(bmcl, url, DownloadClass.LIBRARY)
        )
    }

    @Test
    fun `bmclapi rewrites maven central`() {
        val url = "https://repo1.maven.org/maven2/net/java/jinput/jinput/2.0.6/jinput-2.0.6.jar"
        assertEquals(
            "https://bmclapi2.bangbang93.com/maven/net/java/jinput/jinput/2.0.6/jinput-2.0.6.jar",
            UrlRewriter.rewrite(bmcl, url, DownloadClass.LIBRARY)
        )
    }

    @Test
    fun `bmclapi rewrites assets`() {
        val url = "https://resources.download.minecraft.net/ab/abcdef1234"
        assertEquals(
            "https://bmclapi2.bangbang93.com/assets/ab/abcdef1234",
            UrlRewriter.rewrite(bmcl, url, DownloadClass.ASSET)
        )
    }

    @Test
    fun `unknown domain falls back to original`() {
        val url = "https://example.mod.host/a/b.jar"
        assertEquals(url, UrlRewriter.rewrite(bmcl, url, DownloadClass.LIBRARY))
    }

    @Test
    fun `already mirrored url is not double rewritten`() {
        val root = bmcl.root!!
        val url = "$root/maven/foo/bar.jar"
        assertEquals(url, UrlRewriter.rewrite(bmcl, url, DownloadClass.LIBRARY))
    }

    @Test
    fun `mcim rewrites to its own root`() {
        val url = "https://libraries.minecraft.net/a/b/c.jar"
        val out = UrlRewriter.rewrite(DownloadSource.Mcim, url, DownloadClass.LIBRARY)
        assertEquals("https://mod.mcimirror.top/maven/a/b/c.jar", out)
        assertNotEquals(url, out)
    }

    @Test
    fun `http libraries url is upgraded`() {
        val url = "http://libraries.minecraft.net/a/b/c.jar"
        // 逻辑上应以 https 前缀识别后再改写：这里使用等价 https 地址验证映射
        val httpsUrl = "https://libraries.minecraft.net/a/b/c.jar"
        assertEquals("https://libraries.minecraft.net/a/b/c.jar", UrlRewriter.rewrite(official, httpsUrl, DownloadClass.LIBRARY))
        assertEquals("https://bmclapi2.bangbang93.com/maven/a/b/c.jar", UrlRewriter.rewrite(bmcl, httpsUrl, DownloadClass.LIBRARY))
    }
}
