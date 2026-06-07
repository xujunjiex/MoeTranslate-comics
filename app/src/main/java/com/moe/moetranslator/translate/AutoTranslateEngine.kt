package com.moe.moetranslator.translate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Bitmap.Config.ARGB_8888
import android.graphics.Rect
import com.moe.moetranslator.data.TranslationCacheManager
import com.moe.moetranslator.utils.LogCollector
import com.moe.moetranslator.utils.PixelCompare
import com.moe.moetranslator.utils.TextSimilarity

/**
 * 游戏自动翻译引擎。
 * 负责截图处理决策：OCR → 文字比较 → 缓存查找 → 返回翻译决策。
 * 不直接调用翻译 API，返回 Decision 对象由 FloatingBallService 执行。
 *
 * 核心逻辑：比较连续两次 OCR 结果，相同则翻译，不同则继续等待。
 */
class AutoTranslateEngine(
    private val context: Context,
    private val cacheManager: TranslationCacheManager,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val getSourceLanguage: () -> String,
    private val getTargetLanguage: () -> String
) {
    companion object {
        private const val TAG = "AutoTranslateEngine"
        private const val SIMILARITY_THRESHOLD = 0.9f
    }

    // 状态
    private var isRunning = false
    private var lastOCRText = ""
    private var lastTranslationResult = ""

    // OCR 引擎
    private val ocrEngine = GameOcrEngine(context)

    // 强制翻译标志（手动点击时跳过缓存）
    var isManualForceTranslate = false

    // 像素比较
    private var lastBitmap: Bitmap? = null
    var lastDiffRatio: Float = 0f

    /**
     * 翻译决策
     */
    sealed class Decision {
        /** 像素未变，跳过（不执行 OCR） */
        data class PixelSkip(val diffRatio: Float) : Decision()
        /** OCR 后文字不同或空，跳过 */
        object TextSkip : Decision()
        /** 命中缓存，直接显示；source: "memory" / "database" */
        data class CacheHit(val cachedText: String, val source: String) : Decision()
        /** 需要翻译；similarity: 与上次 OCR 文字的相似度（-1 表示手动强制翻译） */
        data class Translate(val ocrText: String, val similarity: Float) : Decision()
    }

    /**
     * 启动自动翻译
     */
    fun start() {
        if (isRunning) return
        isRunning = true
        lastOCRText = ""
        lastTranslationResult = ""
        isManualForceTranslate = false
        LogCollector.d(TAG, "自动翻译启动")
    }

    /**
     * 停止自动翻译
     */
    fun stop() {
        isRunning = false
        isManualForceTranslate = false
        lastBitmap?.recycle()
        lastBitmap = null
        lastDiffRatio = 0f
        LogCollector.d(TAG, "自动翻译停止")
    }

    /**
     * 处理截图，返回翻译决策。
     *
     * 核心逻辑：
     * 1. OCR 识别
     * 2. 和上次 OCR 结果比较
     * 3. 相同 → 翻译（或显示缓存）
     * 4. 不同 → 更新 lastOCRText，返回 Skip（等待下次检测）
     */
    suspend fun processScreenshot(bitmap: Bitmap): Decision {
        if (!isRunning) return Decision.TextSkip

        // 像素预筛：画面未变则跳过 OCR
        val prevBitmap = lastBitmap
        if (prevBitmap != null && !isManualForceTranslate) {
            val result = PixelCompare.compare(prevBitmap, bitmap)
            lastDiffRatio = result.diffRatio
            prevBitmap.recycle()
            lastBitmap = bitmap.copy(ARGB_8888, true)
            if (result.isSimilar) {
                LogCollector.d(TAG, "【跳过·画面未变】diffRatio=${"%.4f".format(result.diffRatio)}")
                return Decision.PixelSkip(result.diffRatio)
            }
        } else {
            // 首次截图或手动翻译，保存 bitmap 供下次比较
            lastBitmap?.recycle()
            lastBitmap = bitmap.copy(ARGB_8888, true)
        }

        LogCollector.d(TAG, "【检测中】正在 OCR 识别...")
        val ocrText = ocrEngine.recognize(bitmap)
        val normalizedText = TextSimilarity.normalize(ocrText)

        if (normalizedText.isBlank()) {
            LogCollector.d(TAG, "【跳过】OCR 结果为空")
            return Decision.TextSkip
        }

        // 手动强制翻译
        if (isManualForceTranslate) {
            isManualForceTranslate = false
            lastOCRText = normalizedText
            LogCollector.d(TAG, "【翻译中】手动强制翻译: ${normalizedText.take(20)}...")
            return Decision.Translate(normalizedText, -1f)
        }

        // 文字相似度比较
        val sim = TextSimilarity.similarity(normalizedText, lastOCRText)

        if (TextSimilarity.isSimilar(normalizedText, lastOCRText, SIMILARITY_THRESHOLD)) {
            // 相同文字 → 优先返回缓存
            if (lastTranslationResult.isNotEmpty()) {
                LogCollector.d(TAG, "【缓存】文字相同，显示内存缓存 (sim=${"%.2f".format(sim)})")
                return Decision.CacheHit(lastTranslationResult, "memory")
            }
            LogCollector.d(TAG, "【翻译中】文字相同但无缓存 (sim=${"%.2f".format(sim)}): ${normalizedText.take(20)}...")
            return Decision.Translate(normalizedText, sim)
        }

        // 文字不同 → 检查数据库缓存
        LogCollector.d(TAG, "【检测中】文字不同 (sim=${"%.2f".format(sim)})，检查数据库缓存...")
        val dbCache = checkDatabaseCache(normalizedText)
        if (dbCache != null) {
            lastOCRText = normalizedText
            lastTranslationResult = dbCache
            LogCollector.d(TAG, "【缓存】命中数据库缓存: ${normalizedText.take(20)}...")
            return Decision.CacheHit(dbCache, "database")
        }

        // 文字不同且无缓存 → 直接翻译
        LogCollector.d(TAG, "【翻译中】文字不同且无缓存 (sim=${"%.2f".format(sim)}): ${normalizedText.take(20)}...")
        lastOCRText = normalizedText
        return Decision.Translate(normalizedText, sim)
    }

    /**
     * 翻译成功后更新状态
     */
    fun onTranslationSuccess(sourceText: String, translatedText: String) {
        lastOCRText = sourceText
        lastTranslationResult = translatedText
        LogCollector.d(TAG, "翻译成功，更新缓存: ${sourceText.take(20)}...")
    }

    /**
     * 强制翻译（手动点击时调用）
     */
    fun forceTranslate(ocrText: String): Decision.Translate {
        isManualForceTranslate = false
        lastOCRText = TextSimilarity.normalize(ocrText)
        return Decision.Translate(lastOCRText, -1f)
    }

    /**
     * 检查悬浮窗是否与裁剪区域重叠
     */
    fun isFloatingViewOverlappingCrop(floatViewRect: Rect, cropRect: Rect): Boolean {
        return Rect.intersects(floatViewRect, cropRect)
    }

    // ========== 内部方法 ==========

    /**
     * 检查数据库缓存
     */
    private suspend fun checkDatabaseCache(ocrText: String): String? {
        return try {
            val result = cacheManager.findGameCache(
                ocrText,
                getSourceLanguage(),
                getTargetLanguage()
            )
            result?.translatedText
        } catch (e: Exception) {
            LogCollector.e(TAG, "数据库缓存查找失败", e)
            null
        }
    }

    /**
     * 状态变化通知（语言/引擎/裁剪区域/API 配置变化时调用）
     */
    fun onLanguageChanged() {
        lastOCRText = ""
        lastTranslationResult = ""
        LogCollector.d(TAG, "语言变化，清空缓存")
    }

    fun onOcrEngineChanged() {
        lastOCRText = ""
        lastTranslationResult = ""
        LogCollector.d(TAG, "OCR 引擎变化，清空缓存")
    }

    fun onCropRegionChanged() {
        lastOCRText = ""
        lastTranslationResult = ""
        lastBitmap?.recycle()
        lastBitmap = null
        LogCollector.d(TAG, "裁剪区域变化，清空缓存")
    }

    fun onApiConfigChanged() {
        lastOCRText = ""
        lastTranslationResult = ""
        LogCollector.d(TAG, "API 配置变化，清空缓存")
    }
}
