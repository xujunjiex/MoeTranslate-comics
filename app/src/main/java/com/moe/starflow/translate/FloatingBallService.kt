/*
 * Copyright (C) 2024 murangogo
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along
 * with this library; if not, write to the Free Software Foundation, Inc.,
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA
 */

package com.moe.starflow.translate

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Point
import android.os.Build
import android.widget.Toast
import java.util.LinkedList
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.moe.starflow.data.CacheEntry
import com.moe.starflow.data.TranslationCacheManager
import com.moe.starflow.utils.LogCollector
import com.moe.starflow.utils.TextSimilarity
import android.view.*
import android.widget.AdapterView
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.moe.starflow.MainActivity
import com.moe.starflow.R
import com.moe.starflow.me.BuiltinProviders
import com.moe.starflow.me.ConfigurationStorage
import com.moe.starflow.manga.MangaOcrBridge
import com.moe.starflow.manga.MangaOcrDownloadManager
import com.moe.starflow.manga.MangaOcrRecognizer
import com.moe.starflow.manga.PPOcrV5Engine
import com.moe.starflow.utils.Constants
import com.moe.starflow.utils.CustomPreference
import com.moe.starflow.utils.KeystoreManager
import com.moe.starflow.utils.TranslationStatusOverlay
import com.moe.starflow.utils.UtilTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import translationapi.azuretranslation.AzureTranslation
import translationapi.baidutranslation.BaiduTranslationImage
import translationapi.baidutranslation.BaiduTranslationText
import translationapi.bingtranslation.BingTranslation
import translationapi.customtranslation.CustomTranslationImage
import translationapi.doubaotranslation.DoubaoTranslation
import translationapi.customtranslation.CustomTranslationText
import translationapi.deepltranslation.DeepLTranslation
import translationapi.mlkittranslation.MLKitTranslation
import translationapi.niutrans.NiuTranslation
import translationapi.nllbtranslation.NLLBTranslation
import translationapi.openaitranslation.OpenAITranslation
import translationapi.tencentcloud.TencentTranslationImage
import translationapi.tencentcloud.TencentTranslationText
import translationapi.volctranslation.VolcTranslation
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

// 发送服务停止广播
object BroadcastAction {
    const val ACTION_FLOATING_BALL_SERVICE_STOPPED = "action_floating_ball_service_stopped"
    const val ACTION_MANGA_SERVICE_STOPPED = "action_manga_floating_service_stopped"
}

// 悬浮球配置
data class FloatingBallConfig(
    val floatingBallInitialX: Int = 80,
    val floatingBallInitialY: Int = 200,
    val CLICK_SLOP:Float = 5f,           // 点击判定的最大移动距离
    val LONG_PRESS_SLOP:Float = 10f,     // 长按判定的最大移动距离
    var LONG_PRESS_DELAY:Long = 300L   // 长按触发时间（毫秒）
)

data class CropViewConfig(
    val cropViewInitialX: Int = 50,
    val cropViewInitialY: Int = 50
)

// 手势类型
sealed class GestureType {
    object Click : GestureType()
    object LongPress : GestureType()
    object Drag : GestureType()
}

// 状态
sealed class BallStatus {
    object Normal : BallStatus()
    object Crop : BallStatus()
}

class FloatingBallService : LifecycleService() {
    private lateinit var windowManager: WindowManager
    private lateinit var floatingBallView: View
    private lateinit var translationResultView: TranslationResultView
    private lateinit var cropView: CropView

    private var floatingBallParams: WindowManager.LayoutParams? = null
    private var resultViewParams: WindowManager.LayoutParams? = null
    private var cropViewParams: WindowManager.LayoutParams? = null

    private lateinit var prefs: CustomPreference

    // 是否正在翻译，默认false
    private val isTranslating = AtomicBoolean(false)

    // 配置
    private var floatingBallConfig = FloatingBallConfig()
    private var cropViewConfig = CropViewConfig()

    // 手势动作配置
    private var singleClickAction = Constants.BallAction.TRANSLATE
    private var doubleClickAction = Constants.BallAction.AUTO_TRANSLATE
    private var longPressAction = Constants.BallAction.MENU

    // 双击检测
    private var lastClickTime = 0L
    private val singleClickRunnable = Runnable { executeAction(singleClickAction) }

    // 悬浮球触摸相关变量
    private var floatingBallInitialX: Int = 0
    private var floatingBallInitialY: Int = 0
    private var floatingBallInitialTouchX: Float = 0f
    private var floatingBallInitialTouchY: Float = 0f

    // 翻译结果视图状态
    private var isResultViewShowing = false

    // 长按处理器
    private val handler = Handler(Looper.getMainLooper())
    private val longPressRunnable = Runnable { handleLongPress() }

    // 悬浮球状态机
    private var ballStateManager: BallStateManager? = null

    // 当前手势类型
    private var currentGesture: GestureType? = null

    // 当前悬浮球状态
    private var currentBallStatus: BallStatus = BallStatus.Normal

    // 保存裁剪框状态
    private var mRectF: RectF? = null

    // 保存目前的横竖屏配置
    private var orientation = 1

    // 初始化的翻译对象
    private var translatorText: TranslationTextAPI? = null
    private var translatorPic: TranslationPicAPI? = null

    // AI 上下文（仅游戏模式，仅 OpenAI 兼容 API）
    private val contextHistory = LinkedList<Pair<String, String>>()
    private var contextEnabled = false
    private var contextMaxCount = 5

    // 翻译会话 ID（每次服务启动生成新的）
    private val sessionId = java.util.UUID.randomUUID().toString()

    // 自动翻译相关属性
    private var isAutoTranslating = false
    private var pendingAutoStart = false   // 等待权限授权后自动启动
    private var isFullyInitialized = false  // 悬浮球和收集器是否已初始化
    private var isMenuShowing = false
    private var wasAutoTranslatingBeforeCrop = false  // 框选前的自动翻译状态
    private val autoTranslateHandler = Handler(Looper.getMainLooper())


    // 截图提供者
    private var screenshotProvider: ScreenshotProvider? = null

    companion object {
        private const val DEFAULT_PIXEL_CHECK_INTERVAL_MS = 300L
        private const val OCR_TIMEOUT_MS = 3000L
        private const val DOUBLE_CLICK_DELAY = 300L
        private const val FOREGROUND_NOTIFICATION_ID = 34765
        private const val NOTIFICATION_CHANNEL_ID = "screen_capture"
    }

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

    // 启动前台服务（MediaProjection 模式需要）
    private fun startForegroundForScreenshot() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.foreground_service_notification_title),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.foreground_service_notification_text)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
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
            lifecycleScope.launch {
                LogCollector.d(TAG, "Taking MediaProjection screenshot (game mode)")
                val bitmap = provider.takeScreenshot(cropRect, offset)
                if (bitmap != null) {
                    LogCollector.d(TAG, "Screenshot captured: ${bitmap.width}x${bitmap.height}")
                    ScreenshotManager.emitScreenshot(ScreenshotData(bitmap, null))
                } else if (!provider.ensureInitialized()) {
                    // Shooter 已断开（系统回收录屏），停止自动翻译并提示重新授权
                    LogCollector.w(TAG, "Shooter not ready, stopping auto-translate")
                    withContext(Dispatchers.Main) {
                        showToast("录屏已断开，请重新授权", true)
                        stopAutoTranslate()
                    }
                    isTranslating.set(false)
                } else {
                    // 普通截图失败
                    LogCollector.w(TAG, "Screenshot returned null, retrying")
                    isTranslating.set(false)
                    if (isAutoTranslating) {
                        autoTranslateHandler.removeCallbacksAndMessages(null)
                        scheduleNextDetection(getPixelCheckInterval())
                    } else {
                        showToast("截图失败，请重试", true)
                    }
                }
            }
            return true
        } else {
            // AccessibilityService 模式：直接调用（结果通过 ScreenshotManager.screenshotFlow 返回）
            LogCollector.d(TAG, "Taking AccessibilityService screenshot")
            lifecycleScope.launch {
                provider.takeScreenshot(cropRect, offset)
            }
            return true
        }
    }

    private fun getPixelCheckInterval(): Long {
        return prefs.getInt("Game_Pixel_Check_Interval", 300).toLong().coerceAtLeast(300L)
    }

    /**
     * 获取屏幕真实物理像素尺寸（横屏/竖屏都正确，包含系统栏区域）
     * currentWindowMetrics.bounds 返回的是窗口内容区域（减去系统栏），不是真实屏幕尺寸
     */
    @Suppress("DEPRECATION")
    private fun getScreenSize(): android.util.Size {
        val defaultDisplay = windowManager.defaultDisplay
        val realSize = android.graphics.Point()
        defaultDisplay.getRealSize(realSize)
        return android.util.Size(realSize.x, realSize.y)
    }

    /**
     * 屏幕旋转时：旧框选坐标（属于旋转前的坐标系）几何上已失效，
     * 强制置空 mRectF 要求重新框选；若正在自动翻译则停止，避免用错误坐标继续翻译。
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (mRectF != null && newConfig.orientation != orientation) {
            LogCollector.d("FloatingBallService", "屏幕方向变化 $orientation -> ${newConfig.orientation}，清除旧框选")
            mRectF = null
            if (isAutoTranslating) {
                stopAutoTranslate()
            }
            showToast(getString(R.string.orientation_changed), true)
        }
    }

    // 缓存管理
    private lateinit var cacheManager: TranslationCacheManager

    // 自动翻译引擎
    private var autoTranslateEngine: AutoTranslateEngine? = null

    // OCR 引擎（手动翻译时使用）
    private lateinit var ocrEngine: GameOcrEngine

    // 游戏翻译调试浮窗
    private var gameDebugOverlay: GameDebugOverlay? = null
    private var translateStartTime = 0L

    // 翻译状态提示条
    private lateinit var statusOverlay: TranslationStatusOverlay

    // 重新翻译用：记录最近一次翻译的原文
    private var lastTranslatedSource: String? = null

    // SharedPreferences listener（防止被 GC 回收）
    private var prefChangeListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    // 悬浮球图标变更广播接收器（防止被 GC 回收，跨 onCreate/onDestroy 复用同一实例）
    private var iconChangeReceiver: android.content.BroadcastReceiver? = null

    private fun isGameDebugEnabled(): Boolean =
        prefs.getBoolean("Game_Translate_Debug_View", false)

    private fun getOcrEngineName(): String = when (prefs.getInt("Game_OCR_Engine", 0)) {
        1 -> "PP-OCRv5"
        2 -> "manga-ocr"
        else -> "MLKit"
    }

    private fun showDebugOverlay() {
        if (!isGameDebugEnabled()) return
        if (gameDebugOverlay == null) {
            gameDebugOverlay = GameDebugOverlay(this)
        }
        gameDebugOverlay?.show()
    }

    private fun hideDebugOverlay() {
        gameDebugOverlay?.hide()
    }

    private fun updateDebugStatus(
        status: String,
        similarity: Float = -1f,
        cacheSource: String = "",
        elapsedMs: Long = -1L,
        diffRatio: Float = -1f
    ) {
        if (!isGameDebugEnabled()) return
        gameDebugOverlay?.update(
            status = status,
            ocrEngine = getOcrEngineName(),
            similarity = similarity,
            cacheSource = cacheSource,
            elapsedMs = elapsedMs,
            diffRatio = diffRatio
        )
    }

    override fun onCreate() {
        super.onCreate()
        LogCollector.d(TAG, "FloatingBallService onCreate")
        prefs = CustomPreference.getInstance(this)

        // 注册悬浮球图标变更广播（Personalization 设置页改图标时实时刷新）
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                val key = intent?.getStringExtra("extra_icon_key") ?: return
                if (key == "Icon_Game") {
                    // 重读状态机的 Idle 图标路径
                    ballStateManager?.setState(BallStateManager.State.Idle)
                }
            }
        }
        iconChangeReceiver = receiver
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this)
            .registerReceiver(receiver, android.content.IntentFilter("action_floating_ball_icon_changed"))
        statusOverlay = TranslationStatusOverlay(this)
        // 读取手势动作配置
        singleClickAction = Constants.BallAction.fromValue(prefs.getString("Ball_Gesture_Single_Click", "0").toIntOrNull() ?: 0)
        doubleClickAction = Constants.BallAction.fromValue(prefs.getString("Ball_Gesture_Double_Click", "2").toIntOrNull() ?: 2)
        longPressAction = Constants.BallAction.fromValue(prefs.getString("Ball_Gesture_Long_Press", "1").toIntOrNull() ?: 1)
        // 读取 AI 上下文设置
        contextEnabled = prefs.getBoolean("game_context_enabled", false)
        contextMaxCount = try {
            prefs.getString("game_context_count", "5").toIntOrNull() ?: 5
        } catch (e: Exception) { 5 }

        // 先初始化截图提供者，再检查权限（权限检查在悬浮球创建之前）
        initScreenshotProvider()

        if (screenshotProvider is MediaProjectionProvider) {
            startForegroundForScreenshot()
            if (!(screenshotProvider as MediaProjectionProvider).ensureInitialized()) {
                // 未授权：弹授权对话框，延迟初始化（不创建悬浮球）
                LogCollector.d(TAG, "MediaProjection needs permission, deferring init")
                ScreenCapturePermissionActivity.start(this, "game")
                // 服务继续运行但不显示悬浮球，等授权后再初始化
                return
            }
        }

        // 权限就绪，正常初始化
        fullInit()

        // 监听源语言、引擎、AI 上下文变化，实时检查语言/模型提示并刷新上下文设置
        val watchedKeys = setOf(
            "Source_Language",
            "Game_OCR_Engine",
            "game_context_enabled",
            "game_context_count"
        )
        val styleKeys = setOf(
            "Custom_Result_Font_Size",
            "Custom_Result_Font_Color",
            "Custom_Result_Background_Color",
            "Custom_Result_Font"
        )
        prefChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when {
                key == "game_context_enabled" -> contextEnabled = prefs.getBoolean("game_context_enabled", false)
                key == "game_context_count" -> contextMaxCount = prefs.getString("game_context_count", "5").toIntOrNull() ?: 5
                key in watchedKeys -> checkLanguageHints()
                key in styleKeys -> {
                    // 设置页改了字号/字体/颜色 → 立即应用到翻译结果 view
                    translationResultView.applyStyle()
                }
            }
        }
        prefs.getSharedPreferences().registerOnSharedPreferenceChangeListener(prefChangeListener)
        checkLanguageHints()

        LogCollector.d(TAG, "FloatingBallService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra("PERMISSION_RESULT", false) == true) {
            LogCollector.d(TAG, "Permission granted, initializing Shooter")
            val initialized = (screenshotProvider as? MediaProjectionProvider)?.ensureInitialized() ?: false
            LogCollector.d(TAG, "Shooter init result: $initialized")
            if (initialized) {
                // 如果悬浮球还没创建（onCreate 被跳过了），现在初始化
                if (!isFullyInitialized) fullInit()
                if (pendingAutoStart) {
                    pendingAutoStart = false
                    LogCollector.d(TAG, "Starting pending auto-translate")
                    startAutoTranslate()
                } else if (isAutoTranslating) {
                    LogCollector.d(TAG, "Resuming auto-translate")
                    scheduleNextDetection(0L)
                }
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun fullInit() {
        if (isFullyInitialized) return
        isFullyInitialized = true
        initialize()
        setupScreenshotCollector()
        LogCollector.d(TAG, "Full initialization complete")
    }

    @SuppressLint("InflateParams")
    private fun initialize() {
        // 初始化缓存管理器
        cacheManager = TranslationCacheManager(this)
        LogCollector.d(TAG, "缓存管理器初始化完成")

        // 初始化翻译API
        LogCollector.d(TAG, "开始初始化翻译 API, Text_API=${prefs.getInt("Text_API", Constants.TextApi.BING.id)}")
        try {
            if (prefs.getInt("Translate_Mode", Constants.TranslateMode.TEXT.id) == Constants.TranslateMode.TEXT.id){
                when (prefs.getInt("Text_API", Constants.TextApi.BING.id)) {
                    Constants.TextApi.AI.id -> when (prefs.getInt("Text_AI", Constants.TextAI.MLKIT.id)){
                        Constants.TextAI.MLKIT.id -> {
                            translatorText = MLKitTranslation()
                            LogCollector.d(TAG, "翻译 API 初始化: MLKit Translation")
                        }
                        Constants.TextAI.NLLB.id -> {
                            translatorText = NLLBTranslation(this)
                            LogCollector.d(TAG, "翻译 API 初始化: NLLB Translation")
                        }
                        else -> {
                            LogCollector.e(TAG, "Unknown AI Translator: ${prefs.getInt("Text_AI", 0)}")
                            showToast("Unknown Translator.")
                        }
                    }
                    Constants.TextApi.BING.id -> {
                        translatorText = BingTranslation()
                        LogCollector.d(TAG, "翻译 API 初始化: Bing Translation")
                    }
                    Constants.TextApi.NIUTRANS.id -> {
                        translatorText = NiuTranslation(KeystoreManager.retrieveKey(this, "Niutrans")!!)
                        LogCollector.d(TAG, "翻译 API 初始化: NiuTrans")
                    }
                    Constants.TextApi.OPENAI.id -> {
                        val providerList = ConfigurationStorage.loadAllProviders(prefs)
                        val selectedIndex = prefs.getInt("OpenAI_Selected_Provider", 0)
                        if (providerList.isNotEmpty() && selectedIndex < providerList.size) {
                            val provider = providerList[selectedIndex]
                            val effectiveSystemPrompt = if (provider.isBuiltin) {
                                provider.systemPrompt.ifEmpty { provider.defaultSystemPrompt }
                            } else {
                                provider.systemPrompt.ifEmpty { BuiltinProviders.DEFAULT_SYSTEM_PROMPT }
                            }
                            val effectiveUserPrompt = if (provider.isBuiltin) {
                                provider.userPrompt.ifEmpty { provider.defaultUserPrompt }
                            } else {
                                provider.userPrompt.ifEmpty { BuiltinProviders.DEFAULT_USER_PROMPT }
                            }
                            translatorText = OpenAITranslation(apiKey = provider.apiKey, baseUrl = provider.baseUrl, model = provider.modelName, systemPrompt = effectiveSystemPrompt, userPrompt = effectiveUserPrompt)
                            LogCollector.d(TAG, "翻译 API 初始化: OpenAI (${provider.modelName})")
                        } else {
                            LogCollector.e(TAG, "No OpenAI Provider Config Found")
                            showToast("No OpenAI Provider Config Found.")
                        }
                    }
                    Constants.TextApi.VOLC.id -> {
                        translatorText = VolcTranslation(KeystoreManager.retrieveKey(this, "Volc_ACCOUNT")!!, KeystoreManager.retrieveKey(this, "Volc_SECRETKEY")!!)
                        LogCollector.d(TAG, "翻译 API 初始化: Volc Translation")
                    }
                    Constants.TextApi.AZURE.id -> {
                        translatorText = AzureTranslation(KeystoreManager.retrieveKey(this, "Azure")!!)
                        LogCollector.d(TAG, "翻译 API 初始化: Azure Translation")
                    }
                    Constants.TextApi.DEEPL.id -> {
                        translatorText = DeepLTranslation(KeystoreManager.retrieveKey(this, "DeepL_Translate_HOST")!!, KeystoreManager.retrieveKey(this, "DeepL_Translate_APIKEY")!!)
                        LogCollector.d(TAG, "翻译 API 初始化: DeepL Translation")
                    }
                    Constants.TextApi.BAIDU.id -> {
                        translatorText = BaiduTranslationText(KeystoreManager.retrieveKey(this, "Baidu_Translate_ACCOUNT")!!, KeystoreManager.retrieveKey(this, "Baidu_Translate_SECRETKEY")!!)
                        LogCollector.d(TAG, "翻译 API 初始化: Baidu Translation")
                    }
                    Constants.TextApi.TENCENT.id -> {
                        translatorText = TencentTranslationText(KeystoreManager.retrieveKey(this, "Tencent_Cloud_ACCOUNT")!!, KeystoreManager.retrieveKey(this, "Tencent_Cloud_SECRETKEY")!!)
                        LogCollector.d(TAG, "翻译 API 初始化: Tencent Translation")
                    }
                    Constants.TextApi.CUSTOM_TEXT.id -> {
                        val apiList = ConfigurationStorage.loadTextConfigList(prefs)
                        val selectedIndex = prefs.getInt("Custom_Text_API", 0)
                        if (apiList.isEmpty() || selectedIndex >= apiList.size) {
                            LogCollector.e(TAG, "No Custom Text API Config Found")
                            showToast("No Custom Text API Config Found.")
                        } else {
                            translatorText = CustomTranslationText(apiList[selectedIndex].config)
                            LogCollector.d(TAG, "翻译 API 初始化: Custom Text API")
                        }
                    }
                    else -> {
                        LogCollector.e(TAG, "Unknown Text API: ${prefs.getInt("Text_API", 0)}")
                        showToast("Unknown Translator.")
                    }
                }
            }else{
                when (prefs.getInt("Pic_API", Constants.PicApi.BAIDU.id)){
                    Constants.PicApi.BAIDU.id -> {
                        translatorPic = BaiduTranslationImage(KeystoreManager.retrieveKey(this, "Baidu_Translate_ACCOUNT")!!, KeystoreManager.retrieveKey(this, "Baidu_Translate_SECRETKEY")!!)
                        LogCollector.d(TAG, "翻译 API 初始化: Baidu Image Translation")
                    }
                    Constants.PicApi.TENCENT.id -> {
                        translatorPic = TencentTranslationImage(KeystoreManager.retrieveKey(this, "Tencent_Cloud_ACCOUNT")!!, KeystoreManager.retrieveKey(this, "Tencent_Cloud_SECRETKEY")!!)
                        LogCollector.d(TAG, "翻译 API 初始化: Tencent Image Translation")
                    }
                    Constants.PicApi.CUSTOM_PIC.id -> {
                        val apiList = ConfigurationStorage.loadPicConfigList(prefs)
                        val selectedIndex = prefs.getInt("Custom_Pic_API", 0)
                        if (apiList.isEmpty() || selectedIndex >= apiList.size) {
                            LogCollector.e(TAG, "No Custom Pic API Config Found")
                            showToast("No Custom Pic API Config Found.")
                        } else {
                            translatorPic = CustomTranslationImage(apiList[selectedIndex].config)
                            LogCollector.d(TAG, "翻译 API 初始化: Custom Pic API")
                        }
                    }
                    else -> {
                        LogCollector.e(TAG, "Unknown Pic API: ${prefs.getInt("Pic_API", 0)}")
                        showToast("Unknown Translator.")
                    }
                }
            }
        } catch (e: Exception){
            LogCollector.e(TAG, "翻译 API 初始化失败", e)
            showToast("Initialize Error: ${e.message}")
        }

        // 显示翻译 API 初始化成功的消息
        if (translatorText != null || translatorPic != null) {
            val apiName = if (translatorText != null) {
                translatorText!!::class.simpleName ?: "Text API"
            } else {
                translatorPic!!::class.simpleName ?: "Pic API"
            }
            LogCollector.d(TAG, "翻译 API 初始化成功: $apiName")
            showToast("$apiName 初始化成功")
        }

        // 初始化 OCR 引擎
        ocrEngine = GameOcrEngine(this) { msg -> showToast(msg, true) }
        LogCollector.d(TAG, "OCR 引擎初始化: ${getOcrEngineName()}")

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // 创建悬浮窗参数
        floatingBallParams = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            format = PixelFormat.RGBA_8888
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.START or Gravity.TOP
            x = floatingBallConfig.floatingBallInitialX
            y = floatingBallConfig.floatingBallInitialY
        }

        // 设置裁剪框视图参数（必须用屏幕真实尺寸，MATCH_PARENT 会被系统栏截断）
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

        // 创建悬浮球视图
        floatingBallView = LayoutInflater.from(this).inflate(R.layout.floatball_layout, null)

        // 创建翻译结果视图
        resultViewParams = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.CENTER
            x = 0
            y = 0
        }
        translationResultView = TranslationResultView(this, windowManager, resultViewParams!!)
        translationResultView.onClose = { removeResultView() }
        translationResultView.onRetranslate = { retranslateCurrentText() }

        // 创建裁剪框视图
        cropView = CropView(this)

        // 设置悬浮球图标
        // 一次性迁移：旧 Custom_Floating_Pic 首次遇到时复制到 Icon_Game
        if (!prefs.contains("Icon_Game")) {
            val legacy = prefs.getString("Custom_Floating_Pic", "")
            if (legacy.isNotEmpty()) prefs.setString("Icon_Game", legacy)
        }

        val iconName = prefs.getString("Icon_Game", "game-1.进入游戏-启动游戏界面.png")
        val iconView = floatingBallView.findViewById<ImageView>(R.id.floating_ball_icon)
        if (iconName.isEmpty()) {
            iconView.setImageResource(R.mipmap.icon_game_default)
        } else {
            val iconFile = File(getExternalFilesDir(null), "icon/$iconName")
            try {
                if (iconFile.exists()) {
                    val bitmap = BitmapFactory.decodeFile(iconFile.absolutePath)
                    iconView.setImageBitmap(bitmap)
                } else {
                    iconView.setImageResource(R.mipmap.icon_game_default)
                }
            } catch (e: Exception) {
                iconView.setImageResource(R.mipmap.icon_game_default)
            }
        }

        // 设置长按判定时间
        floatingBallConfig.LONG_PRESS_DELAY = prefs.getLong("Custom_Long_Press_Delay", 300L)

        // 添加到窗口
        windowManager.addView(floatingBallView, floatingBallParams)
        ballStateManager = BallStateManager(this, floatingBallView, BallStateManager.Mode.Game)
        ballStateManager?.setState(BallStateManager.State.Idle)

        // 游戏翻译调试浮窗
        if (isGameDebugEnabled()) {
            showDebugOverlay()
        }

        // 设置点击接收器
        setupTouchListener()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchListener() {
        floatingBallView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    floatingBallInitialX = floatingBallParams?.x ?: 0
                    floatingBallInitialY = floatingBallParams?.y ?: 0
                    floatingBallInitialTouchX = event.rawX
                    floatingBallInitialTouchY = event.rawY

                    // 开始长按检测
                    handler.postDelayed(longPressRunnable, floatingBallConfig.LONG_PRESS_DELAY)
                    currentGesture = null
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    // 总移动距离
                    val totalMoveX = abs(event.rawX - floatingBallInitialTouchX)
                    val totalMoveY = abs(event.rawY - floatingBallInitialTouchY)

                    // 判断总移动距离是否超出长按移动阈值
                    if (totalMoveX > floatingBallConfig.LONG_PRESS_SLOP || totalMoveY > floatingBallConfig.LONG_PRESS_SLOP) {
                        handler.removeCallbacks(longPressRunnable)
                    }

                    // 如果移动距离足够大，判定为拖动
                    if (totalMoveX > floatingBallConfig.CLICK_SLOP || totalMoveY > floatingBallConfig.CLICK_SLOP) {
                        currentGesture = GestureType.Drag
                        // 更新悬浮球位置
                        floatingBallParams?.apply {
                            x = (floatingBallInitialX + (event.rawX - floatingBallInitialTouchX)).toInt()
                            y = (floatingBallInitialY + (event.rawY - floatingBallInitialTouchY)).toInt()
                            windowManager.updateViewLayout(floatingBallView, this)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // 移除长按检测
                    handler.removeCallbacks(longPressRunnable)

                    // 处理点击事件
                    if (currentGesture == null) {
                        val totalMoveX = abs(event.rawX - floatingBallInitialTouchX)
                        val totalMoveY = abs(event.rawY - floatingBallInitialTouchY)
                        if (totalMoveX <= floatingBallConfig.CLICK_SLOP && totalMoveY <= floatingBallConfig.CLICK_SLOP) {
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

    private fun showLongPressMenu() {
        val ocrLabel = getOcrEngineLabel()
        val langName = getCurrentSourceLangName()
        val (dialog, listView) = Dialogs.menuDialog(this, isAutoTranslating, ocrLabel, langName)

        // 动态计算菜单索引
        var idx = 2  // 前 2 项固定：框选、字体
        val ocrIdx = idx++                  // OCR 模型
        val langIdx = idx++                 // 切换语言
        val historyIdx = idx++              // 历史
        val autoIdx = idx++                 // 自动翻译
        val closeIdx = idx++                // 关闭
        val backIdx = idx.also { idx++ }             // 返回

        listView.onItemClickListener = object : AdapterView.OnItemClickListener {
            override fun onItemClick(p0: AdapterView<*>?, p1: View?, p2: Int, p3: Long) {
                when (p2) {
                    0 -> {
                        when (currentBallStatus) {
                            is BallStatus.Crop -> showToast(getString(R.string.repeat_crop), true)
                            is BallStatus.Normal -> setCropView()
                        }
                        dialog.dismiss()
                    }
                    1 -> {
                        if (isAutoTranslating) {
                            showToast(getString(R.string.auto_translate_disabled_hint), true)
                        } else {
                            showFontSizeDialog()
                            dialog.dismiss()
                        }
                    }
                    ocrIdx -> {
                        if (isAutoTranslating) {
                            showToast(getString(R.string.auto_translate_disabled_hint), true)
                        } else {
                            // 循环切换，不关闭菜单
                            cycleOcrEngine()
                            val adapter = listView.adapter as MenuDialogAdapter
                            adapter.updateLabel(ocrIdx, getString(R.string.game_ocr_engine_label) + "：" + getOcrEngineLabel())
                        }
                    }
                    langIdx -> {
                        if (isAutoTranslating) {
                            showToast(getString(R.string.auto_translate_no_switch), true)
                        } else {
                            // 循环切换源语言，不关闭菜单
                            cycleSourceLang()
                            val adapter = listView.adapter as MenuDialogAdapter
                            adapter.updateLabel(langIdx, getString(R.string.game_switch_language) + "：" + getCurrentSourceLangName())
                        }
                    }
                    historyIdx -> {
                        showTranslationHistoryDialog()
                        dialog.dismiss()
                    }
                    autoIdx -> {
                        toggleAutoTranslate()
                        dialog.dismiss()
                    }
                    closeIdx -> {
                        if (isAutoTranslating) {
                            showToast(getString(R.string.game_cannot_close_ball), true)
                        } else {
                            stopServiceAndRemoveViews()
                            dialog.dismiss()
                        }
                    }
                    backIdx -> {
                        backToMainActivity()
                        dialog.dismiss()
                    }
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
        isMenuShowing = true
        dialog.setOnDismissListener { isMenuShowing = false }
    }

    private fun getOcrEngineLabel(): String {
        return when (prefs.getInt("Game_OCR_Engine", 0)) {
            1 -> getString(R.string.game_ocr_engine_ppocr)
            2 -> getString(R.string.game_ocr_engine_manga_ocr)
            else -> getString(R.string.game_ocr_engine_mlkit)
        }
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
        val ocrEngine = prefs.getInt("Game_OCR_Engine", 0)
        if (ocrEngine != 1) return true  // 非 PP-OCRv5 不需要检查
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
        return com.moe.starflow.translate.CustomLocale.getInstance(lang).getDisplayName()
    }

    /** 循环切换 OCR 引擎：MLKit → PP-OCRv5 → manga-ocr → MLKit */
    private fun cycleOcrEngine() {
        val current = prefs.getInt("Game_OCR_Engine", 0)
        val next = (current + 1) % 3
        prefs.setInt("Game_OCR_Engine", next)
        val label = when (next) {
            1 -> getString(R.string.game_ocr_engine_ppocr)
            2 -> getString(R.string.game_ocr_engine_manga_ocr)
            else -> getString(R.string.game_ocr_engine_mlkit)
        }
        val fromLabel = when (current) {
            1 -> getString(R.string.game_ocr_engine_ppocr)
            2 -> getString(R.string.game_ocr_engine_manga_ocr)
            else -> getString(R.string.game_ocr_engine_mlkit)
        }
        LogCollector.d(TAG, "OCR 引擎切换: $fromLabel → $label")
        showToast(getString(R.string.game_ocr_engine_label) + "：$label", true)

        // 释放旧引擎
        when (current) {
            1 -> {
                if (PPOcrV5Engine.isInitialized) {
                    LogCollector.d(TAG, "释放 PP-OCRv5 识别器")
                    PPOcrV5Engine.release()
                }
            }
            2 -> {
                if (MangaOcrBridge.isAvailable()) {
                    LogCollector.d(TAG, "释放 manga-ocr 识别器")
                    MangaOcrRecognizer.release()
                }
            }
        }

        // 立即初始化新引擎
        lifecycleScope.launch {
            try {
                when (next) {
                    1 -> {
                        // PP-OCRv5
                        LogCollector.d(TAG, "初始化 PP-OCRv5 识别器...")
                        showToast("PP-OCRv5 识别器初始化中...", true)
                        withContext(Dispatchers.IO) {
                            PPOcrV5Engine.initialize(this@FloatingBallService)
                        }
                        LogCollector.d(TAG, "PP-OCRv5 识别器初始化成功")
                        showToast("PP-OCRv5 识别器初始化成功", true)
                    }
                    2 -> {
                        // manga-ocr
                        if (MangaOcrDownloadManager.isModelDownloaded(this@FloatingBallService)) {
                            LogCollector.d(TAG, "初始化 manga-ocr 识别器...")
                            showToast("manga-ocr 识别器初始化中...", true)
                            withContext(Dispatchers.IO) {
                                MangaOcrBridge.initializeDownloaded(this@FloatingBallService)
                            }
                            LogCollector.d(TAG, "manga-ocr 识别器初始化成功")
                            showToast("manga-ocr 识别器初始化成功", true)
                        } else {
                            LogCollector.w(TAG, "manga-ocr 未下载")
                            showToast("manga-ocr 未下载，请先在模型管理中下载", true)
                        }
                    }
                    else -> {
                        // MLKit 无需初始化
                        LogCollector.d(TAG, "MLKit 无需初始化")
                    }
                }
            } catch (e: Exception) {
                LogCollector.e(TAG, "OCR 引擎初始化失败", e)
                showToast("OCR 引擎初始化失败: ${e.message}", true)
            }
        }
    }

    /**
     * 语言/模型可用性提示（系统 Toast）
     * 场景：
     * 1. manga-ocr 模型 + 非日文 → 提示
     * 2. 韩文 + PP引擎 + KO未下载 → 提示下载
     * 3. 俄文 + 非PP引擎 → 提示切换到PP
     * 4. 俄文 + PP引擎 + RU未下载 → 提示下载
     */
    private fun checkLanguageHints() {
        val currentOcr = prefs.getInt("Game_OCR_Engine", 0)
        val isPP = currentOcr == 1  // PP-OCRv5
        val isMangaOcr = currentOcr == 2  // manga-ocr
        val src = prefs.getString("Source_Language", "ja")

        // 优先级1：俄文 + 非PP引擎 → 提示切换到PP
        if (src == "ru" && !isPP) {
            Toast.makeText(this, getString(R.string.ru_need_ppocrv5_engine), Toast.LENGTH_SHORT).show()
            return
        }

        // 优先级2：PP引擎 + KO/RU未下载 → 提示下载
        if (isPP) {
            val (_, hint) = PPOcrV5Engine.resolveRecLang(this, src)
            if (hint != null) {
                Toast.makeText(this, hint, Toast.LENGTH_SHORT).show()
                return
            }
        }

        // 优先级3：manga-ocr 模型 + 非日文 → 提示
        if (isMangaOcr && src != "ja") {
            Toast.makeText(this, getString(R.string.manga_ocr_non_ja_hint), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showTranslationHistoryDialog() {
        lifecycleScope.launch {
            try {
                val historyList = cacheManager.getHistory(
                    type = TranslationCacheManager.MODE_GAME,
                    limit = 20
                )
                if (historyList.isEmpty()) {
                    showToast("暂无翻译历史", true)
                    return@launch
                }
                val dateFormat = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                val items = historyList.map { entry ->
                    Dialogs.HistoryItem(
                        time = dateFormat.format(java.util.Date(entry.updatedAt)),
                        source = entry.sourceText?.take(50) ?: "",
                        translated = entry.translatedText?.take(50) ?: ""
                    )
                }
                withContext(Dispatchers.Main) {
                    val histDialog = Dialogs.historyDialog(this@FloatingBallService, items,
                        onItemClick = { position ->
                            // 点击：显示翻译结果
                            val selected = historyList[position]
                            composeResultText(selected.sourceText, selected.translatedText)
                                ?.let {
                                    translationResultView.setText(it)
                                    if (!isResultViewShowing) {
                                        showResultView()
                                    }
                                }
                        },
                        onItemLongClick = { position ->
                            // 长按：重新翻译
                            val selected = historyList[position]
                            if (!selected.sourceText.isNullOrEmpty()) {
                                translateByText(selected.sourceText)
                            }
                        }
                    )
                    histDialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                    histDialog.show()
                    histDialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
                    histDialog.window?.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
            } catch (e: Exception) {
                LogCollector.e("FloatingBallService", "显示翻译历史失败", e)
                showToast("显示历史失败", true)
            }
        }
    }

    // 5.1.0新增：切换自动翻译状态
    private fun toggleAutoTranslate() {
        if (isAutoTranslating) {
            stopAutoTranslate()
//            isAutoTranslating = false
        } else {
            startAutoTranslate()
//            isAutoTranslating = true
        }
    }

    // 5.1.0新增：启动自动翻译
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
            ScreenCapturePermissionActivity.start(this, "game")
            return
        }
        pendingAutoStart = false

        when (currentBallStatus) {
            is BallStatus.Crop -> {
                showToast(getString(R.string.crop_first), true)
                return
            }
            is BallStatus.Normal -> {
                if (mRectF == null) {
                    showToast(getString(R.string.crop_first), true)
                    return
                }
            }
        }

        if (orientation != this.resources.configuration.orientation) {
            // 屏幕方向已变化，旧框选坐标失效，强制重新框选
            showToast(getString(R.string.orientation_changed), true)
            return
        }
        if (isTranslating.get()) {
            showToast(getString(R.string.is_translating), true)
        }

        // 确保翻译结果视图已添加
        if (!isResultViewShowing) {
            showResultView()
        }

        // 初始化并启动自动翻译引擎
        autoTranslateEngine = AutoTranslateEngine(
            context = this,
            cacheManager = cacheManager,
            scope = lifecycleScope,
            getSourceLanguage = { prefs.getString("Source_Language", "ja") },
            getTargetLanguage = { prefs.getString("Target_Language", "zh") },
            onMessage = { msg -> showToast(msg, true) }
        )
        autoTranslateEngine?.start()

        showDebugOverlay()
        updateDebugStatus("【空闲】自动翻译已启动")

        isAutoTranslating = true
        scheduleNextDetection(0L)
        showToast(getString(R.string.auto_translate_start))
    }

    private fun stopAutoTranslate() {
        isAutoTranslating = false
        autoTranslateEngine?.stop()
        autoTranslateEngine = null
        autoTranslateHandler.removeCallbacksAndMessages(null)
        hideDebugOverlay()
        showToast(getString(R.string.auto_translate_stop))
    }

    private fun scheduleNextDetection(delayMs: Long) {
        autoTranslateHandler.removeCallbacksAndMessages(null)
        autoTranslateHandler.postDelayed({ runAutoDetect() }, delayMs)
    }

    private fun runAutoDetect() {
        if (!isAutoTranslating) return
        if (isMenuShowing) {
            scheduleNextDetection(1000L)
            return
        }
        if (isTranslating.get()) {
            // 翻译还在进行中（API 未返回），调度下一次检查
            scheduleNextDetection(getPixelCheckInterval())
            return
        }
        // 设置超时：如果截图失败或没有响应，也要继续检测
        autoTranslateHandler.postDelayed({
            if (isAutoTranslating && isTranslating.get()) {
                LogCollector.d("FloatingBallService", "截图超时，重置状态")
                isTranslating.set(false)
                scheduleNextDetection(getPixelCheckInterval())
            }
        }, OCR_TIMEOUT_MS)
        val screenshotStarted = takeScreenshotWithProvider(mRectF, cropView.absolutePointOffset)
        if (!screenshotStarted) {
            LogCollector.w(TAG, "Screenshot not started in runAutoDetect, resetting state")
            isTranslating.set(false)
            autoTranslateHandler.removeCallbacksAndMessages(null)
            if (isAutoTranslating) {
                scheduleNextDetection(getPixelCheckInterval())
            }
        }
    }

    private fun setCropView(){
        // 暂停自动翻译
        if (isAutoTranslating) {
            wasAutoTranslatingBeforeCrop = true
            stopAutoTranslate()
            LogCollector.d(TAG, "框选模式：暂停自动翻译")
        }

        val screenSize = getScreenSize()

        // 若有保存的裁剪框，则直接应用
        if ((orientation == this.resources.configuration.orientation) && (mRectF != null)){
            cropView.setRect(mRectF!!)
        }else{
            // 等布局完成后用 view 自身尺寸计算居中框选区域
            cropView.setRectCentered(0.9f, 0.35f)
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

        // 存储屏幕方向
        orientation = this.resources.configuration.orientation

        // 保持悬浮球在最上层
        windowManager.removeView(floatingBallView)
        windowManager.addView(floatingBallView, floatingBallParams)
        // 重新挂载后，强制刷一次状态显示
        ballStateManager?.setState(BallStateManager.State.Idle)
        currentBallStatus = BallStatus.Crop
    }

    private fun confirmCrop() {
        mRectF = cropView.mRect
        try {
            windowManager.removeView(cropView)
        } catch (e: Exception) {
            LogCollector.e(TAG, "Error removing crop view", e)
        }
        showToast(getString(R.string.game_crop_done), true)
        currentBallStatus = BallStatus.Normal

        // 恢复自动翻译
        if (wasAutoTranslatingBeforeCrop) {
            wasAutoTranslatingBeforeCrop = false
            startAutoTranslate()
            LogCollector.d(TAG, "框选完成：恢复自动翻译")
        }
    }

    private fun showResultView() {
        if (!isViewAdded(translationResultView)) {
            windowManager.addView(translationResultView, resultViewParams)
            // 保持悬浮球在最上层
            windowManager.removeView(floatingBallView)
            windowManager.addView(floatingBallView, floatingBallParams)
        }
        isResultViewShowing = true
    }

    private fun removeResultView() {
        if (isViewAdded(translationResultView)) {
            windowManager.removeView(translationResultView)
        }
        isResultViewShowing = false
    }

    private fun executeAction(action: Constants.BallAction) {
        when (action) {
            Constants.BallAction.TRANSLATE -> doTranslate()
            Constants.BallAction.MENU -> showLongPressMenu()
            Constants.BallAction.AUTO_TRANSLATE -> toggleAutoTranslate()
            Constants.BallAction.CLOSE_FLOATING -> stopServiceAndRemoveViews()
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

        when (currentBallStatus){
            is BallStatus.Normal -> {
                // 只在 AccessibilityService 模式下检查无障碍服务
                val isMediaProjection = screenshotProvider is MediaProjectionProvider
                if (!isMediaProjection && AccessibilityServiceManager.getService() == null) {
                    showToast(getString(R.string.accessibility_recycle), true)
                    return
                }

                // 未框选时弹出提示
                if (mRectF == null) {
                    showToast(getString(R.string.game_please_crop_first), true)
                    return
                }

                if (orientation != this.resources.configuration.orientation) {
                    showToast(getString(R.string.orientation_changed), true)
                    return
                }

                if (isTranslating.get()) {
                    showToast(getString(R.string.is_translating), true)
                    return
                }

                // 确保翻译结果视图已添加
                if (!isResultViewShowing) {
                    showResultView()
                }

                // 手动翻译：如果自动翻译中，强制翻译当前页面
                if (isAutoTranslating) {
                    autoTranslateEngine?.isManualForceTranslate = true
                }
                if (isGameDebugEnabled() && !isAutoTranslating) {
                    showDebugOverlay()
                    updateDebugStatus("【检测中】手动翻译")
                }
                val screenshotStarted = takeScreenshotWithProvider(mRectF, cropView.absolutePointOffset)
                if (!screenshotStarted) {
                    LogCollector.w(TAG, "Screenshot not started in manual translate, resetting state")
                    isTranslating.set(false)
                }
            }
            is BallStatus.Crop -> {
                // 框选确认通过 CropView 的确认按钮完成，此处忽略
            }
        }
    }

    private fun setupScreenshotCollector() {
        // 截图处理
        lifecycleScope.launch {
            ScreenshotManager.screenshotFlow.collect { data ->
                val bitmap = data.croppedBitmap ?: data.fullBitmap
                if (data.croppedBitmap != null) data.fullBitmap.recycle()
                try {
                    isTranslating.set(true)
                    processScreenshot(bitmap)
                } catch (e: Exception) {
                    isTranslating.set(false)
                    updateDebugStatus("【错误】截图处理失败: ${e.message?.take(30)}")
                    statusOverlay.showError("OCR失败：$e")
                    ballStateManager?.setState(BallStateManager.State.Error)
                } finally {
                    if (isAutoTranslating) {
                        scheduleNextDetection(getPixelCheckInterval())
                    }
                }
            }
        }

        // 画面变化加速检测：AccessibilityService 检测到事件时立即触发
        lifecycleScope.launch {
            ScreenshotManager.eventTriggerFlow.collect {
                if (isAutoTranslating && !isTranslating.get()) {
                    autoTranslateHandler.removeCallbacksAndMessages(null)
                    runAutoDetect()
                }
            }
        }
    }

    private fun showFontSizeDialog(){
        val dialog = Dialogs.fontSizeDialog(this, translationResultView.getTextView(), null)
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
    }

    /**
     * 根据"显示原文"设置 + 给定 (原文, 译文)，返回最终要显示到 resultView 的文本。
     * 用于统一处理缓存路径（之前每处都漏读 Custom_Show_Source_Mode）。
     */
    private fun composeResultText(source: String?, translated: String?): String? {
        if (translated.isNullOrBlank()) return null
        val showSourceMode = if (isGameDebugEnabled()) 1 else prefs.getInt("Custom_Show_Source_Mode", 0)
        val src = source?.takeIf { it.isNotBlank() }
        return when (showSourceMode) {
            0 -> translated
            1 -> if (src != null) "$src\n\n$translated" else translated
            else -> if (src != null) "$translated\n\n$src" else translated
        }
    }

    private fun backToMainActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        startActivity(intent)
    }

    private suspend fun processScreenshot(bitmap: Bitmap) {
        try {
            if (prefs.getInt("Translate_Mode", 0) == 0) {
                val engine = autoTranslateEngine
                if (engine != null && isAutoTranslating) {
                    // 第一步：像素快检
                    translateStartTime = System.currentTimeMillis()
                    when (val pixelDecision = engine.checkPixel(bitmap)) {
                        is AutoTranslateEngine.Decision.Idle -> {
                            // 已翻译，像素不变，跳过 OCR
                            isTranslating.set(false)
                        }
                        is AutoTranslateEngine.Decision.PixelChanging -> {
                            updateDebugStatus("【像素变化】", diffRatio = pixelDecision.diffRatio)
                            isTranslating.set(false)
                        }
                        is AutoTranslateEngine.Decision.PixelStabilizing -> {
                            if (pixelDecision.stableCount >= 2) {
                                // 达到稳定阈值，触发 OCR
                                updateDebugStatus("【触发OCR】", diffRatio = pixelDecision.diffRatio)
                                statusOverlay.showImmediate("文字识别中...")
                                ballStateManager?.setState(BallStateManager.State.Processing)
                                when (val ocrDecision = engine.ocrAndTranslate(bitmap)) {
                                    is AutoTranslateEngine.Decision.CacheHit -> {
                                        val elapsed = System.currentTimeMillis() - translateStartTime
                                        updateDebugStatus("【LRU缓存命中】", elapsedMs = elapsed, diffRatio = pixelDecision.diffRatio)
                                        statusOverlay.showImmediate("缓存命中")
                                        ballStateManager?.setState(BallStateManager.State.Completed)
                                        composeResultText(ocrDecision.ocrText, ocrDecision.cachedText)
                                            ?.let { translationResultView.setText(it) }
                                        translationResultView.showCacheIndicator()
                                        engine.markIdle()
                                        ballStateManager?.setState(BallStateManager.State.Idle)
                                        updateDebugStatus("【IDLE】等待像素变化")
                                        isTranslating.set(false)
                                    }
                                    is AutoTranslateEngine.Decision.Translate -> {
                                        // LRU 未命中，查数据库缓存
                                        val dbCache = cacheManager.findGameCache(
                                            ocrDecision.ocrText,
                                            prefs.getString("Source_Language", "ja"),
                                            prefs.getString("Target_Language", "zh")
                                        )
                                        if (dbCache?.translatedText != null) {
                                            val elapsed = System.currentTimeMillis() - translateStartTime
                                            updateDebugStatus("【缓存】database", elapsedMs = elapsed, diffRatio = pixelDecision.diffRatio)
                                            statusOverlay.showImmediate("缓存命中")
                                            ballStateManager?.setState(BallStateManager.State.Completed)
                                            composeResultText(ocrDecision.ocrText, dbCache.translatedText)
                                                ?.let { translationResultView.setText(it) }
                                            translationResultView.showCacheIndicator()
                                            lastTranslatedSource = ocrDecision.ocrText
                                            autoTranslateEngine?.onTranslationSuccess(ocrDecision.ocrText, dbCache.translatedText)
                                            autoTranslateEngine?.markIdle()
                                            ballStateManager?.setState(BallStateManager.State.Idle)
                                            isTranslating.set(false)
                                        } else {
                                            updateDebugStatus("【翻译中】", diffRatio = pixelDecision.diffRatio)
                                            statusOverlay.showImmediate("翻译中...")
                                            ballStateManager?.setState(BallStateManager.State.Translating)
                                            translateByText(ocrDecision.ocrText)
                                        }
                                    }
                                    else -> {
                                        isTranslating.set(false)
                                    }
                                }
                            } else {
                                updateDebugStatus("【像素稳定】${pixelDecision.stableCount}/2", diffRatio = pixelDecision.diffRatio)
                                isTranslating.set(false)
                            }
                        }
                        else -> {
                            isTranslating.set(false)
                        }
                    }
                } else {
                    // 手动翻译模式
                    statusOverlay.showImmediate("检测中...")
                    ballStateManager?.setState(BallStateManager.State.Processing)
                    updateDebugStatus("【检测中】手动翻译")
                    translateStartTime = System.currentTimeMillis()
                    val txt = ocrEngine.recognize(bitmap)
                    if (txt.isBlank()) {
                        updateDebugStatus("【跳过】OCR 结果为空")
                        statusOverlay.showImmediate("未检测到文字")
                        ballStateManager?.setState(BallStateManager.State.Completed)
                        isTranslating.set(false)
                        return
                    }
                    // 检查数据库缓存（使用 normalize 后的文本，不区分大小写）
                    val normalizedTxt = TextSimilarity.normalize(txt)
                    val dbCache = cacheManager.findGameCache(
                        normalizedTxt,
                        prefs.getString("Source_Language", "ja"),
                        prefs.getString("Target_Language", "zh")
                    )
                    if (dbCache?.translatedText != null) {
                        val elapsed = System.currentTimeMillis() - translateStartTime
                        updateDebugStatus("【缓存】database", elapsedMs = elapsed)
                        statusOverlay.show("缓存命中")
                        ballStateManager?.setState(BallStateManager.State.Completed)
                        composeResultText(normalizedTxt, dbCache.translatedText)
                            ?.let { translationResultView.setText(it) }
                        translationResultView.showCacheIndicator()
                        lastTranslatedSource = normalizedTxt
                        isTranslating.set(false)
                    } else {
                        updateDebugStatus("【翻译中】手动")
                        statusOverlay.show("翻译中...")
                        ballStateManager?.setState(BallStateManager.State.Translating)
                        translateByText(normalizedTxt)
                    }
                }
            } else {
                updateDebugStatus("【翻译中】图片翻译")
                statusOverlay.show("翻译中...")
                ballStateManager?.setState(BallStateManager.State.Translating)
                val bitmapCopy = bitmap.copy(bitmap.config!!, true)
                translateByPic(bitmapCopy)
            }
        } catch (e: Exception) {
            isTranslating.set(false)
            updateDebugStatus("【错误】${e.message?.take(30) ?: "未知"}")
            e.printStackTrace()
            statusOverlay.showError("翻译失败：${e.message ?: "未知错误"}")
            ballStateManager?.setState(BallStateManager.State.Error)
        } finally {
            bitmap.recycle()
        }
    }



    // 文本翻译
    private fun translateByText(str: String) {
        val sourceLang = prefs.getString("Source_Language", "ja")
        val targetLang = prefs.getString("Target_Language", "zh")
        LogCollector.d(TAG, "开始文本翻译: ${str.take(50)}..., $sourceLang → $targetLang")

        // 更新 AI 上下文（仅 OpenAI 兼容 API）
        (translatorText as? OpenAITranslation)?.updateContext(
            if (contextEnabled) contextHistory.toList() else emptyList(),
            contextEnabled
        )

        translatorText?.getTranslation(str, sourceLang, targetLang) { result ->
            lifecycleScope.launch(Dispatchers.Main) {
                when (result) {
                    is TranslationResult.Success -> {
                        val elapsed = System.currentTimeMillis() - translateStartTime
                        LogCollector.d(TAG, "文本翻译成功: ${result.translatedText.take(50)}..., 耗时: ${elapsed}ms")
                        updateDebugStatus("【完成】", elapsedMs = elapsed)

                        // 调试模式强制显示原文+译文，否则按个性设置（统一通过 composeResultText 走同一条路径）
                        composeResultText(str, result.translatedText)?.let { translationResultView.setText(it) }
                        translationResultView.hideCacheIndicator()
                        lastTranslatedSource = str

                        // 自动翻译中自动恢复显示
                        if (isAutoTranslating && !isResultViewShowing) {
                            showResultView()
                        }

                        // 更新缓存并进入 IDLE
                        autoTranslateEngine?.onTranslationSuccess(str, result.translatedText)
                        autoTranslateEngine?.markIdle()
                        ballStateManager?.setState(BallStateManager.State.Idle)
                        statusOverlay.showImmediate("翻译完成")
                        ballStateManager?.setState(BallStateManager.State.Completed)
                        updateDebugStatus("【IDLE】等待像素变化")

                        // 保存到历史
                        saveTranslationToCache(
                            sourceText = str,
                            translatedText = result.translatedText,
                            translatorName = translatorText?.javaClass?.simpleName ?: "Unknown"
                        )

                        // 更新 AI 上下文
                        if (contextEnabled && translatorText is OpenAITranslation) {
                            contextHistory.addLast(Pair(str, result.translatedText))
                            while (contextHistory.size > contextMaxCount) {
                                contextHistory.removeFirst()
                            }
                            LogCollector.d(TAG, "上下文已更新: ${contextHistory.size}/$contextMaxCount 轮")
                        }
                    }
                    is TranslationResult.Error -> {
                        LogCollector.e(TAG, "文本翻译失败", result.error)
                        updateDebugStatus("【错误】翻译失败")
                        statusOverlay.showError("翻译失败：${result.error.message ?: "未知错误"}")
                        ballStateManager?.setState(BallStateManager.State.Error)
                        translationResultView.setText(getString(R.string.translation_failed, result.error.message))
                        // 报错时停止自动翻译，让用户可以复制错误信息
                        if (isAutoTranslating) {
                            stopAutoTranslate()
                        }
                    }
                }
                isTranslating.set(false)
            }
        }
    }

    private fun translateByPic(bitmap: Bitmap){
        val sourceLang = prefs.getString("Source_Language", "ja")
        val targetLang = prefs.getString("Target_Language", "zh")
        LogCollector.d(TAG, "开始图片翻译: ${bitmap.width}x${bitmap.height}, $sourceLang → $targetLang")
        translatorPic?.getTranslation(bitmap, sourceLang, targetLang){
                result->
            lifecycleScope.launch(Dispatchers.Main) {
                when (result) {
                    is TranslationResult.Success -> {
                        val elapsed = System.currentTimeMillis() - translateStartTime
                        LogCollector.d(TAG, "图片翻译成功: ${result.translatedText.take(50)}..., 耗时: ${elapsed}ms")
                        updateDebugStatus("【完成】图片翻译", elapsedMs = elapsed)
                        statusOverlay.showImmediate("翻译完成")
                        ballStateManager?.setState(BallStateManager.State.Completed)
                        translationResultView.setText(result.translatedText)
                        // 自动翻译中自动恢复显示
                        if (isAutoTranslating && !isResultViewShowing) {
                            showResultView()
                        }
                    }
                    is TranslationResult.Error -> {
                        LogCollector.e(TAG, "图片翻译失败", result.error)
                        updateDebugStatus("【错误】图片翻译失败")
                        statusOverlay.showError("翻译失败：${result.error.message ?: "未知错误"}")
                        ballStateManager?.setState(BallStateManager.State.Error)
                        translationResultView.setText(getString(R.string.translation_failed, result.error.message))
                        // 报错时停止自动翻译，让用户可以复制错误信息
                        if (isAutoTranslating) {
                            stopAutoTranslate()
                        }
                    }
                }
                isTranslating.set(false)
            }
        }
    }

    private fun saveTranslationToCache(
        sourceText: String,
        translatedText: String,
        translatorName: String
    ) {
        lifecycleScope.launch {
            try {
                val entry = CacheEntry(
                    type = TranslationCacheManager.MODE_GAME,
                    sourceText = sourceText,
                    translatedText = translatedText,
                    resultBitmap = null,
                    sourceLang = prefs.getString("Source_Language", "ja"),
                    targetLang = prefs.getString("Target_Language", "zh"),
                    translatorName = translatorName,
                    pHash = 0L,
                    sessionId = sessionId,
                    lastSessionId = sessionId
                )
                // 先删除旧的同源记录，再保存新结果
                cacheManager.refreshGameCache(sourceText, entry.sourceLang, entry.targetLang, entry)
            } catch (e: Exception) {
                LogCollector.e("FloatingBallService", "保存缓存失败", e)
            }
        }
    }

    /**
     * 重新翻译当前文本（从缓存标识旁的重新翻译按钮触发）
     */
    private fun retranslateCurrentText() {
        val sourceText = lastTranslatedSource
        if (sourceText.isNullOrBlank()) {
            showToast("无可翻译内容", true)
            return
        }
        if (isTranslating.get()) {
            showToast(getString(R.string.is_translating), true)
            return
        }
        isTranslating.set(true)
        translateStartTime = System.currentTimeMillis()
        LogCollector.d(TAG, "重新翻译: ${sourceText.take(50)}...")
        statusOverlay.show("重新翻译中...")
        ballStateManager?.setState(BallStateManager.State.Translating)
        translateByText(sourceText)
    }

    /**
     * 显示提示消息
     * @param message 消息内容
     * @param immediate true=覆盖显示（状态进度、模型切换），false=队列显示（初始化、启停提示）
     */
    fun showToast(message: String, immediate: Boolean = false) {
        if (immediate) {
            statusOverlay.showImmediate(message)
        } else {
            statusOverlay.show(message)
        }
    }

    fun isViewAdded(v: View): Boolean {
        return try {
            // 尝试更新View的LayoutParams
            // 如果View没有被添加，会抛出IllegalArgumentException
            windowManager.updateViewLayout(v, v.layoutParams)
            true
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    private fun stopServiceAndRemoveViews() {
        try {
            // 停止自动翻译引擎
            autoTranslateEngine?.stop()
            autoTranslateEngine = null

            // 停止自动翻译
            if (isAutoTranslating) {
                stopAutoTranslate()
            }

            // 移除所有窗口
            if (isViewAdded(floatingBallView)) {
                windowManager.removeView(floatingBallView)
            }
            if (isViewAdded(translationResultView)) {
                windowManager.removeView(translationResultView)
            }
            if (isViewAdded(cropView)) {
                windowManager.removeView(cropView)
            }

            // 清理资源
            hideDebugOverlay()
            OCRTextRecognizer.cleanup()
            translatorText?.release()
            translatorPic?.release()
            handler.removeCallbacks(longPressRunnable)
            lifecycleScope.cancel()

            // 发送服务停止的广播
            LocalBroadcastManager.getInstance(this).sendBroadcast(
                Intent(BroadcastAction.ACTION_FLOATING_BALL_SERVICE_STOPPED)
            )

            // 停止服务
            stopSelf()
        } catch (e: Exception) {
            showToast("Stop service failed: ${e.message}")
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // 清除后台时停止服务
        stopServiceAndRemoveViews()
    }

    override fun onDestroy() {
        LogCollector.d(TAG, "FloatingBallService onDestroy")
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

        // 停止自动翻译引擎
        autoTranslateEngine?.stop()
        autoTranslateEngine = null

        // 停止自动翻译
        if (isAutoTranslating) {
            stopAutoTranslate()
        }

        // 隐藏调试浮窗
        hideDebugOverlay()
        gameDebugOverlay = null

        // 释放状态提示条
        statusOverlay.release()

        // 释放悬浮球状态机
        ballStateManager?.release()
        ballStateManager = null

        // 清理 handler
        autoTranslateHandler.removeCallbacksAndMessages(null)

        // 移除所有窗口
        if (isViewAdded(floatingBallView)) {
            windowManager.removeView(floatingBallView)
        }
        if (isViewAdded(translationResultView)) {
            windowManager.removeView(translationResultView)
        }
        if (isViewAdded(cropView)) {
            windowManager.removeView(cropView)
        }

        // 清理资源
        OCRTextRecognizer.cleanup()
        translatorText?.release()
        translatorPic?.release()
        handler.removeCallbacks(longPressRunnable)
        lifecycleScope.cancel()
        LogCollector.d(TAG, "FloatingBallService destroyed")
    }
}