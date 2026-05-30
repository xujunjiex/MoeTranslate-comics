package com.moe.moetranslator.manga

/**
 * OCR 引擎类型
 */
enum class OcrEngine(val value: Int) {
    MLKit(0),      // 系统 OCR（默认，无需下载）
    MangaOcr(1),   // manga-ocr（下载版，从 HuggingFace 下载）
    PPOcrV4(3);    // PP-OCRv4 JA rec（日文，内置）

    companion object {
        fun fromValue(value: Int) = entries.firstOrNull { it.value == value } ?: MLKit
    }
}

enum class TextDirection {
    VERTICAL_RL,   // 从上到下，列从右到左（传统日漫）
    VERTICAL_LR,   // 从上到下，列从左到右
    HORIZONTAL     // 从左到右，从上到下（标准）
}

/**
 * 文字检测引擎。
 * MLKIT: ML Kit 检测+识别一体化
 * CTD: ComicTextDetector 文字行级检测（无需合并）
 */
enum class DetEngine(val value: Int) {
    MLKIT(0),
    CTD(1),
    RT_DETR_V2(3);

    companion object {
        fun fromValue(value: Int) = entries.firstOrNull { it.value == value } ?: MLKIT
    }
}

data class MangaModeConfig(
    val enabled: Boolean = false,
    val textDirection: TextDirection = TextDirection.VERTICAL_RL,
    val smartBackground: Boolean = true,
    val autoDetectBubble: Boolean = true,
    val fontSize: Float = 16f,
    val autoFontSize: Boolean = true,
    val sourceLang: String = "ja",
    val targetLang: String = "zh",
    val textColor: Int = android.graphics.Color.BLACK,
    val bgColor: Int = android.graphics.Color.argb(200, 255, 255, 255),
    val ocrEngine: OcrEngine = OcrEngine.MLKit,  // OCR 引擎
    val detEngine: DetEngine = DetEngine.MLKIT  // 检测引擎
)
