package com.moe.starflow.utils
import com.moe.starflow.translate.widget.*

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 统一日志收集器（内存缓冲 + 单文件落盘）
 *
 * 所有日志（Java 层 + native 层 + 崩溃 backtrace）统一写入同一个文件：
 * `getExternalFilesDir/logs/starflow.log`。
 *
 * - 内存缓冲：app 内日志查看器实时查看最近 500 条
 * - 文件落盘：统一格式追加写入，native 崩溃/进程死亡后日志仍保留
 * - 固定大小：超 2MB 时保留尾部（截掉最旧的一半），启动不清空
 *
 * ⚠️ 必须在 Application.onCreate 调用 [init] 后才能落盘（否则只有内存缓冲）。
 */
object LogCollector {

    // 日志 tag 常量
    const val TAG_OCR = "OCR"
    const val TAG_DETECTION = "DetectionBridge"

    /** 日志文件最大字节数（超限保留尾部，滚动截断） */
    const val MAX_LOG_BYTES = 2 * 1024 * 1024L

    /** 日志文件名（统一） */
    const val LOG_FILE_NAME = "starflow.log"

    /** 日志文件目录（init 后可用） */
    @Volatile
    private var logDir: File? = null

    /** 当前日志文件（追加写入） */
    @Volatile
    private var logFile: File? = null

    /**
     * 初始化文件落盘。幂等：重复调用只刷新路径。
     * @param context 应用上下文（取 applicationContext）
     */
    @Synchronized
    fun init(context: Context) {
        try {
            val dir = File(context.applicationContext.getExternalFilesDir(null), "logs")
            if (!dir.exists()) dir.mkdirs()
            logDir = dir
            logFile = File(dir, LOG_FILE_NAME)
            // 追加写入，不截断（保留跨会话日志，崩溃后仍可读）
        } catch (_: Exception) {
        }
    }

    /** 统一日志文件路径（Java + native 共用） */
    val logFilePath: String?
        get() = logFile?.absolutePath

    /** 统一日志文件（给 AboutMe 崩溃日志入口展示用） */
    val crashLogFile: File?
        get() = logFile

    /** 读取文件落盘的全部日志（native 崩溃 backtrace 也在这里） */
    fun readLogFile(): String {
        return try {
            logFile?.takeIf { it.exists() }?.readText() ?: ""
        } catch (_: Exception) {
            ""
        }
    }

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
    private val buffer = CopyOnWriteArrayList<LogEntry>()

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
        // 文件落盘：统一格式追加写入（尽力而为，失败不阻塞主流程）
        try {
            val file = logFile
            if (file != null) {
                synchronized(file) {
                    file.appendText(entry.format() + "\n")
                    rotateIfNeeded(file)
                }
            }
        } catch (_: Exception) {
        }
    }

    /**
     * 固定大小滚动：文件超 [MAX_LOG_BYTES] 时保留尾部一半（截掉最旧的一半）。
     * 启动不清空；超出固定大小才替换。截断只发生在整行边界，避免切断日志行。
     */
    private fun rotateIfNeeded(file: File) {
        if (file.length() <= MAX_LOG_BYTES) return
        try {
            val data = file.readText()
            val keepChars = data.length / 2
            // 从保留起点找下一个换行，避免截断一行
            var start = data.length - keepChars
            val nl = data.indexOf('\n', start)
            if (nl in 0 until data.length) start = nl + 1
            file.writeText("…（日志已滚动，截断最旧内容）…\n" + data.substring(start))
        } catch (_: Exception) {
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
