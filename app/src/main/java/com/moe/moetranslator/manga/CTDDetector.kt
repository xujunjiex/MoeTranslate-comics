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
 * CTD（ComicTextDetector）文字检测器（ONNX Runtime 推理）。
 *
 * 使用 YOLOv5 backbone + UnetHead + DBHead 检测图片中的文字区域。
 * 与 DBNet 的区别：CTD 输出文字行级别区域，不需要 BoxMerger 合并。
 *
 * 输入: [1, 3, 1024, 1024] float32, 归一化到 [0, 1]
 * 输出: blks + mask [1, 1, H, W] + lines_map [1, 2, H, W]
 * 后处理: SegDetectorRepresenter（与 DBNet 相同）
 */
object CTDDetector {

    private const val TAG = "CTDDetector"
    private const val DETECT_SIZE = 1024  // CTD 输入尺寸
    private const val STRIDE = 64         // CTD stride 对齐

    private var ortEnv: OrtEnvironment? = null
    private var session: OrtSession? = null

    @Volatile
    var isInitialized = false
        private set

    /**
     * 初始化模型
     */
    suspend fun initialize(context: Context, modelDir: String = "ctd", useAssets: Boolean = true) {
        if (isInitialized) return

        try {
            LogCollector.d(TAG, "开始初始化 CTD 模型...")

            ortEnv = OrtEnvironment.getEnvironment()

            val sessionOptions = OrtSession.SessionOptions().apply {
                setMemoryPatternOptimization(false)
                setCPUArenaAllocator(false)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT)
            }

            val modelPath = if (useAssets) {
                copyAssetToCache(context, "$modelDir/${CTDModelManager.getModelFileName()}")
            } else {
                "$modelDir/${CTDModelManager.getModelFileName()}"
            }

            session = ortEnv!!.createSession(modelPath, sessionOptions)
            LogCollector.d(TAG, "CTD 模型加载完成")

            isInitialized = true
            LogCollector.d(TAG, "CTD 模型初始化完成")

        } catch (e: Exception) {
            LogCollector.e(TAG, "CTD 初始化失败", e)
            release()
            throw e
        }
    }

    /**
     * 检测图片中的文字区域，返回 QuadBox（保留旋转信息）。
     * CTD 输出文字行级别区域，不需要 BoxMerger 合并。
     */
    fun detectQuadBoxes(bitmap: Bitmap): List<QuadBox> {
        if (!isInitialized) {
            throw IllegalStateException("CTDDetector 未初始化")
        }

        try {
            val origWidth = bitmap.width
            val origHeight = bitmap.height

            val (linesMap, contentH, contentW) = runInference(bitmap)

            // 使用 CTDPostProcessor 的 SegDetectorRepresenter 逻辑（对齐 Python ctd.py）
            // 参数：textThreshold=0.3, boxThreshold=0.6, unclipRatio=1.5
            val quadBoxes = CTDPostProcessor.extractQuadBoxes(
                probMap = linesMap,
                height = contentH,
                width = contentW,
                origWidth = origWidth,
                origHeight = origHeight,
                textThreshold = 0.3f,
                boxThreshold = 0.6f,
                unclipRatio = 1.5f
            )

            LogCollector.d(TAG, "检测完成: ${quadBoxes.size} 个文字行")
            return quadBoxes

        } catch (e: Exception) {
            LogCollector.e(TAG, "检测失败", e)
            throw e
        }
    }

    /**
     * 运行 CTD 推理，返回 lines_map 的概率图和内容尺寸。
     */
    private fun runInference(bitmap: Bitmap): Triple<FloatArray, Int, Int> {
        // 1. 预处理：letterbox 缩放到 DETECT_SIZE×DETECT_SIZE（stride=64 对齐）
        val (inputTensor, contentH, contentW) = preprocessImage(bitmap)

        // 2. ONNX 推理（3 个输出：blks, mask, lines_map）
        val results: OrtSession.Result = session!!.run(mapOf("images" to inputTensor))
        inputTensor.close()

        // 3. 获取 lines_map 输出（det: [1, 2, H, W]，2 通道概率图）
        //    注意：必须精确匹配 name="det"，不能依赖顺序！
        //    因为 onnxruntime 遍历 session.outputNames 的顺序不固定，
        //    seg([1,1,1024,1024]) 可能先于 det([1,2,1024,1024]) 被遍历到。
        var linesMapTensor: OnnxTensor? = null
        for (name in session!!.outputNames) {
            val value = results.get(name)
            if (value.isPresent && value.get() is OnnxTensor) {
                val tensor = value.get() as OnnxTensor
                val shape = tensor.info.shape
                LogCollector.d(TAG, "输出: name=$name, shape=${shape.contentToString()}")
                // 精确匹配 name="det"（2 通道概率图，形状 [1, 2, H, W]）
                if (name == "det" && shape.size == 4 && shape[1].toInt() == 2) {
                    linesMapTensor = tensor
                    break
                }
            }
        }
        // 如果精确匹配失败（兼容部分 onnxruntime 变体），fallback 到之前的启发式逻辑
        if (linesMapTensor == null) {
            LogCollector.d(TAG, "精确匹配 det 失败，尝试 fallback 遍历")
            for (name in session!!.outputNames) {
                val value = results.get(name)
                if (value.isPresent && value.get() is OnnxTensor) {
                    val tensor = value.get() as OnnxTensor
                    val shape = tensor.info.shape
                    if (shape.size == 4 && shape[1].toInt() == 2) {
                        linesMapTensor = tensor
                        LogCollector.d(TAG, "Fallback 选中: name=$name")
                        break
                    }
                }
            }
        }
        if (linesMapTensor == null) {
            results.close()
            throw IllegalStateException("找不到 2 通道 det 输出 tensor")
        }

        val linesData = extractFloatArray(linesMapTensor)
        val linesShape = linesMapTensor.info.shape
        val linesC = linesShape[1].toInt()  // 通道数（可能是 1 或 2）
        val linesW = linesShape[3].toInt()
        LogCollector.d(TAG, "使用输出: shape=${linesShape.contentToString()}, contentH=$contentH, contentW=$contentW")

        // 4. 后处理：取 channel 0 作为概率图
        //    NCHW 布局：行步长 = linesC * linesW
        //    注意：CTD 模型的 det 输出已经过 sigmoid，不需要再应用
        val channelStride = linesC * linesW
        val probMap = FloatArray(contentH * contentW)
        for (y in 0 until contentH) {
            for (x in 0 until contentW) {
                probMap[y * contentW + x] = linesData[y * channelStride + x]
            }
        }

        linesMapTensor.close()
        results.close()

        return Triple(probMap, contentH, contentW)
    }

    /**
     * 预处理图片：letterbox 缩放到 DETECT_SIZE×DETECT_SIZE（stride=64 对齐），归一化到 [0, 1]
     *
     * 对齐 manga-image-translator 的 preprocess_img：
     * - letterbox 保持宽高比，stride=64 对齐
     * - 归一化到 [0, 1]（不是 [-1, 1]）
     */
    private fun preprocessImage(bitmap: Bitmap): Triple<OnnxTensor, Int, Int> {
        val origW = bitmap.width
        val origH = bitmap.height

        // 保持宽高比缩放，使长边 = DETECT_SIZE
        val scale = DETECT_SIZE.toFloat() / maxOf(origW, origH)
        val contentW = ((origW * scale).toInt() / STRIDE) * STRIDE  // 对齐到 STRIDE 的倍数
        val contentH = ((origH * scale).toInt() / STRIDE) * STRIDE

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

        // CHW 格式，RGB 通道，归一化到 [0, 1]（CTD 使用 [0,1] 而非 [-1,1]）
        for (c in 0 until 3) {
            for (i in pixels.indices) {
                val pixel = pixels[i]
                val value = when (c) {
                    0 -> (pixel shr 16 and 0xFF) / 255.0f  // R
                    1 -> (pixel shr 8 and 0xFF) / 255.0f   // G
                    2 -> (pixel and 0xFF) / 255.0f          // B
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
     * sigmoid 函数
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
        LogCollector.d(TAG, "复制 assets 文件: $assetPath -> ${cacheFile.absolutePath}")
        context.assets.open(assetPath).use { input ->
            cacheFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        LogCollector.d(TAG, "复制完成: ${cacheFile.absolutePath} (${cacheFile.length()} bytes)")
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
