# 像素驱动自动翻译 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用像素比较替代 OCR 文字比较作为稳定检测，配合 150ms 快检间隔 + LRU 内存缓存，提升游戏自动翻译反应速度。

**Architecture:** AutoTranslateEngine 内部状态机改为像素驱动（CHANGED→STABLE_1→STABLE_2→OCR），FloatingBallService 拆分为 150ms 像素快检和 OCR 触发两个独立调度。翻译结果用 LruCache<String,String>(20) 内存缓存替代单条 lastTranslationResult。

**Tech Stack:** Kotlin, Android LruCache, PixelCompare (existing), CustomPreference

---

## 文件结构

| 文件 | 操作 | 职责 |
|------|------|------|
| `app/src/main/res/xml/personalization.xml:104-123` | 修改 | OCR 分类下新增像素阈值 ListPreference |
| `app/src/main/res/values-zh/arrays.xml` | 修改 | 新增像素阈值 entries/values 数组 |
| `app/src/main/res/values/arrays.xml` | 修改 | 英文版像素阈值 entries/values |
| `app/src/main/res/values-zh/strings.xml` | 修改 | 新增设置项标题和摘要文案 |
| `app/src/main/res/values/strings.xml` | 修改 | 英文版设置项文案 |
| `app/src/main/java/com/moe/moetranslator/me/PersonalizationConfig.kt` | 修改 | 读写像素阈值偏好 |
| `app/src/main/java/com/moe/moetranslator/translate/AutoTranslateEngine.kt` | 重写 | 状态机 + LRU 缓存 + 像素驱动 OCR |
| `app/src/main/java/com/moe/moetranslator/translate/FloatingBallService.kt` | 修改 | 150ms 像素快检调度 + OCR 触发分离 |

---

### Task 1: 添加像素阈值设置项

**Files:**
- Modify: `app/src/main/res/xml/personalization.xml:104-123`
- Modify: `app/src/main/res/values-zh/arrays.xml`
- Modify: `app/src/main/res/values/arrays.xml`
- Modify: `app/src/main/res/values-zh/strings.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/java/com/moe/moetranslator/me/PersonalizationConfig.kt`

- [ ] **Step 1: 添加字符串资源**

`app/src/main/res/values-zh/strings.xml`，在 `game_hide_log` 行后添加：
```xml
<string name="pixel_threshold_title">像素变化阈值</string>
<string name="pixel_threshold_summary">当前: %s。diffRatio 低于此值认为画面没变化</string>
```

`app/src/main/res/values/strings.xml`，在 `game_hide_log` 行后添加：
```xml
<string name="pixel_threshold_title">Pixel Change Threshold</string>
<string name="pixel_threshold_summary">Current: %s. Below this diffRatio = no change</string>
```

- [ ] **Step 2: 添加数组资源**

`app/src/main/res/values-zh/arrays.xml`，在 `show_source_text_values` 后添加：
```xml
<string-array name="pixel_threshold_entries">
    <item>1%</item>
    <item>2%</item>
    <item>3%</item>
    <item>5%</item>
    <item>8%</item>
    <item>10%</item>
    <item>15%</item>
    <item>20%</item>
</string-array>

<string-array name="pixel_threshold_values">
    <item>1</item>
    <item>2</item>
    <item>3</item>
    <item>5</item>
    <item>8</item>
    <item>10</item>
    <item>15</item>
    <item>20</item>
</string-array>
```

`app/src/main/res/values/arrays.xml`，同样位置添加相同的数组（数值一样，标签已经是百分比符号）。

- [ ] **Step 3: 添加 ListPreference 到 personalization.xml**

`app/src/main/res/xml/personalization.xml`，在 `</PreferenceCategory>` (OCR category, line 123) 之前添加：
```xml
<ListPreference
    android:key="pixel_threshold"
    app:iconSpaceReserved="false"
    android:defaultValue="5"
    android:title="@string/pixel_threshold_title"
    android:entries="@array/pixel_threshold_entries"
    android:entryValues="@array/pixel_threshold_values"/>
```

- [ ] **Step 4: PersonalizationConfig 读写偏好**

`app/src/main/java/com/moe/moetranslator/me/PersonalizationConfig.kt`：

在 `private lateinit var showSource` 声明附近添加：
```kotlin
private lateinit var pixelThreshold: ListPreference
```

在 `showSource = findPreference(...)` 附近添加：
```kotlin
pixelThreshold = findPreference<ListPreference>("pixel_threshold")!!
```

在 `showSource` 的 `setOnPreferenceChangeListener` 之后添加：
```kotlin
// 像素变化阈值
pixelThreshold.setOnPreferenceChangeListener { _, newValue ->
    prefs.setInt("Game_Pixel_Similar_Threshold", newValue.toString().toInt())
    true
}
pixelThreshold.summaryProvider = Preference.SummaryProvider<ListPreference> { _ ->
    getString(R.string.pixel_threshold_summary, pixelThreshold.entry)
}
```

- [ ] **Step 5: 编译验证**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 提交**

```bash
git add app/src/main/res/xml/personalization.xml app/src/main/res/values-zh/arrays.xml app/src/main/res/values/arrays.xml app/src/main/res/values-zh/strings.xml app/src/main/res/values/strings.xml app/src/main/java/com/moe/moetranslator/me/PersonalizationConfig.kt
git commit -m "feat: add pixel change threshold setting to personalization OCR section"
```

---

### Task 2: 重写 AutoTranslateEngine 状态机

**Files:**
- Modify: `app/src/main/java/com/moe/moetranslator/translate/AutoTranslateEngine.kt`

- [ ] **Step 1: 重写 AutoTranslateEngine**

完整替换 `AutoTranslateEngine.kt`：

```kotlin
package com.moe.moetranslator.translate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.LruCache
import com.moe.moetranslator.data.TranslationCacheManager
import com.moe.moetranslator.utils.CustomPreference
import com.moe.moetranslator.utils.LogCollector
import com.moe.moetranslator.utils.PixelCompare
import com.moe.moetranslator.utils.TextSimilarity

/**
 * 游戏自动翻译引擎（像素驱动版）。
 *
 * 核心逻辑：
 * 1. 像素快检（150ms）判断页面是否稳定
 * 2. 像素连续稳定 2 帧后触发 OCR
 * 3. OCR 结果查 LRU 缓存，命中直接显示，未命中调翻译 API
 */
class AutoTranslateEngine(
    private val context: Context,
    private val cacheManager: TranslationCacheManager,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val getSourceLanguage: () -> String,
    private val getTargetLanguage: () -> String
) {
    companion object {
        private const val TAG = "AutoTranslateEngine"
        private const val STABLE_FRAMES = 2
        private const val LRU_CAPACITY = 20
        private const val DEFAULT_PIXEL_THRESHOLD = 5
    }

    // 像素稳定状态机
    enum class PixelState {
        CHANGED,    // 像素和上帧不同
        STABLE_1,   // 稳定第1帧
        STABLE_2    // 稳定第2帧，触发 OCR
    }

    // 状态
    private var isRunning = false
    private var pixelState = PixelState.CHANGED
    private var stableCount = 0

    // OCR 引擎
    private val ocrEngine = GameOcrEngine(context)

    // 强制翻译标志（手动点击时跳过像素检查）
    var isManualForceTranslate = false

    // 像素比较
    private var lastPixels: IntArray? = null
    private var lastWidth = 0
    private var lastHeight = 0
    var lastDiffRatio: Float = 0f

    // 内存 LRU 缓存：normalize(sourceText) → translatedText
    private val translationCache = LruCache<String, String>(LRU_CAPACITY)

    // 翻译决策
    sealed class Decision {
        /** 像素正在变化，跳过 */
        data class PixelChanging(val diffRatio: Float) : Decision()
        /** 像素稳定但未到阈值，继续等待 */
        data class PixelStabilizing(val stableCount: Int, val diffRatio: Float) : Decision()
        /** 命中 LRU 缓存，直接显示 */
        data class CacheHit(val cachedText: String) : Decision()
        /** 需要翻译 */
        data class Translate(val ocrText: String) : Decision()
    }

    fun start() {
        if (isRunning) return
        isRunning = true
        pixelState = PixelState.CHANGED
        stableCount = 0
        isManualForceTranslate = false
        LogCollector.d(TAG, "自动翻译启动（像素驱动）")
    }

    fun stop() {
        isRunning = false
        isManualForceTranslate = false
        lastPixels = null
        lastDiffRatio = 0f
        LogCollector.d(TAG, "自动翻译停止")
    }

    /**
     * 像素快检：仅比较像素，返回当前像素状态。
     * 不执行 OCR，由 FloatingBallService 根据返回值决定是否触发 OCR。
     */
    fun checkPixel(bitmap: Bitmap): Decision {
        if (!isRunning) return Decision.PixelChanging(0f)

        val w = bitmap.width
        val h = bitmap.height
        val currPixels = IntArray(w * h)
        bitmap.getPixels(currPixels, 0, w, 0, 0, w, h)

        // 读取用户设置的像素阈值
        val prefs = CustomPreference.getInstance(context)
        val thresholdPct = prefs.getInt("Game_Pixel_Similar_Threshold", DEFAULT_PIXEL_THRESHOLD)
        val threshold = thresholdPct / 100f

        val prevPixels = lastPixels
        if (prevPixels != null && lastWidth == w && lastHeight == h) {
            val result = PixelCompare.comparePixels(prevPixels, currPixels, w, h, threshold)
            lastDiffRatio = result.diffRatio
            lastPixels = currPixels

            if (result.isSimilar) {
                // 像素没变，稳定计数+1
                stableCount++
                pixelState = if (stableCount >= STABLE_FRAMES) PixelState.STABLE_2 else PixelState.STABLE_1
                LogCollector.d(TAG, "【像素稳定】count=$stableCount diff=${"%.6f".format(result.diffRatio)}")
                return if (pixelState == PixelState.STABLE_2) {
                    Decision.PixelStabilizing(stableCount, result.diffRatio)
                } else {
                    Decision.PixelStabilizing(stableCount, result.diffRatio)
                }
            } else {
                // 像素变了，重置
                stableCount = 0
                pixelState = PixelState.CHANGED
                LogCollector.d(TAG, "【像素变化】diff=${"%.6f".format(result.diffRatio)}")
                return Decision.PixelChanging(result.diffRatio)
            }
        } else {
            // 首次截图，保存像素
            lastPixels = currPixels
            lastWidth = w
            lastHeight = h
            stableCount = 0
            pixelState = PixelState.CHANGED
            LogCollector.d(TAG, "【首次截图·保存像素】")
            return Decision.PixelChanging(0f)
        }
    }

    /**
     * OCR + 缓存查询。像素稳定后由 FloatingBallService 调用。
     */
    suspend fun ocrAndTranslate(bitmap: Bitmap): Decision {
        if (!isRunning) return Decision.PixelChanging(0f)

        LogCollector.d(TAG, "【触发OCR】正在识别...")
        val ocrText = ocrEngine.recognize(bitmap)
        val normalizedText = TextSimilarity.normalize(ocrText)

        if (normalizedText.isBlank()) {
            LogCollector.d(TAG, "【跳过】OCR 结果为空")
            return Decision.PixelChanging(0f)
        }

        // 手动强制翻译
        if (isManualForceTranslate) {
            isManualForceTranslate = false
            LogCollector.d(TAG, "【手动强制翻译】${normalizedText.take(20)}...")
            return Decision.Translate(normalizedText)
        }

        // 查 LRU 缓存
        val cached = translationCache.get(normalizedText)
        if (cached != null) {
            LogCollector.d(TAG, "【LRU缓存命中】${normalizedText.take(20)}...")
            return Decision.CacheHit(cached)
        }

        // 缓存未命中，需要翻译
        LogCollector.d(TAG, "【需翻译】${normalizedText.take(20)}...")
        return Decision.Translate(normalizedText)
    }

    /**
     * 翻译成功后更新 LRU 缓存。
     */
    fun onTranslationSuccess(sourceText: String, translatedText: String) {
        val normalized = TextSimilarity.normalize(sourceText)
        translationCache.put(normalized, translatedText)
        LogCollector.d(TAG, "翻译成功，写入LRU缓存: ${sourceText.take(20)}...")
    }

    /**
     * 手动翻译：跳过像素检查，直接 OCR。
     */
    fun forceTranslate(ocrText: String): Decision.Translate {
        isManualForceTranslate = false
        return Decision.Translate(TextSimilarity.normalize(ocrText))
    }

    fun isFloatingViewOverlappingCrop(floatViewRect: Rect, cropRect: Rect): Boolean {
        return Rect.intersects(floatViewRect, cropRect)
    }

    fun onLanguageChanged() {
        translationCache.evictAll()
        LogCollector.d(TAG, "语言变化，清空 LRU 缓存")
    }

    fun onOcrEngineChanged() {
        translationCache.evictAll()
        LogCollector.d(TAG, "OCR 引擎变化，清空 LRU 缓存")
    }

    fun onCropRegionChanged() {
        translationCache.evictAll()
        lastPixels = null
        LogCollector.d(TAG, "裁剪区域变化，清空 LRU 缓存")
    }

    fun onApiConfigChanged() {
        translationCache.evictAll()
        LogCollector.d(TAG, "API 配置变化，清空 LRU 缓存")
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL（FloatingBallService 引用的旧方法会报错，Task 3 修复）

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/com/moe/moetranslator/translate/AutoTranslateEngine.kt
git commit -m "feat: rewrite AutoTranslateEngine with pixel-driven state machine and LRU cache"
```

---

### Task 3: 更新 FloatingBallService 轮询调度

**Files:**
- Modify: `app/src/main/java/com/moe/moetranslator/translate/FloatingBallService.kt`

- [ ] **Step 1: 修改常量和调度逻辑**

`FloatingBallService.kt`：

将 `DETECT_INTERVAL_MS` 改为两个常量：
```kotlin
companion object {
    private const val PIXEL_CHECK_INTERVAL_MS = 150L  // 像素快检间隔
    private const val OCR_TIMEOUT_MS = 3000L           // OCR 超时
}
```

- [ ] **Step 2: 重写 runAutoDetect**

替换 `runAutoDetect()` 方法：

```kotlin
private fun runAutoDetect() {
    if (!isAutoTranslating) return
    if (isTranslating.get()) {
        scheduleNextDetection(PIXEL_CHECK_INTERVAL_MS)
        return
    }
    // 设置超时
    autoTranslateHandler.postDelayed({
        if (isAutoTranslating && isTranslating.get()) {
            LogCollector.d("FloatingBallService", "截图超时，重置状态")
            isTranslating.set(false)
            scheduleNextDetection(PIXEL_CHECK_INTERVAL_MS)
        }
    }, OCR_TIMEOUT_MS)
    AccessibilityServiceManager.takeScreenshot(mRectF, cropView.absolutePointOffset)
}
```

- [ ] **Step 3: 重写 processScreenshot 自动翻译分支**

替换 `processScreenshot` 中自动翻译分支（`if (engine != null && isAutoTranslating)` 内部）：

```kotlin
if (engine != null && isAutoTranslating) {
    // 第一步：像素快检
    translateStartTime = System.currentTimeMillis()
    when (val pixelDecision = engine.checkPixel(bitmap)) {
        is AutoTranslateEngine.Decision.PixelChanging -> {
            updateDebugStatus("【像素变化】", diffRatio = pixelDecision.diffRatio)
            isTranslating.set(false)
            scheduleNextDetection(PIXEL_CHECK_INTERVAL_MS)
        }
        is AutoTranslateEngine.Decision.PixelStabilizing -> {
            // 像素稳定但未到阈值，继续快检
            if (pixelDecision.stableCount >= 2) {
                // 达到稳定阈值，触发 OCR
                updateDebugStatus("【触发OCR】", diffRatio = pixelDecision.diffRatio)
                when (val ocrDecision = engine.ocrAndTranslate(bitmap)) {
                    is AutoTranslateEngine.Decision.CacheHit -> {
                        val elapsed = System.currentTimeMillis() - translateStartTime
                        updateDebugStatus("【LRU缓存命中】", elapsedMs = elapsed, diffRatio = pixelDecision.diffRatio)
                        floatingTextView.text = ocrDecision.cachedText
                        isTranslating.set(false)
                        scheduleNextDetection(PIXEL_CHECK_INTERVAL_MS)
                    }
                    is AutoTranslateEngine.Decision.Translate -> {
                        updateDebugStatus("【翻译中】", diffRatio = pixelDecision.diffRatio)
                        translateByText(ocrDecision.ocrText)
                    }
                    else -> {
                        isTranslating.set(false)
                        scheduleNextDetection(PIXEL_CHECK_INTERVAL_MS)
                    }
                }
            } else {
                updateDebugStatus("【像素稳定】${pixelDecision.stableCount}/2", diffRatio = pixelDecision.diffRatio)
                isTranslating.set(false)
                scheduleNextDetection(PIXEL_CHECK_INTERVAL_MS)
            }
        }
        else -> {
            isTranslating.set(false)
            scheduleNextDetection(PIXEL_CHECK_INTERVAL_MS)
        }
    }
}
```

- [ ] **Step 4: 更新 translateByText 回调**

在 `translateByText` 的成功回调中，将 `autoTranslateEngine?.onTranslationSuccess(str, result.translatedText)` 保留（已有），同时将 `scheduleNextDetection` 的参数改为 `PIXEL_CHECK_INTERVAL_MS`：

```kotlin
isTranslating.set(false)
if (isAutoTranslating) {
    scheduleNextDetection(PIXEL_CHECK_INTERVAL_MS)
}
```

同样更新 `translateByPic` 中的 `scheduleNextDetection` 调用。

- [ ] **Step 5: 更新 startAutoTranslate 中的 scheduleNextDetection**

`startAutoTranslate()` 中 `scheduleNextDetection(0L)` 保持不变（首次立即执行）。

- [ ] **Step 6: 编译验证**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: 提交**

```bash
git add app/src/main/java/com/moe/moetranslator/translate/FloatingBallService.kt
git commit -m "feat: split polling into 150ms pixel check + OCR trigger in FloatingBallService"
```

---

### Task 4: 安装测试

- [ ] **Step 1: 安装到设备**

Run: `adb install -r app/build/outputs/apk/debug/app-debug.apk`

- [ ] **Step 2: 验证设置项**

打开 app → 个性化设置 → OCR 部分 → 确认"像素变化阈值"出现，默认 5%

- [ ] **Step 3: 验证自动翻译**

1. 开启游戏翻译 → 悬浮球长按 → 开启自动翻译
2. 调试面板应显示"【像素变化】"、"【像素稳定】1/2"、"【触发OCR】"等状态
3. 翻译结果应正常显示
4. 切换页面后反应速度应明显快于之前

- [ ] **Step 4: 验证 LRU 缓存**

1. 翻译一段文字 → 显示翻译结果
2. 切走再切回 → 应显示"【LRU缓存命中】"，不调翻译 API

- [ ] **Step 5: 提交最终版本**

```bash
git add -A
git commit -m "feat: pixel-driven auto-translate with 150ms polling and LRU cache"
```
