# 模型管理功能 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 添加设置菜单中的模型管理功能，支持 CTCOcr 模型下载/删除，显示下载速度

**Architecture:** 创建 ModelManagementFragment 作为独立设置页面，修复 CtcOcrRecognizer 加载逻辑，添加下载速度显示

**Tech Stack:** Kotlin (Android), AndroidX Fragment, Coroutines

---

## File Structure

```
app/src/main/java/com/moe/moetranslator/
├── me/
│   ├── ModelManagementFragment.kt  # Create: 模型管理页面
│   ├── AboutMe.kt                   # Modify: 添加"模型管理"按钮
│   └── SettingPageActivity.kt       # Modify: 添加 TYPE_FRAGMENT_MODEL_MANAGEMENT
├── manga/
│   ├── ModelDownloadManager.kt       # Modify: 添加下载速度
│   ├── CtcOcrModelManager.kt         # Modify: 添加删除模型功能
│   ├── CtcOcrRecognizer.kt           # Modify: 修复 useAssets=false 加载
│   └── MangaFloatingService.kt       # Modify: 缺失模型时提示
app/src/main/res/layout/
└── fragment_model_management.xml    # Create: 模型管理页面布局
```

---

## Task 1: 修改 ModelDownloadManager 添加下载速度

**Files:**
- Modify: `app/src/main/java/com/moe/moetranslator/manga/ModelDownloadManager.kt:29-31`

### Step 1: 修改 ProgressCallback 接口

```kotlin
interface ProgressCallback {
    fun onProgress(bytesRead: Long, totalBytes: Long, speed: Float)
}
```

### Step 2: 在下载循环中计算速度

在 `downloadModel` 函数中，添加速度计算：

```kotlin
private const val TAG = "ModelDownloadManager"
private const val SPEED_UPDATE_INTERVAL = 500L  // 每 500ms 更新一次速度

suspend fun downloadModel(...): Result<Unit> {
    // ... 现有代码 ...
    var lastUpdateTime = System.currentTimeMillis()
    var lastBytesRead = existingSize
    var speed = 0f

    while (inputStream.read(buffer).also { read = it } != -1) {
        outputStream.write(buffer, 0, read)
        bytesRead += read

        val currentTime = System.currentTimeMillis()
        val elapsed = currentTime - lastUpdateTime
        if (elapsed >= SPEED_UPDATE_INTERVAL) {
            val bytesDelta = bytesRead - lastBytesRead
            speed = (bytesDelta.toFloat() / elapsed) * 1000f / (1024f * 1024f)  // MB/s
            lastUpdateTime = currentTime
            lastBytesRead = bytesRead
        }
        onProgress?.onProgress(bytesRead, totalBytes, speed)
    }
    // ... 现有代码 ...
}
```

### Step 3: 验证编译

Run: `./gradlew assembleDebug --no-daemon -q 2>&1 | head -20`
Expected: BUILD SUCCESSFUL

### Step 4: Commit

```bash
git add app/src/main/java/com/moe/moetranslator/manga/ModelDownloadManager.kt
git commit -m "feat(ModelDownloadManager): add download speed to ProgressCallback"
```

---

## Task 2: 修改 CtcOcrModelManager 添加删除模型功能

**Files:**
- Modify: `app/src/main/java/com/moe/moetranslator/manga/CtcOcrModelManager.kt`

### Step 1: 添加 deleteModel 函数

在 `CtcOcrModelManager` 对象中添加：

```kotlin
/**
 * 删除已下载的模型
 */
fun deleteModel(context: Context): Result<Unit> {
    return try {
        val modelDir = getModelDir(context)
        if (modelDir.exists()) {
            modelDir.deleteRecursively()
        }
        LogCollector.d(TAG, "模型已删除: ${modelDir.absolutePath}")
        Result.success(Unit)
    } catch (e: Exception) {
        LogCollector.e(TAG, "删除模型失败", e)
        Result.failure(e)
    }
}

/**
 * 获取模型大小
 */
fun getModelSize(context: Context): Long {
    val modelDir = getModelDir(context)
    if (!modelDir.exists()) return 0
    return modelDir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
}

/**
 * 获取模型大小描述
 */
fun getModelSizeString(context: Context): String {
    val size = getModelSize(context)
    return when {
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> String.format("%.1f KB", size / 1024.0)
        else -> String.format("%.1f MB", size / (1024.0 * 1024.0))
    }
}
```

### Step 2: 验证编译

Run: `./gradlew assembleDebug --no-daemon -q 2>&1 | head -20`
Expected: BUILD SUCCESSFUL

### Step 3: Commit

```bash
git add app/src/main/java/com/moe/moetranslator/manga/CtcOcrModelManager.kt
git commit -m "feat(CtcOcrModelManager): add delete model and get model size functions"
```

---

## Task 3: 修复 CtcOcrRecognizer 加载逻辑

**Files:**
- Modify: `app/src/main/java/com/moe/moetranslator/manga/CtcOcrRecognizer.kt:36-51`

### Step 1: 修改 initialize 函数

修改 `initialize` 函数，当模型文件存在于 filesDir 时，使用 `useAssets=false`：

```kotlin
suspend fun initialize(context: Context, modelDir: String = "ocr_ctc", useAssets: Boolean = true) {
    if (isInitialized) return

    // 如果 filesDir 中已有模型文件，优先从 filesDir 加载
    val actualUseAssets = if (!useAssets) {
        // 显式指定 useAssets=false，从 filesDir 加载
        false
    } else {
        // 检查 filesDir 是否有模型文件
        val modelFile = File(context.filesDir, "$modelDir/${CtcOcrModelManager.MODEL_FILE}")
        if (modelFile.exists()) {
            LogCollector.d(TAG, "检测到 filesDir 中已有模型文件，优先从 filesDir 加载")
            false
        } else {
            // 从 assets 加载（首次使用，模型在 assets 中）
            true
        }
    }

    try {
        LogCollector.d(TAG, "开始初始化 48px_ctc 模型 (useAssets=$actualUseAssets)...")
        // ... 后续代码使用 actualUseAssets
    }
}
```

### Step 2: 修改模型路径获取逻辑

```kotlin
val modelPath = if (actualUseAssets) {
    copyAssetToCache(context, "$modelDir/${CtcOcrModelManager.MODEL_FILE}")
} else {
    File(context.filesDir, "$modelDir/${CtcOcrModelManager.MODEL_FILE}").absolutePath
}
```

### Step 3: 验证编译

Run: `./gradlew assembleDebug --no-daemon -q 2>&1 | head -20`
Expected: BUILD SUCCESSFUL

### Step 4: Commit

```bash
git add app/src/main/java/com/moe/moetranslator/manga/CtcOcrRecognizer.kt
git commit -m "fix(CtcOcrRecognizer): fix model loading from filesDir when useAssets=false"
```

---

## Task 4: 添加字符串资源

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh/strings.xml`
- Modify: `app/src/main/res/values-en/strings.xml`

### Step 1: 添加字符串资源

在 `strings.xml` 中添加：

```xml
<!-- 模型管理 -->
<string name="model_management">Model Management</string>
<string name="model_management_tip">Manage downloaded recognition models</string>
<string name="detector_section">Detector</string>
<string name="detector_ctd_desc">CTD Detector - Embedded in app</string>
<string name="detector_dbnet_desc">DBNet Detector - Embedded in app</string>
<string name="recognizer_section">Recognizer</string>
<string name="recognizer_ctc_desc">48px CTC Recognizer - Multilingual fast OCR, requires download</string>
<string name="recognizer_mlkit_desc">MLKit - Built-in system OCR</string>
<string name="recognizer_manga_desc">manga-ocr - Japanese OCR, embedded in app</string>
<string name="model_download">Download</string>
<string name="model_delete">Delete</string>
<string name="model_downloaded">Downloaded</string>
<string name="model_not_downloaded">Not downloaded</string>
<string name="model_download_speed">Speed: %.1f MB/s</string>
<string name="model_delete_confirm">Delete %s model?</string>
<string name="model_delete_success">Model deleted</string>
<string name="model_missing_hint">Model not downloaded. Go to Settings > Model Management to download.</string>
```

在 `values-zh/strings.xml` 中添加中文翻译：

```xml
<!-- 模型管理 -->
<string name="model_management">模型管理</string>
<string name="model_management_tip">管理已下载的识别模型</string>
<string name="detector_section">检测器</string>
<string name="detector_ctd_desc">CTD 检测器 - 内嵌于应用中</string>
<string name="detector_dbnet_desc">DBNet 检测器 - 内嵌于应用中</string>
<string name="recognizer_section">识别器</string>
<string name="recognizer_ctc_desc">48px CTC 识别器 - 多语言快速 OCR，需下载</string>
<string name="recognizer_mlkit_desc">MLKit - 系统自带 OCR</string>
<string name="recognizer_manga_desc">manga-ocr - 日文 OCR，内嵌于应用中</string>
<string name="model_download">下载</string>
<string name="model_delete">删除</string>
<string name="model_downloaded">已下载</string>
<string name="model_not_downloaded">未下载</string>
<string name="model_download_speed">下载速度: %.1f MB/s</string>
<string name="model_delete_confirm">确定删除 %s 模型吗？</string>
<string name="model_delete_success">模型已删除</string>
<string name="model_missing_hint">模型未下载，请到设置 > 模型管理 中下载</string>
```

### Step 2: 验证编译

Run: `./gradlew assembleDebug --no-daemon -q 2>&1 | head -20`
Expected: BUILD SUCCESSFUL

### Step 3: Commit

```bash
git add app/src/main/res/values/strings.xml app/src/main/res/values-zh/strings.xml app/src/main/res/values-en/strings.xml
git commit -m "res: add model management strings"
```

---

## Task 5: 修改 AboutMe 添加"模型管理"按钮

**Files:**
- Modify: `app/src/main/res/layout/fragment_about_me.xml`
- Modify: `app/src/main/java/com/moe/moetranslator/me/AboutMe.kt`

### Step 1: 在 fragment_about_me.xml 添加按钮

在 `personalization_btn` 下方添加：

```xml
<RelativeLayout
    android:background="?attr/selectableItemBackground"
    android:clickable="true"
    android:id="@+id/model_management_btn"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_marginTop="10dp">

    <ImageView
        android:id="@+id/modelicon"
        android:layout_width="50dp"
        android:layout_height="50dp"
        android:src="@drawable/update"/>

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_centerVertical="true"
        android:layout_marginStart="20dp"
        android:layout_toEndOf="@id/modelicon"
        android:text="@string/model_management"
        android:textColor="#6A6464"
        android:textSize="20sp"/>

    <ImageView
        android:layout_width="40dp"
        android:layout_height="40dp"
        android:layout_alignParentEnd="true"
        android:layout_centerVertical="true"
        android:src="@drawable/righticon"/>
</RelativeLayout>
```

### Step 2: 在 AboutMe.kt 添加点击事件

在 `setupButton()` 中 `personalizationBtn` 点击事件后添加：

```kotlin
binding.modelManagementBtn.setOnClickListener {
    if (isServiceRunning(FloatingBallService::class.java) || isServiceRunning(MangaFloatingService::class.java)){
        showToast(getString(R.string.still_running))
    } else {
        val intent = Intent(requireContext(), SettingPageActivity::class.java)
        intent.putExtra(SettingPageActivity.EXTRA_FRAGMENT_TYPE, SettingPageActivity.TYPE_FRAGMENT_MODEL_MANAGEMENT)
        startActivity(intent)
    }
}
```

### Step 3: 验证编译

Run: `./gradlew assembleDebug --no-daemon -q 2>&1 | head -20`
Expected: BUILD SUCCESSFUL

### Step 4: Commit

```bash
git add app/src/main/res/layout/fragment_about_me.xml app/src/main/java/com/moe/moetranslator/me/AboutMe.kt
git commit -m "feat(AboutMe): add model management button"
```

---

## Task 6: 修改 SettingPageActivity 添加类型常量

**Files:**
- Modify: `app/src/main/java/com/moe/moetranslator/me/SettingPageActivity.kt`

### Step 1: 添加常量

```kotlin
companion object {
    const val EXTRA_FRAGMENT_TYPE = "fragment_type"
    const val TYPE_FRAGMENT_TRANSLATE_MODE = 1
    const val TYPE_FRAGMENT_API_CONFIG = 2
    const val TYPE_FRAGMENT_PERSONALIZATION = 3
    const val TYPE_FRAGMENT_READ = 4
    const val TYPE_FRAGMENT_FAQ = 5
    const val TYPE_FRAGMENT_ERROR_CODE = 6
    const val TYPE_FRAGMENT_DEVELOPER = 7
    const val TYPE_FRAGMENT_MODEL_MANAGEMENT = 8  // 新增
}
```

### Step 2: 添加 when 分支

```kotlin
when(intent.getIntExtra(EXTRA_FRAGMENT_TYPE,0)){
    // ... 现有分支 ...
    TYPE_FRAGMENT_MODEL_MANAGEMENT->supportFragmentManager.beginTransaction().replace(
        binding.fragmentContainerView.id,
        ModelManagementFragment()
    ).commit()
}
```

### Step 3: 验证编译

Run: `./gradlew assembleDebug --no-daemon -q 2>&1 | head -20`
Expected: BUILD SUCCESSFUL

### Step 4: Commit

```bash
git add app/src/main/java/com/moe/moetranslator/me/SettingPageActivity.kt
git commit -m "feat(SettingPageActivity): add TYPE_FRAGMENT_MODEL_MANAGEMENT"
```

---

## Task 7: 创建 ModelManagementFragment

**Files:**
- Create: `app/src/main/java/com/moe/moetranslator/me/ModelManagementFragment.kt`

### Step 1: 创建 ModelManagementFragment.kt

```kotlin
package com.moe.moetranslator.me

import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.moe.moetranslator.R
import com.moe.moetranslator.manga.CtcOcrModelManager
import com.moe.moetranslator.manga.ModelDownloadManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ModelManagementFragment : Fragment() {

    private lateinit var binding: View
    private val handler = Handler(Looper.getMainLooper())
    private var isDownloading = false
    private var downloadJob: kotlinx.coroutines.Job? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = inflater.inflate(R.layout.fragment_model_management, container, false)
        return binding
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupViews()
        updateCtcStatus()
    }

    private fun setupViews() {
        val ctcDownloadBtn = binding.findViewById<Button>(R.id.ctc_download_button)
        val ctcDeleteBtn = binding.findViewById<Button>(R.id.ctc_delete_button)
        val ctcStatus = binding.findViewById<TextView>(R.id.ctc_status_text)
        val ctcSize = binding.findViewById<TextView>(R.id.ctc_size_text)

        // 更新 CTCOcr 状态
        val isDownloaded = CtcOcrModelManager.isModelDownloaded(requireContext())
        if (isDownloaded) {
            ctcStatus.text = getString(R.string.model_downloaded)
            ctcSize.text = CtcOcrModelManager.getModelSizeString(requireContext())
            ctcDownloadBtn.visibility = View.GONE
            ctcDeleteBtn.visibility = View.VISIBLE
        } else {
            ctcStatus.text = getString(R.string.model_not_downloaded)
            ctcSize.text = "144 MB"
            ctcDownloadBtn.visibility = View.VISIBLE
            ctcDeleteBtn.visibility = View.GONE
        }

        // 下载按钮
        ctcDownloadBtn.setOnClickListener {
            if (isDownloading) return@setOnClickListener
            showDownloadDialog()
        }

        // 删除按钮
        ctcDeleteBtn.setOnClickListener {
            showDeleteConfirmDialog()
        }
    }

    private fun updateCtcStatus() {
        val ctcStatus = binding.findViewById<TextView>(R.id.ctc_status_text)
        val ctcSize = binding.findViewById<TextView>(R.id.ctc_size_text)
        val ctcDownloadBtn = binding.findViewById<Button>(R.id.ctc_download_button)
        val ctcDeleteBtn = binding.findViewById<Button>(R.id.ctc_delete_button)

        val isDownloaded = CtcOcrModelManager.isModelDownloaded(requireContext())
        if (isDownloaded) {
            ctcStatus.text = getString(R.string.model_downloaded)
            ctcSize.text = CtcOcrModelManager.getModelSizeString(requireContext())
            ctcDownloadBtn.visibility = View.GONE
            ctcDeleteBtn.visibility = View.VISIBLE
        } else {
            ctcStatus.text = getString(R.string.model_not_downloaded)
            ctcSize.text = "144 MB"
            ctcDownloadBtn.visibility = View.VISIBLE
            ctcDeleteBtn.visibility = View.GONE
        }
    }

    private fun showDownloadDialog() {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_model_download, null)
        val progressBar = dialogView.findViewById<ProgressBar>(R.id.download_progress)
        val statusText = dialogView.findViewById<TextView>(R.id.download_status_text)
        val speedText = dialogView.findViewById<TextView>(R.id.download_speed_text)

        var dialogDismissed = false

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.manga_ocr_ctc_download_title)
            .setView(dialogView)
            .setNegativeButton(R.string.user_cancel) { _, _ ->
                dialogDismissed = true
                downloadJob?.cancel()
            }
            .setCancelable(false)
            .create()

        dialog.show()

        isDownloading = true

        downloadJob = lifecycleScope.launch(Dispatchers.IO) {
            val result = CtcOcrModelManager.downloadModel(
                requireContext(),
                object : ModelDownloadManager.ProgressCallback {
                    override fun onProgress(bytesRead: Long, totalBytes: Long, speed: Float) {
                        handler.post {
                            if (!dialogDismissed && dialog.isShowing) {
                                val progress = if (totalBytes > 0) {
                                    (bytesRead * 100 / totalBytes).toInt()
                                } else 0
                                progressBar.progress = progress
                                statusText.text = getString(R.string.manga_ocr_ctc_download_progress, progress)
                                speedText.text = getString(R.string.model_download_speed, speed)
                            }
                        }
                    }
                }
            )

            withContext(Dispatchers.Main) {
                isDownloading = false
                if (dialogDismissed) return@withContext

                dialog.dismiss()
                if (result.isSuccess) {
                    Toast.makeText(requireContext(), R.string.model_downloaded, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), getString(R.string.manga_ocr_ctc_download_failed, result.exceptionOrNull()?.message), Toast.LENGTH_LONG).show()
                }
                updateCtcStatus()
            }
        }
    }

    private fun showDeleteConfirmDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.model_delete)
            .setMessage(getString(R.string.model_delete_confirm, "48px CTC"))
            .setPositiveButton(R.string.user_confirm) { _, _ ->
                deleteModel()
            }
            .setNegativeButton(R.string.user_cancel, null)
            .show()
    }

    private fun deleteModel() {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = CtcOcrModelManager.deleteModel(requireContext())
            withContext(Dispatchers.Main) {
                if (result.isSuccess) {
                    Toast.makeText(requireContext(), R.string.model_delete_success, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), R.string.delete_download_failed, Toast.LENGTH_LONG).show()
                }
                updateCtcStatus()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        downloadJob?.cancel()
    }
}
```

### Step 2: 验证编译

Run: `./gradlew assembleDebug --no-daemon -q 2>&1 | head -20`
Expected: BUILD SUCCESSFUL

### Step 3: Commit

```bash
git add app/src/main/java/com/moe/moetranslator/me/ModelManagementFragment.kt
git commit -m "feat(ModelManagementFragment): add model management fragment"
```

---

## Task 8: 创建 fragment_model_management.xml

**Files:**
- Create: `app/src/main/res/layout/fragment_model_management.xml`

### Step 1: 创建布局文件

```xml
<?xml version="1.0" encoding="utf-8"?>
<ScrollView xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:padding="12dp">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical">

        <!-- 检测器区域 -->
        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="@string/detector_section"
            android:textSize="18sp"
            android:textStyle="bold"
            android:layout_marginTop="8dp"/>

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical"
            android:background="@drawable/setting_shape"
            android:padding="12dp"
            android:layout_marginTop="8dp">

            <!-- CTD 检测器 -->
            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="horizontal"
                android:padding="8dp">

                <TextView
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_weight="1"
                    android:text="CTD"
                    android:textSize="16sp"/>

                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="@string/detector_ctd_desc"
                    android:textSize="12sp"
                    android:textColor="#888"/>
            </LinearLayout>

            <!-- DBNet 检测器 -->
            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="horizontal"
                android:padding="8dp">

                <TextView
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_weight="1"
                    android:text="DBNet"
                    android:textSize="16sp"/>

                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="@string/detector_dbnet_desc"
                    android:textSize="12sp"
                    android:textColor="#888"/>
            </LinearLayout>
        </LinearLayout>

        <!-- 识别器区域 -->
        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="@string/recognizer_section"
            android:textSize="18sp"
            android:textStyle="bold"
            android:layout_marginTop="16dp"/>

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical"
            android:background="@drawable/setting_shape"
            android:padding="12dp"
            android:layout_marginTop="8dp">

            <!-- MLKit -->
            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="horizontal"
                android:padding="8dp">

                <TextView
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_weight="1"
                    android:text="MLKit"
                    android:textSize="16sp"/>

                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="@string/recognizer_mlkit_desc"
                    android:textSize="12sp"
                    android:textColor="#888"/>
            </LinearLayout>

            <!-- manga-ocr -->
            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="horizontal"
                android:padding="8dp">

                <TextView
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_weight="1"
                    android:text="manga-ocr"
                    android:textSize="16sp"/>

                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="@string/recognizer_manga_desc"
                    android:textSize="12sp"
                    android:textColor="#888"/>
            </LinearLayout>

            <!-- CTCOcr -->
            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="horizontal"
                android:padding="8dp">

                <LinearLayout
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_weight="1"
                    android:orientation="vertical">

                    <TextView
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:text="48px CTC"
                        android:textSize="16sp"/>

                    <TextView
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:text="@string/recognizer_ctc_desc"
                        android:textSize="12sp"
                        android:textColor="#888"/>

                    <LinearLayout
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:orientation="horizontal"
                        android:layout_marginTop="4dp">

                        <TextView
                            android:id="@+id/ctc_status_text"
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:text="@string/model_not_downloaded"
                            android:textSize="12sp"/>

                        <TextView
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:text=" - "
                            android:textSize="12sp"/>

                        <TextView
                            android:id="@+id/ctc_size_text"
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:text="144 MB"
                            android:textSize="12sp"/>
                    </LinearLayout>
                </LinearLayout>

                <Button
                    android:id="@+id/ctc_download_button"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="@string/model_download"/>

                <Button
                    android:id="@+id/ctc_delete_button"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="@string/model_delete"
                    android:visibility="gone"/>
            </LinearLayout>
        </LinearLayout>
    </LinearLayout>
</ScrollView>
```

### Step 2: 验证编译

Run: `./gradlew assembleDebug --no-daemon -q 2>&1 | head -20`
Expected: BUILD SUCCESSFUL

### Step 3: Commit

```bash
git add app/src/main/res/layout/fragment_model_management.xml
git commit -m "feat(layout): add fragment_model_management.xml"
```

---

## Task 9: 创建下载进度对话框布局

**Files:**
- Create: `app/src/main/res/layout/dialog_model_download.xml`

### Step 1: 创建下载对话框布局

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical"
    android:padding="16dp">

    <ProgressBar
        android:id="@+id/download_progress"
        style="@style/Widget.AppCompat.ProgressBar.Horizontal"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:max="100"/>

    <TextView
        android:id="@+id/download_status_text"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="8dp"
        android:text="0%"
        android:textSize="14sp"/>

    <TextView
        android:id="@+id/download_speed_text"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="4dp"
        android:text="Speed: 0.0 MB/s"
        android:textSize="12sp"
        android:textColor="#888"/>
</LinearLayout>
```

### Step 2: 验证编译

Run: `./gradlew assembleDebug --no-daemon -q 2>&1 | head -20`
Expected: BUILD SUCCESSFUL

### Step 3: Commit

```bash
git add app/src/main/res/layout/dialog_model_download.xml
git commit -m "feat(layout): add dialog_model_download.xml"
```

---

## Task 10: 修改 MangaFloatingService 缺失模型提示

**Files:**
- Modify: `app/src/main/java/com/moe/moetranslator/manga/MangaFloatingService.kt`

### Step 1: 在 processMangaScreenshot 中添加检测

在 `processMangaScreenshot` 函数中，当 `config.ocrEngine` 为 `CTCOcr` 时，检测模型是否已下载：

```kotlin
// 在 OCR 初始化之前检查
if (config.ocrEngine == OcrEngine.CTCOcr && !CtcOcrModelManager.isModelDownloaded(context)) {
    showToast(getString(R.string.model_missing_hint))
    return
}
```

### Step 2: 验证编译

Run: `./gradlew assembleDebug --no-daemon -q 2>&1 | head -20`
Expected: BUILD SUCCESSFUL

### Step 3: Commit

```bash
git add app/src/main/java/com/moe/moetranslator/manga/MangaFloatingService.kt
git commit -m "feat(MangaFloatingService): show hint when CTCOcr model not downloaded"
```

---

## Verification Checklist

- [ ] ModelDownloadManager 下载进度包含速度显示 (MB/s)
- [ ] CtcOcrModelManager 可删除模型
- [ ] CtcOcrRecognizer 可从 filesDir 加载模型
- [ ] AboutMe 页面有"模型管理"按钮
- [ ] SettingPageActivity 支持 ModelManagementFragment
- [ ] ModelManagementFragment 显示检测器和识别器列表
- [ ] CTCOcr 可下载，显示进度和速度
- [ ] CTCOcr 可删除
- [ ] 切换到未下载的 OCR 引擎时提示用户
- [ ] 内嵌模型（CTD/DBNet/manga-ocr）状态显示为已下载
- [ ] Build passes