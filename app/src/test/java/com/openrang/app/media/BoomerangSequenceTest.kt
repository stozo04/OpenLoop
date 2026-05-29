package com.openrang.app.media

import com.openrang.app.media.ClipDirection.FORWARD
import com.openrang.app.media.ClipDirection.REVERSED
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM tests for [boomerangSequence] — the clip-ordering + seam-drop decision extracted out of
 * the Media3 [Media3VideoProcessor] (which needs a device) so the position-based seam rule is
 * exercised without a codec. Each case lists the expected [ClipSpec]s as (direction, dropLeadingFrame).
 *
 * The two slice-02 bugs these guard against: a standalone `REVERSE` must NOT drop a frame (it has no
 * seam), and `REVERSE_THEN_FORWARD` must drop the *forward* clip (the one in second position), not
 * the reversed one.
 */
class BoomerangSequenceTest {

    private fun specs(vararg pairs: Pair<ClipDirection, Boolean>) =
        pairs.map { ClipSpec(it.first, it.second) }

    // ── single repetition (this slice's render: speed 2×, reps 1) ──────────────────────────────────

    @Test
    fun `forward is one undropped forward clip`() {
        assertEquals(specs(FORWARD to false), boomerangSequence(BoomerangMode.FORWARD, 1))
    }

    @Test
    fun `reverse is one undropped reversed clip (no seam to drop)`() {
        // Slice-02 bug: the old code dropped this lone clip's first frame, losing real footage.
        assertEquals(specs(REVERSED to false), boomerangSequence(BoomerangMode.REVERSE, 1))
    }

    @Test
    fun `forward-then-reverse drops the reversed clip at the seam`() {
        assertEquals(
            specs(FORWARD to false, REVERSED to true),
            boomerangSequence(BoomerangMode.FORWARD_THEN_REVERSE, 1),
        )
    }

    @Test
    fun `reverse-then-forward drops the forward clip (second position), not the reversed one`() {
        // Slice-02 bug: the old code dropped the reversed clip (now first) and left the real seam
        // on the forward clip un-dropped.
        assertEquals(
            specs(REVERSED to false, FORWARD to true),
            boomerangSequence(BoomerangMode.REVERSE_THEN_FORWARD, 1),
        )
    }

    // ── repetitions (proves the cycle-to-cycle seam is handled, not just the within-cycle one) ─────

    @Test
    fun `repeated forward never drops (same direction throughout)`() {
        assertEquals(
            specs(FORWARD to false, FORWARD to false, FORWARD to false),
            boomerangSequence(BoomerangMode.FORWARD, 3),
        )
    }

    @Test
    fun `repeated reverse never drops (same direction throughout)`() {
        assertEquals(
            specs(REVERSED to false, REVERSED to false, REVERSED to false),
            boomerangSequence(BoomerangMode.REVERSE, 3),
        )
    }

    @Test
    fun `repeated forward-then-reverse drops every clip after the first`() {
        // F R F R: F→R, R→F, F→R are all direction turns, so the within-cycle AND the cycle-boundary
        // seams both drop — the latter is the slice-02 gap (forward of cycle 2 was never dropped).
        assertEquals(
            specs(FORWARD to false, REVERSED to true, FORWARD to true, REVERSED to true),
            boomerangSequence(BoomerangMode.FORWARD_THEN_REVERSE, 2),
        )
    }

    @Test
    fun `repeated reverse-then-forward drops every clip after the first`() {
        assertEquals(
            specs(REVERSED to false, FORWARD to true, REVERSED to true, FORWARD to true),
            boomerangSequence(BoomerangMode.REVERSE_THEN_FORWARD, 2),
        )
    }

    // ── edge cases ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `non-positive repetitions are coerced to a single cycle`() {
        assertEquals(specs(FORWARD to false, REVERSED to true), boomerangSequence(BoomerangMode.FORWARD_THEN_REVERSE, 0))
        assertEquals(specs(FORWARD to false, REVERSED to true), boomerangSequence(BoomerangMode.FORWARD_THEN_REVERSE, -5))
    }

    @Test
    fun `clip count is cycle length times repetitions`() {
        assertEquals(2, boomerangSequence(BoomerangMode.FORWARD, 2).size)
        assertEquals(6, boomerangSequence(BoomerangMode.FORWARD_THEN_REVERSE, 3).size)
        assertEquals(8, boomerangSequence(BoomerangMode.REVERSE_THEN_FORWARD, 4).size)
    }

    // ── boomerangOutputDurationMs (editor's live readout) ────────────────────────────────────────

    @Test
    fun `single-direction output is trim duration divided by speed`() {
        // FORWARD: one cycle clip → 2000ms / 2x = 1000ms.
        assertEquals(1000L, boomerangOutputDurationMs(BoomerangMode.FORWARD, 0L, 2000L, speed = 2f, repetitions = 1))
        assertEquals(1000L, boomerangOutputDurationMs(BoomerangMode.REVERSE, 0L, 2000L, speed = 2f, repetitions = 1))
    }

    @Test
    fun `two-part output is double the trim duration before speed (PRD example)`() {
        // PRD: a 5 s trim in F→R at reps 1 / speed 2 produces 5 s output (cycle 10s / 2x).
        assertEquals(5000L, boomerangOutputDurationMs(BoomerangMode.FORWARD_THEN_REVERSE, 0L, 5000L, speed = 2f, repetitions = 1))
        assertEquals(5000L, boomerangOutputDurationMs(BoomerangMode.REVERSE_THEN_FORWARD, 0L, 5000L, speed = 2f, repetitions = 1))
    }

    @Test
    fun `output scales with repetitions and respects the trim window offset`() {
        // R→F over [1000,4000] = 3000ms trim, 2 clips/cycle, reps 2, speed 1 → 3000*2*2 = 12000.
        assertEquals(12000L, boomerangOutputDurationMs(BoomerangMode.REVERSE_THEN_FORWARD, 1000L, 4000L, speed = 1f, repetitions = 2))
    }

    @Test
    fun `non-positive speed is treated as 1x to avoid divide-by-zero`() {
        assertEquals(1000L, boomerangOutputDurationMs(BoomerangMode.FORWARD, 0L, 1000L, speed = 0f, repetitions = 1))
    }

    @Test
    fun `non-positive repetitions are coerced to one cycle`() {
        assertEquals(1000L, boomerangOutputDurationMs(BoomerangMode.FORWARD, 0L, 2000L, speed = 2f, repetitions = 0))
    }
}
