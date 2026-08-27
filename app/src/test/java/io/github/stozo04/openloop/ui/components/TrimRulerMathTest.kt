package io.github.stozo04.openloop.ui.components

import io.github.stozo04.openloop.ui.OpenLoopViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * JVM tests for trim timeline-ruler math and the seconds-only trim formatters.
 *
 * Regressions pinned here: short clips used to round the ruler scale up to the next 5 s bucket
 * (`ceil(duration / 5000) * 5000`), so a 2.6 s video showed a padded end tick; and the readouts
 * used to spend their width on a `00:` minutes field that the 30 s cap keeps at zero (issue #154).
 */
class TrimRulerMathTest {

    private companion object {
        /** 1.5 × a 24 dp `7.0s` label on a 360 dp rail — the narrowest phone the app targets. */
        const val NARROW_PHONE = 0.10f

        /** 1.5 × a 50 px label on a 1080 px rail — Pixel-class. */
        const val WIDE_PHONE = 0.07f
    }

    @Test
    fun `short clip end label matches actual duration, not a 5s pad`() {
        // The screenshot bug: 2600 ms clip showed a padded 5 s ruler end.
        val labels = trimRulerLabelTimesMs(2_600L, NARROW_PHONE)
        assertEquals(listOf(0L, 1_000L, 2_000L, 2_600L), labels)
        // End keeps its tenths; do not round up to "3.0s".
        assertEquals("2.6s", formatTrimRulerLabel(labels.last()))
        assertTrue("ruler must not invent a 5s end tick", labels.none { it == 5_000L })
    }

    @Test
    fun `sub-second and zero clips still produce a valid scale`() {
        assertEquals(listOf(0L), trimRulerLabelTimesMs(0L, NARROW_PHONE))
        assertEquals(listOf(0L, 335L), trimRulerLabelTimesMs(335L, NARROW_PHONE))
        assertEquals("0.3s", formatTrimRulerLabel(335L))
    }

    @Test
    fun `clips up to ten seconds are labelled every second through the true end`() {
        assertEquals(1_000L, trimRulerMajorIntervalMs(5_000L))
        assertEquals(1_000L, trimRulerMajorIntervalMs(10_000L))
        assertEquals(
            listOf(0L, 1_000L, 2_000L, 3_000L, 4_000L, 5_000L),
            trimRulerLabelTimesMs(5_000L, NARROW_PHONE),
        )
        // The 7.79 s capture from the PR screenshot: every second, then the real end.
        assertEquals(
            listOf(0L, 1_000L, 2_000L, 3_000L, 4_000L, 5_000L, 6_000L, 7_000L, 7_790L),
            trimRulerLabelTimesMs(7_790L, WIDE_PHONE),
        )
    }

    @Test
    fun `longer clips step up to the coarsest interval that keeps about ten labels`() {
        assertEquals(2_000L, trimRulerMajorIntervalMs(12_000L))
        assertEquals(
            listOf(0L, 2_000L, 4_000L, 6_000L, 8_000L, 10_000L, 12_000L),
            trimRulerLabelTimesMs(12_000L, NARROW_PHONE),
        )

        assertEquals(5_000L, trimRulerMajorIntervalMs(30_000L))
        assertEquals(
            listOf(0L, 5_000L, 10_000L, 15_000L, 20_000L, 25_000L, 30_000L),
            trimRulerLabelTimesMs(30_000L, NARROW_PHONE),
        )

        assertEquals(10_000L, trimRulerMajorIntervalMs(65_460L))
    }

    @Test
    fun `a label that would sit under a pinned edge label is dropped, measured not guessed`() {
        // 2.0 s is 9 % from the right-pinned end on a 2.2 s clip — keep only the true end.
        assertEquals(listOf(0L, 1_000L, 2_200L), trimRulerLabelTimesMs(2_200L, NARROW_PHONE))
        assertEquals("2.2s", formatTrimRulerLabel(2_200L))
        // 20 % of the rail between them is plenty — keep it.
        assertEquals(listOf(0L, 1_000L, 2_000L, 2_500L), trimRulerLabelTimesMs(2_500L, NARROW_PHONE))
        // "7.0s" beside "7.8s": fits on a wide phone (above), not on the narrowest at 12 % clearance.
        assertEquals(
            listOf(0L, 1_000L, 2_000L, 3_000L, 4_000L, 5_000L, 6_000L, 7_790L),
            trimRulerLabelTimesMs(7_790L, 0.12f),
        )
        // The start edge is pinned too: "1.0s" at 10 % of a 10 s rail goes when the label is wide.
        assertEquals(
            listOf(0L, 2_000L, 3_000L, 4_000L, 5_000L, 6_000L, 7_000L, 8_000L, 10_000L),
            trimRulerLabelTimesMs(10_000L, 0.12f),
        )
        assertEquals(
            (0L..10_000L step 1_000L).toList(),
            trimRulerLabelTimesMs(10_000L, WIDE_PHONE),
        )
        // A 31 s import (cap + grace) drops its 30 s tick, which sits at 97 % of the rail.
        assertEquals(
            listOf(0L, 5_000L, 10_000L, 15_000L, 20_000L, 25_000L, 31_000L),
            trimRulerLabelTimesMs(31_000L, NARROW_PHONE),
        )
        // The emulator-drift clip from the first PR pass: "60.0s" at 92 % must not sit under "65.5s".
        // A five-character end label measures ~6.5 % of a Pixel rail, so the clearance is ~10 %.
        assertEquals(
            listOf(0L, 10_000L, 20_000L, 30_000L, 40_000L, 50_000L, 65_460L),
            trimRulerLabelTimesMs(65_460L, 0.10f),
        )
    }

    @Test
    fun `edge clearance is one and a half label widths as a share of the rail`() {
        assertEquals(0.10f, trimRulerEdgeClearance(labelWidthPx = 24f, railWidthPx = 360f), 1e-6f)
        assertEquals(0f, trimRulerEdgeClearance(labelWidthPx = 24f, railWidthPx = 0f), 0f)
        // A clearance past the whole rail is clamped: only the two edge labels survive.
        assertEquals(listOf(0L, 5_000L), trimRulerLabelTimesMs(5_000L, 3f))
    }

    @Test
    fun `every ruler tick shares one seconds-with-tenths format`() {
        assertEquals("0.0s", formatTrimRulerLabel(0L))
        assertEquals("2.0s", formatTrimRulerLabel(2_000L))
        assertEquals("2.6s", formatTrimRulerLabel(2_600L))
        assertEquals("5.0s", formatTrimRulerLabel(5_000L))
        assertEquals("30.0s", formatTrimRulerLabel(30_000L))
        assertEquals("0.0s", formatTrimRulerLabel(-5L))
    }

    @Test
    fun `range pill reads seconds with hundredths and no minutes field`() {
        assertEquals("0.00s", formatTrimClock(0L))
        assertEquals("2.30s", formatTrimClock(2_300L))
        assertEquals("6.00s", formatTrimClock(6_000L))
        assertEquals("30.00s", formatTrimClock(30_000L))
        // The narrowest legal window is still legible.
        assertEquals("0.40s", formatTrimClock(OpenLoopViewModel.MIN_TRIM_DURATION.inWholeMilliseconds))
        // Rounds rather than truncates: 1234 ms is closer to 1.23 than 1.24; 1235 ms rounds up.
        assertEquals("1.23s", formatTrimClock(1_234L))
        assertEquals("1.24s", formatTrimClock(1_235L))
        assertEquals("0.00s", formatTrimClock(-5L))
    }

    @Test
    fun `seconds-only readouts rest on the configured clip ceiling staying under a minute`() {
        // Raising MAX_RECORDING (or IMPORT_MAX_DURATION) past 60 s means formatTrimClock /
        // formatTrimRulerLabel must grow a minutes field back — see the KDoc on MAX_RECORDING.
        val longestClip = OpenLoopViewModel.IMPORT_MAX_DURATION + OpenLoopViewModel.IMPORT_DURATION_GRACE
        assertTrue(
            "trim formatters show seconds only; $longestClip needs minutes",
            longestClip < 60.seconds,
        )
        assertTrue(OpenLoopViewModel.MAX_RECORDING <= OpenLoopViewModel.IMPORT_MAX_DURATION)
        // A capture that drifts past the cap (tick-based timer on a starved emulator) still reads.
        assertEquals("65.46s", formatTrimClock(65_460L))
    }

    @Test
    fun `minor ticks keep one-second resolution under every major interval`() {
        assertEquals(250L, trimRulerMinorIntervalMs(1_000L))
        assertEquals(500L, trimRulerMinorIntervalMs(2_000L))
        assertEquals(1_000L, trimRulerMinorIntervalMs(5_000L))
        assertEquals(1_000L, trimRulerMinorIntervalMs(10_000L))
    }
}
