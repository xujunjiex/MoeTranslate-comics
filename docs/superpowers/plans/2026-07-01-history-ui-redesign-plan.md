# 历史记录 UI 重构 + 管线分离 — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 统一管理/默认视图卡片布局，操作按钮移至全屏页，引擎选择器简化并独立存储，游戏 tab 隐藏视图切换。

**Architecture:** 删除 `item_history_manga_manage.xml`，两个视图共用 `item_history_manga.xml`（新增 🔄 标记）。重翻/删除按钮迁移至 `MangaViewerActivity`。引擎选择器只保留 OCR 引擎 Spinner，去掉翻译 Spinner。MangaFloatingService broadcast extras 去掉 `openaiProviderIndex`。

**Tech Stack:** Kotlin, Android Views + ViewBinding, Room, LocalBroadcastManager, Material3 Spinner → AutoCompleteTextView

## Global Constraints

- minSdk 29, targetSdk 35
- 所有日志用 LogCollector
- Dialog 用 android.app.AlertDialog
- 引擎选择器 prefs key: `history_retranslate_engine`（独立于悬浮窗）
- 数据库不变
- 编译: `./gradlew assembleDebug`
- 每 Task 编译通过后 commit

---

### Task 1: 删除管理视图独立布局，统一卡片

**Files:**
- Delete: `app/src/main/res/layout/item_history_manga_manage.xml`
- Modify: `app/src/main/res/layout/item_history_manga.xml`

---

- [ ] **Step 1: 删除 manage layout**

```bash
rm app/src/main/res/layout/item_history_manga_manage.xml
```

---

- [ ] **Step 2: item_history_manga.xml 添加 🔄 重翻标记**

在 `btnToggleImage` 旁边新增一个 TextView（和 btnToggleImage 同级，FrameLayout 内）：

```xml
<!-- 在 btnToggleImage 之前或之后添加 -->
<TextView
    android:id="@+id/tvRetranslateBadge"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:layout_gravity="end|top"
    android:layout_marginTop="6dp"
    android:layout_marginEnd="6dp"
    android:text="🔄"
    android:textSize="14sp"
    android:background="#CCFF6600"
    android:paddingStart="6dp"
    android:paddingEnd="6dp"
    android:paddingTop="2dp"
    android:paddingBottom="2dp"
    android:textColor="#FFFFFF"
    android:visibility="gone" />
```

注意：`tvRetranslateBadge` 和已有的 `tv_size_badge` 共用同一个位置（都在右上角）。`tv_size_badge` 在 end|top 带 6dp margin，`tvRetranslateBadge` 需要稍微偏移避免重叠。放在 `tv_size_badge` 下面（marginTop 从 6dp 改到 `tv_size_badge` 下方），或者两个 badge 放入一个水平 LinearLayout。

简化方案：两个 badge 水平排列，共享同一个位置：

```xml
<LinearLayout
    android:id="@+id/badgeContainer"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:layout_gravity="end|top"
    android:layout_marginTop="6dp"
    android:layout_marginEnd="6dp"
    android:orientation="horizontal"
    android:visibility="gone">

    <TextView
        android:id="@+id/tvRetranslateBadge"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="🔄"
        android:textSize="14sp"
        android:background="#CCFF6600"
        android:paddingStart="4dp"
        android:paddingEnd="4dp"
        android:paddingTop="2dp"
        android:paddingBottom="2dp"
        android:textColor="#FFFFFF"
        android:layout_marginEnd="4dp"
        android:visibility="gone" />

    <TextView
        android:id="@+id/tv_size_badge"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:background="@drawable/badge_background"
        android:paddingStart="6dp"
        android:paddingEnd="6dp"
        android:paddingTop="2dp"
        android:paddingBottom="2dp"
        android:textColor="#FFFFFF"
        android:textSize="10sp"
        android:textStyle="bold"
        android:visibility="gone" />

</LinearLayout>
```

原有的 `tv_size_badge` 删除（移到 badgeContainer 内）。

同理更新 `item_history_manga_list.xml`（列表模式）。

---

- [ ] **Step 3: 编译**

```bash
./gradlew assembleDebug
```

---

- [ ] **Step 4: Commit**

```bash
git add app/src/main/res/layout/item_history_manga_manage.xml \
        app/src/main/res/layout/item_history_manga.xml \
        app/src/main/res/layout/item_history_manga_list.xml
git commit -m "refactor: delete manage layout, unify card with retranslate badge"
```

---

### Task 2: 引擎选择器简化

**Files:**
- Modify: `app/src/main/res/layout/fragment_history.xml`
- Modify: `app/src/main/java/com/moe/moetranslator/ui/history/HistoryFragment.kt`

---

- [ ] **Step 1: fragment_history.xml — 删除翻译 Spinner，只留引擎**

`engineSelectorLayout` 改为只包含一个引擎 Spinner：

```xml
<LinearLayout
    android:id="@+id/engineSelectorLayout"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="horizontal"
    android:paddingHorizontal="8dp"
    android:paddingVertical="4dp"
    android:visibility="gone">

    <com.google.android.material.textfield.TextInputLayout
        android:id="@+id/ocrEngineInputLayout"
        style="@style/Widget.Material3.TextInputLayout.OutlinedBox.ExposedDropdownMenu"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:hint="引擎">

        <com.google.android.material.textfield.MaterialAutoCompleteTextView
            android:id="@+id/spinnerOcrEngine"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:inputType="none" />

    </com.google.android.material.textfield.TextInputLayout>

</LinearLayout>
```

删除 `spinnerTranslateApi` 和 `translateInputLayout`。

---

- [ ] **Step 2: HistoryFragment.kt — setupEngineSelectors 简化**

```kotlin
private fun setupEngineSelectors() {
    val prefs = com.moe.moetranslator.utils.CustomPreference.getInstance(requireContext())

    val ocrEngines = arrayOf("PP-OCRv5", "manga-ocr", "ML Kit")
    val ocrValues = arrayOf("PP_OCR_V5", "MANGA_OCR", "MLKIT")
    val savedOcr = prefs.getString("history_retranslate_engine", "PP_OCR_V5") ?: "PP_OCR_V5"
    val ocrIdx = ocrValues.indexOfFirst { it == savedOcr }.coerceAtLeast(0)

    (binding.spinnerOcrEngine as? AutoCompleteTextView)?.apply {
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, ocrEngines)
        setAdapter(adapter)
        setText(ocrEngines[ocrIdx], false)
        setOnItemClickListener { _, _, position, _ ->
            prefs.setString("history_retranslate_engine", ocrValues[position])
        }
    }
}
```

删除翻译 Spinner 相关代码（`spinnerTranslateApi`、`providerList` 等）。

---

- [ ] **Step 3: 编译**

```bash
./gradlew assembleDebug
```

---

- [ ] **Step 4: Commit**

```bash
git add app/src/main/res/layout/fragment_history.xml \
        app/src/main/java/com/moe/moetranslator/ui/history/HistoryFragment.kt
git commit -m "refactor: simplify engine selector — remove translate spinner, use history_retranslate_engine key"
```

---

### Task 3: 游戏 tab 隐藏视图切换

**Files:**
- Modify: `app/src/main/java/com/moe/moetranslator/ui/history/HistoryFragment.kt`

---

- [ ] **Step 1: switchTab 中控制 viewModeTabLayout 可见性**

```kotlin
private fun switchTab(tab: Int) {
    // ... existing RecyclerView visibility logic ...

    // 游戏 tab 隐藏视图切换，漫画 tab 显示
    binding.viewModeTabLayout.visibility = if (tab == TranslationCacheManager.MODE_MANGA) View.VISIBLE else View.GONE
    // 切到游戏 tab 时强制隐藏引擎选择器
    if (tab == TranslationCacheManager.MODE_GAME) {
        binding.engineSelectorLayout.visibility = View.GONE
    }
    updateEngineSelectorVisibility()
    loadHistory()
}
```

---

- [ ] **Step 2: 编译 + Commit**

```bash
./gradlew assembleDebug
git add app/src/main/java/com/moe/moetranslator/ui/history/HistoryFragment.kt
git commit -m "fix: hide view mode tabs on game tab"
```

---

### Task 4: 清理 Adapter + 进程组下载

**Files:**
- Modify: `app/src/main/java/com/moe/moetranslator/ui/history/HistoryMangaAdapter.kt`
- Modify: `app/src/main/java/com/moe/moetranslator/ui/history/HistoryMangaGroupAdapter.kt`

---

- [ ] **Step 1: HistoryMangaAdapter — 删除管理视图特殊代码**

1. 删除 `isManageView` 字段及其 setter
2. 删除 `bindManageViews()` 方法
3. 删除 `retranslateCountMap` 相关代码
4. 删除 `onRetranslateClick`、`onDeleteVariantClick`、`onSwitchVariant` 回调
5. 删除 `item_history_manga_manage.xml` 的 viewType

在 bind() 中新增：
```kotlin
// 🔄 重翻标记（仅管理视图时显示）
val showBadge = isManageView && entry.isRetranslated
binding.badgeContainer.visibility = if (showBadge || entry.variantCount > 1) View.VISIBLE else View.GONE
binding.tvRetranslateBadge.visibility = if (showBadge) View.VISIBLE else View.GONE
binding.tvSizeBadge.visibility = if (entry.variantCount > 1) View.VISIBLE else View.GONE
if (entry.variantCount > 1) {
    binding.tvSizeBadge.text = "${entry.variantCount}尺寸"
}

// 点击缩略图 → 打开全屏页
binding.ivThumbnail.setOnClickListener {
    onThumbnailClick?.invoke(entry)
}
```

新增回调：
```kotlin
private val onThumbnailClick: ((HistoryEntry) -> Unit)?
```

在构造函数中加这个参数，默认 null。

---

- [ ] **Step 2: HistoryMangaGroupAdapter — 删除 isManageView + 加下载按钮**

1. 删除 `isManageView` 字段及传递链
2. 删除 `onRetranslateClick`、`onDeleteVariantClick`、`onSwitchVariant` 回调
3. 在 SessionViewHolder 的 bind() 中加下载按钮逻辑：

```kotlin
// 下载按钮（仅管理视图时显示）
btnDownloadSession.visibility = if (isManageView) View.VISIBLE else View.GONE
btnDownloadSession.setOnClickListener {
    onDownloadSessionClick?.invoke(session)
}
```

新增回调：
```kotlin
private val onDownloadSessionClick: ((HistorySession) -> Unit)?
```

`isManageView` 需要保留，但只是一个 boolean 参数传给 adapter——不再控制卡片布局，只控制 🔄 标记和下载按钮的显示。

---

- [ ] **Step 3: 编译 + Commit**

```bash
./gradlew assembleDebug
git add app/src/main/java/com/moe/moetranslator/ui/history/HistoryMangaAdapter.kt \
        app/src/main/java/com/moe/moetranslator/ui/history/HistoryMangaGroupAdapter.kt
git commit -m "refactor: clean adapters — remove manage layout code, add thumbnail click + session download callbacks"
```

---

### Task 5: MangaViewerActivity — 全屏详情页 + 操作按钮

**Files:**
- Modify: `app/src/main/java/com/moe/moetranslator/ui/history/MangaViewerActivity.kt`
- Modify: `app/src/main/res/layout/activity_manga_viewer.xml`

---

- [ ] **Step 1: activity_manga_viewer.xml 加操作按钮**

在底部信息面板（`bottomSheetPanel`）中添加：

```xml
<!-- 尺寸选择 -->
<LinearLayout
    android:id="@+id/variantSelectorLayout"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="horizontal"
    android:visibility="gone"
    android:gravity="center_vertical"
    android:paddingHorizontal="16dp"
    android:paddingVertical="8dp">

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="尺寸:"
        android:textSize="14sp" />

    <Spinner
        android:id="@+id/spinnerVariant"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:layout_marginStart="8dp" />

</LinearLayout>

<!-- 操作按钮 -->
<LinearLayout
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="horizontal"
    android:padding="16dp"
    android:gravity="center">

    <Button
        android:id="@+id/btnRetranslate"
        android:layout_width="0dp"
        android:layout_height="48dp"
        android:layout_weight="1"
        android:text="重新翻译"
        android:layout_marginEnd="8dp" />

    <Button
        android:id="@+id/btnDeleteVariant"
        android:layout_width="0dp"
        android:layout_height="48dp"
        android:layout_weight="1"
        android:text="删除此尺寸" />

</LinearLayout>
```

---

- [ ] **Step 2: MangaViewerActivity.kt — 绑定操作按钮 + 原图/译文切换**

```kotlin
// 新增成员变量
private var currentVariant: HistoryEntry? = null
private var showingOriginal = false

// setupViews() 中新增：

// 原图/译文切换（新增按钮或在现有 btnShowTranslation 旁加一个）
binding.btnToggleOriginal.setOnClickListener {
    showingOriginal = !showingOriginal
    val entry = getCurrentVariant()
    val path = if (showingOriginal) entry.originalImagePath else (entry.imagePath ?: entry.thumbnailPath)
    if (path != null && File(path).exists()) {
        val bmp = BitmapFactory.decodeFile(path)
        // 更新 ViewPager 当前页的图片
        val adapter = binding.viewPager.adapter as? PageGroupAdapter
        adapter?.setOverrideImage(bmp)
        adapter?.notifyItemChanged(binding.viewPager.currentItem)
    }
    binding.btnToggleOriginal.text = if (showingOriginal) "译" else "原"
}

// 变体选择
setupVariantSpinner()

// 重新翻译
binding.btnRetranslate.setOnClickListener {
    val entry = getCurrentVariant()
    startRetranslateFlow(entry)
}

// 删除此尺寸
binding.btnDeleteVariant.setOnClickListener {
    val entry = getCurrentVariant()
    val group = pageGroups.getOrNull(binding.viewPager.currentItem)
    val variantCount = group?.variants?.size ?: 1
    AlertDialog.Builder(this)
        .setMessage(if (variantCount <= 1) "删除此记录？" else "删除此尺寸？")
        .setPositiveButton("删除") { _, _ ->
            lifecycleScope.launch {
                cacheManager.deleteHistory(entry.id)
                // 重新加载数据
                val allEntries = cacheManager.getHistory(TranslationCacheManager.MODE_MANGA, limit = 500)
                pageGroups.clear()
                pageGroups.addAll(buildPageGroups(allEntries))
                if (pageGroups.isEmpty()) { finish(); return@launch }
                binding.viewPager.adapter?.notifyDataSetChanged()
                updatePageIndicator(binding.viewPager.currentItem.coerceAtMost(pageGroups.size - 1))
                Toast.makeText(this@MangaViewerActivity, "已删除", Toast.LENGTH_SHORT).show()
            }
        }
        .setNegativeButton("取消", null)
        .show()
}
```

---

- [ ] **Step 3: startRetranslateFlow**

```kotlin
private fun startRetranslateFlow(entry: HistoryEntry) {
    if (!ServiceUtils.isServiceRunning(this, MangaFloatingService::class.java)) {
        Toast.makeText(this, "请先启动漫画翻译", Toast.LENGTH_SHORT).show()
        return
    }

    val originalPath = entry.originalImagePath
    if (originalPath.isNullOrEmpty() || !File(originalPath).exists()) {
        Toast.makeText(this, "原图不可用", Toast.LENGTH_SHORT).show()
        return
    }

    AlertDialog.Builder(this)
        .setTitle("重新翻译")
        .setItems(arrayOf("用当前裁剪", "重新裁剪")) { _, which ->
            when (which) {
                0 -> openCropFragment(originalPath, entry)
                1 -> openCropFragment(originalPath, null)
            }
        }
        .show()
}

private fun openCropFragment(imagePath: String, entry: HistoryEntry?) {
    val presetCrop: RectF? = if (entry != null) {
        lifecycleScope.launch {
            val cache = cacheManager.getCacheByHistoryId(entry.id)
            val rect = if (cache != null && cache.cropRight > 0) {
                RectF(cache.cropLeft.toFloat(), cache.cropTop.toFloat(), cache.cropRight.toFloat(), cache.cropBottom.toFloat())
            } else null
            showCropFragment(imagePath, rect)
        }
    } else {
        showCropFragment(imagePath, null)
    }
}

private fun showCropFragment(imagePath: String, presetCrop: RectF?) {
    val fragment = CropFragment.newInstance(imagePath, presetCrop)
    fragment.show(supportFragmentManager, "CropFragment")
    supportFragmentManager.setFragmentResultListener(CropFragment.RESULT_KEY, this) { _, bundle ->
        sendRetranslateRequest(imagePath, bundle)
    }
}

private fun sendRetranslateRequest(imagePath: String, bundle: Bundle) {
    val prefs = com.moe.moetranslator.utils.CustomPreference.getInstance(this)
    val intent = Intent("com.moe.moetranslator.RETRANSLATE_REQUEST").apply {
        putExtra("originalImagePath", imagePath)
        putExtra("cropLeft", bundle.getInt("cropLeft"))
        putExtra("cropTop", bundle.getInt("cropTop"))
        putExtra("cropRight", bundle.getInt("cropRight"))
        putExtra("cropBottom", bundle.getInt("cropBottom"))
        putExtra("ocrEngine", prefs.getString("history_retranslate_engine", "PP_OCR_V5"))
    }
    LocalBroadcastManager.getInstance(this).sendBroadcast(intent)

    // 注册完成广播接收器
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            LocalBroadcastManager.getInstance(this@MangaViewerActivity).unregisterReceiver(this)
            val success = intent?.getBooleanExtra("success", false) ?: false
            val errorMessage = intent?.getStringExtra("errorMessage")
            runOnUiThread {
                if (success) {
                    Toast.makeText(this@MangaViewerActivity, "重新翻译完成", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MangaViewerActivity, errorMessage ?: "重新翻译失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    LocalBroadcastManager.getInstance(this)
        .registerReceiver(receiver, IntentFilter("com.moe.moetranslator.RETRANSLATE_COMPLETE"))
}
```

---

- [ ] **Step 4: 变体 Spinner**

```kotlin
private fun setupVariantSpinner() {
    binding.spinnerVariant.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            val group = pageGroups.getOrNull(binding.viewPager.currentItem) ?: return
            val variant = group.variants.getOrNull(position) ?: return
            currentVariant = variant
            // 更新 PageGroupAdapter 显示该变体的图片
            val adapter = binding.viewPager.adapter as? PageGroupAdapter
            adapter?.setActiveVariant(binding.viewPager.currentItem, variant.id)
            // 刷新译文面板
            expandPanel()
        }
        override fun onNothingSelected(parent: AdapterView<*>?) {}
    }
}
```

在 `updateSizeSwitcherVisibility` 中同步更新变体 Spinner。

---

- [ ] **Step 5: 编译 + Commit**

```bash
./gradlew assembleDebug
git add app/src/main/java/com/moe/moetranslator/ui/history/MangaViewerActivity.kt \
        app/src/main/res/layout/activity_manga_viewer.xml
git commit -m "feat: MangaViewerActivity — retranslate/delete buttons, variant spinner, original/toggle"
```

---

### Task 6: MangaFloatingService — broadcast extras 简化

**Files:**
- Modify: `app/src/main/java/com/moe/moetranslator/manga/MangaFloatingService.kt`

---

- [ ] **Step 1: handleRetranslateRequest 中去掉 openaiProviderIndex**

```kotlin
// 删除这一行：
val openaiProviderIndex = intent.getIntExtra("openaiProviderIndex", 0)

// 翻译器创建改为用 Service 当前的配置（translatorText 字段即可）
// 不需要根据 providerIndex 创建新的翻译器 —— 直接用现有的 translatorText
val translator = translatorText
if (translator == null) {
    sendRetranslateComplete(success = false, errorMessage = "翻译器未初始化")
    isProcessing = false
    return@launch
}
```

注意：如果 `translatorText` 是 `OpenAITranslation`，直接用。如果是其他类型（MLKit/NLLB/Bing），需要确保 `getTranslation` 接口一致。所有翻译器都实现 `TranslationTextAPI`，所以直接放在 `TranslationTextAPI` 类型变量里调用即可。

---

- [ ] **Step 2: 引擎切换简化**

引擎选择现在只需要切 `config.ocrEngine`（不涉及 detEngine），但保留 `config.detEngine` 的保存/恢复：

```kotlin
val savedOcrEngine = config.ocrEngine
when (ocrEngineName) {
    "MLKIT" -> { config.detEngine = DetEngine.MLKIT; config.ocrEngine = OcrEngine.MLKIT }
    "MANGA_OCR" -> { config.detEngine = DetEngine.PP_OCR_V5; config.ocrEngine = OcrEngine.MANGA_OCR }
    "PP_OCR_V5" -> { config.detEngine = DetEngine.PP_OCR_V5; config.ocrEngine = OcrEngine.PP_OCR_V5 }
}
try {
    // ... OCR + translate ...
} finally {
    config.ocrEngine = savedOcrEngine
    // config.detEngine 也恢复（但 OCR 流程应该已经正确设置了）
}
```

---

- [ ] **Step 3: 编译 + Commit**

```bash
./gradlew assembleDebug
git add app/src/main/java/com/moe/moetranslator/manga/MangaFloatingService.kt
git commit -m "refactor: simplify retranslate broadcast — use service translator, remove providerIndex"
```

---

### Task 7: 字符串清理 + HistoryFragment 整合

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh/strings.xml`
- Modify: `app/src/main/java/com/moe/moetranslator/ui/history/HistoryFragment.kt`

---

- [ ] **Step 1: 删除不再使用的字符串**

删除（中英文同步）：
- `history_translate_api` / `历史翻译 API`
- 其他仅管理视图旧 layout 引用的字符串（检查 `item_history_manga_manage.xml` 删除后无引用）

---

- [ ] **Step 2: HistoryFragment 整合**

1. `setupRecyclerViews()` 中 `mangaGroupAdapter` 改动：
   - 去掉 `isManageView` 传参
   - 新增 `onThumbnailClick` → 打开 `MangaViewerActivity`（和现在的 `onItemClick` 逻辑一样——现在已经是打开 MangaViewerActivity，保持不变即可）
   - 新增 `onDownloadSessionClick`（仅管理视图 adapter 回调）

2. `loadHistory()` 中 `retranslateCountMap` 计算删除（不再需要，标记只判断 `isRetranslated`）

3. 发广播的 `sendRetranslateRequest` 方法删除（移至 MangaViewerActivity）

4. 重翻完成广播接收器删除（移至 MangaViewerActivity）

---

- [ ] **Step 3: HistoryFragment 中 isManageView 改为从 viewMode Tab 读取**

Adapter 的 `isManageView` 由 ViewMode Tab 的状态决定。在 `loadHistory()` 的 manga 分支传入：

```kotlin
val isManage = prefs.getString("history_view_mode", "default") == "manage"
mangaGroupAdapter.setIsManageView(isManage)
```

---

- [ ] **Step 4: 编译**

```bash
./gradlew assembleDebug
```

---

- [ ] **Step 5: Commit**

```bash
git add app/src/main/res/values/strings.xml \
        app/src/main/res/values-zh/strings.xml \
        app/src/main/java/com/moe/moetranslator/ui/history/HistoryFragment.kt
git commit -m "chore: clean up strings and wire HistoryFragment to new detail page"
```

---

## Implementation Order

```
Task 1 (unify cards) → Task 2 (simplify engine) → Task 3 (game tab fix)
    → Task 4 (clean adapters) → Task 5 (MangaViewerActivity)
    → Task 6 (MangaFloatingService cleanup) → Task 7 (strings + integration)
```
