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

    private val _contentChangedFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val contentChangedFlow = _contentChangedFlow.asSharedFlow()

    /**
     * 发送截图数据
     */
    fun emitScreenshot(data: ScreenshotData) {
        _screenshotFlow.tryEmit(data)
    }

    /**
     * 通知内容变化（用于加速漫画自动翻译检测）
     */
    fun notifyContentChanged() {
        _contentChangedFlow.tryEmit(Unit)
    }

    /**
     * 裁剪 Bitmap
     */
    fun cropBitmap(source: Bitmap, cropRect: RectF, offset: Point): Bitmap {
        val x = (cropRect.left - offset.x).toInt().coerceIn(0, source.width - 1)
        val y = (cropRect.top - offset.y).toInt().coerceIn(0, source.height - 1)
        val w = cropRect.width().toInt().coerceAtMost(source.width - x)
        val h = cropRect.height().toInt().coerceAtMost(source.height - y)

        return if (w > 0 && h > 0) {
            Bitmap.createBitmap(source, x, y, w, h)
        } else {
            source
        }
    }
}
