package com.moe.starflow.manga

import android.content.Context
import com.moe.starflow.utils.LogCollector
import java.io.File
import java.io.IOException

/**
 * RT-DETR-V2 模型管理器
 *
 * 模型文件：detector-v4-s_int8.onnx (~11MB)
 * RT-DETR-v2 r50vd 模型，微调用于漫画/网漫/美漫的文字和对话气泡检测。
 *
 * 模型需从网络下载，存储在 filesDir/rt_detr/ 目录。
 * 下载地址：https://huggingface.co/ogkalu/comic-text-and-bubble-detector
 */
object RTDetrModelManager {

    private const val TAG = "RTDetrModelManager"
    const val MODEL_DIR = "rt_detr"
    const val MODEL_FILE = "model.onnx"

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
     * 检查模型是否已下载（filesDir 或 assets）
     */
    fun isModelAvailable(context: Context): Boolean {
        return isModelInFilesDir(context) || isModelInAssets(context)
    }

    /**
     * 检查外部存储中是否有模型
     */
    fun isModelInFilesDir(context: Context): Boolean {
        val modelFile = getFilesDirModelFile(context)
        return modelFile.exists() && modelFile.length() > 0
    }

    /**
     * 检查 assets 中是否有模型（旧版内置）
     */
    fun isModelInAssets(context: Context): Boolean {
        return try {
            context.assets.open("bubble_detector/model.onnx").use { }
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
     * 获取已下载模型大小
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
}
