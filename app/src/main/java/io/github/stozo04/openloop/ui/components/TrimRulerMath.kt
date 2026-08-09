package io.github.stozo04.openloop.ui.components

import java.util.Locale

/**
 * Pure timeline-ruler math for the Trim filmstrip, kept free of Compose/Android imports so it is
 * JVM-testable. The ruler must span exactly `[0, durationMs]` — the same domain as the filmstrip
 * handles — otherwise short clips show a padded end label (e.g. `00:05` on a 2.6 s video).
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
 * Label times in ms for the ruler. Always includes `0` and [durationMs]; intermediate labels land
 * on major-interval multiples whose rounded `mm:ss` text would not collide with the end's
 * rounded second (so a 2.2 s clip does not draw a whole-second `"00:02"` next to end `"00:02.2"`).
 */
internal fun trimRulerLabelTimesMs(durationMs: Long): List<Long> {
    val safe = durationMs.coerceAtLeast(0L)
    if (safe == 0L) return listOf(0L)

    val major = trimRulerMajorIntervalMs(safe)
    val endRounded = formatTrimRulerLabel(safe)
    val labels = mutableListOf(0L)
    var t = major
    while (t < safe) {
        if (formatTrimRulerLabel(t) != endRounded) {
            labels.add(t)
        }
        t += major
    }
    labels.add(safe)
    return labels
}

/** `mm:ss` label for intermediate ticks; rounds to the nearest whole second. */
internal fun formatTrimRulerLabel(ms: Long): String {
    val totalSeconds = ((ms.coerceAtLeast(0L) + 500L) / 1_000L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}

/**
 * End-of-ruler label with one decimal — same shape as the range pill (`formatTrimClock`) so a
 * 2.6 s clip reads `00:02.6` instead of a rounded `00:03`.
 */
internal fun formatTrimRulerEndLabel(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0L) / 1000.0
    val minutes = (totalSeconds / 60.0).toInt()
    val seconds = totalSeconds - minutes * 60.0
    return String.format(Locale.US, "%02d:%04.1f", minutes, seconds)
}
