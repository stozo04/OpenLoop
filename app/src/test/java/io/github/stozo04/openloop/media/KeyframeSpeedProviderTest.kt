package io.github.stozo04.openloop.media

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `SpeedProvider` contract, exercised the way Media3 exercises it.
 *
 * `SpeedProviderUtil.getDurationAfterSpeedProviderApplied` and `SpeedChangeShaderProgram` both walk
 * `getNextSpeedChangeTimeUs` in a loop and assert the returned time strictly increases — a provider
 * that reports a non-advancing change time hangs or trips `checkState` inside the encoder. These
 * tests pin the contract on our side of that boundary.
 */
@UnstableApi
class KeyframeSpeedProviderTest {

    @Test
    fun `getSpeed holds each step until the next one`() {
        val provider = KeyframeSpeedProvider(mapOf(0L to 1f, 100L to 2f, 200L to 0.5f))
        assertEquals(1f, provider.getSpeed(0L), 1e-5f)
        assertEquals(1f, provider.getSpeed(99L), 1e-5f)
        assertEquals(2f, provider.getSpeed(100L), 1e-5f)
        assertEquals(2f, provider.getSpeed(199L), 1e-5f)
        assertEquals(0.5f, provider.getSpeed(200L), 1e-5f)
        assertEquals(0.5f, provider.getSpeed(10_000L), 1e-5f)
    }

    @Test
    fun `getNextSpeedChangeTimeUs reports the following change, then TIME_UNSET`() {
        val provider = KeyframeSpeedProvider(mapOf(0L to 1f, 100L to 2f, 200L to 0.5f))
        assertEquals(100L, provider.getNextSpeedChangeTimeUs(0L))
        assertEquals(100L, provider.getNextSpeedChangeTimeUs(50L))
        assertEquals(200L, provider.getNextSpeedChangeTimeUs(100L))
        assertEquals(C.TIME_UNSET, provider.getNextSpeedChangeTimeUs(200L))
        assertEquals(C.TIME_UNSET, provider.getNextSpeedChangeTimeUs(9_999L))
    }

    /** The exact walk `SpeedProviderUtil` performs. It must terminate and never stall. */
    @Test
    fun `walking the provider terminates with strictly increasing change times`() {
        val curve = SpeedCurve(listOf(SpeedKey(0f, 0.5f), SpeedKey(0.5f, 3f), SpeedKey(1f, 0.5f)))
        val provider = KeyframeSpeedProvider(curve.sampleClip(0L, 2_000_000L, 2_000_000L, 33_000L))

        var timeUs = 0L
        var iterations = 0
        while (true) {
            val next = provider.getNextSpeedChangeTimeUs(timeUs)
            if (next == C.TIME_UNSET) break
            assertTrue("change time must strictly advance: $timeUs then $next", next > timeUs)
            timeUs = next
            check(++iterations < 100_000) { "provider walk did not terminate" }
        }
        assertTrue("a real ramp should produce several steps", iterations > 5)
    }

    @Test
    fun `a negative timestamp reads the first step instead of throwing`() {
        // Media3's own SegmentSpeedProvider asserts timeUs >= 0; an exception thrown from the sample
        // read path would take a user's save down, so we clamp instead.
        val provider = KeyframeSpeedProvider(mapOf(0L to 1.5f, 100L to 2f))
        assertEquals(1.5f, provider.getSpeed(-500L), 1e-5f)
        assertEquals(100L, provider.getNextSpeedChangeTimeUs(-500L))
    }

    @Test
    fun `an empty step map falls back to neutral speed, never silently to nothing`() {
        val provider = KeyframeSpeedProvider(emptyMap())
        assertEquals(SpeedCurve.NEUTRAL_SPEED, provider.getSpeed(0L), 1e-5f)
        assertEquals(C.TIME_UNSET, provider.getNextSpeedChangeTimeUs(0L))
        assertTrue(provider.isConstant)
    }

    @Test
    fun `speeds are clamped into the slider bounds on the way in`() {
        val provider = KeyframeSpeedProvider(mapOf(0L to 99f, 10L to 0.001f))
        assertEquals(SpeedCurve.MAX_SPEED, provider.getSpeed(0L), 1e-5f)
        assertEquals(SpeedCurve.MIN_SPEED, provider.getSpeed(10L), 1e-5f)
    }

    @Test
    fun `a flat curve yields a constant provider, matching the old SpeedChangeEffect behaviour`() {
        val provider = speedProviderForClip(
            curve = SpeedCurve.flat(2f),
            span = ClipSpan(startUs = 0L, durationUs = 3_000_000L),
            totalUs = 3_000_000L,
            stepUs = 33_000L,
        )
        assertTrue(provider.isConstant)
        assertEquals(2f, provider.getSpeed(0L), 1e-5f)
        assertEquals(2f, provider.getSpeed(2_999_999L), 1e-5f)
        assertEquals(C.TIME_UNSET, provider.getNextSpeedChangeTimeUs(0L))
    }

    @Test
    fun `a later clip's provider starts at that clip's slice of the curve`() {
        val curve = SpeedCurve(listOf(SpeedKey(0f, 1f), SpeedKey(1f, 2f)))
        val secondHalf = speedProviderForClip(
            curve = curve,
            span = ClipSpan(startUs = 1_000_000L, durationUs = 1_000_000L),
            totalUs = 2_000_000L,
            stepUs = 100_000L,
        )
        // Clip-local time 0 is global t = 0.5, where the ramp reads 1.5x.
        assertEquals(1.5f, secondHalf.getSpeed(0L), 1e-2f)
    }
}
