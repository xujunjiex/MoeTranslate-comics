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
import android.os.HandlerThread
import android.os.Looper
import android.graphics.Point
import android.view.WindowManager
import com.moe.moetranslator.utils.LogCollector
import com.moe.moetranslator.utils.PerceptualHash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * MediaProjection 截图器
 *
 * 关键设计：OnImageAvailableListener 必须在独立后台线程上运行，
 * 不能用主线程 Handler —— 主线程被 OCR/翻译/渲染阻塞时，listener 回调
 * 无法执行，导致 imageAvailable 永远不会被设为 true → 超时。
 */
class Shooter(private val context: Context) {
    companion object {
        private const val TAG = "Shooter"
        private const val IMAGE_BUFFER_COUNT = 2
        private const val WAIT_IMAGE_TIMEOUT_MS = 200L
        private const val WAIT_IMAGE_DELAY_MS = 10L
        private const val FRAME_READY_DELAY_MS = 50L
        private const val FRAME_CHANGE_CHECK_INTERVAL_MS = 300L  // 帧变化检查节流间隔
        private const val FRAME_CHANGE_THRESHOLD = 0.95f         // 低于此相似度视为帧变化
    }

    private var lastFrameHash: Long = 0L
    private var lastFrameChangeCheckTime: Long = 0L

    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var lastBitmap: Bitmap? = null
    private var listenerThread: HandlerThread? = null
    private val bitmapLock = Any()  // 保护 lastBitmap 的并发访问

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

            // 注册回调，监听投影停止（用主线程 Handler 即可，回调很轻）
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

            // 创建专用后台线程处理 ImageAvailable 回调
            // 不能用主线程 —— 主线程被 OCR/翻译阻塞时回调无法执行
            listenerThread = HandlerThread("Shooter-ImageListener").also { it.start() }
            val listenerHandler = Handler(listenerThread!!.looper)

            // 创建 ImageReader
            imageReader = ImageReader.newInstance(
                screenWidth, screenHeight,
                PixelFormat.RGBA_8888, IMAGE_BUFFER_COUNT
            )
            imageReader!!.setOnImageAvailableListener({ reader ->
                // 在后台线程直接 acquire 并缓存，避免主线程阻塞导致丢帧
                try {
                    val image = reader.acquireLatestImage()
                    if (image != null) {
                        val newBitmap = convert(image)
                        image.close()
                        synchronized(bitmapLock) {
                            lastBitmap?.recycle()
                            lastBitmap = newBitmap
                        }
                        imageAvailable = true

                        // P1: 帧变化检测 — 当内容发生显著变化时通知上层加速检测
                        // 节流：最多每 300ms 检查一次，避免 60fps 下频繁计算 pHash
                        val now = System.currentTimeMillis()
                        if (now - lastFrameChangeCheckTime >= FRAME_CHANGE_CHECK_INTERVAL_MS) {
                            lastFrameChangeCheckTime = now
                            val currentHash = PerceptualHash.compute(newBitmap, centerCrop = true)
                            val prevHash = lastFrameHash
                            lastFrameHash = currentHash
                            if (prevHash != 0L) {
                                val sim = PerceptualHash.similarity(prevHash, currentHash)
                                if (sim < FRAME_CHANGE_THRESHOLD) {
                                    // 内容发生显著变化（如翻页），通知上层加速检测
                                    ScreenshotManager.notifyContentChanged()
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    LogCollector.e(TAG, "Listener acquire failed", e)
                }
            }, listenerHandler)

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
     * @return Bitmap 的副本，调用方负责 recycle；null 表示失败
     */
    suspend fun shot(): Bitmap? = withContext(Dispatchers.IO) {
        if (!ready) {
            LogCollector.w(TAG, "Not ready, returning null")
            return@withContext null
        }

        // 等待 imageAvailable（listener 在后台线程设置此标志）
        val startTime = System.currentTimeMillis()
        while (!imageAvailable && System.currentTimeMillis() - startTime < WAIT_IMAGE_TIMEOUT_MS) {
            delay(WAIT_IMAGE_DELAY_MS)
        }

        if (!imageAvailable) {
            // 屏幕静止时 VirtualDisplay 可能不再产生新帧，buffer 为空导致 timeout。
            // 检测逻辑（pHash 比较）只需要最近的截图，不一定要最新帧。
            // 返回 lastBitmap 的缓存副本，让上层正常做相似度判断。
            LogCollector.w(TAG, "Timeout waiting for image, returning cached bitmap (ready=$ready, projection=${mediaProjection != null})")
            return@withContext synchronized(bitmapLock) {
                lastBitmap?.let { bmp -> bmp.copy(bmp.config ?: Bitmap.Config.ARGB_8888, false) }
            }
        }

        // 额外延迟确保帧就绪
        delay(FRAME_READY_DELAY_MS)

        // listener 已经在后台线程完成了 acquire + convert，直接复制结果
        imageAvailable = false
        return@withContext synchronized(bitmapLock) {
            lastBitmap?.let { bmp -> bmp.copy(bmp.config ?: Bitmap.Config.ARGB_8888, false) }
        }
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
        synchronized(bitmapLock) {
            lastBitmap?.recycle()
            lastBitmap = null
        }
        lastFrameHash = 0L
        lastFrameChangeCheckTime = 0L
        imageAvailable = false
        ready = false
        imageReader?.close()
        imageReader = null
        virtualDisplay?.release()
        virtualDisplay = null
        mediaProjection?.stop()
        mediaProjection = null
        listenerThread?.quitSafely()
        listenerThread = null
    }
}
