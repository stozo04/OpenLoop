package io.github.stozo04.openloop.camera

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.FrameLayout

/**
 * [FrameLayout] that intercepts two-finger pinch gestures before children (e.g. [PreviewView])
 * consume them. Compose [pointerInput] and [PreviewView.setOnTouchListener] both fail to receive
 * multi-touch on some OEMs when [PreviewView] uses an internal [android.view.SurfaceView].
 */
class PinchZoomLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    var callbacks: PinchZoomCallbacks? = null

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                if (callbacks?.isBound?.invoke() != true) return false
                callbacks?.onBegin?.invoke()
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (callbacks?.isBound?.invoke() != true) return false
                callbacks?.onScale?.invoke(detector.scaleFactor)
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                callbacks?.onEnd?.invoke()
            }
        },
    )

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (event.pointerCount >= 2 || scaleDetector.isInProgress) {
            scaleDetector.onTouchEvent(event)
            return true
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        return true
    }
}

/** Wiring from [io.github.stozo04.openloop.ui.CameraScreen] into [PinchZoomLayout]. */
data class PinchZoomCallbacks(
    val isBound: () -> Boolean,
    val onBegin: () -> Unit,
    val onScale: (Float) -> Unit,
    val onEnd: () -> Unit,
)
