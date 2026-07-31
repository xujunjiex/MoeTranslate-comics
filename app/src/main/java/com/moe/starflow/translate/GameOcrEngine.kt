package com.moe.starflow.translate

import android.content.Context
import android.graphics.Bitmap
import com.moe.starflow.manga.MangaOcrBridge
import com.moe.starflow.manga.MangaOcrModelFiles
import com.moe.starflow.manga.PPOcrV5Engine
import com.moe.starflow.manga.PPOcrV6Engine
import com.moe.starflow.utils.CustomPreference
import com.moe.starflow.utils.LogCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 游戏翻译 OCR 引擎封装。
 * 封装 MLKit(0)、PP-OCRv5(1)、manga-ocr(2)、PP-OCRv6(3) 四种引擎，
 * 统一调用接口，支持引擎切换和自动降级。
 */
class GameOcrEngine(
    private val context: Context,
    private val onMessage: ((String) -> Unit)? = null
) {

    companion object {
        private const val TAG = "GameOcrEngine"
    }

    private val prefs = CustomPreference.getInstance(context)

    /**
     * 执行 OCR 识别。
     * @param bitmap 待识别的图片
     * @return 识别出的文字
     */
    suspend fun recognize(bitmap: Bitmap): String {
        val engine = prefs.getInt("Game_OCR_Engine", 0)
        val language = prefs.getString("Source_Language", "ja")

        return when (engine) {
            1 -> recognizeWithPPOcrV5(bitmap, language)
            2 -> recognizeWithMangaOcr(bitmap, language)
            3 -> recognizeWithPPOcrV6(bitmap, language)
            else -> recognizeWithMLKit(bitmap, language)
        }
    }

    private suspend fun recognizeWithMLKit(bitmap: Bitmap, language: String): String {
        return OCRTextRecognizer.getPicText(language, bitmap)
    }

    private suspend fun recognizeWithPPOcrV5(bitmap: Bitmap, language: String): String {
        initPPOcrV5IfNeeded()
        val (recLang, hint) = PPOcrV5Engine.resolveRecLang(context, language)
        if (hint != null) {
            LogCollector.w(TAG, "PP-OCRv5: $hint")
        }
        return if (recLang != null) {
            val result = withContext(Dispatchers.IO) {
                PPOcrV5Engine.runOCR(context, bitmap, recLang, useDet = true)
            }
            result.texts.joinToString("")
        } else {
            LogCollector.w(TAG, "PP-OCRv5 不支持语言: $language, 回退 ML Kit")
            recognizeWithMLKit(bitmap, language)
        }
    }

    private suspend fun recognizeWithMangaOcr(bitmap: Bitmap, language: String): String {
        initMangaOcrIfNeeded()
        return if (MangaOcrBridge.isAvailable()) {
            val textBlocks = MangaOcrBridge.recognizeWithLocation(bitmap, language)
            textBlocks.joinToString("\n") { it.text }
        } else {
            LogCollector.w(TAG, "manga-ocr 未初始化, 回退 ML Kit")
            recognizeWithMLKit(bitmap, language)
        }
    }

    private fun initPPOcrV5IfNeeded() {
        LogCollector.d(TAG, "initPPOcrV5IfNeeded: isInitialized=${PPOcrV5Engine.isInitialized}")
        if (PPOcrV5Engine.isInitialized) return
        synchronized(PPOcrV5Engine) {
            if (!PPOcrV5Engine.isInitialized) {
                LogCollector.d(TAG, "initPPOcrV5IfNeeded: 开始初始化 PP-OCRv5")
                onMessage?.invoke("PP-OCRv5 识别器初始化中...")
                PPOcrV5Engine.initialize(context)
                LogCollector.d(TAG, "initPPOcrV5IfNeeded: PP-OCRv5 初始化完成")
                onMessage?.invoke("PP-OCRv5 识别器初始化成功")
            }
        }
    }

    private suspend fun recognizeWithPPOcrV6(bitmap: Bitmap, language: String): String {
        initPPOcrV6IfNeeded()
        val result = withContext(Dispatchers.IO) {
            PPOcrV6Engine.runOCR(context, bitmap, useDet = true)
        }
        return result.texts.joinToString("")
    }

    private fun initPPOcrV6IfNeeded() {
        LogCollector.d(TAG, "initPPOcrV6IfNeeded: isInitialized=${PPOcrV6Engine.isInitialized}")
        if (PPOcrV6Engine.isInitialized) return
        synchronized(PPOcrV6Engine) {
            if (!PPOcrV6Engine.isInitialized) {
                LogCollector.d(TAG, "initPPOcrV6IfNeeded: 开始初始化 PP-OCRv6")
                onMessage?.invoke("PP-OCRv6 识别器初始化中...")
                PPOcrV6Engine.initialize(context)
                LogCollector.d(TAG, "initPPOcrV6IfNeeded: PP-OCRv6 初始化完成")
                onMessage?.invoke("PP-OCRv6 识别器初始化成功")
            }
        }
    }

    private suspend fun initMangaOcrIfNeeded() {
        LogCollector.d(TAG, "initMangaOcrIfNeeded: isAvailable=${MangaOcrBridge.isAvailable()}")
        if (MangaOcrBridge.isAvailable()) return
        try {
            if (MangaOcrModelFiles.isModelDownloaded(context)) {
                LogCollector.d(TAG, "initMangaOcrIfNeeded: 开始初始化 manga-ocr")
                onMessage?.invoke("manga-ocr 识别器初始化中...")
                MangaOcrBridge.initializeDownloaded(context)
                LogCollector.d(TAG, "initMangaOcrIfNeeded: manga-ocr 初始化完成")
                onMessage?.invoke("manga-ocr 识别器初始化成功")
            }
        } catch (e: Exception) {
            LogCollector.e(TAG, "manga-ocr 识别器初始化失败", e)
            onMessage?.invoke("manga-ocr 识别器初始化失败: ${e.message}")
        }
    }
}
