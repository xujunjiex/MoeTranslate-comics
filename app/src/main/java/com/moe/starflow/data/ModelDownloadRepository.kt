package com.moe.starflow.data

import android.content.Context
import com.moe.starflow.manga.ModelKey
import com.moe.starflow.utils.LogCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class ModelDownloadRepository private constructor(private val context: Context) {

    private val stateMutex = Mutex()
    private val stateMap = ConcurrentHashMap<ModelKey, DownloadState>()
    private val modelListCached = MutableStateFlow<List<ModelInfo>>(emptyList())

    private val _snapshot = MutableStateFlow(DownloadSnapshot(emptyMap(), emptySet()))
    fun observe(): StateFlow<DownloadSnapshot> = _snapshot.asStateFlow()

    data class DownloadSnapshot(
        val states: Map<ModelKey, DownloadState>,
        val activeNotifications: Set<ModelKey>
    )

    fun getState(modelKey: ModelKey): DownloadState = stateMap[modelKey] ?: DownloadState.Idle

    fun getModelInfo(modelKey: ModelKey): ModelInfo? =
        modelListCached.value.firstOrNull { it.modelKey == modelKey }

    fun getBrowserUrl(modelKey: ModelKey): String? =
        getModelInfo(modelKey)?.browserUrl

    suspend fun initialize() {
        withContext(Dispatchers.IO) {
            stateMutex.withLock {
                for (key in ModelKey.values()) {
                    val targetFile = targetFileFor(key)
                    val partFile = partFileFor(key)
                    val state = when {
                        targetFile.exists() && targetFile.length() > 0 -> DownloadState.Done
                        partFile.exists() && partFile.length() > 0 -> {
                            val info = getModelInfo(key)
                            val totalBytes = info?.files?.sumOf { it.fileSize } ?: 0L
                            val downloadedBytes = info?.files?.sumOf { fileInfo ->
                                val f = File(targetFile.parentFile, fileInfo.fileName)
                                if (f.exists() && f.length() > 0) f.length() else 0L
                            } ?: partFile.length()
                            DownloadState.Partial(downloadedBytes, totalBytes)
                        }
                        else -> DownloadState.Idle
                    }
                    stateMap[key] = state
                }
                emitSnapshot()
            }
        }
    }

    suspend fun markRunning(
        modelKey: ModelKey,
        currentFileIndex: Int = 0,
        currentFileCount: Int = 1,
        currentFileName: String = ""
    ) = updateState(modelKey) {
        val totalBytes = getModelInfo(modelKey)?.files?.sumOf { it.fileSize } ?: 0L
        DownloadState.Running(
            bytesDownloaded = 0,
            totalBytes = totalBytes,
            speedBytesPerSec = 0,
            currentFileIndex = currentFileIndex,
            currentFileCount = currentFileCount,
            currentFileName = currentFileName,
            currentFileProgress = 0
        )
    }

    suspend fun markPartial(modelKey: ModelKey) = updateState(modelKey) {
        val totalBytes = getModelInfo(modelKey)?.files?.sumOf { it.fileSize } ?: 0L
        val downloadedBytes = getModelInfo(modelKey)?.files?.sumOf { fileInfo ->
            val f = File(targetFileFor(modelKey).parentFile, fileInfo.fileName)
            if (f.exists() && f.length() > 0) f.length() else 0L
        } ?: 0L
        DownloadState.Partial(downloadedBytes, totalBytes)
    }

    suspend fun markPaused(modelKey: ModelKey) = updateState(modelKey) {
        val totalBytes = getModelInfo(modelKey)?.files?.sumOf { it.fileSize } ?: 0L
        val downloadedBytes = getModelInfo(modelKey)?.files?.sumOf { fileInfo ->
            val f = File(targetFileFor(modelKey).parentFile, fileInfo.fileName)
            if (f.exists() && f.length() > 0) f.length() else 0L
        } ?: 0L
        DownloadState.Paused(downloadedBytes, totalBytes)
    }

    suspend fun markDone(modelKey: ModelKey) = updateState(modelKey) { DownloadState.Done }

    suspend fun updateProgress(
        modelKey: ModelKey,
        bytes: Long,
        total: Long,
        speed: Long,
        currentFileIndex: Int,
        currentFileCount: Int,
        currentFileName: String,
        currentFileProgress: Int
    ) = updateState(modelKey) {
        DownloadState.Running(
            bytesDownloaded = bytes,
            totalBytes = total,
            speedBytesPerSec = speed,
            currentFileIndex = currentFileIndex,
            currentFileCount = currentFileCount,
            currentFileName = currentFileName,
            currentFileProgress = currentFileProgress
        )
    }

    suspend fun cancelDownload(modelKey: ModelKey) = withContext(Dispatchers.IO) {
        stateMutex.withLock {
            val info = getModelInfo(modelKey) ?: return@withContext
            for (fileInfo in info.files) {
                val partFile = File(targetFileFor(modelKey).parentFile, fileInfo.fileName + ".part")
                if (partFile.exists()) {
                    partFile.delete()
                    LogCollector.d(TAG, "Cancelled: deleted .part for ${fileInfo.fileName}")
                }
            }
            stateMap[modelKey] = DownloadState.Idle
            emitSnapshot()
        }
    }

    suspend fun deleteDownload(modelKey: ModelKey) = withContext(Dispatchers.IO) {
        stateMutex.withLock {
            val info = getModelInfo(modelKey) ?: return@withContext
            for (fileInfo in info.files) {
                val target = File(targetFileFor(modelKey).parentFile, fileInfo.fileName)
                if (target.exists()) target.delete()
                val part = File(targetFileFor(modelKey).parentFile, fileInfo.fileName + ".part")
                if (part.exists()) part.delete()
            }
            stateMap[modelKey] = DownloadState.Idle
            emitSnapshot()
        }
    }

    /** Test-only: clear all state */
    fun clearAll() {
        stateMap.clear()
    }

    /**
     * Load model metadata from the bundled assets JSON.
     * Must be called after getInstance() and before any download.
     */
    suspend fun loadModelList() {
        val json = context.assets.open("models/downloadinfo.json")
            .bufferedReader().use { it.readText() }
        val root = org.json.JSONObject(json)
        val modelsArray = root.getJSONArray("models")
        val models = mutableListOf<ModelInfo>()
        for (i in 0 until modelsArray.length()) {
            val modelObj = modelsArray.getJSONObject(i)
            val keyStr = modelObj.optString("model_key", "")
            val modelKey = try {
                ModelKey.valueOf(keyStr)
            } catch (e: IllegalArgumentException) {
                LogCollector.w(TAG, "Unknown model_key: $keyStr, skip")
                continue
            }
            val browserUrl = modelObj.optString("browser_url", "")
            val filesArray = modelObj.getJSONArray("files")
            val files = mutableListOf<FileInfo>()
            for (j in 0 until filesArray.length()) {
                val fileObj = filesArray.getJSONObject(j)
                val fileName = fileObj.optString("file_name", "")
                if (fileName.isEmpty()) continue
                files.add(FileInfo(
                    fileName = fileName,
                    downloadUrl = fileObj.optString("download_url", ""),
                    fileSize = fileObj.optLong("file_size", 0L),
                    checksum = fileObj.optString("checksum", "")
                ))
            }
            models.add(ModelInfo(modelKey, browserUrl, files))
        }
        modelListCached.value = models
        LogCollector.d(TAG, "loadModelList: loaded ${models.size} models")
    }

    private suspend fun updateState(modelKey: ModelKey, transform: () -> DownloadState) {
        stateMutex.withLock {
            stateMap[modelKey] = transform()
            emitSnapshot()
        }
    }

    private fun emitSnapshot() {
        _snapshot.value = DownloadSnapshot(
            stateMap.toMap(),
            stateMap.filter { it.value is DownloadState.Running }.keys.toSet()
        )
    }

    private val queuedKeys = ConcurrentHashMap.newKeySet<ModelKey>()

    /**
     * 如果该 modelKey 当前不是 Running，将其加入待处理队列。
     * 返回 true 表示需要调用方启动下载；返回 false 表示已在下载中（已自动入队，当前完成后会再次启动）。
     */
    suspend fun enqueueDownload(modelKey: ModelKey): Boolean {
        stateMutex.withLock {
            val current = stateMap[modelKey]
            return if (current is DownloadState.Running) {
                queuedKeys.add(modelKey)
                false
            } else {
                true
            }
        }
    }

    fun dequeueNext(): ModelKey? {
        val first = queuedKeys.firstOrNull() ?: return null
        queuedKeys.remove(first)
        return first
    }

    /**
     * Single-file models return the expected target file path.
     * Multi-file models return a sentinel path (Use fileName from FileInfo instead).
     */
    private fun targetFileFor(modelKey: ModelKey): File {
        val baseDir = baseDirFor(modelKey)
        if (!baseDir.exists()) baseDir.mkdirs()
        val firstName = getModelInfo(modelKey)?.files?.firstOrNull()?.fileName ?: "model.onnx"
        return File(baseDir, firstName)
    }

    private fun partFileFor(modelKey: ModelKey): File {
        val t = targetFileFor(modelKey)
        return File(t.parentFile, t.name + ".part")
    }

    private fun baseDirFor(modelKey: ModelKey): File = when (modelKey) {
        ModelKey.NLLB_GROUP -> File(context.getExternalFilesDir(null), "models")
        ModelKey.MANGA_OCR_GROUP -> File(context.getExternalFilesDir(null), "manga_ocr_download")
        ModelKey.RT_DETR_V2 -> File(context.getExternalFilesDir(null), "rt_detr")
        ModelKey.PP_OCR_V6_MEDIUM_DET, ModelKey.PP_OCR_V6_MEDIUM_REC ->
            File(context.getExternalFilesDir(null), "ppocrv6")
        ModelKey.PP_OCR_V5_DET, ModelKey.PP_OCR_V5_REC_ZH,
        ModelKey.PP_OCR_V5_REC_EN, ModelKey.PP_OCR_V5_REC_KO,
        ModelKey.PP_OCR_V5_REC_RU -> File(context.getExternalFilesDir(null), "ppocrv5")
    }

    companion object {
        private const val TAG = "ModelDownloadRepo"

        @Volatile
        private var INSTANCE: ModelDownloadRepository? = null

        fun getInstance(context: Context): ModelDownloadRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: ModelDownloadRepository(context.applicationContext).also { INSTANCE = it }
            }
    }
}
