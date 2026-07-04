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
import java.nio.ByteBuffer
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
    private var listenerHandler: Handler? = null
    private val bitmapLock = Any()  // 保护 lastBitmap 的并发访问

    @Volatile private var imageAvailable = false

    private var initRotation = -1  // init 时的屏幕方向，用于检测旋转

    @Volatile var ready = false
        private set

    @Volatile var frameSeq = 0L
        private set

    /**
     * ImageReader 回调：后台线程 acquire + convert + 缓存。
     * 提取为字段，供 init() 复用。
     */
    private val imageListener = ImageReader.OnImageAvailableListener { reader ->
        // 在后台线程直接 acquire 并缓存，避免主线程阻塞导致丢帧
        try {
            val image = reader.acquireLatestImage()
            if (image != null) {
                val newBitmap = convert(image)
                image.close()
                if (newBitmap == null) {
                    LogCollector.w(TAG, "convert returned null, skipping frame")
                    imageAvailable = true  // 唤醒等待的 shot()，避免永久超时
                    return@OnImageAvailableListener
                }

                // P1: 帧变化检测 — 用局部引用 newBitmap 计算，必须在存入 lastBitmap 之前。
                // 否则 release() 在主线程 recycle lastBitmap(==newBitmap) 会与此处竞态，
                // 导致 PerceptualHash.compute 抛 "Bitmap is recycled"。
                // 节流：最多每 300ms 检查一次，避免 60fps 下频繁计算 pHash
                val now = System.currentTimeMillis()
                if (now - lastFrameChangeCheckTime >= FRAME_CHANGE_CHECK_INTERVAL_MS && !newBitmap.isRecycled) {
                    lastFrameChangeCheckTime = now
                    try {
                        val currentHash = PerceptualHash.compute(newBitmap, centerCrop = true)
                        val prevHash = lastFrameHash
                        lastFrameHash = currentHash
                        if (prevHash != 0L) {
                            val sim = PerceptualHash.similarity(prevHash, currentHash)
                            if (sim < FRAME_CHANGE_THRESHOLD) {
                                // 内容发生显著变化，通知上层加速检测
                                ScreenshotManager.notifyEventTrigger("content_changed")
                            }
                        }
                    } catch (e: Exception) {
                        LogCollector.w(TAG, "frame-change pHash skipped: ${e.message}")
                    }
                }

                synchronized(bitmapLock) {
                    lastBitmap?.recycle()
                    lastBitmap = newBitmap
                }
                imageAvailable = true
                frameSeq++
            }
        } catch (e: Exception) {
            LogCollector.e(TAG, "Listener acquire failed", e)
        }
    }

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
            listenerHandler = Handler(listenerThread!!.looper)

            // 创建 ImageReader
            imageReader = ImageReader.newInstance(
                screenWidth, screenHeight,
                PixelFormat.RGBA_8888, IMAGE_BUFFER_COUNT
            )
            imageReader!!.setOnImageAvailableListener(imageListener, listenerHandler)

            // 创建 VirtualDisplay
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "MoeTranslate",
                screenWidth, screenHeight, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface,
                null, null
            )

            ready = true
            @Suppress("DEPRECATION")
            initRotation = wm.defaultDisplay.rotation
            LogCollector.d(TAG, "Initialized: ${screenWidth}x${screenHeight}, rotation=$initRotation")
            return true
        } catch (e: SecurityException) {
            // Token 过期或重复使用：清除已失效的 intent，下次请求新授权
            LogCollector.e(TAG, "MediaProjection token expired or reused, clearing stored intent", e)
            MediaProjectionIntentHolder.clear()
            release()
            return false
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

        // 屏幕旋转后重建 buffer 使其与当前屏幕坐标系一致
        ensureBufferOrientation()

        // 等待 imageAvailable（listener 在后台线程设置此标志）
        val startTime = System.currentTimeMillis()
        while (!imageAvailable && System.currentTimeMillis() - startTime < WAIT_IMAGE_TIMEOUT_MS) {
            delay(WAIT_IMAGE_DELAY_MS)
        }

        if (!imageAvailable) {
            // 屏幕静止时 VirtualDisplay 可能不再产生新帧，buffer 为空导致 timeout。
            // 检测逻辑（pHash 比较）只需要最近的截图，不一定要最新帧。
            // 返回 lastBitmap 的缓存副本，让上层正常做相似度判断。
            LogCollector.w(TAG, "shot TIMEOUT: no new frame in ${WAIT_IMAGE_TIMEOUT_MS}ms, lastFrameSeq=$frameSeq, ready=$ready")
            return@withContext synchronized(bitmapLock) {
                lastBitmap?.let { bmp -> bmp.copy(bmp.config ?: Bitmap.Config.ARGB_8888, false) }
            }
        }

        // 额外延迟确保帧就绪
        delay(FRAME_READY_DELAY_MS)

        // listener 已经在后台线程完成了 acquire + convert，直接复制结果
        imageAvailable = false
        val currentSeq = frameSeq
        return@withContext synchronized(bitmapLock) {
            lastBitmap?.let { bmp ->
                LogCollector.d(TAG, "shot OK: frameSeq=$currentSeq, size=${bmp.width}x${bmp.height}")
                bmp.copy(bmp.config ?: Bitmap.Config.ARGB_8888, false)
            }
        }
    }

    /**
     * 不等待新帧，直接返回最新缓存帧。
     * 游戏模式用：像素对比不要求绝对最新帧，避免画面静止时白等 200ms。
     */
    suspend fun shotNoWait(): Bitmap? = withContext(Dispatchers.IO) {
        if (!ready) {
            LogCollector.w(TAG, "Not ready, returning null")
            return@withContext null
        }
        return@withContext synchronized(bitmapLock) {
            lastBitmap?.let { bmp -> bmp.copy(bmp.config ?: Bitmap.Config.ARGB_8888, false) }
        }
    }

    /**
     * 屏幕旋转时重建 ImageReader + resize VirtualDisplay。
     * 不停 MediaProjection（避免 token 重用抛 SecurityException），
     * 只用 setSurface + resize 切换 buffer 方向。
     */
    private fun ensureBufferOrientation() {
        val reader = imageReader ?: return
        val vd = virtualDisplay ?: return
        val handler = listenerHandler ?: return
        try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            val currentRotation = wm.defaultDisplay.rotation
            if (currentRotation == initRotation) return

            val point = Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(point)
            val newW = point.x
            val newH = point.y
            if (newW <= 0 || newH <= 0) return
            if (newW == reader.width && newH == reader.height) return

            val dpi = context.resources.displayMetrics.densityDpi
            LogCollector.d(TAG, "!!! ROTATION buffer resize: ${reader.width}x${reader.height} -> ${newW}x${newH}, rotation $initRotation -> $currentRotation")

            reader.setOnImageAvailableListener(null, null)

            // 等待正在执行中的回调完成，防止 reader.close() 释放 native buffer
            // 时 imageListener 的 buffer.get() 还在读 → SIGSEGV
            val latch = java.util.concurrent.CountDownLatch(1)
            handler.post { latch.countDown() }
            try {
                latch.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {}

            val newReader = ImageReader.newInstance(newW, newH, PixelFormat.RGBA_8888, IMAGE_BUFFER_COUNT)
            newReader.setOnImageAvailableListener(imageListener, handler)
            vd.setSurface(newReader.surface)
            vd.resize(newW, newH, dpi)
            reader.close()
            imageReader = newReader
            imageAvailable = false
            synchronized(bitmapLock) {
                lastBitmap?.recycle()
                lastBitmap = null
            }
            lastFrameHash = 0L
            initRotation = currentRotation
        } catch (e: Exception) {
            LogCollector.e(TAG, "ensureBufferOrientation failed", e)
        }
    }

    /**
     * 将 Image 转换为 Bitmap。
     *
     * ⚠️ Image.planes[0].buffer 是 DirectByteBuffer，先 get() 拷贝到 Java 堆
     * byte[]，再用 ByteBuffer.wrap 包装后调用 copyPixelsFromBuffer，避免
     * DirectByteBuffer native 指针失效导致 memcpy crash。
     *
     * ⚠️ pixelStride 单位是**字节**（不是像素），RGBA_8888 = 4 bytes/pixel。
     */
    private fun convert(image: Image): Bitmap? {
        val width = image.width
        val height = image.height
        val planes = image.planes
        if (planes.isEmpty()) {
            LogCollector.e(TAG, "No planes in image")
            return lastKnownGoodBitmap()
        }

        val plane = planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride   // 字节/像素，RGBA_8888 = 4
        val rowStride = plane.rowStride        // 字节/行

        val rowPadding = (rowStride - pixelStride * width) / pixelStride
        val bitmapWidth = width + rowPadding
        val expectedBytes = bitmapWidth * height * 4

        val remaining = buffer.remaining()
        if (remaining < expectedBytes) {
            LogCollector.e(TAG, "Buffer too small: remaining=$remaining, expected=$expectedBytes")
            return lastKnownGoodBitmap()
        }

        // 先拷到 Java 堆 byte[]，避免 DirectByteBuffer native 指针问题
        val bytes = ByteArray(expectedBytes)
        try {
            buffer.rewind()
            buffer.get(bytes)
        } catch (e: Exception) {
            LogCollector.e(TAG, "Failed to read buffer to byte array: ${e.message}", e)
            return lastKnownGoodBitmap()
        }

        val bitmap = Bitmap.createBitmap(bitmapWidth, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
        } catch (e: Exception) {
            LogCollector.e(TAG, "copyPixelsFromBuffer failed: ${e.message}", e)
            bitmap.recycle()
            return lastKnownGoodBitmap()
        }

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
     * Fallback：返回缓存的最新有效帧，用于 convert 失败时降级
     */
    private fun lastKnownGoodBitmap(): Bitmap? {
        return synchronized(bitmapLock) {
            lastBitmap?.let { bmp -> bmp.copy(bmp.config ?: Bitmap.Config.ARGB_8888, false) }
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

        // 1. 先移除 listener，防止新回调入队
        imageReader?.setOnImageAvailableListener(null, null)

        // 2. 释放 VirtualDisplay（停止生产新帧）
        virtualDisplay?.release()
        virtualDisplay = null

        // 3. 退出 listener 线程（在 imageReader.close() 之前）
        //    quitSafely 会排空待处理的回调，此时 Image 仍有效
        listenerThread?.quitSafely()
        listenerThread = null
        listenerHandler = null

        // 4. 安全关闭 ImageReader——不再有回调访问其 Image
        imageReader?.close()
        imageReader = null

        // 5. 最后停止 MediaProjection
        mediaProjection?.stop()
        mediaProjection = null
    }
}
