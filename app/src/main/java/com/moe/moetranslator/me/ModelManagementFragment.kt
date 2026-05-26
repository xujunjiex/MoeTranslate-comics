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
import com.moe.moetranslator.manga.CtcOcrModelManager
import com.moe.moetranslator.manga.MangaOcrDownloadManager
import com.moe.moetranslator.manga.ModelDownloadManager
import com.moe.moetranslator.utils.LogCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ModelManagementFragment : Fragment() {

    private val TAG = "ModelManagementFragment"
    private lateinit var rootView: View
    private val handler = Handler(Looper.getMainLooper())
    private var downloadJob: Job? = null
    private var isCancelled = false

    // 缓存 View 引用，避免在回调中频繁 findViewById
    private var progressBar: ProgressBar? = null
    private var speedText: TextView? = null
    private var sizeText: TextView? = null

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
        setupViews()
        updateCtcStatus()
        updateMangaOcrStatus()
    }

    private fun setupViews() {
        progressBar = rootView.findViewById(R.id.ctc_download_progress)
        speedText = rootView.findViewById(R.id.ctc_speed_text)
        sizeText = rootView.findViewById(R.id.ctc_size_text)

        val ctcDownloadBtn = rootView.findViewById<Button>(R.id.ctc_download_button)
        val ctcDeleteBtn = rootView.findViewById<Button>(R.id.ctc_delete_button)
        val ctcCancelBtn = rootView.findViewById<Button>(R.id.ctc_cancel_button)

        // 下载按钮
        ctcDownloadBtn.setOnClickListener {
            startDownload()
        }

        // 删除按钮
        ctcDeleteBtn.setOnClickListener {
            showDeleteConfirmDialog()
        }

        // 取消按钮
        ctcCancelBtn.setOnClickListener {
            cancelDownload()
        }
    }

    private fun cancelDownload() {
        isCancelled = true
        downloadJob?.cancel()
        downloadJob = null
        LogCollector.d(TAG, "下载已取消")
        updateCtcStatus()
    }

    private fun updateCtcStatus() {
        val ctcStatus = rootView.findViewById<TextView>(R.id.ctc_status_text)
        val ctcDownloadBtn = rootView.findViewById<Button>(R.id.ctc_download_button)
        val ctcDeleteBtn = rootView.findViewById<Button>(R.id.ctc_delete_button)
        val ctcCancelBtn = rootView.findViewById<Button>(R.id.ctc_cancel_button)

        val isInFilesDir = CtcOcrModelManager.isModelInFilesDir(requireContext())
        val isInAssets = CtcOcrModelManager.isModelInAssets(requireContext())

        when {
            downloadJob != null && !isCancelled -> {
                // 下载中
                ctcStatus.text = getString(R.string.model_downloading)
                sizeText?.text = ""
                ctcDownloadBtn.visibility = View.GONE
                ctcDeleteBtn.visibility = View.GONE
                ctcCancelBtn.visibility = View.VISIBLE
                progressBar?.visibility = View.VISIBLE
                speedText?.visibility = View.VISIBLE
            }
            isInFilesDir -> {
                // 用户下载的模型
                ctcStatus.text = getString(R.string.model_downloaded)
                sizeText?.text = CtcOcrModelManager.getModelSizeString(requireContext())
                ctcDownloadBtn.visibility = View.GONE
                ctcDeleteBtn.visibility = View.VISIBLE
                ctcCancelBtn.visibility = View.GONE
                progressBar?.visibility = View.GONE
                progressBar?.progress = 0
                speedText?.visibility = View.GONE
            }
            isInAssets -> {
                // 内置模型（assets），不能删除
                ctcStatus.text = getString(R.string.model_built_in)
                sizeText?.text = "169 MB"
                ctcDownloadBtn.visibility = View.GONE
                ctcDeleteBtn.visibility = View.GONE
                ctcCancelBtn.visibility = View.GONE
                progressBar?.visibility = View.GONE
                progressBar?.progress = 0
                speedText?.visibility = View.GONE
            }
            else -> {
                // 未下载
                ctcStatus.text = getString(R.string.model_not_downloaded)
                sizeText?.text = "144 MB"
                ctcDownloadBtn.visibility = View.VISIBLE
                ctcDeleteBtn.visibility = View.GONE
                ctcCancelBtn.visibility = View.GONE
                progressBar?.visibility = View.GONE
                progressBar?.progress = 0
                speedText?.visibility = View.GONE
            }
        }
    }

    private fun startDownload() {
        isCancelled = false

        // 先清理可能残留的下载文件
        val modelDir = CtcOcrModelManager.getModelDir(requireContext())
        val zipFile = File(modelDir, "ocr-ctc.zip")
        if (zipFile.exists()) {
            zipFile.delete()
        }

        downloadJob = lifecycleScope.launch(Dispatchers.IO) {
            LogCollector.d(TAG, "开始下载模型...")
            try {
                val result = CtcOcrModelManager.downloadModel(
                    requireContext(),
                    object : ModelDownloadManager.ProgressCallback {
                        override fun onProgress(bytesRead: Long, totalBytes: Long, speed: Float) {
                            if (isCancelled || !isAdded) return
                            handler.post {
                                if (isCancelled || !isAdded) return@post
                                val progress = if (totalBytes > 0) {
                                    (bytesRead * 100 / totalBytes).toInt()
                                } else 0
                                progressBar?.progress = progress

                                val mbRead = bytesRead / (1024 * 1024)
                                val mbTotal = if (totalBytes > 0) totalBytes / (1024 * 1024) else 0
                                sizeText?.text = "${mbRead}/${mbTotal} MB"

                                speedText?.text = getString(R.string.model_download_speed, speed)
                            }
                        }
                    }
                )

                withContext(Dispatchers.Main) {
                    downloadJob = null
                    if (isCancelled) {
                        LogCollector.d(TAG, "下载已取消，不更新UI")
                        return@withContext
                    }
                    if (result.isSuccess) {
                        LogCollector.d(TAG, "下载成功")
                        Toast.makeText(requireContext(), R.string.model_download_success, Toast.LENGTH_SHORT).show()
                    } else {
                        LogCollector.e(TAG, "下载失败", result.exceptionOrNull())
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.model_download_failed, result.exceptionOrNull()?.message),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    updateCtcStatus()
                }
            } catch (e: Exception) {
                LogCollector.e(TAG, "下载异常", e)
                withContext(Dispatchers.Main) {
                    downloadJob = null
                    if (isCancelled) {
                        LogCollector.d(TAG, "下载已取消，不显示错误")
                        return@withContext
                    }
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.model_download_failed, e.message),
                        Toast.LENGTH_LONG
                    ).show()
                    updateCtcStatus()
                }
            }
        }
        updateCtcStatus()
    }

    private fun showDeleteConfirmDialog() {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.model_delete)
            .setMessage(getString(R.string.model_delete_confirm, "48px CTC"))
            .setPositiveButton(R.string.confirm) { _, _ ->
                deleteModel()
            }
            .setNegativeButton(R.string.user_cancel, null)
            .show()
    }

    private fun deleteModel() {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = CtcOcrModelManager.deleteModel(requireContext())
            withContext(Dispatchers.Main) {
                if (result.isSuccess) {
                    Toast.makeText(requireContext(), R.string.model_delete_success, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), R.string.model_delete_failed, Toast.LENGTH_LONG).show()
                }
                updateCtcStatus()
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
                                val sizeText = rowView?.findViewById<TextView>(R.id.version_size)
                                sizeText?.text = "${mbRead}/${mbTotal} MB"
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
        // 清理 View 引用和 pending callbacks，防止 View 销毁后仍被访问
        progressBar = null
        speedText = null
        sizeText = null
        handler.removeCallbacksAndMessages(null)
        // 销毁时取消下载，但不设置 isCancelled 以便下次能正常下载
        downloadJob?.cancel()
        downloadJob = null
        mangaOcrDownloadJob?.cancel()
        mangaOcrDownloadJob = null
        mangaOcrCurrentDownloadingVersion = null
    }
}