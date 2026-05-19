package com.moe.moetranslator.manga

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect

object OverlayRenderer {

    fun renderOverlay(
        original: Bitmap,
        regions: List<TranslatedBubble>,
        direction: TextDirection,
        fontSize: Float = 16f,
        autoFit: Boolean = true,
        textColor: Int = Color.BLACK,
        bgColor: Int = Color.argb(200, 255, 255, 255)
    ): Bitmap {
        val result = original.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)

        for (region in regions) {
            val bgPaint = Paint().apply {
                color = bgColor
                style = Paint.Style.FILL
            }
            canvas.drawRect(region.rect, bgPaint)

            VerticalTextRenderer.drawText(
                canvas = canvas,
                text = region.translatedText,
                region = region.rect,
                direction = direction,
                fontSize = fontSize,
                textColor = textColor,
                autoFit = autoFit
            )
        }

        return result
    }

    private fun getContrastColor(backgroundColor: Int): Int {
        val luminance = (0.299 * Color.red(backgroundColor) +
                0.587 * Color.green(backgroundColor) +
                0.114 * Color.blue(backgroundColor)) / 255
        return if (luminance > 0.5) Color.BLACK else Color.WHITE
    }
}

data class TranslatedBubble(
    val rect: Rect,
    val originalText: String,
    val translatedText: String,
    val backgroundColor: Int
)
