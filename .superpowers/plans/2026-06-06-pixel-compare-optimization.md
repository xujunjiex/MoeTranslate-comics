# 游戏翻译轮询优化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用 pixelmatch 风格的像素比较替代 dHash，作为 OCR 前的轻量预筛，画面未变时跳过 OCR

**Architecture:** 新建独立 PixelCompare 工具类（移植 pixelmatch 核心算法），集成到 AutoTranslateEngine 作为 OCR 前的预筛层。画面稳定 → 跳过 OCR；画面变化 → 执行原有 OCR + 文字比较流程

**Tech Stack:** Kotlin, Android Bitmap API, YIQ 色彩空间

---

## File Structure

| 文件 | 职责 |
|------|------|
| `app/src/main/java/com/moe/moetranslator/utils/PixelCompare.kt` | **新建** — 通用像素比较工具，完整移植 pixelmatch（colorDelta + antialiased + hasManySiblings） |
| `app/src/main/java/com/moe/moetranslator/translate/AutoTranslateEngine.kt` | **修改** — Decision 密封类扩展 PixelSkip，processScreenshot 入口加像素预筛 |
| `app/src/main/java/com/moe/moetranslator/translate/FloatingBallService.kt` | **修改** — 处理 PixelSkip 分支，debug 状态显示像素差异 |

---

### Task 1: 新建 PixelCompare.kt — 完整移植 pixelmatch

**Files:**
- Create: `app/src/main/java/com/moe/moetranslator/utils/PixelCompare.kt`

- [ ] **Step 1: 创建 PixelCompare.kt**

```kotlin
package com.moe.moetranslator.utils

import android.graphics.Bitmap

/**
 * 逐像素图像比较工具（移植自 pixelmatch）
 *
 * 原项目: https://github.com/mapbox/pixelmatch
 * 核心算法: YIQ 感知色彩差异 + 抗锯齿像素检测
 *
 * 用途：
 * - 检测相邻帧画面是否变化
 * - 检测画面是否稳定（翻页完成后）
 * - 作为 OCR 前的轻量预筛
 */
object PixelCompare {

    // YIQ 差异最大可能值
    private const val MAX_YIQ_DELTA = 35215f

    // diffRatio 小于此值认为画面没变
    private const val SIMILAR_THRESHOLD = 0.02f

    /**
     * 比较两个 Bitmap 的像素差异
     * @param prev 上一帧
     * @param curr 当前帧
     * @param threshold YIQ 颜色差异阈值 (0~1)，默认 0.1
     * @return CompareResult
     */
    fun compare(prev: Bitmap, curr: Bitmap, threshold: Float = 0.1f): CompareResult {
        val w = prev.width
        val h = prev.height

        // 尺寸必须一致
        require(w == curr.width && h == curr.height) {
            "Bitmap sizes must match: prev=${w}x${h}, curr=${curr.width}x${curr.height}"
        }

        val totalPixels = w * h
        val aPixels = IntArray(totalPixels)
        val bPixels = IntArray(totalPixels)
        prev.getPixels(aPixels, 0, w, 0, 0, w, h)
        curr.getPixels(bPixels, 0, w, 0, 0, w, h)

        // 快速路径：32位比较，完全相同直接返回
        var identical = true
        for (i in 0 until totalPixels) {
            if (aPixels[i] != bPixels[i]) {
                identical = false
                break
            }
        }
        if (identical) {
            return CompareResult(0, totalPixels, 0f, true)
        }

        // 最大可接受的 YIQ 差异平方值
        val maxDelta = MAX_YIQ_DELTA * threshold * threshold
        var diff = 0

        // 逐像素比较
        for (i in 0 until totalPixels) {
            val a = aPixels[i]
            val b = bPixels[i]
            if (a == b) continue

            // YIQ 感知色彩差异
            val delta = colorDelta(a, b)
            if (kotlin.math.abs(delta) > maxDelta) {
                val x = i % w
                val y = i / w
                // 检查是否为抗锯齿像素
                val isAA = antialiased(aPixels, x, y, w, h, aPixels, bPixels) ||
                           antialiased(bPixels, x, y, w, h, bPixels, aPixels)
                if (!isAA) {
                    diff++
                }
            }
        }

        val diffRatio = diff.toFloat() / totalPixels
        return CompareResult(diff, totalPixels, diffRatio, diffRatio < SIMILAR_THRESHOLD)
    }

    /**
     * YIQ 感知色彩差异（移植自 pixelmatch colorDelta）
     * 使用 Android ARGB 格式
     */
    private fun colorDelta(pixel1: Int, pixel2: Int): Float {
        val r1 = (pixel1 shr 16) and 0xFF
        val g1 = (pixel1 shr 8) and 0xFF
        val b1 = pixel1 and 0xFF
        val a1 = (pixel1 shr 24) and 0xFF

        val r2 = (pixel2 shr 16) and 0xFF
        val g2 = (pixel2 shr 8) and 0xFF
        val b2 = pixel2 and 0xFF
        val a2 = (pixel2 shr 24) and 0xFF

        var dr = (r1 - r2).toFloat()
        var dg = (g1 - g2).toFloat()
        var db = (b1 - b2).toFloat()
        val da = (a1 - a2).toFloat()

        // alpha < 255 时混合白色背景
        if (a1 < 255 || a2 < 255) {
            dr = (r1 * a1 - r2 * a2 - 255f * da) / 255f
            dg = (g1 * a1 - g2 * a2 - 255f * da) / 255f
            db = (b1 * a1 - b2 * a2 - 255f * da) / 255f
        }

        // YIQ 色彩空间
        val y = dr * 0.29889531f + dg * 0.58662247f + db * 0.11448223f
        val i = dr * 0.59597799f - dg * 0.27417610f - db * 0.32180189f
        val q = dr * 0.21147017f - dg * 0.52261711f + db * 0.31114694f

        val delta = 0.5053f * y * y + 0.299f * i * i + 0.1957f * q * q
        return if (y > 0) -delta else delta
    }

    /**
     * 亮度差异（抗锯齿检测用）
     */
    private fun brightnessDelta(pixel1: Int, pixel2: Int): Float {
        val r1 = (pixel1 shr 16) and 0xFF
        val g1 = (pixel1 shr 8) and 0xFF
        val b1 = pixel1 and 0xFF
        val a1 = (pixel1 shr 24) and 0xFF

        val r2 = (pixel2 shr 16) and 0xFF
        val g2 = (pixel2 shr 8) and 0xFF
        val b2 = pixel2 and 0xFF
        val a2 = (pixel2 shr 24) and 0xFF

        var dr = (r1 - r2).toFloat()
        var dg = (g1 - g2).toFloat()
        var db = (b1 - b2).toFloat()
        val da = (a1 - a2).toFloat()

        if (dr == 0f && dg == 0f && db == 0f && da == 0f) return 0f

        if (a1 < 255 || a2 < 255) {
            dr = (r1 * a1 - r2 * a2 - 255f * da) / 255f
            dg = (g1 * a1 - g2 * a2 - 255f * da) / 255f
            db = (b1 * a1 - b2 * a2 - 255f * da) / 255f
        }

        return dr * 0.29889531f + dg * 0.58662247f + db * 0.11448223f
    }

    /**
     * 抗锯齿像素检测（移植自 pixelmatch antialiased）
     * 基于 "Anti-aliased Pixel and Intensity Slope Detector" (V. Vysniauskas, 2009)
     */
    private fun antialiased(
        img: IntArray, x1: Int, y1: Int,
        width: Int, height: Int,
        a32: IntArray, b32: IntArray
    ): Boolean {
        val x0 = (x1 - 1).coerceAtLeast(0)
        val y0 = (y1 - 1).coerceAtLeast(0)
        val x2 = (x1 + 1).coerceAtMost(width - 1)
        val y2 = (y1 + 1).coerceAtMost(height - 1)
        val pos4 = y1 * width + x1
        val centerPixel = img[pos4]
        var zeroes = if (x1 == x0 || x1 == x2 || y1 == y0 || y1 == y2) 1 else 0
        var min = 0f
        var max = 0f
        var minX = 0; var minY = 0
        var maxX = 0; var maxY = 0

        for (x in x0..x2) {
            for (y in y0..y2) {
                if (x == x1 && y == y1) continue
                val delta = brightnessDelta(centerPixel, img[y * width + x])
                when {
                    delta == 0f -> {
                        zeroes++
                        if (zeroes > 2) return false
                    }
                    delta < min -> { min = delta; minX = x; minY = y }
                    delta > max -> { max = delta; maxX = x; maxY = y }
                }
            }
        }

        if (min == 0f || max == 0f) return false

        return (hasManySiblings(a32, minX, minY, width, height) &&
                hasManySiblings(b32, minX, minY, width, height)) ||
               (hasManySiblings(a32, maxX, maxY, width, height) &&
                hasManySiblings(b32, maxX, maxY, width, height))
    }

    /**
     * 检查像素是否有 3+ 相同色邻居
     */
    private fun hasManySiblings(
        img: IntArray, x1: Int, y1: Int,
        width: Int, height: Int
    ): Boolean {
        val x0 = (x1 - 1).coerceAtLeast(0)
        val y0 = (y1 - 1).coerceAtLeast(0)
        val x2 = (x1 + 1).coerceAtMost(width - 1)
        val y2 = (y1 + 1).coerceAtMost(height - 1)
        val val_ = img[y1 * width + x1]
        var zeroes = if (x1 == x0 || x1 == x2 || y1 == y0 || y1 == y2) 1 else 0

        for (x in x0..x2) {
            for (y in y0..y2) {
                if (x == x1 && y == y1) continue
                if (val_ == img[y * width + x]) zeroes++
                if (zeroes > 2) return true
            }
        }
        return false
    }
}

data class CompareResult(
    val diffPixels: Int,
    val totalPixels: Int,
    val diffRatio: Float,
    val isSimilar: Boolean
)
```

- [ ] **Step 2: 编译验证**

Run: `cd D:\xjj20\Desktop\fyapp\MoeTranslate-comics && gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/com/moe/moetranslator/utils/PixelCompare.kt
git commit -m "feat: add PixelCompare utility - port of pixelmatch YIQ perceptual diff"
```

---

### Task 2: 修改 AutoTranslateEngine — 像素预筛 + Decision 扩展

**Files:**
- Modify: `app/src/main/java/com/moe/moetranslator/translate/AutoTranslateEngine.kt`

- [ ] **Step 1: 扩展 Decision 密封类**

将 `Decision.Skip` 拆分为 `PixelSkip` 和 `TextSkip`：

```kotlin
// 替换 Decision 密封类（原文件第 43-50 行）
sealed class Decision {
    /** 像素未变，跳过（不执行 OCR） */
    data class PixelSkip(val diffRatio: Float) : Decision()
    /** OCR 后文字不同或空，跳过 */
    object TextSkip : Decision()
    /** 命中缓存，直接显示；source: "memory" / "database" */
    data class CacheHit(val cachedText: String, val source: String) : Decision()
    /** 需要翻译；similarity: 与上次 OCR 文字的相似度（-1 表示手动强制翻译） */
    data class Translate(val ocrText: String, val similarity: Float) : Decision()
}
```

- [ ] **Step 2: 添加 PixelCompare 字段和 import**

在 import 区添加：
```kotlin
import com.moe.moetranslator.utils.PixelCompare
import android.graphics.Bitmap.Config.ARGB_8888
```

在类字段区（`isManualForceTranslate` 之后）添加：
```kotlin
// 像素比较
private var lastBitmap: Bitmap? = null
var lastDiffRatio: Float = 0f
```

- [ ] **Step 3: 修改 processScreenshot 入口 — 加入像素预筛**

在 `processScreenshot()` 的 `if (!isRunning) return Decision.Skip` 之后、OCR 之前插入像素比较逻辑：

```kotlin
suspend fun processScreenshot(bitmap: Bitmap): Decision {
    if (!isRunning) return Decision.TextSkip

    // 像素预筛：画面未变则跳过 OCR
    val prevBitmap = lastBitmap
    if (prevBitmap != null && !isManualForceTranslate) {
        val result = PixelCompare.compare(prevBitmap, bitmap)
        lastDiffRatio = result.diffRatio
        prevBitmap.recycle()
        lastBitmap = bitmap.copy(ARGB_8888, true)
        if (result.isSimilar) {
            LogCollector.d(TAG, "【跳过·画面未变】diffRatio=${"%.4f".format(result.diffRatio)}")
            return Decision.PixelSkip(result.diffRatio)
        }
    } else {
        // 首次截图或手动翻译，保存 bitmap 供下次比较
        lastBitmap?.recycle()
        lastBitmap = bitmap.copy(ARGB_8888, true)
    }

    LogCollector.d(TAG, "【检测中】正在 OCR 识别...")
    // ... 后续原有 OCR 逻辑不变
```

- [ ] **Step 4: 修改原有 Decision.Skip 引用为 TextSkip**

将整个文件中所有 `Decision.Skip` 替换为 `Decision.TextSkip`：
- `processScreenshot()` 第 89 行 `return Decision.Skip` → `return Decision.TextSkip`（OCR 结果为空）
- `processScreenshot()` 第 129 行 `return Decision.Skip` → `return Decision.TextSkip`（文字不同且无缓存）
- `forceTranslate()` 第 148 行 `return Decision.Translate(...)` 不变（已经是 Translate，不涉及 Skip）

- [ ] **Step 5: 修改 stop() 清理 lastBitmap**

在 `stop()` 方法中添加：
```kotlin
fun stop() {
    isRunning = false
    isManualForceTranslate = false
    lastBitmap?.recycle()
    lastBitmap = null
    lastDiffRatio = 0f
    LogCollector.d(TAG, "自动翻译停止")
}
```

同样在 `onCropRegionChanged()` 等清理方法中添加 `lastBitmap?.recycle(); lastBitmap = null`

- [ ] **Step 6: 编译验证**

Run: `cd D:\xjj20\Desktop\fyapp\MoeTranslate-comics && gradlew.bat assembleDebug`
Expected: 可能有 FloatingBallService 中 Decision.Skip 的编译错误，Task 3 会修复

- [ ] **Step 7: 提交**

```bash
git add app/src/main/java/com/moe/moetranslator/translate/AutoTranslateEngine.kt
git commit -m "feat: add pixel pre-filter to AutoTranslateEngine, split Decision.Skip"
```

---

### Task 3: 修改 FloatingBallService — 处理 PixelSkip + debug 显示

**Files:**
- Modify: `app/src/main/java/com/moe/moetranslator/translate/FloatingBallService.kt`

- [ ] **Step 1: 处理 Decision.Skip → Decision.TextSkip**

将 `processScreenshot()` 中所有 `is AutoTranslateEngine.Decision.Skip` 替换为 `is AutoTranslateEngine.Decision.TextSkip`。

- [ ] **Step 2: 添加 PixelSkip 分支处理**

在 `when (val decision = engine.processScreenshot(bitmap))` 中，在 `TextSkip` 分支之前添加：

```kotlin
is AutoTranslateEngine.Decision.PixelSkip -> {
    val pct = "%.1f%%".format(decision.diffRatio * 100)
    updateDebugStatus("【跳过·画面未变】$pct")
    LogCollector.d("FloatingBallService", "像素未变，跳过OCR: $pct")
    isTranslating.set(false)
    scheduleNextDetection(DETECT_INTERVAL_MS)
}
```

- [ ] **Step 3: 编译验证**

Run: `cd D:\xjj20\Desktop\fyapp\MoeTranslate-comics && gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/com/moe/moetranslator/translate/FloatingBallService.kt
git commit -m "feat: handle PixelSkip in FloatingBallService with debug status"
```

---

### Task 4: 端到端验证

- [ ] **Step 1: 安装到设备**

Run: `adb install -r app/build/outputs/apk/debug/app-debug.apk`

- [ ] **Step 2: 验证画面静止场景**

1. 开启游戏翻译调试模式
2. 进入游戏，选框对准文本区域
3. 启动自动翻译
4. 保持画面不动
5. 预期：debug 显示【跳过·画面未变】，diffRatio < 2%，不触发 OCR

- [ ] **Step 3: 验证翻页场景**

1. 游戏中翻页
2. 预期：diffRatio 升高（>2%），触发 OCR，正常翻译

- [ ] **Step 4: 验证手动翻译**

1. 停止自动翻译
2. 点击悬浮球手动翻译
3. 预期：跳过像素比较，直接 OCR + 翻译

- [ ] **Step 5: 提交最终版本**

```bash
git add -A
git commit -m "feat: game translate polling optimization with pixelmatch comparison"
```
