# CTD + 混合 OCR 路径设计

## 背景

现有 CTD 路径存在两个问题：
1. `mergeRectsByRowThenCol` 合并效果差（22→22），未真正减少 OCR 调用
2. 所有检测框统一用 manga-ocr 或 ML Kit，未按复杂度分流

本设计新增一条混合 OCR 路径，参考 manga-image-translator 的思路。

## 参考项目分析

### manga-image-translator 的 OCR 流程

```
检测 22 个 textlines
    ↓
┌─────────────────────────────────────────────┐
│  manga-ocr 路径：merge_bboxes → 处理合并区域  │
│  （16 次调用，精度优先）                      │
├─────────────────────────────────────────────┤
│  48px CTC 路径：处理所有原始区域               │
│  （2 次 batch 调用，速度优先 + 提供颜色）       │
└─────────────────────────────────────────────┘
    ↓
最终结果：manga-ocr 文字 + 48px CTC 颜色
```

**关键发现**：
- manga-ocr 只处理 merged 区域（16 个），不是所有检测框
- 48px CTC 处理所有原始区域（22 个），用于颜色提取
- 两条路径互补，manga-ocr 结果优先级更高

### BoxMerger 修复

原 `BoxMerger` 参数与 manga-image-translator 不一致，已修复：

| 参数 | 原值 | 正确值 |
|------|------|--------|
| `CHAR_GAP_TOLERANCE` | 0.6 | 1.0 |
| `CHAR_GAP_TOLERANCE2` | 1.5 | 3.0 |
| `FONT_SIZE_RATIO_TOL` | 1.5 | 2.0 |
| `ASPECT_RATIO_TOL` | 2.0 | 1.3 |
| 距离计算 | AABB Chebyshev | 多边形距离 `polyDistance()` |

## 新路径设计

### 入口

```kotlin
DetectionBridge.detectWithCTDHybrid(bitmap: Bitmap, language: String): List<TextBlockInfo>
```

### 流程

```
CTD 检测
    ↓
BoxMerger.merge(quadBoxes) → List<List<QuadBox>>
    ↓
┌────────────────────────────────────────────┐
│  对每个合并组判断：                          │
│  - size > 1（多个 QuadBox 合并）：manga-ocr   │
│  - size == 1（单个 QuadBox）：ML Kit         │
├────────────────────────────────────────────┤
│  manga-ocr 分支：                           │
│  1. 取合并组的 union AABB                   │
│  2. 裁剪图片                                │
│  3. 缩放到 224x224（保持现有行为）           │
│  4. MangaOcrRecognizer.recognize()          │
│  5. 结果作为最终结果                         │
├────────────────────────────────────────────┤
│  ML Kit 分支：                              │
│  1. 取单个 QuadBox 的 AABB                 │
│  2. 裁剪图片（+padding）                    │
│  3. OCRBridge.recognizeText()              │
│  4. 结果作为最终结果                         │
└────────────────────────────────────────────┘
    ↓
合并所有结果，按阅读顺序排序
```

### 详细设计

#### 1. CTD 检测

```kotlin
val quadBoxes = CTDDetector.detectQuadBoxes(bitmap)
```

输出：`List<QuadBox>`，每个包含几何信息（旋转四边形）+ fontSize。

#### 2. BoxMerger 合并

```kotlin
val groups = BoxMerger.merge(quadBoxes)  // List<List<QuadBox>>
```

输出：`List<List<QuadBox>>`，每组已按阅读顺序排序。

- **合并组**（size > 1）：多个 QuadBox 属于同一气泡
- **单独组**（size == 1）：独立的文字区域

#### 3. 合并组 → manga-ocr

对每个 `size > 1` 的组：

```kotlin
fun processMergedGroup(bitmap: Bitmap, group: List<QuadBox>): TextBlockInfo {
    // 1. 计算 union AABB（包含所有 QuadBox 的最小矩形）
    val unionRect = computeUnionAABB(group)

    // 2. 裁剪图片
    val crop = cropBitmap(bitmap, unionRect)

    // 3. 缩放到 224x224（与现有路径一致）
    val resized = Bitmap.createScaledBitmap(crop, 224, 224, true)

    // 4. manga-ocr 识别
    val text = MangaOcrRecognizer.recognize(resized)

    return TextBlockInfo(text, unionRect, ...)
}
```

**computeUnionAABB 实现**：
```kotlin
fun computeUnionAABB(group: List<QuadBox>): Rect {
    var left = Int.MAX_VALUE; var top = Int.MAX_VALUE
    var right = Int.MIN_VALUE; var bottom = Int.MIN_VALUE
    for (qb in group) {
        val aabb = qb.aabb
        left = minOf(left, aabb.left)
        top = minOf(top, aabb.top)
        right = maxOf(right, aabb.right)
        bottom = maxOf(bottom, aabb.bottom)
    }
    return Rect(left, top, right, bottom)
}
```

#### 4. 单独组 → ML Kit

对每个 `size == 1` 的 QuadBox：

```kotlin
fun processSingleQuadBox(bitmap: Bitmap, qb: QuadBox, language: String): TextBlockInfo {
    // 1. 取 AABB + padding
    val rect = qb.aabb
    val paddedRect = Rect(
        (rect.left - 10).coerceAtLeast(0),
        (rect.top - 10).coerceAtLeast(0),
        (rect.right + 10).coerceAtMost(bitmap.width),
        (rect.bottom + 10).coerceAtMost(bitmap.height)
    )

    // 2. 裁剪图片
    val crop = Bitmap.createBitmap(bitmap, paddedRect.left, paddedRect.top,
                                   paddedRect.width(), paddedRect.height())

    // 3. ML Kit 识别
    val text = OCRBridge.recognizeText(language, crop)

    return TextBlockInfo(text, paddedRect, isVertical = null)
}
```

#### 5. 阅读顺序排序

`BoxMerger.merge` 返回的组已经排好序，但不同组合并组的相对顺序需要确定。

按各组的**中心点**排序：
- 竖排为主：从右到左，从上到下
- 横排为主：从左到右，从上到下

```kotlin
fun sortByReadingOrder(groups: List<List<QuadBox>>): List<List<QuadBox>> {
    // 判断整体方向
    val isVertical = groups.count { g -> g.first().let { qb ->
        // 竖排：高度 > 宽度
        qb.aabb.height() > qb.aabb.width()
    }} > groups.size / 2

    return if (isVertical) {
        groups.sortedWith(compareBy({ -it.first().centroidX }, { it.first().centroidY }))
    } else {
        groups.sortedWith(compareBy({ it.first().centroidY }, { it.first().centroidX }))
    }
}
```

### 分流阈值设计

**当前方案：按组合并的 QuadBox 数量判断**

| 条件 | OCR 引擎 | 理由 |
|------|---------|------|
| `size > 1` | manga-ocr | 多个框合并，上下文重要，精度优先 |
| `size == 1` | ML Kit | 单个框，上下文简单，速度优先 |

**为什么不按框大小判断**：
- 合并组本身就代表了"需要一起处理"的区域
- 如果单个 QuadBox 很大，说明检测可能有问题（误检或跨气泡）
- 这个判断标准简单明确

### 预估收益

以日志中的 22 个检测框为例：
- 假设 22→16 合并（实际取决于图片）
- manga-ocr 调用：16 次（合并组）
- ML Kit 调用：6 次（未合并的单个）
- Encoder 时间节省：约 27%（16/22 → 如果完全合并成功）

如果合并效果更好（如 22→10），节省更多。

## 实现位置

```
app/src/main/java/com/moe/moetranslator/manga/
├── DetectionBridge.kt       ← 添加 detectWithCTDHybrid()
├── BoxMerger.kt             ← 已修复，直接使用
└── MangaFloatingService.kt  ← 添加新的 DetEngine.HYBRID 枚举值
```

### 新增方法

```kotlin
// DetectionBridge.kt

// 新增入口方法
suspend fun detectWithCTDHybrid(
    bitmap: Bitmap,
    language: String
): List<TextBlockInfo>

// 辅助函数
private fun computeUnionAABB(group: List<QuadBox>): Rect
private fun sortByReadingOrder(groups: List<List<QuadBox>>): List<List<QuadBox>>
```

## 风险与注意事项

1. **manga-ocr 输入尺寸**：与现有路径一致，强制 224×224，已有行为。

2. **union AABB 的背景噪声**：合并组之间可能有背景，但 manga-ocr 相对鲁棒。

3. **ML Kit 识别失败**：需要错误处理，识别失败时跳过或降级。

4. **现有路径不受影响**：新路径是独立方法，不修改现有 `detectWithCTD`。

5. **并发**：manga-ocr 和 ML Kit 调用是串行的（先跑完所有 manga-ocr，再跑所有 ML Kit，或反之）。后续可优化为 coroutine 并行。

6. **调试日志**：新路径需要详细的日志，便于对比旧路径验证效果。

## 测试计划

1. 编译验证
2. 对比日志中的相同图片：
   - 旧路径：22 个检测框 → 22 次 manga-ocr encoder
   - 新路径：合并后 → 预期更少 encoder 调用
3. 验证识别结果一致性（对同一张图片，新旧路径结果应接近）

## 实现步骤（TODO）

- [ ] 在 `DetectionBridge.kt` 添加 `detectWithCTDHybrid()` 方法
- [ ] 实现 `computeUnionAABB()` 辅助函数
- [ ] 实现 `sortByReadingOrder()` 辅助函数
- [ ] 在 `MangaFloatingService.kt` 添加 `DetEngine.HYBRID` 枚举值
- [ ] 在 `MangaFloatingService.kt` 的 OCR 流程中添加 `detectWithCTDHybrid` 分支
- [ ] 添加详细的调试日志
- [ ] 编译验证
- [ ] 实际测试对比