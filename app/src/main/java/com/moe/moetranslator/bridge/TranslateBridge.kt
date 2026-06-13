package com.moe.moetranslator.bridge

import android.content.Context
import com.moe.moetranslator.me.ConfigurationStorage
import com.moe.moetranslator.me.ConfigurationStorage.loadTextConfig
import com.moe.moetranslator.translate.TranslationResult
import com.moe.moetranslator.translate.TranslationTextAPI
import com.moe.moetranslator.utils.Constants
import com.moe.moetranslator.utils.CustomPreference
import com.moe.moetranslator.utils.KeystoreManager
import translationapi.azuretranslation.AzureTranslation
import translationapi.baidutranslation.BaiduTranslationText
import translationapi.bingtranslation.BingTranslation
import translationapi.customtranslation.CustomTranslationText
import translationapi.deepltranslation.DeepLTranslation
import translationapi.niutrans.NiuTranslation
import translationapi.openaitranslation.OpenAITranslation
import translationapi.doubaotranslation.DoubaoTranslation
import translationapi.tencentcloud.TencentTranslationText
import translationapi.volctranslation.VolcTranslation

object TranslateBridge {

    private var currentApi: TranslationTextAPI? = null

    private val defaultSystemPrompt =
        "你是一名专业翻译。你的任务是准确、自然地翻译给定的文本为usetolang。\n" +
        "具体规则如下： \n" +
        "1、根据用户的要求，将文本翻译成指定的目标语言；\n" +
        "2、保持原意和语气；\n" +
        "3、尽可能保持格式和结构；\n" +
        "4、直接返回翻译后的文本，不要有任何解释或附加内容；\n" +
        "5、如果文本已经是目标语言，请按原样返回。"

    private val defaultUserPrompt =
        "请将下面的文本从usefromlang翻译为usetolang：\n\nusesourcetext"

    fun initFromPreferences(context: Context) {
        val prefs = CustomPreference.getInstance(context)
        val textApiIndex = prefs.getInt("Text_API", Constants.TextApi.BING.id)
        val textApi = Constants.TextApi.entries[textApiIndex]

        currentApi = when (textApi) {
            Constants.TextApi.BING -> BingTranslation()
            Constants.TextApi.NIUTRANS -> {
                val key = KeystoreManager.retrieveKey(context, "Niutrans")
                    ?: throw IllegalStateException("NiuTrans API key not found")
                NiuTranslation(key)
            }
            Constants.TextApi.OPENAI -> {
                val providerList = ConfigurationStorage.loadAllProviders(prefs)
                val selectedIndex = prefs.getInt("OpenAI_Selected_Provider", 0)
                if (providerList.isNotEmpty() && selectedIndex < providerList.size) {
                    val provider = providerList[selectedIndex]
                    OpenAITranslation(
                        apiKey = provider.apiKey,
                        baseUrl = provider.baseUrl,
                        model = provider.modelName,
                        systemPrompt = provider.systemPrompt,
                        userPrompt = provider.userPrompt
                    )
                } else {
                    null
                }
            }
            Constants.TextApi.VOLC -> {
                val ak = KeystoreManager.retrieveKey(context, "Volc_ACCOUNT")
                    ?: throw IllegalStateException("Volc access key not found")
                val sk = KeystoreManager.retrieveKey(context, "Volc_SECRETKEY")
                    ?: throw IllegalStateException("Volc secret key not found")
                VolcTranslation(ak, sk)
            }
            Constants.TextApi.AZURE -> {
                val key = KeystoreManager.retrieveKey(context, "Azure")
                    ?: throw IllegalStateException("Azure subscription key not found")
                AzureTranslation(key)
            }
            Constants.TextApi.DEEPL -> {
                val host = KeystoreManager.retrieveKey(context, "DeepL_Translate_HOST")
                    ?: throw IllegalStateException("DeepL host not found")
                val apiKey = KeystoreManager.retrieveKey(context, "DeepL_Translate_APIKEY")
                    ?: throw IllegalStateException("DeepL API key not found")
                DeepLTranslation(host, apiKey)
            }
            Constants.TextApi.BAIDU -> {
                val appId = KeystoreManager.retrieveKey(context, "Baidu_Translate_ACCOUNT")
                    ?: throw IllegalStateException("Baidu app ID not found")
                val secretKey = KeystoreManager.retrieveKey(context, "Baidu_Translate_SECRETKEY")
                    ?: throw IllegalStateException("Baidu secret key not found")
                BaiduTranslationText(appId, secretKey)
            }
            Constants.TextApi.TENCENT -> {
                val secretId = KeystoreManager.retrieveKey(context, "Tencent_Cloud_ACCOUNT")
                    ?: throw IllegalStateException("Tencent secret ID not found")
                val secretKey = KeystoreManager.retrieveKey(context, "Tencent_Cloud_SECRETKEY")
                    ?: throw IllegalStateException("Tencent secret key not found")
                TencentTranslationText(secretId, secretKey)
            }
            Constants.TextApi.CUSTOM_TEXT -> {
                val customApiIndex = prefs.getInt("Custom_Text_API", 0)
                val config = loadTextConfig(prefs, customApiIndex)
                if (config != null) {
                    CustomTranslationText(config)
                } else {
                    null
                }
            }
            else -> null
        }
    }

    fun translateText(
        text: String,
        sourceLang: String,
        targetLang: String,
        callback: (TranslationResult) -> Unit
    ) {
        val api = currentApi ?: run {
            callback(TranslationResult.Error(IllegalStateException("翻译API未初始化")))
            return
        }
        api.getTranslation(text, sourceLang, targetLang, callback)
    }

    fun release() {
        currentApi?.release()
        currentApi = null
    }
}
