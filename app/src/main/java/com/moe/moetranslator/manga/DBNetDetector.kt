package com.moe.moetranslator.manga

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.moe.moetranslator.utils.LogCollector
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/**
 * DBNet 文字检测器（ONNX Runtime 推理）。
 *
 * 使用 ResNet34 + DBHead 检测图片中的文字区域。
 * 输入: [1, 3, H, W] float32, 归一化到 [-1, 1]
 * 输出: db [1, 2, H/4, W/4] + mask [1, 1, H, W]
 */
object DBNetDetector {

    private const val TAG = "DBNetDetector"
    private const val DETECT_SIZE = 2048  // 检测输入尺寸（与 manga-image-translator 对齐）

    private var ortEnv: OrtEnvironment? = null
    private var session: OrtSession? = null

    @Volatile
    var isInitialized = false
        private set

    /**
     * 初始化模型
     */
    suspend fun initialize(context: Context, modelDir: String = "dbnet", useAssets: Boolean = true) {
        if (isInitialized) return

        try {
            LogCollector.d(TAG, "开始初始化 DBNet 模型...")

            ortEnv = OrtEnvironment.getEnvironment()

            val sessionOptions = OrtSession.SessionOptions().apply {
                setMemoryPatternOptimization(false)
                setCPUArenaAllocator(false)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT)
            }

            val modelPath = if (useAssets) {
                copyAssetToCache(context, "$modelDir/${DBNetModelManager.getModelFileName()}")
            } else {
                "$modelDir/${DBNetModelManager.getModelFileName()}"
            }

            session = ortEnv!!.createSession(modelPath, sessionOptions)
            LogCollector.d(TAG, "DBNet 模型加载完成")

            isInitialized = true
            LogCollector.d(TAG, "DBNet 模型初始化完成")

        } catch (e: Exception) {
            LogCollector.e(TAG, "DBNet 初始化失败", e)
            release()
            throw e
        }
    }

    /**
     * 检测图片中的文字区域
     *
     * @param bitmap 输入图片
     * @return 检测到的文字区域 bounding box 列表（原图坐标）
     */
    fun detect(bitmap: Bitmap): List<Rect> {
        if (!isInitialized) {
            throw IllegalStateException("DBNetDetector 未初始化")
        }

        try {
            val origWidth = bitmap.width
            val origHeight = bitmap.height

            val (probMap, contentH, contentW) = runInference(bitmap)

            val boxes = DBNetPostProcessor.extractBoxes(
                probMap = probMap,
                height = contentH,
                width = contentW,
                origWidth = origWidth,
                origHeight = origHeight
            )

            LogCollector.d(TAG, "检测完成: ${boxes.size} 个文字区域")
            return boxes

        } catch (e: Exception) {
            LogCollector.e(TAG, "检测失败", e)
            throw e
        }
    }

    /**
     * 检测图片中的文字区域，返回 QuadBox（保留旋转信息）。
     * 用于 OCR 前的 box 合并。
     */
    fun detectQuadBoxes(bitmap: Bitmap): List<QuadBox> {
        if (!isInitialized) {
            throw IllegalStateException("DBNetDetector 未初始化")
        }

        try {
            val origWidth = bitmap.width
            val origHeight = bitmap.height

            val (probMap, contentH, contentW) = runInference(bitmap)

            val quadBoxes = DBNetPostProcessor.extractQuadBoxes(
                probMap = probMap,
                height = contentH,
                width = contentW,
                origWidth = origWidth,
                origHeight = origHeight
            )

            LogCollector.d(TAG, "检测完成: ${quadBoxes.size} 个 QuadBox")
            return quadBoxes

        } catch (e: Exception) {
            LogCollector.e(TAG, "检测失败", e)
            throw e
        }
    }

    /**
     * 运行 DBNet 推理，返回概率图和内容尺寸。
     */
    private fun runInference(bitmap: Bitmap): Triple<FloatArray, Int, Int> {
        // 1. 预处理：保持宽高比缩放 + padding 到 DETECT_SIZE×DETECT_SIZE
        val (inputTensor, contentH, contentW) = preprocessImage(bitmap)

        // 2. ONNX 推理
        val outputs = session!!.run(mapOf("input" to inputTensor))
        inputTensor.close()

        // 3. 获取输出
        val dbTensor = outputs.get("db").get() as OnnxTensor
        val dbData = extractFloatArray(dbTensor)
        val dbShape = dbTensor.info.shape  // [1, 2, H, W]

        val dbH = dbShape[2].toInt()
        val dbW = dbShape[3].toInt()

        LogCollector.d(TAG, "dbTensor shape: ${dbShape.contentToString()}, contentH=$contentH, contentW=$contentW")

        // 4. 后处理：取 channel 0 作为概率图，应用 sigmoid
        //    只取 contentH x contentW 区域（padding 区域无有效内容）
        val probMap = FloatArray(contentH * contentW)
        for (y in 0 until contentH) {
            for (x in 0 until contentW) {
                probMap[y * contentW + x] = sigmoid(dbData[y * dbW + x])
            }
        }

        dbTensor.close()
        outputs.close()

        return Triple(probMap, contentH, contentW)
    }

    /**
     * 预处理图片：保持宽高比缩放到 DETECT_SIZE×DETECT_SIZE（padding 填充），归一化到 [-1, 1]
     *
     * @return Triple(输入tensor, 实际内容高度, 实际内容宽度)（不含 padding）
     */
    private fun preprocessImage(bitmap: Bitmap): Triple<OnnxTensor, Int, Int> {
        val origW = bitmap.width
        val origH = bitmap.height

        // 保持宽高比缩放，使长边 = DETECT_SIZE
        val scale = DETECT_SIZE.toFloat() / maxOf(origW, origH)
        val contentW = ((origW * scale).toInt() / 32) * 32  // 对齐到 32 的倍数
        val contentH = ((origH * scale).toInt() / 32) * 32

        // 缩放图片
        val scaled = Bitmap.createScaledBitmap(bitmap, contentW, contentH, true)

        // 创建 DETECT_SIZE × DETECT_SIZE 画布，左上角放置缩放后的图片，其余填黑
        val padded = Bitmap.createBitmap(DETECT_SIZE, DETECT_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(padded)
        canvas.drawColor(android.graphics.Color.BLACK)
        canvas.drawBitmap(scaled, 0f, 0f, null)

        if (scaled != bitmap) scaled.recycle()

        // 提取像素
        val floatBuffer = FloatBuffer.allocate(1 * 3 * DETECT_SIZE * DETECT_SIZE)
        val pixels = IntArray(DETECT_SIZE * DETECT_SIZE)
        padded.getPixels(pixels, 0, DETECT_SIZE, 0, 0, DETECT_SIZE, DETECT_SIZE)
        padded.recycle()

        // CHW 格式，RGB 通道，归一化到 [-1, 1]
        for (c in 0 until 3) {
            for (i in pixels.indices) {
                val pixel = pixels[i]
                val value = when (c) {
                    0 -> ((pixel shr 16 and 0xFF) / 255.0f - 0.5f) / 0.5f  // R
                    1 -> ((pixel shr 8 and 0xFF) / 255.0f - 0.5f) / 0.5f   // G
                    2 -> ((pixel and 0xFF) / 255.0f - 0.5f) / 0.5f         // B
                    else -> 0f
                }
                floatBuffer.put(value)
            }
        }
        floatBuffer.rewind()

        val tensor = OnnxTensor.createTensor(
            ortEnv!!, floatBuffer,
            longArrayOf(1, 3, DETECT_SIZE.toLong(), DETECT_SIZE.toLong())
        )
        return Triple(tensor, contentH, contentW)
    }

    /**
     * sigmoid 函数：将 logits 转为 [0, 1] 概率
     */
    private fun sigmoid(x: Float): Float {
        return if (x >= 0) {
            1f / (1f + Math.exp(-x.toDouble())).toFloat()
        } else {
            val ex = Math.exp(x.toDouble()).toFloat()
            ex / (1f + ex)
        }
    }

    /**
     * 从 OnnxTensor 提取 float 数组
     */
    private fun extractFloatArray(tensor: OnnxTensor): FloatArray {
        val buffer = tensor.floatBuffer
        val arr = FloatArray(buffer.remaining())
        buffer.get(arr)
        buffer.rewind()
        return arr
    }

    /**
     * 将 assets 文件复制到缓存目录
     */
    private fun copyAssetToCache(context: Context, assetPath: String): String {
        val fileName = assetPath.substringAfterLast("/")
        val cacheFile = context.cacheDir.resolve(fileName)
        // .onnx 每次覆盖（模型结构可能更新），.onnx.data 仅在缺失时复制（权重不变）
        LogCollector.d(TAG, "复制 assets 文件: $assetPath -> ${cacheFile.absolutePath}")
        context.assets.open(assetPath).use { input ->
            cacheFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        LogCollector.d(TAG, "复制完成: ${cacheFile.absolutePath} (${cacheFile.length()} bytes)")
        // 复制外部数据文件 (.onnx.data) — 仅在缺失时
        if (assetPath.endsWith(".onnx")) {
            val dataAssetPath = "$assetPath.data"
            val dataFileName = dataAssetPath.substringAfterLast("/")
            val dataCacheFile = context.cacheDir.resolve(dataFileName)
            if (!dataCacheFile.exists()) {
                try {
                    context.assets.open(dataAssetPath).use { input ->
                        dataCacheFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    LogCollector.d(TAG, "外部数据文件已复制: ${dataCacheFile.absolutePath}")
                } catch (e: Exception) {
                    LogCollector.d(TAG, "无外部数据文件: $dataAssetPath")
                }
            }
        }
        return cacheFile.absolutePath
    }

    /**
     * 释放资源
     */
    fun release() {
        try {
            session?.close()
            ortEnv?.close()
        } catch (e: Exception) {
            LogCollector.e(TAG, "释放资源失败", e)
        } finally {
            session = null
            ortEnv = null
            isInitialized = false
        }
    }
}
