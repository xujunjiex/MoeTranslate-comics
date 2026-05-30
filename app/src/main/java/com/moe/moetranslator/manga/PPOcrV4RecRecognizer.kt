package com.moe.moetranslator.manga

import android.content.Context
import android.graphics.Bitmap
import com.moe.moetranslator.utils.LogCollector
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * PP-OCRv4 rec ONNX 推理引擎
 *
 * 预处理：BGR→CHW, (pixel-127.5)/127.5, 零 padding
 * Buffer：ByteBuffer.allocateDirect（对齐 CTC 已验证可用的 Direct Buffer 模式）
 * 后处理：CTC 贪心解码（blank=0, keys[maxId] 直接索引）
 */
object PPOcrV4RecRecognizer {

    private const val TAG = "PPOcrV4RecRecognizer"
    private const val IMAGE_HEIGHT = 48
    private const val MAX_BATCH_SIZE = 8

    private const val ASSET_MODEL_DIR = "ppocrv4_ja"
    private const val ASSET_MODEL_FILE = "rec.onnx"
    private const val ASSET_DICT_FILE = "japan_dict.txt"

    private var ortEnv: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var keys: List<String> = emptyList()

    @Volatile
    var isInitialized = false
        private set

    suspend fun initialize(context: Context) {
        if (isInitialized) return

        try {
            LogCollector.d(TAG, "开始初始化 PP-OCRv4 JA rec 模型...")
            ortEnv = OrtEnvironment.getEnvironment()
            val modelPath = copyAssetToCache(context, "$ASSET_MODEL_DIR/$ASSET_MODEL_FILE")

            session = try {
                ortEnv!!.createSession(modelPath, OrtSession.SessionOptions().apply {
                    setMemoryPatternOptimization(true)
                    setCPUArenaAllocator(true)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    addNnapi()
                })
            } catch (e: Exception) {
                LogCollector.d(TAG, "NNAPI 不可用，回退 CPU: ${e.message}")
                ortEnv!!.createSession(modelPath, OrtSession.SessionOptions().apply {
                    setMemoryPatternOptimization(true)
                    setCPUArenaAllocator(true)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    setIntraOpNumThreads(4)
                })
            }
            LogCollector.d(TAG, "模型加载完成")

            // 字典加载 — 对齐 Rec.kt
            val dictLines = context.assets.open("$ASSET_MODEL_DIR/$ASSET_DICT_FILE")
                .bufferedReader(Charsets.UTF_8)
                .use { it.readLines().toMutableList() }
            dictLines.add(0, "#")
            dictLines.add(" ")
            keys = dictLines.toList()
            LogCollector.d(TAG, "字典: ${keys.size} keys")

            // 打印模型信息
            try {
                session?.inputInfo?.forEach { (name, info) ->
                    val shape = (info.info as? TensorInfo)?.shape?.contentToString() ?: "?"
                    LogCollector.d(TAG, "输入: name=$name, shape=$shape")
                }
                session?.outputInfo?.forEach { (name, info) ->
                    val shape = (info.info as? TensorInfo)?.shape?.contentToString() ?: "?"
                    LogCollector.d(TAG, "输出: name=$name, shape=$shape")
                }
            } catch (e: Exception) { LogCollector.e(TAG, "获取元数据失败", e) }

            isInitialized = true
            LogCollector.d(TAG, "PP-OCRv4 JA rec 初始化完成")
        } catch (e: Exception) {
            LogCollector.e(TAG, "初始化失败", e)
            release()
            throw e
        }
    }

    suspend fun recognizeBatch(bitmaps: List<Bitmap>): List<String> {
        if (!isInitialized) throw IllegalStateException("PPOcrV4RecRecognizer 未初始化")
        if (bitmaps.isEmpty()) return emptyList()
        val results = mutableListOf<String>()
        for (chunk in bitmaps.chunked(MAX_BATCH_SIZE)) {
            results.addAll(recognizeBatchInternal(chunk))
        }
        return results
    }

    /**
     * 批量识别 — 对齐 CTC 的 ByteBuffer.allocateDirect 模式
     *
     * 通道顺序：B plane, G plane, R plane（PP-OCRv4 训练用 BGR，CHW 布局）
     * 归一化：(pixel - 127.5) / 127.5 → [-1, 1]
     * Padding：0f（零填充）
     */
    private fun recognizeBatchInternal(bitmaps: List<Bitmap>): List<String> {
        val N = bitmaps.size

        val resized = bitmaps.map { bmp ->
            if (bmp.height == IMAGE_HEIGHT) bmp
            else {
                val scale = IMAGE_HEIGHT.toFloat() / bmp.height
                val newWidth = (bmp.width * scale).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(bmp, newWidth, IMAGE_HEIGHT, true)
            }
        }

        val sortedIndices = (0 until N).sortedBy { resized[it].width }
        val results = arrayOfNulls<String>(N)

        for (batchIndices in sortedIndices.chunked(MAX_BATCH_SIZE)) {
            val batchN = batchIndices.size
            val maxWidth = batchIndices.maxOf { resized[it].width }
            // stride=4 对齐
            val alignedWidth = ((maxWidth + 3) / 4) * 4
            val imageSize = alignedWidth * IMAGE_HEIGHT
            val totalFloats = batchN * 3 * imageSize

            // Direct Buffer（对齐 CTC 已验证可用的模式）
            val byteBuffer = ByteBuffer.allocateDirect(totalFloats * 4).order(ByteOrder.nativeOrder())
            val floatBuffer = byteBuffer.asFloatBuffer()

            for (origIdx in batchIndices) {
                val bmp = resized[origIdx]
                val w = bmp.width
                val pixels = IntArray(w * IMAGE_HEIGHT)
                bmp.getPixels(pixels, 0, w, 0, 0, w, IMAGE_HEIGHT)

                // 提取 B/G/R 平面（PP-OCRv4 训练用 BGR）
                val planeB = FloatArray(w * IMAGE_HEIGHT)
                val planeG = FloatArray(w * IMAGE_HEIGHT)
                val planeR = FloatArray(w * IMAGE_HEIGHT)
                for (i in pixels.indices) {
                    val pixel = pixels[i]
                    planeB[i] = ((pixel and 0xFF) - 127.5f) / 127.5f          // B → plane[0]
                    planeG[i] = ((pixel shr 8 and 0xFF) - 127.5f) / 127.5f    // G → plane[1]
                    planeR[i] = ((pixel shr 16 and 0xFF) - 127.5f) / 127.5f   // R → plane[2]
                }

                // 写入 B 平面（按 y,x 顺序，padding 填 0）
                for (y in 0 until IMAGE_HEIGHT) {
                    for (x in 0 until w) floatBuffer.put(planeB[y * w + x])
                    for (x in w until alignedWidth) floatBuffer.put(0f)
                }
                // 写入 G 平面
                for (y in 0 until IMAGE_HEIGHT) {
                    for (x in 0 until w) floatBuffer.put(planeG[y * w + x])
                    for (x in w until alignedWidth) floatBuffer.put(0f)
                }
                // 写入 R 平面
                for (y in 0 until IMAGE_HEIGHT) {
                    for (x in 0 until w) floatBuffer.put(planeR[y * w + x])
                    for (x in w until alignedWidth) floatBuffer.put(0f)
                }
            }
            byteBuffer.rewind()
            floatBuffer.rewind()

            // 诊断
            if (floatBuffer.capacity() > 0) {
                val allVals = FloatArray(minOf(20, floatBuffer.capacity()))
                floatBuffer.position(0)
                floatBuffer.get(allVals)
                floatBuffer.rewind()
                LogCollector.d(TAG, "Buffer: ${byteBuffer.capacity()}B, floats=$totalFloats, alignedW=$alignedWidth")
                LogCollector.d(TAG, "输入前${allVals.size}: ${allVals.joinToString { String.format("%.4f", it) }}")
            }

            // 推理
            val env = ortEnv!!
            val inputTensor = OnnxTensor.createTensor(env, floatBuffer,
                longArrayOf(batchN.toLong(), 3, IMAGE_HEIGHT.toLong(), alignedWidth.toLong()))
            val inputName = session!!.inputInfo.keys.first()
            val outputName = session!!.outputInfo.keys.first()
            val outputs = session!!.run(mapOf(inputName to inputTensor))
            val onnxValue = outputs.get(outputName).get()
            val tensorValue = onnxValue.value as Array<Array<FloatArray>>

            inputTensor.close()
            outputs.close()

            LogCollector.d(TAG, "输出: [${tensorValue.size}, ${tensorValue[0].size}, ${tensorValue[0][0].size}]")

            // CTC 解码 — 对齐 scoreToTextLine
            for ((batchIdx, origIdx) in batchIndices.withIndex()) {
                val outputData = tensorValue[batchIdx]
                val sb = StringBuilder()
                var lastIndex = 0
                for (step in outputData) {
                    val max = step.withIndex().maxBy { it.value }
                    if (max.index in 1 until keys.size && max.index != lastIndex) {
                        sb.append(keys[max.index])
                    }
                    lastIndex = max.index
                }
                results[origIdx] = sb.toString()
                LogCollector.d(TAG, "样本$origIdx: '${sb}'")
            }
        }

        for ((idx, bmp) in resized.withIndex()) {
            if (bmp !== bitmaps[idx]) bmp.recycle()
        }

        return results.map { it ?: "" }
    }

    private fun copyAssetToCache(context: Context, assetPath: String): String {
        val fileName = assetPath.substringAfterLast("/")
        val cacheFile = context.cacheDir.resolve("ppocrv4_$fileName")
        val prefs = context.getSharedPreferences("ppocrv4_rec_cache", Context.MODE_PRIVATE)
        val currentVersion = try {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        } catch (e: Exception) { 0L }
        val cachedVersion = prefs.getLong("version_code", 0)

        if (cacheFile.exists() && cachedVersion == currentVersion) {
            LogCollector.d(TAG, "模型已缓存: ${cacheFile.absolutePath}")
            return cacheFile.absolutePath
        }

        LogCollector.d(TAG, "复制: $assetPath -> ${cacheFile.absolutePath}")
        context.assets.open(assetPath).use { input ->
            cacheFile.outputStream().use { output -> input.copyTo(output) }
        }
        LogCollector.d(TAG, "完成: ${cacheFile.absolutePath} (${cacheFile.length()} bytes)")

        val dataAssetPath = "$assetPath.data"
        val dataCacheFile = context.cacheDir.resolve("ppocrv4_${fileName}.data")
        try {
            context.assets.open(dataAssetPath).use { input ->
                dataCacheFile.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (_: Exception) {}

        prefs.edit().putLong("version_code", currentVersion).apply()
        return cacheFile.absolutePath
    }

    fun release() {
        try { session?.close(); ortEnv?.close() } catch (e: Exception) {
            LogCollector.e(TAG, "释放失败", e)
        } finally {
            session = null; ortEnv = null; keys = emptyList(); isInitialized = false
        }
    }
}
