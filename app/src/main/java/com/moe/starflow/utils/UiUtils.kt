package com.moe.starflow.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * UI 相关工具函数
 */
object UiUtils {

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 显示 Toast（自动切换到主线程）
     * @param context 上下文
     * @param message 消息内容
     * @param isShort 是否短时显示，默认 true
     */
    fun showToast(context: Context, message: String, isShort: Boolean = true) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // 已在主线程，直接显示
            Toast.makeText(context, message, if (isShort) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
        } else {
            // 在后台线程，切换到主线程
            mainHandler.post {
                Toast.makeText(context, message, if (isShort) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
            }
        }
    }
}
