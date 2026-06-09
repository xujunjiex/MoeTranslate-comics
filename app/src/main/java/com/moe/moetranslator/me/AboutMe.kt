/*
 * Copyright (C) 2024 murangogo
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along
 * with this library; if not, write to the Free Software Foundation, Inc.,
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA
 */

package com.moe.moetranslator.me

import android.app.ActivityManager
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.moe.moetranslator.R
import com.moe.moetranslator.databinding.FragmentAboutMeBinding
import com.moe.moetranslator.translate.FloatingBallService
import com.moe.moetranslator.manga.MangaFloatingService
import com.moe.moetranslator.utils.LogCollector
import com.moe.moetranslator.utils.ServiceUtils
import com.moe.moetranslator.utils.UiUtils
import com.moe.moetranslator.utils.UpdateChecker
import com.moe.moetranslator.utils.UpdateResult
import kotlinx.coroutines.launch


class AboutMe : Fragment() {
    private lateinit var binding: FragmentAboutMeBinding
    private lateinit var updateChecker: UpdateChecker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updateChecker = UpdateChecker(requireContext())
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = FragmentAboutMeBinding.inflate(inflater,container,false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupButton()
    }

    private fun setupButton(){
        binding.translateModeBtn.setOnClickListener{
            if (ServiceUtils.isServiceRunning(requireContext(), FloatingBallService::class.java) || ServiceUtils.isServiceRunning(requireContext(), MangaFloatingService::class.java)){
                UiUtils.showToast(requireContext(), getString(R.string.still_running), isShort = false)
            } else {
                val intent = Intent(requireContext(), SettingPageActivity::class.java)
                intent.putExtra(SettingPageActivity.EXTRA_FRAGMENT_TYPE, SettingPageActivity.TYPE_FRAGMENT_TRANSLATE_MODE)
                startActivity(intent)
            }
        }
        binding.apiConfigBtn.setOnClickListener {
            if (ServiceUtils.isServiceRunning(requireContext(), FloatingBallService::class.java) || ServiceUtils.isServiceRunning(requireContext(), MangaFloatingService::class.java)){
                UiUtils.showToast(requireContext(), getString(R.string.still_running), isShort = false)
            } else {
                val intent = Intent(requireContext(), SettingPageActivity::class.java)
                intent.putExtra(SettingPageActivity.EXTRA_FRAGMENT_TYPE, SettingPageActivity.TYPE_FRAGMENT_API_CONFIG)
                startActivity(intent)
            }
        }
        binding.personalizationBtn.setOnClickListener {
            if (ServiceUtils.isServiceRunning(requireContext(), FloatingBallService::class.java) || ServiceUtils.isServiceRunning(requireContext(), MangaFloatingService::class.java)){
                UiUtils.showToast(requireContext(), getString(R.string.still_running), isShort = false)
            } else {
                val intent = Intent(requireContext(), SettingPageActivity::class.java)
                intent.putExtra(SettingPageActivity.EXTRA_FRAGMENT_TYPE, SettingPageActivity.TYPE_FRAGMENT_PERSONALIZATION)
                startActivity(intent)
            }
        }
        binding.modelManagementBtn.setOnClickListener {
            if (ServiceUtils.isServiceRunning(requireContext(), FloatingBallService::class.java) || ServiceUtils.isServiceRunning(requireContext(), MangaFloatingService::class.java)){
                UiUtils.showToast(requireContext(), getString(R.string.still_running), isShort = false)
            } else {
                val intent = Intent(requireContext(), SettingPageActivity::class.java)
                intent.putExtra(SettingPageActivity.EXTRA_FRAGMENT_TYPE, SettingPageActivity.TYPE_FRAGMENT_MODEL_MANAGEMENT)
                startActivity(intent)
            }
        }
        binding.faqBtn.setOnClickListener {
            val intent = Intent(requireContext(), SettingPageActivity::class.java)
            intent.putExtra(SettingPageActivity.EXTRA_FRAGMENT_TYPE, SettingPageActivity.TYPE_FRAGMENT_FAQ)
            startActivity(intent)
        }
        binding.errorCodeBtn.setOnClickListener {
            val intent = Intent(requireContext(), SettingPageActivity::class.java)
            intent.putExtra(SettingPageActivity.EXTRA_FRAGMENT_TYPE, SettingPageActivity.TYPE_FRAGMENT_ERROR_CODE)
            startActivity(intent)
        }
        binding.updateBtn.setOnClickListener{
            UiUtils.showToast(requireContext(), getString(R.string.getting_update), isShort = false)
            checkForUpdate()
        }
        binding.developerBtn.setOnClickListener {
            val intent = Intent(requireContext(), SettingPageActivity::class.java)
            intent.putExtra(SettingPageActivity.EXTRA_FRAGMENT_TYPE, SettingPageActivity.TYPE_FRAGMENT_DEVELOPER)
            startActivity(intent)
        }
        binding.viewLogsBtn.setOnClickListener {
            showLogViewerDialog()
        }
    }

    private fun showLogViewerDialog() {
        val logEntries = LogCollector.getAllLogs()
        val logs = LogCollector.getFormattedLogs()
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_log_viewer, null)
        val logContent = dialogView.findViewById<TextView>(R.id.log_content)
        val logCount = dialogView.findViewById<TextView>(R.id.log_count)

        if (logs.isEmpty()) {
            logContent.text = getString(R.string.log_empty)
            logCount.text = getString(R.string.log_count_format, 0)
        } else {
            val spannable = buildLogSpannable(logEntries)
            logContent.text = spannable
            logContent.movementMethod = LinkMovementMethod.getInstance()
            logCount.text = getString(R.string.log_count_format, logEntries.size)
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.log_viewer_title)
            .setView(dialogView)
            .setNegativeButton(R.string.user_cancel, null)
            .create()

        dialogView.findViewById<View>(R.id.btn_copy).setOnClickListener {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("app_logs", logs))
            UiUtils.showToast(requireContext(), getString(R.string.log_copied), isShort = false)
        }

        dialogView.findViewById<View>(R.id.btn_clear).setOnClickListener {
            LogCollector.clear()
            logContent.text = getString(R.string.log_empty)
            logCount.text = getString(R.string.log_count_format, 0)
        }

        dialogView.findViewById<View>(R.id.btn_export).setOnClickListener {
            exportLogsToFile(logs)
        }

        dialog.show()
    }

    /**
     * 构建带样式的日志 SpannableString：
     * - E 级别日志：红色高亮，点击可复制该条错误到剪切板
     * - 其他级别：默认颜色
     */
    private fun buildLogSpannable(entries: List<LogCollector.LogEntry>): SpannableString {
        val sb = StringBuilder()
        val errorRanges = mutableListOf<Pair<Int, Int>>() // (start, end) of each error line

        for (entry in entries) {
            val line = entry.format()
            val start = sb.length
            sb.append(line).append("\n")
            if (entry.level == "E") {
                errorRanges.add(start to sb.length)
            }
        }

        val fullText = sb.toString()
        val spannable = SpannableString(fullText)

        for ((start, end) in errorRanges) {
            // 红色高亮
            spannable.setSpan(
                ForegroundColorSpan(ContextCompat.getColor(requireContext(), android.R.color.holo_red_dark)),
                start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            // 点击复制该条错误信息
            val errorText = fullText.substring(start, end).trimEnd()
            spannable.setSpan(
                object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("error_log", errorText))
                        UiUtils.showToast(requireContext(), getString(R.string.log_error_copied), isShort = true)
                    }

                    override fun updateDrawState(ds: TextPaint) {
                        ds.color = ContextCompat.getColor(requireContext(), android.R.color.holo_red_dark)
                        ds.isUnderlineText = false
                    }
                },
                start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        return spannable
    }

    private fun exportLogsToFile(logs: String) {
        try {
            val dir = java.io.File(requireContext().getExternalFilesDir(null), "logs")
            if (!dir.exists()) dir.mkdirs()
            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
                .format(java.util.Date())
            val file = java.io.File(dir, "moe_log_$timestamp.txt")
            file.writeText(logs)
            UiUtils.showToast(requireContext(), getString(R.string.log_exported, file.absolutePath), isShort = false)
        } catch (e: Exception) {
            UiUtils.showToast(requireContext(), getString(R.string.log_export_failed, e.message ?: "未知错误"), isShort = false)
        }
    }

    private fun checkForUpdate() {
        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = updateChecker.checkForUpdate()) {
                is UpdateResult.UpdateAvailable -> { showUpdateDialog(result) }
                is UpdateResult.NoUpdate -> { UiUtils.showToast(requireContext(), getString(R.string.no_update), isShort = false) }
                else -> { UiUtils.showToast(requireContext(), getString(R.string.internet_error), isShort = false) }
            }
        }
    }

    private fun showUpdateDialog(update: UpdateResult.UpdateAvailable) {
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.find_new_version)
            .setMessage(getString(R.string.version_name)+ update.versionName+"\n${update.versionDescription}\n"+getString(R.string.update_prompt))
            .setCancelable(false)
            .setPositiveButton(R.string.go_to_update) { _, _ ->
                val url = update.downloadUrl.ifEmpty { "https://github.com/xujunjiex/StarFlow/releases" }
                val intent = Intent(Intent.ACTION_VIEW)
                intent.data = Uri.parse(url)
                startActivity(intent)
            }
            .setNegativeButton(R.string.not_update, null)
            .create()
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
    }

}