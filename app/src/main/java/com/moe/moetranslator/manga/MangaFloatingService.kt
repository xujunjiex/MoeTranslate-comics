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
import com.moe.moetranslator.manga.MangaOcrDownloadManager
import com.moe.moetranslator.utils.LogCollector
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
import com.moe.moetranslator.bridge.TextBlockInfo
import com.moe.moetranslator.me.ConfigurationStorage
import com.moe.moetranslator.translate.AccessibilityServiceManager
import com.moe.moetranslator.translate.CropView
import com.moe.moetranslator.translate.Dialogs
import com.moe.moetranslator.translate.ScreenshotManager
import com.moe.moetranslator.translate.TranslationResult
import com.moe.moetranslator.translate.TranslationTextAPI
import com.moe.moetranslator.utils.Constants
import com.moe.moetranslator.utils.CustomPreference
import com.moe.moetranslator.utils.KeystoreManager
import com.moe.moetranslator.utils.UtilTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import translationapi.bingtranslation.BingTranslation
import translationapi.niutrans.NiuTranslation
import translationapi.openaitranslation.OpenAITranslation
import translationapi.volctranslation.VolcTranslation
import translationapi.azuretranslation.AzureTranslation
import translationapi.deepltranslation.DeepLTranslation
import translationapi.baidutranslation.BaiduTranslationText
import translationapi.tencentcloud.TencentTranslationText
import translationapi.customtranslation.CustomTranslationText
import translationapi.mlkittranslation.MLKitTranslation
import translationapi.nllbtranslation.NLLBTranslation
import kotlin.math.abs

class MangaFloatingService : LifecycleService() {

    companion object {
        private const val TAG = "MangaFloatingService"
        private const val NOTIFICATION_CHANNEL_ID = "manga_floating_service"
        private const val NOTIFICATION_ID = 7

        private const val CLICK_SLOP = 5f
        private const val LONG_PRESS_SLOP = 10f

        // 当前加载的 manga-ocr 版本，用于日志
        @Volatile
        var currentLoadedMangaOcrVersion: String = "unknown"
            private set

        fun start(context: Context) {
            androidx.core.content.ContextCompat.startForegroundService(
                context, Intent(context, MangaFloatingService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MangaFloatingService::class.java))
        }
    }

    private var longPressDelay = 500L

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
    private var isMenuShowing = false

    // Progress overlay
    private lateinit var progressOverlayView: android.widget.TextView
    private var progressOverlayParams: WindowManager.LayoutParams? = null
    private var isProgressShowing = false

    // Toast overlay (替代系统 Toast，不受系统限制)
    private var toastOverlayView: android.widget.TextView? = null
    private var toastOverlayParams: WindowManager.LayoutParams? = null
    private var isToastShowing = false
    private val toastDismissRunnable = Runnable { dismissToastOverlay() }

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
                if (!isResultShowing && !isMenuShowing) {
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
    private var translatorText: TranslationTextAPI? = null

    private val defaultSystemPrompt = "你是一名专业翻译。你的任务是准确、自然地翻译给定的文本。\n具体规则如下： \n1、根据用户的要求，将文本翻译成指定的目标语言；\n2、保持原意和语气；\n3、尽可能保持格式和结构；\n4、直接返回翻译后的文本，不要有任何解释或附加内容；\n5、如果文本已经是目标语言，请按原样返回。"
    private val defaultUserPrompt = "请将下面的文本从usefromlang翻译为usetolang：\n\nusesourcetext"

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
        initTranslator()

        // 互斥：停止普通翻译服务
        try {
            stopService(Intent(this, com.moe.moetranslator.translate.FloatingBallService::class.java))
        } catch (e: Exception) {
            LogCollector.w(TAG, "Could not stop FloatingBallService", e)
        }

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        initializeViews()
        setupScreenshotCollector()

        // 初始化 OCR 引擎（根据配置）
        when (config.ocrEngine) {
            OcrEngine.MLKit -> {}  // MLKit 无需初始化
            OcrEngine.MangaOcr -> lifecycleScope.launch { ensureMangaOcrInitialized() }
            OcrEngine.PPOcrV4 -> lifecycleScope.launch { initPPOcrV4IfNeeded() }
        }

        // 初始化检测引擎
        when (config.detEngine) {
            DetEngine.CTD -> initCTD()
            DetEngine.MLKIT -> {}
            DetEngine.RT_DETR_V2 -> lifecycleScope.launch { initRTDetrV2() }
        }

        LogCollector.d(TAG, "MangaFloatingService created")
    }

    override fun onDestroy() {
        super.onDestroy()
        removeAllViews()
        translatorText?.release()
        autoTranslateHandler.removeCallbacks(autoTranslateRunnable)

        // 释放 OCR 引擎资源
        when (config.ocrEngine) {
            OcrEngine.MLKit -> {}
            OcrEngine.MangaOcr -> releaseMangaOcr()
            OcrEngine.PPOcrV4 -> releasePPOcrV4()
        }

        // 释放检测引擎资源
        when (config.detEngine) {
            DetEngine.CTD -> releaseCTD()
            DetEngine.MLKIT -> {}
            DetEngine.RT_DETR_V2 -> releaseRTDetrV2()
        }

        // 发送广播通知 UI 更新按钮状态
        val stopIntent = Intent("action_manga_floating_service_stopped")
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this).sendBroadcast(stopIntent)
        LogCollector.d(TAG, "MangaFloatingService destroyed")
    }

    // ---------- Initialization ----------

    private fun reloadConfig() {
        config = loadConfig()
        translatorText?.release()
        initTranslator()
    }

    private fun initTranslator() {
        LogCollector.d(TAG, "initTranslator: Text_API=${prefs.getInt("Text_API", Constants.TextApi.BING.id)}")
        try {
            when (prefs.getInt("Text_API", Constants.TextApi.BING.id)) {
                Constants.TextApi.AI.id -> when (prefs.getInt("Text_AI", Constants.TextAI.MLKIT.id)) {
                    Constants.TextAI.MLKIT.id -> translatorText = MLKitTranslation()
                    Constants.TextAI.NLLB.id -> translatorText = NLLBTranslation(this)
                    else -> { showToast("Unknown Translator.") }
                }
                Constants.TextApi.BING.id -> translatorText = BingTranslation()
                Constants.TextApi.NIUTRANS.id -> translatorText = NiuTranslation(KeystoreManager.retrieveKey(this, "Niutrans")!!)
                Constants.TextApi.OPENAI.id -> translatorText = OpenAITranslation(
                    apiKey = prefs.getString("OpenAI_Api_Key", ""),
                    baseUrl = prefs.getString("OpenAI_Base_Url", ""),
                    model = prefs.getString("OpenAI_Model_Name", ""),
                    systemPrompt = prefs.getString("OpenAI_System_Prompt", defaultSystemPrompt),
                    userPrompt = prefs.getString("OpenAI_User_Prompt", defaultUserPrompt)
                )
                Constants.TextApi.VOLC.id -> translatorText = VolcTranslation(KeystoreManager.retrieveKey(this, "Volc_ACCOUNT")!!, KeystoreManager.retrieveKey(this, "Volc_SECRETKEY")!!)
                Constants.TextApi.AZURE.id -> translatorText = AzureTranslation(KeystoreManager.retrieveKey(this, "Azure")!!)
                Constants.TextApi.DEEPL.id -> translatorText = DeepLTranslation(KeystoreManager.retrieveKey(this, "DeepL_Translate_HOST")!!, KeystoreManager.retrieveKey(this, "DeepL_Translate_APIKEY")!!)
                Constants.TextApi.BAIDU.id -> translatorText = BaiduTranslationText(KeystoreManager.retrieveKey(this, "Baidu_Translate_ACCOUNT")!!, KeystoreManager.retrieveKey(this, "Baidu_Translate_SECRETKEY")!!)
                Constants.TextApi.TENCENT.id -> translatorText = TencentTranslationText(KeystoreManager.retrieveKey(this, "Tencent_Cloud_ACCOUNT")!!, KeystoreManager.retrieveKey(this, "Tencent_Cloud_SECRETKEY")!!)
                Constants.TextApi.CUSTOM_TEXT.id -> {
                    val textConfig = ConfigurationStorage.loadTextConfig(prefs, prefs.getInt("Custom_Text_API", 0))
                    if (textConfig != null) {
                        translatorText = CustomTranslationText(textConfig)
                    } else {
                        showToast("No Custom Text API Config Found.")
                    }
                }
                else -> { showToast("Unknown Translator.") }
            }
        } catch (e: Exception) {
            LogCollector.e(TAG, "initTranslator: Exception", e)
            showToast("Initialize Error: ${e.message}")
        }
        LogCollector.d(TAG, "initTranslator: result translatorText=${translatorText?.javaClass?.simpleName}")
    }

    private fun releaseMangaOcr() {
        try {
            LogCollector.d(TAG, "releaseMangaOcr: 释放 manga-ocr 资源")
            MangaOcrRecognizer.release()
        } catch (e: Exception) {
            LogCollector.e(TAG, "releaseMangaOcr: 释放失败", e)
        }
    }

    private fun releasePPOcrV4() {
        try {
            LogCollector.d(TAG, "releasePPOcrV4: 释放 PP-OCRv4 rec 资源")
            PPOcrV4RecRecognizer.release()
        } catch (e: Exception) {
            LogCollector.e(TAG, "releasePPOcrV4: 释放失败", e)
        }
    }

    private fun initCTD() {
        lifecycleScope.launch {
            try {
                // 检查模型是否已下载
                if (!CTDModelManager.isModelAvailable(this@MangaFloatingService)) {
                    LogCollector.d(TAG, "initCTD: CTD 模型未下载")
                    showToast("CTD 模型未下载，请先在模型管理中下载")
                    return@launch
                }
                LogCollector.d(TAG, "initCTD: 开始初始化 CTD")
                CTDDetector.initialize(this@MangaFloatingService)
                LogCollector.d(TAG, "initCTD: CTD 初始化完成")
                showToast("CTD 初始化完成")
            } catch (e: Exception) {
                LogCollector.e(TAG, "initCTD: 初始化失败", e)
                showToast("CTD 初始化失败: ${e.message ?: "未知错误"}")
            }
        }
    }

    private fun releaseCTD() {
        try {
            LogCollector.d(TAG, "releaseCTD: 释放 CTD 资源")
            CTDDetector.release()
        } catch (e: Exception) {
            LogCollector.e(TAG, "releaseCTD: 释放失败", e)
        }
    }

    /**
     * 同步初始化 CTD（如果需要），在 processMangaScreenshot 中调用。
     */
    private suspend fun initCTDIfNeeded() {
        if (CTDDetector.isInitialized) return
        // 检查模型是否已下载
        if (!CTDModelManager.isModelAvailable(this@MangaFloatingService)) {
            LogCollector.d(TAG, "initCTDIfNeeded: CTD 模型未下载")
            withContext(Dispatchers.Main) {
                showToast("CTD 模型未下载，请先在模型管理中下载")
            }
            throw IllegalStateException("CTD model not downloaded")
        }
        try {
            LogCollector.d(TAG, "initCTDIfNeeded: 开始初始化 CTD")
            withContext(Dispatchers.IO) {
                CTDDetector.initialize(this@MangaFloatingService)
            }
            LogCollector.d(TAG, "initCTDIfNeeded: CTD 初始化完成")
        } catch (e: Exception) {
            LogCollector.e(TAG, "initCTDIfNeeded: 初始化失败", e)
            withContext(Dispatchers.Main) {
                showToast("CTD 初始化失败: ${e.message}")
            }
            throw e
        }
    }

    private fun initRTDetrV2() {
        lifecycleScope.launch {
            try {
                initRTDetrV2IfNeeded()
                showToast("RT-DETR-V2 初始化完成")
            } catch (e: Exception) {
                LogCollector.e(TAG, "RT-DETR-V2 初始化失败", e)
                showToast("RT-DETR-V2 初始化失败: ${e.message ?: "未知错误"}")
            }
        }
    }

    private suspend fun initRTDetrV2IfNeeded() {
        if (ComicBubbleDetector.isInitialized) return
        try {
            LogCollector.d(TAG, "initRTDetrV2IfNeeded: 开始初始化 RT-DETR-V2")
            withContext(Dispatchers.IO) {
                ComicBubbleDetector.initialize(this@MangaFloatingService)
            }
            LogCollector.d(TAG, "initRTDetrV2IfNeeded: RT-DETR-V2 初始化完成")
        } catch (e: Exception) {
            LogCollector.e(TAG, "initRTDetrV2IfNeeded: 初始化失败", e)
            withContext(Dispatchers.Main) {
                showToast("RT-DETR-V2 初始化失败: ${e.message}")
            }
            throw e
        }
    }

    private fun releaseRTDetrV2() {
        try {
            LogCollector.d(TAG, "releaseRTDetrV2: 释放 RT-DETR-V2 资源")
            ComicBubbleDetector.release()
        } catch (e: Exception) {
            LogCollector.e(TAG, "releaseRTDetrV2: 释放失败", e)
        }
    }

    /**
     * 确保 manga-ocr 已初始化。
     * 优先使用已下载的模型（通过 MangaOcrDownloadManager 管理），
     * 如果没有下载的模型则提示用户去下载。
     */
    private suspend fun ensureMangaOcrInitialized() {
        val currentConfig = loadConfig()

        when (currentConfig.ocrEngine) {
            OcrEngine.MangaOcr -> {
                val activeVersion = MangaOcrDownloadManager.getActiveVersion(this@MangaFloatingService)
                if (activeVersion != null && MangaOcrDownloadManager.isVersionDownloaded(this@MangaFloatingService, activeVersion)) {
                    try {
                        val versionStr = activeVersion.name
                        // 如果已初始化但版本不匹配，先释放再重新加载
                        if (MangaOcrRecognizer.isInitialized) {
                            MangaOcrRecognizer.release()
                        }
                        LogCollector.d(TAG, "ensureMangaOcrInitialized: 使用已下载的 manga-ocr 模型: $activeVersion")
                        withContext(Dispatchers.IO) {
                            MangaOcrBridge.initializeDownloaded(this@MangaFloatingService, activeVersion)
                        }
                        currentLoadedMangaOcrVersion = versionStr
                    } catch (e: Exception) {
                        LogCollector.e(TAG, "manga-ocr 初始化失败", e)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(applicationContext, R.string.manga_ocr_init_failed, Toast.LENGTH_LONG).show()
                        }
                        return
                    }
                } else {
                    // 未下载，提示用户去下载
                    LogCollector.d(TAG, "ensureMangaOcrInitialized: manga-ocr 未下载，提示用户")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(applicationContext, R.string.manga_ocr_download_required, Toast.LENGTH_LONG).show()
                    }
                    return
                }
            }
            else -> {
                // MLKit 不需要 manga-ocr
                return
            }
        }
        LogCollector.d(TAG, "ensureMangaOcrInitialized: manga-ocr 初始化完成")
    }

    private suspend fun initPPOcrV4IfNeeded() {
        if (PPOcrV4RecRecognizer.isInitialized) return

        try {
            LogCollector.d(TAG, "initPPOcrV4IfNeeded: 开始初始化 PP-OCRv4 rec")
            withContext(Dispatchers.IO) {
                PPOcrV4RecRecognizer.initialize(this@MangaFloatingService)
            }
            LogCollector.d(TAG, "initPPOcrV4IfNeeded: PP-OCRv4 rec 初始化完成")
        } catch (e: Exception) {
            LogCollector.e(TAG, "initPPOcrV4IfNeeded: 初始化失败", e)
            withContext(Dispatchers.Main) {
                showToast("PP-OCRv4 初始化失败: ${e.message}")
            }
            throw e
        }
    }

    private fun loadConfig(): MangaModeConfig {
        return MangaModeConfig(
            enabled = true,
            textDirection = TextDirection.VERTICAL_RL,
            smartBackground = prefs.getBoolean("Manga_Smart_Background", true),
            autoDetectBubble = prefs.getBoolean("Manga_Auto_Detect_Bubble", true),
            fontSize = prefs.getFloat("Manga_Font_Size", 16f),
            autoFontSize = prefs.getBoolean("Manga_Auto_Font_Size", true),
            sourceLang = prefs.getString("Source_Language", "ja"),
            targetLang = prefs.getString("Target_Language", "zh"),
            textColor = prefs.getInt("Manga_Text_Color", android.graphics.Color.BLACK),
            bgColor = prefs.getInt("Manga_BG_Color", android.graphics.Color.argb(200, 255, 255, 255)),
            ocrEngine = OcrEngine.fromValue(prefs.getInt("Manga_Rec_Model", 0)),
            detEngine = DetEngine.fromValue(prefs.getInt("Manga_Det_Model", 0))
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

        // 加载自定义悬浮球图标
        val customPicName = prefs.getString("Custom_Floating_Pic", "")
        if (customPicName.isNotEmpty()) {
            try {
                val iconFile = java.io.File(getExternalFilesDir(null), "icon/$customPicName")
                if (iconFile.exists()) {
                    val bitmap = android.graphics.BitmapFactory.decodeFile(iconFile.absolutePath)
                    floatingBallView.findViewById<ImageView>(R.id.floating_ball_icon)
                        .setImageBitmap(bitmap)
                }
            } catch (e: Exception) {
                LogCollector.w(TAG, "Failed to load custom icon", e)
            }
        }

        // 加载长按判定时间
        longPressDelay = prefs.getLong("Custom_Long_Press_Delay", 500L)

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

        // Toast overlay (底部弹出提示，替代系统 Toast)
        toastOverlayView = android.widget.TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setBackgroundColor(Color.argb(220, 50, 50, 50))
            setPadding(40, 24, 40, 24)
            gravity = android.view.Gravity.CENTER
        }
        toastOverlayParams = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            format = PixelFormat.RGBA_8888
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 200
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
                    handler.postDelayed(longPressRunnable, longPressDelay)
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
        val cropLabel = if (cropRect != null) {
            getString(R.string.manga_mode_crop)
        } else {
            getString(R.string.manga_mode_fullscreen)
        }
        val detModelLabel = when (config.detEngine) {
            DetEngine.CTD -> "CTD"
            DetEngine.MLKIT -> getString(R.string.manga_det_mlkit)
            DetEngine.RT_DETR_V2 -> "RT-DETR-V2"
        }
        val ocrEngineLabel = when (config.ocrEngine) {
            OcrEngine.MLKit -> getString(R.string.manga_ocr_mlkit)
            OcrEngine.MangaOcr -> getString(R.string.manga_ocr_manga_ocr)
            OcrEngine.PPOcrV4 -> "PP-OCRv4"
        }

        val (dialog, listView) = Dialogs.mangaMenuDialog(
            applicationContext, isAutoTranslating, cropLabel, detModelLabel, ocrEngineLabel
        )

        isMenuShowing = true

        listView.onItemClickListener = android.widget.AdapterView.OnItemClickListener { _, _, which, _ ->
            when (which) {
                0 -> {
                    // 切换全屏/框选
                    if (cropRect != null) {
                        cropRect = null
                        showToast(getString(R.string.manga_mode_fullscreen))
                        val adapter = listView.adapter as com.moe.moetranslator.translate.MenuDialogAdapter
                        adapter.updateLabel(0, "${getString(R.string.manga_crop_toggle)}：${getString(R.string.manga_mode_fullscreen)}")
                    } else {
                        dialog.dismiss()
                        handler.postDelayed({ startCropSelection() }, 200)
                    }
                }
                1 -> {
                    // 字体大小（不关闭菜单，打开子对话框）
                    showFontSizeDialog()
                }
                2 -> {
                    // 切换检测模型（不关闭菜单）
                    toggleDetModel(dialog, listView)
                }
                3 -> {
                    // 切换 OCR 引擎（不关闭菜单）
                    toggleOcrEngine(dialog, listView)
                }
                4 -> {
                    // 自动翻译切换（不关闭菜单）
                    toggleAutoTranslate()
                    val adapter = listView.adapter as com.moe.moetranslator.translate.MenuDialogAdapter
                    if (isAutoTranslating) {
                        adapter.updateLabel(4, getString(R.string.manga_menu_stop_auto))
                        adapter.updateIcon(4, R.drawable.stop_auto)
                    } else {
                        adapter.updateLabel(4, getString(R.string.manga_menu_auto_translate))
                        adapter.updateIcon(4, R.drawable.start_auto)
                    }
                }
                5 -> {
                    // 关闭悬浮球
                    dialog.dismiss()
                    stopSelf()
                }
                6 -> {
                    // 返回主界面
                    dialog.dismiss()
                    backToMainActivity()
                }
            }
        }
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
        dialog.window?.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        dialog.setOnDismissListener {
            isMenuShowing = false
        }
    }

    private fun backToMainActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
    }

    // ---------- Fullscreen translate ----------

    private fun clearCrop() {
        cropRect = null
        showToast(getString(R.string.manga_mode_fullscreen))
    }

    // ---------- Menu actions ----------

    private fun toggleOcrEngine(dialog: AlertDialog, listView: android.widget.ListView) {
        // 循环切换：MLKit -> MangaOcr -> PPOcrV4 -> MLKit
        val newEngine = when (config.ocrEngine) {
            OcrEngine.MLKit -> OcrEngine.MangaOcr
            OcrEngine.MangaOcr -> OcrEngine.PPOcrV4
            OcrEngine.PPOcrV4 -> OcrEngine.MLKit
        }
        config = config.copy(ocrEngine = newEngine)
        prefs.setInt("Manga_Rec_Model", newEngine.ordinal)

        val adapter = listView.adapter as com.moe.moetranslator.translate.MenuDialogAdapter
        val label = when (newEngine) {
            OcrEngine.MLKit -> getString(R.string.manga_ocr_mlkit)
            OcrEngine.MangaOcr -> getString(R.string.manga_ocr_manga_ocr)
            OcrEngine.PPOcrV4 -> "PP-OCRv4"
        }
        adapter.updateLabel(3, "${getString(R.string.manga_ocr_toggle)}：$label")

        // 释放旧引擎，初始化新引擎
        when (newEngine) {
            OcrEngine.MLKit -> {
                releaseMangaOcr()
                releasePPOcrV4()
                showToast(getString(R.string.manga_ocr_mlkit))
            }
            OcrEngine.MangaOcr -> {
                releasePPOcrV4()
                showToast(getString(R.string.manga_ocr_initializing))
                lifecycleScope.launch { ensureMangaOcrInitialized() }
            }
            OcrEngine.PPOcrV4 -> {
                releaseMangaOcr()
                showToast("PP-OCRv4 日文识别器")
                lifecycleScope.launch {
                    try {
                        PPOcrV4RecRecognizer.initialize(this@MangaFloatingService)
                        withContext(Dispatchers.Main) {
                            showToast("PP-OCRv4 初始化完成")
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            showToast("PP-OCRv4 初始化失败: ${e.message}")
                        }
                    }
                }
            }
        }
    }

    private fun toggleDetModel(dialog: AlertDialog, listView: android.widget.ListView) {
        // 循环切换：MLKIT -> CTD -> RT_DETR_V2 -> MLKIT
        val newEngine = when (config.detEngine) {
            DetEngine.MLKIT -> DetEngine.CTD
            DetEngine.CTD -> DetEngine.RT_DETR_V2
            DetEngine.RT_DETR_V2 -> DetEngine.MLKIT
        }
        config = config.copy(detEngine = newEngine)
        prefs.setInt("Manga_Det_Model", newEngine.value)

        val adapter = listView.adapter as com.moe.moetranslator.translate.MenuDialogAdapter
        val label = when (newEngine) {
            DetEngine.CTD -> "CTD"
            DetEngine.MLKIT -> getString(R.string.manga_det_mlkit)
            DetEngine.RT_DETR_V2 -> "RT-DETR-V2"
        }
        adapter.updateLabel(2, "${getString(R.string.manga_det_toggle)}：$label")

        // 释放旧引擎，初始化新引擎
        when (newEngine) {
            DetEngine.CTD -> {
                showToast("CTD 初始化中...")
                initCTD()
            }
            DetEngine.MLKIT -> {
                releaseCTD()
                releaseRTDetrV2()
                showToast(getString(R.string.manga_det_mlkit))
            }
            DetEngine.RT_DETR_V2 -> {
                releaseCTD()
                showToast("RT-DETR-V2 初始化中...")
                lifecycleScope.launch { initRTDetrV2() }
            }
        }
    }

    private fun showFontSizeDialog() {
        val sizes = arrayOf(
            getString(R.string.manga_font_size_auto),
            "12", "14", "16", "18", "20", "24", "28", "32"
        )
        val currentIndex = if (config.autoFontSize) {
            0
        } else {
            val idx = sizes.indexOf(config.fontSize.toInt().toString())
            if (idx < 0) 3 else idx
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.manga_font_size_title))
            .setSingleChoiceItems(sizes, currentIndex) { d, which ->
                if (which == 0) {
                    config = config.copy(autoFontSize = true)
                    prefs.setBoolean("Manga_Auto_Font_Size", true)
                    showToast(getString(R.string.manga_font_size_auto))
                } else {
                    val newSize = sizes[which].toFloat()
                    config = config.copy(fontSize = newSize, autoFontSize = false)
                    prefs.setFloat("Manga_Font_Size", newSize)
                    prefs.setBoolean("Manga_Auto_Font_Size", false)
                    showToast("${sizes[which]}sp")
                }
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
            LogCollector.e(TAG, "Error removing crop view", e)
        }
        try {
            cropConfirmView?.let { windowManager.removeView(it) }
        } catch (e: Exception) {
            LogCollector.e(TAG, "Error removing confirm button", e)
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
        LogCollector.d(TAG, "========== triggerTranslation START ==========")
        if (isProcessing) {
            LogCollector.d(TAG, "triggerTranslation: already processing, skipping")
            showToast(getString(R.string.is_translating))
            return
        }
        if (isCropActive) {
            LogCollector.d(TAG, "triggerTranslation: crop is active, skipping")
            return
        }

        val service = AccessibilityServiceManager.getService()
        LogCollector.d(TAG, "triggerTranslation: accessibilityService=$service")
        if (service == null) {
            showToast(getString(R.string.accessibility_recycle))
            return
        }

        // 只重新加载视觉配置（文字方向、字体等），不重新初始化翻译API
        config = loadConfig()

        isProcessing = true

        // 先关闭结果overlay再截图，避免截到翻译结果
        dismissResultOverlay()

        // 根据模式显示不同的进度文本
        if (isAutoTranslating) {
            showProgressOverlay(getString(R.string.manga_auto_detecting))
        } else {
            showProgressOverlay(getString(R.string.manga_translating))
        }

        LogCollector.d(TAG, "triggerTranslation: translatorText=${translatorText?.javaClass?.simpleName}")
        LogCollector.d(TAG, "triggerTranslation: cropRect=$cropRect")
        if (cropRect != null) {
            LogCollector.d(TAG, "triggerTranslation: taking cropped screenshot")
            AccessibilityServiceManager.takeScreenshot(cropRect, cropView.absolutePointOffset)
        } else {
            LogCollector.d(TAG, "triggerTranslation: taking full screenshot")
            AccessibilityServiceManager.takeScreenshot(null, android.graphics.Point(0, 0))
        }
        LogCollector.d(TAG, "========== triggerTranslation END ==========")
    }

    // ---------- Screenshot collection ----------

    private fun setupScreenshotCollector() {
        LogCollector.d(TAG, "setupScreenshotCollector: starting collector coroutine")
        lifecycleScope.launch {
            LogCollector.d(TAG, "Screenshot collector: coroutine started, waiting for screenshots...")
            ScreenshotManager.screenshotFlow.collect { bitmap ->
                LogCollector.d(TAG, "Screenshot collector: BITMAP RECEIVED! ${bitmap.width}x${bitmap.height}")
                try {
                    processMangaScreenshot(bitmap)
                    LogCollector.d(TAG, "Screenshot collector: processMangaScreenshot completed normally")
                } catch (e: Exception) {
                    LogCollector.e(TAG, "Screenshot collector: CAUGHT EXCEPTION", e)
                    showToast(getString(R.string.translation_failed, e.message ?: "Unknown error"))
                    isProcessing = false
                    dismissProgressOverlay()
                }
            }
            LogCollector.d(TAG, "Screenshot collector: collect() returned (THIS SHOULD NEVER HAPPEN)")
        }
    }

    // ---------- Manga translation pipeline ----------

    private suspend fun processMangaScreenshot(bitmap: Bitmap) {
        try {
            LogCollector.d(TAG, "processMangaScreenshot: START")

            // CTD 调试模式：只检测，不翻译，显示结果后直接返回
            if (prefs.getBoolean("CTD_Debug_View", false)) {
                LogCollector.d(TAG, "CTD Debug Mode: 开始检测")
                val debugResult = withContext(Dispatchers.IO) {
                    detectWithCTDDebug(bitmap)
                }
                LogCollector.d(TAG, "CTD Debug Mode: raw=${debugResult.rawBoxes.size}, merged=${debugResult.mergedGroups.size}")

                // 显示调试视图
                showCTDDebugView(bitmap, debugResult)
                return
            }

            // RT-DETR-V2 调试模式：只检测，不翻译，显示所有类别检测框
            if (prefs.getBoolean("RTDetrV2_Debug_View", false) && config.detEngine == DetEngine.RT_DETR_V2) {
                LogCollector.d(TAG, "RT-DETR-V2 Debug Mode: 开始检测")
                initRTDetrV2IfNeeded()
                val debugResult = withContext(Dispatchers.IO) {
                    DetectionBridge.detectWithRTDetrV2Debug(bitmap)
                }
                LogCollector.d(TAG, "RT-DETR-V2 Debug Mode: total=${debugResult.allBubbles.size}, text_bubble=${debugResult.textBubbles.size}, text_free=${debugResult.textFree.size}, bubble=${debugResult.emptyBubbles.size}")
                showRTDetrV2DebugView(bitmap, debugResult)
                return
            }

            // ML Kit 调试模式：只识别，不翻译，显示所有返回数据
            if (prefs.getBoolean("MLKit_Debug_View", false)) {
                LogCollector.d(TAG, "ML Kit Debug Mode: 开始识别")
                val mlKitResult = withContext(Dispatchers.IO) {
                    detectWithMLKitDebug(bitmap, config.sourceLang)
                }
                LogCollector.d(TAG, "ML Kit Debug Mode: blocks=${mlKitResult.textBlocks.size}, totalLines=${mlKitResult.totalLines}, totalElements=${mlKitResult.totalElements}")
                showMLKitDebugView(bitmap, mlKitResult)
                return
            }

            // 确保选中的模型已初始化
            when (config.detEngine) {
                DetEngine.CTD -> initCTDIfNeeded()
                DetEngine.MLKIT -> {}
                DetEngine.RT_DETR_V2 -> initRTDetrV2IfNeeded()
            }
            when (config.ocrEngine) {
                OcrEngine.MLKit -> {}
                OcrEngine.MangaOcr -> ensureMangaOcrInitialized()
                OcrEngine.PPOcrV4 -> initPPOcrV4IfNeeded()
            }

            // Step 1: 文字检测 + 识别
            LogCollector.d(TAG, "processMangaScreenshot: Step 1 - OCR starting, sourceLang=${config.sourceLang}, ocrEngine=${config.ocrEngine}, detEngine=${config.detEngine}")
            val textBlocks: List<TextBlockInfo> = withContext(Dispatchers.IO) {
                when (config.detEngine) {
                    DetEngine.CTD -> {
                        val ctdOcrEngine = when (config.ocrEngine) {
                            OcrEngine.MLKit -> DetectionBridge.CTDOCREngine.MLKit
                            OcrEngine.MangaOcr -> DetectionBridge.CTDOCREngine.MangaOcr
                            OcrEngine.PPOcrV4 -> DetectionBridge.CTDOCREngine.PPOcrV4
                        }
                        LogCollector.d(TAG, "使用 CTD(${ctdOcrEngine.name}) 识别")
                        DetectionBridge.detectWithCTD(bitmap, config.sourceLang, ctdOcrEngine)
                    }
                    DetEngine.MLKIT -> {
                        when (config.ocrEngine) {
                            OcrEngine.MLKit -> {
                                LogCollector.d(TAG, "使用 ML Kit 检测 + 识别")
                                OCRBridge.recognizeWithLocation(config.sourceLang, bitmap)
                            }
                            OcrEngine.MangaOcr -> {
                                LogCollector.d(TAG, "使用 ML Kit 检测 + manga-ocr(${MangaFloatingService.currentLoadedMangaOcrVersion}) 识别")
                                MangaOcrBridge.recognizeWithLocation(bitmap, config.sourceLang)
                            }
                            OcrEngine.PPOcrV4 -> {
                                LogCollector.d(TAG, "使用 ML Kit 检测 + PP-OCRv4 识别")
                                val mlKitBlocks = OCRBridge.recognizeWithLocation(config.sourceLang, bitmap)
                                if (mlKitBlocks.isNotEmpty()) {
                                    val bitmaps = mlKitBlocks.mapNotNull { block ->
                                        block.boundingBox?.let { rect ->
                                            android.graphics.Bitmap.createBitmap(
                                                bitmap,
                                                rect.left.toInt().coerceAtLeast(0),
                                                rect.top.toInt().coerceAtLeast(0),
                                                rect.width().toInt().coerceAtLeast(1),
                                                rect.height().toInt().coerceAtLeast(1)
                                            )
                                        }
                                    }
                                    if (bitmaps.isNotEmpty()) {
                                        val ppTexts = PPOcrV4RecRecognizer.recognizeBatch(bitmaps)
                                        bitmaps.forEachIndexed { index, bmp ->
                                            bmp.recycle()
                                        }
                                        mlKitBlocks.mapIndexed { index, block ->
                                            if (index < ppTexts.size) {
                                                block.copy(text = ppTexts[index])
                                            } else block
                                        }
                                    } else mlKitBlocks
                                } else mlKitBlocks
                            }
                        }
                    }
                    DetEngine.RT_DETR_V2 -> {
                        // RT-DETR-V2 气泡检测 + 指定 OCR 引擎识别
                        val rtdetrOcrEngine = when (config.ocrEngine) {
                            OcrEngine.MLKit -> DetectionBridge.CTDOCREngine.MLKit
                            OcrEngine.MangaOcr -> DetectionBridge.CTDOCREngine.MangaOcr
                            OcrEngine.PPOcrV4 -> DetectionBridge.CTDOCREngine.PPOcrV4
                        }
                        LogCollector.d(TAG, "使用 RT-DETR-V2 + ${rtdetrOcrEngine.name} 识别")
                        DetectionBridge.detectWithRTDetrV2(bitmap, config.sourceLang, rtdetrOcrEngine)
                    }
                }
            }
            LogCollector.d(TAG, "processMangaScreenshot: Step 1 - OCR done, found ${textBlocks.size} text blocks")

            if (textBlocks.isEmpty()) {
                LogCollector.d(TAG, "processMangaScreenshot: No text found, returning early")
                if (!isAutoTranslating) {
                    showToast(getString(R.string.no_text_found))
                }
                return
            }

            // 自动翻译模式下，检查文本是否变化
            val currentOcrText = textBlocks.joinToString("\n") { it.text }
            if (isAutoTranslating && !shouldTranslateText(currentOcrText)) {
                LogCollector.d(TAG, "processMangaScreenshot: Auto-translate text unchanged, skipping")
                return
            }
            lastOcrText = currentOcrText

            // 更新进度文本为"正在翻译…"
            if (isAutoTranslating) {
                showProgressOverlay(getString(R.string.manga_translating))
            }

            // Step 2: Detect bubbles (or use raw blocks)
            LogCollector.d(TAG, "processMangaScreenshot: Step 2 - Detecting bubbles, autoDetect=${config.autoDetectBubble}")
            val bubbles = if (config.autoDetectBubble) {
                BubbleDetector.detectBubbles(textBlocks, config)
            } else {
                textBlocks.filter { it.boundingBox != null }.map { block ->
                    BubbleRegion(
                        rect = block.boundingBox!!,
                        texts = listOf(block.text)
                    )
                }
            }
            LogCollector.d(TAG, "processMangaScreenshot: Step 2 - Detected ${bubbles.size} bubbles")

            // Step 3: Translate each bubble
            LogCollector.d(TAG, "processMangaScreenshot: Step 3 - Starting translateBubbles")
            val translatedBubbles = translateBubbles(bubbles)
            LogCollector.d(TAG, "processMangaScreenshot: Step 3 - translateBubbles done, got ${translatedBubbles.size} results")

            // Step 4: Render overlay
            LogCollector.d(TAG, "processMangaScreenshot: Step 4 - Rendering overlay")
            val resultBitmap = withContext(Dispatchers.Default) {
                OverlayRenderer.renderOverlay(
                    original = bitmap,
                    regions = translatedBubbles,
                    fontSize = config.fontSize,
                    autoFit = config.autoFontSize,
                    textColor = config.textColor,
                    bgColor = config.bgColor
                )
            }
            LogCollector.d(TAG, "processMangaScreenshot: Step 4 - Render done")

            // Step 5: Show result overlay
            LogCollector.d(TAG, "processMangaScreenshot: Step 5 - Showing result overlay")
            withContext(Dispatchers.Main) {
                showResultOverlay(resultBitmap)
            }
            LogCollector.d(TAG, "processMangaScreenshot: Step 5 - DONE")

        } finally {
            bitmap.recycle()
            LogCollector.d(TAG, "processMangaScreenshot: FINALLY - dismissing progress, isProcessing=false")
            isProcessing = false
            dismissProgressOverlay()
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

    /**
     * 检查文本是否仅包含符号/标点（不含实际文字内容）。
     * 用于过滤漫画中的纯符号表达，避免提交给翻译模型导致文字被缩小。
     */
    private fun isSymbolOnlyText(text: String): Boolean {
        if (text.isBlank()) return true
        val stripped = text.replace(Regex("\\s+"), "")
        if (stripped.isEmpty()) return true
        return stripped.all { ch ->
            val type = Character.getType(ch).toByte()
            type == Character.START_PUNCTUATION ||
            type == Character.END_PUNCTUATION ||
            type == Character.DASH_PUNCTUATION ||
            type == Character.OTHER_PUNCTUATION ||
            type == Character.MATH_SYMBOL ||
            type == Character.CURRENCY_SYMBOL ||
            type == Character.MODIFIER_SYMBOL ||
            type == Character.OTHER_SYMBOL ||
            ch == '♡' || ch == '♥' || ch == '♪' || ch == '♫' ||
            ch == '〜' || ch == '～' || ch == '…' || ch == '─'
        }
    }

    private suspend fun translateBubbles(
        bubbles: List<BubbleRegion>
    ): List<TranslatedBubble> {
        LogCollector.d(TAG, "translateBubbles: ${bubbles.size} bubbles, translatorText=${translatorText?.javaClass?.simpleName}")
        if (translatorText == null) {
            LogCollector.e(TAG, "translateBubbles: translatorText is NULL!")
            throw RuntimeException("Translation API not initialized")
        }

        // 准备气泡数据：清理文本，过滤空的，分离纯符号气泡
        val preparedBubbles = mutableListOf<Pair<BubbleRegion, String>>()
        val symbolOnlyBubbles = mutableListOf<TranslatedBubble>()

        for (bubble in bubbles) {
            val cleaned = bubble.texts.map { cleanOcrText(it) }.filter { it.isNotBlank() }
            if (cleaned.isEmpty()) continue
            val combinedText = cleaned.joinToString("")

            if (isSymbolOnlyText(combinedText)) {
                // 纯符号气泡：跳过翻译，保留原文
                LogCollector.d(TAG, "translateBubbles: skipping symbol-only: '$combinedText'")
                symbolOnlyBubbles.add(TranslatedBubble(
                    rect = bubble.rect,
                    originalText = combinedText,
                    translatedText = combinedText,
                    backgroundColor = Color.TRANSPARENT,
                    fontSize = bubble.fontSize,
                    direction = bubble.direction
                ))
            } else {
                preparedBubbles.add(bubble to combinedText)
            }
        }
        if (preparedBubbles.isEmpty()) return symbolOnlyBubbles

        // AI翻译（OpenAI兼容）用批量请求，机器翻译用逐个请求
        val isAI = translatorText is translationapi.openaitranslation.OpenAITranslation
                || translatorText?.javaClass?.simpleName?.contains("Custom") == true

        val translatedResults = if (isAI && preparedBubbles.size > 1) {
            translateBubblesBatch(preparedBubbles)
        } else {
            translateBubblesSequential(preparedBubbles)
        }

        return symbolOnlyBubbles + translatedResults
    }

    /**
     * AI翻译：所有气泡合并为一次请求，用编号分隔
     */
    private suspend fun translateBubblesBatch(
        bubbles: List<Pair<BubbleRegion, String>>
    ): List<TranslatedBubble> = withContext(Dispatchers.IO) {
        LogCollector.d(TAG, "translateBubblesBatch: ${bubbles.size} bubbles in 1 request")

        // 构建带编号的文本，前置格式约束保证 AI 按编号返回
        val formatInstruction = "请逐条翻译以下文本，保持每条的[N]编号格式不变，只输出翻译结果，不要添加额外解释：\n"
        val numberedText = formatInstruction + bubbles.mapIndexed { index, (_, text) ->
            "[${index + 1}] $text"
        }.joinToString("\n")

        val latch = java.util.concurrent.CountDownLatch(1)
        var resultText: String? = null
        var errorMsg: String? = null

        // 直接调用翻译API，使用用户在设置页面配置的系统提示词和用户提示词
        // numberedText 会替换用户提示词中的 usesourcetext 占位符
        translatorText?.getTranslation(
            numberedText,
            config.sourceLang,
            config.targetLang
        ) { result ->
            when (result) {
                is TranslationResult.Success -> {
                    resultText = result.translatedText
                }
                is TranslationResult.Error -> {
                    errorMsg = result.error.message ?: "Unknown error"
                }
            }
            latch.countDown()
        } ?: run {
            errorMsg = "translatorText is null"
            latch.countDown()
        }

        val completed = latch.await(60, java.util.concurrent.TimeUnit.SECONDS)
        if (!completed) {
            throw RuntimeException("AI batch translation timeout (60s)")
        }
        if (errorMsg != null) {
            throw RuntimeException("AI batch translation failed: $errorMsg")
        }

        // 按编号解析结果
        val translations = parseNumberedTranslations(resultText!!, bubbles.size)
        LogCollector.d(TAG, "translateBubblesBatch: parsed ${translations.size} translations")
        // 输出翻译结果
        for (i in translations.indices) {
            val (bubble, original) = bubbles[i]
            val translated = translations[i]
            LogCollector.d(TAG, "翻译结果[$i]: orig='$original' → trans='$translated'")
        }

        bubbles.mapIndexed { index, (bubble, originalText) ->
            TranslatedBubble(
                rect = bubble.rect,
                originalText = originalText,
                translatedText = translations.getOrElse(index) { originalText },
                backgroundColor = Color.TRANSPARENT,
                fontSize = bubble.fontSize,
                direction = bubble.direction
            )
        }
    }

    /**
     * 解析带编号的翻译结果
     * 支持格式: "[1] 翻译文本" 或 "1. 翻译文本" 或 "1、翻译文本"
     */
    private fun parseNumberedTranslations(text: String, expectedCount: Int): List<String> {
        val results = mutableListOf<String>()
        // 匹配 [N] 或 N. 或 N、开头的行
        val pattern = Regex("""\[(\d+)]\s*([\s\S]*?)(?=\[\d+]|$)""")
        val matches = pattern.findAll(text).toList()

        if (matches.size >= expectedCount) {
            for (match in matches.take(expectedCount)) {
                results.add(match.groupValues[2].trim())
            }
        } else {
            // 降级：按行拆分
            val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
            for (line in lines) {
                val cleaned = line.replace(Regex("""^\[?\d+]?[.、\s]*"""), "").trim()
                if (cleaned.isNotBlank()) {
                    results.add(cleaned)
                }
            }
        }

        // 补齐不足的部分
        while (results.size < expectedCount) {
            results.add("")
        }
        return results.take(expectedCount)
    }

    /**
     * 机器翻译：逐个气泡请求
     */
    private suspend fun translateBubblesSequential(
        bubbles: List<Pair<BubbleRegion, String>>
    ): List<TranslatedBubble> = withContext(Dispatchers.IO) {
        LogCollector.d(TAG, "translateBubblesSequential: ${bubbles.size} bubbles, sequential")

        val results = mutableListOf<TranslatedBubble>()
        val errors = mutableListOf<String>()
        for ((bubble, combinedText) in bubbles) {
            LogCollector.d(TAG, "translateBubblesSequential: translating '$combinedText'")

            val latch = java.util.concurrent.CountDownLatch(1)
            var successResult: TranslatedBubble? = null
            var errorMsg: String? = null

            translatorText?.getTranslation(
                combinedText,
                config.sourceLang,
                config.targetLang
            ) { result ->
                when (result) {
                    is TranslationResult.Success -> {
                        LogCollector.d(TAG, "translateBubblesSequential: SUCCESS for '$combinedText'")
                        successResult = TranslatedBubble(
                            rect = bubble.rect,
                            originalText = combinedText,
                            translatedText = result.translatedText,
                            backgroundColor = Color.TRANSPARENT,
                            fontSize = bubble.fontSize,
                            direction = bubble.direction
                        )
                    }
                    is TranslationResult.Error -> {
                        errorMsg = result.error.message ?: "Unknown error"
                        LogCollector.e(TAG, "translateBubblesSequential: ERROR: $errorMsg")
                    }
                }
                latch.countDown()
            } ?: run {
                errorMsg = "translatorText is null"
                latch.countDown()
            }

            val completed = latch.await(30, java.util.concurrent.TimeUnit.SECONDS)
            if (!completed) {
                errorMsg = "Translation timeout (30s)"
            }

            if (successResult != null) {
                results.add(successResult!!)
            } else if (errorMsg != null) {
                errors.add(errorMsg!!)
            }
        }

        LogCollector.d(TAG, "translateBubblesSequential: ${results.size} successful out of ${bubbles.size}")
        if (results.isEmpty() && bubbles.isNotEmpty()) {
            val errorDetail = errors.distinct().joinToString("; ")
            throw RuntimeException("All bubbles failed to translate: $errorDetail")
        }
        results
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
                LogCollector.e(TAG, "Error dismissing overlay", e)
            }
            isResultShowing = false

            // 重置自动翻译定时器，给用户时间翻页/滑动
            if (isAutoTranslating) {
                val delay = prefs.getLong("Auto_Translate_Dismiss_Delay", 1000L)
                autoTranslateHandler.removeCallbacks(autoTranslateRunnable)
                autoTranslateHandler.postDelayed(autoTranslateRunnable, delay)
            }
        }
    }

    /**
     * CTD 调试模式：渲染检测结果到图片上并显示
     */
    private fun showCTDDebugView(bitmap: Bitmap, debugResult: CTDDebugResult) {
        // 创建带调试框的可视化图片
        val debugBitmap = renderCTDDebugOverlay(bitmap, debugResult)
        showCTDDebugResultOverlay(debugBitmap, debugResult)
    }

    private fun renderCTDDebugOverlay(bitmap: Bitmap, debugResult: CTDDebugResult): Bitmap {
        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = android.graphics.Canvas(result)

        val rawPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.RED
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 4f
        }

        val mergedPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.BLUE
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 6f
        }

        val textPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 24f
            isAntiAlias = true
            setShadowLayer(2f, 1f, 1f, android.graphics.Color.BLACK)
        }

        val fillPaint = android.graphics.Paint().apply {
            style = android.graphics.Paint.Style.FILL
        }

        // 绘制被过滤丢弃的框（红色）D=Discarded
        for ((idx, box) in debugResult.discardedBoxes.withIndex()) {
            val aabb = box.aabb
            fillPaint.color = android.graphics.Color.argb(60, 255, 0, 0)
            canvas.drawRect(aabb.left.toFloat(), aabb.top.toFloat(), aabb.right.toFloat(), aabb.bottom.toFloat(), fillPaint)
            val redPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.RED
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 2f
            }
            canvas.drawRect(aabb.left.toFloat(), aabb.top.toFloat(), aabb.right.toFloat(), aabb.bottom.toFloat(), redPaint)
            val label = "D[$idx]"
            canvas.drawText(label, aabb.left.toFloat() + 4, aabb.top.toFloat() + 20, textPaint)
        }

        // 绘制原始未合并的框（绿色）R=Raw
        for ((idx, box) in debugResult.rawBoxes.withIndex()) {
            val aabb = box.aabb
            fillPaint.color = android.graphics.Color.argb(80, 0, 255, 0)
            canvas.drawRect(aabb.left.toFloat(), aabb.top.toFloat(), aabb.right.toFloat(), aabb.bottom.toFloat(), fillPaint)
            val greenPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.GREEN
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 4f
            }
            canvas.drawRect(aabb.left.toFloat(), aabb.top.toFloat(), aabb.right.toFloat(), aabb.bottom.toFloat(), greenPaint)
            val label = "R[$idx]"
            canvas.drawText(label, aabb.left.toFloat() + 4, aabb.top.toFloat() + 20, textPaint)
        }

        // 绘制真正合并的框（蓝色）M=Merged - 只画 size > 1 的组
        for ((groupIdx, group) in debugResult.mergedGroups.withIndex()) {
            if (group.size < 2) continue  // size=1 表示没有合并，跳过

            var left = Int.MAX_VALUE; var top = Int.MAX_VALUE
            var right = Int.MIN_VALUE; var bottom = Int.MIN_VALUE
            for (qb in group) {
                val aabb = qb.aabb
                left = minOf(left, aabb.left)
                top = minOf(top, aabb.top)
                right = maxOf(right, aabb.right)
                bottom = maxOf(bottom, aabb.bottom)
            }

            // 计算原始索引用于日志对应
            val rawIndices = group.mapNotNull { qb -> debugResult.rawBoxes.indexOf(qb).takeIf { it >= 0 } }

            fillPaint.color = android.graphics.Color.argb(80, 0, 0, 255)
            canvas.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), fillPaint)
            val bluePaint = android.graphics.Paint().apply {
                color = android.graphics.Color.BLUE
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 6f
            }
            canvas.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), bluePaint)
            // 标签不重叠：Y 坐标随 groupIdx 递增偏移
            val labelY = top.toFloat() + 20f + groupIdx * 25f
            val label = "M[$groupIdx]:${group.size}boxes"
            canvas.drawText(label, left.toFloat() + 4, labelY, textPaint)
        }

        // 绘制图例
        val legendY = bitmap.height - 60f
        textPaint.color = android.graphics.Color.GREEN
        canvas.drawText("绿色=原始 (${debugResult.rawBoxes.size})", 20f, legendY, textPaint)
        textPaint.color = android.graphics.Color.BLUE
        canvas.drawText("蓝色=合并 (${debugResult.mergedGroups.size}组)", 20f, legendY - 35f, textPaint)
        textPaint.color = android.graphics.Color.RED
        canvas.drawText("红色=丢弃 (${debugResult.discardedBoxes.size})", 20f, legendY - 70f, textPaint)
        textPaint.color = android.graphics.Color.WHITE
        canvas.drawText("点击任意位置关闭", 20f, legendY - 105f, textPaint)

        return result
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showCTDDebugResultOverlay(debugBitmap: Bitmap, debugResult: CTDDebugResult) {
        if (isResultShowing) {
            dismissResultOverlay()
        }

        resultOverlayView.setImageBitmap(debugBitmap)
        resultOverlayView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                dismissResultOverlay()
            }
            true
        }

        resultOverlayView.scaleType = ImageView.ScaleType.FIT_CENTER
        windowManager.addView(resultOverlayView, resultOverlayParams)
        isResultShowing = true

        val mergedCount = debugResult.mergedGroups.count { it.size > 1 }
        showToast("CTD Debug: ${debugResult.rawBoxes.size} 个原始框, ${debugResult.mergedGroups.size} 个组合, $mergedCount 个实际合并, ${debugResult.discardedBoxes.size} 个丢弃")

        // Keep floating ball on top
        if (isViewAdded(floatingBallView)) {
            windowManager.removeView(floatingBallView)
            windowManager.addView(floatingBallView, floatingBallParams)
        }
    }

    /**
     * RT-DETR-V2 调试模式：渲染检测结果到图片上并显示
     */
    private fun showRTDetrV2DebugView(bitmap: Bitmap, debugResult: RTDetrV2DebugResult) {
        val debugBitmap = renderRTDetrV2DebugOverlay(bitmap, debugResult)
        showRTDetrV2DebugResultOverlay(debugBitmap, debugResult)
    }

    private fun renderRTDetrV2DebugOverlay(bitmap: Bitmap, debugResult: RTDetrV2DebugResult): Bitmap {
        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = android.graphics.Canvas(result)

        val textPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 28f
            isAntiAlias = true
            setShadowLayer(3f, 1f, 1f, android.graphics.Color.BLACK)
        }

        val fillPaint = android.graphics.Paint().apply {
            style = android.graphics.Paint.Style.FILL
        }

        val strokePaint = android.graphics.Paint().apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 4f
        }

        // 0: bubble（无文字气泡）— 红色
        for ((idx, b) in debugResult.emptyBubbles.withIndex()) {
            fillPaint.color = android.graphics.Color.argb(50, 255, 0, 0)
            strokePaint.color = android.graphics.Color.RED
            strokePaint.strokeWidth = 2f
            canvas.drawRect(b.rect.left.toFloat(), b.rect.top.toFloat(), b.rect.right.toFloat(), b.rect.bottom.toFloat(), fillPaint)
            canvas.drawRect(b.rect.left.toFloat(), b.rect.top.toFloat(), b.rect.right.toFloat(), b.rect.bottom.toFloat(), strokePaint)
            textPaint.color = android.graphics.Color.RED
            canvas.drawText("bubble[$idx] ${String.format("%.0f%%", b.confidence * 100)}", b.rect.left.toFloat() + 4, b.rect.top.toFloat() + 24, textPaint)
        }

        // 1: text_bubble（气泡内文字）— 绿色
        for ((idx, b) in debugResult.textBubbles.withIndex()) {
            fillPaint.color = android.graphics.Color.argb(60, 0, 255, 0)
            strokePaint.color = android.graphics.Color.GREEN
            strokePaint.strokeWidth = 4f
            canvas.drawRect(b.rect.left.toFloat(), b.rect.top.toFloat(), b.rect.right.toFloat(), b.rect.bottom.toFloat(), fillPaint)
            canvas.drawRect(b.rect.left.toFloat(), b.rect.top.toFloat(), b.rect.right.toFloat(), b.rect.bottom.toFloat(), strokePaint)
            textPaint.color = android.graphics.Color.GREEN
            canvas.drawText("text_bubble[$idx] ${String.format("%.0f%%", b.confidence * 100)}", b.rect.left.toFloat() + 4, b.rect.top.toFloat() + 24, textPaint)
        }

        // 2: text_free（自由文字）— 蓝色
        for ((idx, b) in debugResult.textFree.withIndex()) {
            fillPaint.color = android.graphics.Color.argb(60, 0, 100, 255)
            strokePaint.color = android.graphics.Color.CYAN
            strokePaint.strokeWidth = 4f
            canvas.drawRect(b.rect.left.toFloat(), b.rect.top.toFloat(), b.rect.right.toFloat(), b.rect.bottom.toFloat(), fillPaint)
            canvas.drawRect(b.rect.left.toFloat(), b.rect.top.toFloat(), b.rect.right.toFloat(), b.rect.bottom.toFloat(), strokePaint)
            textPaint.color = android.graphics.Color.CYAN
            canvas.drawText("text_free[$idx] ${String.format("%.0f%%", b.confidence * 100)}", b.rect.left.toFloat() + 4, b.rect.top.toFloat() + 24, textPaint)
        }

        // 最终提交给OCR的区域 — 黄色粗框（最上层）
        for ((idx, rect) in debugResult.finalRegions.withIndex()) {
            strokePaint.color = android.graphics.Color.YELLOW
            strokePaint.strokeWidth = 6f
            canvas.drawRect(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat(), strokePaint)
            textPaint.color = android.graphics.Color.YELLOW
            textPaint.textSize = 28f
            canvas.drawText("OCR[$idx]", rect.left.toFloat() + 4, rect.bottom.toFloat() - 8, textPaint)
        }

        // 图例
        val legendY = bitmap.height - 40f
        textPaint.textSize = 24f
        textPaint.color = android.graphics.Color.GREEN
        canvas.drawText("绿色=text_bubble(${debugResult.textBubbles.size})", 20f, legendY - 100f, textPaint)
        textPaint.color = android.graphics.Color.CYAN
        canvas.drawText("蓝色=text_free(${debugResult.textFree.size}) 丢弃", 20f, legendY - 70f, textPaint)
        textPaint.color = android.graphics.Color.RED
        canvas.drawText("红色=bubble(${debugResult.emptyBubbles.size}) 压缩15%", 20f, legendY - 40f, textPaint)
        textPaint.color = android.graphics.Color.YELLOW
        canvas.drawText("黄色=最终提交(${debugResult.finalRegions.size})", 20f, legendY - 10f, textPaint)

        return result
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showRTDetrV2DebugResultOverlay(debugBitmap: Bitmap, debugResult: RTDetrV2DebugResult) {
        if (isResultShowing) {
            dismissResultOverlay()
        }

        resultOverlayView.setImageBitmap(debugBitmap)
        resultOverlayView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                dismissResultOverlay()
            }
            true
        }

        resultOverlayView.scaleType = ImageView.ScaleType.FIT_CENTER
        windowManager.addView(resultOverlayView, resultOverlayParams)
        isResultShowing = true

        showToast("RT-DETR-V2 Debug: green=${debugResult.textBubbles.size}, blue=${debugResult.textFree.size}(丢弃), red=${debugResult.emptyBubbles.size}(压缩), 最终提交=${debugResult.finalRegions.size}")

        // Keep floating ball on top
        if (isViewAdded(floatingBallView)) {
            windowManager.removeView(floatingBallView)
            windowManager.addView(floatingBallView, floatingBallParams)
        }
    }

    private fun showProgressOverlay(text: String = getString(R.string.manga_translating)) {
        if (isProgressShowing) {
            progressOverlayView.text = text
            return
        }
        try {
            progressOverlayView.text = text
            windowManager.addView(progressOverlayView, progressOverlayParams)
            isProgressShowing = true
            // Keep floating ball on top
            if (isViewAdded(floatingBallView)) {
                windowManager.removeView(floatingBallView)
                windowManager.addView(floatingBallView, floatingBallParams)
            }
        } catch (e: Exception) {
            LogCollector.e(TAG, "Error showing progress", e)
        }
    }

    private fun dismissProgressOverlay() {
        if (isProgressShowing) {
            try {
                windowManager.removeView(progressOverlayView)
            } catch (e: Exception) {
                LogCollector.e(TAG, "Error dismissing progress", e)
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
            LogCollector.e(TAG, "Error removing floating ball", e)
        }
        dismissResultOverlay()
        dismissProgressOverlay()
        dismissToastOverlay()
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
        handler.post {
            try {
                toastOverlayView?.text = message
                if (!isToastShowing) {
                    windowManager.addView(toastOverlayView, toastOverlayParams)
                    isToastShowing = true
                }
                handler.removeCallbacks(toastDismissRunnable)
                handler.postDelayed(toastDismissRunnable, 2500)
            } catch (e: Exception) {
                LogCollector.e(TAG, "showToast failed: $message", e)
            }
        }
    }

    private fun dismissToastOverlay() {
        if (isToastShowing) {
            try {
                windowManager.removeView(toastOverlayView)
            } catch (_: Exception) {}
            isToastShowing = false
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

    // ========== ML Kit 调试模式 ==========

    private suspend fun detectWithMLKitDebug(bitmap: Bitmap, language: String): MLKitDebugResult {
        return DetectionBridge.detectWithMLKitDebug(bitmap, language)
    }

    /**
     * ML Kit 调试模式：渲染所有识别数据到图片上
     */
    private fun showMLKitDebugView(bitmap: Bitmap, result: MLKitDebugResult) {
        val debugBitmap = renderMLKitDebugOverlay(bitmap, result)
        showMLKitDebugResultOverlay(debugBitmap, result)
    }

    private fun renderMLKitDebugOverlay(original: Bitmap, result: MLKitDebugResult): Bitmap {
        val mutableBitmap = original.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = android.graphics.Canvas(mutableBitmap)

        // 块级框（绿色）
        val blockPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.GREEN
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 3f
            isAntiAlias = true
        }

        // 行级框（黄色）
        val linePaint = android.graphics.Paint().apply {
            color = android.graphics.Color.YELLOW
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 2f
            isAntiAlias = true
        }

        // 元素级框（红色）
        val elementPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.RED
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 1f
            isAntiAlias = true
        }

        // 四角点圆点画笔
        val blockPointPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.GREEN
            style = android.graphics.Paint.Style.FILL
        }
        val linePointPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.YELLOW
            style = android.graphics.Paint.Style.FILL
        }
        val elementPointPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.RED
            style = android.graphics.Paint.Style.FILL
        }

        // 绘制每个块（只画框和角点，不画文字）
        for (block in result.textBlocks) {
            // 块边界框（绿色）+ 四角点
            block.blockRect?.let { rect ->
                canvas.drawRect(rect, blockPaint)
            }
            block.blockCorners?.let { corners ->
                for (pt in corners) {
                    canvas.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 5f, blockPointPaint)
                }
            }

            // 行（黄色）+ 四角点
            for (line in block.lines) {
                line.lineRect?.let { rect ->
                    canvas.drawRect(rect, linePaint)
                }
                line.lineCorners?.let { corners ->
                    for (pt in corners) {
                        canvas.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 3f, linePointPaint)
                    }
                }

                // 元素（红色）+ 四角点
                for (element in line.elements) {
                    element.elementRect?.let { rect ->
                        canvas.drawRect(rect, elementPaint)
                    }
                    element.elementCorners?.let { corners ->
                        for (pt in corners) {
                            canvas.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 2f, elementPointPaint)
                        }
                    }
                }
            }
        }

        return mutableBitmap
    }

    private fun showMLKitDebugResultOverlay(debugBitmap: Bitmap, result: MLKitDebugResult) {
        lifecycleScope.launch(Dispatchers.Main) {
            if (isResultShowing) {
                dismissResultOverlay()
            }
            dismissProgressOverlay()

            val overlayView = android.widget.FrameLayout(this@MangaFloatingService)
            val imageView = android.widget.ImageView(this@MangaFloatingService).apply {
                setImageBitmap(debugBitmap)
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = true
            }

            // 底部信息栏
            val infoText = android.widget.TextView(this@MangaFloatingService).apply {
                text = buildString {
                    appendLine("ML Kit 调试模式")
                    appendLine("块: ${result.textBlocks.size}  行: ${result.totalLines}  元素: ${result.totalElements}")
                    appendLine("语言: ${result.detectedLanguage ?: "未知"}")
                    appendLine("绿=块  黄=行  红=元素")
                    appendLine("")
                    for ((i, block) in result.textBlocks.withIndex()) {
                        val bRect = block.blockRect?.let { "[${it.left},${it.top},${it.right},${it.bottom}]" } ?: ""
                        appendLine("B${i} $bRect \"${block.blockText.take(25)}\" ${block.language ?: ""}")
                        for ((j, line) in block.lines.withIndex()) {
                            val lRect = line.lineRect?.let { "[${it.left},${it.top},${it.right},${it.bottom}]" } ?: ""
                            appendLine("  L${j} $lRect ${String.format("%.1f", line.angle)}° ${line.elements.size}elem \"${line.lineText.take(20)}\"")
                            for (element in line.elements) {
                                val eRect = element.elementRect?.let { "[${it.left},${it.top}]" } ?: ""
                                appendLine("    $eRect \"${element.elementText}\"")
                            }
                        }
                    }
                }
                textSize = 11f
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundColor(android.graphics.Color.argb(220, 0, 0, 0))
                setPadding(16, 16, 16, 16)
            }

            val scrollView = android.widget.ScrollView(this@MangaFloatingService).apply {
                addView(infoText)
            }

            overlayView.addView(imageView, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            ))
            overlayView.addView(scrollView, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                600
            ).apply { gravity = android.view.Gravity.BOTTOM })

            overlayView.setOnClickListener {
                try {
                    windowManager.removeView(overlayView)
                    isResultShowing = false
                    debugBitmap.recycle()
                } catch (e: Exception) { }
            }

            val params = android.view.WindowManager.LayoutParams(
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                android.graphics.PixelFormat.TRANSLUCENT
            )

            try {
                windowManager.addView(overlayView, params)
                isResultShowing = true
            } catch (e: Exception) {
                LogCollector.e(TAG, "ML Kit Debug: 显示失败", e)
            }
        }
    }
}
