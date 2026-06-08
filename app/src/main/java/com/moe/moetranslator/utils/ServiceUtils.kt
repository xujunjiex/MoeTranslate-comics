package com.moe.moetranslator.utils

import android.app.ActivityManager
import android.content.Context

/**
 * 服务状态检查工具
 */
object ServiceUtils {

    /**
     * 检查指定服务是否正在运行
     * 注意：此 API 在 Android 8+ 已废弃，但仍可用
     */
    @Suppress("DEPRECATION")
    fun isServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return manager.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == serviceClass.name }
    }
}
