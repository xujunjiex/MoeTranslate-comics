package com.moe.moetranslator.manga

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.moe.moetranslator.utils.LogCollector
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.Collections

/**
 * manga-ocr ONNX 推理引擎
 *
 * 使用 ViT Encoder + BERT Decoder 进行漫画文字识别。
 * 支持多 Session 并行推理以提升吞吐量。
 */
object MangaOcrRecognizer {

    private const val TAG = "MangaOcrRecognizer"
    private const val IMAGE_SIZE = 224
    private const val MAX_NEW_TOKENS = 300

    // ONNX 环境和会话池
    private var ortEnv: OrtEnvironment? = null
    private var encoderSessions: List<OrtSession> = emptyList()
    private var decoderSessions: List<OrtSession> = emptyList()
    private var tokenizer: MangaOcrTokenizer? = null

    // Session 数量：取 CPU 核心数，限制在 2-4 之间
    private val sessionCount = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

    @Volatile
    var isInitialized = false
        private set

    /**
     * 初始化模型
     */
    suspend fun initialize(context: Context, modelDir: String = "manga_ocr", useAssets: Boolean = true) {
        if (isInitialized) return

        try {
            LogCollector.d(TAG, "开始初始化 manga-ocr 模型 (sessions=$sessionCount)...")

            ortEnv = OrtEnvironment.getEnvironment()

            val sessionOptions = OrtSession.SessionOptions().apply {
                setMemoryPatternOptimization(true)
                setCPUArenaAllocator(true)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setIntraOpNumThreads(2)
            }

            // 加载 encoder（多个 session）
            val encoderPath = if (useAssets) {
                copyAssetToCache(context, "$modelDir/manga_ocr_encoder.onnx")
            } else {
                "$modelDir/manga_ocr_encoder.onnx"
            }
            encoderSessions = (1..sessionCount).map {
                ortEnv!!.createSession(encoderPath, sessionOptions)
            }
            LogCollector.d(TAG, "Encoder 加载完成 ($sessionCount sessions)")

            // 加载 decoder（多个 session）
            val decoderPath = if (useAssets) {
                copyAssetToCache(context, "$modelDir/manga_ocr_decoder.onnx")
            } else {
                "$modelDir/manga_ocr_decoder.onnx"
            }
            decoderSessions = (1..sessionCount).map {
                ortEnv!!.createSession(decoderPath, sessionOptions)
            }
            LogCollector.d(TAG, "Decoder 加载完成 ($sessionCount sessions)")

            // 加载 tokenizer
            tokenizer = MangaOcrTokenizer(context).apply {
                loadFromAssets(modelDir)
            }
            LogCollector.d(TAG, "Tokenizer 加载完成")

            isInitialized = true
            LogCollector.d(TAG, "manga-ocr 模型初始化完成 ($sessionCount sessions)")

        } catch (e: Exception) {
            LogCollector.e(TAG, "初始化失败", e)
            release()
            throw e
        }
    }

    /**
     * 识别图片中的文字（使用 session 0）
     */
    fun recognize(bitmap: Bitmap): String {
        return recognizeWithSession(bitmap, 0)
    }

    /**
     * 使用指定 session 识别图片中的文字
     */
    private fun recognizeWithSession(bitmap: Bitmap, sessionIndex: Int): String {
        if (!isInitialized) {
            throw IllegalStateException("MangaOcrRecognizer 未初始化")
        }

        val idx = sessionIndex % encoderSessions.size
        val encSession = encoderSessions[idx]
        val decSession = decoderSessions[idx]

        try {
            // 1. 预处理图片
            val inputTensor = preprocessImage(bitmap)

            // 2. Encoder 推理
            val encoderResults = encSession.run(Collections.singletonMap("pixel_values", inputTensor))
            val encoderOutputs = encoderResults.get("last_hidden_state").get() as OnnxTensor
            inputTensor.close()

            // 3. Decoder 自回归生成
            val tokenIds = runDecoderWithSession(decSession, encoderOutputs)
            encoderOutputs.close()

            // 4. 解码为文字
            return tokenizer!!.decode(tokenIds)

        } catch (e: Exception) {
            LogCollector.e(TAG, "识别失败 (session $idx)", e)
            throw e
        }
    }

    /**
     * 批量识别图片中的文字（并行推理）。
     *
     * 使用多个 ONNX Session 并行处理，每个 bitmap 分配到不同的 session。
     * 当模型 batch_size=1 时，这是在 Android 端实现并行推理的最佳方式。
     *
     * @param bitmaps 待识别的裁剪图片列表
     * @return 识别结果列表，与输入顺序一一对应
     */
    suspend fun recognizeBatch(bitmaps: List<Bitmap>): List<String> {
        if (!isInitialized) {
            throw IllegalStateException("MangaOcrRecognizer 未初始化")
        }
        if (bitmaps.isEmpty()) return emptyList()
        if (bitmaps.size == 1) return listOf(recognize(bitmaps[0]))

        // 并行推理：每个 bitmap 分配到不同的 session
        return coroutineScope {
            bitmaps.mapIndexed { index, bitmap ->
                async(Dispatchers.Default) {
                    recognizeWithSession(bitmap, index)
                }
            }.awaitAll()
        }
    }

    /**
     * 预处理单张图片：resize + normalize
     */
    private fun preprocessImage(bitmap: Bitmap): OnnxTensor {
        val resized = Bitmap.createScaledBitmap(bitmap, IMAGE_SIZE, IMAGE_SIZE, true)

        val floatBuffer = FloatBuffer.allocate(1 * 3 * IMAGE_SIZE * IMAGE_SIZE)
        val pixels = IntArray(IMAGE_SIZE * IMAGE_SIZE)
        resized.getPixels(pixels, 0, IMAGE_SIZE, 0, 0, IMAGE_SIZE, IMAGE_SIZE)

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

        if (resized != bitmap) resized.recycle()

        return OnnxTensor.createTensor(ortEnv!!, floatBuffer, longArrayOf(1, 3, IMAGE_SIZE.toLong(), IMAGE_SIZE.toLong()))
    }

    /**
     * 使用指定 session 运行 Decoder（自回归生成，无 KV cache）
     */
    private fun runDecoderWithSession(decSession: OrtSession, encoderHiddenStates: OnnxTensor): List<Int> {
        val tok = tokenizer!!
        val generatedTokens = mutableListOf<Int>()

        // 初始输入: [CLS] token
        var currentIds = mutableListOf(tok.getBosTokenId())

        for (step in 0 until MAX_NEW_TOKENS) {
            // 传完整序列 [1, seq_len]（decoder 支持动态 seq_len）
            val inputIdsArray = currentIds.map { it.toLong() }.toLongArray()
            val inputIdsBuffer = java.nio.LongBuffer.wrap(inputIdsArray)
            val inputIdsTensor = OnnxTensor.createTensor(ortEnv!!, inputIdsBuffer, longArrayOf(1, inputIdsArray.size.toLong()))

            // 运行 decoder
            val inputs = mapOf(
                "input_ids" to inputIdsTensor,
                "encoder_hidden_states" to encoderHiddenStates
            )
            val outputs = decSession.run(inputs)

            // 获取最后一个 token 的 logits
            val logitsTensor = outputs.get("logits").get() as OnnxTensor
            val logits = logitsTensor.floatBuffer.array()
            val vocabSize = logits.size / currentIds.size
            val lastLogitsStart = (currentIds.size - 1) * vocabSize
            val lastLogits = logits.sliceArray(lastLogitsStart until lastLogitsStart + vocabSize)

            // Greedy decoding
            val nextTokenId = lastLogits.indices.maxByOrNull { lastLogits[it] } ?: 0

            // 检查 EOS
            if (nextTokenId == tok.getEosTokenId()) break

            generatedTokens.add(nextTokenId)
            currentIds.add(nextTokenId)

            // 清理
            inputIdsTensor.close()
            logitsTensor.close()
        }

        return generatedTokens
    }

    /**
     * 将 assets 文件复制到缓存目录
     * 如果是 .onnx 文件，同时复制对应的 .onnx.data 文件
     */
    private fun copyAssetToCache(context: Context, assetPath: String): String {
        val fileName = assetPath.substringAfterLast("/")
        val cacheFile = context.cacheDir.resolve(fileName)
        if (!cacheFile.exists()) {
            LogCollector.d(TAG, "复制 assets 文件: $assetPath -> ${cacheFile.absolutePath}")
            context.assets.open(assetPath).use { input ->
                cacheFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            LogCollector.d(TAG, "复制完成: ${cacheFile.absolutePath} (${cacheFile.length()} bytes)")
        } else {
            LogCollector.d(TAG, "文件已存在: ${cacheFile.absolutePath} (${cacheFile.length()} bytes)")
        }
        // 复制外部数据文件 (.onnx.data)
        if (assetPath.endsWith(".onnx")) {
            val dataAssetPath = "$assetPath.data"
            val dataFileName = dataAssetPath.substringAfterLast("/")
            val dataCacheFile = context.cacheDir.resolve(dataFileName)
            if (!dataCacheFile.exists()) {
                LogCollector.d(TAG, "复制外部数据文件: $dataAssetPath")
                try {
                    context.assets.open(dataAssetPath).use { input ->
                        dataCacheFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    LogCollector.d(TAG, "外部数据文件已复制: ${dataCacheFile.absolutePath} (${dataCacheFile.length()} bytes)")
                } catch (e: Exception) {
                    LogCollector.d(TAG, "外部数据文件不存在，跳过: $dataAssetPath")
                }
            } else {
                LogCollector.d(TAG, "外部数据文件已存在: ${dataCacheFile.absolutePath} (${dataCacheFile.length()} bytes)")
            }
        }
        return cacheFile.absolutePath
    }

    /**
     * 释放资源
     */
    fun release() {
        try {
            encoderSessions.forEach { try { it.close() } catch (_: Exception) {} }
            decoderSessions.forEach { try { it.close() } catch (_: Exception) {} }
            ortEnv?.close()
        } catch (e: Exception) {
            LogCollector.e(TAG, "释放资源失败", e)
        } finally {
            encoderSessions = emptyList()
            decoderSessions = emptyList()
            ortEnv = null
            tokenizer = null
            isInitialized = false
        }
    }
}
