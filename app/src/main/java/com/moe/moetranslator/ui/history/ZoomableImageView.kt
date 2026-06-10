package com.moe.moetranslator.ui.history

import android.content.Context
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import androidx.appcompat.widget.AppCompatImageView

/**
 * 支持双指缩放、双击缩放、拖动平移的 ImageView。
 * 与 ViewPager2 配合时，缩放状态下会拦截水平滑动以平移图片。
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val matrix = Matrix()
    private val savedMatrix = Matrix()
    private val matrixValues = FloatArray(9)

    private var mode = NONE
    private var startPoint = PointF()
    private var midPoint = PointF()
    private var oldDist = 1f

    private var minScale = 1f
    private var maxScale = 5f
    private var currentScale = 1f

    private var isInitialized = false

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val scaleFactor = detector.scaleFactor
            val newScale = currentScale * scaleFactor
            if (newScale in minScale..maxScale) {
                matrix.postScale(scaleFactor, scaleFactor, detector.focusX, detector.focusY)
                currentScale = newScale
                constrainMatrix()
                imageMatrix = matrix
            }
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (currentScale > 1.5f) {
                // 缩小到原始大小
                animateScale(currentScale, 1f, e.x, e.y)
            } else {
                // 放大到 2.5x
                animateScale(currentScale, 2.5f, e.x, e.y)
            }
            return true
        }
    })

    private var scroller: OverScroller? = null

    init {
        scaleType = ScaleType.MATRIX
        scroller = OverScroller(context, DecelerateInterpolator())
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        isInitialized = false
        requestLayout()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (!isInitialized && drawable != null) {
            fitCenter()
            isInitialized = true
        }
    }

    /**
     * 将图片居中适配到 View 内
     */
    private fun fitCenter() {
        val d = drawable ?: return
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        val imgW = d.intrinsicWidth.toFloat()
        val imgH = d.intrinsicHeight.toFloat()

        if (imgW == 0f || imgH == 0f) return

        val scale = minOf(viewW / imgW, viewH / imgH)
        val dx = (viewW - imgW * scale) / 2f
        val dy = (viewH - imgH * scale) / 2f

        matrix.reset()
        matrix.setScale(scale, scale)
        matrix.postTranslate(dx, dy)
        currentScale = 1f
        imageMatrix = matrix
    }

    /**
     * 限制矩阵，防止图片移出可视区域
     */
    private fun constrainMatrix() {
        val d = drawable ?: return
        val imgW = d.intrinsicWidth.toFloat()
        val imgH = d.intrinsicHeight.toFloat()

        matrix.getValues(matrixValues)
        val scaleX = matrixValues[Matrix.MSCALE_X]
        val scaleY = matrixValues[Matrix.MSCALE_Y]
        val transX = matrixValues[Matrix.MTRANS_X]
        val transY = matrixValues[Matrix.MTRANS_Y]

        val scaledW = imgW * scaleX
        val scaledH = imgH * scaleY
        val viewW = width.toFloat()
        val viewH = height.toFloat()

        var dx = 0f
        var dy = 0f

        if (scaledW <= viewW) {
            // 图片比视图窄，居中
            dx = (viewW - scaledW) / 2f - transX
        } else {
            // 图片比视图宽，限制边界
            if (transX > 0) dx = -transX
            if (transX + scaledW < viewW) dx = viewW - (transX + scaledW)
        }

        if (scaledH <= viewH) {
            dy = (viewH - scaledH) / 2f - transY
        } else {
            if (transY > 0) dy = -transY
            if (transY + scaledH < viewH) dy = viewH - (transY + scaledH)
        }

        if (dx != 0f || dy != 0f) {
            matrix.postTranslate(dx, dy)
        }
    }

    private fun animateScale(from: Float, to: Float, focusX: Float, focusY: Float) {
        val animator = android.animation.ValueAnimator.ofFloat(from, to).apply {
            duration = 250
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val value = anim.animatedValue as Float
                val factor = value / currentScale
                matrix.postScale(factor, factor, focusX, focusY)
                currentScale = value
                constrainMatrix()
                imageMatrix = matrix
            }
        }
        animator.start()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.action and MotionEvent.ACTION_MASK) {
            MotionEvent.ACTION_DOWN -> {
                savedMatrix.set(matrix)
                startPoint.set(event.x, event.y)
                mode = DRAG
                // 通知父 View 不要拦截事件
                parent.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                oldDist = spacing(event)
                if (oldDist > 10f) {
                    savedMatrix.set(matrix)
                    midPoint(event, midPoint)
                    mode = ZOOM
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == DRAG && !scaleDetector.isInProgress) {
                    matrix.set(savedMatrix)
                    val dx = event.x - startPoint.x
                    val dy = event.y - startPoint.y
                    matrix.postTranslate(dx, dy)
                    constrainMatrix()
                    imageMatrix = matrix

                    // 判断是否应该让 ViewPager2 接管
                    if (currentScale <= 1.01f) {
                        // 未缩放时，水平滑动距离大于垂直滑动距离则让父 View 处理
                        if (Math.abs(dx) > Math.abs(dy) && Math.abs(dx) > 10f) {
                            parent.requestDisallowInterceptTouchEvent(false)
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                mode = NONE
            }
        }
        return true
    }

    /**
     * 判断是否已缩放（供外部判断手势冲突）
     */
    fun isZoomed(): Boolean = currentScale > 1.05f

    /**
     * 重置到原始缩放
     */
    fun resetZoom() {
        if (currentScale != 1f) {
            animateScale(currentScale, 1f, width / 2f, height / 2f)
        }
    }

    private fun spacing(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val x = event.getX(0) - event.getX(1)
        val y = event.getY(0) - event.getY(1)
        return Math.sqrt((x * x + y * y).toDouble()).toFloat()
    }

    private fun midPoint(event: MotionEvent, point: PointF) {
        if (event.pointerCount < 2) return
        point.set(
            (event.getX(0) + event.getX(1)) / 2f,
            (event.getY(0) + event.getY(1)) / 2f
        )
    }

    companion object {
        private const val NONE = 0
        private const val DRAG = 1
        private const val ZOOM = 2
    }
}
