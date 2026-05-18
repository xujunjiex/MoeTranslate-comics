package com.moe.moetranslator.manga

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.moe.moetranslator.MainActivity
import com.moe.moetranslator.R
import com.moe.moetranslator.bridge.OCRBridge
import com.moe.moetranslator.bridge.TranslateBridge
import com.moe.moetranslator.translate.AccessibilityServiceManager
import com.moe.moetranslator.translate.CropView
import com.moe.moetranslator.translate.Dialogs
import com.moe.moetranslator.translate.ScreenshotManager
import com.moe.moetranslator.translate.TranslationResult
import com.moe.moetranslator.utils.CustomPreference
import com.moe.moetranslator.utils.UtilTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.math.abs

class MangaFloatingService : LifecycleService() {

    companion object {
        private const val TAG = "MangaFloatingService"
        private const val NOTIFICATION_CHANNEL_ID = "manga_floating_service"
        private const val NOTIFICATION_ID = 7

        private const val CLICK_SLOP = 5f
        private const val LONG_PRESS_SLOP = 10f
        private const val LONG_PRESS_DELAY = 500L

        fun start(context: Context) {
            androidx.core.content.ContextCompat.startForegroundService(
                context, Intent(context, MangaFloatingService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MangaFloatingService::class.java))
        }
    }

    private lateinit var windowManager: WindowManager
    private lateinit var floatingBallView: View
    private lateinit var resultOverlayView: ImageView

    private var floatingBallParams: WindowManager.LayoutParams? = null
    private var resultOverlayParams: WindowManager.LayoutParams? = null

    private var ballInitialX = 0
    private var ballInitialY = 0
    private var ballInitialTouchX = 0f
    private var ballInitialTouchY = 0f

    private var isProcessing = false
    private var isResultShowing = false

    // Progress overlay
    private lateinit var progressOverlayView: android.widget.TextView
    private var progressOverlayParams: WindowManager.LayoutParams? = null
    private var isProgressShowing = false

    // Long press detection
    private val handler = Handler(Looper.getMainLooper())
    private val longPressRunnable = Runnable { handleLongPress() }
    private var currentGesture: GestureType? = null

    // Auto-translate
    private var isAutoTranslating = false
    private var lastOcrText = ""
    private val autoTranslateHandler = Handler(Looper.getMainLooper())
    private val autoTranslateRunnable = object : Runnable {
        override fun run() {
            if (isAutoTranslating) {
                // 如果翻译结果正在显示，跳过本次触发，避免截到翻译图层
                if (!isResultShowing) {
                    triggerTranslation()
                }
                autoTranslateHandler.postDelayed(this, prefs.getLong("Auto_Translate_Interval", 3000L))
            }
        }
    }

    // Crop selection
    private lateinit var cropView: CropView
    private var cropViewParams: WindowManager.LayoutParams? = null
    private var cropRect: RectF? = null
    private var isCropActive = false
    private var cropConfirmView: View? = null

    private lateinit var prefs: CustomPreference
    private lateinit var config: MangaModeConfig

    private sealed class GestureType {
        object Click : GestureType()
        object LongPress : GestureType()
        object Drag : GestureType()
    }

    // ---------- Lifecycle ----------

    override fun onCreate() {
        super.onCreate()
        prefs = CustomPreference.getInstance(this)
        config = loadConfig()
        TranslateBridge.initFromPreferences(this)

        // 互斥：停止普通翻译服务
        try {
            stopService(Intent(this, com.moe.moetranslator.translate.FloatingBallService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "Could not stop FloatingBallService", e)
        }

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        initializeViews()
        setupScreenshotCollector()

        Log.d(TAG, "MangaFloatingService created")
    }

    override fun onDestroy() {
        super.onDestroy()
        removeAllViews()
        TranslateBridge.release()
        autoTranslateHandler.removeCallbacks(autoTranslateRunnable)
        // 发送广播通知 UI 更新按钮状态
        val stopIntent = Intent("action_manga_floating_service_stopped")
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this).sendBroadcast(stopIntent)
        Log.d(TAG, "MangaFloatingService destroyed")
    }

    // ---------- Initialization ----------

    private fun loadConfig(): MangaModeConfig {
        val directionIndex = prefs.getInt("Manga_Text_Direction", 0)
        val direction = TextDirection.entries.getOrElse(directionIndex) { TextDirection.VERTICAL_RL }
        return MangaModeConfig(
            enabled = true,
            textDirection = direction,
            smartBackground = prefs.getBoolean("Manga_Smart_Background", true),
            autoDetectBubble = prefs.getBoolean("Manga_Auto_Detect_Bubble", true),
            fontSize = prefs.getFloat("Manga_Font_Size", 16f),
            autoFontSize = prefs.getBoolean("Manga_Auto_Font_Size", true),
            sourceLang = prefs.getString("Source_Language", "ja"),
            targetLang = prefs.getString("Target_Language", "zh")
        )
    }

    @SuppressLint("InflateParams")
    private fun initializeViews() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // Create floating ball using original layout (65dp icon)
        floatingBallView = LayoutInflater.from(this).inflate(R.layout.floatball_layout, null)

        floatingBallParams = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            format = PixelFormat.RGBA_8888
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.START or Gravity.TOP
            x = 80
            y = 300
        }

        windowManager.addView(floatingBallView, floatingBallParams)
        setupTouchListener()

        // Result overlay (initially not added)
        resultOverlayView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            setBackgroundColor(Color.argb(180, 0, 0, 0))
        }

        resultOverlayParams = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            format = PixelFormat.RGBA_8888
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            gravity = Gravity.START or Gravity.TOP
        }

        // Progress overlay (initially not added)
        progressOverlayView = android.widget.TextView(this).apply {
            text = getString(R.string.manga_translating)
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
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 100
        }

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
    }

    // ---------- Touch handling (matches original FloatingBallService pattern) ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchListener() {
        floatingBallView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    ballInitialX = floatingBallParams?.x ?: 0
                    ballInitialY = floatingBallParams?.y ?: 0
                    ballInitialTouchX = event.rawX
                    ballInitialTouchY = event.rawY

                    // Start long press detection
                    handler.postDelayed(longPressRunnable, LONG_PRESS_DELAY)
                    currentGesture = null
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val totalMoveX = abs(event.rawX - ballInitialTouchX)
                    val totalMoveY = abs(event.rawY - ballInitialTouchY)

                    // Cancel long press if moved too much
                    if (totalMoveX > LONG_PRESS_SLOP || totalMoveY > LONG_PRESS_SLOP) {
                        handler.removeCallbacks(longPressRunnable)
                    }

                    // Drag if moved enough
                    if (totalMoveX > CLICK_SLOP || totalMoveY > CLICK_SLOP) {
                        currentGesture = GestureType.Drag
                        floatingBallParams?.apply {
                            x = (ballInitialX + (event.rawX - ballInitialTouchX)).toInt()
                            y = (ballInitialY + (event.rawY - ballInitialTouchY)).toInt()
                            windowManager.updateViewLayout(floatingBallView, this)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressRunnable)

                    // Click if no gesture detected and within slop
                    if (currentGesture == null) {
                        val totalMoveX = abs(event.rawX - ballInitialTouchX)
                        val totalMoveY = abs(event.rawY - ballInitialTouchY)
                        if (totalMoveX <= CLICK_SLOP && totalMoveY <= CLICK_SLOP) {
                            onBallClicked()
                        }
                    }

                    currentGesture = null
                    true
                }
                else -> false
            }
        }
    }

    // ---------- Long press menu ----------

    private fun handleLongPress() {
        currentGesture = GestureType.LongPress
        // 长按震动反馈动画（缩放+透明度）
        floatingBallView.animate()
            .scaleX(1.2f).scaleY(1.2f)
            .alpha(0.7f)
            .setDuration(100)
            .withEndAction {
                floatingBallView.animate()
                    .scaleX(1f).scaleY(1f)
                    .alpha(1f)
                    .setDuration(100)
                    .start()
            }
            .start()
        showMenu()
    }

    private fun showMenu() {
        val directionLabel = when (config.textDirection) {
            TextDirection.VERTICAL_RL -> getString(R.string.manga_mode_vertical_rl)
            TextDirection.VERTICAL_LR -> getString(R.string.manga_mode_vertical_lr)
            TextDirection.HORIZONTAL -> getString(R.string.manga_mode_horizontal)
        }
        val cropLabel = if (cropRect != null) {
            getString(R.string.manga_mode_crop)
        } else {
            getString(R.string.manga_mode_fullscreen)
        }

        val (dialog, listView) = Dialogs.mangaMenuDialog(
            applicationContext, isAutoTranslating, directionLabel, cropLabel
        )
        listView.onItemClickListener = android.widget.AdapterView.OnItemClickListener { _, _, which, _ ->
            dialog.dismiss()
            when (which) {
                0 -> clearCropAndTranslate()
                1 -> startCropSelection()
                2 -> switchTextDirection()
                3 -> showFontSizeDialog()
                4 -> {
                    // 延迟启动，等菜单关闭动画完成
                    handler.postDelayed({ toggleAutoTranslate() }, 200)
                }
                5 -> stopSelf()
                6 -> backToMainActivity()
            }
        }
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
        dialog.window?.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun backToMainActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
    }

    // ---------- Fullscreen translate ----------

    private fun clearCropAndTranslate() {
        cropRect = null
        triggerTranslation()
    }

    // ---------- Menu actions ----------

    private fun switchTextDirection() {
        val newDirection = when (config.textDirection) {
            TextDirection.VERTICAL_RL -> TextDirection.VERTICAL_LR
            TextDirection.VERTICAL_LR -> TextDirection.HORIZONTAL
            TextDirection.HORIZONTAL -> TextDirection.VERTICAL_RL
        }
        config = config.copy(textDirection = newDirection)
        prefs.setInt("Manga_Text_Direction", newDirection.ordinal)
        val label = when (newDirection) {
            TextDirection.VERTICAL_RL -> getString(R.string.manga_mode_vertical_rl)
            TextDirection.VERTICAL_LR -> getString(R.string.manga_mode_vertical_lr)
            TextDirection.HORIZONTAL -> getString(R.string.manga_mode_horizontal)
        }
        showToast("${getString(R.string.manga_direction_switched)}：$label")
    }

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

    // ---------- Auto-translate ----------

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
        lastOcrText = ""
        autoTranslateHandler.removeCallbacks(autoTranslateRunnable)
        showToast(getString(R.string.manga_auto_translate_stop))
    }

    // ---------- Crop selection ----------

    private fun startCropSelection() {
        if (isCropActive) {
            showToast(getString(R.string.manga_crop_active))
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

        showCropConfirmButton()
    }

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

        showToast(getString(R.string.manga_crop_confirm))
    }

    // ---------- Click handler ----------

    private fun onBallClicked() {
        // 点击脉冲动画
        floatingBallView.animate()
            .scaleX(0.85f).scaleY(0.85f)
            .setDuration(80)
            .withEndAction {
                floatingBallView.animate()
                    .scaleX(1f).scaleY(1f)
                    .setDuration(80)
                    .start()
            }
            .start()
        triggerTranslation()
    }

    private fun triggerTranslation() {
        if (isProcessing) return
        if (isCropActive) return

        val service = AccessibilityServiceManager.getService()
        if (service == null) {
            showToast(getString(R.string.accessibility_recycle))
            return
        }

        isProcessing = true

        // 先关闭结果overlay再截图，避免截到翻译结果
        dismissResultOverlay()
        showProgressOverlay()

        if (cropRect != null) {
            AccessibilityServiceManager.takeScreenshot(cropRect, cropView.absolutePointOffset)
        } else {
            AccessibilityServiceManager.takeScreenshot(null, android.graphics.Point(0, 0))
        }
    }

    // ---------- Screenshot collection ----------

    private fun setupScreenshotCollector() {
        lifecycleScope.launch {
            ScreenshotManager.screenshotFlow.collect { bitmap ->
                try {
                    Log.d(TAG, "Screenshot received, starting manga pipeline")
                    processMangaScreenshot(bitmap)
                } catch (e: Exception) {
                    Log.e(TAG, "Manga pipeline failed", e)
                    showToast("Manga translation failed: ${e.message}")
                    isProcessing = false
                }
            }
        }
    }

    // ---------- Manga translation pipeline ----------

    private fun processMangaScreenshot(bitmap: Bitmap) {
        lifecycleScope.launch {
            try {
                // Step 1: OCR with location
                val textBlocks = withContext(Dispatchers.IO) {
                    OCRBridge.recognizeWithLocation(config.sourceLang, bitmap)
                }
                Log.d(TAG, "OCR found ${textBlocks.size} text blocks")

                if (textBlocks.isEmpty()) {
                    bitmap.recycle()
                    if (!isAutoTranslating) {
                        showToast(getString(R.string.no_text_found))
                    }
                    return@launch
                }

                // 自动翻译模式下，检查文本是否变化
                val currentOcrText = textBlocks.joinToString("\n") { it.text }
                if (isAutoTranslating && !shouldTranslateText(currentOcrText)) {
                    Log.d(TAG, "Auto-translate: text unchanged, skipping")
                    bitmap.recycle()
                    return@launch
                }
                lastOcrText = currentOcrText

                // Step 2: Detect bubbles (or use raw blocks)
                val bubbles = if (config.autoDetectBubble) {
                    BubbleDetector.detectBubbles(textBlocks)
                } else {
                    textBlocks.filter { it.boundingBox != null }.map { block ->
                        BubbleRegion(
                            rect = block.boundingBox!!,
                            texts = listOf(block.text)
                        )
                    }
                }
                Log.d(TAG, "Detected ${bubbles.size} bubbles")

                // Step 3: Translate each bubble and build TranslatedBubble list
                val translatedBubbles = translateBubbles(bubbles)

                // Step 4: Render overlay
                val resultBitmap = withContext(Dispatchers.Default) {
                    OverlayRenderer.renderOverlay(
                        original = bitmap,
                        regions = translatedBubbles,
                        direction = config.textDirection,
                        fontSize = config.fontSize,
                        autoFit = config.autoFontSize
                    )
                }

                // Step 5: Show result overlay
                withContext(Dispatchers.Main) {
                    showResultOverlay(resultBitmap)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Pipeline error", e)
                if (!isAutoTranslating) {
                    showToast("Error: ${e.message}")
                }
            } finally {
                isProcessing = false
                dismissProgressOverlay()
            }
        }
    }

    private fun shouldTranslateText(currentText: String): Boolean {
        if (currentText.isBlank()) return false
        // 短文本直接翻译
        if (currentText.length < prefs.getInt("Auto_Translate_Str_Length", 10)) return true
        // 首次翻译
        if (lastOcrText.isEmpty()) return true
        // 相似度检测
        val similarity = UtilTools.calculateSimilarity(lastOcrText, currentText)
        return similarity < prefs.getFloat("Auto_Translate_Str_Similarity", 0.8f)
    }

    private fun cleanOcrText(text: String): String {
        return text
            .replace(Regex("[\\n\\r]+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private suspend fun translateBubbles(
        bubbles: List<BubbleRegion>
    ): List<TranslatedBubble> {
        return coroutineScope {
            bubbles.map { bubble ->
                val cleaned = bubble.texts.map { cleanOcrText(it) }.filter { it.isNotBlank() }
                bubble to cleaned
            }.filter { it.second.isNotEmpty() }.map { (bubble, cleanedTexts) ->
                async {
                    val combinedText = cleanedTexts.joinToString("")
                    val translatedText = translateSingleText(combinedText)
                    TranslatedBubble(
                        rect = bubble.rect,
                        originalText = combinedText,
                        translatedText = translatedText,
                        backgroundColor = Color.TRANSPARENT
                    )
                }
            }.awaitAll()
        }
    }

    private suspend fun translateSingleText(text: String): String {
        return suspendCancellableCoroutine { continuation ->
            TranslateBridge.translateText(
                text = text,
                sourceLang = config.sourceLang,
                targetLang = config.targetLang
            ) { result ->
                when (result) {
                    is TranslationResult.Success -> {
                        continuation.resume(result.translatedText) {}
                    }
                    is TranslationResult.Error -> {
                        Log.e(TAG, "Translation error: ${result.error.message}")
                        continuation.resume("[Error: ${result.error.message}]") {}
                    }
                }
            }
        }
    }

    // ---------- Result overlay ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun showResultOverlay(bitmap: Bitmap) {
        if (isResultShowing) {
            dismissResultOverlay()
        }

        resultOverlayView.setImageBitmap(bitmap)
        resultOverlayView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                dismissResultOverlay()
            }
            true
        }

        // 框选模式下，结果只显示在框选区域内
        if (cropRect != null) {
            val crop = cropRect!!
            val params = WindowManager.LayoutParams().apply {
                type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                format = PixelFormat.RGBA_8888
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                width = crop.width().toInt()
                height = crop.height().toInt()
                gravity = Gravity.START or Gravity.TOP
                x = crop.left.toInt() + cropView.absolutePointOffset.x
                y = crop.top.toInt() + cropView.absolutePointOffset.y
            }
            resultOverlayView.scaleType = ImageView.ScaleType.FIT_XY
            windowManager.addView(resultOverlayView, params)
        } else {
            // 全屏模式使用 FIT_CENTER 避免图片压缩变形
            resultOverlayView.scaleType = ImageView.ScaleType.FIT_CENTER
            windowManager.addView(resultOverlayView, resultOverlayParams)
        }
        isResultShowing = true

        // Keep floating ball on top
        if (isViewAdded(floatingBallView)) {
            windowManager.removeView(floatingBallView)
            windowManager.addView(floatingBallView, floatingBallParams)
        }
    }

    private fun dismissResultOverlay() {
        if (isResultShowing) {
            try {
                resultOverlayView.setImageBitmap(null)
                resultOverlayView.setOnTouchListener(null)
                windowManager.removeView(resultOverlayView)
            } catch (e: Exception) {
                Log.e(TAG, "Error dismissing overlay", e)
            }
            isResultShowing = false

            // 重置自动翻译定时器，给用户 1 秒时间翻页/滑动
            if (isAutoTranslating) {
                autoTranslateHandler.removeCallbacks(autoTranslateRunnable)
                autoTranslateHandler.postDelayed(autoTranslateRunnable, 1000L)
            }
        }
    }

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

    // ---------- Helpers ----------

    private fun isViewAdded(view: View): Boolean {
        return try {
            windowManager.updateViewLayout(view, view.layoutParams)
            true
        } catch (e: IllegalArgumentException) {
            false
        }
    }

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

    private fun showToast(message: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- Notification ----------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Manga Floating Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Manga translation floating window"
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Manga Translation")
            .setContentText("Floating ball active - tap to translate manga")
            .setSmallIcon(R.drawable.floating_ball_icon)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
