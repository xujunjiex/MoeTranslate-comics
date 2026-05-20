package com.moe.moetranslator.manga

/**
 * 阅读顺序排序器。
 * 移植自 manga-image-translator 的 merge_bboxes_text_region 中的排序逻辑。
 * 竖排日文：从右到左，从上到下；横排：从左到右，从上到下。
 */
object ReadingOrderSorter {

    /**
     * 按阅读顺序排序气泡。
     */
    fun sort(bubbles: List<BubbleRegion>, config: MangaModeConfig): List<BubbleRegion> {
        if (bubbles.size <= 1) return bubbles

        return when (config.textDirection) {
            TextDirection.VERTICAL_RL -> {
                // 竖排右→左：x 降序（从右到左），同列 y 升序（从上到下）
                bubbles.sortedWith(compareByDescending<BubbleRegion> { it.rect.centerX() }
                    .thenBy { it.rect.centerY() })
            }
            TextDirection.VERTICAL_LR -> {
                // 竖排左→右：x 升序（从左到右），同列 y 升序（从上到下）
                bubbles.sortedWith(compareBy<BubbleRegion> { it.rect.centerX() }
                    .thenBy { it.rect.centerY() })
            }
            TextDirection.HORIZONTAL -> {
                // 横排：y 升序（从上到下），同行 x 升序（从左到右）
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
