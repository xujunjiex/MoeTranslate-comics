package com.moe.starflow.manga

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.moe.starflow.manga.TranslateUtils.parseNumberedTranslationsPartial

/**
 * parseNumberedTranslationsPartial 契约测试：
 * 1) 只返回「已完整」条目（最后一条可能仍在生成，跳过）
 * 2) 保留 [N] 编号，调用方按编号匹配气泡、跳过越界编号 —— 防止模型输出条数超过气泡数时越界崩溃
 */
class TranslateUtilsStreamingTest {

    @Test
    fun emptyText_returnsEmpty() {
        assertTrue(parseNumberedTranslationsPartial("").isEmpty())
    }

    @Test
    fun singleIncompleteEntry_isSkipped() {
        // 只有 [1]，无后续 [N] 确认其完整 → 不渲染（等 [2] 出现）
        assertTrue(parseNumberedTranslationsPartial("[1] hello").isEmpty())
    }

    @Test
    fun twoComplete_keepsOnlyFirst() {
        assertEquals(listOf(1 to "a"), parseNumberedTranslationsPartial("[1] a [2] b"))
    }

    @Test
    fun moreEntriesThanBubbles_keepsNumbersForCallerToFilter() {
        // 模型输出 5 条、只有 3 个气泡的越界场景：解析器保留全部已完整条目+编号，
        // 调用方用 mapNotNull { index = number-1; index in bubbles.indices } 过滤。
        val parsed = parseNumberedTranslationsPartial("[1] a [2] b [3] c [4] d [5] e")
        assertEquals(4, parsed.size)  // 全部除最后一条
        assertEquals(listOf(1, 2, 3, 4), parsed.map { it.first })

        // 模拟调用方过滤逻辑：只取编号 1..3 → 不会越界
        val bubbleCount = 3
        val rendered = parsed.mapNotNull { (number, text) ->
            val index = number - 1
            if (index !in 0 until bubbleCount) null else text
        }
        assertEquals(listOf("a", "b", "c"), rendered)
    }

    @Test
    fun skippedNumber_preservedForAlignment() {
        // 模型跳号输出 [1] [3] [4]：编号保留，调用方按编号对位，不会把 [3] 的文本贴到第 2 个气泡上
        val parsed = parseNumberedTranslationsPartial("[1] a [3] c [4] d")
        assertEquals(listOf(1 to "a", 3 to "c", 4 to "d"), parsed)
    }

    @Test
    fun trailingPartialEntry_isDropped() {
        assertEquals(listOf(1 to "a"), parseNumberedTranslationsPartial("[1] a [2] b [3"))
    }
}
