package com.mizulauncher.auth.yggdrasil

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 外置账号的本地存储。
 *
 * ## 安全边界
 * 存的是 `accessToken`（相当于密码的等价物），所以：
 *  - 文件放在应用私有目录（`filesDir`），其他应用无权限访问；
 *  - **绝不存明文密码**，登录成功后立刻丢弃；
 *  - 写入用「先写临时文件再原子改名」，避免进程被杀时留下半个 JSON 导致下次启动解析失败、
 *    进而把用户账号"弄丢"——这类问题在低内存设备上非常常见。
 *
 * ## 为什么不用 DataStore / SharedPreferences
 * 账号数量少、结构简单，一个 JSON 文件足够。
 * DataStore 要引入协程 Flow 全套依赖，SharedPreferences 在多个账号并发写入时会丢更新。
 */
class AccountStore(context: Context) {

    private val file = File(context.filesDir, "accounts/yggdrasil.json")
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    /** 落盘结构：带一层 version，将来改格式时能识别旧文件 */
    private data class Envelope(
        val version: Int = 1,
        val sessions: List<YggdrasilSpec.StoredSession> = emptyList()
    )

    suspend fun loadAll(): List<YggdrasilSpec.StoredSession> = withContext(Dispatchers.IO) {
        if (!file.isFile) return@withContext emptyList()
        runCatching {
            val env = gson.fromJson(file.readText(), Envelope::class.java)
            env?.sessions ?: emptyList()
        }.getOrDefault(emptyList())
    }

    suspend fun save(session: YggdrasilSpec.StoredSession) = withContext(Dispatchers.IO) {
        val current = loadAll().toMutableList()
        // 同一 API 根 + 同一角色视为同一个账号，覆盖而不是追加
        current.removeAll {
            it.apiRoot == session.apiRoot && it.profileId == session.profileId
        }
        current.add(session)
        writeAll(current)
    }

    suspend fun remove(apiRoot: String, profileId: String?) = withContext(Dispatchers.IO) {
        val current = loadAll().filterNot {
            it.apiRoot == apiRoot && it.profileId == profileId
        }
        writeAll(current)
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        runCatching { file.delete() }
        Unit
    }

    private fun writeAll(list: List<YggdrasilSpec.StoredSession>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        runCatching {
            tmp.writeText(gson.toJson(Envelope(sessions = list)))
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }.onFailure {
            tmp.delete()
        }
    }
}
