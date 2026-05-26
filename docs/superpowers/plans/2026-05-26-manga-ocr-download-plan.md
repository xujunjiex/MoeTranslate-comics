# manga-ocr 下载管理实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现从 HuggingFace 下载 manga-ocr ONNX 模型（FULL/FP16/QUANTIZED 版本），支持多版本共存，下载后可在设置中选择使用哪个版本

**Architecture:**
- 下载目录：`filesDir/manga_ocr_download/{VERSION}/`（每个版本独立目录）
- 下载文件：`encoder_model.onnx` + `decoder_model.onnx`（文件名与内置一致，内容不同）
- 内置 manga-ocr（assets）保持不变，仅用于测试；下载模型用于正式版
- 识别引擎选择 manga-ocr 时，显示版本子选项（从属关系）

**Tech Stack:** Kotlin, Android, ONNX Runtime, SharedPreferences

---

## 文件变更概览

| 文件 | 修改内容 |
|------|----------|
| `MangaOcrDownloadManager.kt` | 新增版本目录支持方法，修改文件命名为通用名称 |
| `MangaOcrRecognizer.kt` | `initialize()` 新增 `version: ModelVersion?` 参数，支持 useAssets=false |
| `MangaOcrBridge.kt` | 新增 `initializeDownloaded()` 初始化下载的模型 |
| `ModelManagementFragment.kt` | 下载完成后显示版本信息 |
| `PersonalizationConfig.kt` | 新增 `manga_rec_model_version` ListPreference |
| `arrays.xml` (values/) | 新增 `manga_ocr_version_entries` 和 `manga_ocr_version_values` |
| `arrays.xml` (values-en/) | 同步英文版本数组 |
| `strings.xml` | 新增版本子选项相关字符串 |
| `personalization.xml` | 新增 `manga_rec_model_version` ListPreference 条目 |

---

## Task 1: MangaOcrDownloadManager 版本目录支持

**Files:**
- Modify: `app/src/main/java/com/moe/moetranslator/manga/MangaOcrDownloadManager.kt`

- [ ] **Step 1: 修改文件命名为通用名称**

现有 `ENCODER_FILE = "encoder_model.onnx"` 已是通用名称，无需修改。检查 `ModelVersion` 枚举的 `description` 字段更新为中文显示。

当前代码（确认）：
```kotlin
enum class ModelVersion(val encoderFile: String, val decoderFile: String, val description: String) {
    FULL("encoder_model.onnx", "decoder_model.onnx", "完整版 (343MB+117MB)"),
    FP16("encoder_model_fp16.onnx", "decoder_model_fp16.onnx", "半精度 (172MB+59MB)"),
    QUANTIZED("encoder_model_quantized.onnx", "decoder_model_int8.onnx", "量化版 (87MB+30MB)")
}
```

`description` 字段已是中文，后续 UI 显示使用此字段。

- [ ] **Step 2: 新增版本目录方法**

在 `MangaOcrDownloadManager` 中添加以下方法：

```kotlin
/**
 * 获取指定版本的模型目录
 */
fun getModelDir(context: Context, version: ModelVersion): File {
    return File(context.filesDir, "$MODEL_DIR/${version.name}")
}

/**
 * 获取指定版本的 encoder 文件路径
 */
fun getEncoderFile(context: Context, version: ModelVersion): File {
    return File(getModelDir(context, version), ENCODER_FILE)
}

/**
 * 获取指定版本的 decoder 文件路径
 */
fun getDecoderFile(context: Context, version: ModelVersion): File {
    return File(getModelDir(context, version), DECODER_FILE)
}

/**
 * 检查指定版本是否已下载（两个文件都存在且大小 > 1000）
 */
fun isVersionDownloaded(context: Context, version: ModelVersion): Boolean {
    val encoder = getEncoderFile(context, version)
    val decoder = getDecoderFile(context, version)
    return encoder.exists() && decoder.exists() && encoder.length() > 1000
}

/**
 * 获取已下载的模型版本（遍历查找哪个版本已下载）
 */
fun getDownloadedVersion(context: Context): ModelVersion? {
    return ModelVersion.entries.firstOrNull { isVersionDownloaded(context, it) }
}

/**
 * 删除指定版本的模型
 */
fun deleteVersion(context: Context, version: ModelVersion): Result<Unit> {
    return try {
        val modelDir = getModelDir(context, version)
        if (modelDir.exists()) {
            val deleted = modelDir.deleteRecursively()
            if (!deleted) {
                LogCollector.e(TAG, "删除模型失败: ${modelDir.absolutePath}")
                return Result.failure(Exception("Failed to delete model directory"))
            }
        }
        LogCollector.d(TAG, "版本 ${version.name} 模型已删除")
        Result.success(Unit)
    } catch (e: Exception) {
        LogCollector.e(TAG, "删除模型失败", e)
        Result.failure(e)
    }
}
```

- [ ] **Step 3: 修改 downloadModel 支持版本目录**

修改 `downloadModel()` 方法，将下载文件保存到版本目录：

```kotlin
suspend fun downloadModel(
    context: Context,
    version: ModelVersion = ModelVersion.FULL,
    onProgress: ModelDownloadManager.ProgressCallback? = null
): Result<Unit> {
    val modelDir = getModelDir(context, version)  // 改为版本目录
    // ... 其余逻辑不变，encoderFile 和 decoderFile 使用版本目录下的文件
}
```

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/com/moe/moetranslator/manga/MangaOcrDownloadManager.kt
git commit -m "feat(MangaOcrDownloadManager): support version-specific directories"
```

---

## Task 2: MangaOcrRecognizer 支持下载模型

**Files:**
- Modify: `app/src/main/java/com/moe/moetranslator/manga/MangaOcrRecognizer.kt:46-95`

- [ ] **Step 1: 修改 initialize 方法签名**

原方法：
```kotlin
suspend fun initialize(context: Context, modelDir: String = "manga_ocr", useAssets: Boolean = true)
```

修改为：
```kotlin
suspend fun initialize(context: Context, modelDir: String = "manga_ocr", useAssets: Boolean = true, version: MangaOcrDownloadManager.ModelVersion? = null)
```

- [ ] **Step 2: 修改加载逻辑**

```kotlin
// 加载 encoder
val encoderPath = when {
    useAssets -> copyAssetToCache(context, "$modelDir/manga_ocr_encoder.onnx")
    version != null -> MangaOcrDownloadManager.getEncoderFile(context, version).absolutePath
    else -> "$modelDir/manga_ocr_encoder.onnx"
}

// 加载 decoder
val decoderPath = when {
    useAssets -> copyAssetToCache(context, "$modelDir/manga_ocr_decoder.onnx")
    version != null -> MangaOcrDownloadManager.getDecoderFile(context, version).absolutePath
    else -> "$modelDir/manga_ocr_decoder.onnx"
}
```

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/com/moe/moetranslator/manga/MangaOcrRecognizer.kt
git commit -m "feat(MangaOcrRecognizer): support loading downloaded model with version"
```

---

## Task 3: MangaOcrBridge 新增初始化下载模型方法

**Files:**
- Modify: `app/src/main/java/com/moe/moetranslator/manga/MangaOcrBridge.kt`

- [ ] **Step 1: 新增 initializeDownloaded 方法**

在 `MangaOcrBridge` 中添加：

```kotlin
/**
 * 初始化下载的 manga-ocr 模型
 *
 * @param context Context
 * @param version 模型版本（FULL/FP16/QUANTIZED）
 */
suspend fun initializeDownloaded(context: Context, version: MangaOcrDownloadManager.ModelVersion) {
    MangaOcrRecognizer.initialize(context, useAssets = false, version = version)
}
```

- [ ] **Step 2: 修改 isAvailable 检查逻辑**

现有 `isAvailable()` 只检查 `MangaOcrRecognizer.isInitialized`。下载模型时需要额外检查配置：

```kotlin
fun isAvailable(): Boolean {
    return MangaOcrRecognizer.isInitialized
}
```

保持不变，因为 `isInitialized` 在两种模式下都会设置。

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/com/moe/moetranslator/manga/MangaOcrBridge.kt
git commit -m "feat(MangaOcrBridge): add initializeDownloaded for downloaded models"
```

---

## Task 4: ModelManagementFragment 显示版本信息

**Files:**
- Modify: `app/src/main/java/com/moe/moetranslator/me/ModelManagementFragment.kt`

- [ ] **Step 1: 修改 updateMangaOcrStatus 显示版本**

在 `updateMangaOcrStatus()` 中，当 `isDownloaded == true` 时，显示版本描述：

当前代码：
```kotlin
isDownloaded -> {
    mangaOcrStatus.text = getString(R.string.model_downloaded)
    mangaOcrSizeText?.text = MangaOcrDownloadManager.getModelSizeString(requireContext())
```

修改为：
```kotlin
isDownloaded -> {
    val version = MangaOcrDownloadManager.getDownloadedVersion(requireContext())
    val versionDesc = version?.description ?: "已下载"
    mangaOcrStatus.text = getString(R.string.model_downloaded)
    mangaOcrSizeText?.text = "$versionDesc - ${MangaOcrDownloadManager.getModelSizeString(requireContext())}"
```

- [ ] **Step 2: 提交**

```bash
git add app/src/main/java/com/moe/moetranslator/me/ModelManagementFragment.kt
git commit -m "feat(ModelManagementFragment): show downloaded version info"
```

---

## Task 5: PersonalizationConfig 添加版本子选项

**Files:**
- Modify: `app/src/main/java/com/moe/moetranslator/me/PersonalizationConfig.kt`
- Modify: `app/src/main/res/xml/personalization.xml`
- Modify: `app/src/main/res/values/arrays.xml`
- Modify: `app/src/main/res/values-en/arrays.xml`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: 在 arrays.xml 添加版本选择数组**

在 `values/arrays.xml` 中已有：
```xml
<string-array name="manga_ocr_version_entries">
    <item>完整版 (460MB)</item>
    <item>半精度 FP16 (231MB)</item>
    <item>量化版 (117MB)</item>
</string-array>
<string-array name="manga_ocr_version_values">
    <item>FULL</item>
    <item>FP16</item>
    <item>QUANTIZED</item>
</string-array>
```

确认 `values-en/arrays.xml` 同步：
```xml
<string-array name="manga_ocr_version_entries">
    <item>Full (460MB)</item>
    <item>FP16 (231MB)</item>
    <item>Quantized (117MB)</item>
</string-array>
<string-array name="manga_ocr_version_values">
    <item>FULL</item>
    <item>FP16</item>
    <item>QUANTIZED</item>
</string-array>
```

- [ ] **Step 2: 在 personalization.xml 添加 ListPreference**

在 `res/xml/personalization.xml` 中的 `manga_rec_model` 条目之后添加：

```xml
<ListPreference
    android:key="manga_rec_model_version"
    android:title="@string/manga_rec_model_version_title"
    android:entries="@array/manga_ocr_version_entries"
    android:entryValues="@array/manga_ocr_version_values"
    android:defaultValue="FULL"
    android:dependency="manga_rec_model" />
```

- [ ] **Step 3: 在 strings.xml 添加字符串**

```xml
<string name="manga_rec_model_version_title">识别模型版本</string>
<string name="manga_rec_model_version_summary">当前选择: %s</string>
```

- [ ] **Step 4: 在 PersonalizationConfig.kt 添加版本选择逻辑**

在 `onCreatePreferences` 中添加：

```kotlin
val mangaRecModelVersion = findPreference<ListPreference>("manga_rec_model_version")!!

mangaRecModelVersion.setOnPreferenceChangeListener { _, newValue ->
    prefs.setString("Manga_Rec_Model_Version", newValue as String)
    true
}
mangaRecModelVersion.summaryProvider = Preference.SummaryProvider<ListPreference> { pref ->
    val entry = pref.entry ?: "完整版"
    getString(R.string.manga_rec_model_version_summary, entry)
}
```

还需要在 `mangaRecModel` 的 `setOnPreferenceChangeListener` 中更新版本选择的可用性：

```kotlin
mangaRecModel.setOnPreferenceChangeListener { _, newValue ->
    prefs.setInt("Manga_Rec_Model", newValue.toString().toInt())
    // 当选择 manga-ocr 时启用版本选择
    mangaRecModelVersion.isEnabled = newValue.toString().toInt() == OcrEngine.MangaOcr.value
    true
}
```

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/moe/moetranslator/me/PersonalizationConfig.kt
git add app/src/main/res/xml/personalization.xml
git add app/src/main/res/values/arrays.xml
git add app/src/main/res/values-en/arrays.xml
git add app/src/main/res/values/strings.xml
git commit -m "feat(PersonalizationConfig): add manga-ocr version sub-selection"
```

---

## Task 6: 运行时初始化逻辑修改

**Files:**
- Modify: `app/src/main/java/com/moe/moetranslator/manga/MangaFloatingService.kt`

- [ ] **Step 1: 修改 MangaOcrRecognizer 初始化时机**

在 `MangaFloatingService` 的截图处理流程中，根据配置决定使用内置还是下载的 manga-ocr。

查找 `MangaOcrRecognizer.initialize` 的调用位置，在 `MangaFloatingService` 中添加：

```kotlin
// 在服务启动或首次使用时初始化
// 根据配置选择使用内置还是下载的模型
private suspend fun ensureMangaOcrInitialized() {
    if (MangaOcrRecognizer.isInitialized) return

    val config = loadMangaModeConfig()
    when (config.ocrEngine) {
        OcrEngine.MangaOcr -> {
            // 优先使用下载的模型
            val downloadedVersion = MangaOcrDownloadManager.getDownloadedVersion(requireContext())
            if (downloadedVersion != null) {
                MangaOcrBridge.initializeDownloaded(requireContext(), downloadedVersion)
            } else if (MangaOcrBridge.isAvailableForAssets()) {
                // 回退到内置模型（仅测试用）
                MangaOcrRecognizer.initialize(requireContext(), useAssets = true)
            }
        }
        else -> { /* MLKit 或 CTCOcr，不需要 manga-ocr */ }
    }
}
```

注意：`isAvailableForAssets()` 是假设性方法，实际应该直接调用 `MangaOcrRecognizer.isInitialized` 或添加一个检查 assets 模型是否存在的逻辑。

- [ ] **Step 2: 提交**

```bash
git add app/src/main/java/com/moe/moetranslator/manga/MangaFloatingService.kt
git commit -m "feat(MangaFloatingService): initialize manga-ocr based on config"
```

---

## Task 7: 编译验证

- [ ] **Step 1: 运行 assembleDebug 构建**

```bash
./gradlew assembleDebug
```

预期：构建成功，无编译错误

- [ ] **Step 2: 如有编译错误，修复后重新构建**

---

## 依赖关系

```
Task 1 (MangaOcrDownloadManager)
    ↓
Task 2 (MangaOcrRecognizer) ← Task 1 的版本方法
    ↓
Task 3 (MangaOcrBridge) ← Task 2 的初始化参数
    ↓
Task 6 (MangaFloatingService) ← Task 3 的新方法
    ↑
Task 4 (ModelManagementFragment) ← Task 1 的 getDownloadedVersion
    ↑
Task 5 (PersonalizationConfig) ← UI 层，与 Task 6 独立
```

**Task 5 和 Task 6 可并行实施，但 Task 6 依赖 Task 3。**

---

## 待验证场景

1. **下载完整版后，选择完整版识别** → 使用 `filesDir/manga_ocr_download/FULL/` 的模型
2. **下载多个版本（FULL + FP16）** → 两个版本目录独立存在，设置中选择使用哪个
3. **未下载时选择 manga-ocr** → 提示下载或回退到内置（测试用）
4. **内置 manga-ocr** → 仅用于测试，保持现有逻辑不变