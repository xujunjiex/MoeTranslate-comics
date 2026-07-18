package com.moe.starflow.me

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
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

    // v5 核心模型下载（det + rec_zh，原内置改为下载）
    @Volatile private var v5DetJob: Job? = null
    @Volatile private var v5DetCancelled = false
    @Volatile private var v5RecZhJob: Job? = null
    @Volatile private var v5RecZhCancelled = false

    // v5 可选模型下载（en/ko/ru）
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
        updateV5DetStatus()
        updateV5RecZhStatus()
        updateV5RecEnStatus()
        updateV5RecKoStatus()
        updateV5RecRuStatus()
        updatePPOcrV6Status()

        // 浏览器下载按钮
        setupBrowserDownloadButtons()
        setupV6Buttons()
        setupV6TierSwitching()

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
        // manga-ocr (3 independent buttons)
        rootView.findViewById<TextView>(R.id.manga_ocr_encoder_button)?.setOnClickListener {
            openBrowser("https://huggingface.co/l0wgear/manga-ocr-2025-onnx/resolve/main/encoder_model.onnx")
        }
        rootView.findViewById<TextView>(R.id.manga_ocr_decoder_button)?.setOnClickListener {
            openBrowser("https://huggingface.co/l0wgear/manga-ocr-2025-onnx/resolve/main/decoder_model.onnx")
        }
        rootView.findViewById<TextView>(R.id.manga_ocr_vocab_button)?.setOnClickListener {
            openBrowser("https://huggingface.co/l0wgear/manga-ocr-2025-onnx/resolve/main/vocab.txt")
        }
        // v5 det
        rootView.findViewById<TextView>(R.id.v5_det_browser_button)?.setOnClickListener {
            openBrowser(PPOcrModelManager.V5_DET_URL)
        }
        // v5 rec_zh ONNX+dict
        rootView.findViewById<TextView>(R.id.v5_rec_zh_onnx_button)?.setOnClickListener {
            openBrowser(PPOcrModelManager.V5_REC_ZH_ONNX_URL)
        }
        rootView.findViewById<TextView>(R.id.v5_rec_zh_dict_button)?.setOnClickListener {
            openBrowser(PPOcrModelManager.V5_REC_ZH_DICT_URL)
        }
        // v5 EN ONNX+dict
        rootView.findViewById<TextView>(R.id.v5_rec_en_onnx_button)?.setOnClickListener {
            PPOcrModelManager.DOWNLOAD_URLS["rec_en.onnx"]?.let { url -> openBrowser(url) }
        }
        rootView.findViewById<TextView>(R.id.v5_rec_en_dict_button)?.setOnClickListener {
            PPOcrModelManager.REC_DICT_URLS["en"]?.let { url -> openBrowser(url) }
        }
        // v5 KO ONNX+dict
        rootView.findViewById<TextView>(R.id.v5_rec_ko_onnx_button)?.setOnClickListener {
            PPOcrModelManager.DOWNLOAD_URLS["rec_ko.onnx"]?.let { url -> openBrowser(url) }
        }
        rootView.findViewById<TextView>(R.id.v5_rec_ko_dict_button)?.setOnClickListener {
            PPOcrModelManager.REC_DICT_URLS["ko"]?.let { url -> openBrowser(url) }
        }
        // v5 RU ONNX+dict
        rootView.findViewById<TextView>(R.id.v5_rec_ru_onnx_button)?.setOnClickListener {
            PPOcrModelManager.DOWNLOAD_URLS["rec_ru.onnx"]?.let { url -> openBrowser(url) }
        }
        rootView.findViewById<TextView>(R.id.v5_rec_ru_dict_button)?.setOnClickListener {
            PPOcrModelManager.REC_DICT_URLS["ru"]?.let { url -> openBrowser(url) }
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

    // ========== v5 det 下载相关 ==========

    private fun updateV5DetStatus() {
        val statusText = rootView.findViewById<TextView>(R.id.v5_det_status)
        val actionBtn = rootView.findViewById<TextView>(R.id.v5_det_action_button)
        val isDownloaded = PPOcrModelManager.isV5DetDownloaded(requireContext())
        val isDownloading = v5DetJob != null
        when {
            isDownloading -> {
                statusText.text = getString(R.string.model_downloading)
                actionBtn.text = getString(R.string.user_cancel)
                setButtonDeleteStyle(actionBtn, false)
                actionBtn.setOnClickListener { cancelV5DetDownload() }
            }
            isDownloaded -> {
                val size = PPOcrModelManager.getV5DetSizeString(requireContext())
                statusText.text = "${getString(R.string.model_downloaded)} ($size)"
                actionBtn.text = getString(R.string.model_delete)
                setButtonDeleteStyle(actionBtn, true)
                actionBtn.setOnClickListener { showV5DetDeleteConfirmDialog() }
            }
            else -> {
                statusText.text = getString(R.string.model_not_downloaded)
                actionBtn.text = getString(R.string.model_download)
                setButtonDeleteStyle(actionBtn, false)
                actionBtn.setOnClickListener { startV5DetDownload() }
            }
        }
    }

    private fun cancelV5DetDownload() { v5DetCancelled = true; v5DetJob?.cancel(); v5DetJob = null; updateV5DetStatus() }
    private fun startV5DetDownload() { startGenericDownload("det") }
    private fun showV5DetDeleteConfirmDialog() { showDeleteDialog("v5 det", "det") }

    // ========== v5 rec_zh 下载相关 ==========

    private fun updateV5RecZhStatus() {
        val statusText = rootView.findViewById<TextView>(R.id.v5_rec_zh_status)
        val actionBtn = rootView.findViewById<TextView>(R.id.v5_rec_zh_action_button)
        val isDownloaded = PPOcrModelManager.isV5RecZhDownloaded(requireContext())
        val isDownloading = v5RecZhJob != null
        when {
            isDownloading -> {
                statusText.text = getString(R.string.model_downloading)
                actionBtn.text = getString(R.string.user_cancel)
                setButtonDeleteStyle(actionBtn, false)
                actionBtn.setOnClickListener { cancelV5RecZhDownload() }
            }
            isDownloaded -> {
                val size = PPOcrModelManager.getV5RecZhSizeString(requireContext())
                statusText.text = "${getString(R.string.model_downloaded)} ($size)"
                actionBtn.text = getString(R.string.model_delete)
                setButtonDeleteStyle(actionBtn, true)
                actionBtn.setOnClickListener { showV5RecZhDeleteConfirmDialog() }
            }
            else -> {
                statusText.text = getString(R.string.model_not_downloaded)
                actionBtn.text = getString(R.string.model_download)
                setButtonDeleteStyle(actionBtn, false)
                actionBtn.setOnClickListener { startV5RecZhDownload() }
            }
        }
    }

    private fun cancelV5RecZhDownload() { v5RecZhCancelled = true; v5RecZhJob?.cancel(); v5RecZhJob = null; updateV5RecZhStatus() }
    private fun startV5RecZhDownload() { startGenericDownload("rec_zh") }
    private fun showV5RecZhDeleteConfirmDialog() { showDeleteDialog("v5 rec_zh", "rec_zh") }

    // ========== Generic download helpers for v5 ==========

    private fun startGenericDownload(type: String) {
        when (type) {
            "det" -> v5DetCancelled = false
            "rec_zh" -> v5RecZhCancelled = false
        }
        val job = lifecycleScope.launch(Dispatchers.IO) {
            val result = when (type) {
                "det" -> PPOcrModelManager.downloadV5Det(requireContext())
                "rec_zh" -> PPOcrModelManager.downloadV5RecZh(requireContext())
                else -> return@launch
            }
            withContext(Dispatchers.Main) {
                when (type) { "det" -> v5DetJob = null; "rec_zh" -> v5RecZhJob = null }
                if (result.isSuccess) Toast.makeText(requireContext(), R.string.model_download_success, Toast.LENGTH_SHORT).show()
                else Toast.makeText(requireContext(), getString(R.string.model_download_failed, result.exceptionOrNull()?.message), Toast.LENGTH_LONG).show()
                when (type) { "det" -> updateV5DetStatus(); "rec_zh" -> updateV5RecZhStatus() }
            }
        }
        when (type) { "det" -> v5DetJob = job; "rec_zh" -> v5RecZhJob = job }
        when (type) { "det" -> updateV5DetStatus(); "rec_zh" -> updateV5RecZhStatus() }
    }

    private fun showDeleteDialog(name: String, type: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.model_delete)
            .setMessage(getString(R.string.model_delete_confirm, name))
            .setPositiveButton(R.string.confirm) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val result = when (type) {
                        "det" -> PPOcrModelManager.deleteV5Det(requireContext())
                        "rec_zh" -> PPOcrModelManager.deleteV5RecZh(requireContext())
                        else -> return@launch
                    }
                    withContext(Dispatchers.Main) {
                        if (result.isSuccess) Toast.makeText(requireContext(), R.string.model_delete_success, Toast.LENGTH_SHORT).show()
                        else Toast.makeText(requireContext(), R.string.model_delete_failed, Toast.LENGTH_LONG).show()
                        when (type) { "det" -> updateV5DetStatus(); "rec_zh" -> updateV5RecZhStatus() }
                    }
                }
            }
            .setNegativeButton(R.string.user_cancel, null).show()
    }

    // ========== v5 EN 下载相关 ==========

    private fun updateV5RecEnStatus() {
        val statusText = rootView.findViewById<TextView>(R.id.v5_rec_en_status)
        val actionBtn = rootView.findViewById<TextView>(R.id.v5_rec_en_action_button)

        val isDownloaded = PPOcrModelManager.isRecModelDownloaded(requireContext(), "en")
        val isDownloading = ppOcrEnDownloadJob != null

        when {
            isDownloading -> {
                statusText.text = getString(R.string.model_downloading)
                actionBtn.text = getString(R.string.user_cancel)
                setButtonDeleteStyle(actionBtn, false)
                actionBtn.setOnClickListener { cancelV5RecEnDownload() }
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

    private fun cancelV5RecEnDownload() {
        cancelPPOcrDownload("en")
    }

    // ========== PP-OCRv5 KO 下载相关 ==========

    private fun updateV5RecKoStatus() {
        val statusText = rootView.findViewById<TextView>(R.id.v5_rec_ko_status)
        val actionBtn = rootView.findViewById<TextView>(R.id.v5_rec_ko_action_button)

        val isDownloaded = PPOcrModelManager.isRecModelDownloaded(requireContext(), "ko")
        val isDownloading = ppOcrKoDownloadJob != null

        when {
            isDownloading -> {
                statusText.text = getString(R.string.model_downloading)
                actionBtn.text = getString(R.string.user_cancel)
                setButtonDeleteStyle(actionBtn, false)
                actionBtn.setOnClickListener { cancelV5RecKoDownload() }
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

    private fun cancelV5RecKoDownload() {
        cancelPPOcrDownload("ko")
    }

    // ========== PP-OCRv5 RU 下载相关 ==========

    private fun updateV5RecRuStatus() {
        val statusText = rootView.findViewById<TextView>(R.id.v5_rec_ru_status)
        val actionBtn = rootView.findViewById<TextView>(R.id.v5_rec_ru_action_button)

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
            "en" -> updateV5RecEnStatus()
            "ko" -> updateV5RecKoStatus()
            "ru" -> updateV5RecRuStatus()
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
            "en" -> R.id.v5_rec_en_status
            "ko" -> R.id.v5_rec_ko_status
            else -> R.id.v5_rec_ru_status
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
                    "en" -> updateV5RecEnStatus()
                    "ko" -> updateV5RecKoStatus()
                    "ru" -> updateV5RecRuStatus()
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
        v5DetJob?.cancel()
        v5DetJob = null
        v5RecZhJob?.cancel()
        v5RecZhJob = null
        ppOcrV6DetJob?.cancel()
        ppOcrV6DetJob = null
        ppOcrV6RecJob?.cancel()
        ppOcrV6RecJob = null
    }

    // ========== PP-OCRv6 下载相关 ==========

    private var ppOcrV6DetJob: kotlinx.coroutines.Job? = null
    private var ppOcrV6RecJob: kotlinx.coroutines.Job? = null
    private var ppOcrV6DetIsCancelled = false
    private var ppOcrV6RecIsCancelled = false

    private fun setupV6Buttons() {
        // 浏览器按钮
        rootView.findViewById<TextView>(R.id.ppocrv6_medium_det_browser_button)?.setOnClickListener {
            PPOcrModelManager.V6_DOWNLOAD_URLS["det"]?.let { url -> openBrowser(url) }
        }
        rootView.findViewById<TextView>(R.id.ppocrv6_medium_rec_browser_button)?.setOnClickListener {
            PPOcrModelManager.V6_DOWNLOAD_URLS["rec"]?.let { url -> openBrowser(url) }
        }
    }

    private fun setupV6TierSwitching() {
        val smallRadio = rootView.findViewById<RadioButton>(R.id.ppocrv6_tier_small)
        val mediumRadio = rootView.findViewById<RadioButton>(R.id.ppocrv6_tier_medium)
        val currentTier = PreferenceManager.getDefaultSharedPreferences(requireContext()).getString("ppocrv6_tier", "small") ?: "small"

        // 初始状态
        smallRadio.isChecked = currentTier == "small"
        mediumRadio.isChecked = currentTier == "medium"

        smallRadio.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                PreferenceManager.getDefaultSharedPreferences(requireContext()).edit().putString("ppocrv6_tier", "small").commit()
                mediumRadio.isChecked = false
                LogCollector.d(TAG, "PP-OCRv6 tier switched to small")
                Toast.makeText(requireContext(), "PP-OCRv6 已切换到 small 模型", Toast.LENGTH_SHORT).show()
            }
        }
        mediumRadio.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                PreferenceManager.getDefaultSharedPreferences(requireContext()).edit().putString("ppocrv6_tier", "medium").commit()
                smallRadio.isChecked = false
                LogCollector.d(TAG, "PP-OCRv6 tier switched to medium")
                Toast.makeText(requireContext(), "PP-OCRv6 已切换到 medium 模型", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updatePPOcrV6Status() {
        updatePPOcrV6DetStatus()
        updatePPOcrV6RecStatus()
        updateV6TierVisibility()
    }

    private fun updateV6TierVisibility() {
        val smallRadio = rootView.findViewById<RadioButton>(R.id.ppocrv6_tier_small)
        val mediumRadio = rootView.findViewById<RadioButton>(R.id.ppocrv6_tier_medium)
        val detDownloaded = PPOcrModelManager.isV6MediumDownloaded(requireContext(), "det")
        val recDownloaded = PPOcrModelManager.isV6MediumDownloaded(requireContext(), "rec")
        val mediumAvailable = detDownloaded && recDownloaded

        mediumRadio.visibility = if (mediumAvailable) android.view.View.VISIBLE else android.view.View.GONE

        val currentTier = PreferenceManager.getDefaultSharedPreferences(requireContext()).getString("ppocrv6_tier", "small") ?: "small"
        smallRadio.isChecked = currentTier == "small"
        if (mediumAvailable) {
            mediumRadio.isChecked = currentTier == "medium"
        } else if (currentTier == "medium") {
            // medium 被删了，回退到 small
            PreferenceManager.getDefaultSharedPreferences(requireContext()).edit().putString("ppocrv6_tier", "small").commit()
            smallRadio.isChecked = true
            Toast.makeText(requireContext(), "medium 模型不完整，已自动切回 small", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updatePPOcrV6DetStatus() {
        val statusText = rootView.findViewById<TextView>(R.id.ppocrv6_medium_det_status)
        val actionBtn = rootView.findViewById<TextView>(R.id.ppocrv6_medium_det_action)
        val isDownloaded = PPOcrModelManager.isV6MediumDownloaded(requireContext(), "det")
        val isDownloading = ppOcrV6DetJob != null

        when {
            isDownloading -> {
                statusText.text = getString(R.string.model_downloading)
                actionBtn.text = getString(R.string.user_cancel)
                setButtonDeleteStyle(actionBtn, false)
                actionBtn.setOnClickListener { cancelV6Download("det") }
            }
            isDownloaded -> {
                val size = PPOcrModelManager.getV6MediumSize("det")
                statusText.text = "${getString(R.string.model_downloaded)} ($size)"
                actionBtn.text = getString(R.string.model_delete)
                setButtonDeleteStyle(actionBtn, true)
                actionBtn.setOnClickListener { showV6DeleteConfirmDialog("det") }
            }
            else -> {
                statusText.text = getString(R.string.model_not_downloaded)
                actionBtn.text = getString(R.string.model_download)
                setButtonDeleteStyle(actionBtn, false)
                actionBtn.setOnClickListener { startV6Download("det") }
            }
        }
    }

    private fun updatePPOcrV6RecStatus() {
        val statusText = rootView.findViewById<TextView>(R.id.ppocrv6_medium_rec_status)
        val actionBtn = rootView.findViewById<TextView>(R.id.ppocrv6_medium_rec_action)
        val isDownloaded = PPOcrModelManager.isV6MediumDownloaded(requireContext(), "rec")
        val isDownloading = ppOcrV6RecJob != null

        when {
            isDownloading -> {
                statusText.text = getString(R.string.model_downloading)
                actionBtn.text = getString(R.string.user_cancel)
                setButtonDeleteStyle(actionBtn, false)
                actionBtn.setOnClickListener { cancelV6Download("rec") }
            }
            isDownloaded -> {
                val size = PPOcrModelManager.getV6MediumSize("rec")
                statusText.text = "${getString(R.string.model_downloaded)} ($size)"
                actionBtn.text = getString(R.string.model_delete)
                setButtonDeleteStyle(actionBtn, true)
                actionBtn.setOnClickListener { showV6DeleteConfirmDialog("rec") }
            }
            else -> {
                statusText.text = getString(R.string.model_not_downloaded)
                actionBtn.text = getString(R.string.model_download)
                setButtonDeleteStyle(actionBtn, false)
                actionBtn.setOnClickListener { startV6Download("rec") }
            }
        }
    }

    private fun cancelV6Download(type: String) {
        when (type) {
            "det" -> { ppOcrV6DetIsCancelled = true; ppOcrV6DetJob?.cancel(); ppOcrV6DetJob = null }
            "rec" -> { ppOcrV6RecIsCancelled = true; ppOcrV6RecJob?.cancel(); ppOcrV6RecJob = null }
        }
        LogCollector.d(TAG, "PP-OCRv6 $type 下载已取消")
        when (type) {
            "det" -> updatePPOcrV6DetStatus()
            "rec" -> updatePPOcrV6RecStatus()
        }
    }

    private fun startV6Download(type: String) {
        when (type) {
            "det" -> ppOcrV6DetIsCancelled = false
            "rec" -> ppOcrV6RecIsCancelled = false
        }

        val isCancelled: () -> Boolean = {
            when (type) {
                "det" -> ppOcrV6DetIsCancelled
                else -> ppOcrV6RecIsCancelled
            }
        }

        val statusId = if (type == "det") R.id.ppocrv6_medium_det_status else R.id.ppocrv6_medium_rec_status

        val job = lifecycleScope.launch(Dispatchers.IO) {
            LogCollector.d(TAG, "开始下载 PP-OCRv6 $type 模型...")
            try {
                val result = PPOcrModelManager.downloadV6Medium(
                    requireContext(),
                    type,
                    object : ModelDownloadManager.ProgressCallback {
                        override fun onProgress(bytesRead: Long, totalBytes: Long, speed: Float) {
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
                    when (type) {
                        "det" -> ppOcrV6DetJob = null
                        "rec" -> ppOcrV6RecJob = null
                    }
                    if (isCancelled()) return@withContext
                    if (result.isSuccess) {
                        Toast.makeText(requireContext(), R.string.model_download_success, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(requireContext(), getString(R.string.model_download_failed, result.exceptionOrNull()?.message), Toast.LENGTH_LONG).show()
                    }
                    when (type) {
                        "det" -> updatePPOcrV6DetStatus()
                        "rec" -> updatePPOcrV6RecStatus()
                    }
                }
            } catch (e: Exception) {
                LogCollector.e(TAG, "PP-OCRv6 $type 下载异常", e)
                withContext(Dispatchers.Main) {
                    when (type) {
                        "det" -> ppOcrV6DetJob = null
                        "rec" -> ppOcrV6RecJob = null
                    }
                    if (isCancelled()) return@withContext
                    Toast.makeText(requireContext(), getString(R.string.model_download_failed, e.message), Toast.LENGTH_LONG).show()
                    when (type) {
                        "det" -> updatePPOcrV6DetStatus()
                        "rec" -> updatePPOcrV6RecStatus()
                    }
                }
            }
        }

        when (type) {
            "det" -> ppOcrV6DetJob = job
            "rec" -> ppOcrV6RecJob = job
        }
        when (type) {
            "det" -> updatePPOcrV6DetStatus()
            "rec" -> updatePPOcrV6RecStatus()
        }
    }

    private fun showV6DeleteConfirmDialog(type: String) {
        val name = if (type == "det") "PP-OCRv6 检测 medium" else "PP-OCRv6 识别 medium"
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.model_delete)
            .setMessage(getString(R.string.model_delete_confirm, name))
            .setPositiveButton(R.string.confirm) { _, _ -> deleteV6Model(type) }
            .setNegativeButton(R.string.user_cancel, null)
            .show()
    }

    private fun deleteV6Model(type: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = PPOcrModelManager.deleteV6Medium(requireContext(), type)
            withContext(Dispatchers.Main) {
                if (result.isSuccess) {
                    Toast.makeText(requireContext(), R.string.model_delete_success, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), R.string.model_delete_failed, Toast.LENGTH_LONG).show()
                }
                when (type) {
                    "det" -> updatePPOcrV6DetStatus()
                    "rec" -> updatePPOcrV6RecStatus()
                }
            }
        }
    }
}
