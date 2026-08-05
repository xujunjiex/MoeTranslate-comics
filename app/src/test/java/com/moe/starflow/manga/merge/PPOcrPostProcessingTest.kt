package com.moe.starflow.manga.merge

import com.moe.starflow.manga.types.*
import android.graphics.PointF
import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PPOcrPostProcessingTest {

    private fun group(texts: List<String>) = TextRegionGroup(
        rect = Rect(0, 0, 10, 10),
        quadPoints = arrayOf(PointF(0f, 0f), PointF(10f, 0f), PointF(10f, 10f), PointF(0f, 10f)),
        texts = texts,
        direction = TextDirection.VERTICAL_RL,
        fontSize = 10f,
        angle = 0f,
        score = 0.9f,
        center = PointF(5f, 5f),
        members = emptyList()
    )

    @Test
    fun filterMergedRegions_keepsMeaningfulText() {
        val kept = PPOcrPostProcessing.filterMergedRegions(listOf(group(listOf("こんにちは"))))
        assertEquals(1, kept.first.size)
        assertTrue(kept.second.isEmpty())
    }

    @Test
    fun filterMergedRegions_discardsEmptyAndSingleChar() {
        val (kept, discarded) = PPOcrPostProcessing.filterMergedRegions(
            listOf(
                group(listOf("")),              // 空白
                group(listOf("あ")),            // 单字符
                group(listOf("はろー"))
            )
        )
        assertEquals(1, kept.size)
        assertEquals(2, discarded.size)
        assertEquals("空白", discarded[0].second)
        assertEquals("单字符", discarded[1].second)
    }

    @Test
    fun filterMergedRegions_discardsSymbolOnlyAndShortNumber() {
        val (kept, discarded) = PPOcrPostProcessing.filterMergedRegions(
            listOf(
                group(listOf("!!!")),   // 纯符号
                group(listOf("12")),    // 短数字
                group(listOf("text"))
            )
        )
        assertEquals(1, kept.size)
        assertEquals(2, discarded.size)
        assertEquals("纯符号", discarded[0].second)
        assertEquals("短数字", discarded[1].second)
    }

    @Test
    fun toTextRegion_convertsFields() {
        val line = PPOcrTextLine(
            rect = Rect(0, 0, 10, 20),
            text = "テスト",
            fontSize = 10f,
            isVertical = true,
            score = 0.95f,
            angle = 0f,
            quadPoints = arrayOf(PointF(0f, 0f), PointF(10f, 0f), PointF(10f, 20f), PointF(0f, 20f))
        )
        val region = line.toTextRegion()
        assertEquals("テスト", region.text)
        assertEquals(0.95f, region.score, 0.01f)
        assertTrue(region.quad.pts.isNotEmpty())  // quadPoints 转换后非空
    }
}
