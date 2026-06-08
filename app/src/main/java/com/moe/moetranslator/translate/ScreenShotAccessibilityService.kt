/*
 * Copyright (C) 2024 murangogo
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along
 * with this library; if not, write to the Free Software Foundation, Inc.,
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA
 */

package com.moe.moetranslator.translate

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.RectF
import android.os.Build
import android.util.Log
import android.view.Display.DEFAULT_DISPLAY
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 单例类管理SharedFlow
object ScreenshotManager {
    private val _screenshotFlow = MutableSharedFlow<Bitmap>()
    val screenshotFlow = _screenshotFlow.asSharedFlow()

    // 内容变化事件流（AccessibilityService 通知 MangaFloatingService 加速检测）
    private val _contentChangedFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val contentChangedFlow = _contentChangedFlow.asSharedFlow()

    suspend fun emitScreenshot(screenshot: Bitmap) {
        _screenshotFlow.emit(screenshot)
    }

    fun notifyContentChanged() {
        _contentChangedFlow.tryEmit(Unit)
    }
}

class ScreenShotAccessibilityService: AccessibilityService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // 当用户点击悬浮球时调用此方法
    fun takeScreenshot(mRectF: RectF?, offset: Point) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {  // Android 11及以上
            takeScreenshotImpl(mRectF, offset)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun takeScreenshotImpl(mRectF: RectF?, offset: Point) {
        try {
            takeScreenshot(
                DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        var bitmap: Bitmap? = null
                        try {
                            bitmap = Bitmap.wrapHardwareBuffer(
                                screenshot.hardwareBuffer,
                                screenshot.colorSpace
                            )?.copy(Bitmap.Config.ARGB_8888, true)

                            Log.d("ASSOFFSET", "x:"+offset.x+"  y:"+offset.y)
                            if (mRectF != null){
                                val b = bitmap!!
                                val x = (mRectF.left.toInt() + offset.x).coerceIn(0, b.width - 1)
                                val y = (mRectF.top.toInt() + offset.y).coerceIn(0, b.height - 1)
                                val w = mRectF.width().toInt().coerceAtMost(b.width - x)
                                val h = mRectF.height().toInt().coerceAtMost(b.height - y)
                                if (w > 0 && h > 0) {
                                    bitmap = Bitmap.createBitmap(b, x, y, w, h)
                                }
                            }

                            //使用sharedflow，发送截图完成信号以及bitmap
                            bitmap?.let { nonNullBitmap ->
                                serviceScope.launch {

                                    // 在IO线程保存图片
                                    val savePath = withContext(Dispatchers.IO) {
                                        ImageFileManager.saveBitmapToCache(
                                            applicationContext,
                                            nonNullBitmap
                                        )
                                    }

                                    when(savePath){
                                        null -> {
                                            showToast("Failed to save image")
                                        }
                                        else -> {
                                            try {
                                                ScreenshotManager.emitScreenshot(nonNullBitmap)
                                            } catch (e: Exception) {
                                                showToast("Error emitting screenshot：$e")
                                            }
                                        }
                                    }
                                }
                            }

                        } catch (e: Exception) {
                            Log.e("Screenshot", "Error processing screenshot", e)
                        } finally {
                            screenshot.hardwareBuffer.close()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        val errorText = when (errorCode){
                            ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "截图失败：内部错误"
                            ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "截图失败：无无障碍权限"
                            ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "截图频率过高，已自动降速"
                            ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "截图失败：无效显示器"
                            else -> "截图失败：未知错误 $errorCode"
                        }
                        Log.e("Screenshot", "onFailure: errorCode=$errorCode, $errorText")
                        showToast(errorText)
                    }
                }
            )
        } catch (e: Exception) {
            showToast("Failed to take screenshot：$e")
        }
    }


    fun showToast(message: String) {
        serviceScope.launch {
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        AccessibilityServiceManager.setService(this)
        Log.d("CONNECT", "Service Connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 通知自动翻译加速检测（TYPE_WINDOW_CONTENT_CHANGED 表示屏幕内容变化）
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            ScreenshotManager.notifyContentChanged()
        }
    }

    override fun onInterrupt() {
        // 处理中断
    }

    override fun onDestroy() {
        super.onDestroy()
        // 取消服务
        AccessibilityServiceManager.setService(null)
        // 取消所有协程
        serviceScope.cancel()
    }
}