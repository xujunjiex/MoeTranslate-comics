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

class UpdateChecker(private val context: Context) {
    private val client = OkHttpClient()

    suspend fun checkForUpdate(): UpdateResult = withContext(Dispatchers.IO) {
        // 暂时禁用自动更新检查
        UpdateResult.NoUpdate
    }

    private fun getCurrentVersion(): Long {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.longVersionCode
        } catch (e: PackageManager.NameNotFoundException) {
            e.printStackTrace()
            Log.e("UpdateChecker", "Error getting current version: ${e.message}")
            0
        }
    }
}

sealed class UpdateResult {
    object NoUpdate : UpdateResult()
    data class UpdateAvailable(val versionCode: Long, val versionName: String, val versionDescription: String) : UpdateResult()
    object Error : UpdateResult()
}