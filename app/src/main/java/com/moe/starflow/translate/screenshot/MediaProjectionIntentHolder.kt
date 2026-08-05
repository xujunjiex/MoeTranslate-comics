package com.moe.starflow.translate.screenshot
import com.moe.starflow.translate.widget.*
import com.moe.starflow.translate.*
import com.moe.starflow.manga.*
import com.moe.starflow.manga.engine.*
import com.moe.starflow.manga.types.*
import com.moe.starflow.manga.config.*

import android.content.Intent

/**
 * MediaProjection Intent 持有者
 * 用于在 ScreenCapturePermissionActivity 和翻译服务之间传递 Intent
 * Intent 只能使用一次，用后即清
 */
object MediaProjectionIntentHolder {
    private var _intent: Intent? = null

    val intent: Intent?
        get() = _intent

    fun set(intent: Intent?) {
        _intent = intent
    }

    fun clear() {
        _intent = null
    }
}
