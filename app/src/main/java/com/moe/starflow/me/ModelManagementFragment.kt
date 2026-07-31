package com.moe.starflow.me

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import android.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.moe.starflow.R
import com.moe.starflow.data.DownloadState
import com.moe.starflow.data.ModelDownloadRepository
import com.moe.starflow.manga.ModelKey
import com.moe.starflow.service.ModelDownloadService
import com.moe.starflow.utils.LogCollector
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.launch

class ModelManagementFragment : Fragment() {

    private val TAG = "ModelManagementFragment"
    private lateinit var rootView: View
    private val handler = Handler(Looper.getMainLooper())
    private val repo by lazy { ModelDownloadRepository.getInstance(requireContext()) }

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

        // Subscribe to Repository state changes; refresh all model status blocks on each emission.
        viewLifecycleOwner.lifecycleScope.launch {
            // 页面进入时先按磁盘文件重新计算状态（识别已下载/部分下载的模型）
            val shownKeys = listOf(
                ModelKey.RT_DETR_V2,
                ModelKey.MANGA_OCR_GROUP,
                ModelKey.PP_OCR_V5_DET,
                ModelKey.PP_OCR_V5_REC_ZH,
                ModelKey.PP_OCR_V5_REC_EN,
                ModelKey.PP_OCR_V5_REC_KO,
                ModelKey.PP_OCR_V5_REC_RU,
                ModelKey.PP_OCR_V6_MEDIUM_DET,
                ModelKey.PP_OCR_V6_MEDIUM_REC
            )
            for (key in shownKeys) {
                repo.refreshFromDisk(key)
            }
            repo.observe().collect {
                updateRTDetrStatus()
                updateMangaOcrStatus()
                updateV5DetStatus()
                updateV5RecZhStatus()
                updateV5RecEnStatus()
                updateV5RecKoStatus()
                updateV5RecRuStatus()
                updatePPOcrV6Status()
            }
        }

        // 浏览器下载按钮（用 JSON browser_url）
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
        // 所有浏览器按钮改为通过 repo.getBrowserUrl() 取 JSON 的 browser_url
        rootView.findViewById<TextView>(R.id.rtdetr_browser_button)?.setOnClickListener {
            openBrowser(repo.getBrowserUrl(ModelKey.RT_DETR_V2) ?: "")
        }
        // manga-ocr 三个文件按钮：用 JSON 里每个文件的 download_url
        rootView.findViewById<TextView>(R.id.manga_ocr_encoder_button)?.setOnClickListener {
            openBrowser(repo.getModelInfo(ModelKey.MANGA_OCR_GROUP)?.files?.firstOrNull()?.downloadUrl ?: "")
        }
        rootView.findViewById<TextView>(R.id.manga_ocr_decoder_button)?.setOnClickListener {
            openBrowser(repo.getModelInfo(ModelKey.MANGA_OCR_GROUP)?.files?.getOrNull(1)?.downloadUrl ?: "")
        }
        rootView.findViewById<TextView>(R.id.manga_ocr_vocab_button)?.setOnClickListener {
            openBrowser(repo.getModelInfo(ModelKey.MANGA_OCR_GROUP)?.files?.getOrNull(2)?.downloadUrl ?: "")
        }
        // v5 det / rec_zh
        rootView.findViewById<TextView>(R.id.v5_det_browser_button)?.setOnClickListener {
            openBrowser(repo.getBrowserUrl(ModelKey.PP_OCR_V5_DET) ?: "")
        }
        rootView.findViewById<TextView>(R.id.v5_rec_zh_onnx_button)?.setOnClickListener {
            openBrowser(repo.getModelInfo(ModelKey.PP_OCR_V5_REC_ZH)?.files?.firstOrNull()?.downloadUrl ?: "")
        }
        rootView.findViewById<TextView>(R.id.v5_rec_zh_dict_button)?.setOnClickListener {
            openBrowser(repo.getModelInfo(ModelKey.PP_OCR_V5_REC_ZH)?.files?.getOrNull(1)?.downloadUrl ?: "")
        }
        // v5 EN/KO/RU
        rootView.findViewById<TextView>(R.id.v5_rec_en_onnx_button)?.setOnClickListener {
            openBrowser(repo.getModelInfo(ModelKey.PP_OCR_V5_REC_EN)?.files?.firstOrNull()?.downloadUrl ?: "")
        }
        rootView.findViewById<TextView>(R.id.v5_rec_en_dict_button)?.setOnClickListener {
            openBrowser(repo.getModelInfo(ModelKey.PP_OCR_V5_REC_EN)?.files?.getOrNull(1)?.downloadUrl ?: "")
        }
        rootView.findViewById<TextView>(R.id.v5_rec_ko_onnx_button)?.setOnClickListener {
            openBrowser(repo.getModelInfo(ModelKey.PP_OCR_V5_REC_KO)?.files?.firstOrNull()?.downloadUrl ?: "")
        }
        rootView.findViewById<TextView>(R.id.v5_rec_ko_dict_button)?.setOnClickListener {
            openBrowser(repo.getModelInfo(ModelKey.PP_OCR_V5_REC_KO)?.files?.getOrNull(1)?.downloadUrl ?: "")
        }
        rootView.findViewById<TextView>(R.id.v5_rec_ru_onnx_button)?.setOnClickListener {
            openBrowser(repo.getModelInfo(ModelKey.PP_OCR_V5_REC_RU)?.files?.firstOrNull()?.downloadUrl ?: "")
        }
        rootView.findViewById<TextView>(R.id.v5_rec_ru_dict_button)?.setOnClickListener {
            openBrowser(repo.getModelInfo(ModelKey.PP_OCR_V5_REC_RU)?.files?.getOrNull(1)?.downloadUrl ?: "")
        }
    }

    private fun openBrowser(url: String) {
        if (url.isEmpty()) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            LogCollector.e(TAG, "无法打开浏览器: $url", e)
        }
    }

    private fun setButtonDeleteStyle(btn: TextView, isDelete: Boolean) {
        btn.setBackgroundResource(if (isDelete) R.drawable.btn_delete else R.drawable.btn_download)
    }

    /**
     * 统一渲染一个模型的状态块（2 按钮版）
     *
     * - Idle / Partial: 单按钮「下载」
     * - Running: 双按钮「暂停」+「取消」
     * - Paused: 单按钮「继续」
     * - Done: 单按钮「删除」
     */
    private fun renderModelBlock(
        statusId: Int,
        downloadBtnId: Int,
        cancelBtnId: Int,
        modelKey: ModelKey,
        state: DownloadState,
        displayName: String,
        expectedSize: String
    ) {
        val statusText = rootView.findViewById<TextView>(statusId)
        val actionBtn = rootView.findViewById<TextView>(downloadBtnId)
        val cancelBtn = rootView.findViewById<TextView>(cancelBtnId)

        // 默认隐藏 cancel 按钮
        cancelBtn.visibility = View.GONE

        when (state) {
            DownloadState.Idle, is DownloadState.Partial -> {
                statusText.text = getString(R.string.model_status_undownloaded_format, expectedSize)
                actionBtn.text = getString(R.string.model_download)
                setButtonDeleteStyle(actionBtn, false)
                actionBtn.setOnClickListener {
                    ModelDownloadService.startDownload(requireContext(), modelKey, isResume = state is DownloadState.Partial)
                }
            }
            is DownloadState.Running -> {
                val totalPct = if (state.totalBytes > 0)
                    (state.bytesDownloaded * 100 / state.totalBytes).toInt() else 0
                val speed = if (state.speedBytesPerSec > 0) " · ${formatSpeed(state.speedBytesPerSec)}" else ""
                statusText.text = if (state.currentFileCount > 1) {
                    // 多文件显示当前文件进度（第几个 + 当前文件百分比 + 文件名）+ 速度
                    getString(
                        R.string.model_status_running_multi,
                        state.currentFileIndex + 1,
                        state.currentFileCount,
                        state.currentFileProgress,
                        state.currentFileName
                    ) + speed
                } else {
                    "${getString(R.string.model_downloading)} $totalPct%$speed"
                }
                actionBtn.text = getString(R.string.model_btn_pause)
                setButtonDeleteStyle(actionBtn, false)
                actionBtn.setOnClickListener {
                    ModelDownloadService.pauseDownload(requireContext(), modelKey)
                }
                cancelBtn.visibility = View.VISIBLE
                cancelBtn.setOnClickListener {
                    ModelDownloadService.cancelDownload(requireContext(), modelKey)
                }
            }
            is DownloadState.Paused -> {
                statusText.text = if (state.currentFileCount > 1) {
                    getString(
                        R.string.model_status_paused_multi,
                        state.currentFileIndex + 1,
                        state.currentFileCount,
                        formatBytes(state.currentFileBytesDownloaded),
                        formatBytes(state.currentFileTotalBytes)
                    )
                } else {
                    getString(
                        R.string.model_status_paused,
                        formatBytes(state.bytesDownloaded),
                        formatBytes(state.totalBytes)
                    )
                }
                actionBtn.text = getString(R.string.model_btn_resume)
                setButtonDeleteStyle(actionBtn, false)
                actionBtn.setOnClickListener {
                    ModelDownloadService.startDownload(requireContext(), modelKey, isResume = true)
                }
            }
            DownloadState.Done -> {
                val total = repo.getModelInfo(modelKey)?.files?.sumOf { it.fileSize } ?: 0L
                statusText.text = getString(R.string.model_status_with_size_format, formatBytes(total))
                actionBtn.text = getString(R.string.model_delete)
                setButtonDeleteStyle(actionBtn, true)
                actionBtn.setOnClickListener {
                    confirmDelete(modelKey, displayName)
                }
            }
        }
        LogCollector.d(TAG, "$displayName state=$state")
    }

    private fun confirmDelete(modelKey: ModelKey, displayName: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.model_delete)
            .setMessage(getString(R.string.model_delete_confirm, displayName))
            .setPositiveButton(R.string.confirm) { _, _ ->
                lifecycleScope.launch { repo.deleteDownload(modelKey) }
            }
            .setNegativeButton(R.string.user_cancel, null)
            .show()
    }

    private fun formatBytes(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1) String.format("%.1f MB", mb) else String.format("%.0f KB", bytes / 1024.0)
    }

    private fun formatSpeed(bytesPerSec: Long): String =
        String.format("%.1f MB/s", bytesPerSec / (1024.0 * 1024.0))

    // ========== RT-DETR-V2 ==========
    private fun updateRTDetrStatus() {
        val state = repo.getState(ModelKey.RT_DETR_V2)
        renderModelBlock(
            statusId = R.id.rtdetr_status,
            downloadBtnId = R.id.rtdetr_action_button,
            cancelBtnId = R.id.rtdetr_cancel_button,
            modelKey = ModelKey.RT_DETR_V2,
            state = state,
            displayName = "RT-DETR-V2",
            expectedSize = "~11MB"
        )
    }

    // ========== manga-ocr ==========
    private fun updateMangaOcrStatus() {
        val state = repo.getState(ModelKey.MANGA_OCR_GROUP)
        renderModelBlock(
            statusId = R.id.manga_ocr_status,
            downloadBtnId = R.id.manga_ocr_action_button,
            cancelBtnId = R.id.manga_ocr_cancel_button,
            modelKey = ModelKey.MANGA_OCR_GROUP,
            state = state,
            displayName = "manga-ocr",
            expectedSize = "~135MB"
        )
    }

    // ========== v5 det ==========
    private fun updateV5DetStatus() {
        val state = repo.getState(ModelKey.PP_OCR_V5_DET)
        renderModelBlock(
            statusId = R.id.v5_det_status,
            downloadBtnId = R.id.v5_det_action_button,
            cancelBtnId = R.id.v5_det_cancel_button,
            modelKey = ModelKey.PP_OCR_V5_DET,
            state = state,
            displayName = "PP-OCRv5 DET",
            expectedSize = "~4.6MB"
        )
    }

    // ========== v5 rec_zh ==========
    private fun updateV5RecZhStatus() {
        val state = repo.getState(ModelKey.PP_OCR_V5_REC_ZH)
        renderModelBlock(
            statusId = R.id.v5_rec_zh_status,
            downloadBtnId = R.id.v5_rec_zh_action_button,
            cancelBtnId = R.id.v5_rec_zh_cancel_button,
            modelKey = ModelKey.PP_OCR_V5_REC_ZH,
            state = state,
            displayName = "PP-OCRv5 REC ZH",
            expectedSize = "~16MB"
        )
    }

    // ========== v5 rec_en ==========
    private fun updateV5RecEnStatus() {
        val state = repo.getState(ModelKey.PP_OCR_V5_REC_EN)
        renderModelBlock(
            statusId = R.id.v5_rec_en_status,
            downloadBtnId = R.id.v5_rec_en_action_button,
            cancelBtnId = R.id.v5_rec_en_cancel_button,
            modelKey = ModelKey.PP_OCR_V5_REC_EN,
            state = state,
            displayName = "PP-OCRv5 REC EN",
            expectedSize = "~7.5MB"
        )
    }

    // ========== v5 rec_ko ==========
    private fun updateV5RecKoStatus() {
        val state = repo.getState(ModelKey.PP_OCR_V5_REC_KO)
        renderModelBlock(
            statusId = R.id.v5_rec_ko_status,
            downloadBtnId = R.id.v5_rec_ko_action_button,
            cancelBtnId = R.id.v5_rec_ko_cancel_button,
            modelKey = ModelKey.PP_OCR_V5_REC_KO,
            state = state,
            displayName = "PP-OCRv5 REC KO",
            expectedSize = "~12.9MB"
        )
    }

    // ========== v5 rec_ru ==========
    private fun updateV5RecRuStatus() {
        val state = repo.getState(ModelKey.PP_OCR_V5_REC_RU)
        renderModelBlock(
            statusId = R.id.v5_rec_ru_status,
            downloadBtnId = R.id.v5_rec_ru_action_button,
            cancelBtnId = R.id.v5_rec_ru_cancel_button,
            modelKey = ModelKey.PP_OCR_V5_REC_RU,
            state = state,
            displayName = "PP-OCRv5 REC RU",
            expectedSize = "~7.7MB"
        )
    }

    // ========== PP-OCRv6 ==========
    private fun updatePPOcrV6Status() {
        updatePPOcrV6DetStatus()
        updatePPOcrV6RecStatus()
        updateV6TierVisibility()
    }

    private fun updateV6TierVisibility() {
        val smallRadio = rootView.findViewById<RadioButton>(R.id.ppocrv6_tier_small)
        val mediumRadio = rootView.findViewById<RadioButton>(R.id.ppocrv6_tier_medium)
        val detDownloaded = com.moe.starflow.manga.PPOcrModelManager.isV6MediumDownloaded(requireContext(), "det")
        val recDownloaded = com.moe.starflow.manga.PPOcrModelManager.isV6MediumDownloaded(requireContext(), "rec")
        val mediumAvailable = detDownloaded && recDownloaded

        mediumRadio.visibility = if (mediumAvailable) android.view.View.VISIBLE else android.view.View.GONE

        val currentTier = PreferenceManager.getDefaultSharedPreferences(requireContext()).getString("ppocrv6_tier", "small") ?: "small"
        smallRadio.isChecked = currentTier == "small"
        if (mediumAvailable) {
            mediumRadio.isChecked = currentTier == "medium"
        } else if (currentTier == "medium") {
            PreferenceManager.getDefaultSharedPreferences(requireContext()).edit().putString("ppocrv6_tier", "small").commit()
            smallRadio.isChecked = true
            android.widget.Toast.makeText(requireContext(), "medium 模型不完整，已自动切回 small", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun updatePPOcrV6DetStatus() {
        val state = repo.getState(ModelKey.PP_OCR_V6_MEDIUM_DET)
        renderModelBlock(
            statusId = R.id.ppocrv6_medium_det_status,
            downloadBtnId = R.id.ppocrv6_medium_det_action,
            cancelBtnId = R.id.ppocrv6_medium_det_cancel,
            modelKey = ModelKey.PP_OCR_V6_MEDIUM_DET,
            state = state,
            displayName = "PP-OCRv6 DET (medium)",
            expectedSize = "~60MB"
        )
    }

    private fun updatePPOcrV6RecStatus() {
        val state = repo.getState(ModelKey.PP_OCR_V6_MEDIUM_REC)
        renderModelBlock(
            statusId = R.id.ppocrv6_medium_rec_status,
            downloadBtnId = R.id.ppocrv6_medium_rec_action,
            cancelBtnId = R.id.ppocrv6_medium_rec_cancel,
            modelKey = ModelKey.PP_OCR_V6_MEDIUM_REC,
            state = state,
            displayName = "PP-OCRv6 REC (medium)",
            expectedSize = "~74MB"
        )
    }

    private fun setupV6Buttons() {
        rootView.findViewById<TextView>(R.id.ppocrv6_medium_det_browser_button)?.setOnClickListener {
            openBrowser(repo.getModelInfo(ModelKey.PP_OCR_V6_MEDIUM_DET)?.files?.firstOrNull()?.downloadUrl ?: "")
        }
        rootView.findViewById<TextView>(R.id.ppocrv6_medium_rec_browser_button)?.setOnClickListener {
            openBrowser(repo.getModelInfo(ModelKey.PP_OCR_V6_MEDIUM_REC)?.files?.firstOrNull()?.downloadUrl ?: "")
        }
    }

    private fun setupV6TierSwitching() {
        val smallRadio = rootView.findViewById<RadioButton>(R.id.ppocrv6_tier_small)
        val mediumRadio = rootView.findViewById<RadioButton>(R.id.ppocrv6_tier_medium)
        val currentTier = PreferenceManager.getDefaultSharedPreferences(requireContext()).getString("ppocrv6_tier", "small") ?: "small"

        smallRadio.isChecked = currentTier == "small"
        mediumRadio.isChecked = currentTier == "medium"

        smallRadio.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                PreferenceManager.getDefaultSharedPreferences(requireContext()).edit().putString("ppocrv6_tier", "small").commit()
                mediumRadio.isChecked = false
                LogCollector.d(TAG, "PP-OCRv6 tier switched to small")
                android.widget.Toast.makeText(requireContext(), "PP-OCRv6 已切换到 small 模型", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        mediumRadio.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                PreferenceManager.getDefaultSharedPreferences(requireContext()).edit().putString("ppocrv6_tier", "medium").commit()
                smallRadio.isChecked = false
                LogCollector.d(TAG, "PP-OCRv6 tier switched to medium")
                android.widget.Toast.makeText(requireContext(), "PP-OCRv6 已切换到 medium 模型", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacksAndMessages(null)
    }
}