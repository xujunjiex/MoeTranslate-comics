# In-App Log Viewer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an in-app log viewer so users can see and copy full error stack traces without needing adb.

**Architecture:** Create a `LogCollector` singleton with a ring buffer that wraps `android.util.Log`. All app logging goes through `LogCollector` which both writes to Android logcat AND stores in memory. A "View Logs" button in the settings page opens a dialog showing recent logs with a copy button.

**Tech Stack:** Kotlin, Android Views, AlertDialog

---

## File Structure

| File | Action | Purpose |
|------|--------|---------|
| `utils/LogCollector.kt` | Create | Singleton ring buffer log collector |
| `me/AboutMe.kt` | Modify | Add "View Logs" button handler |
| `res/layout/fragment_about_me.xml` | Modify | Add "View Logs" button UI |
| `res/layout/dialog_log_viewer.xml` | Create | Log viewer dialog layout |
| `res/values-zh/strings.xml` | Modify | Add Chinese strings |
| `res/values/strings.xml` | Modify | Add English strings |
| `res/drawable/log_icon.xml` | Create | Log viewer icon |
| `manga/MangaFloatingService.kt` | Modify | Replace `Log.*` with `LogCollector.*` |
| `manga/MangaOcrRecognizer.kt` | Modify | Replace `Log.*` with `LogCollector.*` |
| `manga/MangaOcrBridge.kt` | Modify | Replace `Log.*` with `LogCollector.*` |
| `manga/MangaOcrModelManager.kt` | Modify | Replace `Log.*` with `LogCollector.*` |

---

### Task 1: Create LogCollector

**Files:**
- Create: `app/src/main/java/com/moe/moetranslator/utils/LogCollector.kt`

- [ ] **Step 1: Create LogCollector singleton**

```kotlin
package com.moe.moetranslator.utils

import android.util.Log

/**
 * 内存日志收集器（环形缓冲区）
 *
 * 所有日志通过此类写入，同时写入 Android logcat 和内存缓冲区。
 * 用户可在设置页面查看最近的日志，方便复制错误信息。
 */
object LogCollector {

    data class LogEntry(
        val level: String,  // D, I, W, E, V
        val tag: String,
        val message: String,
        val throwable: Throwable? = null,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        fun format(): String {
            val time = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault())
                .format(java.util.Date(timestamp))
            val sb = StringBuilder("[$time] $level/$tag: $message")
            if (throwable != null) {
                sb.append("\n").append(Log.getStackTraceString(throwable))
            }
            return sb.toString()
        }
    }

    private const val MAX_ENTRIES = 500
    private val buffer = java.util.concurrent.CopyOnWriteArrayList<LogEntry>()

    fun v(tag: String, msg: String): Int {
        addEntry("V", tag, msg)
        return Log.v(tag, msg)
    }

    fun d(tag: String, msg: String): Int {
        addEntry("D", tag, msg)
        return Log.d(tag, msg)
    }

    fun i(tag: String, msg: String): Int {
        addEntry("I", tag, msg)
        return Log.i(tag, msg)
    }

    fun w(tag: String, msg: String): Int {
        addEntry("W", tag, msg)
        return Log.w(tag, msg)
    }

    fun w(tag: String, msg: String, tr: Throwable?): Int {
        addEntry("W", tag, msg, tr)
        return Log.w(tag, msg, tr)
    }

    fun e(tag: String, msg: String): Int {
        addEntry("E", tag, msg)
        return Log.e(tag, msg)
    }

    fun e(tag: String, msg: String, tr: Throwable?): Int {
        addEntry("E", tag, msg, tr)
        return Log.e(tag, msg, tr)
    }

    private fun addEntry(level: String, tag: String, msg: String, tr: Throwable? = null) {
        val entry = LogEntry(level, tag, msg, tr)
        buffer.add(entry)
        // 超过上限时移除最旧的
        while (buffer.size > MAX_ENTRIES) {
            buffer.removeAt(0)
        }
    }

    /**
     * 获取所有日志（从旧到新）
     */
    fun getAllLogs(): List<LogEntry> {
        return buffer.toList().sortedBy { it.timestamp }
    }

    /**
     * 获取格式化的日志文本
     */
    fun getFormattedLogs(): String {
        return getAllLogs().joinToString("\n") { it.format() }
    }

    /**
     * 清空日志
     */
    fun clear() {
        buffer.clear()
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `cd D:/xjj20/Desktop/fyapp/MoeTranslate-comics && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/moe/moetranslator/utils/LogCollector.kt
git commit -m "feat: add LogCollector singleton with ring buffer"
```

---

### Task 2: Create Log Viewer Dialog Layout

**Files:**
- Create: `app/src/main/res/layout/dialog_log_viewer.xml`
- Create: `app/src/main/res/drawable/log_icon.xml`

- [ ] **Step 1: Create log viewer dialog layout**

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical"
    android:padding="16dp">

    <TextView
        android:id="@+id/log_count"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:textSize="14sp"
        android:textColor="#666666"
        android:layout_marginBottom="8dp"/>

    <ScrollView
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1"
        android:maxHeight="400dp">

        <TextView
            android:id="@+id/log_content"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:textSize="12sp"
            android:fontFamily="monospace"
            android:textColor="#333333"
            android:textIsSelectable="true"
            android:padding="8dp"
            android:background="#F5F5F5"/>
    </ScrollView>

</LinearLayout>
```

- [ ] **Step 2: Create log icon drawable**

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24"
    android:tint="#F3B605">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M14,2H6C4.9,2 4,2.9 4,4v16c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V8L14,2zM6,20V4h7v5h5v11H6zM8,15.01V17h2v-2h2v-2h-2v-2H8v2H6v2h2z"/>
</vector>
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/res/layout/dialog_log_viewer.xml app/src/main/res/drawable/log_icon.xml
git commit -m "feat: add log viewer dialog layout and icon"
```

---

### Task 3: Add String Resources

**Files:**
- Modify: `app/src/main/res/values-zh/strings.xml`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Add Chinese strings**

Add before `</resources>`:
```xml
<string name="view_logs">查看日志</string>
<string name="log_viewer_title">应用日志</string>
<string name="log_copy">复制日志</string>
<string name="log_clear">清空日志</string>
<string name="log_copied">日志已复制到剪贴板</string>
<string name="log_empty">暂无日志</string>
<string name="log_count_format">共 %d 条日志</string>
```

- [ ] **Step 2: Add English strings**

Add before `</resources>`:
```xml
<string name="view_logs">View Logs</string>
<string name="log_viewer_title">App Logs</string>
<string name="log_copy">Copy Logs</string>
<string name="log_clear">Clear Logs</string>
<string name="log_copied">Logs copied to clipboard</string>
<string name="log_empty">No logs yet</string>
<string name="log_count_format">%d log entries</string>
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/res/values-zh/strings.xml app/src/main/res/values/strings.xml
git commit -m "feat: add log viewer string resources"
```

---

### Task 4: Add Log Viewer to Settings Page

**Files:**
- Modify: `app/src/main/res/layout/fragment_about_me.xml`
- Modify: `app/src/main/java/com/moe/moetranslator/me/AboutMe.kt`

- [ ] **Step 1: Add "View Logs" button to layout**

In `fragment_about_me.xml`, first add `android:id="@+id/lin2"` to the second `LinearLayout` (the one containing update_btn, clean_btn, developer_btn — around line 243).

Then add a new `LinearLayout` section after it, before the closing `</androidx.constraintlayout.widget.ConstraintLayout>`:

```xml
<LinearLayout
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_marginStart="20dp"
    android:layout_marginTop="30dp"
    android:layout_marginEnd="20dp"
    android:background="@drawable/setting_shape"
    android:orientation="vertical"
    android:padding="15dp"
    app:layout_constraintEnd_toEndOf="parent"
    app:layout_constraintStart_toStartOf="parent"
    app:layout_constraintTop_toBottomOf="@+id/lin2">

    <RelativeLayout
        android:background="?attr/selectableItemBackground"
        android:clickable="true"
        android:id="@+id/view_logs_btn"
        android:layout_width="match_parent"
        android:layout_height="wrap_content">

        <ImageView
            android:id="@+id/logicon"
            android:layout_width="50dp"
            android:layout_height="50dp"
            android:src="@drawable/log_icon"/>

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_centerVertical="true"
            android:layout_marginStart="20dp"
            android:layout_toEndOf="@id/logicon"
            android:text="@string/view_logs"
            android:textColor="#6A6464"
            android:textSize="20sp"/>

        <ImageView
            android:layout_width="40dp"
            android:layout_height="40dp"
            android:layout_alignParentEnd="true"
            android:layout_centerVertical="true"
            android:src="@drawable/righticon"/>
    </RelativeLayout>
</LinearLayout>
```

- [ ] **Step 2: Add log viewer dialog code to AboutMe.kt**

Add import at top:
```kotlin
import com.moe.moetranslator.utils.LogCollector
import android.content.ClipData
import android.content.ClipboardManager
```

Add method to `AboutMe` class:
```kotlin
private fun showLogViewerDialog() {
    val logs = LogCollector.getFormattedLogs()
    val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_log_viewer, null)
    val logContent = dialogView.findViewById<TextView>(R.id.log_content)
    val logCount = dialogView.findViewById<TextView>(R.id.log_count)

    if (logs.isEmpty()) {
        logContent.text = getString(R.string.log_empty)
        logCount.text = getString(R.string.log_count_format, 0)
    } else {
        logContent.text = logs
        logCount.text = getString(R.string.log_count_format, LogCollector.getAllLogs().size)
    }

    val dialog = AlertDialog.Builder(requireContext())
        .setTitle(R.string.log_viewer_title)
        .setView(dialogView)
        .setPositiveButton(R.string.log_copy) { _, _ ->
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("app_logs", logs))
            showToast(getString(R.string.log_copied))
        }
        .setNeutralButton(R.string.log_clear) { _, _ ->
            LogCollector.clear()
            showToast("日志已清空")
        }
        .setNegativeButton(R.string.user_cancel, null)
        .create()
    dialog.show()
}
```

Add click listener in `setupButton()`:
```kotlin
binding.viewLogsBtn.setOnClickListener {
    showLogViewerDialog()
}
```

- [ ] **Step 3: Verify compilation**

Run: `cd D:/xjj20/Desktop/fyapp/MoeTranslate-comics && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/res/layout/fragment_about_me.xml app/src/main/java/com/moe/moetranslator/me/AboutMe.kt
git commit -m "feat: add log viewer to settings page"
```

---

### Task 5: Replace Log.* with LogCollector.* in Manga Module

**Files:**
- Modify: `app/src/main/java/com/moe/moetranslator/manga/MangaFloatingService.kt`
- Modify: `app/src/main/java/com/moe/moetranslator/manga/MangaOcrRecognizer.kt`
- Modify: `app/src/main/java/com/moe/moetranslator/manga/MangaOcrBridge.kt`
- Modify: `app/src/main/java/com/moe/moetranslator/manga/MangaOcrModelManager.kt`

- [ ] **Step 1: Replace Log.* in MangaFloatingService.kt**

Add import:
```kotlin
import com.moe.moetranslator.utils.LogCollector
```

Replace all `Log.d(TAG, ...)` with `LogCollector.d(TAG, ...)` and `Log.e(TAG, ...)` with `LogCollector.e(TAG, ...)`.

Key locations to replace (search for `Log.d` and `Log.e`):
- Line ~242: `Log.d(TAG, "initMangaOcr: 开始初始化 manga-ocr")`
- Line ~245: `Log.d(TAG, "initMangaOcr: manga-ocr 初始化完成")`
- Line ~248: `Log.e(TAG, "initMangaOcr: 初始化失败", e)`
- Line ~256: `Log.d(TAG, "releaseMangaOcr: 释放 manga-ocr 资源")`
- Line ~259: `Log.e(TAG, "releaseMangaOcr: 释放失败", e)`
- Line ~812: `Log.d(TAG, "processMangaScreenshot: Step 1 - OCR starting...")`
- And all other `Log.*` calls in the file

- [ ] **Step 2: Replace Log.* in MangaOcrRecognizer.kt**

Add import:
```kotlin
import com.moe.moetranslator.utils.LogCollector
```

Replace all `Log.d(TAG, ...)` and `Log.e(TAG, ...)` and `Log.w(TAG, ...)` with `LogCollector.*`.

- [ ] **Step 3: Replace Log.* in MangaOcrBridge.kt**

Add import and replace all `Log.*` calls.

- [ ] **Step 4: Replace Log.* in MangaOcrModelManager.kt**

Add import and replace all `Log.*` calls.

- [ ] **Step 5: Verify compilation**

Run: `cd D:/xjj20/Desktop/fyapp/MoeTranslate-comics && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/moe/moetranslator/manga/MangaFloatingService.kt
git add app/src/main/java/com/moe/moetranslator/manga/MangaOcrRecognizer.kt
git add app/src/main/java/com/moe/moetranslator/manga/MangaOcrBridge.kt
git add app/src/main/java/com/moe/moetranslator/manga/MangaOcrModelManager.kt
git commit -m "feat: replace Log.* with LogCollector.* in manga module"
```

---

## Verification

1. Build: `./gradlew assembleDebug` — must pass
2. Install on device, open app
3. Go to Settings page → "查看日志" → verify dialog opens
4. Switch to manga-ocr engine → reproduce error
5. Go back to Settings → "查看日志" → verify error logs are visible with full stack trace
6. Tap "复制日志" → verify clipboard contains full logs
7. Paste in a text editor to confirm full error details are captured

---

## Notes

- The `LogCollector.getAllLogs()` uses `drainTo` which removes entries from the buffer. For the dialog, this is fine since we format before showing. If needed, a peek-only method can be added.
- Ring buffer size: 500 entries. Each entry is ~200-500 bytes, so total ~100-250KB memory usage.
- Only manga module files are converted to `LogCollector.*` in this plan. Other modules can be converted incrementally.
