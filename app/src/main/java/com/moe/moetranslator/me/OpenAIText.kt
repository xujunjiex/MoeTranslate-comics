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
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.moe.moetranslator.R
import com.moe.moetranslator.databinding.FragmentOpenaiApiBinding
import com.moe.moetranslator.utils.CustomPreference
import kotlinx.coroutines.launch

class OpenAIText :Fragment() {
    private lateinit var binding: FragmentOpenaiApiBinding
    private lateinit var prefs: CustomPreference
    private var providerIndex: Int = 0
    private var isNew = false
    private val defaultSystemPrompt = "你是一名专业翻译。你的任务是准确、自然地翻译给定的文本。\n具体规则如下： \n1、根据用户的要求，将文本翻译成指定的目标语言；\n2、保持原意和语气；\n3、尽可能保持格式和结构；\n4、直接返回翻译后的文本，不要有任何解释或附加内容；\n5、如果文本已经是目标语言，请按原样返回。"
    private val defaultUserPrompt = "请将下面的文本从usefromlang翻译为usetolang：\n\nusesourcetext"

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

        setupButtons()
        loadConfig()

        if(!prefs.getBoolean("Read_OpenAI_API_Introduce", false)){
            showIntroduce()
        }
    }

    private fun showIntroduce(){
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.introduce_openai_api_title)
            .setMessage(R.string.introduce_openai_api_content)
            .setCancelable(false)
            .setPositiveButton(R.string.user_known, null)
            .setNeutralButton(R.string.introduce_not_show_again){
                    _, _ ->
                prefs.setBoolean("Read_OpenAI_API_Introduce", true)
            }
            .create()
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
    }

    private fun setupButtons() {
        binding.introduce.setOnClickListener{ showIntroduce() }
        binding.btnSave.setOnClickListener { saveConfiguration() }
        binding.btnDelete.setOnClickListener { deleteConfiguration() }
        if (isNew) {
            binding.btnDelete.visibility = View.GONE
        }
    }

    private fun saveConfiguration() {
        try{
            val providerName = binding.editProviderName.text.toString().trim()
            if (providerName.isBlank()) {
                throw Exception(getString(R.string.custom_api_name_blank))
            }

            if(binding.editApiKey.text.toString().trim().isBlank()){
                throw Exception(getString(R.string.fill_blank))
            }

            val normalizedUrl = UrlUtils.normalizeUrl(requireContext(), binding.editBaseUrl.text.toString())

            if(binding.editModelName.text.toString().trim().isBlank()){
                throw Exception(getString(R.string.fill_blank))
            }

            val systemPrompt = if (binding.editSystemPrompt.text.toString().isBlank()) {
                defaultSystemPrompt
            } else {
                binding.editSystemPrompt.text.toString()
            }

            val userPrompt = if (binding.editUserPrompt.text.toString().isBlank()) {
                defaultUserPrompt
            } else {
                binding.editUserPrompt.text.toString()
            }

            val provider = OpenAIProviderConfig(
                name = providerName,
                apiKey = binding.editApiKey.text.toString().trim(),
                baseUrl = normalizedUrl,
                modelName = binding.editModelName.text.toString().trim(),
                systemPrompt = systemPrompt,
                userPrompt = userPrompt
            )

            lifecycleScope.launch {
                ConfigurationStorage.saveOpenAIProviderToList(prefs, provider, providerIndex)
                showToast(getString(R.string.save_successfully))
                requireActivity().finish()
            }
        } catch (e: Exception){
            showToast(getString(R.string.failed_save_config, e.message))
        }
    }

    private fun loadConfig() {
        try {
            val providerList = ConfigurationStorage.loadOpenAIProviders(prefs)
            if (!isNew && providerIndex < providerList.size) {
                val provider = providerList[providerIndex]
                binding.editProviderName.setText(provider.name)
                binding.editApiKey.setText(provider.apiKey)
                binding.editBaseUrl.setText(provider.baseUrl)
                binding.editModelName.setText(provider.modelName)
                binding.editSystemPrompt.setText(provider.systemPrompt)
                binding.editUserPrompt.setText(provider.userPrompt)
            } else {
                // 新建时设置默认prompt
                binding.editSystemPrompt.setText(defaultSystemPrompt)
                binding.editUserPrompt.setText(defaultUserPrompt)
            }
        } catch (e: Exception) {
            showToast("Error loading configuration: ${e.message}")
        }
    }

    private fun deleteConfiguration() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.custom_api_delete)
            .setMessage(R.string.custom_api_delete_confirm)
            .setPositiveButton(R.string.user_known) { _, _ ->
                ConfigurationStorage.deleteOpenAIProvider(prefs, providerIndex)
                val currentIndex = prefs.getInt("OpenAI_Selected_Provider", 0)
                if (currentIndex == providerIndex) {
                    prefs.setInt("OpenAI_Selected_Provider", 0)
                } else if (currentIndex > providerIndex) {
                    prefs.setInt("OpenAI_Selected_Provider", currentIndex - 1)
                }
                showToast(getString(R.string.save_successfully))
                requireActivity().finish()
            }
            .setNegativeButton(R.string.user_cancel, null)
            .create()
            .show()
    }

    private fun showToast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }
}
