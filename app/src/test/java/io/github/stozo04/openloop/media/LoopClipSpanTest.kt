package io.github.stozo04.openloop.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **The highest-risk arithmetic in the speed-curve feature** (PRD-speed-curves.md §5.4, DoD item 1).
 *
 * A speed curve is defined over the whole loop's 1× timeline, and each clip is handed the slice of it
 * covering that clip's span. Those spans must accumulate from **post-seam-drop** durations: a clip
 * that turns direction from its predecessor drops its leading frame and is one frame shorter
 * (Lesson 018). Laying the clips out at `index × windowUs` instead would slide the curve later by one
 * frame per turn — invisible on a two-clip loop, plainly wrong once repetitions stack turns up.
 *
 * These tests fail if that ever regresses.
 */
class LoopClipSpanTest {

    private val windowMs = 2_000L
    private val seamMs = 33L // ~1 frame at 30 fps
    private val windowUs = windowMs * 1_000L
    private val seamUs = seamMs * 1_000L

    private fun spansFor(mode: BoomerangMode, repetitions: Int): List<ClipSpan> =
        loopClipSpans(boomerangSequence(mode, repetitions), windowMs, seamMs)

    private val allModes = BoomerangMode.entries

    @Test
    fun `spans are contiguous with no gaps or overlaps for every mode and repetition count`() {
        for (mode in allModes) {
            for (reps in 1..2) {
                val spans = spansFor(mode, reps)
                assertEquals("$mode x$reps starts at zero", 0L, spans.first().startUs)
                spans.zipWithNext { previous, next ->
                    assertEquals(
                        "$mode x$reps: clip must start exactly where the previous one ended",
                        previous.startUs + previous.durationUs,
                        next.startUs,
                    )
                }
                assertEquals(
                    "$mode x$reps: total must equal the sum of the parts",
                    spans.sumOf { it.durationUs },
                    spans.totalUs(),
                )
            }
        }
    }

    @Test
    fun `a turning clip is exactly one seam frame shorter and a non-turning clip is not`() {
        for (mode in allModes) {
            for (reps in 1..2) {
                val specs = boomerangSequence(mode, reps)
                val spans = loopClipSpans(specs, windowMs, seamMs)
                specs.forEachIndexed { index, spec ->
                    val expected = if (spec.dropLeadingFrame) windowUs - seamUs else windowUs
                    assertEquals(
                        "$mode x$reps clip $index (drop=${spec.dropLeadingFrame})",
                        expected,
                        spans[index].durationUs,
                    )
                }
            }
        }
    }

    /**
     * The actual regression guard. `FORWARD_THEN_REVERSE` × 2 is `[fwd, rev, fwd, rev]` — three
     * internal turns, so by the fourth clip a nominal `index × windowUs` layout is a full three
     * frames ahead of the truth.
     */
    @Test
    fun `span starts diverge from a nominal layout once turns accumulate`() {
        val spans = spansFor(BoomerangMode.FORWARD_THEN_REVERSE, repetitions = 2)
        assertEquals(4, spans.size)

        // First clip never drops, so it alone matches the nominal layout.
        assertEquals(0L, spans[0].startUs)
        assertEquals(windowUs, spans[1].startUs)

        // Every clip after the first turn is behind the nominal position by one seam per turn.
        assertEquals(2 * windowUs - seamUs, spans[2].startUs)
        assertEquals(3 * windowUs - 2 * seamUs, spans[3].startUs)

        assertNotEquals(
            "clip 3 must NOT sit at the nominal 3 x window position",
            3 * windowUs,
            spans[3].startUs,
        )
        assertEquals(4 * windowUs - 3 * seamUs, spans.totalUs())
    }

    @Test
    fun `single-clip modes have no drop and span the whole window`() {
        for (mode in listOf(BoomerangMode.FORWARD, BoomerangMode.REVERSE)) {
            val spans = spansFor(mode, repetitions = 1)
            assertEquals(1, spans.size)
            assertEquals(0L, spans[0].startUs)
            assertEquals("$mode must not lose a real frame", windowUs, spans[0].durationUs)
        }
    }

    @Test
    fun `same-direction repeats never drop so the layout stays nominal`() {
        val spans = spansFor(BoomerangMode.FORWARD, repetitions = 2)
        assertEquals(2, spans.size)
        assertEquals(windowUs, spans[1].startUs)
        assertEquals(2 * windowUs, spans.totalUs())
    }

    /**
     * End-to-end continuity: walking every clip's slice of the curve must reproduce the global curve
     * without a jump at any seam. This is what the per-clip `SpeedProvider` split has to preserve.
     */
    @Test
    fun `per-clip curve slices reconstruct the global curve continuously across seams`() {
        val curve = SpeedCurve(
            listOf(
                SpeedKey(0f, 0.5f),
                SpeedKey(0.5f, 2.5f),
                SpeedKey(1f, 0.75f),
            ),
        )
        for (mode in allModes) {
            for (reps in 1..2) {
                val spans = spansFor(mode, reps)
                val totalUs = spans.totalUs()
                spans.forEachIndexed { index, span ->
                    // The speed at each clip's first and last sampled step, as the clip-local slice
                    // sees it (the rebased provider the render is handed)...
                    val slice = curve.sampleClip(span.startUs, span.durationUs, totalUs, stepUs = 33_000L)
                    val sliceStart = slice.getValue(0L)
                    val (lastLocalUs, sliceEnd) = slice.entries.last().let { it.key to it.value }
                    // ...must equal what the global curve says at the same global instant.
                    val globalStart = curve.speedAt(span.startUs.toFloat() / totalUs)
                    val globalEnd = curve.speedAt((span.startUs + lastLocalUs).toFloat() / totalUs)
                    assertEquals("$mode x$reps clip $index start", globalStart, sliceStart, 1e-6f)
                    assertEquals("$mode x$reps clip $index end", globalEnd, sliceEnd, 1e-6f)

                    if (index > 0) {
                        val previous = spans[index - 1]
                        val seamUsGlobal = previous.startUs + previous.durationUs
                        assertEquals(
                            "$mode x$reps: no discontinuity at the seam before clip $index",
                            span.startUs,
                            seamUsGlobal,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `a window shorter than the seam clamps to zero instead of going negative`() {
        // A sub-frame trim window can reach here through a degenerate capture; a negative duration
        // would poison every downstream fraction with a divide-by-negative.
        val spans = loopClipSpans(
            boomerangSequence(BoomerangMode.FORWARD_THEN_REVERSE, 1),
            windowMs = 10L,
            seamMs = 33L,
        )
        assertTrue("durations must never be negative", spans.all { it.durationUs >= 0L })
        assertEquals(0L, spans[1].durationUs)
    }

    @Test
    fun `an empty clip plan yields an empty layout rather than throwing`() {
        val spans = loopClipSpans(emptyList(), windowMs, seamMs)
        assertTrue(spans.isEmpty())
        assertEquals(0L, spans.totalUs())
    }

    // ── The reversed clip is measured, not assumed ───────────────────────────────────────────────
    //
    // Regression: a reversed clip materially shorter than its trim window (a low-frame-rate source
    // can produce one) was laid out as if it were a full window long. The curve's reverse half then
    // covered footage that did not exist, and the editor's duration chip promised a length the
    // encoder never produced.

    @Test
    fun `a short reversed clip shortens only the reversed span`() {
        val specs = boomerangSequence(BoomerangMode.FORWARD_THEN_REVERSE, 1)
        val spans = loopClipSpans(specs, windowMs, seamMs, reversedMs = 600L)

        assertEquals("forward keeps the full window", windowUs, spans[0].durationUs)
        assertEquals("reversed uses its measured length, minus the seam", 600_000L - seamUs, spans[1].durationUs)
        assertEquals(windowUs + 600_000L - seamUs, spans.totalUs())
    }

    @Test
    fun `the reversed measurement moves the seam marker, not just the total`() {
        val specs = boomerangSequence(BoomerangMode.REVERSE_THEN_FORWARD, 1)
        val spans = loopClipSpans(specs, windowMs, seamMs, reversedMs = 600L)
        // REVERSE_THEN_FORWARD: the reversed clip is FIRST and does not drop, so the forward clip
        // now starts at 0.6s rather than at the nominal 2.0s.
        assertEquals(600_000L, spans[0].durationUs)
        assertEquals(600_000L, spans[1].startUs)
        assertEquals(windowUs - seamUs, spans[1].durationUs)
    }

    @Test
    fun `a null or non-positive reversed measurement falls back to the window`() {
        val specs = boomerangSequence(BoomerangMode.FORWARD_THEN_REVERSE, 1)
        val fallback = loopClipSpans(specs, windowMs, seamMs, reversedMs = null)
        assertEquals(loopClipSpans(specs, windowMs, seamMs), fallback)
        assertEquals(fallback, loopClipSpans(specs, windowMs, seamMs, reversedMs = 0L))
        assertEquals(fallback, loopClipSpans(specs, windowMs, seamMs, reversedMs = -5L))
    }

    @Test
    fun `a forward-only loop ignores the reversed measurement entirely`() {
        val specs = boomerangSequence(BoomerangMode.FORWARD, 1)
        assertEquals(
            loopClipSpans(specs, windowMs, seamMs),
            loopClipSpans(specs, windowMs, seamMs, reversedMs = 37L),
        )
    }

    @Test
    fun `output duration follows the measured layout, so the chip cannot over-promise`() {
        val specs = boomerangSequence(BoomerangMode.FORWARD_THEN_REVERSE, 1)
        val assumed = loopClipSpans(specs, windowMs, seamMs)
        val measured = loopClipSpans(specs, windowMs, seamMs, reversedMs = 600L)

        // At 1x the predicted output is just the input length — and the two layouts disagree, which
        // is precisely the discrepancy this fix removes.
        assertEquals(3_967L, loopOutputDurationMs(assumed, 1f))
        assertEquals(2_567L, loopOutputDurationMs(measured, 1f))
    }

    @Test
    fun `output duration divides by the equivalent constant speed`() {
        val spans = loopClipSpans(boomerangSequence(BoomerangMode.FORWARD, 1), windowMs, seamMs)
        assertEquals(2_000L, loopOutputDurationMs(spans, 1f))
        assertEquals(1_000L, loopOutputDurationMs(spans, 2f))
        assertEquals(4_000L, loopOutputDurationMs(spans, 0.5f))
        // A zero/negative speed must not divide by zero — treat as 1x.
        assertEquals(2_000L, loopOutputDurationMs(spans, 0f))
    }

    @Test
    fun `a flat curve's flatten reproduces the constant-speed duration exactly`() {
        val spans = loopClipSpans(boomerangSequence(BoomerangMode.FORWARD_THEN_REVERSE, 1), windowMs, seamMs)
        assertEquals(
            loopOutputDurationMs(spans, 1.5f),
            loopOutputDurationMs(spans, SpeedCurve.flat(1.5f).flatten()),
        )
    }
}
