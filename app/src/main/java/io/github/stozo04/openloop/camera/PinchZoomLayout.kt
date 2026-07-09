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

    /** Whether the touch stream that is currently down ever became a pinch. */
    private var pinchInStream = false

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) pinchInStream = false
        if (event.pointerCount >= 2 || scaleDetector.isInProgress) {
            pinchInStream = true
            scaleDetector.onTouchEvent(event)
            return true
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // When no child consumed ACTION_DOWN this view is the touch target and interception is
        // never consulted again — a pinch must still mark the stream here.
        if (event.pointerCount >= 2 || scaleDetector.isInProgress) pinchInStream = true
        scaleDetector.onTouchEvent(event)
        // Accessibility contract for a touch-handling view (ClickableViewAccessibility): a
        // completed single-finger tap must route through performClick(). After a pinch the final
        // ACTION_UP also lands here (interception redirected the stream) — that is not a tap.
        if (event.actionMasked == MotionEvent.ACTION_UP && !pinchInStream) {
            performClick()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        // The viewfinder has no tap action today (tap-to-focus would hook in here).
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
