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
import com.moe.moetranslator.manga.ModelDownloadManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ModelManagementFragment : Fragment() {

    private lateinit var rootView: View
    private val handler = Handler(Looper.getMainLooper())
    private var isDownloading = false
    private var downloadJob: kotlinx.coroutines.Job? = null

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
    }

    private fun setupViews() {
        val ctcDownloadBtn = rootView.findViewById<Button>(R.id.ctc_download_button)
        val ctcDeleteBtn = rootView.findViewById<Button>(R.id.ctc_delete_button)
        val ctcCancelBtn = rootView.findViewById<Button>(R.id.ctc_cancel_button)

        // 下载按钮
        ctcDownloadBtn.setOnClickListener {
            if (isDownloading) return@setOnClickListener
            startDownload()
        }

        // 删除按钮
        ctcDeleteBtn.setOnClickListener {
            showDeleteConfirmDialog()
        }

        // 取消按钮
        ctcCancelBtn.setOnClickListener {
            downloadJob?.cancel()
            isDownloading = false
            updateCtcStatus()
        }
    }

    private fun updateCtcStatus() {
        val ctcStatus = rootView.findViewById<TextView>(R.id.ctc_status_text)
        val ctcSize = rootView.findViewById<TextView>(R.id.ctc_size_text)
        val ctcDownloadBtn = rootView.findViewById<Button>(R.id.ctc_download_button)
        val ctcDeleteBtn = rootView.findViewById<Button>(R.id.ctc_delete_button)
        val ctcCancelBtn = rootView.findViewById<Button>(R.id.ctc_cancel_button)
        val ctcProgress = rootView.findViewById<ProgressBar>(R.id.ctc_download_progress)
        val ctcSpeed = rootView.findViewById<TextView>(R.id.ctc_speed_text)

        val isDownloaded = CtcOcrModelManager.isModelDownloaded(requireContext())
        if (isDownloaded) {
            ctcStatus.text = getString(R.string.model_downloaded)
            ctcSize.text = CtcOcrModelManager.getModelSizeString(requireContext())
            ctcDownloadBtn.visibility = View.GONE
            ctcDeleteBtn.visibility = View.VISIBLE
            ctcCancelBtn.visibility = View.GONE
            ctcProgress.visibility = View.GONE
            ctcProgress.progress = 0
            ctcSpeed.visibility = View.GONE
        } else if (isDownloading) {
            // 下载中
            ctcStatus.text = getString(R.string.model_downloading)
            ctcSize.text = ""
            ctcDownloadBtn.visibility = View.GONE
            ctcDeleteBtn.visibility = View.GONE
            ctcCancelBtn.visibility = View.VISIBLE
            ctcProgress.visibility = View.VISIBLE
            ctcSpeed.visibility = View.VISIBLE
        } else {
            ctcStatus.text = getString(R.string.model_not_downloaded)
            ctcSize.text = "144 MB"
            ctcDownloadBtn.visibility = View.VISIBLE
            ctcDeleteBtn.visibility = View.GONE
            ctcCancelBtn.visibility = View.GONE
            ctcProgress.visibility = View.GONE
            ctcProgress.progress = 0
            ctcSpeed.visibility = View.GONE
        }
    }

    private fun startDownload() {
        isDownloading = true
        updateCtcStatus()

        downloadJob = lifecycleScope.launch(Dispatchers.IO) {
            val result = CtcOcrModelManager.downloadModel(
                requireContext(),
                object : ModelDownloadManager.ProgressCallback {
                    override fun onProgress(bytesRead: Long, totalBytes: Long, speed: Float) {
                        handler.post {
                            val progressBar = rootView.findViewById<ProgressBar>(R.id.ctc_download_progress)
                            val speedText = rootView.findViewById<TextView>(R.id.ctc_speed_text)
                            val ctcSize = rootView.findViewById<TextView>(R.id.ctc_size_text)

                            val progress = if (totalBytes > 0) {
                                (bytesRead * 100 / totalBytes).toInt()
                            } else 0
                            progressBar.progress = progress

                            val mbRead = bytesRead / (1024 * 1024)
                            val mbTotal = if (totalBytes > 0) totalBytes / (1024 * 1024) else 0
                            ctcSize.text = "${mbRead}/${mbTotal} MB"

                            speedText.text = getString(R.string.model_download_speed, speed)
                        }
                    }
                }
            )

            withContext(Dispatchers.Main) {
                isDownloading = false
                if (result.isSuccess) {
                    Toast.makeText(requireContext(), R.string.model_download_success, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.model_download_failed, result.exceptionOrNull()?.message),
                        Toast.LENGTH_LONG
                    ).show()
                }
                updateCtcStatus()
            }
        }
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

    override fun onDestroyView() {
        super.onDestroyView()
        downloadJob?.cancel()
    }
}