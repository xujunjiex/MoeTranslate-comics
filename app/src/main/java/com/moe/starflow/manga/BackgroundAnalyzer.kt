package com.moe.starflow.manga

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect

object BackgroundAnalyzer {

    fun analyzeBackground(bitmap: Bitmap, region: Rect): Int {
        val safeRegion = Rect(
            region.left.coerceIn(0, bitmap.width - 1),
            region.top.coerceIn(0, bitmap.height - 1),
            region.right.coerceIn(0, bitmap.width),
            region.bottom.coerceIn(0, bitmap.height)
        )

        val samples = mutableListOf<Int>()

        for (x in safeRegion.left until safeRegion.right step 5) {
            samples.add(bitmap.getPixel(x, safeRegion.top))
        }
        for (x in safeRegion.left until safeRegion.right step 5) {
            samples.add(bitmap.getPixel(x, (safeRegion.bottom - 1).coerceAtLeast(0)))
        }
        for (y in safeRegion.top until safeRegion.bottom step 5) {
            samples.add(bitmap.getPixel(safeRegion.left, y))
        }
        for (y in safeRegion.top until safeRegion.bottom step 5) {
            samples.add(bitmap.getPixel((safeRegion.right - 1).coerceAtLeast(0), y))
        }

        if (samples.isEmpty()) return Color.WHITE
        return averageColor(samples)
    }

    private fun averageColor(colors: List<Int>): Int {
        var r = 0L
        var g = 0L
        var b = 0L
        for (color in colors) {
            r += Color.red(color)
            g += Color.green(color)
            b += Color.blue(color)
        }
        val count = colors.size
        return Color.rgb((r / count).toInt(), (g / count).toInt(), (b / count).toInt())
    }
}
