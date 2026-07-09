package io.github.stozo04.openloop.camera

import java.util.Locale

/**
 * Immutable UI snapshot of the camera's zoom state, mirrored from CameraX's
 * `CameraInfo.getZoomState()` LiveData by [CameraManager]. `null` upstream means "no camera bound".
 *
 * Pure data in its own file (no camera/Android types) so consumers of the clamp and chip-format
 * rules below are JVM-testable without a device — the [io.github.stozo04.openloop.media.BoomerangSequence]
 * extraction pattern.
 */
data class ZoomUi(
    val ratio: Float,
    val minRatio: Float,
    val maxRatio: Float,
)

/**
 * Clamps a requested zoom ratio to the device-reported range. Never hardcode bounds (PRD D4):
 * devices that expose a sub-1x ultra-wide report `minRatio < 1` and must stay pinch-able below 1x;
 * a degenerate range (`minRatio == maxRatio`) pins to that single value.
 */
internal fun clampZoom(requested: Float, minRatio: Float, maxRatio: Float): Float =
    requested.coerceIn(minRatio, maxRatio)

/** CameraX default / normal-lens ratio after bind (PRD D3 reset-on-rebind). */
internal const val DEFAULT_ZOOM_RATIO = 1.0f

/**
 * When the user releases a pinch within this band of [DEFAULT_ZOOM_RATIO], snap to exactly 1.0x —
 * the stock-camera "magnetic" default. Ultra-wide (~0.5x on Pixel Fold) stays outside this band.
 */
internal const val SNAP_TO_DEFAULT_TOLERANCE = 0.08f

/**
 * Returns [DEFAULT_ZOOM_RATIO] when [ratio] is close enough to the normal lens to treat as
 * "back at default"; otherwise returns [ratio] unchanged (e.g. intentional 0.5x ultra-wide).
 */
internal fun snapZoomToDefaultIfNear(ratio: Float): Float =
    if (ratio in (DEFAULT_ZOOM_RATIO - SNAP_TO_DEFAULT_TOLERANCE)..
            (DEFAULT_ZOOM_RATIO + SNAP_TO_DEFAULT_TOLERANCE)
    ) {
        DEFAULT_ZOOM_RATIO
    } else {
        ratio
    }

/** Reported ratio within this margin of the default is "already reset" — no forced write. */
internal const val ZOOM_RESET_EPSILON = 0.01f

/**
 * PRD D3: every rebind starts at [DEFAULT_ZOOM_RATIO]. CameraX resets zoom when the camera
 * closes, but some devices (Fold-class ultra-wide) report a sub-1.0 ratio on the first zoom-state
 * emission after bind. Decides, from that first emission, the ratio to force — or `null` when the
 * reported ratio already sits at the range-clamped default (e.g. a degenerate `[1.0, 1.0]`
 * emulator range).
 */
internal fun zoomResetTargetAfterBind(
    reportedRatio: Float,
    minRatio: Float,
    maxRatio: Float,
): Float? {
    val target = clampZoom(DEFAULT_ZOOM_RATIO, minRatio, maxRatio)
    return if (kotlin.math.abs(reportedRatio - target) > ZOOM_RESET_EPSILON) target else null
}

/**
 * Final ratio within this margin of [minRatio] is intentional ultra-wide — do not snap to 1.0x.
 */
internal const val ULTRA_WIDE_AT_MIN_EPSILON = 0.02f

/**
 * Post-gesture snap: magnetic 1.0x when near default, plus correction when a pinch-in / pinch-out
 * round trip undershoots below 1.0x multiplicatively (e.g. 2.0x → 0.5x) but the user clearly
 * zoomed in first ([sessionPeakRatio] > 1.0). Intentional ultra-wide from 1.0x (peak ≈ 1.0,
 * final ≈ min) is left alone.
 */
internal fun snapZoomOnGestureEnd(
    sessionPeakRatio: Float,
    finalRatio: Float,
    minRatio: Float,
): Float {
    snapZoomToDefaultIfNear(finalRatio).let { if (it != finalRatio) return it }

    val undershotAfterZoomIn =
        sessionPeakRatio > DEFAULT_ZOOM_RATIO + 0.03f &&
            finalRatio < DEFAULT_ZOOM_RATIO - SNAP_TO_DEFAULT_TOLERANCE &&
            finalRatio > minRatio + ULTRA_WIDE_AT_MIN_EPSILON

    return if (undershotAfterZoomIn) DEFAULT_ZOOM_RATIO else finalRatio
}

/**
 * Chip label for a zoom ratio: one decimal + `x` — `1.0x`, `2.3x`, `0.5x`. [Locale.US] keeps the
 * separator a dot on every device locale, matching the mock and the stock-camera convention.
 */
internal fun formatZoomRatio(ratio: Float): String = String.format(Locale.US, "%.1fx", ratio)

/**
 * Chip label with near-default magnetic display: ratios within [SNAP_TO_DEFAULT_TOLERANCE] of 1.0x
 * read as `1.0x` so OEM float noise (0.97) and post-snap lag do not flash `0.9x`.
 */
internal fun formatZoomRatioForChip(ratio: Float): String =
    formatZoomRatio(snapZoomToDefaultIfNear(ratio))
