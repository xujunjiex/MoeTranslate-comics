package com.moe.moetranslator.me

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Spinner
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
    private var mangaOcrProgressBar: ProgressBar? = null
    private var mangaOcrSpeedText: TextView? = null
    private var mangaOcrSizeText: TextView? = null
    private var mangaOcrSpinner: Spinner? = null
    private var mangaOcrDownloadJob: Job? = null
    private var mangaOcrIsCancelled = false

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

        // manga-ocr 下载相关
        mangaOcrProgressBar = rootView.findViewById(R.id.manga_ocr_download_progress)
        mangaOcrSpeedText = rootView.findViewById(R.id.manga_ocr_speed_text)
        mangaOcrSizeText = rootView.findViewById(R.id.manga_ocr_size_text)
        mangaOcrSpinner = rootView.findViewById(R.id.manga_ocr_version_spinner)

        val mangaOcrDownloadBtn = rootView.findViewById<Button>(R.id.manga_ocr_download_button)
        val mangaOcrDeleteBtn = rootView.findViewById<Button>(R.id.manga_ocr_delete_button)
        val mangaOcrCancelBtn = rootView.findViewById<Button>(R.id.manga_ocr_cancel_button)

        mangaOcrDownloadBtn.setOnClickListener {
            startMangaOcrDownload()
        }

        mangaOcrDeleteBtn.setOnClickListener {
            showMangaOcrDeleteConfirmDialog()
        }

        mangaOcrCancelBtn.setOnClickListener {
            cancelMangaOcrDownload()
        }

        // 设置 Spinner 适配器
        val versionAdapter = android.widget.ArrayAdapter.createFromResource(
            requireContext(),
            R.array.manga_ocr_version_entries,
            android.R.layout.simple_spinner_item
        )
        versionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        mangaOcrSpinner?.adapter = versionAdapter
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

    // ========== manga-ocr 下载相关 ==========

    private fun updateMangaOcrStatus() {
        val mangaOcrStatus = rootView.findViewById<TextView>(R.id.manga_ocr_status_text)
        val mangaOcrDownloadBtn = rootView.findViewById<Button>(R.id.manga_ocr_download_button)
        val mangaOcrDeleteBtn = rootView.findViewById<Button>(R.id.manga_ocr_delete_button)
        val mangaOcrCancelBtn = rootView.findViewById<Button>(R.id.manga_ocr_cancel_button)

        val isDownloaded = MangaOcrDownloadManager.isModelDownloaded(requireContext())

        when {
            mangaOcrDownloadJob != null && !mangaOcrIsCancelled -> {
                // 下载中
                mangaOcrStatus.text = getString(R.string.model_downloading)
                mangaOcrSizeText?.text = ""
                mangaOcrDownloadBtn.visibility = View.GONE
                mangaOcrDeleteBtn.visibility = View.GONE
                mangaOcrCancelBtn.visibility = View.VISIBLE
                mangaOcrProgressBar?.visibility = View.VISIBLE
                mangaOcrSpeedText?.visibility = View.VISIBLE
                mangaOcrSpinner?.visibility = View.GONE
            }
            isDownloaded -> {
                // 已下载
                val version = MangaOcrDownloadManager.getDownloadedVersion(requireContext())
                val versionDesc = version?.description ?: "已下载"
                mangaOcrStatus.text = getString(R.string.model_downloaded)
                mangaOcrSizeText?.text = "$versionDesc - ${MangaOcrDownloadManager.getModelSizeString(requireContext())}"
                mangaOcrDownloadBtn.visibility = View.GONE
                mangaOcrDeleteBtn.visibility = View.VISIBLE
                mangaOcrCancelBtn.visibility = View.GONE
                mangaOcrProgressBar?.visibility = View.GONE
                mangaOcrProgressBar?.progress = 0
                mangaOcrSpeedText?.visibility = View.GONE
                mangaOcrSpinner?.visibility = View.GONE
            }
            else -> {
                // 未下载
                mangaOcrStatus.text = getString(R.string.model_not_downloaded)
                mangaOcrSizeText?.text = "460 MB"
                mangaOcrDownloadBtn.visibility = View.VISIBLE
                mangaOcrDeleteBtn.visibility = View.GONE
                mangaOcrCancelBtn.visibility = View.GONE
                mangaOcrProgressBar?.visibility = View.GONE
                mangaOcrProgressBar?.progress = 0
                mangaOcrSpeedText?.visibility = View.GONE
                mangaOcrSpinner?.visibility = View.VISIBLE
            }
        }
    }

    private fun cancelMangaOcrDownload() {
        mangaOcrIsCancelled = true
        mangaOcrDownloadJob?.cancel()
        mangaOcrDownloadJob = null
        LogCollector.d(TAG, "manga-ocr 下载已取消")
        updateMangaOcrStatus()
    }

    private fun startMangaOcrDownload() {
        mangaOcrIsCancelled = false

        mangaOcrDownloadJob = lifecycleScope.launch(Dispatchers.IO) {
            LogCollector.d(TAG, "开始下载 manga-ocr 模型...")

            // 获取选择的版本
            val versionIndex = mangaOcrSpinner?.selectedItemPosition ?: 0
            val version = MangaOcrDownloadManager.ModelVersion.entries[versionIndex]
            LogCollector.d(TAG, "选择的版本: ${version.name} - ${version.description}")
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
                                mangaOcrProgressBar?.progress = progress

                                val mbRead = bytesRead / (1024 * 1024)
                                val mbTotal = if (totalBytes > 0) totalBytes / (1024 * 1024) else 0
                                mangaOcrSizeText?.text = "${mbRead}/${mbTotal} MB"

                                mangaOcrSpeedText?.text = getString(R.string.model_download_speed, speed)
                            }
                        }
                    }
                )

                withContext(Dispatchers.Main) {
                    mangaOcrDownloadJob = null
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

    private fun showMangaOcrDeleteConfirmDialog() {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.model_delete)
            .setMessage(getString(R.string.model_delete_confirm, "manga-ocr"))
            .setPositiveButton(R.string.confirm) { _, _ ->
                deleteMangaOcrModel()
            }
            .setNegativeButton(R.string.user_cancel, null)
            .show()
    }

    private fun deleteMangaOcrModel() {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = MangaOcrDownloadManager.deleteModel(requireContext())
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
        mangaOcrProgressBar = null
        mangaOcrSpeedText = null
        mangaOcrSizeText = null
        mangaOcrSpinner = null
        handler.removeCallbacksAndMessages(null)
        // 销毁时取消下载，但不设置 isCancelled 以便下次能正常下载
        downloadJob?.cancel()
        downloadJob = null
        mangaOcrDownloadJob?.cancel()
        mangaOcrDownloadJob = null
    }
}