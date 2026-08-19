package io.github.stozo04.openloop.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage for the speed curve: interpolation, the flatten semantics, and the sampling that
 * feeds Media3's piecewise-constant `SpeedProvider`.
 *
 * Everything here is deliberately reachable without a device (Lesson 008 / 018 — the encoder needs
 * hardware, the math does not).
 */
class SpeedCurveTest {

    private fun ramp(from: Float, to: Float) = SpeedCurve(listOf(SpeedKey(0f, from), SpeedKey(1f, to)))

    // ── speedAt ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `speedAt interpolates linearly between neighbouring keys`() {
        val curve = ramp(1f, 2f)
        assertEquals(1.0f, curve.speedAt(0f), 1e-5f)
        assertEquals(1.25f, curve.speedAt(0.25f), 1e-5f)
        assertEquals(1.5f, curve.speedAt(0.5f), 1e-5f)
        assertEquals(2.0f, curve.speedAt(1f), 1e-5f)
    }

    @Test
    fun `speedAt clamps t outside the domain instead of extrapolating`() {
        val curve = ramp(1f, 2f)
        assertEquals(1.0f, curve.speedAt(-5f), 1e-5f)
        assertEquals(2.0f, curve.speedAt(17f), 1e-5f)
    }

    @Test
    fun `speedAt holds flat before the first and after the last key`() {
        // A curve that does not span the whole domain must still answer everywhere.
        val curve = SpeedCurve(listOf(SpeedKey(0.3f, 0.5f), SpeedKey(0.7f, 2f)))
        assertEquals(0.5f, curve.speedAt(0f), 1e-5f)
        assertEquals(2f, curve.speedAt(1f), 1e-5f)
    }

    @Test
    fun `speedAt clamps the result to the slider bounds`() {
        val curve = ramp(0.01f, 99f)
        assertEquals(SpeedCurve.MIN_SPEED, curve.speedAt(0f), 1e-5f)
        assertEquals(SpeedCurve.MAX_SPEED, curve.speedAt(1f), 1e-5f)
    }

    @Test
    fun `a multi-segment curve reads each segment independently`() {
        val curve = SpeedCurve(
            listOf(SpeedKey(0f, 0.5f), SpeedKey(0.5f, 2.5f), SpeedKey(1f, 1f)),
        )
        assertEquals(1.5f, curve.speedAt(0.25f), 1e-5f)
        assertEquals(2.5f, curve.speedAt(0.5f), 1e-5f)
        assertEquals(1.75f, curve.speedAt(0.75f), 1e-5f)
    }

    @Test
    fun `unsorted keys are ordered defensively rather than read backwards`() {
        val curve = SpeedCurve(listOf(SpeedKey(1f, 2f), SpeedKey(0f, 1f)))
        assertEquals(1.5f, curve.speedAt(0.5f), 1e-5f)
    }

    // ── Degenerate inputs — must never throw, this runs inside the render worker ─────────────────

    @Test
    fun `an empty curve reads as neutral speed`() {
        val curve = SpeedCurve(emptyList())
        assertEquals(SpeedCurve.NEUTRAL_SPEED, curve.speedAt(0.5f), 1e-5f)
        assertEquals(SpeedCurve.NEUTRAL_SPEED, curve.flatten(), 1e-5f)
        assertEquals(SpeedCurve.NEUTRAL_SPEED, curve.maxSpeed(), 1e-5f)
    }

    @Test
    fun `a single-key curve reads as that constant everywhere`() {
        val curve = SpeedCurve(listOf(SpeedKey(0.4f, 1.75f)))
        assertEquals(1.75f, curve.speedAt(0f), 1e-5f)
        assertEquals(1.75f, curve.speedAt(1f), 1e-5f)
        assertEquals(1.75f, curve.flatten(), 1e-4f)
    }

    @Test
    fun `coincident keys do not divide by zero`() {
        val curve = SpeedCurve(listOf(SpeedKey(0f, 1f), SpeedKey(0.5f, 2f), SpeedKey(0.5f, 0.5f), SpeedKey(1f, 1f)))
        val speed = curve.speedAt(0.5f)
        assertTrue("got $speed", speed.isFinite())
        assertTrue(curve.flatten().isFinite())
    }

    // ── flatten: the harmonic mean, which preserves output duration ─────────────────────────────

    @Test
    fun `flatten of a flat curve is that speed`() {
        assertEquals(1.5f, SpeedCurve.flat(1.5f).flatten(), 1e-4f)
        assertEquals(0.25f, SpeedCurve.flat(0.25f).flatten(), 1e-4f)
    }

    /**
     * The decision that makes Flatten honest: it must be the speed that plays the loop in the *same
     * time*, i.e. the harmonic mean — not the arithmetic mean a reader would first reach for.
     */
    @Test
    fun `flatten is the harmonic mean not the arithmetic mean`() {
        val curve = ramp(0.5f, 2f)
        val arithmetic = 1.25f
        val expectedHarmonic = 1f / (kotlin.math.ln(2f / 0.5f) / (2f - 0.5f))

        assertEquals("closed form sanity", 1.082f, expectedHarmonic, 0.005f)
        assertEquals(expectedHarmonic, curve.flatten(), 1e-3f)
        assertTrue(
            "flatten must not be the arithmetic mean",
            kotlin.math.abs(curve.flatten() - arithmetic) > 0.1f,
        )
    }

    /**
     * The property that actually matters to the user: flattening must not change how long the loop
     * runs. Cross-checked against brute-force numeric integration rather than against the closed
     * form, so an error in the closed form cannot hide behind itself.
     */
    @Test
    fun `flatten preserves output duration against numeric integration`() {
        val curves = listOf(
            ramp(0.5f, 2f),
            ramp(3f, 0.25f),
            SpeedCurve(listOf(SpeedKey(0f, 0.6f), SpeedKey(0.5f, 2.5f), SpeedKey(1f, 0.8f))),
            SpeedCurve(listOf(SpeedKey(0f, 1f), SpeedKey(0.2f, 1f), SpeedKey(0.8f, 2f), SpeedKey(1f, 2f))),
        )
        val steps = 200_000
        for (curve in curves) {
            var integral = 0.0
            for (i in 0 until steps) {
                val t = (i + 0.5f) / steps
                integral += 1.0 / curve.speedAt(t)
            }
            integral /= steps
            val numericMean = (1.0 / integral).toFloat()
            assertEquals("curve=$curve", numericMean, curve.flatten(), 1e-2f)
        }
    }

    @Test
    fun `flatten stays within the slider bounds`() {
        assertTrue(ramp(3f, 3f).flatten() <= SpeedCurve.MAX_SPEED)
        assertTrue(ramp(0.25f, 0.25f).flatten() >= SpeedCurve.MIN_SPEED)
    }

    // ── maxSpeed / isFlat ───────────────────────────────────────────────────────────────────────

    @Test
    fun `maxSpeed reports the fastest key, which is what caps the output frame rate`() {
        val curve = SpeedCurve(listOf(SpeedKey(0f, 0.5f), SpeedKey(0.5f, 2.75f), SpeedKey(1f, 1f)))
        assertEquals(2.75f, curve.maxSpeed(), 1e-5f)
    }

    @Test
    fun `isFlat distinguishes a constant curve from a ramp`() {
        assertTrue(SpeedCurve.flat(1.5f).isFlat)
        assertFalse(ramp(1f, 2f).isFlat)
    }

    // ── sampleClip: what the SpeedProvider is built from ────────────────────────────────────────

    @Test
    fun `sampleClip emits steps that track the curve across the clip`() {
        val curve = ramp(1f, 2f)
        val steps = curve.sampleClip(
            clipStartUs = 0L,
            clipDurationUs = 1_000_000L,
            totalUs = 1_000_000L,
            stepUs = 100_000L,
        )
        assertEquals(1f, steps[0L]!!, 1e-3f)
        val last = steps.keys.max()
        assertTrue("last sampled speed should be near 2x", steps[last]!! > 1.85f)
    }

    @Test
    fun `sampleClip keys are strictly increasing, as SpeedProviderUtil requires`() {
        val curve = SpeedCurve(listOf(SpeedKey(0f, 0.5f), SpeedKey(0.5f, 3f), SpeedKey(1f, 0.5f)))
        val steps = curve.sampleClip(0L, 2_000_000L, 2_000_000L, 33_000L)
        val keys = steps.keys.toList()
        keys.zipWithNext { a, b -> assertTrue("keys must strictly increase: $a then $b", b > a) }
    }

    @Test
    fun `sampleClip collapses a flat curve to a single step`() {
        val steps = SpeedCurve.flat(2f).sampleClip(0L, 5_000_000L, 5_000_000L, 33_000L)
        assertEquals("a constant needs exactly one entry", 1, steps.size)
        assertEquals(2f, steps[0L]!!, 1e-5f)
    }

    @Test
    fun `sampleClip of a later clip reads that clip's slice of the curve`() {
        val curve = ramp(1f, 2f)
        // Second half of a two-clip loop → speeds should start at the curve's midpoint, not at 1x.
        val steps = curve.sampleClip(
            clipStartUs = 1_000_000L,
            clipDurationUs = 1_000_000L,
            totalUs = 2_000_000L,
            stepUs = 100_000L,
        )
        assertEquals(1.5f, steps[0L]!!, 1e-3f)
    }

    @Test
    fun `sampleClip never returns empty for a clip shorter than one step`() {
        val steps = SpeedCurve.flat(1.5f).sampleClip(0L, 1_000L, 1_000_000L, 33_000L)
        assertEquals(1, steps.size)
        assertEquals(1.5f, steps.values.first(), 1e-5f)
    }

    @Test
    fun `sampleClip returns empty for a zero-length clip or loop`() {
        assertTrue(SpeedCurve.flat(1f).sampleClip(0L, 0L, 1_000L, 1_000L).isEmpty())
        assertTrue(SpeedCurve.flat(1f).sampleClip(0L, 1_000L, 0L, 1_000L).isEmpty())
    }

    @Test
    fun `clampSpeed maps NaN to neutral rather than propagating it into the encoder`() {
        assertEquals(SpeedCurve.NEUTRAL_SPEED, SpeedCurve.clampSpeed(Float.NaN), 1e-5f)
    }

    @Test
    fun `flat builds a two-point curve spanning the whole loop`() {
        val curve = SpeedCurve.flat(1.5f)
        assertEquals(2, curve.keys.size)
        assertEquals(0f, curve.keys.first().t, 1e-6f)
        assertEquals(1f, curve.keys.last().t, 1e-6f)
    }
}
