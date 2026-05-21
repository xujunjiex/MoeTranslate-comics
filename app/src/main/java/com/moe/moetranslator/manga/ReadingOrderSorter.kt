package com.moe.moetranslator.manga

/**
 * 阅读顺序排序器。
 * 移植自 manga-image-translator 的 merge_bboxes_text_region 中的排序逻辑。
 * 竖排日文：从右到左，从上到下；横排：从左到右，从上到下。
 */
object ReadingOrderSorter {

    /**
     * 按阅读顺序排序气泡（使用每个气泡自身的方向）。
     */
    fun sort(bubbles: List<BubbleRegion>): List<BubbleRegion> {
        if (bubbles.size <= 1) return bubbles

        // 按每个气泡的方向分组排序
        // 对于混合方向的漫画，先按方向分组，每组内排序，再合并
        // 简化方案：统一按第一个气泡的方向排序（大多数漫画方向一致）
        val primaryDir = bubbles.first().direction

        return when (primaryDir) {
            TextDirection.VERTICAL_RL -> {
                bubbles.sortedWith(compareByDescending<BubbleRegion> { it.rect.centerX() }
                    .thenBy { it.rect.centerY() })
            }
            TextDirection.VERTICAL_LR -> {
                bubbles.sortedWith(compareBy<BubbleRegion> { it.rect.centerX() }
                    .thenBy { it.rect.centerY() })
            }
            TextDirection.HORIZONTAL -> {
                bubbles.sortedWith(compareBy<BubbleRegion> { it.rect.centerY() }
                    .thenBy { it.rect.centerX() })
            }
        }
    }

    /**
     * 对气泡内的文本行按阅读顺序排序（用于合并前的文本拼接）。
     */
    fun sortTextLines(textLines: List<TextLine>, direction: TextDirection): List<TextLine> {
        if (textLines.size <= 1) return textLines

        return when (direction) {
            TextDirection.VERTICAL_RL -> {
                textLines.sortedWith(compareByDescending<TextLine> { it.centroidX }
                    .thenBy { it.centroidY })
            }
            TextDirection.VERTICAL_LR -> {
                textLines.sortedWith(compareBy<TextLine> { it.centroidX }
                    .thenBy { it.centroidY })
            }
            TextDirection.HORIZONTAL -> {
                textLines.sortedWith(compareBy<TextLine> { it.centroidY }
                    .thenBy { it.centroidX })
            }
        }
    }
}
