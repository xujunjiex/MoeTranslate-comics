package com.moe.starflow.me

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_nllb_model, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            repo.observe().collect { updateUI(view, it.states[ModelKey.NLLB_GROUP] ?: DownloadState.Idle) }
        }
    }

    private fun updateUI(view: View, state: DownloadState) {
        val statusText = view.findViewById<TextView>(R.id.nllb_status)
        val actionBtn = view.findViewById<TextView>(R.id.nllb_action_button)

        when (state) {
            is DownloadState.Running -> {
                val mb = state.bytesDownloaded / (1024 * 1024)
                val total = if (state.totalBytes > 0) state.totalBytes / (1024 * 1024) else 0
                statusText.text = "下载中 ${mb}/${total} MB"
                actionBtn.text = "取消"
                actionBtn.setOnClickListener {
                    ModelDownloadService.cancelDownload(requireContext(), ModelKey.NLLB_GROUP)
                }
            }
            is DownloadState.Partial -> {
                val mb = state.bytesDownloaded / (1024 * 1024)
                val total = if (state.totalBytes > 0) state.totalBytes / (1024 * 1024) else 0
                statusText.text = "已下载 ${mb} MB / ${total} MB（部分）"
                actionBtn.text = "继续"
                actionBtn.setOnClickListener {
                    ModelDownloadService.startDownload(requireContext(), ModelKey.NLLB_GROUP, isResume = true)
                }
            }
            is DownloadState.Done -> {
                statusText.text = "已下载"
                actionBtn.text = "删除"
                actionBtn.setOnClickListener {
                    lifecycleScope.launch {
                        repo.deleteDownload(ModelKey.NLLB_GROUP)
                    }
                }
            }
            else -> {
                statusText.text = "未下载"
                actionBtn.text = "下载"
                actionBtn.setOnClickListener {
                    ModelDownloadService.startDownload(requireContext(), ModelKey.NLLB_GROUP, isResume = false)
                }
            }
        }
        LogCollector.d("NllbModelFragment", "updateUI: state=$state")
    }
}
