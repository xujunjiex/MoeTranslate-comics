# CTD Merge/Expand Rewrite Based on manga-image-translator

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rewrite CTD detection merge and expand logic to use dynamic, font-size-based thresholds instead of fixed multipliers, matching manga-image-translator's approach.

**Architecture:** Remove pre-expand step. Replace `mergeRectsByRowThenCol` with `canMergeWithDynamicThreshold` based on character size. Final-expand only adds minimal rendering padding, not scaling.

**Tech Stack:** Kotlin (Android), CTDDetector, Rect manipulation

---

## File Structure

```
app/src/main/java/com/moe/moetranslator/manga/
├── DetectionBridge.kt              # Modify: rewrite merge/expand logic
├── CTDDetector.kt                  # Read: to understand font_size estimation
```

---

## Task 1: Analyze CTD font_size estimation

**Files:**
- Read: `app/src/main/java/com/moe/moetranslator/manga/CTDDetector.kt:1-50` (CTDPostProcessor for font_size)
- Read: `app/src/main/java/com/moe/moetranslator/manga/CTDPostProcessor.kt` (for font_size extraction)

manga-image-translator uses `font_size` from CTD detection. We need to understand how to get or estimate font_size from our `detectRectsSimple` output.

- [ ] **Step 1: Check CTDPostProcessor for font_size**

Read `CTDPostProcessor.kt` to find how `font_size` is extracted from detection results.

- [ ] **Step 2: Check if DetectedRect or QuadBox has font_size**

Look at `CTDDetector.detectQuadBoxes` return type and what data it provides.

- [ ] **Step 3: Determine how to get font_size for detectRectsSimple output**

The current `detectRectsSimple` only returns `DetectedRect(rect, isVertical)`. We need font_size for dynamic merge.

- [ ] **Step 4: Commit analysis findings**

```bash
git add -m "docs: CTD font_size analysis for merge rewrite"
```

---

## Task 2: Rewrite merge logic with dynamic thresholds

**Files:**
- Modify: `app/src/main/java/com/moe/moetranslator/manga/DetectionBridge.kt:563-613`

The current `mergeRectsByRowThenCol` uses fixed thresholds:
```kotlin
val Y_GAP_THRESHOLD = 10
if (gap > 0 && gap <= Y_GAP_THRESHOLD) { ... } // BUG: doesn't account for font size
```

Replace with manga-image-translator approach based on `char_size`:
```kotlin
/**
 * 动态判断两个矩形是否可以合并（基于字高）
 *
 * 对齐 manga-image-translator quadrilateral_can_merge_region:
 * - gap 阈值 = discard_connection_gap * char_size
 * - char_size = min(字高A, 字高B)
 * - 横排用 X gap，竖排用 Y gap
 */
private fun canMergeWithDynamicThreshold(
    a: Rect, b: Rect,
    isVerticalA: Boolean, isVerticalB: Boolean,
    fontSizeA: Float, fontSizeB: Float
): Boolean {
    val charSize = minOf(fontSizeA, fontSizeB)
    val discardGap = 2 * charSize  // discard_connection_gap = 2
    val charGapTol = 0.6 * charSize  // char_gap_tolerance = 0.6
    val charGapTol2 = 1.5 * charSize  // char_gap_tolerance2 = 1.5
    val fontSizeRatioTol = 1.5f

    // 1. 字高比例检查
    if (maxOf(fontSizeA, fontSizeB) / charSize > fontSizeRatioTol) {
        return false
    }

    // 2. 横竖排不合并
    if (isVerticalA != isVerticalB) {
        return false
    }

    // 3. 计算间距（基于方向）
    if (isVerticalA) {
        // 竖排：检查 Y gap
        val gap = minOf(
            abs(a.top - b.bottom),  // a 在 b 上方
            abs(b.top - a.bottom)   // b 在 a 上方
        )
        if (gap > discardGap) return false
        // 竖排额外检查：y 边对齐
        val yAligned = abs(a.left - b.left) < charGapTol2 ||
                       abs(a.right - b.right) < charGapTol2
        return yAligned
    } else {
        // 横排：检查 X gap
        val gap = minOf(
            abs(a.left - b.right),  // a 在 b 左边
            abs(b.left - a.right)   // b 在 a 左边
        )
        if (gap > discardGap) return false
        // 横排额外检查：x 边对齐
        val xAligned = abs(a.top - b.top) < charGapTol2 ||
                       abs(a.bottom - b.bottom) < charGapTol2
        return xAligned
    }
}
```

- [ ] **Step 1: Add font_size estimation to DetectedRect or use height as proxy**

In CTD, `font_size` is typically estimated from detected box height. For simplicity, use `rect.height()` as proxy for font size (since vertical text boxes have height ≈ font_size).

- [ ] **Step 2: Rewrite mergeRectsByRowThenCol with dynamic thresholds**

Replace the function with new implementation that:
1. Sorts by vertical position
2. Groups by proximity (using dynamic Y_GAP based on font size)
3. Within groups, uses X gap with dynamic threshold to merge

```kotlin
private fun mergeRectsByRowThenCol(rects: List<DetectedRect>): List<Rect> {
    if (rects.isEmpty()) return emptyList()
    if (rects.size == 1) return listOf(rects[0].rect)

    val sorted = rects.sortedBy { it.rect.top }
    val rows = mutableListOf<MutableList<DetectedRect>>()
    var currentRow = mutableListOf<DetectedRect>()
    var currentRowBottom = sorted[0].rect.bottom
    var currentCharSize = sorted[0].rect.height().toFloat()

    for (detected in sorted) {
        val rect = detected.rect
        val charSize = rect.height().toFloat()
        val gap = rect.top - currentRowBottom

        // Dynamic Y gap threshold: 2 * avg char size of the row
        val dynamicGapThreshold = 2 * currentCharSize

        if (gap > 0 && gap <= dynamicGapThreshold) {
            currentRow.add(detected)
            currentRowBottom = maxOf(currentRowBottom, rect.bottom)
            currentCharSize = (currentCharSize + charSize) / 2  // running average
        } else {
            if (currentRow.isNotEmpty()) rows.add(currentRow)
            currentRow = mutableListOf(detected)
            currentRowBottom = rect.bottom
            currentCharSize = charSize
        }
    }
    if (currentRow.isNotEmpty()) rows.add(currentRow)

    // Within each row, merge by X proximity with dynamic threshold
    val result = mutableListOf<Rect>()
    for (row in rows) {
        val sortedRow = row.sortedBy { it.rect.left }
        var merged: Rect? = null
        for (detected in sortedRow) {
            val rect = detected.rect
            if (merged == null) {
                merged = rect
            } else {
                val gap = rect.left - merged.right
                val dynamicXGap = 2 * minOf(rect.height(), merged.height()).toFloat()
                if (gap <= dynamicXGap) {
                    merged = Rect(
                        merged.left,
                        minOf(merged.top, rect.top),
                        maxOf(merged.right, rect.right),
                        maxOf(merged.bottom, rect.bottom)
                    )
                } else {
                    result.add(merged)
                    merged = rect
                }
            }
        }
        if (merged != null) result.add(merged)
    }
    return result
}
```

- [ ] **Step 3: Update call site to pass DetectedRect list**

The current `detectWithCTD` calls `mergeRectsByRowThenCol(preExpandedRects)` with `List<Rect>`. Update to pass `List<DetectedRect>` instead so we have access to `isVertical` and `fontSize` proxy.

- [ ] **Step 4: Build verification**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/moe/moetranslator/manga/DetectionBridge.kt
git commit -m "refactor(DetectionBridge): dynamic merge thresholds based on font size"
```

---

## Task 3: Remove pre-expand, simplify final-expand

**Files:**
- Modify: `app/src/main/java/com/moe/moetranslator/manga/DetectionBridge.kt:181-230`

Current problematic flow:
1. pre-expand (1.5x) → makes boxes bigger artificially
2. merge → merges the expanded boxes
3. final-expand (2.5x/2.1x) → expands again

New flow (matching manga-image-translator):
1. detectRectsSimple → get raw boxes
2. merge with dynamic thresholds → no artificial expansion
3. final-expand with minimal rendering padding only (e.g., 10px each side)

- [ ] **Step 1: Remove pre-expand from detectWithCTD**

```kotlin
// REMOVED: pre-expand step
// val PRE_EXPAND = 1.5f
// val preExpandedRects = rects.map { ... }

// Instead, pass raw rects to merge
val mergedRects = mergeRectsByRowThenCol(rects)
```

- [ ] **Step 2: Change final-expand to simple padding (no scaling)**

```kotlin
// final-expand: just add rendering padding (10px each side)
// No scaling multiplier - merged boxes are already correctly sized
val PADDING = 10
val expandedRects = mergedRects.map { rect ->
    Rect(
        (rect.left - PADDING).coerceAtLeast(0),
        (rect.top - PADDING).coerceAtLeast(0),
        (rect.right + PADDING).coerceAtMost(bitmap.width),
        (rect.bottom + PADDING).coerceAtMost(bitmap.height)
    )
}
```

- [ ] **Step 3: Build verification**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/moe/moetranslator/manga/DetectionBridge.kt
git commit -m "refactor(DetectionBridge): remove pre-expand, use simple padding for final-expand"
```

---

## Task 4: Verify and test

After implementing, user should test:
- CTD + manga-ocr: Are independent bubbles correctly NOT merged?
- CTD + MLKit: Same as above
- Rendering: Does text fit within expanded regions without overflow?

---

## Verification Checklist

- [ ] `canMergeWithDynamicThreshold` function exists with correct manga-image-translator logic
- [ ] `mergeRectsByRowThenCol` uses dynamic thresholds (not fixed 10/5 pixels)
- [ ] Pre-expand step removed from `detectWithCTD`
- [ ] Final-expand uses simple padding (10px) instead of scaling multipliers
- [ ] Both CTD+MLKit and CTD+manga-ocr use the same merge logic
- [ ] Build passes
- [ ] Independent bubbles are NOT merged together
- [ ] Related bubbles ARE merged correctly
- [ ] Rendering区域 doesn't excessively超出原始文字区域