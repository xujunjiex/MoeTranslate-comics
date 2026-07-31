package com.moe.starflow.data

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
        val totalBytes: Long
    ) : DownloadState()

    data class Running(
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val speedBytesPerSec: Long,
        val currentFileIndex: Int,     // 0-based；单文件始终为 0
        val currentFileCount: Int,     // 单文件始终为 1
        val currentFileName: String,   // 单文件 = fileInfo.fileName
        val currentFileProgress: Int   // 0-100；单文件 = 总进度
    ) : DownloadState()

    data class Paused(
        val bytesDownloaded: Long,
        val totalBytes: Long
    ) : DownloadState()

    object Done : DownloadState()
}