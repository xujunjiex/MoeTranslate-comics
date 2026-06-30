package com.moe.moetranslator.manga

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.widget.Toast
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
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
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.moe.moetranslator.MainActivity
import com.moe.moetranslator.R
import com.moe.moetranslator.bridge.OCRBridge
import com.moe.moetranslator.bridge.TextBlockInfo
import com.moe.moetranslator.me.ConfigurationStorage
import com.moe.moetranslator.me.OpenAIProviderConfig
import com.moe.moetranslator.translate.AccessibilityProvider
import com.moe.moetranslator.translate.AccessibilityEventHandler
import com.moe.moetranslator.translate.AccessibilityServiceManager
import com.moe.moetranslator.translate.CropView
import com.moe.moetranslator.translate.Dialogs
import com.moe.moetranslator.translate.MediaProjectionProvider
import com.moe.moetranslator.translate.ScreenshotData
import com.moe.moetranslator.translate.ScreenshotManager
import com.moe.moetranslator.translate.MediaProjectionIntentHolder
import com.moe.moetranslator.translate.ScreenshotProvider
import com.moe.moetranslator.translate.ScreenCapturePermissionActivity
import com.moe.moetranslator.translate.TranslationResult
import com.moe.moetranslator.translate.TranslationTextAPI
import com.moe.moetranslator.utils.Constants
import com.moe.moetranslator.utils.CustomPreference
import com.moe.moetranslator.utils.KeystoreManager
import com.moe.moetranslator.utils.TextSimilarity
import com.moe.moetranslator.utils.TranslationStatusOverlay
import com.moe.moetranslator.utils.UtilTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import translationapi.bingtranslation.BingTranslation
import translationapi.niutrans.NiuTranslation
import translationapi.openaitranslation.OpenAITranslation
import translationapi.doubaotranslation.DoubaoTranslation
import translationapi.volctranslation.VolcTranslation
import translationapi.azuretranslation.AzureTranslation
import translationapi.deepltranslation.DeepLTranslation
import translationapi.baidutranslation.BaiduTranslationText
import translationapi.tencentcloud.TencentTranslationText
import translationapi.customtranslation.CustomTranslationText
import translationapi.mlkittranslation.MLKitTranslation
import translationapi.nllbtranslation.NLLBTranslation
import com.moe.moetranslator.data.CacheEntry
import com.moe.moetranslator.data.TranslationCacheManager
import com.moe.moetranslator.utils.PerceptualHash
import java.util.LinkedList
import kotlin.math.abs

class MangaFloatingService : LifecycleService() {

    companion object {
        private const val TAG = "MangaFloatingService"
        private const val NOTIFICATION_CHANNEL_ID = "manga_floating_service"
        private const val NOTIFICATION_ID = 7

        // 前台服务通知 ID（MediaProjection 模式需要）
        private const val FOREGROUND_NOTIFICATION_ID = 34766
        private const val SCREEN_CAPTURE_CHANNEL_ID = "screen_capture"

        private const val CLICK_SLOP = 5f
        private const val LONG_PRESS_SLOP = 10f
        private const val DOUBLE_CLICK_DELAY = 300L

        // pHash 阈值常量
        const val PHASH_STABLE_THRESHOLD = 0.95f   // >= 此值认为画面没变
        const val PHASH_NEW_PAGE_THRESHOLD = 0.60f  // < 此值认为是全新页面
        const val STABLE_CONFIRM_COUNT = 2          // 连续稳定次数
        const val DETECT_INTERVAL_MS = 500L         // 运动中检测间隔
        const val REGION_IOU_THRESHOLD = 0.4f       // 区域重叠判定阈值
        const val MAX_CACHED_REGIONS = 50           // 最大缓存区域数
        const val REGION_TTL_MS = 300_000L          // 区域缓存有效期 5 分钟

        // 分批渲染常量
        const val INCREMENTAL_THRESHOLD = 6       // 触发分批的气泡数量阈值
        const val CLUSTER_THRESHOLD = 250f        // 空间聚类加权距离阈值

        // 重新翻译广播 Action
        const val ACTION_RETRANSLATE_REQUEST = "com.moe.moetranslator.RETRANSLATE_REQUEST"
        const val ACTION_RETRANSLATE_COMPLETE = "com.moe.moetranslator.RETRANSLATE_COMPLETE"

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

    // 手势动作配置
    private var singleClickAction = Constants.BallAction.TRANSLATE
    private var doubleClickAction = Constants.BallAction.AUTO_TRANSLATE
    private var longPressAction = Constants.BallAction.MENU

    // 双击检测
    private var lastClickTime = 0L
    private val singleClickRunnable = Runnable { executeAction(singleClickAction) }

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

    // 翻译状态提示条
    private lateinit var statusOverlay: TranslationStatusOverlay

    // 重新翻译广播接收器
    private var retranslateReceiver: BroadcastReceiver? = null

    // SharedPreferences listener（防止被 GC 回收）
    private var prefChangeListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    // Long press detection
    private val handler = Handler(Looper.getMainLooper())
    private val longPressRunnable = Runnable { handleLongPress() }
    private var currentGesture: GestureType? = null

    // AI 上下文（仅 OpenAI 兼容 API）
    private var contextEnabled = false
    private var contextMaxCount = 5
    private val contextHistory = LinkedList<Pair<String, String>>()  // (原文, 译文) 对

    // Auto-translate — 基于图像哈希 + 区域级缓存的智能自动翻译
    private var isAutoTranslating = false
    private var isManualTranslating = false  // 手动翻译标志：暂停自动检测，跳过 pHash 门控
    private var wasAutoTranslatingBeforeCrop = false  // 框选前的自动翻译状态
    private val autoTranslateHandler = Handler(Looper.getMainLooper())
    private var consecutiveEmptyCount = 0  // 连续未检测到文字的计数（用于检测受保护区域）
    private var pendingAutoStart = false   // 等待权限授权后自动启动

    // 检测状态机
    private enum class DetectState { IDLE, MOTION, STABLE }
    private var detectState = DetectState.IDLE
    private var lastTranslatedHash = 0L        // 上次翻译页的哈希（IDLE 判断是否需翻译）
    private var previousScreenshotHash = 0L    // 上一次截图的哈希（MOTION 判断页面是否稳定）
    private var stableCount = 0
    private var motionStartTime = 0L

    // 区域级翻译缓存
    private data class TranslatedRegion(
        val ocrText: String,
        val ocrTextHash: Int,
        val translation: String,
        val translatedAt: Long = System.currentTimeMillis()
    )
    private val translatedRegions = mutableListOf<TranslatedRegion>()

    // Crop selection
    private lateinit var cropView: CropView
    private var cropViewParams: WindowManager.LayoutParams? = null
    private var cropRect: RectF? = null
    private var isCropActive = false

    private lateinit var prefs: CustomPreference
    private lateinit var config: MangaModeConfig
    private var translatorText: TranslationTextAPI? = null

    // 截图提供者
    private var screenshotProvider: ScreenshotProvider? = null

    // 缓存管理
    private lateinit var cacheManager: TranslationCacheManager
    private var forceRefresh = false
    private var isForceRefreshActive = false  // 保存 forceRefresh 状态，用于保存缓存时判断
    private var lastCachedHistoryId: Long = 0  // 缓存命中的 historyId，用于强制刷新时删除旧记录
    private var lastCachedPHash: Long = 0      // 缓存命中的 pHash，用于验证 historyId 有效性

    // 翻译会话 ID（每次服务启动生成新的）
    private val sessionId = java.util.UUID.randomUUID().toString()
    private var currentPHash = 0L
    private var cacheOverlayContainer: android.widget.FrameLayout? = null

    // 当前翻译的原始全屏截图（未裁剪），用于缓存 originalBitmap
    // 注意：MediaProjectionProvider 和 AccessibilityProvider 的 data.fullBitmap 均为全屏截图，
    // 不会被裁剪。如果以后有截图提供者在 emit 前裁剪 fullBitmap，此处需同步更新。
    private var pendingFullBitmap: Bitmap? = null

    // 调试详情面板（固定在屏幕底部）
    private var debugInfoPanelView: android.view.View? = null  // 整个 container（包含 imageView + infoPanel + toggleButton）
    private var debugInfoPanelContentView: android.view.View? = null  // 仅 infoPanel（可折叠部分）
    private var debugInfoPanelAdded = false
    private var debugInfoPanelCollapsed = false
    private var debugToggleButton: android.widget.TextView? = null
    private var debugToggleButtonAdded = false

    private sealed class GestureType {
        object Click : GestureType()
        object LongPress : GestureType()
        object Drag : GestureType()
    }

    // ---------- Lifecycle ----------

    override fun onCreate() {
        super.onCreate()
        prefs = CustomPreference.getInstance(this)
        statusOverlay = TranslationStatusOverlay(this)
        cacheManager = TranslationCacheManager(this)
        // 读取手势动作配置
        singleClickAction = Constants.BallAction.fromValue(prefs.getString("Ball_Gesture_Single_Click", "0").toIntOrNull() ?: 0)
        doubleClickAction = Constants.BallAction.fromValue(prefs.getString("Ball_Gesture_Double_Click", "2").toIntOrNull() ?: 2)
        longPressAction = Constants.BallAction.fromValue(prefs.getString("Ball_Gesture_Long_Press", "1").toIntOrNull() ?: 1)
        config = loadConfig()
        checkLanguageHints()
        initTranslator()

        // 读取 AI 上下文设置
        contextEnabled = prefs.getBoolean("game_context_enabled", false)
        contextMaxCount = try {
            prefs.getString("game_context_count", "5").toIntOrNull() ?: 5
        } catch (e: Exception) { 5 }

        // 监听源语言和引擎变化，实时检查语言/模型提示
        val watchedKeys = setOf("Source_Language", "Manga_Det_Model", "Manga_Rec_Model", "Manga_Keep_Text_Free")
        prefChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key in watchedKeys) {
                config = loadConfig()
                checkLanguageHints()
            }
        }
        prefs.getSharedPreferences().registerOnSharedPreferenceChangeListener(prefChangeListener)

        // 互斥：停止普通翻译服务
        try {
            stopService(Intent(this, com.moe.moetranslator.translate.FloatingBallService::class.java))
        } catch (e: Exception) {
            LogCollector.w(TAG, "Could not stop FloatingBallService", e)
        }

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        // 注册重新翻译广播接收器
        registerRetranslateReceiver()

        // 先初始化截图提供者，权限检查在 UI 初始化之前
        initScreenshotProvider()

        if (screenshotProvider is MediaProjectionProvider) {
            updateForegroundTypeForMediaProjection()
            if (!(screenshotProvider as MediaProjectionProvider).ensureInitialized()) {
                LogCollector.d(TAG, "MediaProjection needs permission, deferring UI init")
                ScreenCapturePermissionActivity.start(this, "manga")
                // 不创建 UI，等授权后再初始化
                return
            }
        }

        // 权限就绪，正常初始化 UI
        initializeViews()
        setupScreenshotCollector()

        // 初始化 OCR 引擎（识别器）
        when (config.ocrEngine) {
            OcrEngine.MLKit -> {}  // MLKit 无需初始化
            OcrEngine.MangaOcr -> lifecycleScope.launch { ensureMangaOcrInitialized() }
            OcrEngine.PPOcrV5 -> lifecycleScope.launch { initPPOcrV5("识别器") }
        }

        // 初始化检测引擎（检测器）
        when (config.detEngine) {
            DetEngine.CTD -> initCTD()
            DetEngine.MLKIT -> {}
            DetEngine.RT_DETR_V2 -> lifecycleScope.launch { initRTDetrV2() }
            DetEngine.PP_OCR_V5 -> lifecycleScope.launch { initPPOcrV5("检测器") }
        }

        LogCollector.d(TAG, "MangaFloatingService created")
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // 清除后台时停止服务
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra("PERMISSION_RESULT", false) == true) {
            LogCollector.d(TAG, "Permission granted, initializing Shooter")
            updateForegroundTypeForMediaProjection()
            val initialized = (screenshotProvider as? MediaProjectionProvider)?.ensureInitialized() ?: false
            LogCollector.d(TAG, "Shooter init result: $initialized")
            if (pendingAutoStart && initialized) {
                pendingAutoStart = false
                LogCollector.d(TAG, "Starting pending auto-translate")
                startAutoTranslate()
            } else if (isAutoTranslating && initialized) {
                LogCollector.d(TAG, "Resuming auto-translate")
                scheduleNextDetection(0L)
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        super.onDestroy()
        // 释放截图提供者
        screenshotProvider?.release()
        stopForegroundForScreenshot()
        // 注销 SharedPreferences listener
        prefChangeListener?.let {
            prefs.getSharedPreferences().unregisterOnSharedPreferenceChangeListener(it)
        }
        prefChangeListener = null
        removeAllViews()
        statusOverlay.release()
        translatorText?.release()
        autoTranslateHandler.removeCallbacksAndMessages(null)
        clearRegionCache()

        // 注销重新翻译广播接收器
        retranslateReceiver?.let {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(it)
        }

        // 取消所有协程，等待正在执行的 ONNX 推理完成后再释放资源
        // 防止 session.close() 和 session.run() 并发导致 native 内存损坏
        lifecycleScope.cancel()
        runBlocking(Dispatchers.IO) {
            coroutineContext[Job]?.children?.forEach { it.join() }
        }

        // 释放 OCR 引擎资源
        when (config.ocrEngine) {
            OcrEngine.MLKit -> {}
            OcrEngine.MangaOcr -> releaseMangaOcr()
            OcrEngine.PPOcrV5 -> releasePPOcrV5()
        }

        // 释放检测引擎资源
        when (config.detEngine) {
            DetEngine.CTD -> releaseCTD()
            DetEngine.MLKIT -> {}
            DetEngine.RT_DETR_V2 -> releaseRTDetrV2()
            DetEngine.PP_OCR_V5 -> releasePPOcrV5()
        }

        // 发送广播通知 UI 更新按钮状态
        val stopIntent = Intent(com.moe.moetranslator.translate.BroadcastAction.ACTION_MANGA_SERVICE_STOPPED)
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this).sendBroadcast(stopIntent)
        LogCollector.d(TAG, "MangaFloatingService destroyed")
    }

    // ---------- Initialization ----------

    // 初始化截图提供者
    private fun initScreenshotProvider() {
        val method = prefs.getString("Screenshot_Method", "0")?.toIntOrNull() ?: 0
        screenshotProvider = when (method) {
            0 -> MediaProjectionProvider(this)
            1 -> AccessibilityProvider()
            else -> MediaProjectionProvider(this)
        }
        LogCollector.d(TAG, "Screenshot provider initialized: ${screenshotProvider?.javaClass?.simpleName}")
    }

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
                Constants.TextApi.OPENAI.id -> {
                    val providerList = ConfigurationStorage.loadAllProviders(prefs)
                    val selectedIndex = prefs.getInt("OpenAI_Selected_Provider", 0)
                    if (providerList.isNotEmpty() && selectedIndex < providerList.size) {
                        val provider = providerList[selectedIndex]
                        translatorText = OpenAITranslation(
                            apiKey = provider.apiKey,
                            baseUrl = provider.baseUrl,
                            model = provider.modelName,
                            systemPrompt = provider.mangaSystemPrompt.ifEmpty { provider.defaultMangaSystemPrompt },
                            userPrompt = provider.mangaUserPrompt.ifEmpty { provider.defaultMangaUserPrompt },
                            continuationType = provider.continuationType,
                            prefillContent = if (provider.continuationType != OpenAIProviderConfig.CONTINUATION_NONE && provider.continuationType != OpenAIProviderConfig.CONTINUATION_JSON) "[1] " else ""
                        )
                    } else {
                        showToast("No OpenAI Provider Config Found.")
                    }
                }
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

        // 显示翻译 API 初始化成功的消息
        if (translatorText != null) {
            val apiName = translatorText!!::class.simpleName ?: "Translation API"
            showToast("$apiName 初始化成功")
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

    private fun initCTD() {
        lifecycleScope.launch {
            try {
                // 检查模型是否已下载
                if (!CTDModelManager.isModelAvailable(this@MangaFloatingService)) {
                    LogCollector.d(TAG, "initCTD: CTD 模型未下载")
                    showToast("CTD 检测器模型未下载，请先在模型管理中下载")
                    return@launch
                }
                LogCollector.d(TAG, "initCTD: 开始初始化 CTD")
                CTDDetector.initialize(this@MangaFloatingService)
                LogCollector.d(TAG, "initCTD: CTD 初始化完成")
                showToast("CTD 检测器初始化成功")
            } catch (e: Exception) {
                LogCollector.e(TAG, "initCTD: 初始化失败", e)
                showToast("CTD 检测器初始化失败: ${e.message ?: "未知错误"}")
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
                showToast("CTD 检测器模型未下载，请先在模型管理中下载")
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
                showToast("CTD 检测器初始化失败: ${e.message}")
            }
            throw e
        }
    }

    private fun initRTDetrV2() {
        lifecycleScope.launch {
            try {
                initRTDetrV2IfNeeded()
                showToast("RT-DETR-V2 检测器初始化成功")
            } catch (e: Exception) {
                LogCollector.e(TAG, "RT-DETR-V2 检测器初始化失败", e)
                showToast("RT-DETR-V2 检测器初始化失败: ${e.message ?: "未知错误"}")
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
                showToast("RT-DETR-V2 检测器初始化失败: ${e.message}")
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

    // ---------- PP-OCRv5 ----------

    /**
     * 初始化 PP-OCRv5
     * @param role 角色："检测器" 或 "识别器"
     */
    private fun initPPOcrV5(role: String = "检测器") {
        lifecycleScope.launch {
            try {
                initPPOcrV5IfNeeded()
                showToast("PP-OCRv5${role}初始化成功")
            } catch (e: Exception) {
                LogCollector.e(TAG, "PP-OCRv5${role}初始化失败", e)
                showToast("PP-OCRv5${role}初始化失败: ${e.message ?: "未知错误"}")
            }
        }
    }

    private suspend fun initPPOcrV5IfNeeded() {
        if (PPOcrV5Engine.isInitialized) return
        try {
            LogCollector.d(TAG, "initPPOcrV5IfNeeded: 开始初始化 PP-OCRv5")
            withContext(Dispatchers.IO) {
                PPOcrV5Engine.initialize(this@MangaFloatingService)
            }
            LogCollector.d(TAG, "initPPOcrV5IfNeeded: PP-OCRv5 初始化完成")
        } catch (e: Exception) {
            LogCollector.e(TAG, "initPPOcrV5IfNeeded: 初始化失败", e)
            throw e
        }
    }

    private fun releasePPOcrV5() {
        try {
            LogCollector.d(TAG, "releasePPOcrV5: 释放 PP-OCRv5 资源")
            PPOcrV5Engine.release()
        } catch (e: Exception) {
            LogCollector.e(TAG, "releasePPOcrV5: 释放失败", e)
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
                        // 如果已初始化且版本匹配，直接返回
                        if (MangaOcrRecognizer.isInitialized && currentLoadedMangaOcrVersion == versionStr) {
                            LogCollector.d(TAG, "ensureMangaOcrInitialized: manga-ocr 已初始化，版本匹配 ($versionStr)")
                            return
                        }
                        // 版本不匹配或未初始化，先释放再重新加载
                        if (MangaOcrRecognizer.isInitialized) {
                            LogCollector.d(TAG, "ensureMangaOcrInitialized: 版本变更 ($currentLoadedMangaOcrVersion → $versionStr)，重新加载")
                            MangaOcrRecognizer.release()
                        }
                        LogCollector.d(TAG, "ensureMangaOcrInitialized: 使用已下载的 manga-ocr 模型: $activeVersion")
                        withContext(Dispatchers.IO) {
                            MangaOcrBridge.initializeDownloaded(this@MangaFloatingService, activeVersion)
                        }
                        currentLoadedMangaOcrVersion = versionStr
                    } catch (e: Exception) {
                        LogCollector.e(TAG, "manga-ocr 识别器初始化失败", e)
                        withContext(Dispatchers.Main) {
                            statusOverlay.showError("manga-ocr 识别器初始化失败：${e.message ?: "未知错误"}")
                        }
                        return
                    }
                } else {
                    // 未下载，提示用户去下载
                    LogCollector.d(TAG, "ensureMangaOcrInitialized: manga-ocr 未下载，提示用户")
                    withContext(Dispatchers.Main) {
                        statusOverlay.showImmediate(getString(R.string.manga_ocr_download_required))
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
        withContext(Dispatchers.Main) {
            showToast("manga-ocr 识别器初始化成功")
        }
    }

    private fun loadConfig(): MangaModeConfig {
        val detEngine = DetEngine.fromValue(prefs.getInt("Manga_Det_Model", DetEngine.PP_OCR_V5.value))
        // CTD 和 RT-DETR-V2 检测器已输出气泡/区域级结果，不需要 BubbleDetector 再次聚类
        val autoDetectBubble = if (detEngine == DetEngine.CTD || detEngine == DetEngine.RT_DETR_V2) {
            false
        } else {
            prefs.getBoolean("Manga_Auto_Detect_Bubble", true)
        }
        return MangaModeConfig(
            enabled = true,
            textDirection = TextDirection.VERTICAL_RL,
            smartBackground = prefs.getBoolean("Manga_Smart_Background", true),
            autoDetectBubble = autoDetectBubble,
            fontSize = prefs.getFloat("Manga_Font_Size", 16f),
            autoFontSize = prefs.getBoolean("Manga_Auto_Font_Size", true),
            sourceLang = prefs.getString("Source_Language", "ja"),
            targetLang = prefs.getString("Target_Language", "zh"),
            textColor = prefs.getInt("Manga_Text_Color", android.graphics.Color.BLACK),
            bgColor = prefs.getInt("Manga_BG_Color", android.graphics.Color.argb(200, 255, 255, 255)),
            ocrEngine = OcrEngine.fromValue(prefs.getInt("Manga_Rec_Model", OcrEngine.PPOcrV5.value)),
            detEngine = detEngine,
            keepTextFree = prefs.getBoolean("Manga_Keep_Text_Free", false)
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
        longPressDelay = prefs.getLong("Custom_Long_Press_Delay", 300L)

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
        // Crop view (initially not added)
        cropView = CropView(this)
        // 必须用屏幕真实尺寸，MATCH_PARENT 会被系统栏截断
        val cropScreenSize = getScreenSize()
        cropViewParams = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            width = cropScreenSize.width
            height = cropScreenSize.height
            gravity = Gravity.START or Gravity.TOP
            x = 0
            y = 0
        }
    }

    /**
     * 获取屏幕真实物理像素尺寸（横屏/竖屏都正确，包含系统栏区域）
     * resources.displayMetrics 在 Service 上下文中可能返回竖屏尺寸
     */
    @Suppress("DEPRECATION")
    private fun getScreenSize(): android.util.Size {
        val defaultDisplay = windowManager.defaultDisplay
        val realSize = android.graphics.Point()
        defaultDisplay.getRealSize(realSize)
        return android.util.Size(realSize.x, realSize.y)
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
                            // 双击检测
                            val now = System.currentTimeMillis()
                            if (now - lastClickTime < DOUBLE_CLICK_DELAY) {
                                // 双击：取消单击计时器，执行双击动作
                                handler.removeCallbacks(singleClickRunnable)
                                lastClickTime = 0L
                                // 双击反馈动画（快速双脉冲）
                                floatingBallView.animate()
                                    .scaleX(0.85f).scaleY(0.85f)
                                    .setDuration(60)
                                    .withEndAction {
                                        floatingBallView.animate()
                                            .scaleX(1.1f).scaleY(1.1f)
                                            .setDuration(60)
                                            .withEndAction {
                                                floatingBallView.animate()
                                                    .scaleX(1f).scaleY(1f)
                                                    .setDuration(60)
                                                    .start()
                                            }
                                            .start()
                                    }
                                    .start()
                                executeAction(doubleClickAction)
                            } else {
                                // 可能是单击，延迟等待第二次点击
                                lastClickTime = now
                                handler.removeCallbacks(singleClickRunnable)
                                handler.postDelayed(singleClickRunnable, DOUBLE_CLICK_DELAY)
                            }
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
        executeAction(longPressAction)
    }

    private fun showMenu() {
        val cropLabel = if (cropRect != null) {
            getString(R.string.manga_mode_crop)
        } else {
            getString(R.string.manga_mode_fullscreen)
        }

        val isAdvancedMode = prefs.getBoolean("Manga_Advanced_Mode", false)

        isMenuShowing = true

        if (isAdvancedMode) {
            // 高级模式：检测模型 + OCR 引擎分开选择
            showMenuAdvanced(cropLabel)
        } else {
            // 普通模式：固定搭配，只有一个"模型"选项
            showMenuSimple(cropLabel)
        }
    }

    /**
     * 普通模式菜单：固定搭配
     * MLKit → det=MLKIT, ocr=MLKit
     * PP-OCRv5 → det=PP_OCR_V5, ocr=PPOcrV5
     * manga-ocr → det=RT_DETR_V2, ocr=MangaOcr
     */
    private fun showMenuSimple(cropLabel: String) {
        val modelLabel = when {
            config.detEngine == DetEngine.MLKIT && config.ocrEngine == OcrEngine.MLKit ->
                getString(R.string.manga_model_mlkit)
            config.detEngine == DetEngine.PP_OCR_V5 && config.ocrEngine == OcrEngine.PPOcrV5 ->
                getString(R.string.manga_model_ppocr)
            config.detEngine == DetEngine.RT_DETR_V2 && config.ocrEngine == OcrEngine.MangaOcr ->
                getString(R.string.manga_model_manga_ocr)
            else -> getString(R.string.manga_model_mlkit)  // 兜底
        }

        val langName = getCurrentSourceLangName()
        val (dialog, listView) = Dialogs.mangaMenuDialogSimple(
            this, isAutoTranslating, cropLabel, modelLabel, langName
        )

        listView.onItemClickListener = android.widget.AdapterView.OnItemClickListener { _, _, which, _ ->
            when (which) {
                0 -> {
                    // 切换全屏/框选
                    if (cropRect != null) {
                        cropRect = null
                        showToast(getString(R.string.manga_mode_fullscreen), true)
                        val adapter = listView.adapter as com.moe.moetranslator.translate.MenuDialogAdapter
                        adapter.updateLabel(0, "${getString(R.string.manga_crop_toggle)}：${getString(R.string.manga_mode_fullscreen)}")
                    } else {
                        dialog.dismiss()
                        handler.postDelayed({ startCropSelection() }, 200)
                    }
                }
                1 -> {
                    if (isAutoTranslating) {
                        showToast(getString(R.string.auto_translate_disabled_hint), true)
                    } else {
                        showFontSizeDialog()
                    }
                }
                2 -> {
                    if (isAutoTranslating) {
                        showToast(getString(R.string.auto_translate_disabled_hint), true)
                    } else {
                        // 切换模型（固定搭配）
                        toggleModelSimple(dialog, listView)
                    }
                }
                3 -> {
                    if (isAutoTranslating) {
                        showToast(getString(R.string.auto_translate_no_switch), true)
                    } else {
                        // 循环切换源语言，不关闭菜单
                        cycleSourceLang()
                        val adapter = listView.adapter as com.moe.moetranslator.translate.MenuDialogAdapter
                        adapter.updateLabel(3, "${getString(R.string.game_switch_language)}：${getCurrentSourceLangName()}")
                    }
                }
                4 -> {
                    // 自动翻译
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
                    dialog.dismiss()
                    stopSelf()
                }
                6 -> {
                    dialog.dismiss()
                    backToMainActivity()
                }
            }
        }

        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
        // 横屏时缩小菜单，竖屏保持原样
        if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            val screenSize = getScreenSize()
            val maxW = (screenSize.width * 0.4).toInt()
            val maxH = (screenSize.height * 0.7).toInt()
            dialog.window?.setLayout(maxW, maxH)
        } else {
            dialog.window?.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.setOnDismissListener { isMenuShowing = false }
    }

    /**
     * 高级模式菜单：检测模型 + OCR 引擎独立选择
     */
    private fun showMenuAdvanced(cropLabel: String) {
        val detModelLabel = when (config.detEngine) {
            DetEngine.CTD -> "CTD"
            DetEngine.MLKIT -> getString(R.string.manga_det_mlkit)
            DetEngine.RT_DETR_V2 -> "RT-DETR-V2"
            DetEngine.PP_OCR_V5 -> "PP-OCRv5"
        }
        val ocrEngineLabel = when (config.ocrEngine) {
            OcrEngine.MLKit -> getString(R.string.manga_ocr_mlkit)
            OcrEngine.MangaOcr -> getString(R.string.manga_ocr_manga_ocr)
            OcrEngine.PPOcrV5 -> "PP-OCRv5"
        }

        val langName = getCurrentSourceLangName()
        val (dialog, listView) = Dialogs.mangaMenuDialog(
            this, isAutoTranslating, cropLabel, detModelLabel, ocrEngineLabel, langName
        )

        listView.onItemClickListener = android.widget.AdapterView.OnItemClickListener { _, _, which, _ ->
            when (which) {
                0 -> {
                    if (cropRect != null) {
                        cropRect = null
                        showToast(getString(R.string.manga_mode_fullscreen), true)
                        val adapter = listView.adapter as com.moe.moetranslator.translate.MenuDialogAdapter
                        adapter.updateLabel(0, "${getString(R.string.manga_crop_toggle)}：${getString(R.string.manga_mode_fullscreen)}")
                    } else {
                        dialog.dismiss()
                        handler.postDelayed({ startCropSelection() }, 200)
                    }
                }
                1 -> {
                    if (isAutoTranslating) {
                        showToast(getString(R.string.auto_translate_disabled_hint), true)
                    } else {
                        showFontSizeDialog()
                    }
                }
                2 -> {
                    if (isAutoTranslating) {
                        showToast(getString(R.string.auto_translate_disabled_hint), true)
                    } else {
                        toggleDetModel(dialog, listView)
                    }
                }
                3 -> {
                    if (isAutoTranslating) {
                        showToast(getString(R.string.auto_translate_disabled_hint), true)
                    } else {
                        toggleOcrEngine(dialog, listView)
                    }
                }
                4 -> {
                    if (isAutoTranslating) {
                        showToast(getString(R.string.auto_translate_no_switch), true)
                    } else {
                        // 循环切换源语言，不关闭菜单
                        cycleSourceLang()
                        val adapter = listView.adapter as com.moe.moetranslator.translate.MenuDialogAdapter
                        adapter.updateLabel(4, "${getString(R.string.game_switch_language)}：${getCurrentSourceLangName()}")
                    }
                }
                5 -> {
                    toggleAutoTranslate()
                    val adapter = listView.adapter as com.moe.moetranslator.translate.MenuDialogAdapter
                    if (isAutoTranslating) {
                        adapter.updateLabel(5, getString(R.string.manga_menu_stop_auto))
                        adapter.updateIcon(5, R.drawable.stop_auto)
                    } else {
                        adapter.updateLabel(5, getString(R.string.manga_menu_auto_translate))
                        adapter.updateIcon(5, R.drawable.start_auto)
                    }
                }
                6 -> {
                    dialog.dismiss()
                    stopSelf()
                }
                7 -> {
                    dialog.dismiss()
                    backToMainActivity()
                }
            }
        }

        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
        // 横屏时缩小菜单，竖屏保持原样
        if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            val screenSize = getScreenSize()
            val maxW = (screenSize.width * 0.4).toInt()
            val maxH = (screenSize.height * 0.7).toInt()
            dialog.window?.setLayout(maxW, maxH)
        } else {
            dialog.window?.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.setOnDismissListener { isMenuShowing = false }
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
        // 循环切换：MLKit -> MangaOcr -> PPOcrV5 -> MLKit
        // RT-DETR-V2 不支持 PPOcrV5（PPOcrV5 有自己的 det，不需要外部检测器），跳过
        val newEngine = if (config.detEngine == DetEngine.RT_DETR_V2) {
            when (config.ocrEngine) {
                OcrEngine.MLKit -> OcrEngine.MangaOcr
                OcrEngine.MangaOcr -> OcrEngine.MLKit
                OcrEngine.PPOcrV5 -> OcrEngine.MLKit  // 不应出现，兜底
            }
        } else {
            when (config.ocrEngine) {
                OcrEngine.MLKit -> OcrEngine.MangaOcr
                OcrEngine.MangaOcr -> OcrEngine.PPOcrV5
                OcrEngine.PPOcrV5 -> OcrEngine.MLKit
            }
        }
        config = config.copy(ocrEngine = newEngine)
        prefs.setInt("Manga_Rec_Model", newEngine.value)

        val adapter = listView.adapter as com.moe.moetranslator.translate.MenuDialogAdapter
        val label = when (newEngine) {
            OcrEngine.MLKit -> getString(R.string.manga_ocr_mlkit)
            OcrEngine.MangaOcr -> getString(R.string.manga_ocr_manga_ocr)
            OcrEngine.PPOcrV5 -> "PP-OCRv5"
        }
        adapter.updateLabel(3, "${getString(R.string.manga_ocr_toggle)}：$label")

        // 释放旧引擎，初始化新引擎
        when (newEngine) {
            OcrEngine.MLKit -> {
                releaseMangaOcr()
                releasePPOcrV5()
                showToast(getString(R.string.manga_ocr_mlkit), true)
            }
            OcrEngine.MangaOcr -> {
                releasePPOcrV5()
                showToast("manga-ocr 识别器初始化中...", true)
                lifecycleScope.launch { ensureMangaOcrInitialized() }
            }
            OcrEngine.PPOcrV5 -> {
                releaseMangaOcr()
                showToast("PP-OCRv5 识别器初始化中...", true)
                lifecycleScope.launch { initPPOcrV5("识别器") }
            }
        }
    }

    /**
     * 语言/模型可用性提示（系统 Toast）
     * 触发点：onCreate、toggleOcrEngine、toggleModelSimple、toggleDetModel、SharedPreferences listener
     *
     * 场景：
     * 1. 漫画翻译运行中 + 非日文 → 提示
     * 2. 韩文 + PP引擎 + KO未下载 → 提示下载
     * 3. 俄文 + 非PP引擎 → 提示切换到PP
     * 4. 俄文 + PP引擎 + RU未下载 → 提示下载
     */
    private fun checkLanguageHints() {
        val isPP = config.ocrEngine == OcrEngine.PPOcrV5 || config.detEngine == DetEngine.PP_OCR_V5
        val isMangaOcr = config.ocrEngine == OcrEngine.MangaOcr
        val src = config.sourceLang

        // 优先级1：俄文 + 非PP引擎 → 提示切换到PP
        if (src == "ru" && !isPP) {
            showSystemToast(getString(R.string.ru_need_ppocrv5_engine))
            return
        }

        // 优先级2：PP引擎 + KO/RU未下载 → 提示下载
        if (isPP) {
            val (_, hint) = PPOcrV5Engine.resolveRecLang(this, src)
            if (hint != null) {
                showSystemToast(hint)
                return
            }
        }

        // 优先级3：manga-ocr 模型 + 非日文 → 提示
        if (isMangaOcr && src != "ja") {
            showSystemToast(getString(R.string.manga_ocr_non_ja_hint))
        }
    }

    private fun showSystemToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /**
     * 循环切换源语言：ja → en → zh → ko → ru → ja
     * 跳过 OCR 模型不可用的语言（PP-OCRv5 的 KO/RU 需要检查是否已下载）
     */
    private fun cycleSourceLang() {
        val langCycle = arrayOf("ja", "en", "zh", "ko", "ru")
        val current = prefs.getString("Source_Language", "ja")
        val currentIdx = langCycle.indexOf(current).coerceAtLeast(0)

        for (i in 1..langCycle.size) {
            val nextIdx = (currentIdx + i) % langCycle.size
            val nextLang = langCycle[nextIdx]
            if (isOcrLangAvailable(nextLang)) {
                prefs.setString("Source_Language", nextLang)
                config = loadConfig()  // 重新加载配置
                val langName = com.moe.moetranslator.translate.CustomLocale.getInstance(nextLang).getDisplayName()
                showToast(getString(R.string.language_switched_to, langName), true)
                checkLanguageHints()
                return
            }
        }
        showToast(getString(R.string.no_available_ocr_model), true)
    }

    /**
     * 检查指定语言的 OCR 模型是否可用
     */
    private fun isOcrLangAvailable(lang: String): Boolean {
        val isPP = config.ocrEngine == OcrEngine.PPOcrV5 || config.detEngine == DetEngine.PP_OCR_V5
        if (!isPP) return true  // 非 PP-OCRv5 不需要检查
        return when (lang) {
            "zh", "ja" -> true  // 内置模型
            "en" -> PPOcrV5Engine.isRecModelAvailable(this, PPOcrV5Engine.RecLang.EN)
            "ko" -> PPOcrV5Engine.isRecModelAvailable(this, PPOcrV5Engine.RecLang.KO)
            "ru" -> PPOcrV5Engine.isRecModelAvailable(this, PPOcrV5Engine.RecLang.RU)
            else -> true
        }
    }

    /**
     * 获取当前源语言的显示名称
     */
    private fun getCurrentSourceLangName(): String {
        val lang = prefs.getString("Source_Language", "ja")
        return com.moe.moetranslator.translate.CustomLocale.getInstance(lang).getDisplayName()
    }

    /**
     * 普通模式：切换固定搭配模型
     * MLKit → PP-OCRv5 → manga-ocr → MLKit
     */
    private fun toggleModelSimple(dialog: AlertDialog, listView: android.widget.ListView) {
        // 判断当前是哪个组合
        val currentCombo = when {
            config.detEngine == DetEngine.MLKIT && config.ocrEngine == OcrEngine.MLKit -> "mlkit"
            config.detEngine == DetEngine.PP_OCR_V5 && config.ocrEngine == OcrEngine.PPOcrV5 -> "ppocr"
            config.detEngine == DetEngine.RT_DETR_V2 && config.ocrEngine == OcrEngine.MangaOcr -> "manga"
            else -> "mlkit"
        }

        // 循环切换，最多尝试3次找到可用模型
        val combos = listOf("mlkit", "ppocr", "manga")
        val startIndex = combos.indexOf(currentCombo).coerceAtLeast(0)
        var newCombo: String
        var attempts = 0

        while (true) {
            val nextIndex = (startIndex + 1 + attempts) % combos.size
            newCombo = combos[nextIndex]
            attempts++

            // MLKit 和 PP-OCRv5 内置，始终可用
            if (newCombo == "mlkit" || newCombo == "ppocr") break

            // manga-ocr 需要检查模型是否已下载
            if (newCombo == "manga") {
                val rtdetrReady = RTDetrModelManager.isModelAvailable(this)
                val mangaOcrReady = MangaOcrDownloadManager.getActiveVersion(this)?.let {
                    MangaOcrDownloadManager.isVersionDownloaded(this, it)
                } == true

                if (rtdetrReady && mangaOcrReady) break

                // manga-ocr 不可用，跳过继续循环
                if (attempts >= combos.size) {
                    // 所有模型都不可用（理论上不会发生，MLKit 始终可用）
                    newCombo = "mlkit"
                    break
                }
            }
        }

        // 释放所有旧引擎
        releaseMangaOcr()
        releasePPOcrV5()
        releaseCTD()
        releaseRTDetrV2()

        when (newCombo) {
            "mlkit" -> {
                config = config.copy(detEngine = DetEngine.MLKIT, ocrEngine = OcrEngine.MLKit)
                prefs.setInt("Manga_Det_Model", DetEngine.MLKIT.value)
                prefs.setInt("Manga_Rec_Model", OcrEngine.MLKit.value)
                showToast(getString(R.string.manga_model_mlkit), true)
            }
            "ppocr" -> {
                config = config.copy(detEngine = DetEngine.PP_OCR_V5, ocrEngine = OcrEngine.PPOcrV5)
                prefs.setInt("Manga_Det_Model", DetEngine.PP_OCR_V5.value)
                prefs.setInt("Manga_Rec_Model", OcrEngine.PPOcrV5.value)
                showToast(getString(R.string.manga_model_ppocr), true)
                lifecycleScope.launch { initPPOcrV5("检测器+识别器") }
            }
            "manga" -> {
                config = config.copy(detEngine = DetEngine.RT_DETR_V2, ocrEngine = OcrEngine.MangaOcr)
                prefs.setInt("Manga_Det_Model", DetEngine.RT_DETR_V2.value)
                prefs.setInt("Manga_Rec_Model", OcrEngine.MangaOcr.value)
                showToast(getString(R.string.manga_model_manga_ocr), true)
                lifecycleScope.launch {
                    initRTDetrV2()
                    ensureMangaOcrInitialized()
                }
            }
        }

        // 更新菜单标签
        val adapter = listView.adapter as com.moe.moetranslator.translate.MenuDialogAdapter
        val label = when (newCombo) {
            "mlkit" -> getString(R.string.manga_model_mlkit)
            "ppocr" -> getString(R.string.manga_model_ppocr)
            "manga" -> getString(R.string.manga_model_manga_ocr)
            else -> ""
        }
        adapter.updateLabel(2, "${getString(R.string.manga_model_toggle)}：$label")
    }

    private fun toggleDetModel(dialog: AlertDialog, listView: android.widget.ListView) {
        // 循环切换：MLKIT -> CTD -> RT_DETR_V2 -> PP_OCR_V5 -> MLKIT
        val newEngine = when (config.detEngine) {
            DetEngine.MLKIT -> DetEngine.CTD
            DetEngine.CTD -> DetEngine.RT_DETR_V2
            DetEngine.RT_DETR_V2 -> DetEngine.PP_OCR_V5
            DetEngine.PP_OCR_V5 -> DetEngine.MLKIT
        }
        config = config.copy(detEngine = newEngine)
        prefs.setInt("Manga_Det_Model", newEngine.value)

        val adapter = listView.adapter as com.moe.moetranslator.translate.MenuDialogAdapter
        val label = when (newEngine) {
            DetEngine.CTD -> "CTD"
            DetEngine.MLKIT -> getString(R.string.manga_det_mlkit)
            DetEngine.RT_DETR_V2 -> "RT-DETR-V2"
            DetEngine.PP_OCR_V5 -> "PP-OCRv5"
        }
        adapter.updateLabel(2, "${getString(R.string.manga_det_toggle)}：$label")

        // 释放旧引擎，初始化新引擎
        when (newEngine) {
            DetEngine.CTD -> {
                showToast("CTD 检测器初始化中...", true)
                initCTD()
            }
            DetEngine.MLKIT -> {
                releaseCTD()
                releaseRTDetrV2()
                releasePPOcrV5()
                showToast(getString(R.string.manga_det_mlkit), true)
            }
            DetEngine.RT_DETR_V2 -> {
                releaseCTD()
                releasePPOcrV5()
                // RT-DETR-V2 不支持 PPOcrV5，自动切换为 MLKit
                if (config.ocrEngine == OcrEngine.PPOcrV5) {
                    config = config.copy(ocrEngine = OcrEngine.MLKit)
                    prefs.setInt("Manga_Rec_Model", OcrEngine.MLKit.value)
                    LogCollector.d(TAG, "RT-DETR-V2 不支持 PPOcrV5，自动切换为 MLKit")
                }
                showToast("RT-DETR-V2 检测器初始化中...", true)
                lifecycleScope.launch { initRTDetrV2() }
            }
            DetEngine.PP_OCR_V5 -> {
                releaseCTD()
                releaseRTDetrV2()
                showToast("PP-OCRv5 检测器初始化中...", true)
                lifecycleScope.launch { initPPOcrV5("检测器") }
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
                    showToast(getString(R.string.manga_font_size_auto), true)
                } else {
                    val newSize = sizes[which].toFloat()
                    config = config.copy(fontSize = newSize, autoFontSize = false)
                    prefs.setFloat("Manga_Font_Size", newSize)
                    prefs.setBoolean("Manga_Auto_Font_Size", false)
                    showToast("${sizes[which]}sp", true)
                }
                d.dismiss()
            }
            .create()

        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
    }

    // ---------- Auto-translate — 智能状态机 ----------

    private fun toggleAutoTranslate() {
        if (isAutoTranslating) {
            stopAutoTranslate()
        } else {
            startAutoTranslate()
        }
    }

    private fun startAutoTranslate() {
        // 只在 AccessibilityService 模式下检查无障碍服务
        val isMediaProjection = screenshotProvider is MediaProjectionProvider
        if (!isMediaProjection && AccessibilityServiceManager.getService() == null) {
            showToast(getString(R.string.accessibility_recycle), true)
            return
        }
        // MediaProjection 模式：检查权限，未授权则请求并等待回调
        if (isMediaProjection && !(screenshotProvider as MediaProjectionProvider).ensureInitialized()) {
            LogCollector.d(TAG, "startAutoTranslate: MediaProjection not ready, requesting permission")
            pendingAutoStart = true
            ScreenCapturePermissionActivity.start(this, "manga")
            return
        }
        pendingAutoStart = false
        isAutoTranslating = true
        detectState = DetectState.IDLE
        stableCount = 0
        consecutiveEmptyCount = 0
        previousScreenshotHash = 0L
        lastTranslatedHash = 0L
        translatedRegions.clear()
        scheduleNextDetection(0L)
        LogCollector.d(TAG, "Auto-translate started")
        showToast(getString(R.string.manga_auto_translate_start))
    }

    private fun stopAutoTranslate() {
        isAutoTranslating = false
        isManualTranslating = false
        detectState = DetectState.IDLE
        stableCount = 0
        consecutiveEmptyCount = 0
        previousScreenshotHash = 0L
        autoTranslateHandler.removeCallbacksAndMessages(null)
        translatedRegions.clear()
        dismissProgressOverlay()
        LogCollector.d(TAG, "Auto-translate stopped")
        showToast(getString(R.string.manga_auto_translate_stop))
    }

    private fun scheduleNextDetection(delayMs: Long) {
        autoTranslateHandler.removeCallbacksAndMessages(null)
        autoTranslateHandler.postDelayed({ runAutoDetect() }, delayMs)
    }

    /**
     * 自动检测入口 — 通过 triggerTranslation 请求截图，
     * 实际 pHash 门控在 screenshotCollector 中执行。
     */
    private fun runAutoDetect() {
        if (!isAutoTranslating) return
        if (isResultShowing || isMenuShowing) {
            scheduleNextDetection(1000L)
            return
        }
        if (isProcessing) {
            scheduleNextDetection(DETECT_INTERVAL_MS)
            return
        }
        // 请求截图 — 截图到达后由 collector 处理 pHash 状态机
        triggerTranslation()
    }

    /**
     * pHash 状态机：在 screenshotCollector 中调用。
     * 返回 true = 应继续处理（OCR+翻译），false = 跳过本次截图。
     *
     * 状态流转:
     *   IDLE   → pHash 相似 → 已翻译? 跳过 : 翻译
     *         → pHash 不同 → 进入 MOTION
     *   MOTION → pHash 相似 → stableCount++ → 达标? 进入 STABLE : 继续等
     *         → pHash 不同 → 重置计数，继续等
     *         → 超时 → 强制 STABLE
     *   STABLE → 判断是否新页面 → 翻译 or 跳过 → 回到 IDLE
     */
    private fun processAutoDetectPHash(currentHash: Long): Boolean {
        LogCollector.d(TAG, "AutoDetect: state=$detectState, prev=$previousScreenshotHash, lastTranslated=$lastTranslatedHash")

        when (detectState) {
            DetectState.IDLE -> {
                // 首次截图，直接翻译
                if (lastTranslatedHash == 0L && previousScreenshotHash == 0L) {
                    previousScreenshotHash = currentHash
                    LogCollector.d(TAG, "AutoDetect[IDLE]: first screenshot → translate")
                    return true
                }

                // IDLE: 比较当前截图和上次翻译页的哈希
                val simToTranslated = PerceptualHash.similarity(lastTranslatedHash, currentHash)
                if (simToTranslated >= PHASH_STABLE_THRESHOLD) {
                    // 画面没变或回到了已翻译的页面，跳过
                    LogCollector.d(TAG, "AutoDetect[IDLE]: simToTranslated=$simToTranslated → skip")
                    previousScreenshotHash = currentHash
                    // P4: 缩短 IDLE 重检间隔（MediaProjection 模式下也有帧变化事件加速）
                    scheduleNextDetection(DETECT_INTERVAL_MS)
                    return false
                } else {
                    // 画面和已翻译页不同 — 但可能是 overlay dismiss 导致的视觉差异
                    // dismiss overlay 后再检查一次：如果和已翻译页一样，说明只是 overlay 变化，跳过
                    // 这也覆盖了"翻页后翻回来"的场景：回来的页面和已翻译页一样 → skip
                    detectState = DetectState.MOTION
                    stableCount = 0
                    previousScreenshotHash = currentHash
                    motionStartTime = System.currentTimeMillis()
                    LogCollector.d(TAG, "AutoDetect[IDLE→MOTION]: simToTranslated=$simToTranslated, motion detected")
                    showProgressOverlay("检测中...")
                    scheduleNextDetection(DETECT_INTERVAL_MS)
                    return false
                }
            }

            DetectState.MOTION -> {
                // MOTION: 比较连续两次截图，判断页面是否稳定
                val simConsecutive = PerceptualHash.similarity(previousScreenshotHash, currentHash)
                previousScreenshotHash = currentHash

                if (simConsecutive >= PHASH_STABLE_THRESHOLD) {
                    // 连续两次截图一致 → 页面已稳定
                    stableCount++
                    if (stableCount >= STABLE_CONFIRM_COUNT) {
                        detectState = DetectState.STABLE
                        LogCollector.d(TAG, "AutoDetect[MOTION→STABLE]: consecutive sim=$simConsecutive, stabilized after ${stableCount} checks")
                        return onMotionStabilized(currentHash)
                    } else {
                        LogCollector.d(TAG, "AutoDetect[MOTION]: stabilizing... consecutive sim=$simConsecutive, count=$stableCount")
                        scheduleNextDetection(DETECT_INTERVAL_MS)
                        return false
                    }
                } else {
                    // 还在动，重置计数继续等
                    stableCount = 0
                    scheduleNextDetection(DETECT_INTERVAL_MS)
                    return false
                }
            }

            DetectState.STABLE -> {
                // 不应该停留在此状态，回到 IDLE
                detectState = DetectState.IDLE
                scheduleNextDetection(DETECT_INTERVAL_MS)
                return false
            }
        }
    }

    /**
     * 画面稳定后 — 判断是否需要翻译。
     * @return true = 应翻译，false = 跳过
     */
    private fun onMotionStabilized(stableHash: Long): Boolean {
        val similarityToTranslated = PerceptualHash.similarity(lastTranslatedHash, stableHash)

        if (similarityToTranslated >= PHASH_STABLE_THRESHOLD) {
            // 与已翻译页面相同，跳过
            LogCollector.d(TAG, "onMotionStabilized: same as translated page → skip")
            detectState = DetectState.IDLE
            scheduleNextDetection(1000L)
            return false
        }

        if (similarityToTranslated < PHASH_NEW_PAGE_THRESHOLD) {
            // 差异巨大 → 翻页，但保留文本缓存（文字匹配决定是否复用，不靠坐标）
            LogCollector.d(TAG, "onMotionStabilized: new page (sim=$similarityToTranslated), keeping text cache")
        } else {
            // 小幅变化 → 滚动，保留缓存做增量翻译
            LogCollector.d(TAG, "onMotionStabilized: incremental (sim=$similarityToTranslated), keeping cache")
        }

        // 需要翻译
        return true
    }

    // ---------- Crop selection ----------

    private fun startCropSelection() {
        if (isCropActive) {
            showToast(getString(R.string.manga_crop_active), true)
            return
        }

        // 暂停自动翻译
        if (isAutoTranslating) {
            wasAutoTranslatingBeforeCrop = true
            stopAutoTranslate()
            LogCollector.d(TAG, "框选模式：暂停自动翻译")
        }

        val screenSize = getScreenSize()

        if (cropRect != null && resources.configuration.orientation == 1) {
            cropView.setRect(cropRect!!)
        } else {
            // 等布局完成后用 view 自身尺寸计算居中框选区域
            cropView.setRectCentered(0.8f, 0.6f)
        }

        cropView.onConfirmCrop = { confirmCrop() }
        // 每次显示时更新 overlay 尺寸，防止旋转后过期
        cropViewParams?.apply {
            width = screenSize.width
            height = screenSize.height
            x = 0
            y = 0
        }
        windowManager.addView(cropView, cropViewParams)
        isCropActive = true

        bringFloatingBallToFront()
    }

    private fun confirmCrop() {
        cropRect = RectF(cropView.mRect)
        isCropActive = false

        try {
            windowManager.removeView(cropView)
        } catch (e: Exception) {
            LogCollector.e(TAG, "Error removing crop view", e)
        }

        bringFloatingBallToFront()

        showToast(getString(R.string.manga_crop_confirm), true)

        // 恢复自动翻译
        if (wasAutoTranslatingBeforeCrop) {
            wasAutoTranslatingBeforeCrop = false
            startAutoTranslate()
            LogCollector.d(TAG, "框选完成：恢复自动翻译")
        }
    }

    // ---------- Click handler ----------

    private fun executeAction(action: Constants.BallAction) {
        when (action) {
            Constants.BallAction.TRANSLATE -> doTranslate()
            Constants.BallAction.MENU -> showMenu()
            Constants.BallAction.AUTO_TRANSLATE -> toggleAutoTranslate()
            Constants.BallAction.CLOSE_FLOATING -> stop(this)
        }
    }

    private fun doTranslate() {
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
        // 自动翻译中点击 → 手动翻译，暂停自动检测
        if (isAutoTranslating) {
            isManualTranslating = true
        }
        triggerTranslation()
    }

    private fun triggerTranslation() {
        LogCollector.d(TAG, "========== triggerTranslation START ==========")
        if (isProcessing) {
            LogCollector.d(TAG, "triggerTranslation: already processing, skipping")
            showToast(getString(R.string.is_translating), true)
            return
        }
        if (isCropActive) {
            LogCollector.d(TAG, "triggerTranslation: crop is active, skipping")
            return
        }

        // 只在 AccessibilityService 模式下检查无障碍服务
        val isMediaProjection = screenshotProvider is MediaProjectionProvider
        if (!isMediaProjection) {
            val service = AccessibilityServiceManager.getService()
            LogCollector.d(TAG, "triggerTranslation: accessibilityService=$service")
            if (service == null) {
                showToast(getString(R.string.accessibility_recycle), true)
                return
            }
        } else {
            LogCollector.d(TAG, "triggerTranslation: MediaProjection mode, skipping accessibility check")
        }

        // 只重新加载视觉配置（文字方向、字体等），不重新初始化翻译API
        config = loadConfig()

        isProcessing = true

        // 先关闭所有overlay再截图，避免截到进度条/翻译结果
        dismissResultOverlay()
        dismissProgressOverlay()

        // 延迟截图，确保 overlay 从屏幕上完全消失（需要等下一帧渲染）
        lifecycleScope.launch {
            kotlinx.coroutines.delay(150)
            LogCollector.d(TAG, "triggerTranslation: translatorText=${translatorText?.javaClass?.simpleName}")
            LogCollector.d(TAG, "triggerTranslation: cropRect=$cropRect")
            if (cropRect != null) {
                LogCollector.d(TAG, "triggerTranslation: taking cropped screenshot")
            } else {
                LogCollector.d(TAG, "triggerTranslation: taking full screenshot")
            }
            val screenshotStarted = takeScreenshotWithProvider(cropRect, cropView.absolutePointOffset)
            if (!screenshotStarted) {
                LogCollector.w(TAG, "Screenshot not started (permission needed?), resetting isProcessing")
                isProcessing = false
            }
            LogCollector.d(TAG, "========== triggerTranslation END ==========")
        }
    }

    // 使用 ScreenshotProvider 截图
    // @return true 截图已启动，false 截图未启动（需要权限等）
    private fun takeScreenshotWithProvider(cropRect: RectF?, offset: Point): Boolean {
        val provider = screenshotProvider ?: return false
        LogCollector.d(TAG, "takeScreenshotWithProvider: provider=${provider.javaClass.simpleName}, cropRect=$cropRect")

        if (provider is MediaProjectionProvider) {
            // MediaProjection 模式：需要已初始化（权限在服务启动时请求）
            if (!provider.ensureInitialized()) {
                LogCollector.w(TAG, "MediaProjection not initialized, permission not granted yet")
                showToast("录屏权限未授予，无法截图", true)
                return false
            }
            // 异步截图：先获取全屏，再由服务层裁剪（保留全屏 bitmap 供缓存使用）
            lifecycleScope.launch {
                LogCollector.d(TAG, "Taking MediaProjection screenshot")
                try {
                    val fullBitmap = provider.takeScreenshot(null, offset)
                    if (fullBitmap != null) {
                        LogCollector.d(TAG, "Full screenshot: ${fullBitmap.width}x${fullBitmap.height}")
                        val croppedBitmap = if (cropRect != null) {
                            val cropped = ScreenshotManager.cropBitmap(fullBitmap, cropRect, offset)
                            LogCollector.d(TAG, "Cropped screenshot: ${cropped.width}x${cropped.height}")
                            cropped
                        } else null
                        ScreenshotManager.emitScreenshot(ScreenshotData(fullBitmap, croppedBitmap))
                    } else {
                        LogCollector.w(TAG, "Screenshot returned null")
                        isProcessing = false
                        // 截图失败，恢复自动检测循环
                        if (isAutoTranslating) {
                            scheduleNextDetection(DETECT_INTERVAL_MS)
                        }
                    }
                } catch (e: Exception) {
                    LogCollector.e(TAG, "Screenshot exception", e)
                    isProcessing = false
                    if (isAutoTranslating) {
                        scheduleNextDetection(DETECT_INTERVAL_MS)
                    }
                }
            }
            return true
        } else {
            // AccessibilityService 模式：直接调用
            LogCollector.d(TAG, "Taking AccessibilityService screenshot")
            lifecycleScope.launch {
                provider.takeScreenshot(cropRect, offset)
            }
            return true
        }
    }

    // 启动前台服务（MediaProjection 模式需要）
    private fun startForegroundForScreenshot() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                SCREEN_CAPTURE_CHANNEL_ID,
                getString(R.string.foreground_service_notification_title),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.foreground_service_notification_text)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, SCREEN_CAPTURE_CHANNEL_ID)
            .setContentTitle(getString(R.string.foreground_service_notification_title))
            .setContentText(getString(R.string.foreground_service_notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                FOREGROUND_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(FOREGROUND_NOTIFICATION_ID, notification)
        }
    }

    // 停止前台服务
    private fun stopForegroundForScreenshot() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    /**
     * 更新前台服务类型为 MEDIA_PROJECTION
     * 服务在 onCreate 中以默认类型启动，使用 MediaProjection 前需要更新类型
     */
    private fun updateForegroundTypeForMediaProjection() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val notification = buildNotification()
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            LogCollector.d(TAG, "Updated foreground service type to MEDIA_PROJECTION")
        }
    }

    // ---------- Screenshot collection ----------

    private fun setupScreenshotCollector() {
        LogCollector.d(TAG, "setupScreenshotCollector: starting collector coroutine")
        ScreenshotManager.setEventMode(AccessibilityEventHandler.Mode.MANGA)
        lifecycleScope.launch {
            LogCollector.d(TAG, "Screenshot collector: coroutine started, waiting for screenshots...")
            ScreenshotManager.screenshotFlow.collect { data ->
                // ocrBitmap: 用于 OCR 和翻译流程的 bitmap（裁剪后或全屏）
                val ocrBitmap = data.croppedBitmap ?: data.fullBitmap
                LogCollector.d(TAG, "Screenshot collector: RECEIVED! full=${data.fullBitmap.width}x${data.fullBitmap.height}, ocr=${ocrBitmap.width}x${ocrBitmap.height}")
                try {
                    // 自动翻译模式：pHash 门控（手动翻译时跳过）
                    if (isAutoTranslating && !isManualTranslating) {
                        // 用全屏截图计算稳定的 pHash（不受框选偏移影响）
                        val pHash = PerceptualHash.compute(data.fullBitmap, centerCrop = true)
                        // 保存全屏 bitmap 引用用于缓存（不要在翻译前释放）
                        pendingFullBitmap = data.fullBitmap
                        val shouldTranslate = processAutoDetectPHash(pHash)
                        if (!shouldTranslate) {
                            ocrBitmap.recycle()
                            pendingFullBitmap = null
                            if (data.croppedBitmap != null) data.fullBitmap.recycle()
                            isProcessing = false
                            // 不关闭进度条，保持"自动检测中"显示
                            return@collect
                        }
                        // P5: restricted check 延迟到真正翻译时才执行（MOTION 阶段跳过）
                        if (isRestrictedScreenshot(ocrBitmap)) {
                            LogCollector.d(TAG, "Screenshot collector: 检测到受限区域截图，跳过翻译")
                            ocrBitmap.recycle()
                            pendingFullBitmap = null
                            if (data.croppedBitmap != null) data.fullBitmap.recycle()
                            isProcessing = false
                            statusOverlay.showError("该区域无法截图，可能是受限内容（安全应用/DRM保护）")
                            scheduleNextDetection(DETECT_INTERVAL_MS)
                            return@collect
                        }
                        // pHash 通过门控，切换为"翻译中"，执行 OCR + 翻译
                        showProgressOverlay(getString(R.string.manga_translating))
                        processMangaScreenshot(ocrBitmap, pHash)
                    } else {
                        // 手动模式：先检测受限区域，再翻译
                        if (isRestrictedScreenshot(ocrBitmap)) {
                            LogCollector.d(TAG, "Screenshot collector: 检测到受限区域截图，跳过翻译")
                            ocrBitmap.recycle()
                            if (data.croppedBitmap != null) data.fullBitmap.recycle()
                            isProcessing = false
                            isManualTranslating = false
                            statusOverlay.showError("该区域无法截图，可能是受限内容（安全应用/DRM保护）")
                            return@collect
                        }
                        // 手动模式：用全屏截图计算稳定的缓存 pHash
                        val cachePHash = PerceptualHash.compute(data.fullBitmap, centerCrop = true)
                        // 保存全屏 bitmap 引用用于缓存（不要在翻译前释放）
                        pendingFullBitmap = data.fullBitmap
                        showProgressOverlay("检测中...")
                        try {
                            processMangaScreenshot(ocrBitmap, cachePHash)
                        } finally {
                            isManualTranslating = false  // 无论成功失败，恢复自动检测
                        }
                    }
                    LogCollector.d(TAG, "Screenshot collector: processMangaScreenshot completed normally")
                } catch (e: java.io.FileNotFoundException) {
                    LogCollector.e(TAG, "Screenshot collector: 模型文件缺失", e)
                    isProcessing = false
                    dismissProgressOverlay()
                    statusOverlay.showError("识别模型文件缺失：${e.message}")
                } catch (e: Exception) {
                    LogCollector.e(TAG, "Screenshot collector: CAUGHT EXCEPTION", e)
                    isProcessing = false
                    // 先 dismiss 进度条，再显示错误（错误会保持显示直到用户点击复制）
                    dismissProgressOverlay()
                    statusOverlay.showError("翻译失败：${e.message ?: "Unknown error"}")
                }
            }
            LogCollector.d(TAG, "Screenshot collector: collect() returned (THIS SHOULD NEVER HAPPEN)")
        }

        // 无障碍事件辅助：滚动/内容变化时加速检测（事件经 EventHandler 去抖后到达）
        lifecycleScope.launch {
            ScreenshotManager.eventTriggerFlow.collect { eventType ->
                if (isAutoTranslating && detectState == DetectState.IDLE && !isProcessing) {
                    LogCollector.d(TAG, "事件触发 [$eventType]: 立即检测")
                    autoTranslateHandler.removeCallbacksAndMessages(null)
                    autoTranslateHandler.postDelayed({ runAutoDetect() }, 500L)
                }
            }
        }
    }

    // ---------- Manga translation pipeline ----------

    /**
     * 按漫画阅读顺序排序裁剪结果：从上到下，从右到左。
     */
    private fun sortByMangaReadingOrder(bubbles: List<CroppedBubble>): List<CroppedBubble> {
        return bubbles.sortedWith(
            compareBy<CroppedBubble> { it.rect.top }
                .thenByDescending { it.rect.left }
        )
    }

    // ========== 空间聚类（分批切分用） ==========

    private class UnionFind(n: Int) {
        private val parent = IntArray(n) { it }
        private val rank = IntArray(n)
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            var i = x
            while (i != r) { val p = parent[i]; parent[i] = r; i = p }
            return r
        }
        fun union(a: Int, b: Int) {
            val ra = find(a); val rb = find(b)
            if (ra == rb) return
            when {
                rank[ra] < rank[rb] -> parent[ra] = rb
                rank[ra] > rank[rb] -> parent[rb] = ra
                else -> { parent[rb] = ra; rank[ra]++ }
            }
        }
    }

    /**
     * 按 AABB 空间距离聚类，加权距离 dy×5 + dx。
     * 垂直接近的行更容易归为同一组（漫画同行文字水平可远但垂直接近）。
     */
    private fun <T> groupByProximity(sorted: List<T>, getRect: (T) -> Rect, tag: String): List<List<T>> {
        if (sorted.size <= 1) return listOf(sorted)
        val rects = sorted.map { getRect(it) }
        val uf = UnionFind(sorted.size)
        for (i in rects.indices) {
            for (j in i + 1 until rects.size) {
                val ri = rects[i]; val rj = rects[j]
                val dx = maxOf(0, maxOf(rj.left - ri.right, ri.left - rj.right))
                val dy = maxOf(0, maxOf(rj.top - ri.bottom, ri.top - rj.bottom))
                if (dy * 5f + dx < CLUSTER_THRESHOLD) uf.union(i, j)
            }
        }
        val groups = mutableMapOf<Int, MutableList<T>>()
        for (i in sorted.indices) groups.getOrPut(uf.find(i)) { mutableListOf() }.add(sorted[i])
        val result = groups.values.toList()
        LogCollector.d(TAG, "groupByProximity($tag): ${sorted.size} 行 → ${result.size} 组 ${result.joinToString { "${it.size}行" }}")
        return result
    }

    /** 按组边界切分，不拆开任何组。 */
    private fun <T> splitAtGroupBoundaries(groups: List<List<T>>, fraction: Int = 2, divisor: Int = 5): Pair<List<T>, List<T>> {
        val total = groups.sumOf { it.size }
        val target = total * fraction / divisor
        var cum = 0; var splitIdx = 0
        for ((i, g) in groups.withIndex()) { cum += g.size; if (cum >= target) { splitIdx = i + 1; break } }
        if (splitIdx == 0 && groups.isNotEmpty()) splitIdx = 1
        val first = groups.take(splitIdx).flatten()
        val second = groups.drop(splitIdx).flatten()
        LogCollector.d(TAG, "splitAtGroupBoundaries: target=$target, 第一批=${first.size} (${splitIdx}组), 第二批=${second.size} (${groups.size - splitIdx}组)")
        return first to second
    }

    /**
     * 将 TextBlockInfo 列表转换为 BubbleRegion 列表。
     * 复用 processMangaScreenshot Step 2 中的转换逻辑。
     */
    private fun textBlocksToBubbleRegions(textBlocks: List<TextBlockInfo>): List<BubbleRegion> {
        return textBlocks.filter { block ->
            if (block.boundingBox == null) return@filter false
            // 过滤单字符纯标点噪声
            val cleaned = block.text.replace("\n", "").trim()
            if (cleaned.length == 1 && cleaned[0].category in SINGLE_CHAR_NOISE_CATEGORIES) {
                LogCollector.d(TAG, "过滤单字符噪声: \"${cleaned}\" [${block.boundingBox}]")
                return@filter false
            }
            true
        }.map { block ->
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

    // 单字符噪声类别（标点、符号）
    private val SINGLE_CHAR_NOISE_CATEGORIES = setOf(
        CharCategory.OTHER_PUNCTUATION,
        CharCategory.DASH_PUNCTUATION,
        CharCategory.START_PUNCTUATION,
        CharCategory.END_PUNCTUATION,
        CharCategory.MATH_SYMBOL,
        CharCategory.OTHER_SYMBOL
    )

    /**
     * 保存翻译缓存（不渲染 overlay）。
     * 用于分批渲染场景：用户关闭 overlay 后仍保存完整缓存。
     */
    private suspend fun saveTranslationCache(original: Bitmap, allBubbles: List<TranslatedBubble>) {
        try {
            val translatorName = buildTranslatorDisplayName()
            val ocrTexts = allBubbles.mapIndexed { i, b -> "[${i + 1}] ${b.originalText}" }.joinToString("\n")
            val transTexts = allBubbles.mapIndexed { i, b -> "[${i + 1}] ${b.translatedText}" }.joinToString("\n")
            LogCollector.d(TAG, "saveTranslationCache: ${allBubbles.size} 个气泡")
            // 渲染完整 bitmap 用于缓存
            val resultBitmap = withContext(Dispatchers.Default) {
                OverlayRenderer.renderOverlay(
                    original = original,
                    regions = allBubbles,
                    fontSize = config.fontSize,
                    autoFit = config.autoFontSize,
                    textColor = config.textColor,
                    bgColor = config.bgColor
                )
            }
            // 使用实际裁剪坐标（如果有 cropRect）或全屏尺寸
            val fullWidth = pendingFullBitmap?.width ?: original.width
            val fullHeight = pendingFullBitmap?.height ?: original.height
            val useCrop = cropRect != null
            val entry = CacheEntry(
                type = TranslationCacheManager.MODE_MANGA,
                sourceText = ocrTexts.ifEmpty { null },
                translatedText = transTexts.ifEmpty { null },
                resultBitmap = resultBitmap.copy(resultBitmap.config ?: Bitmap.Config.ARGB_8888, false),
                sourceLang = config.sourceLang,
                targetLang = config.targetLang,
                translatorName = translatorName,
                pHash = currentPHash,
                sessionId = sessionId,
                lastSessionId = sessionId,
                cropLeft = if (useCrop) cropRect!!.left.toInt() else 0,
                cropTop = if (useCrop) cropRect!!.top.toInt() else 0,
                cropRight = if (useCrop) cropRect!!.right.toInt() else fullWidth,
                cropBottom = if (useCrop) cropRect!!.bottom.toInt() else fullHeight
            )
            if (isForceRefreshActive) {
                // 只删除同页面的缓存（pHash 匹配），避免误删其他页面
                val historyIdToDelete = if (currentPHash == lastCachedPHash) lastCachedHistoryId else 0L
                cacheManager.refreshCache(historyIdToDelete, entry, originalBitmap = pendingFullBitmap)
                lastCachedHistoryId = 0
                lastCachedPHash = 0
                isForceRefreshActive = false
            } else {
                cacheManager.saveToCache(entry, originalBitmap = pendingFullBitmap)
            }
        } catch (e: Exception) {
            LogCollector.e(TAG, "saveTranslationCache 失败", e)
        }
    }

    /**
     * 分批渲染流程：检测+裁剪 → 分两批 OCR+翻译+渲染。
     * 支持 RT-DETR-V2 + MangaOcr 和 PP-OCRv5 独立两种组合。
     *
     * @return true 如果执行了分批流程，false 如果不满足条件（应回退到原有流程）
     */
    private suspend fun incrementalTranslateFlow(bitmap: Bitmap): Boolean {
        val isIncrementalEnabled = prefs.getBoolean("Incremental_Render", false)
        if (!isIncrementalEnabled) return false

        val isRTDetrMangaOcr = config.detEngine == DetEngine.RT_DETR_V2 && config.ocrEngine == OcrEngine.MangaOcr
        val isPPOcrV5Standalone = config.detEngine == DetEngine.PP_OCR_V5 && config.ocrEngine == OcrEngine.PPOcrV5
        if (!isRTDetrMangaOcr && !isPPOcrV5Standalone) return false

        return if (isRTDetrMangaOcr) {
            incrementalRTDetrMangaOcr(bitmap)
        } else {
            incrementalPPOcrV5(bitmap)
        }
    }

    /**
     * RT-DETR-V2 + MangaOcr 增量渲染。
     * 检测气泡 → 分批 MangaOcr encoder+decoder → 翻译+渲染。
     */
    private suspend fun incrementalRTDetrMangaOcr(bitmap: Bitmap): Boolean {
        initRTDetrV2IfNeeded()
        ensureMangaOcrInitialized()

        LogCollector.d(TAG, "incrementalRTDetrMangaOcr: 开始检测+裁剪, keepTextFree=${config.keepTextFree}")
        val croppedBubbles = DetectionBridge.detectAndCropRTDetrV2(bitmap, config.keepTextFree)
        if (croppedBubbles.isEmpty()) {
            LogCollector.d(TAG, "incrementalRTDetrMangaOcr: 未检测到气泡")
            if (!isAutoTranslating) {
                withContext(Dispatchers.Main) { showToast(getString(R.string.no_text_found), true) }
            }
            return true
        }

        if (croppedBubbles.size <= INCREMENTAL_THRESHOLD) {
            LogCollector.d(TAG, "incrementalRTDetrMangaOcr: ${croppedBubbles.size} <= $INCREMENTAL_THRESHOLD，不触发")
            croppedBubbles.forEach { it.croppedBitmap.recycle() }
            return false
        }

        val sorted = sortByMangaReadingOrder(croppedBubbles)
        val groups = groupByProximity(sorted, { it.rect }, "RT-DETR")
        val (firstBatch, secondBatch) = splitAtGroupBoundaries(groups)
        LogCollector.d(TAG, "incrementalRTDetrMangaOcr: 第一批 ${firstBatch.size}，第二批 ${secondBatch.size}")

        try {
            showProgressOverlay("识别中（1/2）...")
            val firstTextBlocks = DetectionBridge.recognizeCroppedBubbles(
                firstBatch, DetectionBridge.CTDOCREngine.MangaOcr, this@MangaFloatingService, config.sourceLang
            )
            LogCollector.d(TAG, "incrementalRTDetrMangaOcr: 第一批 OCR ${firstTextBlocks.size} 个文字块")

            // 保存上下文历史大小，分批翻译完后回滚，避免污染后续页面的上下文
            val contextSnapshotSize = contextHistory.size

            val firstTranslated = if (firstTextBlocks.isEmpty()) {
                emptyList()
            } else {
                val firstBubbleRegions = textBlocksToBubbleRegions(firstTextBlocks)
                withContext(Dispatchers.Main) { showProgressOverlay("翻译进行中，请勿点击屏幕...") }

                val ocrJob = lifecycleScope.async(Dispatchers.IO) {
                    DetectionBridge.recognizeCroppedBubbles(
                        secondBatch, DetectionBridge.CTDOCREngine.MangaOcr, this@MangaFloatingService, config.sourceLang
                    )
                }

                val result = incrementalTranslateBubbles(firstBubbleRegions, forceContext = true)
                if (result.isNotEmpty()) renderAndShowMergedOverlay(bitmap, result, saveCache = false)

                val secondTextBlocks = ocrJob.await()
                LogCollector.d(TAG, "incrementalRTDetrMangaOcr: 第二批 OCR ${secondTextBlocks.size} 个文字块")
                if (secondTextBlocks.isNotEmpty()) {
                    val secondBubbleRegions = textBlocksToBubbleRegions(secondTextBlocks)
                    result + incrementalTranslateBubbles(secondBubbleRegions, forceContext = true)
                } else result
            }

            // 回滚分批渲染添加的上下文，只保留翻译前的历史
            while (contextHistory.size > contextSnapshotSize) {
                contextHistory.removeLast()
            }

            finalizeIncremental(bitmap, firstTranslated)
            return true
        } catch (e: Exception) {
            LogCollector.e(TAG, "incrementalRTDetrMangaOcr: 失败", e)
            return false
        }
    }

    /**
     * PP-OCRv5 独立增量渲染。
     * det 检测全部文字行 → 逐行裁剪 → 分批 OCR + TextLineMerger 合并 → 翻译+渲染。
     */
    private suspend fun incrementalPPOcrV5(bitmap: Bitmap): Boolean {
        initPPOcrV5IfNeeded()

        val (ppRecLang, hint) = PPOcrV5Engine.resolveRecLang(this@MangaFloatingService, config.sourceLang)
        if (hint != null) withContext(Dispatchers.Main) { showToast(hint, true) }
        // 非默认模型时提示
        if (ppRecLang != null && ppRecLang != PPOcrV5Engine.RecLang.ZH && ppRecLang != PPOcrV5Engine.RecLang.JA) {
            withContext(Dispatchers.Main) { showToast("使用专用识别模型: rec_${ppRecLang.code}", false) }
        }
        if (ppRecLang == null) return false

        LogCollector.d(TAG, "incrementalPPOcrV5: 开始检测")
        val textLines = DetectionBridge.detectAndCropPPOcrV5Lines(this@MangaFloatingService, bitmap)
        if (textLines.isEmpty()) {
            LogCollector.d(TAG, "incrementalPPOcrV5: 未检测到文字")
            if (!isAutoTranslating) {
                withContext(Dispatchers.Main) { showToast(getString(R.string.no_text_found), true) }
            }
            return true
        }

        if (textLines.size <= INCREMENTAL_THRESHOLD) {
            LogCollector.d(TAG, "incrementalPPOcrV5: ${textLines.size} <= $INCREMENTAL_THRESHOLD，不触发")
            textLines.forEach { it.croppedBitmap.recycle() }
            return false
        }

        val groups = groupByProximity(textLines, { it.rect }, "PP-OCRv5")
        val (firstBatch, secondBatch) = splitAtGroupBoundaries(groups)
        LogCollector.d(TAG, "incrementalPPOcrV5: 第一批 ${firstBatch.size} 行，第二批 ${secondBatch.size} 行")

        // 识别单批：OCR → TextLineMerger 合并 → TextBlockInfo
        suspend fun recognizeBatch(batch: List<CroppedTextLine>): List<TextBlockInfo> {
            val crops = batch.map { it.croppedBitmap }
            val rects = batch.map { it.rect }
            val angles = batch.map { it.angle }
            val centers = batch.map { android.graphics.PointF(it.centerX, it.centerY) }
            val recResults = try {
                withContext(Dispatchers.IO) {
                    PPOcrV5Engine.recognizeBatchWithCls(this@MangaFloatingService, crops, ppRecLang)
                }
            } catch (e: java.io.FileNotFoundException) {
                crops.forEach { it.recycle() }
                statusOverlay.showError("识别模型加载失败：${e.message}")
                throw e
            } catch (e: Exception) {
                crops.forEach { it.recycle() }
                statusOverlay.showError("识别模型异常：${e.message}")
                throw e
            }
            // 释放裁剪图片
            crops.forEach { it.recycle() }
            // TextLineMerger 识别后合并
            val mergedInput = PPOcrV5Engine.recResultsToTextLines(recResults, rects, angles, centers)
            TextRegionMerger.refreshParams(this@MangaFloatingService)
            val allMerged = TextRegionMerger.merge(mergedInput.map { it.toTextRegion() })
            // 合并后内容过滤
            val (mergedRegions, contentDiscarded) = filterMergedRegions(allMerged)
            LogCollector.d(TAG, "recognizeBatch TextLineMerger: ${mergedInput.size} 行 → ${allMerged.size} 合并 → 内容丢弃${contentDiscarded.size} → ${mergedRegions.size} 输出")
            return mergedRegions.map { region ->
                TextBlockInfo(
                    text = region.texts.joinToString("\n"),
                    boundingBox = region.rect,
                    cornerPoints = null,
                    isVertical = region.direction == TextDirection.VERTICAL_RL,
                    angle = region.angle,
                    centerX = region.center.x,
                    centerY = region.center.y
                )
            }.filter { it.text.isNotBlank() }
        }

        var ocrJob: kotlinx.coroutines.Deferred<List<TextBlockInfo>>? = null
        try {
            showProgressOverlay("识别中（1/2）...")
            val firstTextBlocks = recognizeBatch(firstBatch)
            LogCollector.d(TAG, "incrementalPPOcrV5: 第一批 OCR ${firstTextBlocks.size} 个文字块")

            // 保存上下文历史大小，分批翻译完后回滚，避免污染后续页面的上下文
            val contextSnapshotSize = contextHistory.size

            val firstTranslated = if (firstTextBlocks.isEmpty()) {
                emptyList()
            } else {
                val firstBubbleRegions = textBlocksToBubbleRegions(firstTextBlocks)
                withContext(Dispatchers.Main) { showProgressOverlay("翻译进行中，请勿点击屏幕...") }

                ocrJob = lifecycleScope.async(Dispatchers.IO) {
                    recognizeBatch(secondBatch)
                }

                val result = incrementalTranslateBubbles(firstBubbleRegions, forceContext = true)
                if (result.isNotEmpty()) renderAndShowMergedOverlay(bitmap, result, saveCache = false)

                val secondTextBlocks = ocrJob!!.await()
                ocrJob = null // 已完成，不再需要取消
                LogCollector.d(TAG, "incrementalPPOcrV5: 第二批 OCR ${secondTextBlocks.size} 个文字块")
                if (secondTextBlocks.isNotEmpty()) {
                    val secondBubbleRegions = textBlocksToBubbleRegions(secondTextBlocks)
                    result + incrementalTranslateBubbles(secondBubbleRegions, forceContext = true)
                } else {
                    result
                }
            }

            // 回滚分批渲染添加的上下文，只保留翻译前的历史
            while (contextHistory.size > contextSnapshotSize) {
                contextHistory.removeLast()
            }

            finalizeIncremental(bitmap, firstTranslated)
            return true
        } catch (e: Exception) {
            LogCollector.e(TAG, "incrementalPPOcrV5: 失败", e)
            // 取消正在运行的 OCR 任务，避免 use-after-recycle
            ocrJob?.cancel()
            // 回收未处理的裁剪图片（firstBatch 已在 recognizeBatch 内部回收，跳过）
            secondBatch.forEach { if (!it.croppedBitmap.isRecycled) it.croppedBitmap.recycle() }
            return false
        }
    }

    /**
     * 增量渲染公共收尾：最终渲染/缓存 + 状态更新。
     */
    private suspend fun finalizeIncremental(bitmap: Bitmap, allTranslated: List<TranslatedBubble>) {
        if (allTranslated.isNotEmpty()) {
            renderAndShowMergedOverlay(bitmap, allTranslated, saveCache = false)
            LogCollector.d(TAG, "finalizeIncremental: 最终渲染完成，共 ${allTranslated.size} 个气泡")
            // 统一保存完整缓存
            LogCollector.d(TAG, "finalizeIncremental: 保存完整缓存，共 ${allTranslated.size} 个气泡")
            saveTranslationCache(bitmap, allTranslated)
        }
        statusOverlay.showImmediate("翻译完成")
        lastTranslatedHash = currentPHash
        if (isAutoTranslating) scheduleNextDetection(DETECT_INTERVAL_MS)
    }

    private suspend fun processMangaScreenshot(bitmap: Bitmap, precomputedPHash: Long? = null) {
        try {
            LogCollector.d(TAG, "processMangaScreenshot: START")

            // 清理过期的区域缓存
            if (isAutoTranslating) {
                val beforeSize = translatedRegions.size
                evictExpiredRegions()
                if (beforeSize != translatedRegions.size) {
                    LogCollector.d(TAG, "evictExpiredRegions: ${beforeSize} → ${translatedRegions.size} (removed ${beforeSize - translatedRegions.size})")
                }
            }
            LogCollector.d(TAG, "processMangaScreenshot: cacheSize at start=${translatedRegions.size}")

            // 使用全屏截图计算的稳定 pHash（不受框选偏移影响）
            // collector 已传入全屏 pHash，fallback 到 bitmap 计算（理论上不会走到）
            currentPHash = precomputedPHash ?: PerceptualHash.compute(bitmap, centerCrop = true)

            // 调试模式：最高优先级，跳过缓存直接检测
            val isDebugMode = when (config.detEngine) {
                DetEngine.CTD -> prefs.getBoolean("CTD_Debug_View", false)
                DetEngine.RT_DETR_V2 -> prefs.getBoolean("RTDetrV2_Debug_View", false)
                DetEngine.MLKIT -> prefs.getBoolean("MLKit_Debug_View", false)
                DetEngine.PP_OCR_V5 -> prefs.getBoolean("PPOcrV5_Debug_View", false)
                else -> false
            }

            if (isDebugMode) {
                LogCollector.d(TAG, "processMangaScreenshot: Debug mode enabled, skip cache")
                when (config.detEngine) {
                    DetEngine.CTD -> {
                        LogCollector.d(TAG, "CTD Debug Mode: 开始检测")
                        val debugResult = withContext(Dispatchers.IO) {
                            detectWithCTDDebug(bitmap)
                        }
                        LogCollector.d(TAG, "CTD Debug Mode: raw=${debugResult.rawBoxes.size}, merged=${debugResult.mergedGroups.size}")
                        showCTDDebugView(bitmap, debugResult)
                    }
                    DetEngine.RT_DETR_V2 -> {
                        LogCollector.d(TAG, "RT-DETR-V2 Debug Mode: 开始检测")
                        initRTDetrV2IfNeeded()
                        val debugResult = withContext(Dispatchers.IO) {
                            DetectionBridge.detectWithRTDetrV2Debug(bitmap, config.keepTextFree)
                        }
                        LogCollector.d(TAG, "RT-DETR-V2 Debug Mode: total=${debugResult.allBubbles.size}, text_bubble=${debugResult.textBubbles.size}, text_free=${debugResult.textFree.size}, bubble=${debugResult.emptyBubbles.size}")
                        showRTDetrV2DebugView(bitmap, debugResult)
                    }
                    DetEngine.MLKIT -> {
                        LogCollector.d(TAG, "ML Kit Debug Mode: 开始识别")
                        val mlKitResult = withContext(Dispatchers.IO) {
                            detectWithMLKitDebug(bitmap, config.sourceLang)
                        }
                        LogCollector.d(TAG, "ML Kit Debug Mode: blocks=${mlKitResult.textBlocks.size}, totalLines=${mlKitResult.totalLines}, totalElements=${mlKitResult.totalElements}")
                        showMLKitDebugView(bitmap, mlKitResult)
                    }
                    DetEngine.PP_OCR_V5 -> {
                        LogCollector.d(TAG, "PP-OCRv5 Debug Mode: 开始检测+识别")
                        initPPOcrV5IfNeeded()
                        val (recLang, hint) = PPOcrV5Engine.resolveRecLang(this@MangaFloatingService, config.sourceLang)
                        if (hint != null) {
                            showToast(hint, true)
                        }
                        // 非默认模型时提示
                        if (recLang != null && recLang != PPOcrV5Engine.RecLang.ZH && recLang != PPOcrV5Engine.RecLang.JA) {
                            showToast("使用专用识别模型: rec_${recLang.code}", false)
                        }
                        if (recLang != null) {
                            val ocrResult = withContext(Dispatchers.IO) {
                                PPOcrV5Engine.runOCR(this@MangaFloatingService, bitmap, recLang, useDet = true, useCls = false)
                            }
                            // 调试检测：获取被丢弃的选区
                            val debugDet = withContext(Dispatchers.IO) {
                                PPOcrV5Engine.runDetForDebug(this@MangaFloatingService, bitmap)
                            }
                            val recDisc = ocrResult.recDebug
                            val scoreDisc = recDisc?.discardedReasons?.count { it == "score" } ?: 0
                            val contentDisc = recDisc?.discardedReasons?.count { it != "score" } ?: 0
                            LogCollector.d(TAG, "PP-OCRv5 Debug Mode: det=${ocrResult.boxes.size}, rec=${ocrResult.texts.size}, det丢弃=${debugDet.discardedBoxes.size}, 识别丢弃=$scoreDisc, 内容丢弃=$contentDisc")
                            // 原始识别详情
                            for (i in ocrResult.texts.indices) {
                                val text = ocrResult.texts[i]
                                val score = ocrResult.scores.getOrElse(i) { 0f }
                                val box = ocrResult.boxes.getOrNull(i)
                                if (box != null && box.size >= 8) {
                                    val topDx = box[2] - box[0]
                                    val topDy = box[3] - box[1]
                                    val leftDx = box[6] - box[0]
                                    val leftDy = box[7] - box[1]
                                    val topLen = kotlin.math.sqrt((topDx * topDx + topDy * topDy).toDouble()).toFloat()
                                    val leftLen = kotlin.math.sqrt((leftDx * leftDx + leftDy * leftDy).toDouble()).toFloat()
                                    var angle = kotlin.math.atan2(topDy, topDx) * 180f / Math.PI.toFloat()
                                    if (abs(angle) <= 3f) angle = 0f
                                    val isVertical = leftLen > topLen * 1.5f
                                    val fontSize = if (isVertical) topLen else leftLen
                                    val dirLabel = if (isVertical) "V" else "H"
                                    val angleStr = if (abs(angle) > 0.5f) "∠${String.format("%.1f°", angle)}" else "∠0°"
                                    val quadStr = "TL(${box[0].toInt()},${box[1].toInt()}) TR(${box[2].toInt()},${box[3].toInt()}) BR(${box[4].toInt()},${box[5].toInt()}) BL(${box[6].toInt()},${box[7].toInt()})"
                                    LogCollector.d(TAG, "PP-OCRv5 RAW[$i]: ${String.format("%.2f", score)} $dirLabel fs=${String.format("%.0f", fontSize)} $angleStr $quadStr \"$text\"")
                                }
                            }
                            // 被丢弃选区详情
                            for (i in debugDet.discardedBoxes.indices) {
                                val box = debugDet.discardedBoxes[i]
                                val score = debugDet.discardedScores.getOrElse(i) { 0f }
                                val reason = debugDet.discardedReasons.getOrElse(i) { "" }
                                LogCollector.d(TAG, "PP-OCRv5 DISCARDED[$i]: ${String.format("%.2f", score)} [${box[0].toInt()},${box[1].toInt()}→${box[4].toInt()},${box[5].toInt()}] $reason")
                            }
                            // 识别/内容丢弃详情
                            if (recDisc != null) {
                                for (i in recDisc.discardedBoxes.indices) {
                                    val box = recDisc.discardedBoxes[i]
                                    val score = recDisc.discardedScores.getOrElse(i) { 0f }
                                    val text = recDisc.discardedTexts.getOrElse(i) { "" }
                                    val reason = recDisc.discardedReasons.getOrElse(i) { "score" }
                                    val boxStr = "[${box[0].toInt()},${box[1].toInt()}→${box[4].toInt()},${box[5].toInt()}]"
                                    if (reason == "score") {
                                        LogCollector.d(TAG, "PP-OCRv5 REC_DISCARD[$i]: ${String.format("%.2f", score)}<thresh $boxStr \"${text.take(20)}\"")
                                    } else {
                                        LogCollector.d(TAG, "PP-OCRv5 CONTENT_DISCARD[$i]: $reason $boxStr \"${text.take(20)}\"")
                                    }
                                }
                            }
                            // 运行 TextLineMerger 合并
                            val allMerged = runTextLineMerge(ocrResult, bitmap.width, bitmap.height)
                            // 合并后内容过滤
                            val (mergedRegions, contentDiscarded) = filterMergedRegions(allMerged)
                            LogCollector.d(TAG, "PP-OCRv5 Debug Mode: merged=${allMerged.size}, 内容丢弃=${contentDiscarded.size}, 输出=${mergedRegions.size}")
                            // 合并区域详情
                            for ((idx, region) in mergedRegions.withIndex()) {
                                val dirLabel = if (region.direction == TextDirection.VERTICAL_RL) "竖排" else "横排"
                                val r = region.rect
                                val merged = region.texts.joinToString("｜")
                                val angleStr = if (abs(region.angle) > 0.5f) " ∠${String.format("%.1f°", region.angle)}" else ""
                                LogCollector.d(TAG, "PP-OCRv5 MERGED[$idx]: $dirLabel ×${region.texts.size}$angleStr fs=${String.format("%.0f", region.fontSize)} [${r.left},${r.top},${r.right},${r.bottom}] \"$merged\"")
                            }
                            // 内容丢弃详情
                            for ((region, reason) in contentDiscarded) {
                                val text = region.texts.joinToString("")
                                val r = region.rect
                                LogCollector.d(TAG, "PP-OCRv5 CONTENT_DISCARD: $reason [${r.left},${r.top},${r.right},${r.bottom}] \"${text.take(20)}\"")
                            }
                            showPPOcrV5DebugView(bitmap, ocrResult, mergedRegions, debugDet)
                        } else {
                            LogCollector.w(TAG, "PP-OCRv5 Debug Mode: 不支持的语言 ${config.sourceLang}")
                            showToast("PP-OCRv5 不支持语言: ${config.sourceLang}", true)
                        }
                    }
                    else -> {}
                }
                return
            }

            // 全局缓存检查
            isForceRefreshActive = forceRefresh
            if (!isForceRefreshActive) {
                val cached = cacheManager.findCache(currentPHash, TranslationCacheManager.MODE_MANGA, bitmap.width, bitmap.height, sessionId)
                if (cached != null && cached.resultBitmap != null) {
                    LogCollector.d(TAG, "processMangaScreenshot: 缓存命中, historyId=${cached.historyId}")
                    lastCachedHistoryId = cached.historyId
                    lastCachedPHash = currentPHash
                    statusOverlay.showImmediate("缓存命中")
                    lastTranslatedHash = currentPHash
                    withContext(Dispatchers.Main) {
                        showResultOverlay(cached.resultBitmap, fromCache = true)
                    }
                    return
                }
            } else {
                LogCollector.d(TAG, "processMangaScreenshot: 强制刷新，跳过缓存")
                forceRefresh = false
            }

            // 分批渲染：在检测之前尝试分批流程
            if (incrementalTranslateFlow(bitmap)) {
                LogCollector.d(TAG, "processMangaScreenshot: 分批渲染完成，跳过原有流程")
                return
            }
            LogCollector.d(TAG, "processMangaScreenshot: 分批渲染未触发，走原有流程")

            // 确保选中的模型已初始化
            when (config.detEngine) {
                DetEngine.CTD -> initCTDIfNeeded()
                DetEngine.MLKIT -> {}
                DetEngine.RT_DETR_V2 -> initRTDetrV2IfNeeded()
                DetEngine.PP_OCR_V5 -> initPPOcrV5IfNeeded()
            }
            when (config.ocrEngine) {
                OcrEngine.MLKit -> {}
                OcrEngine.MangaOcr -> ensureMangaOcrInitialized()
                OcrEngine.PPOcrV5 -> initPPOcrV5IfNeeded()
            }

            // Step 1: 文字检测 + 识别
            showProgressOverlay("文字识别中...")
            val (ppRecLang, ppHint) = if (config.ocrEngine == OcrEngine.PPOcrV5 || config.detEngine == DetEngine.PP_OCR_V5) {
                PPOcrV5Engine.resolveRecLang(this@MangaFloatingService, config.sourceLang)
            } else {
                Pair(PPOcrV5Engine.getRecLang(config.sourceLang), null)
            }
            if (ppHint != null) {
                showToast(ppHint, true)
            }
            // 非默认模型时提示
            if (ppRecLang != null && ppRecLang != PPOcrV5Engine.RecLang.ZH && ppRecLang != PPOcrV5Engine.RecLang.JA) {
                showToast("使用专用识别模型: rec_${ppRecLang.code}", false)
            }
            LogCollector.d(TAG, "Step 1 配置: detEngine=${config.detEngine}, ocrEngine=${config.ocrEngine}, sourceLang=${config.sourceLang}" +
                if (config.ocrEngine == OcrEngine.PPOcrV5 || config.detEngine == DetEngine.PP_OCR_V5) ", PP-recModel=${ppRecLang?.code ?: "不支持"}" else "")
            val textBlocks: List<TextBlockInfo> = withContext(Dispatchers.IO) {
                when (config.detEngine) {
                    DetEngine.CTD -> {
                        val ctdOcrEngine = when (config.ocrEngine) {
                            OcrEngine.MLKit -> DetectionBridge.CTDOCREngine.MLKit
                            OcrEngine.MangaOcr -> DetectionBridge.CTDOCREngine.MangaOcr
                            OcrEngine.PPOcrV5 -> DetectionBridge.CTDOCREngine.PPOcrV5
                        }
                        LogCollector.d(TAG, "使用 CTD(检测) + ${ctdOcrEngine.name}(识别), lang=${config.sourceLang}" +
                            if (ctdOcrEngine == DetectionBridge.CTDOCREngine.PPOcrV5) ", rec=${ppRecLang?.code}" else "")
                        DetectionBridge.detectWithCTD(bitmap, config.sourceLang, ctdOcrEngine, this@MangaFloatingService)
                    }
                    DetEngine.MLKIT -> {
                        when (config.ocrEngine) {
                            OcrEngine.MLKit -> {
                                LogCollector.d(TAG, "使用 ML Kit(检测+识别), lang=${config.sourceLang}")
                                OCRBridge.recognizeWithLocation(config.sourceLang, bitmap)
                            }
                            OcrEngine.MangaOcr -> {
                                LogCollector.d(TAG, "使用 ML Kit(检测) + manga-ocr(${MangaFloatingService.currentLoadedMangaOcrVersion})(识别), lang=${config.sourceLang}")
                                MangaOcrBridge.recognizeWithLocation(bitmap, config.sourceLang)
                            }
                            OcrEngine.PPOcrV5 -> {
                                LogCollector.d(TAG, "使用 PP-OCRv5(独立det+cls+rec), lang=${config.sourceLang}, rec=${ppRecLang?.code}")
                                DetectionBridge.detectWithPPOcrV5(bitmap, config.sourceLang, this@MangaFloatingService)
                            }
                        }
                    }
                    DetEngine.RT_DETR_V2 -> {
                        // RT-DETR-V2 气泡检测 + 指定 OCR 引擎识别
                        val rtdetrOcrEngine = when (config.ocrEngine) {
                            OcrEngine.MLKit -> DetectionBridge.CTDOCREngine.MLKit
                            OcrEngine.MangaOcr -> DetectionBridge.CTDOCREngine.MangaOcr
                            OcrEngine.PPOcrV5 -> DetectionBridge.CTDOCREngine.PPOcrV5
                        }
                        LogCollector.d(TAG, "使用 RT-DETR-V2(检测) + ${rtdetrOcrEngine.name}(识别), lang=${config.sourceLang}" +
                            if (rtdetrOcrEngine == DetectionBridge.CTDOCREngine.PPOcrV5) ", rec=${ppRecLang?.code}" else "")
                        DetectionBridge.detectWithRTDetrV2(bitmap, config.sourceLang, rtdetrOcrEngine, this@MangaFloatingService, config.keepTextFree)
                    }
                    DetEngine.PP_OCR_V5 -> {
                        LogCollector.d(TAG, "使用 PP-OCRv5(独立det+cls+rec), lang=${config.sourceLang}, rec=${ppRecLang?.code}")
                        DetectionBridge.detectWithPPOcrV5(bitmap, config.sourceLang, this@MangaFloatingService)
                    }
                }
            }
            LogCollector.d(TAG, "processMangaScreenshot: Step 1 - OCR done, found ${textBlocks.size} text blocks")

            if (textBlocks.isEmpty()) {
                LogCollector.d(TAG, "processMangaScreenshot: No text found, returning early")
                if (isAutoTranslating) {
                    consecutiveEmptyCount++
                    if (consecutiveEmptyCount >= 3) {
                        LogCollector.d(TAG, "processMangaScreenshot: ${consecutiveEmptyCount} consecutive empty OCR — possible protected area")
                        withContext(Dispatchers.Main) {
                            showToast(getString(R.string.no_text_found_protected), false)
                        }
                        consecutiveEmptyCount = 0  // 重置，避免反复弹
                    }
                } else {
                    showToast(getString(R.string.no_text_found), true)
                }
                return
            }
            // 有文字时重置连续空计数
            consecutiveEmptyCount = 0

            // Step 2: 气泡合并（自动/手动共用）
            // CTD 已用 BoxMerger 分组，RT-DETR-V2 检测器直接输出气泡级结果，跳过后合并
            // PP-OCRv5 已在 detectWithPPOcrV5 内部用 TextLineMerger 合并，跳过后合并
            // MLKit 需要 BubbleDetector 把行级结果合并成气泡
            val needsPostMerge = config.detEngine == DetEngine.MLKIT
            val allBubbles = if (needsPostMerge) {
                LogCollector.d(TAG, "processMangaScreenshot: Step 2 - BubbleDetector 后合并")
                BubbleDetector.detectBubbles(textBlocks, config)
            } else {
                LogCollector.d(TAG, "processMangaScreenshot: Step 2 - 已前合并，跳过后合并")
                textBlocks.filter { it.boundingBox != null }.map { block ->
                    val rect = block.boundingBox!!
                    val isVertical = block.isVertical ?: (rect.height() > rect.width())
                    if (kotlin.math.abs(block.angle) > 0.5f) {
                        LogCollector.d(TAG, "BubbleRegion: angle=${block.angle}, cx=${block.centerX}, cy=${block.centerY}, text='${block.text.take(15)}'")
                    }
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
            LogCollector.d(TAG, "processMangaScreenshot: Step 2 - Detected ${allBubbles.size} bubbles")

            // Step 3: Translate
            val newTranslatedBubbles: List<TranslatedBubble>
            if (isAutoTranslating) {
                // 自动翻译：直接传入全部气泡，由 incrementalTranslateBubbles 内部做文本级缓存匹配
                // （不再用 IoU 坐标过滤 — 坐标在滚动时会变，文本匹配更可靠）
                LogCollector.d(TAG, "processMangaScreenshot: Step 3 - Incremental translate ${allBubbles.size} bubbles")
                newTranslatedBubbles = incrementalTranslateBubbles(allBubbles)
            } else {
                // 手动翻译：翻译全部 bubbles
                LogCollector.d(TAG, "processMangaScreenshot: Step 3 - Full translate ${allBubbles.size} bubbles")
                newTranslatedBubbles = translateBubbles(allBubbles)
            }
            LogCollector.d(TAG, "processMangaScreenshot: Step 3 - done, got ${newTranslatedBubbles.size} results")

            // Step 4: 合并已缓存翻译 + 新翻译，渲染 overlay
            LogCollector.d(TAG, "processMangaScreenshot: Step 4 - Rendering merged overlay")
            renderAndShowMergedOverlay(bitmap, newTranslatedBubbles)
            LogCollector.d(TAG, "processMangaScreenshot: Step 4 - DONE")
            statusOverlay.showImmediate("翻译完成")

            // 更新区域缓存和 pHash
            lastTranslatedHash = currentPHash
            if (isAutoTranslating) {
                // 翻译完成后用短间隔快速重新检测，响应翻页
                scheduleNextDetection(DETECT_INTERVAL_MS)
            }

        } finally {
            bitmap.recycle()
            // 如果有独立的全屏 bitmap（不同于 OCR bitmap），一并释放
            if (pendingFullBitmap != null && pendingFullBitmap !== bitmap) {
                pendingFullBitmap!!.recycle()
            }
            pendingFullBitmap = null
            // 自动翻译模式：确保 lastTranslatedHash 被更新，避免异常后状态机卡住
            if (isAutoTranslating && currentPHash != 0L) {
                lastTranslatedHash = currentPHash
            }
            LogCollector.d(TAG, "processMangaScreenshot: FINALLY - dismissing progress, isProcessing=false")
            isProcessing = false
            dismissProgressOverlay()
        }
    }

    /**
     * 增量翻译：基于合并后的气泡，先查文本缓存，再翻译未命中的。
     * 翻译完成后将结果加入 translatedRegions 缓存。
     */
    private suspend fun incrementalTranslateBubbles(bubbles: List<BubbleRegion>, forceContext: Boolean = false): List<TranslatedBubble> {
        if (bubbles.isEmpty()) return emptyList()

        LogCollector.d(TAG, "incrementalTranslateBubbles: ${bubbles.size} bubbles, forceContext=$forceContext, cacheSize=${translatedRegions.size}")

        // 文本级缓存：先精确匹配（快速路径），再模糊匹配（编辑距离）
        val fromCache = mutableListOf<TranslatedBubble>()
        val needTranslation = mutableListOf<BubbleRegion>()

        for (bubble in bubbles) {
            val combinedText = bubble.texts.map { cleanOcrText(it) }.filter { it.isNotBlank() }.joinToString("")
            if (combinedText.isBlank()) continue

            // 精确匹配
            val exactMatch = translatedRegions.find { it.ocrTextHash == combinedText.hashCode() && it.ocrText == combinedText }
            if (exactMatch != null) {
                fromCache.add(TranslatedBubble(
                    rect = bubble.rect,
                    originalText = combinedText,
                    translatedText = exactMatch.translation,
                    backgroundColor = Color.TRANSPARENT,
                    fontSize = bubble.fontSize,
                    direction = bubble.direction,
                    angle = bubble.angle,
                    centerX = bubble.centerX,
                    centerY = bubble.centerY,
                    fromCache = true
                ))
                // 更新时间
                translatedRegions.remove(exactMatch)
                translatedRegions.add(exactMatch.copy(
                    translatedAt = System.currentTimeMillis()
                ))
                LogCollector.d(TAG, "Text cache hit (exact): '${combinedText.take(20)}' → '${exactMatch.translation.take(20)}'")
            } else {
                // 模糊匹配：编辑距离自适应阈值
                val fuzzyMatch = findFuzzyMatch(combinedText)
                if (fuzzyMatch != null) {
                    fromCache.add(TranslatedBubble(
                        rect = bubble.rect,
                        originalText = combinedText,
                        translatedText = fuzzyMatch.translation,
                        backgroundColor = Color.TRANSPARENT,
                        fontSize = bubble.fontSize,
                        direction = bubble.direction,
                        angle = bubble.angle,
                        centerX = bubble.centerX,
                        centerY = bubble.centerY,
                        fromCache = true
                    ))
                    translatedRegions.remove(fuzzyMatch)
                    translatedRegions.add(fuzzyMatch.copy(
                        translatedAt = System.currentTimeMillis()
                    ))
                    LogCollector.d(TAG, "Text cache hit (fuzzy): '${combinedText.take(20)}' ~ '${fuzzyMatch.ocrText.take(20)}' → '${fuzzyMatch.translation.take(20)}'")
                } else {
                    needTranslation.add(bubble)
                }
            }
        }

        if (needTranslation.isEmpty()) {
            LogCollector.d(TAG, "incrementalTranslateBubbles: all ${bubbles.size} from text cache")
            evictOldRegions()
            return fromCache
        }

        LogCollector.d(TAG, "incrementalTranslateBubbles: ${fromCache.size} cached + ${needTranslation.size} need API")
        if (needTranslation.isNotEmpty()) {
            showProgressOverlay(getString(R.string.manga_translating))
        }

        // 用 translateBubbles 走和手动翻译完全相同的路径
        val results = translateBubbles(needTranslation, forceContext)

        // 缓存翻译结果
        for (result in results) {
            val textHash = result.originalText.hashCode()
            translatedRegions.add(TranslatedRegion(
                ocrText = result.originalText,
                ocrTextHash = textHash,
                translation = result.translatedText
            ))
            LogCollector.d(TAG, "Cached bubble: '${result.originalText.take(20)}' → '${result.translatedText.take(20)}'")
        }
        evictOldRegions()
        return fromCache + results
    }


    /**
     * 淘汰过旧的缓存区域。
     */
    private fun evictOldRegions() {
        if (translatedRegions.size > MAX_CACHED_REGIONS) {
            val removeCount = translatedRegions.size - MAX_CACHED_REGIONS
            repeat(removeCount) { translatedRegions.removeAt(0) }
        }
    }

    /**
     * 模糊文本匹配：在 translatedRegions 中查找与 targetText 最相似的条目。
     * 使用 OCR 感知的加权编辑距离，相似字符（如カ/力）替换代价更低。
     *
     * @return 最佳匹配的 TranslatedRegion，或 null。
     */
    private fun findFuzzyMatch(targetText: String): TranslatedRegion? {
        if (targetText.isEmpty()) return null

        // 获取自适应阈值
        val threshold = TextSimilarity.getAdaptiveThreshold(targetText.length)
        if (threshold <= 0.0f) return null  // 太短，必须精确匹配

        val normalizedTarget = TextSimilarity.normalize(targetText)
        if (normalizedTarget.isEmpty()) return null

        var bestMatch: TranslatedRegion? = null
        var bestDistance = threshold + 1.0f
        var checkedCount = 0
        var lengthSkipCount = 0

        for (region in translatedRegions) {
            val normalizedCache = TextSimilarity.normalize(region.ocrText)

            // 长度快速过滤
            if (kotlin.math.abs(normalizedTarget.length - normalizedCache.length).toFloat() > threshold) {
                lengthSkipCount++
                continue
            }

            // 加权编辑距离（带 early exit）
            val distance = TextSimilarity.weightedLevenshtein(normalizedTarget, normalizedCache, bestDistance)
            checkedCount++
            if (distance < bestDistance) {
                bestDistance = distance
                bestMatch = region
            }
        }

        if (bestMatch != null && bestDistance <= threshold) {
            LogCollector.d(TAG, "Fuzzy match: dist=${"%.2f".format(bestDistance)}/$threshold, '${targetText.take(15)}' ~ '${bestMatch.ocrText.take(15)}'")
            return bestMatch
        }
        if (checkedCount > 0 || lengthSkipCount > 0) {
            LogCollector.d(TAG, "Fuzzy no match: target='${targetText.take(15)}', threshold=$threshold, totalRegions=${translatedRegions.size}, lengthSkip=$lengthSkipCount, checked=$checkedCount, bestDist=$bestDistance")
        }
        return null
    }

    /**
     * 检测截图是否来自受限区域（全黑、纯色覆盖、DRM保护等）。
     * 两种检测：
     * 1. 全黑检测：95%+ 像素亮度极低
     * 2. 低方差检测：像素颜色几乎一致（纯色覆盖层）
     */
    private fun isRestrictedScreenshot(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        if (w == 0 || h == 0) return true

        // 采样 10x10 网格
        val stepX = (w / 10).coerceAtLeast(1)
        val stepY = (h / 10).coerceAtLeast(1)
        val pixels = mutableListOf<Triple<Int, Int, Int>>()
        var blackCount = 0

        for (iy in 0 until 10) {
            for (ix in 0 until 10) {
                val px = (ix * stepX + stepX / 2).coerceIn(0, w - 1)
                val py = (iy * stepY + stepY / 2).coerceIn(0, h - 1)
                val pixel = bitmap.getPixel(px, py)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                pixels.add(Triple(r, g, b))
                // 亮度阈值：RGB 均 < 16 视为黑色
                if (r < 16 && g < 16 && b < 16) {
                    blackCount++
                }
            }
        }

        // 检测1：全黑
        val blackRatio = blackCount.toFloat() / pixels.size
        if (blackRatio > 0.95f) {
            LogCollector.d(TAG, "isRestrictedScreenshot: 全黑 (${(blackRatio * 100).toInt()}%)")
            return true
        }

        // 检测2：低方差（纯色覆盖层，如DRM保护、安全应用遮罩）
        // 计算 RGB 各通道的方差
        val avgR = pixels.sumOf { it.first } / pixels.size
        val avgG = pixels.sumOf { it.second } / pixels.size
        val avgB = pixels.sumOf { it.third } / pixels.size
        var varianceSum = 0.0
        for ((r, g, b) in pixels) {
            varianceSum += (r - avgR) * (r - avgR) + (g - avgG) * (g - avgG) + (b - avgB) * (b - avgB)
        }
        val avgVariance = varianceSum / pixels.size
        // 方差极低（<50）说明像素几乎一致
        if (avgVariance < 50.0) {
            LogCollector.d(TAG, "isRestrictedScreenshot: 低方差覆盖层 (variance=${String.format("%.1f", avgVariance)}, avgRGB=($avgR,$avgG,$avgB))")
            return true
        }

        LogCollector.d(TAG, "isRestrictedScreenshot: 正常截图 (black=${(blackRatio * 100).toInt()}%, variance=${String.format("%.1f", avgVariance)})")
        return false
    }

    /**
     * 清除过期的缓存区域（超过 TTL）。
     */
    private fun evictExpiredRegions() {
        val now = System.currentTimeMillis()
        translatedRegions.removeAll { now - it.translatedAt > REGION_TTL_MS }
    }

    /**
     * 清除区域缓存。
     */
    private fun clearRegionCache() {
        translatedRegions.clear()
    }


    /**
     * 合并已缓存翻译 + 新翻译，渲染并显示 overlay。
     * 跳开与新翻译重叠的缓存区域，避免重复覆盖。
     */
    private suspend fun renderAndShowMergedOverlay(
        original: Bitmap,
        newBubbles: List<TranslatedBubble>,
        saveCache: Boolean = true,
        isRetranslate: Boolean = false,
        historyIdToDelete: Long = 0,
        originalBitmap: Bitmap? = null,
        cropLeft: Int = 0,
        cropTop: Int = 0,
        cropRight: Int = 0,
        cropBottom: Int = 0
    ) {
        if (newBubbles.isEmpty()) {
            LogCollector.d(TAG, "renderAndShowMergedOverlay: no content to render")
            return
        }

        // 更新进度
        if (isAutoTranslating) {
            showProgressOverlay(getString(R.string.manga_translating))
        }

        // 渲染
        val resultBitmap = withContext(Dispatchers.Default) {
            OverlayRenderer.renderOverlay(
                original = original,
                regions = newBubbles,
                fontSize = config.fontSize,
                autoFit = config.autoFontSize,
                textColor = config.textColor,
                bgColor = config.bgColor
            )
        }

        // 显示
        withContext(Dispatchers.Main) {
            showResultOverlay(resultBitmap)
        }

        // 保存到缓存和历史（分批翻译时由 finalizeIncremental 统一保存，避免保存中间结果）
        if (saveCache) {
            try {
                val translatorName = buildTranslatorDisplayName()
                val ocrTexts = newBubbles.mapIndexed { i, b -> "[${i + 1}] ${b.originalText}" }.joinToString("\n")
                val transTexts = newBubbles.mapIndexed { i, b -> "[${i + 1}] ${b.translatedText}" }.joinToString("\n")
                LogCollector.d(TAG, "保存缓存: ${newBubbles.size} 个气泡")
                // 使用实际裁剪坐标（如果有 cropRect 或重翻）或全屏尺寸
                val fullWidth = pendingFullBitmap?.width ?: original.width
                val fullHeight = pendingFullBitmap?.height ?: original.height
                val saveOrigBmp = if (isRetranslate) originalBitmap else pendingFullBitmap
                val entryCropLeft: Int
                val entryCropTop: Int
                val entryCropRight: Int
                val entryCropBottom: Int
                if (isRetranslate) {
                    entryCropLeft = cropLeft
                    entryCropTop = cropTop
                    entryCropRight = cropRight
                    entryCropBottom = cropBottom
                } else if (cropRect != null) {
                    entryCropLeft = cropRect!!.left.toInt()
                    entryCropTop = cropRect!!.top.toInt()
                    entryCropRight = cropRect!!.right.toInt()
                    entryCropBottom = cropRect!!.bottom.toInt()
                } else {
                    entryCropLeft = 0
                    entryCropTop = 0
                    entryCropRight = fullWidth
                    entryCropBottom = fullHeight
                }
                val entry = CacheEntry(
                    type = TranslationCacheManager.MODE_MANGA,
                    sourceText = ocrTexts.ifEmpty { null },
                    translatedText = transTexts.ifEmpty { null },
                    resultBitmap = resultBitmap.copy(resultBitmap.config ?: Bitmap.Config.ARGB_8888, false),
                    sourceLang = config.sourceLang,
                    targetLang = config.targetLang,
                    translatorName = translatorName,
                    pHash = currentPHash,
                    sessionId = sessionId,
                    lastSessionId = sessionId,
                    isRetranslated = isRetranslate,
                    cropLeft = entryCropLeft,
                    cropTop = entryCropTop,
                    cropRight = entryCropRight,
                    cropBottom = entryCropBottom
                )
                if (isRetranslate && historyIdToDelete > 0) {
                    cacheManager.refreshCache(historyIdToDelete, entry, originalBitmap = saveOrigBmp)
                    LogCollector.d(TAG, "重翻：替换旧缓存, historyId=$historyIdToDelete")
                } else if (isForceRefreshActive) {
                    val refreshId = if (currentPHash == lastCachedPHash) lastCachedHistoryId else 0L
                    cacheManager.refreshCache(refreshId, entry, originalBitmap = saveOrigBmp)
                    LogCollector.d(TAG, "强制刷新：替换旧缓存和历史, historyId=$refreshId")
                    lastCachedHistoryId = 0
                    lastCachedPHash = 0
                    isForceRefreshActive = false
                } else {
                    cacheManager.saveToCache(entry, originalBitmap = saveOrigBmp)
                }
            } catch (e: Exception) {
                LogCollector.e(TAG, "保存缓存失败", e)
            }
        }
    }

    private fun cleanOcrText(text: String): String {
        return text
            .replace(Regex("[\\n\\r]+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /** 构建翻译器显示名：API名(模型名) + 检测器 + 识别器 + 分批等参数 */
    private fun buildTranslatorDisplayName(): String {
        val apiName = translatorText?.javaClass?.simpleName ?: "Unknown"
        val model = translatorText?.modelName ?: ""
        val apiStr = if (model.isNotEmpty()) "$apiName($model)" else apiName

        val det = when (config.detEngine) {
            DetEngine.CTD -> "CTD"
            DetEngine.MLKIT -> "MLKit"
            DetEngine.RT_DETR_V2 -> "RT-DETR"
            DetEngine.PP_OCR_V5 -> "PP-OCRv5"
        }
        val ocr = when (config.ocrEngine) {
            OcrEngine.MLKit -> "MLKit"
            OcrEngine.MangaOcr -> "manga-ocr"
            OcrEngine.PPOcrV5 -> "PP-OCRv5"
        }

        val parts = mutableListOf(apiStr, "$det+$ocr")

        // 分批翻译：开关打开 + 支持的组合（RT-DETR+manga-ocr 或 PP-OCRv5 独立）
        val incrementalEnabled = prefs.getBoolean("Incremental_Render", false)
        val isRTDetrMangaOcr = config.detEngine == DetEngine.RT_DETR_V2 && config.ocrEngine == OcrEngine.MangaOcr
        val isPPOcrV5Standalone = config.detEngine == DetEngine.PP_OCR_V5 && config.ocrEngine == OcrEngine.PPOcrV5
        if (incrementalEnabled && (isRTDetrMangaOcr || isPPOcrV5Standalone)) {
            parts.add("分批✓")
        } else if (incrementalEnabled) {
            parts.add("分批✗")  // 开关打开但组合不支持
        }

        // 自由文字：开关打开 + 检测器是 RT-DETR-V2
        val keepTextFreeEnabled = prefs.getBoolean("Manga_Keep_Text_Free", false)
        if (keepTextFreeEnabled && config.detEngine == DetEngine.RT_DETR_V2) {
            parts.add("自由文字✓")
        } else if (keepTextFreeEnabled) {
            parts.add("自由文字✗")  // 开关打开但检测器不是 RT-DETR
        }

        // PP-OCRv5 参数（仅当检测器或识别器为 PP-OCRv5 时显示）
        if (config.detEngine == DetEngine.PP_OCR_V5 || config.ocrEngine == OcrEngine.PPOcrV5) {
            val boxThresh = prefs.getFloat("ppocr_det_box_thresh", 0.3f)
            val unclipRatio = prefs.getFloat("ppocr_det_unclip_ratio", 1.6f)
            val textScore = prefs.getFloat("ppocr_text_score_thresh", 0.5f)
            parts.add("box=%.2f unclip=%.1f score=%.2f".format(boxThresh, unclipRatio, textScore))
        }

        return parts.joinToString(" | ")
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
        bubbles: List<BubbleRegion>,
        forceContext: Boolean = false
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
                    direction = bubble.direction,
                    angle = bubble.angle,
                    centerX = bubble.centerX,
                    centerY = bubble.centerY
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
            translateBubblesBatch(preparedBubbles, forceContext)
        } else {
            translateBubblesSequential(preparedBubbles)
        }

        return symbolOnlyBubbles + translatedResults
    }

    /**
     * AI翻译：所有气泡合并为一次请求，用编号分隔
     */
    private suspend fun translateBubblesBatch(
        bubbles: List<Pair<BubbleRegion, String>>,
        forceContext: Boolean = false
    ): List<TranslatedBubble> = withContext(Dispatchers.IO) {
        LogCollector.d(TAG, "translateBubblesBatch: ${bubbles.size} bubbles in 1 request, forceContext=$forceContext")

        // 构建带编号的文本，格式说明由用户提示词中的模板提供
        val numberedText = bubbles.mapIndexed { index, (_, text) ->
            "[${index + 1}] $text"
        }.joinToString("\n")

        val latch = java.util.concurrent.CountDownLatch(1)
        var resultText: String? = null
        var errorMsg: String? = null

        // 分批渲染强制开启上下文（仅批次间传递）；正常漫画翻译不使用上下文
        val currentContextEnabled = forceContext
        val currentContextMaxCount = try {
            prefs.getString("game_context_count", "5").toIntOrNull() ?: 5
        } catch (e: Exception) { 5 }

        // 更新 AI 上下文（仅 OpenAI 兼容 API）
        (translatorText as? translationapi.openaitranslation.OpenAITranslation)?.updateContext(
            if (currentContextEnabled) contextHistory.toList() else emptyList(),
            currentContextEnabled
        )

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

        // 按编号解析结果（支持JSON格式和编号格式）
        val result = resultText!!.trim()
        val translations = if (result.startsWith("{")) {
            parseJsonTranslations(result, bubbles.size)
        } else {
            parseNumberedTranslations(result, bubbles.size)
        }
        LogCollector.d(TAG, "translateBubblesBatch: parsed ${translations.size} translations")

        // 更新 AI 上下文历史（仅 OpenAI 兼容 API）
        if (currentContextEnabled && translations.isNotEmpty()) {
            val sourceText = bubbles.map { it.second }.joinToString("\n")
            val translatedText = translations.joinToString("\n")
            contextHistory.addLast(Pair(sourceText, translatedText))
            while (contextHistory.size > currentContextMaxCount) {
                contextHistory.removeFirst()
            }
            LogCollector.d(TAG, "上下文已更新: ${contextHistory.size}/$currentContextMaxCount 轮")
        }

        // 输出翻译结果
        for (i in translations.indices) {
            val (bubble, original) = bubbles[i]
            val translated = translations[i]
            LogCollector.d(TAG, "翻译结果[$i]: orig='$original' → trans='$translated'")
        }

        bubbles.mapIndexed { index, (bubble, originalText) ->
            if (kotlin.math.abs(bubble.angle) > 0.5f) {
                LogCollector.d(TAG, "TranslatedBubble[$index]: angle=${bubble.angle}, cx=${bubble.centerX}, cy=${bubble.centerY}, text='${originalText.take(15)}'")
            }
            TranslatedBubble(
                rect = bubble.rect,
                originalText = originalText,
                translatedText = translations.getOrElse(index) { originalText },
                backgroundColor = Color.TRANSPARENT,
                fontSize = bubble.fontSize,
                direction = bubble.direction,
                angle = bubble.angle,
                centerX = bubble.centerX,
                centerY = bubble.centerY
            )
        }
    }

    /**
     * 解析JSON格式的翻译结果
     * 支持格式:
     *   1. {"translations": ["译文1", "译文2"]}
     *   2. ["译文1", "译文2"]
     *   3. [{"translations": ["译文1"]}, ...] (模型可能返回的混合格式)
     */
    private fun parseJsonTranslations(text: String, expectedCount: Int): List<String> {
        return try {
            val results = mutableListOf<String>()

            // 尝试解析为 JSON 对象 {"translations": [...]}
            try {
                val jsonObject = org.json.JSONObject(text)
                val translations = jsonObject.getJSONArray("translations")
                for (i in 0 until translations.length().coerceAtMost(expectedCount)) {
                    results.add(translations.getString(i))
                }
            } catch (_: Exception) {
                // 尝试解析为 JSON 数组
                val jsonArray = org.json.JSONArray(text)
                for (i in 0 until jsonArray.length().coerceAtMost(expectedCount)) {
                    val item = jsonArray.get(i)
                    when (item) {
                        is String -> results.add(item)
                        is org.json.JSONObject -> {
                            // 处理 {"translations": ["译文"]} 格式的数组元素
                            if (item.has("translations")) {
                                val arr = item.getJSONArray("translations")
                                if (arr.length() > 0) results.add(arr.getString(0))
                            }
                        }
                        // 跳过数字等其他类型（如 [2], [3]）
                    }
                }
            }

            // 补齐不足的部分
            while (results.size < expectedCount) {
                results.add("")
            }
            results.take(expectedCount)
        } catch (e: Exception) {
            LogCollector.e(TAG, "Failed to parse JSON translations: ${text.take(200)}", e)
            List(expectedCount) { "" }
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
    ): List<TranslatedBubble> = coroutineScope {
        LogCollector.d(TAG, "translateBubblesConcurrent: ${bubbles.size} bubbles, concurrent")

        val deferreds = bubbles.map { (bubble, combinedText) ->
            async(Dispatchers.IO) {
                LogCollector.d(TAG, "translateBubblesConcurrent: translating '$combinedText'")

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
                            LogCollector.d(TAG, "translateBubblesConcurrent: SUCCESS for '$combinedText'")
                            successResult = TranslatedBubble(
                                rect = bubble.rect,
                                originalText = combinedText,
                                translatedText = result.translatedText,
                                backgroundColor = Color.TRANSPARENT,
                                fontSize = bubble.fontSize,
                                direction = bubble.direction,
                                angle = bubble.angle,
                                centerX = bubble.centerX,
                                centerY = bubble.centerY
                            )
                        }
                        is TranslationResult.Error -> {
                            errorMsg = result.error.message ?: "Unknown error"
                            LogCollector.e(TAG, "translateBubblesConcurrent: ERROR: $errorMsg")
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

                Pair(successResult, errorMsg)
            }
        }

        val allResults = deferreds.awaitAll()
        val results = mutableListOf<TranslatedBubble>()
        val errors = mutableListOf<String>()

        for ((successResult, errorMsg) in allResults) {
            if (successResult != null) {
                results.add(successResult)
            } else if (errorMsg != null) {
                errors.add(errorMsg)
            }
        }

        LogCollector.d(TAG, "translateBubblesConcurrent: ${results.size} successful out of ${bubbles.size}")
        if (results.isEmpty() && bubbles.isNotEmpty()) {
            val errorDetail = errors.distinct().joinToString("; ")
            throw RuntimeException("All bubbles failed to translate: $errorDetail")
        }
        results
    }

    // ---------- Result overlay ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun showResultOverlay(bitmap: Bitmap, fromCache: Boolean = false) {
        if (isResultShowing) {
            dismissResultOverlay()
        }

        if (fromCache) {
            showCacheOverlay(bitmap)
            return
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
            // 全屏模式：获取屏幕真实像素尺寸，overlay 精确覆盖全屏
            val screenSize = getScreenSize()
            val screenW = screenSize.width
            val screenH = screenSize.height
            LogCollector.d(TAG, "showResultOverlay: bitmap=${bitmap.width}x${bitmap.height}, screen=${screenW}x${screenH}")
            val params = WindowManager.LayoutParams().apply {
                type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                format = PixelFormat.RGBA_8888
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                width = screenW
                height = screenH
                gravity = Gravity.START or Gravity.TOP
                x = 0
                y = 0
            }
            resultOverlayView.scaleType = ImageView.ScaleType.FIT_XY
            windowManager.addView(resultOverlayView, params)
        }
        isResultShowing = true

        bringFloatingBallToFront()
    }

    private fun dismissResultOverlay() {
        if (cacheOverlayContainer != null) {
            dismissCacheOverlay()
            return
        }
        dismissDebugInfoPanel()
        if (isResultShowing) {
            try {
                // 先清除引用再回收，避免 Choreographer 待处理帧使用已回收的 bitmap
                val oldBitmap = (resultOverlayView.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                resultOverlayView.setImageBitmap(null)
                oldBitmap?.recycle()
                resultOverlayView.setOnTouchListener(null)
                if (resultOverlayView.isAttachedToWindow) {
                    windowManager.removeView(resultOverlayView)
                }
            } catch (e: Exception) {
                LogCollector.e(TAG, "Error dismissing overlay", e)
            }
            isResultShowing = false

            // 重置自动翻译状态，但不清楚文本缓存（文本缓存跨页面有效）
            if (isAutoTranslating) {
                detectState = DetectState.IDLE
                stableCount = 0
                scheduleNextDetection(0L)
            }
        }
    }

    /**
     * 显示缓存结果 overlay — 带"缓存"标签和刷新按钮
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun showCacheOverlay(bitmap: Bitmap) {
        dismissProgressOverlay()

        val screenSize = getScreenSize()
        val screenW = screenSize.width
        val screenH = screenSize.height

        val container = android.widget.FrameLayout(this).apply {
            setBackgroundColor(Color.argb(180, 0, 0, 0))
        }

        // 结果图片
        val imageView = ImageView(this).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.FIT_XY
        }
        container.addView(imageView, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // "⚡ 缓存" 标签（左上角）
        val cacheTag = android.widget.TextView(this).apply {
            text = "⚡ 缓存"
            setTextColor(Color.WHITE)
            textSize = 14f
            setBackgroundColor(Color.argb(180, 255, 152, 0))
            setPadding(24, 12, 24, 12)
        }
        container.addView(cacheTag, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            setMargins(24, 24, 0, 0)
        })

        // 刷新按钮（右上角）
        val refreshBtn = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_rotate)
            setBackgroundColor(Color.argb(180, 0, 0, 0))
            setPadding(20, 20, 20, 20)
            setOnClickListener {
                dismissCacheOverlay()
                forceRefresh = true
                lastTranslatedHash = 0L
                triggerTranslation()
            }
        }
        container.addView(refreshBtn, android.widget.FrameLayout.LayoutParams(
            120, 120
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            setMargins(0, 24, 24, 0)
        })

        // 点击其他区域关闭
        container.setOnTouchListener { _, event ->
            // 检查是否点击在刷新按钮区域
            val refreshRight = screenW - 24
            val refreshLeft = refreshRight - 120
            val refreshTop = 24
            val refreshBottom = refreshTop + 120
            val touchX = event.x.toInt()
            val touchY = event.y.toInt()
            if (touchX in refreshLeft..refreshRight && touchY in refreshTop..refreshBottom) {
                false  // 让刷新按钮处理
            } else {
                if (event.action == MotionEvent.ACTION_UP) {
                    dismissCacheOverlay()
                }
                true
            }
        }

        val params = if (cropRect != null) {
            val crop = cropRect!!
            WindowManager.LayoutParams().apply {
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
        } else {
            WindowManager.LayoutParams().apply {
                type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                format = PixelFormat.RGBA_8888
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                width = screenW
                height = screenH
                gravity = Gravity.START or Gravity.TOP
                x = 0
                y = 0
            }
        }

        windowManager.addView(container, params)
        cacheOverlayContainer = container
        isResultShowing = true

        bringFloatingBallToFront()
    }

    /**
     * 关闭缓存 overlay
     */
    private fun dismissCacheOverlay() {
        cacheOverlayContainer?.let { container ->
            try {
                // 回收 bitmap
                val imageView = container.getChildAt(0) as? ImageView
                val bitmap = imageView?.drawable?.let { drawable ->
                    if (drawable is android.graphics.drawable.BitmapDrawable) drawable.bitmap else null
                }
                windowManager.removeView(container)
                imageView?.setImageDrawable(null)
                // 延迟回收，等 View 绘制完成
                container.post { bitmap?.recycle() }
            } catch (e: Exception) {
                LogCollector.e(TAG, "dismissCacheOverlay: 错误", e)
            }
            cacheOverlayContainer = null
            isResultShowing = false

            // 重置自动翻译：清除区域缓存，立刻恢复检测
            if (isAutoTranslating) {
                clearRegionCache()
                detectState = DetectState.IDLE
                stableCount = 0
                scheduleNextDetection(0L)
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
            if (group.members.size < 2) continue  // size=1 表示没有合并，跳过

            // TextRegionGroup 已有合并后的 rect
            val rect = group.rect

            // 计算原始索引用于日志对应
            val rawIndices = group.members.mapNotNull { tr -> debugResult.rawBoxes.indexOf(tr.quad).takeIf { it >= 0 } }

            fillPaint.color = android.graphics.Color.argb(80, 0, 0, 255)
            canvas.drawRect(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat(), fillPaint)
            val bluePaint = android.graphics.Paint().apply {
                color = android.graphics.Color.BLUE
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 6f
            }
            canvas.drawRect(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat(), bluePaint)
            // 标签不重叠：Y 坐标随 groupIdx 递增偏移
            val labelY = rect.top.toFloat() + 20f + groupIdx * 25f
            val label = "M[$groupIdx]:${group.members.size}boxes"
            canvas.drawText(label, rect.left.toFloat() + 4, labelY, textPaint)
        }

        return result
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showCTDDebugResultOverlay(debugBitmap: Bitmap, debugResult: CTDDebugResult) {
        if (isResultShowing) {
            dismissResultOverlay()
        }

        // 应用框选外区域遮罩
        val displayBitmap = applyCropDimmingIfNeeded(debugBitmap)

        // 创建容器 FrameLayout
        val container = android.widget.FrameLayout(this)
        val imageView = android.widget.ImageView(this).apply {
            setImageBitmap(displayBitmap)
            scaleType = android.widget.ImageView.ScaleType.FIT_XY
        }
        container.addView(imageView, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // 添加底部 info panel 到容器中
        val mergedCount = debugResult.mergedGroups.count { it.members.size > 1 }
        val infoLines = listOf(
            "🟢 绿色 = 原始框（${debugResult.rawBoxes.size}）",
            "🔵 蓝色 = 合并（${debugResult.mergedGroups.size}组，$mergedCount 个实际合并）",
            "🔴 红色 = 丢弃（${debugResult.discardedBoxes.size}）"
        )
        val infoPanel = createInfoPanelView(infoLines)
        val infoPanelParams = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = android.view.Gravity.BOTTOM
        }
        container.addView(infoPanel, infoPanelParams)
        debugInfoPanelContentView = infoPanel  // 记录 infoPanel 引用，折叠时只隐藏它

        // 添加右下角展开/折叠按钮
        val toggleButton = createToggleButton()
        val toggleParams = android.widget.FrameLayout.LayoutParams(
            android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, 48f, resources.displayMetrics).toInt(),
            android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, 48f, resources.displayMetrics).toInt()
        ).apply {
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
            val margin = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, 16f, resources.displayMetrics).toInt()
            marginEnd = margin
            bottomMargin = margin
        }
        container.addView(toggleButton, toggleParams)

        // imageView 点击关闭全部（toggle 按钮在更高层级会优先接收点击）
        imageView.isClickable = true
        imageView.setOnClickListener {
            dismissDebugInfoPanel()
            dismissResultOverlay()
        }

        // 始终全屏显示
        val screenSize = getScreenSize()
        val params = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            format = PixelFormat.RGBA_8888
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            width = screenSize.width
            height = screenSize.height
            gravity = Gravity.START or Gravity.TOP
            x = 0; y = 0
        }
        windowManager.addView(container, params)
        debugInfoPanelView = container
        debugInfoPanelAdded = true
        debugInfoPanelCollapsed = false
        debugToggleButton = toggleButton
        debugToggleButtonAdded = true
        isResultShowing = true

        bringFloatingBallToFront()
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

        return result
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showRTDetrV2DebugResultOverlay(debugBitmap: Bitmap, debugResult: RTDetrV2DebugResult) {
        if (isResultShowing) {
            dismissResultOverlay()
        }

        // 应用框选外区域遮罩
        val displayBitmap = applyCropDimmingIfNeeded(debugBitmap)

        // 创建容器 FrameLayout
        val container = android.widget.FrameLayout(this)
        val imageView = android.widget.ImageView(this).apply {
            setImageBitmap(displayBitmap)
            scaleType = android.widget.ImageView.ScaleType.FIT_XY
        }
        container.addView(imageView, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // 添加底部 info panel 到容器中
        val infoLines = listOf(
            "🟢 绿色 = text_bubble（${debugResult.textBubbles.size}）",
            "🔵 蓝色 = text_free（${debugResult.textFree.size}）${if (config.keepTextFree) "保留" else "丢弃"}",
            "🔴 红色 = bubble（${debugResult.emptyBubbles.size}）压缩15%",
            "🟡 黄色 = 最终提交OCR（${debugResult.finalRegions.size}）"
        )
        val infoPanel = createInfoPanelView(infoLines)
        val infoPanelParams = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = android.view.Gravity.BOTTOM
        }
        container.addView(infoPanel, infoPanelParams)
        debugInfoPanelContentView = infoPanel  // 记录 infoPanel 引用，折叠时只隐藏它

        // 添加右下角展开/折叠按钮
        val toggleButton = createToggleButton()
        val toggleParams = android.widget.FrameLayout.LayoutParams(
            android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, 48f, resources.displayMetrics).toInt(),
            android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, 48f, resources.displayMetrics).toInt()
        ).apply {
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
            val margin = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, 16f, resources.displayMetrics).toInt()
            marginEnd = margin
            bottomMargin = margin
        }
        container.addView(toggleButton, toggleParams)

        // imageView 点击关闭全部（toggle 按钮在更高层级会优先接收点击）
        imageView.isClickable = true
        imageView.setOnClickListener {
            dismissDebugInfoPanel()
            dismissResultOverlay()
        }

        // 始终全屏显示
        val screenSize = getScreenSize()
        val params = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            format = PixelFormat.RGBA_8888
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            width = screenSize.width
            height = screenSize.height
            gravity = Gravity.START or Gravity.TOP
            x = 0; y = 0
        }
        windowManager.addView(container, params)
        debugInfoPanelView = container
        debugInfoPanelAdded = true
        debugInfoPanelCollapsed = false
        debugToggleButton = toggleButton
        debugToggleButtonAdded = true
        isResultShowing = true

        bringFloatingBallToFront()
    }

    private fun showProgressOverlay(text: String = getString(R.string.manga_translating)) {
        LogCollector.d(TAG, "showProgressOverlay called: $text")
        statusOverlay.showImmediate(text, autoDismiss = false)
    }

    private fun dismissProgressOverlay() {
        statusOverlay.dismiss()
    }

    /**
     * 显示调试详情面板：固定在屏幕底部的半透明信息面板。
     * 与 debug bitmap 分离，不随框选区域移动。
     * 右下角有展开/折叠按钮。
     * @param maxHeight 最大高度（px），0 表示不限制（WRAP_CONTENT）
     */
    @SuppressLint("SetTextI18n")
    private fun showDebugInfoPanel(lines: List<String>, scrollable: Boolean = false, maxHeight: Int = 0) {
        dismissDebugInfoPanel()
        val tv = android.widget.TextView(this).apply {
            text = lines.joinToString("\n")
            setTextColor(android.graphics.Color.WHITE)
            textSize = if (scrollable) 11f else 13f
            setPadding(24, 16, 24, 16)
            setBackgroundColor(android.graphics.Color.argb(200, 0, 0, 0))
        }

        val contentView: android.view.View
        val layoutParamsHeight: Int

        if (scrollable) {
            val scrollView = android.widget.ScrollView(this).apply {
                addView(tv)
            }
            contentView = scrollView
            layoutParamsHeight = if (maxHeight > 0) maxHeight
                else android.util.TypedValue.applyDimension(
                    android.util.TypedValue.COMPLEX_UNIT_DIP, 400f, resources.displayMetrics
                ).toInt()
        } else {
            contentView = tv
            layoutParamsHeight = WindowManager.LayoutParams.WRAP_CONTENT
        }

        val params = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            format = PixelFormat.RGBA_8888
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = layoutParamsHeight
            gravity = Gravity.BOTTOM or Gravity.START
            x = 0; y = 0
        }
        windowManager.addView(contentView, params)
        debugInfoPanelView = contentView
        debugInfoPanelAdded = true
        debugInfoPanelCollapsed = false

        // 添加右下角展开/折叠按钮
        addDebugToggleButton()
    }

    /** 添加右下角展开/折叠按钮 */
    @SuppressLint("SetTextI18n")
    private fun addDebugToggleButton() {
        removeDebugToggleButton()
        val buttonSize = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_DIP, 48f, resources.displayMetrics
        ).toInt()
        val margin = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_DIP, 16f, resources.displayMetrics
        ).toInt()

        val button = android.widget.TextView(this).apply {
            text = "▼"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 18f
            gravity = android.view.Gravity.CENTER
            setBackgroundColor(android.graphics.Color.argb(180, 0, 0, 0))
            setOnClickListener {
                if (debugInfoPanelCollapsed) {
                    expandDebugInfoPanel()
                } else {
                    collapseDebugInfoPanel()
                }
            }
        }

        val params = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            format = PixelFormat.RGBA_8888
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            width = buttonSize
            height = buttonSize
            gravity = Gravity.BOTTOM or Gravity.END
            x = margin
            y = margin
        }
        windowManager.addView(button, params)
        debugToggleButton = button
        debugToggleButtonAdded = true
    }

    /** 移除展开/折叠按钮 */
    private fun removeDebugToggleButton() {
        if (debugToggleButtonAdded) {
            try {
                windowManager.removeView(debugToggleButton)
            } catch (e: Exception) {
                LogCollector.w(TAG, "removeDebugToggleButton: ${e.message}")
            }
            debugToggleButton = null
            debugToggleButtonAdded = false
        }
    }

    /** 折叠调试详情面板 */
    private fun collapseDebugInfoPanel() {
        if (debugInfoPanelAdded && !debugInfoPanelCollapsed && debugInfoPanelContentView != null) {
            debugInfoPanelContentView!!.visibility = android.view.View.GONE
            debugInfoPanelCollapsed = true
            debugToggleButton?.text = "▲"
        }
    }

    /** 展开已折叠的调试详情面板 */
    private fun expandDebugInfoPanel() {
        if (debugInfoPanelAdded && debugInfoPanelCollapsed && debugInfoPanelContentView != null) {
            debugInfoPanelContentView!!.visibility = android.view.View.VISIBLE
            debugInfoPanelCollapsed = false
            debugToggleButton?.text = "▼"
        }
    }

    /**
     * 移除调试详情面板。
     */
    private fun dismissDebugInfoPanel() {
        if (debugInfoPanelAdded) {
            try {
                windowManager.removeView(debugInfoPanelView)
            } catch (e: Exception) {
                LogCollector.w(TAG, "dismissDebugInfoPanel: ${e.message}")
            }
            debugInfoPanelView = null
            debugInfoPanelContentView = null
            debugInfoPanelAdded = false
            debugInfoPanelCollapsed = false
        }
        removeDebugToggleButton()
    }

    /**
     * 为 debug 图片添加框选外区域遮罩。
     * 框选模式：创建全屏 bitmap，框选区域显示 debug 图片，框选外区域添加半透明黑色遮罩。
     * 全屏模式：直接返回原 debug bitmap。
     */
    private fun applyCropDimmingIfNeeded(debugBitmap: Bitmap): Bitmap {
        if (cropRect == null) return debugBitmap

        val screenSize = getScreenSize()
        val screenW = screenSize.width
        val screenH = screenSize.height

        // 创建全屏 bitmap
        val fullBitmap = Bitmap.createBitmap(screenW, screenH, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(fullBitmap)

        // 绘制半透明黑色背景（全屏）
        val dimPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.argb(150, 0, 0, 0)
        }
        canvas.drawRect(0f, 0f, screenW.toFloat(), screenH.toFloat(), dimPaint)

        // 将 debug bitmap 绘制到框选区域
        val crop = cropRect!!
        val srcRect = android.graphics.Rect(0, 0, debugBitmap.width, debugBitmap.height)
        val dstRect = android.graphics.Rect(
            crop.left.toInt(),
            crop.top.toInt(),
            crop.right.toInt(),
            crop.bottom.toInt()
        )
        canvas.drawBitmap(debugBitmap, srcRect, dstRect, null)

        return fullBitmap
    }

    /**
     * 创建 PP-OCRv5 参数调节滑块面板（3 检测滑块 + 4 合并滑块 + 大框过滤开关 + 恢复默认按钮）
     */
    @SuppressLint("SetTextI18n")
    private fun createPPOcrParamSlidersView(): android.view.View {
        val dp = resources.displayMetrics.density

        // 默认值
        val DEF_BOX = 0.3f; val DEF_UNCLIP = 1.6f; val DEF_TEXT = 0.5f
        val DEF_LARGE_ENABLED = false; val DEF_LARGE_RATIO = 0.6f

        // 滑块范围映射
        fun boxToSeek(v: Float) = ((v - 0.01f) / 0.49f * 100).toInt().coerceIn(0, 100)
        fun seekToBox(v: Int) = 0.01f + v / 100f * 0.49f
        fun unclipToSeek(v: Float) = ((v - 1.0f) / 2.0f * 100).toInt().coerceIn(0, 100)
        fun seekToUnclip(v: Int) = 1.0f + v / 100f * 2.0f
        fun textToSeek(v: Float) = ((v - 0.1f) / 0.8f * 100).toInt().coerceIn(0, 100)
        fun seekToText(v: Int) = 0.1f + v / 100f * 0.8f
        fun ratioToSeek(v: Float) = ((v - 0.3f) / 0.5f * 100).toInt().coerceIn(0, 100)
        fun seekToRatio(v: Int) = 0.3f + v / 100f * 0.5f

        // 外层垂直容器
        val outerPanel = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((8 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt())
            setBackgroundColor(android.graphics.Color.argb(200, 30, 30, 30))
        }

        // ── 第一行：3 个滑块 ──
        val row1 = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // 存储引用以便恢复默认时更新
        data class SliderRef(
            val label: android.widget.TextView,
            val seekBar: android.widget.SeekBar,
            val labelText: String,
            val formatValue: (Int) -> String,
            val save: (Int) -> Unit
        )
        val sliderRefs = mutableListOf<SliderRef>()

        val sliders = listOf(
            Triple("检测置信度", boxToSeek(prefs.getFloat("ppocr_det_box_thresh", DEF_BOX)), { v: Int -> String.format("%.2f", seekToBox(v)) }),
            Triple("扩展比例", unclipToSeek(prefs.getFloat("ppocr_det_unclip_ratio", DEF_UNCLIP)), { v: Int -> String.format("%.1f", seekToUnclip(v)) }),
            Triple("识别置信度", textToSeek(prefs.getFloat("ppocr_text_score_thresh", DEF_TEXT)), { v: Int -> String.format("%.2f", seekToText(v)) })
        )
        val saveFns: List<(Int) -> Unit> = listOf(
            { v -> prefs.setFloat("ppocr_det_box_thresh", seekToBox(v)) },
            { v -> prefs.setFloat("ppocr_det_unclip_ratio", seekToUnclip(v)) },
            { v -> prefs.setFloat("ppocr_text_score_thresh", seekToText(v)) }
        )

        for ((idx, triple) in sliders.withIndex()) {
            val (name, seekInit, fmt) = triple
            val group = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val label = android.widget.TextView(this).apply {
                text = "$name\n${fmt(seekInit)}"
                setTextColor(android.graphics.Color.WHITE)
                textSize = 11f
                gravity = android.view.Gravity.CENTER
                maxLines = 2
            }

            val seekBar = android.widget.SeekBar(this).apply {
                max = 100
                progress = seekInit
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (24 * dp).toInt()
                )
                setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser) {
                            label.text = "$name\n${fmt(progress)}"
                            saveFns[idx](progress)
                            PPOcrV5Engine.refreshParams(this@MangaFloatingService)
                        }
                    }
                    override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                    override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
                })
            }

            sliderRefs.add(SliderRef(label, seekBar, name, fmt, saveFns[idx]))
            group.addView(label)
            group.addView(seekBar)
            row1.addView(group)
        }
        outerPanel.addView(row1)

        // ── 第二行：合并参数滑块（4 个） ──
        val rowMerge = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4 * dp).toInt() }
        }

        val DEF_GAP = MergeParams.DISCARD_CONNECTION_GAP_DEFAULT

        fun mGapToSeek(v: Float) = ((v - 0.5f) / 4.5f * 100).toInt().coerceIn(0, 100)
        fun mSeekToGap(v: Int) = 0.5f + v / 100f * 4.5f

        data class MergeSliderRef(
            val label: android.widget.TextView,
            val seekBar: android.widget.SeekBar,
            val labelText: String,
            val formatValue: (Int) -> String,
            val save: (Int) -> Unit
        )
        val mergeSliderRefs = mutableListOf<MergeSliderRef>()

        // 唯一可调参数：距离门控（manga hardcoded 2.0）
        val mergeSliders = listOf(
            Triple("距离门控", mGapToSeek(prefs.getFloat("merge_discard_gap", DEF_GAP)), { v: Int -> String.format("%.1f", mSeekToGap(v)) })
        )
        val mergeSaveFns: List<(Int) -> Unit> = listOf(
            { v -> prefs.setFloat("merge_discard_gap", mSeekToGap(v)); TextRegionMerger.refreshParams(this) }
        )

        for ((idx, triple) in mergeSliders.withIndex()) {
            val (name, seekInit, fmt) = triple
            val group = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val label = android.widget.TextView(this).apply {
                text = "$name\n${fmt(seekInit)}"
                setTextColor(android.graphics.Color.WHITE)
                textSize = 11f
                gravity = android.view.Gravity.CENTER
                maxLines = 2
            }

            val seekBar = android.widget.SeekBar(this).apply {
                max = 100
                progress = seekInit
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (24 * dp).toInt()
                )
                setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser) {
                            label.text = "$name\n${fmt(progress)}"
                            mergeSaveFns[idx](progress)
                        }
                    }
                    override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                    override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
                })
            }

            mergeSliderRefs.add(MergeSliderRef(label, seekBar, name, fmt, mergeSaveFns[idx]))
            group.addView(label)
            group.addView(seekBar)
            rowMerge.addView(group)
        }
        outerPanel.addView(rowMerge)

        // ── 第三行：大框过滤开关 + 比例滑块 ──
        val row2 = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4 * dp).toInt() }
        }

        val toggleLabel = android.widget.TextView(this).apply {
            text = "大框过滤"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 11f
            setPadding(0, 0, (4 * dp).toInt(), 0)
        }
        val largeBoxToggle = android.widget.Switch(this).apply {
            isChecked = prefs.getBoolean("ppocr_large_box_enabled", DEF_LARGE_ENABLED)
            textSize = 11f
            setTextColor(android.graphics.Color.WHITE)
            setOnCheckedChangeListener { _, isChecked ->
                prefs.setBoolean("ppocr_large_box_enabled", isChecked)
                PPOcrV5Engine.refreshParams(this@MangaFloatingService)
            }
        }
        row2.addView(toggleLabel)
        row2.addView(largeBoxToggle)

        val ratioLabel = android.widget.TextView(this).apply {
            val cur = prefs.getFloat("ppocr_large_box_ratio", DEF_LARGE_RATIO)
            text = "丢弃比例 ${String.format("%.0f%%", cur * 100)}"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 11f
            gravity = android.view.Gravity.CENTER
            setPadding((8 * dp).toInt(), 0, (4 * dp).toInt(), 0)
        }
        val ratioSeekBar = android.widget.SeekBar(this).apply {
            max = 100
            progress = ratioToSeek(prefs.getFloat("ppocr_large_box_ratio", DEF_LARGE_RATIO))
            layoutParams = android.widget.LinearLayout.LayoutParams(0, (24 * dp).toInt(), 1f)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val ratio = seekToRatio(progress)
                        ratioLabel.text = "丢弃比例 ${String.format("%.0f%%", ratio * 100)}"
                        prefs.setFloat("ppocr_large_box_ratio", ratio)
                        PPOcrV5Engine.refreshParams(this@MangaFloatingService)
                    }
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
            })
        }
        row2.addView(ratioLabel)
        row2.addView(ratioSeekBar)
        outerPanel.addView(row2)

        // ── 第三行：恢复默认按钮 ──
        val row3 = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4 * dp).toInt() }
        }
        val resetBtn = android.widget.TextView(this).apply {
            text = "恢复默认"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 12f
            setPadding((16 * dp).toInt(), (4 * dp).toInt(), (16 * dp).toInt(), (4 * dp).toInt())
            setBackgroundColor(android.graphics.Color.argb(150, 100, 100, 100))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                // 重置 SharedPreferences
                prefs.setFloat("ppocr_det_box_thresh", DEF_BOX)
                prefs.setFloat("ppocr_det_unclip_ratio", DEF_UNCLIP)
                prefs.setFloat("ppocr_text_score_thresh", DEF_TEXT)
                prefs.setBoolean("ppocr_large_box_enabled", DEF_LARGE_ENABLED)
                prefs.setFloat("ppocr_large_box_ratio", DEF_LARGE_RATIO)
                PPOcrV5Engine.refreshParams(this@MangaFloatingService)

                // 更新 UI
                sliderRefs[0].apply { seekBar.progress = boxToSeek(DEF_BOX); label.text = "$labelText\n${formatValue(seekBar.progress)}" }
                sliderRefs[1].apply { seekBar.progress = unclipToSeek(DEF_UNCLIP); label.text = "$labelText\n${formatValue(seekBar.progress)}" }
                sliderRefs[2].apply { seekBar.progress = textToSeek(DEF_TEXT); label.text = "$labelText\n${formatValue(seekBar.progress)}" }
                largeBoxToggle.isChecked = DEF_LARGE_ENABLED
                ratioSeekBar.progress = ratioToSeek(DEF_LARGE_RATIO)
                ratioLabel.text = "丢弃比例 ${String.format("%.0f%%", DEF_LARGE_RATIO * 100)}"

                // 重置合并参数（仅距离门控 1 个滑块）
                TextRegionMerger.resetParams(this@MangaFloatingService)
                mergeSliderRefs[0].apply { seekBar.progress = mGapToSeek(DEF_GAP); label.text = "$labelText\n${formatValue(seekBar.progress)}" }
            }
        }
        row3.addView(resetBtn)
        outerPanel.addView(row3)

        return outerPanel
    }

    /**
     * 创建 info panel 视图（用于嵌入到 debug 图片窗口中）
     */
    @SuppressLint("SetTextI18n")
    private fun createInfoPanelView(lines: List<String>, scrollable: Boolean = false, maxHeight: Int = 0): android.view.View {
        val tv = android.widget.TextView(this).apply {
            text = lines.joinToString("\n")
            setTextColor(android.graphics.Color.WHITE)
            textSize = if (scrollable) 11f else 13f
            setPadding(24, 16, 24, 16)
            setBackgroundColor(android.graphics.Color.argb(200, 0, 0, 0))
        }

        return if (scrollable) {
            val limit = if (maxHeight > 0) maxHeight else (getScreenSize().height / 2)
            MaxHeightScrollView(this, limit).apply {
                addView(tv)
                isVerticalScrollBarEnabled = true
            }
        } else {
            tv
        }
    }

    /**
     * 创建展开/折叠按钮（用于嵌入到 debug 图片窗口中）
     */
    @SuppressLint("SetTextI18n")
    private fun createToggleButton(onToggle: (() -> Unit)? = null): android.widget.TextView {
        return android.widget.TextView(this).apply {
            text = "▼"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 18f
            gravity = android.view.Gravity.CENTER
            setBackgroundColor(android.graphics.Color.argb(180, 0, 0, 0))
            setOnClickListener {
                onToggle?.invoke() ?: run {
                    if (debugInfoPanelCollapsed) {
                        expandDebugInfoPanel()
                    } else {
                        collapseDebugInfoPanel()
                    }
                }
            }
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

    /** 将悬浮球重新添加到窗口栈顶，确保不被其他 overlay 遮挡 */
    private fun bringFloatingBallToFront() {
        if (isViewAdded(floatingBallView)) {
            windowManager.removeView(floatingBallView)
            windowManager.addView(floatingBallView, floatingBallParams)
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
        dismissCacheOverlay()
        dismissDebugInfoPanel()
        dismissResultOverlay()
        dismissProgressOverlay()
        dismissToastOverlay()
        handler.removeCallbacks(longPressRunnable)
        autoTranslateHandler.removeCallbacksAndMessages(null)
        // Remove crop view if active
        if (isCropActive) {
            try {
                windowManager.removeView(cropView)
            } catch (e: Exception) { /* ignore */ }
            isCropActive = false
        }
    }

    /**
     * 显示提示消息
     * @param message 消息内容
     * @param immediate true=覆盖显示（状态进度、模型切换），false=队列显示（初始化、启停提示）
     */
    private fun showToast(message: String, immediate: Boolean = false) {
        if (immediate) {
            statusOverlay.showImmediate(message)
        } else {
            statusOverlay.show(message)
        }
    }

    private fun dismissToastOverlay() {
        statusOverlay.dismiss()
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
        if (isResultShowing) {
            dismissResultOverlay()
        }

        // 应用框选外区域遮罩
        val displayBitmap = applyCropDimmingIfNeeded(debugBitmap)

        // 创建容器 FrameLayout
        val container = android.widget.FrameLayout(this)
        val imageView = android.widget.ImageView(this).apply {
            setImageBitmap(displayBitmap)
            scaleType = android.widget.ImageView.ScaleType.FIT_XY
        }
        container.addView(imageView, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // 添加底部 info panel 到容器中
        val infoLines = buildList {
            add("ML Kit 调试模式 | 块: ${result.textBlocks.size}  行: ${result.totalLines}  元素: ${result.totalElements} | 语言: ${result.detectedLanguage ?: "未知"}")
            add("绿=块  黄=行  红=元素")
            add("")
            // 只显示每块的文字摘要，不显示坐标和子元素
            for ((i, block) in result.textBlocks.withIndex()) {
                val text = block.blockText.take(30).replace("\n", " ")
                add("B${i}: \"$text\" ${block.language ?: ""}")
            }
        }
        val infoPanel = createInfoPanelView(infoLines, scrollable = true)
        val infoPanelParams = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = android.view.Gravity.BOTTOM
        }
        container.addView(infoPanel, infoPanelParams)
        debugInfoPanelContentView = infoPanel  // 记录 infoPanel 引用，折叠时只隐藏它

        // 添加右下角展开/折叠按钮
        val toggleButton = createToggleButton()
        val toggleParams = android.widget.FrameLayout.LayoutParams(
            android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, 48f, resources.displayMetrics).toInt(),
            android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, 48f, resources.displayMetrics).toInt()
        ).apply {
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
            val margin = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, 16f, resources.displayMetrics).toInt()
            marginEnd = margin
            bottomMargin = margin
        }
        container.addView(toggleButton, toggleParams)

        // imageView 点击关闭全部（toggle 按钮在更高层级会优先接收点击）
        imageView.isClickable = true
        imageView.setOnClickListener {
            dismissDebugInfoPanel()
            dismissResultOverlay()
        }

        // 始终全屏显示
        val screenSize = getScreenSize()
        val params = android.view.WindowManager.LayoutParams(
            screenSize.width,
            screenSize.height,
            android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = android.view.Gravity.START or android.view.Gravity.TOP
            x = 0; y = 0
        }

        try {
            windowManager.addView(container, params)
            debugInfoPanelView = container
            debugInfoPanelAdded = true
            debugInfoPanelCollapsed = false
            debugToggleButton = toggleButton
            debugToggleButtonAdded = true
            isResultShowing = true
        } catch (e: Exception) {
            LogCollector.e(TAG, "ML Kit Debug: 显示失败", e)
        }

        bringFloatingBallToFront()
    }

    /**
     * 从 OcrResult 构建 TextRegionMerger 输入并执行合并
     */
    private fun runTextLineMerge(ocrResult: OcrResult, bitmapWidth: Int, bitmapHeight: Int): List<TextRegionGroup> {
        val textLines = PPOcrV5Engine.ocrResultToTextLines(ocrResult, bitmapWidth, bitmapHeight)
        TextRegionMerger.refreshParams(this)
        return TextRegionMerger.merge(textLines.map { it.toTextRegion() })
    }

    /**
     * TextLine → TextRegion 转换
     */
    private fun PPOcrTextLine.toTextRegion(): TextRegion {
        return TextRegion(
            quad = QuadBox(quadPoints),
            text = text,
            score = score
        )
    }

    /**
     * 合并后内容过滤：丢弃无意义的合并结果。
     * 在 TextLineMerger.merge 之后调用，基于合并后的完整文本判断。
     * 返回 Pair(保留的区域, 丢弃的区域+原因)
     */
    private fun filterMergedRegions(regions: List<TextRegionGroup>): Pair<List<TextRegionGroup>, List<Pair<TextRegionGroup, String>>> {
        val kept = mutableListOf<TextRegionGroup>()
        val discarded = mutableListOf<Pair<TextRegionGroup, String>>()
        for (region in regions) {
            val text = region.texts.joinToString("").trim()
            val reason = when {
                text.isEmpty() -> "空白"
                text.length == 1 -> "单字符"
                text.all { !it.isLetterOrDigit() } -> "纯符号"
                text.length <= 2 && text.all { it.isDigit() } -> "短数字"
                else -> null
            }
            if (reason != null) {
                discarded.add(region to reason)
            } else {
                kept.add(region)
            }
        }
        return Pair(kept, discarded)
    }

    /**
     * PP-OCRv5 调试模式：渲染检测+识别+合并结果并显示
     */
    private fun showPPOcrV5DebugView(bitmap: Bitmap, ocrResult: OcrResult, mergedRegions: List<TextRegionGroup>, debugDet: PPOcrV5Engine.DebugDetResult? = null) {
        val debugBitmap = renderPPOcrV5DebugWithMerge(bitmap, ocrResult, mergedRegions, debugDet)
        showPPOcrV5DebugResultOverlay(debugBitmap, ocrResult, mergedRegions, debugDet)
    }

    /**
     * 渲染 PP-OCRv5 调试图：原始检测框 + 合并区域框 + 被丢弃选区
     */
    private fun renderPPOcrV5DebugWithMerge(
        bitmap: Bitmap,
        ocrResult: OcrResult,
        mergedRegions: List<TextRegionGroup>,
        debugDet: PPOcrV5Engine.DebugDetResult? = null
    ): Bitmap {
        val output = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = android.graphics.Canvas(output)

        // 原始检测框（绿色，细线）
        val rawPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.GREEN
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 2f
            isAntiAlias = true
        }

        // 合并区域框（青色，粗线）
        val mergedPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.CYAN
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 4f
            isAntiAlias = true
        }

        // 合并区域半透明填充
        val mergedFillPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.argb(30, 0, 255, 255)
            style = android.graphics.Paint.Style.FILL
        }

        // 文字标签画笔
        val labelPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 24f
            isAntiAlias = true
            setShadowLayer(3f, 1f, 1f, android.graphics.Color.BLACK)
        }

        // ① 绘制原始检测框（绿色）
        for (box in ocrResult.boxes) {
            canvas.drawLine(box[0], box[1], box[2], box[3], rawPaint)
            canvas.drawLine(box[2], box[3], box[4], box[5], rawPaint)
            canvas.drawLine(box[4], box[5], box[6], box[7], rawPaint)
            canvas.drawLine(box[6], box[7], box[0], box[1], rawPaint)
        }

        // ② 绘制合并区域框（青色）+ 标签
        for ((idx, region) in mergedRegions.withIndex()) {
            val r = region.rect
            val hasTilt = kotlin.math.abs(region.angle) > 0.5f

            canvas.save()
            if (hasTilt) {
                canvas.rotate(region.angle, region.center.x, region.center.y)
            }
            canvas.drawRect(r, mergedFillPaint)
            canvas.drawRect(r, mergedPaint)

            // 标签：序号 + 方向 + 文字数 + 倾斜角
            val dirLabel = if (region.direction == TextDirection.VERTICAL_RL) "V" else "H"
            val angleStr = if (hasTilt) " ∠${String.format("%.0f°", region.angle)}" else ""
            val label = "[$idx]$dirLabel ×${region.texts.size}$angleStr"
            canvas.drawText(label, r.left.toFloat(), r.top.toFloat() - 6f, labelPaint)
            canvas.restore()
        }

        // ③ 绘制被丢弃的选区（红色虚线）
        if (debugDet != null && debugDet.discardedBoxes.isNotEmpty()) {
            val discPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.RED
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 2f
                isAntiAlias = true
                pathEffect = android.graphics.DashPathEffect(floatArrayOf(8f, 4f), 0f)
            }
            val discLabelPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.RED
                textSize = 20f
                isAntiAlias = true
                setShadowLayer(3f, 1f, 1f, android.graphics.Color.BLACK)
            }
            for (i in debugDet.discardedBoxes.indices) {
                val box = debugDet.discardedBoxes[i]
                canvas.drawLine(box[0], box[1], box[2], box[3], discPaint)
                canvas.drawLine(box[2], box[3], box[4], box[5], discPaint)
                canvas.drawLine(box[4], box[5], box[6], box[7], discPaint)
                canvas.drawLine(box[6], box[7], box[0], box[1], discPaint)
                // 标签：分数 + 原因
                val score = debugDet.discardedScores.getOrElse(i) { 0f }
                val reason = debugDet.discardedReasons.getOrElse(i) { "" }
                val label = "✗${String.format("%.2f", score)} $reason"
                canvas.drawText(label, box[0], box[1] - 4f, discLabelPaint)
            }
        }

        // ④ 绘制被识别置信度丢弃的选区（橙色虚线）
        if (ocrResult.recDebug != null && ocrResult.recDebug.discardedBoxes.isNotEmpty()) {
            val recDiscPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.rgb(255, 165, 0) // 橙色
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 2f
                isAntiAlias = true
                pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f, 4f, 2f, 4f), 0f)
            }
            val recDiscFillPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.argb(40, 255, 165, 0)
                style = android.graphics.Paint.Style.FILL
            }
            val recDiscLabelPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.rgb(255, 165, 0)
                textSize = 18f
                isAntiAlias = true
                setShadowLayer(3f, 1f, 1f, android.graphics.Color.BLACK)
            }
            for (i in ocrResult.recDebug.discardedBoxes.indices) {
                val box = ocrResult.recDebug.discardedBoxes[i]
                // 半透明填充
                val path = android.graphics.Path().apply {
                    moveTo(box[0], box[1])
                    lineTo(box[2], box[3])
                    lineTo(box[4], box[5])
                    lineTo(box[6], box[7])
                    close()
                }
                canvas.drawPath(path, recDiscFillPaint)
                canvas.drawPath(path, recDiscPaint)
                // 标签：根据丢弃原因显示
                val reason = ocrResult.recDebug.discardedReasons.getOrElse(i) { "score" }
                val label = if (reason == "score") {
                    val score = ocrResult.recDebug.discardedScores.getOrElse(i) { 0f }
                    "✗${String.format("%.2f", score)}<${String.format("%.2f", prefs.getFloat("ppocr_text_score_thresh", 0.5f))}"
                } else {
                    "✗内容:$reason"
                }
                canvas.drawText(label, box[0], box[1] - 4f, recDiscLabelPaint)
            }
        }

        return output
    }

    private fun showPPOcrV5DebugResultOverlay(debugBitmap: Bitmap, ocrResult: OcrResult, mergedRegions: List<TextRegionGroup> = emptyList(), debugDet: PPOcrV5Engine.DebugDetResult? = null) {
        if (isResultShowing) {
            dismissResultOverlay()
        }

        // 应用框选外区域遮罩
        val displayBitmap = applyCropDimmingIfNeeded(debugBitmap)

        // 创建容器 FrameLayout
        val container = android.widget.FrameLayout(this)
        val imageView = android.widget.ImageView(this).apply {
            setImageBitmap(displayBitmap)
            scaleType = android.widget.ImageView.ScaleType.FIT_XY
        }
        container.addView(imageView, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // 添加底部 info panel 到容器中（包含参数滑块 + 调试信息）
        val infoLines = buildList {
            val discCount = debugDet?.discardedBoxes?.size ?: 0
            val recDebug = ocrResult.recDebug
            val scoreDisc = recDebug?.discardedReasons?.count { it == "score" } ?: 0
            val contentDisc = recDebug?.discardedReasons?.count { it != "score" } ?: 0
            val curBox = prefs.getFloat("ppocr_det_box_thresh", 0.3f)
            val curUnclip = prefs.getFloat("ppocr_det_unclip_ratio", 1.6f)
            val curText = prefs.getFloat("ppocr_text_score_thresh", 0.5f)
            add("PP-OCRv5 调试模式 | 检测: ${ocrResult.boxes.size}  检测丢弃: $discCount  识别丢弃: $scoreDisc  内容丢弃: $contentDisc  输出: ${ocrResult.texts.size}  合并: ${mergedRegions.size}区域")
            add("图例: 绿=检测框  青=合并区  红虚线=检测分数低  橙虚线=识别/内容丢弃")
            add("参数: box_thresh=${String.format("%.2f", curBox)}  unclip=${String.format("%.1f", curUnclip)}  text_score=${String.format("%.2f", curText)}")
            val curGap = prefs.getFloat("merge_discard_gap", MergeParams.DISCARD_CONNECTION_GAP_DEFAULT)
            add("合并参数（对齐 manga-image-translator）:")
            add("  距离门控 = ${String.format("%.1f", curGap)} (×字号, AABB距离超过则拒绝合并)")
            add("  其他参数: 字号比AA=2.0/Tilted=0.25  角度差Tilted=15°  长宽比=1.3")
            add("耗时: det=${String.format("%.2f", ocrResult.elapseList.getOrElse(0){0f})}s  " +
                "cls=${String.format("%.2f", ocrResult.elapseList.getOrElse(2){0f})}s  " +
                "rec=${String.format("%.2f", ocrResult.elapseList.getOrElse(3){0f})}s  " +
                "总=${String.format("%.2f", ocrResult.elapseList.getOrElse(4){0f})}s")
            add("━━━ 合并结果 ━━━")
            for ((idx, region) in mergedRegions.withIndex()) {
                val dirLabel = if (region.direction == TextDirection.VERTICAL_RL) "竖排" else "横排"
                val srcCount = region.texts.size
                val merged = region.texts.joinToString("｜")
                val r = region.rect
                val angleStr = if (kotlin.math.abs(region.angle) > 0.5f) " ∠${String.format("%.1f°", region.angle)}" else ""
                add("【$idx】$dirLabel ×$srcCount$angleStr [${r.left},${r.top},${r.right},${r.bottom}]")
                add("    $merged")
            }
            add("")
            add("━━━ 原始识别 ━━━")
            for (i in ocrResult.texts.indices) {
                val text = ocrResult.texts[i]
                val score = ocrResult.scores.getOrElse(i) { 0f }
                val box = ocrResult.boxes.getOrNull(i)
                val boxStr = if (box != null && box.size >= 8) {
                    "[${box[0].toInt()},${box[1].toInt()} → ${box[4].toInt()},${box[5].toInt()}]"
                } else ""
                // 计算每个 box 的倾斜角（与 ocrResultToTextLines 同样的算法）
                val angleStr = if (box != null && box.size >= 8) {
                    val topDx = box[2] - box[0]
                    val topDy = box[3] - box[1]
                    val ang = kotlin.math.atan2(topDy, topDx) * 180f / Math.PI.toFloat()
                    val finalAng = if (kotlin.math.abs(ang) <= 3f) 0f else ang
                    if (kotlin.math.abs(finalAng) > 0.5f) " ∠${String.format("%.1f°", finalAng)}" else ""
                } else ""
                add("[$i] ${String.format("%.2f", score)}$angleStr $boxStr \"$text\"")
            }
            if (debugDet != null && debugDet.discardedBoxes.isNotEmpty()) {
                add("")
                add("━━━ 被检测丢弃选区 (${debugDet.discardedBoxes.size}) ━━━")
                for (i in debugDet.discardedBoxes.indices) {
                    val box = debugDet.discardedBoxes[i]
                    val score = debugDet.discardedScores.getOrElse(i) { 0f }
                    val reason = debugDet.discardedReasons.getOrElse(i) { "" }
                    add("✗[$i] ${String.format("%.2f", score)} [${box[0].toInt()},${box[1].toInt()}→${box[4].toInt()},${box[5].toInt()}] $reason")
                }
            }
            // 识别置信度丢弃的选区
            val recDisc = ocrResult.recDebug
            if (recDisc != null && recDisc.discardedBoxes.isNotEmpty()) {
                add("")
                add("━━━ 被识别/内容丢弃选区 (${recDisc.discardedBoxes.size}) ━━━")
                for (i in recDisc.discardedBoxes.indices) {
                    val box = recDisc.discardedBoxes[i]
                    val score = recDisc.discardedScores.getOrElse(i) { 0f }
                    val text = recDisc.discardedTexts.getOrElse(i) { "" }
                    val reason = recDisc.discardedReasons.getOrElse(i) { "score" }
                    val preview = text.take(20).ifEmpty { "(空)" }
                    if (reason == "score") {
                        add("✗[$i] 分数${String.format("%.2f", score)} [${box[0].toInt()},${box[1].toInt()}→${box[4].toInt()},${box[5].toInt()}] \"$preview\"")
                    } else {
                        add("✗[$i] 内容:$reason [${box[0].toInt()},${box[1].toInt()}→${box[4].toInt()},${box[5].toInt()}] \"$preview\"")
                    }
                }
            }
        }
        val infoPanel = createInfoPanelView(infoLines, scrollable = true)

        // 创建可折叠内容容器：参数滑块 + 调试信息
        val foldableContent = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        // 参数滑块（带恢复默认按钮）
        val slidersView = createPPOcrParamSlidersView()
        foldableContent.addView(slidersView, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        // 调试信息
        foldableContent.addView(infoPanel, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val foldableParams = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = android.view.Gravity.BOTTOM
        }
        container.addView(foldableContent, foldableParams)
        debugInfoPanelContentView = foldableContent  // 折叠时隐藏整个内容区

        // 添加右下角展开/折叠按钮
        val toggleButton = createToggleButton()
        val toggleParams = android.widget.FrameLayout.LayoutParams(
            android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, 48f, resources.displayMetrics).toInt(),
            android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, 48f, resources.displayMetrics).toInt()
        ).apply {
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
            val margin = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, 16f, resources.displayMetrics).toInt()
            marginEnd = margin
            bottomMargin = margin
        }
        container.addView(toggleButton, toggleParams)

        // imageView 点击关闭全部（toggle 按钮在更高层级会优先接收点击）
        imageView.isClickable = true
        imageView.setOnClickListener {
            dismissDebugInfoPanel()
            dismissResultOverlay()
        }

        // 始终全屏显示
        val screenSize = getScreenSize()
        val params = android.view.WindowManager.LayoutParams(
            screenSize.width,
            screenSize.height,
            android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = android.view.Gravity.START or android.view.Gravity.TOP
            x = 0; y = 0
        }

        try {
            windowManager.addView(container, params)
            debugInfoPanelView = container
            debugInfoPanelAdded = true
            debugInfoPanelCollapsed = true  // 默认折叠
            debugToggleButton = toggleButton
            debugToggleButtonAdded = true
            isResultShowing = true
            // 初始折叠状态：隐藏内容，按钮显示展开箭头
            foldableContent.visibility = android.view.View.GONE
            toggleButton.text = "▲"
        } catch (e: Exception) {
            LogCollector.e(TAG, "PP-OCRv5 Debug: 显示失败", e)
        }

        bringFloatingBallToFront()
    }

    /** 限制最大高度的 ScrollView，用于调试面板半屏约束 */
    private class MaxHeightScrollView(context: android.content.Context, private val maxHeightPx: Int) :
        android.widget.ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val limitSpec = android.view.View.MeasureSpec.makeMeasureSpec(maxHeightPx, android.view.View.MeasureSpec.AT_MOST)
            super.onMeasure(widthMeasureSpec, limitSpec)
        }
    }

    // ========== 重新翻译（广播接收器） ==========

    /**
     * 注册重新翻译请求广播接收器。
     * 历史页面发送 ACTION_RETRANSLATE_REQUEST 触发重新翻译流程。
     */
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

    /**
     * 处理重新翻译请求。
     * 从 Intent 获取原图路径、裁剪坐标、OCR 引擎和翻译提供商，
     * 执行完整的 OCR → 翻译 → 渲染 → 缓存流程。
     */
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
        val historyIdToDelete = intent.getLongExtra("historyIdToDelete", 0)
        val existingPHash = intent.getLongExtra("existingPHash", 0)
        if (isProcessing) {
            sendRetranslateComplete(success = false, errorMessage = "翻译进行中，请稍后")
            return
        }

        isProcessing = true
        lifecycleScope.launch {
            var originalBitmap: android.graphics.Bitmap? = null
            var croppedBitmap: android.graphics.Bitmap? = null
            var renderedBitmap: android.graphics.Bitmap? = null

            try {
                // 1. Load original image
                originalBitmap = android.graphics.BitmapFactory.decodeFile(originalImagePath)
                if (originalBitmap == null) {
                    sendRetranslateComplete(success = false, errorMessage = "原图加载失败")
                    isProcessing = false
                    return@launch
                }

                // 2. Crop
                val cropRectF = android.graphics.RectF(cropLeft.toFloat(), cropTop.toFloat(), cropRight.toFloat(), cropBottom.toFloat())
                croppedBitmap = ScreenshotManager.cropBitmap(originalBitmap!!, cropRectF, android.graphics.Point(0, 0))

                // 3. Save current engine config and temporarily switch
                val savedDetEngine = config.detEngine
                val savedOcrEngine = config.ocrEngine
                when (ocrEngineName) {
                    "MLKIT" -> { config = config.copy(detEngine = DetEngine.MLKIT, ocrEngine = OcrEngine.MLKit) }
                    "MANGA_OCR" -> { config = config.copy(detEngine = DetEngine.PP_OCR_V5, ocrEngine = OcrEngine.MangaOcr) }
                    "PP_OCR_V5" -> { config = config.copy(detEngine = DetEngine.PP_OCR_V5, ocrEngine = OcrEngine.PPOcrV5) }
                }

                try {
                    // 4. Initialize engines and run OCR
                    when (config.detEngine) {
                        DetEngine.CTD -> initCTDIfNeeded()
                        DetEngine.MLKIT -> {}
                        DetEngine.RT_DETR_V2 -> initRTDetrV2IfNeeded()
                        DetEngine.PP_OCR_V5 -> initPPOcrV5IfNeeded()
                    }
                    when (config.ocrEngine) {
                        OcrEngine.MLKit -> {}
                        OcrEngine.MangaOcr -> ensureMangaOcrInitialized()
                        OcrEngine.PPOcrV5 -> initPPOcrV5IfNeeded()
                    }

                    val ocrResults = runOcrOnBitmap(croppedBitmap!!)

                    if (ocrResults.isEmpty()) {
                        sendRetranslateComplete(success = false, errorMessage = "OCR 未识别到文字")
                        isProcessing = false
                        return@launch
                    }

                    // 5. Check translator is available (use service's current translator)
                    if (translatorText == null) {
                        sendRetranslateComplete(success = false, errorMessage = "翻译器未初始化")
                        isProcessing = false
                        return@launch
                    }

                    // 6. Convert TextBlockInfo to BubbleRegion (matching processMangaScreenshot Step 2)
                    val allBubbles = ocrResults.filter { it.boundingBox != null }.map { block ->
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
                    if (allBubbles.isEmpty()) {
                        sendRetranslateComplete(success = false, errorMessage = "OCR 未识别到文字区域")
                        isProcessing = false
                        return@launch
                    }

                    // 7. Reuse translateBubbles + renderAndShowMergedOverlay instead of duplicate pipeline
                    currentPHash = if (existingPHash != 0L) existingPHash else PerceptualHash.compute(originalBitmap!!, centerCrop = true)
                    val newBubbles = translateBubbles(allBubbles)
                    renderAndShowMergedOverlay(
                        original = croppedBitmap!!,
                        newBubbles = newBubbles,
                        saveCache = true,
                        isRetranslate = true,
                        historyIdToDelete = historyIdToDelete,
                        originalBitmap = originalBitmap!!,
                        cropLeft = cropLeft,
                        cropTop = cropTop,
                        cropRight = cropRight,
                        cropBottom = cropBottom
                    )

                    sendRetranslateComplete(success = true)
                } finally {
                    // P0 #1: Restore engine config even on exception
                    config = config.copy(detEngine = savedDetEngine, ocrEngine = savedOcrEngine)
                }
            } catch (e: Exception) {
                LogCollector.e(TAG, "Retranslate failed", e)
                sendRetranslateComplete(success = false, errorMessage = e.message ?: "未知错误")
            } finally {
                // P0 #2: Recycle bitmaps
                originalBitmap?.recycle()
                croppedBitmap?.recycle()
                renderedBitmap?.recycle()
                isProcessing = false
            }
        }
    }

    /**
     * 发送重新翻译完成广播。
     */
    private fun sendRetranslateComplete(success: Boolean, errorMessage: String? = null, historyId: Long = 0L) {
        val intent = Intent(ACTION_RETRANSLATE_COMPLETE).apply {
            putExtra("success", success)
            if (historyId > 0) putExtra("historyId", historyId)
            putExtra("errorMessage", errorMessage)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }


    /**
     * 对 bitmap 运行检测+OCR，返回带位置信息的文字块列表。
     * 根据当前 config.detEngine 和 config.ocrEngine 选择对应引擎。
     */
    private suspend fun runOcrOnBitmap(bitmap: android.graphics.Bitmap): List<TextBlockInfo> {
        return withContext(Dispatchers.IO) {
            when (config.detEngine) {
                DetEngine.CTD -> {
                    val ctdOcrEngine = when (config.ocrEngine) {
                        OcrEngine.MLKit -> DetectionBridge.CTDOCREngine.MLKit
                        OcrEngine.MangaOcr -> DetectionBridge.CTDOCREngine.MangaOcr
                        OcrEngine.PPOcrV5 -> DetectionBridge.CTDOCREngine.PPOcrV5
                    }
                    DetectionBridge.detectWithCTD(bitmap, config.sourceLang, ctdOcrEngine, this@MangaFloatingService)
                }
                DetEngine.MLKIT -> {
                    when (config.ocrEngine) {
                        OcrEngine.MLKit -> OCRBridge.recognizeWithLocation(config.sourceLang, bitmap)
                        OcrEngine.MangaOcr -> MangaOcrBridge.recognizeWithLocation(bitmap, config.sourceLang)
                        OcrEngine.PPOcrV5 -> DetectionBridge.detectWithPPOcrV5(bitmap, config.sourceLang, this@MangaFloatingService)
                    }
                }
                DetEngine.RT_DETR_V2 -> {
                    val rtdetrOcrEngine = when (config.ocrEngine) {
                        OcrEngine.MLKit -> DetectionBridge.CTDOCREngine.MLKit
                        OcrEngine.MangaOcr -> DetectionBridge.CTDOCREngine.MangaOcr
                        OcrEngine.PPOcrV5 -> DetectionBridge.CTDOCREngine.PPOcrV5
                    }
                    DetectionBridge.detectWithRTDetrV2(bitmap, config.sourceLang, rtdetrOcrEngine, this@MangaFloatingService, config.keepTextFree)
                }
                DetEngine.PP_OCR_V5 -> {
                    DetectionBridge.detectWithPPOcrV5(bitmap, config.sourceLang, this@MangaFloatingService)
                }
            }
        }
    }
}
