package com.moe.moetranslator.translate

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.moe.moetranslator.manga.MangaFloatingService
import com.moe.moetranslator.utils.LogCollector
import com.moe.moetranslator.utils.UiUtils

/**
 * 截图权限请求 Activity
 * 透明 Activity，弹出系统授权弹窗后立即关闭
 */
class ScreenCapturePermissionActivity : AppCompatActivity() {
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
     * 通知所有翻译服务权限已处理
     */
    private fun notifyServices() {
        // 通知 FloatingBallService
        val gameIntent = Intent(this, FloatingBallService::class.java).apply {
            putExtra("PERMISSION_RESULT", true)
        }
        startService(gameIntent)

        // 通知 MangaFloatingService
        val mangaIntent = Intent(this, MangaFloatingService::class.java).apply {
            putExtra("PERMISSION_RESULT", true)
        }
        startService(mangaIntent)
    }
}
