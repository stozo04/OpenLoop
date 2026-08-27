package io.github.stozo04.openloop.ui.components

import java.util.Locale

/**
 * Pure timeline-ruler math for the Trim filmstrip, kept free of Compose/Android imports so it is
 * JVM-testable. The ruler must span exactly `[0, durationMs]` — the same domain as the filmstrip
 * handles — otherwise short clips show a padded end label (e.g. `5.0s` on a 2.6 s video).
 *
 * Every trim time on screen — the range pill, the handle `stateDescription`, the ruler ticks, the
 * speed graph's axis — is formatted here as **seconds only**. Both entry points cap a clip at
 * `OpenLoopViewModel.MAX_RECORDING` (30 s, plus `IMPORT_DURATION_GRACE` for imports), so a minutes
 * field is never non-zero by design and its width goes to sub-second precision instead (issue
 * #154). A clip that slips past the cap anyway (the tick-based recording timer drifts on a starved
 * emulator) still reads plainly — `65.46s`. `TrimRulerMathTest` pins the configured ceiling: raise
 * it past 60 s and these formatters need minutes back.
 */

/**
 * Labelled-tick spacing. Every second up to 10 s — the length of most boomerang captures — then
 * the coarsest step that still keeps about ten labels on the rail: a 360 dp phone fits ten
 * `10.0s`-wide labels at 11 sp and no more. The 1 s resolution survives as minor ticks on the
 * longer clips ([trimRulerMinorIntervalMs]).
 */
internal fun trimRulerMajorIntervalMs(durationMs: Long): Long = when {
    durationMs <= 10_000L -> 1_000L
    durationMs <= 20_000L -> 2_000L
    durationMs <= 60_000L -> 5_000L
    else -> 10_000L
}

/** Minor tick spacing under a given major interval; never coarser than one second. */
internal fun trimRulerMinorIntervalMs(majorIntervalMs: Long): Long = when {
    majorIntervalMs <= 1_000L -> 250L
    majorIntervalMs <= 2_000L -> 500L
    else -> 1_000L
}

/**
 * The share of the rail beside each edge label that an intermediate label must stay out of. The
 * edge labels are pinned to the rail ends and the intermediate ones are centred on their ticks,
 * so the two touch when the tick is 1.5 label widths from the edge. Callers measure the widest
 * label on their rail (the end label) and pass real pixels — a guessed percentage either drops
 * `7.0s` beside `7.8s` on a phone where it fits, or draws `60.0s` under `65.5s` where it doesn't.
 */
internal fun trimRulerEdgeClearance(labelWidthPx: Float, railWidthPx: Float): Float =
    if (railWidthPx <= 0f) 0f else 1.5f * labelWidthPx / railWidthPx

/**
 * Label times in ms for the ruler. Always includes `0` and [durationMs] — the end label is the
 * clip's true length, never a rounded-up bucket. Intermediate labels sit on major-interval
 * multiples, minus any that would land within [edgeClearance] (a fraction of the rail, from
 * [trimRulerEdgeClearance]) of either pinned edge label.
 */
internal fun trimRulerLabelTimesMs(durationMs: Long, edgeClearance: Float): List<Long> {
    val safe = durationMs.coerceAtLeast(0L)
    if (safe == 0L) return listOf(0L)

    val major = trimRulerMajorIntervalMs(safe)
    val clearanceMs = (safe * edgeClearance.coerceIn(0f, 1f)).toLong()
    val labels = mutableListOf(0L)
    var t = major
    while (t <= safe - clearanceMs) {
        if (t >= clearanceMs) labels.add(t)
        t += major
    }
    labels.add(safe)
    return labels
}

/** Trim readout with hundredths — `2.30s`. The range pill and the handles' `stateDescription`. */
internal fun formatTrimClock(ms: Long): String = formatTrimSeconds(ms, decimals = 2)

/** Ruler tick with tenths — `2.0s`. Every tick, whole-second or the true end, reads the same way. */
internal fun formatTrimRulerLabel(ms: Long): String = formatTrimSeconds(ms, decimals = 1)

private fun formatTrimSeconds(ms: Long, decimals: Int): String =
    String.format(Locale.US, "%.${decimals}fs", ms.coerceAtLeast(0L) / 1000.0)
