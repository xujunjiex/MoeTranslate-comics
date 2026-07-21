package com.moe.starflow.manga

import android.graphics.Color
import com.moe.starflow.translate.TranslationResult
import com.moe.starflow.translate.TranslationTextAPI
import com.moe.starflow.utils.CustomPreference
import com.moe.starflow.utils.LogCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import translationapi.openaitranslation.OpenAITranslation
import java.util.LinkedList
import kotlin.coroutines.resume
import kotlin.math.abs

/**
 * 漫画翻译工具类，从 MangaFloatingService 抽取的静态翻译方法。
 * 所有方法均为纯函数，不依赖 Service 实例。
 */
object TranslateUtils {

    private const val TAG = "TranslateUtils"

    // ========== 对外入口 ==========

    /**
     * 翻译全部气泡。自动判断使用 AI 批量翻译还是机器逐个翻译。
     */
    suspend fun translateBubbles(
        translator: TranslationTextAPI,
        bubbles: List<BubbleRegion>,
        sourceLang: String,
        targetLang: String,
        prefs: CustomPreference,
        contextHistory: LinkedList<Pair<String, String>> = LinkedList(),
        forceContext: Boolean = false
    ): List<TranslatedBubble> {
        LogCollector.d(TAG, "translateBubbles: ${bubbles.size} bubbles, translator=${translator.javaClass.simpleName}")

        // 准备气泡数据：清理文本，过滤空的，分离纯符号气泡
        val preparedBubbles = mutableListOf<Pair<BubbleRegion, String>>()
        val symbolOnlyBubbles = mutableListOf<TranslatedBubble>()

        for (bubble in bubbles) {
            val cleaned = bubble.texts.map { cleanOcrText(it) }.filter { it.isNotBlank() }
            if (cleaned.isEmpty()) continue
            val combinedText = cleaned.joinToString("")

            if (isSymbolOnlyText(combinedText)) {
                LogCollector.d(TAG, "translateBubbles: skipping symbol-only: '$combinedText'")
                symbolOnlyBubbles.add(TranslatedBubble(
                    rect = bubble.rect,
                    originalText = combinedText,
                    translatedText = combinedText,
                    backgroundColor = Color.TRANSPARENT,
                    fontSize = bubble.fontSize,
                    direction = bubble.direction,
                    angle = bubble.angle,
                    centerX = bubble.centerX,
                    centerY = bubble.centerY
                ))
            } else {
                preparedBubbles.add(bubble to combinedText)
            }
        }
        if (preparedBubbles.isEmpty()) return symbolOnlyBubbles

        // AI 翻译（OpenAI 兼容）用批量请求，机器翻译用逐个请求
        val isAI = translator is OpenAITranslation
                || translator.javaClass.simpleName.contains("Custom")

        val translatedResults = if (isAI && preparedBubbles.size > 1) {
            translateBubblesBatch(translator, preparedBubbles, sourceLang, targetLang, prefs, contextHistory, forceContext)
        } else {
            translateBubblesSequential(translator, preparedBubbles, sourceLang, targetLang)
        }

        return symbolOnlyBubbles + translatedResults
    }

    // ========== AI 批量翻译 ==========

    /**
     * AI 翻译：所有气泡合并为一次请求，用编号分隔
     */
    private suspend fun translateBubblesBatch(
        translator: TranslationTextAPI,
        bubbles: List<Pair<BubbleRegion, String>>,
        sourceLang: String,
        targetLang: String,
        prefs: CustomPreference,
        contextHistory: LinkedList<Pair<String, String>> = LinkedList(),
        forceContext: Boolean = false
    ): List<TranslatedBubble> = withContext(Dispatchers.IO) {
        LogCollector.d(TAG, "translateBubblesBatch: ${bubbles.size} bubbles, forceContext=$forceContext")

        // 构建带编号的文本
        val numberedText = bubbles.mapIndexed { index, (_, text) ->
            "[${index + 1}] $text"
        }.joinToString("\n")

        val latch = java.util.concurrent.CountDownLatch(1)
        var resultText: String? = null
        var errorMsg: String? = null

        // 分批渲染强制开启上下文（仅批次间传递）；正常漫画翻译不使用上下文
        val currentContextEnabled = forceContext
        val currentContextMaxCount = try {
            prefs.getString("game_context_count", "5").toIntOrNull() ?: 5
        } catch (e: Exception) { 5 }

        // 更新 AI 上下文（仅 OpenAI 兼容 API）
        (translator as? OpenAITranslation)?.updateContext(
            if (currentContextEnabled) contextHistory.toList() else emptyList(),
            currentContextEnabled
        )

        // 用 suspendCancellableCoroutine 等待 callback：协程被 cancel 时立即返回，不再阻塞线程
        val completed = withTimeoutOrNull(35_000L) {
            suspendCancellableCoroutine<Unit> { cont ->
                cont.invokeOnCancellation {
                    // 协程被取消时尝试主动取消翻译任务
                    translator.cancelTranslation()
                }
                translator.getTranslation(
                    numberedText,
                    sourceLang,
                    targetLang
                ) { result ->
                    when (result) {
                        is TranslationResult.Success -> {
                            resultText = result.translatedText
                        }
                        is TranslationResult.Error -> {
                            errorMsg = result.error.message ?: "Unknown error"
                        }
                    }
                    if (cont.isActive) cont.resume(Unit)
                }
            }
        }
        if (completed == null) {
            translator.cancelTranslation()
            throw RuntimeException("AI batch translation timeout (35s)")
        }
        if (errorMsg != null) {
            throw RuntimeException("AI batch translation failed: $errorMsg")
        }

        // 按编号解析结果（支持 JSON 格式和编号格式）
        val result = resultText!!.trim()
        val translations = if (result.startsWith("{")) {
            parseJsonTranslations(result, bubbles.size)
        } else {
            parseNumberedTranslations(result, bubbles.size)
        }
        LogCollector.d(TAG, "translateBubblesBatch: parsed ${translations.size} translations")

        // 更新 AI 上下文历史（仅 OpenAI 兼容 API）
        if (currentContextEnabled && translations.isNotEmpty()) {
            val sourceText = bubbles.map { it.second }.joinToString("\n")
            val translatedText = translations.joinToString("\n")
            contextHistory.addLast(Pair(sourceText, translatedText))
            while (contextHistory.size > currentContextMaxCount) {
                contextHistory.removeFirst()
            }
            LogCollector.d(TAG, "上下文已更新: ${contextHistory.size}/$currentContextMaxCount 轮")
        }

        // 输出翻译结果
        for (i in translations.indices) {
            val (_, original) = bubbles[i]
            val translated = translations[i]
            LogCollector.d(TAG, "翻译结果[$i]: orig='$original' → trans='$translated'")
        }

        bubbles.mapIndexed { index, (bubble, originalText) ->
            if (abs(bubble.angle) > 0.5f) {
                LogCollector.d(TAG, "TranslatedBubble[$index]: angle=${bubble.angle}, cx=${bubble.centerX}, cy=${bubble.centerY}, text='${originalText.take(15)}'")
            }
            TranslatedBubble(
                rect = bubble.rect,
                originalText = originalText,
                translatedText = translations.getOrElse(index) { originalText },
                backgroundColor = Color.TRANSPARENT,
                fontSize = bubble.fontSize,
                direction = bubble.direction,
                angle = bubble.angle,
                centerX = bubble.centerX,
                centerY = bubble.centerY
            )
        }
    }

    // ========== 机器逐个翻译 ==========

    /**
     * 机器翻译：逐个气泡请求
     */
    private suspend fun translateBubblesSequential(
        translator: TranslationTextAPI,
        bubbles: List<Pair<BubbleRegion, String>>,
        sourceLang: String,
        targetLang: String
    ): List<TranslatedBubble> = coroutineScope {
        LogCollector.d(TAG, "translateBubblesConcurrent: ${bubbles.size} bubbles, concurrent")

        val deferreds = bubbles.map { (bubble, combinedText) ->
            async(Dispatchers.IO) {
                LogCollector.d(TAG, "translateBubblesConcurrent: translating '$combinedText'")

                val latch = java.util.concurrent.CountDownLatch(1)
                var successResult: TranslatedBubble? = null
                var errorMsg: String? = null

                translator.getTranslation(
                    combinedText,
                    sourceLang,
                    targetLang
                ) { result ->
                    when (result) {
                        is TranslationResult.Success -> {
                            LogCollector.d(TAG, "translateBubblesConcurrent: SUCCESS for '$combinedText'")
                            successResult = TranslatedBubble(
                                rect = bubble.rect,
                                originalText = combinedText,
                                translatedText = result.translatedText,
                                backgroundColor = Color.TRANSPARENT,
                                fontSize = bubble.fontSize,
                                direction = bubble.direction,
                                angle = bubble.angle,
                                centerX = bubble.centerX,
                                centerY = bubble.centerY
                            )
                        }
                        is TranslationResult.Error -> {
                            errorMsg = result.error.message ?: "Unknown error"
                            LogCollector.e(TAG, "translateBubblesConcurrent: ERROR: $errorMsg")
                        }
                    }
                    latch.countDown()
                }

                val completed = latch.await(30, java.util.concurrent.TimeUnit.SECONDS)
                if (!completed) {
                    errorMsg = "Translation timeout (30s)"
                }

                Pair(successResult, errorMsg)
            }
        }

        val allResults = deferreds.awaitAll()
        val results = mutableListOf<TranslatedBubble>()
        val errors = mutableListOf<String>()

        for ((successResult, errorMsg) in allResults) {
            if (successResult != null) {
                results.add(successResult)
            } else if (errorMsg != null) {
                errors.add(errorMsg)
            }
        }

        LogCollector.d(TAG, "translateBubblesConcurrent: ${results.size} successful out of ${bubbles.size}")
        if (results.isEmpty() && bubbles.isNotEmpty()) {
            val errorDetail = errors.distinct().joinToString("; ")
            throw RuntimeException("All bubbles failed to translate: $errorDetail")
        }
        results
    }

    // ========== 结果解析 ==========

    /**
     * 解析 JSON 格式的翻译结果
     * 支持格式:
     *   1. {"translations": ["译文1", "译文2"]}
     *   2. ["译文1", "译文2"]
     *   3. [{"translations": ["译文1"]}, ...] (模型可能返回的混合格式)
     */
    fun parseJsonTranslations(text: String, expectedCount: Int): List<String> {
        return try {
            val results = mutableListOf<String>()

            // 尝试解析为 JSON 对象 {"translations": [...]}
            try {
                val jsonObject = org.json.JSONObject(text)
                val translations = jsonObject.getJSONArray("translations")
                for (i in 0 until translations.length().coerceAtMost(expectedCount)) {
                    results.add(translations.getString(i))
                }
            } catch (_: Exception) {
                // 尝试解析为 JSON 数组
                val jsonArray = org.json.JSONArray(text)
                for (i in 0 until jsonArray.length().coerceAtMost(expectedCount)) {
                    val item = jsonArray.get(i)
                    when (item) {
                        is String -> results.add(item)
                        is org.json.JSONObject -> {
                            // 处理 {"translations": ["译文"]} 格式的数组元素
                            if (item.has("translations")) {
                                val arr = item.getJSONArray("translations")
                                if (arr.length() > 0) results.add(arr.getString(0))
                            }
                        }
                        // 跳过数字等其他类型（如 [2], [3]）
                    }
                }
            }

            // 补齐不足的部分
            while (results.size < expectedCount) {
                results.add("")
            }
            results.take(expectedCount)
        } catch (e: Exception) {
            LogCollector.e(TAG, "Failed to parse JSON translations: ${text.take(200)}", e)
            List(expectedCount) { "" }
        }
    }

    /**
     * 解析带编号的翻译结果
     * 支持格式: "[1] 翻译文本" 或 "1. 翻译文本" 或 "1、翻译文本"
     */
    fun parseNumberedTranslations(text: String, expectedCount: Int): List<String> {
        val results = mutableListOf<String>()
        // 匹配 [N] 或 N. 或 N、开头的行
        val pattern = Regex("""\[(\d+)]\s*([\s\S]*?)(?=\[\d+]|$)""")
        val matches = pattern.findAll(text).toList()

        if (matches.size >= expectedCount) {
            for (match in matches.take(expectedCount)) {
                results.add(match.groupValues[2].trim())
            }
        } else {
            // 降级：按行拆分
            val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
            for (line in lines) {
                val cleaned = line.replace(Regex("""^\[?\d+]?[.、\s]*"""), "").trim()
                if (cleaned.isNotBlank()) {
                    results.add(cleaned)
                }
            }
        }

        // 补齐不足的部分
        while (results.size < expectedCount) {
            results.add("")
        }
        return results.take(expectedCount)
    }

    // ========== 文字处理 ==========

    /**
     * 清理 OCR 文本：移除换行符，合并空格。
     */
    fun cleanOcrText(text: String): String {
        return text
            .replace(Regex("[\\n\\r]+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * 检查文本是否仅包含符号/标点（不含实际文字内容）。
     * 用于过滤漫画中的纯符号表达，避免提交给翻译模型导致文字被缩小。
     */
    fun isSymbolOnlyText(text: String): Boolean {
        if (text.isBlank()) return true
        val stripped = text.replace(Regex("\\s+"), "")
        if (stripped.isEmpty()) return true
        return stripped.all { ch ->
            val type = Character.getType(ch).toByte()
            type == Character.START_PUNCTUATION ||
            type == Character.END_PUNCTUATION ||
            type == Character.DASH_PUNCTUATION ||
            type == Character.OTHER_PUNCTUATION ||
            type == Character.MATH_SYMBOL ||
            type == Character.CURRENCY_SYMBOL ||
            type == Character.MODIFIER_SYMBOL ||
            type == Character.OTHER_SYMBOL ||
            ch == '♡' || ch == '♥' || ch == '♪' || ch == '♫' ||
            ch == '〜' || ch == '～' || ch == '…' || ch == '─'
        }
    }
}
