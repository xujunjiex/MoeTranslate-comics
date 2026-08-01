package com.moe.starflow.manga

import com.moe.starflow.manga.debug.MangaDebugOverlays
import com.moe.starflow.manga.debug.MangaDebugSliders
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
import com.moe.starflow.manga.MangaOcrModelFiles
import com.moe.starflow.utils.LogCollector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.moe.starflow.MainActivity
import com.moe.starflow.R
import com.moe.starflow.bridge.OCRBridge
import com.moe.starflow.bridge.TextBlockInfo
import com.moe.starflow.me.apiconfig.BuiltinProviders
import com.moe.starflow.me.apiconfig.ConfigurationStorage
import com.moe.starflow.me.apiconfig.OpenAIProviderConfig
import com.moe.starflow.translate.AccessibilityProvider
import com.moe.starflow.translate.AccessibilityEventHandler
import com.moe.starflow.translate.AccessibilityServiceManager
import com.moe.starflow.translate.BallStateManager
import com.moe.starflow.translate.CropView
import com.moe.starflow.translate.Dialogs
import com.moe.starflow.translate.MediaProjectionProvider
import com.moe.starflow.translate.ScreenshotData
import com.moe.starflow.translate.ScreenshotManager
import com.moe.starflow.translate.MediaProjectionIntentHolder
import com.moe.starflow.translate.ScreenshotProvider
import com.moe.starflow.translate.ScreenCapturePermissionActivity
import com.moe.starflow.translate.TranslationTextAPI
import com.moe.starflow.utils.Constants
import com.moe.starflow.utils.CustomPreference
import com.moe.starflow.utils.KeystoreManager
import com.moe.starflow.utils.TextSimilarity
import com.moe.starflow.utils.TranslationStatusOverlay
import com.moe.starflow.utils.UtilTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
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
import translationapi.nllbtranslation.NLLBTranslation
import com.moe.starflow.data.CacheEntry
import com.moe.starflow.data.TranslationCacheManager
import com.moe.starflow.utils.PerceptualHash
import org.json.JSONArray
import org.json.JSONObject
import java.util.LinkedList
import kotlin.math.abs
import kotlin.math.roundToInt

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
    private lateinit var resultOverlayView: FrameLayout
    private lateinit var resultOverlayImage: ImageView  // overlay 内的图片子 View

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

    // 悬浮球图标变更广播接收器（防止被 GC 回收，跨 onCreate/onDestroy 复用同一实例）
    private var iconChangeReceiver: android.content.BroadcastReceiver? = null

    // 悬浮球状态机
    private var ballStateManager: BallStateManager? = null

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
    private var lastTranslatedTime = 0L       // 上次翻译时间戳（40s 超时省电检测）
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

    // ── 引擎组合：单一数据源，所有标签/切换/初始化共用 ──
    private data class ComboDef(
        val key: String,
        val detEngine: DetEngine,
        val ocrEngine: OcrEngine,
        val labelRes: Int,
        val needsDownloadCheck: Boolean = false
    )

    /** 切换顺序：mlkit → ppocr → ppocrv6 → manga → mlkit */
    private val engineCombos = listOf(
        ComboDef("mlkit", DetEngine.MLKIT, OcrEngine.MLKit, R.string.manga_model_mlkit),
        ComboDef("ppocr", DetEngine.PP_OCR_V5, OcrEngine.PPOcrV5, R.string.manga_model_ppocr, needsDownloadCheck = true),
        ComboDef("ppocrv6", DetEngine.PP_OCR_V6, OcrEngine.PPOcrV6, R.string.manga_model_ppocrv6),
        ComboDef("manga", DetEngine.RT_DETR_V2, OcrEngine.MangaOcr, R.string.manga_model_manga_ocr, needsDownloadCheck = true),
    )

    /** 当前 config 匹配的组合（兜底 mlkit） */
    private fun currentCombo(): ComboDef = engineCombos.firstOrNull {
        it.detEngine == config.detEngine && it.ocrEngine == config.ocrEngine
    } ?: engineCombos[0]

    /** combo key → 显示标签 */
    private fun comboLabel(combo: ComboDef): String = getString(combo.labelRes)

    /** 检查需下载的组合是否可用 */
    private fun isComboAvailable(combo: ComboDef): Boolean = when {
        !combo.needsDownloadCheck -> true
        combo.key == "ppocr" -> PPOcrModelFiles.isV5DetDownloaded(this) && PPOcrModelFiles.isV5RecZhDownloaded(this)
        else -> RTDetrModelFiles.isModelAvailable(this) && MangaOcrModelFiles.isModelDownloaded(this)
    }

    /** 应用组合：更新 config + 持久化 + 初始化引擎 */
    private fun applyCombo(combo: ComboDef) {
        config = config.copy(detEngine = combo.detEngine, ocrEngine = combo.ocrEngine)
        prefs.setInt("Manga_Det_Model", combo.detEngine.value)
        prefs.setInt("Manga_Rec_Model", combo.ocrEngine.value)
        showToast(getString(combo.labelRes), true)
        when (combo.key) {
            "ppocr" -> lifecycleScope.launch { initPPOcrV5("检测器+识别器") }
            "ppocrv6" -> lifecycleScope.launch { initPPOcrV6("检测器+识别器") }
            "manga" -> lifecycleScope.launch { initRTDetrV2(); ensureMangaOcrInitialized() }
            else -> {} // MLKit 无需初始化
        }
    }
    private var translatorText: TranslationTextAPI? = null

    // 截图提供者
    private var screenshotProvider: ScreenshotProvider? = null

    // 缓存管理
    private lateinit var cacheManager: TranslationCacheManager
    private var forceRefresh = false
    private var isForceRefreshActive = false  // 保存 forceRefresh 状态，用于保存缓存时判断
    private var lastCachedHistoryId: Long = 0  // 缓存命中的 historyId，用于强制刷新时删除旧记录
    private var lastCachedPHash: Long = 0      // 缓存命中的 pHash，用于验证 historyId 有效性

    // 复制模式
    private var isCopyMode = false
    private var copyOriginalMode = false  // false=译文, true=原文
    private var copyClickLayer: android.widget.FrameLayout? = null
    private var copyBubbleViews: MutableList<View> = mutableListOf()
    private var copyButtonsContainer: android.widget.LinearLayout? = null
    private var currentShowBubbles: List<TranslatedBubble> = emptyList()  // 当前显示的翻译气泡（非缓存）
    private var renderToggleJob: kotlinx.coroutines.Job? = null  // toggle 渲染协程，避免重复渲染
    @Volatile private var currentOriginalBitmap: Bitmap? = null  // 原始截图（用于原文模式重新渲染）
    private var currentOverlayBitmapW: Int = 0  // 当前 overlay 对应的 bitmap 宽度（用于坐标映射）
    private var currentOverlayBitmapH: Int = 0  // 当前 overlay 对应的 bitmap 高度（用于坐标映射）
    private var lastCacheBubbleRects: String? = null  // 缓存命中的气泡 rect JSON
    private var cachedOriginalTextList: List<String> = emptyList()  // 缓存结果解析后的原文列表
    private var cachedTranslatedTextList: List<String> = emptyList()  // 缓存结果解析后的译文列表

    // 翻译会话 ID（每次服务启动生成新的）
    private val sessionId = java.util.UUID.randomUUID().toString()
    private var currentPHash = 0L
    private var currentExtHashes: LongArray? = null  // 256-bit 扩展哈希（用于缓存匹配和存储）
    private var cacheOverlayContainer: android.widget.FrameLayout? = null
    private var cacheOverlayImage: ImageView? = null  // cache overlay 中的 ImageView 引用

    // 无障碍重截图：干净截图到达后拦截处理
    private var pendingCleanScreenshot = false
    private var pendingDetectionPHash = 0L
    private var pendingDetectionExtHashes: LongArray? = null

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

        // 注册悬浮球图标变更广播（Personalization 设置页改图标时实时刷新）
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                val key = intent?.getStringExtra("extra_icon_key") ?: return
                if (key == "Icon_Comic") {
                    // 重读状态机的 Idle 图标路径
                    ballStateManager?.setState(BallStateManager.State.Idle)
                }
            }
        }
        iconChangeReceiver = receiver
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this)
            .registerReceiver(receiver, android.content.IntentFilter("action_floating_ball_icon_changed"))

        statusOverlay = TranslationStatusOverlay.getInstance(this)
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

        // 监听源语言、引擎、结果样式变化，实时检查语言/模型提示并刷新 config
        val watchedKeys = setOf(
            "Source_Language",
            "Manga_Det_Model",
            "Manga_Rec_Model",
            "Manga_Keep_Text_Free",
            "Manga_Text_Color",
            "Manga_BG_Color"
        )
        prefChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key in watchedKeys) {
                config = loadConfig()
                checkLanguageHints()
            }
        }
        prefs.getSharedPreferences().registerOnSharedPreferenceChangeListener(prefChangeListener)

        // 互斥：停止普通翻译服务
        try {
            stopService(Intent(this, com.moe.starflow.translate.FloatingBallService::class.java))
        } catch (e: Exception) {
            LogCollector.w(TAG, "Could not stop FloatingBallService", e)
        }

        createNotificationChannel()
        // 先用 specialUse 启动（兼容无障碍模式），需要 MediaProjection 时再升级
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

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
            OcrEngine.PPOcrV6 -> lifecycleScope.launch { initPPOcrV6("识别器") }
        }

        // 初始化检测引擎（检测器）
        when (config.detEngine) {
            DetEngine.MLKIT -> {}
            DetEngine.RT_DETR_V2 -> lifecycleScope.launch { initRTDetrV2() }
            DetEngine.PP_OCR_V5 -> lifecycleScope.launch { initPPOcrV5("检测器") }
            DetEngine.PP_OCR_V6 -> lifecycleScope.launch { initPPOcrV6("检测器") }
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
        // 注销悬浮球图标变更广播
        iconChangeReceiver?.let {
            androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this).unregisterReceiver(it)
        }
        iconChangeReceiver = null
        // 释放截图提供者
        screenshotProvider?.release()
        stopForegroundForScreenshot()
        // 注销 SharedPreferences listener
        prefChangeListener?.let {
            prefs.getSharedPreferences().unregisterOnSharedPreferenceChangeListener(it)
        }
        prefChangeListener = null
        // 先取消协程，防止 View 清理后又由协程回调添加新 View
        lifecycleScope.cancel()
        removeAllViews()
        statusOverlay.release()
        ballStateManager?.release()
        ballStateManager = null
        translatorText?.release()
        autoTranslateHandler.removeCallbacksAndMessages(null)
        clearRegionCache()

        // 等待正在执行的 ONNX 推理完成后再释放资源
        // 防止 session.close() 和 session.run() 并发导致 native 内存损坏
        runBlocking(Dispatchers.IO) {
            coroutineContext[Job]?.children?.forEach { it.join() }
        }

        // 释放 OCR 引擎资源
        when (config.ocrEngine) {
            OcrEngine.MLKit -> {}
            OcrEngine.MangaOcr -> releaseMangaOcr()
            OcrEngine.PPOcrV5 -> releasePPOcrV5()
            OcrEngine.PPOcrV6 -> releasePPOcrV6()
        }

        // 释放检测引擎资源
        when (config.detEngine) {
            DetEngine.MLKIT -> {}
            DetEngine.RT_DETR_V2 -> releaseRTDetrV2()
            DetEngine.PP_OCR_V5 -> releasePPOcrV5()
            DetEngine.PP_OCR_V6 -> releasePPOcrV6()
        }

        // 发送广播通知 UI 更新按钮状态
        val stopIntent = Intent(com.moe.starflow.translate.BroadcastAction.ACTION_MANGA_SERVICE_STOPPED)
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this).sendBroadcast(stopIntent)
        LogCollector.d(TAG, "MangaFloatingService destroyed")
    }

    // ---------- Initialization ----------

    // 初始化截图提供者
    private fun initScreenshotProvider() {
        val method = prefs.getString("Screenshot_Method", "0").toIntOrNull() ?: 0
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
                Constants.TextApi.AI.id -> when (prefs.getInt("Text_AI", Constants.TextAI.NLLB.id)) {
                    Constants.TextAI.NLLB.id, 1 -> translatorText = NLLBTranslation(this)  // 1 = 升级前 NLLB 旧值
                    else -> { showToast("Unknown Translator.") }
                }
                Constants.TextApi.BING.id -> translatorText = BingTranslation()
                Constants.TextApi.NIUTRANS.id -> translatorText = NiuTranslation(KeystoreManager.retrieveKey(this, "Niutrans")!!)
                Constants.TextApi.OPENAI.id -> {
                    val providerList = ConfigurationStorage.loadAllProviders(prefs)
                    val selectedIndex = prefs.getInt("OpenAI_Selected_Provider", 0)
                    if (providerList.isNotEmpty() && selectedIndex < providerList.size) {
                        val provider = providerList[selectedIndex]
                        // 用户自定义 API：continuationType 不准确，禁用 prefill 避免服务端 hang
                        // 用户自定义 API：漫画 prompt 未配置时回退到游戏 prompt，避免空 prompt 导致模型返回聊天回复
                        val effectiveContinuationType = if (provider.isBuiltin) {
                            provider.continuationType
                        } else {
                            OpenAIProviderConfig.CONTINUATION_NONE
                        }
                        val effectiveSystemPrompt = if (provider.isBuiltin) {
                            provider.mangaSystemPrompt.ifEmpty { provider.defaultMangaSystemPrompt }
                        } else {
                            provider.mangaSystemPrompt.ifEmpty { BuiltinProviders.DEFAULT_MANGA_SYSTEM_PROMPT }
                        }
                        val effectiveUserPrompt = if (provider.isBuiltin) {
                            provider.mangaUserPrompt.ifEmpty { provider.defaultMangaUserPrompt }
                        } else {
                            provider.mangaUserPrompt.ifEmpty { BuiltinProviders.DEFAULT_MANGA_USER_PROMPT }
                        }
                        translatorText = OpenAITranslation(
                            apiKey = provider.apiKey,
                            baseUrl = provider.baseUrl,
                            model = provider.modelName,
                            systemPrompt = effectiveSystemPrompt,
                            userPrompt = effectiveUserPrompt,
                            continuationType = effectiveContinuationType,
                            prefillContent = if (effectiveContinuationType != OpenAIProviderConfig.CONTINUATION_NONE && effectiveContinuationType != OpenAIProviderConfig.CONTINUATION_JSON) "[1] " else "",
                            autoAppendPath = provider.autoAppendPath
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

    private fun initPPOcrV6(role: String = "检测器") {
        lifecycleScope.launch {
            try {
                initPPOcrV6IfNeeded()
                showToast("PP-OCRv6${role}初始化成功")
            } catch (e: Exception) {
                LogCollector.e(TAG, "PP-OCRv6${role}初始化失败", e)
                showToast("PP-OCRv6${role}初始化失败: ${e.message ?: "未知错误"}")
            }
        }
    }

    private suspend fun initPPOcrV6IfNeeded() {
        if (PPOcrV6Engine.isInitialized) return
        try {
            LogCollector.d(TAG, "initPPOcrV6IfNeeded: 开始初始化 PP-OCRv6")
            withContext(Dispatchers.IO) {
                PPOcrV6Engine.initialize(this@MangaFloatingService)
            }
            LogCollector.d(TAG, "initPPOcrV6IfNeeded: PP-OCRv6 初始化完成")
        } catch (e: Exception) {
            LogCollector.e(TAG, "initPPOcrV6IfNeeded: 初始化失败", e)
            throw e
        }
    }

    private fun releasePPOcrV6() {
        try {
            LogCollector.d(TAG, "releasePPOcrV6: 释放 PP-OCRv6 资源")
            PPOcrV6Engine.release()
        } catch (e: Exception) {
            LogCollector.e(TAG, "releasePPOcrV6: 释放失败", e)
        }
    }

    /**
     * 确保 manga-ocr 已初始化。
     * 优先使用已下载的模型（通过 MangaOcrModelFiles 管理），
     * 如果没有下载的模型则提示用户去下载。
     */
    private suspend fun ensureMangaOcrInitialized() {
        val currentConfig = loadConfig()

        when (currentConfig.ocrEngine) {
            OcrEngine.MangaOcr -> {
                if (MangaOcrModelFiles.isModelDownloaded(this@MangaFloatingService)) {
                    try {
                        // 如果已初始化，直接返回
                        if (MangaOcrRecognizer.isInitialized) {
                            LogCollector.d(TAG, "ensureMangaOcrInitialized: manga-ocr 已初始化")
                            return
                        }
                        LogCollector.d(TAG, "ensureMangaOcrInitialized: 加载 manga-ocr 模型")
                        withContext(Dispatchers.IO) {
                            MangaOcrBridge.initializeDownloaded(this@MangaFloatingService)
                        }
                    } catch (e: Exception) {
                        LogCollector.e(TAG, "manga-ocr 识别器初始化失败", e)
                        withContext(Dispatchers.Main) {
                            statusOverlay.showError("manga-ocr 识别器初始化失败：${e.message ?: "未知错误"}")
                            ballStateManager?.setState(BallStateManager.State.Error)
                        }
                        return
                    }
                } else {
                    // 未下载，提示用户去下载
                    LogCollector.d(TAG, "ensureMangaOcrInitialized: manga-ocr 未下载，提示用户")
                    withContext(Dispatchers.Main) {
                        statusOverlay.showImmediate(getString(R.string.manga_ocr_download_required))
                        ballStateManager?.setState(BallStateManager.State.Idle)
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
        val detEngine = DetEngine.fromValue(prefs.getInt("Manga_Det_Model", DetEngine.PP_OCR_V6.value))
        // RT-DETR-V2 检测器已输出气泡/区域级结果，不需要 BubbleDetector 再次聚类
        val autoDetectBubble = if (detEngine == DetEngine.RT_DETR_V2) {
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
            keepTextFree = prefs.getBoolean("Manga_Keep_Text_Free", true)
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

        ballStateManager = BallStateManager(this, floatingBallView, BallStateManager.Mode.Comic)
        ballStateManager?.setState(BallStateManager.State.Idle)

        // 加载自定义悬浮球图标
        // 一次性迁移：旧 Custom_Floating_Pic 首次遇到时复制到 Icon_Comic
        if (!prefs.contains("Icon_Comic")) {
            val legacy = prefs.getString("Custom_Floating_Pic", "")
            if (legacy.isNotEmpty()) prefs.setString("Icon_Comic", legacy)
        }

        val iconName = prefs.getString("Icon_Comic", "comic-1.准备识别-打开漫画页面.png")
        val iconView = floatingBallView.findViewById<ImageView>(R.id.floating_ball_icon)
        if (iconName.isEmpty()) {
            iconView.setImageResource(R.mipmap.icon_comic_default)
        } else {
            val iconFile = java.io.File(getExternalFilesDir(null), "icon/$iconName")
            try {
                if (iconFile.exists()) {
                    val bitmap = android.graphics.BitmapFactory.decodeFile(iconFile.absolutePath)
                    iconView.setImageBitmap(bitmap)
                } else {
                    iconView.setImageResource(R.mipmap.icon_comic_default)
                }
            } catch (e: Exception) {
                LogCollector.w(TAG, "Failed to load custom icon", e)
                iconView.setImageResource(R.mipmap.icon_comic_default)
            }
        }

        // 加载长按判定时间
        longPressDelay = prefs.getLong("Custom_Long_Press_Delay", 300L)

        setupTouchListener()

        // Result overlay (initially not added) — FrameLayout 包含 ImageView，按钮可加入同一窗口
        resultOverlayImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_XY
        }
        resultOverlayView = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(180, 0, 0, 0))
            addView(resultOverlayImage, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
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

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
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

        isMenuShowing = true

        // 普通模式：固定搭配，3 个合法组合循环（高级模式菜单已删除）
        showMenuSimple(cropLabel)
    }

    /**
     * 普通模式菜单：从 engineCombos 读取当前标签
     */
    private fun showMenuSimple(cropLabel: String) {
        val modelLabel = comboLabel(currentCombo())

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
                        val adapter = listView.adapter as com.moe.starflow.translate.MenuDialogAdapter
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
                        val adapter = listView.adapter as com.moe.starflow.translate.MenuDialogAdapter
                        adapter.updateLabel(3, "${getString(R.string.game_switch_language)}：${getCurrentSourceLangName()}")
                    }
                }
                4 -> {
                    // 自动翻译
                    toggleAutoTranslate()
                    val adapter = listView.adapter as com.moe.starflow.translate.MenuDialogAdapter
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
        // 竖屏宽度限制，横屏保持原有比例
        val screenSize = getScreenSize()
        if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            val maxW = (screenSize.width * 0.4).toInt()
            val maxH = (screenSize.height * 0.7).toInt()
            dialog.window?.setLayout(maxW, maxH)
        } else {
            val maxW = (screenSize.width * 0.80f).toInt()
            dialog.window?.setLayout(maxW, ViewGroup.LayoutParams.WRAP_CONTENT)
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

    /**
     * 语言/模型可用性提示（系统 Toast）
     * 触发点：onCreate、toggleModelSimple、SharedPreferences listener
     *
     * 场景：
     * 1. 漫画翻译运行中 + 非日文 → 提示
     * 2. 韩文 + PP引擎 + KO未下载 → 提示下载
     * 3. 俄文 + 非PP引擎 → 提示切换到PP
     * 4. 俄文 + PP引擎 + RU未下载 → 提示下载
     */
    private fun checkLanguageHints() {
        val isPPv5 = config.ocrEngine == OcrEngine.PPOcrV5 || config.detEngine == DetEngine.PP_OCR_V5
        val isPPv6 = config.ocrEngine == OcrEngine.PPOcrV6 || config.detEngine == DetEngine.PP_OCR_V6
        val isAnyPP = isPPv5 || isPPv6
        val isMangaOcr = config.ocrEngine == OcrEngine.MangaOcr
        val src = config.sourceLang

        // 俄文：需要 PP 引擎（v5 或 v6）
        if (src == "ru" && !isAnyPP) {
            showSystemToast(getString(R.string.ru_need_ppocrv5_engine))
            return
        }
        // PP-OCRv5：检查 KO/RU 等需要下载独立模型的语言
        if (isPPv5) {
            val (_, hint) = PPOcrV5Engine.resolveRecLang(this, src)
            if (hint != null) {
                showSystemToast(hint)
                return
            }
        }
        // PP-OCRv6：多语言模型内置，无需额外下载检查

        // manga-ocr 模型 + 非日文 → 提示
        if (isMangaOcr && src != "ja") {
            showSystemToast(getString(R.string.manga_ocr_non_ja_hint))
        }
    }

    private fun showSystemToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /**
     * 循环切换源语言：ja → en → zh → zh-TW → ko → ru → ja
     * 跳过 OCR 模型不可用的语言（PP-OCRv5 的 KO/RU 需要检查是否已下载）
     */
    private fun cycleSourceLang() {
        val langCycle = arrayOf("ja", "en", "zh", "zh-TW", "ko", "ru")
        val current = prefs.getString("Source_Language", "ja")
        val currentIdx = langCycle.indexOf(current).coerceAtLeast(0)

        for (i in 1..langCycle.size) {
            val nextIdx = (currentIdx + i) % langCycle.size
            val nextLang = langCycle[nextIdx]
            if (isOcrLangAvailable(nextLang)) {
                prefs.setString("Source_Language", nextLang)
                config = loadConfig()  // 重新加载配置
                val langName = com.moe.starflow.translate.CustomLocale.getInstance(nextLang).getDisplayName()
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
        val isPPv5 = config.ocrEngine == OcrEngine.PPOcrV5 || config.detEngine == DetEngine.PP_OCR_V5
        val isPPv6 = config.ocrEngine == OcrEngine.PPOcrV6 || config.detEngine == DetEngine.PP_OCR_V6
        if (!isPPv5 && !isPPv6) return true  // 非 PP 不需要检查
        // PP-OCRv6：多语言模型内置，所有语言都支持
        if (isPPv6) return true
        // PP-OCRv5：需要检查 KO/RU/EN 模型是否已下载
        return when (lang) {
            "zh", "zh-TW", "ja" -> true  // 内置模型
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
        return com.moe.starflow.translate.CustomLocale.getInstance(lang).getDisplayName()
    }

    /**
     * 切换模型组合（循环遍历 engineCombos，跳过不可用的）
     */
    private fun toggleModelSimple(@Suppress("UNUSED_PARAMETER") dialog: AlertDialog, listView: android.widget.ListView) {
        val cur = currentCombo()
        val curIdx = engineCombos.indexOf(cur).coerceAtLeast(0)

        // 找下一个可用组合（向前循环，最多绕过一圈）
        var nextIdx = curIdx
        var next: ComboDef
        do {
            nextIdx = (nextIdx + 1) % engineCombos.size
            next = engineCombos[nextIdx]
        } while (!isComboAvailable(next) && nextIdx != curIdx)

        // 释放所有旧引擎
        releaseMangaOcr()
        releasePPOcrV5()
        releasePPOcrV6()
        releaseRTDetrV2()

        applyCombo(next)

        // 更新菜单标签
        val adapter = listView.adapter as com.moe.starflow.translate.MenuDialogAdapter
        adapter.updateLabel(2, "${getString(R.string.manga_model_toggle)}：${comboLabel(next)}")
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
        lastTranslatedTime = 0L
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
        LogCollector.d(TAG, "AutoDetect: state=$detectState, current=$currentHash, prev=$previousScreenshotHash, lastTranslated=$lastTranslatedHash")

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

        var ballWasShowing = false

        if (provider is MediaProjectionProvider) {
            // MediaProjection 模式：需要已初始化（权限在服务启动时请求）
            if (!provider.ensureInitialized()) {
                LogCollector.w(TAG, "MediaProjection not initialized, permission not granted yet")
                showToast("录屏权限未授予，无法截图", true)
                return false
            }
            // 手动模式：截图前隐藏悬浮球，避免遮挡页面内容影响 pHash
            // 自动模式：不隐藏（由 collector 中 STABLE 后的重截图逻辑获取干净截图）
            if (!isAutoTranslating) {
                ballWasShowing = ::floatingBallView.isInitialized &&
                    isViewAdded(floatingBallView) &&
                    floatingBallView.visibility == View.VISIBLE
            }
            // 异步截图：先获取全屏，再由服务层裁剪（保留全屏 bitmap 供缓存使用）
            lifecycleScope.launch {
                if (!isAutoTranslating && ballWasShowing) {
                    floatingBallView.visibility = View.GONE
                    LogCollector.d(TAG, "takeScreenshotWithProvider: 手动模式隐藏悬浮球")
                    // 等至少一个 VSYNC 周期，确保 VD 产出无球的新帧
                    delay(50)
                }
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
                        if (isAutoTranslating) {
                            stopAutoTranslate()
                        }
                    }
                } catch (e: Exception) {
                    LogCollector.e(TAG, "Screenshot exception", e)
                    isProcessing = false
                    if (isAutoTranslating) {
                        stopAutoTranslate()
                    }
                } finally {
                    if (ballWasShowing) {
                        floatingBallView.visibility = View.VISIBLE
                        LogCollector.d(TAG, "takeScreenshotWithProvider: 恢复悬浮球")
                    }
                }
            }
            return true
        } else {
            // AccessibilityService 模式：手动模式截图前隐藏悬浮球
            // 自动模式：不隐藏（由 collector 中 STABLE 后的重截图逻辑获取干净截图）
            if (!isAutoTranslating) {
                ballWasShowing = ::floatingBallView.isInitialized &&
                    isViewAdded(floatingBallView) &&
                    floatingBallView.visibility == View.VISIBLE
                if (ballWasShowing) {
                    floatingBallView.visibility = View.GONE
                }
            }
            lifecycleScope.launch {
                if (ballWasShowing) delay(50)
                LogCollector.d(TAG, "Taking AccessibilityService screenshot")
                try {
                    provider.takeScreenshot(cropRect, offset)
                } finally {
                    if (ballWasShowing) {
                        floatingBallView.visibility = View.VISIBLE
                    }
                }
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
                    // 无障碍重截图拦截：干净截图到达后直接用保存的检测 hash 处理
                    if (pendingCleanScreenshot) {
                        pendingCleanScreenshot = false
                        val detectionPHash = pendingDetectionPHash
                        val detectionExtHashes = pendingDetectionExtHashes
                        pendingDetectionPHash = 0L
                        pendingDetectionExtHashes = null
                        pendingFullBitmap = data.fullBitmap
                        showProgressOverlay(getString(R.string.manga_translating))
                        processMangaScreenshot(ocrBitmap, detectionPHash, detectionExtHashes)
                        return@collect
                    }

                    // 自动翻译模式：pHash 门控（手动翻译时跳过）
                    if (isAutoTranslating && !isManualTranslating) {
                        // 40s 超时省电检测：同一页面超过 40s 未变化，自动停止翻译
                        if (lastTranslatedTime > 0 && System.currentTimeMillis() - lastTranslatedTime > 40_000) {
                            LogCollector.d(TAG, "Screenshot collector: 40s 超时，页面未变化，停止自动翻译")
                            ocrBitmap.recycle()
                            pendingFullBitmap?.recycle()
                            pendingFullBitmap = null
                            if (data.croppedBitmap != null) data.fullBitmap.recycle()
                            isProcessing = false
                            stopAutoTranslate()
                            AlertDialog.Builder(this@MangaFloatingService)
                                .setTitle("自动翻译超时")
                                .setMessage("页面超过 40 秒未变化，已自动停止翻译服务以节省电量。")
                                .setCancelable(false)
                                .setPositiveButton("确定", null)
                                .create()
                                .apply {
                                    window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                                    show()
                                }
                            return@collect
                        }
                        // 用全屏截图计算稳定的 pHash（不受框选偏移影响）
                        val pHash = PerceptualHash.compute(data.fullBitmap, centerCrop = true)
                        // 256-bit 扩展哈希（用于缓存精确匹配）
                        val extHashes = PerceptualHash.computeExtended(data.fullBitmap, centerCrop = true)
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
                        // pHash 通过门控 → 确认翻译，重截干净图（无悬浮球、无进度条）
                        if (screenshotProvider is MediaProjectionProvider) {
                            val mpProvider = screenshotProvider as MediaProjectionProvider
                            val offset = cropView.absolutePointOffset

                            dismissProgressOverlay()
                            val ballWasShowing = ::floatingBallView.isInitialized &&
                                isViewAdded(floatingBallView) &&
                                floatingBallView.visibility == View.VISIBLE
                            if (ballWasShowing) {
                                floatingBallView.visibility = View.GONE
                                LogCollector.d(TAG, "Auto STABLE: 隐藏悬浮球准备重截干净图")
                            }
                            // 等 VD 产新帧（无球的画面），50ms 通常足够 SurfaceFlinger 刷新
                            delay(50)

                            // 截图拿干净图
                            val cleanFull = mpProvider.takeScreenshot(null, offset)
                            if (ballWasShowing) floatingBallView.visibility = View.VISIBLE

                            if (cleanFull != null) {
                                showProgressOverlay(getString(R.string.manga_translating))
                                ocrBitmap.recycle()
                                if (data.croppedBitmap != null) data.fullBitmap.recycle()
                                pendingFullBitmap = cleanFull
                                val rect = cropRect
                                val cleanOcr = if (rect != null) {
                                    ScreenshotManager.cropBitmap(cleanFull, rect, offset)
                                } else cleanFull
                                val cleanExtHashes = PerceptualHash.computeExtended(cleanFull, centerCrop = true)
                                processMangaScreenshot(cleanOcr, pHash, cleanExtHashes)
                            } else {
                                LogCollector.w(TAG, "Auto STABLE: 干净截图返回 null，降级到检测截图")
                                if (ballWasShowing) floatingBallView.visibility = View.VISIBLE
                                showProgressOverlay(getString(R.string.manga_translating))
                                processMangaScreenshot(ocrBitmap, pHash, extHashes)
                            }
                        } else {
                            // 无障碍异步重截：等冷却 → 隐藏球 → 触发截图 → 设标志等下一张 flow
                            // 无障碍 takeScreenshot API 需至少 350ms 冷却，否则系统限流返回失败
                            lifecycleScope.launch {
                                delay(350) // 无障碍截图 API 冷却期（Android 12+ 后台截图频率限制）
                                dismissProgressOverlay()  // 关掉 MOTION 阶段的"检测中..."，避免被截入
                                val ballWasShowing = ::floatingBallView.isInitialized &&
                                    isViewAdded(floatingBallView) &&
                                    floatingBallView.visibility == View.VISIBLE
                                if (ballWasShowing) {
                                    floatingBallView.visibility = View.GONE
                                    LogCollector.d(TAG, "Auto STABLE: 无障碍隐藏悬浮球准备重截")
                                }
                                delay(50)
                                screenshotProvider!!.takeScreenshot(cropRect, cropView.absolutePointOffset)
                                pendingDetectionPHash = pHash
                                pendingDetectionExtHashes = extHashes
                                pendingCleanScreenshot = true
                                if (ballWasShowing) {
                                    floatingBallView.visibility = View.VISIBLE
                                    LogCollector.d(TAG, "Auto STABLE: 无障碍恢复悬浮球")
                                }
                            }
                            // 回收当前带球截图，等干净截图到达
                            ocrBitmap.recycle()
                            pendingFullBitmap?.recycle()
                            pendingFullBitmap = null
                            if (data.croppedBitmap != null) data.fullBitmap.recycle()
                        }
                    } else {
                        // 手动模式：用全屏截图计算稳定的缓存 pHash
                        val cachePHash = PerceptualHash.compute(data.fullBitmap, centerCrop = true)
                        // 256-bit 扩展哈希（用于缓存精确匹配）
                        val extHashes = PerceptualHash.computeExtended(data.fullBitmap, centerCrop = true)
                        // 保存全屏 bitmap 引用用于缓存（不要在翻译前释放）
                        pendingFullBitmap = data.fullBitmap
                        showProgressOverlay("检测中...")
                        try {
                            processMangaScreenshot(ocrBitmap, cachePHash, extHashes)
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
                    ballStateManager?.setState(BallStateManager.State.Error)
                    if (isAutoTranslating) {
                        stopAutoTranslate()
                    }
                } catch (e: Exception) {
                    LogCollector.e(TAG, "Screenshot collector: CAUGHT EXCEPTION", e)
                    isProcessing = false
                    // 先 dismiss 进度条，再显示错误（错误会保持显示直到用户点击复制）
                    dismissProgressOverlay()
                    statusOverlay.showError("翻译失败：${e.message ?: "Unknown error"}")
                    ballStateManager?.setState(BallStateManager.State.Error)
                    if (isAutoTranslating) {
                        stopAutoTranslate()
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE")
            LogCollector.e(TAG, "Screenshot collector: collect() returned unexpectedly", null)
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
    private suspend fun saveCacheEntry(original: Bitmap, allBubbles: List<TranslatedBubble>) {
        try {
            val translatorName = buildTranslatorDisplayName()
            val ocrTexts = allBubbles.mapIndexed { i, b -> "[${i + 1}] ${b.originalText}" }.joinToString("\n")
            val transTexts = allBubbles.mapIndexed { i, b -> "[${i + 1}] ${b.translatedText}" }.joinToString("\n")
            LogCollector.d(TAG, "saveCacheEntry: ${allBubbles.size} 个气泡")
            // 使用实际裁剪坐标（如果有 cropRect）或全屏尺寸
            val fullWidth = pendingFullBitmap?.width ?: original.width
            val fullHeight = pendingFullBitmap?.height ?: original.height
            val useCrop = cropRect != null
            val entry = CacheEntry(
                type = TranslationCacheManager.MODE_MANGA,
                sourceText = ocrTexts.ifEmpty { null },
                translatedText = transTexts.ifEmpty { null },
                resultBitmap = null,
                sourceLang = config.sourceLang,
                targetLang = config.targetLang,
                translatorName = translatorName,
                pHash = (currentExtHashes?.getOrElse(0) { currentPHash }) ?: currentPHash,
                pHash2 = currentExtHashes?.getOrElse(1) { 0L } ?: 0L,
                pHash3 = currentExtHashes?.getOrElse(2) { 0L } ?: 0L,
                pHash4 = currentExtHashes?.getOrElse(3) { 0L } ?: 0L,
                sessionId = sessionId,
                lastSessionId = sessionId,
                cropLeft = if (useCrop) cropRect!!.left.toInt() else 0,
                cropTop = if (useCrop) cropRect!!.top.toInt() else 0,
                cropRight = if (useCrop) cropRect!!.right.toInt() else fullWidth,
                cropBottom = if (useCrop) cropRect!!.bottom.toInt() else fullHeight,
                bubbleRects = if (allBubbles.isNotEmpty()) {
                    serializeBubbleRects(allBubbles)
                } else null
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
            LogCollector.e(TAG, "saveCacheEntry 失败", e)
        }
    }

    /**
     * 分批渲染流程：检测+裁剪 → 分两批 OCR+翻译+渲染。
     * 支持 RT-DETR-V2 + MangaOcr 和 PP-OCRv5 独立两种组合。
     *
     * @return true 如果执行了分批流程，false 如果不满足条件（应回退到原有流程）
     */
    private suspend fun incrementalTranslateFlow(bitmap: Bitmap): Boolean {
        val isIncrementalEnabled = prefs.getBoolean("Incremental_Render", true)
        if (!isIncrementalEnabled) return false

        val isRTDetrMangaOcr = config.detEngine == DetEngine.RT_DETR_V2 && config.ocrEngine == OcrEngine.MangaOcr
        val isPPOcrV5Standalone = config.detEngine == DetEngine.PP_OCR_V5 && config.ocrEngine == OcrEngine.PPOcrV5
        val isPPOcrV6Standalone = config.detEngine == DetEngine.PP_OCR_V6 && config.ocrEngine == OcrEngine.PPOcrV6
        if (!isRTDetrMangaOcr && !isPPOcrV5Standalone && !isPPOcrV6Standalone) return false

        return if (isRTDetrMangaOcr) {
            incrementalRTDetrMangaOcr(bitmap)
        } else if (isPPOcrV6Standalone) {
            incrementalPPOcrV6(bitmap)
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
                firstBatch, config.sourceLang
            )
            LogCollector.d(TAG, "incrementalRTDetrMangaOcr: 第一批 OCR ${firstTextBlocks.size} 个文字块")

            // 保存上下文历史大小，分批翻译完后回滚，避免污染后续页面的上下文
            val contextSnapshotSize = contextHistory.size

            val firstTranslated = if (firstTextBlocks.isEmpty()) {
                emptyList()
            } else {
                val firstBubbleRegions = textBlocksToBubbleRegions(firstTextBlocks)
                withContext(Dispatchers.Main) {
                    showProgressOverlay("翻译进行中，请勿点击屏幕...")
                    ballStateManager?.setState(BallStateManager.State.Translating)
                }

                val ocrJob = lifecycleScope.async(Dispatchers.IO) {
                    DetectionBridge.recognizeCroppedBubbles(
                        secondBatch, config.sourceLang
                    )
                }

                val result = incrementalTranslateBubbles(firstBubbleRegions, forceContext = true)
                if (result.isNotEmpty()) renderAndShowMergedOverlay(bitmap, result, saveCache = false, showCopyButton = false)

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
                ballStateManager?.setState(BallStateManager.State.Error)
                throw e
            } catch (e: Exception) {
                crops.forEach { it.recycle() }
                statusOverlay.showError("识别模型异常：${e.message}")
                ballStateManager?.setState(BallStateManager.State.Error)
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
                withContext(Dispatchers.Main) {
                    showProgressOverlay("翻译进行中，请勿点击屏幕...")
                    ballStateManager?.setState(BallStateManager.State.Translating)
                }

                ocrJob = lifecycleScope.async(Dispatchers.IO) {
                    recognizeBatch(secondBatch)
                }

                val result = incrementalTranslateBubbles(firstBubbleRegions, forceContext = true)
                if (result.isNotEmpty()) renderAndShowMergedOverlay(bitmap, result, saveCache = false, showCopyButton = false)

                val secondTextBlocks = ocrJob.await()
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

    private suspend fun incrementalPPOcrV6(bitmap: Bitmap): Boolean {
        initPPOcrV6IfNeeded()

        LogCollector.d(TAG, "incrementalPPOcrV6: 开始检测")
        val textLines = DetectionBridge.detectAndCropPPOcrV6Lines(this@MangaFloatingService, bitmap)
        if (textLines.isEmpty()) {
            LogCollector.d(TAG, "incrementalPPOcrV6: 未检测到文字")
            if (!isAutoTranslating) {
                withContext(Dispatchers.Main) { showToast(getString(R.string.no_text_found), true) }
            }
            return true
        }

        if (textLines.size <= INCREMENTAL_THRESHOLD) {
            LogCollector.d(TAG, "incrementalPPOcrV6: ${textLines.size} <= $INCREMENTAL_THRESHOLD，不触发")
            textLines.forEach { it.croppedBitmap.recycle() }
            return false
        }

        val groups = groupByProximity(textLines, { it.rect }, "PP-OCRv6")
        val (firstBatch, secondBatch) = splitAtGroupBoundaries(groups)
        LogCollector.d(TAG, "incrementalPPOcrV6: 第一批 ${firstBatch.size} 行，第二批 ${secondBatch.size} 行")

        suspend fun recognizeBatch(batch: List<CroppedTextLine>): List<TextBlockInfo> {
            val crops = batch.map { it.croppedBitmap }
            val rects = batch.map { it.rect }
            val angles = batch.map { it.angle }
            val centers = batch.map { android.graphics.PointF(it.centerX, it.centerY) }
            val recResults = try {
                withContext(Dispatchers.IO) {
                    PPOcrV6Engine.recognizeBatchWithCls(this@MangaFloatingService, crops)
                }
            } catch (e: java.io.FileNotFoundException) {
                crops.forEach { it.recycle() }
                statusOverlay.showError("识别模型加载失败：${e.message}")
                ballStateManager?.setState(BallStateManager.State.Error)
                throw e
            } catch (e: Exception) {
                crops.forEach { it.recycle() }
                statusOverlay.showError("识别模型异常：${e.message}")
                ballStateManager?.setState(BallStateManager.State.Error)
                throw e
            }
            crops.forEach { it.recycle() }
            val mergedInput = PPOcrV6Engine.recResultsToTextLines(recResults, rects, angles, centers)
            TextRegionMerger.refreshParams(this@MangaFloatingService)
            val allMerged = TextRegionMerger.merge(mergedInput.map { it.toTextRegion() })
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
            LogCollector.d(TAG, "incrementalPPOcrV6: 第一批 OCR ${firstTextBlocks.size} 个文字块")

            val contextSnapshotSize = contextHistory.size
            val firstTranslated = if (firstTextBlocks.isEmpty()) {
                emptyList()
            } else {
                val firstBubbleRegions = textBlocksToBubbleRegions(firstTextBlocks)
                withContext(Dispatchers.Main) {
                    showProgressOverlay("翻译进行中，请勿点击屏幕...")
                    ballStateManager?.setState(BallStateManager.State.Translating)
                }

                ocrJob = lifecycleScope.async(Dispatchers.IO) {
                    recognizeBatch(secondBatch)
                }

                val result = incrementalTranslateBubbles(firstBubbleRegions, forceContext = true)
                if (result.isNotEmpty()) renderAndShowMergedOverlay(bitmap, result, saveCache = false, showCopyButton = false)

                val secondTextBlocks = ocrJob.await()
                ocrJob = null
                LogCollector.d(TAG, "incrementalPPOcrV6: 第二批 OCR ${secondTextBlocks.size} 个文字块")
                if (secondTextBlocks.isNotEmpty()) {
                    val secondBubbleRegions = textBlocksToBubbleRegions(secondTextBlocks)
                    result + incrementalTranslateBubbles(secondBubbleRegions, forceContext = true)
                } else {
                    result
                }
            }

            while (contextHistory.size > contextSnapshotSize) {
                contextHistory.removeLast()
            }

            finalizeIncremental(bitmap, firstTranslated)
            return true
        } catch (e: Exception) {
            LogCollector.e(TAG, "incrementalPPOcrV6: 失败", e)
            ocrJob?.cancel()
            secondBatch.forEach { if (!it.croppedBitmap.isRecycled) it.croppedBitmap.recycle() }
            return false
        }
    }

    /**
     */
    private suspend fun finalizeIncremental(bitmap: Bitmap, allTranslated: List<TranslatedBubble>) {
        if (allTranslated.isNotEmpty()) {
            renderAndShowMergedOverlay(bitmap, allTranslated, saveCache = false)
            LogCollector.d(TAG, "finalizeIncremental: 最终渲染完成，共 ${allTranslated.size} 个气泡")
            // Translating 状态已在分批翻译入口处设置（第一批翻译开始时），
            // 此处不再重复设置，避免图标在翻译完成后才短暂闪过。
            // 统一保存完整缓存
            LogCollector.d(TAG, "finalizeIncremental: 保存完整缓存，共 ${allTranslated.size} 个气泡")
            saveCacheEntry(bitmap, allTranslated)
            ballStateManager?.setState(BallStateManager.State.Completed)
        }
        statusOverlay.showImmediate("翻译完成")
        ballStateManager?.setState(BallStateManager.State.Completed)
        lastTranslatedHash = currentPHash
        lastTranslatedTime = System.currentTimeMillis()
        if (isAutoTranslating) scheduleNextDetection(DETECT_INTERVAL_MS)
    }

    private suspend fun processMangaScreenshot(bitmap: Bitmap, precomputedPHash: Long? = null, precomputedExtHashes: LongArray? = null) {
        try {
            LogCollector.d(TAG, "processMangaScreenshot: START")
            // 整个翻译流程开始（OCR 阶段），立刻标 Processing。
            // 单页路径会一路保持 Processing 直到 Step 3 调翻译；分批路径同理保持到 finalizeIncremental。
            ballStateManager?.setState(BallStateManager.State.Processing)

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
            // 256-bit 扩展哈希（用于缓存精确匹配和存储）
            currentExtHashes = precomputedExtHashes
                ?: PerceptualHash.computeExtended(pendingFullBitmap ?: bitmap, centerCrop = true)

            // 纯色/均匀页面精确检测：dHash 全零说明中心区域无任何纹理结构（纯白/纯黑/纯色）
            // 全零 dHash 会假命中低纹理缓存页面（curBits=0/256 匹配 entryBits=7/256, sim=0.973）
            // 检测到无内容 → 跳过缓存+OCR+翻译，回到 IDLE
            if (currentExtHashes != null && currentExtHashes!!.all { it == 0L }) {
                LogCollector.d(TAG, "processMangaScreenshot: 画面无内容（dHash全零），跳过翻译")
                statusOverlay.showImmediate("未检测到文字")
                ballStateManager?.setState(BallStateManager.State.Completed)
                showToast("未检测到文字", false)
                lastTranslatedHash = currentPHash
        lastTranslatedTime = System.currentTimeMillis()
                if (isAutoTranslating) {
                    scheduleNextDetection(DETECT_INTERVAL_MS)
                }
                return
            }

            // 调试模式：最高优先级，跳过缓存直接检测
            val isDebugMode = when (config.detEngine) {
                DetEngine.RT_DETR_V2 -> prefs.getBoolean("RTDetrV2_Debug_View", false)
                DetEngine.MLKIT -> prefs.getBoolean("MLKit_Debug_View", false)
                DetEngine.PP_OCR_V5 -> prefs.getBoolean("PPOcrV5_Debug_View", false)
                DetEngine.PP_OCR_V6 -> prefs.getBoolean("PPOcrV6_Debug_View", false)
            }

            if (isDebugMode) {
                LogCollector.d(TAG, "processMangaScreenshot: Debug mode enabled, skip cache")
                when (config.detEngine) {
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
                    DetEngine.PP_OCR_V6 -> {
                        LogCollector.d(TAG, "PP-OCRv6 Debug Mode: 开始检测+识别")
                        initPPOcrV6IfNeeded()
                        val ocrResult = withContext(Dispatchers.IO) {
                            PPOcrV6Engine.runOCR(this@MangaFloatingService, bitmap, useDet = true)
                        }
                        val debugDet = withContext(Dispatchers.IO) {
                            PPOcrV6Engine.runDetForDebug(this@MangaFloatingService, bitmap)
                        }
                        val recDisc = ocrResult.recDebug
                        val scoreDisc = recDisc?.discardedReasons?.count { it == "score" } ?: 0
                        val contentDisc = recDisc?.discardedReasons?.count { it != "score" } ?: 0
                        LogCollector.d(TAG, "PP-OCRv6 Debug: det=${ocrResult.boxes.size}, rec=${ocrResult.texts.size}, det丢弃=${debugDet.discardedBoxes.size}, 识别丢弃=$scoreDisc, 内容丢弃=$contentDisc")
                        val allMerged = runTextLineMerge(ocrResult, bitmap.width, bitmap.height, isV6 = true)
                        val (mergedRegions, contentDiscarded) = filterMergedRegions(allMerged)
                        LogCollector.d(TAG, "PP-OCRv6 Debug: merged=${allMerged.size}, 内容丢弃=${contentDiscarded.size}, 输出=${mergedRegions.size}")
                        showPPOcrV6DebugView(bitmap, ocrResult, mergedRegions, debugDet)
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
                                PPOcrV5Engine.runOCR(this@MangaFloatingService, bitmap, recLang, useDet = true)
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
                }
                return
            }

            // 全局缓存检查（使用 256-bit 扩展哈希）
            isForceRefreshActive = forceRefresh
            if (!isForceRefreshActive) {
                val extHashesForCache = currentExtHashes
                    ?: PerceptualHash.computeExtended(pendingFullBitmap ?: bitmap, centerCrop = true)
                val (curLeft, curTop) = cropRect?.let { Pair(it.left.toInt(), it.top.toInt()) } ?: Pair(-1, -1)
                val cached = cacheManager.findCacheExt(
                    extHashesForCache,
                    TranslationCacheManager.MODE_MANGA,
                    bitmap.width, bitmap.height,
                    curLeft, curTop,
                    sessionId
                )
                if (cached != null && cached.resultBitmap != null) {
                    LogCollector.d(TAG, "processMangaScreenshot: 缓存命中, historyId=${cached.historyId}")
                    lastCachedHistoryId = cached.historyId
                    lastCachedPHash = currentPHash
                    lastCacheBubbleRects = cached.bubbleRects
                    // 解析缓存的原文/译文列表供复制模式使用
                    cachedOriginalTextList = TranslationCacheManager.parseIndexedTextList(cached.originalText)
                    cachedTranslatedTextList = TranslationCacheManager.parseIndexedTextList(cached.translatedText)
                    // 从缓存数据重建 TranslatedBubble 列表（供原文模式 overlay 渲染）
                    currentShowBubbles = TranslationCacheManager.rebuildBubblesFromCache(
                        cachedOriginalTextList, cachedTranslatedTextList, lastCacheBubbleRects, config.fontSize, config.bgColor)
                    // 通过共享层渲染裁剪区域的译文 overlay（替代加载预渲染 JPEG）
                    // 仅新数据（有 bubbleRects）走实时渲染；旧数据回退到 imagePath
                    val rendered = if (cached.historyEntity != null && cached.pageCache != null
                        && !cached.bubbleRects.isNullOrBlank()) {
                        cacheManager.renderOverlay(
                            history = cached.historyEntity,
                            pageCache = cached.pageCache,
                            mode = TranslationCacheManager.OverlayMode.TRANSLATED,
                            forFullImage = false,
                            config = TranslationCacheManager.OverlayConfig(config.fontSize, config.autoFontSize, config.textColor, config.bgColor)
                        )
                    } else null
                    statusOverlay.showImmediate("缓存命中")
                    ballStateManager?.setState(BallStateManager.State.Completed)
                    lastTranslatedHash = currentPHash
        lastTranslatedTime = System.currentTimeMillis()
                    withContext(Dispatchers.Main) {
                        currentOriginalBitmap?.recycle()
                        currentOriginalBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                        // 若实时渲染成功，释放 buildCacheResult 加载的原图（不再需要）
                        if (rendered != null) cached.resultBitmap?.recycle()
                        showResultOverlay(rendered ?: cached.resultBitmap, fromCache = true)
                    }
                    return
                }
            } else {
                LogCollector.d(TAG, "processMangaScreenshot: 强制刷新，跳过缓存")
                forceRefresh = false
            }

            // OcrLock: 保护 ONNX 模型的多线程访问（PP-OCRv5/manga-ocr 等单例引擎）
            if (!com.moe.starflow.manga.OcrLock.tryAcquire()) {
                scheduleNextDetection(DETECT_INTERVAL_MS)
                isProcessing = false
                return
            }
            var ocrTextBlocks: List<TextBlockInfo>
            try {
                // 分批渲染：在检测之前尝试分批流程。
                // BUGFIX (2026-07-06): 之前这里设 Translating，但分批翻译入口还在 OCR 阶段（可能包含多批 OCR），
                // 切到 Translating 让图标闪。现在保持 Processing，由 finalizeIncremental 在所有气泡 OCR 完成后切 Translating。
                ballStateManager?.setState(BallStateManager.State.Processing)
                if (incrementalTranslateFlow(bitmap)) {
                    LogCollector.d(TAG, "processMangaScreenshot: 分批渲染完成，跳过原有流程")
                    return
                }
                LogCollector.d(TAG, "processMangaScreenshot: 分批渲染未触发，走原有流程")
                // 确保选中的模型已初始化
                when (config.detEngine) {
                    DetEngine.MLKIT -> {}
                    DetEngine.RT_DETR_V2 -> initRTDetrV2IfNeeded()
                    DetEngine.PP_OCR_V5 -> initPPOcrV5IfNeeded()
                    DetEngine.PP_OCR_V6 -> initPPOcrV6IfNeeded()
                }
                when (config.ocrEngine) {
                    OcrEngine.MLKit -> {}
                    OcrEngine.MangaOcr -> ensureMangaOcrInitialized()
                    OcrEngine.PPOcrV5 -> initPPOcrV5IfNeeded()
                    OcrEngine.PPOcrV6 -> initPPOcrV6IfNeeded()
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
                ocrTextBlocks = withContext(Dispatchers.IO) {
                    when (config.detEngine) {
                        DetEngine.MLKIT -> {
                            LogCollector.d(TAG, "使用 ML Kit(检测+识别), lang=${config.sourceLang}")
                            OCRBridge.recognizeWithLocation(config.sourceLang, bitmap)
                        }
                        DetEngine.RT_DETR_V2 -> {
                            LogCollector.d(TAG, "使用 RT-DETR-V2(检测) + MangaOcr(识别), lang=${config.sourceLang}")
                            DetectionBridge.detectWithRTDetrV2(bitmap, config.sourceLang, this@MangaFloatingService, config.keepTextFree)
                        }
                        DetEngine.PP_OCR_V5 -> {
                            LogCollector.d(TAG, "使用 PP-OCRv5(独立det+cls+rec), lang=${config.sourceLang}, rec=${ppRecLang?.code}")
                            DetectionBridge.detectWithPPOcrV5(bitmap, config.sourceLang, this@MangaFloatingService)
                        }
                        DetEngine.PP_OCR_V6 -> {
                            LogCollector.d(TAG, "使用 PP-OCRv6(独立det+cls+rec), lang=${config.sourceLang}")
                            DetectionBridge.detectWithPPOcrV6(bitmap, config.sourceLang, this@MangaFloatingService)
                        }
                    }
                }
                LogCollector.d(TAG, "processMangaScreenshot: Step 1 - OCR done, found ${ocrTextBlocks.size} text blocks")
            } finally {
                com.moe.starflow.manga.OcrLock.release()
            }

            // No text handling — outside OcrLock (early return if empty)
            if (ocrTextBlocks.isEmpty()) {
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
            // RT-DETR-V2 检测器直接输出气泡级结果，跳过后合并
            // PP-OCRv5 已在 detectWithPPOcrV5 内部用 TextLineMerger 合并，跳过后合并
            // MLKit 需要 BubbleDetector 把行级结果合并成气泡
            val needsPostMerge = config.detEngine == DetEngine.MLKIT
            val allBubbles = if (needsPostMerge) {
                LogCollector.d(TAG, "processMangaScreenshot: Step 2 - BubbleDetector 后合并")
                BubbleDetector.detectBubbles(ocrTextBlocks, config)
            } else {
                LogCollector.d(TAG, "processMangaScreenshot: Step 2 - 已前合并，跳过后合并")
                ocrTextBlocks.filter { it.boundingBox != null }.map { block ->
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

            // Step 3: 翻译（走文本缓存匹配，自动/手动均适用）
            LogCollector.d(TAG, "processMangaScreenshot: Step 3 - Translate ${allBubbles.size} bubbles")
            // BUGFIX (2026-07-06): 调翻译函数之前立刻切 Translating 图标（之前遗漏，自动模式从 Idle 直接到翻译完成）。
            ballStateManager?.setState(BallStateManager.State.Translating)
            val newTranslatedBubbles = incrementalTranslateBubbles(allBubbles)
            LogCollector.d(TAG, "processMangaScreenshot: Step 3 - done, got ${newTranslatedBubbles.size} results")

            // Step 4: 合并已缓存翻译 + 新翻译，渲染 overlay
            LogCollector.d(TAG, "processMangaScreenshot: Step 4 - Rendering merged overlay")
            renderAndShowMergedOverlay(bitmap, newTranslatedBubbles)
            LogCollector.d(TAG, "processMangaScreenshot: Step 4 - DONE")
            statusOverlay.showImmediate("翻译完成")
            ballStateManager?.setState(BallStateManager.State.Completed)

            // 更新区域缓存和 pHash
            lastTranslatedHash = currentPHash
        lastTranslatedTime = System.currentTimeMillis()
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
        lastTranslatedTime = System.currentTimeMillis()
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
            val combinedText = bubble.texts.map { TranslateUtils.cleanOcrText(it) }.filter { it.isNotBlank() }.joinToString("")
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
                    isInMemoryCache = true
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
        cropBottom: Int = 0,
        showCopyButton: Boolean = true  // 分批中间结果不显示复制按钮
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
            showResultOverlay(resultBitmap, showCopyButton = showCopyButton)
            // 必须在 showResultOverlay 之后赋值，因为 dismissResultOverlay 会清空 currentShowBubbles
            currentShowBubbles = newBubbles
            currentOriginalBitmap?.recycle()
            currentOriginalBitmap = original.copy(Bitmap.Config.ARGB_8888, false)
            currentOverlayBitmapW = resultBitmap.width
            currentOverlayBitmapH = resultBitmap.height
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
                    pHash = (currentExtHashes?.getOrElse(0) { currentPHash }) ?: currentPHash,
                    pHash2 = currentExtHashes?.getOrElse(1) { 0L } ?: 0L,
                    pHash3 = currentExtHashes?.getOrElse(2) { 0L } ?: 0L,
                    pHash4 = currentExtHashes?.getOrElse(3) { 0L } ?: 0L,
                    sessionId = sessionId,
                    lastSessionId = sessionId,
                    isRetranslated = isRetranslate,
                    cropLeft = entryCropLeft,
                    cropTop = entryCropTop,
                    cropRight = entryCropRight,
                    cropBottom = entryCropBottom,
                    bubbleRects = if (newBubbles.isNotEmpty()) {
                        serializeBubbleRects(newBubbles)
                    } else null
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

    /** 构建翻译器显示名：API名(模型名) + 检测器 + 识别器 + 分批等参数 */
    private fun buildTranslatorDisplayName(): String {
        val apiName = translatorText?.javaClass?.simpleName ?: "Unknown"
        val model = translatorText?.modelName ?: ""
        val apiStr = if (model.isNotEmpty()) "$apiName($model)" else apiName

        val det = when (config.detEngine) {
            DetEngine.MLKIT -> "MLKit"
            DetEngine.RT_DETR_V2 -> "RT-DETR"
            DetEngine.PP_OCR_V5 -> "PP-OCRv5"
            DetEngine.PP_OCR_V6 -> "PP-OCRv6"
        }
        val ocr = when (config.ocrEngine) {
            OcrEngine.MLKit -> "MLKit"
            OcrEngine.MangaOcr -> "manga-ocr"
            OcrEngine.PPOcrV5 -> "PP-OCRv5"
            OcrEngine.PPOcrV6 -> "PP-OCRv6"
        }

        val parts = mutableListOf(apiStr, "$det+$ocr")

        // 分批翻译：开关打开 + 支持的组合（RT-DETR+manga-ocr 或 PP-OCRv5 独立）
        val incrementalEnabled = prefs.getBoolean("Incremental_Render", true)
        val isRTDetrMangaOcr = config.detEngine == DetEngine.RT_DETR_V2 && config.ocrEngine == OcrEngine.MangaOcr
        val isPPOcrV5Standalone = config.detEngine == DetEngine.PP_OCR_V5 && config.ocrEngine == OcrEngine.PPOcrV5
        val isPPOcrV6Standalone2 = config.detEngine == DetEngine.PP_OCR_V6 && config.ocrEngine == OcrEngine.PPOcrV6
        if (incrementalEnabled && (isRTDetrMangaOcr || isPPOcrV5Standalone || isPPOcrV6Standalone2)) {
            parts.add("分批✓")
        } else if (incrementalEnabled) {
            parts.add("分批✗")  // 开关打开但组合不支持
        }

        // 自由文字：开关打开 + 检测器是 RT-DETR-V2
        val keepTextFreeEnabled = prefs.getBoolean("Manga_Keep_Text_Free", true)
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

    private suspend fun translateBubbles(
        bubbles: List<BubbleRegion>,
        forceContext: Boolean = false
    ): List<TranslatedBubble> {
        if (translatorText == null) throw RuntimeException("Translation API not initialized")
        return TranslateUtils.translateBubbles(translatorText!!, bubbles, config.sourceLang, config.targetLang, prefs, contextHistory, forceContext)
    }

    // ---------- Result overlay ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun showResultOverlay(bitmap: Bitmap, fromCache: Boolean = false, showCopyButton: Boolean = true) {
        if (isResultShowing) {
            dismissResultOverlay()
        }

        if (fromCache) {
            showCacheOverlay(bitmap)
            return
        }

        currentOverlayBitmapW = bitmap.width
        currentOverlayBitmapH = bitmap.height
        resultOverlayImage.setImageBitmap(bitmap)
        // touch listener 放在 ImageView 上（FrameLayout 最底层子 View），
        // 按钮作为更上层子 View 先收到触摸，不会被打断
        resultOverlayImage.setOnTouchListener { _, event ->
            if (isCopyMode) {
                false  // 复制模式穿透
            } else {
                if (event.action == MotionEvent.ACTION_UP) {
                    dismissResultOverlay()
                }
                true
            }
        }
        // 确保 FrameLayout 本身不拦截触摸（使用默认行为）

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
            resultOverlayImage.scaleType = ImageView.ScaleType.FIT_XY
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
            resultOverlayImage.scaleType = ImageView.ScaleType.FIT_XY
            windowManager.addView(resultOverlayView, params)
        }
        isResultShowing = true

        bringFloatingBallToFront()
        if (showCopyButton) {
            showCopyButtons()
        }
    }

    private fun dismissResultOverlay() {
        if (cacheOverlayContainer != null) {
            dismissCacheOverlay()
            return
        }
        if (isCopyMode) {
            isCopyMode = false
            copyOriginalMode = false  // 退出复制模式时重置为译文，避免下次进入状态错乱
            removeCopyClickLayer()
        }
        removeCopyButtons()
        dismissDebugInfoPanel()
        if (isResultShowing) {
            try {
                // 先 removeView 再清 drawable，避免 FrameLayout 半透明黑色背景在清 bitmap 后、removeView 前那一帧暴露给用户（曾短暂闪烁黑色图层）
                if (resultOverlayView.isAttachedToWindow) {
                    windowManager.removeView(resultOverlayView)
                }
                // 先清除引用再回收，避免 Choreographer 待处理帧使用已回收的 bitmap
                val oldBitmap = (resultOverlayImage.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                resultOverlayImage.setImageBitmap(null)
                oldBitmap?.recycle()
                resultOverlayView.setOnTouchListener(null)
                resultOverlayImage.setOnTouchListener(null)
            } catch (e: Exception) {
                LogCollector.e(TAG, "Error dismissing overlay", e)
            }
            isResultShowing = false
            currentShowBubbles = emptyList()
            currentOriginalBitmap?.recycle()
            currentOriginalBitmap = null

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

        currentOverlayBitmapW = bitmap.width
        currentOverlayBitmapH = bitmap.height

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
        cacheOverlayImage = imageView
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
        lastTranslatedTime = 0L
                translatedRegions.clear()  // 清空内存缓存，避免 ⚡ 标志
                triggerTranslation()
            }
        }
        container.addView(refreshBtn, android.widget.FrameLayout.LayoutParams(
            120, 120
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            setMargins(0, 24, 24, 0)
        })

        // 点击其他区域关闭（复制模式下不拦截，让气泡窗口和按钮处理）
        container.setOnTouchListener { _, event ->
            val refreshRight = screenW - 24
            val refreshLeft = refreshRight - 120
            val refreshTop = 24
            val refreshBottom = refreshTop + 120
            val touchX = event.x.toInt()
            val touchY = event.y.toInt()
            if (touchX in refreshLeft..refreshRight && touchY in refreshTop..refreshBottom) {
                false  // 让刷新按钮处理
            } else if (isCopyMode) {
                false  // 复制模式下不拦截触摸
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
        showCopyButtons()
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
            cacheOverlayImage = null
            isResultShowing = false
            renderToggleJob?.cancel()  // 取消正在进行的渲染，避免 recycled bitmap 被使用
            currentShowBubbles = emptyList()
            currentOriginalBitmap?.recycle()
            currentOriginalBitmap = null
            lastCacheBubbleRects = null
            cachedOriginalTextList = emptyList()
            cachedTranslatedTextList = emptyList()

            // 清理复制模式
            if (isCopyMode) {
                isCopyMode = false
                copyOriginalMode = false  // 退出时重置
                removeCopyClickLayer()
            }
            removeCopyButtons()

            // 重置自动翻译：清除区域缓存，立刻恢复检测
            if (isAutoTranslating) {
                clearRegionCache()
                detectState = DetectState.IDLE
                stableCount = 0
                scheduleNextDetection(0L)
            }
        }
    }

    // ---------- 复制模式 ----------

    private fun serializeBubbleRects(bubbles: List<TranslatedBubble>): String {
        return TranslationCacheManager.serializeBubbleRects(bubbles)
    }

    private fun parseBubbleRectsJson(json: String?): List<android.graphics.Rect> {
        if (json.isNullOrEmpty()) return emptyList()
        return try {
            val result = mutableListOf<android.graphics.Rect>()
            val jsonArray = JSONArray(json)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                result.add(android.graphics.Rect(
                    obj.getInt("l"), obj.getInt("t"),
                    obj.getInt("r"), obj.getInt("b")
                ))
            }
            result
        } catch (e: Exception) {
            LogCollector.e(TAG, "parseBubbleRectsJson failed", e)
            emptyList()
        }
    }

    // parseBubbleEntriesJson / parseIndexedTextList / rebuildBubblesFromCache / BubbleJsonEntry
    // 已搬迁至 TranslationCacheManager，调用时加 TranslationCacheManager. 前缀

    // ---------- 按钮工具方法 ----------

    /** 分段切换控件的两个 TextView，用于更新激活状态 */
    private var toggleSegOriginal: android.widget.TextView? = null
    private var toggleSegTranslation: android.widget.TextView? = null

    /**
     * 创建操作按钮（全部复制、退出等执行动作的按钮）
     */
    private fun createCopyActionBtn(text: String, textSize: Float = 12f): android.widget.TextView {
        val bg = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.argb(160, 30, 30, 30))
            cornerRadius = dpToPx(6).toFloat()
            setStroke(dpToPx(1), Color.argb(80, 255, 255, 255))
        }
        return android.widget.TextView(this).apply {
            this.text = text
            this.textSize = textSize
            setTextColor(Color.argb(220, 255, 255, 255))
            gravity = Gravity.CENTER
            setPadding(dpToPx(10), dpToPx(6), dpToPx(10), dpToPx(6))
            background = bg
            isClickable = true
            isFocusable = true
            setOnTouchListener { v, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(80).start()
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
                    }
                }
                false
            }
        }
    }

    /**
     * 创建分段切换控件 [ 原文 | 译文 ]
     * 激活段白底带圆角，与外层容器边缘对齐
     */
    private fun createSegmentedToggle(): android.widget.LinearLayout {
        val r = dpToPx(6).toFloat()
        val padH = dpToPx(12)
        val padV = dpToPx(6)

        // 左段激活背景：左侧圆角
        val activeBgLeft = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.argb(160, 255, 255, 255))
            cornerRadii = floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
        }
        // 右段激活背景：右侧圆角
        val activeBgRight = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.argb(160, 255, 255, 255))
            cornerRadii = floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
        }

        toggleSegOriginal = android.widget.TextView(this).apply {
            text = getString(R.string.copy_original)
            textSize = 11f
            setTextColor(if (copyOriginalMode) Color.argb(220, 30, 30, 30) else Color.argb(200, 255, 255, 255))
            gravity = Gravity.CENTER
            setPadding(padH, padV, padH, padV)
            background = if (copyOriginalMode) activeBgLeft else null
            setOnClickListener {
                if (!copyOriginalMode) {
                    copyOriginalMode = true
                    updateToggleSegments()
                }
            }
        }

        toggleSegTranslation = android.widget.TextView(this).apply {
            text = getString(R.string.copy_translation)
            textSize = 11f
            setTextColor(if (!copyOriginalMode) Color.argb(220, 30, 30, 30) else Color.argb(200, 255, 255, 255))
            gravity = Gravity.CENTER
            setPadding(padH, padV, padH, padV)
            background = if (!copyOriginalMode) activeBgRight else null
            setOnClickListener {
                if (copyOriginalMode) {
                    copyOriginalMode = false
                    updateToggleSegments()
                }
            }
        }

        val outerBg = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.argb(160, 30, 30, 30))
            cornerRadius = r
            setStroke(dpToPx(1), Color.argb(80, 255, 255, 255))
        }

        return android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            background = outerBg
            addView(toggleSegOriginal)
            addView(toggleSegTranslation)
        }
    }

    /** 更新分段切换的激活状态 + 切换 overlay 显示原文/译文 */
    private fun updateToggleSegments() {
        val r = dpToPx(6).toFloat()
        val activeBgLeft = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.argb(160, 255, 255, 255))
            cornerRadii = floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
        }
        val activeBgRight = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.argb(160, 255, 255, 255))
            cornerRadii = floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
        }
        toggleSegOriginal?.apply {
            background = if (copyOriginalMode) activeBgLeft else null
            setTextColor(if (copyOriginalMode) Color.argb(220, 30, 30, 30) else Color.argb(200, 255, 255, 255))
        }
        toggleSegTranslation?.apply {
            background = if (!copyOriginalMode) activeBgRight else null
            setTextColor(if (!copyOriginalMode) Color.argb(220, 30, 30, 30) else Color.argb(200, 255, 255, 255))
        }
        // 切换 overlay 图片：原文/译文都实时渲染（不再依赖预渲染的 currentTranslatedOverlay）
        renderToggleJob?.cancel()
        renderToggleJob = lifecycleScope.launch {
            val original = currentOriginalBitmap ?: return@launch
            val bubbles = currentShowBubbles
            if (bubbles.isEmpty()) return@launch
            val overlay = withContext(Dispatchers.Default) {
                // 防止竞态：用户可能在 suspension point 期间关闭 overlay 导致 bitmap 被回收
                if (original.isRecycled) return@withContext null
                OverlayRenderer.renderOverlay(
                    original = original,
                    regions = bubbles,
                    fontSize = config.fontSize,
                    autoFit = config.autoFontSize,
                    textColor = config.textColor,
                    bgColor = config.bgColor,
                    useOriginalText = copyOriginalMode
                )
            }
            withContext(Dispatchers.Main) {
                if (!isResultShowing || overlay == null) return@withContext
                val target = cacheOverlayImage ?: resultOverlayImage
                // 先回收旧 bitmap，避免全屏 overlay 每次切换都泄漏一张 ~13MB（OOM 风险）
                val oldBitmap = (target.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                target.setImageBitmap(overlay)
                if (oldBitmap !== overlay) oldBitmap?.recycle()
            }
        }
    }

    private fun showCopyButtons() {
        if (copyButtonsContainer != null) return
        buildCopyButtonsLayout()
    }

    /**
     * 重建按钮面板。独立 WindowManager 窗口，始终在屏幕右下角，
     * enterCopyMode 保证在气泡窗口之后添加，所以 z 层在气泡之上。
     */
    private fun buildCopyButtonsLayout() {
        val oldContainer = copyButtonsContainer
        copyButtonsContainer = null
        toggleSegOriginal = null
        toggleSegTranslation = null

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }

        if (isCopyMode) {
            val gap = dpToPx(4)
            container.addView(createSegmentedToggle(), android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.END
                bottomMargin = gap
            })

            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
            }
            row.addView(createCopyActionBtn(getString(R.string.copy_all), 11f).apply {
                setOnClickListener { copyAllBubbles() }
            })
            row.addView(createCopyActionBtn(getString(R.string.copy_exit), 12f).apply {
                setOnClickListener { toggleCopyMode() }
            })
            container.addView(row, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.END
            })
        } else {
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
            }
            row.addView(createCopyActionBtn(getString(R.string.copy_text), 12f).apply {
                setOnClickListener { toggleCopyMode() }
            })
            container.addView(row, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.END
            })
        }

        copyButtonsContainer = container
        // 独立窗口，屏幕右下角固定位置，不受 crop 影响
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
        windowManager.addView(container, params)

        oldContainer?.let {
            try { if (it.isAttachedToWindow) windowManager.removeView(it) } catch (_: Exception) {}
        }
    }

    private fun removeCopyButtons() {
        toggleSegOriginal = null
        toggleSegTranslation = null
        if (copyButtonsContainer != null) {
            try {
                if (copyButtonsContainer!!.isAttachedToWindow) windowManager.removeView(copyButtonsContainer)
            } catch (_: Exception) {}
            copyButtonsContainer = null
        }
    }

    private fun toggleCopyMode() {
        isCopyMode = !isCopyMode
        if (isCopyMode) {
            enterCopyMode()
        } else {
            exitCopyMode()
        }
    }

    private fun enterCopyMode() {
        createCopyClickLayer()
        buildCopyButtonsLayout()
    }

    private fun exitCopyMode() {
        copyOriginalMode = false  // 退出时重置为译文模式
        removeCopyClickLayer()
        buildCopyButtonsLayout()
    }

    /**
     * 创建复制模式的可点击气泡覆盖层。
     *
     * 使用与 showResultOverlay / showCacheOverlay 完全相同的坐标映射规则：
     * - 全屏模式：bitmap == screen → 1:1 映射
     * - 框选模式：overlay 窗口在 (cropOffset + cropRect.left, cropOffset + cropRect.top)，
     *   气泡 rect 在裁剪后 bitmap 坐标系中 → 需加上窗口偏移量映射到屏幕坐标
     */
    private fun createCopyClickLayer() {
        removeCopyClickLayer()

        val container = android.widget.FrameLayout(this)
        copyBubbleViews.clear()

        // 获取气泡 rect 列表：优先用 currentShowBubbles（新翻译），否则用缓存数据
        val bubbles: List<android.graphics.Rect> = if (currentShowBubbles.isNotEmpty()) {
            currentShowBubbles.map { it.rect }
        } else {
            parseBubbleRectsJson(lastCacheBubbleRects)
        }

        if (bubbles.isEmpty()) {
            LogCollector.d(TAG, "createCopyClickLayer: 无气泡数据，仅支持复制全部")
            return
        }

        // 计算坐标映射参数：与 showResultOverlay / showCacheOverlay 一致
        val screenSize = getScreenSize()
        val screenW = screenSize.width
        val screenH = screenSize.height
        val bitmapW = if (currentOverlayBitmapW > 0) currentOverlayBitmapW else screenW
        val bitmapH = if (currentOverlayBitmapH > 0) currentOverlayBitmapH else screenH

        // overlay 窗口的屏幕坐标偏移和尺寸
        val overlayScreenX: Int
        val overlayScreenY: Int
        val overlayWidth: Int
        val overlayHeight: Int

        if (cropRect != null) {
            val crop = cropRect!!
            val offset = cropView.absolutePointOffset
            overlayScreenX = offset.x + crop.left.toInt()
            overlayScreenY = offset.y + crop.top.toInt()
            overlayWidth = crop.width().toInt()
            overlayHeight = crop.height().toInt()
        } else {
            overlayScreenX = 0
            overlayScreenY = 0
            overlayWidth = screenW
            overlayHeight = screenH
        }

        // bitmap → overlay 缩放比例
        val scaleX = if (bitmapW > 0) overlayWidth.toFloat() / bitmapW else 1f
        val scaleY = if (bitmapH > 0) overlayHeight.toFloat() / bitmapH else 1f

        LogCollector.d(TAG, "createCopyClickLayer: ${bubbles.size} bubbles, " +
            "bitmap=${bitmapW}x${bitmapH}, overlay=${overlayWidth}x${overlayHeight}@($overlayScreenX,$overlayScreenY), " +
            "cropRect=${cropRect != null}, scale=(${scaleX},${scaleY})")

        // 每个气泡一个独立小窗口，精确覆盖气泡区域，不阻挡按钮和悬浮球
        for ((idx, rect) in bubbles.withIndex()) {
            // bitmap 坐标 → 屏幕坐标
            val screenLeft = (rect.left * scaleX + overlayScreenX).toInt()
            val screenTop = (rect.top * scaleY + overlayScreenY).toInt()
            val bubbleW = (rect.width() * scaleX).toInt()
            val bubbleH = (rect.height() * scaleY).toInt()

            val overlay = View(this).apply {
                setBackgroundColor(Color.argb(40, 100, 200, 255))
                setOnClickListener {
                    copyBubbleText(idx)
                    // 高亮反馈 200ms
                    setBackgroundColor(Color.argb(120, 100, 200, 255))
                    postDelayed({
                        setBackgroundColor(Color.argb(40, 100, 200, 255))
                    }, 200)
                }
            }
            copyBubbleViews.add(overlay)

            val params = WindowManager.LayoutParams().apply {
                type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                format = PixelFormat.TRANSLUCENT
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                width = bubbleW
                height = bubbleH
                gravity = Gravity.START or Gravity.TOP
                x = screenLeft
                y = screenTop
            }
            windowManager.addView(overlay, params)
        }
    }

    private fun removeCopyClickLayer() {
        for (v in copyBubbleViews) {
            try {
                if (v.isAttachedToWindow) {
                    windowManager.removeView(v)
                }
            } catch (_: Exception) {}
        }
        copyBubbleViews.clear()
        // 兼容旧版单容器模式
        if (copyClickLayer != null) {
            try {
                if (copyClickLayer!!.isAttachedToWindow) {
                    windowManager.removeView(copyClickLayer)
                }
            } catch (_: Exception) {}
            copyClickLayer = null
        }
    }

    private fun copyBubbleText(idx: Int) {
        val text = if (currentShowBubbles.isNotEmpty()) {
            val bubble = currentShowBubbles.getOrNull(idx) ?: return
            if (copyOriginalMode) bubble.originalText else bubble.translatedText
        } else {
            // 缓存命中：从解析的文本列表中获取
            val list = if (copyOriginalMode) cachedOriginalTextList else cachedTranslatedTextList
            list.getOrNull(idx) ?: return
        }
        copyToClipboard(text)
    }

    private fun copyAllBubbles() {
        if (currentShowBubbles.isNotEmpty()) {
            val regions = currentShowBubbles
            val text = regions.mapIndexed { idx, r ->
                val content = if (copyOriginalMode) r.originalText else r.translatedText
                "[${idx + 1}] $content"
            }.joinToString("\n")
            copyToClipboard(text)
        } else {
            // 缓存命中：使用解析的文本列表，保留原有 [N] 格式
            val list = if (copyOriginalMode) cachedOriginalTextList else cachedTranslatedTextList
            if (list.isEmpty()) return
            val text = list.mapIndexed { idx, content ->
                "[${idx + 1}] $content"
            }.joinToString("\n")
            copyToClipboard(text)
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("copied_text", text))
        // 用 statusOverlay 显示反馈（Service 上下文下系统 Toast 可能被 overlay 遮挡）
        statusOverlay.showImmediate(getString(R.string.text_copied))
        LogCollector.d(TAG, "copyToClipboard: ${text.take(50)}...")
    }

    /**
     * RT-DETR-V2 调试模式：渲染检测结果到图片上并显示
     */
    private fun showRTDetrV2DebugView(bitmap: Bitmap, debugResult: RTDetrV2DebugResult) {
        val debugBitmap = MangaDebugOverlays.renderRTDetrV2DebugOverlay(bitmap, debugResult)
        showRTDetrV2DebugResultOverlay(debugBitmap, debugResult)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showRTDetrV2DebugResultOverlay(debugBitmap: Bitmap, debugResult: RTDetrV2DebugResult) {
        if (isResultShowing) {
            dismissResultOverlay()
        }

        // 应用框选外区域遮罩
        val displayBitmap = MangaDebugOverlays.applyCropDimming(debugBitmap, cropRect, getScreenSize())

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
        val infoPanel = MangaDebugOverlays.createInfoPanelView(this, infoLines)
        val infoPanelParams = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = android.view.Gravity.BOTTOM
        }
        container.addView(infoPanel, infoPanelParams)
        debugInfoPanelContentView = infoPanel  // 记录 infoPanel 引用，折叠时只隐藏它

        // 添加右下角展开/折叠按钮
        val toggleButton = MangaDebugOverlays.createToggleButton(this, onToggle = { if (debugInfoPanelCollapsed) expandDebugInfoPanel() else collapseDebugInfoPanel() })
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
        // 清理复制模式状态
        isCopyMode = false
        removeCopyClickLayer()
        removeCopyButtons()
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
        val debugBitmap = MangaDebugOverlays.renderMLKitDebugOverlay(bitmap, result)
        showMLKitDebugResultOverlay(debugBitmap, result)
    }

    private fun showMLKitDebugResultOverlay(debugBitmap: Bitmap, result: MLKitDebugResult) {
        if (isResultShowing) {
            dismissResultOverlay()
        }

        // 应用框选外区域遮罩
        val displayBitmap = MangaDebugOverlays.applyCropDimming(debugBitmap, cropRect, getScreenSize())

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
        val infoPanel = MangaDebugOverlays.createInfoPanelView(this, infoLines, scrollable = true, maxHeight = getScreenSize().height / 2)
        val infoPanelParams = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = android.view.Gravity.BOTTOM
        }
        container.addView(infoPanel, infoPanelParams)
        debugInfoPanelContentView = infoPanel  // 记录 infoPanel 引用，折叠时只隐藏它

        // 添加右下角展开/折叠按钮
        val toggleButton = MangaDebugOverlays.createToggleButton(this, onToggle = { if (debugInfoPanelCollapsed) expandDebugInfoPanel() else collapseDebugInfoPanel() })
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
    private fun runTextLineMerge(ocrResult: OcrResult, bitmapWidth: Int, bitmapHeight: Int, isV6: Boolean = false): List<TextRegionGroup> {
        val textLines = if (isV6) PPOcrV6Engine.ocrResultToTextLines(ocrResult, bitmapWidth, bitmapHeight)
        else PPOcrV5Engine.ocrResultToTextLines(ocrResult, bitmapWidth, bitmapHeight)
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
        val debugBitmap = MangaDebugOverlays.renderPPOcrV5DebugWithMerge(bitmap, ocrResult, mergedRegions, debugDet, prefs.getFloat("ppocr_text_score_thresh", 0.5f))
        showPPOcrV5DebugResultOverlay(debugBitmap, ocrResult, mergedRegions, debugDet)
    }


    private fun showPPOcrV6DebugView(bitmap: Bitmap, ocrResult: OcrResult, mergedRegions: List<TextRegionGroup>, debugDet: PPOcrV6Engine.DebugDetResult? = null) {
        val debugBitmap = MangaDebugOverlays.renderPPOcrV6DebugWithMerge(bitmap, ocrResult, mergedRegions, debugDet, prefs.getFloat("ppocrv6_text_score", 0.5f))
        showPPOcrV6DebugResultOverlay(debugBitmap, ocrResult, mergedRegions, debugDet)
    }

    /**
     * 渲染 PP-OCRv5 调试图：原始检测框 + 合并区域框 + 被丢弃选区
     */
    private fun showPPOcrV5DebugResultOverlay(debugBitmap: Bitmap, ocrResult: OcrResult, mergedRegions: List<TextRegionGroup> = emptyList(), debugDet: PPOcrV5Engine.DebugDetResult? = null) {
        if (isResultShowing) {
            dismissResultOverlay()
        }

        // 应用框选外区域遮罩
        val displayBitmap = MangaDebugOverlays.applyCropDimming(debugBitmap, cropRect, getScreenSize())

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
        val infoPanel = MangaDebugOverlays.createInfoPanelView(this, infoLines, scrollable = true, maxHeight = getScreenSize().height / 2)

        // 创建可折叠内容容器：参数滑块 + 调试信息
        val foldableContent = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        // 参数滑块（带恢复默认按钮）
        val slidersView = MangaDebugSliders.createPPOcrParamSlidersView(prefs, this)
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
        val toggleButton = MangaDebugOverlays.createToggleButton(this, onToggle = { if (debugInfoPanelCollapsed) expandDebugInfoPanel() else collapseDebugInfoPanel() })
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


    private fun showPPOcrV6DebugResultOverlay(debugBitmap: Bitmap, ocrResult: OcrResult, mergedRegions: List<TextRegionGroup> = emptyList(), debugDet: PPOcrV6Engine.DebugDetResult? = null) {
        if (isResultShowing) {
            dismissResultOverlay()
        }

        // 应用框选外区域遮罩
        val displayBitmap = MangaDebugOverlays.applyCropDimming(debugBitmap, cropRect, getScreenSize())

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
            val curBox = prefs.getFloat("ppocrv6_det_box_thresh", 0.5f)
            val curUnclip = prefs.getFloat("ppocrv6_det_unclip_ratio", 1.6f)
            val curText = prefs.getFloat("ppocrv6_text_score", 0.5f)
            add("PP-OCRv6 调试模式 | det尺寸: ${PPOcrV6Engine.lastDetSize}")
            add("检测: ${ocrResult.boxes.size}  丢弃: $discCount  识别丢: $scoreDisc  内容丢: $contentDisc  输出: ${ocrResult.texts.size}  合并: ${mergedRegions.size}")
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
        val infoPanel = MangaDebugOverlays.createInfoPanelView(this, infoLines, scrollable = true, maxHeight = getScreenSize().height / 2)

        // ============================================================
        // 参数面板（可折叠，初始隐藏）
        // ============================================================
        val slidersView = MangaDebugSliders.createPPOcrV6ParamSlidersView(prefs, this)
        slidersView.visibility = android.view.View.GONE  // 默认隐藏
        val sliderParams = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = android.view.Gravity.BOTTOM
            // 给 info 面板留空间
            bottomMargin = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, 80f, resources.displayMetrics).toInt()
        }
        container.addView(slidersView, sliderParams)

        // ============================================================
        // 调试信息面板（可折叠，初始可见）
        // ============================================================
        val infoParams = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = android.view.Gravity.BOTTOM
        }
        container.addView(infoPanel, infoParams)
        debugInfoPanelContentView = infoPanel  // 折叠时隐藏 info 面板

        // ============================================================
        // 两个折叠按钮：⚙参数 / 📊信息（右下角，左右排列，无背景）
        // ============================================================
        val btnSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, 36f, resources.displayMetrics).toInt()
        val margin = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, 12f, resources.displayMetrics).toInt()

        // 信息面板折叠按钮（右侧）
        val toggleButton = android.widget.TextView(this).apply {
            text = "📊"
            textSize = 18f
            gravity = android.view.Gravity.CENTER
            setTextColor(android.graphics.Color.argb(220, 255, 255, 255))
            isClickable = true; isFocusable = true
            setOnClickListener {
                if (infoPanel.visibility == android.view.View.GONE) {
                    infoPanel.visibility = android.view.View.VISIBLE
                    text = "📊"
                } else {
                    infoPanel.visibility = android.view.View.GONE
                    text = "📊"
                }
            }
        }
        val toggleParams = android.widget.FrameLayout.LayoutParams(btnSize, btnSize).apply {
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
            marginEnd = margin
            bottomMargin = margin
        }
        container.addView(toggleButton, toggleParams)

        // 参数面板折叠按钮（左侧，紧挨信息按钮）
        val paramsToggle = android.widget.TextView(this).apply {
            text = "⚙"
            textSize = 18f
            gravity = android.view.Gravity.CENTER
            setTextColor(android.graphics.Color.argb(220, 255, 255, 255))
            isClickable = true; isFocusable = true
            setOnClickListener {
                if (slidersView.visibility == android.view.View.GONE) {
                    slidersView.visibility = android.view.View.VISIBLE
                } else {
                    slidersView.visibility = android.view.View.GONE
                }
            }
        }
        val paramsToggleParams = android.widget.FrameLayout.LayoutParams(btnSize, btnSize).apply {
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
            marginEnd = margin + btnSize + 2
            bottomMargin = margin
        }
        container.addView(paramsToggle, paramsToggleParams)

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
            // info 面板初始可见，参数面板初始隐藏
            toggleButton.text = "▼"
        } catch (e: Exception) {
            LogCollector.e(TAG, "PP-OCRv6 Debug: 显示失败", e)
        }

        bringFloatingBallToFront()
    }

    /** 限制最大高度的 ScrollView，用于调试面板半屏约束 */
    private suspend fun runOcrOnBitmap(bitmap: android.graphics.Bitmap): List<TextBlockInfo> {
        return withContext(Dispatchers.IO) {
            when (config.detEngine) {
                DetEngine.MLKIT -> {
                    OCRBridge.recognizeWithLocation(config.sourceLang, bitmap)
                }
                DetEngine.RT_DETR_V2 -> {
                    DetectionBridge.detectWithRTDetrV2(bitmap, config.sourceLang, this@MangaFloatingService, config.keepTextFree)
                }
                DetEngine.PP_OCR_V6 -> {
                    DetectionBridge.detectWithPPOcrV6(bitmap, config.sourceLang, this@MangaFloatingService)
                }
                DetEngine.PP_OCR_V5 -> {
                    DetectionBridge.detectWithPPOcrV5(bitmap, config.sourceLang, this@MangaFloatingService)
                }
            }
        }
    }
}
