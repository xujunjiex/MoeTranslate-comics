package com.moe.moetranslator.translate

import android.content.Context
import android.graphics.Bitmap
import com.moe.moetranslator.manga.MangaOcrBridge
import com.moe.moetranslator.manga.MangaOcrDownloadManager
import com.moe.moetranslator.manga.PPOcrV5Engine
import com.moe.moetranslator.utils.CustomPreference
import com.moe.moetranslator.utils.LogCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 游戏翻译 OCR 引擎封装。
 * 封装 MLKit(0)、PP-OCRv5(1)、manga-ocr(2) 三种引擎，
 * 统一调用接口，支持引擎切换和自动降级。
 */
class GameOcrEngine(private val context: Context) {

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
        val mergeMode = prefs.getInt("Custom_OCR_Merge_Mode", 2)

        return when (engine) {
            1 -> recognizeWithPPOcrV5(bitmap, language, mergeMode)
            2 -> recognizeWithMangaOcr(bitmap, language, mergeMode)
            else -> recognizeWithMLKit(bitmap, language, mergeMode)
        }
    }

    private suspend fun recognizeWithMLKit(bitmap: Bitmap, language: String, mergeMode: Int): String {
        return OCRTextRecognizer.getPicText(language, bitmap, mergeMode)
    }

    private suspend fun recognizeWithPPOcrV5(bitmap: Bitmap, language: String, mergeMode: Int): String {
        initPPOcrV5IfNeeded()
        val recLang = PPOcrV5Engine.getRecLang(language)
        return if (recLang != null) {
            val result = withContext(Dispatchers.IO) {
                PPOcrV5Engine.runOCR(context, bitmap, recLang, useDet = true, useCls = false)
            }
            result.texts.joinToString("")
        } else {
            LogCollector.w(TAG, "PP-OCRv5 不支持语言: $language, 回退 ML Kit")
            recognizeWithMLKit(bitmap, language, mergeMode)
        }
    }

    private suspend fun recognizeWithMangaOcr(bitmap: Bitmap, language: String, mergeMode: Int): String {
        initMangaOcrIfNeeded()
        return if (MangaOcrBridge.isAvailable()) {
            val textBlocks = MangaOcrBridge.recognizeWithLocation(bitmap, language)
            textBlocks.joinToString("\n") { it.text }
        } else {
            LogCollector.w(TAG, "manga-ocr 未初始化, 回退 ML Kit")
            recognizeWithMLKit(bitmap, language, mergeMode)
        }
    }

    private fun initPPOcrV5IfNeeded() {
        if (PPOcrV5Engine.isInitialized) return
        synchronized(PPOcrV5Engine) {
            if (!PPOcrV5Engine.isInitialized) {
                PPOcrV5Engine.initialize(context)
            }
        }
    }

    private suspend fun initMangaOcrIfNeeded() {
        if (MangaOcrBridge.isAvailable()) return
        try {
            val activeVersion = MangaOcrDownloadManager.getActiveVersion(context)
            if (activeVersion != null && MangaOcrDownloadManager.isVersionDownloaded(context, activeVersion)) {
                MangaOcrBridge.initializeDownloaded(context, activeVersion)
            }
        } catch (e: Exception) {
            LogCollector.e(TAG, "manga-ocr 初始化失败", e)
        }
    }
}
