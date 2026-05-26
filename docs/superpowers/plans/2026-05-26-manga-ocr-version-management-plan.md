# manga-ocr 版本管理实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现模型管理页面 manga-ocr 版本列表 UI，支持多版本同时下载/删除，选择当前使用版本；修复下载网络问题和 assets 模型 OCR 报错

**Architecture:**
- 每个版本独立目录：`filesDir/manga_ocr_download/{FULL,FP16,QUANTIZED}/`
- 配置项 `Manga_OCR_Active_Version` 存储当前使用的版本
- 模型管理页面显示三个版本的状态和操作按钮
- 悬浮窗读取配置加载对应版本

**Tech Stack:** Kotlin, Android, ONNX Runtime, SharedPreferences

---

## 文件变更概览

| 文件 | 修改内容 |
|------|----------|
| `MangaOcrDownloadManager.kt` | 新增 `getActiveVersion()`、`setActiveVersion()`、`isVersionDownloaded()` 带版本参数、`deleteVersion()` |
| `MangaOcrDownloadManager.kt` | 下载 URL 添加 ghproxy 镜像支持 |
| `ModelManagementFragment.kt` | 完全重构 manga-ocr UI 为列表样式（三个版本） |
| `fragment_model_management.xml` | 重构布局为三个版本列表行 |
| `MangaFloatingService.kt` | `ensureMangaOcrInitialized()` 读取 `Manga_OCR_Active_Version` 配置 |
| `CustomPreference.kt` | 新增 `Manga_OCR_Active_Version` 配置读写方法 |

---

## Task 1: MangaOcrDownloadManager 添加 ActiveVersion 配置和 ghproxy 镜像

**Files:**
- Modify: `app/src/main/java/com/moe/moetranslator/manga/MangaOcrDownloadManager.kt`

- [ ] **Step 1: 添加 ghproxy 镜像支持**

当前 URL:
```kotlin
private const val HF_BASE_URL = "https://huggingface.co/onnx-community/manga-ocr-base-ONNX/resolve/main/onnx"
```

修改为（中国大陆镜像）:
```kotlin
private const val HF_BASE_URL = "https://huggingface.co/onnx-community/manga-ocr-base-ONNX/resolve/main/onnx"
private const val HF_MIRROR_URL = "https://ghproxy.cn/https://huggingface.co/onnx-community/manga-ocr-base-ONNX/resolve/main/onnx"
```

在 `downloadModel()` 方法中，使用镜像 URL:
```kotlin
val encoderUrl = "$HF_MIRROR_URL/${version.encoderFile}"
val decoderUrl = "$HF_MIRROR_URL/${version.decoderFile}"
```

- [ ] **Step 2: 添加 getActiveVersion/setActiveVersion 方法**

```kotlin
/**
 * 获取当前使用的版本配置
 */
fun getActiveVersion(context: Context): ModelVersion? {
    val prefs = context.getSharedPreferences("manga_ocr_prefs", Context.MODE_PRIVATE)
    val versionName = prefs.getString("active_version", null) ?: return null
    return try {
        ModelVersion.valueOf(versionName)
    } catch (e: Exception) {
        null
    }
}

/**
 * 设置当前使用的版本
 */
fun setActiveVersion(context: Context, version: ModelVersion) {
    val prefs = context.getSharedPreferences("manga_ocr_prefs", Context.MODE_PRIVATE)
    prefs.edit().putString("active_version", version.name).apply()
}
```

- [ ] **Step 3: 添加带版本参数的 isVersionDownloaded**

现有方法 `isVersionDownloaded(context, version)` 已存在，确认签名正确。

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/com/moe/moetranslator/manga/MangaOcrDownloadManager.kt
git commit -m "feat(MangaOcrDownloadManager): add ghproxy mirror and ActiveVersion config"
```

---

## Task 2: ModelManagementFragment 重构为版本列表 UI

**Files:**
- Modify: `app/src/main/java/com/moe/moetranslator/me/ModelManagementFragment.kt`
- Modify: `app/src/main/res/layout/fragment_model_management.xml`

- [ ] **Step 1: 修改 fragment_model_management.xml**

将现有的 manga-ocr 单行布局替换为三个版本的列表布局：

```xml
<!-- manga-ocr 版本列表容器 -->
<LinearLayout
    android:id="@+id/manga_ocr_version_list"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical">

    <!-- 版本 1: FULL -->
    <include layout="@layout/manga_ocr_version_item" android:id="@+id/manga_ocr_full_row"/>
    <!-- 版本 2: FP16 -->
    <include layout="@layout/manga_ocr_version_item" android:id="@+id/manga_ocr_fp16_row"/>
    <!-- 版本 3: QUANTIZED -->
    <include layout="@layout/manga_ocr_version_item" android:id="@+id/manga_ocr_quantized_row"/>
</LinearLayout>
```

创建 `manga_ocr_version_item.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="horizontal"
    android:padding="8dp"
    android:gravity="center_vertical">

    <LinearLayout
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:orientation="vertical">

        <TextView
            android:id="@+id/version_name"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:textSize="14sp"/>

        <TextView
            android:id="@+id/version_size"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:textSize="12sp"
            android:textColor="#888"/>
    </LinearLayout>

    <TextView
        android:id="@+id/version_status"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:textSize="12sp"
        android:layout_marginEnd="8dp"/>

    <Button
        android:id="@+id/version_action_button"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="@string/model_download"/>
</LinearLayout>
```

- [ ] **Step 2: 修改 ModelManagementFragment.kt**

完全重构 `updateMangaOcrStatus()` 为三个版本分别更新：

```kotlin
private fun updateMangaOcrStatus() {
    for (version in MangaOcrDownloadManager.ModelVersion.entries) {
        updateMangaOcrVersionStatus(version)
    }
}

private fun updateMangaOcrVersionStatus(version: MangaOcrDownloadManager.ModelVersion) {
    val rowView = when (version) {
        ModelVersion.FULL -> rootView.findViewById<View>(R.id.manga_ocr_full_row)
        ModelVersion.FP16 -> rootView.findViewById<View>(R.id.manga_ocr_fp16_row)
        ModelVersion.QUANTIZED -> rootView.findViewById<View>(R.id.manga_ocr_quantized_row)
    }

    val nameText = rowView.findViewById<TextView>(R.id.version_name)
    val sizeText = rowView.findViewById<TextView>(R.id.version_size)
    val statusText = rowView.findViewById<TextView>(R.id.version_status)
    val actionBtn = rowView.findViewById<Button>(R.id.version_action_button)

    nameText.text = version.description.split(" (")[0]  // "完整版 (343MB+117MB)" -> "完整版"
    sizeText.text = version.description.substringAfter("(").substringBefore(")")

    val isDownloaded = MangaOcrDownloadManager.isVersionDownloaded(requireContext(), version)
    val isActive = MangaOcrDownloadManager.getActiveVersion(requireContext()) == version

    when {
        isActive -> {
            statusText.text = "当前使用✓"
            actionBtn.text = getString(R.string.model_delete)
            actionBtn.setOnClickListener { showDeleteConfirmDialog(version) }
        }
        isDownloaded -> {
            statusText.text = "已下载"
            actionBtn.text = getString(R.string.model_delete)
            actionBtn.setOnClickListener { showDeleteConfirmDialog(version) }
        }
        else -> {
            statusText.text = ""
            actionBtn.text = getString(R.string.model_download)
            actionBtn.setOnClickListener { startMangaOcrDownload(version) }
        }
    }

    // 点击行设置当前使用版本
    rowView.setOnClickListener {
        if (MangaOcrDownloadManager.isVersionDownloaded(requireContext(), version)) {
            MangaOcrDownloadManager.setActiveVersion(requireContext(), version)
            updateMangaOcrStatus()
            Toast.makeText(requireContext(), "已选择 ${version.description}", Toast.LENGTH_SHORT).show()
        }
    }
}
```

- [ ] **Step 3: 修改下载和删除方法支持版本参数**

```kotlin
private fun startMangaOcrDownload(version: MangaOcrDownloadManager.ModelVersion) {
    // 使用指定版本下载
    mangaOcrDownloadJob = lifecycleScope.launch(Dispatchers.IO) {
        val result = MangaOcrDownloadManager.downloadModel(requireContext(), version, ...)
        // ...
    }
}

private fun showDeleteConfirmDialog(version: MangaOcrDownloadManager.ModelVersion) {
    // 显示删除确认对话框
    AlertDialog.Builder(requireContext())
        .setTitle(R.string.model_delete)
        .setMessage(getString(R.string.model_delete_confirm, version.description))
        .setPositiveButton(R.string.confirm) { _, _ -> deleteMangaOcrModel(version) }
        .setNegativeButton(R.string.user_cancel, null)
        .show()
}

private fun deleteMangaOcrModel(version: MangaOcrDownloadManager.ModelVersion) {
    lifecycleScope.launch(Dispatchers.IO) {
        val result = MangaOcrDownloadManager.deleteVersion(requireContext(), version)
        // ...
    }
}
```

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/com/moe/moetranslator/me/ModelManagementFragment.kt
git add app/src/main/res/layout/fragment_model_management.xml
git add app/src/main/res/layout/manga_ocr_version_item.xml
git commit -m "feat(ModelManagementFragment): redesign to version list UI"
```

---

## Task 3: 悬浮窗读取 ActiveVersion 配置

**Files:**
- Modify: `app/src/main/java/com/moe/moetranslator/manga/MangaFloatingService.kt`

- [ ] **Step 1: 修改 ensureMangaOcrInitialized**

```kotlin
private suspend fun ensureMangaOcrInitialized() {
    if (MangaOcrRecognizer.isInitialized) return

    val currentConfig = loadConfig()

    when (currentConfig.ocrEngine) {
        OcrEngine.MangaOcr -> {
            val activeVersion = MangaOcrDownloadManager.getActiveVersion(this)
            if (activeVersion != null && MangaOcrDownloadManager.isVersionDownloaded(this, activeVersion)) {
                MangaOcrBridge.initializeDownloaded(this, activeVersion)
            } else {
                Toast.makeText(applicationContext, R.string.manga_ocr_download_required, Toast.LENGTH_LONG).show()
                return
            }
        }
        OcrEngine.MangaOcrAssets -> {
            MangaOcrRecognizer.initialize(this, useAssets = true)
        }
        else -> return
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add app/src/main/java/com/moe/moetranslator/manga/MangaFloatingService.kt
git commit -m "feat(MangaFloatingService): read ActiveVersion from config"
```

---

## Task 4: MangaOcrAssets OCR 报错修复（调查）

**Files:**
- Modify: `app/src/main/java/com/moe/moetranslator/manga/MangaOcrRecognizer.kt`

**问题分析：**
错误 `Got invalid dimensions for input: input_ids for the following indices index: 1 Got: 2 Expected: 1` 表示 batch size 不匹配。

**调查步骤：**
- 检查 `runDecoderWithSession` 是否在批量识别时被调用
- 检查 `useAssets=true` 路径加载的文件是否正确
- 检查是否是 `recognizeBatch` 调用了多次 decoder

**修复可能方向：**
1. 如果问题出在 assets 模型本身，可能需要重新导出
2. 如果问题出在代码，检查 decoder 输入的 batch dimension 是否正确

**注：** 由于这是测试用 assets 模型，优先确保下载版本正常工作。如无法修复，记录但不阻塞主功能。

- [ ] **Step 1: 添加详细日志**

在 `runDecoderWithSession` 添加输入维度日志：
```kotlin
LogCollector.d(TAG, "Decoder input: currentIds.size=${currentIds.size}, shape=[1, ${inputIdsArray.size}]")
```

- [ ] **Step 2: 检查 assets 文件是否正确**

确认 `copyAssetToCache` 复制的文件与 assets 原始文件一致。

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/com/moe/moetranslator/manga/MangaOcrRecognizer.kt
git commit -m "debug(MangaOcrRecognizer): add decoder input dimension logging"
```

---

## Task 5: 编译验证

- [ ] **Step 1: 运行 assembleDebug**

```bash
./gradlew assembleDebug
```

预期：BUILD SUCCESSFUL

---

## 依赖关系

```
Task 1 (MangaOcrDownloadManager)
    ↓
Task 2 (ModelManagementFragment UI) ← Task 1 的新方法
    ↓
Task 3 (悬浮窗读取配置) ← Task 1 的 getActiveVersion
    ↓
Task 4 (OCR 报错修复) ← 独立调查
    ↓
Task 5 (编译验证)
```

---

## 待验证场景

1. **下载 FULL 版本** → UI 显示 FULL 已下载，其他未下载
2. **再下载 FP16 版本** → UI 显示 FULL + FP16 已下载
3. **点击 FULL 行** → 设置为当前使用，显示"当前使用✓"
4. **悬浮窗 manga-ocr（下载版）** → 读取配置使用 FULL 版本
5. **MangaOcrAssets** → 使用 assets 模型（测试用）