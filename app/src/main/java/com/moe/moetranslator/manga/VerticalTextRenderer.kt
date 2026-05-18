package com.moe.moetranslator.manga

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect

object VerticalTextRenderer {

    // 从上到下，列从右到左（传统日漫）
    fun drawVerticalTextRL(
        canvas: Canvas,
        text: String,
        region: Rect,
        fontSize: Float = 16f,
        textColor: Int = Color.BLACK
    ) {
        val paint = Paint().apply {
            color = textColor
            textSize = fontSize
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }

        val charHeight = fontSize * 1.4f
        val columnSpacing = fontSize * 1.2f
        var currentX = region.right - columnSpacing / 2
        var currentY = region.top + fontSize

        // 裁剪到区域内，防止文字溢出
        canvas.save()
        canvas.clipRect(region)

        for (char in text) {
            if (currentY > region.bottom) {
                currentX -= columnSpacing
                currentY = region.top + fontSize
                if (currentX < region.left) break
            }
            canvas.drawText(char.toString(), currentX, currentY, paint)
            currentY += charHeight
        }

        canvas.restore()
    }

    // 从上到下，列从左到右
    fun drawVerticalTextLR(
        canvas: Canvas,
        text: String,
        region: Rect,
        fontSize: Float = 16f,
        textColor: Int = Color.BLACK
    ) {
        val paint = Paint().apply {
            color = textColor
            textSize = fontSize
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }

        val charHeight = fontSize * 1.4f
        val columnSpacing = fontSize * 1.2f
        var currentX = region.left + columnSpacing / 2
        var currentY = region.top + fontSize

        // 裁剪到区域内，防止文字溢出
        canvas.save()
        canvas.clipRect(region)

        for (char in text) {
            if (currentY > region.bottom) {
                currentX += columnSpacing
                currentY = region.top + fontSize
                if (currentX > region.right) break
            }
            canvas.drawText(char.toString(), currentX, currentY, paint)
            currentY += charHeight
        }

        canvas.restore()
    }

    fun drawHorizontalText(
        canvas: Canvas,
        text: String,
        region: Rect,
        fontSize: Float = 16f,
        textColor: Int = Color.BLACK
    ) {
        val paint = Paint().apply {
            color = textColor
            textSize = fontSize
            isAntiAlias = true
        }

        val lineHeight = fontSize * 1.4f
        val maxWidth = region.width().toFloat()
        var currentY = region.top + fontSize

        val paragraphs = text.split("\n")
        for (paragraph in paragraphs) {
            if (currentY > region.bottom) break
            if (paragraph.isEmpty()) {
                currentY += lineHeight
                continue
            }

            var remaining = paragraph
            while (remaining.isNotEmpty() && currentY <= region.bottom) {
                val count = paint.breakText(remaining, true, maxWidth, null)
                if (count <= 0) break
                val line = remaining.substring(0, count)
                canvas.drawText(line, region.left.toFloat(), currentY, paint)
                currentY += lineHeight
                remaining = remaining.substring(count)
            }
        }
    }

    fun drawText(
        canvas: Canvas,
        text: String,
        region: Rect,
        direction: TextDirection,
        fontSize: Float = 16f,
        textColor: Int = Color.BLACK,
        autoFit: Boolean = true
    ) {
        var actualFontSize = fontSize
        if (autoFit) {
            actualFontSize = calculateFitFontSize(text, region, direction, fontSize)
        }
        when (direction) {
            TextDirection.VERTICAL_RL -> drawVerticalTextRL(canvas, text, region, actualFontSize, textColor)
            TextDirection.VERTICAL_LR -> drawVerticalTextLR(canvas, text, region, actualFontSize, textColor)
            TextDirection.HORIZONTAL -> drawHorizontalText(canvas, text, region, actualFontSize, textColor)
        }
    }

    private fun calculateFitFontSize(
        text: String,
        region: Rect,
        direction: TextDirection,
        maxFontSize: Float
    ): Float {
        val regionWidth = region.width().toFloat()
        val regionHeight = region.height().toFloat()
        if (regionWidth <= 0 || regionHeight <= 0 || text.isEmpty()) return maxFontSize

        var fontSize = maxFontSize
        val minFontSize = 8f
        while (fontSize > minFontSize) {
            if (doesTextFit(text, region, direction, fontSize)) {
                return fontSize
            }
            fontSize -= 1f
        }
        return minFontSize
    }

    private fun doesTextFit(
        text: String,
        region: Rect,
        direction: TextDirection,
        fontSize: Float
    ): Boolean {
        val charHeight = fontSize * 1.4f
        val columnSpacing = fontSize * 1.2f
        val regionWidth = region.width().toFloat()
        val regionHeight = region.height().toFloat()

        return when (direction) {
            TextDirection.VERTICAL_RL, TextDirection.VERTICAL_LR -> {
                val charsPerColumn = (regionHeight / charHeight).toInt()
                val columns = if (charsPerColumn > 0) (text.length + charsPerColumn - 1) / charsPerColumn else text.length
                val neededWidth = columns * columnSpacing
                neededWidth <= regionWidth && charsPerColumn > 0
            }
            TextDirection.HORIZONTAL -> {
                val paint = Paint().apply { textSize = fontSize }
                val lineHeight = fontSize * 1.4f
                val maxLines = (regionHeight / lineHeight).toInt()
                var lines = 0
                val paragraphs = text.split("\n")
                for (paragraph in paragraphs) {
                    if (paragraph.isEmpty()) {
                        lines++
                        if (lines > maxLines) return false
                        continue
                    }
                    var remaining = paragraph
                    while (remaining.isNotEmpty()) {
                        val count = paint.breakText(remaining, true, regionWidth, null)
                        if (count <= 0) break
                        remaining = remaining.substring(count)
                        lines++
                        if (lines > maxLines) return false
                    }
                }
                true
            }
        }
    }
}
