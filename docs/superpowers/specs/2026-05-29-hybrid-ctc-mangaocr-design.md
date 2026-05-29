# CTD Hybrid 更新：manga-ocr + CTC 并行

## Context
当前 HYBRID 模式：合并组 → manga-ocr，单框 → ML Kit。有两个问题：
1. 单框用 ML Kit 慢且不支持 batch
2. manga-ocr 路径细节与 CTD+manga-ocr 标准流程不一致（缺 padding、逐个调用、没过滤）

更新后：合并组 → manga-ocr，单框 → CTC，并行执行。两条路径完全对齐已验证的 CTD+manga-ocr 和 CTD+CTC 流程。

## 当前 Hybrid vs CTD 标准流程的差异

| 细节 | Hybrid (当前) | CTD+manga-ocr 标准 | CTD+CTC 标准 |
|------|-------------|-------------------|-------------|
| 组裁剪 padding | **无** | cropBitmap +10px | AABB 直接裁剪，cropBitmap +10px |
| OCR 调用方式 | 逐个 recognize | **recognizeBatch** | **recognizeBatch** |
| isDotOnlyPattern | **未过滤** | 过滤 | 过滤 |
| isVertical | null | globalIsVertical | **per-box assignedDirection** |
| CTC 预处理 | 无 | N/A | **prepareCtcInputs** |

## 设计

### 数据流（对齐标准流程）
```
CTD detect → BoxMerger.merge → 分流
  ├─ 合并组(size>1)
  │   computeUnionAABB → cropBitmap(+10px) → resize 224x224 → manga-ocr.recognizeBatch
  │   过滤 isDotOnlyPattern，isVertical=globalIsVertical
  │
  └─ 单框(size==1)
      AABB → cropBitmap(+10px) → prepareCtcInputs(structRatio+direction) → CTC.recognizeBatch
      过滤 isDotOnlyPattern，isVertical=per-box assignedDirection

  两者 coroutineScope + async 并行执行
```

### 修改文件

**1. `DetectionBridge.kt`**

- `prepareCtcInputs` 改为 `internal`（当前是 `private`，Hybrid 方法需要调用）
- `detectWithCTDHybrid` 重写：
  - Step 1-2 不变：CTD detect → BoxMerger.merge
  - Step 3 分流：
    ```kotlin
    // 收集合并组和单框
    val mangaGroups = mutableListOf<Pair<Int, List<QuadBox>>>()
    val ctcSingles = mutableListOf<Pair<Int, QuadBox>>()
    for ((idx, group) in groups.withIndex()) {
        if (group.size > 1) mangaGroups.add(idx to group)
        else ctcSingles.add(idx to group.first())
    }
    val globalIsVertical = rawQuadBoxes.count { it.aspectRatio > 1f } > rawQuadBoxes.size / 2
    ```
  - 并行执行（对齐标准流程细节）：
    ```kotlin
    coroutineScope {
        val mangaDeferred = async {
            // 对齐 CTD+MangaOcr：cropBitmap(+10px) → resize 224x224 → recognizeBatch → isDotOnlyPattern
            val mangaResults = mutableListOf<Pair<Int, TextBlockInfo>>()
            if (mangaGroups.isNotEmpty()) {
                val expandedRects = mangaGroups.map { (_, group) ->
                    val unionRect = computeUnionAABB(group)
                    // +10px padding（对齐 detectWithCTD MangaOcr 路径的 PADDING=10）
                    Rect(
                        (unionRect.left - 10).coerceAtLeast(0),
                        (unionRect.top - 10).coerceAtLeast(0),
                        (unionRect.right + 10).coerceAtMost(bitmap.width),
                        (unionRect.bottom + 10).coerceAtMost(bitmap.height)
                    )
                }
                val croppedBitmaps = expandedRects.map { rect -> cropBitmap(bitmap, rect) }
                val resizedBitmaps = croppedBitmaps.map { crop ->
                    val r = Bitmap.createScaledBitmap(crop, 224, 224, true)
                    crop.recycle(); r
                }
                val texts = MangaOcrRecognizer.recognizeBatch(resizedBitmaps)
                resizedBitmaps.forEach { it.recycle() }
                for (i in mangaGroups.indices) {
                    val text = texts[i].trim()
                    if (text.isNotBlank() && !isDotOnlyPattern(text)) {
                        mangaResults.add(mangaGroups[i].first to TextBlockInfo(
                            text = text,
                            boundingBox = expandedRects[i],
                            cornerPoints = null,
                            isVertical = globalIsVertical  // 对齐标准 MangaOcr 流程
                        ))
                    }
                }
            }
            mangaResults
        }
        val ctcDeferred = async {
            // 对齐 CTD+CTC：AABB → cropBitmap(+10px) → prepareCtcInputs → recognizeBatch → per-box direction
            val ctcResults = mutableListOf<Pair<Int, TextBlockInfo>>()
            if (ctcSingles.isNotEmpty()) {
                val singleGroups = ctcSingles.map { it.second }
                // expandedRects = AABB 直接（不额外加 padding，cropBitmap 内部加 10px）
                val expandedRects = singleGroups.map { qb ->
                    val aabb = qb.aabb
                    Rect(
                        aabb.left.coerceAtLeast(0),
                        aabb.top.coerceAtLeast(0),
                        aabb.right.coerceAtMost(bitmap.width),
                        aabb.bottom.coerceAtMost(bitmap.height)
                    )
                }
                val croppedBitmaps = expandedRects.map { rect -> cropBitmap(bitmap, rect) }
                // prepareCtcInputs 需要 mergedGroups 格式
                val mergedGroupsForCtc = singleGroups.map { listOf(it) }
                val ctcInputs = prepareCtcInputs(croppedBitmaps, mergedGroupsForCtc, expandedRects, globalIsVertical)
                croppedBitmaps.forEach { if (it !== bitmap) it.recycle() }
                val texts = CtcOcrRecognizer.recognizeBatch(ctcInputs)
                ctcInputs.forEach { it.recycle() }
                for (i in ctcSingles.indices) {
                    val text = texts[i].trim()
                    val isVertical = singleGroups[i].assignedDirection == "v"  // per-box direction
                    if (text.isNotBlank() && !isDotOnlyPattern(text)) {
                        ctcResults.add(ctcSingles[i].first to TextBlockInfo(
                            text = text,
                            boundingBox = expandedRects[i],
                            cornerPoints = null,
                            isVertical = isVertical
                        ))
                    }
                }
            }
            ctcResults
        }
        val allResults = (mangaDeferred.await() + ctcDeferred.await())
            .sortedBy { it.first }
            .map { it.second }
    }
    ```

**2. `MangaFloatingService.kt` — HYBRID 初始化**

- `DetEngine.HYBRID` 分支增加 CTC 初始化：
  ```kotlin
  DetEngine.HYBRID -> {
      initCTDIfNeeded()
      ensureMangaOcrInitialized()
      if (CtcOcrModelManager.isModelDownloaded(applicationContext)) {
          initCTCOcrIfNeeded()
      } else {
          LogCollector.w(TAG, "CTC 模型未下载，HYBRID 单框将无法识别")
      }
  }
  ```
- 日志更新：`"CTD 检测 + 混合 OCR（合并组→manga-ocr, 单框→CTC）"`

## 验证
1. 编译通过
2. HYBRID 模式截图：日志确认合并组走 manga-ocr（recognizeBatch）、单框走 CTC
3. 确认 manga-ocr 有 10px padding、有 isDotOnlyPattern 过滤、isVertical=globalIsVertical
4. 确认 CTC 有 prepareCtcInputs 预处理、per-box direction、isDotOnlyPattern 过滤
5. 并行执行：manga-ocr 和 CTC 日志时间重叠
