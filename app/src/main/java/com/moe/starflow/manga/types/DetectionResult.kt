package com.moe.starflow.manga.types
import com.moe.starflow.translate.screenshot.*

import android.graphics.Bitmap
import android.graphics.Rect

/** ML Kit 调试模式结果（从 DetectionBridge.kt 提取，被 MangaFloatingService/debug 共享） */
data class MLKitDebugResult(
    val textBlocks: List<MLKitDebugBlock>,  // 所有文字块
    val totalLines: Int,                     // 总行数
    val totalElements: Int,                  // 总元素数
    val detectedLanguage: String?            // 检测到的语言
)

data class MLKitDebugBlock(
    val blockText: String,                   // 块文字
    val blockRect: android.graphics.Rect?,   // 块边界框
    val blockCorners: Array<android.graphics.Point>?, // 块四角点
    val lines: List<MLKitDebugLine>,         // 行列表
    val language: String?                    // 识别语言
)

data class MLKitDebugLine(
    val lineText: String,                    // 行文字
    val lineRect: android.graphics.Rect?,    // 行边界框
    val lineCorners: Array<android.graphics.Point>?, // 行四角点
    val angle: Float,                        // 行倾斜角度
    val elements: List<MLKitDebugElement>    // 元素列表
)

data class MLKitDebugElement(
    val elementText: String,                 // 元素文字
    val elementRect: android.graphics.Rect?, // 元素边界框
    val elementCorners: Array<android.graphics.Point>? // 元素四角点
)

/**
 * RT-DETR-V2 调试模式结果
 */
data class RTDetrV2DebugResult(
    val allBubbles: List<DetectedBubble>,  // 所有检测结果（含 classId=0）
    val textBubbles: List<DetectedBubble>,  // classId=1
    val textFree: List<DetectedBubble>,     // classId=2
    val emptyBubbles: List<DetectedBubble>, // classId=0
    val finalRegions: List<Rect>                                // 最终提交给OCR的区域（压缩+去重后）
)

/**
 * 检测+裁剪结果，用于分批 OCR 识别。
 * @param croppedBitmap 裁剪后的图片（含 10px padding）
 * @param rect 原图中的位置
 * @param classId RT-DETR-V2 类别：0=bubble(红色), 1=text_bubble(绿色)
 * @param confidence 检测置信度
 */
data class CroppedBubble(
    val croppedBitmap: Bitmap,
    val rect: Rect,
    val classId: Int,
    val confidence: Float
)

/**
 * 裁剪后的单行文字，用于 PP-OCRv5 增量渲染的分批 OCR。
 * @param croppedBitmap 透视裁剪后的图片
 * @param rect 在原图中的位置
 * @param angle 倾斜角（度），±3° 内视为正交
 * @param centerX 选区中心 X
 * @param centerY 选区中心 Y
 */
data class CroppedTextLine(
    val croppedBitmap: Bitmap,
    val rect: Rect,
    val angle: Float = 0f,
    val centerX: Float = -1f,
    val centerY: Float = -1f
)
