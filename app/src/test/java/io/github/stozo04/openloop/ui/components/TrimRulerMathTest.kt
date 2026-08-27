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

    @Test
    fun `short clip end label matches actual duration, not a 5s pad`() {
        // The screenshot bug: 2600 ms clip showed a padded 5 s ruler end.
        val labels = trimRulerLabelTimesMs(2_600L)
        assertEquals(0L, labels.first())
        assertEquals(2_600L, labels.last())
        // End keeps its tenths; do not round up to "3.0s".
        assertEquals("2.6s", formatTrimRulerLabel(labels.last()))
        assertTrue("ruler must not invent a 5s end tick", labels.none { it == 5_000L })
    }

    @Test
    fun `sub-second and zero clips still produce a valid scale`() {
        assertEquals(listOf(0L), trimRulerLabelTimesMs(0L))
        assertEquals(listOf(0L, 335L), trimRulerLabelTimesMs(335L))
        assertEquals("0.3s", formatTrimRulerLabel(335L))
    }

    @Test
    fun `five second clip keeps whole-second ticks through the true end`() {
        assertEquals(
            listOf(0L, 1_000L, 2_000L, 3_000L, 4_000L, 5_000L),
            trimRulerLabelTimesMs(5_000L),
        )
        assertEquals(1_000L, trimRulerMajorIntervalMs(5_000L))
    }

    @Test
    fun `longer clips use coarser major intervals still ending at duration`() {
        assertEquals(5_000L, trimRulerMajorIntervalMs(12_000L))
        assertEquals(listOf(0L, 5_000L, 10_000L, 12_000L), trimRulerLabelTimesMs(12_000L))

        assertEquals(10_000L, trimRulerMajorIntervalMs(30_000L))
        assertEquals(listOf(0L, 10_000L, 20_000L, 30_000L), trimRulerLabelTimesMs(30_000L))
    }

    @Test
    fun `penultimate major tick is dropped when it would crowd the end label`() {
        // 2.0 s sits 200 ms from the right-pinned end — keep only the true end.
        assertEquals(listOf(0L, 1_000L, 2_200L), trimRulerLabelTimesMs(2_200L))
        assertEquals("2.2s", formatTrimRulerLabel(2_200L))
        // Exactly half a second of clearance is enough to keep it.
        assertEquals(listOf(0L, 1_000L, 2_000L, 2_500L), trimRulerLabelTimesMs(2_500L))
        // A 30.4 s import (cap + grace) drops the 30 s tick the same way.
        assertEquals(listOf(0L, 10_000L, 20_000L, 30_400L), trimRulerLabelTimesMs(30_400L))
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
    fun `seconds-only readouts are safe because no clip can reach a minute`() {
        // Raising MAX_RECORDING (or IMPORT_MAX_DURATION) past 60 s means formatTrimClock /
        // formatTrimRulerLabel must grow a minutes field back — see the KDoc on MAX_RECORDING.
        val longestClip = OpenLoopViewModel.IMPORT_MAX_DURATION + OpenLoopViewModel.IMPORT_DURATION_GRACE
        assertTrue(
            "trim formatters show seconds only; $longestClip needs minutes",
            longestClip < 60.seconds,
        )
        assertTrue(OpenLoopViewModel.MAX_RECORDING <= OpenLoopViewModel.IMPORT_MAX_DURATION)
    }

    @Test
    fun `minor interval tracks the major spacing`() {
        assertEquals(250L, trimRulerMinorIntervalMs(1_000L))
        assertEquals(500L, trimRulerMinorIntervalMs(5_000L))
        assertEquals(1_000L, trimRulerMinorIntervalMs(10_000L))
    }
}
