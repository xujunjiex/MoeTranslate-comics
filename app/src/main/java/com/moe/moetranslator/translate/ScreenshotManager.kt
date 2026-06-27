package com.moe.moetranslator.translate

import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.RectF
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 截图数据
 */
data class ScreenshotData(val fullBitmap: Bitmap, val croppedBitmap: Bitmap?)

/**
 * 截图管理器 - 解耦截图生产者和消费者
 * 从 ScreenShotAccessibilityService 中提取，支持多种截图方式
 */
object ScreenshotManager {
    private val _screenshotFlow = MutableSharedFlow<ScreenshotData>(extraBufferCapacity = 1)
    val screenshotFlow = _screenshotFlow.asSharedFlow()

    private val _eventTriggerFlow = MutableSharedFlow<String>(extraBufferCapacity = 2)
    val eventTriggerFlow = _eventTriggerFlow.asSharedFlow()

    /** 框选区域（用于事件过滤） */
    var cropRect: RectF? = null

    /** 事件模式设置 */
    private var eventModeHandler: AccessibilityEventHandler? = null
    fun setEventMode(mode: AccessibilityEventHandler.Mode) {
        eventModeHandler?.setMode(mode)
    }
    fun registerEventHandler(handler: AccessibilityEventHandler) {
        eventModeHandler = handler
    }

    /**
     * 发送截图数据
     */
    fun emitScreenshot(data: ScreenshotData) {
        _screenshotFlow.tryEmit(data)
    }

    /**
     * 通知无障碍事件触发（经 EventHandler 去抖后调用）
     */
    fun notifyEventTrigger(eventType: String) {
        _eventTriggerFlow.tryEmit(eventType)
    }

    /**
     * 裁剪 Bitmap
     */
    fun cropBitmap(source: Bitmap, cropRect: RectF, offset: Point): Bitmap {
        val x = (cropRect.left.toInt() + offset.x).coerceIn(0, source.width - 1)
        val y = (cropRect.top.toInt() + offset.y).coerceIn(0, source.height - 1)
        val w = cropRect.width().toInt().coerceAtMost(source.width - x)
        val h = cropRect.height().toInt().coerceAtMost(source.height - y)

        return if (w > 0 && h > 0) {
            Bitmap.createBitmap(source, x, y, w, h)
        } else {
            source
        }
    }
}
