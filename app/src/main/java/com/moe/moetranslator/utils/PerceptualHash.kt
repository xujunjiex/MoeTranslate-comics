package com.moe.moetranslator.utils

import android.graphics.Bitmap

/**
 * 感知哈希工具 — 用于检测相同/相似页面画面。
 *
 * 算法：Average Hash (aHash)
 * 1. 缩放到 8x8 像素
 * 2. 转灰度
 * 3. 计算平均灰度值
 * 4. 每个像素 >= 平均值为 1，否则为 0
 * 5. 组成 64-bit Long
 */
object PerceptualHash {

    private const val DEFAULT_HASH_SIZE = 8

    /**
     * 计算图片的感知哈希值。
     * @param bitmap 输入图片（会被缩放，不影响原图）
     * @param hashSize 哈希网格大小，默认 8（8x8=64-bit）
     * @return 64-bit 哈希值
     */
    fun compute(bitmap: Bitmap, hashSize: Int = DEFAULT_HASH_SIZE): Long {
        // 1. 缩放到 hashSize x hashSize
        val scaled = Bitmap.createScaledBitmap(bitmap, hashSize, hashSize, true)

        // 2. 读取像素并转灰度
        val pixels = IntArray(hashSize * hashSize)
        scaled.getPixels(pixels, 0, hashSize, 0, 0, hashSize, hashSize)

        val grayscale = IntArray(pixels.size)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            grayscale[i] = (r * 0.299 + g * 0.587 + b * 0.114).toInt()
        }

        // 3. 计算平均灰度值
        var sum = 0L
        for (v in grayscale) sum += v
        val avg = sum / grayscale.size

        // 4. 每个像素与平均值比较，组成 64-bit Long
        var hash = 0L
        for (i in grayscale.indices) {
            if (grayscale[i] >= avg) {
                hash = hash or (1L shl i)
            }
        }

        // 不回收原图，只回收缩放后的
        if (scaled !== bitmap) {
            scaled.recycle()
        }

        return hash
    }

    /**
     * 计算两个哈希值的相似度。
     * @return 0.0~1.0，1.0 表示完全相同
     */
    fun similarity(hash1: Long, hash2: Long): Float {
        if (hash1 == hash2) return 1.0f
        val xor = hash1 xor hash2
        val diffBits = java.lang.Long.bitCount(xor)
        val totalBits = 64
        return 1.0f - diffBits.toFloat() / totalBits
    }

    /**
     * 判断两个哈希值是否相似。
     * @param threshold 阈值，默认 0.85
     */
    fun isSimilar(hash1: Long, hash2: Long, threshold: Float = 0.85f): Boolean {
        return similarity(hash1, hash2) >= threshold
    }
}
