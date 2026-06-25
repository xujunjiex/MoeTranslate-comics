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

        val intent = MediaProjectionIntentHolder.intent ?: return false
        isInitialized = shooter.init(intent)
        return isInitialized
    }

    override suspend fun takeScreenshot(cropRect: RectF?, offset: Point): Bitmap? {
        if (!ensureInitialized()) {
            LogCollector.w(TAG, "Not initialized")
            return null
        }

        val fullBitmap = shooter.shot() ?: return null

        return if (cropRect != null) {
            ScreenshotManager.cropBitmap(fullBitmap, cropRect, offset)
        } else {
            fullBitmap
        }
    }

    override fun release() {
        shooter.release()
        isInitialized = false
    }
}
