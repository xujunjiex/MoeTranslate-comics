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
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.moe.moetranslator.R
import com.moe.moetranslator.translate.AccessibilityServiceManager
import com.moe.moetranslator.translate.FloatingBallService
import com.moe.moetranslator.manga.MangaFloatingService
import com.moe.moetranslator.utils.Constants
import com.moe.moetranslator.utils.CustomPreference


class APIConfig : PreferenceFragmentCompat() {
    private lateinit var allTranslationKeys: List<String>
    private lateinit var prefs: CustomPreference

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        prefs = CustomPreference.getInstance(requireContext())
        if(prefs.getInt("Translate_Mode", 0) == 0){
            setPreferencesFromResource(R.xml.preferences_ocr, rootKey)
            allTranslationKeys = listOf(
                "mlkit_translation", "nllb_translation",
                "ui_bing_translation_text", "ui_niu_translation_text",
                "ui_volc_translation_text", "ui_azure_translation_text", "ui_deepl_translation_text",
                "ui_baidu_translation_text", "ui_tencent_translation_text"
            )
        }else{
            setPreferencesFromResource(R.xml.preferences_pic, rootKey)
            allTranslationKeys = listOf(
                "ui_baidu_translation_pic", "ui_tencent_translation_pic"
            )
        }

        // 设置每个选项的监听器
        allTranslationKeys.forEach { key ->
            findPreference<SwitchPreferenceCompat>(key)?.setOnPreferenceChangeListener { _, newValue ->
                if (newValue as Boolean) {
                    if (isAnyTranslationServiceRunning()) {
                        Toast.makeText(requireContext(), getString(R.string.stop_service_first), Toast.LENGTH_SHORT).show()
                        return@setOnPreferenceChangeListener false
                    }
                    // 如果打开了这个选项，关闭其他所有选项
                    changeCustomPreferences(key)
                    setKey(key)
                    true
                }else{
                    Toast.makeText(requireContext(), getString(R.string.no_less_one), Toast.LENGTH_LONG).show()
                    false
                }
            }
        }

        if (prefs.getInt("Translate_Mode", 0) == 0){

            findPreference<Preference>("manage_mlkit_model")?.setOnPreferenceClickListener {
                val intent = Intent(requireContext(), ManageActivity::class.java).apply {
                    putExtra(ManageActivity.EXTRA_FRAGMENT_TYPE, ManageActivity.TYPE_FRAGMENT_MANAGE_MLKIT)
                }
                startActivity(intent)
                true
            }

            findPreference<Preference>("manage_nllb_model")?.setOnPreferenceClickListener {
                val intent = Intent(requireContext(), ManageActivity::class.java).apply {
                    putExtra(ManageActivity.EXTRA_FRAGMENT_TYPE, ManageActivity.TYPE_FRAGMENT_MANAGE_NLLB)
                }
                startActivity(intent)
                true
            }

            findPreference<Preference>("ui_manage_niu_api_text")?.setOnPreferenceClickListener {
                val intent = Intent(requireContext(), ManageActivity::class.java).apply {
                    putExtra(ManageActivity.EXTRA_FRAGMENT_TYPE, ManageActivity.TYPE_FRAGMENT_MANAGE_NIU_API)
                }
                startActivity(intent)
                true
            }

            // 动态OpenAI兼容API厂商列表
            ConfigurationStorage.migrateOldOpenAIConfig(prefs)
            setupOpenAIProviderList(prefs)

            findPreference<Preference>("ui_manage_volc_api_text")?.setOnPreferenceClickListener {
                val intent = Intent(requireContext(), ManageActivity::class.java).apply {
                    putExtra(ManageActivity.EXTRA_FRAGMENT_TYPE, ManageActivity.TYPE_FRAGMENT_MANAGE_VOLC_API)
                }
                startActivity(intent)
                true
            }

            findPreference<Preference>("ui_manage_azure_api_text")?.setOnPreferenceClickListener {
                val intent = Intent(requireContext(), ManageActivity::class.java).apply {
                    putExtra(ManageActivity.EXTRA_FRAGMENT_TYPE, ManageActivity.TYPE_FRAGMENT_MANAGE_AZURE_API)
                }
                startActivity(intent)
                true
            }

            findPreference<Preference>("ui_manage_deepl_api_text")?.setOnPreferenceClickListener {
                val intent = Intent(requireContext(), ManageActivity::class.java).apply {
                    putExtra(ManageActivity.EXTRA_FRAGMENT_TYPE, ManageActivity.TYPE_FRAGMENT_MANAGE_DEEPL_API)
                }
                startActivity(intent)
                true
            }

            findPreference<Preference>("ui_manage_baidu_api_text")?.setOnPreferenceClickListener {
                val intent = Intent(requireContext(), ManageActivity::class.java).apply {
                    putExtra(ManageActivity.EXTRA_FRAGMENT_TYPE, ManageActivity.TYPE_FRAGMENT_MANAGE_BAIDU_API)
                }
                startActivity(intent)
                true
            }

            findPreference<Preference>("ui_manage_tencent_api_text")?.setOnPreferenceClickListener {
                val intent = Intent(requireContext(), ManageActivity::class.java).apply {
                    putExtra(ManageActivity.EXTRA_FRAGMENT_TYPE, ManageActivity.TYPE_FRAGMENT_MANAGE_TENCENT_API)
                }
                startActivity(intent)
                true
            }

            // 动态自定义文本 API
            ConfigurationStorage.migrateOldTextConfigs(prefs)
            setupDynamicCustomApiList(prefs, true)
        }else{
            findPreference<Preference>("ui_manage_baidu_api_pic")?.setOnPreferenceClickListener {
                val intent = Intent(requireContext(), ManageActivity::class.java).apply {
                    putExtra(ManageActivity.EXTRA_FRAGMENT_TYPE, ManageActivity.TYPE_FRAGMENT_MANAGE_BAIDU_API)
                }
                startActivity(intent)
                true
            }

            findPreference<Preference>("ui_manage_tencent_api_pic")?.setOnPreferenceClickListener {
                val intent = Intent(requireContext(), ManageActivity::class.java).apply {
                    putExtra(ManageActivity.EXTRA_FRAGMENT_TYPE, ManageActivity.TYPE_FRAGMENT_MANAGE_TENCENT_API)
                }
                startActivity(intent)
                true
            }

            // 动态自定义图片 API
            ConfigurationStorage.migrateOldPicConfigs(prefs)
            setupDynamicCustomApiList(prefs, false)
        }

        // 从 SharedPreferences 加载设置
        loadSettingsFromSharedPreferences()
    }

    private fun changeCustomPreferences(key: String) {
        when (key){
            "mlkit_translation" -> {
                prefs.setInt("Text_API", Constants.TextApi.AI.id)
                prefs.setInt("Text_AI", Constants.TextAI.MLKIT.id)
                prefs.setString("Source_Language", "ja")
                prefs.setString("Target_Language", "zh")
                Log.d("APIConfig", "mlkit_translation")
            }
            "nllb_translation" -> {
                prefs.setInt("Text_API", Constants.TextApi.AI.id)
                prefs.setInt("Text_AI", Constants.TextAI.NLLB.id)
                prefs.setString("Source_Language", "ja")
                prefs.setString("Target_Language", "zh")
                Log.d("APIConfig", "nllb_translation")
            }
            "ui_bing_translation_text"->{
                prefs.setInt("Text_API", Constants.TextApi.BING.id)
                prefs.setString("Source_Language", "ja")
                prefs.setString("Target_Language", "zh")
                Log.d("APIConfig", "OCR bing")
            }
            "ui_niu_translation_text"->{
                prefs.setInt("Text_API", Constants.TextApi.NIUTRANS.id)
                prefs.setString("Source_Language", "ja")
                prefs.setString("Target_Language", "zh")
                Log.d("APIConfig", "OCR niu")
            }
            "ui_openai_translation_text"->{
                prefs.setInt("Text_API", Constants.TextApi.OPENAI.id)
                prefs.setString("Source_Language", "ja")
                prefs.setString("Target_Language", "zh")
                Log.d("APIConfig", "OCR openai")
            }
            "ui_volc_translation_text"->{
                prefs.setInt("Text_API", Constants.TextApi.VOLC.id)
                prefs.setString("Source_Language", "ja")
                prefs.setString("Target_Language", "zh")
                Log.d("APIConfig", "OCR volc")
            }
            "ui_azure_translation_text"->{
                prefs.setInt("Text_API", Constants.TextApi.AZURE.id)
                prefs.setString("Source_Language", "ja")
                prefs.setString("Target_Language", "zh")
                Log.d("APIConfig", "OCR azure")
            }
            "ui_deepl_translation_text"->{
                prefs.setInt("Text_API", Constants.TextApi.DEEPL.id)
                prefs.setString("Source_Language", "ja")
                prefs.setString("Target_Language", "zh")
                Log.d("APIConfig", "OCR deepl")
            }
            "ui_baidu_translation_text"->{
                prefs.setInt("Text_API", Constants.TextApi.BAIDU.id)
                prefs.setString("Source_Language", "ja")
                prefs.setString("Target_Language", "zh")
                Log.d("APIConfig", "OCR baidu")
            }
            "ui_baidu_translation_pic"->{
                prefs.setInt("Pic_API", Constants.PicApi.BAIDU.id)
                prefs.setString("Source_Language", "ja")
                prefs.setString("Target_Language", "zh")
                Log.d("APIConfig", "pic baidu")
            }
            "ui_tencent_translation_text" -> {
                prefs.setInt("Text_API", Constants.TextApi.TENCENT.id)
                prefs.setString("Source_Language", "ja")
                prefs.setString("Target_Language", "zh")
                Log.d("APIConfig", "OCR tencent")
            }
            "ui_tencent_translation_pic" -> {
                prefs.setInt("Pic_API", Constants.PicApi.TENCENT.id)
                prefs.setString("Source_Language", "ja")
                prefs.setString("Target_Language", "zh")
                Log.d("APIConfig", "pic tencent")
            }
        }
    }

    private fun loadSettingsFromSharedPreferences() {
        // 获取当前设置
        val translateMode = prefs.getInt("Translate_Mode", Constants.TranslateMode.TEXT.id)
        val textApi = prefs.getInt("Text_API", Constants.TextApi.BING.id)
        val textAi = prefs.getInt("Text_AI", Constants.TextAI.MLKIT.id)
        val picApi = prefs.getInt("Pic_API", Constants.PicApi.BAIDU.id)
        val customTextApi = prefs.getInt("Custom_Text_API", 0)
        val customPicApi = prefs.getInt("Custom_Pic_API", 0)

        // 加载设置到UI上
        when {
            translateMode == Constants.TranslateMode.TEXT.id -> when (textApi) {
                Constants.TextApi.AI.id -> {
                    if (textAi == Constants.TextAI.MLKIT.id) {
                        val key = "mlkit_translation"
                        findPreference<SwitchPreferenceCompat>(key)?.isChecked = true
                        setKey(key)
                    } else {
                        val key = "nllb_translation"
                        findPreference<SwitchPreferenceCompat>(key)?.isChecked = true
                        setKey(key)
                    }
                }
                Constants.TextApi.BING.id -> {
                    val key = "ui_bing_translation_text"
                    findPreference<SwitchPreferenceCompat>(key)?.isChecked = true
                    setKey(key)
                }
                Constants.TextApi.NIUTRANS.id -> {
                    val key = "ui_niu_translation_text"
                    findPreference<SwitchPreferenceCompat>(key)?.isChecked = true
                    setKey(key)
                }
                Constants.TextApi.OPENAI.id -> {
                    // OpenAI通过厂商列表的select switch来选中
                    val selectedProvider = prefs.getInt("OpenAI_Selected_Provider", 0)
                    val key = "ui_openai_provider_select_$selectedProvider"
                    findPreference<SwitchPreferenceCompat>(key)?.isChecked = true
                }
                Constants.TextApi.VOLC.id -> {
                    val key = "ui_volc_translation_text"
                    findPreference<SwitchPreferenceCompat>(key)?.isChecked = true
                    setKey(key)
                }
                Constants.TextApi.AZURE.id -> {
                    val key = "ui_azure_translation_text"
                    findPreference<SwitchPreferenceCompat>(key)?.isChecked = true
                    setKey(key)
                }
                Constants.TextApi.DEEPL.id -> {
                    val key = "ui_deepl_translation_text"
                    findPreference<SwitchPreferenceCompat>(key)?.isChecked = true
                    setKey(key)
                }
                Constants.TextApi.BAIDU.id -> {
                    val key = "ui_baidu_translation_text"
                    findPreference<SwitchPreferenceCompat>(key)?.isChecked = true
                    setKey(key)
                }
                Constants.TextApi.TENCENT.id -> {
                    val key = "ui_tencent_translation_text"
                    findPreference<SwitchPreferenceCompat>(key)?.isChecked = true
                    setKey(key)
                }
                else -> {
                    val apiList = ConfigurationStorage.loadTextConfigList(prefs)
                    if (customTextApi < apiList.size) {
                        val key = "ui_custom_api_text_$customTextApi"
                        findPreference<SwitchPreferenceCompat>(key)?.isChecked = true
                    }
                }
            }
            else -> when (picApi) {
                Constants.PicApi.BAIDU.id -> {
                    val key = "ui_baidu_translation_pic"
                    findPreference<SwitchPreferenceCompat>(key)?.isChecked = true
                    setKey(key)
                }
                Constants.PicApi.TENCENT.id -> {
                    val key = "ui_tencent_translation_pic"
                    findPreference<SwitchPreferenceCompat>(key)?.isChecked = true
                    setKey(key)
                }
                else -> {
                    val apiList = ConfigurationStorage.loadPicConfigList(prefs)
                    if (customPicApi < apiList.size) {
                        val key = "ui_custom_api_pic_$customPicApi"
                        findPreference<SwitchPreferenceCompat>(key)?.isChecked = true
                    }
                }
            }
        }
    }

    private fun setKey(key: String){
        Log.d("APIConfig", "key=$key")
        allTranslationKeys.filter { it != key }.forEach { otherKey ->
            findPreference<SwitchPreferenceCompat>(otherKey)?.isChecked = false
        }
        // 关闭动态 custom API switches
        val isText = prefs.getInt("Translate_Mode", 0) == 0
        val categoryKey = if (isText) "ui_custom_cloud_api_translation_text" else "ui_custom_cloud_api_translation_pic"
        val category = findPreference<PreferenceCategory>(categoryKey)
        category?.let {
            for (i in 0 until it.preferenceCount) {
                val pref = it.getPreference(i)
                if (pref is SwitchPreferenceCompat && pref.key != null && pref.key!!.startsWith("ui_custom_api_")) {
                    pref.isChecked = false
                }
            }
        }
        // 关闭 OpenAI 厂商 switches
        val openaiCategory = findPreference<PreferenceCategory>("ui_openai_providers")
        openaiCategory?.let {
            for (i in 0 until it.preferenceCount) {
                val pref = it.getPreference(i)
                if (pref is SwitchPreferenceCompat && pref.key != null && pref.key!!.startsWith("ui_openai_provider_select_")) {
                    pref.isChecked = false
                }
            }
        }
    }

    private fun isAnyTranslationServiceRunning(): Boolean {
        return AccessibilityServiceManager.getService() != null &&
                (isServiceRunning(FloatingBallService::class.java) ||
                 isServiceRunning(MangaFloatingService::class.java))
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = requireContext().getSystemService(ActivityManager::class.java)
        @Suppress("DEPRECATION")
        return manager.getRunningServices(Integer.MAX_VALUE)
            .any { it.service.className == serviceClass.name }
    }

    private fun setupDynamicCustomApiList(prefs: CustomPreference, isText: Boolean) {
        val categoryKey = if (isText) "ui_custom_cloud_api_translation_text" else "ui_custom_cloud_api_translation_pic"
        val category = findPreference<PreferenceCategory>(categoryKey) ?: return

        category.removeAll()

        if (isText) {
            val apiList = ConfigurationStorage.loadTextConfigList(prefs)
            val selectedIndex = prefs.getInt("Custom_Text_API", 0)

            apiList.forEachIndexed { index, named ->
                val switchKey = "ui_custom_api_text_$index"
                val manageKey = "ui_manage_custom_api_text_$index"

                val switchPref = SwitchPreferenceCompat(requireContext()).apply {
                    key = switchKey
                    title = named.name
                    isIconSpaceReserved = false
                    isChecked = selectedIndex == index && prefs.getInt("Text_API", 0) == Constants.TextApi.CUSTOM_TEXT.id
                    setOnPreferenceChangeListener { _, newValue ->
                        if (newValue as Boolean) {
                            if (isAnyTranslationServiceRunning()) {
                                Toast.makeText(requireContext(), getString(R.string.stop_service_first), Toast.LENGTH_SHORT).show()
                                return@setOnPreferenceChangeListener false
                            }
                            prefs.setInt("Text_API", Constants.TextApi.CUSTOM_TEXT.id)
                            prefs.setInt("Custom_Text_API", index)
                            prefs.setString("Source_Language", "ja")
                            prefs.setString("Target_Language", "zh")
                            refreshCustomApiSwitches(category, switchKey)
                            allTranslationKeys.forEach { key ->
                                findPreference<SwitchPreferenceCompat>(key)?.isChecked = false
                            }
                            true
                        } else {
                            Toast.makeText(requireContext(), getString(R.string.no_less_one), Toast.LENGTH_LONG).show()
                            false
                        }
                    }
                }
                category.addPreference(switchPref)

                val managePref = Preference(requireContext()).apply {
                    key = manageKey
                    title = getString(R.string.custom_api_manage)
                    isIconSpaceReserved = false
                    setOnPreferenceClickListener {
                        val intent = Intent(requireContext(), ManageActivity::class.java).apply {
                            putExtra(ManageActivity.EXTRA_FRAGMENT_TYPE, ManageActivity.TYPE_FRAGMENT_MANAGE_CUSTOM_TEXT_API)
                            putExtra(ManageActivity.EXTRA_CUSTOM_CODE, index)
                            putExtra(ManageActivity.EXTRA_IS_NEW, false)
                        }
                        startActivity(intent)
                        true
                    }
                }
                category.addPreference(managePref)
            }

            if (apiList.size < ConfigurationStorage.MAX_CUSTOM_API_COUNT) {
                val addPref = Preference(requireContext()).apply {
                    key = "ui_custom_api_text_add"
                    title = getString(R.string.custom_api_add_new)
                    isIconSpaceReserved = false
                    setOnPreferenceClickListener {
                        val intent = Intent(requireContext(), ManageActivity::class.java).apply {
                            putExtra(ManageActivity.EXTRA_FRAGMENT_TYPE, ManageActivity.TYPE_FRAGMENT_MANAGE_CUSTOM_TEXT_API)
                            putExtra(ManageActivity.EXTRA_CUSTOM_CODE, apiList.size)
                            putExtra(ManageActivity.EXTRA_IS_NEW, true)
                        }
                        startActivity(intent)
                        true
                    }
                }
                category.addPreference(addPref)
            }
        } else {
            val apiList = ConfigurationStorage.loadPicConfigList(prefs)
            val selectedIndex = prefs.getInt("Custom_Pic_API", 0)

            apiList.forEachIndexed { index, named ->
                val switchKey = "ui_custom_api_pic_$index"
                val manageKey = "ui_manage_custom_api_pic_$index"

                val switchPref = SwitchPreferenceCompat(requireContext()).apply {
                    key = switchKey
                    title = named.name
                    isIconSpaceReserved = false
                    isChecked = selectedIndex == index && prefs.getInt("Pic_API", 0) == Constants.PicApi.CUSTOM_PIC.id
                    setOnPreferenceChangeListener { _, newValue ->
                        if (newValue as Boolean) {
                            if (isAnyTranslationServiceRunning()) {
                                Toast.makeText(requireContext(), getString(R.string.stop_service_first), Toast.LENGTH_SHORT).show()
                                return@setOnPreferenceChangeListener false
                            }
                            prefs.setInt("Pic_API", Constants.PicApi.CUSTOM_PIC.id)
                            prefs.setInt("Custom_Pic_API", index)
                            prefs.setString("Source_Language", "ja")
                            prefs.setString("Target_Language", "zh")
                            refreshCustomApiSwitches(category, switchKey)
                            allTranslationKeys.forEach { key ->
                                findPreference<SwitchPreferenceCompat>(key)?.isChecked = false
                            }
                            true
                        } else {
                            Toast.makeText(requireContext(), getString(R.string.no_less_one), Toast.LENGTH_LONG).show()
                            false
                        }
                    }
                }
                category.addPreference(switchPref)

                val managePref = Preference(requireContext()).apply {
                    key = manageKey
                    title = getString(R.string.custom_api_manage)
                    isIconSpaceReserved = false
                    setOnPreferenceClickListener {
                        val intent = Intent(requireContext(), ManageActivity::class.java).apply {
                            putExtra(ManageActivity.EXTRA_FRAGMENT_TYPE, ManageActivity.TYPE_FRAGMENT_MANAGE_CUSTOM_PIC_API)
                            putExtra(ManageActivity.EXTRA_CUSTOM_CODE, index)
                            putExtra(ManageActivity.EXTRA_IS_NEW, false)
                        }
                        startActivity(intent)
                        true
                    }
                }
                category.addPreference(managePref)
            }

            if (apiList.size < ConfigurationStorage.MAX_CUSTOM_API_COUNT) {
                val addPref = Preference(requireContext()).apply {
                    key = "ui_custom_api_pic_add"
                    title = getString(R.string.custom_api_add_new)
                    isIconSpaceReserved = false
                    setOnPreferenceClickListener {
                        val intent = Intent(requireContext(), ManageActivity::class.java).apply {
                            putExtra(ManageActivity.EXTRA_FRAGMENT_TYPE, ManageActivity.TYPE_FRAGMENT_MANAGE_CUSTOM_PIC_API)
                            putExtra(ManageActivity.EXTRA_CUSTOM_CODE, apiList.size)
                            putExtra(ManageActivity.EXTRA_IS_NEW, true)
                        }
                        startActivity(intent)
                        true
                    }
                }
                category.addPreference(addPref)
            }
        }
    }

    private fun refreshCustomApiSwitches(category: PreferenceCategory, activeKey: String) {
        for (i in 0 until category.preferenceCount) {
            val pref = category.getPreference(i)
            if (pref is SwitchPreferenceCompat && pref.key != null && pref.key != activeKey && pref.key!!.startsWith("ui_custom_api_")) {
                pref.isChecked = false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 从编辑页面返回时刷新厂商列表
        if (::prefs.isInitialized) {
            setupOpenAIProviderList(prefs)
            setupDynamicCustomApiList(prefs, true)
            setupDynamicCustomApiList(prefs, false)
        }
    }

    private fun setupOpenAIProviderList(prefs: CustomPreference) {
        val category = findPreference<PreferenceCategory>("ui_openai_providers") ?: return
        category.removeAll()

        val providerList = ConfigurationStorage.loadOpenAIProviders(prefs)
        val selectedProvider = prefs.getInt("OpenAI_Selected_Provider", 0)
        val isOpenAISelected = prefs.getInt("Text_API", 0) == Constants.TextApi.OPENAI.id

        providerList.forEachIndexed { index, provider ->
            val isSelected = isOpenAISelected && selectedProvider == index
            val pref = Preference(requireContext()).apply {
                key = "ui_openai_provider_$index"
                title = if (isSelected) "✓ ${provider.name}" else provider.name
                summary = provider.modelName
                isIconSpaceReserved = false
                setOnPreferenceClickListener {
                    // 弹出选择对话框
                    val options = arrayOf(
                        getString(R.string.select_provider),
                        getString(R.string.edit_provider),
                        getString(R.string.custom_api_delete)
                    )
                    AlertDialog.Builder(requireContext())
                        .setTitle(provider.name)
                        .setItems(options) { _, which ->
                            when (which) {
                                0 -> { // 选择
                                    if (isAnyTranslationServiceRunning()) {
                                        Toast.makeText(requireContext(), getString(R.string.stop_service_first), Toast.LENGTH_SHORT).show()
                                        return@setItems
                                    }
                                    prefs.setInt("Text_API", Constants.TextApi.OPENAI.id)
                                    prefs.setInt("OpenAI_Selected_Provider", index)
                                    prefs.setString("Source_Language", "ja")
                                    prefs.setString("Target_Language", "zh")
                                    setupOpenAIProviderList(prefs)
                                    allTranslationKeys.forEach { k ->
                                        findPreference<SwitchPreferenceCompat>(k)?.isChecked = false
                                    }
                                }
                                1 -> { // 编辑
                                    val intent = Intent(requireContext(), ManageActivity::class.java).apply {
                                        putExtra(ManageActivity.EXTRA_FRAGMENT_TYPE, ManageActivity.TYPE_FRAGMENT_MANAGE_OPENAI_API)
                                        putExtra(ManageActivity.EXTRA_CUSTOM_CODE, index)
                                        putExtra(ManageActivity.EXTRA_IS_NEW, false)
                                    }
                                    startActivity(intent)
                                }
                                2 -> { // 删除
                                    AlertDialog.Builder(requireContext())
                                        .setTitle(R.string.custom_api_delete)
                                        .setMessage(R.string.custom_api_delete_confirm)
                                        .setPositiveButton(R.string.user_known) { _, _ ->
                                            ConfigurationStorage.deleteOpenAIProvider(prefs, index)
                                            val currentIndex = prefs.getInt("OpenAI_Selected_Provider", 0)
                                            if (currentIndex == index) {
                                                prefs.setInt("OpenAI_Selected_Provider", 0)
                                            } else if (currentIndex > index) {
                                                prefs.setInt("OpenAI_Selected_Provider", currentIndex - 1)
                                            }
                                            setupOpenAIProviderList(prefs)
                                        }
                                        .setNegativeButton(R.string.user_cancel, null)
                                        .show()
                                }
                            }
                        }
                        .show()
                    true
                }
            }
            category.addPreference(pref)
        }

        // 添加新厂商按钮
        if (providerList.size < ConfigurationStorage.MAX_CUSTOM_API_COUNT) {
            val addPref = Preference(requireContext()).apply {
                key = "ui_openai_provider_add"
                title = getString(R.string.custom_api_add_new)
                isIconSpaceReserved = false
                setOnPreferenceClickListener {
                    val intent = Intent(requireContext(), ManageActivity::class.java).apply {
                        putExtra(ManageActivity.EXTRA_FRAGMENT_TYPE, ManageActivity.TYPE_FRAGMENT_MANAGE_OPENAI_API)
                        putExtra(ManageActivity.EXTRA_CUSTOM_CODE, providerList.size)
                        putExtra(ManageActivity.EXTRA_IS_NEW, true)
                    }
                    startActivity(intent)
                    true
                }
            }
            category.addPreference(addPref)
        }
    }
}