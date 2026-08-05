package com.moe.starflow.manga.render
import com.moe.starflow.translate.widget.*
import com.moe.starflow.translate.autotranslate.*
import com.moe.starflow.translate.screenshot.*
import com.moe.starflow.manga.state.*
import com.moe.starflow.manga.*

import com.moe.starflow.manga.engine.*
import com.moe.starflow.manga.types.*
import com.moe.starflow.manga.config.*
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface

object VerticalTextRenderer {

    /** 竖排字符步距系数（上下相邻字步进），与 OverlayRenderer.VERTICAL_CHAR_RATIO 必须一致 */
    private const val VERTICAL_CHAR_RATIO = 1.1f

    /** 横排换行行距系数（与竖排字距解耦，保持 1.2） */
    private const val HORIZONTAL_LINE_RATIO = 1.2f

    // 从上到下，列从右到左（传统日漫）
    fun drawVerticalTextRL(
        canvas: Canvas,
        text: String,
        region: Rect,
        fontSize: Float = 16f,
        textColor: Int = Color.BLACK,
        centered: Boolean = false,
        columnSpacingOverride: Float? = null,
        fontTypeface: Typeface? = null
    ) {
        val paint = Paint().apply {
            color = textColor
            textSize = fontSize
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            typeface = fontTypeface ?: Typeface.DEFAULT
        }

        val charHeight = fontSize * VERTICAL_CHAR_RATIO
        val columnSpacing = columnSpacingOverride ?: (fontSize * VERTICAL_CHAR_RATIO)
        val charsPerColumn = maxOf(1, ((region.height() - fontSize) / charHeight).toInt() + 1)
        val columns = (text.length + charsPerColumn - 1) / charsPerColumn
        // 水平居中：列组中心对齐 region 中心（最右列中心）；否则从右缘开始
        var currentX = if (centered) {
            region.centerX() + (columns * columnSpacing) / 2f - columnSpacing / 2
        } else {
            region.right - columnSpacing / 2
        }
        // 垂直：单列短文字时文字块垂直居中（避免一列占满高但只有几个字）；多列从顶部开始（每列填满）
        val textHeight = minOf(text.length, charsPerColumn) * charHeight
        var currentY = if (centered && columns <= 1) {
            (region.top + region.bottom - textHeight) / 2 + fontSize
        } else {
            region.top + fontSize
        }

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
        textColor: Int = Color.BLACK,
        centered: Boolean = false,
        columnSpacingOverride: Float? = null,
        fontTypeface: Typeface? = null
    ) {
        val paint = Paint().apply {
            color = textColor
            textSize = fontSize
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            typeface = fontTypeface ?: Typeface.DEFAULT
        }

        val charHeight = fontSize * VERTICAL_CHAR_RATIO
        val columnSpacing = columnSpacingOverride ?: (fontSize * VERTICAL_CHAR_RATIO)
        val charsPerColumn = maxOf(1, ((region.height() - fontSize) / charHeight).toInt() + 1)
        val columns = (text.length + charsPerColumn - 1) / charsPerColumn
        // 水平居中：列组中心对齐 region 中心（最左列中心）；否则从左缘开始
        var currentX = if (centered) {
            region.centerX() - (columns * columnSpacing) / 2f + columnSpacing / 2
        } else {
            region.left + columnSpacing / 2
        }
        // 垂直：单列短文字时文字块垂直居中；多列从顶部开始
        val textHeight = minOf(text.length, charsPerColumn) * charHeight
        var currentY = if (centered && columns <= 1) {
            (region.top + region.bottom - textHeight) / 2 + fontSize
        } else {
            region.top + fontSize
        }

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
        textColor: Int = Color.BLACK,
        fontTypeface: Typeface? = null
    ) {
        val paint = Paint().apply {
            color = textColor
            textSize = fontSize
            isAntiAlias = true
            typeface = fontTypeface ?: Typeface.DEFAULT
        }

        val lineHeight = fontSize * HORIZONTAL_LINE_RATIO
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
        autoFit: Boolean = true,
        centered: Boolean = false,
        columnSpacingOverride: Float? = null,
        fontTypeface: Typeface? = null
    ) {
        var actualFontSize = fontSize
        if (autoFit) {
            actualFontSize = calculateFitFontSize(text, region, direction, fontSize)
        }
        when (direction) {
            TextDirection.VERTICAL_RL -> drawVerticalTextRL(canvas, text, region, actualFontSize, textColor, centered, columnSpacingOverride, fontTypeface)
            TextDirection.VERTICAL_LR -> drawVerticalTextLR(canvas, text, region, actualFontSize, textColor, centered, columnSpacingOverride, fontTypeface)
            TextDirection.HORIZONTAL -> drawHorizontalText(canvas, text, region, actualFontSize, textColor, fontTypeface)
        }
    }

    fun calculateFitFontSize(
        text: String,
        region: Rect,
        direction: TextDirection,
        maxFontSize: Float
    ): Float {
        val regionWidth = region.width().toFloat()
        val regionHeight = region.height().toFloat()
        if (regionWidth <= 0 || regionHeight <= 0 || text.isEmpty()) return maxFontSize

        // 字体上限不能超过矩形本身能容纳的大小
        val rectLimit = when (direction) {
            TextDirection.VERTICAL_RL, TextDirection.VERTICAL_LR -> regionHeight / VERTICAL_CHAR_RATIO
            TextDirection.HORIZONTAL -> regionWidth / HORIZONTAL_LINE_RATIO
        }
        val cappedMax = minOf(maxFontSize, rectLimit)

        val minFontSize = 8f
        // 二分查找最优字体大小
        var lo = minFontSize
        var hi = cappedMax
        var best = minFontSize

        while (hi - lo >= 0.5f) {
            val mid = (lo + hi) / 2
            if (doesTextFit(text, region, direction, mid)) {
                best = mid
                lo = mid
            } else {
                hi = mid
            }
        }
        return best
    }

    private fun doesTextFit(
        text: String,
        region: Rect,
        direction: TextDirection,
        fontSize: Float
    ): Boolean {
        val charHeight = fontSize * VERTICAL_CHAR_RATIO
        val columnSpacing = fontSize * VERTICAL_CHAR_RATIO
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
                val lineHeight = fontSize * HORIZONTAL_LINE_RATIO
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
