package io.github.stozo04.openloop.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrimRulerMathTest {

    @Test
    fun shortClip_usesOneSecondMajorMarks_andEndsAtRealDuration() {
        assertEquals(1_000L, trimRulerLabelIntervalMs(2_600L))
        assertEquals(listOf(0L, 1_000L, 2_000L, 2_600L), trimRulerLabelTimesMs(2_600L))
    }

    @Test
    fun neverPadsPastClipEnd_likeOldFiveSecondFloor() {
        val labels = trimRulerLabelTimesMs(2_600L)
        assertFalse("ruler must not invent a 5s end for a 2.6s clip", 5_000L in labels)
        assertEquals(2_600L, labels.last())
    }

    @Test
    fun longerClip_keepsFiveSecondMajors() {
        assertEquals(5_000L, trimRulerLabelIntervalMs(30_000L))
        assertEquals(
            listOf(0L, 5_000L, 10_000L, 15_000L, 20_000L, 25_000L, 30_000L),
            trimRulerLabelTimesMs(30_000L),
        )
    }

    @Test
    fun midLengthClip_usesTwoSecondMajors() {
        assertEquals(2_000L, trimRulerLabelIntervalMs(8_000L))
        assertTrue(trimRulerLabelTimesMs(8_000L).contains(8_000L))
    }
}
