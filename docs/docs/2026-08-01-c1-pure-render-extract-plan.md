# C1 修正版：MangaFloatingService 纯调试代码提取实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `MangaFloatingService.kt`（6340 行）中的 10 项纯调试代码（4 渲染 + 4 辅助 + 2 滑块，约 1460 行）搬到新包 `manga/debug/`，服务缩至约 4880 行，行为零变化。

**Architecture:** 新建 `manga/debug/` 子包，2 个 object：`MangaDebugOverlays`（4 渲染 + 4 辅助）与 `MangaDebugSliders`（2 滑块）。被搬函数内部逻辑一字不改，仅把对服务状态/上下文的访问（`prefs`、`cropRect`、`getScreenSize()`、`this`）改为参数传入。`show*ResultOverlay`、`show*DebugView`、`showDebugInfoPanel`、复制模式、缓存、自动翻译状态机、`DetectState` 全部留在服务里。

**Tech Stack:** Kotlin、Android Service、Robolectric（冒烟测试）、Gradle（assembleDebug / testDebugUnitTest）。

**规格：** `docs/docs/2026-08-01-c1-pure-render-extract-design.md`

## Global Constraints

- **只搬设计文档 §2.1 列出的 10 项**，一个业务函数都不碰（红线）
- 被搬函数内部逻辑一字不改，只改设计文档明确的依赖访问路径
- **V6 overlay 不调 `createToggleButton`**，其内联 📊/⚙ 按钮（`showPPOcrV6DebugResultOverlay` 内 6225-6268 行）原样保留、不动
- `showDebugInfoPanel`（4108-4154 行）不调用任何被提取函数，原样保留
- 所有日志用 `LogCollector`，不用 `Log.d/i/e`（本任务无新增日志）
- prefs 键名、默认值、seekbar 换算逻辑**一字不改**
- 每个 Task 结束必须编译通过（`./gradlew assembleDebug`）
- 单测必须用 PowerShell + 干净 PATH + `--no-daemon` 运行（Git Bash 会弄乱 PATH 使 test worker 崩溃）
- **⚠️ 行号漂移：** 计划中的行号均为**原始文件（91d8c03）行号**。Task 1 删除 4 个渲染函数后，后续所有行号会整体上移。**编辑时一律以「原代码」列的字符串为锚点定位**（这些字符串唯一），禁止用行号硬找/硬删。每个函数删除用签名锚点 + 匹配闭合 `}` 完成。

---

### Task 1: MangaDebugOverlays — 4 个渲染函数 + 冒烟测试

**Files:**
- Create: `app/src/main/java/com/moe/starflow/manga/debug/MangaDebugOverlays.kt`（只含 4 个 render 函数）
- Create: `app/src/test/java/com/moe/starflow/manga/debug/MangaDebugOverlaysTest.kt`
- Modify: `app/src/main/java/com/moe/starflow/manga/MangaFloatingService.kt`（4 处调用点改前缀 + 删除 4 个原函数）

**Interfaces:**
- Produces: `MangaDebugOverlays.renderRTDetrV2DebugOverlay(bitmap: Bitmap, debugResult: RTDetrV2DebugResult): Bitmap`、`renderMLKitDebugOverlay(original: Bitmap, result: MLKitDebugResult): Bitmap`、`renderPPOcrV5DebugWithMerge(bitmap: Bitmap, ocrResult: OcrResult, mergedRegions: List<TextRegionGroup>, debugDet: PPOcrV5Engine.DebugDetResult? = null, textScoreThresh: Float): Bitmap`、`renderPPOcrV6DebugWithMerge(bitmap: Bitmap, ocrResult: OcrResult, mergedRegions: List<TextRegionGroup>, debugDet: PPOcrV6Engine.DebugDetResult? = null, textScoreThresh: Float): Bitmap`
- Consumes: `RTDetrV2DebugResult`/`MLKitDebugResult`（DetectionBridge.kt 顶层）、`OcrResult`（PPOcrV5Engine.kt 顶层）、`TextRegionGroup`、`PPOcrV5Engine.DebugDetResult`/`PPOcrV6Engine.DebugDetResult`（object 内 public 嵌套）、`TextDirection`（MangaModeConfig.kt 顶层）——全部 public，Task 2 依赖本文件继续追加

- [ ] **Step 1: 写失败冒烟测试**

创建 `app/src/test/java/com/moe/starflow/manga/debug/MangaDebugOverlaysTest.kt`：

```kotlin
package com.moe.starflow.manga.debug

import android.graphics.Bitmap
import com.moe.starflow.manga.MLKitDebugResult
import com.moe.starflow.manga.OcrResult
import com.moe.starflow.manga.RTDetrV2DebugResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MangaDebugOverlaysTest {

    @Test
    fun renderFunctionsProduceValidBitmap() {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val out1 = MangaDebugOverlays.renderRTDetrV2DebugOverlay(
            bitmap,
            RTDetrV2DebugResult(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        )
        val out2 = MangaDebugOverlays.renderMLKitDebugOverlay(
            bitmap,
            MLKitDebugResult(emptyList(), 0, 0, null)
        )
        val ocr = OcrResult(emptyList(), emptyList(), emptyList(), emptyList())
        val out3 = MangaDebugOverlays.renderPPOcrV5DebugWithMerge(bitmap, ocr, emptyList(), null, 0.5f)
        val out4 = MangaDebugOverlays.renderPPOcrV6DebugWithMerge(bitmap, ocr, emptyList(), null, 0.5f)
        for (out in listOf(out1, out2, out3, out4)) {
            assertTrue("尺寸必须与输入一致", out.width == 100 && out.height == 100)
            assertFalse("bitmap 不能已回收", out.isRecycled)
        }
        bitmap.recycle()
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

PowerShell + 干净 PATH：

```powershell
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot'
$env:PATH='C:\Windows\System32;C:\Windows;'+$env:JAVA_HOME+'\bin'
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests com.moe.starflow.manga.debug.MangaDebugOverlaysTest
```

Expected: 编译失败，`MangaDebugOverlays` 未定义（red）。

- [ ] **Step 3: 创建 MangaDebugOverlays.kt（4 个 render 函数）**

创建 `app/src/main/java/com/moe/starflow/manga/debug/MangaDebugOverlays.kt`：

```kotlin
package com.moe.starflow.manga.debug

import android.graphics.Bitmap
import com.moe.starflow.manga.MLKitDebugResult
import com.moe.starflow.manga.OcrResult
import com.moe.starflow.manga.PPOcrV5Engine
import com.moe.starflow.manga.PPOcrV6Engine
import com.moe.starflow.manga.RTDetrV2DebugResult
import com.moe.starflow.manga.TextDirection
import com.moe.starflow.manga.TextRegionGroup

/**
 * 调试渲染函数（纯函数）：输入截图 + 检测/识别结果，输出带调试框的 bitmap。
 * 无状态，不依赖任何服务字段；依赖全部通过参数传入。
 */
object MangaDebugOverlays {

    fun renderRTDetrV2DebugOverlay(bitmap: Bitmap, debugResult: RTDetrV2DebugResult): Bitmap {
        // 从 MangaFloatingService.kt 3945-4011 行原样复制函数体（去掉 private 修饰符）
    }

    fun renderMLKitDebugOverlay(original: Bitmap, result: MLKitDebugResult): Bitmap {
        // 从 MangaFloatingService.kt 5392-5473 行原样复制函数体（去掉 private 修饰符）
    }

    fun renderPPOcrV5DebugWithMerge(
        bitmap: Bitmap,
        ocrResult: OcrResult,
        mergedRegions: List<TextRegionGroup>,
        debugDet: PPOcrV5Engine.DebugDetResult? = null,
        textScoreThresh: Float
    ): Bitmap {
        // 从 MangaFloatingService.kt 5628-5768 行原样复制函数体，仅改 1 处：
        //   line 5759: "✗${String.format("%.2f", score)}<${String.format("%.2f", prefs.getFloat("ppocr_text_score_thresh", 0.5f))}"
        //   改为:      "✗${String.format("%.2f", score)}<${String.format("%.2f", textScoreThresh)}"
    }

    fun renderPPOcrV6DebugWithMerge(
        bitmap: Bitmap,
        ocrResult: OcrResult,
        mergedRegions: List<TextRegionGroup>,
        debugDet: PPOcrV6Engine.DebugDetResult? = null,
        textScoreThresh: Float
    ): Bitmap {
        // 从 MangaFloatingService.kt 5771-5911 行原样复制函数体，仅改 1 处：
        //   line 5902: "✗${String.format("%.2f", score)}<${String.format("%.2f", prefs.getFloat("ppocrv6_text_score", 0.5f))}"
        //   改为:      "✗${String.format("%.2f", score)}<${String.format("%.2f", textScoreThresh)}"
    }
}
```

> 复制时注意：函数体内的 `android.graphics.*`、`kotlin.math.*` 全部是 FQN，原样保留即可。每个函数体以 `return output` / `return mutableBitmap` / `return result` 结束。

- [ ] **Step 4: 改 4 处调用点 + 删除原函数**

在 `MangaFloatingService.kt` 中：

| 行 | 原代码 | 改为 |
|----|--------|------|
| 3941 | `val debugBitmap = renderRTDetrV2DebugOverlay(bitmap, debugResult)` | `val debugBitmap = MangaDebugOverlays.renderRTDetrV2DebugOverlay(bitmap, debugResult)` |
| 5388 | `val debugBitmap = renderMLKitDebugOverlay(bitmap, result)` | `val debugBitmap = MangaDebugOverlays.renderMLKitDebugOverlay(bitmap, result)` |
| 5615 | `val debugBitmap = renderPPOcrV5DebugWithMerge(bitmap, ocrResult, mergedRegions, debugDet)` | `val debugBitmap = MangaDebugOverlays.renderPPOcrV5DebugWithMerge(bitmap, ocrResult, mergedRegions, debugDet, prefs.getFloat("ppocr_text_score_thresh", 0.5f))` |
| 5621 | `val debugBitmap = renderPPOcrV6DebugWithMerge(bitmap, ocrResult, mergedRegions, debugDet)` | `val debugBitmap = MangaDebugOverlays.renderPPOcrV6DebugWithMerge(bitmap, ocrResult, mergedRegions, debugDet, prefs.getFloat("ppocrv6_text_score", 0.5f))` |

顶部加 import：`import com.moe.starflow.manga.debug.MangaDebugOverlays`

删除 4 个原函数（整段删除，含 `private fun` 声明行）：
- `renderRTDetrV2DebugOverlay`：3945-4011 行
- `renderMLKitDebugOverlay`：5392-5473 行
- `renderPPOcrV5DebugWithMerge`：5628-5768 行
- `renderPPOcrV6DebugWithMerge`：5771-5911 行

> 安全删除法：用函数签名作锚点定位起止，逐行删除到下一个 `private fun` / 空行之前，不要依赖行号硬删（删前几行后行号会漂移）。每个函数删除后立即目检左右邻函数是否完整。

- [ ] **Step 5: 运行冒烟测试 + 编译**

```powershell
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot'
$env:PATH='C:\Windows\System32;C:\Windows;'+$env:JAVA_HOME+'\bin'
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests com.moe.starflow.manga.debug.MangaDebugOverlaysTest
```

Expected: BUILD SUCCESSFUL，测试通过（green）。

再编译完整 APK：`./gradlew assembleDebug` → BUILD SUCCESSFUL。

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/moe/starflow/manga/debug/MangaDebugOverlays.kt app/src/test/java/com/moe/starflow/manga/debug/MangaDebugOverlaysTest.kt app/src/main/java/com/moe/starflow/manga/MangaFloatingService.kt
git commit -m "refactor: C1 - 提取 4 个调试渲染函数到 manga/debug/MangaDebugOverlays"
```

---

### Task 2: MangaDebugOverlays — 4 个辅助函数 + 4 个 overlay 调用点

**Files:**
- Modify: `app/src/main/java/com/moe/starflow/manga/debug/MangaDebugOverlays.kt`（追加 4 个辅助函数 + 新增 import）
- Modify: `app/src/main/java/com/moe/starflow/manga/MangaFloatingService.kt`（4 个 `show*ResultOverlay` 内共 11 处调用点 + 删除 4 个原函数）

**Interfaces:**
- Consumes: Task 1 的 `MangaDebugOverlays` object
- Produces: `MangaDebugOverlays.applyCropDimming(debugBitmap: Bitmap, cropRect: RectF?, screenSize: Size): Bitmap`、`createInfoPanelView(context: Context, lines: List<String>, scrollable: Boolean = false, maxHeight: Int = 0): View`、`createToggleButton(context: Context, onToggle: () -> Unit): TextView`、`class MaxHeightScrollView(context: Context, maxHeightPx: Int) : ScrollView`

- [ ] **Step 1: 向 MangaDebugOverlays.kt 追加 4 个辅助函数**

新增 import（加到 Task 1 的 import 块后面）：

```kotlin
import android.content.Context
import android.graphics.RectF
import android.util.Size
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
```

在 `object MangaDebugOverlays` 内、render 函数之后追加：

```kotlin
    /**
     * 为 debug 图片添加框选外区域遮罩。
     * cropRect 为 null（全屏模式）时直接返回原 bitmap。
     */
    fun applyCropDimming(debugBitmap: Bitmap, cropRect: RectF?, screenSize: Size): Bitmap {
        if (cropRect == null) return debugBitmap

        val screenW = screenSize.width
        val screenH = screenSize.height

        // 创建全屏 bitmap
        val fullBitmap = Bitmap.createBitmap(screenW, screenH, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(fullBitmap)

        // 绘制半透明黑色背景（全屏）
        val dimPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.argb(150, 0, 0, 0)
        }
        canvas.drawRect(0f, 0f, screenW.toFloat(), screenH.toFloat(), dimPaint)

        // 将 debug bitmap 绘制到框选区域
        val crop = cropRect!!
        val srcRect = android.graphics.Rect(0, 0, debugBitmap.width, debugBitmap.height)
        val dstRect = android.graphics.Rect(
            crop.left.toInt(),
            crop.top.toInt(),
            crop.right.toInt(),
            crop.bottom.toInt()
        )
        canvas.drawBitmap(debugBitmap, srcRect, dstRect, null)

        return fullBitmap
    }

    /** 创建调试信息面板 view */
    fun createInfoPanelView(context: Context, lines: List<String>, scrollable: Boolean = false, maxHeight: Int = 0): View {
        val tv = android.widget.TextView(context).apply {
            text = lines.joinToString("\n")
            setTextColor(android.graphics.Color.WHITE)
            textSize = if (scrollable) 11f else 13f
            setPadding(24, 16, 24, 16)
            setBackgroundColor(android.graphics.Color.argb(200, 0, 0, 0))
        }

        return if (scrollable) {
            // 调用方负责传 maxHeight；此兜底仅在误用（scrollable 但未传）时触发
            val limit = if (maxHeight > 0) maxHeight else (context.resources.displayMetrics.heightPixels / 2)
            MaxHeightScrollView(context, limit).apply {
                addView(tv)
                isVerticalScrollBarEnabled = true
            }
        } else {
            tv
        }
    }

    /** 创建展开/折叠按钮（onToggle 必传，行为由调用方决定） */
    @android.annotation.SuppressLint("SetTextI18n")
    fun createToggleButton(context: Context, onToggle: () -> Unit): android.widget.TextView {
        return android.widget.TextView(context).apply {
            text = "▼"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 18f
            gravity = android.view.Gravity.CENTER
            setBackgroundColor(android.graphics.Color.argb(180, 0, 0, 0))
            setOnClickListener {
                onToggle()
            }
        }
    }

    /** 限制最大高度的 ScrollView */
    class MaxHeightScrollView(context: Context, private val maxHeightPx: Int) : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val limitSpec = android.view.View.MeasureSpec.makeMeasureSpec(maxHeightPx, android.view.View.MeasureSpec.AT_MOST)
            super.onMeasure(widthMeasureSpec, limitSpec)
        }
    }
```

> 说明：原 `createInfoPanelView` 的兜底用 `getScreenSize().height / 2`（真实像素含系统栏），此处改为 `context.resources.displayMetrics.heightPixels / 2`。**实际不触发**——3 个 scrollable 调用点（MLKit/V5/V6）都会传 `maxHeight = getScreenSize().height / 2`，行为零变化。原 `createToggleButton` 的 `onToggle?.invoke() ?: run { 折叠逻辑 }` 分支删除，改为调用方必传 lambda。

- [ ] **Step 2: 改 4 个 overlay 的调用点**

在 `MangaFloatingService.kt` 中逐行替换（每个 overlay 内）：

**`showRTDetrV2DebugResultOverlay`：**

| 行 | 原代码 | 改为 |
|----|--------|------|
| 4018 | `val displayBitmap = applyCropDimmingIfNeeded(debugBitmap)` | `val displayBitmap = MangaDebugOverlays.applyCropDimming(debugBitmap, cropRect, getScreenSize())` |
| 4038 | `val infoPanel = createInfoPanelView(infoLines)` | `val infoPanel = MangaDebugOverlays.createInfoPanelView(this, infoLines)` |
| 4049 | `val toggleButton = createToggleButton()` | `val toggleButton = MangaDebugOverlays.createToggleButton(this, onToggle = { if (debugInfoPanelCollapsed) expandDebugInfoPanel() else collapseDebugInfoPanel() })` |

**`showMLKitDebugResultOverlay`：**

| 行 | 原代码 | 改为 |
|----|--------|------|
| 5480 | `val displayBitmap = applyCropDimmingIfNeeded(debugBitmap)` | `val displayBitmap = MangaDebugOverlays.applyCropDimming(debugBitmap, cropRect, getScreenSize())` |
| 5504 | `val infoPanel = createInfoPanelView(infoLines, scrollable = true)` | `val infoPanel = MangaDebugOverlays.createInfoPanelView(this, infoLines, scrollable = true, maxHeight = getScreenSize().height / 2)` |
| 5515 | `val toggleButton = createToggleButton()` | `val toggleButton = MangaDebugOverlays.createToggleButton(this, onToggle = { if (debugInfoPanelCollapsed) expandDebugInfoPanel() else collapseDebugInfoPanel() })` |

**`showPPOcrV5DebugResultOverlay`：**

| 行 | 原代码 | 改为 |
|----|--------|------|
| 5919 | `val displayBitmap = applyCropDimmingIfNeeded(debugBitmap)` | `val displayBitmap = MangaDebugOverlays.applyCropDimming(debugBitmap, cropRect, getScreenSize())` |
| 6010 | `val infoPanel = createInfoPanelView(infoLines, scrollable = true)` | `val infoPanel = MangaDebugOverlays.createInfoPanelView(this, infoLines, scrollable = true, maxHeight = getScreenSize().height / 2)` |
| 6038 | `val toggleButton = createToggleButton()` | `val toggleButton = MangaDebugOverlays.createToggleButton(this, onToggle = { if (debugInfoPanelCollapsed) expandDebugInfoPanel() else collapseDebugInfoPanel() })` |

**`showPPOcrV6DebugResultOverlay`（只改 2 处，不碰内联按钮）：**

| 行 | 原代码 | 改为 |
|----|--------|------|
| 6097 | `val displayBitmap = applyCropDimmingIfNeeded(debugBitmap)` | `val displayBitmap = MangaDebugOverlays.applyCropDimming(debugBitmap, cropRect, getScreenSize())` |
| 6189 | `val infoPanel = createInfoPanelView(infoLines, scrollable = true)` | `val infoPanel = MangaDebugOverlays.createInfoPanelView(this, infoLines, scrollable = true, maxHeight = getScreenSize().height / 2)` |

> ⚠️ **V6 的内联 📊/⚙ 按钮（6225-6268 行）和 `debugInfoPanelContentView = infoPanel` 等赋值一律不动。**

- [ ] **Step 3: 删除服务内 4 个原函数**

用函数签名锚点整段删除（含 `private fun` 声明行）：
- `applyCropDimmingIfNeeded`：4253-4287 行
- `createInfoPanelView`：5237-5255 行
- `createToggleButton`：5261-5278 行
- `MaxHeightScrollView`：6310-6316 行

> 同样用签名锚点定位，不用行号硬删。删除后目检左右邻函数完整。`showDebugInfoPanel` 及其 `addDebugToggleButton` 内联按钮**不在此列，保留**。

- [ ] **Step 4: 编译 + 冒烟测试 + 全量单测**

```bash
./gradlew assembleDebug
```
Expected: BUILD SUCCESSFUL。

```powershell
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot'
$env:PATH='C:\Windows\System32;C:\Windows;'+$env:JAVA_HOME+'\bin'
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests com.moe.starflow.manga.debug.MangaDebugOverlaysTest
```
Expected: BUILD SUCCESSFUL，测试通过。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/moe/starflow/manga/debug/MangaDebugOverlays.kt app/src/main/java/com/moe/starflow/manga/MangaFloatingService.kt
git commit -m "refactor: C1 - 提取 4 个调试辅助函数到 MangaDebugOverlays（applyCropDimming/infoPanel/toggle/scrollview）"
```

---

### Task 3: MangaDebugSliders — 2 个滑块构建器

**Files:**
- Create: `app/src/main/java/com/moe/starflow/manga/debug/MangaDebugSliders.kt`
- Modify: `app/src/main/java/com/moe/starflow/manga/MangaFloatingService.kt`（2 处调用点 + 删除 2 个原函数）

**Interfaces:**
- Consumes: `CustomPreference`（`com.moe.starflow.utils`）、`PPOcrV5Engine`/`PPOcrV6Engine`/`TextRegionMerger`/`PPOcrDefault`/`MergeParams`（全部 public）
- Produces: `MangaDebugSliders.createPPOcrParamSlidersView(prefs: CustomPreference, context: Context): View`、`MangaDebugSliders.createPPOcrV6ParamSlidersView(prefs: CustomPreference, context: Context): View`

> ⚠️ **设计补正（规格 §3.2 已更新）：** `prefs` 类型是 **`CustomPreference`**（服务字段类型，非 `SharedPreferences`）。滑块函数体有约 **90 处 `this`/`resources`** 引用需机械改写（不只是 5 处）——全部纯机械替换，逻辑零改动。

- [ ] **Step 1: 创建 MangaDebugSliders.kt**

创建 `app/src/main/java/com/moe/starflow/manga/debug/MangaDebugSliders.kt`：

```kotlin
package com.moe.starflow.manga.debug

import android.content.Context
import android.view.View
import com.moe.starflow.manga.MergeParams
import com.moe.starflow.manga.PPOcrDefault
import com.moe.starflow.manga.PPOcrV5Engine
import com.moe.starflow.manga.PPOcrV6Engine
import com.moe.starflow.manga.TextRegionMerger
import com.moe.starflow.utils.CustomPreference

/**
 * 调试参数滑块面板构建器：读取/写入 prefs 中的调试参数，构建可交互的滑块 UI。
 * 依赖 prefs + context 通过参数注入，不持有任何服务引用。
 */
object MangaDebugSliders {

    fun createPPOcrParamSlidersView(prefs: CustomPreference, context: Context): View {
        // 从 MangaFloatingService.kt 4288-4664 行复制函数体，应用 Step 2 的 3 条改写规则
    }

    fun createPPOcrV6ParamSlidersView(prefs: CustomPreference, context: Context): View {
        // 从 MangaFloatingService.kt 4665-5236 行复制函数体，应用 Step 2 的 3 条改写规则
    }
}
```

- [ ] **Step 2: 复制函数体并应用 3 条机械改写规则**

把原函数体（`fun createPPOcrParamSlidersView(): android.view.View { ... }` 的花括号内全部内容，含局部 `data class SliderRef`、局部 `val DEF_*`、局部 `addSection` 函数）原样粘进新函数体，然后**全局**执行 3 条替换（替换后函数体内不应再出现任何 `this@MangaFloatingService` 或 `(this)`）：

| # | 全局替换 | 目的 |
|---|---------|------|
| 1 | `this@MangaFloatingService` → `context` | 22 处 engine `refreshParams`/`resetParams` 的 Context 实参 |
| 2 | `(this)` → `(context)` | ~70 处 view 构造器（`LinearLayout(this)`/`TextView(this)`/`SeekBar(this)`/`Switch(this)`/`ScrollView(this)`）+ 2 处 `refreshParams(this)` |
| 3 | `resources.displayMetrics` → `context.resources.displayMetrics` | 3 处（`density`×2 + `heightPixels`×1） |

> **不动的部分：** `.apply {}` 接收者 `this`（如 `this.text = text`、`this.setPadding(...)`）——不带括号、不用 `this@`，保持原样。`prefs.X` 因参数同名自动解析到参数，零改动。`DEF_*`/`SliderRef`/`addSection` 随函数体搬运。

**自检**（替换完成后，在编辑器搜索确认）：
- 搜索 `this@MangaFloatingService` → 0 结果
- 搜索 `(this)` → 0 结果（`apply` 接收者 `this.` 除外）
- 搜索 `resources.` → 0 结果
- 剩余 `this` 只出现在 `.apply`/`.also` 块内（接收者，合法）

- [ ] **Step 3: 改 2 处调用点 + 删除原函数**

在 `MangaFloatingService.kt` 中：

| 行 | 原代码 | 改为 |
|----|--------|------|
| 6017 | `val slidersView = createPPOcrParamSlidersView()` | `val slidersView = MangaDebugSliders.createPPOcrParamSlidersView(prefs, this)` |
| 6194 | `val slidersView = createPPOcrV6ParamSlidersView()` | `val slidersView = MangaDebugSliders.createPPOcrV6ParamSlidersView(prefs, this)` |

顶部加 import：`import com.moe.starflow.manga.debug.MangaDebugSliders`

用函数签名锚点整段删除原函数（含 `private fun` 声明行）：
- `createPPOcrParamSlidersView`：4288-4664 行
- `createPPOcrV6ParamSlidersView`：4665-5236 行

> ⚠️ 这两个函数中间含大量局部声明，删除时从 `private fun createPPOcrParamSlidersView(): android.view.View {` 删到匹配的闭合 `}`（下一个 `private fun` 或类末尾之前）。**`createPPOcrV6ParamSlidersView` 删除后紧跟 `createInfoPanelView`（已被 Task 2 删除），注意确认 `createPPOcrV6ParamSlidersView` 的闭合 `}` 与类成员衔接正确**（原 6310 行 `MaxHeightScrollView` 已在 Task 2 删除）。

- [ ] **Step 4: 编译 + 冒烟测试 + diff 抽查**

```bash
./gradlew assembleDebug
```
Expected: BUILD SUCCESSFUL。

```powershell
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot'
$env:PATH='C:\Windows\System32;C:\Windows;'+$env:JAVA_HOME+'\bin'
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests com.moe.starflow.manga.debug.MangaDebugOverlaysTest
```
Expected: BUILD SUCCESSFUL，测试通过。

**diff 抽查（验证只改了机械替换）：**
```bash
git diff app/src/main/java/com/moe/starflow/manga/debug/MangaDebugSliders.kt app/src/main/java/com/moe/starflow/manga/MangaFloatingService.kt
```
Expected: 新文件只有签名 + 3 条规则替换；原文件只有删除 + 2 处调用点改动。**不得出现任何 seekbar 换算、prefs 键名、默认值的逻辑改动。**

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/moe/starflow/manga/debug/MangaDebugSliders.kt app/src/main/java/com/moe/starflow/manga/MangaFloatingService.kt
git commit -m "refactor: C1 - 提取 2 个参数滑块构建器到 manga/debug/MangaDebugSliders"
```

---

### Task 4: 最终验证

**Files:**
- 验证：`app/src/main/java/com/moe/starflow/manga/MangaFloatingService.kt`、`app/src/main/java/com/moe/starflow/manga/debug/`
- 文档：`docs/docs/2026-08-01-c1-pure-render-extract-design.md`、`docs/docs/2026-08-01-c1-pure-render-extract-plan.md`

- [ ] **Step 1: 全量单测 + 全量编译**

```powershell
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot'
$env:PATH='C:\Windows\System32;C:\Windows;'+$env:JAVA_HOME+'\bin'
.\gradlew.bat --no-daemon :app:testDebugUnitTest
```
Expected: 66 个测试全过（65 现有 + 1 新增冒烟）。

```bash
./gradlew assembleDebug
```
Expected: BUILD SUCCESSFUL。

- [ ] **Step 2: 行数与引用核查**

```bash
wc -l app/src/main/java/com/moe/starflow/manga/MangaFloatingService.kt
```
Expected: 约 4882 行（6340 − 1458；允许 ±50 浮动，但不得 > 5100）。

```bash
# 服务内不得再出现裸调用（必须是 MangaDebugOverlays./MangaDebugSliders. 前缀）
grep -nE "(^|[^A-Za-z.])(renderRTDetrV2DebugOverlay|renderMLKitDebugOverlay|renderPPOcrV5DebugWithMerge|renderPPOcrV6DebugWithMerge|applyCropDimmingIfNeeded|createInfoPanelView|createToggleButton|createPPOcrParamSlidersView|createPPOcrV6ParamSlidersView|MaxHeightScrollView)\(" app/src/main/java/com/moe/starflow/manga/MangaFloatingService.kt
```
Expected: 0 结果（或全部带 `MangaDebugOverlays.`/`MangaDebugSliders.` 前缀的行——用 `grep -oE "MangaDebug(Overlays|Sliders)\." | wc -l` 对比确认 10 个函数各自由新 object 提供）。

```bash
# 新文件内不得残留服务引用
grep -nE "this@MangaFloatingService|\(this\)|resources\.displayMetrics" app/src/main/java/com/moe/starflow/manga/debug/*.kt
```
Expected: 0 结果（接收者 `this` 除外——`(this)` 不应出现在任何位置）。

- [ ] **Step 3: 编译告警清理（如有）**

若 `assembleDebug` 报告 `MangaFloatingService.kt` 有 unused import 告警（搬走函数后可能残留如 `TextDirection` 等 import），删除对应的 import 行后重新编译。Expected: BUILD SUCCESSFUL 无新增告警。

- [ ] **Step 4: 提交文档 + 最终收尾**

```bash
git add docs/docs/2026-08-01-c1-pure-render-extract-design.md docs/docs/2026-08-01-c1-pure-render-extract-plan.md
git commit -m "docs: C1 纯调试代码提取 - 设计补正（CustomPreference 类型 + 90 处 this 改写规则）+ 实现计划"
git log --oneline -6
```
Expected: 4 个代码 commit + 1 个文档 commit（Task 1/2/3 已各自提交）。

- [ ] **Step 5: 设备验证清单（用户执行）**

构建产物安装后，请用户验证（**用户自己点，AI 不碰设备**）：

| # | 操作 | 预期 |
|---|------|------|
| 1 | 关于页开「PP-OCRv5 调试」→ 漫画模式翻译一页 | 调试图：绿框（原始检测）+ 青框（合并区）+ 红/橙虚线（丢弃选区）+ 参数滑块可拖动、调参生效 |
| 2 | 切换「PP-OCRv6 调试」→ 翻译一页 | 同上 + 📊/⚙ 两个内联按钮可收展 info 面板和参数面板 |
| 3 | 开「RT-DETR-V2 调试」→ 翻译一页 | 红/绿/蓝框 + 黄色 OCR 框 + 底部 info 面板 + 右下 ▼ 收展按钮 |
| 4 | 开「MLKit 调试」→ 翻译一页 | 绿块/黄行/红元素框 + info 面板收展 |
| 5 | 框选模式翻译一页 | 框选外区域半透明遮罩正常 |
| 6 | 关闭所有调试 → 正常漫画翻译一页 | 无任何调试图，行为与之前一致 |

> 判定标准：6 项全过 = 提取成功，前端体验零变化。任何一项异常，记录现象并回滚对应 Task 的 commit 排查。
