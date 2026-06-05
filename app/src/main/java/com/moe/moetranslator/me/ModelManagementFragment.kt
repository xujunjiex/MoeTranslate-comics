package com.moe.moetranslator.me

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.moe.moetranslator.R
import com.moe.moetranslator.manga.CTDModelManager
import com.moe.moetranslator.manga.MangaOcrDownloadManager
import com.moe.moetranslator.manga.ModelDownloadManager
import com.moe.moetranslator.manga.RTDetrModelManager
import com.moe.moetranslator.utils.LogCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ModelManagementFragment : Fragment() {

    private val TAG = "ModelManagementFragment"
    private lateinit var rootView: View
    private val handler = Handler(Looper.getMainLooper())

    // RT-DETR-V2 下载相关
    private var rtdetrDownloadJob: Job? = null
    private var rtdetrIsCancelled = false

    // CTD 下载相关
    private var ctdDownloadJob: Job? = null
    private var ctdIsCancelled = false

    // manga-ocr 下载相关
    private var mangaOcrDownloadJob: Job? = null
    private var mangaOcrIsCancelled = false
    private var mangaOcrCurrentDownloadingVersion: MangaOcrDownloadManager.ModelVersion? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        rootView = inflater.inflate(R.layout.fragment_model_management, container, false)
        return rootView
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        updateRTDetrStatus()
        updateCtdStatus()
        updateMangaOcrStatus()
    }

    // ========== RT-DETR-V2 下载相关 ==========

    private fun updateRTDetrStatus() {
        val statusText = rootView.findViewById<TextView>(R.id.rtdetr_status)
        val actionBtn = rootView.findViewById<Button>(R.id.rtdetr_action_button)
        val progressBar = rootView.findViewById<ProgressBar>(R.id.rtdetr_progress)

        val isDownloaded = RTDetrModelManager.isModelInFilesDir(requireContext())
        val isDownloading = rtdetrDownloadJob != null

        when {
            isDownloading -> {
                statusText.text = getString(R.string.model_downloading)
                actionBtn.text = getString(R.string.user_cancel)
                actionBtn.setOnClickListener { cancelRTDetrDownload() }
                progressBar.visibility = View.VISIBLE
            }
            isDownloaded -> {
                val size = RTDetrModelManager.getModelSizeString(requireContext())
                statusText.text = "${getString(R.string.model_downloaded)} ($size)"
                actionBtn.text = getString(R.string.model_delete)
                actionBtn.setOnClickListener { showRTDetrDeleteConfirmDialog() }
                progressBar.visibility = View.GONE
            }
            else -> {
                statusText.text = getString(R.string.model_not_downloaded)
                actionBtn.text = getString(R.string.model_download)
                actionBtn.setOnClickListener { startRTDetrDownload() }
                progressBar.visibility = View.GONE
            }
        }
    }

    private fun cancelRTDetrDownload() {
        rtdetrIsCancelled = true
        rtdetrDownloadJob?.cancel()
        rtdetrDownloadJob = null
        LogCollector.d(TAG, "RT-DETR-V2 下载已取消")
        updateRTDetrStatus()
    }

    private fun startRTDetrDownload() {
        rtdetrIsCancelled = false
        rtdetrDownloadJob = lifecycleScope.launch(Dispatchers.IO) {
            LogCollector.d(TAG, "开始下载 RT-DETR-V2 模型...")
            try {
                val result = RTDetrModelManager.downloadModel(
                    requireContext(),
                    object : ModelDownloadManager.ProgressCallback {
                        override fun onProgress(bytesRead: Long, totalBytes: Long, speed: Float) {
                            if (rtdetrIsCancelled || !isAdded) return
                            handler.post {
                                if (rtdetrIsCancelled || !isAdded) return@post
                                val progress = if (totalBytes > 0) (bytesRead * 100 / totalBytes).toInt() else 0
                                val progressBar = rootView.findViewById<ProgressBar>(R.id.rtdetr_progress)
                                progressBar.progress = progress
                                val statusText = rootView.findViewById<TextView>(R.id.rtdetr_status)
                                val mbRead = bytesRead / (1024 * 1024)
                                val mbTotal = if (totalBytes > 0) totalBytes / (1024 * 1024) else 0
                                val speedStr = if (speed > 0) String.format(" (%.1f MB/s)", speed) else ""
                                statusText.text = "${getString(R.string.model_downloading)} $progress%  ${mbRead}/${mbTotal} MB$speedStr"
                            }
                        }
                    }
                )

                withContext(Dispatchers.Main) {
                    rtdetrDownloadJob = null
                    if (rtdetrIsCancelled) return@withContext
                    if (result.isSuccess) {
                        Toast.makeText(requireContext(), R.string.model_download_success, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(requireContext(), getString(R.string.model_download_failed, result.exceptionOrNull()?.message), Toast.LENGTH_LONG).show()
                    }
                    updateRTDetrStatus()
                }
            } catch (e: Exception) {
                LogCollector.e(TAG, "RT-DETR-V2 下载异常", e)
                withContext(Dispatchers.Main) {
                    rtdetrDownloadJob = null
                    if (rtdetrIsCancelled) return@withContext
                    Toast.makeText(requireContext(), getString(R.string.model_download_failed, e.message), Toast.LENGTH_LONG).show()
                    updateRTDetrStatus()
                }
            }
        }
        updateRTDetrStatus()
    }

    private fun showRTDetrDeleteConfirmDialog() {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.model_delete)
            .setMessage(getString(R.string.model_delete_confirm, "RT-DETR-V2"))
            .setPositiveButton(R.string.confirm) { _, _ -> deleteRTDetrModel() }
            .setNegativeButton(R.string.user_cancel, null)
            .show()
    }

    private fun deleteRTDetrModel() {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = RTDetrModelManager.deleteModel(requireContext())
            withContext(Dispatchers.Main) {
                if (result.isSuccess) {
                    Toast.makeText(requireContext(), R.string.model_delete_success, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), R.string.model_delete_failed, Toast.LENGTH_LONG).show()
                }
                updateRTDetrStatus()
            }
        }
    }

    // ========== CTD 下载相关 ==========

    private fun updateCtdStatus() {
        val statusText = rootView.findViewById<TextView>(R.id.ctd_status)
        val actionBtn = rootView.findViewById<Button>(R.id.ctd_action_button)
        val progressBar = rootView.findViewById<ProgressBar>(R.id.ctd_progress)

        val isDownloaded = CTDModelManager.isModelAvailable(requireContext())
        val isDownloading = ctdDownloadJob != null

        when {
            isDownloading -> {
                statusText.text = getString(R.string.model_downloading)
                actionBtn.text = getString(R.string.user_cancel)
                actionBtn.setOnClickListener { cancelCtdDownload() }
                progressBar.visibility = View.VISIBLE
            }
            isDownloaded -> {
                val size = CTDModelManager.getModelSizeString(requireContext())
                statusText.text = "${getString(R.string.model_downloaded)} ($size)"
                actionBtn.text = getString(R.string.model_delete)
                actionBtn.setOnClickListener { showCtdDeleteConfirmDialog() }
                progressBar.visibility = View.GONE
            }
            else -> {
                statusText.text = getString(R.string.model_not_downloaded)
                actionBtn.text = getString(R.string.model_download)
                actionBtn.setOnClickListener { startCtdDownload() }
                progressBar.visibility = View.GONE
            }
        }
    }

    private fun cancelCtdDownload() {
        ctdIsCancelled = true
        ctdDownloadJob?.cancel()
        ctdDownloadJob = null
        LogCollector.d(TAG, "CTD 下载已取消")
        updateCtdStatus()
    }

    private fun startCtdDownload() {
        ctdIsCancelled = false
        ctdDownloadJob = lifecycleScope.launch(Dispatchers.IO) {
            LogCollector.d(TAG, "开始下载 CTD 模型...")
            try {
                val result = CTDModelManager.downloadModel(
                    requireContext(),
                    object : ModelDownloadManager.ProgressCallback {
                        override fun onProgress(bytesRead: Long, totalBytes: Long, speed: Float) {
                            if (ctdIsCancelled || !isAdded) return
                            handler.post {
                                if (ctdIsCancelled || !isAdded) return@post
                                val progress = if (totalBytes > 0) (bytesRead * 100 / totalBytes).toInt() else 0
                                val progressBar = rootView.findViewById<ProgressBar>(R.id.ctd_progress)
                                progressBar.progress = progress
                                val statusText = rootView.findViewById<TextView>(R.id.ctd_status)
                                val mbRead = bytesRead / (1024 * 1024)
                                val mbTotal = if (totalBytes > 0) totalBytes / (1024 * 1024) else 0
                                val speedStr = if (speed > 0) String.format(" (%.1f MB/s)", speed) else ""
                                statusText.text = "${getString(R.string.model_downloading)} $progress%  ${mbRead}/${mbTotal} MB$speedStr"
                            }
                        }
                    }
                )

                withContext(Dispatchers.Main) {
                    ctdDownloadJob = null
                    if (ctdIsCancelled) return@withContext
                    if (result.isSuccess) {
                        Toast.makeText(requireContext(), R.string.model_download_success, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(requireContext(), getString(R.string.model_download_failed, result.exceptionOrNull()?.message), Toast.LENGTH_LONG).show()
                    }
                    updateCtdStatus()
                }
            } catch (e: Exception) {
                LogCollector.e(TAG, "CTD 下载异常", e)
                withContext(Dispatchers.Main) {
                    ctdDownloadJob = null
                    if (ctdIsCancelled) return@withContext
                    Toast.makeText(requireContext(), getString(R.string.model_download_failed, e.message), Toast.LENGTH_LONG).show()
                    updateCtdStatus()
                }
            }
        }
        updateCtdStatus()
    }

    private fun showCtdDeleteConfirmDialog() {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.model_delete)
            .setMessage(getString(R.string.model_delete_confirm, "CTD"))
            .setPositiveButton(R.string.confirm) { _, _ -> deleteCtdModel() }
            .setNegativeButton(R.string.user_cancel, null)
            .show()
    }

    private fun deleteCtdModel() {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = CTDModelManager.deleteModel(requireContext())
            withContext(Dispatchers.Main) {
                if (result.isSuccess) {
                    Toast.makeText(requireContext(), R.string.model_delete_success, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), R.string.model_delete_failed, Toast.LENGTH_LONG).show()
                }
                updateCtdStatus()
            }
        }
    }

    // ========== manga-ocr 下载相关（版本列表）==========

    private fun updateMangaOcrStatus() {
        for (version in MangaOcrDownloadManager.ModelVersion.entries) {
            updateMangaOcrVersionStatus(version)
        }
    }

    private fun updateMangaOcrVersionStatus(version: MangaOcrDownloadManager.ModelVersion) {
        val rowView = getVersionRowView(version) ?: return

        val nameText = rowView.findViewById<TextView>(R.id.version_name)
        val sizeText = rowView.findViewById<TextView>(R.id.version_size)
        val statusText = rowView.findViewById<TextView>(R.id.version_status)
        val actionBtn = rowView.findViewById<Button>(R.id.version_action_button)

        // version.description = "完整版 (343MB+117MB)"
        val descParts = version.description.split(" (")
        nameText.text = descParts[0]
        sizeText.text = if (descParts.size > 1) descParts[1].removeSuffix(")") else ""

        val isDownloaded = MangaOcrDownloadManager.isVersionDownloaded(requireContext(), version)
        val isActive = MangaOcrDownloadManager.getActiveVersion(requireContext()) == version
        val isDownloading = mangaOcrCurrentDownloadingVersion == version && mangaOcrDownloadJob != null

        when {
            isDownloading -> {
                statusText.text = getString(R.string.model_downloading)
                actionBtn.text = getString(R.string.user_cancel)
                actionBtn.setOnClickListener { cancelMangaOcrDownload() }
            }
            isActive -> {
                statusText.text = "当前使用"
                actionBtn.text = getString(R.string.model_delete)
                actionBtn.setOnClickListener { showMangaOcrDeleteConfirmDialog(version) }
            }
            isDownloaded -> {
                statusText.text = "已下载"
                actionBtn.text = getString(R.string.model_delete)
                actionBtn.setOnClickListener { showMangaOcrDeleteConfirmDialog(version) }
            }
            else -> {
                statusText.text = ""
                actionBtn.text = getString(R.string.model_download)
                actionBtn.setOnClickListener { startMangaOcrDownload(version) }
            }
        }

        // 点击行设置当前使用版本
        rowView.setOnClickListener {
            if (MangaOcrDownloadManager.isVersionDownloaded(requireContext(), version)) {
                MangaOcrDownloadManager.setActiveVersion(requireContext(), version)
                updateMangaOcrStatus()
                Toast.makeText(requireContext(), "已选择 ${version.description.split(" (")[0]}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun cancelMangaOcrDownload() {
        mangaOcrIsCancelled = true
        mangaOcrDownloadJob?.cancel()
        mangaOcrDownloadJob = null
        mangaOcrCurrentDownloadingVersion = null
        LogCollector.d(TAG, "manga-ocr 下载已取消")
        updateMangaOcrStatus()
    }

    private fun startMangaOcrDownload(version: MangaOcrDownloadManager.ModelVersion) {
        mangaOcrIsCancelled = false
        mangaOcrCurrentDownloadingVersion = version

        mangaOcrDownloadJob = lifecycleScope.launch(Dispatchers.IO) {
            LogCollector.d(TAG, "开始下载 manga-ocr ${version.name} 模型...")

            try {
                val result = MangaOcrDownloadManager.downloadModel(
                    requireContext(),
                    version,
                    object : ModelDownloadManager.ProgressCallback {
                        override fun onProgress(bytesRead: Long, totalBytes: Long, speed: Float) {
                            if (mangaOcrIsCancelled || !isAdded) return
                            handler.post {
                                if (mangaOcrIsCancelled || !isAdded) return@post
                                val progress = if (totalBytes > 0) {
                                    (bytesRead * 100 / totalBytes).toInt()
                                } else 0
                                val rowView = getVersionRowView(version)
                                val statusText = rowView?.findViewById<TextView>(R.id.version_status)
                                statusText?.text = getString(R.string.model_downloading) + " $progress%"

                                val mbRead = bytesRead / (1024 * 1024)
                                val mbTotal = if (totalBytes > 0) totalBytes / (1024 * 1024) else 0
                                val speedStr = if (speed > 0) String.format(" (%.1f MB/s)", speed) else ""
                                val sizeText = rowView?.findViewById<TextView>(R.id.version_size)
                                sizeText?.text = "${mbRead}/${mbTotal} MB$speedStr"
                            }
                        }
                    }
                )

                withContext(Dispatchers.Main) {
                    mangaOcrDownloadJob = null
                    mangaOcrCurrentDownloadingVersion = null
                    if (mangaOcrIsCancelled) {
                        LogCollector.d(TAG, "manga-ocr 下载已取消，不更新UI")
                        return@withContext
                    }
                    if (result.isSuccess) {
                        LogCollector.d(TAG, "manga-ocr 下载成功")
                        Toast.makeText(requireContext(), R.string.model_download_success, Toast.LENGTH_SHORT).show()
                    } else {
                        LogCollector.e(TAG, "manga-ocr 下载失败", result.exceptionOrNull())
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.model_download_failed, result.exceptionOrNull()?.message),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    updateMangaOcrStatus()
                }
            } catch (e: Exception) {
                LogCollector.e(TAG, "manga-ocr 下载异常", e)
                withContext(Dispatchers.Main) {
                    mangaOcrDownloadJob = null
                    mangaOcrCurrentDownloadingVersion = null
                    if (mangaOcrIsCancelled) {
                        LogCollector.d(TAG, "manga-ocr 下载已取消，不显示错误")
                        return@withContext
                    }
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.model_download_failed, e.message),
                        Toast.LENGTH_LONG
                    ).show()
                    updateMangaOcrStatus()
                }
            }
        }
        updateMangaOcrStatus()
    }

    private fun getVersionRowView(version: MangaOcrDownloadManager.ModelVersion): View? {
        return when (version) {
            MangaOcrDownloadManager.ModelVersion.FULL -> rootView.findViewById(R.id.manga_ocr_full_row)
            MangaOcrDownloadManager.ModelVersion.V2025 -> rootView.findViewById(R.id.manga_ocr_v2025_row)
        }
    }

    private fun showMangaOcrDeleteConfirmDialog(version: MangaOcrDownloadManager.ModelVersion) {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.model_delete)
            .setMessage(getString(R.string.model_delete_confirm, version.description.split(" (")[0]))
            .setPositiveButton(R.string.confirm) { _, _ ->
                deleteMangaOcrModel(version)
            }
            .setNegativeButton(R.string.user_cancel, null)
            .show()
    }

    private fun deleteMangaOcrModel(version: MangaOcrDownloadManager.ModelVersion) {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = MangaOcrDownloadManager.deleteVersion(requireContext(), version)
            withContext(Dispatchers.Main) {
                if (result.isSuccess) {
                    Toast.makeText(requireContext(), R.string.model_delete_success, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), R.string.model_delete_failed, Toast.LENGTH_LONG).show()
                }
                updateMangaOcrStatus()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacksAndMessages(null)
        rtdetrDownloadJob?.cancel()
        rtdetrDownloadJob = null
        ctdDownloadJob?.cancel()
        ctdDownloadJob = null
        mangaOcrDownloadJob?.cancel()
        mangaOcrDownloadJob = null
        mangaOcrCurrentDownloadingVersion = null
    }
}
