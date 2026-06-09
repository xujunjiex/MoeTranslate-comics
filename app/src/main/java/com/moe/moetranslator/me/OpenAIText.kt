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

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.moe.moetranslator.R
import com.moe.moetranslator.databinding.FragmentOpenaiApiBinding
import com.moe.moetranslator.utils.CustomPreference
import com.moe.moetranslator.utils.UiUtils
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class OpenAIText :Fragment() {
    private lateinit var binding: FragmentOpenaiApiBinding
    private lateinit var prefs: CustomPreference
    private var providerIndex: Int = 0
    private var isNew = false
    private var selectedModelIndex: Int = 0
    private val defaultSystemPrompt = "你是一名专业翻译。你的任务是准确、自然地翻译给定的文本。\n具体规则如下： \n1、根据用户的要求，将文本翻译成指定的目标语言；\n2、保持原意和语气；\n3、尽可能保持格式和结构；\n4、直接返回翻译后的文本，不要有任何解释或附加内容；\n5、如果文本已经是目标语言，请按原样返回。"
    private val defaultUserPrompt = "请将下面的文本从usefromlang翻译为usetolang：\n\nusesourcetext"

    private var currentTab = 0  // 0=游戏模式, 1=漫画模式
    private var mangaSystemPrompt: String = ""
    private var mangaUserPrompt: String = ""
    private var defaultMangaSystemPrompt: String = ""
    private var defaultMangaUserPrompt: String = ""

    // 新建用户API时的漫画默认提示词
    private val fallbackMangaSystemPrompt = "你是漫画翻译引擎。只输出译文，不输出任何解释、标注、引言或附加内容。"
    private val fallbackMangaUserPrompt = "将以下漫画文本从usefromlang翻译为usetolang，逐条翻译，保持每条的[N]编号格式不变，只输出译文：\n\nusesourcetext"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = CustomPreference.getInstance(requireContext())
        arguments?.let {
            providerIndex = it.getInt("custom_code", 0)
            isNew = it.getBoolean("is_new", false)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentOpenaiApiBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupTabs()
        setupButtons()
        loadConfig()
    }

    private fun setupButtons() {
        binding.btnSave.setOnClickListener { saveConfiguration() }
        binding.btnDelete.setOnClickListener { deleteConfiguration() }
        binding.btnTest.setOnClickListener { testConnection() }
        if (isNew) {
            binding.btnDelete.visibility = View.GONE
        }
    }

    private fun setupTabs() {
        binding.promptModeTabs.addTab(binding.promptModeTabs.newTab().setText(R.string.tab_game_mode))
        binding.promptModeTabs.addTab(binding.promptModeTabs.newTab().setText(R.string.tab_manga_mode))

        binding.promptModeTabs.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab?) {
                // Save current tab's content before switching
                saveCurrentTabContent()
                currentTab = tab?.position ?: 0
                switchPromptDisplay()
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
        })
    }

    private fun saveCurrentTabContent() {
        val currentText = binding.editSystemPrompt.text.toString()
        val currentUserText = binding.editUserPrompt.text.toString()
        if (currentTab == 0) {
            // Was on game tab, these are already the "active" prompts
        } else {
            // Was on manga tab
            mangaSystemPrompt = currentText
            mangaUserPrompt = currentUserText
        }
    }

    private fun switchPromptDisplay() {
        val allProviders = ConfigurationStorage.loadAllProviders(prefs)
        if (!isNew && providerIndex < allProviders.size) {
            val provider = allProviders[providerIndex]
            if (currentTab == 0) {
                binding.editSystemPrompt.setText(provider.systemPrompt)
                binding.editUserPrompt.setText(provider.userPrompt)
            } else {
                binding.editSystemPrompt.setText(provider.mangaSystemPrompt.ifEmpty { provider.defaultMangaSystemPrompt })
                binding.editUserPrompt.setText(provider.mangaUserPrompt.ifEmpty { provider.defaultMangaUserPrompt })
            }
        } else {
            if (currentTab == 0) {
                binding.editSystemPrompt.setText(defaultSystemPrompt)
                binding.editUserPrompt.setText(defaultUserPrompt)
            } else {
                binding.editSystemPrompt.setText(defaultMangaSystemPrompt.ifEmpty { fallbackMangaSystemPrompt })
                binding.editUserPrompt.setText(defaultMangaUserPrompt.ifEmpty { fallbackMangaUserPrompt })
            }
        }
    }

    private fun saveConfiguration() {
        try {
            val allProviders = ConfigurationStorage.loadAllProviders(prefs)
            val isBuiltin = !isNew && providerIndex < allProviders.size && allProviders[providerIndex].isBuiltin

            if (isBuiltin) {
                saveBuiltinConfig(allProviders[providerIndex])
            } else {
                saveUserConfig()
            }
        } catch (e: Exception) {
            UiUtils.showToast(requireContext(), getString(R.string.failed_save_config, e.message))
        }
    }

    private fun saveBuiltinConfig(original: OpenAIProviderConfig) {
        val apiKey = binding.editApiKey.text.toString().trim()
        if (apiKey.isBlank()) {
            throw Exception(getString(R.string.fill_blank))
        }

        // Determine prompts based on current tab
        val systemPrompt: String
        val userPrompt: String
        val mangaSys: String
        val mangaUsr: String

        if (currentTab == 0) {
            // Currently on game tab - editor has game prompts
            systemPrompt = binding.editSystemPrompt.text.toString().ifBlank { original.defaultSystemPrompt }
            userPrompt = binding.editUserPrompt.text.toString().ifBlank { original.defaultUserPrompt }
            mangaSys = mangaSystemPrompt.ifBlank { original.defaultMangaSystemPrompt }
            mangaUsr = mangaUserPrompt.ifBlank { original.defaultMangaUserPrompt }
        } else {
            // Currently on manga tab - editor has manga prompts
            systemPrompt = original.systemPrompt.ifBlank { original.defaultSystemPrompt }
            userPrompt = original.userPrompt.ifBlank { original.defaultUserPrompt }
            mangaSys = binding.editSystemPrompt.text.toString().ifBlank { original.defaultMangaSystemPrompt }
            mangaUsr = binding.editUserPrompt.text.toString().ifBlank { original.defaultMangaUserPrompt }
        }

        val selectedModelIndex = this.selectedModelIndex

        // 保存到内置API修改列表
        val mods = ConfigurationStorage.loadBuiltInProviderMods(prefs).toMutableList()
        val existingIndex = mods.indexOfFirst { it.name == original.name }
        val mod = BuiltInProviderMod(
            name = original.name,
            apiKey = apiKey,
            systemPrompt = if (systemPrompt != original.defaultSystemPrompt) systemPrompt else null,
            userPrompt = if (userPrompt != original.defaultUserPrompt) userPrompt else null,
            mangaSystemPrompt = if (mangaSys != original.defaultMangaSystemPrompt) mangaSys else null,
            mangaUserPrompt = if (mangaUsr != original.defaultMangaUserPrompt) mangaUsr else null,
            selectedModelIndex = selectedModelIndex
        )
        if (existingIndex >= 0) {
            mods[existingIndex] = mod
        } else {
            mods.add(mod)
        }
        ConfigurationStorage.saveBuiltInProviderMods(prefs, mods)

        UiUtils.showToast(requireContext(), getString(R.string.save_successfully))
        requireActivity().finish()
    }

    private fun saveUserConfig() {
        val providerName = binding.editProviderName.text.toString().trim()
        if (providerName.isBlank()) {
            throw Exception(getString(R.string.custom_api_name_blank))
        }

        if (binding.editApiKey.text.toString().trim().isBlank()) {
            throw Exception(getString(R.string.fill_blank))
        }

        val normalizedUrl = UrlUtils.normalizeUrl(requireContext(), binding.editBaseUrl.text.toString())

        if (binding.editModelName.text.toString().trim().isBlank()) {
            throw Exception(getString(R.string.fill_blank))
        }

        // Determine prompts based on current tab
        val systemPrompt: String
        val userPrompt: String
        val mangaSys: String
        val mangaUsr: String

        if (currentTab == 0) {
            systemPrompt = binding.editSystemPrompt.text.toString().ifBlank { defaultSystemPrompt }
            userPrompt = binding.editUserPrompt.text.toString().ifBlank { defaultUserPrompt }
            mangaSys = mangaSystemPrompt.ifBlank { "" }
            mangaUsr = mangaUserPrompt.ifBlank { "" }
        } else {
            systemPrompt = defaultSystemPrompt
            userPrompt = defaultUserPrompt
            mangaSys = binding.editSystemPrompt.text.toString().ifBlank { "" }
            mangaUsr = binding.editUserPrompt.text.toString().ifBlank { "" }
        }

        val provider = OpenAIProviderConfig(
            name = providerName,
            apiKey = binding.editApiKey.text.toString().trim(),
            baseUrl = normalizedUrl,
            modelName = binding.editModelName.text.toString().trim(),
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            mangaSystemPrompt = mangaSys,
            mangaUserPrompt = mangaUsr
        )

        lifecycleScope.launch {
            ConfigurationStorage.saveOpenAIProviderToList(prefs, provider, providerIndex - BuiltinProviders.providers.size)
            UiUtils.showToast(requireContext(), getString(R.string.save_successfully))
            requireActivity().finish()
        }
    }

    private fun loadConfig() {
        try {
            val allProviders = ConfigurationStorage.loadAllProviders(prefs)
            if (!isNew && providerIndex < allProviders.size) {
                val provider = allProviders[providerIndex]
                binding.editProviderName.setText(provider.name)
                binding.editApiKey.setText(provider.apiKey)
                binding.editBaseUrl.setText(provider.baseUrl)
                binding.editSystemPrompt.setText(provider.systemPrompt)
                binding.editUserPrompt.setText(provider.userPrompt)

                // 加载漫画提示词
                mangaSystemPrompt = provider.mangaSystemPrompt.ifEmpty { provider.defaultMangaSystemPrompt }
                mangaUserPrompt = provider.mangaUserPrompt.ifEmpty { provider.defaultMangaUserPrompt }
                defaultMangaSystemPrompt = provider.defaultMangaSystemPrompt
                defaultMangaUserPrompt = provider.defaultMangaUserPrompt

                if (provider.isBuiltin) {
                    setupBuiltinMode(provider)
                } else {
                    setupUserMode()
                }
            } else {
                // 新建时设置默认prompt
                binding.editSystemPrompt.setText(defaultSystemPrompt)
                binding.editUserPrompt.setText(defaultUserPrompt)
                mangaSystemPrompt = ""
                mangaUserPrompt = ""
                defaultMangaSystemPrompt = fallbackMangaSystemPrompt
                defaultMangaUserPrompt = fallbackMangaUserPrompt
                setupUserMode()
            }
        } catch (e: Exception) {
            UiUtils.showToast(requireContext(), "Error loading configuration: ${e.message}")
        }
    }

    private fun setupBuiltinMode(provider: OpenAIProviderConfig) {
        // 显示提供商图标和名称（居中显示）
        binding.providerIcon.visibility = View.VISIBLE
        binding.providerIcon.setImageResource(builtinIconRes(provider.name))
        binding.providerNameText.visibility = View.VISIBLE
        binding.providerNameText.text = provider.name
        binding.providerNameInputLayout.visibility = View.GONE
        // 隐藏整个URL卡片
        binding.baseUrlCard.visibility = View.GONE

        // 显示控制台链接（不显示URL，点击跳转）
        if (provider.consoleUrl.isNotEmpty()) {
            binding.consoleLink.visibility = View.VISIBLE
            binding.consoleLink.text = "🔑 获取 API Key"
            binding.consoleLink.setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(provider.consoleUrl)))
            }
        }

        // 模型：点击弹出自定义PopupWindow选择
        binding.modelInputLayout.visibility = View.GONE
        binding.modelSpinnerLayout.visibility = View.VISIBLE
        selectedModelIndex = provider.selectedModelIndex
        binding.modelSelector.text = provider.models[selectedModelIndex]
        binding.modelSelector.setOnClickListener { anchor ->
            showModelPopup(provider.models, anchor)
        }
        binding.modelTips.visibility = View.VISIBLE

        // 重置按钮
        binding.btnResetSystemPrompt.visibility = View.VISIBLE
        binding.btnResetUserPrompt.visibility = View.VISIBLE
        binding.btnResetSystemPrompt.setOnClickListener {
            if (currentTab == 0) {
                binding.editSystemPrompt.setText(provider.defaultSystemPrompt)
            } else {
                binding.editSystemPrompt.setText(provider.defaultMangaSystemPrompt)
            }
        }
        binding.btnResetUserPrompt.setOnClickListener {
            if (currentTab == 0) {
                binding.editUserPrompt.setText(provider.defaultUserPrompt)
            } else {
                binding.editUserPrompt.setText(provider.defaultMangaUserPrompt)
            }
        }

        // 隐藏删除按钮
        binding.btnDelete.visibility = View.GONE
    }

    private fun builtinIconRes(name: String): Int = when (name) {
        "火山引擎" -> R.drawable.ic_provider_doubao
        "智谱AI" -> R.drawable.ic_provider_zhipu
        "DeepSeek" -> R.drawable.ic_provider_deepseek
        "通义千问" -> R.drawable.ic_provider_qianwen
        else -> R.drawable.ic_launcher_foreground
    }

    private fun setupUserMode() {
        // 名称和URL可编辑
        binding.editProviderName.isEnabled = true
        binding.editProviderName.alpha = 1.0f
        binding.editBaseUrl.isEnabled = true
        binding.editBaseUrl.alpha = 1.0f
        binding.providerNameText.visibility = View.GONE
        binding.providerNameInputLayout.visibility = View.VISIBLE
        binding.providerIcon.visibility = View.GONE
        binding.baseUrlCard.visibility = View.VISIBLE
        binding.builtinUrlBadge.visibility = View.GONE

        // 模型：文本输入
        binding.modelInputLayout.visibility = View.VISIBLE
        binding.modelSpinnerLayout.visibility = View.GONE
        binding.modelTips.visibility = View.VISIBLE

        // 隐藏重置按钮
        binding.btnResetSystemPrompt.visibility = View.GONE
        binding.btnResetUserPrompt.visibility = View.GONE

        // 显示删除按钮（非新建时）
        if (!isNew) {
            binding.btnDelete.visibility = View.VISIBLE
        }
    }

    private fun showModelPopup(models: List<String>, anchor: View) {
        val context = requireContext()
        val density = context.resources.displayMetrics.density

        val popupView = LayoutInflater.from(context).inflate(R.layout.popup_model_selector, null)
        val container = popupView.findViewById<android.widget.LinearLayout>(R.id.model_list_container)

        val freeModels = setOf("glm-4-flash-250414")

        fun refreshItems() {
            container.removeAllViews()
            models.forEachIndexed { index, model ->
                val isSelected = index == selectedModelIndex
                val displayName = if (model in freeModels) "$model（免费）" else model

                val item = TextView(context).apply {
                    text = displayName
                    textSize = 15f
                    setPadding((24 * density).toInt(), (14 * density).toInt(), (24 * density).toInt(), (14 * density).toInt())
                    setTextColor(if (isSelected) Color.parseColor("#55AEEA") else Color.parseColor("#333333"))
                    isClickable = true
                    isFocusable = true
                    setBackgroundResource(R.drawable.ripple_item_bg)
                    setOnClickListener {
                        selectedModelIndex = index
                        binding.modelSelector.text = model
                        popupWindow?.dismiss()
                    }
                }
                container.addView(item)

                if (index < models.size - 1) {
                    val divider = View(context).apply {
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, (1 * density).toInt()
                        ).apply {
                            marginStart = (16 * density).toInt()
                            marginEnd = (16 * density).toInt()
                        }
                        setBackgroundColor(Color.parseColor("#E8E8E8"))
                    }
                    container.addView(divider)
                }
            }
        }

        refreshItems()

        val popup = PopupWindow(popupView, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        popup.isOutsideTouchable = true
        popup.elevation = 8f * density
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.showAsDropDown(anchor, 0, (4 * density).toInt(), Gravity.START)
        popupWindow = popup
    }

    private var popupWindow: PopupWindow? = null

    private fun testConnection() {
        val apiKey = binding.editApiKey.text.toString().trim()
        if (apiKey.isBlank()) {
            UiUtils.showToast(requireContext(), getString(R.string.fill_blank))
            return
        }

        val allProviders = ConfigurationStorage.loadAllProviders(prefs)
        val isBuiltin = !isNew && providerIndex < allProviders.size && allProviders[providerIndex].isBuiltin

        val baseUrl: String
        val modelName: String
        if (isBuiltin) {
            val provider = allProviders[providerIndex]
            baseUrl = provider.baseUrl
            modelName = provider.models.getOrElse(selectedModelIndex) { provider.models[0] }
        } else {
            baseUrl = binding.editBaseUrl.text.toString().trim()
            modelName = binding.editModelName.text.toString().trim()
            if (baseUrl.isBlank() || modelName.isBlank()) {
                UiUtils.showToast(requireContext(), getString(R.string.fill_blank))
                return
            }
        }

        // 自定义加载弹窗
        val loadingDialog = showCustomDialog(getString(R.string.testing_connection), null, isCancelable = false)

        Thread {
            try {
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .build()

                val testSystemPrompt = "你是翻译引擎。只输出译文，不输出任何解释。保持原文格式。"
                val testUserPrompt = "将以下文本从日语翻译为中文，只输出译文：\n\nこんにちは、世界。今日はとても良い天気ですね。"

                val jsonBody = org.json.JSONObject().apply {
                    put("model", modelName)
                    put("messages", org.json.JSONArray().apply {
                        put(org.json.JSONObject().apply {
                            put("role", "system")
                            put("content", testSystemPrompt)
                        })
                        put(org.json.JSONObject().apply {
                            put("role", "user")
                            put("content", testUserPrompt)
                        })
                    })
                    put("max_tokens", 200)
                    put("temperature", 0.3)
                    put("stream", false)
                    put("thinking", org.json.JSONObject().apply {
                        put("type", "disabled")
                    })
                }

                val normalizedUrl = if (baseUrl.startsWith("http://") || baseUrl.startsWith("https://")) {
                    baseUrl
                } else {
                    "https://$baseUrl"
                }

                val url = if (normalizedUrl.endsWith("/")) {
                    "${normalizedUrl}chat/completions"
                } else {
                    "$normalizedUrl/chat/completions"
                }

                val request = okhttp3.Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: "Empty response"
                    activity?.runOnUiThread {
                        loadingDialog.dismiss()
                        if (response.isSuccessful) {
                            val result = try {
                                val jsonObj = org.json.JSONObject(body)
                                jsonObj.getJSONArray("choices")
                                    .getJSONObject(0)
                                    .getJSONObject("message")
                                    .getString("content")
                                    .trim()
                            } catch (e: Exception) {
                                body
                            }
                            showCustomDialog(getString(R.string.test_success), result, isCancelable = true)
                        } else {
                            showCustomDialog(getString(R.string.test_failed), "HTTP ${response.code}\n${body.take(300)}", isCancelable = true)
                        }
                    }
                }
            } catch (e: Exception) {
                activity?.runOnUiThread {
                    loadingDialog.dismiss()
                    showCustomDialog(getString(R.string.test_failed), e.message ?: "Unknown error", isCancelable = true)
                }
            }
        }.start()
    }

    private fun showCustomDialog(title: String, message: String?, isCancelable: Boolean): AlertDialog {
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(message ?: "")
            .setCancelable(isCancelable)
            .apply {
                if (isCancelable) {
                    setPositiveButton(R.string.user_known, null)
                }
            }
            .create()
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
        return dialog
    }

    private fun deleteConfiguration() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.custom_api_delete)
            .setMessage(R.string.custom_api_delete_confirm)
            .setPositiveButton(R.string.user_known) { _, _ ->
                // 用户API的索引需要减去内置API数量
                val userIndex = providerIndex - BuiltinProviders.providers.size
                ConfigurationStorage.deleteOpenAIProvider(prefs, userIndex)
                val currentIndex = prefs.getInt("OpenAI_Selected_Provider", 0)
                if (currentIndex == providerIndex) {
                    prefs.setInt("OpenAI_Selected_Provider", 0)
                } else if (currentIndex > providerIndex) {
                    prefs.setInt("OpenAI_Selected_Provider", currentIndex - 1)
                }
                UiUtils.showToast(requireContext(), getString(R.string.save_successfully))
                requireActivity().finish()
            }
            .setNegativeButton(R.string.user_cancel, null)
            .create()
            .show()
    }
}
