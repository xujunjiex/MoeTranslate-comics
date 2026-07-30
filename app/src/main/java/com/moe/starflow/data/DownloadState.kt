package com.moe.starflow.data

/**
 * 模型下载状态。
 *
 * 状态机：
 *   Idle ─markRunning─→ Running ─markDone─→ Done
 *                          │
 *                          └─markPartial─→ Partial ─markRunning─→ Running ...
 *
 * - Idle: 无下载任务
 * - Partial: 部分下载（.part 文件存在但未完成，或多文件组中部分文件已完成）
 * - Running: 正在下载，包含实时进度和速率
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
        val speedBytesPerSec: Long
    ) : DownloadState()

    object Done : DownloadState()
}