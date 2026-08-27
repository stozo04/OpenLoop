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

/** Major label spacing, scaled to clip length so short captures still get useful ticks. */
internal fun trimRulerMajorIntervalMs(durationMs: Long): Long = when {
    durationMs <= 5_000L -> 1_000L
    durationMs <= 15_000L -> 5_000L
    durationMs <= 60_000L -> 10_000L
    else -> 15_000L
}

/** Minor tick spacing under a given major interval. */
internal fun trimRulerMinorIntervalMs(majorIntervalMs: Long): Long = when {
    majorIntervalMs <= 1_000L -> 250L
    majorIntervalMs <= 5_000L -> 500L
    else -> 1_000L
}

/**
 * An intermediate label closer than this fraction of the clip to the end is dropped, so it never
 * draws on top of the right-pinned end label — `2.0s` on a 2.2 s clip, `30.0s` on a 31 s import.
 * The ruler fills the screen width, so "too close" is a share of the duration, not a fixed number
 * of ms: 15 % clears a ~5-character label on a 360 dp phone with room to spare.
 */
private const val RULER_END_LABEL_CLEARANCE_PERCENT = 15L

/**
 * Label times in ms for the ruler. Always includes `0` and [durationMs]; intermediate labels land
 * on major-interval multiples at least [RULER_END_LABEL_CLEARANCE_PERCENT] of the clip short of
 * the end.
 */
internal fun trimRulerLabelTimesMs(durationMs: Long): List<Long> {
    val safe = durationMs.coerceAtLeast(0L)
    if (safe == 0L) return listOf(0L)

    val major = trimRulerMajorIntervalMs(safe)
    val clearance = safe * RULER_END_LABEL_CLEARANCE_PERCENT / 100L
    val labels = mutableListOf(0L)
    var t = major
    while (safe - t >= clearance) {
        labels.add(t)
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
