package com.moe.moetranslator.utils

import android.graphics.Bitmap

/**
 * 感知哈希算法 — dHash (Difference Hash)
 *
 * 原理：
 *   1. 缩放到 9×8 像素（保留结构信息）
 *   2. 转灰度
 *   3. 每行相邻像素比较：左 > 右 则记为 1，否则 0
 *   4. 产生 8×8 = 64 位哈希
 *
 * 对比 aHash（平均哈希）：aHash 只比较每个像素与平均亮度，
 * dHash 比较相邻像素的亮度方向变化，对结构差异更敏感，
 * 能更好地区分"内容相似但细节不同"的截图。
 *
 * 相似度阈值建议：
 *   - 漫画翻译：0.92（允许 ~5 bit 差异，适应同一页面的轻微渲染变化）
 *   - 游戏翻译：0.97（仅允许 ~2 bit 差异，避免不同对话框误判为相同）
 */
object PerceptualHash {

    private const val HASH_ROWS = 8
    private const val HASH_COLS = 9  // 9 列产生 8×8=64 位哈希（每行 8 对比较）

    // 256-bit 扩展哈希参数（17×16 分辨率，用于漫画缓存匹配）
    private const val EXT_HASH_COLS = 17
    private const val EXT_HASH_ROWS = 16
    private const val EXT_HASH_PARTS = 4      // 256 bits / 64 bits per Long

    /**
     * 计算图片的 dHash。
     * @param bitmap 原始图片
     * @param centerCrop 是否裁剪到中心区域（提高框选偏移时的缓存命中率）
     * @return 64 位哈希值
     */
    fun compute(bitmap: Bitmap, centerCrop: Boolean = false): Long {
        val target = if (centerCrop) {
            // 裁剪到中心 70%，忽略边缘区域（框选偏移主要影响边缘）
            val cropW = (bitmap.width * 0.7f).toInt().coerceAtLeast(HASH_COLS)
            val cropH = (bitmap.height * 0.7f).toInt().coerceAtLeast(HASH_ROWS)
            val x = (bitmap.width - cropW) / 2
            val y = (bitmap.height - cropH) / 2
            Bitmap.createBitmap(bitmap, x, y, cropW, cropH)
        } else {
            bitmap
        }

        // 1. 缩放到 9×8
        val scaled = Bitmap.createScaledBitmap(target, HASH_COLS, HASH_ROWS, true)
        val pixels = IntArray(HASH_COLS * HASH_ROWS)
        scaled.getPixels(pixels, 0, HASH_COLS, 0, 0, HASH_COLS, HASH_ROWS)
        if (scaled !== target) { scaled.recycle() }
        if (target !== bitmap && centerCrop) { target.recycle() }

        // 2 & 3. 灰度比较相邻像素
        var hash = 0L
        var bitIndex = 0
        for (row in 0 until HASH_ROWS) {
            val rowOffset = row * HASH_COLS
            for (col in 0 until HASH_COLS - 1) {
                val leftGray = grayscale(pixels[rowOffset + col])
                val rightGray = grayscale(pixels[rowOffset + col + 1])
                if (leftGray > rightGray) {
                    hash = hash or (1L shl bitIndex)
                }
                bitIndex++
            }
        }
        return hash
    }

    /**
     * 计算两个哈希的相似度（Hamming 距离的归一化形式）。
     * @return 0.0 ~ 1.0，1.0 表示完全相同
     */
    fun similarity(hash1: Long, hash2: Long): Float {
        val distance = java.lang.Long.bitCount(hash1 xor hash2)
        return 1f - distance.toFloat() / 64f
    }

    /**
     * 判断两张图片是否相似。
     * @param threshold 阈值，默认 0.92（漫画场景）
     */
    fun isSimilar(hash1: Long, hash2: Long, threshold: Float = 0.92f): Boolean {
        return similarity(hash1, hash2) >= threshold
    }

    /**
     * 256-bit 扩展感知哈希 — 17×16 dHash。
     * 将图片缩放到 17×16 后做 16×16 = 256 次相邻像素比较，
     * 结果打包为 4 个 Long。
     *
     * 用于漫画缓存匹配，相比 64-bit 版本能更好区分不同页面。
     * @return LongArray(4)，共 256 位
     */
    fun computeExtended(bitmap: Bitmap, centerCrop: Boolean = false): LongArray {
        val target = if (centerCrop) {
            val cropW = (bitmap.width * 0.7f).toInt().coerceAtLeast(EXT_HASH_COLS)
            val cropH = (bitmap.height * 0.7f).toInt().coerceAtLeast(EXT_HASH_ROWS)
            val x = (bitmap.width - cropW) / 2
            val y = (bitmap.height - cropH) / 2
            Bitmap.createBitmap(bitmap, x, y, cropW, cropH)
        } else {
            bitmap
        }

        val scaled = Bitmap.createScaledBitmap(target, EXT_HASH_COLS, EXT_HASH_ROWS, true)
        val pixels = IntArray(EXT_HASH_COLS * EXT_HASH_ROWS)
        scaled.getPixels(pixels, 0, EXT_HASH_COLS, 0, 0, EXT_HASH_COLS, EXT_HASH_ROWS)
        if (scaled !== target) scaled.recycle()
        if (target !== bitmap && centerCrop) target.recycle()

        val hashes = LongArray(EXT_HASH_PARTS)
        var bitIndex = 0
        for (row in 0 until EXT_HASH_ROWS) {
            val rowOffset = row * EXT_HASH_COLS
            for (col in 0 until EXT_HASH_COLS - 1) {
                val leftGray = grayscale(pixels[rowOffset + col])
                val rightGray = grayscale(pixels[rowOffset + col + 1])
                if (leftGray > rightGray) {
                    hashes[bitIndex / 64] = hashes[bitIndex / 64] or (1L shl (bitIndex % 64))
                }
                bitIndex++
            }
        }
        return hashes
    }

    /**
     * 计算两个扩展哈希的相似度。
     * @return 0.0 ~ 1.0，1.0 表示完全相同
     */
    fun similarity(a: LongArray, b: LongArray): Float {
        require(a.size == b.size) { "Hash arrays must have same size, got ${a.size} and ${b.size}" }
        var totalDiff = 0L
        for (i in a.indices) {
            totalDiff += java.lang.Long.bitCount(a[i] xor b[i])
        }
        return 1f - totalDiff.toFloat() / (a.size * 64f)
    }

    /**
     * RGB 转灰度（ITU-R BT.601）
     */
    private fun grayscale(pixel: Int): Int {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return (0.299 * r + 0.587 * g + 0.114 * b).toInt()
    }
}
