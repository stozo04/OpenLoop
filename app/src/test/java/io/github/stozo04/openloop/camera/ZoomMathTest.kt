package io.github.stozo04.openloop.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure zoom math in `ZoomUi.kt` — the clamp rule applied by
 * `CameraManager.setZoomRatio` and the ratio chip's label format (PRD-capture-zoom §6.1).
 * No camera, no Android: the same extraction rationale as [io.github.stozo04.openloop.media.BoomerangSequence].
 */
class ZoomMathTest {

    // ── clampZoom: honor the device-reported range, never hardcode (D4) ──

    @Test
    fun clampZoom_belowMin_clampsToMin() {
        assertEquals(1.0f, clampZoom(requested = 0.3f, minRatio = 1.0f, maxRatio = 8.0f), 0f)
    }

    @Test
    fun clampZoom_aboveMax_clampsToMax() {
        assertEquals(8.0f, clampZoom(requested = 12.7f, minRatio = 1.0f, maxRatio = 8.0f), 0f)
    }

    @Test
    fun clampZoom_inRange_isUnchanged() {
        assertEquals(2.3f, clampZoom(requested = 2.3f, minRatio = 1.0f, maxRatio = 8.0f), 0f)
    }

    @Test
    fun clampZoom_degenerateRange_pinsToThatValue() {
        assertEquals(1.0f, clampZoom(requested = 3.0f, minRatio = 1.0f, maxRatio = 1.0f), 0f)
        assertEquals(1.0f, clampZoom(requested = 0.5f, minRatio = 1.0f, maxRatio = 1.0f), 0f)
    }

    @Test
    fun clampZoom_sub1xMin_allowsUltraWideButNotBelow() {
        // Pixel-style device range: ultra-wide down to 0.55x.
        assertEquals(0.7f, clampZoom(requested = 0.7f, minRatio = 0.55f, maxRatio = 8.0f), 0f)
        assertEquals(0.55f, clampZoom(requested = 0.2f, minRatio = 0.55f, maxRatio = 8.0f), 0f)
    }

    // ── Scale accumulation: a simulated pinch stream (ratio × scaleFactor per tick) never escapes ──

    @Test
    fun clampZoom_pinchStreamAccumulation_neverEscapesRange() {
        val min = 0.55f
        val max = 8.0f
        var ratio = 1.0f
        // Pinch out far past max…
        repeat(40) {
            ratio = clampZoom(ratio * 1.15f, min, max)
            assertTrue("ratio $ratio escaped [$min, $max]", ratio in min..max)
        }
        assertEquals(max, ratio, 0f)
        // …then pinch in far past min.
        repeat(80) {
            ratio = clampZoom(ratio * 0.9f, min, max)
            assertTrue("ratio $ratio escaped [$min, $max]", ratio in min..max)
        }
        assertEquals(min, ratio, 0f)
    }

    // ── Snap to 1.0x on gesture end when near default (not ultra-wide) ──

    @Test
    fun snapZoomToDefaultIfNear_withinBand_snapsToOne() {
        assertEquals(1.0f, snapZoomToDefaultIfNear(1.0f), 0f)
        assertEquals(1.0f, snapZoomToDefaultIfNear(0.96f), 0f)
        assertEquals(1.0f, snapZoomToDefaultIfNear(1.07f), 0f)
    }

    @Test
    fun snapZoomToDefaultIfNear_outsideBand_unchanged() {
        assertEquals(0.5f, snapZoomToDefaultIfNear(0.5f), 0f)
        assertEquals(2.3f, snapZoomToDefaultIfNear(2.3f), 0f)
        assertEquals(0.89f, snapZoomToDefaultIfNear(0.89f), 0f)
    }

    @Test
    fun snapZoomOnGestureEnd_undershootAfterZoomIn_snapsToDefault() {
        // Fold-style min; simulates 2x → multiplicative undershoot to ~0.53x on release.
        assertEquals(1.0f, snapZoomOnGestureEnd(sessionPeakRatio = 2.5f, finalRatio = 0.534f, minRatio = 0.506f), 0f)
    }

    @Test
    fun snapZoomOnGestureEnd_intentionalUltraWideFromDefault_staysSub1x() {
        assertEquals(0.55f, snapZoomOnGestureEnd(sessionPeakRatio = 1.0f, finalRatio = 0.55f, minRatio = 0.506f), 0f)
    }

    @Test
    fun snapZoomOnGestureEnd_nearDefault_staysSnappedToOne() {
        assertEquals(1.0f, snapZoomOnGestureEnd(sessionPeakRatio = 2.0f, finalRatio = 0.97f, minRatio = 0.506f), 0f)
    }

    @Test
    fun snapZoomOnGestureEnd_staysZoomedIn_unchanged() {
        assertEquals(2.0f, snapZoomOnGestureEnd(sessionPeakRatio = 2.5f, finalRatio = 2.0f, minRatio = 0.506f), 0f)
    }

    // ── Chip label format: one decimal + "x", dot separator on every device locale ──

    @Test
    fun formatZoomRatio_wholeNumber_keepsOneDecimal() {
        assertEquals("1.0x", formatZoomRatio(1.0f))
    }

    @Test
    fun formatZoomRatio_fractional_showsOneDecimal() {
        assertEquals("2.3x", formatZoomRatio(2.3f))
    }

    @Test
    fun formatZoomRatio_sub1x_showsLeadingZero() {
        assertEquals("0.5x", formatZoomRatio(0.5f))
    }

    @Test
    fun formatZoomRatioForChip_nearDefault_readsAsOne() {
        assertEquals("1.0x", formatZoomRatioForChip(0.97f))
        assertEquals("1.0x", formatZoomRatioForChip(1.05f))
    }

    @Test
    fun formatZoomRatioForChip_intentionalUltraWide_unchanged() {
        assertEquals("0.5x", formatZoomRatioForChip(0.5f))
        assertEquals("2.3x", formatZoomRatioForChip(2.3f))
    }

    @Test
    fun snapZoomOnGestureEnd_mildZoomInThenUndershoot_snapsToDefault() {
        assertEquals(1.0f, snapZoomOnGestureEnd(sessionPeakRatio = 1.2f, finalRatio = 0.534f, minRatio = 0.506f), 0f)
    }

    @Test
    fun formatZoomRatio_nearMaxRatio_roundsToOneDecimal() {
        assertEquals("8.0x", formatZoomRatio(7.99f))
    }
}
