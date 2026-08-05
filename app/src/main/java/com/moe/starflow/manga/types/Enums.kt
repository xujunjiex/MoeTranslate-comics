package com.moe.starflow.manga.types
import com.moe.starflow.translate.screenshot.*

/**
 * OCR 引擎类型
 */
enum class OcrEngine(val value: Int) {
    MLKit(0),      // 系统 OCR（默认，无需下载）
    MangaOcr(1),   // manga-ocr（下载版，从 HuggingFace 下载）
    PPOcrV5(4),    // PP-OCRv5（内置，多语言）
    PPOcrV6(5);    // PP-OCRv6（内置，多语言）

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
 */
enum class DetEngine(val value: Int) {
    MLKIT(0),
    RT_DETR_V2(3),
    PP_OCR_V5(4),
    PP_OCR_V6(5);

    companion object {
        fun fromValue(value: Int) = entries.firstOrNull { it.value == value } ?: MLKIT
    }
}
