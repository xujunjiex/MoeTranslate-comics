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
            val baseFontSize = if (autoFit) region.fontSize else fontSize

            // 1. 在原始矩形内计算最优字体大小（只缩不大）
            val fitFontSize = if (autoFit) {
                VerticalTextRenderer.calculateFitFontSize(
                    region.translatedText, region.rect, direction, baseFontSize
                )
            } else {
                baseFontSize
            }

            // 2. 用实际字体大小计算需要的绘制区域
            val drawRect = calculateExpandedRect(region.rect, region.translatedText, direction, fitFontSize)

            // 3. 绘制背景
            val bgPaint = Paint().apply {
                color = bgColor
                style = Paint.Style.FILL
            }
            canvas.drawRect(drawRect, bgPaint)

            // 4. 在扩展矩形内渲染文字，autoFit=false 因为字体已确定
            VerticalTextRenderer.drawText(
                canvas = canvas,
                text = region.translatedText,
                region = drawRect,
                direction = direction,
                fontSize = fitFontSize,
                textColor = textColor,
                autoFit = false
            )
        }

        return result
    }

    /**
     * 计算扩展后的绘制区域。
     * 翻译后文字可能比原文长，需要动态扩展：
     * - 竖排：扩展宽度（列数增加）
     * - 横排：扩展高度（行数增加）
     */
    private fun calculateExpandedRect(
        rect: Rect,
        text: String,
        direction: TextDirection,
        fontSize: Float
    ): Rect {
        val charHeight = fontSize * 1.4f
        val columnSpacing = fontSize * 1.2f

        when (direction) {
            TextDirection.VERTICAL_RL, TextDirection.VERTICAL_LR -> {
                // 竖排：计算需要的列数，扩展宽度
                val charsPerColumn = maxOf(1, (rect.height() / charHeight).toInt())
                val neededColumns = (text.length + charsPerColumn - 1) / charsPerColumn
                val neededWidth = (neededColumns * columnSpacing).toInt()
                val expandX = maxOf(0, neededWidth - rect.width())

                return if (direction == TextDirection.VERTICAL_RL) {
                    // 竖排右→左：向左扩展
                    Rect(rect.left - expandX, rect.top, rect.right, rect.bottom)
                } else {
                    // 竖排左→右：向右扩展
                    Rect(rect.left, rect.top, rect.right + expandX, rect.bottom)
                }
            }
            TextDirection.HORIZONTAL -> {
                // 横排：计算需要的行数，扩展高度
                val paint = Paint().apply { textSize = fontSize }
                val maxLineWidth = rect.width().toFloat()
                var lines = 0
                val paragraphs = text.split("\n")
                for (paragraph in paragraphs) {
                    if (paragraph.isEmpty()) {
                        lines++
                        continue
                    }
                    var remaining = paragraph
                    while (remaining.isNotEmpty()) {
                        val count = paint.breakText(remaining, true, maxLineWidth, null)
                        if (count <= 0) break
                        remaining = remaining.substring(count)
                        lines++
                    }
                }
                val neededHeight = (lines * charHeight).toInt()
                val expandY = maxOf(0, neededHeight - rect.height())
                return Rect(rect.left, rect.top, rect.right, rect.bottom + expandY)
            }
        }
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
    val backgroundColor: Int,
    val fontSize: Float = 16f
)
