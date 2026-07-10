package com.moe.starflow.manga

import android.content.Context
import com.moe.starflow.utils.LogCollector

object DBNetModelManager {

    private const val TAG = "DBNetModelManager"
    private const val MODEL_DIR = "dbnet"
    private const val MODEL_FILE = "dbnet_detector.onnx"
    private const val MODEL_DATA_FILE = "dbnet_detector.onnx.data"

    fun getModelDir(): String = MODEL_DIR

    fun getModelFileName(): String = MODEL_FILE

    fun isModelComplete(context: Context): Boolean {
        // 必须有 .onnx 文件
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
        // 可选的 .onnx.data 外部数据文件
        try {
            val inputStream = context.assets.open("$MODEL_DIR/$MODEL_DATA_FILE")
            val size = inputStream.available()
            inputStream.close()
            LogCollector.d(TAG, "外部数据文件存在: $MODEL_DATA_FILE ($size bytes)")
        } catch (e: Exception) {
            LogCollector.d(TAG, "无外部数据文件（模型可能内嵌）: $MODEL_DATA_FILE")
        }
        return true
    }
}
