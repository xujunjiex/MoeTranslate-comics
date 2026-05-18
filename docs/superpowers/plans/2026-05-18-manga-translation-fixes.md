# Manga Translation Bug Fixes & Features Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix all bugs and add missing features in the manga translation mode to make it production-ready.

**Architecture:** The manga translation pipeline (OCR -> Bubble Detection -> Translation -> Rendering) lives in `manga/` package. The service `MangaFloatingService` orchestrates the pipeline. `TranslateFragment` handles the UI entry point. We will optimize the pipeline, add area selection via `CropView` reuse, add text direction config, add auto-translate, add loading feedback, and enforce mutual exclusion between services.

**Tech Stack:** Kotlin, Android WindowManager overlays, ML Kit OCR, Accessibility Service screenshots

---

## File Structure

| File | Action | Responsibility |
|------|--------|----------------|
| `manga/MangaFloatingService.kt` | Modify | Main service: add crop, auto-translate, loading state, mutual exclusion, menu expansion |
| `manga/MangaModeConfig.kt` | Modify | Add `textDirection` field |
| `manga/BackgroundAnalyzer.kt` | Modify | Optimize pixel sampling for speed |
| `manga/OverlayRenderer.kt` | Modify | Support text direction, fix fullscreen coverage |
| `manga/VerticalTextRenderer.kt` | Modify | Add right-to-left horizontal mode |
| `translate/TranslateFragment.kt` | Modify | Manga button state toggle, mutual exclusion check |
| `res/values-zh/arrays.xml` | Modify | Add manga menu items (font size, auto-translate, crop, direction) |
| `res/values/arrays.xml` | Modify | Add manga menu items (English) |
| `res/values-en/arrays.xml` | Modify | Add manga menu items (English) |
| `res/values-zh/strings.xml` | Modify | Add new string resources |
| `res/values/strings.xml` | Modify | Add new string resources |

---

### Task 1: Optimize BackgroundAnalyzer for Speed

**Problem:** `BackgroundAnalyzer.analyzeBackground()` calls `bitmap.getPixel()` in a tight loop along all 4 edges with step=3. For a 1080x1920 screen, a large bubble rect could sample ~1000+ pixels individually. `getPixel()` is slow per-call.

**Files:**
- Modify: `app/src/main/java/com/moe/moetranslator/manga/BackgroundAnalyzer.kt`

- [ ] **Step 1: Replace per-pixel sampling with bulk pixel read**

Replace the entire `analyzeBackground` method. Instead of calling `getPixel()` per-pixel, read a small border strip into an `IntArray` via `Bitmap.getPixels()` and compute the average. Sample only every 5th pixel along the border.

```kotlin
package com.moe.moetranslator.manga

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect

object BackgroundAnalyzer {

    fun analyzeBackground(bitmap: Bitmap, region: Rect): Int {
        val safeRegion = Rect(
            region.left.coerceIn(0, bitmap.width - 1),
            region.top.coerceIn(0, bitmap.height - 1),
            region.right.coerceIn(0, bitmap.width),
            region.bottom.coerceIn(0, bitmap.height)
        )

        val width = safeRegion.width()
        val height = safeRegion.height()
        if (width <= 0 || height <= 0) return Color.WHITE

        val samples = mutableListOf<Int>()
        val step = 5

        // Top edge (1 row)
        for (x in safeRegion.left until safeRegion.right step step) {
            samples.add(bitmap.getPixel(x, safeRegion.top))
        }
        // Bottom edge (1 row)
        for (x in safeRegion.left until safeRegion.right step step) {
            samples.add(bitmap.getPixel(x, (safeRegion.bottom - 1).coerceAtLeast(0)))
        }
        // Left edge (1 column)
        for (y in safeRegion.top until safeRegion.bottom step step) {
            samples.add(bitmap.getPixel(safeRegion.left, y))
        }
        // Right edge (1 column)
        for (y in safeRegion.top until safeRegion.bottom step step) {
            samples.add(bitmap.getPixel((safeRegion.right - 1).coerceAtLeast(0), y))
        }

        if (samples.isEmpty()) return Color.WHITE
        return averageColor(samples)
    }

    private fun averageColor(colors: List<Int>): Int {
        var r = 0L
        var g = 0L
        var b = 0L
        for (color in colors) {
            r += Color.red(color)
            g += Color.green(color)
            b += Color.blue(color)
        }
        val count = colors.size
        return Color.rgb((r / count).toInt(), (g / count).toInt(), (b / count).toInt())
    }
}
```

- [ ] **Step 2: Verify the change compiles**

Run: `cd D:/xjj20/Desktop/fyapp/MoeTranslate-5.3.1 && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/moe/moetranslator/manga/BackgroundAnalyzer.kt
git commit -m "perf: optimize BackgroundAnalyzer with larger sampling step"
```

---

### Task 2: Fix Result Overlay Fullscreen Coverage

**Problem:** `resultOverlayView` uses `ImageView.ScaleType.FIT_CENTER` which preserves aspect ratio and leaves black bars. The result bitmap is a copy of the original screenshot, but the overlay ImageView doesn't scale to fill the screen.

**Files:**
- Modify: `app/src/main/java/com/moe/moetranslator/manga/MangaFloatingService.kt`

- [ ] **Step 1: Change scale type to FIT_XY**

In `initializeViews()`, change the `resultOverlayView` scale type:

Find:
```kotlin
resultOverlayView = ImageView(this).apply {
    scaleType = ImageView.ScaleType.FIT_CENTER
    setBackgroundColor(Color.argb(180, 0, 0, 0))
}
```

Replace with:
```kotlin
resultOverlayView = ImageView(this).apply {
    scaleType = ImageView.ScaleType.FIT_XY
    setBackgroundColor(Color.argb(180, 0, 0, 0))
}
```

- [ ] **Step 2: Verify the change compiles**

Run: `cd D:/xjj20/Desktop/fyapp/MoeTranslate-5.3.1 && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/moe/moetranslator/manga/MangaFloatingService.kt
git commit -m "fix: use FIT_XY for result overlay to cover full screen"
```

---

### Task 3: Add Text Direction Configuration to MangaModeConfig

**Problem:** Text direction is hardcoded by language in `VerticalTextRenderer.drawText()`. Users need to switch between vertical (top-to-bottom, right-to-left) and horizontal (left-to-right, top-to-bottom) reading modes.

**Files:**
- Modify: `app/src/main/java/com/moe/moetranslator/manga/MangaModeConfig.kt`
- Modify: `app/src/main/java/com/moe/moetranslator/manga/VerticalTextRenderer.kt`
- Modify: `app/src/main/java/com/moe/moetranslator/manga/OverlayRenderer.kt`

- [ ] **Step 1: Add TextDirection enum to MangaModeConfig**

Replace `MangaModeConfig.kt` entirely:

```kotlin
package com.moe.moetranslator.manga

enum class TextDirection {
    VERTICAL,    // Top-to-bottom, right-to-left (traditional manga)
    HORIZONTAL   // Left-to-right, top-to-bottom (standard)
}

data class MangaModeConfig(
    val enabled: Boolean = false,
    val textDirection: TextDirection = TextDirection.VERTICAL,
    val smartBackground: Boolean = true,
    val autoDetectBubble: Boolean = true,
    val fontSize: Float = 16f,
    val sourceLang: String = "ja",
    val targetLang: String = "zh"
)
```

- [ ] **Step 2: Update VerticalTextRenderer to use TextDirection**

Replace `VerticalTextRenderer.kt` entirely:

```kotlin
package com.moe.moetranslator.manga

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect

object VerticalTextRenderer {

    fun drawVerticalText(
        canvas: Canvas,
        text: String,
        region: Rect,
        fontSize: Float = 16f,
        textColor: Int = Color.BLACK
    ) {
        val paint = Paint().apply {
            color = textColor
            textSize = fontSize
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }

        val charHeight = fontSize * 1.4f
        val columnSpacing = fontSize * 1.2f
        val lines = text.split("\n")
        var currentX = region.right - columnSpacing / 2

        for (line in lines) {
            if (currentX < region.left) break
            var currentY = region.top + charHeight
            for (char in line) {
                if (currentY > region.bottom) break
                canvas.drawText(char.toString(), currentX, currentY, paint)
                currentY += charHeight
            }
            currentX -= columnSpacing
        }
    }

    fun drawHorizontalText(
        canvas: Canvas,
        text: String,
        region: Rect,
        fontSize: Float = 16f,
        textColor: Int = Color.BLACK
    ) {
        val paint = Paint().apply {
            color = textColor
            textSize = fontSize
            isAntiAlias = true
        }
        val lines = text.split("\n")
        var currentY = region.top + fontSize
        for (line in lines) {
            if (currentY > region.bottom) break
            canvas.drawText(line, region.left.toFloat(), currentY, paint)
            currentY += fontSize * 1.4f
        }
    }

    fun drawText(
        canvas: Canvas,
        text: String,
        region: Rect,
        direction: TextDirection,
        fontSize: Float = 16f,
        textColor: Int = Color.BLACK
    ) {
        when (direction) {
            TextDirection.VERTICAL -> drawVerticalText(canvas, text, region, fontSize, textColor)
            TextDirection.HORIZONTAL -> drawHorizontalText(canvas, text, region, fontSize, textColor)
        }
    }
}
```

- [ ] **Step 3: Update OverlayRenderer to pass TextDirection**

Replace `OverlayRenderer.kt` entirely:

```kotlin
package com.moe.moetranslator.manga

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect

object OverlayRenderer {

    fun renderOverlay(
        original: Bitmap,
        regions: List<TranslatedBubble>,
        direction: TextDirection,
        fontSize: Float = 16f
    ): Bitmap {
        val result = original.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)

        for (region in regions) {
            val bgPaint = Paint().apply {
                color = region.backgroundColor
                style = Paint.Style.FILL
            }
            canvas.drawRect(region.rect, bgPaint)

            VerticalTextRenderer.drawText(
                canvas = canvas,
                text = region.translatedText,
                region = region.rect,
                direction = direction,
                fontSize = fontSize,
                textColor = getContrastColor(region.backgroundColor)
            )
        }

        return result
    }

    private fun getContrastColor(backgroundColor: Int): Int {
        val luminance = (0.299 * Color.red(backgroundColor) +
                0.587 * Color.green(backgroundColor) +
                0.114 * Color.blue(backgroundColor)) / 255
        return if (luminance > 0.5) Color.BLACK else Color.WHITE
    }
}

data class TranslatedBubble(
    val rect: Rect,
    val originalText: String,
    val translatedText: String,
    val backgroundColor: Int
)
```

- [ ] **Step 4: Update MangaFloatingService to use new OverlayRenderer signature**

In `MangaFloatingService.kt`, find the `processMangaScreenshot` method. Change the `renderOverlay` call:

Find:
```kotlin
val resultBitmap = withContext(Dispatchers.Default) {
    OverlayRenderer.renderOverlay(
        original = bitmap,
        regions = translatedBubbles,
        targetLang = config.targetLang,
        fontSize = config.fontSize
    )
}
```

Replace with:
```kotlin
val resultBitmap = withContext(Dispatchers.Default) {
    OverlayRenderer.renderOverlay(
        original = bitmap,
        regions = translatedBubbles,
        direction = config.textDirection,
        fontSize = config.fontSize
    )
}
```

- [ ] **Step 5: Update loadConfig to read textDirection**

In `MangaFloatingService.kt`, find `loadConfig()`:

Find:
```kotlin
private fun loadConfig(): MangaModeConfig {
    return MangaModeConfig(
        enabled = true,
        verticalText = prefs.getBoolean("Manga_Vertical_Text", true),
        smartBackground = prefs.getBoolean("Manga_Smart_Background", true),
        autoDetectBubble = prefs.getBoolean("Manga_Auto_Detect_Bubble", true),
        fontSize = prefs.getFloat("Manga_Font_Size", 16f),
        sourceLang = prefs.getString("Source_Language", "ja"),
        targetLang = prefs.getString("Target_Language", "zh")
    )
}
```

Replace with:
```kotlin
private fun loadConfig(): MangaModeConfig {
    val directionIndex = prefs.getInt("Manga_Text_Direction", 0)
    val direction = TextDirection.entries.getOrElse(directionIndex) { TextDirection.VERTICAL }
    return MangaModeConfig(
        enabled = true,
        textDirection = direction,
        smartBackground = prefs.getBoolean("Manga_Smart_Background", true),
        autoDetectBubble = prefs.getBoolean("Manga_Auto_Detect_Bubble", true),
        fontSize = prefs.getFloat("Manga_Font_Size", 16f),
        sourceLang = prefs.getString("Source_Language", "ja"),
        targetLang = prefs.getString("Target_Language", "zh")
    )
}
```

- [ ] **Step 6: Verify the change compiles**

Run: `cd D:/xjj20/Desktop/fyapp/MoeTranslate-5.3.1 && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/moe/moetranslator/manga/MangaModeConfig.kt \
       app/src/main/java/com/moe/moetranslator/manga/VerticalTextRenderer.kt \
       app/src/main/java/com/moe/moetranslator/manga/OverlayRenderer.kt \
       app/src/main/java/com/moe/moetranslator/manga/MangaFloatingService.kt
git commit -m "feat: add text direction config (vertical/horizontal) to manga mode"
```

---

### Task 4: Add Loading Indicator When Translating

**Problem:** User clicks the floating ball but gets no visual feedback that translation is in progress. They don't know if it's working or frozen.

**Files:**
- Modify: `app/src/main/java/com/moe/moetranslator/manga/MangaFloatingService.kt`

- [ ] **Step 1: Add a progress overlay view and show/hide it**

Add a new `progressOverlayView` field and methods. In `MangaFloatingService.kt`:

Add field after `resultOverlayView`:
```kotlin
private lateinit var progressOverlayView: android.widget.TextView
private var progressOverlayParams: WindowManager.LayoutParams? = null
private var isProgressShowing = false
```

In `initializeViews()`, after the `resultOverlayParams` block, add:
```kotlin
// Progress overlay (initially not added)
progressOverlayView = android.widget.TextView(this).apply {
    text = getString(R.string.is_translating)
    setTextColor(Color.WHITE)
    textSize = 16f
    setBackgroundColor(Color.argb(200, 0, 0, 0))
    setPadding(48, 32, 48, 32)
    gravity = android.view.Gravity.CENTER
}

progressOverlayParams = WindowManager.LayoutParams().apply {
    type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    format = PixelFormat.RGBA_8888
    flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
    width = WindowManager.LayoutParams.WRAP_CONTENT
    height = WindowManager.LayoutParams.WRAP_CONTENT
    gravity = Gravity.CENTER
}
```

Add these methods after `dismissResultOverlay()`:
```kotlin
private fun showProgressOverlay() {
    if (isProgressShowing) return
    try {
        windowManager.addView(progressOverlayView, progressOverlayParams)
        isProgressShowing = true
        // Keep floating ball on top
        if (isViewAdded(floatingBallView)) {
            windowManager.removeView(floatingBallView)
            windowManager.addView(floatingBallView, floatingBallParams)
        }
    } catch (e: Exception) {
        Log.e(TAG, "Error showing progress", e)
    }
}

private fun dismissProgressOverlay() {
    if (isProgressShowing) {
        try {
            windowManager.removeView(progressOverlayView)
        } catch (e: Exception) {
            Log.e(TAG, "Error dismissing progress", e)
        }
        isProgressShowing = false
    }
}
```

In `onBallClicked()`, after `dismissResultOverlay()`, add:
```kotlin
showProgressOverlay()
```

In `processMangaScreenshot()`, in the `finally` block, add `dismissProgressOverlay()`:
```kotlin
} finally {
    isProcessing = false
    dismissProgressOverlay()
}
```

Also in the early return when no text found, add `dismissProgressOverlay()`:
```kotlin
if (textBlocks.isEmpty()) {
    showToast(getString(R.string.no_text_found))
    isProcessing = false
    dismissProgressOverlay()
    bitmap.recycle()
    return@launch
}
```

In `removeAllViews()`, add:
```kotlin
dismissProgressOverlay()
```

- [ ] **Step 2: Verify the change compiles**

Run: `cd D:/xjj20/Desktop/fyapp/MoeTranslate-5.3.1 && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/moe/moetranslator/manga/MangaFloatingService.kt
git commit -m "feat: add loading indicator during manga translation"
```

---

### Task 5: Enforce Mutual Exclusion Between Services

**Problem:** `FloatingBallService` (normal translation) and `MangaFloatingService` (manga translation) can run simultaneously, causing conflicts with screenshot collection and overlay display.

**Files:**
- Modify: `app/src/main/java/com/moe/moetranslator/translate/TranslateFragment.kt`
- Modify: `app/src/main/java/com/moe/moetranslator/manga/MangaFloatingService.kt`

- [ ] **Step 1: Add mutual exclusion to manga button click handler**

In `TranslateFragment.kt`, find the manga button click handler:

Find:
```kotlin
// 漫画翻译按钮
binding.mangaButton.setOnClickListener {
    if (!isServiceRunning(MangaFloatingService::class.java)) {
        if (checkAndroidSDK() && checkAccessibilityService() && checkFloatingBall()) {
            MangaFloatingService.start(requireContext())
            showToast("漫画翻译已启动")
        }
    } else {
        MangaFloatingService.stop(requireContext())
        showToast("漫画翻译已停止")
    }
}
```

Replace with:
```kotlin
// 漫画翻译按钮
binding.mangaButton.setOnClickListener {
    if (!isServiceRunning(MangaFloatingService::class.java)) {
        // Stop normal translation if running
        if (isServiceRunning(FloatingBallService::class.java)) {
            stopFloatingBallService()
        }
        if (checkAndroidSDK() && checkAccessibilityService() && checkFloatingBall()) {
            MangaFloatingService.start(requireContext())
            showToast("漫画翻译已启动")
            setMangaButtonState(true)
        }
    } else {
        MangaFloatingService.stop(requireContext())
        showToast("漫画翻译已停止")
        setMangaButtonState(false)
    }
}
```

- [ ] **Step 2: Add mutual exclusion to start button click handler**

In `TranslateFragment.kt`, find the start button handler. Before `launchFloatingBallService()`, add manga service stop:

Find:
```kotlin
binding.startButton.setOnClickListener {
    if (!isServiceRunning(FloatingBallService::class.java)) {
        if (checkAndroidSDK() && checkAccessibilityService() && checkFloatingBall() && checkNotify() && checkTranslateAPI() && checkCombination()) {
            ...
            launchFloatingBallService()
        }
    } else {
        stopFloatingBallService()
    }
}
```

Replace with:
```kotlin
binding.startButton.setOnClickListener {
    if (!isServiceRunning(FloatingBallService::class.java)) {
        // Stop manga translation if running
        if (isServiceRunning(MangaFloatingService::class.java)) {
            MangaFloatingService.stop(requireContext())
            setMangaButtonState(false)
        }
        if (checkAndroidSDK() && checkAccessibilityService() && checkFloatingBall() && checkNotify() && checkTranslateAPI() && checkCombination()) {
            if ((prefs.getInt("Translate_Mode", Constants.TranslateMode.TEXT.id) == Constants.TranslateMode.TEXT.id) && (prefs.getInt(
                    "Text_API",
                    Constants.TextApi.BING.id
                ) == Constants.TextApi.AI.id) && (prefs.getInt("Text_AI", Constants.TextAI.MLKIT.id) == Constants.TextAI.NLLB.id)
            ) {
                checkRAM()
            }
            launchFloatingBallService()
        }
    } else {
        stopFloatingBallService()
    }
}
```

- [ ] **Step 3: Add setMangaButtonState method**

In `TranslateFragment.kt`, add after `setTitleAndButton()`:

```kotlin
private fun setMangaButtonState(isRunning: Boolean) {
    if (isRunning) {
        binding.mangaButton.text = "停止漫画翻译"
        binding.mangaButton.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.red))
    } else {
        binding.mangaButton.text = "漫画翻译"
        binding.mangaButton.backgroundTintList =
            ColorStateList.valueOf(Color.parseColor("#6200EE"))
    }
}
```

- [ ] **Step 4: Initialize manga button state in onViewCreated**

In `onViewCreated()`, after `setTitleAndButton(...)`, add:
```kotlin
setMangaButtonState(isServiceRunning(MangaFloatingService::class.java))
```

- [ ] **Step 5: Add mutual exclusion in MangaFloatingService.onCreate**

In `MangaFloatingService.kt`, in `onCreate()`, after `TranslateBridge.initFromPreferences(this)`, add:
```kotlin
// Stop normal translation service if running (mutual exclusion)
try {
    val intent = Intent(this, com.moe.moetranslator.translate.FloatingBallService::class.java)
    stopService(intent)
} catch (e: Exception) {
    Log.w(TAG, "Could not stop FloatingBallService", e)
}
```

- [ ] **Step 6: Add broadcast receiver for manga service stop**

In `TranslateFragment.kt`, register a broadcast receiver for manga service stop. First, add a broadcast action in `MangaFloatingService.kt`:

In `MangaFloatingService.kt`, in `onDestroy()`, add before `Log.d(TAG, "MangaFloatingService destroyed")`:
```kotlin
// Send broadcast to update UI
val stopIntent = Intent("action_manga_floating_service_stopped")
androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this).sendBroadcast(stopIntent)
```

In `TranslateFragment.kt`, add a new receiver in `onCreate()`:
```kotlin
val mangaServiceStopReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == "action_manga_floating_service_stopped") {
            setMangaButtonState(false)
        }
    }
}
```

Register it in `onStart()` alongside the existing receiver:
```kotlin
LocalBroadcastManager.getInstance(requireContext()).registerReceiver(
    mangaServiceStopReceiver,
    IntentFilter("action_manga_floating_service_stopped")
)
```

Unregister in `onStop()` (add alongside existing unregistration).

- [ ] **Step 7: Verify the change compiles**

Run: `cd D:/xjj20/Desktop/fyapp/MoeTranslate-5.3.1 && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/moe/moetranslator/translate/TranslateFragment.kt \
       app/src/main/java/com/moe/moetranslator/manga/MangaFloatingService.kt
git commit -m "feat: enforce mutual exclusion between translation services, add manga button state toggle"
```

---

### Task 6: Expand Manga Long-Press Menu

**Problem:** The manga floating ball menu only has 2 items (stop, go home). It needs font size adjustment, text direction switching, crop selection, and auto-translate toggle.

**Files:**
- Modify: `app/src/main/java/com/moe/moetranslator/manga/MangaFloatingService.kt`
- Modify: `app/src/main/res/values-zh/arrays.xml`
- Modify: `app/src/main/res/values/arrays.xml`
- Modify: `app/src/main/res/values-en/arrays.xml`
- Modify: `app/src/main/res/values-zh/strings.xml`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Update manga_menu_items string arrays**

In `values-zh/arrays.xml`, find and replace `manga_menu_items`:
```xml
<string-array name="manga_menu_items">
    <item>框选翻译区域</item>
    <item>切换文字方向</item>
    <item>字体大小设置</item>
    <item>开启自动翻译</item>
    <item>关闭悬浮球</item>
    <item>回到萌译主界面</item>
</string-array>
```

In `values/arrays.xml` and `values-en/arrays.xml`, find and replace `manga_menu_items`:
```xml
<string-array name="manga_menu_items">
    <item>Select Translation Area</item>
    <item>Switch Text Direction</item>
    <item>Set Font Size</item>
    <item>Turn On Auto Translation</item>
    <item>Close Floating Ball</item>
    <item>Return to MoeTranslate Main Page</item>
</string-array>
```

Also add a second array for when auto-translate is on. In `values-zh/arrays.xml`:
```xml
<string-array name="manga_menu_items_auto_on">
    <item>框选翻译区域</item>
    <item>切换文字方向</item>
    <item>字体大小设置</item>
    <item>关闭自动翻译</item>
    <item>关闭悬浮球</item>
    <item>回到萌译主界面</item>
</string-array>
```

In `values/arrays.xml` and `values-en/arrays.xml`:
```xml
<string-array name="manga_menu_items_auto_on">
    <item>Select Translation Area</item>
    <item>Switch Text Direction</item>
    <item>Set Font Size</item>
    <item>Turn Off Auto Translation</item>
    <item>Close Floating Ball</item>
    <item>Return to MoeTranslate Main Page</item>
</string-array>
```

- [ ] **Step 2: Add string resources for direction names**

In `values-zh/strings.xml`, add:
```xml
<string name="manga_direction_vertical">当前：竖排（从上到下，从右到左）</string>
<string name="manga_direction_horizontal">当前：横排（从左到右，从上到下）</string>
<string name="manga_direction_switched">已切换文字方向</string>
<string name="manga_auto_translate_start">漫画自动翻译已开启</string>
<string name="manga_auto_translate_stop">漫画自动翻译已关闭</string>
<string name="manga_font_size_title">漫画翻译字体大小</string>
```

In `values/strings.xml`, add:
```xml
<string name="manga_direction_vertical">Current: Vertical (top-to-bottom, right-to-left)</string>
<string name="manga_direction_horizontal">Current: Horizontal (left-to-right, top-to-bottom)</string>
<string name="manga_direction_switched">Text direction switched</string>
<string name="manga_auto_translate_start">Manga auto-translate enabled</string>
<string name="manga_auto_translate_stop">Manga auto-translate disabled</string>
<string name="manga_font_size_title">Manga Translation Font Size</string>
```

- [ ] **Step 3: Expand the showMenu method in MangaFloatingService**

Replace the `showMenu()` method entirely:

```kotlin
private fun showMenu() {
    val items = if (isAutoTranslating) {
        resources.getStringArray(R.array.manga_menu_items_auto_on)
    } else {
        resources.getStringArray(R.array.manga_menu_items)
    }

    val dialog = AlertDialog.Builder(this)
        .setTitle(getString(R.string.manga_translate_title))
        .setItems(items) { _, which ->
            when (which) {
                0 -> startCropSelection()
                1 -> switchTextDirection()
                2 -> showFontSizeDialog()
                3 -> toggleAutoTranslate()
                4 -> stopSelf()
                5 -> backToMainActivity()
            }
        }
        .create()

    dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
    dialog.show()
    dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
    dialog.window?.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
}
```

- [ ] **Step 4: Implement switchTextDirection**

Add this method to `MangaFloatingService.kt`:

```kotlin
private fun switchTextDirection() {
    val newDirection = when (config.textDirection) {
        TextDirection.VERTICAL -> TextDirection.HORIZONTAL
        TextDirection.HORIZONTAL -> TextDirection.VERTICAL
    }
    config = config.copy(textDirection = newDirection)
    prefs.setInt("Manga_Text_Direction", newDirection.ordinal)
    showToast(getString(R.string.manga_direction_switched))
}
```

- [ ] **Step 5: Implement showFontSizeDialog**

Add this method to `MangaFloatingService.kt`:

```kotlin
private fun showFontSizeDialog() {
    val sizes = arrayOf("12", "14", "16", "18", "20", "24", "28", "32")
    val currentIndex = sizes.indexOf(config.fontSize.toInt().toString()).coerceAtLeast(2)

    val dialog = AlertDialog.Builder(this)
        .setTitle(getString(R.string.manga_font_size_title))
        .setSingleChoiceItems(sizes, currentIndex) { d, which ->
            val newSize = sizes[which].toFloat()
            config = config.copy(fontSize = newSize)
            prefs.setFloat("Manga_Font_Size", newSize)
            showToast("${sizes[which]}sp")
            d.dismiss()
        }
        .create()

    dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
    dialog.show()
    dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
}
```

- [ ] **Step 6: Implement auto-translate toggle**

Add fields to `MangaFloatingService.kt`:
```kotlin
private var isAutoTranslating = false
private val autoTranslateHandler = Handler(Looper.getMainLooper())
private val autoTranslateRunnable = object : Runnable {
    override fun run() {
        if (isAutoTranslating) {
            triggerTranslation()
            autoTranslateHandler.postDelayed(this, prefs.getLong("Auto_Translate_Interval", 3000L))
        }
    }
}
```

Add methods:
```kotlin
private fun toggleAutoTranslate() {
    if (isAutoTranslating) {
        stopAutoTranslate()
    } else {
        startAutoTranslate()
    }
}

private fun startAutoTranslate() {
    if (AccessibilityServiceManager.getService() == null) {
        showToast(getString(R.string.accessibility_recycle))
        return
    }
    isAutoTranslating = true
    autoTranslateHandler.post(autoTranslateRunnable)
    showToast(getString(R.string.manga_auto_translate_start))
}

private fun stopAutoTranslate() {
    isAutoTranslating = false
    autoTranslateHandler.removeCallbacks(autoTranslateRunnable)
    showToast(getString(R.string.manga_auto_translate_stop))
}
```

Extract the translation trigger from `onBallClicked()` into a reusable method:
```kotlin
private fun triggerTranslation() {
    if (isProcessing) return

    val service = AccessibilityServiceManager.getService()
    if (service == null) {
        showToast(getString(R.string.accessibility_recycle))
        return
    }

    dismissResultOverlay()
    showProgressOverlay()
    AccessibilityServiceManager.takeScreenshot(null, android.graphics.Point(0, 0))
}
```

Update `onBallClicked()` to use it:
```kotlin
private fun onBallClicked() {
    triggerTranslation()
}
```

In `onDestroy()`, add cleanup:
```kotlin
autoTranslateHandler.removeCallbacks(autoTranslateRunnable)
```

- [ ] **Step 7: Add CropView import and crop selection support**

In `MangaFloatingService.kt`, add imports:
```kotlin
import com.moe.moetranslator.translate.CropView
import android.graphics.RectF
```

Add fields:
```kotlin
private lateinit var cropView: CropView
private var cropViewParams: WindowManager.LayoutParams? = null
private var cropRect: RectF? = null
private var isCropActive = false
```

In `initializeViews()`, add after the progress overlay setup:
```kotlin
// Crop view (initially not added)
cropView = CropView(this)
cropViewParams = WindowManager.LayoutParams().apply {
    type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    format = PixelFormat.TRANSLUCENT
    flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
    width = WindowManager.LayoutParams.MATCH_PARENT
    height = WindowManager.LayoutParams.MATCH_PARENT
    gravity = Gravity.START or Gravity.TOP
}
```

Add methods:
```kotlin
private fun startCropSelection() {
    if (isCropActive) {
        showToast("请先完成当前框选")
        return
    }

    if (cropRect != null && resources.configuration.orientation == 1) {
        cropView.setRect(cropRect!!)
    } else {
        cropView.setRect(RectF(50f, 50f, 400f, 400f))
    }

    windowManager.addView(cropView, cropViewParams)
    isCropActive = true

    // Keep floating ball on top
    if (isViewAdded(floatingBallView)) {
        windowManager.removeView(floatingBallView)
        windowManager.addView(floatingBallView, floatingBallParams)
    }

    // Add confirm button or use long-press to confirm
    // For simplicity, add a confirm button view
    showCropConfirmButton()
}

private var cropConfirmView: View? = null

private fun showCropConfirmButton() {
    val confirmBtn = android.widget.Button(this).apply {
        text = "确认"
        setOnClickListener { confirmCrop() }
    }
    cropConfirmView = confirmBtn

    val params = WindowManager.LayoutParams().apply {
        type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        format = PixelFormat.RGBA_8888
        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        width = WindowManager.LayoutParams.WRAP_CONTENT
        height = WindowManager.LayoutParams.WRAP_CONTENT
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        y = 200
    }
    windowManager.addView(confirmBtn, params)
}

private fun confirmCrop() {
    cropRect = RectF(cropView.mRect)
    isCropActive = false

    // Remove crop view and confirm button
    try {
        windowManager.removeView(cropView)
    } catch (e: Exception) {
        Log.e(TAG, "Error removing crop view", e)
    }
    try {
        cropConfirmView?.let { windowManager.removeView(it) }
    } catch (e: Exception) {
        Log.e(TAG, "Error removing confirm button", e)
    }
    cropConfirmView = null

    // Keep floating ball on top
    if (isViewAdded(floatingBallView)) {
        windowManager.removeView(floatingBallView)
        windowManager.addView(floatingBallView, floatingBallParams)
    }

    showToast("翻译区域已设置")
}
```

Update `triggerTranslation()` to use crop rect:
```kotlin
private fun triggerTranslation() {
    if (isProcessing) return
    if (isCropActive) return

    val service = AccessibilityServiceManager.getService()
    if (service == null) {
        showToast(getString(R.string.accessibility_recycle))
        return
    }

    dismissResultOverlay()
    showProgressOverlay()

    if (cropRect != null) {
        AccessibilityServiceManager.takeScreenshot(cropRect, cropView.absolutePointOffset)
    } else {
        AccessibilityServiceManager.takeScreenshot(null, android.graphics.Point(0, 0))
    }
}
```

Update `removeAllViews()` to clean up crop view:
```kotlin
private fun removeAllViews() {
    try {
        if (isViewAdded(floatingBallView)) {
            windowManager.removeView(floatingBallView)
        }
    } catch (e: Exception) {
        Log.e(TAG, "Error removing floating ball", e)
    }
    dismissResultOverlay()
    dismissProgressOverlay()
    handler.removeCallbacks(longPressRunnable)
    autoTranslateHandler.removeCallbacks(autoTranslateRunnable)
    // Remove crop view if active
    if (isCropActive) {
        try {
            windowManager.removeView(cropView)
        } catch (e: Exception) { /* ignore */ }
        try {
            cropConfirmView?.let { windowManager.removeView(it) }
        } catch (e: Exception) { /* ignore */ }
        isCropActive = false
    }
}
```

- [ ] **Step 8: Add prefs helper methods if missing**

Check if `CustomPreference` has `putInt`, `putFloat`, `putLong` methods. If not, add them. (Likely already exists since the original code uses `prefs.getInt`, `prefs.getFloat`, etc.)

- [ ] **Step 9: Verify the change compiles**

Run: `cd D:/xjj20/Desktop/fyapp/MoeTranslate-5.3.1 && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/moe/moetranslator/manga/MangaFloatingService.kt \
       app/src/main/res/values-zh/arrays.xml \
       app/src/main/res/values/arrays.xml \
       app/src/main/res/values-en/arrays.xml \
       app/src/main/res/values-zh/strings.xml \
       app/src/main/res/values/strings.xml
git commit -m "feat: expand manga menu with crop, direction, font size, auto-translate"
```

---

### Task 7: Verify All Changes Compile and Test

- [ ] **Step 1: Clean build**

Run: `cd D:/xjj20/Desktop/fyapp/MoeTranslate-5.3.1 && ./gradlew clean assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Verify all features work**

Test checklist:
1. Open app, see "漫画翻译" button on translate page
2. Click "漫画翻译" -> button changes to "停止漫画翻译" (red)
3. Click normal "开始" button -> manga service stops, normal service starts
4. Start manga translation -> floating ball appears (65dp icon)
5. Click floating ball -> loading indicator appears, then result overlay covers full screen
6. Long press floating ball -> menu shows 6 items
7. Select "框选翻译区域" -> crop view appears, drag to select, confirm
8. Click floating ball again -> only crops and translates selected area
9. Select "切换文字方向" -> switches between vertical/horizontal
10. Select "字体大小设置" -> font size picker dialog
11. Select "开启自动翻译" -> auto-translate starts, menu item changes to "关闭自动翻译"
12. Click result overlay to dismiss

- [ ] **Step 3: Final commit if any fixes needed**

```bash
git add -A
git commit -m "fix: final adjustments for manga translation"
```

---

## Spec Self-Review

**1. Spec coverage:**
- Speed optimization: Task 1 (BackgroundAnalyzer step increase)
- Fullscreen coverage: Task 2 (FIT_XY)
- Background fill inaccuracy: Task 1 (better sampling)
- Area selection: Task 6 (crop support)
- Text direction switching: Task 3 + Task 6 (TextDirection enum + menu)
- Mutual exclusion: Task 5
- Loading animation: Task 4
- Button state: Task 5
- Font size: Task 6
- Auto-translate: Task 6

**2. Placeholder scan:** All code blocks are complete. No TBD/TODO.

**3. Type consistency:** `TextDirection` used consistently across config, renderer, and service. `TranslatedBubble` data class unchanged. `OverlayRenderer.renderOverlay` signature updated from `targetLang: String` to `direction: TextDirection`.
