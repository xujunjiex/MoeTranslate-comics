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
import com.moe.starflow.download.DownloadState
import com.moe.starflow.download.ModelDownloadRepository
import com.moe.starflow.download.ModelKey
import com.moe.starflow.download.ModelDownloadService
import com.moe.starflow.utils.LogCollector
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.launch

/**
 * 模型管理页面 — **数据驱动**，可扩展。
 *
 * 新增一个模型只需：
 * 1. 在 `fragment_model_management.xml` 加一行（status/action/cancel + 可选浏览器按钮，ID 见下文约定）
 * 2. 在 [modelRows] 加一条 [ModelRow] 配置
 *
 * 渲染、浏览器按钮接线、磁盘状态刷新全部由 [modelRows] 自动完成，不再需要逐模型写方法。
 */
class ModelManagementFragment : Fragment() {

    private val TAG = "ModelManagementFragment"
    private lateinit var rootView: View
    private val handler = Handler(Looper.getMainLooper())
    private val repo by lazy { ModelDownloadRepository.getInstance(requireContext()) }

    /** 页面展示的所有模型行配置（顺序即页面顺序） */
    private data class ModelRow(
        val modelKey: ModelKey,
        val displayName: String,
        val expectedSize: String,
        val statusId: Int,
        val actionBtnId: Int,
        val cancelBtnId: Int,
        /** 浏览器按钮 → 打开 browser_url（模型主页） */
        val browserUrlBtnId: Int? = null,
        /** 浏览器按钮列表，逐个对应 JSON files 里第 i 个文件的 download_url */
        val fileBrowserBtnIds: List<Int> = emptyList()
    )

    private val modelRows: List<ModelRow> = listOf(
        // 1. RT-DETR-V2（单文件，浏览器按钮开模型主页）
        ModelRow(ModelKey.RT_DETR_V2, "RT-DETR-V2", "~11MB",
            R.id.rtdetr_status, R.id.rtdetr_action_button, R.id.rtdetr_cancel_button,
            browserUrlBtnId = R.id.rtdetr_browser_button),
        // 2. manga-ocr（3 文件：encoder/decoder/vocab）
        ModelRow(ModelKey.MANGA_OCR_GROUP, "manga-ocr", "~135MB",
            R.id.manga_ocr_status, R.id.manga_ocr_action_button, R.id.manga_ocr_cancel_button,
            fileBrowserBtnIds = listOf(
                R.id.manga_ocr_encoder_button,
                R.id.manga_ocr_decoder_button,
                R.id.manga_ocr_vocab_button
            )),
        // 3. PP-OCRv5 检测器（单文件，浏览器按钮开模型主页）
        ModelRow(ModelKey.PP_OCR_V5_DET, "PP-OCRv5 DET", "~4.6MB",
            R.id.v5_det_status, R.id.v5_det_action_button, R.id.v5_det_cancel_button,
            browserUrlBtnId = R.id.v5_det_browser_button),
        // 4-7. PP-OCRv5 识别器（每个 2 文件：onnx + 字典）
        ModelRow(ModelKey.PP_OCR_V5_REC_ZH, "PP-OCRv5 REC ZH", "~16MB",
            R.id.v5_rec_zh_status, R.id.v5_rec_zh_action_button, R.id.v5_rec_zh_cancel_button,
            fileBrowserBtnIds = listOf(R.id.v5_rec_zh_onnx_button, R.id.v5_rec_zh_dict_button)),
        ModelRow(ModelKey.PP_OCR_V5_REC_EN, "PP-OCRv5 REC EN", "~7.5MB",
            R.id.v5_rec_en_status, R.id.v5_rec_en_action_button, R.id.v5_rec_en_cancel_button,
            fileBrowserBtnIds = listOf(R.id.v5_rec_en_onnx_button, R.id.v5_rec_en_dict_button)),
        ModelRow(ModelKey.PP_OCR_V5_REC_KO, "PP-OCRv5 REC KO", "~12.9MB",
            R.id.v5_rec_ko_status, R.id.v5_rec_ko_action_button, R.id.v5_rec_ko_cancel_button,
            fileBrowserBtnIds = listOf(R.id.v5_rec_ko_onnx_button, R.id.v5_rec_ko_dict_button)),
        ModelRow(ModelKey.PP_OCR_V5_REC_RU, "PP-OCRv5 REC RU", "~7.7MB",
            R.id.v5_rec_ru_status, R.id.v5_rec_ru_action_button, R.id.v5_rec_ru_cancel_button,
            fileBrowserBtnIds = listOf(R.id.v5_rec_ru_onnx_button, R.id.v5_rec_ru_dict_button)),
        // 8-9. PP-OCRv6 medium（单文件，浏览器按钮开文件下载）
        ModelRow(ModelKey.PP_OCR_V6_MEDIUM_DET, "PP-OCRv6 DET (medium)", "~60MB",
            R.id.ppocrv6_medium_det_status, R.id.ppocrv6_medium_det_action, R.id.ppocrv6_medium_det_cancel,
            fileBrowserBtnIds = listOf(R.id.ppocrv6_medium_det_browser_button)),
        ModelRow(ModelKey.PP_OCR_V6_MEDIUM_REC, "PP-OCRv6 REC (medium)", "~74MB",
            R.id.ppocrv6_medium_rec_status, R.id.ppocrv6_medium_rec_action, R.id.ppocrv6_medium_rec_cancel,
            fileBrowserBtnIds = listOf(R.id.ppocrv6_medium_rec_browser_button))
    )

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
            for (row in modelRows) {
                repo.refreshFromDisk(row.modelKey)
            }
            repo.observe().collect {
                renderAll()
            }
        }

        setupBrowserDownloadButtons()
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

    /** 渲染所有模型行（数据驱动，遍历 [modelRows]） */
    private fun renderAll() {
        for (row in modelRows) {
            renderModelBlock(row, repo.getState(row.modelKey))
        }
        updateV6TierVisibility()
    }

    /** 接线所有浏览器按钮（数据驱动） */
    private fun setupBrowserDownloadButtons() {
        for (row in modelRows) {
            row.browserUrlBtnId?.let { btnId ->
                rootView.findViewById<TextView>(btnId)?.setOnClickListener {
                    openBrowser(repo.getBrowserUrl(row.modelKey) ?: "")
                }
            }
            row.fileBrowserBtnIds.forEachIndexed { i, btnId ->
                rootView.findViewById<TextView>(btnId)?.setOnClickListener {
                    val url = repo.getModelInfo(row.modelKey)?.files?.getOrNull(i)?.downloadUrl
                        ?: repo.getBrowserUrl(row.modelKey)
                        ?: ""
                    openBrowser(url)
                }
            }
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
        row: ModelRow,
        state: DownloadState
    ) {
        val statusText = rootView.findViewById<TextView>(row.statusId)
        val actionBtn = rootView.findViewById<TextView>(row.actionBtnId)
        val cancelBtn = rootView.findViewById<TextView>(row.cancelBtnId)

        // 默认隐藏 cancel 按钮
        cancelBtn.visibility = View.GONE

        when (state) {
            DownloadState.Idle, is DownloadState.Partial -> {
                statusText.text = getString(R.string.model_status_undownloaded_format, row.expectedSize)
                actionBtn.text = getString(R.string.model_download)
                setButtonDeleteStyle(actionBtn, false)
                actionBtn.setOnClickListener {
                    ModelDownloadService.startDownload(requireContext(), row.modelKey, isResume = state is DownloadState.Partial)
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
                    ModelDownloadService.pauseDownload(requireContext(), row.modelKey)
                }
                cancelBtn.visibility = View.VISIBLE
                cancelBtn.setOnClickListener {
                    ModelDownloadService.cancelDownload(requireContext(), row.modelKey)
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
                    ModelDownloadService.startDownload(requireContext(), row.modelKey, isResume = true)
                }
            }
            DownloadState.Done -> {
                val total = repo.getModelInfo(row.modelKey)?.files?.sumOf { it.fileSize } ?: 0L
                statusText.text = getString(R.string.model_status_with_size_format, formatBytes(total))
                actionBtn.text = getString(R.string.model_delete)
                setButtonDeleteStyle(actionBtn, true)
                actionBtn.setOnClickListener {
                    confirmDelete(row.modelKey, row.displayName)
                }
            }
        }
        LogCollector.d(TAG, "${row.displayName} state=$state")
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

    // ========== PP-OCRv6 small/medium 切档（v6 特有，保留） ==========

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
