package com.moe.moetranslator.me

import android.app.AlertDialog
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

        // 下载按钮
        ctcDownloadBtn.setOnClickListener {
            if (isDownloading) return@setOnClickListener
            showDownloadDialog()
        }

        // 删除按钮
        ctcDeleteBtn.setOnClickListener {
            showDeleteConfirmDialog()
        }
    }

    private fun updateCtcStatus() {
        val ctcStatus = rootView.findViewById<TextView>(R.id.ctc_status_text)
        val ctcSize = rootView.findViewById<TextView>(R.id.ctc_size_text)
        val ctcDownloadBtn = rootView.findViewById<Button>(R.id.ctc_download_button)
        val ctcDeleteBtn = rootView.findViewById<Button>(R.id.ctc_delete_button)

        val isDownloaded = CtcOcrModelManager.isModelDownloaded(requireContext())
        if (isDownloaded) {
            ctcStatus.text = getString(R.string.model_downloaded)
            ctcSize.text = CtcOcrModelManager.getModelSizeString(requireContext())
            ctcDownloadBtn.visibility = View.GONE
            ctcDeleteBtn.visibility = View.VISIBLE
        } else {
            ctcStatus.text = getString(R.string.model_not_downloaded)
            ctcSize.text = "144 MB"
            ctcDownloadBtn.visibility = View.VISIBLE
            ctcDeleteBtn.visibility = View.GONE
        }
    }

    private fun showDownloadDialog() {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_model_download, null)
        val progressBar = dialogView.findViewById<ProgressBar>(R.id.download_progress)
        val statusText = dialogView.findViewById<TextView>(R.id.download_status_text)
        val speedText = dialogView.findViewById<TextView>(R.id.download_speed_text)

        var dialogDismissed = false

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.manga_ocr_ctc_download_title)
            .setView(dialogView)
            .setNegativeButton(R.string.user_cancel) { _, _ ->
                dialogDismissed = true
                downloadJob?.cancel()
            }
            .setCancelable(false)
            .create()

        dialog.show()

        isDownloading = true

        downloadJob = lifecycleScope.launch(Dispatchers.IO) {
            val result = CtcOcrModelManager.downloadModel(
                requireContext(),
                object : ModelDownloadManager.ProgressCallback {
                    override fun onProgress(bytesRead: Long, totalBytes: Long, speed: Float) {
                        handler.post {
                            if (!dialogDismissed && dialog.isShowing) {
                                val progress = if (totalBytes > 0) {
                                    (bytesRead * 100 / totalBytes).toInt()
                                } else 0
                                progressBar.progress = progress
                                statusText.text = getString(R.string.manga_ocr_ctc_download_progress, progress)
                                speedText.text = getString(R.string.model_download_speed, speed)
                            }
                        }
                    }
                }
            )

            withContext(Dispatchers.Main) {
                isDownloading = false
                if (dialogDismissed) return@withContext

                dialog.dismiss()
                if (result.isSuccess) {
                    Toast.makeText(requireContext(), R.string.model_downloaded, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.manga_ocr_ctc_download_failed, result.exceptionOrNull()?.message),
                        Toast.LENGTH_LONG
                    ).show()
                }
                updateCtcStatus()
            }
        }
    }

    private fun showDeleteConfirmDialog() {
        AlertDialog.Builder(requireContext())
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
                    Toast.makeText(requireContext(), R.string.delete_download_failed, Toast.LENGTH_LONG).show()
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