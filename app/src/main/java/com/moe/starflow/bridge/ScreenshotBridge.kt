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

package com.moe.starflow.bridge

import android.graphics.Bitmap
import android.graphics.Point
import com.moe.starflow.translate.AccessibilityServiceManager
import com.moe.starflow.translate.ScreenshotManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

object ScreenshotBridge {

    /**
     * Collect screenshots emitted by the accessibility service.
     * @param scope The coroutine scope to collect in.
     * @param callback Called on each screenshot bitmap.
     * @return The collection Job, can be cancelled to stop collecting.
     */
    fun collectScreenshot(scope: CoroutineScope, callback: (Bitmap) -> Unit): Job {
        return scope.launch {
            ScreenshotManager.screenshotFlow.collect { data ->
                // 游戏翻译：使用裁剪后的 bitmap（或全屏 bitmap）
                val bitmap = data.croppedBitmap ?: data.fullBitmap
                callback(bitmap)
                // 全屏 bitmap 如果不是使用中的 bitmap，释放它
                if (data.croppedBitmap != null) data.fullBitmap.recycle()
            }
        }
    }

    /**
     * Trigger a screenshot via the accessibility service.
     * Full-screen screenshot (no crop region).
     */
    fun takeScreenshot() {
        AccessibilityServiceManager.takeScreenshot(null, Point(0, 0))
    }
}
