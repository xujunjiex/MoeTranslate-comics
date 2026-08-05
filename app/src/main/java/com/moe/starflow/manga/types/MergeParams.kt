package com.moe.starflow.manga.types
import com.moe.starflow.translate.autotranslate.*
import com.moe.starflow.translate.screenshot.*

/**
 * TextRegionMerger 可调参数。
 * 其余参数 (RATIO, ASPECT_RATIO_TOL, CHAR_GAP, TILTED_*) hardcoded 不可调。
 */
data class MergeParams(
    val discardConnectionGap: Float = DISCARD_CONNECTION_GAP_DEFAULT,
    val charGapTolerance2: Float = CHAR_GAP_TOLERANCE2_DEFAULT
) {
    companion object {
        const val DISCARD_CONNECTION_GAP_DEFAULT = 1.5f
        const val CHAR_GAP_TOLERANCE2_DEFAULT = 3.0f
        const val MIN_DISCARD_GAP = 1.0f
        const val MAX_DISCARD_GAP = 3.0f
        const val MIN_CHAR_GAP2 = 1.0f
        const val MAX_CHAR_GAP2 = 5.0f
    }
}
