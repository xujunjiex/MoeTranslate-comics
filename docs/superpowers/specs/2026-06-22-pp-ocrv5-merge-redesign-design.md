# PP-OCRv5 合并逻辑重构设计

> 日期：2026-06-22
> 状态：设计中（待用户批准）
> 作者：brainstorming 流程输出

## 背景与动机

当前漫画翻译引擎有两份独立实现的文字行/区域合并逻辑：

| 文件 | 调用时机 | 输入 | 输出 | 用途 |
|------|---------|------|------|------|
| `app/src/main/java/com/moe/moetranslator/manga/BoxMerger.kt`（288 行） | OCR 前（det 之后） | `List<QuadBox>` | `List<List<QuadBox>>` | CTD + PPOcrV5 前合并 |
| `app/src/main/java/com/moe/moetranslator/manga/TextLineMerger.kt`（520 行） | OCR 后（rec 之后） | `List<TextLine>` | `List<MergedRegion>` | PP-OCRv5 独立模式后合并 |

两份代码均移植自 `manga-image-translator/manga_translator/textline_merge/__init__.py`，重复实现：

- 同一套 `UnionFind`（两份独立实现，命名/接口不同）
- 同一套 `MSTEdge` + Kruskal MST + 拆分逻辑
- 同一套 `canMergeRegion`（AA 分支 + Tilted 分支）
- 同一套 `mean/std` 工具函数

差异主要在：

- `BoxMerger` 用 `polyDistance`（凸四边形 SAT），`TextLineMerger` 用 `quadCenterDistance`（中心点距离）
- `BoxMerger` 参数对齐 `generic.py` 默认值（`RATIO=1.9`、`ASPECT_RATIO_TOL=2.0`、`CHAR_GAP=0.6` 等）
- `TextLineMerger` 参数对齐 manga `merge_bboxes_text_region` 调用值（`ASPECT_RATIO_TOL=1.3`、`CHAR_GAP=1`、`CHAR_GAP2=3` 等）
- `TextLineMerger` 暴露 `discardConnectionGap` 为可调参数，`BoxMerger` 全部 hardcoded
- `TextLineMerger` 有大量 `LogCollector.d` 调试日志（运行时始终开启）

参考项目 `.reference/RapidOCR-main/` 经查证**没有独立的 box 合并模块**，每行 det box 单独识别输出。RapidOCR 提供的价值在于工程化的整体 OCR pipeline（det→cls→rec→CTC）和干净的接口风格——本次重构以 manga-image-translator 算法为来源、以 RapidOCR 的代码风格为参考。

## 目标

1. 消除 `BoxMerger` 与 `TextLineMerger` 之间的 80%+ 重复实现
2. 统一合并算法的入口与接口（OCR 前/后用同一管线）
3. 拆分调试日志为可独立关闭的开关，默认零开销
4. 暴露 1-2 个关键可调参数（默认隐藏），其余 hardcoded
5. 一次性替换：删除 `BoxMerger.kt` + `TextLineMerger.kt`，新建 `TextRegionMerger.kt`

## 非目标

- 不引入跨语言/Rust 实现
- 不修改算法本身（保留 manga-image-translator 的 AA + Tilted 双分支语义）
- 不添加"合并失败重试/降级"机制
- 不影响 MLKit、CTD、RT-DETR-V2 路径的现有行为（合并器对外表现为同一接口，但只迁移 PP-OCRv5 直接调用者）

## 设计

### 1. 数据结构

#### TextRegion — 统一输入

```kotlin
package com.moe.moetranslator.manga

import android.graphics.PointF
import android.graphics.Rect

/**
 * 合并器的统一输入。
 * text == null 表示 OCR 前的几何合并（CTD + PPOcrV5 路径）
 * text != null 表示 OCR 后的语义合并（PP-OCRv5 独立路径）
 *
 * 算法不区分两种 case——文字字段仅在最终拼接阶段使用。
 */
data class TextRegion(
    val quad: QuadBox,
    val text: String? = null,
    val score: Float = 1f,
    val recTimeMs: Long = 0
)
```

#### TextRegionGroup — 统一输出

```kotlin
data class TextRegionGroup(
    val rect: Rect,                          // AABB 合并框
    val quadPoints: Array<PointF>,           // 4 角点（中心加权）
    val texts: List<String>,                 // 拼接后的文字（OCR 后才有内容）
    val direction: TextDirection,            // HORIZONTAL / VERTICAL_RL
    val fontSize: Float,                     // min（取最小，避免换行加粗）
    val angle: Float,                        // 加权平均
    val score: Float,                        // 平均
    val center: PointF,
    val members: List<TextRegion>            // 原始成员（调试/可视化用）
)
```

#### MergeParams — 可调参数

```kotlin
data class MergeParams(
    val discardConnectionGap: Float = 1.5f,  // 主距离门控（共享于 AA + Tilted）
    val charGapTolerance2: Float = 3.0f      // AA 中心对齐容差 + Tilted dist 门控
)
// 其余 hardcoded：RATIO=1.9, ASPECT_RATIO_TOL=1.3, CHAR_GAP=1, TILTED_ANGLE_DIFF_MAX=15°,
//                  TILTED_FS_DIFF_MAX=0.25, FONT_SIZE_RATIO_AA=2.0
```

### 2. 主入口

```kotlin
object TextRegionMerger {
    /**
     * 合并 text regions 为文本组。
     * @param regions 待合并的 text region 列表（det-only 或 rec 后均可）
     * @param params 可调参数（默认从 SharedPreferences 读取）
     * @return 合并后的 text region groups（按阅读顺序：横排 top→bottom，竖排 right→left）
     */
    fun merge(regions: List<TextRegion>, params: MergeParams = defaultParams()): List<TextRegionGroup>

    /**
     * 调试日志开关（默认从 PPOcrV5Engine.isDebugEnabled 读取）。
     * 开启时打印 canMergeRegion / splitTextRegion 详情；
     * 关闭时所有 LogCollector.d 短路返回，零开销。
     */
    fun enableDebugLogging(enabled: Boolean)

    fun refreshParams(context: Context)
    fun resetParams(context: Context)
}
```

### 3. 内部算法

完全移植 manga-image-translator 的 `textline_merge/__init__.py`，三个步骤：

#### Step 1: canMergeRegion 建图

```
对每对 (i, j) i < j：
  charSize = min(quad[i].fontSize, quad[j].fontSize)
  if charSize <= 0: skip
  if dist > discardConnectionGap * charSize: skip
  if fontSizeRatio > FONT_SIZE_RATIO_AA: skip
  if 纵横比交叉 fail: skip
  if 方向不一致 (isVertical): skip

  AA 分支 (i.isApproxAxisAligned && j.isApproxAxisAligned):
    if dist < charSize * CHAR_GAP (1.0):
      centerXDiff = |cx_i - cx_j|
      if centerXDiff < charGapTolerance2: union
      if 横排: |left_i - left_j| < charGapTolerance2 || |right_i - right_j| < charGapTolerance2: union
      if 竖排: |top_i - top_j| < charGapTolerance2 || |bottom_i - bottom_j| < charGapTolerance2: union
    else: skip

  Tilted 分支:
    if angleDiff > 15°: skip
    if |fs_i - fs_j| / min(fs) > 0.25: skip
    if dist > min(fs) * charGapTolerance2: skip
    union
```

`isApproximateAxisAligned` 沿用 manga 实现：检测 quad 两条主结构线方向向量与水平/垂直轴的 dot product < 0.05。

`dist` 统一为 `polyDistance`（凸四边形 SAT，来自 `QuadBox.polyDistance()`）。

#### Step 2: splitTextRegion MST 拆分

对每个连通分量：

```
case 1 (单 box): 直接返回单元素
case 2 (两 box):
  if dist < (1 + gamma) * max(fs) && angleDiff < 0.2π: 保留合并
  else: 拆为两个单元素
case 3+ (MST):
  建全连接图，Kruskal 求 MST
  按权重降序：
    if maxEdge ≤ mean + 2*std OR maxEdge ≤ avgFontSize * 1.5
       AND std < max(0.3*avgFontSize + 5, 5): 保留
    else: 移除最大边，递归处理两个子图
```

#### Step 3: 方向投票 + 排序 + 合并

```
对每个最终 group：
  majorityDir = majority_vote(members.isVertical)  // h/v
  按方向排序：横排 centroidY ↑；竖排 centroidX ↓
  unionRect = min/max of all member.aabb
  combinedTexts = sorted.map { it.text ?: "" }
  minFontSize = members.map { it.fontSize }.min()
  avgScore = members.map { it.score }.average()
  weightedAngle = sum(angle * fontSize) / sum(fontSize)
  weightedQuadPoints = 中心加权（sum(quad.center * fontSize) / sum(fontSize)）
```

### 4. 数据流与调用方迁移

#### 调用点清单

| 原文件 | 原调用 | 新调用 |
|--------|--------|--------|
| `DetectionBridge.kt`（CTD + PPOcrV5 路径） | `BoxMerger.merge(quads)` | `TextRegionMerger.merge(quads.map { TextRegion(it) })` |
| `PPOcrV5Engine.kt::ocrResultToTextLines` 调用方 | `TextLineMerger.merge(textLines)` | `TextRegionMerger.merge(textLines.map { it.toTextRegion() })` |
| `PPOcrV5Engine.kt::recResultsToTextLines` 调用方（增量渲染） | `TextLineMerger.merge(textLines)` | `TextRegionMerger.merge(...)` |
| `OverlayRenderer.kt` | 接收 `MergedRegion` | 接收 `TextRegionGroup`（独立重构渲染输入，不再提供 `MergedRegion` 兼容方法） |
| `TextRegionSplitter.kt`（如有调用） | `TextLineMerger.merge` | `TextRegionMerger.merge` |

#### 三条主路径

**CTD + PPOcrV5（前合并）**：
```
CTDDetector.detect() → List<QuadBox>
  → toRegions() → TextRegionMerger.merge() → TextRegionGroup
  → DetectionBridge.processGroups() → 逐组裁剪+OCR
  → 翻译 → OverlayRenderer
```

**PP-OCRv5 独立（后合并）**：
```
PPOcrV5Engine.runOCR() → OcrResult
  → ocrResultToTextLines() → TextLine → toTextRegion()
  → TextRegionMerger.merge() → TextRegionGroup
  → 翻译 → OverlayRenderer
```

**增量渲染（PP-OCRv5 + 6+ 气泡）**：
```
runDetForBoxes() → List<FloatArray>
  → 裁剪 → recResultsToTextLines() → TextLine → toTextRegion()
  → TextRegionMerger.merge() → TextRegionGroup
  → 翻译第一批 / 同时 OCR 第二批
  → 渲染
```

### 5. 边界与错误处理

- **空输入**：`merge(emptyList())` → `emptyList()`
- **单输入**：单元素 list，保留 fontSize/score，不做投票
- **混合 AA + Tilted**：根据每对的 `isApproximateAxisAligned` 分别走 AA 或 Tilted 分支
- **文字为空**：OCR 前路径 text=null 时只检查几何；OCR 后路径若某行 `text.isBlank()` 仍参与几何合并（与 manga 一致，不剔除）
- **score 过滤**：合并前调用方负责过滤；TextRegionMerger 不重新过滤
- **大输入性能护栏**：当 `regions.size > 200` 时切换空间索引（网格 hash，按 quad 中心点桶分类），避免 N² 配对
- **超大输入警告**：`regions.size > 500` 时打印 `LogCollector.w` 警告

### 6. 调试日志

- 所有 `LogCollector.d` 调用包裹在 `if (debugEnabled)` 中，默认 false
- 开关委托给 `PPOcrV5Engine.isDebugEnabled`（与现有 5 个 PP 调试参数同源）
- 当 PP debug overlay 开启时，合并详情（每个 canMergeRegion / splitTextRegion 决策）会输出
- 关闭时短路返回，零开销（无字符串拼接、无函数调用）

### 7. 可调参数

通过 `TextRegionMerger.refreshParams(context)` 从 SharedPreferences 读取：

| Key | 默认值 | 范围 | 含义 |
|-----|--------|------|------|
| `merge_discard_gap` | 1.5 | 1.0-3.0 | 主距离门控（影响合并的最关键参数） |
| `merge_char_gap2` | 3.0 | 1.0-5.0 | AA 中心对齐 / Tilted 距离门控 |

UI 入口：复用现有 `MergeParamsPreferenceFragment`（如有），或在 PP debug overlay 增加两个 seek bar。**默认折叠**，仅 debug overlay 开启时显示。

`resetParams()` 提供一键恢复默认。

### 8. 文件变更

| 文件 | 操作 | 说明 |
|------|------|------|
| `manga/TextRegionMerger.kt` | **新建** | 主入口（含内部 UnionFind、MSTEdge、canMergeRegion、splitTextRegion） |
| `manga/TextRegion.kt` | **新建** | 输入数据类 |
| `manga/TextRegionGroup.kt` | **新建** | 输出数据类 |
| `manga/MergeParams.kt` | **新建** | 可调参数数据类 |
| `manga/BoxMerger.kt` | **删除** | 288 行 |
| `manga/TextLineMerger.kt` | **删除** | 520 行 |
| `manga/DetectionBridge.kt` | **修改** | 调用点迁移到 TextRegionMerger |
| `manga/PPOcrV5Engine.kt` | **修改** | ocrResultToTextLines / recResultsToTextLines 调用点迁移；移除 TextLine 相关内部数据结构（保留 TextLine 用于中间转换） |
| `manga/OverlayRenderer.kt` | **修改** | 接收 TextRegionGroup，独立重构渲染输入（不再提供 MergedRegion 兼容方法） |
| `manga/MangaFloatingService.kt` | **修改** | 调试/增量路径调用点迁移 |
| `manga/QuadBox.kt` | **保持** | 不变 |

预期净变化：**删除 ~800 行 + 新增 ~350 行 = 净减少 ~450 行**。

### 9. 测试

#### 单元测试（`app/src/test/`）

- `TextRegionMergerTest.kt`
  - 单 box → 单 group
  - 两 box AA 横排合并 / 不合并
  - 三 box 同行合并
  - 多 box 不同行不合并
  - 倾斜 box（angle=10°）合并
  - MST 拆分：3+ box 含 1 离群点
  - OCR 前 vs OCR 后路径同结果（text 字段不参与几何判断）
  - 参数变更：`discardConnectionGap` 1.5 → 3.0，合并数应该增加
  - 大输入性能：500 box 不崩溃

#### 手动测试（ADB）

- 跑同一组漫画截图（3-5 张），对比前后合并区域数（允许 ±5% 浮动）
- 跑同一组游戏/截屏，对比识别结果文本无变化
- 跑倾斜文字场景（英文/日文漫画），合并不丢失行
- 跑增量渲染（>6 气泡），翻译结果一致

### 10. 实施顺序（参考 writing-plans 输出）

1. **Phase 1**：新建 `TextRegion` / `TextRegionGroup` / `MergeParams` 数据类（无算法）
2. **Phase 2**：新建 `TextRegionMerger`，含内部算法（独立、可单元测试）
3. **Phase 3**：写单元测试覆盖 8+ 用例
4. **Phase 4**：迁移 `DetectionBridge` 调用点（CTD + PPOcrV5 前合并）
5. **Phase 5**：迁移 `PPOcrV5Engine` 调用点（独立模式 + 增量渲染）
6. **Phase 6**：迁移 `OverlayRenderer` 输出接收
7. **Phase 7**：编译 + 手动测试 → 验证与 baseline 一致
8. **Phase 8**：删除 `BoxMerger.kt` + `TextLineMerger.kt`
9. **Phase 9**：UI 添加可调参数入口（PP debug overlay）

## 风险与缓解

| 风险 | 缓解 |
|------|------|
| 合并区域数变化导致翻译气泡数变化 | 重构前后跑同一组漫画 diff，允许 ±5% 浮动 |
| `polyDistance` 替换 `quadCenterDistance` 引入精度差异 | 在 BoxMerger 场景原本就用 polyDistance；TextLineMerger 场景用 polyDistance 会让"距离门控"更严，可能减少合并 |
| 调试日志默认关闭导致回归测试不便 | 提供 `enableDebugLogging(true)` 显式开启接口 |
| 可调参数默认值不当 | 与 manga-image-translator 调用值对齐（`discardConnectionGap=1.5`、`charGapTolerance2=3.0`） |
| 大输入 N² 配对性能差 | 200+ 切换空间索引，500+ 警告 |
| OverlayRenderer 接收 TextRegionGroup 改造量大 | 提供 `toMergedRegion()` 兼容方法，分阶段重构 |

## 兼容性

- **PP debug overlay**：保留 5 个原有参数；新增 2 个合并参数，默认折叠
- **翻译 API**：不修改（合并发生在翻译前）
- **历史记录**：不修改（合并结果只是中间数据，不写入缓存）
- **CTD + PPOcrV5 路径**：行为一致（同样的算法，只是入口换了）
- **MLKit + 其他检测器**：不直接调用 TextRegionMerger，不受影响

## 验收标准

- [ ] 单元测试 100% 覆盖关键用例（8+ 个）
- [ ] `BoxMerger.kt` 与 `TextLineMerger.kt` 已删除
- [ ] `TextRegionMerger.kt` 通过所有测试
- [ ] 5 张测试漫画合并区域数变化在 ±5% 以内
- [ ] 编译通过，`./gradlew assembleDebug` 无错误
- [ ] ADB 手动测试：3 种语言（日/英/中）× 3 种场景（直立/倾斜/混合）= 9 个用例无崩溃
- [ ] PP debug overlay 可见合并调试日志（开启时）/ 不可见（关闭时）
- [ ] 可调参数生效：`discardConnectionGap` 调整后合并数变化符合预期

## 后续（非本次重构）

- 如果未来需要支持"合并失败降级"，可以在 TextRegionMerger 之上包一层 retry decorator
- 如果需要 Rust 实现获得性能，可在 TextRegionMerger 内替换 `merge()` 实现，对外接口不变