package com.openrang.app.media

import kotlin.math.roundToLong

/** Playback direction of a single clip within a boomerang sequence. */
internal enum class ClipDirection { FORWARD, REVERSED }

/**
 * One clip in the rendered boomerang sequence: which [direction] it plays, and whether its leading
 * frame is dropped to remove a duplicate-frame seam.
 */
internal data class ClipSpec(
    val direction: ClipDirection,
    val dropLeadingFrame: Boolean,
)

/**
 * Resolve the ordered clip sequence for [mode] repeated [repetitions] times, deciding the 1-frame
 * seam drop by **sequence position** rather than clip identity.
 *
 * Why position, not identity: a boomerang concatenates clips, and wherever a clip plays in the
 * *opposite* temporal direction to the clip before it (forward↔reversed), the two share a boundary
 * frame — the first clip's last rendered frame is pixel-identical to the second clip's first. Played
 * back, that duplicate reads as a freeze/stutter at the seam, so the trailing clip drops its leading
 * frame (parent IMPLEMENTATION.md §6.4).
 *
 * The rule:
 *  - The first clip never drops (nothing precedes it).
 *  - A clip drops its leading frame iff its [ClipDirection] differs from the preceding clip's.
 *
 * This keeps the drop correct for every mode AND every repetition count, fixing two latent slice-02
 * bugs that only surface once direction is user-selectable and clips can be reordered/repeated:
 *  - `FORWARD` / `REVERSE` (and any repeats): all clips face the same way, so no boundary is a turn
 *    and nothing is dropped — in particular a lone `REVERSE` clip no longer loses a real frame (the
 *    old code always dropped "the reversed clip" regardless of where it sat).
 *  - `FORWARD_THEN_REVERSE` / `REVERSE_THEN_FORWARD`: every internal boundary is a direction turn —
 *    both the within-cycle seam (e.g. F→R) and the cycle-to-cycle seam (e.g. R→F when repeated) — so
 *    every clip after the first drops. The old code dropped only the reversed clip, leaving the
 *    `REVERSE_THEN_FORWARD` seam (head of the forward clip) un-dropped and dropping the wrong clip.
 */
internal fun boomerangSequence(mode: BoomerangMode, repetitions: Int): List<ClipSpec> {
    val cycle = when (mode) {
        BoomerangMode.FORWARD -> listOf(ClipDirection.FORWARD)
        BoomerangMode.REVERSE -> listOf(ClipDirection.REVERSED)
        BoomerangMode.FORWARD_THEN_REVERSE -> listOf(ClipDirection.FORWARD, ClipDirection.REVERSED)
        BoomerangMode.REVERSE_THEN_FORWARD -> listOf(ClipDirection.REVERSED, ClipDirection.FORWARD)
    }
    val directions = (0 until repetitions.coerceAtLeast(1)).flatMap { cycle }
    return directions.mapIndexed { index, direction ->
        ClipSpec(
            direction = direction,
            dropLeadingFrame = index > 0 && direction != directions[index - 1],
        )
    }
}

/**
 * Output duration of the rendered boomerang in milliseconds, used for the editor's live readout.
 *
 * One cycle is `clipsPerCycle × trimDuration` (1 clip for `FORWARD`/`REVERSE`, 2 for the two-part
 * modes — read off [boomerangSequence] so it can't drift from the actual clip plan); the whole thing
 * is `cycle × repetitions`, then divided by [speed] because faster playback yields a shorter clip.
 * The ~1-frame seam drops are ignored here (sub-frame rounding on a label) and a non-positive [speed]
 * is treated as 1× to avoid a divide-by-zero. Matches slice-03 PRD §"output duration".
 */
internal fun boomerangOutputDurationMs(
    mode: BoomerangMode,
    trimStartMs: Long,
    trimEndMs: Long,
    speed: Float,
    repetitions: Int,
): Long {
    val trimDurationMs = (trimEndMs - trimStartMs).coerceAtLeast(0L)
    val clipsPerCycle = boomerangSequence(mode, 1).size
    val safeSpeed = if (speed > 0f) speed else 1f
    val totalMs = trimDurationMs * clipsPerCycle * repetitions.coerceAtLeast(1)
    return (totalMs.toDouble() / safeSpeed).roundToLong()
}
