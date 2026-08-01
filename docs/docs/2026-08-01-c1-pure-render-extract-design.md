# C1 修正版：MangaFloatingService 纯调试代码提取设计

> 2026-08-01。本设计基于还原后的 `91d8c03`（C1 会话之前的状态）制定。
> 前置背景：原 C1（debug cluster 拆分）因把 `show*ResultOverlay` 等**业务编排函数**误当调试代码硬拆而失败，本次只提取**严格无状态的纯函数**。

## 1. 目标与红线

**目标：** 把 `MangaFloatingService.kt`（6340 行）中约 1460 行纯调试代码搬到独立文件，使其缩小到约 4880 行，结构更清晰、调用逻辑更合理。

**红线（上次失败的教训固化）：**

1. 只搬本设计列出的 **9 个函数 + 1 个内部类**，一个业务函数都不碰
2. `show*ResultOverlay`（4 个）、`show*DebugView`（4 个编排函数）、复制模式、缓存 overlay、自动翻译状态机、`DetectState`、`showDebugInfoPanel` 及其收展按钮全部**留在服务里**
3. 被搬函数内部逻辑一字不改，只把对服务状态的访问改成参数传入
4. 前端行为完全不变（调试图、滑块、框选遮罩与搬前逐像素一致）
5. 每个 commit 独立可编译

## 2. 现状分析（已核实）

### 2.1 候选函数清单（全部经代码核实）

| # | 函数 | 行号区间 | 行数 | 对服务的依赖 | 归类 |
|---|------|---------|------|------------|------|
| 1 | `renderRTDetrV2DebugOverlay` | 3945-4011 | 67 | **无** | A 渲染 |
| 2 | `renderMLKitDebugOverlay` | 5392-5473 | 82 | **无** | A 渲染 |
| 3 | `renderPPOcrV5DebugWithMerge` | 5628-5768 | 141 | `prefs.getFloat("ppocr_text_score_thresh", 0.5f)`（1 处，line 5759） | A 渲染 |
| 4 | `renderPPOcrV6DebugWithMerge` | 5771-5911 | 141 | `prefs.getFloat("ppocrv6_text_score", 0.5f)`（1 处，line 5902） | A 渲染 |
| 5 | `applyCropDimmingIfNeeded` | 4253-4287 | 35 | 字段 `cropRect` + 方法 `getScreenSize()` | B 辅助 |
| 6 | `createInfoPanelView` | 5237-5255 | 19 | 方法 `getScreenSize()`（`scrollable` 且未传 `maxHeight` 时） | B 辅助 |
| 7 | `createToggleButton` | 5261-5278 | 18 | 默认分支读字段 `debugInfoPanelCollapsed` + 调 `expand/collapseDebugInfoPanel` | B 辅助 |
| 8 | `MaxHeightScrollView`（内部类） | 6310-6316 | 7 | **无**（7 行 ScrollView 子类） | B 辅助 |
| 9 | `createPPOcrParamSlidersView` | 4288-4664 | 376 | `prefs`（26 处读写）+ `resources`（1 处）+ `getString`（1 处） | C 滑块 |
| 10 | `createPPOcrV6ParamSlidersView` | 4665-5236 | 572 | `prefs`（52 处读写）+ `resources`（2 处）+ `getString`（1 处） | C 滑块 |

合计约 **1458 行**。6340 → 约 4880 行。

**关键核实结论：** 10 项**全部不引用** `windowManager` / `floatingBall` / `config` / `showResultOverlay` / `dismissResultOverlay` / 自动翻译状态机 / `DetectState` / `showToast` —— 这是与失败的原 3c 的本质区别。

### 2.2 类型可见性（已核实，全部可跨子包访问）

| 类型 | 定义位置 | 可见性 |
|------|---------|--------|
| `RTDetrV2DebugResult` | `DetectionBridge.kt` 顶层 data class | public |
| `MLKitDebugResult` / `MLKitDebugBlock` / `MLKitDebugLine` / `MLKitDebugElement` | `DetectionBridge.kt` 顶层 | public |
| `OcrResult` / `DebugRecResult` | `PPOcrV5Engine.kt` 顶层 | public |
| `TextRegionGroup` | `TextRegionGroup.kt` 顶层 | public |
| `TextDirection` | `MangaModeConfig.kt` 顶层 enum | public |
| `DebugDetResult` | `PPOcrV5Engine` / `PPOcrV6Engine`（object）内 public 嵌套 data class | public |

## 3. 目标结构

新建 `manga/debug/` 子包（与 `me/` 子包模式一致），2 个文件：

```
app/src/main/java/com/moe/starflow/manga/debug/
├── MangaDebugOverlays.kt   — object MangaDebugOverlays（渲染 + 辅助，约 540 行）
└── MangaDebugSliders.kt    — object MangaDebugSliders（滑块面板，约 950 行）
```

### 3.1 `MangaDebugOverlays` 函数签名（提取后）

```kotlin
object MangaDebugOverlays {
    // A 组：纯渲染（输入 bitmap + 结果数据，输出调试图）
    fun renderRTDetrV2DebugOverlay(bitmap: Bitmap, debugResult: RTDetrV2DebugResult): Bitmap
    fun renderMLKitDebugOverlay(original: Bitmap, result: MLKitDebugResult): Bitmap
    fun renderPPOcrV5DebugWithMerge(
        bitmap: Bitmap, ocrResult: OcrResult,
        mergedRegions: List<TextRegionGroup>,
        debugDet: PPOcrV5Engine.DebugDetResult? = null,
        textScoreThresh: Float                        // ← 新增参数，替换 line 5759 prefs 读取
    ): Bitmap
    fun renderPPOcrV6DebugWithMerge(
        bitmap: Bitmap, ocrResult: OcrResult,
        mergedRegions: List<TextRegionGroup>,
        debugDet: PPOcrV6Engine.DebugDetResult? = null,
        textScoreThresh: Float                        // ← 新增参数，替换 line 5902 prefs 读取
    ): Bitmap

    // B 组：辅助（依赖全部参数化）
    fun applyCropDimming(debugBitmap: Bitmap, cropRect: RectF?, screenSize: Size): Bitmap
    fun createInfoPanelView(lines: List<String>, scrollable: Boolean = false, maxHeight: Int = 0): View
    fun createToggleButton(onToggle: () -> Unit): TextView   // ← onToggle 必传
    class MaxHeightScrollView(context: Context, maxHeightPx: Int) : ScrollView  // 随迁
}
```

### 3.2 `MangaDebugSliders` 函数签名（提取后）

```kotlin
object MangaDebugSliders {
    fun createPPOcrParamSlidersView(prefs: SharedPreferences, context: Context): View
    fun createPPOcrV6ParamSlidersView(prefs: SharedPreferences, context: Context): View
}
```

内部 78 处 prefs 读写、seekbar 换算逻辑、`resources`/`getString` 用法一字不改，仅把访问路径从服务字段改为传入参数（`prefs` / `context.resources`）。

## 4. 调用方改动（MangaFloatingService 内，共 8 处）

| 调用方 | 行号 | 改动 |
|--------|------|------|
| `showRTDetrV2DebugView` | 3941 | → `MangaDebugOverlays.renderRTDetrV2DebugOverlay(bitmap, debugResult)` |
| `showMLKitDebugView` | 5388 | → `MangaDebugOverlays.renderMLKitDebugOverlay(bitmap, result)` |
| `showPPOcrV5DebugView` | 5615 | → `MangaDebugOverlays.renderPPOcrV5DebugWithMerge(bitmap, ocrResult, mergedRegions, debugDet, prefs.getFloat("ppocr_text_score_thresh", 0.5f))` |
| `showPPOcrV6DebugView` | 5621 | → `MangaDebugOverlays.renderPPOcrV6DebugWithMerge(bitmap, ocrResult, mergedRegions, debugDet, prefs.getFloat("ppocrv6_text_score", 0.5f))` |
| `showRTDetrV2DebugResultOverlay` | 4018/4038/4049 | `applyCropDimming(debugBitmap, cropRect, getScreenSize())`；`createInfoPanelView(infoLines)`；`createToggleButton(onToggle = { if (debugInfoPanelCollapsed) expandDebugInfoPanel() else collapseDebugInfoPanel() })` |
| `showMLKitDebugResultOverlay` | 5480/5504/5515 | `applyCropDimming(debugBitmap, cropRect, getScreenSize())`；`createInfoPanelView(infoLines, scrollable = true, maxHeight = getScreenSize().height / 2)`；`createToggleButton(onToggle = { ... })` |
| `showPPOcrV5DebugResultOverlay` | 5919/6010/6017/6038 | `applyCropDimming(debugBitmap, cropRect, getScreenSize())`；`createInfoPanelView(infoLines, scrollable = true, maxHeight = getScreenSize().height / 2)`；`createToggleButton(onToggle = { ... })`；`MangaDebugSliders.createPPOcrParamSlidersView(prefs, this)` |
| `showPPOcrV6DebugResultOverlay` | 6097/6189/6194 | `applyCropDimming(debugBitmap, cropRect, getScreenSize())`；`createInfoPanelView(infoLines, scrollable = true, maxHeight = getScreenSize().height / 2)`；`MangaDebugSliders.createPPOcrV6ParamSlidersView(prefs, this)`。**注意：V6 不调 `createToggleButton`，其内联 📊/⚙ 按钮（6225-6268）原样保留、不动** |

**必须保留的现状不对称（提取不修复）：**
- `createToggleButton` 只有 3 个调用方（RTDetr / MLKit / V5），**V6 没有**
- `showDebugInfoPanel`（4108-4154）内联构建自己的 info panel 和 toggle 按钮，**不调用任何被提取函数**，原样保留

## 5. 测试策略

### 5.1 冒烟单测（新增，Robolectric）

`app/src/test/java/com/moe/starflow/manga/debug/MangaDebugOverlaysTest.kt`

4 个渲染函数输入均可空列表构造，测试可行性已核实：

```kotlin
@RunWith(RobolectricTestRunner::class)
class MangaDebugOverlaysTest {
    @Test fun renderFunctionsProduceValidBitmap() {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val out1 = MangaDebugOverlays.renderRTDetrV2DebugOverlay(bitmap,
            RTDetrV2DebugResult(emptyList(), emptyList(), emptyList(), emptyList(), emptyList()))
        val out2 = MangaDebugOverlays.renderMLKitDebugOverlay(bitmap,
            MLKitDebugResult(emptyList(), 0, 0, null))
        val ocr = OcrResult(emptyList(), emptyList(), emptyList(), emptyList())
        val out3 = MangaDebugOverlays.renderPPOcrV5DebugWithMerge(bitmap, ocr, emptyList(), null, 0.5f)
        val out4 = MangaDebugOverlays.renderPPOcrV6DebugWithMerge(bitmap, ocr, emptyList(), null, 0.5f)
        for (out in listOf(out1, out2, out3, out4)) {
            assertTrue(out.width == 100 && out.height == 100)
            assertFalse(out.isRecycled)
        }
        bitmap.recycle()
    }
}
```

> 用途：证明提取后渲染函数仍能产出有效 bitmap（非空、尺寸一致、未回收），防"搬坏渲染"。

### 5.2 验证步骤

| 层 | 方式 | 预期 |
|----|------|------|
| 编译 | `./gradlew assembleDebug` | BUILD SUCCESSFUL |
| 单测 | PowerShell + 干净 PATH + `:app:testDebugUnitTest` | 现有 65 个 + 新增 1 个全过 |
| 设备（用户做） | 开 PP-OCRv5 / PP-OCRv6 / RT-DETR-V2 / MLKit 4 种调试，漫画模式翻译一页 | 调试图框、滑块调参、框选外遮罩、📊/⚙ 按钮行为与提取前一致 |

## 6. 风险与缓解

| 风险 | 缓解 |
|------|------|
| 滑块 78 处 prefs 读写搬迁出错 | 纯机械替换：键名/默认值/换算逻辑一字不改，只改 `prefs` 引用路径为参数；编译 + 单测 + 设备验证三层把关 |
| V6 overlay 的特殊结构（内联按钮）被误动 | 红线 3 明确"V6 内联按钮原样保留"；调用方改动表逐行列出，V6 只改 3 处 |
| `onToggle` 语义变化 | 3 个调用方统一传 `{ if (debugInfoPanelCollapsed) expandDebugInfoPanel() else collapseDebugInfoPanel() }`，与原默认分支逻辑一致 |
| 类型可见性 | 2.2 已核实全部 public，跨子包无需改可见性 |
