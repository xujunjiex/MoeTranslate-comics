# 翻译文本复制 + 阴影开关 + 历史增强 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为游戏/漫画翻译添加快速复制功能、文字阴影开关、颜色恢复按钮、历史记录复制/下载增强

**Architecture:** 4 个独立模块：阴影开关（仅游戏翻译）、复制功能（游戏底部按钮 + 漫画点击气泡模式）、历史增强（复制原文+译文 + 游戏组下载 txt + 漫画详情文本可选）、DB 升级（v11→v12 存储气泡位置数据支持缓存命中复制）

**Tech Stack:** Kotlin, Android Room (v11→v12 migration), Gson (JSON for bubble rects), SharedPreferences, WindowManager overlay

## Global Constraints

- 所有日志必须通过 `LogCollector` 写入，不能用 `Log.d/i/e`
- 新增 prefs key 必须确认 service 侧有监听（`styleKeys` / `watchedKeys`），或每次读取 prefs
- 字体颜色默认值：`-1516335`（`Color.parseColor("#FFE8E8E8")`）
- 背景颜色默认值：`-649384925`（`Color.argb(60, 60, 60, 60)`）
- bubble_rects JSON 格式：`[{"l":10,"t":20,"r":100,"b":60}, ...]`
- 缓存命中复制全部格式：`[1] text\n[2] text\n...`
- 游戏历史下载 txt 文件名：`session_<sessionId前8位>.txt`
- DB migration MIGRATION_11_12 必须注册到 `addMigrations()`，否则 `fallbackToDestructiveMigration()` 清空数据库
- **每次任务完成后执行 `./gradlew assembleDebug` 构建验证**

---

### Task 1: DB 迁移 v11→v12 + bubble_rects 存储

**Files:**
- Modify: `app/src/main/java/com/moe/starflow/data/TranslationHistoryDatabase.kt`
- Modify: `app/src/main/java/com/moe/starflow/data/HistoryEntity.kt`
- Modify: `app/src/main/java/com/moe/starflow/data/TranslationCacheManager.kt`

**Interfaces:**
- Produces: `HistoryEntity.bubbleRects: String?` — JSON serialized bubble rects, nullable for old data compat
- Produces: `CacheEntry.bubbleRects: String?` — new field for saveToCache
- Produces: `TranslationHistoryDatabase` version 12 with `MIGRATION_11_12`

- [ ] **Step 1: HistoryEntity 新增 bubbleRects 字段**

在 `HistoryEntity.kt` 的 `updatedAt` 字段之后新增：

```kotlin
@ColumnInfo(name = "bubble_rects")
val bubbleRects: String? = null,  // JSON: [{"l":10,"t":20,"r":100,"b":60}, ...] 气泡位置数据，旧数据为 NULL
```

- [ ] **Step 2: CacheEntry 新增 bubbleRects 字段**

在 `TranslationCacheManager.kt` 的 `CacheEntry` 的 `isRetranslated` 之后新增：

```kotlin
val bubbleRects: String? = null,  // JSON: [{"l":10,"t":20,"r":100,"b":60}, ...] 气泡位置数据
```

- [ ] **Step 3: TranslationHistoryDatabase 添加 MIGRATION_11_12**

修改 `TranslationHistoryDatabase.kt`：

```kotlin
// 改 version
@Database(
    entities = [HistoryEntity::class, PageCacheEntity::class],
    version = 12,  // 11 → 12
    exportSchema = false
)

// 新增 migration（放在 MIGRATION_10_11 之后）
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE translation_history ADD COLUMN bubble_rects TEXT")
    }
}

// getInstance() 中 addMigrations 加上新 migration：
).addMigrations(
    MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
    MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8,
    MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11,
    MIGRATION_11_12  // ← 新增
)
```

- [ ] **Step 4: saveToCache 传递 bubbleRects**

在 `TranslationCacheManager.kt` 的 `saveToCache` 中，`HistoryEntity` 构造处新增参数。找到创建 `HistoryEntity` 的位置（约第 330 行），在 `isRetranslated` 后加：

```kotlin
isRetranslated = entry.isRetranslated,
bubbleRects = entry.bubbleRects  // ← 新增
```

同样在 `refreshCache` 中创建的 `HistoryEntity` 也加上（如有）。

- [ ] **Step 5: buildCacheResult 中传递 bubbleRects**

找到 `TranslationCacheManager.kt` 中 `buildCacheResult` 返回 `HistoryEntry` 的位置，新增字段：

```kotlin
bubbleRects = entity.bubbleRects  // ← 在 variantIds 之后、originalImagePath 之前
```

同时在 `HistoryEntry` 数据类中（`TranslationCacheManager.kt` 约 760 行）新增字段：

```kotlin
val bubbleRects: String? = null,  // JSON: [{"l":10,"t":20,"r":100,"b":60}, ...]
```

- [ ] **Step 6: 构建验证**

```bash
./gradlew assembleDebug
```

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/moe/starflow/data/
git commit -m "feat: DB v11→v12 升级，新增 bubble_rects 字段存储气泡位置"
```

---

### Task 2: 字符串资源 + 复制图标

**Files:**
- Create: `app/src/main/res/drawable/ic_copy.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh/strings.xml`
- Modify: `app/src/main/res/values-en/strings.xml`

**Interfaces:**
- Produces: `R.drawable.ic_copy` — 复制矢量图标
- Produces: String resources for all new UI text

- [ ] **Step 1: 创建 ic_copy.xml 矢量图标**

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <!-- 两个重叠文档 + 对勾 -->
    <path
        android:fillColor="#FFFFFF"
        android:pathData="M16,1H4C2.9,1 2,1.9 2,3v14h2V3h12V1zM19,5H8C6.9,5 6,5.9 6,7v14c0,1.1 0.9,2 2,2h11c1.1,0 2,-0.9 2,-2V7C21,5.9 20.1,5 19,5zM19,21H8V7h11V21z" />
</vector>
```

- [ ] **Step 2: 三语言新增 strings.xml**

**values/strings.xml (English default):**
```xml
<string name="text_shadow_title">Text shadow</string>
<string name="text_shadow_summary">Add shadow effect to translation result text</string>
<string name="reset_default_color">Reset to default</string>
<string name="copy_text">Copy text</string>
<string name="copy_all">Copy all</string>
<string name="copy_original">Copy original</string>
<string name="copy_translation">Copy translation</string>
<string name="text_copied">Copied</string>
<string name="copy_original_translation">Original + Translation</string>
<string name="download_txt">Download TXT</string>
<string name="download_txt_description">Download translation as text file</string>
```

**values-zh/strings.xml:**
```xml
<string name="text_shadow_title">文字阴影</string>
<string name="text_shadow_summary">为翻译结果文字添加阴影效果</string>
<string name="reset_default_color">恢复默认</string>
<string name="copy_text">复制文本</string>
<string name="copy_all">复制全部</string>
<string name="copy_original">复制原文</string>
<string name="copy_translation">复制译文</string>
<string name="text_copied">已复制</string>
<string name="copy_original_translation">原文 + 译文</string>
<string name="download_txt">下载 TXT</string>
<string name="download_txt_description">下载翻译文本文件</string>
```

Note: `values-en/strings.xml` — same English strings as `values/strings.xml`.

- [ ] **Step 3: 构建验证**

```bash
./gradlew assembleDebug
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/res/drawable/ic_copy.xml app/src/main/res/values/strings.xml app/src/main/res/values-zh/strings.xml app/src/main/res/values-en/strings.xml
git commit -m "feat: 新增复制图标 + 字符串资源（三语言）"
```

---

### Task 3: TranslationResultView — 阴影开关 + 复制按钮 + ⚡ 迁移

**Files:**
- Modify: `app/src/main/java/com/moe/starflow/translate/TranslationResultView.kt`

**Interfaces:**
- Consumes: `R.drawable.ic_copy` (Task 2), `CustomPreference.text_shadow_enabled` (set in Task 4)
- Produces: `copyButton` ImageButton always visible; `applyStyle()` controls shadow via prefs; ⚡ prefix instead of cacheIndicator

- [ ] **Step 1: 添加 copyButton 成员变量**

在 `TranslationResultView` 的 init 块中，`retranslateButton` 之后、`addView(textView)` 之前添加：

```kotlin
// 复制按钮（左下角，始终显示）
private val copyButton: ImageButton

init {
    // ... existing code for lockButton, closeButton, etc. ...

    // 复制按钮（左下角）
    val copySize = dpToPx(16)
    copyButton = ImageButton(context).apply {
        setBackgroundColor(Color.TRANSPARENT)
        setImageResource(R.drawable.ic_copy)
        setColorFilter(Color.argb(160, 80, 80, 80))
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(0, 0, 0, 0)
        setOnClickListener {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val text = textView.text.toString().removePrefix("⚡")
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("translated_text", text))
            android.widget.Toast.makeText(context, R.string.text_copied, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
```

注意 `CLIPBOARD_SERVICE` 和 `ClipData` 等需要 import `android.content.ClipboardManager` 和 `android.content.ClipData`。

- [ ] **Step 2: 替换 cacheIndicator + 添加 copyButton 到布局**

移除 `cacheIndicator` 变量声明和 addView，改为在 addView 区域添加 copyButton：

```kotlin
// 删除 cacheIndicator 的声明和 addView 代码
// 删除 showCacheIndicator/hideCacheIndicator 中关于 cacheIndicator visibility 的代码

// 添加 copyButton（左下角，替代旧的 cacheIndicator 位置）
addView(copyButton, LayoutParams(copySize, copySize).apply {
    gravity = Gravity.START or Gravity.BOTTOM
    marginStart = btnSpace
    bottomMargin = btnMargin
})
```

- [ ] **Step 3: ⚡ 缓存标志迁移到文本前缀**

修改 `setText()` 调用处。`TranslationResultView` 新增一个 `setText(text: String, fromCache: Boolean)` 重载：

```kotlin
fun setText(text: String, fromCache: Boolean = false) {
    textView.text = if (fromCache) "⚡$text" else text
}
```

如果 `FloatingBallService` 调用 `setText` 的地方需要传 `fromCache`，修改对应调用。后续 Task 4 处理。

- [ ] **Step 4: applyStyle() 加入阴影控制**

在 `applyStyle()` 方法末尾添加：

```kotlin
val shadowEnabled = prefs.getBoolean("text_shadow_enabled", true)
if (shadowEnabled) {
    textView.setShadowLayer(2f, 1f, 1f, Color.BLACK)
} else {
    textView.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
}
```

- [ ] **Step 5: 清理 showCacheIndicator/hideCacheIndicator**

移除 `showCacheIndicator()` 中设置 `cacheIndicator.visibility = View.VISIBLE` 的代码。
移除 `hideCacheIndicator()` 中设置 `cacheIndicator.visibility = View.GONE` 的代码。
如果这两个方法体为空可保留接口不变（FloatingBallService 调用它们）。

retranslateButton 的 visibility 控制保持不变。

- [ ] **Step 6: 构建验证**

```bash
./gradlew assembleDebug
```

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/moe/starflow/translate/TranslationResultView.kt
git commit -m "feat: 游戏翻译结果框 — 阴影开关 + 复制按钮 + ⚡ 迁到文本前缀"
```

---

### Task 4: FloatingBallService — styleKeys 加 text_shadow_enabled

**Files:**
- Modify: `app/src/main/java/com/moe/starflow/translate/FloatingBallService.kt`

**Interfaces:**
- Consumes: `TranslationResultView.setText(text, fromCache)` (Task 3), `TranslationResultView.applyStyle()` reads `text_shadow_enabled`
- Produces: styleKeys includes `text_shadow_enabled`; result display calls pass `fromCache`

- [ ] **Step 1: styleKeys 加入 text_shadow_enabled**

在 `FloatingBallService.kt` 约 472 行，修改 `styleKeys`：

```kotlin
val styleKeys = setOf(
    "Custom_Result_Font_Size",
    "Custom_Result_Font_Color",
    "Custom_Result_Background_Color",
    "Custom_Result_Font",
    "text_shadow_enabled"  // ← 新增
)
```

- [ ] **Step 2: 查找调用 translationResultView.setText() 的位置**

搜索 `FloatingBallService.kt` 中所有 `translationResultView.setText(` 调用，对缓存命中场景传入 `fromCache = true`。

例如（如有）：
```kotlin
// 缓存命中
translationResultView.setText(translatedText, fromCache = true)
// 新翻译
translationResultView.setText(translatedText)
```

搜索命令确认调用处：
```bash
grep -n "translationResultView\.setText\|translationResultView\.text =" app/src/main/java/com/moe/starflow/translate/FloatingBallService.kt
```

- [ ] **Step 3: showCacheIndicator/hideCacheIndicator 调用保持不变**

`FloatingBallService` 中对 `showCacheIndicator()`/`hideCacheIndicator()` 的调用保持原样（Task 3 中这些方法变为控制 retranslateButton 和可能设置 fromCache 标记）。

如果需要保留缓存命中的视觉区分（retranslateButton 仍出现），确保 `showCacheIndicator` 至少保留设置 retranslateButton visibility 的代码。

- [ ] **Step 4: 构建验证**

```bash
./gradlew assembleDebug
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/moe/starflow/translate/FloatingBallService.kt
git commit -m "feat: FloatingBallService styleKeys 加入文字阴影开关"
```

---

### Task 5: PersonalizationConfig — 阴影开关 + 颜色恢复按钮

**Files:**
- Modify: `app/src/main/java/com/moe/starflow/me/PersonalizationConfig.kt`
- Modify: `app/src/main/res/xml/personalization.xml`

**Interfaces:**
- Consumes: String resources from Task 2
- Produces: `text_shadow_enabled` SwitchPreference; `↺` reset buttons for font/background color

- [ ] **Step 1: personalization.xml 新增阴影开关**

在 `personalization.xml` 的「翻译结果框」category（`translate_result_box_with_hint`）中，`show_source_text` 之后、`</PreferenceCategory>` 之前添加：

```xml
<SwitchPreference
    android:key="text_shadow_enabled"
    android:title="@string/text_shadow_title"
    android:summary="@string/text_shadow_summary"
    android:defaultValue="true"
    app:iconSpaceReserved="false" />
```

Note: PreferenceScreen XML 不支持内联按钮，颜色恢复按钮需要在 Fragment 中动态创建，不由 XML 定义。

- [ ] **Step 2: PersonalizationConfig 添加阴影 SwitchPreference 绑定**

在 `PersonalizationConfig.kt` 的 `onCreatePreferences()` 中添加：

```kotlin
// 文字阴影
findPreference<SwitchPreference>("text_shadow_enabled")?.apply {
    setOnPreferenceChangeListener { _, newValue ->
        prefs.setBoolean("text_shadow_enabled", newValue as Boolean)
        true
    }
}
```

- [ ] **Step 3: 颜色恢复按钮 — 动态创建并注入**

由于 `PreferenceScreen` XML 不支持嵌套自定义按钮，采用运行时获取 `PreferenceGroup` 后注入的方式。

在 `onCreatePreferences()` 中 `show_source_text` 绑定之后添加：

```kotlin
// 字体颜色恢复按钮
findPreference<ColorPreferenceCompat>("result_view_font_color")?.apply {
    // 在 summary 后加 "↺ 默认" 点击区域
    // 使用 Preference.onPreferenceClickListener 不够直观（颜色选择器已有 click），
    // 改为在 summary 中提示长按恢复，或使用 widgetLayout
}
```

**实际实施方式：** 在 color picker summary 中添加 Spannable 可点击文本实现「↺ 默认」。更简单的方式是利用 `setOnPreferenceClickListener` 显示确认对话框：

```kotlin
// 字体颜色 — 长按恢复默认
findPreference<ColorPreferenceCompat>("result_view_font_color")?.apply {
    setOnPreferenceChangeListener { _, newValue ->
        prefs.setInt("Custom_Result_Font_Color", newValue as Int)
        true
    }
    // 使用 summary 提示恢复方式
    summary = "${getString(R.string.font_color_summary)}\n${getString(R.string.reset_default_color)}"
    // 点击颜色选择器选色；点击 summary 中的"恢复默认"通过 Preference 的 extras 实现
}
```

**简化方案：** 不修改颜色选择器的点击行为，改为在颜色选择器下方新增一个 `Preference` 条目作为恢复按钮：

在 `personalization.xml` 中，`result_view_font_color` 之后添加：

```xml
<Preference
    android:key="reset_result_font_color"
    app:iconSpaceReserved="false"
    android:title="@string/reset_default_color"
    android:summary="@string/font_color" />
```

在 `PersonalizationConfig.kt` 中：

```kotlin
findPreference<Preference>("reset_result_font_color")?.apply {
    setOnPreferenceClickListener {
        prefs.setInt("Custom_Result_Font_Color", -1516335)
        // 更新颜色选择器的 summary 展示
        (findPreference<ColorPreferenceCompat>("result_view_font_color"))?.let { cp ->
            cp.summary = getString(R.string.font_color_summary)
        }
        UiUtils.showToast(requireContext(), getString(R.string.function_restored_default), isShort = true)
        true
    }
}

// 同样方式添加背景颜色恢复按钮
```

- [ ] **Step 4: 背景颜色恢复按钮 — 同样方式**

```xml
<!-- 在 result_view_background_color 之后 -->
<Preference
    android:key="reset_result_bg_color"
    app:iconSpaceReserved="false"
    android:title="@string/reset_default_color"
    android:summary="@string/result_background_color" />
```

```kotlin
findPreference<Preference>("reset_result_bg_color")?.apply {
    setOnPreferenceClickListener {
        prefs.setInt("Custom_Result_Background_Color", -649384925)
        (findPreference<ColorPreferenceCompat>("result_view_background_color"))?.let { cp ->
            cp.summary = getString(R.string.result_background_color_summary)
        }
        UiUtils.showToast(requireContext(), getString(R.string.function_restored_default), isShort = true)
        true
    }
}
```

Note: String `function_restored_default` 如果不存在需要新增：
```xml
<string name="function_restored_default">已恢复默认</string>
```
添加到 `values-zh/strings.xml`。

- [ ] **Step 5: 构建验证**

```bash
./gradlew assembleDebug
```

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/moe/starflow/me/PersonalizationConfig.kt app/src/main/res/xml/personalization.xml app/src/main/res/values-zh/strings.xml
git commit -m "feat: 个性化设置 — 文字阴影开关 + 颜色恢复默认按钮"
```

---

### Task 6: MangaFloatingService — 漫画复制模式

**Files:**
- Modify: `app/src/main/java/com/moe/starflow/manga/MangaFloatingService.kt`

**Interfaces:**
- Consumes: `CacheEntry.bubbleRects` (Task 1 DB), `R.string.*` (Task 2), `translatedRegions` (existing field)
- Produces: Copy mode with three buttons, transparent clickable overlay, bubble outlines, per-bubble copy, copy-all
- Note: 这是最大的任务，MangaFloatingService.kt 文件可能已经很大。新增代码应集中在 `showResultOverlay`/`showCacheOverlay` 附近。

- [ ] **Step 1: 新增复制模式状态变量**

在 `MangaFloatingService` 类中添加成员变量（放在现有 overlay 变量附近）：

```kotlin
// 复制模式
private var isCopyMode = false
private var copyOriginalMode = false  // false=译文, true=原文
private var copyClickLayer: android.widget.FrameLayout? = null
private var copyBubbleViews: MutableList<View> = mutableListOf()
private var copyButtonsContainer: android.widget.LinearLayout? = null
```

- [ ] **Step 2: saveToCache 时序列化 bubbleRects**

在 `saveTranslationCache` 方法中，`CacheEntry` 构造处添加 `bubbleRects`。

获取气泡 rect 列表：`translatedRegions` 或 `finalBubbles`（最终翻译的气泡列表）。

找到 `saveTranslationCache` 约 2027 行的 `CacheEntry(...)`，添加：

```kotlin
bubbleRects = if (translatedRegions.isNotEmpty()) {
    val gson = com.google.gson.Gson()
    val rects = translatedRegions.map { r ->
        mapOf("l" to r.rect.left, "t" to r.rect.top, "r" to r.rect.right, "b" to r.rect.bottom)
    }
    gson.toJson(rects)
} else null,
```

注意：MangaFloatingService 的 `translatedRegions` 类型是 `MutableList<TranslatedBubble>`。需确认 `TranslatedBubble` 有 `rect` 字段（`Android.Graphics.Rect`）。

同样在增量渲染路径的 `saveToCache` 中（约 3065 行）也加上。

- [ ] **Step 3: showResultOverlay — 右下角添加复制按钮**

在 `showResultOverlay()` 方法中，`resultOverlayView` 添加到 `windowManager` 之后，添加复制按钮 overlay。

创建三个按钮的 `LinearLayout` 容器：

```kotlin
private fun showCopyButtons() {
    if (copyButtonsContainer != null) return
    val btnSize = dpToPx(36)
    val margin = dpToPx(8)
    
    copyButtonsContainer = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.HORIZONTAL
        // 右下角定位
    }
    
    // 📋 复制模式
    val btnCopy = TextView(this).apply {
        text = "📋"
        textSize = 18f
        setTextColor(android.graphics.Color.argb(200, 255, 255, 255))
        setShadowLayer(3f, 1f, 1f, android.graphics.Color.BLACK)
        setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4))
        setOnClickListener { toggleCopyMode() }
    }
    copyButtonsContainer!!.addView(btnCopy)
    
    val params = WindowManager.LayoutParams().apply {
        type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        format = PixelFormat.TRANSLUCENT
        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        width = WindowManager.LayoutParams.WRAP_CONTENT
        height = WindowManager.LayoutParams.WRAP_CONTENT
        gravity = Gravity.BOTTOM or Gravity.END
        x = dpToPx(8)
        y = dpToPx(8)
    }
    windowManager.addView(copyButtonsContainer, params)
}
```

**进入复制模式后展开更多按钮：**

```kotlin
private fun toggleCopyMode() {
    isCopyMode = !isCopyMode
    if (isCopyMode) {
        enterCopyMode()
    } else {
        exitCopyMode()
    }
}

private fun enterCopyMode() {
    // 1. 展开按钮：🔄 + 📄
    if (copyButtonsContainer != null && copyButtonsContainer!!.childCount < 3) {
        val btnToggle = TextView(this).apply {
            text = if (copyOriginalMode) "原文" else "译文"
            textSize = 14f
            setTextColor(android.graphics.Color.argb(200, 255, 255, 255))
            setShadowLayer(3f, 1f, 1f, android.graphics.Color.BLACK)
            setPadding(dpToPx(6), dpToPx(4), dpToPx(6), dpToPx(4))
            setOnClickListener {
                copyOriginalMode = !copyOriginalMode
                text = if (copyOriginalMode) "原文" else "译文"
            }
        }
        copyButtonsContainer!!.addView(btnToggle, 0)
        
        val btnCopyAll = TextView(this).apply {
            text = "📄"
            textSize = 18f
            setTextColor(android.graphics.Color.argb(200, 255, 255, 255))
            setShadowLayer(3f, 1f, 1f, android.graphics.Color.BLACK)
            setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4))
            setOnClickListener { copyAllBubbles() }
        }
        copyButtonsContainer!!.addView(btnCopyAll, 0)
    }
    
    // 2. 创建可点击区域 + 气泡框
    createCopyClickLayer()
}

private fun exitCopyMode() {
    // 移除额外按钮
    if (copyButtonsContainer != null && copyButtonsContainer!!.childCount > 1) {
        copyButtonsContainer!!.removeViews(0, 2)  // 移除 🔄 和 📄
    }
    // 移除可点击层
    removeCopyClickLayer()
}
```

- [ ] **Step 4: 创建可点击透明层 + 气泡框**

```kotlin
private fun createCopyClickLayer() {
    removeCopyClickLayer()
    
    val container = android.widget.FrameLayout(this)
    copyBubbleViews.clear()
    
    val bubbles = if (isCacheResult) {
        // 缓存命中：尝试从 DB 读取 bubbleRects
        lastCacheBubbleRects?.let { parseBubbleRects(it) } ?: emptyList()
    } else {
        translatedRegions.map { it.rect }
    }
    
    for ((idx, rect) in bubbles.withIndex()) {
        val overlay = View(this).apply {
            // 气泡框背景
            setBackgroundColor(android.graphics.Color.argb(40, 100, 200, 255))
            setOnClickListener {
                copyBubbleText(idx)
                // 高亮反馈
                setBackgroundColor(android.graphics.Color.argb(120, 100, 200, 255))
                postDelayed({ setBackgroundColor(android.graphics.Color.argb(40, 100, 200, 255)) }, 200)
            }
        }
        val lp = android.widget.FrameLayout.LayoutParams(rect.width(), rect.height()).apply {
            leftMargin = rect.left
            topMargin = rect.top
        }
        container.addView(overlay, lp)
        copyBubbleViews.add(overlay)
    }
    
    copyClickLayer = container
    
    val params = WindowManager.LayoutParams().apply {
        type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        format = PixelFormat.TRANSLUCENT
        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        width = WindowManager.LayoutParams.MATCH_PARENT
        height = WindowManager.LayoutParams.MATCH_PARENT
        gravity = Gravity.START or Gravity.TOP
        x = 0; y = 0
    }
    windowManager.addView(container, params)
}
```

- [ ] **Step 5: 复制逻辑**

```kotlin
private fun copyBubbleText(idx: Int) {
    val region = translatedRegions.getOrNull(idx) ?: return
    val text = if (copyOriginalMode) {
        region.texts.joinToString("")
    } else {
        region.translatedText
    }
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("copied_text", text))
    android.widget.Toast.makeText(this, R.string.text_copied, android.widget.Toast.LENGTH_SHORT).show()
}

private fun copyAllBubbles() {
    val regions = translatedRegions
    if (regions.isEmpty()) return
    val text = regions.mapIndexed { idx, r ->
        val content = if (copyOriginalMode) r.texts.joinToString("") else r.translatedText
        "[${idx + 1}] $content"
    }.joinToString("\n")
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("copied_all", text))
    android.widget.Toast.makeText(this, R.string.text_copied, android.widget.Toast.LENGTH_SHORT).show()
}
```

- [ ] **Step 6: 缓存命中支持**

在 `showCacheOverlay()` 方法中，加载缓存结果时解析 `bubbleRects`：

```kotlin
// 在 showCacheOverlay 中，获取缓存条目的 bubbleRects
var cacheBubbleRects: List<Rect>? = null  // 新增成员变量

// 缓存查找成功后，从 CacheResult 中读取 bubbleRects
private fun showCacheOverlay(bitmap: Bitmap) {
    // ... existing code ...
    
    // 从最后查找到的缓存结果中获取 bubbleRects
    if (lastCacheBubbleRects != null) {
        cacheBubbleRects = parseBubbleRectsJson(lastCacheBubbleRects!!)
    } else {
        cacheBubbleRects = null
    }
    
    // 添加复制按钮（与 showResultOverlay 相同）
    showCopyButtons()
}
```

添加 JSON 解析辅助函数：

```kotlin
private fun parseBubbleRectsJson(json: String?): List<android.graphics.Rect> {
    if (json.isNullOrEmpty()) return emptyList()
    return try {
        val gson = com.google.gson.Gson()
        val type = object : com.google.gson.reflect.TypeToken<List<Map<String, Int>>>() {}.type
        val list = gson.fromJson<List<Map<String, Int>>>(json, type)
        list.map { android.graphics.Rect(it["l"]!!, it["t"]!!, it["r"]!!, it["b"]!!) }
    } catch (e: Exception) {
        LogCollector.e(TAG, "parseBubbleRectsJson failed", e)
        emptyList()
    }
}
```

- [ ] **Step 7: dismissResultOverlay 时清理复制模式**

在 `dismissResultOverlay()` 中添加：

```kotlin
if (isCopyMode) {
    exitCopyMode()
}
removeCopyButtons()
```

添加 `removeCopyButtons()`：

```kotlin
private fun removeCopyButtons() {
    if (copyButtonsContainer != null) {
        try { if (copyButtonsContainer!!.isAttachedToWindow) windowManager.removeView(copyButtonsContainer) } catch (_: Exception) {}
        copyButtonsContainer = null
    }
}

private fun removeCopyClickLayer() {
    copyBubbleViews.clear()
    if (copyClickLayer != null) {
        try { if (copyClickLayer!!.isAttachedToWindow) windowManager.removeView(copyClickLayer) } catch (_: Exception) {}
        copyClickLayer = null
    }
}
```

- [ ] **Step 8: dpToPx 辅助方法（如不存在）**

检查 `MangaFloatingService` 是否已有 `dpToPx` 方法。如无，添加：

```kotlin
private fun dpToPx(dp: Int): Int {
    return (dp * resources.displayMetrics.density).toInt()
}
```

- [ ] **Step 9: 构建验证**

```bash
./gradlew assembleDebug
```

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/moe/starflow/manga/MangaFloatingService.kt
git commit -m "feat: 漫画翻译复制模式 — 点击气泡复制 + 复制全部"
```

---

### Task 7: HistoryFragment — 复制原文+译文 + 游戏组下载 txt

**Files:**
- Modify: `app/src/main/java/com/moe/starflow/ui/history/HistoryFragment.kt`

**Interfaces:**
- Consumes: `HistoryGroupAdapter.onDownloadSessionClick` (Task 8), String resources (Task 2)
- Produces: `copyTranslatedText()` copies both source+translation; game session download as .txt via SAF

- [ ] **Step 1: 修改 copyTranslatedText — 同时复制原文+译文**

修改 `HistoryFragment.kt` 约 344 行：

```kotlin
private fun copyTranslatedText(entry: HistoryEntry) {
    val sourceText = entry.sourceText ?: ""
    val translatedText = entry.translatedText ?: ""
    if (sourceText.isEmpty() && translatedText.isEmpty()) return
    
    val text = buildString {
        if (sourceText.isNotEmpty()) {
            append("原文: ")
            append(sourceText)
        }
        if (translatedText.isNotEmpty()) {
            if (sourceText.isNotEmpty()) append("\n")
            append("译文: ")
            append(translatedText)
        }
    }
    
    val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("translated_text", text))
    Toast.makeText(requireContext(), R.string.text_copied, Toast.LENGTH_SHORT).show()
}
```

- [ ] **Step 2: 游戏组下载 txt — 新增 downloadGameSession 方法**

在 `HistoryFragment.kt` 中添加：

```kotlin
// SAF 下载 (游戏 tab 使用，需与漫画共用 pendingDownloadTxt)
private var pendingDownloadTxt: File? = null
private val REQUEST_DOWNLOAD_TXT = 1002

private fun downloadGameSession(session: HistorySession) {
    lifecycleScope.launch {
        try {
            withContext(Dispatchers.IO) {
                val txtFile = File(requireContext().cacheDir, "session_${session.sessionId.take(8)}.txt")
                val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                
                txtFile.bufferedWriter().use { writer ->
                    for (entry in session.entries) {
                        val time = dateFormat.format(Date(entry.updatedAt))
                        writer.write("[$time]\n")
                        entry.sourceText?.let { writer.write("原文: $it\n") }
                        entry.translatedText?.let { writer.write("译文: $it\n") }
                        writer.write("---\n")
                    }
                }
                
                withContext(Dispatchers.Main) {
                    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TITLE, "session_${session.sessionId.take(8)}.txt")
                    }
                    pendingDownloadTxt = txtFile
                    @Suppress("DEPRECATION")
                    startActivityForResult(intent, REQUEST_DOWNLOAD_TXT)
                }
            }
        } catch (e: Exception) {
            LogCollector.e(TAG, "Download game session failed", e)
            Toast.makeText(requireContext(), "下载失败", Toast.LENGTH_SHORT).show()
        }
    }
}
```

- [ ] **Step 3: 修改 gameGroupAdapter 构造 — 传入下载回调**

在 `setupRecyclerViews()` 中修改（约 102 行）：

```kotlin
gameGroupAdapter = HistoryGroupAdapter(
    onItemClick = { entry -> copyTranslatedText(entry) },
    onItemLongClick = { entry -> showDeleteDialog(entry) },
    onDownloadSessionClick = { session -> downloadGameSession(session) }  // ← 新增
)
```

- [ ] **Step 4: onActivityResult 处理 txt 下载**

在现有 `onActivityResult` 中添加 `REQUEST_DOWNLOAD_TXT` 处理：

```kotlin
if (requestCode == REQUEST_DOWNLOAD_TXT && resultCode == Activity.RESULT_OK) {
    data?.data?.let { uri ->
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    pendingDownloadTxt?.let { txt ->
                        requireContext().contentResolver.openOutputStream(uri)?.use { out ->
                            txt.inputStream().use { it.copyTo(out) }
                        }
                        txt.delete()
                    }
                }
                com.moe.starflow.utils.UiUtils.showToast(requireContext(), "下载完成")
            } catch (e: Exception) {
                LogCollector.e(TAG, "Download txt save failed", e)
                com.moe.starflow.utils.UiUtils.showToast(requireContext(), "下载失败")
            }
        }
    }
    pendingDownloadTxt = null
}
```

注意：`SimpleDateFormat` 和 `Date` 需要 import。

- [ ] **Step 5: 构建验证**

```bash
./gradlew assembleDebug
```

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/moe/starflow/ui/history/HistoryFragment.kt
git commit -m "feat: 游戏历史 — 复制原文+译文 + 按组下载 txt"
```

---

### Task 8: HistoryGroupAdapter — 下载回调 + 会话标题栏下载按钮

**Files:**
- Modify: `app/src/main/java/com/moe/starflow/ui/history/HistoryGroupAdapter.kt`
- Modify: `app/src/main/res/layout/item_history_session.xml`

**Interfaces:**
- Consumes: `HistorySession` type (existing), download icon
- Produces: `onDownloadSessionClick` callback; download button in session header

- [ ] **Step 1: item_history_session.xml 添加下载按钮**

修改布局为水平 LinearLayout（标题 + 下载按钮）：

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical"
    android:layout_marginTop="4dp">

    <!-- 会话标题栏（时间 + 下载按钮） -->
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:gravity="center_vertical"
        android:paddingHorizontal="24dp"
        android:paddingVertical="6dp">

        <TextView
            android:id="@+id/tvSessionHeader"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:textColor="#FF999999"
            android:textSize="12sp" />

        <!-- 下载按钮 -->
        <TextView
            android:id="@+id/btnDownloadSession"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="⬇"
            android:textColor="#FF999999"
            android:textSize="14sp"
            android:paddingStart="12dp"
            android:paddingEnd="4dp"
            android:clickable="true"
            android:focusable="true"
            android:background="?attr/selectableItemBackgroundBorderless" />
    </LinearLayout>

    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/rvSessionEntries"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:nestedScrollingEnabled="false" />

</LinearLayout>
```

- [ ] **Step 2: HistoryGroupAdapter 添加 onDownloadSessionClick 回调**

修改构造参数：

```kotlin
class HistoryGroupAdapter(
    private val onItemClick: (HistoryEntry) -> Unit,
    private val onItemLongClick: (HistoryEntry) -> Unit,
    private val onDownloadSessionClick: ((HistorySession) -> Unit)? = null  // ← 新增，默认 null 兼容旧调用
) : ListAdapter<HistoryGroup, HistoryGroupAdapter.GroupViewHolder>(GroupDiffCallback()) {
```

- [ ] **Step 3: SessionAdapter 传递回调**

修改 `SessionAdapter` 的构造和 `onBindViewHolder`：

```kotlin
private inner class SessionAdapter(
    private val onItemClick: (HistoryEntry) -> Unit,
    private val onItemLongClick: (HistoryEntry) -> Unit,
    private val onDownloadSessionClick: ((HistorySession) -> Unit)?
) : ListAdapter<HistorySession, SessionAdapter.SessionViewHolder>(SessionDiffCallback()) {

    // ...

    inner class SessionViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvSessionHeader: TextView = view.findViewById(R.id.tvSessionHeader)
        private val btnDownload: TextView = view.findViewById(R.id.btnDownloadSession)
        private val rvEntries: RecyclerView = view.findViewById(R.id.rvSessionEntries)

        fun bind(session: HistorySession) {
            val startTime = timeFormat.format(Date(session.startTime))
            val endTime = timeFormat.format(Date(session.endTime))
            tvSessionHeader.text = "$startTime - $endTime (${session.entries.size})"
            
            btnDownload.visibility = if (onDownloadSessionClick != null) View.VISIBLE else View.GONE
            btnDownload.setOnClickListener { onDownloadSessionClick?.invoke(session) }

            val entryAdapter = HistoryGameAdapter(onItemClick, onItemLongClick)
            rvEntries.layoutManager = LinearLayoutManager(itemView.context)
            rvEntries.adapter = entryAdapter
            entryAdapter.submitList(session.entries)
        }
    }
}
```

- [ ] **Step 4: 更新 SessionAdapter 实例化**

在 `GroupViewHolder.bind()` 中：

```kotlin
val sessionAdapter = SessionAdapter(onItemClick, onItemLongClick, onDownloadSessionClick)
```

- [ ] **Step 5: 构建验证**

```bash
./gradlew assembleDebug
```

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/moe/starflow/ui/history/HistoryGroupAdapter.kt app/src/main/res/layout/item_history_session.xml
git commit -m "feat: 游戏历史组标题栏添加下载 txt 按钮"
```

---

### Task 9: item_translation_detail.xml — textIsSelectable

**Files:**
- Modify: `app/src/main/res/layout/item_translation_detail.xml`

**Interfaces:**
- None — pure XML attribute change

- [ ] **Step 1: 添加 textIsSelectable**

在 `tvOcrText` 和 `tvTranslatedText` 上各加一行：

```xml
<TextView
    android:id="@+id/tvOcrText"
    ...
    android:textIsSelectable="true" />   <!-- ← 新增 -->

<TextView
    android:id="@+id/tvTranslatedText"
    ...
    android:textIsSelectable="true" />   <!-- ← 新增 -->
```

- [ ] **Step 2: 构建验证**

```bash
./gradlew assembleDebug
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/res/layout/item_translation_detail.xml
git commit -m "feat: 漫画译文详情文本可选择复制"
```

---

### Task 10: 最终构建 + 集成验证

- [ ] **Step 1: 完整构建**

```bash
./gradlew clean assembleDebug
```

- [ ] **Step 2: 检查 lint 警告**

```bash
./gradlew lint
```

- [ ] **Step 3: 安装测试**

```powershell
& "C:\Users\xjj20\AppData\Local\Android\Sdk\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk
```

- [ ] **Step 4: 手动测试清单**
  - [ ] 个性化设置 → 文字阴影开关（开/关后游戏翻译结果生效）
  - [ ] 个性化设置 → 字体颜色恢复默认按钮
  - [ ] 个性化设置 → 背景颜色恢复默认按钮
  - [ ] 游戏翻译 → 左下角复制按钮始终显示
  - [ ] 游戏翻译 → 缓存命中时 ⚡ 在前、retranslate 按钮仍显示
  - [ ] 漫画翻译 → 结果覆盖层右下角 📋 按钮
  - [ ] 漫画翻译 → 点击 📋 进入复制模式（气泡框 + 可点击 + 🔄📄按钮）
  - [ ] 漫画翻译 → 点击气泡复制对应文本
  - [ ] 漫画翻译 → 📄 复制全部
  - [ ] 漫画翻译 → 再次 📋 退出复制模式
  - [ ] 漫画翻译缓存命中 → 📋 按钮显示，📄 复制全部可用
  - [ ] 历史 → 游戏 tab 点击条目 → 复制原文+译文
  - [ ] 历史 → 游戏 tab 会话标题 ⬇ → 下载 txt
  - [ ] 历史 → 漫画 tab 图片浏览 → 底部译文详情可长按选择复制

- [ ] **Step 5: Commit (如有修复)**

```bash
git add -A
git commit -m "chore: 集成验证修复"
```

