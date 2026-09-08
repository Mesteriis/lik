package io.github.mesteriis.lik.gallery

import android.content.Context
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ImageView
import kotlin.math.abs
import kotlin.math.min

class ZoomImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ImageView(context, attrs) {
    var onNavigate: ((Int) -> Unit)? = null
    private val transform = Matrix()
    private var zoom = 1f

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val next = (zoom * detector.scaleFactor).coerceIn(1f, MAX_ZOOM)
            val factor = next / zoom
            zoom = next
            transform.postScale(factor, factor, detector.focusX, detector.focusY)
            imageMatrix = transform
            return true
        }
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent) = true

        override fun onDoubleTap(event: MotionEvent): Boolean {
            if (zoom > 1.05f) resetTransform() else {
                val factor = DOUBLE_TAP_ZOOM / zoom
                zoom = DOUBLE_TAP_ZOOM
                transform.postScale(factor, factor, event.x, event.y)
                imageMatrix = transform
            }
            return true
        }

        override fun onScroll(first: MotionEvent?, current: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (zoom <= 1.01f || scaler.isInProgress) return false
            transform.postTranslate(-distanceX, -distanceY)
            imageMatrix = transform
            return true
        }

        override fun onFling(first: MotionEvent?, current: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (zoom > 1.01f || abs(velocityX) < abs(velocityY) || abs(velocityX) < MIN_FLING) return false
            onNavigate?.invoke(if (velocityX < 0) 1 else -1)
            return true
        }
    })

    init {
        scaleType = ScaleType.MATRIX
        isClickable = true
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        post { resetTransform() }
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        resetTransform()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(zoom > 1.01f || event.pointerCount > 1)
        val handled = scaler.onTouchEvent(event) or gestures.onTouchEvent(event)
        if (event.action == MotionEvent.ACTION_UP) performClick()
        return handled || super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    fun resetTransform() {
        val image = drawable ?: return
        if (width == 0 || height == 0 || image.intrinsicWidth <= 0 || image.intrinsicHeight <= 0) return
        val baseScale = min(width.toFloat() / image.intrinsicWidth, height.toFloat() / image.intrinsicHeight)
        val dx = (width - image.intrinsicWidth * baseScale) / 2f
        val dy = (height - image.intrinsicHeight * baseScale) / 2f
        transform.reset()
        transform.postScale(baseScale, baseScale)
        transform.postTranslate(dx, dy)
        zoom = 1f
        imageMatrix = transform
    }

    companion object {
        private const val MAX_ZOOM = 5f
        private const val DOUBLE_TAP_ZOOM = 2.5f
        private const val MIN_FLING = 700f
    }
}
