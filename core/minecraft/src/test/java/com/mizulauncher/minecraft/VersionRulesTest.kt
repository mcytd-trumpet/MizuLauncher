package com.mizulauncher.minecraft

import com.mizulauncher.download.DownloadClass
import com.mizulauncher.minecraft.model.AssetIndex
import com.mizulauncher.minecraft.model.DownloadInfo
import com.mizulauncher.minecraft.model.Library
import com.mizulauncher.minecraft.model.Rule
import com.mizulauncher.minecraft.model.VersionJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionRulesTest {

    private val linuxCtx = PlatformContext()

    @Test
    fun `no rules means allowed`() {
        assertTrue(VersionRules.rulesAllow(null, linuxCtx))
        assertTrue(VersionRules.rulesAllow(emptyList(), linuxCtx))
    }

    @Test
    fun `os specific allow rule is honored`() {
        // rules 是白名单：只有 allow+osx 且当前是 linux → 一条都没命中 → 拒绝
        assertFalse(VersionRules.rulesAllow(listOf(Rule("allow", Rule.OsRule(name = "osx"))), linuxCtx))
        // 命中第一条 allow(linux) → 放行（后面那条 disallow 不再看）
        assertTrue(
            VersionRules.rulesAllow(
                listOf(Rule("allow", Rule.OsRule(name = "linux")), Rule("disallow")),
                linuxCtx
            )
        )
        // 无 os 限定的 allow 命中 → 放行
        assertTrue(VersionRules.rulesAllow(listOf(Rule("allow")), linuxCtx))
        // 无 os 限定的 disallow 命中 → 拒绝
        assertFalse(VersionRules.rulesAllow(listOf(Rule("disallow")), linuxCtx))
    }

    @Test
    fun `first matching rule wins`() {
        // 顺序敏感：disallow 在前则拒绝，allow 在前则放行
        assertFalse(
            VersionRules.rulesAllow(
                listOf(Rule("disallow", Rule.OsRule(name = "linux")), Rule("allow")),
                linuxCtx
            )
        )
        assertTrue(
            VersionRules.rulesAllow(
                listOf(Rule("allow", Rule.OsRule(name = "linux")), Rule("disallow")),
                linuxCtx
            )
        )
    }

    @Test
    fun `android rules are treated as linux`() {
        assertTrue(VersionRules.rulesAllow(listOf(Rule("allow", Rule.OsRule(name = "android"))), linuxCtx))
        assertTrue(VersionRules.rulesAllow(listOf(Rule("allow", Rule.OsRule(name = "linux"))), linuxCtx))
        assertFalse(VersionRules.rulesAllow(listOf(Rule("allow", Rule.OsRule(name = "windows"))), linuxCtx))
    }

    @Test
    fun `explicit disallow wins on match`() {
        // 真实场景：通用 allow + linux 上额外 disallow
        val rules = listOf(
            Rule("allow", Rule.OsRule(name = "windows")),
            Rule("disallow", Rule.OsRule(name = "linux"))
        )
        assertFalse(VersionRules.rulesAllow(rules, linuxCtx))
    }

    @Test
    fun `feature gate blocks quick play`() {
        val rules = listOf(
            Rule("allow", features = mapOf("has_quick_plays_support" to true))
        )
        // 默认上下文不支持 quick play → 规则不命中 → 拒绝
        assertFalse(VersionRules.rulesAllow(rules, linuxCtx))
        // 声明支持后才命中
        assertTrue(
            VersionRules.rulesAllow(
                rules,
                linuxCtx.copy(features = linuxCtx.features + ("has_quick_plays_support" to true))
            )
        )
    }

    @Test
    fun `effective libraries filters osx-only lib`() {
        val json = VersionJson(
            id = "1.20.1",
            libraries = listOf(
                Library(name = "org.ow2.asm:asm:9.3"),
                Library(
                    name = "ca.weblite:java-objc-bridge:1.1",
                    rules = listOf(Rule("allow", Rule.OsRule(name = "osx")))
                ),
                Library(name = "com.mojang:brigadier:1.0.18")
            )
        )
        val names = VersionRules.effectiveLibraries(json).map { it.name }
        assertTrue(names.contains("org.ow2.asm:asm:9.3"))
        assertTrue(names.contains("com.mojang:brigadier:1.0.18"))
        assertFalse(names.contains("ca.weblite:java-objc-bridge:1.1"))
    }

    @Test
    fun `merge child overrides parent main class and libs`() {
        val parent = VersionJson(
            id = "1.20.1",
            mainClass = "net.minecraft.client.main.Main",
            assets = "5",
            libraries = listOf(
                Library(name = "org.ow2.asm:asm:9.3"),
                Library(name = "com.mojang:brigadier:1.0.18")
            ),
            arguments = VersionJson.Arguments(game = listOf("--version", "\${version_name}"))
        )
        val child = VersionJson(
            id = "1.20.1-fabric",
            inheritsFrom = "1.20.1",
            mainClass = "net.fabricmc.loader.impl.launch.knot.KnotClient",
            libraries = listOf(
                Library(name = "org.ow2.asm:asm:9.5"),
                Library(name = "net.fabricmc:fabric-loader:0.15.0")
            ),
            arguments = VersionJson.Arguments(game = listOf("--fabric"))
        )

        val merged = VersionRules.merge(parent, child)
        assertEquals("net.fabricmc.loader.impl.launch.knot.KnotClient", merged.mainClass)
        assertEquals(null, merged.inheritsFrom)
        assertEquals("5", merged.assets)

        // 同 artifact 只保留子版本
        val asm = merged.libraries.filter { it.name.startsWith("org.ow2.asm:asm") }
        assertEquals(1, asm.size)
        assertEquals("org.ow2.asm:asm:9.5", asm[0].name)
        // 父版本独有的库保留
        assertTrue(merged.libraries.any { it.name == "com.mojang:brigadier:1.0.18" })
        // 子参数在前
        assertEquals("--fabric", merged.arguments!!.game.first())
    }

    @Test
    fun `required java major uses javaVersion field`() {
        assertEquals(17, VersionRules.requiredJavaMajor(VersionJson(javaVersion = VersionJson.JavaVersion(majorVersion = 17))))
        assertEquals(21, VersionRules.requiredJavaMajor(VersionJson(javaVersion = VersionJson.JavaVersion(majorVersion = 21))))
    }

    @Test
    fun `compare version ids numerically`() {
        assertTrue(VersionRules.compareVersionId("1.20.1", "1.9") > 0)
        assertTrue(VersionRules.compareVersionId("1.7.10", "1.7.2") > 0)
        assertEquals(0, VersionRules.compareVersionId("1.20.1", "1.20.1"))
        assertTrue(VersionRules.compareVersionId("1.21", "1.20.6") > 0)
    }

    @Test
    fun `maven path builds correct relative path`() {
        val lib = Library(name = "org.ow2.asm:asm:9.3")
        assertEquals("org/ow2/asm/asm/9.3/asm-9.3.jar", lib.mavenPath())

        val native = Library(name = "org.lwjgl:lwjgl:3.3.2:natives-linux")
        assertEquals("org/lwjgl/lwjgl/3.3.2/lwjgl-3.3.2-natives-linux.jar", native.mavenPath())
    }

    @Test
    fun `download plan skips lwjgl and natives`() {
        val json = VersionJson(
            id = "1.20.1",
            libraries = listOf(
                Library(name = "org.lwjgl:lwjgl:3.3.2", downloads = Library.LibraryDownloads(DownloadInfo(url = "u1", sha1 = "s1"))),
                Library(name = "org.ow2.asm:asm:9.3", downloads = Library.LibraryDownloads(DownloadInfo(url = "u2", sha1 = "s2"))),
                Library(
                    name = "org.lwjgl:lwjgl:3.3.2:natives-linux",
                    downloads = Library.LibraryDownloads(DownloadInfo(url = "u3"))
                )
            ),
            downloads = mapOf("client" to DownloadInfo(url = "client-url", sha1 = "cs", size = 100))
        )
        val layout = DownloadPlanBuilder.ClientLayout("game", "assets", "libs", "versions")
        val plan = DownloadPlanBuilder.build("1.20.1", json, layout, null, "client-url")

        val urls = plan.map { it.url }
        assertTrue(urls.contains("client-url"))
        assertTrue(urls.contains("u2"))
        assertFalse(urls.contains("u1"))
        assertFalse(urls.contains("u3"))
    }

    @Test
    fun `download plan includes assets objects`() {
        val objects = mapOf(
            "minecraft/sounds/a.ogg" to AssetIndex.AssetObject("abcdef1234", 100),
            "icons/icon_16x16.png" to AssetIndex.AssetObject("50ce48459eb9e29836662862273d7b15d41c2c33", 50)
        )
        val plan = DownloadPlanBuilder.build(
            "1.20.1",
            VersionJson(id = "1.20.1"),
            DownloadPlanBuilder.ClientLayout("g", "a", "l", "v"),
            AssetIndex(objects = objects),
            null
        )
        assertEquals(2, plan.size)
        assertTrue(plan.all { it.downloadClass == DownloadClass.ASSET })
        assertTrue(plan.any { it.relativePath == "assets/objects/ab/abcdef1234" })
        assertTrue(plan.any { it.url == "https://resources.download.minecraft.net/50/50ce48459eb9e29836662862273d7b15d41c2c33" })
    }
}
