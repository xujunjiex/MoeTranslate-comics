package com.moe.moetranslator.manga

import android.graphics.PointF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * TextRegionMerger 单元测试。
 *
 * QuadBox.fontSize = min(structureLen01, structureLen23)。
 * 对于 AABB box，structureLen = 对边中点连线长度 = min(w, h)。
 *
 * 合并条件：quadCenterDistance < discardConnectionGap * charSize。
 * 默认 discardConnectionGap=1.5。
 */
@RunWith(RobolectricTestRunner::class)
class TextRegionMergerTest {

    // Helper: 构造 AA 横排 box（w > h → 横排, fontSize = h）
    private fun hBox(x: Int, y: Int, w: Int, h: Int, text: String? = "字"): TextRegion {
        val pts = arrayOf(
            PointF(x.toFloat(), y.toFloat()),
            PointF((x + w).toFloat(), y.toFloat()),
            PointF((x + w).toFloat(), (y + h).toFloat()),
            PointF(x.toFloat(), (y + h).toFloat())
        )
        return TextRegion(QuadBox(pts), text)
    }

    // Helper: 构造竖排 box（h > w → 竖排, fontSize = w）
    private fun vBox(x: Int, y: Int, w: Int, h: Int, text: String? = "字"): TextRegion {
        val pts = arrayOf(
            PointF(x.toFloat(), y.toFloat()),
            PointF((x + w).toFloat(), y.toFloat()),
            PointF((x + w).toFloat(), (y + h).toFloat()),
            PointF(x.toFloat(), (y + h).toFloat())
        )
        return TextRegion(QuadBox(pts), text)
    }

    @Test
    fun emptyInputReturnsEmpty() {
        val result = TextRegionMerger.merge(emptyList())
        assertEquals(emptyList<TextRegionGroup>(), result)
    }

    @Test
    fun singleBoxReturnsSingleGroup() {
        val regions = listOf(hBox(0, 0, 80, 40, "你好"))
        val result = TextRegionMerger.merge(regions)
        assertEquals(1, result.size)
        assertEquals("你好", result[0].texts[0])
        assertEquals(TextDirection.HORIZONTAL, result[0].direction)
    }

    @Test
    fun twoHorizontalBoxesCloseMerge() {
        // hBox(0,0,30,40): fontSize=30, charSize=30, maxGap=1.5*30=45
        // Box1 center=(15,20), Box2 center=(46,20), dist=31 < 45 → 合并
        val regions = listOf(
            hBox(0, 0, 30, 40, "你好"),
            hBox(31, 0, 30, 40, "世界")
        )
        val result = TextRegionMerger.merge(regions)
        assertEquals(1, result.size)
        assertTrue(result[0].texts.containsAll(listOf("你好", "世界")))
    }

    @Test
    fun twoHorizontalBoxesFarApartDoNotMerge() {
        val regions = listOf(
            hBox(0, 0, 30, 40, "你好"),
            hBox(200, 0, 30, 40, "世界")
        )
        val result = TextRegionMerger.merge(regions)
        assertEquals(2, result.size)
    }

    @Test
    fun threeHorizontalBoxesSameLineAllMerge() {
        val regions = listOf(
            hBox(0, 0, 30, 40, "a"),
            hBox(31, 0, 30, 40, "b"),
            hBox(62, 0, 30, 40, "c")
        )
        val result = TextRegionMerger.merge(regions)
        assertEquals(1, result.size)
        assertTrue(result[0].texts.containsAll(listOf("a", "b", "c")))
    }

    @Test
    fun twoBoxesDifferentDirectionDoNotMerge() {
        val regions = listOf(
            hBox(0, 0, 80, 40, "横"),
            vBox(200, 0, 40, 80, "竖")
        )
        val result = TextRegionMerger.merge(regions)
        assertEquals(2, result.size)
    }

    @Test
    fun verticalBoxesSameColumnMerge() {
        // vBox(0,0,30,40): w=30, h=40, h>w → 竖排, fontSize=30
        // Box1 center=(15,20), Box2 center=(15,51), dist=31 < 45 → 合并
        val regions = listOf(
            vBox(0, 0, 30, 40, "日"),
            vBox(0, 31, 30, 40, "本")
        )
        val result = TextRegionMerger.merge(regions)
        assertEquals(1, result.size)
        assertEquals(TextDirection.VERTICAL_RL, result[0].direction)
    }

    @Test
    fun mstSplitIsolatedOutlier() {
        val regions = listOf(
            hBox(0, 0, 30, 40, "a"),
            hBox(31, 0, 30, 40, "b"),
            hBox(500, 0, 30, 40, "c")
        )
        val result = TextRegionMerger.merge(regions)
        assertEquals(2, result.size)
    }

    @Test
    fun preOcrAndPostOcrProduceSameGeometricResult() {
        val preOcr = listOf(
            hBox(0, 0, 30, 40, null),
            hBox(31, 0, 30, 40, null)
        )
        val postOcr = listOf(
            hBox(0, 0, 30, 40, "你好"),
            hBox(31, 0, 30, 40, "世界")
        )
        val preResult = TextRegionMerger.merge(preOcr)
        val postResult = TextRegionMerger.merge(postOcr)
        assertEquals(preResult.size, postResult.size)
        assertEquals(1, postResult.size)
        assertTrue(postResult[0].texts.containsAll(listOf("你好", "世界")))
    }

    @Test
    fun debugLoggingToggleIsNoOpWhenDisabled() {
        TextRegionMerger.enableDebugLogging(false)
        val regions = listOf(hBox(0, 0, 80, 40, "test"))
        val result = TextRegionMerger.merge(regions)
        assertEquals(1, result.size)
    }

    @Test
    fun largeInputDoesNotCrash() {
        val regions = (0 until 500).map { hBox(it * 31, 0, 30, 40, "x") }
        val result = TextRegionMerger.merge(regions)
        assertTrue("应合并成多个 group", result.isNotEmpty())
    }
}
