package io.github.stozo04.openloop.camera

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.FrameLayout
import io.github.stozo04.openloop.camera.lens.ViewFlick

/**
 * [FrameLayout] that intercepts two-finger pinch gestures before children (e.g. [PreviewView])
 * consume them. Compose [pointerInput] and [PreviewView.setOnTouchListener] both fail to receive
 * multitouch on some OEMs when [PreviewView] uses an internal [android.view.SurfaceView].
 *
 * Also the home of the single-finger **fling** that flicks a lens
 * (`docs/PRD-lens-interactions.md` §3.1): this layout already owns the viewfinder's touch stream —
 * the one wiring Lesson 025 proved survives OEM input pipelines — so the flick detector lives in
 * the same stream rather than in a second, silently-failing layer. Pinch keeps priority: a stream
 * that ever grew a second finger never reports a fling.
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

    private val flingDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            // Claim the down or the detector never tracks the stream to a fling.
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                // Before any guard, so a fling the guards drop is still visible in logcat — the
                // Fold sessions of 2026-08-26 died silently somewhere in this chain.
                Log.i(
                    TAG,
                    "Fling detected v=($velocityX, $velocityY) " +
                        "pinch=$pinchInStream hasDown=${e1 != null}",
                )
                // A pinch that released into a fast single-finger lift must stay a pinch.
                if (pinchInStream) return false
                // Hit-testing happens on the DOWN position (Lesson 035: everything later is a
                // touch-slop away from where the finger actually landed on the sticker).
                val down = e1 ?: return false
                callbacks?.onFling?.invoke(
                    ViewFlick(
                        downX = down.x,
                        downY = down.y,
                        velocityX = velocityX,
                        velocityY = velocityY,
                        viewWidth = width.toFloat(),
                        viewHeight = height.toFloat(),
                    ),
                )
                return true
            }
        },
    )

    /** Whether the touch stream that is currently down ever became a pinch. */
    private var pinchInStream = false

    /** One-shot: proves in logcat that touch dispatch reaches this layout at all. */
    private var loggedFirstTouch = false

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            pinchInStream = false
            if (!loggedFirstTouch) {
                loggedFirstTouch = true
                Log.i(TAG, "First touch reached PinchZoomLayout at (${event.x}, ${event.y})")
            }
        }
        if (event.pointerCount >= 2 || scaleDetector.isInProgress) {
            pinchInStream = true
            scaleDetector.onTouchEvent(event)
            return true // steal the stream; subsequent events land in onTouchEvent
        }
        // A child may own the single-finger stream outright (PreviewView consumes it on some
        // hardware — the same Lesson 025 pipeline quirk that forced pinch into this hook), and
        // then interception is the ONLY place that sees these events. Feed the fling detector
        // here, observing without stealing; proven on the Fold 2026-08-26, where the
        // onTouchEvent-only wiring below never saw a single event.
        flingDetector.onTouchEvent(event)
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // When no child consumed ACTION_DOWN this view is the touch target and interception is
        // never consulted again — a pinch must still mark the stream here.
        if (event.pointerCount >= 2 || scaleDetector.isInProgress) pinchInStream = true
        // Complete the fling detector's stream for the self-target topology. The DOWN always
        // passed through onInterceptTouchEvent first and was fed there, so it is skipped here —
        // each event reaches the detector exactly once on either topology. A second finger stops
        // the feeding, and the onFling guard drops any fling it had half-tracked.
        if (!pinchInStream && event.actionMasked != MotionEvent.ACTION_DOWN) {
            flingDetector.onTouchEvent(event)
        }
        scaleDetector.onTouchEvent(event)
        // Accessibility contract for a touch-handling view (ClickableViewAccessibility): a
        // completed single-finger tap must route through performClick(). After a pinch the final
        // ACTION_UP also lands here (interception redirected the stream) — that is not a tap.
        // A fling's ACTION_UP also arrives here; GestureDetector separates the two by velocity,
        // and performClick stays a no-op today, so a flick incidentally counting as a click has
        // no effect to double.
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

    private companion object {
        /** Same tag CameraScreen's pinch wiring logs under, so one filter shows the whole story. */
        const val TAG = "OpenLoopPinchZoom"
    }
}

/** Wiring from [io.github.stozo04.openloop.ui.CameraScreen] into [PinchZoomLayout]. */
data class PinchZoomCallbacks(
    val isBound: () -> Boolean,
    val onBegin: () -> Unit,
    val onScale: (Float) -> Unit,
    val onEnd: () -> Unit,
    /** A single-finger fling over the viewfinder — the lens flick. Default keeps old call sites. */
    val onFling: (ViewFlick) -> Unit = {},
)
