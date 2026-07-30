package com.moe.starflow.data

import com.moe.starflow.manga.ModelKey

/**
 * 模型元信息（从 assets/models/downloadinfo.json 加载）。
 *
 * @param modelKey 模型唯一标识（与 enum 一一对应）
 * @param browserUrl 在浏览器中打开的 URL（供 UI 「在浏览器中下载」按钮使用）
 * @param files 模型包含的文件列表（单文件为 1 项，多文件组为 N 项）
 */
data class ModelInfo(
    val modelKey: ModelKey,
    val browserUrl: String,
    val files: List<FileInfo>
)

/**
 * 单个下载文件信息。
 *
 * @param fileName 文件名（如 `encoder_model.onnx`），下载到 baseDir 下
 * @param downloadUrl 完整下载 URL
 * @param fileSize 期望文件大小（字节），用于下载完成后大小校验
 * @param checksum MD5 校验值（32 字符十六进制），为空表示跳过校验
 */
data class FileInfo(
    val fileName: String,
    val downloadUrl: String,
    val fileSize: Long,
    val checksum: String  // MD5
)