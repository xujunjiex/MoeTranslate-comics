package com.moe.starflow.manga.merge
import com.moe.starflow.translate.widget.*
import com.moe.starflow.translate.autotranslate.*
import com.moe.starflow.translate.screenshot.*
import com.moe.starflow.manga.state.*
import com.moe.starflow.manga.*

import com.moe.starflow.manga.engine.*
import com.moe.starflow.manga.types.*
import com.moe.starflow.manga.config.*
import android.content.Context

/**
 * PP-OCR 结果后处理纯逻辑（从 MangaFloatingService 4c-1 提取）。
 * 无服务 UI 状态依赖，全部参数化。
 */
object PPOcrPostProcessing {

    /**
     * 运行 TextRegionMerger 合并 PP-OCR 识别结果。
     * @param isV6 true=PP-OCRv6 结果转换，false=PP-OCRv5
     * @param textDirection 竖排方向（渲染实时覆盖）
     */
    fun runTextLineMerge(
        context: Context,
        ocrResult: OcrResult,
        bitmapWidth: Int,
        bitmapHeight: Int,
        isV6: Boolean = false,
        textDirection: TextDirection
    ): List<TextRegionGroup> {
        val textLines = if (isV6) PPOcrV6Engine.ocrResultToTextLines(ocrResult, bitmapWidth, bitmapHeight)
        else PPOcrV5Engine.ocrResultToTextLines(ocrResult, bitmapWidth, bitmapHeight)
        TextRegionMerger.refreshParams(context)
        return TextRegionMerger.merge(textLines.map { it.toTextRegion() }, verticalDirection = textDirection)
    }

    /**
     * 合并后内容过滤：丢弃无意义的合并结果。
     * 在 TextLineMerger.merge 之后调用，基于合并后的完整文本判断。
     * 返回 Pair(保留的区域, 丢弃的区域+原因)
     */
    fun filterMergedRegions(
        regions: List<TextRegionGroup>
    ): Pair<List<TextRegionGroup>, List<Pair<TextRegionGroup, String>>> {
        val kept = mutableListOf<TextRegionGroup>()
        val discarded = mutableListOf<Pair<TextRegionGroup, String>>()
        for (region in regions) {
            val text = region.texts.joinToString("").trim()
            val reason = when {
                text.isEmpty() -> "空白"
                text.length == 1 -> "单字符"
                text.all { !it.isLetterOrDigit() } -> "纯符号"
                text.length <= 2 && text.all { it.isDigit() } -> "短数字"
                else -> null
            }
            if (reason != null) {
                discarded.add(region to reason)
            } else {
                kept.add(region)
            }
        }
        return Pair(kept, discarded)
    }
}

/** TextLine → TextRegion 转换（顶层扩展，同包调用无需 import）。 */
fun PPOcrTextLine.toTextRegion(): TextRegion {
    return TextRegion(
        quad = QuadBox(quadPoints),
        text = text,
        score = score
    )
}
