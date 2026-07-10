package com.moe.starflow.manga

import android.content.Context
import android.util.Log
import com.moe.starflow.utils.LogCollector

/**
 * manga-ocr 模型文件管理器
 *
 * 从 assets 加载模型文件。
 */
object MangaOcrModelManager {

    private const val TAG = "MangaOcrModelManager"
    private const val MODEL_DIR = "manga_ocr"

    // 模型文件名
    private const val ENCODER_FILE = "manga_ocr_encoder.onnx"
    private const val ENCODER_DATA_FILE = "manga_ocr_encoder.onnx.data"
    private const val DECODER_FILE = "manga_ocr_decoder.onnx"
    private const val DECODER_DATA_FILE = "manga_ocr_decoder.onnx.data"
    private const val VOCAB_FILE = "vocab.txt"

    /**
     * 获取模型目录路径（assets 子目录）
     */
    fun getModelDir(): String = MODEL_DIR

    /**
     * 检查 assets 中的模型文件是否完整
     */
    fun isModelComplete(context: Context): Boolean {
        val requiredFiles = listOf(ENCODER_FILE, ENCODER_DATA_FILE, DECODER_FILE, DECODER_DATA_FILE, VOCAB_FILE)
        for (fileName in requiredFiles) {
            try {
                val inputStream = context.assets.open("$MODEL_DIR/$fileName")
                val size = inputStream.available()
                inputStream.close()
                if (size <= 0) {
                    LogCollector.e(TAG, "模型文件为空: $fileName")
                    return false
                }
                LogCollector.d(TAG, "模型文件存在: $fileName ($size bytes)")
            } catch (e: Exception) {
                LogCollector.e(TAG, "模型文件不存在: $fileName", e)
                return false
            }
        }
        return true
    }
}
