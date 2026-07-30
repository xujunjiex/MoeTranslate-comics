package com.moe.starflow.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.moe.starflow.R
import com.moe.starflow.data.ModelDownloadRepository
import com.moe.starflow.manga.ModelKey
import com.moe.starflow.utils.LogCollector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import com.moe.starflow.service.helpers.ChecksumHelper
import com.moe.starflow.service.helpers.VerifyResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class ModelDownloadService : LifecycleService() {

    private val activeJobs = ConcurrentHashMap<ModelKey, Job>()
    private val lifecycleMutex = Mutex()
    private val repo by lazy { ModelDownloadRepository.getInstance(applicationContext) }

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    private var isForegroundStarted = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForegroundIfNeeded(intent)

        val action = intent?.action ?: return START_NOT_STICKY
        val modelKey = intent.getStringExtra(EXTRA_MODEL_KEY)
            ?.let { ModelKey.valueOf(it) } ?: return START_NOT_STICKY

        when (action) {
            ACTION_START_DOWNLOAD -> startDownload(modelKey = modelKey, isResume = false)
            ACTION_RESUME_DOWNLOAD -> startDownload(modelKey = modelKey, isResume = true)
            ACTION_PAUSE_DOWNLOAD -> lifecycleScope.launch { pauseDownload(modelKey) }
            ACTION_CANCEL_DOWNLOAD -> lifecycleScope.launch { cancelDownload(modelKey) }
        }
        return START_NOT_STICKY
    }

    private fun startDownload(modelKey: ModelKey, isResume: Boolean) {
        activeJobs.computeIfAbsent(modelKey) {
            lifecycleScope.launch(Dispatchers.IO) {
                downloadModelInternal(modelKey, isResume)
            }
        }
    }

    private suspend fun pauseDownload(modelKey: ModelKey) {
        lifecycleMutex.withLock {
            activeJobs.remove(modelKey)?.cancel()
            repo.markPartial(modelKey)
            checkStopSelf()
        }
    }

    private suspend fun cancelDownload(modelKey: ModelKey) {
        lifecycleMutex.withLock {
            activeJobs.remove(modelKey)?.cancel()
            repo.cancelDownload(modelKey)
            checkStopSelf()
        }
    }

    private fun checkStopSelf() {
        if (activeJobs.isEmpty()) stopSelf()
    }

    private fun startForegroundIfNeeded(intent: Intent?) {
        if (isForegroundStarted) return
        isForegroundStarted = true
        val placeholder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("模型下载")
            .setContentText("准备中...")
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID_SERVICE, placeholder)
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "模型下载",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    internal suspend fun downloadModelInternal(modelKey: ModelKey, isResume: Boolean) {
        try {
            val modelInfo = repo.getModelInfo(modelKey) ?: run {
                LogCollector.e(TAG, "ModelInfo not found: $modelKey")
                return
            }
            if (modelInfo.files.size == 1) {
                downloadSingleFile(modelKey, modelInfo.files[0])
            } else {
                downloadMultiFile(modelKey, modelInfo.files)
            }
            if (allFilesComplete(modelKey, modelInfo.files)) {
                repo.markDone(modelKey)
            }
        } catch (e: CancellationException) {
            // propagate
        } finally {
            activeJobs.remove(modelKey)
            checkStopSelf()
        }
    }

    // -- Download Logic -- //

    private suspend fun downloadSingleFile(modelKey: ModelKey, fileInfo: com.moe.starflow.data.FileInfo) {
        val destFile = targetFileFor(modelKey, fileInfo.fileName)
        if (verifyFile(destFile, fileInfo) == VerifyResult.COMPLETE) return

        val result = com.moe.starflow.manga.ModelDownloadManager.downloadModel(
            context = applicationContext,
            url = fileInfo.downloadUrl,
            checksum = fileInfo.checksum,
            destFile = destFile,
            onProgress = object : com.moe.starflow.manga.ModelDownloadManager.ProgressCallback {
                override fun onProgress(bytesRead: Long, totalBytes: Long, speed: Float) {
                    lifecycleScope.launch(Dispatchers.Main) {
                        if (repo.getState(modelKey) !is com.moe.starflow.data.DownloadState.Running) {
                            repo.markRunning(modelKey)
                        }
                        repo.updateProgress(
                            modelKey, bytesRead, totalBytes,
                            (speed * 1_000_000).toLong()
                        )
                    }
                }
            }
        )
        if (result.isFailure) {
            repo.markPartial(modelKey)
        }
    }

    private suspend fun downloadMultiFile(modelKey: ModelKey, files: List<com.moe.starflow.data.FileInfo>) {
        var completedBytes = 0L
        val totalBytes = files.sumOf { it.fileSize }

        for (fileInfo in files) {
            if (!kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.isActive!!) return

            val destFile = targetFileFor(modelKey, fileInfo.fileName)
            if (verifyFile(destFile, fileInfo) == VerifyResult.COMPLETE) {
                completedBytes += fileInfo.fileSize
                continue
            }

            val result = com.moe.starflow.manga.ModelDownloadManager.downloadModel(
                context = applicationContext,
                url = fileInfo.downloadUrl,
                checksum = fileInfo.checksum,
                destFile = destFile,
                onProgress = object : com.moe.starflow.manga.ModelDownloadManager.ProgressCallback {
                    override fun onProgress(bytesRead: Long, totalBytes: Long, speed: Float) {
                        lifecycleScope.launch(Dispatchers.Main) {
                            repo.updateProgress(
                                modelKey, completedBytes + bytesRead, totalBytes,
                                (speed * 1_000_000).toLong()
                            )
                        }
                    }
                }
            )

            if (result.isSuccess) {
                completedBytes += fileInfo.fileSize
            } else {
                repo.markPartial(modelKey)
                return
            }
        }
    }

    private fun allFilesComplete(modelKey: ModelKey, files: List<com.moe.starflow.data.FileInfo>): Boolean {
        return files.all { fileInfo ->
            verifyFile(targetFileFor(modelKey, fileInfo.fileName), fileInfo) == VerifyResult.COMPLETE
        }
    }

    private fun verifyFile(file: File, fileInfo: com.moe.starflow.data.FileInfo): VerifyResult {
        if (!file.exists()) return VerifyResult.MISSING
        if (file.length() != fileInfo.fileSize) {
            file.delete()
            return VerifyResult.DAMAGED
        }
        if (!ChecksumHelper.verifyChecksum(file, fileInfo.checksum)) {
            file.delete()
            return VerifyResult.DAMAGED
        }
        return VerifyResult.COMPLETE
    }

    private fun targetFileFor(modelKey: ModelKey, fileName: String): File {
        val baseDir = baseDirFor(modelKey)
        if (!baseDir.exists()) baseDir.mkdirs()
        return File(baseDir, fileName)
    }

    private fun baseDirFor(modelKey: ModelKey): File = when (modelKey) {
        ModelKey.NLLB_GROUP -> File(applicationContext.getExternalFilesDir(null), "models")
        ModelKey.MANGA_OCR_GROUP -> File(applicationContext.getExternalFilesDir(null), "manga_ocr_download")
        ModelKey.RT_DETR_V2 -> File(applicationContext.getExternalFilesDir(null), "rt_detr")
        ModelKey.PP_OCR_V6_MEDIUM_DET, ModelKey.PP_OCR_V6_MEDIUM_REC ->
            File(applicationContext.getExternalFilesDir(null), "ppocrv6")
        ModelKey.PP_OCR_V5_DET, ModelKey.PP_OCR_V5_REC_ZH,
        ModelKey.PP_OCR_V5_REC_EN, ModelKey.PP_OCR_V5_REC_KO,
        ModelKey.PP_OCR_V5_REC_RU -> File(applicationContext.getExternalFilesDir(null), "ppocrv5")
    }

    companion object {
        private const val TAG = "ModelDownloadService"
        const val ACTION_START_DOWNLOAD = "com.moe.starflow.action.START_DOWNLOAD"
        const val ACTION_RESUME_DOWNLOAD = "com.moe.starflow.action.RESUME_DOWNLOAD"
        const val ACTION_PAUSE_DOWNLOAD = "com.moe.starflow.action.PAUSE_DOWNLOAD"
        const val ACTION_CANCEL_DOWNLOAD = "com.moe.starflow.action.CANCEL_DOWNLOAD"
        const val EXTRA_MODEL_KEY = "model_key"
        const val NOTIFICATION_ID_SERVICE = 9999
        const val CHANNEL_ID = "model_download"

        fun startDownload(context: Context, modelKey: ModelKey, isResume: Boolean) {
            val intent = Intent(context, ModelDownloadService::class.java).apply {
                action = if (isResume) ACTION_RESUME_DOWNLOAD else ACTION_START_DOWNLOAD
                putExtra(EXTRA_MODEL_KEY, modelKey.name)
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                LogCollector.e(TAG, "Failed to start service", e)
            }
        }

        fun pauseDownload(context: Context, modelKey: ModelKey) {
            val intent = Intent(context, ModelDownloadService::class.java).apply {
                action = ACTION_PAUSE_DOWNLOAD
                putExtra(EXTRA_MODEL_KEY, modelKey.name)
            }
            context.startService(intent)
        }

        fun cancelDownload(context: Context, modelKey: ModelKey) {
            val intent = Intent(context, ModelDownloadService::class.java).apply {
                action = ACTION_CANCEL_DOWNLOAD
                putExtra(EXTRA_MODEL_KEY, modelKey.name)
            }
            context.startService(intent)
        }
    }
}
