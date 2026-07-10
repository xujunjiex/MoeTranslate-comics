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

package com.moe.starflow.utils

import android.content.Context
import android.content.pm.PackageManager
import com.moe.starflow.utils.LogCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
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
        private const val RELEASES_API = "https://api.github.com/repos/xujunjiex/StarFlow/releases/latest"
    }

    suspend fun checkForUpdate(): UpdateResult = withContext(Dispatchers.IO) {
        try {
            val currentVersionCode = getCurrentVersion()
            LogCollector.d(TAG, "Current versionCode: $currentVersionCode")

            val request = Request.Builder()
                .url(RELEASES_API)
                .header("Accept", "application/vnd.github.v3+json")
                .header("Cache-Control", "no-cache")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    LogCollector.e(TAG, "GitHub API error: ${response.code}")
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
                LogCollector.d(TAG, "Remote version: $versionName (code=$remoteVersionCode)")

                if (remoteVersionCode > currentVersionCode) {
                    // 从 assets 中解析 APK 下载链接
                    val apkDownloadUrl = parseApkUrl(json)
                    LogCollector.d(TAG, "APK download URL: $apkDownloadUrl")

                    // 从 body 中解析网盘链接
                    val (baiduUrl, quarkUrl) = parseCloudUrls(releaseNotes)
                    LogCollector.d(TAG, "Baidu URL: $baiduUrl, Quark URL: $quarkUrl")

                    UpdateResult.UpdateAvailable(
                        versionCode = remoteVersionCode,
                        versionName = versionName,
                        versionDescription = releaseNotes,
                        downloadUrl = htmlUrl,
                        apkDownloadUrl = apkDownloadUrl,
                        baiduUrl = baiduUrl,
                        quarkUrl = quarkUrl
                    )
                } else {
                    UpdateResult.NoUpdate
                }
            }
        } catch (e: IOException) {
            LogCollector.e(TAG, "Network error: ${e.message}")
            UpdateResult.Error
        } catch (e: Exception) {
            LogCollector.e(TAG, "Error checking update: ${e.message}")
            UpdateResult.Error
        }
    }

    /**
     * 从 Release assets 中解析 APK 下载链接
     */
    private fun parseApkUrl(json: JSONObject): String {
        return try {
            val assets = json.optJSONArray("assets") ?: return ""
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name", "")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    return asset.optString("browser_download_url", "")
                }
            }
            ""
        } catch (e: Exception) {
            LogCollector.e(TAG, "Failed to parse APK URL: ${e.message}")
            ""
        }
    }

    /**
     * 从 Release body 中解析网盘链接
     * 支持格式：
     *   百度网盘：https://pan.baidu.com/s/xxx
     *   夸克网盘：https://pan.quark.cn/s/xxx
     */
    private fun parseCloudUrls(body: String): Pair<String, String> {
        var baiduUrl = ""
        var quarkUrl = ""
        try {
            val lines = body.lines()
            var pendingLinkType: String? = null // 记录上一行的链接类型，等待下一行的 URL

            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue

                // 1. 检查当前行是否有待处理的 URL（上一行是链接类型，本行是 URL）
                if (pendingLinkType != null) {
                    val url = extractUrl(trimmed)
                    if (url.isNotEmpty()) {
                        when (pendingLinkType) {
                            "baidu" -> baiduUrl = url
                            "quark" -> quarkUrl = url
                        }
                        pendingLinkType = null
                        continue
                    }
                    pendingLinkType = null // 上一行没找到 URL，重置
                }

                // 2. 检查链接类型关键词（同行有 URL 则直接提取，否则记录等待下一行）
                when {
                    trimmed.contains("百度网盘") -> {
                        val url = extractUrl(trimmed)
                        if (url.isNotEmpty()) {
                            baiduUrl = url
                        } else {
                            pendingLinkType = "baidu"
                        }
                    }
                    trimmed.contains("夸克网盘") -> {
                        val url = extractUrl(trimmed)
                        if (url.isNotEmpty()) {
                            quarkUrl = url
                        } else {
                            pendingLinkType = "quark"
                        }
                    }
                }
            }
        } catch (e: Exception) {
            LogCollector.e(TAG, "Failed to parse cloud URLs: ${e.message}")
        }
        return Pair(baiduUrl, quarkUrl)
    }

    private fun extractUrl(text: String): String {
        val urlPattern = Regex("""https?://\S+""")
        return urlPattern.find(text)?.value ?: ""
    }

    private fun getCurrentVersion(): Long {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.longVersionCode
        } catch (e: PackageManager.NameNotFoundException) {
            LogCollector.e(TAG, "Error getting current version: ${e.message}")
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
        val downloadUrl: String = "",
        val apkDownloadUrl: String = "",
        val baiduUrl: String = "",
        val quarkUrl: String = ""
    ) : UpdateResult()
    object Error : UpdateResult()
}
