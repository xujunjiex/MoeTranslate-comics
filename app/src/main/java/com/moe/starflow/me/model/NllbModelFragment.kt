package com.moe.starflow.me.model

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.moe.starflow.R
import com.moe.starflow.download.DownloadState
import com.moe.starflow.download.ModelDownloadRepository
import com.moe.starflow.download.ModelKey
import com.moe.starflow.download.ModelDownloadService
import com.moe.starflow.utils.LogCollector
import kotlinx.coroutines.launch

class NllbModelFragment : Fragment() {

    private val repo by lazy { ModelDownloadRepository.getInstance(requireContext()) }
    private val modelKey: ModelKey
        get() = arguments?.getString(ARG_MODEL_KEY)?.let { runCatching { ModelKey.valueOf(it) }.getOrNull() }
            ?: ModelKey.NLLB_GROUP

    companion object {
        private const val ARG_MODEL_KEY = "model_key"

        /** 本地翻译模型下载页。默认 NLLB，可传入其他本地翻译模型 ModelKey 复用本页面。 */
        fun newInstance(modelKey: ModelKey = ModelKey.NLLB_GROUP): NllbModelFragment =
            NllbModelFragment().apply {
                arguments = Bundle().apply { putString(ARG_MODEL_KEY, modelKey.name) }
            }
    }

    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var progressBarOverall: ProgressBar
    private lateinit var overallText: TextView
    private lateinit var downloadButton: Button
    private lateinit var pauseButton: Button
    private lateinit var resumeButton: Button
    private lateinit var cancelButton: Button
    private lateinit var deleteButton: Button
    private lateinit var downloadHandText: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_nllb_model, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        statusText = view.findViewById(R.id.statusText)
        progressBar = view.findViewById(R.id.progressBar)
        progressText = view.findViewById(R.id.progressText)
        progressBarOverall = view.findViewById(R.id.progressBarOverall)
        overallText = view.findViewById(R.id.overallText)
        downloadButton = view.findViewById(R.id.downloadButton)
        pauseButton = view.findViewById(R.id.pauseButton)
        resumeButton = view.findViewById(R.id.resumeButton)
        cancelButton = view.findViewById(R.id.cancelButton)
        deleteButton = view.findViewById(R.id.deleteButton)
        downloadHandText = view.findViewById(R.id.download_hand_Text)

        downloadHandText.setOnClickListener {
            // 手动下载说明链接从 downloadinfo.json 的 browser_url 读取
            val url = repo.getBrowserUrl(modelKey) ?: return@setOnClickListener
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        }

        downloadButton.setOnClickListener {
            ModelDownloadService.startDownload(requireContext(), modelKey, isResume = false)
        }
        pauseButton.setOnClickListener {
            ModelDownloadService.pauseDownload(requireContext(), modelKey)
        }
        resumeButton.setOnClickListener {
            ModelDownloadService.startDownload(requireContext(), modelKey, isResume = true)
        }
        cancelButton.setOnClickListener {
            ModelDownloadService.cancelDownload(requireContext(), modelKey)
        }
        deleteButton.setOnClickListener {
            lifecycleScope.launch {
                repo.deleteDownload(modelKey)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            // 页面进入时先按磁盘文件重新计算状态（识别已下载/部分下载的模型）
            repo.refreshFromDisk(modelKey)
            repo.observe().collect { snapshot ->
                val state = snapshot.states[modelKey] ?: DownloadState.Idle
                render(state)
            }
        }
    }

    private fun render(state: DownloadState) {
        downloadButton.visibility = View.GONE
        pauseButton.visibility = View.GONE
        resumeButton.visibility = View.GONE
        cancelButton.visibility = View.GONE
        deleteButton.visibility = View.GONE

        when (state) {
            DownloadState.Idle -> {
                statusText.text = getString(R.string.model_status_idle)
                progressBar.progress = 0
                progressText.text = "0%"
                progressBarOverall.progress = 0
                overallText.text = "0%"
                downloadButton.visibility = View.VISIBLE
            }
            is DownloadState.Running -> {
                // 当前文件进度（主进度条）
                val currentPct = state.currentFileProgress
                // 整体进度（跨所有文件）
                val overallPct = if (state.totalBytes > 0)
                    (state.bytesDownloaded * 100 / state.totalBytes).toInt() else 0
                progressBar.progress = currentPct
                val currentBytes = "${formatBytes(state.currentFileBytesDownloaded)} / ${formatBytes(state.currentFileTotalBytes)}"
                val speed = if (state.speedBytesPerSec > 0) formatSpeed(state.speedBytesPerSec) else ""
                progressText.text = "$currentPct% · $currentBytes${if (speed.isNotEmpty()) " · $speed" else ""}"
                progressBarOverall.progress = overallPct
                overallText.text = getString(
                    R.string.model_overall_progress,
                    overallPct,
                    formatBytes(state.bytesDownloaded),
                    formatBytes(state.totalBytes)
                )

                statusText.text = if (state.currentFileCount > 1) {
                    getString(
                        R.string.model_status_running_file,
                        state.currentFileIndex + 1,
                        state.currentFileCount,
                        state.currentFileName
                    )
                } else {
                    getString(R.string.model_status_running_single, currentPct)
                }

                pauseButton.visibility = View.VISIBLE
                cancelButton.visibility = View.VISIBLE
            }
            is DownloadState.Paused -> {
                val currentPct = if (state.currentFileTotalBytes > 0)
                    (state.currentFileBytesDownloaded * 100 / state.currentFileTotalBytes).toInt() else 0
                val overallPct = if (state.totalBytes > 0)
                    (state.bytesDownloaded * 100 / state.totalBytes).toInt() else 0
                progressBar.progress = currentPct
                progressText.text = "$currentPct%"
                progressBarOverall.progress = overallPct
                overallText.text = getString(
                    R.string.model_overall_progress,
                    overallPct,
                    formatBytes(state.bytesDownloaded),
                    formatBytes(state.totalBytes)
                )
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
                resumeButton.visibility = View.VISIBLE
            }
            is DownloadState.Partial -> {
                val currentPct = if (state.currentFileTotalBytes > 0)
                    (state.currentFileBytesDownloaded * 100 / state.currentFileTotalBytes).toInt() else 0
                val overallPct = if (state.totalBytes > 0)
                    (state.bytesDownloaded * 100 / state.totalBytes).toInt() else 0
                progressBar.progress = currentPct
                progressText.text = "$currentPct%"
                progressBarOverall.progress = overallPct
                overallText.text = getString(
                    R.string.model_overall_progress,
                    overallPct,
                    formatBytes(state.bytesDownloaded),
                    formatBytes(state.totalBytes)
                )
                statusText.text = if (state.currentFileCount > 1) {
                    getString(
                        R.string.model_status_partial_multi,
                        state.currentFileIndex + 1,
                        state.currentFileCount,
                        formatBytes(state.currentFileBytesDownloaded),
                        formatBytes(state.currentFileTotalBytes)
                    )
                } else {
                    getString(
                        R.string.model_status_partial,
                        formatBytes(state.bytesDownloaded),
                        formatBytes(state.totalBytes)
                    )
                }
                resumeButton.visibility = View.VISIBLE
            }
            DownloadState.Done -> {
                statusText.text = getString(R.string.model_status_done)
                progressBar.progress = 100
                progressText.text = "100%"
                progressBarOverall.progress = 100
                overallText.text = "100%"
                deleteButton.visibility = View.VISIBLE
            }
        }
        LogCollector.d("NllbModelFragment", "render: state=$state")
    }

    private fun formatBytes(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1) String.format("%.1f MB", mb) else String.format("%.0f KB", bytes / 1024.0)
    }

    private fun formatSpeed(bytesPerSec: Long): String =
        String.format("%.1f MB/s", bytesPerSec / (1024.0 * 1024.0))
}