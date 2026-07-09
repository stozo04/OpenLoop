# Lesson 025 — Pinch gestures over a `PreviewView` need a parent `onInterceptTouchEvent`, not a Compose overlay or a touch listener

> Origin: PR [#100](https://github.com/stozo04/OpenLoop/pull/100) (capture pinch-zoom, Fold-class hardware testing).

## What went wrong

Pinch-to-zoom on the live viewfinder worked on the emulator but **never started on a Pixel Fold**: the gesture callbacks were simply not invoked. Two textbook wirings both failed the same way:

1. A Compose `Modifier.pointerInput { detectTransformGestures { … } }` overlay above the `AndroidView` hosting the `PreviewView`.
2. The CameraX-documented `previewView.setOnTouchListener` + `ScaleGestureDetector` (PRD-capture-zoom §4.3's original design).

Root cause: when `PreviewView` runs on its `SurfaceView` path (and on some OEM/foldable input pipelines even beyond that), the second pointer's `ACTION_POINTER_DOWN` is consumed before either the Compose pointer system or the view's own touch listener sees it. One finger arrives; the moment the gesture becomes a pinch, the stream goes silent — so a scale detector attached at or above the `PreviewView` never transitions to `isInProgress`.

## Pattern

Intercept the multi-touch stream **in a parent `ViewGroup`, before child dispatch**, using the framework's interception hook — that is the one place OEM input quirks cannot route around:

```kotlin
class PinchZoomLayout(context: Context) : FrameLayout(context) {
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (event.pointerCount >= 2 || scaleDetector.isInProgress) {
            scaleDetector.onTouchEvent(event)
            return true // steal the stream; subsequent events land in onTouchEvent
        }
        return false // single-finger touches keep flowing to children
    }
}
```

Wrap the `PreviewView` in this layout inside the `remember {}` block and hand the layout to `AndroidView`. Single-finger events still pass through untouched, so Compose control overlays (shutter, flip, home) keep their taps. Because the layout handles raw touch, it must also honor the `ClickableViewAccessibility` contract: override `performClick()` and invoke it from `onTouchEvent` on a completed single-finger `ACTION_UP` — and **not** after a pinch, whose final `ACTION_UP` also lands in `onTouchEvent` once interception redirects the stream.

## Detection checklist

- Any gesture that must work **over** an `AndroidView`-hosted `SurfaceView` (camera preview, video surface): test it on physical foldable hardware, not just the emulator — the emulator's input path delivers multi-touch where real devices may not.
- Grep for `setOnTouchListener` or `pointerInput` attached to/over a `PreviewView` — both are silent-failure wirings for multi-touch; use the parent-intercept layout instead.
- Log gesture begin/end (`OpenLoopPinchZoom` tag): a pinch on hardware that produces zero begin logs is this bug.
- New `View` subclass handling touch: `./gradlew :app:lintDebug` must show no `ClickableViewAccessibility` finding for it.

## Reference

- [Manage touch events in a ViewGroup — `onInterceptTouchEvent`](https://developer.android.com/develop/ui/views/touch-and-input/gestures/viewgroup)
- [Detect scaling gestures — `ScaleGestureDetector`](https://developer.android.com/develop/ui/views/touch-and-input/gestures/scale)
- PRD-capture-zoom §4.3 (original design + shipped deviation note)
