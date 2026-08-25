package io.github.stozo04.openloop.ui.components

import io.github.stozo04.openloop.media.SpeedCurve
import io.github.stozo04.openloop.media.SpeedKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The clamp and geometry rules behind the curve graph.
 *
 * Lesson 030 is the whole reason this file exists: every bound here is an arithmetic expression over
 * runtime state (`previous.t + gap`, `next.t - gap`), which is exactly the shape that threw
 * `Cannot coerce value to an empty range` in production. Inline in a gesture lambda none of it would
 * be reachable from a JVM test.
 */
class SpeedCurveMathTest {

    private fun curveOf(vararg pairs: Pair<Float, Float>) =
        SpeedCurve(pairs.map { SpeedKey(it.first, it.second) })

    // ── The Lesson 030 case ─────────────────────────────────────────────────────────────────────

    @Test
    fun `clampKeyT does not throw when neighbours are closer than twice the minimum gap`() {
        // Neighbors 0.06 apart with MIN_KEY_GAP 0.05 → lower bound 0.11, upper bound 0.05.
        // A naive coerceIn(0.11, 0.05) throws IllegalArgumentException.
        val keys = listOf(
            SpeedKey(0f, 1f),
            SpeedKey(0.06f, 1f),
            SpeedKey(0.09f, 1f),
            SpeedKey(1f, 1f),
        )
        val result = SpeedCurveMath.clampKeyT(0.5f, index = 2, keys = keys)
        assertTrue("result must be finite, got $result", result.isFinite())
        assertTrue("result must stay in the domain", result in 0f..1f)
    }

    @Test
    fun `clampKeyT survives a sweep of adjacent-neighbour spacings`() {
        var gap = 0f
        while (gap <= 0.3f) {
            val keys = listOf(
                SpeedKey(0f, 1f),
                SpeedKey(0.4f, 1f),
                SpeedKey(0.4f + gap, 1f),
                SpeedKey(1f, 1f),
            )
            listOf(-1f, 0f, 0.4f, 0.5f, 1f, 2f).forEach { target ->
                val clamped = SpeedCurveMath.clampKeyT(target, index = 1, keys = keys)
                assertTrue("gap=$gap target=$target gave $clamped", clamped in 0f..1f)
            }
            gap += 0.01f
        }
    }

    @Test
    fun `clampKeyT on an empty key list is total`() {
        assertEquals(0.5f, SpeedCurveMath.clampKeyT(0.5f, 0, emptyList()), 1e-6f)
        assertEquals(0f, SpeedCurveMath.clampKeyT(-3f, 0, emptyList()), 1e-6f)
    }

    // ── Endpoint locking ────────────────────────────────────────────────────────────────────────

    @Test
    fun `endpoints are x-locked so the curve always spans the whole loop`() {
        val keys = curveOf(0f to 1f, 0.5f to 2f, 1f to 1f).keys
        assertEquals(0f, SpeedCurveMath.clampKeyT(0.7f, index = 0, keys = keys), 1e-6f)
        assertEquals(1f, SpeedCurveMath.clampKeyT(0.2f, index = 2, keys = keys), 1e-6f)
    }

    @Test
    fun `an interior key is held clear of both neighbours`() {
        val keys = curveOf(0f to 1f, 0.5f to 2f, 1f to 1f).keys
        assertEquals(SpeedCurveMath.MIN_KEY_GAP, SpeedCurveMath.clampKeyT(0f, 1, keys), 1e-6f)
        assertEquals(1f - SpeedCurveMath.MIN_KEY_GAP, SpeedCurveMath.clampKeyT(1f, 1, keys), 1e-6f)
    }

    @Test
    fun `moveKey clamps speed into the slider bounds and leaves other keys alone`() {
        val curve = curveOf(0f to 1f, 0.5f to 2f, 1f to 1f)
        val moved = SpeedCurveMath.moveKey(curve, index = 1, targetT = 0.6f, targetSpeed = 99f)
        assertEquals(SpeedCurve.MAX_SPEED, moved.keys[1].speed, 1e-5f)
        assertEquals(0.6f, moved.keys[1].t, 1e-5f)
        assertEquals(1f, moved.keys[0].speed, 1e-5f)
        assertEquals(1f, moved.keys[2].speed, 1e-5f)
    }

    @Test
    fun `moveKey with an out-of-range index is a no-op`() {
        val curve = curveOf(0f to 1f, 1f to 2f)
        assertSame(curve, SpeedCurveMath.moveKey(curve, 7, 0.5f, 1f))
        assertSame(curve, SpeedCurveMath.moveKey(curve, -1, 0.5f, 1f))
    }

    @Test
    fun `moveKey keeps keys ordered by time`() {
        val curve = curveOf(0f to 1f, 0.3f to 1f, 0.6f to 1f, 1f to 1f)
        val moved = SpeedCurveMath.moveKey(curve, index = 1, targetT = 0.99f, targetSpeed = 1f)
        val times = moved.keys.map { it.t }
        assertEquals(times.sorted(), times)
    }

    // ── Insert / remove ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `insertKeyAt places the new point exactly on the existing line`() {
        val curve = curveOf(0f to 1f, 1f to 2f)
        val updated = SpeedCurveMath.insertKeyAt(curve, 0.5f)
        assertEquals(3, updated.keys.size)
        assertEquals(1.5f, updated.keys[1].speed, 1e-4f)
        // The shape must not jump when a point is added.
        assertEquals(curve.speedAt(0.25f), updated.speedAt(0.25f), 1e-4f)
        assertEquals(curve.speedAt(0.75f), updated.speedAt(0.75f), 1e-4f)
    }

    @Test
    fun `insertKeyAt refuses at the point cap and returns the same instance`() {
        var curve = curveOf(0f to 1f, 1f to 1f)
        var t = 0.15f
        while (curve.keys.size < SpeedCurve.MAX_KEYS) {
            curve = SpeedCurveMath.insertKeyAt(curve, t)
            t += 0.15f
        }
        assertEquals(SpeedCurve.MAX_KEYS, curve.keys.size)
        assertSame("at the cap the curve must come back untouched", curve, SpeedCurveMath.insertKeyAt(curve, 0.9f))
    }

    @Test
    fun `insertKeyAt refuses on top of an endpoint`() {
        val curve = curveOf(0f to 1f, 1f to 1f)
        assertSame(curve, SpeedCurveMath.insertKeyAt(curve, 0.001f))
        assertSame(curve, SpeedCurveMath.insertKeyAt(curve, 0.999f))
    }

    /**
     * The "too close to an existing interior key" guard. At the 3-point cap the UI can never present a
     * curve that reaches this branch (an interior key means the cap is already hit), so this feeds
     * [SpeedCurveMath.insertKeyAt] a synthetic 2-key curve to keep the guard covered. That guard is
     * what stops two handles stacking if the cap is ever raised again.
     */
    @Test
    fun `insertKeyAt refuses inside an existing key's personal space`() {
        val synthetic = curveOf(0f to 1f, 0.5f to 2f)
        assertSame(synthetic, SpeedCurveMath.insertKeyAt(synthetic, 0.52f))
        assertEquals(3, SpeedCurveMath.insertKeyAt(synthetic, 0.8f).keys.size)
    }

    @Test
    fun `insertKeyInWidestGap splits the span and then refuses at the cap`() {
        val flat = curveOf(0f to 1f, 1f to 1f)
        val split = SpeedCurveMath.insertKeyInWidestGap(flat)
        assertEquals(3, split.keys.size)
        assertEquals("the only gap is the whole loop, so the point lands mid-loop", 0.5f, split.keys[1].t, 1e-4f)
        // Three keys is the cap, so there is nowhere left to put one.
        assertSame(split, SpeedCurveMath.insertKeyInWidestGap(split))
    }

    /**
     * Repeated presses of ＋ Point must keep splitting until the cap — a single-shot insert passing
     * says nothing about whether the *next* one has anywhere left to go.
     */
    @Test
    fun `insertKeyInWidestGap keeps splitting until it reaches the cap`() {
        var curve = SpeedCurve.flat(1.5f)
        repeat(SpeedCurve.MAX_KEYS * 2) { curve = SpeedCurveMath.insertKeyInWidestGap(curve) }
        assertEquals(SpeedCurve.MAX_KEYS, curve.keys.size)
        // Still ordered and inside the gap rule after every split.
        assertEquals(curve.keys.sortedBy { it.t }, curve.keys)
        curve.keys.zipWithNext { a, b ->
            assertTrue("gap ${b.t - a.t} collapsed", b.t - a.t >= SpeedCurveMath.MIN_KEY_GAP)
        }
    }

    @Test
    fun `removeKey deletes an interior point and refuses the endpoints`() {
        val curve = curveOf(0f to 1f, 0.5f to 2f, 1f to 1f)
        assertEquals(2, SpeedCurveMath.removeKey(curve, 1).keys.size)
        assertSame(curve, SpeedCurveMath.removeKey(curve, 0))
        assertSame(curve, SpeedCurveMath.removeKey(curve, 2))
        assertSame(curve, SpeedCurveMath.removeKey(curve, 99))
    }

    @Test
    fun `removeKey never drops below the two endpoints`() {
        val curve = curveOf(0f to 1f, 1f to 2f)
        assertSame(curve, SpeedCurveMath.removeKey(curve, 0))
        assertSame(curve, SpeedCurveMath.removeKey(curve, 1))
    }

    // ── The logarithmic speed axis ──────────────────────────────────────────────────────────────

    @Test
    fun `the speed axis is logarithmic so half and double speed straddle 1x symmetrically`() {
        val half = SpeedCurveMath.speedToFraction(0.5f)
        val one = SpeedCurveMath.speedToFraction(1f)
        val double = SpeedCurveMath.speedToFraction(2f)
        assertEquals(
            "1x must sit exactly between 0.5x and 2x on the axis",
            one,
            (half + double) / 2f,
            1e-4f,
        )
        // A linear axis would put 1x at ~0.27; the log axis centers it.
        assertTrue("1x should be near the middle, got $one", abs(one - 0.5f) < 0.1f)
    }

    @Test
    fun `speed and fraction round-trip`() {
        listOf(0.25f, 0.5f, 1f, 1.5f, 2f, 3f).forEach { speed ->
            val roundTripped = SpeedCurveMath.fractionToSpeed(SpeedCurveMath.speedToFraction(speed))
            assertEquals(speed, roundTripped, 1e-3f)
        }
    }

    @Test
    fun `axis endpoints map to the slider bounds`() {
        assertEquals(0f, SpeedCurveMath.speedToFraction(SpeedCurve.MIN_SPEED), 1e-5f)
        assertEquals(1f, SpeedCurveMath.speedToFraction(SpeedCurve.MAX_SPEED), 1e-5f)
        assertEquals(SpeedCurve.MIN_SPEED, SpeedCurveMath.fractionToSpeed(0f), 1e-4f)
        assertEquals(SpeedCurve.MAX_SPEED, SpeedCurveMath.fractionToSpeed(1f), 1e-4f)
    }

    // ── Hit testing ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `nearestKeyIndex finds a handle within tolerance and nothing beyond it`() {
        val curve = curveOf(0f to 1f, 0.5f to 2f, 1f to 1f)
        assertEquals(1, SpeedCurveMath.nearestKeyIndex(curve, 0.5f, 2f))
        assertNull(SpeedCurveMath.nearestKeyIndex(curve, 0.5f, 0.26f))
    }

    @Test
    fun `nearestKeyIndex measures in graph space so stacked handles do not steal each other`() {
        // Two keys at nearly the same time but far apart in speed.
        val curve = curveOf(0f to 0.25f, 0.5f to 0.3f, 0.56f to 3f, 1f to 1f)
        val hit = SpeedCurveMath.nearestKeyIndex(curve, 0.55f, 3f)
        assertEquals("must grab the fast one, not the slow one below it", 2, hit)
    }

    @Test
    fun `nearestKeyIndex on an empty curve is null`() {
        assertNull(SpeedCurveMath.nearestKeyIndex(SpeedCurve(emptyList()), 0.5f, 1f))
    }

    @Test
    fun `isTimeMovable is false for endpoints and true for interior keys`() {
        val curve = curveOf(0f to 1f, 0.5f to 2f, 1f to 1f)
        assertTrue(SpeedCurveMath.isTimeMovable(curve, 1))
        assertTrue(!SpeedCurveMath.isTimeMovable(curve, 0))
        assertTrue(!SpeedCurveMath.isTimeMovable(curve, 2))
    }

    // ── Canvas geometry ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `graph geometry round-trips a point through pixels`() {
        val geometry = SpeedGraphGeometry(widthPx = 600f, heightPx = 300f, insetPx = 12f)
        val t = 0.4f
        val speed = 1.6f
        assertEquals(t, geometry.tAt(geometry.xOf(t)), 1e-4f)
        assertEquals(speed, geometry.speedAt(geometry.yOf(speed)), 1e-3f)
    }

    @Test
    fun `graph geometry survives a zero-sized canvas before first layout`() {
        val geometry = SpeedGraphGeometry(widthPx = 0f, heightPx = 0f, insetPx = 12f)
        assertTrue(geometry.tAt(0f).isFinite())
        assertTrue(geometry.speedAt(0f).isFinite())
        assertTrue(geometry.xOf(0.5f).isFinite())
        assertTrue(geometry.yOf(1f).isFinite())
    }

    @Test
    fun `graph geometry clamps taps outside the canvas`() {
        val geometry = SpeedGraphGeometry(widthPx = 600f, heightPx = 300f, insetPx = 12f)
        assertEquals(0f, geometry.tAt(-100f), 1e-5f)
        assertEquals(1f, geometry.tAt(9_000f), 1e-5f)
        assertEquals(SpeedCurve.MAX_SPEED, geometry.speedAt(-50f), 1e-3f)
        assertEquals(SpeedCurve.MIN_SPEED, geometry.speedAt(9_000f), 1e-3f)
    }

    // ── Drawing ─────────────────────────────────────────────────────────────────────────────────

    /**
     * On the log axis a straight 0.5×→2× segment passes through 1× at its midpoint (the geometric
     * mean); the encoder plays 1.25× there (the arithmetic one). The drawn line must be the latter,
     * or the readout, the graph, and every point added on the line disagree.
     */
    @Test
    fun `drawPoints follows speedAt, not a straight line on the log axis`() {
        val curve = curveOf(0f to 0.5f, 1f to 2f)
        val points = SpeedCurveMath.drawPoints(curve)
        val (midT, midSpeed) = points[SpeedCurveMath.DRAW_SAMPLES_PER_SEGMENT / 2]
        assertEquals(0.5f, midT, 1e-6f)
        assertEquals(1.25f, midSpeed, 1e-6f)
        // Every sample sits on the interpolation the export applies, and the keys are hit exactly.
        points.forEach { (t, speed) -> assertEquals(curve.speedAt(t), speed, 1e-6f) }
        assertEquals(0f to 0.5f, points.first())
        assertEquals(1f, points.last().first, 1e-6f)
        assertEquals(2f, points.last().second, 1e-5f)
    }

    @Test
    fun `drawPoints passes through every key of a multi-segment curve`() {
        val curve = curveOf(0f to 0.5f, 0.5f to 3f, 1f to 0.5f)
        val points = SpeedCurveMath.drawPoints(curve)
        assertEquals(1 + 2 * SpeedCurveMath.DRAW_SAMPLES_PER_SEGMENT, points.size)
        val peak = points[SpeedCurveMath.DRAW_SAMPLES_PER_SEGMENT]
        assertEquals(0.5f, peak.first, 1e-6f)
        assertEquals(3f, peak.second, 1e-5f)
    }

    // ── Presets ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `every preset is a valid in-bounds curve within the point cap`() {
        SpeedPreset.entries.forEach { preset ->
            val curve = preset.toCurve()
            assertTrue("${preset.name} exceeds the point cap", curve.keys.size <= SpeedCurve.MAX_KEYS)
            assertEquals("${preset.name} must start at 0", 0f, curve.keys.first().t, 1e-6f)
            assertEquals("${preset.name} must end at 1", 1f, curve.keys.last().t, 1e-6f)
            val times = curve.keys.map { it.t }
            assertEquals("${preset.name} must be sorted", times.sorted(), times)
            curve.keys.forEach { key ->
                assertTrue(
                    "${preset.name} speed ${key.speed} out of bounds",
                    key.speed in SpeedCurve.MIN_SPEED..SpeedCurve.MAX_SPEED,
                )
            }
            assertNotNull(preset.labelResId)
        }
    }

    @Test
    fun `accelerate into reverse peaks at the turn, which is the whole point of the loop-wide domain`() {
        val curve = SpeedPreset.ACCELERATE_INTO_REVERSE.toCurve()
        val atTurn = curve.speedAt(0.5f)
        assertTrue("peak $atTurn should beat the start", atTurn > curve.speedAt(0f))
        assertTrue("peak $atTurn should beat the end", atTurn > curve.speedAt(1f))
        assertTrue("peak $atTurn should beat the quarter points", atTurn > curve.speedAt(0.25f))
        assertTrue(atTurn > curve.speedAt(0.75f))
    }

    @Test
    fun `no preset is flat, or it would be indistinguishable from Reset`() {
        SpeedPreset.entries.forEach { preset ->
            assertTrue("${preset.name} must actually vary", !preset.toCurve().isFlat)
        }
    }

    @Test
    fun `speedDiffersMeaningfully gates on the hysteresis threshold`() {
        assertTrue(speedDiffersMeaningfully(1.0f, 1.05f, 0.02f))
        assertTrue(!speedDiffersMeaningfully(1.0f, 1.005f, 0.02f))
    }

    // ── Seam markers ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `seamFractionsFor marks each internal boundary of the loop`() {
        val fractions = seamFractionsFor(listOf(1_000L, 1_000L))
        assertEquals(1, fractions.size)
        assertEquals(0.5f, fractions[0], 1e-5f)
    }

    @Test
    fun `seamFractionsFor accounts for unequal clip lengths from seam drops`() {
        val fractions = seamFractionsFor(listOf(1_000L, 900L, 1_000L))
        assertEquals(2, fractions.size)
        assertEquals(1_000f / 2_900f, fractions[0], 1e-4f)
        assertEquals(1_900f / 2_900f, fractions[1], 1e-4f)
    }

    @Test
    fun `seamFractionsFor has no markers for a single clip or an empty loop`() {
        assertTrue(seamFractionsFor(listOf(1_000L)).isEmpty())
        assertTrue(seamFractionsFor(emptyList()).isEmpty())
        assertTrue(seamFractionsFor(listOf(0L, 0L)).isEmpty())
    }
}
