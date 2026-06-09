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

import com.moe.moetranslator.utils.CustomPreference
import org.json.JSONArray
import org.json.JSONObject

// 文本翻译数据模型
data class CustomTextAPIConfig(
    val method: String,
    val baseUrl: String,
    val queryParams: List<KeyValuePair>,
    val headers: List<KeyValuePair>,
    val jsonBody: List<KeyValuePair>,
    val jsonResponsePath: String
)

// 图片翻译数据模型
data class CustomPicAPIConfig(
    val method: String,                     // GET 或 POST
    val contentType: String?,               // POST时的Content-Type
    val baseUrl: String,                    // 基础URL
    val queryParams: List<KeyValuePair>,    // GET请求的查询参数
    val headers: List<KeyValuePair>,        // 请求头
    val body: List<KeyValuePair>,           // POST请求的body (JSON或Form)
    val jsonResponsePath: String            // JSON响应解析路径
)

data class KeyValuePair(
    val key: String,
    val value: String
)

// 带名称的文本翻译配置
data class NamedTextAPIConfig(
    val name: String,
    val config: CustomTextAPIConfig
)

// 带名称的图片翻译配置
data class NamedPicAPIConfig(
    val name: String,
    val config: CustomPicAPIConfig
)

// OpenAI兼容API厂商配置
data class OpenAIProviderConfig(
    val name: String,
    val apiKey: String,
    val baseUrl: String,
    val modelName: String,
    val systemPrompt: String,
    val userPrompt: String,
    // 内置API相关字段
    val providerType: String = PROVIDER_TYPE_USER,
    val models: List<String> = emptyList(),
    val defaultSystemPrompt: String = "",
    val defaultUserPrompt: String = "",
    val selectedModelIndex: Int = 0,
    val apiFormat: String = FORMAT_CHAT_COMPLETIONS
) {
    companion object {
        const val PROVIDER_TYPE_BUILTIN = "builtin"
        const val PROVIDER_TYPE_USER = "user"
        const val FORMAT_CHAT_COMPLETIONS = "chat_completions"
        const val FORMAT_RESPONSES = "responses"
    }

    val isBuiltin: Boolean get() = providerType == PROVIDER_TYPE_BUILTIN
    val isResponsesFormat: Boolean get() = apiFormat == FORMAT_RESPONSES
}

// 内置API用户修改数据模型
data class BuiltInProviderMod(
    val name: String,
    val apiKey: String = "",
    val systemPrompt: String? = null,
    val userPrompt: String? = null,
    val selectedModelIndex: Int = 0
)

// SharedPreferences存储
object ConfigurationStorage {
    private const val KEY_METHOD = "method"
    private const val KEY_CONTENT_TYPE = "contentType"
    private const val KEY_BASE_URL = "baseUrl"
    private const val KEY_QUERY_PARAMS = "queryParams"
    private const val KEY_HEADERS = "headers"
    private const val KEY_BODY = "body"
    private const val KEY_JSON_BODY = "jsonBody"
    private const val KEY_JSON_RESPONSE_PATH = "jsonResponsePath"
    private const val KEY_PAIR_KEY = "key"
    private const val KEY_PAIR_VALUE = "value"
    private const val KEY_NAME = "name"
    const val MAX_CUSTOM_API_COUNT = 10

    // 解析键值对列表的辅助函数
    private fun parseKeyValuePairs(jsonArray: JSONArray): List<KeyValuePair> {
        val pairs = mutableListOf<KeyValuePair>()
        for (i in 0 until jsonArray.length()) {
            val pairObject = jsonArray.getJSONObject(i)
            pairs.add(KeyValuePair(
                key = pairObject.getString(KEY_PAIR_KEY),
                value = pairObject.getString(KEY_PAIR_VALUE)
            ))
        }
        return pairs
    }

    fun saveTextConfig(prefs: CustomPreference, config: CustomTextAPIConfig, apiCode: Int) {
        try {
            // 创建主JSONObject
            val jsonObject = JSONObject().apply {
                put(KEY_METHOD, config.method)
                put(KEY_BASE_URL, config.baseUrl)
                put(KEY_JSON_RESPONSE_PATH, config.jsonResponsePath)

                // 转换查询参数列表
                put(KEY_QUERY_PARAMS, JSONArray().apply {
                    config.queryParams.forEach { pair ->
                        put(JSONObject().apply {
                            put(KEY_PAIR_KEY, pair.key)
                            put(KEY_PAIR_VALUE, pair.value)
                        })
                    }
                })

                // 转换请求头列表
                put(KEY_HEADERS, JSONArray().apply {
                    config.headers.forEach { pair ->
                        put(JSONObject().apply {
                            put(KEY_PAIR_KEY, pair.key)
                            put(KEY_PAIR_VALUE, pair.value)
                        })
                    }
                })

                // 转换JSON请求体列表
                put(KEY_JSON_BODY, JSONArray().apply {
                    config.jsonBody.forEach { pair ->
                        put(JSONObject().apply {
                            put(KEY_PAIR_KEY, pair.key)
                            put(KEY_PAIR_VALUE, pair.value)
                        })
                    }
                })
            }

            // 保存到SharedPreferences
            prefs.setString("Custom_Text_API_${apiCode}", jsonObject.toString())

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun savePicConfig(pref: CustomPreference, config: CustomPicAPIConfig, apiCode: Int) {
        try {
            val jsonObject = JSONObject().apply {
                put(KEY_METHOD, config.method)
                put(KEY_CONTENT_TYPE, config.contentType)
                put(KEY_BASE_URL, config.baseUrl)
                put(KEY_JSON_RESPONSE_PATH, config.jsonResponsePath)

                // 转换查询参数列表
                put(KEY_QUERY_PARAMS, JSONArray().apply {
                    config.queryParams.forEach { pair ->
                        put(JSONObject().apply {
                            put(KEY_PAIR_KEY, pair.key)
                            put(KEY_PAIR_VALUE, pair.value)
                        })
                    }
                })

                // 转换请求头列表
                put(KEY_HEADERS, JSONArray().apply {
                    config.headers.forEach { pair ->
                        put(JSONObject().apply {
                            put(KEY_PAIR_KEY, pair.key)
                            put(KEY_PAIR_VALUE, pair.value)
                        })
                    }
                })

                // 转换请求体列表
                put(KEY_BODY, JSONArray().apply {
                    config.body.forEach { pair ->
                        put(JSONObject().apply {
                            put(KEY_PAIR_KEY, pair.key)
                            put(KEY_PAIR_VALUE, pair.value)
                        })
                    }
                })
            }

            pref.setString("Custom_Pic_API_${apiCode}", jsonObject.toString())

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun loadTextConfig(prefs: CustomPreference, apiCode: Int): CustomTextAPIConfig? {
        return try {
            val jsonString = prefs.getString("Custom_Text_API_${apiCode}", "")
            if (jsonString.isEmpty()) return null

            val jsonObject = JSONObject(jsonString)

            CustomTextAPIConfig(
                method = jsonObject.getString(KEY_METHOD),
                baseUrl = jsonObject.getString(KEY_BASE_URL),
                queryParams = parseKeyValuePairs(jsonObject.getJSONArray(KEY_QUERY_PARAMS)),
                headers = parseKeyValuePairs(jsonObject.getJSONArray(KEY_HEADERS)),
                jsonBody = parseKeyValuePairs(jsonObject.getJSONArray(KEY_JSON_BODY)),
                jsonResponsePath = jsonObject.getString(KEY_JSON_RESPONSE_PATH)
            )

        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun loadPicConfig(pref: CustomPreference, apiCode: Int): CustomPicAPIConfig? {
        return try {
            val jsonString = pref.getString("Custom_Pic_API_$apiCode", "")
            if (jsonString.isEmpty()) return null

            val jsonObject = JSONObject(jsonString)

            CustomPicAPIConfig(
                method = jsonObject.getString(KEY_METHOD),
                contentType = if (jsonObject.has(KEY_CONTENT_TYPE)) jsonObject.getString(KEY_CONTENT_TYPE) else null, // 使用optString防止出现错误
                baseUrl = jsonObject.getString(KEY_BASE_URL),
                queryParams = parseKeyValuePairs(jsonObject.getJSONArray(KEY_QUERY_PARAMS)),
                headers = parseKeyValuePairs(jsonObject.getJSONArray(KEY_HEADERS)),
                body = parseKeyValuePairs(jsonObject.getJSONArray(KEY_BODY)),
                jsonResponsePath = jsonObject.getString(KEY_JSON_RESPONSE_PATH)
            )

        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // ==================== 列表存储方法 ====================

    fun saveTextConfigList(prefs: CustomPreference, list: List<NamedTextAPIConfig>) {
        try {
            val jsonArray = JSONArray()
            list.forEach { named ->
                jsonArray.put(JSONObject().apply {
                    put(KEY_NAME, named.name)
                    put(KEY_METHOD, named.config.method)
                    put(KEY_BASE_URL, named.config.baseUrl)
                    put(KEY_JSON_RESPONSE_PATH, named.config.jsonResponsePath)
                    put(KEY_QUERY_PARAMS, JSONArray().apply {
                        named.config.queryParams.forEach { pair ->
                            put(JSONObject().apply {
                                put(KEY_PAIR_KEY, pair.key)
                                put(KEY_PAIR_VALUE, pair.value)
                            })
                        }
                    })
                    put(KEY_HEADERS, JSONArray().apply {
                        named.config.headers.forEach { pair ->
                            put(JSONObject().apply {
                                put(KEY_PAIR_KEY, pair.key)
                                put(KEY_PAIR_VALUE, pair.value)
                            })
                        }
                    })
                    put(KEY_JSON_BODY, JSONArray().apply {
                        named.config.jsonBody.forEach { pair ->
                            put(JSONObject().apply {
                                put(KEY_PAIR_KEY, pair.key)
                                put(KEY_PAIR_VALUE, pair.value)
                            })
                        }
                    })
                })
            }
            prefs.setString("Custom_Text_APIs", jsonArray.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun loadTextConfigList(prefs: CustomPreference): List<NamedTextAPIConfig> {
        return try {
            val jsonString = prefs.getString("Custom_Text_APIs", "")
            if (jsonString.isEmpty()) return emptyList()
            val jsonArray = JSONArray(jsonString)
            val list = mutableListOf<NamedTextAPIConfig>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(NamedTextAPIConfig(
                    name = obj.getString(KEY_NAME),
                    config = CustomTextAPIConfig(
                        method = obj.getString(KEY_METHOD),
                        baseUrl = obj.getString(KEY_BASE_URL),
                        queryParams = parseKeyValuePairs(obj.getJSONArray(KEY_QUERY_PARAMS)),
                        headers = parseKeyValuePairs(obj.getJSONArray(KEY_HEADERS)),
                        jsonBody = parseKeyValuePairs(obj.getJSONArray(KEY_JSON_BODY)),
                        jsonResponsePath = obj.getString(KEY_JSON_RESPONSE_PATH)
                    )
                ))
            }
            list
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    fun saveTextConfigToList(prefs: CustomPreference, named: NamedTextAPIConfig, index: Int) {
        val list = loadTextConfigList(prefs).toMutableList()
        if (index < list.size) {
            list[index] = named
        } else {
            list.add(named)
        }
        saveTextConfigList(prefs, list)
    }

    fun deleteTextConfig(prefs: CustomPreference, index: Int) {
        val list = loadTextConfigList(prefs).toMutableList()
        if (index in list.indices) {
            list.removeAt(index)
            saveTextConfigList(prefs, list)
        }
    }

    fun savePicConfigList(prefs: CustomPreference, list: List<NamedPicAPIConfig>) {
        try {
            val jsonArray = JSONArray()
            list.forEach { named ->
                jsonArray.put(JSONObject().apply {
                    put(KEY_NAME, named.name)
                    put(KEY_METHOD, named.config.method)
                    put(KEY_CONTENT_TYPE, named.config.contentType)
                    put(KEY_BASE_URL, named.config.baseUrl)
                    put(KEY_JSON_RESPONSE_PATH, named.config.jsonResponsePath)
                    put(KEY_QUERY_PARAMS, JSONArray().apply {
                        named.config.queryParams.forEach { pair ->
                            put(JSONObject().apply {
                                put(KEY_PAIR_KEY, pair.key)
                                put(KEY_PAIR_VALUE, pair.value)
                            })
                        }
                    })
                    put(KEY_HEADERS, JSONArray().apply {
                        named.config.headers.forEach { pair ->
                            put(JSONObject().apply {
                                put(KEY_PAIR_KEY, pair.key)
                                put(KEY_PAIR_VALUE, pair.value)
                            })
                        }
                    })
                    put(KEY_BODY, JSONArray().apply {
                        named.config.body.forEach { pair ->
                            put(JSONObject().apply {
                                put(KEY_PAIR_KEY, pair.key)
                                put(KEY_PAIR_VALUE, pair.value)
                            })
                        }
                    })
                })
            }
            prefs.setString("Custom_Pic_APIs", jsonArray.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun loadPicConfigList(prefs: CustomPreference): List<NamedPicAPIConfig> {
        return try {
            val jsonString = prefs.getString("Custom_Pic_APIs", "")
            if (jsonString.isEmpty()) return emptyList()
            val jsonArray = JSONArray(jsonString)
            val list = mutableListOf<NamedPicAPIConfig>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(NamedPicAPIConfig(
                    name = obj.getString(KEY_NAME),
                    config = CustomPicAPIConfig(
                        method = obj.getString(KEY_METHOD),
                        contentType = if (obj.has(KEY_CONTENT_TYPE)) obj.getString(KEY_CONTENT_TYPE) else null,
                        baseUrl = obj.getString(KEY_BASE_URL),
                        queryParams = parseKeyValuePairs(obj.getJSONArray(KEY_QUERY_PARAMS)),
                        headers = parseKeyValuePairs(obj.getJSONArray(KEY_HEADERS)),
                        body = parseKeyValuePairs(obj.getJSONArray(KEY_BODY)),
                        jsonResponsePath = obj.getString(KEY_JSON_RESPONSE_PATH)
                    )
                ))
            }
            list
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    fun savePicConfigToList(prefs: CustomPreference, named: NamedPicAPIConfig, index: Int) {
        val list = loadPicConfigList(prefs).toMutableList()
        if (index < list.size) {
            list[index] = named
        } else {
            list.add(named)
        }
        savePicConfigList(prefs, list)
    }

    fun deletePicConfig(prefs: CustomPreference, index: Int) {
        val list = loadPicConfigList(prefs).toMutableList()
        if (index in list.indices) {
            list.removeAt(index)
            savePicConfigList(prefs, list)
        }
    }

    // ==================== 数据迁移 ====================

    fun migrateOldTextConfigs(prefs: CustomPreference) {
        if (prefs.getString("Custom_Text_APIs", "").isNotEmpty()) return
        val migrated = mutableListOf<NamedTextAPIConfig>()
        for (i in 0..2) {
            val oldConfig = loadTextConfig(prefs, i)
            if (oldConfig != null) {
                migrated.add(NamedTextAPIConfig(
                    name = "自定义API${i + 1}",
                    config = oldConfig
                ))
            }
        }
        if (migrated.isNotEmpty()) {
            saveTextConfigList(prefs, migrated)
        }
        prefs.setString("Custom_Text_API_0", "")
        prefs.setString("Custom_Text_API_1", "")
        prefs.setString("Custom_Text_API_2", "")
    }

    fun migrateOldPicConfigs(prefs: CustomPreference) {
        if (prefs.getString("Custom_Pic_APIs", "").isNotEmpty()) return
        val migrated = mutableListOf<NamedPicAPIConfig>()
        for (i in 0..2) {
            val oldConfig = loadPicConfig(prefs, i)
            if (oldConfig != null) {
                migrated.add(NamedPicAPIConfig(
                    name = "自定义API${i + 1}",
                    config = oldConfig
                ))
            }
        }
        if (migrated.isNotEmpty()) {
            savePicConfigList(prefs, migrated)
        }
        prefs.setString("Custom_Pic_API_0", "")
        prefs.setString("Custom_Pic_API_1", "")
        prefs.setString("Custom_Pic_API_2", "")
    }

    // ==================== OpenAI兼容API厂商管理 ====================

    private const val KEY_API_KEY = "apiKey"
    private const val KEY_MODEL_NAME = "modelName"
    private const val KEY_SYSTEM_PROMPT = "systemPrompt"
    private const val KEY_USER_PROMPT = "userPrompt"

    // ==================== 内置API管理 ====================

    private const val KEY_PROVIDER_TYPE = "providerType"
    private const val KEY_MODELS = "models"
    private const val KEY_DEFAULT_SYSTEM_PROMPT = "defaultSystemPrompt"
    private const val KEY_DEFAULT_USER_PROMPT = "defaultUserPrompt"
    private const val KEY_SELECTED_MODEL_INDEX = "selectedModelIndex"
    private const val BUILTIN_MODS_KEY = "BuiltIn_Providers_Modifications"

    fun saveBuiltInProviderMods(prefs: CustomPreference, mods: List<BuiltInProviderMod>) {
        try {
            val jsonArray = JSONArray()
            mods.forEach { mod ->
                jsonArray.put(JSONObject().apply {
                    put(KEY_NAME, mod.name)
                    put(KEY_API_KEY, mod.apiKey)
                    put(KEY_SYSTEM_PROMPT, mod.systemPrompt ?: JSONObject.NULL)
                    put(KEY_USER_PROMPT, mod.userPrompt ?: JSONObject.NULL)
                    put(KEY_SELECTED_MODEL_INDEX, mod.selectedModelIndex)
                })
            }
            prefs.setString(BUILTIN_MODS_KEY, jsonArray.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun loadBuiltInProviderMods(prefs: CustomPreference): List<BuiltInProviderMod> {
        return try {
            val jsonString = prefs.getString(BUILTIN_MODS_KEY, "")
            if (jsonString.isEmpty()) return emptyList()
            val jsonArray = JSONArray(jsonString)
            val list = mutableListOf<BuiltInProviderMod>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(BuiltInProviderMod(
                    name = obj.getString(KEY_NAME),
                    apiKey = obj.optString(KEY_API_KEY, ""),
                    systemPrompt = if (obj.isNull(KEY_SYSTEM_PROMPT)) null else obj.optString(KEY_SYSTEM_PROMPT, null),
                    userPrompt = if (obj.isNull(KEY_USER_PROMPT)) null else obj.optString(KEY_USER_PROMPT, null),
                    selectedModelIndex = obj.optInt(KEY_SELECTED_MODEL_INDEX, 0)
                ))
            }
            list
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    fun loadAllProviders(prefs: CustomPreference): List<OpenAIProviderConfig> {
        val builtinMods = loadBuiltInProviderMods(prefs)
        val builtinProviders = BuiltinProviders.providers.map { builtin ->
            val mod = builtinMods.find { it.name == builtin.name }
            applyMod(builtin, mod)
        }
        val userProviders = loadOpenAIProviders(prefs)
        return builtinProviders + userProviders
    }

    private fun applyMod(builtin: OpenAIProviderConfig, mod: BuiltInProviderMod?): OpenAIProviderConfig {
        if (mod == null) return builtin
        return builtin.copy(
            apiKey = mod.apiKey,
            systemPrompt = mod.systemPrompt ?: builtin.defaultSystemPrompt,
            userPrompt = mod.userPrompt ?: builtin.defaultUserPrompt,
            selectedModelIndex = mod.selectedModelIndex,
            modelName = builtin.models.getOrElse(mod.selectedModelIndex) { builtin.models[0] }
        )
    }

    fun saveOpenAIProviders(prefs: CustomPreference, list: List<OpenAIProviderConfig>) {
        try {
            val jsonArray = JSONArray()
            list.forEach { provider ->
                jsonArray.put(JSONObject().apply {
                    put(KEY_NAME, provider.name)
                    put(KEY_API_KEY, provider.apiKey)
                    put(KEY_BASE_URL, provider.baseUrl)
                    put(KEY_MODEL_NAME, provider.modelName)
                    put(KEY_SYSTEM_PROMPT, provider.systemPrompt)
                    put(KEY_USER_PROMPT, provider.userPrompt)
                    put(KEY_PROVIDER_TYPE, provider.providerType)
                    put(KEY_SELECTED_MODEL_INDEX, provider.selectedModelIndex)
                })
            }
            prefs.setString("OpenAI_Providers", jsonArray.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun loadOpenAIProviders(prefs: CustomPreference): List<OpenAIProviderConfig> {
        return try {
            val jsonString = prefs.getString("OpenAI_Providers", "")
            if (jsonString.isEmpty()) return emptyList()
            val jsonArray = JSONArray(jsonString)
            val list = mutableListOf<OpenAIProviderConfig>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(OpenAIProviderConfig(
                    name = obj.getString(KEY_NAME),
                    apiKey = obj.getString(KEY_API_KEY),
                    baseUrl = obj.getString(KEY_BASE_URL),
                    modelName = obj.getString(KEY_MODEL_NAME),
                    systemPrompt = obj.getString(KEY_SYSTEM_PROMPT),
                    userPrompt = obj.getString(KEY_USER_PROMPT),
                    providerType = obj.optString(KEY_PROVIDER_TYPE, OpenAIProviderConfig.PROVIDER_TYPE_USER),
                    selectedModelIndex = obj.optInt(KEY_SELECTED_MODEL_INDEX, 0)
                ))
            }
            list
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    fun saveOpenAIProviderToList(prefs: CustomPreference, provider: OpenAIProviderConfig, index: Int) {
        val list = loadOpenAIProviders(prefs).toMutableList()
        if (index < list.size) {
            list[index] = provider
        } else {
            list.add(provider)
        }
        saveOpenAIProviders(prefs, list)
    }

    fun deleteOpenAIProvider(prefs: CustomPreference, index: Int) {
        val list = loadOpenAIProviders(prefs).toMutableList()
        if (index in list.indices) {
            list.removeAt(index)
            saveOpenAIProviders(prefs, list)
        }
    }
}
