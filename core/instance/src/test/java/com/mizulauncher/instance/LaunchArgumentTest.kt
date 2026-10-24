package com.mizulauncher.instance

import com.mizulauncher.minecraft.DownloadPlanBuilder
import com.mizulauncher.minecraft.model.VersionJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LaunchArgumentTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val layout = DownloadPlanBuilder.ClientLayout(
        gameDir = "/sdcard/PCL/instances/a/.minecraft",
        assetsDir = "/sdcard/PCL/common/assets",
        librariesDir = "/sdcard/PCL/common/libraries",
        versionsDir = "/sdcard/PCL/instances/a/.minecraft/versions"
    )

    @Test
    fun `offline uuid is deterministic and v3`() {
        val a = Account.offlineUuid("Player")
        val b = Account.offlineUuid("Player")
        assertEquals(a, b)
        assertEquals(36, a.length)
        assertEquals('3', a[14]) // 版本位
        assertTrue(a[19] in listOf('8', '9', 'a', 'b'))
    }

    @Test
    fun `placeholder replacement`() {
        val out = ArgumentBuilder.replace(
            "--username \${auth_player_name} --uuid \${auth_uuid} --nope \${unknown}",
            mapOf("auth_player_name" to "Steve", "auth_uuid" to "abc")
        )
        assertEquals("--username Steve --uuid abc --nope \${unknown}", out)
    }

    @Test
    fun `modern arguments are flattened and resolved`() {
        val json = VersionJson(
            id = "1.20.1",
            mainClass = "net.minecraft.client.main.Main",
            assets = "5",
            libraries = emptyList(),
            arguments = VersionJson.Arguments(
                game = listOf("--username", "\${auth_player_name}", "--gameDir", "\${game_directory}"),
                jvm = listOf("-Djava.library.path=\${natives_directory}", "-cp", "\${classpath}")
            )
        )
        val req = ArgumentBuilder.build("1.20.1", json, layout, Account("Steve"), "inst1")
        assertEquals("net.minecraft.client.main.Main", req.mainClass)
        assertTrue(req.gameArgs.contains("Steve"))
        assertTrue(req.gameArgs.contains(layout.gameDir))
        assertTrue(req.jvmArgs.any { it.contains(layout.nativesDir) })
        assertTrue(req.classpath.isNotEmpty())
        assertEquals(17, req.javaMajor)
    }

    @Test
    fun `legacy minecraftArguments split`() {
        val json = VersionJson(
            id = "1.7.10",
            mainClass = "net.minecraft.launchwrapper.Launch",
            minecraftArguments = "--username \${auth_player_name} --gameDir \${game_directory} --version \${version_name}",
            assets = "1.7.10",
            javaVersion = VersionJson.JavaVersion(majorVersion = 8)
        )
        val req = ArgumentBuilder.build("1.7.10", json, layout, Account("Alex"), "inst2")
        assertTrue(req.gameArgs.contains("Alex"))
        assertTrue(req.gameArgs.contains("--gameDir"))
        assertTrue(req.gameArgs.contains(layout.gameDir))
        assertEquals(8, req.javaMajor)
    }

    @Test
    fun `rules in arguments are honored`() {
        val demoRule = mapOf(
            "rules" to listOf(mapOf("action" to "allow", "features" to mapOf("is_demo_user" to true))),
            "value" to "--demo"
        )
        val out = ArgumentBuilder.flattenArgs(
            listOf("--username", "Steve", demoRule as Any),
            emptyMap(),
            isJvm = false
        )
        assertTrue(out.contains("--username"))
        assertTrue(!out.contains("--demo"))
    }
}

class InstanceManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `create list update delete instance`() {
        val mgr = InstanceManager(tmp.root)
        val meta = InstanceMeta(name = "测试实例", versionId = "1.20.1")
        mgr.create(meta)

        val list = mgr.list()
        assertEquals(1, list.size)
        assertEquals("测试实例", list[0].displayName)

        assertTrue(mgr.dirOf(meta).isDirectory)
        assertTrue(mgr.gameDirOf(meta).resolve("mods").isDirectory)
        assertTrue(mgr.gameDirOf(meta).resolve("saves").isDirectory)

        mgr.update(meta.copy(name = "改名了"))
        assertEquals("改名了", mgr.list()[0].name)

        assertTrue(mgr.delete(meta.id))
        assertTrue(mgr.list().isEmpty())
    }

    @Test
    fun `shared libs dir points to common when enabled`() {
        val mgr = InstanceManager(tmp.root)
        val meta = InstanceMeta(versionId = "1.20.1", shareLibsAndAssets = true)
        mgr.create(meta)
        assertEquals(mgr.commonDir.resolve("libraries").path, mgr.sharedLibsDir(meta).path)

        val isolated = meta.copy(shareLibsAndAssets = false)
        assertTrue(mgr.sharedLibsDir(isolated).path.contains("libraries"))
        assertTrue(mgr.sharedLibsDir(isolated).path.contains(".minecraft"))
    }

    @Test
    fun `non isolated instance shares game dir`() {
        val mgr = InstanceManager(tmp.root)
        val meta = InstanceMeta(versionId = "1.20.1", isolated = false)
        mgr.create(meta)
        assertEquals(tmp.root.resolve(".minecraft").path, mgr.gameDirOf(meta).path)
    }

    @Test
    fun `已经存在的实例再次 create 不会覆盖磁盘上的改动`() {
        // 真 bug：prepare() 每次启动都会 create(meta)，而 meta 往往是主页那份旧缓存。
        // 无条件写入会把改名、javaMajor、lastPlayed 全部冲回旧值。
        val mgr = InstanceManager(tmp.root)
        val meta = InstanceMeta(name = "原名", versionId = "1.20.1", javaMajor = 17)
        mgr.create(meta)

        mgr.update(meta.copy(name = "新名", javaMajor = 21, lastPlayed = 12345L))

        // 拿着旧副本再 create 一次（模拟从主页列表点启动）
        val returned = mgr.create(meta)

        val stored = mgr.list().single()
        assertEquals("改名不该被冲掉", "新名", stored.name)
        assertEquals("javaMajor 不该被冲掉", 21, stored.javaMajor)
        assertEquals(12345L, stored.lastPlayed)
        assertEquals("create 应返回磁盘上的版本", "新名", returned.name)
    }

    @Test
    fun `首次 create 会正常写入`() {
        val mgr = InstanceManager(tmp.root)
        val meta = InstanceMeta(name = "第一次", versionId = "1.20.1", javaMajor = 8)
        val returned = mgr.create(meta)
        assertEquals("第一次", returned.name)
        assertEquals(8, returned.javaMajor)
        assertEquals(1, mgr.list().size)
    }

    @Test
    fun `create 仍然会补齐被删掉的目录骨架`() {
        val mgr = InstanceManager(tmp.root)
        val meta = InstanceMeta(versionId = "1.20.1")
        mgr.create(meta)
        val mods = mgr.gameDirOf(meta).resolve("mods")
        assertTrue(mods.deleteRecursively())

        mgr.create(meta)
        assertTrue("已存在的实例也要能把目录补回来", mods.isDirectory)
    }
}
