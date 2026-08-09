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
 * on major-interval multiples whose rounded `mm:ss` text would not collide with the end label
 * (so a 2.2 s clip does not draw two adjacent `"00:02"` marks).
 */
internal fun trimRulerLabelTimesMs(durationMs: Long): List<Long> {
    val safe = durationMs.coerceAtLeast(0L)
    if (safe == 0L) return listOf(0L)

    val major = trimRulerMajorIntervalMs(safe)
    val endLabel = formatTrimRulerLabel(safe)
    val labels = mutableListOf(0L)
    var t = major
    while (t < safe) {
        if (formatTrimRulerLabel(t) != endLabel) {
            labels.add(t)
        }
        t += major
    }
    labels.add(safe)
    return labels
}

/** `mm:ss` ruler label; rounds to the nearest whole second (ruler ticks are whole-second marks). */
internal fun formatTrimRulerLabel(ms: Long): String {
    val totalSeconds = ((ms.coerceAtLeast(0L) + 500L) / 1_000L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}
