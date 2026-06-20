package com.moe.moetranslator.manga

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.widget.Toast
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
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
import com.moe.moetranslator.MainActivity
import com.moe.moetranslator.R
import com.moe.moetranslator.bridge.OCRBridge
import com.moe.moetranslator.bridge.TextBlockInfo
import com.moe.moetranslator.me.ConfigurationStorage
import com.moe.moetranslator.me.OpenAIProviderConfig
import com.moe.moetranslator.translate.AccessibilityServiceManager
import com.moe.moetranslator.translate.CropView
import com.moe.moetranslator.translate.Dialogs
import com.moe.moetranslator.translate.ScreenshotManager
import com.moe.moetranslator.translate.TranslationResult
import com.moe.moetranslator.translate.TranslationTextAPI
import com.moe.moetranslator.utils.Constants
import com.moe.moetranslator.utils.CustomPreference
import com.moe.moetranslator.utils.KeystoreManager
import com.moe.moetranslator.utils.TranslationStatusOverlay
import com.moe.moetranslator.utils.UtilTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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

        private const val CLICK_SLOP = 5f
        private const val LONG_PRESS_SLOP = 10f
        private const val DOUBLE_CLICK_DELAY = 300L

        // pHash 阈值常量
        const val PHASH_STABLE_THRESHOLD = 0.95f   // >= 此值认为画面没变
        const val PHASH_NEW_PAGE_THRESHOLD = 0.60f  // < 此值认为是全新页面
        const val STABLE_CONFIRM_COUNT = 2          // 连续稳定次数
        const val MOTION_TIMEOUT_MS = 10000L        // 运动超时 10 秒
        const val DETECT_INTERVAL_MS = 500L         // 运动中检测间隔
        const val REGION_IOU_THRESHOLD = 0.4f       // 区域重叠判定阈值
        const val MAX_CACHED_REGIONS = 50           // 最大缓存区域数
        const val REGION_TTL_MS = 300_000L          // 区域缓存有效期 5 分钟

        // 分批渲染常量
        const val INCREMENTAL_THRESHOLD = 6       // 触发分批的气泡数量阈值

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

    // 检测状态机
    private enum class DetectState { IDLE, MOTION, STABLE }
    private var detectState = DetectState.IDLE
    private var lastTranslatedHash = 0L        // 上次翻译页的哈希（IDLE 判断是否需翻译）
    private var previousScreenshotHash = 0L    // 上一次截图的哈希（MOTION 判断页面是否稳定）
    private var stableCount = 0
    private var motionStartTime = 0L

    // 区域级翻译缓存
    private data class TranslatedRegion(
        val bounds: RectF,
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

    // 缓存管理
    private lateinit var cacheManager: TranslationCacheManager
    private var forceRefresh = false
    private var isForceRefreshActive = false  // 保存 forceRefresh 状态，用于保存缓存时判断

    // 翻译会话 ID（每次服务启动生成新的）
    private val sessionId = java.util.UUID.randomUUID().toString()
    private var currentPHash = 0L
    private var cacheOverlayContainer: android.widget.FrameLayout? = null

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

    override fun onDestroy() {
        super.onDestroy()
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

        val (dialog, listView) = Dialogs.mangaMenuDialogSimple(
            applicationContext, isAutoTranslating, cropLabel, modelLabel
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
                    // 自动翻译
                    toggleAutoTranslate()
                    val adapter = listView.adapter as com.moe.moetranslator.translate.MenuDialogAdapter
                    if (isAutoTranslating) {
                        adapter.updateLabel(3, getString(R.string.manga_menu_stop_auto))
                        adapter.updateIcon(3, R.drawable.stop_auto)
                    } else {
                        adapter.updateLabel(3, getString(R.string.manga_menu_auto_translate))
                        adapter.updateIcon(3, R.drawable.start_auto)
                    }
                }
                4 -> {
                    dialog.dismiss()
                    stopSelf()
                }
                5 -> {
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

        val (dialog, listView) = Dialogs.mangaMenuDialog(
            applicationContext, isAutoTranslating, cropLabel, detModelLabel, ocrEngineLabel
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
        if (AccessibilityServiceManager.getService() == null) {
            showToast(getString(R.string.accessibility_recycle), true)
            return
        }
        isAutoTranslating = true
        detectState = DetectState.IDLE
        stableCount = 0
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
                    scheduleNextDetection(1000L)
                    return false
                } else {
                    // 画面变了，进入 MOTION 等待稳定
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
                val elapsed = System.currentTimeMillis() - motionStartTime
                // MOTION: 比较连续两次截图（而非和旧翻译页比较）
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
                } else if (elapsed > MOTION_TIMEOUT_MS) {
                    // 超时，强制进入稳定态
                    detectState = DetectState.STABLE
                    stableCount = 0
                    LogCollector.d(TAG, "AutoDetect[MOTION→STABLE]: timeout, consecutive sim=$simConsecutive")
                    return onMotionStabilized(currentHash)
                } else {
                    // 还在动
                    stableCount = 0
                    scheduleNextDetection(DETECT_INTERVAL_MS)
                    return false
                }
            }

            DetectState.STABLE -> {
                // 不应该停留在此状态，回到 IDLE
                detectState = DetectState.IDLE
                scheduleNextDetection(1000L)
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
            // 差异巨大 → 全新页面，清除区域缓存
            LogCollector.d(TAG, "onMotionStabilized: new page (sim=$similarityToTranslated), clearing region cache")
            translatedRegions.clear()
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

        val service = AccessibilityServiceManager.getService()
        LogCollector.d(TAG, "triggerTranslation: accessibilityService=$service")
        if (service == null) {
            showToast(getString(R.string.accessibility_recycle), true)
            return
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
                AccessibilityServiceManager.takeScreenshot(cropRect, cropView.absolutePointOffset)
            } else {
                LogCollector.d(TAG, "triggerTranslation: taking full screenshot")
                AccessibilityServiceManager.takeScreenshot(null, android.graphics.Point(0, 0))
            }
            LogCollector.d(TAG, "========== triggerTranslation END ==========")
        }
    }

    // ---------- Screenshot collection ----------

    private fun setupScreenshotCollector() {
        LogCollector.d(TAG, "setupScreenshotCollector: starting collector coroutine")
        lifecycleScope.launch {
            LogCollector.d(TAG, "Screenshot collector: coroutine started, waiting for screenshots...")
            ScreenshotManager.screenshotFlow.collect { bitmap ->
                LogCollector.d(TAG, "Screenshot collector: BITMAP RECEIVED! ${bitmap.width}x${bitmap.height}")
                try {
                    // 检测受限区域截图（全黑/几乎全黑）
                    if (isRestrictedScreenshot(bitmap)) {
                        LogCollector.d(TAG, "Screenshot collector: 检测到受限区域截图，跳过翻译")
                        bitmap.recycle()
                        isProcessing = false
                        statusOverlay.showError("该区域无法截图，可能是受限内容（安全应用/DRM保护）")
                        if (isAutoTranslating) scheduleNextDetection(DETECT_INTERVAL_MS)
                        return@collect
                    }

                    // 自动翻译模式：pHash 门控（手动翻译时跳过）
                    if (isAutoTranslating && !isManualTranslating) {
                        val pHash = PerceptualHash.compute(bitmap)
                        val shouldTranslate = processAutoDetectPHash(pHash)
                        if (!shouldTranslate) {
                            bitmap.recycle()
                            isProcessing = false
                            // 不关闭进度条，保持"自动检测中"显示
                            return@collect
                        }
                        // pHash 通过门控，切换为"翻译中"，执行 OCR + 翻译
                        showProgressOverlay(getString(R.string.manga_translating))
                        processMangaScreenshot(bitmap, pHash)
                    } else {
                        // 手动模式：直接翻译
                        showProgressOverlay("检测中...")
                        try {
                            processMangaScreenshot(bitmap)
                        } finally {
                            isManualTranslating = false  // 无论成功失败，恢复自动检测
                        }
                    }
                    LogCollector.d(TAG, "Screenshot collector: processMangaScreenshot completed normally")
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

        // Accessibility 事件辅助：屏幕内容变化时加速 IDLE 状态的检测
        lifecycleScope.launch {
            ScreenshotManager.contentChangedFlow.collect {
                if (isAutoTranslating && detectState == DetectState.IDLE && !isProcessing) {
                    // 取消当前调度，500ms 后立即检测（比默认间隔快）
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

    /**
     * 将 TextBlockInfo 列表转换为 BubbleRegion 列表。
     * 复用 processMangaScreenshot Step 2 中的转换逻辑。
     */
    private fun textBlocksToBubbleRegions(textBlocks: List<TextBlockInfo>): List<BubbleRegion> {
        return textBlocks.filter { it.boundingBox != null }.map { block ->
            val rect = block.boundingBox!!
            val isVertical = block.isVertical ?: (rect.height() > rect.width())
            BubbleRegion(
                rect = rect,
                texts = listOf(block.text),
                fontSize = if (isVertical) rect.width().toFloat() else rect.height().toFloat(),
                direction = if (isVertical) TextDirection.VERTICAL_RL else TextDirection.HORIZONTAL
            )
        }
    }

    /**
     * 保存翻译缓存（不渲染 overlay）。
     * 用于分批渲染场景：用户关闭 overlay 后仍保存完整缓存。
     */
    private suspend fun saveTranslationCache(original: Bitmap, allBubbles: List<TranslatedBubble>) {
        try {
            val translatorName = translatorText?.javaClass?.simpleName ?: "Unknown"
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
            val entry = CacheEntry(
                type = TranslationCacheManager.MODE_MANGA,
                sourceText = ocrTexts.ifEmpty { null },
                translatedText = transTexts.ifEmpty { null },
                resultBitmap = resultBitmap.copy(resultBitmap.config ?: Bitmap.Config.ARGB_8888, false),
                sourceLang = config.sourceLang,
                targetLang = config.targetLang,
                translatorName = translatorName,
                pHash = currentPHash,
                sessionId = sessionId
            )
            if (isForceRefreshActive) {
                cacheManager.refreshCache(currentPHash, TranslationCacheManager.MODE_MANGA, entry)
                isForceRefreshActive = false
            } else {
                cacheManager.saveToCache(entry)
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
        val firstBatchSize = sorted.size * 2 / 5
        val firstBatch = sorted.take(firstBatchSize)
        val secondBatch = sorted.drop(firstBatchSize)
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

                val ocrJob = kotlinx.coroutines.GlobalScope.async(Dispatchers.IO) {
                    DetectionBridge.recognizeCroppedBubbles(
                        secondBatch, DetectionBridge.CTDOCREngine.MangaOcr, this@MangaFloatingService, config.sourceLang
                    )
                }

                val result = translateBubbles(firstBubbleRegions, forceContext = true)
                if (result.isNotEmpty()) renderAndShowMergedOverlay(bitmap, result)

                val secondTextBlocks = ocrJob.await()
                LogCollector.d(TAG, "incrementalRTDetrMangaOcr: 第二批 OCR ${secondTextBlocks.size} 个文字块")
                if (secondTextBlocks.isNotEmpty()) {
                    val secondBubbleRegions = textBlocksToBubbleRegions(secondTextBlocks)
                    result + translateBubbles(secondBubbleRegions, forceContext = true)
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
        if (ppRecLang == null) return false

        LogCollector.d(TAG, "incrementalPPOcrV5: 开始检测")
        val textLines = DetectionBridge.detectAndCropPPOcrV5Lines(bitmap)
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

        val firstBatchSize = textLines.size * 2 / 5
        val firstBatch = textLines.take(firstBatchSize)
        val secondBatch = textLines.drop(firstBatchSize)
        LogCollector.d(TAG, "incrementalPPOcrV5: 第一批 ${firstBatch.size} 行，第二批 ${secondBatch.size} 行")

        // 识别单批：OCR → TextLineMerger 合并 → TextBlockInfo
        suspend fun recognizeBatch(batch: List<CroppedTextLine>): List<TextBlockInfo> {
            val crops = batch.map { it.croppedBitmap }
            val rects = batch.map { it.rect }
            val recResults = withContext(Dispatchers.IO) {
                PPOcrV5Engine.recognizeBatchWithCls(this@MangaFloatingService, crops, ppRecLang)
            }
            // 释放裁剪图片
            crops.forEach { it.recycle() }
            // 构建 TextLineMerger.TextLine
            val mergedInput = mutableListOf<TextLineMerger.TextLine>()
            for (i in recResults.indices) {
                val r = recResults[i]
                if (r.text.isNotBlank() && r.score >= 0.5f && i < rects.size) {
                    val rect = rects[i]
                    val isVertical = rect.height() > rect.width()
                    val fontSize = minOf(rect.width(), rect.height()).toFloat()
                    mergedInput.add(TextLineMerger.TextLine(
                        rect = rect, text = r.text, fontSize = fontSize,
                        isVertical = isVertical, score = r.score
                    ))
                }
            }
            // TextLineMerger 识别后合并
            val mergedRegions = TextLineMerger.merge(mergedInput)
            LogCollector.d(TAG, "recognizeBatch TextLineMerger: ${mergedInput.size} 行 → ${mergedRegions.size} 个文本区域")
            return mergedRegions.map { region ->
                TextBlockInfo(
                    text = region.texts.joinToString("\n"),
                    boundingBox = region.rect,
                    cornerPoints = null,
                    isVertical = region.direction == TextDirection.VERTICAL_RL
                )
            }.filter { it.text.isNotBlank() }
        }

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

                val ocrJob = kotlinx.coroutines.GlobalScope.async(Dispatchers.IO) {
                    recognizeBatch(secondBatch)
                }

                val result = translateBubbles(firstBubbleRegions, forceContext = true)
                if (result.isNotEmpty()) renderAndShowMergedOverlay(bitmap, result)

                val secondTextBlocks = ocrJob.await()
                LogCollector.d(TAG, "incrementalPPOcrV5: 第二批 OCR ${secondTextBlocks.size} 个文字块")
                if (secondTextBlocks.isNotEmpty()) {
                    val secondBubbleRegions = textBlocksToBubbleRegions(secondTextBlocks)
                    result + translateBubbles(secondBubbleRegions, forceContext = true)
                } else result
            }

            // 回滚分批渲染添加的上下文，只保留翻译前的历史
            while (contextHistory.size > contextSnapshotSize) {
                contextHistory.removeLast()
            }

            finalizeIncremental(bitmap, firstTranslated)
            return true
        } catch (e: Exception) {
            LogCollector.e(TAG, "incrementalPPOcrV5: 失败", e)
            // 回收未处理的裁剪图片
            firstBatch.forEach { if (!it.croppedBitmap.isRecycled) it.croppedBitmap.recycle() }
            secondBatch.forEach { if (!it.croppedBitmap.isRecycled) it.croppedBitmap.recycle() }
            return false
        }
    }

    /**
     * 增量渲染公共收尾：最终渲染/缓存 + 状态更新。
     */
    private suspend fun finalizeIncremental(bitmap: Bitmap, allTranslated: List<TranslatedBubble>) {
        if (allTranslated.isNotEmpty()) {
            if (!isResultShowing) {
                LogCollector.d(TAG, "finalizeIncremental: 用户已关闭 overlay，保存缓存")
                saveTranslationCache(bitmap, allTranslated)
            } else {
                withContext(Dispatchers.Main) { showProgressOverlay("显示中...") }
                renderAndShowMergedOverlay(bitmap, allTranslated)
                LogCollector.d(TAG, "finalizeIncremental: 最终渲染完成，共 ${allTranslated.size} 个气泡")
            }
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
                evictExpiredRegions()
            }

            // 计算 pHash（使用中心裁剪提高框选偏移时的缓存命中率）
            // precomputedPHash 是全图 pHash（用于页面变化检测），缓存匹配用中心裁剪 pHash
            currentPHash = PerceptualHash.compute(bitmap, centerCrop = true)

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
                        if (recLang != null) {
                            val ocrResult = withContext(Dispatchers.IO) {
                                PPOcrV5Engine.runOCR(this@MangaFloatingService, bitmap, recLang, useDet = true, useCls = false)
                            }
                            LogCollector.d(TAG, "PP-OCRv5 Debug Mode: det=${ocrResult.boxes.size}, rec=${ocrResult.texts.size}")
                            // 原始识别详情
                            for (i in ocrResult.texts.indices) {
                                val text = ocrResult.texts[i]
                                val score = ocrResult.scores.getOrElse(i) { 0f }
                                val box = ocrResult.boxes.getOrNull(i)
                                val boxStr = if (box != null && box.size >= 8) {
                                    "[${box[0].toInt()},${box[1].toInt()} → ${box[4].toInt()},${box[5].toInt()}]"
                                } else ""
                                LogCollector.d(TAG, "PP-OCRv5 RAW[$i]: ${String.format("%.2f", score)} $boxStr \"$text\"")
                            }
                            // 运行 TextLineMerger 合并
                            val mergedRegions = runTextLineMerge(ocrResult)
                            LogCollector.d(TAG, "PP-OCRv5 Debug Mode: merged=${mergedRegions.size} regions")
                            // 合并区域详情
                            for ((idx, region) in mergedRegions.withIndex()) {
                                val dirLabel = if (region.direction == TextDirection.VERTICAL_RL) "竖排" else "横排"
                                val r = region.rect
                                val merged = region.texts.joinToString("｜")
                                LogCollector.d(TAG, "PP-OCRv5 MERGED[$idx]: $dirLabel ×${region.texts.size} [${r.left},${r.top},${r.right},${r.bottom}] \"$merged\"")
                            }
                            showPPOcrV5DebugView(bitmap, ocrResult, mergedRegions)
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
                val cached = cacheManager.findCache(currentPHash, TranslationCacheManager.MODE_MANGA)
                if (cached != null && cached.resultBitmap != null) {
                    LogCollector.d(TAG, "processMangaScreenshot: 缓存命中, historyId=${cached.historyId}")
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
                if (!isAutoTranslating) {
                    showToast(getString(R.string.no_text_found), true)
                }
                return
            }

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
                    BubbleRegion(
                        rect = rect,
                        texts = listOf(block.text),
                        fontSize = if (isVertical) rect.width().toFloat() else rect.height().toFloat(),
                        direction = if (isVertical) TextDirection.VERTICAL_RL else TextDirection.HORIZONTAL
                    )
                }
            }
            LogCollector.d(TAG, "processMangaScreenshot: Step 2 - Detected ${allBubbles.size} bubbles")

            // Step 2.5: 文本匹配缓存（OCR 完成后，翻译前）
            // 用 OCR 文本内容查找缓存，同一页不同框选范围只要文字相同就能命中
            if (!isForceRefreshActive && allBubbles.isNotEmpty()) {
                val ocrTexts = allBubbles.map { it.texts.joinToString("") }
                val textCached = cacheManager.findMangaCacheByText(ocrTexts, config.sourceLang, config.targetLang)
                if (textCached != null && textCached.resultBitmap != null) {
                    LogCollector.d(TAG, "processMangaScreenshot: 文本缓存命中, historyId=${textCached.historyId}")
                    statusOverlay.showImmediate("缓存命中")
                    lastTranslatedHash = currentPHash
                    // 同步 pHash 缓存
                    cacheManager.syncPHashCache(currentPHash, TranslationCacheManager.MODE_MANGA, textCached.historyId)
                    withContext(Dispatchers.Main) {
                        showResultOverlay(textCached.resultBitmap, fromCache = true)
                    }
                    return
                }
            }

            // Step 3: Translate
            val newTranslatedBubbles: List<TranslatedBubble>
            if (isAutoTranslating) {
                // 自动翻译：基于合并后的气泡做增量过滤
                val newBubbles = allBubbles.filter { bubble ->
                    !isRegionCached(RectF(bubble.rect))
                }
                if (newBubbles.isEmpty()) {
                    LogCollector.d(TAG, "processMangaScreenshot: All bubbles cached, skipping")
                    lastTranslatedHash = currentPHash
                    scheduleNextDetection(DETECT_INTERVAL_MS)
                    return
                }
                LogCollector.d(TAG, "processMangaScreenshot: Step 3 - Incremental translate ${newBubbles.size}/${allBubbles.size} bubbles")
                newTranslatedBubbles = incrementalTranslateBubbles(newBubbles)
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
    private suspend fun incrementalTranslateBubbles(bubbles: List<BubbleRegion>): List<TranslatedBubble> {
        if (bubbles.isEmpty()) return emptyList()

        LogCollector.d(TAG, "incrementalTranslateBubbles: ${bubbles.size} bubbles")

        // 文本级缓存：按文本 hash 查找已翻译的文本，避免重复调 API
        val textCache = translatedRegions.associateBy { it.ocrTextHash }
        val fromCache = mutableListOf<TranslatedBubble>()
        val needTranslation = mutableListOf<BubbleRegion>()

        for (bubble in bubbles) {
            val combinedText = bubble.texts.map { cleanOcrText(it) }.filter { it.isNotBlank() }.joinToString("")
            if (combinedText.isBlank()) continue

            val textHash = combinedText.hashCode()
            val cachedText = textCache[textHash]
            if (cachedText != null && cachedText.ocrText == combinedText) {
                // 文本完全匹配，直接复用翻译
                fromCache.add(TranslatedBubble(
                    rect = bubble.rect,
                    originalText = combinedText,
                    translatedText = cachedText.translation,
                    backgroundColor = Color.TRANSPARENT,
                    fontSize = bubble.fontSize,
                    direction = bubble.direction
                ))
                // 更新位置和时间
                translatedRegions.remove(cachedText)
                translatedRegions.add(cachedText.copy(
                    bounds = RectF(bubble.rect),
                    translatedAt = System.currentTimeMillis()
                ))
                LogCollector.d(TAG, "Text cache hit: '${combinedText.take(20)}' → '${cachedText.translation.take(20)}'")
            } else {
                needTranslation.add(bubble)
            }
        }

        if (needTranslation.isEmpty()) {
            LogCollector.d(TAG, "incrementalTranslateBubbles: all ${bubbles.size} from text cache")
            evictOldRegions()
            return fromCache
        }

        LogCollector.d(TAG, "incrementalTranslateBubbles: ${fromCache.size} cached + ${needTranslation.size} need API")
        showProgressOverlay(getString(R.string.manga_translating))

        // 用 translateBubbles 走和手动翻译完全相同的路径
        val results = translateBubbles(needTranslation)

        // 缓存翻译结果
        for (result in results) {
            val textHash = result.originalText.hashCode()
            translatedRegions.add(TranslatedRegion(
                bounds = RectF(result.rect),
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
     * 检查新检测到的区域是否已被缓存。
     * 通过空间重叠 + 文本相似度双重判断。
     */
    private fun isRegionCached(newBounds: RectF): Boolean {
        for (cached in translatedRegions) {
            val iou = computeIoU(cached.bounds, newBounds)
            if (iou >= REGION_IOU_THRESHOLD) {
                return true
            }
        }
        return false
    }

    /**
     * 计算两个矩形的 IoU（交并比）。
     */
    private fun computeIoU(a: RectF, b: RectF): Float {
        val interLeft = maxOf(a.left, b.left)
        val interTop = maxOf(a.top, b.top)
        val interRight = minOf(a.right, b.right)
        val interBottom = minOf(a.bottom, b.bottom)
        if (interRight <= interLeft || interBottom <= interTop) return 0f
        val interArea = (interRight - interLeft) * (interBottom - interTop)
        val areaA = (a.right - a.left) * (a.bottom - a.top)
        val areaB = (b.right - b.left) * (b.bottom - b.top)
        val minArea = minOf(areaA, areaB)
        return if (minArea <= 0f) 0f else interArea / minArea
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
     * 将已缓存的翻译区域转换为 TranslatedBubble 用于渲染。
     */
    private fun cachedRegionsToBubbles(): List<TranslatedBubble> {
        return translatedRegions.map { region ->
            TranslatedBubble(
                rect = android.graphics.Rect(
                    region.bounds.left.toInt(), region.bounds.top.toInt(),
                    region.bounds.right.toInt(), region.bounds.bottom.toInt()
                ),
                originalText = region.ocrText,
                translatedText = region.translation,
                backgroundColor = Color.TRANSPARENT,
                fontSize = config.fontSize,
                direction = config.textDirection
            )
        }
    }

    /**
     * 合并已缓存翻译 + 新翻译，渲染并显示 overlay。
     * 跳开与新翻译重叠的缓存区域，避免重复覆盖。
     */
    private suspend fun renderAndShowMergedOverlay(
        original: Bitmap,
        newBubbles: List<TranslatedBubble>
    ) {
        val allBubbles = mutableListOf<TranslatedBubble>()

        // 添加已缓存的翻译（排除与新翻译重叠的区域）
        for (cached in translatedRegions) {
            val overlapsNew = newBubbles.any { new ->
                computeIoU(cached.bounds, RectF(new.rect)) >= REGION_IOU_THRESHOLD
            }
            if (!overlapsNew) {
                allBubbles.add(TranslatedBubble(
                    rect = android.graphics.Rect(
                        cached.bounds.left.toInt(), cached.bounds.top.toInt(),
                        cached.bounds.right.toInt(), cached.bounds.bottom.toInt()
                    ),
                    originalText = cached.ocrText,
                    translatedText = cached.translation,
                    backgroundColor = Color.TRANSPARENT,
                    fontSize = config.fontSize,
                    direction = config.textDirection
                ))
            }
        }

        // 添加新翻译
        allBubbles.addAll(newBubbles)

        if (allBubbles.isEmpty()) {
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
                regions = allBubbles,
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

        // 保存到缓存和历史
        try {
            val translatorName = translatorText?.javaClass?.simpleName ?: "Unknown"
            // 从 allBubbles 聚合 OCR 原文和译文（allBubbles 包含缓存 + 新翻译）
            val ocrTexts = allBubbles.mapIndexed { i, b -> "[${i + 1}] ${b.originalText}" }.joinToString("\n")
            val transTexts = allBubbles.mapIndexed { i, b -> "[${i + 1}] ${b.translatedText}" }.joinToString("\n")
            LogCollector.d(TAG, "保存缓存: ${allBubbles.size} 个气泡, ocr=${ocrTexts.take(50)}, trans=${transTexts.take(50)}")
            val entry = CacheEntry(
                type = TranslationCacheManager.MODE_MANGA,
                sourceText = ocrTexts.ifEmpty { null },
                translatedText = transTexts.ifEmpty { null },
                resultBitmap = resultBitmap.copy(resultBitmap.config ?: Bitmap.Config.ARGB_8888, false),
                sourceLang = config.sourceLang,
                targetLang = config.targetLang,
                translatorName = translatorName,
                pHash = currentPHash,
                sessionId = sessionId
            )
            // 强制刷新时替换旧缓存和历史，否则直接保存
            if (isForceRefreshActive) {
                cacheManager.refreshCache(currentPHash, TranslationCacheManager.MODE_MANGA, entry)
                LogCollector.d(TAG, "强制刷新：替换旧缓存和历史")
                isForceRefreshActive = false
            } else {
                cacheManager.saveToCache(entry)
            }
        } catch (e: Exception) {
            LogCollector.e(TAG, "保存缓存失败", e)
        }
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
                                direction = bubble.direction
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
                windowManager.removeView(resultOverlayView)
            } catch (e: Exception) {
                LogCollector.e(TAG, "Error dismissing overlay", e)
            }
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
        val mergedCount = debugResult.mergedGroups.count { it.size > 1 }
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
     * 从 OcrResult 构建 TextLineMerger 输入并执行合并
     */
    private fun runTextLineMerge(ocrResult: OcrResult): List<TextLineMerger.MergedRegion> {
        val mergedInput = mutableListOf<TextLineMerger.TextLine>()
        for (i in ocrResult.texts.indices) {
            val text = ocrResult.texts[i].trim()
            if (text.isEmpty()) continue
            val score = ocrResult.scores.getOrElse(i) { 0f }
            val box = ocrResult.boxes.getOrNull(i) ?: continue
            val tlx = box[0].toInt(); val tly = box[1].toInt()
            val trx = box[2].toInt(); val try_ = box[3].toInt()
            val brx = box[4].toInt(); val bry = box[5].toInt()
            val blx = box[6].toInt(); val bly = box[7].toInt()
            val xs = intArrayOf(tlx, trx, brx, blx)
            val ys = intArrayOf(tly, try_, bry, bly)
            val rect = Rect(xs.min(), ys.min(), xs.max(), ys.max())
            val w = rect.width().toFloat()
            val h = rect.height().toFloat()
            val isVert = h > w * 1.5f
            val fontSize = if (isVert) w else h
            mergedInput.add(TextLineMerger.TextLine(rect, text, fontSize, isVert, score))
        }
        return TextLineMerger.merge(mergedInput)
    }

    /**
     * PP-OCRv5 调试模式：渲染检测+识别+合并结果并显示
     */
    private fun showPPOcrV5DebugView(bitmap: Bitmap, ocrResult: OcrResult, mergedRegions: List<TextLineMerger.MergedRegion>) {
        val debugBitmap = renderPPOcrV5DebugWithMerge(bitmap, ocrResult, mergedRegions)
        showPPOcrV5DebugResultOverlay(debugBitmap, ocrResult, mergedRegions)
    }

    /**
     * 渲染 PP-OCRv5 调试图：原始检测框 + 合并区域框
     */
    private fun renderPPOcrV5DebugWithMerge(
        bitmap: Bitmap,
        ocrResult: OcrResult,
        mergedRegions: List<TextLineMerger.MergedRegion>
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
            canvas.drawRect(r, mergedFillPaint)
            canvas.drawRect(r, mergedPaint)

            // 标签：序号 + 方向 + 文字数
            val dirLabel = if (region.direction == TextDirection.VERTICAL_RL) "V" else "H"
            val label = "[$idx]$dirLabel ×${region.texts.size}"
            canvas.drawText(label, r.left.toFloat(), r.top.toFloat() - 6f, labelPaint)
        }

        return output
    }

    private fun showPPOcrV5DebugResultOverlay(debugBitmap: Bitmap, ocrResult: OcrResult, mergedRegions: List<TextLineMerger.MergedRegion> = emptyList()) {
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
            add("PP-OCRv5 调试模式 | 检测框: ${ocrResult.boxes.size}  识别: ${ocrResult.texts.size}  合并: ${mergedRegions.size}区域")
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
                add("【$idx】$dirLabel ×$srcCount [${r.left},${r.top},${r.right},${r.bottom}]")
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
                add("[$i] ${String.format("%.2f", score)} $boxStr \"$text\"")
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
}
