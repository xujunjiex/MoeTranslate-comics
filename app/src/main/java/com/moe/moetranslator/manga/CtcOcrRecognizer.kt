package com.moe.moetranslator.manga

import android.content.Context
import android.graphics.Bitmap
import com.moe.moetranslator.utils.LogCollector
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.exp

/**
 * 48px_ctc ONNX 推理引擎
 *
 * ResNet + Transformer Encoder + CTC 线性层。
 * 一次前向传播直接输出字符 logits，无需自回归循环。
 * 支持 batch 推理（batch=16）。
 */
object CtcOcrRecognizer {

    private const val TAG = "CtcOcrRecognizer"
    private const val IMAGE_HEIGHT = 48
    private const val MAX_BATCH_SIZE = 16
    // 对齐官方阈值 0.5（48px_ctc执行流程.md: prob < threshold → 丢弃）
    private const val PROB_THRESHOLD = 0.5f
    private const val DEBUG_LOGS = false

    private var ortEnv: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var tokenizer: CtcOcrTokenizer? = null

    @Volatile
    var isInitialized = false
        private set

    suspend fun initialize(context: Context, modelDir: String = "ocr_ctc", useAssets: Boolean = true) {
        if (isInitialized) return

        // 如果 filesDir 中已有模型文件，优先从 filesDir 加载
        val actualUseAssets = if (!useAssets) {
            // 显式指定 useAssets=false，从 filesDir 加载
            false
        } else {
            // 检查 filesDir 是否有模型文件
            val modelFile = File(context.filesDir, "$modelDir/${CtcOcrModelManager.MODEL_FILE}")
            if (modelFile.exists()) {
                LogCollector.d(TAG, "检测到 filesDir 中已有模型文件，优先从 filesDir 加载")
                false
            } else {
                // 从 assets 加载（首次使用，模型在 assets 中）
                true
            }
        }

        try {
            LogCollector.d(TAG, "开始初始化 48px_ctc 模型 (useAssets=$actualUseAssets)...")
            ortEnv = OrtEnvironment.getEnvironment()
            val sessionOptions = OrtSession.SessionOptions().apply {
                setMemoryPatternOptimization(true)
                setCPUArenaAllocator(true)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setIntraOpNumThreads(4)
            }

            // 使用 actualUseAssets 决定模型路径
            val modelPath = if (actualUseAssets) {
                copyAssetToCache(context, "$modelDir/${CtcOcrModelManager.MODEL_FILE}")
            } else {
                File(context.filesDir, "$modelDir/${CtcOcrModelManager.MODEL_FILE}").absolutePath
            }
            session = ortEnv!!.createSession(modelPath, sessionOptions)
            LogCollector.d(TAG, "模型加载完成")

            tokenizer = CtcOcrTokenizer(context).apply {
                // 同样检查 tokenizer 文件位置
                val vocabFile = File(context.filesDir, "$modelDir/${CtcOcrModelManager.ALPHABET_FILE}")
                if (vocabFile.exists()) {
                    loadFromFile(vocabFile)
                } else {
                    loadFromAssets(modelDir)
                }
            }
            LogCollector.d(TAG, "Tokenizer 加载完成, 字典大小=${tokenizer!!.getDictionarySize()}")

            // 验证 tokenizer 初始化成功
            if (tokenizer!!.getDictionarySize() == 0) {
                LogCollector.e(TAG, "Tokenizer 字典为空，初始化失败")
                release()
                throw IllegalStateException("Tokenizer initialization failed - empty dictionary")
            }

            // 诊断：打印 ONNX 模型元数据
            try {
                val inputInfo = session?.inputInfo
                val outputInfo = session?.outputInfo
                LogCollector.d(TAG, "=== ONNX 模型元数据 ===")
                inputInfo?.forEach { (name, info) ->
                    val shape = (info.info as? TensorInfo)?.shape?.contentToString() ?: "N/A"
                    LogCollector.d(TAG, "输入: name=$name, shape=$shape")
                }
                outputInfo?.forEach { (name, info) ->
                    val shape = (info.info as? TensorInfo)?.shape?.contentToString() ?: "N/A"
                    LogCollector.d(TAG, "输出: name=$name, shape=$shape")
                }
            } catch (e: Exception) {
                LogCollector.e(TAG, "获取模型元数据失败", e)
            }

            isInitialized = true
            LogCollector.d(TAG, "48px_ctc 模型初始化完成")
        } catch (e: Exception) {
            LogCollector.e(TAG, "初始化失败", e)
            release()
            throw e
        }
    }

    /**
     * 识别单张图片
     */
    fun recognize(bitmap: Bitmap): String {
        if (!isInitialized) throw IllegalStateException("CtcOcrRecognizer 未初始化")
        try {
            return recognizeBatchInternal(listOf(bitmap))[0].first
        } catch (e: Exception) {
            LogCollector.e(TAG, "识别失败", e)
            throw e
        }
    }

    /**
     * 批量识别（支持 batch 推理，每 batch 最多 MAX_BATCH_SIZE 张）
     * 概率 < PROB_THRESHOLD 的返回空字符串。
     */
    suspend fun recognizeBatch(bitmaps: List<Bitmap>): List<String> {
        if (!isInitialized) throw IllegalStateException("CtcOcrRecognizer 未初始化")
        if (bitmaps.isEmpty()) return emptyList()

        val results = mutableListOf<String>()
        for (chunk in bitmaps.chunked(MAX_BATCH_SIZE)) {
            for ((text, prob) in recognizeBatchInternal(chunk)) {
                results.add(if (prob >= PROB_THRESHOLD) text else "")
            }
        }
        return results
    }

    /**
     * 批量识别，返回文字和概率。
     */
    suspend fun recognizeBatchWithProb(bitmaps: List<Bitmap>): List<Pair<String, Float>> {
        if (!isInitialized) throw IllegalStateException("CtcOcrRecognizer 未初始化")
        if (bitmaps.isEmpty()) return emptyList()

        val results = mutableListOf<Pair<String, Float>>()
        for (chunk in bitmaps.chunked(MAX_BATCH_SIZE)) {
            results.addAll(recognizeBatchInternal(chunk))
        }
        return results
    }

    /**
     * 批量识别并返回颜色信息（支持 batch 推理，每 batch 最多 MAX_BATCH_SIZE 张）
     *
     * @return 每个元素是 [prob, fg_r, fg_g, fg_b, bg_r, bg_g, bg_b] 的 FloatArray
     */
    suspend fun recognizeBatchWithColors(bitmaps: List<Bitmap>): List<FloatArray> {
        if (!isInitialized) throw IllegalStateException("CtcOcrRecognizer 未初始化")
        if (bitmaps.isEmpty()) return emptyList()

        val results = mutableListOf<FloatArray>()
        for (chunk in bitmaps.chunked(MAX_BATCH_SIZE)) {
            results.addAll(recognizeBatchInternalWithColors(chunk))
        }
        return results
    }

    private fun recognizeBatchInternal(bitmaps: List<Bitmap>): List<Pair<String, Float>> {
        val N = bitmaps.size
        val dictSize = tokenizer!!.getDictionarySize()

        // 1. 统一高度为 48px，宽度按比例缩放
        val resized = bitmaps.map { bmp ->
            if (bmp.height == IMAGE_HEIGHT) {
                bmp
            } else {
                val scale = IMAGE_HEIGHT.toFloat() / bmp.height
                val newWidth = (bmp.width * scale).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(bmp, newWidth, IMAGE_HEIGHT, true)
            }
        }

        // 2. 按宽度排序（对齐 Python perm = sorted(..., key=width)）
        val sortedIndices = (0 until N).sortedBy { resized[it].width }

        // 3. 按排序顺序构建输入 tensor，每 batch 最多 MAX_BATCH_SIZE 个
        val results = arrayOfNulls<Pair<String, Float>>(N)
        for (batchStart in sortedIndices.indices step MAX_BATCH_SIZE) {
            val batchEnd = minOf(batchStart + MAX_BATCH_SIZE, sortedIndices.size)
            val batchIndices = sortedIndices.subList(batchStart, batchEnd)
            val batchN = batchIndices.size

            // padding 到 batch 内最大宽度
            val maxWidth = batchIndices.maxOf { resized[it].width }
            val alignedWidth = (4 * ((maxWidth + 7) / 4)) + 128

            LogCollector.d(TAG, "推理: N=$batchN, maxWidth=$maxWidth, alignedWidth=$alignedWidth, dictSize=$dictSize")

            // 构建输入 tensor [batchN, 3, 48, alignedWidth]
            // Python 参考: einops.rearrange(images, 'N H W C -> N C H W')
            // 即先全部 R，再全部 G，最后全部 B（按 channel 分组，不是逐像素交错）
            val totalFloats = batchN * 3 * IMAGE_HEIGHT * alignedWidth
            val byteBuffer = ByteBuffer.allocateDirect(totalFloats * 4).order(ByteOrder.nativeOrder())
            val floatBuffer = byteBuffer.asFloatBuffer()
            for (origIdx in batchIndices) {
                val bmp = resized[origIdx]
                val w = bmp.width
                val pixels = IntArray(w * IMAGE_HEIGHT)
                bmp.getPixels(pixels, 0, w, 0, 0, w, IMAGE_HEIGHT)
                // 提取 R/G/B 平面
                val rPlane = FloatArray(w * IMAGE_HEIGHT)
                val gPlane = FloatArray(w * IMAGE_HEIGHT)
                val bPlane = FloatArray(w * IMAGE_HEIGHT)
                for (i in pixels.indices) {
                    val pixel = pixels[i]
                    rPlane[i] = ((pixel shr 16 and 0xFF) - 127.5f) / 127.5f
                    gPlane[i] = ((pixel shr 8 and 0xFF) - 127.5f) / 127.5f
                    bPlane[i] = ((pixel and 0xFF) - 127.5f) / 127.5f
                }
                // 写入 R 平面（按 y,x 顺序）
                for (y in 0 until IMAGE_HEIGHT) {
                    for (x in 0 until w) {
                        floatBuffer.put(rPlane[y * w + x])
                    }
                    for (x in w until alignedWidth) {
                        floatBuffer.put(-1f)
                    }
                }
                // 写入 G 平面
                for (y in 0 until IMAGE_HEIGHT) {
                    for (x in 0 until w) {
                        floatBuffer.put(gPlane[y * w + x])
                    }
                    for (x in w until alignedWidth) {
                        floatBuffer.put(-1f)
                    }
                }
                // 写入 B 平面
                for (y in 0 until IMAGE_HEIGHT) {
                    for (x in 0 until w) {
                        floatBuffer.put(bPlane[y * w + x])
                    }
                    for (x in w until alignedWidth) {
                        floatBuffer.put(-1f)
                    }
                }
            }
            byteBuffer.rewind()
            floatBuffer.rewind()
            LogCollector.d(TAG, "Buffer: ${byteBuffer.capacity()} bytes, floatCount=$totalFloats")

            // 诊断：打印输入 tensor 统计
            if (floatBuffer.capacity() > 0) {
                val allVals = FloatArray(floatBuffer.capacity())
                floatBuffer.position(0)
                floatBuffer.get(allVals)
                floatBuffer.rewind()
                val minVal = allVals.minOrNull() ?: 0f
                val maxVal = allVals.maxOrNull() ?: 0f
                val meanVal = allVals.average().toFloat()
                LogCollector.d(TAG, "输入tensor统计: capacity=${floatBuffer.capacity()}, min=${String.format("%.4f", minVal)}, max=${String.format("%.4f", maxVal)}, mean=${String.format("%.4f", meanVal)}")
                val first10 = allVals.take(10).joinToString(", ") { String.format("%.4f", it) }
                LogCollector.d(TAG, "输入tensor前10值: $first10")
            }

            // 推理（对齐 Python: char_logits + color_values 双输出）
            val inputTensor = OnnxTensor.createTensor(
                ortEnv!!, floatBuffer,
                longArrayOf(batchN.toLong(), 3, IMAGE_HEIGHT.toLong(), alignedWidth.toLong())
            )
            val outputs = session!!.run(mapOf("images" to inputTensor))
            val logitsTensor = outputs.get("char_logits").get() as OnnxTensor
            val logitsShape = logitsTensor.info.shape
            val actualSeqLen = logitsShape[1].toInt()
            val logits = logitsTensor.floatBuffer.array()

            // 诊断：打印输出 tensor 统计
            val logitsMin = logits.minOrNull() ?: 0f
            val logitsMax = logits.maxOrNull() ?: 0f
            val logitsMean = logits.average().toFloat()
            LogCollector.d(TAG, "输出tensor: shape=${logitsShape.contentToString()}, arraySize=${logits.size}")
            LogCollector.d(TAG, "输出tensor统计: min=${String.format("%.4f", logitsMin)}, max=${String.format("%.4f", logitsMax)}, mean=${String.format("%.4f", logitsMean)}")
            // 打印第一个样本第一个时间步的 top-5 logits
            if (logits.size >= dictSize) {
                val top5 = logits.take(dictSize).withIndex().sortedByDescending { it.value }.take(5)
                LogCollector.d(TAG, "样本0,步0 top5: ${top5.joinToString { "${tokenizer!!.getDictionary().getOrElse(it.index){"?"}}: ${String.format("%.4f", it.value)}" }}")
            }

            inputTensor.close()
            logitsTensor.close()

            // CTC 解码 + 概率
            for ((batchIdx, origIdx) in batchIndices.withIndex()) {
                val offset = batchIdx * actualSeqLen * dictSize
                val sampleLogits = logits.copyOfRange(offset, offset + actualSeqLen * dictSize)
                val (text, prob) = tokenizer!!.decodeCtcWithProb(sampleLogits, actualSeqLen, dictSize)
                results[origIdx] = text to prob
                LogCollector.d(TAG, "样本$origIdx: prob=${String.format("%.6f", prob)}, text='$text'")
            }
        }

        // 回收临时 bitmap
        for ((idx, bmp) in resized.withIndex()) {
            if (bmp !== bitmaps[idx]) {
                bmp.recycle()
            }
        }

        return results.map { it ?: ("" to 0f) }
    }

    /**
     * 批量识别并返回颜色信息（内部实现）
     * 对齐 Python decode_ctc_top1：CTC 解码 + 颜色提取。
     * 按宽度排序 + 分 batch 推理（对齐 recognizeBatchInternal）。
     *
     * @return 每个元素是 [prob, fg_r, fg_g, fg_b, bg_r, bg_g, bg_b] 的 FloatArray
     */
    private fun recognizeBatchInternalWithColors(bitmaps: List<Bitmap>): List<FloatArray> {
        val N = bitmaps.size
        val dictSize = tokenizer!!.getDictionarySize()

        // 1. 统一高度为 48px，宽度按比例缩放
        val resized = bitmaps.map { bmp ->
            if (bmp.height == IMAGE_HEIGHT) {
                bmp
            } else {
                val scale = IMAGE_HEIGHT.toFloat() / bmp.height
                val newWidth = (bmp.width * scale).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(bmp, newWidth, IMAGE_HEIGHT, true)
            }
        }

        // 2. 按宽度排序（对齐 Python perm = sorted(..., key=width)）
        val sortedIndices = (0 until N).sortedBy { resized[it].width }

        // 3. 按排序顺序构建输入 tensor，每 batch 最多 MAX_BATCH_SIZE 个
        val results = arrayOfNulls<FloatArray>(N)
        for (batchStart in sortedIndices.indices step MAX_BATCH_SIZE) {
            val batchEnd = minOf(batchStart + MAX_BATCH_SIZE, sortedIndices.size)
            val batchIndices = sortedIndices.subList(batchStart, batchEnd)
            val batchN = batchIndices.size

            // padding 到 batch 内最大宽度（对齐 Python: (4*(max+7)//4)+128）
            val maxWidth = batchIndices.maxOf { resized[it].width }
            val alignedWidth = (4 * ((maxWidth + 7) / 4)) + 128

            LogCollector.d(TAG, "推理(带颜色): N=$batchN, maxWidth=$maxWidth, alignedWidth=$alignedWidth, dictSize=$dictSize")

            // 构建输入 tensor [batchN, 3, 48, alignedWidth]
            // Python 参考: einops.rearrange(images, 'N H W C -> N C H W')
            // 即先全部 R，再全部 G，最后全部 B（按 channel 分组，不是逐像素交错）
            val totalFloats = batchN * 3 * IMAGE_HEIGHT * alignedWidth
            val byteBuffer = ByteBuffer.allocateDirect(totalFloats * 4).order(ByteOrder.nativeOrder())
            val floatBuffer = byteBuffer.asFloatBuffer()
            for (origIdx in batchIndices) {
                val bmp = resized[origIdx]
                val w = bmp.width
                val pixels = IntArray(w * IMAGE_HEIGHT)
                bmp.getPixels(pixels, 0, w, 0, 0, w, IMAGE_HEIGHT)
                // 提取 R/G/B 平面
                val rPlane = FloatArray(w * IMAGE_HEIGHT)
                val gPlane = FloatArray(w * IMAGE_HEIGHT)
                val bPlane = FloatArray(w * IMAGE_HEIGHT)
                for (i in pixels.indices) {
                    val pixel = pixels[i]
                    rPlane[i] = ((pixel shr 16 and 0xFF) - 127.5f) / 127.5f
                    gPlane[i] = ((pixel shr 8 and 0xFF) - 127.5f) / 127.5f
                    bPlane[i] = ((pixel and 0xFF) - 127.5f) / 127.5f
                }
                // 写入 R 平面（按 y,x 顺序）
                for (y in 0 until IMAGE_HEIGHT) {
                    for (x in 0 until w) {
                        floatBuffer.put(rPlane[y * w + x])
                    }
                    for (x in w until alignedWidth) {
                        floatBuffer.put(-1f)
                    }
                }
                // 写入 G 平面
                for (y in 0 until IMAGE_HEIGHT) {
                    for (x in 0 until w) {
                        floatBuffer.put(gPlane[y * w + x])
                    }
                    for (x in w until alignedWidth) {
                        floatBuffer.put(-1f)
                    }
                }
                // 写入 B 平面
                for (y in 0 until IMAGE_HEIGHT) {
                    for (x in 0 until w) {
                        floatBuffer.put(bPlane[y * w + x])
                    }
                    for (x in w until alignedWidth) {
                        floatBuffer.put(-1f)
                    }
                }
            }
            byteBuffer.rewind()
            floatBuffer.rewind()

            // 4. 推理（对齐 Python: char_logits + color_values 双输出）
            val inputTensor = OnnxTensor.createTensor(
                ortEnv!!, floatBuffer,
                longArrayOf(batchN.toLong(), 3, IMAGE_HEIGHT.toLong(), alignedWidth.toLong())
            )
            val outputs = session!!.run(mapOf("images" to inputTensor))
            val logitsTensor = outputs.get("char_logits").get() as OnnxTensor
            val logitsShape = logitsTensor.info.shape
            val actualSeqLen = logitsShape[1].toInt()
            val logits = logitsTensor.floatBuffer.array()

            // 读取颜色输出（如果模型支持）
            val colorTensor = try {
                outputs.get("color_values").get() as OnnxTensor
            } catch (e: Exception) { null }
            val colorValues = colorTensor?.floatBuffer?.array()
            val colorShape = colorTensor?.info?.shape
            val actualColorSeqLen = if (colorShape != null) colorShape[1].toInt() else 0

            inputTensor.close()
            logitsTensor.close()
            colorTensor?.close()

            // 5. CTC 解码 + 颜色提取（对齐 Python decode_ctc_top1）
            val colorSeqLen = if (colorValues != null) actualColorSeqLen else 0

            for ((batchIdx, origIdx) in batchIndices.withIndex()) {
                val offset = batchIdx * actualSeqLen * dictSize
                val sampleLogits = logits.copyOfRange(offset, offset + actualSeqLen * dictSize)

                // CTC 解码（对齐 Python: log_softmax → argmax → 去重去 blank）
                var totalLogprob = 0.0
                var charCount = 0
                var totalFr = 0.0; var totalFg = 0.0; var totalFb = 0.0
                var totalBr = 0.0; var totalBg = 0.0; var totalBb = 0.0
                var colorCount = 0
                var lastId = 0

                for (t in 0 until actualSeqLen) {
                    val base = t * dictSize
                    // 数值稳定的 log-softmax
                    var maxVal = sampleLogits[base]
                    for (j in 1 until dictSize) {
                        if (sampleLogits[base + j] > maxVal) maxVal = sampleLogits[base + j]
                    }
                    var logSumExp = 0.0
                    for (j in 0 until dictSize) {
                        logSumExp += Math.exp((sampleLogits[base + j] - maxVal).toDouble())
                    }
                    val logNorm = maxVal.toDouble() - Math.log(logSumExp)

                    // argmax
                    var maxId = 0
                    for (j in 1 until dictSize) {
                        if (sampleLogits[base + j] > sampleLogits[base + maxId]) maxId = j
                    }
                    // CTC 去重 + 去 blank
                    if (maxId != 0 && maxId != lastId) {
                        totalLogprob += logNorm
                        charCount++

                        // 提取颜色（对齐 Python: clamp(0,1) 后乘 255，空格不参与）
                        if (colorValues != null && t < colorSeqLen) {
                            val colorBase = batchIdx * colorSeqLen * 6 + t * 6
                            if (colorBase + 5 < colorValues.size) {
                                val fr = colorValues[colorBase].coerceIn(0f, 1f)
                                val fg = colorValues[colorBase + 1].coerceIn(0f, 1f)
                                val fb = colorValues[colorBase + 2].coerceIn(0f, 1f)
                                val br = colorValues[colorBase + 3].coerceIn(0f, 1f)
                                val bg = colorValues[colorBase + 4].coerceIn(0f, 1f)
                                val bb = colorValues[colorBase + 5].coerceIn(0f, 1f)
                                // 检查是否为空格（空格不参与颜色平均）
                                val ch = tokenizer!!.getDictionary().getOrNull(maxId) ?: ""
                                if (ch != "<SP>" && ch != " ") {
                                    totalFr += fr; totalFg += fg; totalFb += fb
                                    totalBr += br; totalBg += bg; totalBb += bb
                                    colorCount++
                                }
                            }
                        }
                    }
                    lastId = maxId
                }

                val prob = if (charCount > 0) Math.exp(totalLogprob / charCount).toFloat() else 0f

                // 计算平均颜色（对齐 Python: mean(values) * 255）
                if (colorCount > 0) {
                    results[origIdx] = floatArrayOf(
                        prob,
                        (totalFr / colorCount * 255).toFloat(),
                        (totalFg / colorCount * 255).toFloat(),
                        (totalFb / colorCount * 255).toFloat(),
                        (totalBr / colorCount * 255).toFloat(),
                        (totalBg / colorCount * 255).toFloat(),
                        (totalBb / colorCount * 255).toFloat()
                    )
                } else {
                    results[origIdx] = floatArrayOf(
                        prob,
                        0f, 0f, 0f,    // fg: 黑色
                        255f, 255f, 255f  // bg: 白色
                    )
                }

                if (DEBUG_LOGS && charCount > 0) {
                    LogCollector.d(TAG, "样本$origIdx: prob=${String.format("%.6f", prob)}, colors=$colorCount")
                }
            }
        }

        // 6. 回收临时 bitmap
        for ((idx, bmp) in resized.withIndex()) {
            if (bmp !== bitmaps[idx]) {
                bmp.recycle()
            }
        }

        return results.map { it ?: floatArrayOf(0f, 0f, 0f, 0f, 255f, 255f, 255f) }
    }

    /**
     * 预热验证：尝试用临时 session 打开模型，成功才返回路径。
     * 失败时返回 null，触发调用方重新复制。
     */
    private fun validateModelFile(modelPath: String): Boolean {
        return try {
            val env = OrtEnvironment.getEnvironment()
            val session = env.createSession(modelPath, OrtSession.SessionOptions())
            session.close()
            env.close()
            true
        } catch (e: Exception) {
            LogCollector.d(TAG, "模型验证失败: $modelPath, ${e.message}")
            false
        }
    }

    /**
     * 强制重新复制 assets 文件到缓存目录
     */
    private fun forceCopyAssetToCache(context: Context, assetPath: String): String {
        val fileName = assetPath.substringAfterLast("/")
        val cacheFile = context.cacheDir.resolve(fileName)
        // 删除可能存在的损坏缓存
        if (cacheFile.exists()) {
            LogCollector.d(TAG, "删除损坏的缓存文件: ${cacheFile.absolutePath}")
            cacheFile.delete()
        }
        LogCollector.d(TAG, "复制 assets 文件: $assetPath -> ${cacheFile.absolutePath}")
        context.assets.open(assetPath).use { input ->
            cacheFile.outputStream().use { output -> input.copyTo(output) }
        }
        LogCollector.d(TAG, "复制完成: ${cacheFile.absolutePath} (${cacheFile.length()} bytes)")
        // 复制 .data 文件
        val dataAssetPath = "$assetPath.data"
        val dataFileName = dataAssetPath.substringAfterLast("/")
        val dataCacheFile = context.cacheDir.resolve(dataFileName)
        try {
            context.assets.open(dataAssetPath).use { input ->
                dataCacheFile.outputStream().use { output -> input.copyTo(output) }
            }
            LogCollector.d(TAG, "外部数据文件已复制: ${dataCacheFile.absolutePath} (${dataCacheFile.length()} bytes)")
        } catch (e: Exception) {
            LogCollector.d(TAG, "外部数据文件不存在，跳过: $dataAssetPath")
        }
        // 更新版本
        val prefs = context.getSharedPreferences("ocr_ctc_cache", Context.MODE_PRIVATE)
        val currentVersion = try {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        } catch (e: Exception) { 0L }
        prefs.edit().putLong("version_code", currentVersion).apply()
        return cacheFile.absolutePath
    }

    /**
     * 将 assets 文件复制到缓存目录（带验证）
     */
    private fun copyAssetToCache(context: Context, assetPath: String): String {
        val fileName = assetPath.substringAfterLast("/")
        val cacheFile = context.cacheDir.resolve(fileName)
        val prefs = context.getSharedPreferences("ocr_ctc_cache", Context.MODE_PRIVATE)
        val currentVersion = try {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        } catch (e: Exception) { 0L }
        val cachedVersion = prefs.getLong("version_code", 0)

        if (cacheFile.exists() && cachedVersion == currentVersion) {
            // 验证缓存文件是否有效
            if (validateModelFile(cacheFile.absolutePath)) {
                LogCollector.d(TAG, "模型已缓存，跳过复制: ${cacheFile.absolutePath}")
                return cacheFile.absolutePath
            } else {
                LogCollector.d(TAG, "缓存文件无效，强制重新复制")
            }
        }
        LogCollector.d(TAG, "复制 assets 文件: $assetPath -> ${cacheFile.absolutePath}")
        context.assets.open(assetPath).use { input ->
            cacheFile.outputStream().use { output -> input.copyTo(output) }
        }
        LogCollector.d(TAG, "复制完成: ${cacheFile.absolutePath} (${cacheFile.length()} bytes)")
        // 复制 .data 文件
        val dataAssetPath = "$assetPath.data"
        val dataFileName = dataAssetPath.substringAfterLast("/")
        val dataCacheFile = context.cacheDir.resolve(dataFileName)
        try {
            context.assets.open(dataAssetPath).use { input ->
                dataCacheFile.outputStream().use { output -> input.copyTo(output) }
            }
            LogCollector.d(TAG, "外部数据文件已复制: ${dataCacheFile.absolutePath} (${dataCacheFile.length()} bytes)")
        } catch (e: Exception) {
            LogCollector.d(TAG, "外部数据文件不存在，跳过: $dataAssetPath")
        }
        prefs.edit().putLong("version_code", currentVersion).apply()
        return cacheFile.absolutePath
    }

    fun release() {
        try {
            session?.close()
            ortEnv?.close()
        } catch (e: Exception) {
            LogCollector.e(TAG, "释放资源失败", e)
        } finally {
            session = null
            ortEnv = null
            tokenizer = null
            isInitialized = false
        }
    }
}
