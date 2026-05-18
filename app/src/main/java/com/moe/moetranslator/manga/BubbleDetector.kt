package com.moe.moetranslator.manga

import android.graphics.Rect
import com.moe.moetranslator.bridge.TextBlockInfo
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class BubbleRegion(
    val rect: Rect,
    val texts: List<String>
)

object BubbleDetector {

    private const val CLUSTER_DISTANCE_THRESHOLD = 80f
    private const val BUBBLE_EXPAND_PX = 20

    fun detectBubbles(textBlocks: List<TextBlockInfo>): List<BubbleRegion> {
        if (textBlocks.isEmpty()) return emptyList()
        val validBlocks = textBlocks.filter { it.boundingBox != null }
        if (validBlocks.isEmpty()) return emptyList()

        val clusters = clusterTextBlocks(validBlocks)

        return clusters.map { cluster ->
            val boundingRect = computeBoundingRect(cluster)
            val expandedRect = expandRect(boundingRect, BUBBLE_EXPAND_PX)
            BubbleRegion(
                rect = expandedRect,
                texts = cluster.map { it.text }
            )
        }
    }

    private fun clusterTextBlocks(blocks: List<TextBlockInfo>): List<List<TextBlockInfo>> {
        val clusters = mutableListOf<MutableList<TextBlockInfo>>()
        for (block in blocks) {
            var merged = false
            for (cluster in clusters) {
                if (isNearCluster(block, cluster)) {
                    cluster.add(block)
                    merged = true
                    break
                }
            }
            if (!merged) {
                clusters.add(mutableListOf(block))
            }
        }
        return clusters
    }

    private fun isNearCluster(block: TextBlockInfo, cluster: List<TextBlockInfo>): Boolean {
        val blockCenter = getCenter(block.boundingBox!!)
        return cluster.any { other ->
            val otherCenter = getCenter(other.boundingBox!!)
            distance(blockCenter, otherCenter) < CLUSTER_DISTANCE_THRESHOLD
        }
    }

    private fun getCenter(rect: Rect): Pair<Float, Float> {
        return Pair(rect.centerX().toFloat(), rect.centerY().toFloat())
    }

    private fun distance(a: Pair<Float, Float>, b: Pair<Float, Float>): Float {
        val dx = a.first - b.first
        val dy = a.second - b.second
        return sqrt(dx * dx + dy * dy)
    }

    private fun computeBoundingRect(blocks: List<TextBlockInfo>): Rect {
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        var bottom = Int.MIN_VALUE
        for (block in blocks) {
            val box = block.boundingBox!!
            left = min(left, box.left)
            top = min(top, box.top)
            right = max(right, box.right)
            bottom = max(bottom, box.bottom)
        }
        return Rect(left, top, right, bottom)
    }

    private fun expandRect(rect: Rect, px: Int): Rect {
        return Rect(
            maxOf(0, rect.left - px),
            maxOf(0, rect.top - px),
            rect.right + px,
            rect.bottom + px
        )
    }
}
