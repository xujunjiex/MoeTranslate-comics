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

import android.util.Log
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
            val request = Request.Builder()
                .url(GIST_RAW_URL)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Gist fetch error: ${response.code}")
                    return@withContext NotificationResult.Error
                }

                val body = response.body?.string() ?: return@withContext NotificationResult.Error
                val json = JSONObject(body)

                val code = json.optLong("code", 0)
                val title = json.optString("title", "")
                val content = json.optString("content", "")

                if (code > 0 && title.isNotEmpty()) {
                    NotificationResult.NotificationAvailable(
                        notificationCode = code,
                        notificationName = title,
                        notificationContent = content
                    )
                } else {
                    NotificationResult.Error
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Network error: ${e.message}")
            NotificationResult.Error
        } catch (e: Exception) {
            Log.e(TAG, "Error checking notification: ${e.message}")
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
