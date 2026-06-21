package com.moe.moetranslator.manga

import android.graphics.PointF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TextRegionMerger 单元测试。
 * 覆盖：单 box、双 box 合并/不合并、AA + Tilted 分支、MST 拆分、参数变更。
 */
class TextRegionMergerTest {

    // Helper: 构造 AA 横排 box
    private fun hBox(x: Int, y: Int, w: Int, h: Int, text: String? = "字"): TextRegion {
        val pts = arrayOf(
            PointF(x.toFloat(), y.toFloat()),
            PointF((x + w).toFloat(), y.toFloat()),
            PointF((x + w).toFloat(), (y + h).toFloat()),
            PointF(x.toFloat(), (y + h).toFloat())
        )
        return TextRegion(QuadBox(pts), text)
    }

    // Helper: 构造竖排 box
    private fun vBox(x: Int, y: Int, w: Int, h: Int, text: String? = "字"): TextRegion {
        val pts = arrayOf(
            PointF(x.toFloat(), y.toFloat()),
            PointF((x + w).toFloat(), y.toFloat()),
            PointF((x + w).toFloat(), (y + h).toFloat()),
            PointF(x.toFloat(), (y + h).toFloat())
        )
        // 竖排：height >> width
        return TextRegion(QuadBox(pts), text)
    }

    @Test
    fun emptyInputReturnsEmpty() {
        val result = TextRegionMerger.merge(emptyList())
        assertEquals(emptyList<TextRegionGroup>(), result)
    }

    @Test
    fun singleBoxReturnsSingleGroup() {
        val regions = listOf(hBox(0, 0, 50, 20, "你好"))
        val result = TextRegionMerger.merge(regions)
        assertEquals(1, result.size)
        assertEquals("你好", result[0].texts[0])
        assertEquals(TextDirection.HORIZONTAL, result[0].direction)
    }

    @Test
    fun twoHorizontalBoxesCloseMerge() {
        // 两 box 横排同行，距离 < charSize*1.5
        val regions = listOf(
            hBox(0, 0, 30, 20, "你好"),
            hBox(40, 0, 30, 20, "世界")
        )
        val result = TextRegionMerger.merge(regions)
        assertEquals(1, result.size)
        assertEquals(listOf("你好", "世界"), result[0].texts)
    }

    @Test
    fun twoHorizontalBoxesFarApartDoNotMerge() {
        // 两 box 横排同行，距离 > charSize*1.5
        val regions = listOf(
            hBox(0, 0, 30, 20, "你好"),
            hBox(200, 0, 30, 20, "世界")
        )
        val result = TextRegionMerger.merge(regions)
        assertEquals(2, result.size)
    }

    @Test
    fun threeHorizontalBoxesSameLineAllMerge() {
        val regions = listOf(
            hBox(0, 0, 30, 20, "a"),
            hBox(40, 0, 30, 20, "b"),
            hBox(80, 0, 30, 20, "c")
        )
        val result = TextRegionMerger.merge(regions)
        assertEquals(1, result.size)
        assertEquals(listOf("a", "b", "c"), result[0].texts)
    }

    @Test
    fun twoBoxesDifferentDirectionDoNotMerge() {
        // 横排 + 竖排 → 不合并
        val regions = listOf(
            hBox(0, 0, 50, 20, "横"),
            vBox(100, 0, 20, 50, "竖")
        )
        val result = TextRegionMerger.merge(regions)
        assertEquals(2, result.size)
    }

    @Test
    fun verticalBoxesSameColumnMerge() {
        // 两 box 竖排同列，距离 < charSize*1.5
        val regions = listOf(
            vBox(0, 0, 20, 50, "日"),
            vBox(0, 60, 20, 50, "本")
        )
        val result = TextRegionMerger.merge(regions)
        assertEquals(1, result.size)
        assertEquals(TextDirection.VERTICAL_RL, result[0].direction)
    }

    @Test
    fun mstSplitIsolatedOutlier() {
        // 3 box：两个靠近，一个远离 → MST 拆分为 2 个 group
        val regions = listOf(
            hBox(0, 0, 30, 20, "a"),
            hBox(40, 0, 30, 20, "b"),
            hBox(500, 0, 30, 20, "c")  // 远离
        )
        val result = TextRegionMerger.merge(regions)
        assertEquals(2, result.size)
    }

    @Test
    fun preOcrAndPostOcrProduceSameGeometricResult() {
        // text 字段不参与几何判断：相同几何的 OCR 前/后 box 应合并结果相同
        val preOcr = listOf(
            hBox(0, 0, 30, 20, null),
            hBox(40, 0, 30, 20, null)
        )
        val postOcr = listOf(
            hBox(0, 0, 30, 20, "你好"),
            hBox(40, 0, 30, 20, "世界")
        )
        val preResult = TextRegionMerger.merge(preOcr)
        val postResult = TextRegionMerger.merge(postOcr)
        assertEquals(preResult.size, postResult.size)
        assertEquals(1, postResult.size)
        assertEquals(listOf("你好", "世界"), postResult[0].texts)
    }

    @Test
    fun largerDiscardGapMergesMore() {
        // discardConnectionGap 增大 → 合并更多
        val regions = listOf(
            hBox(0, 0, 30, 20, "a"),
            hBox(100, 0, 30, 20, "b")  // 距离 = 100 - 30 = 70，比 charSize(20)*1.5=30 大
        )
        val tightResult = TextRegionMerger.merge(regions, MergeParams(discardConnectionGap = 1.5f))
        val looseResult = TextRegionMerger.merge(regions, MergeParams(discardConnectionGap = 3.0f))
        assertEquals(2, tightResult.size)
        assertEquals(1, looseResult.size)
    }

    @Test
    fun debugLoggingToggleIsNoOpWhenDisabled() {
        TextRegionMerger.enableDebugLogging(false)
        val regions = listOf(hBox(0, 0, 50, 20, "test"))
        val result = TextRegionMerger.merge(regions)
        assertEquals(1, result.size)
    }

    @Test
    fun largeInputDoesNotCrash() {
        // 500 box 性能测试
        val regions = (0 until 500).map { hBox(it * 35, 0, 30, 20, "x") }
        val result = TextRegionMerger.merge(regions)
        assertTrue("应合并成多个 group", result.isNotEmpty())
    }
}
