package com.moe.starflow.manga.debug

import android.graphics.Bitmap
import com.moe.starflow.manga.MLKitDebugResult
import com.moe.starflow.manga.OcrResult
import com.moe.starflow.manga.PPOcrV5Engine
import com.moe.starflow.manga.PPOcrV6Engine
import com.moe.starflow.manga.RTDetrV2DebugResult
import com.moe.starflow.manga.TextDirection
import com.moe.starflow.manga.TextRegionGroup

/**
 * 调试渲染函数（纯函数）：输入截图 + 检测/识别结果，输出带调试框的 bitmap。
 * 无状态，不依赖任何服务字段；依赖全部通过参数传入。
 */
object MangaDebugOverlays {

    fun renderRTDetrV2DebugOverlay(bitmap: Bitmap, debugResult: RTDetrV2DebugResult): Bitmap {
        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = android.graphics.Canvas(result)

        val textPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 28f
            isAntiAlias = true
            setShadowLayer(3f, 1f, 1f, android.graphics.Color.BLACK)
        }

        val fillPaint = android.graphics.Paint().apply {
            style = android.graphics.Paint.Style.FILL
        }

        val strokePaint = android.graphics.Paint().apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 4f
        }

        // 0: bubble（无文字气泡）— 红色
        for ((idx, b) in debugResult.emptyBubbles.withIndex()) {
            fillPaint.color = android.graphics.Color.argb(50, 255, 0, 0)
            strokePaint.color = android.graphics.Color.RED
            strokePaint.strokeWidth = 2f
            canvas.drawRect(b.rect.left.toFloat(), b.rect.top.toFloat(), b.rect.right.toFloat(), b.rect.bottom.toFloat(), fillPaint)
            canvas.drawRect(b.rect.left.toFloat(), b.rect.top.toFloat(), b.rect.right.toFloat(), b.rect.bottom.toFloat(), strokePaint)
            textPaint.color = android.graphics.Color.RED
            canvas.drawText("bubble[$idx] ${String.format("%.0f%%", b.confidence * 100)}", b.rect.left.toFloat() + 4, b.rect.top.toFloat() + 24, textPaint)
        }

        // 1: text_bubble（气泡内文字）— 绿色
        for ((idx, b) in debugResult.textBubbles.withIndex()) {
            fillPaint.color = android.graphics.Color.argb(60, 0, 255, 0)
            strokePaint.color = android.graphics.Color.GREEN
            strokePaint.strokeWidth = 4f
            canvas.drawRect(b.rect.left.toFloat(), b.rect.top.toFloat(), b.rect.right.toFloat(), b.rect.bottom.toFloat(), fillPaint)
            canvas.drawRect(b.rect.left.toFloat(), b.rect.top.toFloat(), b.rect.right.toFloat(), b.rect.bottom.toFloat(), strokePaint)
            textPaint.color = android.graphics.Color.GREEN
            canvas.drawText("text_bubble[$idx] ${String.format("%.0f%%", b.confidence * 100)}", b.rect.left.toFloat() + 4, b.rect.top.toFloat() + 24, textPaint)
        }

        // 2: text_free（自由文字）— 蓝色
        for ((idx, b) in debugResult.textFree.withIndex()) {
            fillPaint.color = android.graphics.Color.argb(60, 0, 100, 255)
            strokePaint.color = android.graphics.Color.CYAN
            strokePaint.strokeWidth = 4f
            canvas.drawRect(b.rect.left.toFloat(), b.rect.top.toFloat(), b.rect.right.toFloat(), b.rect.bottom.toFloat(), fillPaint)
            canvas.drawRect(b.rect.left.toFloat(), b.rect.top.toFloat(), b.rect.right.toFloat(), b.rect.bottom.toFloat(), strokePaint)
            textPaint.color = android.graphics.Color.CYAN
            canvas.drawText("text_free[$idx] ${String.format("%.0f%%", b.confidence * 100)}", b.rect.left.toFloat() + 4, b.rect.top.toFloat() + 24, textPaint)
        }

        // 最终提交给OCR的区域 — 黄色粗框（最上层）
        for ((idx, rect) in debugResult.finalRegions.withIndex()) {
            strokePaint.color = android.graphics.Color.YELLOW
            strokePaint.strokeWidth = 6f
            canvas.drawRect(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat(), strokePaint)
            textPaint.color = android.graphics.Color.YELLOW
            textPaint.textSize = 28f
            canvas.drawText("OCR[$idx]", rect.left.toFloat() + 4, rect.bottom.toFloat() - 8, textPaint)
        }

        return result
    }

    fun renderMLKitDebugOverlay(original: Bitmap, result: MLKitDebugResult): Bitmap {
        val mutableBitmap = original.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = android.graphics.Canvas(mutableBitmap)

        // 块级框（绿色）
        val blockPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.GREEN
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 3f
            isAntiAlias = true
        }

        // 行级框（黄色）
        val linePaint = android.graphics.Paint().apply {
            color = android.graphics.Color.YELLOW
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 2f
            isAntiAlias = true
        }

        // 元素级框（红色）
        val elementPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.RED
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 1f
            isAntiAlias = true
        }

        // 四角点圆点画笔
        val blockPointPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.GREEN
            style = android.graphics.Paint.Style.FILL
        }
        val linePointPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.YELLOW
            style = android.graphics.Paint.Style.FILL
        }
        val elementPointPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.RED
            style = android.graphics.Paint.Style.FILL
        }

        // 绘制每个块（只画框和角点，不画文字）
        for (block in result.textBlocks) {
            // 块边界框（绿色）+ 四角点
            block.blockRect?.let { rect ->
                canvas.drawRect(rect, blockPaint)
            }
            block.blockCorners?.let { corners ->
                for (pt in corners) {
                    canvas.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 5f, blockPointPaint)
                }
            }

            // 行（黄色）+ 四角点
            for (line in block.lines) {
                line.lineRect?.let { rect ->
                    canvas.drawRect(rect, linePaint)
                }
                line.lineCorners?.let { corners ->
                    for (pt in corners) {
                        canvas.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 3f, linePointPaint)
                    }
                }

                // 元素（红色）+ 四角点
                for (element in line.elements) {
                    element.elementRect?.let { rect ->
                        canvas.drawRect(rect, elementPaint)
                    }
                    element.elementCorners?.let { corners ->
                        for (pt in corners) {
                            canvas.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 2f, elementPointPaint)
                        }
                    }
                }
            }
        }

        return mutableBitmap
    }

    fun renderPPOcrV5DebugWithMerge(
        bitmap: Bitmap,
        ocrResult: OcrResult,
        mergedRegions: List<TextRegionGroup>,
        debugDet: PPOcrV5Engine.DebugDetResult? = null,
        textScoreThresh: Float
    ): Bitmap {
        val output = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = android.graphics.Canvas(output)

        // 原始检测框（绿色，细线）
        val rawPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.GREEN
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 2f
            isAntiAlias = true
        }

        // 合并区域框（青色，粗线）
        val mergedPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.CYAN
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 4f
            isAntiAlias = true
        }

        // 合并区域半透明填充
        val mergedFillPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.argb(30, 0, 255, 255)
            style = android.graphics.Paint.Style.FILL
        }

        // 文字标签画笔
        val labelPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 24f
            isAntiAlias = true
            setShadowLayer(3f, 1f, 1f, android.graphics.Color.BLACK)
        }

        // ① 绘制原始检测框（绿色）
        for (box in ocrResult.boxes) {
            canvas.drawLine(box[0], box[1], box[2], box[3], rawPaint)
            canvas.drawLine(box[2], box[3], box[4], box[5], rawPaint)
            canvas.drawLine(box[4], box[5], box[6], box[7], rawPaint)
            canvas.drawLine(box[6], box[7], box[0], box[1], rawPaint)
        }

        // ② 绘制合并区域框（青色）+ 标签
        for ((idx, region) in mergedRegions.withIndex()) {
            val r = region.rect
            val hasTilt = kotlin.math.abs(region.angle) > 0.5f

            canvas.save()
            if (hasTilt) {
                canvas.rotate(region.angle, region.center.x, region.center.y)
            }
            canvas.drawRect(r, mergedFillPaint)
            canvas.drawRect(r, mergedPaint)

            // 标签：序号 + 方向 + 文字数 + 倾斜角
            val dirLabel = if (region.direction == TextDirection.VERTICAL_RL) "V" else "H"
            val angleStr = if (hasTilt) " ∠${String.format("%.0f°", region.angle)}" else ""
            val label = "[$idx]$dirLabel ×${region.texts.size}$angleStr"
            canvas.drawText(label, r.left.toFloat(), r.top.toFloat() - 6f, labelPaint)
            canvas.restore()
        }

        // ③ 绘制被丢弃的选区（红色虚线）
        if (debugDet != null && debugDet.discardedBoxes.isNotEmpty()) {
            val discPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.RED
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 2f
                isAntiAlias = true
                pathEffect = android.graphics.DashPathEffect(floatArrayOf(8f, 4f), 0f)
            }
            val discLabelPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.RED
                textSize = 20f
                isAntiAlias = true
                setShadowLayer(3f, 1f, 1f, android.graphics.Color.BLACK)
            }
            for (i in debugDet.discardedBoxes.indices) {
                val box = debugDet.discardedBoxes[i]
                canvas.drawLine(box[0], box[1], box[2], box[3], discPaint)
                canvas.drawLine(box[2], box[3], box[4], box[5], discPaint)
                canvas.drawLine(box[4], box[5], box[6], box[7], discPaint)
                canvas.drawLine(box[6], box[7], box[0], box[1], discPaint)
                // 标签：分数 + 原因
                val score = debugDet.discardedScores.getOrElse(i) { 0f }
                val reason = debugDet.discardedReasons.getOrElse(i) { "" }
                val label = "✗${String.format("%.2f", score)} $reason"
                canvas.drawText(label, box[0], box[1] - 4f, discLabelPaint)
            }
        }

        // ④ 绘制被识别置信度丢弃的选区（橙色虚线）
        if (ocrResult.recDebug != null && ocrResult.recDebug.discardedBoxes.isNotEmpty()) {
            val recDiscPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.rgb(255, 165, 0) // 橙色
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 2f
                isAntiAlias = true
                pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f, 4f, 2f, 4f), 0f)
            }
            val recDiscFillPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.argb(40, 255, 165, 0)
                style = android.graphics.Paint.Style.FILL
            }
            val recDiscLabelPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.rgb(255, 165, 0)
                textSize = 18f
                isAntiAlias = true
                setShadowLayer(3f, 1f, 1f, android.graphics.Color.BLACK)
            }
            for (i in ocrResult.recDebug.discardedBoxes.indices) {
                val box = ocrResult.recDebug.discardedBoxes[i]
                // 半透明填充
                val path = android.graphics.Path().apply {
                    moveTo(box[0], box[1])
                    lineTo(box[2], box[3])
                    lineTo(box[4], box[5])
                    lineTo(box[6], box[7])
                    close()
                }
                canvas.drawPath(path, recDiscFillPaint)
                canvas.drawPath(path, recDiscPaint)
                // 标签：根据丢弃原因显示
                val reason = ocrResult.recDebug.discardedReasons.getOrElse(i) { "score" }
                val label = if (reason == "score") {
                    val score = ocrResult.recDebug.discardedScores.getOrElse(i) { 0f }
                    "✗${String.format("%.2f", score)}<${String.format("%.2f", textScoreThresh)}"
                } else {
                    "✗内容:$reason"
                }
                canvas.drawText(label, box[0], box[1] - 4f, recDiscLabelPaint)
            }
        }

        return output
    }

    fun renderPPOcrV6DebugWithMerge(
        bitmap: Bitmap,
        ocrResult: OcrResult,
        mergedRegions: List<TextRegionGroup>,
        debugDet: PPOcrV6Engine.DebugDetResult? = null,
        textScoreThresh: Float
    ): Bitmap {
        val output = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = android.graphics.Canvas(output)

        // 原始检测框（绿色，细线）
        val rawPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.GREEN
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 2f
            isAntiAlias = true
        }

        // 合并区域框（青色，粗线）
        val mergedPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.CYAN
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 4f
            isAntiAlias = true
        }

        // 合并区域半透明填充
        val mergedFillPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.argb(30, 0, 255, 255)
            style = android.graphics.Paint.Style.FILL
        }

        // 文字标签画笔
        val labelPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 24f
            isAntiAlias = true
            setShadowLayer(3f, 1f, 1f, android.graphics.Color.BLACK)
        }

        // ① 绘制原始检测框（绿色）
        for (box in ocrResult.boxes) {
            canvas.drawLine(box[0], box[1], box[2], box[3], rawPaint)
            canvas.drawLine(box[2], box[3], box[4], box[5], rawPaint)
            canvas.drawLine(box[4], box[5], box[6], box[7], rawPaint)
            canvas.drawLine(box[6], box[7], box[0], box[1], rawPaint)
        }

        // ② 绘制合并区域框（青色）+ 标签
        for ((idx, region) in mergedRegions.withIndex()) {
            val r = region.rect
            val hasTilt = kotlin.math.abs(region.angle) > 0.5f

            canvas.save()
            if (hasTilt) {
                canvas.rotate(region.angle, region.center.x, region.center.y)
            }
            canvas.drawRect(r, mergedFillPaint)
            canvas.drawRect(r, mergedPaint)

            // 标签：序号 + 方向 + 文字数 + 倾斜角
            val dirLabel = if (region.direction == TextDirection.VERTICAL_RL) "V" else "H"
            val angleStr = if (hasTilt) " ∠${String.format("%.0f°", region.angle)}" else ""
            val label = "[$idx]$dirLabel ×${region.texts.size}$angleStr"
            canvas.drawText(label, r.left.toFloat(), r.top.toFloat() - 6f, labelPaint)
            canvas.restore()
        }

        // ③ 绘制被丢弃的选区（红色虚线）
        if (debugDet != null && debugDet.discardedBoxes.isNotEmpty()) {
            val discPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.RED
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 2f
                isAntiAlias = true
                pathEffect = android.graphics.DashPathEffect(floatArrayOf(8f, 4f), 0f)
            }
            val discLabelPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.RED
                textSize = 20f
                isAntiAlias = true
                setShadowLayer(3f, 1f, 1f, android.graphics.Color.BLACK)
            }
            for (i in debugDet.discardedBoxes.indices) {
                val box = debugDet.discardedBoxes[i]
                canvas.drawLine(box[0], box[1], box[2], box[3], discPaint)
                canvas.drawLine(box[2], box[3], box[4], box[5], discPaint)
                canvas.drawLine(box[4], box[5], box[6], box[7], discPaint)
                canvas.drawLine(box[6], box[7], box[0], box[1], discPaint)
                // 标签：分数 + 原因
                val score = debugDet.discardedScores.getOrElse(i) { 0f }
                val reason = debugDet.discardedReasons.getOrElse(i) { "" }
                val label = "✗${String.format("%.2f", score)} $reason"
                canvas.drawText(label, box[0], box[1] - 4f, discLabelPaint)
            }
        }

        // ④ 绘制被识别置信度丢弃的选区（橙色虚线）
        if (ocrResult.recDebug != null && ocrResult.recDebug.discardedBoxes.isNotEmpty()) {
            val recDiscPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.rgb(255, 165, 0) // 橙色
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 2f
                isAntiAlias = true
                pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f, 4f, 2f, 4f), 0f)
            }
            val recDiscFillPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.argb(40, 255, 165, 0)
                style = android.graphics.Paint.Style.FILL
            }
            val recDiscLabelPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.rgb(255, 165, 0)
                textSize = 18f
                isAntiAlias = true
                setShadowLayer(3f, 1f, 1f, android.graphics.Color.BLACK)
            }
            for (i in ocrResult.recDebug.discardedBoxes.indices) {
                val box = ocrResult.recDebug.discardedBoxes[i]
                // 半透明填充
                val path = android.graphics.Path().apply {
                    moveTo(box[0], box[1])
                    lineTo(box[2], box[3])
                    lineTo(box[4], box[5])
                    lineTo(box[6], box[7])
                    close()
                }
                canvas.drawPath(path, recDiscFillPaint)
                canvas.drawPath(path, recDiscPaint)
                // 标签：根据丢弃原因显示
                val reason = ocrResult.recDebug.discardedReasons.getOrElse(i) { "score" }
                val label = if (reason == "score") {
                    val score = ocrResult.recDebug.discardedScores.getOrElse(i) { 0f }
                    "✗${String.format("%.2f", score)}<${String.format("%.2f", textScoreThresh)}"
                } else {
                    "✗内容:$reason"
                }
                canvas.drawText(label, box[0], box[1] - 4f, recDiscLabelPaint)
            }
        }

        return output
    }
}
