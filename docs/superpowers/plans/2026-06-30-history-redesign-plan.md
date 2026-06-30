# 历史记录页面重构 — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将历史记录页面从单一视图重构为"默认视图 + 管理视图"双视图架构，管理视图支持重新翻译、进程组下载、多尺寸管理。

**Architecture:** 数据库新增 `originalImagePath` 和 `isRetranslated` 字段；PageCacheEntity 新增完整 cropRect 字段；MangaFloatingService 新增 broadcast receiver 处理重翻请求；HistoryFragment 拆分为两个 Tab（默认/管理），管理视图通过 LocalBroadcast 与 Service 通信。

**Tech Stack:** Kotlin, Room (DB migration), Android Views + ViewBinding, LocalBroadcastManager, java.util.zip

## Global Constraints

- minSdk 29, targetSdk 35
- 仅 arm64-v8a
- 所有日志使用 LogCollector
- Dialog 使用 android.app.AlertDialog（禁止 MaterialAlertDialogBuilder）
- 重翻仅漫画翻译，游戏 tab 不显示引擎选择器
- 引擎选择器存储 key 独立于悬浮窗配置

---

### Task 1: 数据库 Migration

**Files:**
- Modify: `app/src/main/java/com/moe/moetranslator/data/HistoryEntity.kt`
- Modify: `app/src/main/java/com/moe/moetranslator/data/PageCacheEntity.kt`
- Modify: `app/src/main/java/com/moe/moetranslator/data/TranslationHistoryDatabase.kt`

**Interfaces:**
- Produces: `HistoryEntity.originalImagePath: String?`, `HistoryEntity.isRetranslated: Boolean`
- Produces: `PageCacheEntity.cropLeft: Int`, `cropTop: Int`, `cropRight: Int`, `cropBottom: Int`
- Produces: DB version 8 → 9

---

- [ ] **Step 1: HistoryEntity 新增字段**

```kotlin
// app/src/main/java/com/moe/moetranslator/data/HistoryEntity.kt
// 在现有字段末尾、updatedAt 之前添加：

@ColumnInfo(name = "original_image_path")
val originalImagePath: String? = null,

@ColumnInfo(name = "is_retranslated", defaultValue = "0")
val isRetranslated: Boolean = false,
```

最终字段顺序：`... sessionId, lastSessionId, originalImagePath, isRetranslated, updatedAt`

---

- [ ] **Step 2: PageCacheEntity 新增字段**

```kotlin
// app/src/main/java/com/moe/moetranslator/data/PageCacheEntity.kt
// 在现有 cropHeight 之后添加，保留旧 cropWidth/cropHeight 不动（Migration 不删列）：

@ColumnInfo(name = "crop_left", defaultValue = "0")
val cropLeft: Int = 0,
@ColumnInfo(name = "crop_top", defaultValue = "0")
val cropTop: Int = 0,
@ColumnInfo(name = "crop_right", defaultValue = "0")
val cropRight: Int = 0,
@ColumnInfo(name = "crop_bottom", defaultValue = "0")
val cropBottom: Int = 0,
```

---

- [ ] **Step 3: DB Migration 8 → 9**

```kotlin
// app/src/main/java/com/moe/moetranslator/data/TranslationHistoryDatabase.kt

// 修改版本号：
@Database(..., version = 9, ...)

// 新增 Migration：
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE translation_history ADD COLUMN original_image_path TEXT")
        database.execSQL("ALTER TABLE translation_history ADD COLUMN is_retranslated INTEGER NOT NULL DEFAULT 0")
        database.execSQL("ALTER TABLE page_cache ADD COLUMN crop_left INTEGER NOT NULL DEFAULT 0")
        database.execSQL("ALTER TABLE page_cache ADD COLUMN crop_top INTEGER NOT NULL DEFAULT 0")
        database.execSQL("ALTER TABLE page_cache ADD COLUMN crop_right INTEGER NOT NULL DEFAULT 0")
        database.execSQL("ALTER TABLE page_cache ADD COLUMN crop_bottom INTEGER NOT NULL DEFAULT 0")
    }
}

// 在 build() 链中添加：
.addMigrations(MIGRATION_8_9)
```

---

- [ ] **Step 4: 编译验证**

```bash
./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL

---

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/moe/moetranslator/data/HistoryEntity.kt \
        app/src/main/java/com/moe/moetranslator/data/PageCacheEntity.kt \
        app/src/main/java/com/moe/moetranslator/data/TranslationHistoryDatabase.kt
git commit -m "feat: DB migration 8→9 — originalImagePath, isRetranslated, cropRect fields"
```

---

### Task 2: 数据类字段更新

**Files:**
- Modify: `app/src/main/java/com/moe/moetranslator/data/TranslationCacheManager.kt`（CacheEntry, HistoryEntry, buildCacheResult, toHistoryEntry）

**Interfaces:**
- Consumes: Task 1 的 Entity 字段
- Produces: `CacheEntry(cropLeft, cropTop, cropRight, cropBottom)` 替代 `CropWidth/cropHeight`
- Produces: `HistoryEntry.originalImagePath`, `HistoryEntry.isRetranslated`

---

- [ ] **Step 1: CacheEntry 字段更新**

```kotlin
// TranslationCacheManager.kt 内，CacheEntry data class：
// 删除 cropWidth 和 cropHeight，替换为：

data class CacheEntry(
    // ... 现有字段不变 ...
    val cropLeft: Int = 0,     // 裁剪左边界（像素）
    val cropTop: Int = 0,      // 裁剪上边界
    val cropRight: Int = 0,    // 裁剪右边界
    val cropBottom: Int = 0,   // 裁剪下边界
)
```

---

- [ ] **Step 2: HistoryEntry 新增字段**

```kotlin
// TranslationCacheManager.kt 内，HistoryEntry data class：
// 在现有字段末尾添加：

data class HistoryEntry(
    // ... 现有字段不变 ...
    val variantIds: List<Long> = emptyList(),
    val originalImagePath: String? = null,  // 新增
    val isRetranslated: Boolean = false,     // 新增
)
```

---

- [ ] **Step 3: toHistoryEntry() 映射更新 + getCacheByHistoryId**

```kotlin
// TranslationCacheManager.kt — toHistoryEntry()
fun HistoryEntity.toHistoryEntry() = HistoryEntry(
    // ... 现有映射不变 ...
    variantCount = 1,
    variantIds = emptyList(),
    originalImagePath = originalImagePath,  // 新增
    isRetranslated = isRetranslated,         // 新增
)
```

    originalImagePath = originalImagePath,  // 新增
    isRetranslated = isRetranslated,         // 新增
)
```

在 HistoryDao 中新增查询方法（如不存在）：

```kotlin
// TranslationHistoryDao.kt
@Query("SELECT * FROM page_cache WHERE historyId = :historyId LIMIT 1")
suspend fun findCacheByHistoryId(historyId: Long): PageCacheEntity?
```

在 TranslationCacheManager 中新增包装方法：

```kotlin
suspend fun getCacheByHistoryId(historyId: Long): PageCacheEntity? = withContext(Dispatchers.IO) {
    dao.findCacheByHistoryId(historyId)
}
```

---

- [ ] **Step 4: 编译验证**

```bash
./gradlew assembleDebug
```

Expected: CacheEntry 调用处编译错误（Task 3 逐个修复）

---

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/moe/moetranslator/data/TranslationCacheManager.kt
git commit -m "feat: update CacheEntry/HistoryEntry with originalImagePath and cropRect"
```

---

### Task 3: saveToCache 适配原始截图 + cropRect

**Files:**
- Modify: `app/src/main/java/com/moe/moetranslator/data/TranslationCacheManager.kt`

**Interfaces:**
- Consumes: Task 2 的 CacheEntry 字段
- Produces: `saveToCache()` 新增 `originalBitmap: Bitmap?` 参数

---

- [ ] **Step 1: saveToCache 签名 + 原图保存**

```kotlin
// TranslationCacheManager.kt

suspend fun saveToCache(
    entry: CacheEntry,
    originalBitmap: Bitmap? = null,  // 新增参数 — 原始未裁剪截图
    createdAt: Long = System.currentTimeMillis()
) = withContext(Dispatchers.IO) {
    if (getMaxCacheCount() <= 0) return@withContext

    // ... 现有 sessionId 继承逻辑不变 ...

    // 保存原始截图
    var originalImagePath: String? = null
    if (originalBitmap != null) {
        val timestamp = System.currentTimeMillis()
        originalImagePath = saveBitmap(originalBitmap, "manga_original_${timestamp}.jpg")
    }

    // ... 现有图片保存、history 插入逻辑 ...

    val historyEntity = HistoryEntity(
        // ... 现有字段 ...
        originalImagePath = originalImagePath,  // 新增
        isRetranslated = entry.isRetranslated,   // 注意：CacheEntry 也需要加此字段
        // ...
    )

    // ... LRU 淘汰、cache 插入逻辑不变 ...
}
```

---

- [ ] **Step 2: CacheEntry 补加 isRetranslated**

```kotlin
data class CacheEntry(
    // ... 现有字段 ...
    val cropBottom: Int = 0,
    val isRetranslated: Boolean = false,  // 新增
)
```

---

- [ ] **Step 3: PageCacheEntity 保存 cropRect**

`saveToCache` 中插入 `PageCacheEntity` 时：

```kotlin
val cacheEntity = PageCacheEntity(
    historyId = historyId,
    pHash = entry.pHash,
    mode = entry.type,
    lastAccessedAt = System.currentTimeMillis(),
    createdAt = System.currentTimeMillis(),
    cropWidth = 0,         // 废弃，保留 0
    cropHeight = 0,        // 废弃，保留 0
    cropLeft = entry.cropLeft,      // 新增
    cropTop = entry.cropTop,        // 新增
    cropRight = entry.cropRight,    // 新增
    cropBottom = entry.cropBottom,  // 新增
)
```

---

- [ ] **Step 4: 修复所有 CacheEntry 构造调用处**

需修复的文件（搜索 `CacheEntry(`）：

- `MangaFloatingService.kt` — `saveToCache` 调用
- `FloatingBallService.kt` — `saveGameCache` / `saveToCache` 调用
- `TranslationCacheManager.kt` — `refreshCache` 内构造

将 `cropWidth = xxx, cropHeight = yyy` 改为 `cropLeft = 0, cropTop = 0, cropRight = xxx, cropBottom = yyy`

> 注意：现有调用只有宽高没偏移，暂填 left=0, top=0。cropRect 正确值在 Task 4 中由 MangaFloatingService 填入。

---

- [ ] **Step 5: 编译验证**

```bash
./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL

---

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/moe/moetranslator/data/TranslationCacheManager.kt \
        app/src/main/java/com/moe/moetranslator/manga/MangaFloatingService.kt \
        app/src/main/java/com/moe/moetranslator/translate/FloatingBallService.kt
git commit -m "feat: saveToCache supports originalBitmap and full cropRect"
```

---

### Task 4: MangaFloatingService 保存原始截图

**Files:**
- Modify: `app/src/main/java/com/moe/moetranslator/manga/MangaFloatingService.kt`

**Interfaces:**
- Consumes: Task 3 的 `saveToCache(originalBitmap)`
- Produces: 漫画翻译流程中保存原图

---

- [ ] **Step 1: 截图后立即保存原图**

在 MangaFloatingService 的 OCR 流程中，找到截图获取点（`takeScreenshotWithProvider` 到 OCR 之间），在裁剪前保留原图引用：

```kotlin
// MangaFloatingService.kt — processMangaScreenshot 或等效位置

// 截图获取到的全屏 Bitmap（裁剪前）
val fullScreenBitmap: Bitmap = ...

// 传入 saveToCache 时带上原图
saveToCache(
    entry = CacheEntry(
        type = MODE_MANGA,
        // ... 其他字段 ...
        cropLeft = cropRect.left.toInt(),
        cropTop = cropRect.top.toInt(),
        cropRight = cropRect.right.toInt(),
        cropBottom = cropRect.bottom.toInt(),
    ),
    originalBitmap = fullScreenBitmap  // 新增
)
```

---

- [ ] **Step 2: 编译验证**

```bash
./gradlew assembleDebug
```

---

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/moe/moetranslator/manga/MangaFloatingService.kt
git commit -m "feat: save original screenshot during manga translation"
```

---

### Task 5: MangaFloatingService — Broadcast Receiver + 重翻逻辑

**Files:**
- Modify: `app/src/main/java/com/moe/moetranslator/manga/MangaFloatingService.kt`

**Interfaces:**
- Consumes: Task 4 的原始截图保存
- Produces: `onRetranslateRequest(intent)` 方法，`registerRetranslateReceiver()` / `unregister`

---

- [ ] **Step 1: 定义 Broadcast action 常量**

```kotlin
// MangaFloatingService.kt companion object 中：
const val ACTION_RETRANSLATE_REQUEST = "com.moe.moetranslator.RETRANSLATE_REQUEST"
const val ACTION_RETRANSLATE_COMPLETE = "com.moe.moetranslator.RETRANSLATE_COMPLETE"
```

---

- [ ] **Step 2: 创建 receiver**

```kotlin
// MangaFloatingService.kt — 类成员变量
private var retranslateReceiver: BroadcastReceiver? = null

// onCreate 中注册
private fun registerRetranslateReceiver() {
    retranslateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_RETRANSLATE_REQUEST) return
            handleRetranslateRequest(intent)
        }
    }
    LocalBroadcastManager.getInstance(this)
        .registerReceiver(retranslateReceiver!!, IntentFilter(ACTION_RETRANSLATE_REQUEST))
}

// onDestroy 中注销
private fun unregisterRetranslateReceiver() {
    retranslateReceiver?.let {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(it)
    }
}
```

在 `onCreate` 末尾调用 `registerRetranslateReceiver()`，在 `onDestroy` 开头调用 `unregisterRetranslateReceiver()`。

---

- [ ] **Step 3: 实现重翻逻辑**

重翻 OCR 直接复用 Service 现有的 `runOcrPipeline()` 方法（MangaFloatingService 中已有的 OCR 入口），不创建独立 OCR 方法。

在调用前临时设置 `config.detEngine` / `config.ocrEngine` 为请求中指定的值，OCR 完成后恢复。

```kotlin
private fun handleRetranslateRequest(intent: Intent) {
    val originalImagePath = intent.getStringExtra("originalImagePath") ?: run {
        sendRetranslateComplete(success = false, errorMessage = "原图路径为空")
        return
    }
    val cropLeft = intent.getIntExtra("cropLeft", 0)
    val cropTop = intent.getIntExtra("cropTop", 0)
    val cropRight = intent.getIntExtra("cropRight", 0)
    val cropBottom = intent.getIntExtra("cropBottom", 0)
    val ocrEngineName = intent.getStringExtra("ocrEngine") ?: "PP_OCR_V5"
    val openaiProviderIndex = intent.getIntExtra("openaiProviderIndex", 0)

    if (isProcessing) {
        sendRetranslateComplete(success = false, errorMessage = "翻译进行中，请稍后")
        return
    }

    isProcessing = true
    lifecycleScope.launch {
        try {
            // 1. 加载原图
            val originalBitmap = BitmapFactory.decodeFile(originalImagePath)
            if (originalBitmap == null) {
                sendRetranslateComplete(success = false, errorMessage = "原图加载失败")
                isProcessing = false
                return@launch
            }

            // 2. 裁剪
            val cropRect = RectF(cropLeft.toFloat(), cropTop.toFloat(), cropRight.toFloat(), cropBottom.toFloat())
            val croppedBitmap = ScreenshotManager.cropBitmap(originalBitmap, cropRect, Point(0, 0))

            // 3. 临时切换引擎配置
            val savedDetEngine = config.detEngine
            val savedOcrEngine = config.ocrEngine
            when (ocrEngineName) {
                "MLKIT" -> { config.detEngine = DetEngine.MLKIT; config.ocrEngine = OcrEngine.MLKIT }
                "MANGA_OCR" -> { config.detEngine = DetEngine.PP_OCR_V5; config.ocrEngine = OcrEngine.MANGA_OCR }
                "PP_OCR_V5" -> { config.detEngine = DetEngine.PP_OCR_V5; config.ocrEngine = OcrEngine.PP_OCR_V5 }
            }

            // 4. OCR — 复用现有流程
            val ocrResults = runOcrOnBitmap(croppedBitmap)

            // 恢复引擎配置
            config.detEngine = savedDetEngine
            config.ocrEngine = savedOcrEngine

            if (ocrResults.isEmpty()) {
                sendRetranslateComplete(success = false, errorMessage = "OCR 未识别到文字")
                isProcessing = false
                return@launch
            }

            // 5. 翻译
            val providerList = ConfigurationStorage.loadAllProviders(prefs)
            val provider = providerList.getOrNull(openaiProviderIndex) ?: run {
                sendRetranslateComplete(success = false, errorMessage = "翻译提供商配置无效")
                isProcessing = false
                return@launch
            }
            val translator = OpenAITranslation(
                apiKey = provider.apiKey,
                baseUrl = provider.baseUrl,
                model = provider.modelName,
                systemPrompt = provider.mangaSystemPrompt.ifEmpty { provider.defaultMangaSystemPrompt },
                userPrompt = provider.mangaUserPrompt.ifEmpty { provider.defaultMangaUserPrompt },
                continuationType = provider.continuationType,
                prefillContent = if (provider.continuationType != OpenAIProviderConfig.CONTINUATION_NONE && provider.continuationType != OpenAIProviderConfig.CONTINUATION_JSON) "[1] " else ""
            )

            val sourceText = ocrResults.joinToString("\n") { "[${it.index}] ${it.text}" }
            var translatedText = ""
            translator.getTranslation(sourceText, config.sourceLang, config.targetLang, object : TranslationTextAPI.TranslationCallback {
                override fun onSuccess(translated: String) { translatedText = translated }
                override fun onFailure(e: Exception) { translatedText = "" }
            })

            if (translatedText.isEmpty()) {
                sendRetranslateComplete(success = false, errorMessage = "翻译失败")
                isProcessing = false
                return@launch
            }

            // 6. 渲染
            val renderer = OverlayRenderer(this@MangaFloatingService)
            val renderedBitmap = renderer.render(croppedBitmap, ocrResults, translatedText, config)

            // 7. 保存
            cacheManager.saveToCache(
                entry = CacheEntry(
                    type = TranslationCacheManager.MODE_MANGA,
                    sourceText = sourceText,
                    translatedText = translatedText,
                    resultBitmap = renderedBitmap,
                    sourceLang = config.sourceLang,
                    targetLang = config.targetLang,
                    translatorName = provider.modelName,
                    pHash = PerceptualHash.compute(croppedBitmap),
                    sessionId = "",
                    lastSessionId = currentSessionId,
                    cropLeft = cropLeft,
                    cropTop = cropTop,
                    cropRight = cropRight,
                    cropBottom = cropBottom,
                    isRetranslated = true,
                ),
                originalBitmap = originalBitmap,
            )

            sendRetranslateComplete(success = true)
        } catch (e: Exception) {
            LogCollector.e(TAG, "Retranslate failed", e)
            sendRetranslateComplete(success = false, errorMessage = e.message ?: "未知错误")
        } finally {
            isProcessing = false
        }
    }
}
```

> `runOcrOnBitmap(bitmap)` 是新增的轻量包装方法，从现有 OCR 流程中提取"加载图片 → 检测 → 识别"部分，返回 OCR 结果列表。签名：

```kotlin
private suspend fun runOcrOnBitmap(bitmap: Bitmap): List<OcrResult> {
    return when (config.detEngine) {
        DetEngine.MLKIT -> runMlKitDetection(bitmap)
        DetEngine.PP_OCR_V5 -> runPPOcrV5Detection(bitmap)
        // ... 其他引擎
        else -> emptyList()
    }
}
```

此方法的具体实现依赖现有 OCR 代码路径，实施时从 `processMangaScreenshot` 中提取共同的检测识别部分。

---

- [ ] **Step 4: 编译验证**

```bash
./gradlew assembleDebug
```

---

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/moe/moetranslator/manga/MangaFloatingService.kt
git commit -m "feat: MangaFloatingService retranslate broadcast receiver"
```

---

### Task 6: HistoryFragment — 视图 Tab + 引擎选择器

**Files:**
- Modify: `app/src/main/java/com/moe/moetranslator/ui/history/HistoryFragment.kt`
- Modify: `app/src/main/res/layout/fragment_history.xml`

**Interfaces:**
- Produces: 视图切换 Tab、引擎选择器 UI、`history_view_mode` / `history_ocr_engine` / `history_openai_provider_index` prefs

---

- [ ] **Step 1: fragment_history.xml 添加视图 Tab 和引擎选择器**

在现有 TabLayout (`historyTabLayout`) 下方、RecyclerView 上方添加：

```xml
<!-- fragment_history.xml — 在 historyTabLayout 之后添加 -->

<com.google.android.material.tabs.TabLayout
    android:id="@+id/viewModeTabLayout"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:background="?android:colorBackground"
    app:tabMode="fixed"
    app:tabGravity="fill" />

<LinearLayout
    android:id="@+id/engineSelectorLayout"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="horizontal"
    android:padding="8dp"
    android:visibility="gone">

    <Spinner
        android:id="@+id/spinnerOcrEngine"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:layout_marginEnd="8dp" />

    <Spinner
        android:id="@+id/spinnerTranslateApi"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1" />

</LinearLayout>
```

---

- [ ] **Step 2: HistoryFragment 初始化视图 Tab**

```kotlin
// HistoryFragment.kt — setupViewModeTabs()

private fun setupViewModeTabs() {
    binding.viewModeTabLayout.addTab(
        binding.viewModeTabLayout.newTab().setText("默认视图")
    )
    binding.viewModeTabLayout.addTab(
        binding.viewModeTabLayout.newTab().setText("管理视图")
    )

    // 恢复上次选择的视图
    val savedMode = prefs.getString("history_view_mode", "default")
    if (savedMode == "manage") {
        binding.viewModeTabLayout.selectTab(binding.viewModeTabLayout.getTabAt(1))
    }

    binding.viewModeTabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
        override fun onTabSelected(tab: TabLayout.Tab?) {
            val isManage = tab?.position == 1
            prefs.setString("history_view_mode", if (isManage) "manage" else "default")
            updateEngineSelectorVisibility()
            loadHistory()
        }
        override fun onTabUnselected(tab: TabLayout.Tab?) {}
        override fun onTabReselected(tab: TabLayout.Tab?) {}
    })
}
```

---

- [ ] **Step 3: 引擎选择器初始化**

```kotlin
// HistoryFragment.kt

private fun setupEngineSelectors() {
    // OCR 引擎 Spinner
    val ocrEngines = arrayOf("PP-OCRv5", "manga-ocr", "ML Kit")
    val ocrValues = arrayOf("PP_OCR_V5", "MANGA_OCR", "MLKIT")
    val savedOcr = prefs.getString("history_ocr_engine", "PP_OCR_V5")
    val ocrIdx = ocrValues.indexOfFirst { it == savedOcr }.coerceAtLeast(0)

    binding.spinnerOcrEngine.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, ocrEngines)
    binding.spinnerOcrEngine.setSelection(ocrIdx)
    binding.spinnerOcrEngine.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            prefs.setString("history_ocr_engine", ocrValues[position])
        }
        override fun onNothingSelected(parent: AdapterView<*>?) {}
    }

    // 翻译 API Spinner
    val providerList = ConfigurationStorage.loadAllProviders(prefs)
    val providerNames = providerList.map { "${it.modelName}" }
    if (providerNames.isEmpty()) {
        binding.spinnerTranslateApi.isEnabled = false
        return
    }
    val savedProviderIdx = prefs.getString("history_openai_provider_index", "0").toIntOrNull()?.coerceAtMost(providerNames.size - 1) ?: 0
    binding.spinnerTranslateApi.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, providerNames)
    binding.spinnerTranslateApi.setSelection(savedProviderIdx)
    binding.spinnerTranslateApi.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            prefs.setString("history_openai_provider_index", position.toString())
        }
        override fun onNothingSelected(parent: AdapterView<*>?) {}
    }
}

private fun updateEngineSelectorVisibility() {
    val isManageView = prefs.getString("history_view_mode", "default") == "manage"
    val isMangaTab = currentTab == TranslationCacheManager.MODE_MANGA
    binding.engineSelectorLayout.visibility = if (isManageView && isMangaTab) View.VISIBLE else View.GONE
}
```

在 `switchTab()` 中也调用 `updateEngineSelectorVisibility()`。

---

- [ ] **Step 4: 编译验证**

```bash
./gradlew assembleDebug
```

---

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/moe/moetranslator/ui/history/HistoryFragment.kt \
        app/src/main/res/layout/fragment_history.xml
git commit -m "feat: history view mode tabs + engine selector UI"
```

---

### Task 7: 设置弹窗 — 移除排列方式选项

**Files:**
- Modify: `app/src/main/java/com/moe/moetranslator/ui/history/HistoryFragment.kt`

---

- [ ] **Step 1: showHistorySettingsMenu 中移除排序选项**

在 `showHistorySettingsMenu` 方法中删除：
- `sortOptions` 数组
- `sortGroup` RadioGroup 创建代码
- `currentSortIdx` 计算
- `newSortIdx` 保存逻辑
- "排列方式" 标题 TextView

保留"显示方式"和"缓存数量"两部分不变。

---

- [ ] **Step 2: loadHistory 中排序改为读视图模式**

```kotlin
// 原来：
val sortByUpdated = prefs.getString("history_sort_mode", "created") == "updated"

// 改为：
val viewMode = prefs.getString("history_view_mode", "default")
val sortByUpdated = (viewMode == "default")
```

---

- [ ] **Step 3: 编译验证**

```bash
./gradlew assembleDebug
```

---

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/moe/moetranslator/ui/history/HistoryFragment.kt
git commit -m "refactor: remove sort option from settings, driven by view tab"
```

---

### Task 8: 默认视图 — 原文/译文切换

**Files:**
- Modify: `app/src/main/java/com/moe/moetranslator/ui/history/HistoryMangaGroupAdapter.kt`
- Modify: `app/src/main/res/layout/item_history_manga.xml`
- Modify: `app/src/main/res/layout/item_history_manga_list.xml`

**Interfaces:**
- Consumes: HistoryEntry.originalImagePath
- Produces: 卡片上原文/译文切换按钮

---

- [ ] **Step 1: item_history_manga.xml 添加切换按钮**

在缩略图区域添加一个半透明小按钮：

```xml
<!-- 在 ImageView 的同级或覆盖层添加 -->
<TextView
    android:id="@+id/btnToggleImage"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:text="📷"
    android:textSize="14sp"
    android:padding="6dp"
    android:background="#66000000"
    android:visibility="gone"
    android:clickable="true"
    android:focusable="true" />
```

同理更新 `item_history_manga_list.xml`。

---

- [ ] **Step 2: Adapter 中实现切换逻辑**

```kotlin
// HistoryMangaGroupAdapter 的 entry ViewHolder 中：
private var showingOriginal = false

fun bind(entry: HistoryEntry) {
    // ... 现有绑定 ...

    // 原文/译文切换按钮
    val hasOriginal = !entry.originalImagePath.isNullOrEmpty()
    btnToggleImage.visibility = if (hasOriginal) View.VISIBLE else View.GONE
    if (hasOriginal) {
        btnToggleImage.setOnClickListener {
            showingOriginal = !showingOriginal
            btnToggleImage.text = if (showingOriginal) "📄" else "📷"
            loadImage(if (showingOriginal) entry.originalImagePath else entry.imagePath, entry.thumbnailPath)
        }
    }
}

private fun loadImage(imagePath: String?, thumbnailPath: String?) {
    val path = imagePath ?: thumbnailPath
    if (path != null && File(path).exists()) {
        val bitmap = BitmapFactory.decodeFile(path)
        imageView.setImageBitmap(bitmap)
    }
}
```

---

- [ ] **Step 3: 编译验证**

```bash
./gradlew assembleDebug
```

---

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/moe/moetranslator/ui/history/HistoryMangaGroupAdapter.kt \
        app/src/main/res/layout/item_history_manga.xml \
        app/src/main/res/layout/item_history_manga_list.xml
git commit -m "feat: original/translated image toggle in default view cards"
```

---

### Task 9: 管理视图 — 卡片布局

**Files:**
- Modify: `app/src/main/java/com/moe/moetranslator/ui/history/HistoryGroupAdapter.kt`
- Modify: `app/src/main/res/layout/item_history_manga_session.xml`（或新建管理视图专用 layout）

**Interfaces:**
- Consumes: HistoryEntry.originalImagePath, isRetranslated
- Produces: 重翻角标、多尺寸下拉、操作按钮

---

- [ ] **Step 1: 管理视图用单独的 entry layout**

新建 `item_history_manga_manage.xml`，基于 `item_history_manga.xml`，新增：

```xml
<!-- 在卡片右上角 -->
<TextView
    android:id="@+id/tvRetranslateBadge"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:text="🔄x2"
    android:textSize="12sp"
    android:padding="4dp"
    android:background="#33FF9800"
    android:visibility="gone" />

<!-- 尺寸下拉 -->
<Spinner
    android:id="@+id/spinnerVariant"
    android:layout_width="wrap_content"
    android:layout_height="36dp"
    android:visibility="gone" />

<!-- 操作按钮行 -->
<LinearLayout
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="horizontal">

    <Button
        android:id="@+id/btnRetranslate"
        android:layout_width="0dp"
        android:layout_height="36dp"
        android:layout_weight="1"
        android:text="重新翻译"
        android:textSize="12sp" />

    <Button
        android:id="@+id/btnDeleteVariant"
        android:layout_width="0dp"
        android:layout_height="36dp"
        android:layout_weight="1"
        android:text="删除此尺寸"
        android:textSize="12sp"
        android:backgroundTint="#FF5722" />

</LinearLayout>
```

---

- [ ] **Step 2: Adapter 管理视图模式切换**

在 `HistoryGroupAdapter` 或 `HistoryMangaGroupAdapter` 中添加 `isManageView` 标志。

当 `isManageView = true` 时：
- 使用 `item_history_manga_manage.xml`
- 显示重翻角标：`tvRetranslateBadge.visibility = if (retranslateCount > 0) VISIBLE else GONE`
- 计算 `retranslateCount`：遍历该 pHash 组所有 variantIds，统计 `isRetranslated=true` 的数量
- 显示尺寸下拉：`if (variantIds.size > 1) { spinnerVariant.visibility = VISIBLE }`
- 绑定按钮回调（Task 10-11 实现）

多尺寸切换时更新显式的变体（thumbnail、尺寸文字、翻译结果文本）。

---

- [ ] **Step 3: 编译验证**

```bash
./gradlew assembleDebug
```

---

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/moe/moetranslator/ui/history/HistoryGroupAdapter.kt \
        app/src/main/res/layout/item_history_manga_manage.xml
git commit -m "feat: management view card layout with badge, variant selector, action buttons"
```

---

### Task 10: CropFragment — 重翻裁剪界面

**Files:**
- Create: `app/src/main/java/com/moe/moetranslator/ui/history/CropFragment.kt`
- Create: `app/src/main/res/layout/fragment_crop.xml`

**Interfaces:**
- Consumes: 原图路径（通过 arguments）
- Produces: `setFragmentResult("crop_result", bundleOf("cropLeft", "cropTop", "cropRight", "cropBottom"))`

---

- [ ] **Step 1: fragment_crop.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="#FF000000">

    <com.moe.moetranslator.translate.CropView
        android:id="@+id/cropView"
        android:layout_width="match_parent"
        android:layout_height="match_parent" />

    <Button
        android:id="@+id/btnConfirmCrop"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="bottom|center_horizontal"
        android:layout_marginBottom="32dp"
        android:text="确认裁剪"
        android:backgroundTint="#4CAF50" />

    <Button
        android:id="@+id/btnCancelCrop"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="top|start"
        android:layout_margin="16dp"
        android:text="取消"
        android:backgroundTint="#FF5722" />

</FrameLayout>
```

---

- [ ] **Step 2: CropFragment.kt**

```kotlin
package com.moe.moetranslator.ui.history

import android.graphics.BitmapFactory
import android.graphics.Point
import android.graphics.RectF
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.moe.moetranslator.databinding.FragmentCropBinding

class CropFragment : DialogFragment() {

    companion object {
        private const val ARG_IMAGE_PATH = "imagePath"
        private const val ARG_PRESET_CROP = "presetCrop"  // "left,top,right,bottom" 或 null

        fun newInstance(imagePath: String, presetCrop: RectF? = null): CropFragment {
            return CropFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_IMAGE_PATH, imagePath)
                    if (presetCrop != null) {
                        putString(ARG_PRESET_CROP, "${presetCrop.left},${presetCrop.top},${presetCrop.right},${presetCrop.bottom}")
                    }
                }
            }
        }
    }

    private var _binding: FragmentCropBinding? = null
    private val binding get() = _binding!!

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCropBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val imagePath = arguments?.getString(ARG_IMAGE_PATH) ?: run { dismiss(); return }
        val bitmap = BitmapFactory.decodeFile(imagePath) ?: run { dismiss(); return }

        // 设置 CropView 背景
        binding.cropView.setImageBitmap(bitmap)

        // 预设裁剪框
        val presetCropStr = arguments?.getString(ARG_PRESET_CROP)
        if (presetCropStr != null) {
            val parts = presetCropStr.split(",").map { it.toFloatOrNull() ?: 0f }
            if (parts.size == 4) {
                val rect = RectF(parts[0], parts[1], parts[2], parts[3])
                binding.cropView.setRect(rect)
            }
        } else {
            // 默认居中 80%×60%
            binding.cropView.setRectCentered(0.8f, 0.6f)
        }

        binding.btnConfirmCrop.setOnClickListener {
            val rect = binding.cropView.mRect
            val bundle = Bundle().apply {
                putInt("cropLeft", rect.left.toInt().coerceAtLeast(0))
                putInt("cropTop", rect.top.toInt().coerceAtLeast(0))
                putInt("cropRight", rect.right.toInt().coerceAtMost(bitmap.width))
                putInt("cropBottom", rect.bottom.toInt().coerceAtMost(bitmap.height))
            }
            setFragmentResult("crop_result", bundle)
            dismiss()
        }

        binding.btnCancelCrop.setOnClickListener {
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
```

---

- [ ] **Step 3: 编译验证**

```bash
./gradlew assembleDebug
```

---

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/moe/moetranslator/ui/history/CropFragment.kt \
        app/src/main/res/layout/fragment_crop.xml
git commit -m "feat: CropFragment for retranslation crop interface"
```

---

### Task 11: HistoryFragment — 重翻流程（裁剪 → 广播 → 刷新）

**Files:**
- Modify: `app/src/main/java/com/moe/moetranslator/ui/history/HistoryFragment.kt`

**Interfaces:**
- Consumes: Task 5 (Service broadcast), Task 10 (CropFragment)
- Produces: 完整的重翻入口流程

---

- [ ] **Step 1: 重翻按钮 onClick**

在管理视图卡片 adapter 的回调中添加：

```kotlin
// HistoryFragment.kt — setupRecyclerViews 中 adapter 新增回调：

onRetranslateClick = { entry ->
    startRetranslateFlow(entry)
},
onDeleteVariantClick = { entry ->
    showDeleteVariantDialog(entry)
}
```

---

- [ ] **Step 2: startRetranslateFlow 实现**

```kotlin
private fun startRetranslateFlow(entry: HistoryEntry) {
    // 检查 Service 是否运行
    if (!ServiceUtils.isServiceRunning(requireContext(), MangaFloatingService::class.java)) {
        Toast.makeText(requireContext(), "请先启动漫画翻译", Toast.LENGTH_SHORT).show()
        return
    }

    val originalPath = entry.originalImagePath
    if (originalPath.isNullOrEmpty() || !File(originalPath).exists()) {
        Toast.makeText(requireContext(), "原图不可用", Toast.LENGTH_SHORT).show()
        return
    }

    // 底部弹窗选择裁剪方式
    AlertDialog.Builder(requireContext())
        .setTitle("重新翻译")
        .setItems(arrayOf("用当前裁剪", "重新裁剪")) { _, which ->
            when (which) {
                0 -> openCropFragment(originalPath, entry)  // 用当前裁剪
                1 -> openCropFragment(originalPath, null)    // 重新裁剪
            }
        }
        .show()
}
```

---

- [ ] **Step 3: openCropFragment 实现**

```kotlin
private fun openCropFragment(imagePath: String, entry: HistoryEntry?) {
    val presetCrop: RectF? = if (entry != null) {
        // 从 PageCacheEntity 加载该 entry 的 cropRect
        lifecycleScope.launch {
            val cache = cacheManager.getCacheByHistoryId(entry.id)  // 需要新增此方法
            val rect = if (cache != null && cache.cropRight > 0) {
                RectF(cache.cropLeft.toFloat(), cache.cropTop.toFloat(), cache.cropRight.toFloat(), cache.cropBottom.toFloat())
            } else null
            showCropFragment(imagePath, rect, entry)
        }
    } else {
        showCropFragment(imagePath, null, null)
    }
}

private fun showCropFragment(imagePath: String, presetCrop: RectF?, originalEntry: HistoryEntry?) {
    val fragment = CropFragment.newInstance(imagePath, presetCrop)
    fragment.show(childFragmentManager, "CropFragment")

    childFragmentManager.setFragmentResultListener("crop_result", this) { _, bundle ->
        val cropLeft = bundle.getInt("cropLeft")
        val cropTop = bundle.getInt("cropTop")
        val cropRight = bundle.getInt("cropRight")
        val cropBottom = bundle.getInt("cropBottom")

        sendRetranslateRequest(imagePath, cropLeft, cropTop, cropRight, cropBottom)
    }
}
```

---

- [ ] **Step 4: sendRetranslateRequest**

```kotlin
private fun sendRetranslateRequest(imagePath: String, cropLeft: Int, cropTop: Int, cropRight: Int, cropBottom: Int) {
    // 按钮置灰
    setRetranslateButtonsEnabled(false)

    val ocrEngine = prefs.getString("history_ocr_engine", "PP_OCR_V5") ?: "PP_OCR_V5"
    val providerIndex = prefs.getString("history_openai_provider_index", "0")?.toIntOrNull() ?: 0

    val intent = Intent(MangaFloatingService.ACTION_RETRANSLATE_REQUEST).apply {
        putExtra("originalImagePath", imagePath)
        putExtra("cropLeft", cropLeft)
        putExtra("cropTop", cropTop)
        putExtra("cropRight", cropRight)
        putExtra("cropBottom", cropBottom)
        putExtra("ocrEngine", ocrEngine)
        putExtra("openaiProviderIndex", providerIndex)
    }
    LocalBroadcastManager.getInstance(requireContext()).sendBroadcast(intent)
}
```

---

- [ ] **Step 5: 接收完成广播**

```kotlin
// onCreateView 中注册
private var retranslateCompleteReceiver: BroadcastReceiver? = null

private fun registerRetranslateReceiver() {
    retranslateCompleteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != MangaFloatingService.ACTION_RETRANSLATE_COMPLETE) return
            val success = intent.getBooleanExtra("success", false)
            val errorMessage = intent.getStringExtra("errorMessage")

            lifecycleScope.launch {
                setRetranslateButtonsEnabled(true)
                if (success) {
                    Toast.makeText(requireContext(), "重新翻译完成", Toast.LENGTH_SHORT).show()
                    loadHistory()
                } else {
                    Toast.makeText(requireContext(), errorMessage ?: "重新翻译失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    LocalBroadcastManager.getInstance(requireContext())
        .registerReceiver(retranslateCompleteReceiver!!, IntentFilter(MangaFloatingService.ACTION_RETRANSLATE_COMPLETE))
}

// onDestroyView 中注销
override fun onDestroyView() {
    super.onDestroyView()
    retranslateCompleteReceiver?.let {
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(it)
    }
    _binding = null
}
```

---

- [ ] **Step 6: 删除尺寸**

```kotlin
private fun showDeleteVariantDialog(entry: HistoryEntry) {
    val group = /* 找到 entry 所在的 pHash 组 */
    val variantCount = group.variantIds.size
    val message = if (variantCount <= 1) {
        "删除此记录？这是该页面唯一的结果。"
    } else {
        "删除此尺寸？该页面还有 ${variantCount - 1} 个其他尺寸。"
    }
    AlertDialog.Builder(requireContext())
        .setMessage(message)
        .setPositiveButton("删除") { _, _ ->
            lifecycleScope.launch {
                cacheManager.deleteHistory(entry.id)
                loadHistory()
                Toast.makeText(requireContext(), "已删除", Toast.LENGTH_SHORT).show()
            }
        }
        .setNegativeButton("取消", null)
        .show()
}
```

---

- [ ] **Step 7: 编译验证**

```bash
./gradlew assembleDebug
```

---

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/moe/moetranslator/ui/history/HistoryFragment.kt
git commit -m "feat: retranslate flow — crop → broadcast → refresh"
```

---

### Task 12: 进程组 ZIP 下载

**Files:**
- Modify: `app/src/main/java/com/moe/moetranslator/ui/history/HistoryFragment.kt`

**Interfaces:**
- Consumes: HistorySession entries, HistoryEntry.imagePath
- Produces: ZIP 文件通过 ShareSheet 分享

---

- [ ] **Step 1: 会话 header 加下载按钮**

在管理视图的 `HistorySession` header layout（`item_history_manga_session.xml` 或 `item_history_session.xml`）中添加：

```xml
<ImageButton
    android:id="@+id/btnDownloadSession"
    android:layout_width="36dp"
    android:layout_height="36dp"
    android:src="@android:drawable/stat_sys_download"
    android:background="?selectableItemBackgroundBorderless"
    android:contentDescription="下载进程组" />
```

---

- [ ] **Step 2: 下载逻辑实现**

```kotlin
private fun downloadSession(session: HistorySession) {
    lifecycleScope.launch {
        // 1. 检查多变体，收集用户选择
        val selectedPaths = mutableListOf<String>()
        for (entry in session.entries) {
            if (entry.variantCount > 1) {
                // 弹窗让用户选（切回主线程）
                val chosenPath = withContext(Dispatchers.Main) {
                    showVariantPicker(entry)
                }
                if (chosenPath != null) selectedPaths.add(chosenPath)
            } else {
                entry.imagePath?.let { selectedPaths.add(it) }
            }
        }

        if (selectedPaths.isEmpty()) {
            withContext(Dispatchers.Main) {
                Toast.makeText(requireContext(), "没有可下载的图片", Toast.LENGTH_SHORT).show()
            }
            return@launch
        }

        // 2. 创建 ZIP
        withContext(Dispatchers.IO) {
            val zipFile = File(requireContext().cacheDir, "${session.sessionId}.zip")
            ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
                selectedPaths.forEachIndexed { index, path ->
                    val file = File(path)
                    if (file.exists()) {
                        zos.putNextEntry(ZipEntry("${session.sessionId}_${index}.jpg"))
                        file.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
            }

            // 3. 分享
            withContext(Dispatchers.Main) {
                val uri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.fileprovider", zipFile)
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, "分享进程组"))
            }
        }
    }
}

private suspend fun showVariantPicker(entry: HistoryEntry): String? {
    return suspendCancellableCoroutine { cont ->
        val variants = entry.variantIds.mapNotNull { id ->
            runBlocking { cacheManager.getHistoryById(id) }
        }
        val items = variants.map { "${it.imagePath?.let { getImageDimensions(it) } ?: "?"}" }
        AlertDialog.Builder(requireContext())
            .setTitle("选择保留的尺寸")
            .setItems(items.toTypedArray()) { _, which ->
                cont.resume(variants[which].imagePath)
            }
            .setOnCancelListener { cont.resume(null) }
            .show()
    }
}
```

---

- [ ] **Step 3: 编译验证**

```bash
./gradlew assembleDebug
```

---

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/moe/moetranslator/ui/history/HistoryFragment.kt
git commit -m "feat: session group ZIP download"
```

---

### Task 13: 字符串资源

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh/strings.xml`

---

- [ ] **Step 1: 新增字符串**

```
values/strings.xml:

<string name="history_view_default">Default View</string>
<string name="history_view_manage">Manage</string>
<string name="history_retranslate">Retranslate</string>
<string name="history_delete_variant">Delete This Size</string>
<string name="history_use_current_crop">Use Current Crop</string>
<string name="history_new_crop">New Crop</string>
<string name="history_original_unavailable">Original image unavailable</string>
<string name="history_service_not_running">Please start translation first</string>
<string name="history_retranslate_done">Retranslate completed</string>
<string name="history_retranslate_busy">Translation in progress, try later</string>
<string name="history_ocr_engine">Engine</string>
<string name="history_translate_api">Translate</string>
<string name="history_download_session">Download</string>

values-zh/strings.xml:

<string name="history_view_default">默认视图</string>
<string name="history_view_manage">管理视图</string>
<string name="history_retranslate">重新翻译</string>
<string name="history_delete_variant">删除此尺寸</string>
<string name="history_use_current_crop">用当前裁剪</string>
<string name="history_new_crop">重新裁剪</string>
<string name="history_original_unavailable">原图不可用</string>
<string name="history_service_not_running">请先启动漫画翻译</string>
<string name="history_retranslate_done">重新翻译完成</string>
<string name="history_retranslate_busy">翻译进行中，请稍后</string>
<string name="history_ocr_engine">引擎</string>
<string name="history_translate_api">翻译</string>
<string name="history_download_session">下载</string>
```

---

- [ ] **Step 2: 编译验证**

```bash
./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL

---

- [ ] **Step 3: Commit**

```bash
git add app/src/main/res/values/strings.xml \
        app/src/main/res/values-zh/strings.xml
git commit -m "feat: history redesign string resources"
```

---

## Implementation Order

```
Task 1 (DB) → Task 2 (Data classes) → Task 3 (saveToCache)
    → Task 4 (Service save original) → Task 5 (Service retranslate)
    → Task 6 (View tabs) → Task 7 (Settings) → Task 8 (Default card)
    → Task 9 (Manage card) → Task 10 (CropFragment)
    → Task 11 (Retranslate flow) → Task 12 (Download) → Task 13 (Strings)
```
