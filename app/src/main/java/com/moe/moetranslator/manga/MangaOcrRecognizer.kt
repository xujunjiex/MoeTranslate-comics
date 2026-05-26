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
     *
     * @param context Context
     * @param modelDir 模型目录（仅用于 assets 模式）
     * @param useAssets 是否从 assets 加载，false 时从下载目录加载
     * @param version 模型版本（仅在 useAssets=false 时有效）
     */
    suspend fun initialize(context: Context, modelDir: String = "manga_ocr", useAssets: Boolean = true, version: MangaOcrDownloadManager.ModelVersion? = null) {
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

            // 加载 encoder（1 个 session，支持动态 batch_size）
            val encoderPath = when {
                useAssets -> copyAssetToCache(context, "$modelDir/manga_ocr_encoder.onnx")
                version != null -> MangaOcrDownloadManager.getEncoderFile(context, version).absolutePath
                else -> "$modelDir/manga_ocr_encoder.onnx"
            }
            encoderSessions = listOf(ortEnv!!.createSession(encoderPath, sessionOptions))
            LogCollector.d(TAG, "Encoder 加载完成 (1 session, 支持动态 batch)")

            // 加载 decoder（多个 session）
            val decoderPath = when {
                useAssets -> copyAssetToCache(context, "$modelDir/manga_ocr_decoder.onnx")
                version != null -> MangaOcrDownloadManager.getDecoderFile(context, version).absolutePath
                else -> "$modelDir/manga_ocr_decoder.onnx"
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
     * 批量识别图片中的文字（真正 batch 推理）。
     *
     * Encoder 一次处理 N 张图片（[N,3,224,224] → [N,197,768]），
     * 然后 Decoder 逐个/并行解码每个 hidden states。
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

        val encSession = encoderSessions[0]
        val tok = tokenizer!!

        try {
            // 1. 批量预处理：堆叠为 [N, 3, 224, 224] tensor
            val inputTensor = preprocessImages(bitmaps)

            // 2. Encoder 一次推理：[N, 3, 224, 224] → [N, 197, 768]
            LogCollector.d(TAG, "Encoder batch 推理: ${bitmaps.size} 张图片")
            val t0 = System.currentTimeMillis()
            val encoderResults = encSession.run(Collections.singletonMap("pixel_values", inputTensor))
            val encoderOutputs = encoderResults.get("last_hidden_state").get() as OnnxTensor
            inputTensor.close()
            LogCollector.d(TAG, "Encoder batch 完成: ${System.currentTimeMillis() - t0}ms")

            // 3. 逐个 Decoder 解码（多 Session 并行）
            val t1 = System.currentTimeMillis()
            val results = coroutineScope {
                (0 until bitmaps.size).map { i ->
                    async(Dispatchers.Default) {
                        val singleHidden = extractSingleBatch(encoderOutputs, i)
                        val tokenIds = runDecoderWithSession(decoderSessions[i % decoderSessions.size], singleHidden)
                        singleHidden.close()
                        tok.decode(tokenIds)
                    }
                }.awaitAll()
            }
            LogCollector.d(TAG, "Decoder 并行完成: ${System.currentTimeMillis() - t1}ms, 共 ${bitmaps.size} 个")

            encoderOutputs.close()
            return results

        } catch (e: Exception) {
            LogCollector.e(TAG, "批量识别失败，回退到逐个识别", e)
            return bitmaps.map { recognize(it) }
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
     * 批量预处理：将 N 张图片堆叠为 [N, 3, 224, 224] tensor。
     * 所有图片按 CHW 格式排列，归一化到 [-1, 1]。
     */
    private fun preprocessImages(bitmaps: List<Bitmap>): OnnxTensor {
        val N = bitmaps.size
        val channelSize = IMAGE_SIZE * IMAGE_SIZE
        val floatBuffer = FloatBuffer.allocate(N * 3 * channelSize)

        for (bitmap in bitmaps) {
            val resized = Bitmap.createScaledBitmap(bitmap, IMAGE_SIZE, IMAGE_SIZE, true)
            val pixels = IntArray(channelSize)
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

            if (resized !== bitmap) resized.recycle()
        }

        floatBuffer.rewind()
        return OnnxTensor.createTensor(
            ortEnv!!, floatBuffer,
            longArrayOf(N.toLong(), 3, IMAGE_SIZE.toLong(), IMAGE_SIZE.toLong())
        )
    }

    /**
     * 从 batched encoder outputs 中提取第 i 个 batch 的 hidden states。
     * 输入: [N, 197, 768] OnnxTensor
     * 输出: [1, 197, 768] OnnxTensor
     */
    private fun extractSingleBatch(batchedOutput: OnnxTensor, index: Int): OnnxTensor {
        val shape = batchedOutput.info.shape  // [N, 197, 768]
        val seqLen = shape[1].toInt()
        val hiddenSize = shape[2].toInt()

        val count: Int = seqLen * hiddenSize
        val buffer = FloatBuffer.allocate(count)
        val fullBuffer = batchedOutput.floatBuffer
        val offset: Int = index * count

        // 安全读取：支持 direct buffer 和 heap buffer
        for (i in 0 until count) {
            buffer.put(fullBuffer.get(offset + i))
        }
        buffer.rewind()

        return OnnxTensor.createTensor(ortEnv!!, buffer, longArrayOf(1, seqLen.toLong(), hiddenSize.toLong()))
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
     * 将 assets 文件复制到缓存目录。
     * 通过 APK 版本号判断是否需要更新：APK 升级后才重新复制，避免每次启动都复制大文件。
     * 如果是 .onnx 文件，同时复制对应的 .onnx.data 文件。
     */
    private fun copyAssetToCache(context: Context, assetPath: String): String {
        val fileName = assetPath.substringAfterLast("/")
        val cacheFile = context.cacheDir.resolve(fileName)

        // 检查 APK 版本，只在升级后才重新复制
        val prefs = context.getSharedPreferences("manga_ocr_cache", Context.MODE_PRIVATE)
        val currentVersion = try {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        } catch (e: Exception) { 0L }
        val cachedVersion = prefs.getLong("version_code", 0)

        if (cacheFile.exists() && cachedVersion == currentVersion) {
            LogCollector.d(TAG, "模型已缓存且版本一致，跳过复制: ${cacheFile.absolutePath}")
        } else {
            LogCollector.d(TAG, "复制 assets 文件: $assetPath -> ${cacheFile.absolutePath}")
            context.assets.open(assetPath).use { input ->
                cacheFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            LogCollector.d(TAG, "复制完成: ${cacheFile.absolutePath} (${cacheFile.length()} bytes)")
            // 复制外部数据文件 (.onnx.data)
            if (assetPath.endsWith(".onnx")) {
                val dataAssetPath = "$assetPath.data"
                val dataFileName = dataAssetPath.substringAfterLast("/")
                val dataCacheFile = context.cacheDir.resolve(dataFileName)
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
            }
            prefs.edit().putLong("version_code", currentVersion).apply()
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
