package com.moe.moetranslator.manga

import android.content.Context
import com.moe.moetranslator.utils.LogCollector

object CTDModelManager {

    private const val TAG = "CTDModelManager"
    private const val MODEL_DIR = "ctd"
    private const val MODEL_FILE = "comictextdetector.pt.onnx"

    fun getModelDir(): String = MODEL_DIR

    fun getModelFileName(): String = MODEL_FILE

    fun isModelComplete(context: Context): Boolean {
        try {
            val inputStream = context.assets.open("$MODEL_DIR/$MODEL_FILE")
            val size = inputStream.available()
            inputStream.close()
            if (size <= 0) {
                LogCollector.e(TAG, "模型文件为空: $MODEL_FILE")
                return false
            }
            LogCollector.d(TAG, "模型文件存在: $MODEL_FILE ($size bytes)")
        } catch (e: Exception) {
            LogCollector.e(TAG, "模型文件不存在: $MODEL_FILE", e)
            return false
        }
        return true
    }
}
