package io.github.stozo04.openloop.media

import org.junit.Assert.assertEquals
import org.junit.Test

class Pass1SampleActionTest {

    @Test
    fun `first sample in trim window is always encoded`() {
        assertEquals(
            Pass1SampleAction.ENCODE,
            pass1SampleAction(
                sampleUs = 1_000_000L,
                lastEncodedSampleUs = -1L,
                endUs = 5_000_000L,
                minEncodeIntervalUs = 33_333L,
            ),
        )
    }

    @Test
    fun `dense samples are skipped until interval elapses`() {
        assertEquals(
            Pass1SampleAction.SKIP,
            pass1SampleAction(
                sampleUs = 1_010_000L,
                lastEncodedSampleUs = 1_000_000L,
                endUs = 5_000_000L,
                minEncodeIntervalUs = 33_333L,
            ),
        )
        assertEquals(
            Pass1SampleAction.ENCODE,
            pass1SampleAction(
                sampleUs = 1_040_000L,
                lastEncodedSampleUs = 1_000_000L,
                endUs = 5_000_000L,
                minEncodeIntervalUs = 33_333L,
            ),
        )
    }

    @Test
    fun `tail of trim window is encoded even inside interval`() {
        assertEquals(
            Pass1SampleAction.ENCODE,
            pass1SampleAction(
                sampleUs = 4_980_000L,
                lastEncodedSampleUs = 4_970_000L,
                endUs = 5_000_000L,
                minEncodeIntervalUs = 33_333L,
            ),
        )
    }
}
