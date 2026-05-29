package com.moe.moetranslator.utils

import android.util.Log

/**
 * 内存日志收集器（环形缓冲区）
 *
 * 所有日志通过此类写入，同时写入 Android logcat 和内存缓冲区。
 * 用户可在设置页面查看最近的日志，方便复制错误信息。
 */
object LogCollector {

    // 日志 tag 常量
    const val TAG_OCR = "OCR"
    const val TAG_DETECTION = "DetectionBridge"

    data class LogEntry(
        val level: String,  // D, I, W, E, V
        val tag: String,
        val message: String,
        val throwable: Throwable? = null,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        fun format(): String {
            val time = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault())
                .format(java.util.Date(timestamp))
            val sb = StringBuilder("[$time] $level/$tag: $message")
            if (throwable != null) {
                sb.append("\n").append(Log.getStackTraceString(throwable))
            }
            return sb.toString()
        }
    }

    private const val MAX_ENTRIES = 500
    private val buffer = java.util.concurrent.CopyOnWriteArrayList<LogEntry>()

    fun v(tag: String, msg: String): Int {
        addEntry("V", tag, msg)
        return Log.v(tag, msg)
    }

    fun d(tag: String, msg: String): Int {
        addEntry("D", tag, msg)
        return Log.d(tag, msg)
    }

    fun i(tag: String, msg: String): Int {
        addEntry("I", tag, msg)
        return Log.i(tag, msg)
    }

    fun w(tag: String, msg: String): Int {
        addEntry("W", tag, msg)
        return Log.w(tag, msg)
    }

    fun w(tag: String, msg: String, tr: Throwable?): Int {
        addEntry("W", tag, msg, tr)
        return Log.w(tag, msg, tr)
    }

    fun e(tag: String, msg: String): Int {
        addEntry("E", tag, msg)
        return Log.e(tag, msg)
    }

    fun e(tag: String, msg: String, tr: Throwable?): Int {
        addEntry("E", tag, msg, tr)
        return Log.e(tag, msg, tr)
    }

    private fun addEntry(level: String, tag: String, msg: String, tr: Throwable? = null) {
        val entry = LogEntry(level, tag, msg, tr)
        buffer.add(entry)
        while (buffer.size > MAX_ENTRIES) {
            buffer.removeAt(0)
        }
    }

    /**
     * 获取所有日志（从旧到新）
     */
    fun getAllLogs(): List<LogEntry> {
        return buffer.toList().sortedBy { it.timestamp }
    }

    /**
     * 获取格式化的日志文本
     */
    fun getFormattedLogs(): String {
        return getAllLogs().joinToString("\n") { it.format() }
    }

    /**
     * 清空日志
     */
    fun clear() {
        buffer.clear()
    }
}
