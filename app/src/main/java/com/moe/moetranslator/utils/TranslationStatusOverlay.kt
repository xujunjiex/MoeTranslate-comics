package com.moe.moetranslator.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import java.util.LinkedList

/**
 * 翻译状态悬浮提示条 — 统一的游戏/漫画翻译状态反馈组件。
 *
 * - 位置、时长从个性化设置读取
 * - 所有消息通过 LogCollector 记录
 * - 错误消息可点击复制
 */
class TranslationStatusOverlay(private val context: Context) {

    companion object {
        private const val TAG = "StatusOverlay"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs = CustomPreference.getInstance(context)

    private var overlayView: TextView? = null
    private var isShowing = false
    private var dismissRunnable: Runnable? = null

    // 消息队列
    private val messageQueue = LinkedList<String>()
    private var isDisplaying = false

    // ========== Public API ==========

    /**
     * 队列显示状态提示（依次显示，不覆盖）。
     * 用于：初始化信息、启停提示等用户需要看到每一条的消息。
     * @param message 提示文案
     */
    fun show(message: String) {
        if (!isEnabled()) return
        LogCollector.d(TAG, message)
        runOnMainThread {
            if (isDisplaying) {
                // 当前有消息在显示，加入队列
                messageQueue.add(message)
            } else {
                // 没有消息在显示，直接显示
                displayMessage(message, true)
            }
        }
    }

    /**
     * 覆盖显示状态提示（直接替换当前消息）。
     * 用于：状态进度、模型切换等只关心最新状态的消息。
     * @param message 提示文案
     * @param autoDismiss true=到期自动消失；false=持续显示直到下次 update/dismiss
     */
    fun showImmediate(message: String, autoDismiss: Boolean = true) {
        if (!isEnabled()) return
        LogCollector.d(TAG, message)
        runOnMainThread {
            cancelAutoDismiss()
            messageQueue.clear()
            displayMessage(message, autoDismiss)
        }
    }

    private fun displayMessage(message: String, autoDismiss: Boolean = true) {
        isDisplaying = true
        ensureView()
        overlayView?.let { view ->
            view.text = message
            view.background = createRoundedBackground(Color.argb(150, 0, 0, 0))
            view.setOnClickListener(null)
            view.isClickable = false
            addToWindowIfNeeded()
            scheduleAutoDismiss(autoDismiss)
        }
    }

    private fun showNextMessage() {
        if (messageQueue.isNotEmpty()) {
            val nextMessage = messageQueue.poll()
            if (nextMessage != null) {
                displayMessage(nextMessage, true)
            } else {
                isDisplaying = false
            }
        } else {
            isDisplaying = false
        }
    }

    /**
     * 显示错误提示（红色背景，可点击复制）。
     * @param message 错误文案（包含原始报错信息）
     */
    fun showError(message: String) {
        if (!isEnabled()) return
        LogCollector.e(TAG, message)
        runOnMainThread {
            // 错误消息优先显示，清空队列
            messageQueue.clear()
            isDisplaying = true
            cancelAutoDismiss()
            ensureView()
            overlayView?.let { view ->
                view.text = message
                view.background = createRoundedBackground(Color.argb(150, 180, 0, 0))
                view.isClickable = true
                view.setOnClickListener {
                    copyToClipboard(message)
                    view.text = "已复制"
                    view.background = createRoundedBackground(Color.argb(150, 0, 120, 0))
                    view.isClickable = false
                    scheduleAutoDismiss(true, 1000L)
                }
                addToWindowIfNeeded()
            }
        }
    }

    /**
     * 更新提示文字（不重置计时器）。
     * 用于：进度提示的实时更新。
     */
    fun update(message: String) {
        if (!isEnabled()) return
        runOnMainThread {
            if (isShowing) {
                overlayView?.text = message
            }
        }
    }

    /**
     * 手动关闭提示。
     */
    fun dismiss() {
        runOnMainThread {
            cancelAutoDismiss()
            removeFromWindow()
            messageQueue.clear()
            isDisplaying = false
        }
    }

    /**
     * 释放资源（Service 销毁时调用）。
     */
    fun release() {
        runOnMainThread {
            cancelAutoDismiss()
            removeFromWindow()
            messageQueue.clear()
            isDisplaying = false
            overlayView = null
        }
    }

    // ========== Internal ==========

    private fun createRoundedBackground(color: Int): GradientDrawable {
        val radius = 16 * context.resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(color)
        }
    }

    private fun isEnabled(): Boolean {
        val enabled = prefs.getBoolean("status_overlay_enabled", true)
        LogCollector.d(TAG, "isEnabled: $enabled")
        return enabled
    }

    private fun getPosition(): Int {
        return when (prefs.getString("Status_Position", "top")) {
            "center" -> Gravity.CENTER
            "bottom" -> Gravity.BOTTOM
            else -> Gravity.TOP
        }
    }

    private fun getDurationMs(): Long {
        return prefs.getString("Status_Duration", "2000")?.toLongOrNull() ?: 2000L
    }

    private fun ensureView() {
        if (overlayView != null) return
        overlayView = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            val hPad = (20 * context.resources.displayMetrics.density).toInt()
            val vPad = (12 * context.resources.displayMetrics.density).toInt()
            setPadding(hPad, vPad, hPad, vPad)
            gravity = Gravity.CENTER
        }
    }

    private fun getViewParams(): WindowManager.LayoutParams {
        val position = getPosition()

        return WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            format = PixelFormat.RGBA_8888
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = position or Gravity.CENTER_HORIZONTAL
            y = when (position) {
                Gravity.TOP -> (24 * context.resources.displayMetrics.density).toInt()  // 状态栏下方
                Gravity.BOTTOM -> (80 * context.resources.displayMetrics.density).toInt()  // 导航栏上方
                else -> 0
            }
        }
    }

    private fun addToWindowIfNeeded() {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (isShowing) {
            try {
                wm.updateViewLayout(overlayView, getViewParams())
                LogCollector.d(TAG, "Overlay updated")
            } catch (e: Exception) {
                LogCollector.e(TAG, "Failed to update overlay", e)
            }
        } else {
            try {
                wm.addView(overlayView, getViewParams())
                isShowing = true
                LogCollector.d(TAG, "Overlay added to window")
            } catch (e: Exception) {
                LogCollector.e(TAG, "Failed to add overlay", e)
            }
        }
    }

    private fun removeFromWindow() {
        if (!isShowing) return
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        try {
            wm.removeView(overlayView)
        } catch (_: Exception) {}
        isShowing = false
    }

    private fun scheduleAutoDismiss(enabled: Boolean, customDurationMs: Long? = null) {
        cancelAutoDismiss()
        if (!enabled) return
        val duration = customDurationMs ?: getDurationMs()
        dismissRunnable = Runnable {
            removeFromWindow()
            showNextMessage()
        }
        mainHandler.postDelayed(dismissRunnable!!, duration)
    }

    private fun cancelAutoDismiss() {
        dismissRunnable?.let { mainHandler.removeCallbacks(it) }
        dismissRunnable = null
    }

    private fun copyToClipboard(text: String) {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("translation_error", text))
        } catch (e: Exception) {
            LogCollector.e(TAG, "Failed to copy to clipboard", e)
        }
    }

    private fun runOnMainThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }
}
