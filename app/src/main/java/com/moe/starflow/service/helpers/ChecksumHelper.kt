package com.moe.starflow.service.helpers

import com.moe.starflow.utils.LogCollector
import java.io.File
import java.security.MessageDigest

object ChecksumHelper {
    private const val TAG = "ChecksumHelper"

    fun calculateMD5(file: File): String {
        val md = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                md.update(buffer, 0, bytesRead)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun verifyChecksum(file: File, expectedMd5: String): Boolean {
        return try {
            calculateMD5(file) == expectedMd5
        } catch (e: Exception) {
            LogCollector.e(TAG, "MD5 verify failed: ${file.name}", e)
            false
        }
    }
}

enum class VerifyResult { COMPLETE, MISSING, DAMAGED }
