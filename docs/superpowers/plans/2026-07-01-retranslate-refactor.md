# 重翻流程重构 — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 提取重复代码为公共方法，修复引擎初始化漏洞，加 OCR 互斥锁，确保重翻和正常翻译共用同一套翻译管线。

**Architecture:** `TranslateUtils` 作为翻译公共层；`DetectionBridge.ocrToBubbleRegions()` 作为 OCR 结果公共转换；`OcrLock` 单例保护 PP-OCRv5 引擎互斥。

**Tech Stack:** Kotlin, Android, ONNX Runtime (PP-OCRv5), OkHttp (OpenAI), Room

## Global Constraints

- minSdk 29, targetSdk 35, arm64-v8a only
- 所有日志使用 LogCollector
- 弹窗使用 android.app.AlertDialog
- 引擎 prefs key: 管理视图 `history_retranslate_engine`（值: PP_OCR_V5 / MANGA_OCR / MLKIT）
- Service prefs key: `Manga_Det_Model`（det）, `Manga_Rec_Model`（ocr）
- 重翻使用 `history_retranslate_engine`，翻译 API 使用当前主配置（`Text_API` / `Text_AI`）

---

## 当前漏洞清单

| # | 漏洞 | 严重性 |
|---|------|--------|
| 1 | `translateBubblesBatch` 在 MangaViewerActivity 完整复制一份，缺 cleanOcrText/isSymbolOnlyText/CountDownLatch 超时/非AI顺序翻译 | P0 |
| 2 | `createTranslatorFromPrefs` 完整复制一份 | P1 |
| 3 | 引擎初始化只调 `PPOcrV5Engine.initialize()`，选了 CTD/MangaOcr/MLKit 不初始化 | P0 |
| 4 | prefs key 读的是 `Manga_Det_Engine`/`Manga_Ocr_Engine`，但 Service 用的是 `Manga_Det_Model`/`Manga_Rec_Model`，永远读不到正确值 | P0 |
| 5 | 没读 `history_retranslate_engine`（管理视图独立引擎配置），用户设置完全被忽略 | P0 |
| 6 | 没有 OCR 互斥锁，Service 和重翻同时跑会崩溃 | P1 |
| 7 | `history_retranslate_engine` 只存 OCR 引擎名，det 引擎硬编码错误 | P1 |

---

### Task 1: OcrLock — 单例互斥锁

**Files:**
- Create: `app/src/main/java/com/moe/moetranslator/manga/OcrLock.kt`

```kotlin
package com.moe.moetranslator.manga

object OcrLock {
    @Volatile var isRunning = false
        private set

    fun tryAcquire(): Boolean {
        if (isRunning) return false
        synchronized(this) {
            if (isRunning) return false
            isRunning = true
            return true
        }
    }

    fun release() {
        synchronized(this) { isRunning = false }
    }
}
```

编译：`./gradlew assembleDebug` → commit。

---

### Task 2: DetectionBridge — ocrToBubbleRegions()

**Files:**
- Modify: `app/src/main/java/com/moe/moetranslator/manga/DetectionBridge.kt`

在 `runOCR()` 方法后新增：

```kotlin
/**
 * 将 OCR 结果转换为 BubbleRegion 列表（正常翻译和重翻共用）。
 */
fun ocrToBubbleRegions(ocrResults: List<TextBlockInfo>): List<BubbleRegion> {
    return ocrResults.filter { it.boundingBox != null }.map { block ->
        val rect = block.boundingBox!!
        val isVertical = block.isVertical ?: (rect.height() > rect.width())
        BubbleRegion(
            rect = rect,
            texts = listOf(block.text),
            fontSize = if (isVertical) rect.width().toFloat() else rect.height().toFloat(),
            direction = if (isVertical) TextDirection.VERTICAL_RL else TextDirection.HORIZONTAL,
            angle = block.angle,
            centerX = block.centerX,
            centerY = block.centerY
        )
    }
}
```

编译 → commit。

---

### Task 3: TranslateUtils — 翻译公共层

**Files:**
- Create: `app/src/main/java/com/moe/moetranslator/manga/TranslateUtils.kt`

从 MangaFloatingService 中复制 `translateBubbles()`、`translateBubblesBatch()`、`translateBubblesSequential()`、`cleanOcrText()`、`isSymbolOnlyText()` 到新文件，改为静态方法。

**关键签名：**

```kotlin
object TranslateUtils {

    suspend fun translateBubbles(
        translator: TranslationTextAPI,
        bubbles: List<BubbleRegion>,
        sourceLang: String,
        targetLang: String,
        prefs: CustomPreference,
        contextHistory: LinkedList<Pair<String, String>> = LinkedList(),
        forceContext: Boolean = false
    ): List<TranslatedBubble> { ... }

    private suspend fun translateBubblesBatch(
        translator: TranslationTextAPI,
        bubbles: List<Pair<BubbleRegion, String>>,
        sourceLang: String,
        targetLang: String,
        prefs: CustomPreference,
        contextHistory: LinkedList<Pair<String, String>>,
        forceContext: Boolean
    ): List<TranslatedBubble> { ... }

    private suspend fun translateBubblesSequential(
        translator: TranslationTextAPI,
        bubbles: List<Pair<BubbleRegion, String>>
    ): List<TranslatedBubble> { ... }

    fun cleanOcrText(text: String): String { ... }
    fun isSymbolOnlyText(text: String): Boolean { ... }
}
```

**注意：** `translateBubblesBatch` 中调用 `(translator as? OpenAITranslation)?.updateContext(...)` 并读取 `prefs.getString("game_context_count", "5")`。这些完全照搬，无需修改。

编译 → commit。

---

### Task 4: MangaFloatingService 替换为调公共方法

**Files:**
- Modify: `app/src/main/java/com/moe/moetranslator/manga/MangaFloatingService.kt`

1. 删除 `translateBubbles()`、`translateBubblesBatch()`、`translateBubblesSequential()`、`cleanOcrText()`、`isSymbolOnlyText()` 方法体，改为代理到 `TranslateUtils`：

```kotlin
private suspend fun translateBubbles(
    bubbles: List<BubbleRegion>, forceContext: Boolean = false
): List<TranslatedBubble> {
    if (translatorText == null) throw RuntimeException("Translation API not initialized")
    return TranslateUtils.translateBubbles(translatorText!!, bubbles,
        config.sourceLang, config.targetLang, prefs, contextHistory, forceContext)
}
```

2. `processMangaScreenshot` 中 OCR 前后加锁：

```kotlin
// Step 1: 检测+OCR（加锁保护引擎单例）
if (!OcrLock.tryAcquire()) {
    scheduleNextDetection(DETECT_INTERVAL_MS)
    isProcessing = false
    return@launch
}
try {
    // ... existing detection + OCR code ...
} finally {
    OcrLock.release()
}
```

编译 → commit。

---

### Task 5: MangaViewerActivity — 修复所有漏洞

**Files:**
- Modify: `app/src/main/java/com/moe/moetranslator/ui/history/MangaViewerActivity.kt`

**5a. 删除以下重复方法：**
- `translateBubblesBatch()` — 整段删除
- `createTranslatorFromPrefs()` — 整段删除

**5b. 替换重翻按钮逻辑：**

```kotlin
binding.btnRetranslate.setOnClickListener {
    val entry = getCurrentVariant()
    val originalPath = entry.originalImagePath
    if (originalPath.isNullOrEmpty() || !java.io.File(originalPath).exists()) {
        com.moe.moetranslator.utils.UiUtils.showToast(this, "原图不可用")
        return@setOnClickListener
    }

    lifecycleScope.launch {
        val cache = cacheManager.getCacheByHistoryId(entry.id)
        if (cache == null || cache.cropRight <= 0) {
            com.moe.moetranslator.utils.UiUtils.showToast(this@MangaViewerActivity, "无裁剪信息")
            return@launch
        }

        // 互斥检查
        if (!com.moe.moetranslator.manga.OcrLock.tryAcquire()) {
            com.moe.moetranslator.utils.UiUtils.showToast(this@MangaViewerActivity, "翻译进行中，请稍后")
            return@launch
        }

        binding.btnRetranslate.isEnabled = false
        com.moe.moetranslator.utils.UiUtils.showToast(this@MangaViewerActivity, "正在翻译...")
        try {
            val savedPosition = binding.viewPager.currentItem
            withContext(Dispatchers.IO) {
                // 1. Load + crop
                val original = BitmapFactory.decodeFile(originalPath) ?: throw Exception("原图加载失败")
                val cropRect = android.graphics.RectF(
                    cache.cropLeft.toFloat(), cache.cropTop.toFloat(),
                    cache.cropRight.toFloat(), cache.cropBottom.toFloat()
                )
                val cropped = ScreenshotManager.cropBitmap(original, cropRect, android.graphics.Point(0, 0))

                // 2. Read engine config FROM history_retranslate_engine
                val prefs = CustomPreference.getInstance(this@MangaViewerActivity)
                val engineName = prefs.getString("history_retranslate_engine", "PP_OCR_V5") ?: "PP_OCR_V5"
                val (detEngine, ocrEngine) = mapEngineToDetOcr(engineName)
                val sourceLang = prefs.getString("Manga_Source_Language", "ja") ?: "ja"
                val targetLang = prefs.getString("Manga_Target_Language", "zh") ?: "zh"

                // 3. Initialize engines based on selection
                initializeEngines(detEngine, ocrEngine)

                // 4. OCR via DetectionBridge
                val ocrResults = DetectionBridge.runOCR(cropped, sourceLang, detEngine.ordinal, ocrEngine.ordinal, this@MangaViewerActivity)
                if (ocrResults.isEmpty()) throw Exception("OCR 未识别到文字")

                // 5. Convert via shared method
                val bubbles = DetectionBridge.ocrToBubbleRegions(ocrResults)
                if (bubbles.isEmpty()) throw Exception("无有效文字区域")

                // 6. Create translator via shared factory
                val translator = createTranslator(prefs) ?: throw Exception("翻译器创建失败")

                // 7. Translate via shared utility
                val translatedBubbles = com.moe.moetranslator.manga.TranslateUtils.translateBubbles(
                    translator, bubbles, sourceLang, targetLang, prefs)
                if (translatedBubbles.isEmpty()) throw Exception("翻译失败")

                // 8. Render
                val rendered = OverlayRenderer.renderOverlay(
                    original = cropped, regions = translatedBubbles,
                    fontSize = prefs.getFloat("Manga_Font_Size", 16f),
                    autoFit = prefs.getBoolean("Manga_Auto_Font_Size", true),
                    textColor = prefs.getInt("Manga_Text_Color", android.graphics.Color.BLACK),
                    bgColor = prefs.getInt("Manga_BG_Color", android.graphics.Color.argb(200, 255, 255, 255))
                )

                // 9. Save — replace old variant
                val ocrTexts = bubbles.map { it.texts.first() }
                val numberedText = ocrTexts.mapIndexed { i, t -> "[${i + 1}] $t" }.joinToString("\n")
                val transText = translatedBubbles.mapIndexed { i, b -> "[${i + 1}] ${b.translatedText}" }.joinToString("\n")

                cacheManager.refreshCache(entry.id, CacheEntry(
                    type = TranslationCacheManager.MODE_MANGA,
                    sourceText = numberedText, translatedText = transText,
                    resultBitmap = rendered, sourceLang = sourceLang, targetLang = targetLang,
                    translatorName = "重翻", pHash = entry.pHash,
                    sessionId = "", lastSessionId = "",
                    cropLeft = cache.cropLeft, cropTop = cache.cropTop,
                    cropRight = cache.cropRight, cropBottom = cache.cropBottom,
                    isRetranslated = true,
                ), originalBitmap = original)
            }
            com.moe.moetranslator.utils.UiUtils.showToast(this@MangaViewerActivity, "重新翻译完成")
            val currentPos = savedPosition
            lifecycleScope.launch {
                val allEntries = cacheManager.getHistory(TranslationCacheManager.MODE_MANGA, limit = 500)
                pageGroups.clear()
                pageGroups.addAll(buildPageGroups(allEntries))
                binding.viewPager.adapter?.notifyDataSetChanged()
                updatePageIndicator(currentPos.coerceAtMost(pageGroups.size - 1))
            }
        } catch (e: Exception) {
            LogCollector.e(TAG, "Retranslate failed", e)
            com.moe.moetranslator.utils.UiUtils.showToast(this@MangaViewerActivity, e.message ?: "重新翻译失败")
        } finally {
            binding.btnRetranslate.isEnabled = true
            com.moe.moetranslator.manga.OcrLock.release()
        }
    }
}
```

**5c. 新增 helper 方法在 MangaViewerActivity 中：**

```kotlin
/**
 * 将 history_retranslate_engine 值映射为 DetEngine + OcrEngine。
 */
private fun mapEngineToDetOcr(engineName: String): Pair<DetEngine, OcrEngine> {
    return when (engineName) {
        "PP_OCR_V5" -> DetEngine.PP_OCR_V5 to OcrEngine.PPOcrV5
        "MANGA_OCR" -> DetEngine.PP_OCR_V5 to OcrEngine.MangaOcr
        "MLKIT"     -> DetEngine.MLKIT to OcrEngine.MLKit
        else        -> DetEngine.PP_OCR_V5 to OcrEngine.PPOcrV5
    }
}

/**
 * 根据选择的引擎按需初始化。
 */
private fun initializeEngines(det: DetEngine, ocr: OcrEngine) {
    // PP-OCRv5 engine (used by PP_OCR_V5 det and PPOcrV5/MangaOcr ocr)
    if (det == DetEngine.PP_OCR_V5 || ocr == OcrEngine.PPOcrV5 || ocr == OcrEngine.MangaOcr) {
        PPOcrV5Engine.initialize(this)
    }
    // CTD detector
    if (det == DetEngine.CTD) {
        CTDDetector.initialize(this)
    }
    // RT-DETR-V2 detector
    if (det == DetEngine.RT_DETR_V2) {
        ComicBubbleDetector.initialize(this)
    }
    // MangaOcr recognizer
    if (ocr == OcrEngine.MangaOcr) {
        MangaOcrRecognizer.ensureInitialized(this)
    }
    // MLKit needs no init
}

/**
 * 根据主配置创建翻译器（和 MangaFloatingService.initTranslator 逻辑一致）。
 */
private fun createTranslator(prefs: CustomPreference): TranslationTextAPI? {
    val textApi = prefs.getInt("Text_API", Constants.TextApi.BING.id)
    val textAI = prefs.getInt("Text_AI", Constants.TextAI.MLKIT.id)
    return when (textApi) {
        Constants.TextApi.AI.id -> when (textAI) {
            Constants.TextAI.MLKIT.id -> translationapi.mlkittranslation.MLKitTranslation()
            Constants.TextAI.NLLB.id -> translationapi.nllbtranslation.NLLBTranslation(this)
            else -> null
        }
        Constants.TextApi.BING.id -> translationapi.bingtranslation.BingTranslation()
        Constants.TextApi.NIUTRANS.id -> {
            val key = KeystoreManager.retrieveKey(this, "Niutrans") ?: return null
            translationapi.niutrans.NiuTranslation(key)
        }
        Constants.TextApi.OPENAI.id -> {
            val pl = ConfigurationStorage.loadAllProviders(prefs)
            val p = pl.getOrNull(prefs.getInt("OpenAI_Selected_Provider", 0)) ?: return null
            translationapi.openaitranslation.OpenAITranslation(
                apiKey = p.apiKey, baseUrl = p.baseUrl, model = p.modelName,
                systemPrompt = p.mangaSystemPrompt.ifEmpty { p.defaultMangaSystemPrompt },
                userPrompt = p.mangaUserPrompt.ifEmpty { p.defaultMangaUserPrompt },
                continuationType = p.continuationType,
                prefillContent = if (p.continuationType != com.moe.moetranslator.me.OpenAIProviderConfig.CONTINUATION_NONE) "[1] " else ""
            )
        }
        Constants.TextApi.VOLC.id -> {
            val a = KeystoreManager.retrieveKey(this, "Volc_ACCOUNT") ?: return null
            val s = KeystoreManager.retrieveKey(this, "Volc_SECRETKEY") ?: return null
            translationapi.volctranslation.VolcTranslation(a, s)
        }
        Constants.TextApi.AZURE.id -> {
            val k = KeystoreManager.retrieveKey(this, "Azure") ?: return null
            translationapi.azuretranslation.AzureTranslation(k)
        }
        Constants.TextApi.DEEPL.id -> {
            val h = KeystoreManager.retrieveKey(this, "DeepL_Translate_HOST") ?: return null
            val k = KeystoreManager.retrieveKey(this, "DeepL_Translate_APIKEY") ?: return null
            translationapi.deepltranslation.DeepLTranslation(h, k)
        }
        Constants.TextApi.BAIDU.id -> {
            val a = KeystoreManager.retrieveKey(this, "Baidu_Translate_ACCOUNT") ?: return null
            val s = KeystoreManager.retrieveKey(this, "Baidu_Translate_SECRETKEY") ?: return null
            translationapi.baidutranslation.BaiduTranslationText(a, s)
        }
        Constants.TextApi.TENCENT.id -> {
            val a = KeystoreManager.retrieveKey(this, "Tencent_Cloud_ACCOUNT") ?: return null
            val s = KeystoreManager.retrieveKey(this, "Tencent_Cloud_SECRETKEY") ?: return null
            translationapi.tencentcloud.TencentTranslationText(a, s)
        }
        else -> null
    }
}
```

编译 → commit。

---

### Task 6: 清理 dead code + import

**Files:**
- Modify: `app/src/main/java/com/moe/moetranslator/ui/history/MangaViewerActivity.kt`
- Modify: `app/src/main/java/com/moe/moetranslator/manga/MangaFloatingService.kt`

1. MangaViewerActivity: 删除不再使用的 import（`suspendCancellableCoroutine`, `kotlin.coroutines.resume` 等）
2. MangaFloatingService: 删除空的/废弃的 translate 方法体（已在 Task 4 改为代理）；删除 `retranslateReceiver` 等广播相关残留

编译 → commit。

---

### Task 7: 最终验证

```bash
./gradlew assembleDebug
```

确认 BUILD SUCCESSFUL，无新增 lint 警告。

---

## 涉及文件总览

| 文件 | 变更类型 | 说明 |
|------|---------|------|
| `manga/OcrLock.kt` | 新建 | OCR 引擎互斥锁 |
| `manga/TranslateUtils.kt` | 新建 | 翻译管线公共代码 |
| `manga/DetectionBridge.kt` | 修改 | + ocrToBubbleRegions() |
| `manga/MangaFloatingService.kt` | 修改 | translate* 改为代理；OCR 加锁 |
| `ui/history/MangaViewerActivity.kt` | 修改 | 删重复代码；修复引擎初始化；加锁；调公共方法 |

## 修复对照

| 漏洞 | 修复 |
|------|------|
| translateBubblesBatch 重复 | 提取到 TranslateUtils，Service 重翻都调它 |
| createTranslatorFromPrefs 重复 | 保留在 MangaViewerActivity（翻译器实例创建逻辑独立） |
| 引擎只初始化 PP-OCR | `initializeEngines()` 按 det+ocr 分别初始化 |
| prefs key 读错 | 改为读 `history_retranslate_engine` |
| 忽略管理视图引擎配置 | `mapEngineToDetOcr()` 正确映射 3 个选项 |
| 无 OCR 互斥锁 | OcrLock.tryAcquire() 保护 |
| detEngine 映射错误 | 从 `history_retranslate_engine` 映射出正确 det+ocr 对 |
