package translationapi.doubaotranslation

import android.util.Log
import com.moe.starflow.translate.CustomLocale
import com.moe.starflow.translate.TranslationResult
import com.moe.starflow.translate.TranslationTextAPI
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
 * 豆包 Responses API 翻译
 * 请求方法：POST
 * URL: {baseUrl}/responses
 * 格式：{ "model": "...", "input": "...", "instructions": "..." }
 * 响应：output[0].content[0].text
 */
class DoubaoTranslation(
    private val apiKey: String,
    private val baseUrl: String,
    private val model: String,
    private val systemPrompt: String,
    private val userPrompt: String,
    private val maxTokens: Int = 1000
) : TranslationTextAPI {

    companion object {
        private const val TAG = "DoubaoTranslation"
        private const val SOCKET_TIMEOUT = 30L
    }

    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var currentJob: Job? = null
    private val JSON = "application/json; charset=utf-8".toMediaType()

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
        currentJob?.cancel()
        currentJob = coroutineScope.launch {
            try {
                val result = translate(text, sourceLanguage, targetLanguage)
                withContext(Dispatchers.Main) {
                    callback(TranslationResult.Success(result))
                }
            } catch (e: CancellationException) {
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

        val fromLang = CustomLocale.getInstance(from).getDisplayName()
        val toLang = CustomLocale.getInstance(to).getDisplayName()

        val instructions = systemPrompt
        val input = userPrompt
            .replace("usefromlang", fromLang)
            .replace("usetolang", toLang)
            .replace("usesourcetext", text)

        val requestBody = JSONObject().apply {
            put("model", model)
            put("input", input)
            put("instructions", instructions)
            put("max_tokens", maxTokens)
            put("stream", false)
            put("thinking", JSONObject().apply {
                put("type", "disabled")
            })
        }.toString()

        Log.d(TAG, "Request: $requestBody")

        val url = if (baseUrl.endsWith("/")) "${baseUrl}responses" else "$baseUrl/responses"
        val request = Request.Builder()
            .url(url)
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

        val responseBody = response.body?.string() ?: throw IOException("Empty response body")
        response.close()

        Log.d(TAG, "Response: $responseBody")
        parseResponse(responseBody)
    }

    private fun parseResponse(responseBody: String): String {
        try {
            val jsonObject = JSONObject(responseBody)

            if (jsonObject.has("error")) {
                val error = jsonObject.getJSONObject("error")
                val message = error.optString("message", "Unknown error")
                throw IOException("Doubao API error: $message")
            }

            val output = jsonObject.getJSONArray("output")
            if (output.length() == 0) throw IOException("No output in response")

            val firstOutput = output.getJSONObject(0)
            val content = firstOutput.getJSONArray("content")
            if (content.length() == 0) throw IOException("No content in response")

            val text = content.getJSONObject(0).getString("text").trim()
            if (text.isEmpty()) throw IOException("Empty translation result")

            return text
        } catch (e: IOException) {
            throw e
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
        coroutineScope.cancel()
    }
}
