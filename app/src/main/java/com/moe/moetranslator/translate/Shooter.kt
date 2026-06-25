package com.moe.moetranslator.translate

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.graphics.Point
import android.view.WindowManager
import com.moe.moetranslator.utils.LogCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * MediaProjection 截图器
 * 参考 fby Shooter.java，改进：
 * - 使用实际屏幕尺寸而非固定 2000×2000
 * - 使用协程 suspend 替代忙等待
 * - 添加完善的错误处理和日志
 */
class Shooter(private val context: Context) {
    companion object {
        private const val TAG = "Shooter"
        private const val IMAGE_BUFFER_COUNT = 2
        private const val WAIT_IMAGE_TIMEOUT_MS = 500L
        private const val WAIT_IMAGE_DELAY_MS = 10L
        private const val FRAME_READY_DELAY_MS = 50L
    }

    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var lastBitmap: Bitmap? = null
    @Volatile private var imageAvailable = false

    @Volatile var ready = false
        private set

    /**
     * 初始化 MediaProjection
     * @param captureIntent 从 ScreenCapturePermissionActivity 获取的 Intent
     * @return true 成功，false 失败
     */
    fun init(captureIntent: Intent): Boolean {
        release()
        try {
            val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mgr.getMediaProjection(-1, captureIntent)

            // 注册回调，监听投影停止
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    LogCollector.d(TAG, "MediaProjection stopped")
                    ready = false
                }
            }, Handler(Looper.getMainLooper()))

            // 获取实际屏幕尺寸（使用 getRealSize 匹配项目规范）
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val screenPoint = Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(screenPoint)
            val screenWidth = screenPoint.x
            val screenHeight = screenPoint.y
            val dpi = context.resources.displayMetrics.densityDpi

            // 创建 ImageReader
            imageReader = ImageReader.newInstance(
                screenWidth, screenHeight,
                PixelFormat.RGBA_8888, IMAGE_BUFFER_COUNT
            )
            imageReader!!.setOnImageAvailableListener({
                imageAvailable = true
            }, Handler(Looper.getMainLooper()))

            // 创建 VirtualDisplay
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "MoeTranslate",
                screenWidth, screenHeight, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface,
                null, null
            )

            ready = true
            LogCollector.d(TAG, "Initialized: ${screenWidth}x${screenHeight}")
            return true
        } catch (e: Exception) {
            LogCollector.e(TAG, "Init failed", e)
            release()
            return false
        }
    }

    /**
     * 截图
     * @return Bitmap 或 null（失败时）
     */
    suspend fun shot(): Bitmap? = withContext(Dispatchers.IO) {
        if (!ready) {
            LogCollector.w(TAG, "Not ready")
            return@withContext lastBitmap
        }

        // 等待 imageAvailable（最多 500ms）
        val startTime = System.currentTimeMillis()
        while (!imageAvailable && System.currentTimeMillis() - startTime < WAIT_IMAGE_TIMEOUT_MS) {
            delay(WAIT_IMAGE_DELAY_MS)
        }

        if (!imageAvailable) {
            LogCollector.w(TAG, "Timeout waiting for image")
            return@withContext lastBitmap
        }

        // 额外延迟确保帧就绪
        delay(FRAME_READY_DELAY_MS)

        try {
            val image = imageReader?.acquireLatestImage()
            if (image != null) {
                lastBitmap?.recycle()
                lastBitmap = convert(image)
                image.close()
                imageAvailable = false
            }
        } catch (e: Exception) {
            LogCollector.e(TAG, "Shot failed", e)
        }

        lastBitmap
    }

    /**
     * 将 Image 转换为 Bitmap
     */
    private fun convert(image: Image): Bitmap {
        val width = image.width
        val height = image.height
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = (rowStride - pixelStride * width) / pixelStride

        val bitmap = Bitmap.createBitmap(width + rowPadding, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)

        // 裁剪到实际尺寸
        return if (rowPadding > 0) {
            val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
            bitmap.recycle()
            cropped
        } else {
            bitmap
        }
    }

    /**
     * 释放资源
     */
    fun release() {
        LogCollector.d(TAG, "Releasing")
        lastBitmap?.recycle()
        lastBitmap = null
        imageAvailable = false
        ready = false
        imageReader?.close()
        imageReader = null
        virtualDisplay?.release()
        virtualDisplay = null
        mediaProjection?.stop()
        mediaProjection = null
    }
}
