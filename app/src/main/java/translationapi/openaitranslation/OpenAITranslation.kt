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

package translationapi.openaitranslation

import android.util.Log
import com.moe.moetranslator.translate.CustomLocale
import com.moe.moetranslator.translate.TranslationResult
import com.moe.moetranslator.translate.TranslationTextAPI
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * OpenAI规范的AI翻译
 * 请求方法：POST
 * URL: {baseUrl}/chat/completions
 * 请求头设置Content-Type为application/json和Authorization
 * 请求参数放在请求体中，以json形式发送
 * 支持各种兼容OpenAI API的服务商
 */

class OpenAITranslation(
    private val apiKey: String,
    private val baseUrl: String = "https://api.openai.com/v1",
    private val model: String = "gpt-3.5-turbo",
    private val systemPrompt: String,
    private val userPrompt: String,
    private val maxTokens: Int = 1000,
    private val temperature: Float = 0.3f,
    private val continuationType: String = "none",
    private val prefillContent: String = ""
) : TranslationTextAPI {

    companion object {
        private const val TAG = "OpenAITranslation"
        private const val SOCKET_TIMEOUT = 30L // 30秒，AI接口需要更长时间
    }

    // 上下文相关（动态更新，每次翻译前通过 updateContext 设置）
    @Volatile private var currentContextHistory: List<Pair<String, String>> = emptyList()
    @Volatile private var currentContextEnabled: Boolean = false

    /**
     * 更新上下文。每次翻译前调用，传入最新的历史对话。
     */
    fun updateContext(history: List<Pair<String, String>>, enabled: Boolean) {
        this.currentContextHistory = history
        this.currentContextEnabled = enabled
    }

    // 创建协程作用域
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var currentJob: Job? = null

    private val JSON = "application/json; charset=utf-8".toMediaType()

    // 创建OkHttpClient实例
    private val client = OkHttpClient.Builder()
        .connectTimeout(SOCKET_TIMEOUT, TimeUnit.SECONDS)
        .readTimeout(SOCKET_TIMEOUT, TimeUnit.SECONDS)
        .writeTimeout(SOCKET_TIMEOUT, TimeUnit.SECONDS)
        .build()

    override fun getTranslation(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
        callback: (TranslationResult) -> Unit
    ) {
        // 取消之前的任务（如果存在）
        currentJob?.cancel()

        currentJob = coroutineScope.launch {
            try {
                val result = translate(text, sourceLanguage, targetLanguage)
                withContext(Dispatchers.Main) {
                    callback(TranslationResult.Success(result))
                }
            } catch (e: CancellationException) {
                // 协程被取消，不调用callback
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Translation error", e)
                withContext(Dispatchers.Main) {
                    callback(TranslationResult.Error(e))
                }
            }
        }
    }

    private suspend fun translate(text: String, from: String, to: String): String = withContext(Dispatchers.IO) {
        ensureActive()

        // 构建翻译提示词
        val toLang = CustomLocale.getInstance(to).getDisplayName()
        val systemPrompt = buildSystemPrompt(toLang)
        val userPrompt = buildUserPrompt(text, from, to)

        // 构建请求体，始终发送 thinking:disabled 禁用思考模式
        val requestBody = buildRequestBody(systemPrompt, userPrompt, disableThinking = true)
        Log.d(TAG, "Request: $requestBody")

        val endpoint = if (continuationType == "prefix") {
            "$baseUrl/beta/chat/completions"
        } else {
            "$baseUrl/chat/completions"
        }

        val request = Request.Builder()
            .url(endpoint)
            .post(requestBody.toRequestBody(JSON))
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .build()

        val response = client.newCall(request).execute()
        ensureActive()

        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: ""
            response.close()
            throw IOException("Request failed ${response.code}: $errorBody")
        }

        // 解析响应
        val responseBody = response.body?.string()
            ?: throw IOException("Empty response body")
        response.close()

        Log.d(TAG, "Response: $responseBody")
        parseResponse(responseBody)
    }

    private fun buildSystemPrompt(toLang: String): String {
        val prompt = systemPrompt.replace("usetolang", toLang)
        return if (currentContextEnabled && currentContextHistory.isNotEmpty()) {
            "根据上下文剧情进行翻译，保持角色语气和用词一致。\n\n$prompt"
        } else {
            prompt
        }
    }

    private fun buildUserPrompt(text: String, from: String, to: String): String {
        val fromLang = CustomLocale.getInstance(from).getDisplayName()
        val toLang = CustomLocale.getInstance(to).getDisplayName()

        Log.d(TAG, "翻译配置: model=$model, from=$from($fromLang), to=$to($toLang), continuationType=$continuationType, context=${if (currentContextEnabled) "${currentContextHistory.size}轮" else "关闭"}")
        Log.d(TAG, "SystemPrompt: $systemPrompt")
        Log.d(TAG, "UserPrompt模板: $userPrompt")

        val fullUserPrompt = userPrompt
            .replace("usefromlang", fromLang)
            .replace("usetolang", toLang)
            .replace("usesourcetext", text)

        Log.d(TAG, "UserPrompt: $fullUserPrompt")

        return fullUserPrompt
    }

    private fun buildRequestBody(systemPrompt: String, userPrompt: String, disableThinking: Boolean = false): String {
        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
            // 上下文历史：插入历史 user/assistant 对
            if (currentContextEnabled && currentContextHistory.isNotEmpty()) {
                for ((src, tgt) in currentContextHistory) {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", "翻译：$src")
                    })
                    put(JSONObject().apply {
                        put("role", "assistant")
                        put("content", tgt)
                    })
                }
            }
            put(JSONObject().apply {
                put("role", "user")
                put("content", userPrompt)
            })
            // 续写模式：添加 assistant prefill（JSON模式不加，靠 response_format 控制）
            if (prefillContent.isNotEmpty() && continuationType != "none" && continuationType != "json") {
                put(JSONObject().apply {
                    put("role", "assistant")
                    put("content", prefillContent)
                    when (continuationType) {
                        "partial" -> put("partial", true)   // 千问
                        "prefix" -> put("prefix", true)     // DeepSeek
                    }
                })
            }
        }

        return JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("max_tokens", maxTokens)
            put("temperature", temperature)
            put("stream", false)
            if (disableThinking) {
                put("thinking", JSONObject().apply {
                    put("type", "disabled")
                })
            }
            // 智谱AI结构化输出：强制JSON格式
            if (continuationType == "json") {
                put("response_format", JSONObject().apply {
                    put("type", "json_object")
                })
            }
        }.toString()
    }

    private fun parseResponse(responseBody: String): String {
        try {
            val jsonObject = JSONObject(responseBody)

            // 检查是否有错误
            if (jsonObject.has("error")) {
                val error = jsonObject.getJSONObject("error")
                val message = error.optString("message", "Unknown error")
                val type = error.optString("type", "unknown")
                throw IOException("OpenAI API error ($type): $message")
            }

            // 获取翻译结果
            val choices = jsonObject.getJSONArray("choices")
            if (choices.length() == 0) {
                throw IOException("No translation result in response")
            }

            val firstChoice = choices.getJSONObject(0)
            val message = firstChoice.getJSONObject("message")
            val content = message.getString("content").trim()

            Log.d(TAG, "翻译结果: $content")

            if (content.isEmpty()) {
                throw IOException("Empty translation result")
            }

            return content
        } catch (e: Exception) {
            throw IOException("Failed to parse response: ${e.message}")
        }
    }

    override fun cancelTranslation() {
        currentJob?.cancel()
        currentJob = null
    }

    override fun release() {
        cancelTranslation()
        coroutineScope.cancel() // 取消整个作用域
    }

    /**
     * 获取支持的模型列表（需要API支持）
     * @param callback 模型列表回调
     */
    fun getSupportedModels(callback: (List<String>?, String?) -> Unit) {
        coroutineScope.launch {
            try {
                val request = Request.Builder()
                    .url("$baseUrl/models")
                    .get()
                    .addHeader("Authorization", "Bearer $apiKey")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("Failed to get models: ${response.code}")
                    }

                    val responseBody = response.body?.string()
                        ?: throw IOException("Empty response")

                    val jsonObject = JSONObject(responseBody)
                    val data = jsonObject.getJSONArray("data")
                    val models = mutableListOf<String>()

                    for (i in 0 until data.length()) {
                        val model = data.getJSONObject(i)
                        models.add(model.getString("id"))
                    }

                    withContext(Dispatchers.Main) {
                        callback(models, null)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    callback(null, e.message)
                }
            }
        }
    }
}