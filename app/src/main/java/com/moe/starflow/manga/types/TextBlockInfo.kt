package com.moe.starflow.manga.types

import android.graphics.Rect

/** ML Kit 识别结果的一个文本块（游戏/漫画共用）。 */
data class TextBlockInfo(
    val text: String,
    val boundingBox: Rect?,
    val cornerPoints: Array<android.graphics.Point>?,
    val isVertical: Boolean? = null,  // 新增: 竖排=true, 横排=false, null=从config推断
    val angle: Float = 0f,
    val centerX: Float = -1f,
    val centerY: Float = -1f
)
