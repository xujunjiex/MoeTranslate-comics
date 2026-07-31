package com.moe.starflow.me

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
import com.moe.starflow.data.DownloadState
import com.moe.starflow.data.ModelDownloadRepository
import com.moe.starflow.manga.ModelKey
import com.moe.starflow.service.ModelDownloadService
import com.moe.starflow.utils.LogCollector
import kotlinx.coroutines.launch

class NllbModelFragment : Fragment() {

    private val repo by lazy { ModelDownloadRepository.getInstance(requireContext()) }
    private val modelKey = ModelKey.NLLB_GROUP

    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var sizeText: TextView
    private lateinit var speedText: TextView
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
        sizeText = view.findViewById(R.id.sizeText)
        speedText = view.findViewById(R.id.speedText)
        downloadButton = view.findViewById(R.id.downloadButton)
        pauseButton = view.findViewById(R.id.pauseButton)
        resumeButton = view.findViewById(R.id.resumeButton)
        cancelButton = view.findViewById(R.id.cancelButton)
        deleteButton = view.findViewById(R.id.deleteButton)
        downloadHandText = view.findViewById(R.id.download_hand_Text)

        downloadHandText.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/xujunjiex/StarFlow/releases"))
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
                sizeText.text = ""
                speedText.text = ""
                downloadButton.visibility = View.VISIBLE
            }
            is DownloadState.Running -> {
                val totalPct = if (state.totalBytes > 0)
                    (state.bytesDownloaded * 100 / state.totalBytes).toInt() else 0
                progressBar.progress = totalPct
                progressText.text = "$totalPct%"

                statusText.text = if (state.currentFileCount > 1) {
                    getString(
                        R.string.model_status_running_multi,
                        state.currentFileIndex + 1,
                        state.currentFileCount,
                        state.currentFileProgress,
                        state.currentFileName
                    )
                } else {
                    getString(R.string.model_status_running_single, totalPct)
                }

                sizeText.text = "${formatBytes(state.bytesDownloaded)} / ${formatBytes(state.totalBytes)}"
                speedText.text = if (state.speedBytesPerSec > 0) formatSpeed(state.speedBytesPerSec) else ""

                pauseButton.visibility = View.VISIBLE
                cancelButton.visibility = View.VISIBLE
            }
            is DownloadState.Paused -> {
                val totalPct = if (state.totalBytes > 0)
                    (state.bytesDownloaded * 100 / state.totalBytes).toInt() else 0
                progressBar.progress = totalPct
                progressText.text = "$totalPct%"
                statusText.text = getString(
                    R.string.model_status_paused,
                    formatBytes(state.bytesDownloaded),
                    formatBytes(state.totalBytes)
                )
                sizeText.text = "${formatBytes(state.bytesDownloaded)} / ${formatBytes(state.totalBytes)}"
                speedText.text = ""

                resumeButton.visibility = View.VISIBLE
                cancelButton.visibility = View.VISIBLE
            }
            is DownloadState.Partial -> {
                val totalPct = if (state.totalBytes > 0)
                    (state.bytesDownloaded * 100 / state.totalBytes).toInt() else 0
                progressBar.progress = totalPct
                progressText.text = "$totalPct%"
                statusText.text = getString(
                    R.string.model_status_partial,
                    formatBytes(state.bytesDownloaded),
                    formatBytes(state.totalBytes)
                )
                sizeText.text = "${formatBytes(state.bytesDownloaded)} / ${formatBytes(state.totalBytes)}"
                speedText.text = ""

                resumeButton.visibility = View.VISIBLE
                deleteButton.visibility = View.VISIBLE
            }
            DownloadState.Done -> {
                val totalBytes = repo.getModelInfo(modelKey)?.files?.sumOf { it.fileSize } ?: 0L
                statusText.text = getString(R.string.model_status_done)
                progressBar.progress = 100
                progressText.text = "100%"
                sizeText.text = formatBytes(totalBytes)
                speedText.text = ""
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