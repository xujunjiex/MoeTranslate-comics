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

package com.moe.moetranslator.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class NotificationChecker {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG = "NotificationChecker"
        // 公告 Gist（星译公告）
        private const val GIST_RAW_URL = "https://gist.githubusercontent.com/xujunjiex/7587dcb7b485113ff9a2def3417cc6dc/raw"
    }

    suspend fun checkNotification(): NotificationResult = withContext(Dispatchers.IO) {
        try {
            LogCollector.d(TAG, "Fetching notification from: $GIST_RAW_URL")
            val request = Request.Builder()
                .url(GIST_RAW_URL)
                .header("Cache-Control", "no-cache")
                .build()

            client.newCall(request).execute().use { response ->
                LogCollector.d(TAG, "Response code: ${response.code}")
                if (!response.isSuccessful) {
                    LogCollector.e(TAG, "Gist fetch error: ${response.code}")
                    return@withContext NotificationResult.Error
                }

                val body = response.body?.string()
                LogCollector.d(TAG, "Response body length: ${body?.length ?: 0}")
                if (body == null) {
                    LogCollector.e(TAG, "Response body is null")
                    return@withContext NotificationResult.Error
                }

                try {
                    // Gist 返回的内容可能包含非 JSON 前缀（如 "星译公告"），需要提取 JSON 部分
                    val jsonStart = body.indexOf('{')
                    if (jsonStart < 0) {
                        LogCollector.e(TAG, "No JSON found in response: ${body.take(200)}")
                        return@withContext NotificationResult.Error
                    }
                    val jsonStr = body.substring(jsonStart)
                    val json = JSONObject(jsonStr)
                    val code = json.optLong("code", 0)
                    val title = json.optString("title", "")
                    val content = json.optString("content", "")
                    LogCollector.d(TAG, "Parsed: code=$code, title=$title")

                    if (code > 0 && title.isNotEmpty()) {
                        NotificationResult.NotificationAvailable(
                            notificationCode = code,
                            notificationName = title,
                            notificationContent = content
                        )
                    } else {
                        LogCollector.e(TAG, "Invalid notification data: code=$code, title.isEmpty=${title.isEmpty()}")
                        NotificationResult.Error
                    }
                } catch (e: Exception) {
                    LogCollector.e(TAG, "JSON parse error: ${e.message}, body=${body.take(200)}")
                    NotificationResult.Error
                }
            }
        } catch (e: IOException) {
            LogCollector.e(TAG, "Network error: ${e.message}")
            NotificationResult.Error
        } catch (e: Exception) {
            LogCollector.e(TAG, "Error checking notification: ${e.message}")
            NotificationResult.Error
        }
    }
}

sealed class NotificationResult {
    data class NotificationAvailable(
        val notificationCode: Long,
        val notificationName: String,
        val notificationContent: String
    ) : NotificationResult()
    object Error : NotificationResult()
}
