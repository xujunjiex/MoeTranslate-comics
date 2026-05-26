package com.moe.moetranslator.me

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.moe.moetranslator.R
import com.moe.moetranslator.manga.MangaOcrDownloadManager
import com.moe.moetranslator.manga.ModelDownloadManager
import com.moe.moetranslator.utils.LogCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ModelManagementFragment : Fragment() {

    private val TAG = "ModelManagementFragment"
    private lateinit var rootView: View
    private val handler = Handler(Looper.getMainLooper())

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
        updateMangaOcrStatus()
    }

    // ========== manga-ocr 下载相关（版本列表）==========

    private fun updateMangaOcrStatus() {
        for (version in MangaOcrDownloadManager.ModelVersion.entries) {
            updateMangaOcrVersionStatus(version)
        }
    }

    private fun updateMangaOcrVersionStatus(version: MangaOcrDownloadManager.ModelVersion) {
        val rowView = when (version) {
            MangaOcrDownloadManager.ModelVersion.FULL -> rootView.findViewById<View>(R.id.manga_ocr_full_row)
            MangaOcrDownloadManager.ModelVersion.FP16 -> rootView.findViewById<View>(R.id.manga_ocr_fp16_row)
            MangaOcrDownloadManager.ModelVersion.QUANTIZED -> rootView.findViewById<View>(R.id.manga_ocr_quantized_row)
        }

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
            MangaOcrDownloadManager.ModelVersion.FP16 -> rootView.findViewById(R.id.manga_ocr_fp16_row)
            MangaOcrDownloadManager.ModelVersion.QUANTIZED -> rootView.findViewById(R.id.manga_ocr_quantized_row)
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
        mangaOcrDownloadJob?.cancel()
        mangaOcrDownloadJob = null
        mangaOcrCurrentDownloadingVersion = null
    }
}