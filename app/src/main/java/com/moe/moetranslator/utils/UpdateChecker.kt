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

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class UpdateChecker(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG = "UpdateChecker"
        private const val RELEASES_API = "https://api.github.com/repos/xujunjiex/MoeTranslate-comics/releases/latest"
    }

    suspend fun checkForUpdate(): UpdateResult = withContext(Dispatchers.IO) {
        try {
            val currentVersionCode = getCurrentVersion()
            Log.d(TAG, "Current versionCode: $currentVersionCode")

            val request = Request.Builder()
                .url(RELEASES_API)
                .header("Accept", "application/vnd.github.v3+json")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "GitHub API error: ${response.code}")
                    return@withContext UpdateResult.Error
                }

                val body = response.body?.string() ?: return@withContext UpdateResult.Error
                val json = JSONObject(body)

                val tagName = json.getString("tag_name") // e.g. "v0.0.2"
                val versionName = tagName.removePrefix("v") // e.g. "0.0.2"
                val releaseNotes = json.optString("body", "")
                val htmlUrl = json.optString("html_url", "")

                // 从 tag 解析 versionCode（取最后一段数字，如 v0.0.2 → 2）
                val remoteVersionCode = parseVersionCode(versionName)
                Log.d(TAG, "Remote version: $versionName (code=$remoteVersionCode)")

                if (remoteVersionCode > currentVersionCode) {
                    UpdateResult.UpdateAvailable(
                        versionCode = remoteVersionCode,
                        versionName = versionName,
                        versionDescription = releaseNotes,
                        downloadUrl = htmlUrl
                    )
                } else {
                    UpdateResult.NoUpdate
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Network error: ${e.message}")
            UpdateResult.Error
        } catch (e: Exception) {
            Log.e(TAG, "Error checking update: ${e.message}")
            UpdateResult.Error
        }
    }

    private fun getCurrentVersion(): Long {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.longVersionCode
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, "Error getting current version: ${e.message}")
            0
        }
    }

    /**
     * 解析版本号为 versionCode
     * "0.0.2" → 2, "1.3.15" → 10315
     */
    private fun parseVersionCode(versionName: String): Long {
        return try {
            val parts = versionName.split(".")
            if (parts.size == 3) {
                parts[0].toLong() * 10000 + parts[1].toLong() * 100 + parts[2].toLong()
            } else {
                parts.last().toLong()
            }
        } catch (e: Exception) {
            0
        }
    }
}

sealed class UpdateResult {
    object NoUpdate : UpdateResult()
    data class UpdateAvailable(
        val versionCode: Long,
        val versionName: String,
        val versionDescription: String,
        val downloadUrl: String = ""
    ) : UpdateResult()
    object Error : UpdateResult()
}
