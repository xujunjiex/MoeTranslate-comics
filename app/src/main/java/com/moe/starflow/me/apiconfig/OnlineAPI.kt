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

package com.moe.starflow.me.apiconfig
import com.moe.starflow.translate.widget.*
import com.moe.starflow.translate.autotranslate.*
import com.moe.starflow.translate.screenshot.*

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.moe.starflow.R
import com.moe.starflow.databinding.FragmentOnlineApiBinding
import com.moe.starflow.utils.CustomPreference
import com.moe.starflow.utils.KeystoreManager

class OnlineAPI : Fragment() {

    companion object {
        // 错误代码文档链接
        private const val URL_NIU_ERROR = "https://niutrans.com/documents/contents/trans_text#error"
        private const val URL_VOLC_ERROR = "https://www.volcengine.com/docs/4640/65067"
        private const val URL_AZURE_ERROR = "https://learn.microsoft.com/azure/ai-services/translator/reference/v3-0-translate#response-status-codes"
        private const val URL_DEEPL_ERROR = "https://developers.deepl.com/docs/best-practices/error-handling"
        private const val URL_BAIDU_TEXT_ERROR = "https://fanyi-api.baidu.com/doc/21"
        private const val URL_BAIDU_PIC_ERROR = "https://fanyi-api.baidu.com/doc/26"
        private const val URL_TENCENT_TEXT_ERROR = "https://cloud.tencent.com/document/product/551/15619"
        private const val URL_TENCENT_PIC_ERROR = "https://cloud.tencent.com/document/product/551/17232"
    }

    private var apiType: String? = null
    private lateinit var binding: FragmentOnlineApiBinding
    private lateinit var prefs: CustomPreference

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = CustomPreference.getInstance(requireContext())
        arguments?.let {
            apiType = it.getString("api_type")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentOnlineApiBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        when (apiType){
            "niu" -> prepareNiu()
            "volc" -> prepareVolc()
            "azure" -> prepareAzure()
            "deepl" -> prepareDeepL()
            "baidu" -> prepareBaidu()
            "tencent" -> prepareTencent()
            else -> Toast.makeText(context, "Unknow Error.", Toast.LENGTH_LONG).show()
        }
    }

    private fun prepareNiu(){
        binding.apiConfigImageView.setImageResource(R.drawable.ic_api_niu)
        binding.tvApiType.text = getString(R.string.api_name, getString(R.string.niuapi_name))
        binding.account.hint = getString(R.string.niu_noneed)
        binding.account.isFocusable = false
        binding.account.isClickable = false
        binding.account.isCursorVisible = false
        binding.account.isLongClickable = false
        if (prefs.getString("Niutrans_EncryptedKey","") != ""){
            binding.secretKey.hint = getString(R.string.api_saved)
        }else{
            binding.secretKey.hint = getString(R.string.niu_hint_secret_key)
        }
        binding.whatsThis.setOnClickListener {
            val dialog = AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.whats_api_title, getString(R.string.niuapi_name)))
                .setMessage(getString(R.string.whats_api_content, getString(R.string.niuapi_name)))
                .setCancelable(false)
                .setPositiveButton(R.string.go_now) { _, _ ->
                    val url = "https://niutrans.com/"
                    val intent = Intent(Intent.ACTION_VIEW)
                    intent.data = Uri.parse(url)
                    startActivity(intent)
                }
                .setNegativeButton(R.string.user_cancel) { _, _ ->}
                .create()
            dialog.show()
            dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
        }
        binding.saveOnlineApiButton.setOnClickListener {
            if (binding.secretKey.text.isBlank()) {
                Toast.makeText(context, getString(R.string.fill_blank), Toast.LENGTH_LONG).show()
            } else {
                KeystoreManager.storeKey(
                    requireContext(),
                    binding.secretKey.text.toString().trim(),
                    "Niutrans"
                )
                Toast.makeText(context, getString(R.string.save_successfully), Toast.LENGTH_LONG).show()
                requireActivity().finish()
            }
        }
        setupErrorCodeLink(URL_NIU_ERROR)
    }

    private fun prepareVolc(){
        binding.apiConfigImageView.setImageResource(R.drawable.ic_api_volc)
        binding.tvApiType.text = getString(R.string.api_name, getString(R.string.volcapi_name))
        if (prefs.getString("Volc_ACCOUNT_EncryptedKey","") != ""){
            binding.account.hint = getString(R.string.api_saved)
            binding.secretKey.hint = getString(R.string.api_saved)
        }else{
            binding.account.hint = getString(R.string.volc_hint_account)
            binding.secretKey.hint = getString(R.string.volc_hint_secret_key)
        }
        binding.whatsThis.setOnClickListener {
            val dialog = AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.whats_api_title, getString(R.string.volcapi_name)))
                .setMessage(getString(R.string.whats_api_content, getString(R.string.volcapi_name)))
                .setCancelable(false)
                .setPositiveButton(R.string.go_now) { _, _ ->
                    val url = "https://www.volcengine.com/"
                    val intent = Intent(Intent.ACTION_VIEW)
                    intent.data = Uri.parse(url)
                    startActivity(intent)
                }
                .setNegativeButton(R.string.user_cancel) { _, _ ->}
                .create()
            dialog.show()
            dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
        }
        binding.saveOnlineApiButton.setOnClickListener {
            if (binding.account.text.isBlank() || binding.secretKey.text.isBlank()) {
                Toast.makeText(context, getString(R.string.fill_blank), Toast.LENGTH_LONG).show()
            } else {
                KeystoreManager.storeKey(
                    requireContext(),
                    binding.account.text.toString().trim(),
                    "Volc_ACCOUNT"
                )
                KeystoreManager.storeKey(
                    requireContext(),
                    binding.secretKey.text.toString().trim(),
                    "Volc_SECRETKEY"
                )
                Toast.makeText(context, getString(R.string.save_successfully), Toast.LENGTH_LONG).show()
                requireActivity().finish()
            }
        }
        setupErrorCodeLink(URL_VOLC_ERROR)
    }

    private fun prepareAzure(){
        binding.apiConfigImageView.setImageResource(R.drawable.ic_api_azure)
        binding.tvApiType.text = getString(R.string.api_name, getString(R.string.azureapi_name))
        binding.account.hint = getString(R.string.niu_noneed)
        binding.account.isFocusable = false
        binding.account.isClickable = false
        binding.account.isCursorVisible = false
        binding.account.isLongClickable = false
        if (prefs.getString("Azure_EncryptedKey","") != ""){
            binding.secretKey.hint = getString(R.string.api_saved)
        }else{
            binding.secretKey.hint = getString(R.string.azure_hint_secret_key)
        }
        binding.whatsThis.setOnClickListener {
            val dialog = AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.whats_api_title, getString(R.string.azureapi_name)))
                .setMessage(getString(R.string.whats_api_content, getString(R.string.azureapi_name)))
                .setCancelable(false)
                .setPositiveButton(R.string.go_now) { _, _ ->
                    val url = "https://portal.azure.com/"
                    val intent = Intent(Intent.ACTION_VIEW)
                    intent.data = Uri.parse(url)
                    startActivity(intent)
                }
                .setNegativeButton(R.string.user_cancel) { _, _ ->}
                .create()
            dialog.show()
            dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
        }
        binding.saveOnlineApiButton.setOnClickListener {
            if (binding.secretKey.text.isBlank()) {
                Toast.makeText(context, getString(R.string.fill_blank), Toast.LENGTH_LONG).show()
            } else {
                KeystoreManager.storeKey(
                    requireContext(),
                    binding.secretKey.text.toString().trim(),
                    "Azure"
                )
                Toast.makeText(context, getString(R.string.save_successfully), Toast.LENGTH_LONG).show()
                requireActivity().finish()
            }
        }
        setupErrorCodeLink(URL_AZURE_ERROR)
    }

    private fun prepareDeepL(){
        binding.apiConfigImageView.setImageResource(R.drawable.ic_api_deepl)
        binding.tvApiType.text = getString(R.string.api_name, getString(R.string.deeplapi_name))
        if (prefs.getString("DeepL_Translate_APIKEY_EncryptedKey","") != ""){
            binding.account.hint = getString(R.string.api_saved)
            binding.secretKey.hint = getString(R.string.api_saved)
        }else{
            binding.account.hint = getString(R.string.deepl_hint_host)
            binding.secretKey.hint = getString(R.string.deepl_hint_apikey)
        }
        binding.whatsThis.setOnClickListener {
            val dialog = AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.whats_api_title, getString(R.string.deeplapi_name)))
                .setMessage(getString(R.string.whats_api_content, getString(R.string.deeplapi_name)))
                .setCancelable(false)
                .setPositiveButton(R.string.go_now) { _, _ ->
                    val url = "https://developers.deepl.com/docs/getting-started/intro/"
                    val intent = Intent(Intent.ACTION_VIEW)
                    intent.data = Uri.parse(url)
                    startActivity(intent)
                }
                .setNegativeButton(R.string.user_cancel) { _, _ ->}
                .create()
            dialog.show()
            dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
        }
        binding.saveOnlineApiButton.setOnClickListener {
            if (binding.account.text.isBlank() || binding.secretKey.text.isBlank()) {
                Toast.makeText(context, getString(R.string.fill_blank), Toast.LENGTH_LONG).show()
            } else {
                KeystoreManager.storeKey(
                    requireContext(),
                    binding.account.text.toString().trim(),
                    "DeepL_Translate_HOST"
                )
                KeystoreManager.storeKey(
                    requireContext(),
                    binding.secretKey.text.toString().trim(),
                    "DeepL_Translate_APIKEY"
                )
                Toast.makeText(context, getString(R.string.save_successfully), Toast.LENGTH_LONG).show()
                requireActivity().finish()
            }
        }
        setupErrorCodeLink(URL_DEEPL_ERROR)
    }

    private fun prepareBaidu(){
        binding.apiConfigImageView.setImageResource(R.drawable.ic_api_baidu)
        binding.tvApiType.text = getString(R.string.api_name, getString(R.string.baiduapi_name))
        if (prefs.getString("Baidu_Translate_ACCOUNT_EncryptedKey","") != ""){
            binding.account.hint = getString(R.string.api_saved)
            binding.secretKey.hint = getString(R.string.api_saved)
        }else{
            binding.account.hint = getString(R.string.baidu_hint_account)
            binding.secretKey.hint = getString(R.string.baidu_hint_secret_key)
        }
        binding.whatsThis.setOnClickListener {
            val dialog = AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.whats_api_title, getString(R.string.baiduapi_name)))
                .setMessage(getString(R.string.whats_api_content, getString(R.string.baiduapi_name)))
                .setCancelable(false)
                .setPositiveButton(R.string.go_now) { _, _ ->
                    val url = "https://fanyi-api.baidu.com/"
                    val intent = Intent(Intent.ACTION_VIEW)
                    intent.data = Uri.parse(url)
                    startActivity(intent)
                }
                .setNegativeButton(R.string.user_cancel) { _, _ ->}
                .create()
            dialog.show()
            dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
        }
        binding.saveOnlineApiButton.setOnClickListener {
            if (binding.account.text.isBlank() || binding.secretKey.text.isBlank()) {
                Toast.makeText(context, getString(R.string.fill_blank), Toast.LENGTH_LONG).show()
            } else {
                KeystoreManager.storeKey(
                    requireContext(),
                    binding.account.text.toString().trim(),
                    "Baidu_Translate_ACCOUNT"
                )
                KeystoreManager.storeKey(
                    requireContext(),
                    binding.secretKey.text.toString().trim(),
                    "Baidu_Translate_SECRETKEY"
                )
                Toast.makeText(context, getString(R.string.save_successfully), Toast.LENGTH_LONG).show()
                requireActivity().finish()
            }
        }
        setupErrorCodeLink(
            "百度文本翻译", URL_BAIDU_TEXT_ERROR,
            "百度图片翻译", URL_BAIDU_PIC_ERROR
        )
    }

    private fun prepareTencent(){
        binding.apiConfigImageView.setImageResource(R.drawable.ic_api_tencent)
        binding.tvApiType.text = getString(R.string.api_name, getString(R.string.tencentapi_name))
        if (prefs.getString("Tencent_Cloud_ACCOUNT_EncryptedKey","") != ""){
            binding.account.hint = getString(R.string.api_saved)
            binding.secretKey.hint = getString(R.string.api_saved)
        }else{
            binding.account.hint = getString(R.string.tencent_hint_account)
            binding.secretKey.hint = getString(R.string.tencent_hint_secret_key)
        }
        binding.whatsThis.setOnClickListener {
            val dialog = AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.whats_api_title, getString(R.string.tencentapi_name)))
                .setMessage(getString(R.string.whats_api_content, getString(R.string.tencentapi_name)))
                .setCancelable(false)
                .setPositiveButton(R.string.go_now) { _, _ ->
                    val url = "https://cloud.tencent.com/"
                    val intent = Intent(Intent.ACTION_VIEW)
                    intent.data = Uri.parse(url)
                    startActivity(intent)
                }
                .setNegativeButton(R.string.user_cancel) { _, _ ->}
                .create()
            dialog.show()
            dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
        }
        binding.saveOnlineApiButton.setOnClickListener {
            if (binding.account.text.isBlank() || binding.secretKey.text.isBlank()) {
                Toast.makeText(context, getString(R.string.fill_blank), Toast.LENGTH_LONG).show()
            } else {
                KeystoreManager.storeKey(
                    requireContext(),
                    binding.account.text.toString().trim(),
                    "Tencent_Cloud_ACCOUNT"
                )
                KeystoreManager.storeKey(
                    requireContext(),
                    binding.secretKey.text.toString().trim(),
                    "Tencent_Cloud_SECRETKEY"
                )
                Toast.makeText(context, getString(R.string.save_successfully), Toast.LENGTH_LONG).show()
                requireActivity().finish()
            }
        }
        setupErrorCodeLink(
            "腾讯云文本翻译", URL_TENCENT_TEXT_ERROR,
            "腾讯云图片翻译", URL_TENCENT_PIC_ERROR
        )
    }

    private fun setupErrorCodeLink(url: String) {
        binding.errorCodeLink.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        }
    }

    private fun setupErrorCodeLink(
        label1: String, url1: String,
        label2: String, url2: String
    ) {
        binding.errorCodeLink.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.error_code_link)
                .setItems(arrayOf(label1, label2)) { _, which ->
                    val url = if (which == 0) url1 else url2
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
                .setNegativeButton(R.string.user_cancel, null)
                .create()
                .apply {
                    show()
                    window?.setBackgroundDrawableResource(R.drawable.dialog_background)
                }
        }
    }
}