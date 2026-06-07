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

package com.moe.moetranslator.translate

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import com.moe.moetranslator.data.CacheEntry
import com.moe.moetranslator.data.TranslationCacheManager
import com.moe.moetranslator.utils.LogCollector
import android.view.*
import android.widget.AdapterView
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.moe.moetranslator.MainActivity
import com.moe.moetranslator.R
import com.moe.moetranslator.me.ConfigurationStorage
import com.moe.moetranslator.utils.Constants
import com.moe.moetranslator.utils.CustomPreference
import com.moe.moetranslator.utils.KeystoreManager
import com.moe.moetranslator.utils.UtilTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import translationapi.azuretranslation.AzureTranslation
import translationapi.baidutranslation.BaiduTranslationImage
import translationapi.baidutranslation.BaiduTranslationText
import translationapi.bingtranslation.BingTranslation
import translationapi.customtranslation.CustomTranslationImage
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
}

// 悬浮球配置
data class FloatingBallConfig(
    val floatingBallInitialX: Int = 80,
    val floatingBallInitialY: Int = 200,
    val CLICK_SLOP:Float = 5f,           // 点击判定的最大移动距离
    val LONG_PRESS_SLOP:Float = 10f,     // 长按判定的最大移动距离
    var LONG_PRESS_DELAY:Long = 500L   // 长按触发时间（毫秒）
)

data class FloatingTextViewConfig(
    val floatingTextViewInitialX: Int = 0,
    val floatingTextViewInitialY: Int = 0
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
    object MovingText : BallStatus()
}

class FloatingBallService : LifecycleService() {
    private lateinit var windowManager: WindowManager
    private lateinit var floatingBallView: View
    private lateinit var floatingTextView: TextView
    private lateinit var cropView: CropView

    private var floatingBallParams: WindowManager.LayoutParams? = null
    private var floatingTextViewParams: WindowManager.LayoutParams? = null
    private var cropViewParams: WindowManager.LayoutParams? = null

    private lateinit var prefs: CustomPreference

    private val defaultSystemPrompt = "你是一名专业翻译。你的任务是准确、自然地翻译给定的文本。\n具体规则如下： \n1、根据用户的要求，将文本翻译成指定的目标语言；\n2、保持原意和语气；\n3、尽可能保持格式和结构；\n4、直接返回翻译后的文本，不要有任何解释或附加内容；\n5、如果文本已经是目标语言，请按原样返回。"
    private val defaultUserPrompt = "请将下面的文本从usefromlang翻译为usetolang：\n\nusesourcetext"

    // 是否正在翻译，默认false
    private val isTranslating = AtomicBoolean(false)

    // 配置
    private var floatingBallConfig = FloatingBallConfig()
    private var floatingTextViewConfig = FloatingTextViewConfig()
    private var cropViewConfig = CropViewConfig()

    // 悬浮球触摸相关变量
    private var floatingBallInitialX: Int = 0
    private var floatingBallInitialY: Int = 0
    private var floatingBallInitialTouchX: Float = 0f
    private var floatingBallInitialTouchY: Float = 0f

    // 翻译结果触摸相关变量
    private var floatingTextViewInitialX: Int = 0
    private var floatingTextViewInitialY: Int = 0
    private var floatingTextViewInitialTouchX: Float = 0f
    private var floatingTextViewInitialTouchY: Float = 0f

    // 长按处理器
    private val handler = Handler(Looper.getMainLooper())
    private val longPressRunnable = Runnable { handleLongPress() }

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

    // 自动翻译相关属性
    private var isAutoTranslating = false
    private val autoTranslateHandler = Handler(Looper.getMainLooper())


    companion object {
        private const val DETECT_INTERVAL_MS = 500L
    }

    // 缓存管理
    private lateinit var cacheManager: TranslationCacheManager

    // 自动翻译引擎
    private var autoTranslateEngine: AutoTranslateEngine? = null

    // 游戏翻译调试浮窗
    private var gameDebugOverlay: GameDebugOverlay? = null
    private var translateStartTime = 0L

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

    private fun updateDebugStatus(status: String, similarity: Float = -1f, cacheSource: String = "", elapsedMs: Long = -1L) {
        if (!isGameDebugEnabled()) return
        gameDebugOverlay?.update(
            status = status,
            ocrEngine = getOcrEngineName(),
            similarity = similarity,
            cacheSource = cacheSource,
            elapsedMs = elapsedMs
        )
    }

    override fun onCreate() {
        super.onCreate()
        prefs = CustomPreference.getInstance(this)
        initialize()
        setupScreenshotCollector()
    }

    @SuppressLint("InflateParams")
    private fun initialize() {
        // 初始化缓存管理器
        cacheManager = TranslationCacheManager(this)

        // 初始化翻译API
        try {
            if (prefs.getInt("Translate_Mode", Constants.TranslateMode.TEXT.id) == Constants.TranslateMode.TEXT.id){
                when (prefs.getInt("Text_API", Constants.TextApi.BING.id)) {
                    Constants.TextApi.AI.id -> when (prefs.getInt("Text_AI", Constants.TextAI.MLKIT.id)){
                        Constants.TextAI.MLKIT.id -> translatorText = MLKitTranslation()
                        Constants.TextAI.NLLB.id -> translatorText = NLLBTranslation(this)
                        else -> { showToast("Unknown Translator.") }
                    }
                    Constants.TextApi.BING.id -> translatorText = BingTranslation()
                    Constants.TextApi.NIUTRANS.id -> translatorText = NiuTranslation(KeystoreManager.retrieveKey(this, "Niutrans")!!)
                    Constants.TextApi.OPENAI.id -> {
                        val providerList = ConfigurationStorage.loadOpenAIProviders(prefs)
                        val selectedIndex = prefs.getInt("OpenAI_Selected_Provider", 0)
                        if (providerList.isNotEmpty() && selectedIndex < providerList.size) {
                            val provider = providerList[selectedIndex]
                            translatorText = OpenAITranslation(apiKey = provider.apiKey, baseUrl = provider.baseUrl, model = provider.modelName, systemPrompt = provider.systemPrompt, userPrompt = provider.userPrompt)
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
                        val apiList = ConfigurationStorage.loadTextConfigList(prefs)
                        val selectedIndex = prefs.getInt("Custom_Text_API", 0)
                        if (apiList.isEmpty() || selectedIndex >= apiList.size) {
                            showToast("No Custom Text API Config Found.")
                        } else {
                            translatorText = CustomTranslationText(apiList[selectedIndex].config)
                        }
                    }
                    else -> { showToast("Unknown Translator.") }
                }
            }else{
                when (prefs.getInt("Pic_API", Constants.PicApi.BAIDU.id)){
                    Constants.PicApi.BAIDU.id -> translatorPic = BaiduTranslationImage(KeystoreManager.retrieveKey(this, "Baidu_Translate_ACCOUNT")!!, KeystoreManager.retrieveKey(this, "Baidu_Translate_SECRETKEY")!!)
                    Constants.PicApi.TENCENT.id -> translatorPic = TencentTranslationImage(KeystoreManager.retrieveKey(this, "Tencent_Cloud_ACCOUNT")!!, KeystoreManager.retrieveKey(this, "Tencent_Cloud_SECRETKEY")!!)
                    Constants.PicApi.CUSTOM_PIC.id -> {
                        val apiList = ConfigurationStorage.loadPicConfigList(prefs)
                        val selectedIndex = prefs.getInt("Custom_Pic_API", 0)
                        if (apiList.isEmpty() || selectedIndex >= apiList.size) {
                            showToast("No Custom Pic API Config Found.")
                        } else {
                            translatorPic = CustomTranslationImage(apiList[selectedIndex].config)
                        }
                    }
                    else -> { showToast("Unknown Translator.") }
                }
            }
        } catch (e: Exception){
            showToast("Initialize Error: ${e.message}")
        }

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

        // 设置翻译结果视图参数
        floatingTextViewParams = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.CENTER
            x = floatingTextViewConfig.floatingTextViewInitialX
            y = floatingTextViewConfig.floatingTextViewInitialY
        }

        // 设置裁剪框视图参数
        cropViewParams = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            gravity = Gravity.START or Gravity.TOP
            x = cropViewConfig.cropViewInitialX
            y = cropViewConfig.cropViewInitialY
        }

        // 创建悬浮球视图
        floatingBallView = LayoutInflater.from(this).inflate(R.layout.floatball_layout, null)

        // 创建翻译结果视图
        floatingTextView = FloatingTextView.translateTextView(this)

        // 创建裁剪框视图
        cropView = CropView(this)

        // 设置悬浮球图标
        val customPicName = prefs.getString("Custom_Floating_Pic", "")
        if (customPicName.isNotEmpty()) {
            try {
                val iconFile = File(getExternalFilesDir(null), "icon/$customPicName")
                if (iconFile.exists()) {
                    // 使用BitmapFactory加载图片
                    val bitmap = BitmapFactory.decodeFile(iconFile.absolutePath)
                    floatingBallView.findViewById<ImageView>(R.id.floating_ball_icon)
                        .setImageBitmap(bitmap)
                } else {
                    // 文件不存在时显示默认图片
                    floatingBallView.findViewById<ImageView>(R.id.floating_ball_icon)
                        .setImageResource(R.drawable.floating_ball_icon)
                }
            } catch (e: Exception) {
                // 发生错误时显示默认图片
                floatingBallView.findViewById<ImageView>(R.id.floating_ball_icon)
                    .setImageResource(R.drawable.floating_ball_icon)
            }
        }

        // 设置长按判定时间
        floatingBallConfig.LONG_PRESS_DELAY = prefs.getLong("Custom_Long_Press_Delay", 500L)

        // 添加到窗口
        windowManager.addView(floatingBallView, floatingBallParams)

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
                            handleClick()
                        }
                    }

                    currentGesture = null
                    true
                }
                else -> false
            }
        }

        floatingTextView.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if(view.isClickable){
                        floatingTextViewInitialX = floatingTextViewParams?.x ?: 0
                        floatingTextViewInitialY = floatingTextViewParams?.y ?: 0
                        floatingTextViewInitialTouchX = event.rawX
                        floatingTextViewInitialTouchY = event.rawY
                        true
                    }else{
                        false
                    }
                }
                MotionEvent.ACTION_UP -> {
                    view.isClickable
                }
                MotionEvent.ACTION_MOVE -> {
                    if(view.isClickable){
                        floatingTextViewParams?.apply {
                            x = (floatingTextViewInitialX + (event.rawX - floatingTextViewInitialTouchX)).toInt()
                            y = (floatingTextViewInitialY + (event.rawY - floatingTextViewInitialTouchY)).toInt()
                            windowManager.updateViewLayout(floatingTextView, this)
                        }
                        true
                    }else{
                        false
                    }
                }
                else -> {
                    false
                }
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
        lifecycleScope.launch {
            showLongPressMenu()
        }
    }

    private fun showLongPressMenu() {
        val ocrLabel = getOcrEngineLabel()
        val (dialog, listView) = Dialogs.menuDialog(applicationContext, isAutoTranslating, ocrLabel)

        // 动态计算菜单索引
        var idx = 4  // 前 4 项固定：框选、位置、移除、字体
        val ocrIdx = idx++                  // OCR 模型
        val historyIdx = idx++              // 历史
        val autoIdx = idx++                 // 自动翻译
        val closeIdx = idx++                // 关闭
        val backIdx = idx++                 // 返回

        listView.onItemClickListener = object : AdapterView.OnItemClickListener {
            override fun onItemClick(p0: AdapterView<*>?, p1: View?, p2: Int, p3: Long) {
                when (p2) {
                    0 -> {
                        when (currentBallStatus) {
                            is BallStatus.Crop -> showToast(getString(R.string.repeat_crop))
                            is BallStatus.MovingText -> showToast(getString(R.string.textview_first))
                            is BallStatus.Normal -> setCropView()
                        }
                        dialog.dismiss()
                    }
                    1 -> {
                        when (currentBallStatus) {
                            is BallStatus.Crop -> showToast(getString(R.string.crop_first))
                            is BallStatus.MovingText -> showToast(getString(R.string.repeat_textview))
                            is BallStatus.Normal -> setMovingTextView()
                        }
                        dialog.dismiss()
                    }
                    2 -> {
                        when (currentBallStatus) {
                            is BallStatus.Crop -> showToast(getString(R.string.crop_first))
                            is BallStatus.MovingText -> showToast(getString(R.string.textview_first))
                            is BallStatus.Normal -> {
                                if(isViewAdded(floatingTextView)){
                                    windowManager.removeView(floatingTextView)
                                }else{
                                    showToast(getString(R.string.not_added_remove), true)
                                }
                            }
                        }
                        dialog.dismiss()
                    }
                    3 -> {
                        showFontSizeDialog()
                        dialog.dismiss()
                    }
                    ocrIdx -> {
                        // 循环切换，不关闭菜单
                        cycleOcrEngine()
                        val adapter = listView.adapter as MenuDialogAdapter
                        adapter.updateLabel(ocrIdx, getString(R.string.game_ocr_engine_label) + "：" + getOcrEngineLabel())
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
                        stopServiceAndRemoveViews()
                        dialog.dismiss()
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
        dialog.window?.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun getOcrEngineLabel(): String {
        return when (prefs.getInt("Game_OCR_Engine", 0)) {
            1 -> getString(R.string.game_ocr_engine_ppocr)
            2 -> getString(R.string.game_ocr_engine_manga_ocr)
            else -> getString(R.string.game_ocr_engine_mlkit)
        }
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
        LogCollector.d("FloatingBallService", "OCR 引擎切换为: $label")
        showToast(getString(R.string.game_ocr_engine_label) + "：$label")
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
                        time = dateFormat.format(java.util.Date(entry.createdAt)),
                        source = entry.sourceText?.take(50) ?: "",
                        translated = entry.translatedText?.take(50) ?: ""
                    )
                }
                withContext(Dispatchers.Main) {
                    val histDialog = Dialogs.historyDialog(applicationContext, items,
                        onItemClick = { position ->
                            // 点击：显示翻译结果
                            val selected = historyList[position]
                            if (selected.translatedText != null) {
                                floatingTextView.text = selected.translatedText
                                if (!isViewAdded(floatingTextView)) {
                                    windowManager.addView(floatingTextView, floatingTextViewParams)
                                    windowManager.removeView(floatingBallView)
                                    windowManager.addView(floatingBallView, floatingBallParams)
                                    setFloatingTextViewTouchable(false)
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
        if (AccessibilityServiceManager.getService() == null) {
            showToast(getString(R.string.accessibility_recycle))
            return
        }

        when (currentBallStatus) {
            is BallStatus.Crop -> {
                showToast(getString(R.string.crop_first))
                return
            }
            is BallStatus.MovingText -> {
                showToast(getString(R.string.textview_first))
                return
            }
            is BallStatus.Normal -> {}
        }

        if (orientation == this.resources.configuration.orientation) {
            if (isTranslating.get()) {
                showToast(getString(R.string.is_translating), true)
            }
        } else {
            showToast(getString(R.string.orientation_changed))
        }

        // 确保翻译结果视图已添加
        if (!isViewAdded(floatingTextView)) {
            windowManager.addView(floatingTextView, floatingTextViewParams)
            windowManager.removeView(floatingBallView)
            windowManager.addView(floatingBallView, floatingBallParams)
            setFloatingTextViewTouchable(false)
        }

        // 初始化并启动自动翻译引擎
        autoTranslateEngine = AutoTranslateEngine(
            context = this,
            cacheManager = cacheManager,
            scope = lifecycleScope,
            getSourceLanguage = { prefs.getString("Source_Language", "ja") },
            getTargetLanguage = { prefs.getString("Target_Language", "zh") }
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
        if (isTranslating.get()) {
            scheduleNextDetection(DETECT_INTERVAL_MS)
            return
        }
        // 设置超时：如果截图失败或没有响应，也要继续检测
        autoTranslateHandler.postDelayed({
            if (isAutoTranslating && isTranslating.get()) {
                LogCollector.d("FloatingBallService", "截图超时，重置状态")
                isTranslating.set(false)
                scheduleNextDetection(DETECT_INTERVAL_MS)
            }
        }, 3000L) // 3秒超时
        AccessibilityServiceManager.takeScreenshot(mRectF, cropView.absolutePointOffset)
    }

    private fun setCropView(){
        // 若有保存的裁剪框，则直接应用
        if ((orientation == this.resources.configuration.orientation) && (mRectF != null)){
            cropView.setRect(mRectF!!)
        }else{
            cropView.setRect(RectF(5f, 5f, 350f, 350f))
        }

        windowManager.addView(cropView, cropViewParams)

        // 存储屏幕方向
        orientation = this.resources.configuration.orientation

        // 保持悬浮球在最上层
        windowManager.removeView(floatingBallView)
        windowManager.addView(floatingBallView, floatingBallParams)
        currentBallStatus = BallStatus.Crop
    }

    private fun setMovingTextView(){
        if(isViewAdded(floatingTextView)){
            setFloatingTextViewTouchable(true)
        }else{
            windowManager.addView(floatingTextView, floatingTextViewParams)

            // 保持悬浮球在最上层
            windowManager.removeView(floatingBallView)
            windowManager.addView(floatingBallView, floatingBallParams)
            setFloatingTextViewTouchable(true)
        }
        currentBallStatus = BallStatus.MovingText
    }

    private fun handleClick() {
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
                if (AccessibilityServiceManager.getService() == null) {
                    showToast(getString(R.string.accessibility_recycle))
                    return
                }else{
                    if(!isViewAdded(floatingTextView)){
                        windowManager.addView(floatingTextView, floatingTextViewParams)

                        // 保持悬浮球在最上层
                        windowManager.removeView(floatingBallView)
                        windowManager.addView(floatingBallView, floatingBallParams)
                        setFloatingTextViewTouchable(false)
                    }
                    if (orientation == this.resources.configuration.orientation){
                        if(isTranslating.get()){
                            showToast(getString(R.string.is_translating), true)
                        }else{
                            // 手动翻译：如果自动翻译中，强制翻译当前页面
                            if (isAutoTranslating) {
                                autoTranslateEngine?.isManualForceTranslate = true
                            }
                            if (isGameDebugEnabled() && !isAutoTranslating) {
                                showDebugOverlay()
                                updateDebugStatus("【检测中】手动翻译")
                            }
                            AccessibilityServiceManager.takeScreenshot(mRectF, cropView.absolutePointOffset)
                        }
                    }else{
                        showToast(getString(R.string.orientation_changed))
                    }
                }
            }
            is BallStatus.Crop -> {
                mRectF = cropView.mRect
                windowManager.removeView(cropView)
                if (!(prefs.getBoolean("Custom_Adjust_Not_Text", false))){
                    showToast(getString(R.string.finish_crop), true)
                }
                currentBallStatus = BallStatus.Normal
            }
            is BallStatus.MovingText -> {
                setFloatingTextViewTouchable(false)
                if (!(prefs.getBoolean("Custom_Adjust_Not_Text", false))){
                    showToast(getString(R.string.finish_textview), true)
                }
                currentBallStatus = BallStatus.Normal
            }
        }
    }

    private fun setupScreenshotCollector() {
        lifecycleScope.launch {
            ScreenshotManager.screenshotFlow.collect { bitmap ->
                try {
                    isTranslating.set(true)
                    processScreenshot(bitmap)
                } catch (e: Exception) {
                    isTranslating.set(false)
                    showToast("OCR Failed：$e")
                }
            }
        }
    }

    private fun showFontSizeDialog(){
        val dialog = Dialogs.fontSizeDialog(this, floatingTextView, null)
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
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
                    // 自动翻译模式：使用引擎决策
                    updateDebugStatus("【检测中】")
                    translateStartTime = System.currentTimeMillis()
                    when (val decision = engine.processScreenshot(bitmap)) {
                        is AutoTranslateEngine.Decision.Skip -> {
                            updateDebugStatus("【等待/跳过】")
                            isTranslating.set(false)
                            scheduleNextDetection(DETECT_INTERVAL_MS)
                        }
                        is AutoTranslateEngine.Decision.CacheHit -> {
                            val elapsed = System.currentTimeMillis() - translateStartTime
                            updateDebugStatus(
                                "【缓存】${decision.source}",
                                elapsedMs = elapsed
                            )
                            // 调试模式：显示⚡ + 原文+译文；非调试：只显示译文
                            if (isGameDebugEnabled()) {
                                floatingTextView.text = getString(R.string.cache_indicator) + decision.cachedText
                            } else {
                                floatingTextView.text = decision.cachedText
                            }
                            isTranslating.set(false)
                            scheduleNextDetection(DETECT_INTERVAL_MS)
                        }
                        is AutoTranslateEngine.Decision.Translate -> {
                            updateDebugStatus(
                                "【翻译中】",
                                similarity = decision.similarity
                            )
                            translateByText(decision.ocrText)
                        }
                    }
                } else {
                    // 手动翻译模式
                    updateDebugStatus("【检测中】手动翻译")
                    translateStartTime = System.currentTimeMillis()
                    val txt = GameOcrEngine(this).recognize(bitmap)
                    updateDebugStatus("【翻译中】手动")
                    translateByText(txt)
                }
            } else {
                updateDebugStatus("【翻译中】图片翻译")
                val bitmapCopy = bitmap.copy(bitmap.config!!, true)
                translateByPic(bitmapCopy)
            }
        } catch (e: Exception) {
            isTranslating.set(false)
            updateDebugStatus("【错误】${e.message?.take(30) ?: "未知"}")
            e.printStackTrace()
            showToast(getString(R.string.translation_failed, e.message))
            if (isAutoTranslating) {
                scheduleNextDetection(DETECT_INTERVAL_MS)
            }
        } finally {
            bitmap.recycle()
        }
    }



    // 文本翻译
    private fun translateByText(str: String) {
        translatorText?.getTranslation(str, prefs.getString("Source_Language", "ja"), prefs.getString("Target_Language", "zh")) { result ->
            lifecycleScope.launch(Dispatchers.Main) {
                when (result) {
                    is TranslationResult.Success -> {
                        val elapsed = System.currentTimeMillis() - translateStartTime
                        updateDebugStatus("【完成】", elapsedMs = elapsed)

                        // 调试模式强制显示原文+译文，否则按个性设置
                        val showSourceMode = if (isGameDebugEnabled()) 1 else prefs.getInt("Custom_Show_Source_Mode", 0)
                        when (showSourceMode) {
                            0 -> floatingTextView.text = result.translatedText
                            1 -> floatingTextView.text = str + "\n\n" + result.translatedText
                            else -> floatingTextView.text = result.translatedText + "\n\n" + str
                        }

                        // 更新缓存
                        autoTranslateEngine?.onTranslationSuccess(str, result.translatedText)

                        // 保存到历史
                        saveTranslationToCache(
                            sourceText = str,
                            translatedText = result.translatedText,
                            translatorName = translatorText?.javaClass?.simpleName ?: "Unknown"
                        )
                    }
                    is TranslationResult.Error -> {
                        updateDebugStatus("【错误】翻译失败")
                        floatingTextView.text = getString(R.string.translation_failed, result.error.message)
                    }
                }
                isTranslating.set(false)
                if (isAutoTranslating) {
                    scheduleNextDetection(DETECT_INTERVAL_MS)
                }
            }
        }
    }

    private fun translateByPic(bitmap: Bitmap){
        translatorPic?.getTranslation(bitmap, prefs.getString("Source_Language", "ja"), prefs.getString("Target_Language", "zh")){
                result->
            lifecycleScope.launch(Dispatchers.Main) {
                when (result) {
                    is TranslationResult.Success -> {
                        val elapsed = System.currentTimeMillis() - translateStartTime
                        updateDebugStatus("【完成】图片翻译", elapsedMs = elapsed)
                        floatingTextView.text = result.translatedText
                    }
                    is TranslationResult.Error -> {
                        updateDebugStatus("【错误】图片翻译失败")
                        floatingTextView.text = getString(R.string.translation_failed, result.error.message)
                    }
                }
                isTranslating.set(false)
                if (isAutoTranslating) {
                    scheduleNextDetection(DETECT_INTERVAL_MS)
                }
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
                cacheManager.saveToCache(CacheEntry(
                    type = TranslationCacheManager.MODE_GAME,
                    sourceText = sourceText,
                    translatedText = translatedText,
                    resultBitmap = null,
                    sourceLang = prefs.getString("Source_Language", "ja"),
                    targetLang = prefs.getString("Target_Language", "zh"),
                    translatorName = translatorName,
                    pHash = 0L
                ))
            } catch (e: Exception) {
                LogCollector.e("FloatingBallService", "保存缓存失败", e)
            }
        }
    }

    fun showToast(message: String, isShort: Boolean = false) {
        lifecycleScope.launch(Dispatchers.Main) {
            if (isShort) {
                Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
            }
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
            if (isViewAdded(floatingTextView)) {
                windowManager.removeView(floatingTextView)
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

    private fun setFloatingTextViewTouchable(touchable: Boolean) {
        floatingTextView.isClickable = touchable
        if (prefs.getBoolean("Custom_Result_Penetrability", true)){
            floatingTextViewParams?.apply {
                if (touchable) {
                    flags = flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                } else {
                    flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                }
                windowManager.updateViewLayout(floatingTextView, this)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()

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
        if (isViewAdded(floatingTextView)) {
            windowManager.removeView(floatingTextView)
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
    }
}