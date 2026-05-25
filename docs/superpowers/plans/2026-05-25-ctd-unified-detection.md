# CTD OCR Engine Unification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Unify `detectWithCTDMLKit` and `detectWithCTDManga` into a single `detectWithCTD` function with `CTDOCREngine` parameter.

**Architecture:** Create `CTDOCREngine` enum, refactor common CTD detection flow into one function, deprecate the two old functions.

**Tech Stack:** Kotlin (Android), CTDDetector, MangaOcrRecognizer, OCRBridge, ML Kit

---

## File Structure

```
app/src/main/java/com/moe/moetranslator/manga/
├── DetectionBridge.kt              # Modify: add enum, unified function, deprecate old
├── MangaFloatingService.kt         # Modify: update function calls
```

---

## Task 1: Add CTDOCREngine enum and detectWithCTD function

**Files:**
- Modify: `app/src/main/java/com/moe/moetranslator/manga/DetectionBridge.kt:16-20`

- [ ] **Step 1: Add CTDOCREngine enum after BATCH_SIZE constant**

```kotlin
    private const val BATCH_SIZE = 16

    enum class CTDOCREngine {
        MLKit,
        MangaOcr
    }
```

- [ ] **Step 2: Add unified detectWithCTD function (replacing detectWithCTDMLKit/detectWithCTDManga)**

```kotlin
    /**
     * 统一 CTD 检测 + OCR 识别函数。
     *
     * 流程：CTD 简化检测 → pre-expand(1.5x) → merge → final-expand(2.5x宽/3x高) → OCR
     *
     * @param bitmap 输入图片
     * @param language 语言
     * @param ocrEngine OCR 引擎选择
     * @return TextBlockInfo 列表（含位置和识别文字）
     */
    suspend fun detectWithCTD(
        bitmap: Bitmap,
        language: String,
        ocrEngine: CTDOCREngine
    ): List<TextBlockInfo> {
        try {
            // Step 1: CTD 简化检测
            LogCollector.d(TAG, "使用 CTD(${ocrEngine.name}) 检测文字区域...")
            val rects = CTDDetector.detectRectsSimple(bitmap)
            if (rects.isEmpty()) {
                LogCollector.d(TAG, "CTD(${ocrEngine.name}) 未检测到文字区域")
                return emptyList()
            }
            LogCollector.d(TAG, "CTD(${ocrEngine.name}) 检测到 ${rects.size} 个文字区域")
            for ((idx, detectedRect) in rects.withIndex()) {
                LogCollector.d(TAG, "CTD(${ocrEngine.name}) 检测[$idx]: rect=[${detectedRect.rect.left}, ${detectedRect.rect.top}, ${detectedRect.rect.right}, ${detectedRect.rect.bottom}], isVertical=${detectedRect.isVertical}")
            }

            // Step 2: pre-expand (1.5x)
            val PRE_EXPAND = 1.5f
            val preExpandedRects = rects.map { detectedRect ->
                val rect = detectedRect.rect
                val cx = (rect.left + rect.right) / 2f
                val ew = rect.width() * PRE_EXPAND
                Rect(
                    (cx - ew / 2).toInt().coerceAtLeast(0),
                    rect.top,
                    (cx + ew / 2).toInt().coerceAtMost(bitmap.width),
                    rect.bottom
                )
            }
            LogCollector.d(TAG, "CTD(${ocrEngine.name}) 预扩展后: ${preExpandedRects.size} 个框")

            // Step 3: mergeRectsByRowThenCol
            val mergedRects = mergeRectsByRowThenCol(preExpandedRects)
            LogCollector.d(TAG, "CTD(${ocrEngine.name}) 合并: ${rects.size} → ${mergedRects.size} 个区域")

            // Step 4: final-expand 宽度（2.5x）+ 高度（3x）
            val FINAL_EXPAND_WIDTH = 2.5f
            val FINAL_EXPAND_HEIGHT = 3.0f
            val expandedRects = mergedRects.map { rect ->
                val cx = (rect.left + rect.right) / 2f
                val cy = (rect.top + rect.bottom) / 2f
                val ew = rect.width() * FINAL_EXPAND_WIDTH
                val eh = maxOf(rect.height() * FINAL_EXPAND_HEIGHT, 32f)
                Rect(
                    (cx - ew / 2).toInt().coerceAtLeast(0),
                    (cy - eh / 2).toInt().coerceAtLeast(0),
                    (cx + ew / 2).toInt().coerceAtMost(bitmap.width),
                    (cy + eh / 2).toInt().coerceAtMost(bitmap.height)
                )
            }
            for ((idx, rect) in expandedRects.withIndex()) {
                val merged = mergedRects.getOrNull(idx)
                val mergedStr = if (merged != null) " → [${merged.left}, ${merged.top}, ${merged.right}, ${merged.bottom}]" else ""
                LogCollector.d(TAG, "CTD(${ocrEngine.name}) [$idx]: 合并后→最终扩展: [${rect.left}, ${rect.top}, ${rect.right}, ${rect.bottom}]$mergedStr")
            }

            // Step 5: 裁剪图片
            val croppedBitmaps = expandedRects.map { rect -> cropBitmap(bitmap, rect) }

            // Step 6: OCR 识别
            val globalIsVertical = rects.count { it.isVertical } > rects.size / 2
            val results = mutableListOf<TextBlockInfo>()

            when (ocrEngine) {
                CTDOCREngine.MangaOcr -> {
                    val texts = MangaOcrRecognizer.recognizeBatch(croppedBitmaps)
                    for (i in expandedRects.indices) {
                        val text = texts[i].trim()
                        if (text.isNotBlank() && !isDotOnlyPattern(text)) {
                            val rect = expandedRects[i]
                            results.add(TextBlockInfo(
                                text = text,
                                boundingBox = rect,
                                cornerPoints = null,
                                isVertical = globalIsVertical
                            ))
                            LogCollector.d(TAG, "CTD(${ocrEngine.name}) 识别结果[$i]: rect=[${rect.left}, ${rect.top}, ${rect.right}, ${rect.bottom}], text='$text', isVertical=$globalIsVertical")
                        } else if (isDotOnlyPattern(text)) {
                            LogCollector.d(TAG, "CTD(${ocrEngine.name}) 过滤纯符号[$i]: '$text'")
                        }
                    }
                }
                CTDOCREngine.MLKit -> {
                    for (i in expandedRects.indices) {
                        val cropped = croppedBitmaps[i]
                        try {
                            val text = OCRBridge.recognizeText(language, cropped)
                            if (text.isNotBlank()) {
                                val rect = expandedRects[i]
                                results.add(TextBlockInfo(
                                    text = text,
                                    boundingBox = rect,
                                    cornerPoints = null,
                                    isVertical = globalIsVertical
                                ))
                                LogCollector.d(TAG, "CTD(${ocrEngine.name}) 识别结果[$i]: rect=[${rect.left}, ${rect.top}, ${rect.right}, ${rect.bottom}], text='$text', isVertical=$globalIsVertical")
                            }
                        } catch (e: Exception) {
                            LogCollector.e(TAG, "CTD(${ocrEngine.name}) ML Kit 识别失败[$i]", e)
                        }
                    }
                }
            }

            // 释放裁剪的图片
            for (cropped in croppedBitmaps) {
                if (cropped !== bitmap) cropped.recycle()
            }

            LogCollector.d(TAG, "CTD(${ocrEngine.name}) 完成，共 ${results.size} 个文字块")
            return results

        } catch (e: Exception) {
            LogCollector.e(TAG, "CTD(${ocrEngine.name}) 失败", e)
            throw e
        }
    }
```

- [ ] **Step 3: Deprecate old functions**

Add `@Deprecated` annotations to `detectWithCTDMLKit` and `detectWithCTDManga`:

```kotlin
    @Deprecated("Use detectWithCTD with CTDOCREngine parameter", ReplaceWith("detectWithCTD(bitmap, language, CTDOCREngine.MLKit)"))
    suspend fun detectWithCTDMLKit(...)

    @Deprecated("Use detectWithCTD with CTDOCREngine parameter", ReplaceWith("detectWithCTD(bitmap, language, CTDOCREngine.MangaOcr)"))
    suspend fun detectWithCTDManga(...)
```

- [ ] **Step 4: Build verification**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/moe/moetranslator/manga/DetectionBridge.kt
git commit -m "refactor(DetectionBridge): add CTDOCREngine enum and unified detectWithCTD function"
```

---

## Task 2: Update MangaFloatingService calls

**Files:**
- Modify: `app/src/main/java/com/moe/moetranslator/manga/MangaFloatingService.kt:986-993`

- [ ] **Step 1: Update function calls**

Replace:
```kotlin
DetEngine.CTD -> {
    if (config.useMangaOcr) {
        LogCollector.d(TAG, "使用 CTD(简化) 检测 + manga-ocr 识别")
        DetectionBridge.detectWithCTDManga(bitmap, config.sourceLang)
    } else {
        LogCollector.d(TAG, "使用 CTD 检测 + ML Kit 识别")
        DetectionBridge.detectWithCTDMLKit(bitmap, config.sourceLang)
    }
}
```

With:
```kotlin
DetEngine.CTD -> {
    val ocrEngine = if (config.useMangaOcr) DetectionBridge.CTDOCREngine.MangaOcr else DetectionBridge.CTDOCREngine.MLKit
    LogCollector.d(TAG, "使用 CTD(${ocrEngine.name}) 识别")
    DetectionBridge.detectWithCTD(bitmap, config.sourceLang, ocrEngine)
}
```

- [ ] **Step 2: Build verification**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/moe/moetranslator/manga/MangaFloatingService.kt
git commit -m "refactor(MangaFloatingService): use unified detectWithCTD function"
```

---

## Task 3: Remove deprecated functions (optional, do later)

After verifying the unified function works correctly, the deprecated functions can be removed in a follow-up task.

---

## Verification Checklist

- [ ] `CTDOCREngine` enum exists with `MLKit` and `MangaOcr` values
- [ ] `detectWithCTD` function handles both OCR engines correctly
- [ ] `detectWithCTDMLKit` and `detectWithCTDManga` are marked `@Deprecated`
- [ ] `MangaFloatingService` calls `detectWithCTD` with correct engine
- [ ] Build passes
- [ ] Both CTD+MLKit and CTD+manga-ocr modes work correctly