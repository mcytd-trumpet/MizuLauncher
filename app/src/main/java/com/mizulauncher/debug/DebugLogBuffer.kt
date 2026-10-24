package com.mizulauncher.debug

import java.util.ArrayDeque

/**
 * 内存环形日志缓冲。
 *
 * 调试场景下最常问的两个问题是「刚才那步为什么失败」和「卡在哪个文件」。
 * logcat 需要开 USB 调试或 root 才能看，用 rish 时反而绕了一圈。
 * 这里在进程内留最后 N 行，直接通过 `GET /logs` 拿走。
 *
 * 刻意做得极简：不加时间戳格式化、不加级别过滤，就是原始行。
 * 调试接口的价值在于「看得见」，不在于「好看」。
 *
 * ## 为什么用 ArrayDeque + 同步块
 *
 * 写入方可能来自任意线程（下载线程、IO 线程、主线程），读取方是 HTTP 线程。
 * 用 `synchronized` 保护一个 Deque，对这个量级（几千行）足够，
 * 比 `Collections.synchronizedList` 少一层包装，也比空转 CAS 好读。
 */
object DebugLogBuffer {

    private const val MAX_LINES = 3000

    private val lines = ArrayDeque<String>(MAX_LINES)
    private val lock = Any()
    private var dropped = 0L

    /** 追加一行。超过容量时丢最旧的 */
    fun append(line: String) {
        synchronized(lock) {
            if (lines.size >= MAX_LINES) {
                lines.pollFirst()
                dropped++
            }
            lines.addLast(line)
        }
    }

    /** 取最后 [n] 行 */
    fun tail(n: Int): List<String> = synchronized(lock) {
        if (lines.isEmpty()) return emptyList()
        val take = n.coerceAtLeast(1).coerceAtMost(lines.size)
        lines.toList().takeLast(take)
    }

    fun capacity(): Int = MAX_LINES

    fun size(): Int = synchronized(lock) { lines.size }

    /** 因容量上限被丢掉的行数，用来判断日志是否被截断过 */
    fun droppedCount(): Long = synchronized(lock) { dropped }

    fun clear() = synchronized(lock) {
        lines.clear()
        dropped = 0
    }
}
