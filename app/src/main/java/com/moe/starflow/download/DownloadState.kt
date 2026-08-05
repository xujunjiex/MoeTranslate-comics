package com.moe.starflow.download
import com.moe.starflow.translate.widget.*

/**
 * 模型下载状态。
 *
 * 状态机：
 *   Idle ──markRunning──→ Running ──markPaused──→ Paused ──markRunning──→ Running
 *                            │                        │
 *                            │                        └─cancel──→ Idle
 *                            ├─markPartial──→ Partial ──markRunning──→ Running
 *                            └─markDone──→ Done
 *
 * - Idle: 无下载任务
 * - Running: 正在下载，包含实时进度（多文件时含 i/N + 当前文件进度）
 * - Paused: 用户主动暂停（运行时态，service 死后回到 Partial）
 * - Partial: 部分下载（.part 存在但未完成，或多文件组中部分文件未完成）
 * - Done: 全部文件下载完成且校验通过
 */
sealed class DownloadState {
    object Idle : DownloadState()

    data class Partial(
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val currentFileIndex: Int,          // 0-based 当前文件（正在下载或下一个待下载）
        val currentFileCount: Int,          // 单文件 = 1
        val currentFileName: String,        // 当前文件（正在下载或下一个待下载）
        val currentFileBytesDownloaded: Long,  // 当前文件已下载字节（含 .part）
        val currentFileTotalBytes: Long     // 当前文件总大小
    ) : DownloadState()

    data class Running(
        val bytesDownloaded: Long,           // 整体已下载字节（跨所有文件）
        val totalBytes: Long,                // 整体总字节（所有文件之和）
        val speedBytesPerSec: Long,
        val currentFileIndex: Int,     // 0-based；单文件始终为 0
        val currentFileCount: Int,     // 单文件始终为 1
        val currentFileName: String,   // 单文件 = fileInfo.fileName
        val currentFileProgress: Int,  // 当前文件 0-100
        val currentFileBytesDownloaded: Long,  // 当前文件已下载字节
        val currentFileTotalBytes: Long        // 当前文件总大小
    ) : DownloadState()

    data class Paused(
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val currentFileIndex: Int,          // 0-based 当前文件（正在下载或下一个待下载）
        val currentFileCount: Int,          // 单文件 = 1
        val currentFileName: String,        // 当前文件（正在下载或下一个待下载）
        val currentFileBytesDownloaded: Long,  // 当前文件已下载字节（含 .part）
        val currentFileTotalBytes: Long     // 当前文件总大小
    ) : DownloadState()

    object Done : DownloadState()
}