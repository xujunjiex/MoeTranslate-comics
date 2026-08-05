package com.moe.starflow.me.model
import com.moe.starflow.translate.autotranslate.*
import com.moe.starflow.translate.screenshot.*

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.moe.starflow.R
import com.moe.starflow.databinding.FragmentHymt2DetailBinding
import com.moe.starflow.download.DownloadState
import com.moe.starflow.download.ModelDownloadRepository
import com.moe.starflow.download.ModelDownloadService
import com.moe.starflow.download.ModelKey
import com.moe.starflow.utils.CustomPreference
import com.moe.starflow.utils.LogCollector
import com.moe.starflow.utils.UiUtils
import kotlinx.coroutines.launch
import translationapi.hymt2translation.HyMt2Params

class HyMt2DetailFragment : Fragment() {

    private val repo by lazy { ModelDownloadRepository.getInstance(requireContext()) }
    private val modelKey get() = ModelKey.HY_MT2_GROUP
    private var binding: FragmentHymt2DetailBinding? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = FragmentHymt2DetailBinding.inflate(inflater, container, false).also { binding = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val b = binding ?: return

        b.downloadButton.setOnClickListener {
            ModelDownloadService.startDownload(requireContext(), modelKey, isResume = false)
        }
        b.pauseButton.setOnClickListener {
            ModelDownloadService.pauseDownload(requireContext(), modelKey)
        }
        b.resumeButton.setOnClickListener {
            ModelDownloadService.startDownload(requireContext(), modelKey, isResume = true)
        }
        b.cancelButton.setOnClickListener {
            ModelDownloadService.cancelDownload(requireContext(), modelKey)
        }
        b.deleteButton.setOnClickListener {
            lifecycleScope.launch { repo.deleteDownload(modelKey) }
        }
        b.downloadHandText.setOnClickListener {
            val url = repo.getBrowserUrl(modelKey) ?: return@setOnClickListener
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }

        // 上下文下拉
        val contextOptions = arrayOf("1024", "2048", "4096")
        b.contextSelect.setAdapter(
            android.widget.ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, contextOptions)
        )

        setupParamHelp(b)
        loadParams(b)

        b.saveButton.setOnClickListener {
            saveParams(b)
            UiUtils.showToast(requireContext(), getString(R.string.hymt2_params_saved), isShort = true)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repo.refreshFromDisk(modelKey)
            repo.observe().collect { snapshot ->
                val state = snapshot.states[modelKey] ?: DownloadState.Idle
                render(b, state)
            }
        }
    }

    /** 每个参数输入框右侧的信息图标 → 弹出该参数的官方说明。 */
    private fun setupParamHelp(b: FragmentHymt2DetailBinding) {
        b.promptInput.setEndIconOnClickListener {
            showParamHelp(R.string.hymt2_prompt_label, R.string.hymt2_help_prompt)
        }
        b.threadsInput.setEndIconOnClickListener {
            showParamHelp(R.string.hymt2_threads_label, R.string.hymt2_help_threads)
        }
        b.batchThreadsInput.setEndIconOnClickListener {
            showParamHelp(R.string.hymt2_batch_threads_label, R.string.hymt2_help_batch_threads)
        }
        b.contextInput.setEndIconOnClickListener {
            showParamHelp(R.string.hymt2_context_label, R.string.hymt2_help_context)
        }
        b.tempInput.setEndIconOnClickListener {
            showParamHelp(R.string.hymt2_temp_label, R.string.hymt2_help_temperature)
        }
        b.topPInput.setEndIconOnClickListener {
            showParamHelp(R.string.hymt2_top_p_label, R.string.hymt2_help_top_p)
        }
        b.topKInput.setEndIconOnClickListener {
            showParamHelp(R.string.hymt2_top_k_label, R.string.hymt2_help_top_k)
        }
        b.repPenaltyInput.setEndIconOnClickListener {
            showParamHelp(R.string.hymt2_rep_penalty_label, R.string.hymt2_help_rep_penalty)
        }
        b.maxTokensInput.setEndIconOnClickListener {
            showParamHelp(R.string.hymt2_max_tokens_label, R.string.hymt2_help_max_tokens)
        }
    }

    private fun showParamHelp(titleRes: Int, descRes: Int) {
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(titleRes)
            .setMessage(descRes)
            .setPositiveButton(R.string.user_known, null)
            .create()
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
    }

    private fun loadParams(b: FragmentHymt2DetailBinding) {
        val s = HyMt2Params.read(CustomPreference.getInstance(requireContext()).getSharedPreferences())
        b.promptEdit.setText(s.promptTemplate)
        b.threadsEdit.setText(s.threads.toString())
        b.batchThreadsEdit.setText(s.batchThreads.toString())
        b.contextSelect.setText(s.contextSize.toString(), false)
        b.tempEdit.setText(s.temperature.toString())
        b.topPEdit.setText(s.topP.toString())
        b.topKEdit.setText(s.topK.toString())
        b.repPenaltyEdit.setText(s.repetitionPenalty.toString())
        b.maxTokensEdit.setText(s.maxTokens.toString())
    }

    private fun saveParams(b: FragmentHymt2DetailBinding) {
        val prefs = CustomPreference.getInstance(requireContext())
        prefs.setString(HyMt2Params.KEY_PROMPT, b.promptEdit.text?.toString()?.takeIf { it.isNotBlank() } ?: HyMt2Params.DEFAULT_PROMPT)
        prefs.setInt(HyMt2Params.KEY_THREADS, b.threadsEdit.text?.toString()?.toIntOrNull()?.coerceIn(1, 16) ?: HyMt2Params.defaultThreads)
        prefs.setInt(HyMt2Params.KEY_BATCH_THREADS, b.batchThreadsEdit.text?.toString()?.toIntOrNull()?.coerceIn(1, 16) ?: HyMt2Params.defaultBatchThreads)
        prefs.setInt(HyMt2Params.KEY_CONTEXT, b.contextSelect.text?.toString()?.toIntOrNull() ?: HyMt2Params.DEFAULT_CONTEXT)
        prefs.setFloat(HyMt2Params.KEY_TEMP, b.tempEdit.text?.toString()?.toFloatOrNull() ?: HyMt2Params.DEFAULT_TEMP)
        prefs.setFloat(HyMt2Params.KEY_TOP_P, b.topPEdit.text?.toString()?.toFloatOrNull() ?: HyMt2Params.DEFAULT_TOP_P)
        prefs.setInt(HyMt2Params.KEY_TOP_K, b.topKEdit.text?.toString()?.toIntOrNull() ?: HyMt2Params.DEFAULT_TOP_K)
        prefs.setFloat(HyMt2Params.KEY_REP_PENALTY, b.repPenaltyEdit.text?.toString()?.toFloatOrNull() ?: HyMt2Params.DEFAULT_REP_PENALTY)
        prefs.setInt(HyMt2Params.KEY_MAX_TOKENS, b.maxTokensEdit.text?.toString()?.toIntOrNull() ?: HyMt2Params.DEFAULT_MAX_TOKENS)
    }

    /** 单文件模型：只显示单个进度条（Hy-MT2 仅一个 gguf 文件，无需整体进度）。 */
    private fun render(b: FragmentHymt2DetailBinding, state: DownloadState) {
        b.downloadButton.visibility = View.GONE
        b.pauseButton.visibility = View.GONE
        b.resumeButton.visibility = View.GONE
        b.cancelButton.visibility = View.GONE
        b.deleteButton.visibility = View.GONE

        when (state) {
            DownloadState.Idle -> {
                b.statusText.text = getString(R.string.model_status_idle)
                b.progressBar.progress = 0
                b.progressText.text = "0%"
                b.downloadButton.visibility = View.VISIBLE
            }
            is DownloadState.Running -> {
                val currentPct = state.currentFileProgress
                b.progressBar.progress = currentPct
                b.progressText.text = "$currentPct% · ${formatBytes(state.currentFileBytesDownloaded)} / ${formatBytes(state.currentFileTotalBytes)}"
                b.statusText.text = getString(R.string.model_status_running_single, currentPct)
                b.pauseButton.visibility = View.VISIBLE
                b.cancelButton.visibility = View.VISIBLE
            }
            is DownloadState.Paused -> {
                val pct = if (state.currentFileTotalBytes > 0)
                    (state.currentFileBytesDownloaded * 100 / state.currentFileTotalBytes).toInt() else 0
                b.progressBar.progress = pct
                b.progressText.text = "$pct%"
                b.statusText.text = getString(R.string.model_status_paused, formatBytes(state.bytesDownloaded), formatBytes(state.totalBytes))
                b.resumeButton.visibility = View.VISIBLE
            }
            is DownloadState.Partial -> {
                b.statusText.text = getString(R.string.model_status_partial, formatBytes(state.bytesDownloaded), formatBytes(state.totalBytes))
                b.resumeButton.visibility = View.VISIBLE
            }
            DownloadState.Done -> {
                b.statusText.text = getString(R.string.model_status_done)
                b.progressBar.progress = 100
                b.progressText.text = "100%"
                b.deleteButton.visibility = View.VISIBLE
            }
        }
        LogCollector.d("HyMt2DetailFragment", "render: state=$state")
    }

    private fun formatBytes(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1) String.format("%.1f MB", mb) else String.format("%.0f KB", bytes / 1024.0)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }
}
