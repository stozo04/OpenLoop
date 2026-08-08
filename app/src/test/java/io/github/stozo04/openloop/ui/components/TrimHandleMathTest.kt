package io.github.stozo04.openloop.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the trim-handle clamp math extracted from `FilmstripTrimSelector`.
 *
 * Regression coverage for Crashlytics 7169b499 / issue #95 (Lesson 030): a 335 ms clip on the
 * Trim screen inverted the `coerceIn` range (`maximum 335 is less than minimum 400`) and crashed
 * on the first handle drag or accessibility `setProgress`. The clamp must never throw, and must
 * never let a handle escape `[0, durationMs]`, for any combination of duration and target.
 */
class TrimHandleMathTest {

    private companion object {
        /** OpenLoopViewModel.MIN_TRIM_DURATION. */
        const val MIN_GAP_MS = 400L

        /** The exact clip duration from the Crashlytics report. */
        const val CRASH_DURATION_MS = 335L
    }

    @Test
    fun `short clip handles pin instead of throwing, for any drag target`() {
        // Was coerceIn(0, 335 - 400) / coerceIn(0 + 400, 335) — both inverted before the guard.
        for (targetMs in longArrayOf(Long.MIN_VALUE, -1L, 0L, 100L, 335L, 400L, Long.MAX_VALUE)) {
            assertEquals(0L, clampTrimStartMs(targetMs, endMs = CRASH_DURATION_MS, minGapMs = MIN_GAP_MS))
            assertEquals(
                CRASH_DURATION_MS,
                clampTrimEndMs(targetMs, startMs = 0L, durationMs = CRASH_DURATION_MS, minGapMs = MIN_GAP_MS),
            )
        }
    }

    @Test
    fun `normal clip keeps the original clamp behavior`() {
        // In range → untouched; past the min gap → pinned to the gap; overshoot → clip bounds.
        assertEquals(1_200L, clampTrimStartMs(1_200L, endMs = 5_000L, minGapMs = MIN_GAP_MS))
        assertEquals(4_600L, clampTrimStartMs(4_900L, endMs = 5_000L, minGapMs = MIN_GAP_MS))
        assertEquals(0L, clampTrimStartMs(-250L, endMs = 5_000L, minGapMs = MIN_GAP_MS))

        assertEquals(7_500L, clampTrimEndMs(7_500L, startMs = 2_000L, durationMs = 10_000L, minGapMs = MIN_GAP_MS))
        assertEquals(2_400L, clampTrimEndMs(2_100L, startMs = 2_000L, durationMs = 10_000L, minGapMs = MIN_GAP_MS))
        assertEquals(10_000L, clampTrimEndMs(12_000L, startMs = 0L, durationMs = 10_000L, minGapMs = MIN_GAP_MS))
    }

    @Test
    fun `no duration and target combination throws or escapes the clip bounds`() {
        // The two clamps need OPPOSITE coerce precedence (start floors at 0, end caps at duration);
        // a copy-pasted wrong order still compiles and never throws, so sweep the bounds too.
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
