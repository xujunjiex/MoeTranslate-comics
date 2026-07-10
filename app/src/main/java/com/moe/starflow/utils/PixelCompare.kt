package com.moe.starflow.utils

/**
 * 逐像素图像比较工具（移植自 pixelmatch）
 *
 * 原项目: https://github.com/mapbox/pixelmatch
 * 核心算法: YIQ 感知色彩差异 + 抗锯齿像素检测
 *
 * 用途：
 * - 检测相邻帧画面是否变化
 * - 检测画面是否稳定（翻页完成后）
 * - 作为 OCR 前的轻量预筛
 */
object PixelCompare {

    // YIQ 差异最大可能值
    private const val MAX_YIQ_DELTA = 35215f

    /**
     * 比较两帧像素数组的差异（推荐，避免 Bitmap.copy() 共享缓冲区问题）
     * @param prevPixels 上一帧像素数据
     * @param currPixels 当前帧像素数据
     * @param width 图像宽度
     * @param height 图像高度
     * @param yiqThreshold YIQ 颜色差异阈值 (0~1)，控制单个像素颜色差异灵敏度
     * @param diffThreshold diffRatio 阈值 (0~1)，超过此值认为画面变化，默认 0.05 (5%)
     * @return CompareResult
     */
    fun comparePixels(prevPixels: IntArray, currPixels: IntArray, width: Int, height: Int, yiqThreshold: Float = 0.1f, diffThreshold: Float = 0.05f): CompareResult {
        val totalPixels = width * height
        require(prevPixels.size >= totalPixels && currPixels.size >= totalPixels) {
            "Pixel array size mismatch: need $totalPixels, got prev=${prevPixels.size} curr=${currPixels.size}"
        }

        // 快速路径：32位比较，完全相同直接返回
        var identical = true
        for (i in 0 until totalPixels) {
            if (prevPixels[i] != currPixels[i]) {
                identical = false
                break
            }
        }
        if (identical) {
            return CompareResult(0, totalPixels, 0f, true)
        }

        // 最大可接受的 YIQ 差异平方值
        val maxDelta = MAX_YIQ_DELTA * yiqThreshold * yiqThreshold
        var diff = 0

        // 逐像素比较
        for (i in 0 until totalPixels) {
            val a = prevPixels[i]
            val b = currPixels[i]
            if (a == b) continue

            // YIQ 感知色彩差异
            val delta = colorDelta(a, b)
            if (kotlin.math.abs(delta) > maxDelta) {
                val x = i % width
                val y = i / width
                // 检查是否为抗锯齿像素
                val isAA = antialiased(prevPixels, x, y, width, height, prevPixels, currPixels) ||
                           antialiased(currPixels, x, y, width, height, currPixels, prevPixels)
                if (!isAA) {
                    diff++
                }
            }
        }

        val diffRatio = diff.toFloat() / totalPixels
        return CompareResult(diff, totalPixels, diffRatio, diffRatio < diffThreshold)
    }

    /**
     * YIQ 感知色彩差异（移植自 pixelmatch colorDelta）
     * 使用 Android ARGB 格式
     */
    private fun colorDelta(pixel1: Int, pixel2: Int): Float {
        val r1 = (pixel1 shr 16) and 0xFF
        val g1 = (pixel1 shr 8) and 0xFF
        val b1 = pixel1 and 0xFF
        val a1 = (pixel1 shr 24) and 0xFF

        val r2 = (pixel2 shr 16) and 0xFF
        val g2 = (pixel2 shr 8) and 0xFF
        val b2 = pixel2 and 0xFF
        val a2 = (pixel2 shr 24) and 0xFF

        var dr = (r1 - r2).toFloat()
        var dg = (g1 - g2).toFloat()
        var db = (b1 - b2).toFloat()
        val da = (a1 - a2).toFloat()

        // alpha < 255 时混合白色背景
        if (a1 < 255 || a2 < 255) {
            dr = (r1 * a1 - r2 * a2 - 255f * da) / 255f
            dg = (g1 * a1 - g2 * a2 - 255f * da) / 255f
            db = (b1 * a1 - b2 * a2 - 255f * da) / 255f
        }

        // YIQ 色彩空间
        val y = dr * 0.29889531f + dg * 0.58662247f + db * 0.11448223f
        val i = dr * 0.59597799f - dg * 0.27417610f - db * 0.32180189f
        val q = dr * 0.21147017f - dg * 0.52261711f + db * 0.31114694f

        val delta = 0.5053f * y * y + 0.299f * i * i + 0.1957f * q * q
        return if (y > 0) -delta else delta
    }

    /**
     * 亮度差异（抗锯齿检测用）
     */
    private fun brightnessDelta(pixel1: Int, pixel2: Int): Float {
        val r1 = (pixel1 shr 16) and 0xFF
        val g1 = (pixel1 shr 8) and 0xFF
        val b1 = pixel1 and 0xFF
        val a1 = (pixel1 shr 24) and 0xFF

        val r2 = (pixel2 shr 16) and 0xFF
        val g2 = (pixel2 shr 8) and 0xFF
        val b2 = pixel2 and 0xFF
        val a2 = (pixel2 shr 24) and 0xFF

        var dr = (r1 - r2).toFloat()
        var dg = (g1 - g2).toFloat()
        var db = (b1 - b2).toFloat()
        val da = (a1 - a2).toFloat()

        if (dr == 0f && dg == 0f && db == 0f && da == 0f) return 0f

        if (a1 < 255 || a2 < 255) {
            dr = (r1 * a1 - r2 * a2 - 255f * da) / 255f
            dg = (g1 * a1 - g2 * a2 - 255f * da) / 255f
            db = (b1 * a1 - b2 * a2 - 255f * da) / 255f
        }

        return dr * 0.29889531f + dg * 0.58662247f + db * 0.11448223f
    }

    /**
     * 抗锯齿像素检测（移植自 pixelmatch antialiased）
     * 基于 "Anti-aliased Pixel and Intensity Slope Detector" (V. Vysniauskas, 2009)
     */
    private fun antialiased(
        img: IntArray, x1: Int, y1: Int,
        width: Int, height: Int,
        a32: IntArray, b32: IntArray
    ): Boolean {
        val x0 = (x1 - 1).coerceAtLeast(0)
        val y0 = (y1 - 1).coerceAtLeast(0)
        val x2 = (x1 + 1).coerceAtMost(width - 1)
        val y2 = (y1 + 1).coerceAtMost(height - 1)
        val pos4 = y1 * width + x1
        val centerPixel = img[pos4]
        var zeroes = if (x1 == x0 || x1 == x2 || y1 == y0 || y1 == y2) 1 else 0
        var min = 0f
        var max = 0f
        var minX = 0; var minY = 0
        var maxX = 0; var maxY = 0

        for (x in x0..x2) {
            for (y in y0..y2) {
                if (x == x1 && y == y1) continue
                val delta = brightnessDelta(centerPixel, img[y * width + x])
                when {
                    delta == 0f -> {
                        zeroes++
                        if (zeroes > 2) return false
                    }
                    delta < min -> { min = delta; minX = x; minY = y }
                    delta > max -> { max = delta; maxX = x; maxY = y }
                }
            }
        }

        if (min == 0f || max == 0f) return false

        return (hasManySiblings(a32, minX, minY, width, height) &&
                hasManySiblings(b32, minX, minY, width, height)) ||
               (hasManySiblings(a32, maxX, maxY, width, height) &&
                hasManySiblings(b32, maxX, maxY, width, height))
    }

    /**
     * 检查像素是否有 3+ 相同色邻居
     */
    private fun hasManySiblings(
        img: IntArray, x1: Int, y1: Int,
        width: Int, height: Int
    ): Boolean {
        val x0 = (x1 - 1).coerceAtLeast(0)
        val y0 = (y1 - 1).coerceAtLeast(0)
        val x2 = (x1 + 1).coerceAtMost(width - 1)
        val y2 = (y1 + 1).coerceAtMost(height - 1)
        val val_ = img[y1 * width + x1]
        var zeroes = if (x1 == x0 || x1 == x2 || y1 == y0 || y1 == y2) 1 else 0

        for (x in x0..x2) {
            for (y in y0..y2) {
                if (x == x1 && y == y1) continue
                if (val_ == img[y * width + x]) zeroes++
                if (zeroes > 2) return true
            }
        }
        return false
    }
}

data class CompareResult(
    val diffPixels: Int,
    val totalPixels: Int,
    val diffRatio: Float,
    val isSimilar: Boolean
)
