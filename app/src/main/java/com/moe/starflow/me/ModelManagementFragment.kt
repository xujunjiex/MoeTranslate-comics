package com.moe.starflow.me

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import android.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.moe.starflow.R
import com.moe.starflow.manga.MangaOcrDownloadManager
import com.moe.starflow.manga.ModelDownloadManager
import com.moe.starflow.manga.PPOcrModelManager
import com.moe.starflow.manga.RTDetrModelManager
import com.moe.starflow.utils.LogCollector
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ModelManagementFragment : Fragment() {

    private val TAG = "ModelManagementFragment"
    private lateinit var rootView: View
    private val handler = Handler(Looper.getMainLooper())

    // RT-DETR-V2 下载相关（@Volatile 跨线程标识，确保 IO 线程及时看到取消状态）
    @Volatile private var rtdetrDownloadJob: Job? = null
    @Volatile private var rtdetrIsCancelled = false

    // manga-ocr 下载相关
    @Volatile private var mangaOcrDownloadJob: Job? = null
    @Volatile private var mangaOcrIsCancelled = false

    // PP-OCRv5 可选模型下载相关（核心模型内置在 assets 中）
    @Volatile private var ppOcrEnDownloadJob: Job? = null
    @Volatile private var ppOcrEnIsCancelled = false
    @Volatile private var ppOcrKoDownloadJob: Job? = null
    @Volatile private var ppOcrKoIsCancelled = false
    @Volatile private var ppOcrRuDownloadJob: Job? = null
    @Volatile private var ppOcrRuIsCancelled = false

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
        updateMangaOcrStatus()
        updatePPOcrEnStatus()
        updatePPOcrKoStatus()
        updatePPOcrRuStatus()
        updatePPOcrV6Status()

        // 浏览器下载按钮
        setupBrowserDownloadButtons()
        setupV6Buttons()

        // 显示模型存储路径（Android 通用格式）
        val pathText = rootView.findViewById<TextView>(R.id.model_storage_path)
        val fullPath = requireContext().getExternalFilesDir(null)?.absolutePath ?: "N/A"
        // 提取 Android/data/... 部分，去掉 /storage/emulated/0/ 前缀
        val genericPath = if (fullPath.contains("Android/data/")) {
            fullPath.substring(fullPath.indexOf("Android/data/"))
        } else {
            fullPath
        }
        pathText.text = genericPath
    }

    private fun setupBrowserDownloadButtons() {
        // RT-DETR-V2
        rootView.findViewById<TextView>(R.id.rtdetr_browser_button)?.setOnClickListener {
            openBrowser(RTDetrModelManager.DOWNLOAD_URL)
        }
        // PP-OCRv5 EN
        rootView.findViewById<TextView>(R.id.ppocr_en_browser_button)?.setOnClickListener {
            PPOcrModelManager.DOWNLOAD_URLS["rec_en.onnx"]?.let { url -> openBrowser(url) }
        }
        // PP-OCRv5 KO
        rootView.findViewById<TextView>(R.id.ppocr_ko_browser_button)?.setOnClickListener {
            PPOcrModelManager.DOWNLOAD_URLS["rec_ko.onnx"]?.let { url -> openBrowser(url) }
        }
        // PP-OCRv5 RU
        rootView.findViewById<TextView>(R.id.ppocr_ru_browser_button)?.setOnClickListener {
            PPOcrModelManager.DOWNLOAD_URLS["rec_ru.onnx"]?.let { url -> openBrowser(url) }
        }
    }

    private fun openBrowser(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "无法打开浏览器", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setButtonDeleteStyle(btn: TextView, isDelete: Boolean) {
        btn.setBackgroundResource(if (isDelete) R.drawable.btn_delete else R.drawable.btn_download)
    }

    // ========== RT-DETR-V2 下载相关 ==========

    private fun updateRTDetrStatus() {
        val statusText = rootView.findViewById<TextView>(R.id.rtdetr_status)
        val actionBtn = rootView.findViewById<TextView>(R.id.rtdetr_action_button)

        val isDownloaded = RTDetrModelManager.isModelInFilesDir(requireContext())
        val isDownloading = rtdetrDownloadJob != null

        when {
            isDownloading -> {
                statusText.text = getString(R.string.model_downloading)
                actionBtn.text = getString(R.string.user_cancel)
                setButtonDeleteStyle(actionBtn, false)
                actionBtn.setOnClickListener { cancelRTDetrDownload() }
            }
            isDownloaded -> {
                val size = RTDetrModelManager.getModelSizeString(requireContext())
                statusText.text = "${getString(R.string.model_downloaded)} ($size)"
                actionBtn.text = getString(R.string.model_delete)
                setButtonDeleteStyle(actionBtn, true)
                actionBtn.setOnClickListener { showRTDetrDeleteConfirmDialog() }
            }
            else -> {
                statusText.text = getString(R.string.model_not_downloaded)
                actionBtn.text = getString(R.string.model_download)
                setButtonDeleteStyle(actionBtn, false)
                actionBtn.setOnClickListener { startRTDetrDownload() }
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
        AlertDialog.Builder(requireContext())
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

    // ========== manga-ocr 下载相关 ==========

    private fun updateMangaOcrStatus() {
        val statusText = rootView.findViewById<TextView>(R.id.manga_ocr_status)
        val actionBtn = rootView.findViewById<TextView>(R.id.manga_ocr_action_button)
        val browserBtn = rootView.findViewById<TextView>(R.id.manga_ocr_browser_button)

        val isDownloaded = MangaOcrDownloadManager.isModelDownloaded(requireContext())
        val isDownloading = mangaOcrDownloadJob != null

        when {
            isDownloading -> {
                statusText.text = getString(R.string.model_downloading)
                actionBtn.text = getString(R.string.user_cancel)
                setButtonDeleteStyle(actionBtn, false)
                actionBtn.setOnClickListener { cancelMangaOcrDownload() }
            }
            isDownloaded -> {
                val size = MangaOcrDownloadManager.getModelSizeString(requireContext())
                statusText.text = "${getString(R.string.model_downloaded)} ($size)"
                actionBtn.text = getString(R.string.model_delete)
                setButtonDeleteStyle(actionBtn, true)
                actionBtn.setOnClickListener { showMangaOcrDeleteConfirmDialog() }
            }
            else -> {
                statusText.text = getString(R.string.model_not_downloaded)
                actionBtn.text = getString(R.string.model_download)
                setButtonDeleteStyle(actionBtn, false)
                actionBtn.setOnClickListener { startMangaOcrDownload() }
            }
        }

        // 浏览器下载按钮
        browserBtn?.setOnClickListener {
            openBrowser("https://huggingface.co/l0wgear/manga-ocr-2025-onnx")
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

            try {
                val result = MangaOcrDownloadManager.downloadModel(
                    requireContext(),
                    object : MangaOcrDownloadManager.AggregateProgressCallback {
                        override fun onAggregateProgress(
                            bytesRead: Long,
                            totalBytes: Long,
                            speed: Float,
                            currentFileBytesRead: Long,
                            currentFileTotalBytes: Long,
                            currentFileName: String
                        ) {
                            if (mangaOcrIsCancelled || !isAdded) return
                            handler.post {
                                if (mangaOcrIsCancelled || !isAdded) return@post
                                val statusText = rootView.findViewById<TextView>(R.id.manga_ocr_status)

                                if (totalBytes > 0) {
                                    val pct = (bytesRead * 100 / totalBytes).toInt()
                                    val speedStr = if (speed > 0) String.format(" (%.1f MB/s)", speed) else ""
                                    statusText?.text = "${getString(R.string.model_downloading)} $pct%$speedStr"
                                } else {
                                    val mbRead = currentFileBytesRead / (1024 * 1024)
                                    val mbTotal = if (currentFileTotalBytes > 0) currentFileTotalBytes / (1024 * 1024) else 0
                                    val speedStr = if (speed > 0) String.format(" (%.1f MB/s)", speed) else ""
                                    statusText?.text = "${getString(R.string.model_downloading)} ${currentFileName}: $mbRead/$mbTotal MB$speedStr"
                                }
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
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.model_delete)
            .setMessage(getString(R.string.model_delete_confirm, "manga-ocr"))
            .setPositiveButton(R.string.confirm) { _, _ -> deleteMangaOcrModel() }
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

    // ========== PP-OCRv5 EN 下载相关 ==========（核心模型内置在 assets 中，无需下载）

    private fun updatePPOcrEnStatus() {
        val statusText = rootView.findViewById<TextView>(R.id.ppocr_en_status)
        val actionBtn = rootView.findViewById<TextView>(R.id.ppocr_en_action_button)

        val isDownloaded = PPOcrModelManager.isRecModelDownloaded(requireContext(), "en")
        val isDownloading = ppOcrEnDownloadJob != null

        when {
            isDownloading -> {
                statusText.text = getString(R.string.model_downloading)
                actionBtn.text = getString(R.string.user_cancel)
                setButtonDeleteStyle(actionBtn, false)
                actionBtn.setOnClickListener { cancelPPOcrEnDownload() }
            }
            isDownloaded -> {
                val size = PPOcrModelManager.getRecModelSizeString(requireContext(), "en")
                statusText.text = "${getString(R.string.model_downloaded)} ($size)"
                actionBtn.text = getString(R.string.model_delete)
                setButtonDeleteStyle(actionBtn, true)
                actionBtn.setOnClickListener { showPPOcrDeleteConfirmDialog("en") }
            }
            else -> {
                statusText.text = getString(R.string.model_not_downloaded)
                actionBtn.text = getString(R.string.model_download)
                setButtonDeleteStyle(actionBtn, false)
                actionBtn.setOnClickListener { startPPOcrDownload("en") }
            }
        }
    }

    private fun cancelPPOcrEnDownload() {
        cancelPPOcrDownload("en")
    }

    // ========== PP-OCRv5 KO 下载相关 ==========

    private fun updatePPOcrKoStatus() {
        val statusText = rootView.findViewById<TextView>(R.id.ppocr_ko_status)
        val actionBtn = rootView.findViewById<TextView>(R.id.ppocr_ko_action_button)

        val isDownloaded = PPOcrModelManager.isRecModelDownloaded(requireContext(), "ko")
        val isDownloading = ppOcrKoDownloadJob != null

        when {
            isDownloading -> {
                statusText.text = getString(R.string.model_downloading)
                actionBtn.text = getString(R.string.user_cancel)
                setButtonDeleteStyle(actionBtn, false)
                actionBtn.setOnClickListener { cancelPPOcrKoDownload() }
            }
            isDownloaded -> {
                val size = PPOcrModelManager.getRecModelSizeString(requireContext(), "ko")
                statusText.text = "${getString(R.string.model_downloaded)} ($size)"
                actionBtn.text = getString(R.string.model_delete)
                setButtonDeleteStyle(actionBtn, true)
                actionBtn.setOnClickListener { showPPOcrDeleteConfirmDialog("ko") }
            }
            else -> {
                statusText.text = getString(R.string.model_not_downloaded)
                actionBtn.text = getString(R.string.model_download)
                setButtonDeleteStyle(actionBtn, false)
                actionBtn.setOnClickListener { startPPOcrDownload("ko") }
            }
        }
    }

    private fun cancelPPOcrKoDownload() {
        cancelPPOcrDownload("ko")
    }

    // ========== PP-OCRv5 RU 下载相关 ==========

    private fun updatePPOcrRuStatus() {
        val statusText = rootView.findViewById<TextView>(R.id.ppocr_ru_status)
        val actionBtn = rootView.findViewById<TextView>(R.id.ppocr_ru_action_button)

        val isDownloaded = PPOcrModelManager.isRecModelDownloaded(requireContext(), "ru")
        val isDownloading = ppOcrRuDownloadJob != null

        when {
            isDownloading -> {
                statusText.text = getString(R.string.model_downloading)
                actionBtn.text = getString(R.string.user_cancel)
                setButtonDeleteStyle(actionBtn, false)
                actionBtn.setOnClickListener { cancelPPOcrDownload("ru") }
            }
            isDownloaded -> {
                val size = PPOcrModelManager.getRecModelSizeString(requireContext(), "ru")
                statusText.text = "${getString(R.string.model_downloaded)} ($size)"
                actionBtn.text = getString(R.string.model_delete)
                setButtonDeleteStyle(actionBtn, true)
                actionBtn.setOnClickListener { showPPOcrDeleteConfirmDialog("ru") }
            }
            else -> {
                statusText.text = getString(R.string.model_not_downloaded)
                actionBtn.text = getString(R.string.model_download)
                setButtonDeleteStyle(actionBtn, false)
                actionBtn.setOnClickListener { startPPOcrDownload("ru") }
            }
        }
    }

    // ========== PP-OCRv5 通用下载逻辑 ==========

    private fun cancelPPOcrDownload(lang: String) {
        when (lang) {
            "en" -> { ppOcrEnIsCancelled = true; ppOcrEnDownloadJob?.cancel(); ppOcrEnDownloadJob = null }
            "ko" -> { ppOcrKoIsCancelled = true; ppOcrKoDownloadJob?.cancel(); ppOcrKoDownloadJob = null }
            "ru" -> { ppOcrRuIsCancelled = true; ppOcrRuDownloadJob?.cancel(); ppOcrRuDownloadJob = null }
        }
        LogCollector.d(TAG, "PP-OCRv5 $lang 下载已取消")
        updatePPOcrStatus(lang)
    }

    private fun updatePPOcrStatus(lang: String) {
        when (lang) {
            "en" -> updatePPOcrEnStatus()
            "ko" -> updatePPOcrKoStatus()
            "ru" -> updatePPOcrRuStatus()
        }
    }

    private fun startPPOcrDownload(lang: String) {
        when (lang) {
            "en" -> ppOcrEnIsCancelled = false
            "ko" -> ppOcrKoIsCancelled = false
            "ru" -> ppOcrRuIsCancelled = false
        }

        val isCancelled: () -> Boolean = {
            when (lang) {
                "en" -> ppOcrEnIsCancelled
                "ko" -> ppOcrKoIsCancelled
                else -> ppOcrRuIsCancelled
            }
        }

        val statusId = when (lang) {
            "en" -> R.id.ppocr_en_status
            "ko" -> R.id.ppocr_ko_status
            else -> R.id.ppocr_ru_status
        }

        val job = lifecycleScope.launch(Dispatchers.IO) {
            LogCollector.d(TAG, "开始下载 PP-OCRv5 $lang 模型...")
            try {
                val result = PPOcrModelManager.downloadRecModel(
                    requireContext(),
                    lang,
                    object : ModelDownloadManager.ProgressCallback {
                        override fun onProgress(bytesRead: Long, totalBytes: Long, speed: Float) {
                            // 双重检查 + 短路：避免 cancel 后 handler.post 链上仍在执行 stale update
                            if (isCancelled() || !isAdded) return
                            handler.post {
                                if (isCancelled() || !isAdded) return@post
                                val progress = if (totalBytes > 0) (bytesRead * 100 / totalBytes).toInt() else 0
                                val statusText = rootView.findViewById<TextView>(statusId)
                                val mbRead = bytesRead / (1024 * 1024)
                                val mbTotal = if (totalBytes > 0) totalBytes / (1024 * 1024) else 0
                                val speedStr = if (speed > 0) String.format(" (%.1f MB/s)", speed) else ""
                                statusText.text = "${getString(R.string.model_downloading)} $progress%  ${mbRead}/${mbTotal} MB$speedStr"
                            }
                        }
                    }
                )

                withContext(Dispatchers.Main) {
                    when (lang) {
                        "en" -> ppOcrEnDownloadJob = null
                        "ko" -> ppOcrKoDownloadJob = null
                        "ru" -> ppOcrRuDownloadJob = null
                    }
                    if (isCancelled()) return@withContext
                    if (result.isSuccess) {
                        Toast.makeText(requireContext(), R.string.model_download_success, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(requireContext(), getString(R.string.model_download_failed, result.exceptionOrNull()?.message), Toast.LENGTH_LONG).show()
                    }
                    updatePPOcrStatus(lang)
                }
            } catch (e: Exception) {
                LogCollector.e(TAG, "PP-OCRv5 $lang 下载异常", e)
                withContext(Dispatchers.Main) {
                    when (lang) {
                        "en" -> ppOcrEnDownloadJob = null
                        "ko" -> ppOcrKoDownloadJob = null
                        "ru" -> ppOcrRuDownloadJob = null
                    }
                    if (isCancelled()) return@withContext
                    Toast.makeText(requireContext(), getString(R.string.model_download_failed, e.message), Toast.LENGTH_LONG).show()
                    updatePPOcrStatus(lang)
                }
            }
        }

        // 关键修复：先把 job 赋值给字段，再调 update，顺序反过来了 update 会看到正确状态
        when (lang) {
            "en" -> ppOcrEnDownloadJob = job
            "ko" -> ppOcrKoDownloadJob = job
            "ru" -> ppOcrRuDownloadJob = job
        }
        updatePPOcrStatus(lang)
    }

    private fun showPPOcrDeleteConfirmDialog(lang: String) {
        val name = when (lang) {
            "en" -> "PP-OCRv5 EN"
            "ko" -> "PP-OCRv5 KO"
            else -> "PP-OCRv5 RU"
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.model_delete)
            .setMessage(getString(R.string.model_delete_confirm, name))
            .setPositiveButton(R.string.confirm) { _, _ -> deletePPOcrModel(lang) }
            .setNegativeButton(R.string.user_cancel, null)
            .show()
    }

    private fun deletePPOcrModel(lang: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = PPOcrModelManager.deleteRecModel(requireContext(), lang)
            withContext(Dispatchers.Main) {
                if (result.isSuccess) {
                    Toast.makeText(requireContext(), R.string.model_delete_success, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), R.string.model_delete_failed, Toast.LENGTH_LONG).show()
                }
                when (lang) {
                    "en" -> updatePPOcrEnStatus()
                    "ko" -> updatePPOcrKoStatus()
                    "ru" -> updatePPOcrRuStatus()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacksAndMessages(null)
        rtdetrDownloadJob?.cancel()
        rtdetrDownloadJob = null
        mangaOcrDownloadJob?.cancel()
        mangaOcrDownloadJob = null
        ppOcrEnDownloadJob?.cancel()
        ppOcrEnDownloadJob = null
        ppOcrKoDownloadJob?.cancel()
        ppOcrKoDownloadJob = null
        ppOcrRuDownloadJob?.cancel()
        ppOcrRuDownloadJob = null
        ppOcrV6DetJob?.cancel()
        ppOcrV6DetJob = null
        ppOcrV6RecJob?.cancel()
        ppOcrV6RecJob = null
    }

    // ========== PP-OCRv6 下载相关 ==========

    private var ppOcrV6DetJob: kotlinx.coroutines.Job? = null
    private var ppOcrV6RecJob: kotlinx.coroutines.Job? = null

    private fun setupV6Buttons() {
        rootView.findViewById<TextView>(R.id.ppocrv6_medium_det_action)?.setOnClickListener {
            if (PPOcrModelManager.isV6MediumDownloaded(requireContext())) {
                showV6DeleteConfirmDialog()
            } else {
                startV6Download("det")
            }
        }
        rootView.findViewById<TextView>(R.id.ppocrv6_medium_rec_action)?.setOnClickListener {
            if (PPOcrModelManager.isV6MediumDownloaded(requireContext())) {
                showV6DeleteConfirmDialog()
            } else {
                startV6Download("rec")
            }
        }
    }

    private fun updatePPOcrV6Status() {
        val detStatus = rootView.findViewById<TextView>(R.id.ppocrv6_medium_det_status)
        val recStatus = rootView.findViewById<TextView>(R.id.ppocrv6_medium_rec_status)
        val detBtn = rootView.findViewById<TextView>(R.id.ppocrv6_medium_det_action)
        val recBtn = rootView.findViewById<TextView>(R.id.ppocrv6_medium_rec_action)
        val isDownloaded = PPOcrModelManager.isV6MediumDownloaded(requireContext())

        if (isDownloaded) {
            detStatus?.text = "已下载"
            recStatus?.text = "已下载"
            detBtn?.text = getString(R.string.model_delete)
            recBtn?.text = getString(R.string.model_delete)
        } else {
            detStatus?.text = "未下载"
            recStatus?.text = "未下载"
            detBtn?.text = getString(R.string.model_download)
            recBtn?.text = getString(R.string.model_download)
        }
    }

    private fun startV6Download(type: String) {
        val statusId = if (type == "det") R.id.ppocrv6_medium_det_status else R.id.ppocrv6_medium_rec_status
        lifecycleScope.launch {
            try {
                val statusText = rootView.findViewById<TextView>(statusId)
                statusText?.text = "下载中..."
                val result = PPOcrModelManager.downloadV6Medium(requireContext(), type)
                if (result.isSuccess) {
                    updatePPOcrV6Status()
                } else {
                    statusText?.text = "下载失败"
                }
            } catch (e: Exception) {
                LogCollector.e("ModelManagement", "PP-OCRv6 $type 下载异常", e)
                val statusText = rootView.findViewById<TextView>(statusId)
                statusText?.text = "下载失败: ${e.message}"
            }
        }
    }

    private fun showV6DeleteConfirmDialog() {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("删除 PP-OCRv6 medium 模型")
            .setMessage("确定要删除已下载的 medium 模型吗？删除后将使用内置 small 模型。")
            .setPositiveButton(R.string.confirm) { _, _ ->
                PPOcrModelManager.deleteV6Medium(requireContext())
                updatePPOcrV6Status()
            }
            .setNegativeButton(R.string.user_cancel, null)
            .show()
    }
}
