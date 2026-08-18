package io.github.stozo04.openloop.media

import androidx.media3.common.util.SpeedProviderUtil
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-check our duration prediction against **Media3's own** duration calculation.
 *
 * The editor's duration chip is `loopOutputDurationMs(loopClipSpans(...), curve.flatten())` — the
 * *same* spans the render slices the curve over (Lesson 033). The encoder's actual output length is
 * whatever `SpeedProviderUtil.getDurationAfterSpeedProviderApplied` computes over the providers we
 * hand it. If those two disagree, the chip lies to the user about how long their loop will be — so
 * this test pins them together using the real library function, not a re-implementation of it.
 */
@UnstableApi
class SpeedCurveDurationTest {

    private val trimMs = 3_800L
    private val seamMs = 33L
    private val stepUs = 1_000_000L / 30L

    private fun predictedVsActual(
        curve: SpeedCurve,
        mode: BoomerangMode,
        reversedMs: Long? = null,
    ): Pair<Double, Double> {
        val specs = boomerangSequence(mode, 1)
        val spans = loopClipSpans(specs, trimMs, seamMs, reversedMs)
        val totalUs = spans.totalUs()

        // What Media3 will actually produce, summed over the per-clip providers.
        val actualUs = spans.sumOf { span ->
            val provider = speedProviderForClip(curve, span, totalUs, stepUs)
            SpeedProviderUtil.getDurationAfterSpeedProviderApplied(provider, span.durationUs)
        }

        // What the editor's chip predicts — from the same spans, so a short artifact shortens both.
        val predictedMs = loopOutputDurationMs(spans, curve.flatten())
        return predictedMs / 1000.0 to actualUs / 1_000_000.0
    }

    @Test
    fun `a flat curve predicts exactly what Media3 produces`() {
        val (predicted, actual) = predictedVsActual(
            SpeedCurve.flat(2f),
            BoomerangMode.FORWARD_THEN_REVERSE,
        )
        assertEquals("predicted=$predicted actual=$actual", predicted, actual, 0.1)
    }

    @Test
    fun `a ramp predicts what Media3 produces`() {
        val (predicted, actual) = predictedVsActual(
            SpeedCurve(listOf(SpeedKey(0f, 0.5f), SpeedKey(1f, 2f))),
            BoomerangMode.FORWARD_THEN_REVERSE,
        )
        assertEquals("predicted=$predicted actual=$actual", predicted, actual, 0.15)
    }

    /**
     * The Lesson 033 shape: a reversed clip that came back materially shorter than its trim window
     * (the emulator's ~5 fps virtual camera produced one at 19 samples). The chip must shrink with
     * the artifact, not promise the nominal window's length.
     */
    @Test
    fun `a short reversed artifact shortens the prediction to match Media3`() {
        val curve = SpeedCurve(listOf(SpeedKey(0f, 0.5f), SpeedKey(1f, 2f)))
        val (nominal, _) = predictedVsActual(curve, BoomerangMode.FORWARD_THEN_REVERSE)
        val (predicted, actual) = predictedVsActual(
            curve,
            BoomerangMode.FORWARD_THEN_REVERSE,
            reversedMs = 2_400L,
        )
        assertEquals("predicted=$predicted actual=$actual", predicted, actual, 0.15)
        assertTrue("short reverse must shorten the chip: $predicted vs $nominal", predicted < nominal - 1.0)
    }

    /** The exact on-device shape: the accelerate-into-reverse preset with its peak dragged to 0.5x. */
    @Test
    fun `the dragged-peak curve from the device run predicts what Media3 produces`() {
        val curve = SpeedCurve(
            listOf(
                SpeedKey(0f, 0.6f),
                SpeedKey(0.3f, 1.0f),
                SpeedKey(0.5f, 0.5f),
                SpeedKey(0.7f, 1.4f),
                SpeedKey(1f, 0.6f),
            ),
        )
        val (predicted, actual) = predictedVsActual(curve, BoomerangMode.FORWARD_THEN_REVERSE)
        println("dragged-peak curve: predicted=${"%.2f".format(predicted)}s actual=${"%.2f".format(actual)}s")
        assertEquals("predicted=$predicted actual=$actual", predicted, actual, 0.2)
    }

    /** And the preset before the drag, which is what the device output actually measured at. */
    @Test
    fun `the accelerate-into-reverse preset predicts what Media3 produces`() {
        val (predicted, actual) = predictedVsActual(
            SpeedPresetKeys.ACCELERATE_INTO_REVERSE,
            BoomerangMode.FORWARD_THEN_REVERSE,
        )
        println("preset: predicted=${"%.2f".format(predicted)}s actual=${"%.2f".format(actual)}s")
        assertEquals("predicted=$predicted actual=$actual", predicted, actual, 0.2)
    }

    private object SpeedPresetKeys {
        val ACCELERATE_INTO_REVERSE = SpeedCurve(
            listOf(
                SpeedKey(0f, 0.6f),
                SpeedKey(0.3f, 1.0f),
                SpeedKey(0.5f, 2.6f),
                SpeedKey(0.7f, 1.4f),
                SpeedKey(1f, 0.6f),
            ),
        )
    }
}
