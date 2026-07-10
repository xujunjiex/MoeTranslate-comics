package com.moe.starflow.bridge

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.moe.starflow.utils.LogCollector
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class TextBlockInfo(
    val text: String,
    val boundingBox: Rect?,
    val cornerPoints: Array<android.graphics.Point>?,
    val isVertical: Boolean? = null,  // 新增: 竖排=true, 横排=false, null=从config推断
    val angle: Float = 0f,
    val centerX: Float = -1f,
    val centerY: Float = -1f
)

object OCRBridge {

    private const val TAG = "OCRBridge"

    suspend fun recognizeWithLocation(
        language: String,
        bitmap: Bitmap
    ): List<TextBlockInfo> = suspendCancellableCoroutine { continuation ->
        val recognizer = getTextRecognizer(language)
        LogCollector.d(TAG, "recognizeWithLocation: bitmap=${bitmap.width}x${bitmap.height}")
        val image = InputImage.fromBitmap(bitmap, 0)

        recognizer.process(image)
            .addOnSuccessListener { result ->
                val blocks = result.textBlocks.map { block ->
                    LogCollector.d(TAG, "recognizeWithLocation: block boundingBox=${block.boundingBox}, text='${block.text.take(20)}'")
                    TextBlockInfo(
                        text = block.text,
                        boundingBox = block.boundingBox,
                        cornerPoints = block.cornerPoints
                    )
                }
                continuation.resume(blocks)
            }
            .addOnFailureListener { e ->
                continuation.resumeWithException(e)
            }
            .addOnCompleteListener {
                recognizer.close()
            }
    }

    suspend fun recognizeText(language: String, bitmap: Bitmap): String {
        LogCollector.d(TAG, "recognizeText: bitmap=${bitmap.width}x${bitmap.height}")
        return com.moe.starflow.translate.OCRTextRecognizer.getPicText(language, bitmap)
    }

    private fun getTextRecognizer(language: String): TextRecognizer {
        return when (language) {
            "zh", "zh-CN", "zh-TW" -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            "ja" -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }
    }
}
