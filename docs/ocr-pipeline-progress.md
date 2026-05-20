# 漫画翻译 OCR 管线改进 — 进度报告

日期：2026-05-20
分支：`feature/ocr-improvements`

---

## 文件变更清单

### 新建文件

| 文件 | 改动内容 | 编译状态 | 备注 |
|------|----------|----------|------|
| `manga/TextLine.kt` | 新建 `TextLine` 数据类（rect、text、direction、fontSize、centroidX/Y、aspectRatio）；`TextBlockInfo.toTextLine()` 扩展函数；`rectDistance()` 工具函数 | ✅ 通过 | — |
| `manga/BubbleMerger.kt` | 多条件合并器，移植自 `quadrilateral_can_merge_region`。5 个合并条件：距离、字体大小比、方向一致性、对齐方式、宽高比。使用 Union-Find 连通分量 | ✅ 通过 | `config` 参数预留但当前未使用（方向判断已在 `toTextLine` 中完成） |
| `manga/TextRegionSplitter.kt` | MST 分割器，移植自 `split_text_region`。Kruskal 最小生成树 + 距离标准差判断是否切断最大边。含内部 `UnionFind` 类 | ✅ 通过 | — |
| `manga/ReadingOrderSorter.kt` | 阅读顺序排序器。`sort()` 按气泡排序；`sortTextLines()` 按文本行排序。竖排 RL 从右到左，竖排 LR 从左到右，横排从上到下 | ✅ 通过 | — |

### 修改文件

| 文件 | 改动内容 | 编译状态 | 备注 |
|------|----------|----------|------|
| `manga/BubbleDetector.kt` | **重写**。替换欧氏距离聚类为新管线：`TextLine → BubbleMerger.merge → TextRegionSplitter.split → BubbleRegion → ReadingOrderSorter.sort`。保留无参 `detectBubbles()` 兼容方法 | ✅ 通过 | `BubbleRegion` 数据类不变，下游兼容 |
| `manga/VerticalTextRenderer.kt` | `calculateFitFontSize` 从线性递减（每次 -1f）改为二分查找（精度 0.5f），性能从 O(n) 降到 O(log n) | ✅ 通过 | — |
| `manga/OverlayRenderer.kt` | 新增 `calculateExpandedRect()`：根据翻译后文字长度动态扩展绘制区域（竖排扩展宽度，横排扩展高度），替代原来的固定区域 | ✅ 通过 | 扩展后的 Rect 未做边界裁剪（可能超出屏幕） |
| `manga/MangaFloatingService.kt` | `processMangaScreenshot` 第 780 行：`detectBubbles(textBlocks)` → `detectBubbles(textBlocks, config)` | ✅ 通过 | 仅 1 行改动 |

---

## 进度摘要

### (1) 已完成的工作

- **方向检测**：从 boundingBox 宽高比推断文字方向（高 > 宽 → 竖排，宽 > 高 → 横排），用户配置优先
- **多条件合并**：替代原来的 80px 固定阈值欧氏距离聚类，使用 5 个条件（距离、字体大小比、方向、对齐、宽高比）判断是否合并
- **MST 分割**：对合并后过大的区域，用最小生成树 + 距离标准差自动拆分
- **阅读排序**：竖排日漫从右到左排序，横排从左到右排序
- **渲染优化**：字体自适应改为二分查找；绘制区域根据翻译文字长度动态扩展
- **全量编译验证**：`assembleDebug` 通过，无新增错误或警告

### (2) 已知问题

| 严重度 | 问题 | 说明 |
|--------|------|------|
| 低 | `BubbleMerger.canMerge` 的 `config` 参数未使用 | 已用 `@Suppress` 消除警告，后续可用于基于用户配置的合并策略微调 |
| 低 | `OverlayRenderer` 扩展后的 Rect 未做屏幕边界裁剪 | 翻译文字远长于原文时，绘制区域可能超出屏幕可视范围 |
| 低 | `TextRegionSplitter` 角度差判断简化 | 原版用 `angle < 0.2π`（约 36°），轴对齐矩形无角度信息，改用方向一致性替代 |
| 信息 | 原 `BubbleDetector` 的 `BUBBLE_EXPAND_PX = 20` 保留 | 与 `OverlayRenderer` 的动态扩展叠加，可能导致区域偏大 |

### (3) 建议的下一步

1. **实机测试**：用竖排日文漫画和横排中文漫画分别测试，验证合并/分割/排序效果
2. **调参**：根据测试结果调整合并阈值（`DISCARD_CONNECTION_GAP`、`CHAR_GAP_TOLERANCE` 等）
3. **边界裁剪**：`calculateExpandedRect` 添加屏幕边界限制
4. **去重扩展**：`BUBBLE_EXPAND_PX` 固定扩展和动态扩展可能冲突，考虑统一逻辑
5. **后续可选**：集成 manga-ocr 模型替换 ML Kit（需额外 ~200MB 模型文件）
