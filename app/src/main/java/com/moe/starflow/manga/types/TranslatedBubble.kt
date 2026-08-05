package com.moe.starflow.manga.types

import android.graphics.Rect

/** 单个翻译气泡（译文 overlay 渲染的基本单元）。 */
data class TranslatedBubble(
    val rect: Rect,
    val originalText: String,
    val translatedText: String,
    val backgroundColor: Int,
    val fontSize: Float = 16f,
    val direction: TextDirection = TextDirection.VERTICAL_RL,
    val angle: Float = 0f,
    val centerX: Float = -1f,
    val centerY: Float = -1f,
    val fromCache: Boolean = false,  // true = 来自数据库反序列化（不进 ⚡）
    val isInMemoryCache: Boolean = false  // true = 来自内存 translatedRegions 命中（显示 ⚡）
)
