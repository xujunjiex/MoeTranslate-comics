package com.moe.starflow.manga.types

/**
 * 合并器的统一输入。
 * text == null 表示 OCR 前的几何合并。
 * text != null 表示 OCR 后的语义合并（PP-OCRv5 独立路径）。
 *
 * 算法不区分两种 case——文字字段仅在最终拼接阶段使用。
 */
data class TextRegion(
    val quad: QuadBox,
    val text: String? = null,
    val score: Float = 1f,
    val recTimeMs: Long = 0
)
