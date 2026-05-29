package com.moe.moetranslator.manga

import android.content.Context
import com.moe.moetranslator.utils.LogCollector
import java.io.File

/**
 * 48px_ctc OCR 的字典和 CTC 贪心解码器
 *
 * CTC 解码逻辑：argmax → 去重连续相同字符 → 去除 blank（index 0）
 */
class CtcOcrTokenizer(private val context: Context) {

    companion object {
        private const val TAG = "CtcOcrTokenizer"
        private const val BLANK_ID = 0
        private const val SPACE_TOKEN = "<SP>"
    }

    private lateinit var dictionary: List<String>

    fun loadFromAssets(assetDir: String = "ocr_ctc") {
        // 尝试多个可能的文件名
        val possibleNames = listOf("alphabet-all-v5.txt", "alphabet.txt")
        var loaded = false
        for (fileName in possibleNames) {
            try {
                val text = loadAssetText("$assetDir/$fileName")
                dictionary = text.lines().filter { it.isNotEmpty() }
                LogCollector.d(TAG, "字典加载完成: ${dictionary.size} 个字符, file=$fileName")
                loaded = true
                break
            } catch (e: Exception) {
                LogCollector.d(TAG, "尝试加载 $fileName 失败: ${e.message}")
            }
        }
        if (!loaded) {
            dictionary = emptyList()
            LogCollector.e(TAG, "无法加载字典文件")
        }
    }

    fun loadFromFile(file: File) {
        try {
            val text = file.readText(Charsets.UTF_8)
            dictionary = text.lines().filter { it.isNotEmpty() }
            LogCollector.d(TAG, "字典文件加载完成: ${dictionary.size} 个字符, file=${file.absolutePath}")
        } catch (e: Exception) {
            LogCollector.e(TAG, "从文件加载字典失败: ${file.absolutePath}", e)
            throw e
        }
    }

    /**
     * CTC 贪心解码
     *
     * @param logits [seqLen * vocabSize] 的 flat float 数组
     * @param seqLen 时间步数
     * @param vocabSize 字典大小
     * @return 识别的文字
     */
    fun decodeCtc(logits: FloatArray, seqLen: Int, vocabSize: Int): String {
        if (!::dictionary.isInitialized || dictionary.isEmpty()) {
            LogCollector.e(TAG, "字典未加载，解码跳过")
            return ""
        }
        val sb = StringBuilder()
        var lastId = BLANK_ID

        for (t in 0 until seqLen) {
            val offset = t * vocabSize
            // argmax
            var maxId = 0
            var maxVal = logits[offset]
            for (i in 1 until vocabSize) {
                if (logits[offset + i] > maxVal) {
                    maxVal = logits[offset + i]
                    maxId = i
                }
            }
            // 去重连续相同 + 去除 blank
            if (maxId != BLANK_ID && maxId != lastId) {
                val ch = dictionary.getOrNull(maxId) ?: ""
                if (ch == SPACE_TOKEN) sb.append(" ")
                else sb.append(ch)
            }
            lastId = maxId
        }
        return sb.toString()
    }

    /**
     * CTC 贪心解码，返回文字和概率。
     * 对齐 Python decode_ctc_top1 的 log_softmax + exp(mean(logprob)) 概率计算。
     *
     * @return Pair(文字, 概率)，概率 = exp(mean(logprobs))
     */
    fun decodeCtcWithProb(logits: FloatArray, seqLen: Int, vocabSize: Int): Pair<String, Float> {
        if (!::dictionary.isInitialized || dictionary.isEmpty()) {
            LogCollector.e(TAG, "字典未加载，解码跳过")
            return Pair("", 0f)
        }
        val sb = StringBuilder()
        var lastId = BLANK_ID
        var totalLogprob = 0.0
        var charCount = 0

        for (t in 0 until seqLen) {
            val offset = t * vocabSize

            // log_softmax: log(exp(x_i) / sum(exp(x_j))) = x_i - log(sum(exp(x_j)))
            var maxVal = logits[offset]
            for (i in 1 until vocabSize) {
                if (logits[offset + i] > maxVal) maxVal = logits[offset + i]
            }
            var logSumExp = 0.0
            for (i in 0 until vocabSize) {
                logSumExp += Math.exp((logits[offset + i] - maxVal).toDouble())
            }
            val logNorm = (maxVal - Math.log(logSumExp)).toFloat()

            // argmax over log-softmax values (Python: logprobs.max(2))
            var maxId = 0
            for (i in 1 until vocabSize) {
                if (logits[offset + i] > logits[offset + maxId]) maxId = i
            }

            // 去重连续相同 + 去除 blank
            if (maxId != BLANK_ID && maxId != lastId) {
                val ch = dictionary.getOrNull(maxId) ?: ""
                if (ch == SPACE_TOKEN) sb.append(" ") else sb.append(ch)
                // Python uses logprobs[b, t, pred_ch] = logNorm + logits[offset + maxId]
                // (since log_softmax[pred_ch] = logits[pred_ch] - log(sum(exp))))
                val charLogProb = (logits[offset + maxId] - logNorm).toDouble()
                // 诊断：如果 charLogProb > 0，说明 log-softmax 计算有异常
                if (charLogProb > 0) {
                    val rawLogit = logits[offset + maxId]
                    LogCollector.e(TAG, "异常 charLogProb>0: 步$t, char='$ch', rawLogit=$rawLogit, maxVal=$maxVal, logNorm=$logNorm, logSumExp=$logSumExp, charLogProb=$charLogProb")
                }
                totalLogprob += charLogProb
                charCount++
            }
            lastId = maxId
        }

        val prob = if (charCount > 0) Math.exp(totalLogprob / charCount).toFloat() else 0f
        // 诊断：打印前 3 个非空字符的 log-softmax 中间值
        if (charCount > 0) {
            var diagCount = 0
            var diagLastId = BLANK_ID
            for (t in 0 until seqLen) {
                if (diagCount >= 3) break
                val offset = t * vocabSize
                var maxVal = logits[offset]
                for (i in 1 until vocabSize) {
                    if (logits[offset + i] > maxVal) maxVal = logits[offset + i]
                }
                var logSumExp = 0.0
                for (i in 0 until vocabSize) {
                    logSumExp += Math.exp((logits[offset + i] - maxVal).toDouble())
                }
                val logNorm = (maxVal - Math.log(logSumExp)).toFloat()

                var maxId = 0
                for (i in 1 until vocabSize) {
                    if (logits[offset + i] > logits[offset + maxId]) maxId = i
                }
                if (maxId != BLANK_ID && maxId != diagLastId) {
                    val rawLogit = logits[offset + maxId]
                    val charLP = (rawLogit - logNorm).toDouble()
                    val ch = dictionary.getOrNull(maxId) ?: "?"
                    LogCollector.d(TAG, "诊断 步$t: char='$ch', rawLogit=${String.format("%.4f", rawLogit)}, logNorm=${String.format("%.4f", logNorm)}, charLogProb=${String.format("%.6f", charLP)}, exp(charLP)=${String.format("%.6f", Math.exp(charLP))}")
                    diagCount++
                }
                diagLastId = maxId
            }
        }
        if (charCount > 0) {
            LogCollector.d(TAG, "DEBUG decode: text='${sb}', charCount=$charCount, avgLogprob=${totalLogprob / charCount}, prob=$prob")
        }
        return sb.toString() to prob
    }

    fun getDictionarySize(): Int = if (::dictionary.isInitialized) dictionary.size else 0

    fun getDictionary(): List<String> = if (::dictionary.isInitialized) dictionary else emptyList()

    private fun loadAssetText(path: String): String {
        return context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
