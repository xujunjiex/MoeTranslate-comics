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
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
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
import java.io.File


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

        binding.cachesize.text = getCacheSize()
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
        binding.cleanBtn.setOnClickListener {
            showClearCacheDialog()
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
        val logs = LogCollector.getFormattedLogs()
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_log_viewer, null)
        val logContent = dialogView.findViewById<TextView>(R.id.log_content)
        val logCount = dialogView.findViewById<TextView>(R.id.log_count)

        if (logs.isEmpty()) {
            logContent.text = getString(R.string.log_empty)
            logCount.text = getString(R.string.log_count_format, 0)
        } else {
            logContent.text = logs
            logCount.text = getString(R.string.log_count_format, LogCollector.getAllLogs().size)
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

    private fun showClearCacheDialog(){
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.clear_cache)
            .setMessage(R.string.cache_content)
            .setCancelable(false)
            .setPositiveButton(R.string.clear_cache) { _, _ ->
                val success = clearCache()
                if(success){
                    UiUtils.showToast(requireContext(), getString(R.string.clear_cache_success), isShort = false)
                }else{
                    UiUtils.showToast(requireContext(), getString(R.string.clear_cache_failed), isShort = false)
                }
                binding.cachesize.text = getCacheSize()
            }
            .setNegativeButton(R.string.keep_cache) { _, _ ->}
            .create()
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
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

    private fun getCacheSize(): String {
        val cacheDir = File(requireContext().externalCacheDir, "screenshots")

        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
            return "0.00KB"
        }

        var size = 0L
        cacheDir.listFiles()?.forEach { file ->
            if (file.isFile) {
                size += file.length()
            }
        }

        return when {
            size < 1024 * 1024 -> String.format("%.2fKB", size / 1024.0)
            else -> String.format("%.2fMB", size / (1024.0 * 1024.0))
        }
    }

    private fun clearCache(): Boolean {
        val cacheDir = File(requireContext().externalCacheDir, "screenshots")

        if (!cacheDir.exists()) {
            return true
        }

        var success = true
        cacheDir.listFiles()?.forEach { file ->
            if (file.isFile && !file.delete()) {
                success = false
            }
        }

        return success
    }

}