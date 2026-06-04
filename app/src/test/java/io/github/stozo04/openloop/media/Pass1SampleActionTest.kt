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
    fun `at-cap source with low timestamp jitter is never subsampled`() {
        // Regression (fold-loop iter 1, Pixel 6): a real "30 fps" camera/library clip stamps
        // frames 33,222 us apart — just under the computed 33,333 us interval. The old floor
        // comparison skipped every other frame, halving the reversed half to ~15 fps and
        // macroblock-smearing it (skipped compressed samples were P-frame references).
        val spacingUs = 33_222L
        var lastEncodedUs = 0L // frame 0 encoded
        for (i in 1..120) {
            val sampleUs = i * spacingUs
            assertEquals(
                "frame $i must encode (at-cap cadence, jitter under nominal interval)",
                Pass1SampleAction.ENCODE,
                pass1SampleAction(
                    sampleUs = sampleUs,
                    lastEncodedSampleUs = lastEncodedUs,
                    endUs = 10_000_000L,
                    minEncodeIntervalUs = 33_333L,
                ),
            )
            lastEncodedUs = sampleUs
        }
    }

    @Test
    fun `60 fps source still halves to the cap`() {
        // Above-cap subsampling must keep working: 16,667 us spacing (60 fps) against a
        // 33,333 us interval should alternate SKIP/ENCODE — i.e. land at the 30 fps cap.
        val spacingUs = 16_667L
        var lastEncodedUs = 0L
        var encoded = 1 // frame 0
        for (i in 1..119) {
            val sampleUs = i * spacingUs
            val action = pass1SampleAction(
                sampleUs = sampleUs,
                lastEncodedSampleUs = lastEncodedUs,
                endUs = 10_000_000L,
                minEncodeIntervalUs = 33_333L,
            )
            if (action == Pass1SampleAction.ENCODE) {
                encoded++
                lastEncodedUs = sampleUs
            }
        }
        // 2 seconds of 60 fps input → ~60 encoded frames (30 fps), never the full 120.
        assertEquals(60, encoded)
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
