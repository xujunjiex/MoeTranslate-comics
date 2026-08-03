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
        // 所有气泡原 rect：扩展的 drawRect 不得侵入任何相邻气泡区域（避免大字号白块相互覆盖）
        val allRegionRects = regions.map { it.rect }

        for (region in sortedRegions) {
            // 实际显示的文字：原文模式用 originalText，否则用译文
            // 注意：⚡ 标志只用于翻译进程中的内存缓存命中（isInMemoryCache），数据库反序列化的 bubbles 永远不显示 ⚡
            val displayText = if (useOriginalText) {
                region.originalText
            } else if (region.isInMemoryCache) {
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
            val neededRect = if (autoFit) {
                // 自动模式：fitFontSize 已尽量填满气泡，保持原"只在文字超出时扩展"语义
                calculateExpandedRect(region.rect, displayText, region.direction, fitFontSize)
            } else {
                // 非自动模式：文字按用户字号绘制，不一定填满气泡。
                // 收缩到文字实际所需并居中，避免小字号时气泡内大片空白
                calculateCompactRect(region.rect, displayText, region.direction, fitFontSize)
            }
            // 收缩/扩展矩形（≠ 原气泡）与已画区域或其他气泡原区域重叠时回退到原气泡 rect。
            // 回退安全的前提：所有已画的扩展矩形也经过此检测，不侵入其他气泡原区域。
            val drawRect = if (neededRect == region.rect ||
                (!hasOverlap(neededRect, usedRects) && !intrudesOtherBubble(neededRect, region.rect, allRegionRects))
            ) {
                neededRect
            } else {
                region.rect
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

    /**
     * 非自动模式专用：计算文字实际所需矩形并居中于气泡内。
     *
     * 背景问题：小字号时文字不填满气泡，若 drawRect 用整个气泡 rect，背景色块会覆盖
     * 大片空白。这里把 drawRect 收缩到「文字实际尺寸 + 小 padding」，视觉上白底贴合文字。
     *
     * 文字超出气泡（长译文）时保持原 `calculateExpandedRect` 的扩展语义，保证文字完整显示。
     */
    private fun calculateCompactRect(
        rect: Rect,
        text: String,
        direction: TextDirection,
        fontSize: Float
    ): Rect {
        val charHeight = fontSize * 1.4f
        val columnSpacing = fontSize * 1.2f
        val padding = (fontSize * 0.4f).toInt()

        val textW: Float
        val textH: Float
        when (direction) {
            TextDirection.VERTICAL_RL, TextDirection.VERTICAL_LR -> {
                val charsPerColumn = maxOf(1, (rect.height() / charHeight).toInt())
                val columns = (text.length + charsPerColumn - 1) / charsPerColumn
                textW = columns * columnSpacing
                textH = minOf(text.length, charsPerColumn) * charHeight
            }
            TextDirection.HORIZONTAL -> {
                val paint = Paint().apply { textSize = fontSize }
                val maxLineWidth = rect.width().toFloat()
                var lines = 0
                var maxLineW = 0f
                for (paragraph in text.split("\n")) {
                    if (paragraph.isEmpty()) { lines++; continue }
                    var remaining = paragraph
                    while (remaining.isNotEmpty()) {
                        val count = paint.breakText(remaining, true, maxLineWidth, null)
                        if (count <= 0) break
                        val line = remaining.substring(0, count)
                        maxLineW = maxOf(maxLineW, paint.measureText(line))
                        remaining = remaining.substring(count)
                        lines++
                    }
                }
                textW = maxLineW
                textH = lines * charHeight
            }
        }

        // 文字本体超出气泡 → 保持原扩展语义（文字完整显示，重叠检测兜底）
        if (textW > rect.width() || textH > rect.height()) {
            return calculateExpandedRect(rect, text, direction, fontSize)
        }

        // 收缩居中：背景贴合文字，气泡内其余区域露出原图（避免大片空白）
        val w = (textW + 2 * padding).toInt().coerceIn(1, rect.width())
        val h = (textH + 2 * padding).toInt().coerceIn(1, rect.height())
        val left = rect.centerX() - w / 2
        val top = rect.centerY() - h / 2
        return Rect(left, top, left + w, top + h)
    }

    private fun hasOverlap(rect: Rect, existing: List<Rect>): Boolean {
        return existing.any { Rect.intersects(rect, it) }
    }

    /**
     * rect 是否与除 self 外任一气泡原区域相交。
     * 扩展 drawRect（大字号文字超出气泡时）不可盖住相邻气泡区域，否则白块相互覆盖。
     */
    private fun intrudesOtherBubble(rect: Rect, self: Rect, allRegionRects: List<Rect>): Boolean {
        return allRegionRects.any { it != self && Rect.intersects(rect, it) }
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
    val fromCache: Boolean = false,  // true = 来自数据库反序列化（不进 ⚡）
    val isInMemoryCache: Boolean = false  // true = 来自内存 translatedRegions 命中（显示 ⚡）
)
