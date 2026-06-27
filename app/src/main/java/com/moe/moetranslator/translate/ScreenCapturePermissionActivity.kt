package com.moe.moetranslator.translate

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.app.Activity
import com.moe.moetranslator.manga.MangaFloatingService
import com.moe.moetranslator.utils.LogCollector
import com.moe.moetranslator.utils.ServiceUtils
import com.moe.moetranslator.utils.UiUtils

/**
 * 截图权限请求 Activity
 * 透明 Activity，弹出系统授权弹窗后立即关闭
 */
class ScreenCapturePermissionActivity : Activity() {
    companion object {
        private const val TAG = "ScreenCapturePermission"
        private const val REQUEST_CODE = 222

        /**
         * 启动权限请求
         */
        fun start(context: Context) {
            val intent = Intent(context, ScreenCapturePermissionActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mgr.createScreenCaptureIntent(), REQUEST_CODE)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_CODE) {
            if (resultCode == RESULT_OK && data != null) {
                // 保存 Intent
                MediaProjectionIntentHolder.set(data)
                LogCollector.d(TAG, "Permission granted")
            } else {
                // 用户拒绝
                LogCollector.w(TAG, "Permission denied")
                UiUtils.showToast(this, "需要截图权限才能翻译", isShort = true)
            }

            // 通知服务权限已处理
            notifyServices()
        }

        finish()
    }

    /**
     * 通知当前正在运行的翻译服务权限已处理
     * 只通知正在运行的服务，避免意外启动另一个服务
     */
    private fun notifyServices() {
        if (ServiceUtils.isServiceRunning(this, MangaFloatingService::class.java)) {
            LogCollector.d(TAG, "Notifying MangaFloatingService of permission result")
            val mangaIntent = Intent(this, MangaFloatingService::class.java).apply {
                putExtra("PERMISSION_RESULT", true)
            }
            startService(mangaIntent)
        } else if (ServiceUtils.isServiceRunning(this, FloatingBallService::class.java)) {
            LogCollector.d(TAG, "Notifying FloatingBallService of permission result")
            val gameIntent = Intent(this, FloatingBallService::class.java).apply {
                putExtra("PERMISSION_RESULT", true)
            }
            startService(gameIntent)
        } else {
            LogCollector.w(TAG, "No translation service running, permission result not delivered")
        }
    }
}
