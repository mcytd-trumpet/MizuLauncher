package com.mizulauncher.instance

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import java.util.UUID

/**
 * 实例（Instance）= PCL2 的「版本隔离」。
 *
 * 每个实例拥有独立的 `.minecraft` 目录：
 * ```
 * <root>/instances/<id>/
 *   ├── instance.json       实例元数据
 *   ├── .minecraft/         游戏目录（存档、模组、配置全部隔离）
 *   │    ├── versions/      版本 json / jar
 *   │    ├── libraries/     共享库（若开启全局共享则软链到公共目录）
 *   │    ├── assets/
 *   │    ├── mods/ saves/ resourcepacks/ shaderpacks/ config/
 *   └── icon.png
 * ```
 * 共享目录模式（PCL2 的「版本共享」）：只隔离 versions/mods/saves，libraries/assets 指回公共目录，
 * 可节省数 GB 空间——这在手机上非常关键。
 */
data class InstanceMeta(
    val id: String = UUID.randomUUID().toString().replace("-", "").take(12),
    /** 显示名称，例如「1.20.1 机械动力」 */
    val name: String = "",
    /** 对应的上游版本 id，例如 1.20.1 / 1.20.1-forge-47.2.0 */
    val versionId: String = "",
    /** 加载器类型：vanilla / fabric / quilt / forge / neoforge / optifine */
    val loaderType: String = "vanilla",
    val loaderVersion: String = "",
    val javaMajor: Int = 17,
    /** 图标：内置图标 key 或本地文件路径 */
    val iconKey: String = "default",
    /** 版本隔离：true = 独立 .minecraft；false = 共享公共目录 */
    val isolated: Boolean = true,
    /** 共享 libraries/assets 目录（省空间） */
    val shareLibsAndAssets: Boolean = true,
    /** 上次启动时间戳 */
    val lastPlayed: Long = 0L,
    /** 是否为主页置顶卡片 */
    val pinned: Boolean = true,
    /** JVM 参数覆盖（空则用全局） */
    val jvmArgs: String = "",
    val createdAt: Long = System.currentTimeMillis()
) {
    val displayName: String get() = name.ifBlank { versionId }
}

/**
 * 实例仓库：负责实例的增删改查与目录布局计算。
 * 纯 JVM 实现（File + Gson），便于单测。
 */
class InstanceManager(private val rootDir: File) {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    val instancesDir: File get() = File(rootDir, "instances")
    val commonDir: File get() = File(rootDir, "common")

    fun list(): List<InstanceMeta> {
        val dir = instancesDir
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { f -> f.isDirectory && File(f, META_FILE).isFile }
            ?.mapNotNull { read(it) }
            ?.sortedByDescending { it.lastPlayed }
            ?: emptyList()
    }

    fun read(dir: File): InstanceMeta? = runCatching {
        gson.fromJson(File(dir, META_FILE).readText(), InstanceMeta::class.java)
    }.getOrNull()

    /**
     * 创建实例。**已存在则原样返回磁盘上的元数据，不覆盖。**
     *
     * 之前这里无条件 `writeText`，而 [LaunchOrchestrator.prepare] 每次启动都会先调 create()，
     * 于是内存里的旧副本（例如主页列表里那份）会把磁盘上更新过的 lastPlayed、javaMajor、
     * 重命名后的 name 全部冲回去。用户看到的现象就是「改了名字重启又变回来」「Java 版本
     * 每次启动都要重新探测一遍」。true 的语义应该是「确保存在」，不是「重新写入」。
     */
    fun create(meta: InstanceMeta): InstanceMeta {
        val dir = File(instancesDir, meta.id)
        dir.mkdirs()
        val file = File(dir, META_FILE)
        if (file.isFile) {
            ensureSkeleton(meta)
            return read(dir) ?: meta
        }
        file.writeText(gson.toJson(meta))
        ensureSkeleton(meta)
        return meta
    }

    fun update(meta: InstanceMeta) {
        val dir = File(instancesDir, meta.id)
        if (!dir.isDirectory) dir.mkdirs()
        File(dir, META_FILE).writeText(gson.toJson(meta))
        ensureSkeleton(meta)
    }

    fun delete(id: String, deleteFiles: Boolean = true): Boolean {
        val dir = File(instancesDir, id)
        if (!deleteFiles) {
            // 只从列表移除：改名为 .removed 后缀
            return dir.renameTo(File(instancesDir, "$id.removed"))
        }
        return dir.deleteRecursively()
    }

    /** 游戏目录（.minecraft） */
    fun gameDirOf(meta: InstanceMeta): File =
        if (meta.isolated) File(File(instancesDir, meta.id), ".minecraft") else File(rootDir, ".minecraft")

    fun dirOf(meta: InstanceMeta): File = File(instancesDir, meta.id)

    /** 公共库/资源目录：实例共享时指向这里，节省空间 */
    fun sharedLibsDir(meta: InstanceMeta): File =
        if (meta.shareLibsAndAssets) File(commonDir, "libraries") else File(gameDirOf(meta), "libraries")

    fun sharedAssetsDir(meta: InstanceMeta): File =
        if (meta.shareLibsAndAssets) File(commonDir, "assets") else File(gameDirOf(meta), "assets")

    /** 创建 PCL2 风格的标准目录骨架 */
    fun ensureSkeleton(meta: InstanceMeta) {
        val game = gameDirOf(meta)
        listOf(
            game, File(game, "versions"), File(game, "mods"), File(game, "saves"),
            File(game, "resourcepacks"), File(game, "shaderpacks"), File(game, "config"),
            File(game, "logs"), File(game, "screenshots"), File(game, "crash-reports")
        ).forEach { it.mkdirs() }
        if (!meta.shareLibsAndAssets) {
            File(game, "libraries").mkdirs()
            File(game, "assets").mkdirs()
        } else {
            File(commonDir, "libraries").mkdirs()
            File(commonDir, "assets").mkdirs()
        }
        if (meta.isolated && meta.shareLibsAndAssets) {
            // 用符号链接把 libraries/assets 指向公共目录（不支持软链时退化为软链接文件）
            linkOrCopyDir(File(game, "libraries"), sharedLibsDir(meta))
            linkOrCopyDir(File(game, "assets"), sharedAssetsDir(meta))
        }
    }

    private fun linkOrCopyDir(link: File, target: File) {
        if (link.exists()) return
        target.mkdirs()
        runCatching { java.nio.file.Files.createSymbolicLink(link.toPath(), target.toPath()) }
    }

    /** 统计实例占用空间（供 UI 显示） */
    fun sizeOf(meta: InstanceMeta): Long = dirOf(meta).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    companion object {
        const val META_FILE = "instance.json"
        const val DEFAULT_ROOT_NAME = ".pclmobile"
    }
}
