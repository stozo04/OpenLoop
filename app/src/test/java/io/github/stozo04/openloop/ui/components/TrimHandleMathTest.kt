package io.github.stozo04.openloop.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the trim-handle clamp math extracted from `FilmstripTrimSelector`.
 *
 * Regression coverage for Crashlytics 7169b499 / issue #95 (Lesson 025): a 335 ms clip on the
 * Trim screen inverted the `coerceIn` range (`maximum 335 is less than minimum 400`) and crashed
 * on the first handle drag or accessibility `setProgress`. The clamp must never throw, for any
 * combination of clip duration and handle positions.
 */
class TrimHandleMathTest {

    private companion object {
        /** OpenLoopViewModel.MIN_TRIM_DURATION at the time of the crash. */
        const val MIN_GAP_MS = 400L

        /** The exact clip duration from the Crashlytics report. */
        const val CRASH_DURATION_MS = 335L
    }

    // ── Regression: clip shorter than the minimum trim window (the issue #95 crash) ──

    @Test
    fun `end handle on 335ms clip does not throw and pins to clip duration`() {
        // Was coerceIn(0 + 400, 335) -> IllegalArgumentException before the guard.
        val clamped = clampTrimEndMs(
            targetMs = 200L,
            startMs = 0L,
            durationMs = CRASH_DURATION_MS,
            minGapMs = MIN_GAP_MS,
        )
        assertEquals(CRASH_DURATION_MS, clamped)
    }

    @Test
    fun `start handle on 335ms clip does not throw and pins to zero`() {
        // Was coerceIn(0, 335 - 400) -> inverted range before the guard.
        val clamped = clampTrimStartMs(
            targetMs = 200L,
            endMs = CRASH_DURATION_MS,
            minGapMs = MIN_GAP_MS,
        )
        assertEquals(0L, clamped)
    }

    @Test
    fun `short clip handles are immovable regardless of drag target`() {
        for (targetMs in longArrayOf(Long.MIN_VALUE, -1L, 0L, 100L, 335L, 400L, Long.MAX_VALUE)) {
            assertEquals(0L, clampTrimStartMs(targetMs, endMs = CRASH_DURATION_MS, minGapMs = MIN_GAP_MS))
            assertEquals(
                CRASH_DURATION_MS,
                clampTrimEndMs(targetMs, startMs = 0L, durationMs = CRASH_DURATION_MS, minGapMs = MIN_GAP_MS),
            )
        }
    }

    // ── Normal clips: original clamp behavior is preserved ──

    @Test
    fun `start handle clamps to min gap below the end handle`() {
        val clamped = clampTrimStartMs(targetMs = 4_900L, endMs = 5_000L, minGapMs = MIN_GAP_MS)
        assertEquals(4_600L, clamped)
    }

    @Test
    fun `start handle clamps negative drag to zero`() {
        val clamped = clampTrimStartMs(targetMs = -250L, endMs = 5_000L, minGapMs = MIN_GAP_MS)
        assertEquals(0L, clamped)
    }

    @Test
    fun `start handle passes through an in-range target`() {
        val clamped = clampTrimStartMs(targetMs = 1_200L, endMs = 5_000L, minGapMs = MIN_GAP_MS)
        assertEquals(1_200L, clamped)
    }

    @Test
    fun `end handle clamps to min gap above the start handle`() {
        val clamped = clampTrimEndMs(targetMs = 2_100L, startMs = 2_000L, durationMs = 10_000L, minGapMs = MIN_GAP_MS)
        assertEquals(2_400L, clamped)
    }

    @Test
    fun `end handle clamps overshoot to clip duration`() {
        val clamped = clampTrimEndMs(targetMs = 12_000L, startMs = 0L, durationMs = 10_000L, minGapMs = MIN_GAP_MS)
        assertEquals(10_000L, clamped)
    }

    @Test
    fun `end handle passes through an in-range target`() {
        val clamped = clampTrimEndMs(targetMs = 7_500L, startMs = 2_000L, durationMs = 10_000L, minGapMs = MIN_GAP_MS)
        assertEquals(7_500L, clamped)
    }

    // ── Boundary durations ──

    @Test
    fun `clip exactly at the minimum trim window pins both handles`() {
        assertEquals(0L, clampTrimStartMs(targetMs = 150L, endMs = MIN_GAP_MS, minGapMs = MIN_GAP_MS))
        assertEquals(
            MIN_GAP_MS,
            clampTrimEndMs(targetMs = 150L, startMs = 0L, durationMs = MIN_GAP_MS, minGapMs = MIN_GAP_MS),
        )
    }

    @Test
    fun `zero duration clip does not throw`() {
        assertEquals(0L, clampTrimStartMs(targetMs = 50L, endMs = 0L, minGapMs = MIN_GAP_MS))
        assertEquals(0L, clampTrimEndMs(targetMs = 50L, startMs = 0L, durationMs = 0L, minGapMs = MIN_GAP_MS))
    }

    @Test
    fun `no duration and target combination ever throws or escapes the clip bounds`() {
        // Sweep every duration around the minimum window with hostile targets; the clamp must
        // stay total (no inverted-range throw) and keep results inside [0, duration].
        val targets = longArrayOf(Long.MIN_VALUE, -1L, 0L, 1L, 399L, 400L, 401L, 30_000L, Long.MAX_VALUE)
        for (durationMs in 0L..1_000L step 7) {
            for (targetMs in targets) {
                val start = clampTrimStartMs(targetMs, endMs = durationMs, minGapMs = MIN_GAP_MS)
                val end = clampTrimEndMs(targetMs, startMs = 0L, durationMs = durationMs, minGapMs = MIN_GAP_MS)
                assertTrue("start $start out of [0, $durationMs]", start in 0L..durationMs)
                assertTrue("end $end out of [0, $durationMs]", end in 0L..durationMs)
            }
        }
    }
}
