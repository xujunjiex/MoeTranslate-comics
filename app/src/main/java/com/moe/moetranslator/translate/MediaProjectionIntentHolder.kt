package com.moe.moetranslator.translate

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
