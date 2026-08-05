package com.moe.starflow.translate.screenshot
import com.moe.starflow.translate.*
import com.moe.starflow.manga.*
import com.moe.starflow.manga.engine.*
import com.moe.starflow.manga.types.*
import com.moe.starflow.manga.config.*

import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import com.moe.starflow.utils.LogCollector

/**
 * 无障碍事件去抖处理器。
 * 漫画模式监听滚动停止、屏幕内容变化等事件，去抖后触发检测。
 */
class AccessibilityEventHandler(
    private val onTriggerDetection: (String) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private val debounceRunnables = mutableMapOf<Int, Runnable>()

    enum class Mode { MANGA }
    private var currentMode = Mode.MANGA

    companion object {
        private const val DEBOUNCE_SCROLL_MS = 500L
        private const val DEBOUNCE_CONTENT_CHANGE_MS = 300L

        private val mangaDebounceTimes = mapOf(
            AccessibilityEvent.TYPE_VIEW_SCROLLED to DEBOUNCE_SCROLL_MS,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED to DEBOUNCE_CONTENT_CHANGE_MS
        )
    }

    fun setMode(mode: Mode) {
        if (currentMode == mode) return
        currentMode = mode
        debounceRunnables.values.forEach { handler.removeCallbacks(it) }
        debounceRunnables.clear()
    }

    fun onEvent(event: AccessibilityEvent) {
        val eventType = event.eventType
        val debounceTime = mangaDebounceTimes[eventType] ?: return

        val label = eventTypeToString(eventType)
        LogCollector.d("EventHandler", "收到事件: $label, 去抖: ${debounceTime}ms")

        debounceRunnables[eventType]?.let { handler.removeCallbacks(it) }
        val runnable = Runnable {
            LogCollector.d("EventHandler", "去抖触发: $label")
            onTriggerDetection(label)
        }
        debounceRunnables[eventType] = runnable
        handler.postDelayed(runnable, debounceTime)
    }

    fun cancel() {
        debounceRunnables.values.forEach { handler.removeCallbacks(it) }
        debounceRunnables.clear()
    }

    private fun eventTypeToString(eventType: Int): String = when (eventType) {
        AccessibilityEvent.TYPE_VIEW_SCROLLED -> "滚动停止"
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "屏幕内容变化"
        else -> "未知($eventType)"
    }
}
