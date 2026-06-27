package com.moe.moetranslator.translate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.RectF
import com.moe.moetranslator.utils.LogCollector

/**
 * MediaProjection 截图提供者
 * 使用 Shooter 进行截图
 */
class MediaProjectionProvider(private val context: Context) : ScreenshotProvider {
    companion object {
        private const val TAG = "MediaProjectionProvider"
    }

    private val shooter = Shooter(context)
    private var isInitialized = false

    override fun isAvailable(): Boolean {
        return MediaProjectionIntentHolder.intent != null
    }

    override fun needsPermission(): Boolean {
        return true
    }

    /**
     * 初始化（如果需要）
     * @return true 成功，false 需要请求权限
     */
    fun ensureInitialized(): Boolean {
        if (isInitialized && shooter.ready) return true

        val intent = MediaProjectionIntentHolder.intent
        if (intent == null) {
            LogCollector.w(TAG, "No MediaProjection intent available")
            return false
        }
        LogCollector.d(TAG, "Initializing Shooter with stored intent")
        isInitialized = shooter.init(intent)
        if (!isInitialized) {
            // init 失败（intent 已过期/被重用），清除存储的 intent 以便下次请求新的
            LogCollector.w(TAG, "Shooter init failed, clearing stored intent")
            MediaProjectionIntentHolder.clear()
        }
        LogCollector.d(TAG, "Shooter init result: $isInitialized")
        return isInitialized
    }

    override suspend fun takeScreenshot(cropRect: RectF?, offset: Point): Bitmap? {
        if (!ensureInitialized()) {
            LogCollector.w(TAG, "Not initialized, cannot take screenshot")
            return null
        }

        LogCollector.d(TAG, "Taking screenshot, cropRect=$cropRect")
        val fullBitmap = shooter.shot()
        if (fullBitmap == null) {
            LogCollector.w(TAG, "Shooter returned null bitmap")
            return null
        }
        LogCollector.d(TAG, "Full screenshot: ${fullBitmap.width}x${fullBitmap.height}")

        return if (cropRect != null) {
            val cropped = ScreenshotManager.cropBitmap(fullBitmap, cropRect, offset)
            LogCollector.d(TAG, "Cropped screenshot: ${cropped.width}x${cropped.height}")
            cropped
        } else {
            fullBitmap
        }
    }

    override fun release() {
        shooter.release()
        isInitialized = false
    }
}
