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

package com.moe.moetranslator.translate

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import com.moe.moetranslator.utils.LogCollector
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.moe.moetranslator.launch.FirstLaunchPage
import com.moe.moetranslator.manga.MangaFloatingService
import com.moe.moetranslator.me.AboutMe
import com.moe.moetranslator.utils.UpdateChecker
import com.moe.moetranslator.R
import com.moe.moetranslator.databinding.FragmentTranslateBinding
import com.moe.moetranslator.me.ConfigurationStorage
import com.moe.moetranslator.me.ManageActivity
import com.moe.moetranslator.utils.Constants
import com.moe.moetranslator.utils.CustomPreference
import com.moe.moetranslator.utils.NotificationChecker
import com.moe.moetranslator.utils.NotificationResult
import com.moe.moetranslator.utils.ServiceUtils
import com.moe.moetranslator.utils.UiUtils
import com.moe.moetranslator.utils.UpdateResult
import kotlinx.coroutines.launch
import java.io.File

val TAG = "TranslateFragment"

class TranslateFragment : Fragment() {
    private lateinit var binding: FragmentTranslateBinding
    private lateinit var updateChecker: UpdateChecker
    private lateinit var notificationChecker: NotificationChecker
    private lateinit var prefs: CustomPreference
    private lateinit var serviceStopReceiver: BroadcastReceiver
    private lateinit var mangaServiceStopReceiver: BroadcastReceiver

    companion object {
        private var updateCheckedThisSession = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 初始化广播接收器
        serviceStopReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == BroadcastAction.ACTION_FLOATING_BALL_SERVICE_STOPPED) {
                    setTitleAndButton(false)
                }
            }
        }

        mangaServiceStopReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == BroadcastAction.ACTION_MANGA_SERVICE_STOPPED) {
                    setMangaButtonState(false)
                }
            }
        }

        // 创建文件夹
        val modelDir = File(requireContext().getExternalFilesDir(null), "models")
        if (!modelDir.exists()) {
            modelDir.mkdirs()
        }

        prefs = CustomPreference.getInstance(requireContext())
        updateChecker = UpdateChecker(requireContext())
        notificationChecker = NotificationChecker()
    }

    override fun onStart() {
        super.onStart()

        LogCollector.d(TAG, "onStart")

        // 注册广播接收器
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(
            serviceStopReceiver,
            IntentFilter(BroadcastAction.ACTION_FLOATING_BALL_SERVICE_STOPPED)
        )
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(
            mangaServiceStopReceiver,
            IntentFilter(BroadcastAction.ACTION_MANGA_SERVICE_STOPPED)
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentTranslateBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // 显示版本号
        binding.versionText.text = "v${com.moe.moetranslator.BuildConfig.VERSION_NAME}"

        if (!updateCheckedThisSession) {
            updateCheckedThisSession = true
            checkForUpdate()
        }
        checkNotification()
        showAPIName()
        setTitleAndButton(ServiceUtils.isServiceRunning(requireContext(), FloatingBallService::class.java))
        setMangaButtonState(ServiceUtils.isServiceRunning(requireContext(), MangaFloatingService::class.java))

        val helpIntent = Intent(requireContext(), FirstLaunchPage::class.java)
        binding.help.setOnClickListener {
            startActivity(helpIntent)
        }

        binding.notice.setOnClickListener {
            UiUtils.showToast(requireContext(), getString(R.string.getting_notification), isShort = false)
            checkNotification(true)
        }

        binding.selectedAPI.setOnClickListener {
            UiUtils.showToast(requireContext(), getString(R.string.more_api), isShort = false)
        }

        binding.startButton.setOnClickListener {
            if (!ServiceUtils.isServiceRunning(requireContext(), FloatingBallService::class.java)) {
                // Stop manga translation if running
                if (ServiceUtils.isServiceRunning(requireContext(), MangaFloatingService::class.java)) {
                    MangaFloatingService.stop(requireContext())
                    setMangaButtonState(false)
                }
                if (checkAndroidSDK() && checkAccessibilityService() && checkFloatingBall() && checkNotify() && checkTranslateAPI() && checkCombination()) {
                    if ((prefs.getInt("Translate_Mode", Constants.TranslateMode.TEXT.id) == Constants.TranslateMode.TEXT.id) && (prefs.getInt(
                            "Text_API",
                            Constants.TextApi.BING.id
                        ) == Constants.TextApi.AI.id) && (prefs.getInt("Text_AI", Constants.TextAI.MLKIT.id) == Constants.TextAI.NLLB.id)
                    ) {
                        checkRAM()
                    }
                    launchFloatingBallService()
                }
            } else {
                stopFloatingBallService()
            }
        }

        // 漫画翻译按钮
        binding.mangaButton.setOnClickListener {
            if (!ServiceUtils.isServiceRunning(requireContext(), MangaFloatingService::class.java)) {
                // Stop normal translation if running
                if (ServiceUtils.isServiceRunning(requireContext(), FloatingBallService::class.java)) {
                    stopFloatingBallService()
                }
                if (checkAndroidSDK() && checkAccessibilityService() && checkFloatingBall()) {
                    MangaFloatingService.start(requireContext())
                    UiUtils.showToast(requireContext(), "漫画翻译已启动", isShort = false)
                    setMangaButtonState(true)
                }
            } else {
                MangaFloatingService.stop(requireContext())
                UiUtils.showToast(requireContext(), "漫画翻译已停止", isShort = false)
                setMangaButtonState(false)
            }
        }

        if ((prefs.getInt("Translate_Mode", Constants.TranslateMode.TEXT.id) == Constants.TranslateMode.TEXT.id) && (prefs.getInt("Text_API", Constants.TextApi.BING.id) == Constants.TextApi.CUSTOM_TEXT.id)) {
            binding.SourceLanguageName.text =
                CustomLocale.getInstance(prefs.getString("Source_Language", "ja")).getDisplayName()
            binding.TargetLanguageName.text = getString(R.string.custom_api_select_language)
        } else if ((prefs.getInt("Translate_Mode", Constants.TranslateMode.TEXT.id) == Constants.TranslateMode.PIC.id) && (prefs.getInt("Pic_API", Constants.PicApi.BAIDU.id) == Constants.PicApi.CUSTOM_PIC.id)) {
            binding.SourceLanguageName.text = getString(R.string.custom_api_select_language)
            binding.TargetLanguageName.text = getString(R.string.custom_api_select_language)
        } else {
            binding.SourceLanguageName.text =
                CustomLocale.getInstance(prefs.getString("Source_Language", "ja")).getDisplayName()
            binding.TargetLanguageName.text =
                CustomLocale.getInstance(prefs.getString("Target_Language", "zh")).getDisplayName()
        }

        binding.oriLanguage.setOnClickListener {
            showLanguageListDialog(1)
        }

        binding.tarLanguage.setOnClickListener {
            showLanguageListDialog(2)
        }
    }

    private fun checkForUpdate() {
        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = updateChecker.checkForUpdate()) {
                is UpdateResult.UpdateAvailable -> {
                    LogCollector.d(TAG, "Update available: ${result.versionName}")
                    if (prefs.getLong("Ignore_Version", 0) != result.versionCode) showUpdateDialog(
                        result
                    )
                }

                is UpdateResult.NoUpdate -> {
                    LogCollector.d(TAG, "Already latest version")
                }

                is UpdateResult.Error -> {
                    LogCollector.e(TAG, "Update check failed (network error)")
                }
            }
        }
    }

    private fun checkNotification(userGet: Boolean = false) {
        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = notificationChecker.checkNotification()) {
                is NotificationResult.NotificationAvailable -> {
                    val readNotice = prefs.getLong("Read_Notice", 0)
                    LogCollector.d(TAG, "Notification available: code=${result.notificationCode}, readNotice=$readNotice, userGet=$userGet")
                    if (readNotice != result.notificationCode || userGet) {
                        showNotificationDialog(result)
                    } else {
                        LogCollector.d(TAG, "Notification already read, skip")
                    }
                }

                is NotificationResult.Error -> {
                    LogCollector.e(TAG, "Notification check failed")
                    if (userGet) UiUtils.showToast(requireContext(), getString(R.string.get_notification_error), isShort = false)
                }
            }
        }
    }

    private fun checkCombination(): Boolean =
        if (prefs.getString("Source_Language", "ja") == prefs.getString("Target_Language", "zh")) {
            UiUtils.showToast(requireContext(), getString(R.string.invalid_combination), isShort = false)
            false
        } else {
            true
        }

    private fun showAPIName() {
        val translateMode = prefs.getInt("Translate_Mode", Constants.TranslateMode.TEXT.id)
        val textApi = prefs.getInt("Text_API", Constants.TextApi.BING.id)
        val textAi = prefs.getInt("Text_AI", Constants.TextAI.MLKIT.id)
        val picApi = prefs.getInt("Pic_API", Constants.PicApi.BAIDU.id)
        val customTextApi = prefs.getInt("Custom_Text_API", 0)
        val customPicApi = prefs.getInt("Custom_Pic_API", 0)

        LogCollector.d(
            TAG,
            "translatemode$translateMode，textapi:$textApi，textAI:$textAi，picapi:$picApi，customtextapi:$customTextApi，custompicapi:$customPicApi"
        )

        when {
            translateMode == Constants.TranslateMode.TEXT.id -> when (textApi) {
                Constants.TextApi.AI.id -> {
                    if (textAi == Constants.TextAI.MLKIT.id) {
                        binding.selectedAPI.text = getString(
                            R.string.api_name,
                            getString(R.string.mlkit_name)
                        ) + "（${getString(R.string.ocr)}）"
                    } else {
                        binding.selectedAPI.text = getString(
                            R.string.api_name,
                            getString(R.string.nllb_name)
                        ) + "（${getString(R.string.ocr)}）"
                    }
                }

                Constants.TextApi.BING.id -> {
                    binding.selectedAPI.text = getString(
                        R.string.api_name,
                        getString(R.string.bingapi_name)
                    ) + "（${getString(R.string.ocr)}）"
                }

                Constants.TextApi.NIUTRANS.id -> {
                    binding.selectedAPI.text = getString(
                        R.string.api_name,
                        getString(R.string.niuapi_name)
                    ) + "（${getString(R.string.ocr)}）"
                }

                Constants.TextApi.OPENAI.id -> {
                    val providerList = ConfigurationStorage.loadAllProviders(prefs)
                    val selectedProvider = prefs.getInt("OpenAI_Selected_Provider", 0)
                    val name = if (selectedProvider < providerList.size) providerList[selectedProvider].name else getString(R.string.uniaiapi_name)
                    binding.selectedAPI.text = getString(R.string.api_name, name) + "（${getString(R.string.ocr)}）"
                }

                Constants.TextApi.VOLC.id -> {
                    binding.selectedAPI.text = getString(
                        R.string.api_name,
                        getString(R.string.volcapi_name)
                    ) + "（${getString(R.string.ocr)}）"
                }

                Constants.TextApi.AZURE.id -> {
                    binding.selectedAPI.text = getString(
                        R.string.api_name,
                        getString(R.string.azureapi_name)
                    ) + "（${getString(R.string.ocr)}）"
                }

                Constants.TextApi.DEEPL.id -> {
                    binding.selectedAPI.text = getString(
                        R.string.api_name,
                        getString(R.string.deeplapi_name)
                    ) + "（${getString(R.string.ocr)}）"
                }

                Constants.TextApi.BAIDU.id -> {
                    binding.selectedAPI.text = getString(
                        R.string.api_name,
                        getString(R.string.baiduapi_name)
                    ) + "（${getString(R.string.ocr)}）"
                }

                Constants.TextApi.TENCENT.id -> {
                    binding.selectedAPI.text = getString(
                        R.string.api_name,
                        getString(R.string.tencentapi_name)
                    ) + "（${getString(R.string.ocr)}）"
                }

                else -> {
                    val apiList = ConfigurationStorage.loadTextConfigList(prefs)
                    val name = if (customTextApi < apiList.size) apiList[customTextApi].name else getString(R.string.custom)
                    binding.selectedAPI.text = getString(R.string.api_name, name) + "（${getString(R.string.ocr)}）"
                }
            }

            else -> when (picApi) {
                Constants.PicApi.BAIDU.id -> {
                    binding.selectedAPI.text = getString(
                        R.string.api_name,
                        getString(R.string.baiduapi_name)
                    ) + "（${getString(R.string.pic)}）"
                }

                Constants.PicApi.TENCENT.id -> {
                    binding.selectedAPI.text = getString(
                        R.string.api_name,
                        getString(R.string.tencentapi_name)
                    ) + "（${getString(R.string.pic)}）"
                }

                else -> {
                    val apiList = ConfigurationStorage.loadPicConfigList(prefs)
                    val name = if (customPicApi < apiList.size) apiList[customPicApi].name else getString(R.string.custom)
                    binding.selectedAPI.text = getString(R.string.api_name, name) + "（${getString(R.string.pic)}）"
                }
            }
        }
    }

    private fun setTitleAndButton(isRunning: Boolean) {
        if (!isRunning) {
            binding.welcomeTitle.text = getString(R.string.welcome_home_title)
            binding.welcomeSubtitle.text = getString(R.string.welcome_home_subtitle)
            binding.startButton.text = getString(R.string.start_ball)
            binding.startButton.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.primary))
        } else {
            binding.welcomeTitle.text = getString(R.string.welcome_home_title_2)
            binding.welcomeSubtitle.text = getString(R.string.welcome_home_subtitle_2)
            binding.startButton.text = getString(R.string.stop_ball)
            binding.startButton.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.red))
        }
    }

    private fun setMangaButtonState(isRunning: Boolean) {
        if (isRunning) {
            binding.mangaButton.text = "停止漫画翻译"
            binding.mangaButton.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.red))
        } else {
            binding.mangaButton.text = "漫画翻译"
            binding.mangaButton.backgroundTintList =
                ColorStateList.valueOf(Color.parseColor("#6200EE"))
        }
    }

    private fun checkAndroidSDK(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            val dialog = AlertDialog.Builder(requireContext())
                .setTitle(R.string.android_sdk_old_title)
                .setMessage(R.string.android_sdk_old_content)
                .setCancelable(false)
                .setPositiveButton(R.string.user_known, null)
                .create()
            dialog.show()
            dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
            return false
        } else {
            return true
        }
    }

    private fun checkTranslateAPI(): Boolean {
        val translateMode = prefs.getInt("Translate_Mode", Constants.TranslateMode.TEXT.id)
        val textApi = prefs.getInt("Text_API", Constants.TextApi.BING.id)
        val textAi = prefs.getInt("Text_AI", Constants.TextAI.MLKIT.id)
        val picApi = prefs.getInt("Pic_API", Constants.PicApi.BAIDU.id)
        val customTextApi = prefs.getInt("Custom_Text_API", 0)
        val customPicApi = prefs.getInt("Custom_Pic_API", 0)

        val ret: Boolean = when {
            translateMode == Constants.TranslateMode.TEXT.id -> when (textApi) {
                Constants.TextApi.AI.id -> {
                    if ((textAi == Constants.TextAI.MLKIT.id) && (!(prefs.getBoolean("Download_MLKit", false)))) {
                        LogCollector.d(
                            TAG,
                            "Download_MLKit" + prefs.getBoolean("Download_MLKit", false).toString()
                        )
                        val dialog = AlertDialog.Builder(requireContext())
                            .setTitle(R.string.mlkit_not_download_title)
                            .setMessage(R.string.mlkit_not_download_content)
                            .setCancelable(false)
                            .setPositiveButton(R.string.go_to_download) { _, _ ->
                                val intent =
                                    Intent(requireContext(), ManageActivity::class.java).apply {
                                        putExtra(
                                            ManageActivity.EXTRA_FRAGMENT_TYPE,
                                            ManageActivity.TYPE_FRAGMENT_MANAGE_MLKIT
                                        )
                                    }
                                startActivity(intent)
                            }
                            .setNegativeButton(R.string.user_cancel, null)
                            .create()
                        dialog.show()
                        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
                        false
                    } else if ((textAi == Constants.TextAI.NLLB.id) && (!(prefs.getBoolean("Download_NLLB", false)))) {
                        LogCollector.d(
                            TAG,
                            "Download_NLLB" + prefs.getBoolean("Download_NLLB", false).toString()
                        )
                        val dialog = AlertDialog.Builder(requireContext())
                            .setTitle(R.string.nllb_not_download_title)
                            .setMessage(R.string.nllb_not_download_content)
                            .setCancelable(false)
                            .setPositiveButton(R.string.go_to_download) { _, _ ->
                                val intent =
                                    Intent(requireContext(), ManageActivity::class.java).apply {
                                        putExtra(
                                            ManageActivity.EXTRA_FRAGMENT_TYPE,
                                            ManageActivity.TYPE_FRAGMENT_MANAGE_NLLB
                                        )
                                    }
                                startActivity(intent)
                            }
                            .setNegativeButton(R.string.user_cancel, null)
                            .create()
                        dialog.show()
                        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
                        false
                    } else {
                        true
                    }
                }

                Constants.TextApi.BING.id -> {
                    true
                }

                Constants.TextApi.NIUTRANS.id -> {
                    if (prefs.getString("Niutrans_EncryptedKey", "") == "") {
                        val dialog = AlertDialog.Builder(requireContext())
                            .setTitle(R.string.api_not_config_title)
                            .setMessage(
                                getString(
                                    R.string.api_not_config_content,
                                    getString(R.string.niuapi_name)
                                )
                            )
                            .setCancelable(false)
                            .setPositiveButton(R.string.go_to_config) { _, _ ->
                                val intent =
                                    Intent(requireContext(), ManageActivity::class.java).apply {
                                        putExtra(
                                            ManageActivity.EXTRA_FRAGMENT_TYPE,
                                            ManageActivity.TYPE_FRAGMENT_MANAGE_NIU_API
                                        )
                                    }
                                startActivity(intent)
                            }
                            .setNegativeButton(R.string.user_cancel, null)
                            .create()
                        dialog.show()
                        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
                        false
                    } else {
                        true
                    }
                }

                Constants.TextApi.OPENAI.id -> {
                    val providerList = ConfigurationStorage.loadAllProviders(prefs)
                    val selectedProvider = prefs.getInt("OpenAI_Selected_Provider", 0)
                    if (providerList.isEmpty() || selectedProvider >= providerList.size) {
                        val dialog = AlertDialog.Builder(requireContext())
                            .setTitle(R.string.api_not_config_title)
                            .setMessage(R.string.custom_api_not_config_content)
                            .setCancelable(false)
                            .setPositiveButton(R.string.go_to_config) { _, _ ->
                                val intent = Intent(requireContext(), ManageActivity::class.java).apply {
                                    putExtra(ManageActivity.EXTRA_FRAGMENT_TYPE, ManageActivity.TYPE_FRAGMENT_MANAGE_OPENAI_API)
                                    putExtra(ManageActivity.EXTRA_CUSTOM_CODE, 0)
                                    putExtra(ManageActivity.EXTRA_IS_NEW, true)
                                }
                                startActivity(intent)
                            }
                            .setNegativeButton(R.string.user_cancel, null)
                            .create()
                        dialog.show()
                        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
                        false
                    } else {
                        true
                    }
                }

                Constants.TextApi.VOLC.id -> {
                    if (prefs.getString("Volc_ACCOUNT_EncryptedKey", "") == "") {
                        val dialog = AlertDialog.Builder(requireContext())
                            .setTitle(R.string.api_not_config_title)
                            .setMessage(
                                getString(
                                    R.string.api_not_config_content,
                                    getString(R.string.volcapi_name)
                                )
                            )
                            .setCancelable(false)
                            .setPositiveButton(R.string.go_to_config) { _, _ ->
                                val intent =
                                    Intent(requireContext(), ManageActivity::class.java).apply {
                                        putExtra(
                                            ManageActivity.EXTRA_FRAGMENT_TYPE,
                                            ManageActivity.TYPE_FRAGMENT_MANAGE_VOLC_API
                                        )
                                    }
                                startActivity(intent)
                            }
                            .setNegativeButton(R.string.user_cancel, null)
                            .create()
                        dialog.show()
                        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
                        false
                    } else {
                        true
                    }
                }

                Constants.TextApi.AZURE.id -> {
                    if (prefs.getString("Azure_EncryptedKey", "") == "") {
                        val dialog = AlertDialog.Builder(requireContext())
                            .setTitle(R.string.api_not_config_title)
                            .setMessage(
                                getString(
                                    R.string.api_not_config_content,
                                    getString(R.string.azureapi_name)
                                )
                            )
                            .setCancelable(false)
                            .setPositiveButton(R.string.go_to_config) { _, _ ->
                                val intent =
                                    Intent(requireContext(), ManageActivity::class.java).apply {
                                        putExtra(
                                            ManageActivity.EXTRA_FRAGMENT_TYPE,
                                            ManageActivity.TYPE_FRAGMENT_MANAGE_AZURE_API
                                        )
                                    }
                                startActivity(intent)
                            }
                            .setNegativeButton(R.string.user_cancel, null)
                            .create()
                        dialog.show()
                        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
                        false
                    } else {
                        true
                    }
                }

                Constants.TextApi.DEEPL.id -> {
                    if (prefs.getString("DeepL_Translate_APIKEY_EncryptedKey", "") == "") {
                        val dialog = AlertDialog.Builder(requireContext())
                            .setTitle(R.string.api_not_config_title)
                            .setMessage(
                                getString(
                                    R.string.api_not_config_content,
                                    getString(R.string.deeplapi_name)
                                )
                            )
                            .setCancelable(false)
                            .setPositiveButton(R.string.go_to_config) { _, _ ->
                                val intent =
                                    Intent(requireContext(), ManageActivity::class.java).apply {
                                        putExtra(
                                            ManageActivity.EXTRA_FRAGMENT_TYPE,
                                            ManageActivity.TYPE_FRAGMENT_MANAGE_DEEPL_API
                                        )
                                    }
                                startActivity(intent)
                            }
                            .setNegativeButton(R.string.user_cancel, null)
                            .create()
                        dialog.show()
                        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
                        false
                    } else {
                        true
                    }
                }

                Constants.TextApi.BAIDU.id -> {
                    if (prefs.getString("Baidu_Translate_ACCOUNT_EncryptedKey", "") == "") {
                        val dialog = AlertDialog.Builder(requireContext())
                            .setTitle(R.string.api_not_config_title)
                            .setMessage(
                                getString(
                                    R.string.api_not_config_content,
                                    getString(R.string.baiduapi_name)
                                )
                            )
                            .setCancelable(false)
                            .setPositiveButton(R.string.go_to_config) { _, _ ->
                                val intent =
                                    Intent(requireContext(), ManageActivity::class.java).apply {
                                        putExtra(
                                            ManageActivity.EXTRA_FRAGMENT_TYPE,
                                            ManageActivity.TYPE_FRAGMENT_MANAGE_BAIDU_API
                                        )
                                    }
                                startActivity(intent)
                            }
                            .setNegativeButton(R.string.user_cancel, null)
                            .create()
                        dialog.show()
                        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
                        false
                    } else {
                        true
                    }
                }

                Constants.TextApi.TENCENT.id -> {
                    if (prefs.getString("Tencent_Cloud_ACCOUNT_EncryptedKey", "") == "") {
                        val dialog = AlertDialog.Builder(requireContext())
                            .setTitle(R.string.api_not_config_title)
                            .setMessage(
                                getString(
                                    R.string.api_not_config_content,
                                    getString(R.string.tencentapi_name)
                                )
                            )
                            .setCancelable(false)
                            .setPositiveButton(R.string.go_to_config) { _, _ ->
                                val intent =
                                    Intent(requireContext(), ManageActivity::class.java).apply {
                                        putExtra(
                                            ManageActivity.EXTRA_FRAGMENT_TYPE,
                                            ManageActivity.TYPE_FRAGMENT_MANAGE_TENCENT_API
                                        )
                                    }
                                startActivity(intent)
                            }
                            .setNegativeButton(R.string.user_cancel, null)
                            .create()
                        dialog.show()
                        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
                        false
                    } else {
                        true
                    }
                }

                else -> {
                    val apiList = ConfigurationStorage.loadTextConfigList(prefs)
                    val selectedIndex = prefs.getInt("Custom_Text_API", 0)
                    if (apiList.isEmpty() || selectedIndex >= apiList.size) {
                        val dialog = AlertDialog.Builder(requireContext())
                            .setTitle(R.string.api_not_config_title)
                            .setMessage(R.string.custom_api_not_config_content)
                            .setCancelable(false)
                            .setPositiveButton(R.string.go_to_config) { _, _ ->
                                val intent = Intent(requireContext(), ManageActivity::class.java).apply {
                                    putExtra(ManageActivity.EXTRA_FRAGMENT_TYPE, ManageActivity.TYPE_FRAGMENT_MANAGE_CUSTOM_TEXT_API)
                                    putExtra(ManageActivity.EXTRA_CUSTOM_CODE, 0)
                                    putExtra(ManageActivity.EXTRA_IS_NEW, true)
                                }
                                startActivity(intent)
                            }
                            .setNegativeButton(R.string.user_cancel, null)
                            .create()
                        dialog.show()
                        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
                        false
                    } else {
                        true
                    }
                }
            }

            else -> when (picApi) {
                Constants.PicApi.BAIDU.id -> {
                    if (prefs.getString("Baidu_Translate_ACCOUNT_EncryptedKey", "") == "") {
                        val dialog = AlertDialog.Builder(requireContext())
                            .setTitle(R.string.api_not_config_title)
                            .setMessage(
                                getString(
                                    R.string.api_not_config_content,
                                    getString(R.string.baiduapi_name)
                                )
                            )
                            .setCancelable(false)
                            .setPositiveButton(R.string.go_to_config) { _, _ ->
                                val intent =
                                    Intent(requireContext(), ManageActivity::class.java).apply {
                                        putExtra(
                                            ManageActivity.EXTRA_FRAGMENT_TYPE,
                                            ManageActivity.TYPE_FRAGMENT_MANAGE_BAIDU_API
                                        )
                                    }
                                startActivity(intent)
                            }
                            .setNegativeButton(R.string.user_cancel, null)
                            .create()
                        dialog.show()
                        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
                        false
                    } else {
                        true
                    }
                }

                Constants.PicApi.TENCENT.id -> {
                    if (prefs.getString("Tencent_Cloud_ACCOUNT_EncryptedKey", "") == "") {
                        val dialog = AlertDialog.Builder(requireContext())
                            .setTitle(R.string.api_not_config_title)
                            .setMessage(
                                getString(
                                    R.string.api_not_config_content,
                                    getString(R.string.tencentapi_name)
                                )
                            )
                            .setCancelable(false)
                            .setPositiveButton(R.string.go_to_config) { _, _ ->
                                val intent =
                                    Intent(requireContext(), ManageActivity::class.java).apply {
                                        putExtra(
                                            ManageActivity.EXTRA_FRAGMENT_TYPE,
                                            ManageActivity.TYPE_FRAGMENT_MANAGE_TENCENT_API
                                        )
                                    }
                                startActivity(intent)
                            }
                            .setNegativeButton(R.string.user_cancel, null)
                            .create()
                        dialog.show()
                        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
                        false
                    } else {
                        true
                    }
                }

                else -> {
                    val apiList = ConfigurationStorage.loadPicConfigList(prefs)
                    val selectedIndex = prefs.getInt("Custom_Pic_API", 0)
                    if (apiList.isEmpty() || selectedIndex >= apiList.size) {
                        val dialog = AlertDialog.Builder(requireContext())
                            .setTitle(R.string.api_not_config_title)
                            .setMessage(R.string.custom_api_not_config_content)
                            .setCancelable(false)
                            .setPositiveButton(R.string.go_to_config) { _, _ ->
                                val intent = Intent(requireContext(), ManageActivity::class.java).apply {
                                    putExtra(ManageActivity.EXTRA_FRAGMENT_TYPE, ManageActivity.TYPE_FRAGMENT_MANAGE_CUSTOM_PIC_API)
                                    putExtra(ManageActivity.EXTRA_CUSTOM_CODE, 0)
                                    putExtra(ManageActivity.EXTRA_IS_NEW, true)
                                }
                                startActivity(intent)
                            }
                            .setNegativeButton(R.string.user_cancel, null)
                            .create()
                        dialog.show()
                        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
                        false
                    } else {
                        true
                    }
                }
            }
        }
        return ret
    }

    private fun checkAccessibilityService(): Boolean {
        val accessibilityManager =
            requireContext().getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices =
            accessibilityManager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)

//        Log.d(TAG, accessibilityManager.isEnabled.toString())

        val expectedServiceId =
            "${requireContext().packageName}/.translate.ScreenShotAccessibilityService"
        val ret = enabledServices.any { it.id == expectedServiceId }
        if (!ret) {
            val dialog = AlertDialog.Builder(requireContext())
                .setTitle(R.string.not_accessibility_service_title)
                .setMessage(R.string.not_accessibility_service_content)
                .setPositiveButton(R.string.go_to_grant) { _, _ ->
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    startActivity(intent)
                }
                .setNegativeButton(R.string.user_cancel, null)
                .setCancelable(false)
                .create()
            dialog.show()
            dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
        }
        return ret
    }

    private fun checkFloatingBall(): Boolean {
        // 检测是否有悬浮窗权限
        val ret = Settings.canDrawOverlays(requireContext())
        if (!ret) {
            val dialog = AlertDialog.Builder(requireContext())
                .setTitle(R.string.not_floating_title)
                .setMessage(R.string.not_floating_content)
                .setPositiveButton(R.string.go_to_grant) { _, _ ->
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${requireContext().packageName}")
                    )
                    startActivity(intent)
                }
                .setNegativeButton(R.string.user_cancel, null)
                .setCancelable(false)
                .create()
            dialog.show()
            dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
        }
        return ret
    }

    private fun checkNotify(): Boolean {
        val notificationManager =
            requireContext().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ret = notificationManager.areNotificationsEnabled()
        if (!ret) {
            val dialog = AlertDialog.Builder(requireContext())
                .setTitle(R.string.not_notify_title)
                .setMessage(R.string.not_notify_content)
                .setPositiveButton(R.string.go_to_grant) { _, _ ->
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(intent)
                }
                .setNegativeButton(R.string.user_cancel, null)
                .setCancelable(false)
                .create()
            dialog.show()
            dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
        }
        return ret
    }

    private fun checkRAM() {
        val activityManager =
            requireContext().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)

        val totalMemoryGB = memoryInfo.totalMem / (1024 * 1024 * 1024.0)

        if (totalMemoryGB < 6) {
            val dialog = AlertDialog.Builder(requireContext())
                .setTitle(R.string.small_ram_title)
                .setMessage(R.string.small_ram_content)
                .setCancelable(false)
                .setPositiveButton(R.string.user_known, null)
                .create()
            dialog.show()
            dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
        }
    }

    private fun showUpdateDialog(update: UpdateResult.UpdateAvailable) {
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.find_new_version)
            .setMessage(
                getString(R.string.version_name) + update.versionName + "\n${update.versionDescription}\n" + getString(
                    R.string.update_prompt
                )
            )
            .setCancelable(false)
            .setNeutralButton(R.string.ignore_update) { _, _ ->
                prefs.setLong("Ignore_Version", update.versionCode)
            }
            .setPositiveButton(R.string.go_to_update) { _, _ ->
                // 通过底部导航切换到"关于"页面，并标记自动检查更新
                prefs.setBoolean(AboutMe.ARG_AUTO_CHECK_UPDATE, true)
                requireActivity().findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNavigation).selectedItemId = R.id.me_fragment
            }
            .setNegativeButton(R.string.not_update, null)
            .create()
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
    }

    private fun showNotificationDialog(notification: NotificationResult.NotificationAvailable) {
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(notification.notificationName)
            .setMessage(notification.notificationContent)
            .setCancelable(false)
            .setPositiveButton(R.string.user_known) { _, _ ->
                prefs.setLong("Read_Notice", notification.notificationCode)
            }
            .create()
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
    }

    private fun showLanguageListDialog(type: Int) {
        if (((prefs.getInt("Translate_Mode", Constants.TranslateMode.TEXT.id) == Constants.TranslateMode.TEXT.id) && (prefs.getInt(
                "Text_API",
                Constants.TextApi.BING.id
            ) == Constants.TextApi.CUSTOM_TEXT.id) && (type == 2)) || ((prefs.getInt(
                "Translate_Mode",
                Constants.TranslateMode.TEXT.id
            ) == Constants.TranslateMode.PIC.id) && (prefs.getInt("Pic_API", Constants.PicApi.BAIDU.id) == Constants.PicApi.CUSTOM_PIC.id))
        ) {
            val dialog = AlertDialog.Builder(requireContext())
                .setTitle(R.string.custom_api_select_language_title)
                .setMessage(R.string.custom_api_select_language_content)
                .setCancelable(false)
                .setPositiveButton(R.string.user_known, null)
                .create()
            dialog.show()
            dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
        } else {
            LogCollector.d(TAG, TranslateTools.getLanguagesList(requireContext(), type)!!.toString())
            LanguageSelectionDialog(
                requireContext(),
                type,
                TranslateTools.getLanguagesList(requireContext(), type)!!
            ) { selectedLocale ->
                if (type == 1) {
                    LogCollector.d(TAG, "Source_Language：" + selectedLocale.getOriCode())
                    prefs.setString("Source_Language", selectedLocale.getOriCode())
                    binding.SourceLanguageName.text =
                        CustomLocale.getInstance(prefs.getString("Source_Language", "ja"))
                            .getDisplayName()
                } else {
                    LogCollector.d(TAG, "Target_Language：" + selectedLocale.getOriCode())
                    prefs.setString("Target_Language", selectedLocale.getOriCode())
                    binding.TargetLanguageName.text =
                        CustomLocale.getInstance(prefs.getString("Target_Language", "zh"))
                            .getDisplayName()
                }
            }.show()
        }
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onStop() {
        super.onStop()

        LogCollector.d(TAG, "onStop")

        // 注销广播接收器
        LocalBroadcastManager.getInstance(requireContext())
            .unregisterReceiver(serviceStopReceiver)
        LocalBroadcastManager.getInstance(requireContext())
            .unregisterReceiver(mangaServiceStopReceiver)
    }

    override fun onResume() {
        super.onResume()

        LogCollector.d(TAG, "onResume")

        showAPIName()
        setTitleAndButton(ServiceUtils.isServiceRunning(requireContext(), FloatingBallService::class.java))
        setMangaButtonState(ServiceUtils.isServiceRunning(requireContext(), MangaFloatingService::class.java))
    }

    // 实际启动服务的方法
    private fun launchFloatingBallService() {
        try {
            // 检查服务是否已经在运行
            if (!ServiceUtils.isServiceRunning(requireContext(), FloatingBallService::class.java)) {
                val serviceIntent = Intent(requireContext(), FloatingBallService::class.java)
                requireContext().startService(serviceIntent)
                UiUtils.showToast(requireContext(), getString(R.string.startup_success), isShort = true)
                setTitleAndButton(true)
            } else {
                setTitleAndButton(true)
                UiUtils.showToast(requireContext(), "already running", isShort = false)
            }
        } catch (e: Exception) {
            UiUtils.showToast(requireContext(), getString(R.string.startup_failure, e.toString()), isShort = false)
        }
    }

    private fun stopFloatingBallService() {
        try {
            // 检查服务是否已经停止
            if (ServiceUtils.isServiceRunning(requireContext(), FloatingBallService::class.java)) {
                val intent = Intent(requireContext(), FloatingBallService::class.java)
                requireContext().stopService(intent)
                UiUtils.showToast(requireContext(), getString(R.string.stop_success), isShort = true)
                setTitleAndButton(false)
            } else {
                setTitleAndButton(false)
                UiUtils.showToast(requireContext(), "already stopped", isShort = false)
            }
        } catch (e: Exception) {
            UiUtils.showToast(requireContext(), getString(R.string.stop_failed, e.toString()), isShort = false)
        }
    }
}