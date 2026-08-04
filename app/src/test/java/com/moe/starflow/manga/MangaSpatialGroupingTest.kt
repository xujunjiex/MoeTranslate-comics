package com.moe.starflow.manga

import android.graphics.Bitmap
import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MangaSpatialGroupingTest {

    private fun bubble(left: Int, top: Int, right: Int, bottom: Int) = CroppedBubble(
        croppedBitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888),
        rect = Rect(left, top, right, bottom),
        classId = 0,
        confidence = 1f
    )

    @Test
    fun sortByReadingOrder_topToBottomThenRightToLeft() {
        val a = bubble(0, 50, 100, 150)   // 下
        val b = bubble(200, 0, 300, 50)   // 上右
        val c = bubble(0, 0, 100, 50)     // 上左
        val sorted = MangaSpatialGrouping.sortByMangaReadingOrder(listOf(a, b, c))
        assertEquals(listOf(b, c, a), sorted)  // top 小的先，同 top 时 right 大的先
    }

    @Test
    fun groupByProximity_clustersNearRectsAndSeparatesFar() {
        val rects = listOf(
            Rect(0, 0, 50, 50),      // 与 [2] 相邻 → 合并
            Rect(500, 0, 550, 50),   // 与任何都远（dx≥400）→ 孤立
            Rect(50, 0, 100, 50)     // 与 [0] dx=0 < 250 → 合并
        )
        val groups = MangaSpatialGrouping.groupByProximity(rects, { it }, "test")
        assertEquals(2, groups.size)   // [0,2] 一组、[1] 一组
    }

    @Test
    fun groupByProximity_singleElementReturnsSelf() {
        val groups = MangaSpatialGrouping.groupByProximity(listOf(Rect(0, 0, 10, 10)), { it }, "test")
        assertEquals(1, groups.size)
        assertEquals(1, groups[0].size)
    }

    @Test
    fun splitAtGroupBoundaries_splitsWithoutBreakingGroups() {
        // total=5, target=5*2/5=2 → 切在第 2 组（[a,b]）后 → 第一批 2 元素
        val groups = listOf(listOf("a", "b"), listOf("c", "d"), listOf("e"))
        val (first, second) = MangaSpatialGrouping.splitAtGroupBoundaries(groups)
        assertEquals(listOf("a", "b"), first)
        assertEquals(listOf("c", "d", "e"), second)
    }

    @Test
    fun textBlocksToBubbleRegions_filtersSingleCharNoise() {
        val blocks = listOf(
            TextBlockInfo("，", Rect(0, 0, 10, 20), null),       // 单字符标点 → 过滤
            TextBlockInfo("hello", Rect(10, 0, 110, 20), null)   // 正常
        )
        val regions = MangaSpatialGrouping.textBlocksToBubbleRegions(blocks, TextDirection.HORIZONTAL)
        assertEquals(1, regions.size)
        assertEquals("hello", regions[0].texts[0])
    }

    @Test
    fun textBlocksToBubbleRegions_verticalInferenceAndDirectionParam() {
        val block = TextBlockInfo("あ", Rect(0, 0, 10, 100), null)  // 高100 > 宽10 → 竖排
        val regions = MangaSpatialGrouping.textBlocksToBubbleRegions(
            listOf(block), TextDirection.VERTICAL_LR
        )
        assertEquals(TextDirection.VERTICAL_LR, regions[0].direction)
        assertEquals(10f, regions[0].fontSize, 0.01f)  // 竖排 fontSize = width
    }
}
