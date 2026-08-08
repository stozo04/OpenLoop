package io.github.stozo04.openloop.ui.components

/**
 * Pure clamp math for the trim handles, kept free of Compose/Android imports so it is JVM-testable
 * ([io.github.stozo04.openloop.ui.components.TrimHandleMathTest]) — same split as
 * `media/BoomerangSequence.kt`.
 *
 * Kotlin's `coerceIn(min, max)` throws `IllegalArgumentException` when `max < min`, and both trim
 * bounds are derived from runtime state: a clip shorter than the minimum trim window (e.g. a 335 ms
 * capture vs the 400 ms gap) inverts the range — Crashlytics 7169b499 / issue #95, Lesson 030. Each
 * derived bound is therefore clamped to keep the range valid; on a too-short clip both handles pin
 * (start at 0, end at the clip duration), matching the disabled NEXT button on the Trim screen.
 */

/** START handle: clamp [targetMs] into `[0, endMs - minGapMs]`, flooring the upper bound at 0. */
internal fun clampTrimStartMs(targetMs: Long, endMs: Long, minGapMs: Long): Long =
    targetMs.coerceIn(0L, (endMs - minGapMs).coerceAtLeast(0L))

/**
 * END handle: clamp [targetMs] into `[startMs + minGapMs, durationMs]`, capping the lower bound at
 * [durationMs].
 */
internal fun clampTrimEndMs(targetMs: Long, startMs: Long, durationMs: Long, minGapMs: Long): Long =
    targetMs.coerceIn((startMs + minGapMs).coerceAtMost(durationMs), durationMs)
