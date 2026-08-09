package io.github.stozo04.openloop.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for trim timeline-ruler math. Regression: short clips used to round the ruler scale up
 * to the next 5 s bucket (`ceil(duration / 5000) * 5000`), so a 2.6 s video showed `00:05` at the
 * right edge even though the filmstrip and range pill correctly used 2.6 s.
 */
class TrimRulerMathTest {

    @Test
    fun `short clip end label matches actual duration, not a 5s pad`() {
        // The screenshot bug: 2600 ms clip showed ruler end "00:05".
        val labels = trimRulerLabelTimesMs(2_600L)
        assertEquals(0L, labels.first())
        assertEquals(2_600L, labels.last())
        // End uses one decimal (matches the range pill); do not round up to "00:03".
        assertEquals("00:02.6", formatTrimRulerEndLabel(labels.last()))
        assertTrue("ruler must not invent a 5s end tick", labels.none { it == 5_000L })
    }

    @Test
    fun `sub-second and zero clips still produce a valid scale`() {
        assertEquals(listOf(0L), trimRulerLabelTimesMs(0L))
        assertEquals(listOf(0L, 335L), trimRulerLabelTimesMs(335L))
        assertEquals("00:00.3", formatTrimRulerEndLabel(335L))
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
    fun `penultimate major tick is dropped when its mm ss would match the end label`() {
        // 2.2 s rounds to the same whole second as the 2 s major tick — keep only the true end.
        assertEquals(listOf(0L, 1_000L, 2_200L), trimRulerLabelTimesMs(2_200L))
        assertEquals("00:02.2", formatTrimRulerEndLabel(2_200L))
    }

    @Test
    fun `end label keeps one decimal like the range pill`() {
        assertEquals("00:02.6", formatTrimRulerEndLabel(2_600L))
        assertEquals("00:05.0", formatTrimRulerEndLabel(5_000L))
        // Intermediate ticks sit on whole-second marks and stay mm:ss (no tenths).
        assertEquals("00:02", formatTrimRulerLabel(2_000L))
    }

    @Test
    fun `minor interval tracks the major spacing`() {
        assertEquals(250L, trimRulerMinorIntervalMs(1_000L))
        assertEquals(500L, trimRulerMinorIntervalMs(5_000L))
        assertEquals(1_000L, trimRulerMinorIntervalMs(10_000L))
    }
}
