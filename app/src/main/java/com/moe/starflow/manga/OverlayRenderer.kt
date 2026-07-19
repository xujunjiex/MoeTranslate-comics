package com.moe.starflow.manga

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect

object OverlayRenderer {

    fun renderOverlay(
        original: Bitmap,
        regions: List<TranslatedBubble>,
        fontSize: Float = 16f,
        autoFit: Boolean = true,
        textColor: Int = Color.BLACK,
        bgColor: Int = Color.argb(200, 255, 255, 255),
        useOriginalText: Boolean = false
    ): Bitmap {
        val result = original.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)

        data class DrawInfo(val region: TranslatedBubble, val drawRect: Rect, val fitFontSize: Float, val displayText: String)

        val sortedRegions = regions.sortedByDescending { it.rect.width() * it.rect.height() }
        val usedRects = mutableListOf<Rect>()
        val drawInfoMap = mutableMapOf<TranslatedBubble, DrawInfo>()

        for (region in sortedRegions) {
            // 实际显示的文字：原文模式用 originalText，否则用译文
            val displayText = if (useOriginalText) {
                region.originalText
            } else if (region.fromCache) {
                "⚡${region.translatedText}"
            } else {
                region.translatedText
            }
            val baseFontSize = if (autoFit) region.fontSize else fontSize
            val fitFontSize = if (autoFit) {
                VerticalTextRenderer.calculateFitFontSize(
                    displayText, region.rect, region.direction, baseFontSize
                )
            } else {
                baseFontSize
            }
            val neededRect = calculateExpandedRect(region.rect, displayText, region.direction, fitFontSize)
            val drawRect = if (hasOverlap(neededRect, usedRects)) {
                region.rect
            } else {
                neededRect
            }
            usedRects.add(drawRect)
            drawInfoMap[region] = DrawInfo(region, drawRect, fitFontSize, displayText)
        }

        val bgPaint = Paint().apply {
            color = bgColor
            style = Paint.Style.FILL
        }
        for (region in regions) {
            val info = drawInfoMap[region] ?: continue
            val hasTilt = kotlin.math.abs(info.region.angle) > 0.5f

            canvas.save()
            if (hasTilt) {
                canvas.rotate(info.region.angle, info.region.centerX, info.region.centerY)
            }
            canvas.save()
            canvas.clipRect(info.drawRect)
            canvas.drawBitmap(original, 0f, 0f, null)
            canvas.restore()

            canvas.drawRect(info.drawRect, bgPaint)

            canvas.save()
            canvas.clipRect(info.drawRect)
            VerticalTextRenderer.drawText(
                canvas = canvas,
                text = info.displayText,
                region = info.drawRect,
                direction = info.region.direction,
                fontSize = info.fitFontSize,
                textColor = textColor,
                autoFit = false
            )
            canvas.restore()

            canvas.restore()
        }

        return result
    }

    private fun hasOverlap(rect: Rect, existing: List<Rect>): Boolean {
        return existing.any { Rect.intersects(rect, it) }
    }

    private fun calculateExpandedRect(
        rect: Rect,
        text: String,
        direction: TextDirection,
        fontSize: Float
    ): Rect {
        val charHeight = fontSize * 1.4f
        val columnSpacing = fontSize * 1.2f

        val expanded = when (direction) {
            TextDirection.VERTICAL_RL, TextDirection.VERTICAL_LR -> {
                val charsPerColumn = maxOf(1, (rect.height() / charHeight).toInt())
                val neededColumns = (text.length + charsPerColumn - 1) / charsPerColumn
                val neededWidth = (neededColumns * columnSpacing).toInt()
                val expandX = maxOf(0, neededWidth - rect.width())
                if (direction == TextDirection.VERTICAL_RL) {
                    Rect(rect.left - expandX, rect.top, rect.right, rect.bottom)
                } else {
                    Rect(rect.left, rect.top, rect.right + expandX, rect.bottom)
                }
            }
            TextDirection.HORIZONTAL -> {
                val paint = Paint().apply { textSize = fontSize }
                val maxLineWidth = rect.width().toFloat()
                var lines = 0
                val paragraphs = text.split("\n")
                for (paragraph in paragraphs) {
                    if (paragraph.isEmpty()) { lines++; continue }
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
                Rect(rect.left, rect.top, rect.right, rect.bottom + expandY)
            }
        }

        return Rect(
            minOf(expanded.left, rect.left),
            minOf(expanded.top, rect.top),
            maxOf(expanded.right, rect.right),
            maxOf(expanded.bottom, rect.bottom)
        )
    }
}

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
    val fromCache: Boolean = false
)
