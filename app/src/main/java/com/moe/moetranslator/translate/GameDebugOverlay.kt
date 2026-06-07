package com.moe.moetranslator.translate

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/**
 * 游戏翻译调试浮窗 —— 独立半透明面板，显示实时调试信息。
 * 仅在 Game_Translate_Debug_View=true 时创建。
 */
class GameDebugOverlay(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var debugView: TextView? = null
    private var isAdded = false

    companion object {
        private const val TEXT_SIZE_SP = 11f
        private const val PADDING_DP = 10
        private const val BG_COLOR = 0xCC000000.toInt()  // 80% 黑色
        private const val TEXT_COLOR = Color.GREEN
    }

    @SuppressLint("SetTextI18n")
    fun show() {
        if (isAdded) return

        val paddingPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, PADDING_DP.toFloat(),
            context.resources.displayMetrics
        ).toInt()

        val textView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SIZE_SP)
            setTextColor(TEXT_COLOR)
            setBackgroundColor(BG_COLOR)
            setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
            text = "【空闲】等待截图..."
        }

        val params = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            gravity = Gravity.START or Gravity.TOP
            x = 0
            y = 0
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
        }

        try {
            windowManager.addView(textView, params)
            debugView = textView
            isAdded = true
        } catch (e: Exception) {
            debugView = null
            isAdded = false
        }
    }

    fun hide() {
        if (!isAdded) return
        try {
            debugView?.let { windowManager.removeView(it) }
        } catch (_: Exception) {}
        debugView = null
        isAdded = false
    }

    /**
     * 更新调试信息。必须在主线程调用。
     * OCR 文本和译文不在此显示 — 它们在翻译结果浮窗中展示。
     *
     * @param status      状态标签，如 "【检测中】" "【翻译中】" "【缓存】" "【等待】" "【跳过】" "【空闲】"
     * @param ocrEngine   OCR 引擎名称
     * @param similarity  与上次 OCR 的相似度（0.0~1.0），-1 表示无比较
     * @param cacheSource 缓存来源："内存缓存" / "数据库缓存" / "无"
     * @param elapsedMs   翻译耗时（毫秒），-1 表示未翻译
     */
    @SuppressLint("SetTextI18n")
    fun update(
        status: String,
        ocrEngine: String = "",
        similarity: Float = -1f,
        cacheSource: String = "",
        elapsedMs: Long = -1L
    ) {
        if (!isAdded) return
        val sb = StringBuilder()
        sb.appendLine(status)
        if (ocrEngine.isNotEmpty()) sb.appendLine("OCR: $ocrEngine")
        if (similarity >= 0f) sb.appendLine("相似度: ${"%.2f".format(similarity)}")
        if (cacheSource.isNotEmpty()) sb.appendLine("缓存: $cacheSource")
        if (elapsedMs >= 0L) sb.appendLine("耗时: ${elapsedMs}ms")
        debugView?.text = sb.toString().trimEnd()
    }
}
