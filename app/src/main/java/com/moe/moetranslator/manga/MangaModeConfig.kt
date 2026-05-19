package com.moe.moetranslator.manga

enum class TextDirection {
    VERTICAL_RL,   // 从上到下，列从右到左（传统日漫）
    VERTICAL_LR,   // 从上到下，列从左到右
    HORIZONTAL     // 从左到右，从上到下（标准）
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
    val bgColor: Int = android.graphics.Color.argb(200, 255, 255, 255)
)
