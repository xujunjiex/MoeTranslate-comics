package com.moe.moetranslator.manga

import android.content.Context
import com.moe.moetranslator.utils.LogCollector
import java.io.File
import java.io.IOException

/**
 * CTD (Comic Text Detector) 模型管理器
 *
 * 模型文件：comictextdetector.pt.onnx (~94MB)
 * 基于 YOLOv5 + UnetHead + DBHead 架构，用于漫画文字区域检测。
 *
 * 模型需从网络下载，存储在 filesDir/ctd/ 目录。
 * 下载地址：https://github.com/zyddnys/manga-image-translator/releases/download/beta-0.3/comictextdetector.pt.onnx
 */
object CTDModelManager {

    private const val TAG = "CTDModelManager"
    const val MODEL_DIR = "ctd"
    const val MODEL_FILE = "comictextdetector.pt.onnx"

    const val DOWNLOAD_URL = "https://github.com/zyddnys/manga-image-translator/releases/download/beta-0.3/comictextdetector.pt.onnx"

    fun getModelDir(): String = MODEL_DIR

    fun getModelFileName(): String = MODEL_FILE

    /**
     * 获取外部存储中的模型目录
     */
    fun getFilesDirModelDir(context: Context): File {
        return File(context.getExternalFilesDir(null), MODEL_DIR)
    }

    /**
     * 获取 filesDir 中的模型文件
     */
    fun getFilesDirModelFile(context: Context): File {
        return File(getFilesDirModelDir(context), MODEL_FILE)
    }

    /**
     * 检查模型是否已下载
     */
    fun isModelAvailable(context: Context): Boolean {
        return isModelInFilesDir(context)
    }

    /**
     * 检查外部存储中是否有模型
     */
    fun isModelInFilesDir(context: Context): Boolean {
        val modelFile = getFilesDirModelFile(context)
        return modelFile.exists() && modelFile.length() > 0
    }

    /**
     * 检查 assets 中是否有模型（已废弃，CTD 不再内置）
     */
    fun isModelInAssets(context: Context): Boolean {
        return try {
            context.assets.open("$MODEL_DIR/$MODEL_FILE").use { }
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 删除已下载的模型（仅删除 filesDir 中的）
     */
    fun deleteModel(context: Context): Result<Unit> {
        return try {
            val modelDir = getFilesDirModelDir(context)
            if (modelDir.exists()) {
                val deleted = modelDir.deleteRecursively()
                if (!deleted) {
                    LogCollector.e(TAG, "删除模型失败: ${modelDir.absolutePath}")
                    return Result.failure(IOException("Failed to delete model directory"))
                }
            }
            LogCollector.d(TAG, "模型已删除: ${modelDir.absolutePath}")
            Result.success(Unit)
        } catch (e: Exception) {
            LogCollector.e(TAG, "删除模型失败", e)
            Result.failure(e)
        }
    }

    /**
     * 获取模型大小
     */
    fun getModelSize(context: Context): Long {
        val modelDir = getFilesDirModelDir(context)
        if (!modelDir.exists()) return 0
        return modelDir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
    }

    /**
     * 获取模型大小描述
     */
    fun getModelSizeString(context: Context): String {
        val size = getModelSize(context)
        return when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> String.format("%.1f KB", size / 1024.0)
            else -> String.format("%.1f MB", size / (1024.0 * 1024.0))
        }
    }

    /**
     * 下载模型到 filesDir
     */
    suspend fun downloadModel(
        context: Context,
        onProgress: ModelDownloadManager.ProgressCallback? = null
    ): Result<Unit> {
        val modelDir = getFilesDirModelDir(context)
        if (!modelDir.exists()) {
            if (!modelDir.mkdirs()) {
                return Result.failure(IllegalStateException("Failed to create model directory: $modelDir"))
            }
        }

        val modelFile = getFilesDirModelFile(context)
        // 已存在则跳过
        if (modelFile.exists() && modelFile.length() > 0) {
            LogCollector.d(TAG, "模型已存在，跳过下载: ${modelFile.absolutePath}")
            return Result.success(Unit)
        }

        LogCollector.d(TAG, "开始下载 CTD 模型: $DOWNLOAD_URL")
        return ModelDownloadManager.downloadModel(
            context = context,
            url = DOWNLOAD_URL,
            sha256Hash = "",  // HuggingFace/GitHub 不提供 hash
            destFile = modelFile,
            onProgress = onProgress
        )
    }
}
