package io.github.stozo04.openloop.media

import kotlin.math.abs
import kotlin.math.ln

/**
 * One point on a [SpeedCurve]: [t] is the position along the whole loop (`0f` = first frame,
 * `1f` = last frame), [speed] the playback multiplier there.
 */
data class SpeedKey(val t: Float, val speed: Float)

/**
 * A playback-speed ramp across the **whole boomerang loop** — not the trim window.
 *
 * Speed between two neighboring keys is **linearly interpolated**, and the export honors exactly
 * that: [sampleClip] flattens the ramp into per-frame constant steps, which is the only shape
 * Media3's piecewise-constant `SpeedProvider` can express. What the graph draws is what the encoder
 * produces (PRD-speed-curves.md D-3 / D-7).
 *
 * The domain is the whole loop, stored normalized, because that is the only way a ramp can peak *at
 * the turn*. On a trim-window domain the reversed half could only ever mirror the forward half, and
 * "Accelerate into Reverse" would be inexpressible (D-1). Normalized also means a re-trim, a
 * direction change, or a repetition change re-stretches the curve instead of invalidating it.
 *
 * Every accessor is **total**: an empty, single-point, or unsorted [keys] list yields sane values
 * rather than throwing. This runs inside the render worker on a production app — a malformed curve
 * must degrade to a sensible speed, never take a save down with it.
 */
data class SpeedCurve(val keys: List<SpeedKey>) {

    /**
     * Defensive ordering. The editor's drag clamps already preserve order and presets are authored
     * sorted, so this is normally a no-op copy; it exists so a hand-built or deserialized curve can
     * never make [speedAt] read a bracket backwards.
     */
    private val ordered: List<SpeedKey> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        keys.sortedBy { it.t }
    }

    /** True when this curve is a flat line — i.e. indistinguishable from a constant speed. */
    val isFlat: Boolean
        get() = ordered.isEmpty() || ordered.all { abs(it.speed - ordered[0].speed) < FLAT_EPSILON }

    /** Interpolated speed at [t]; [t] is clamped to `0f..1f` and the result to the slider bounds. */
    fun speedAt(t: Float): Float {
        if (ordered.isEmpty()) return NEUTRAL_SPEED
        if (ordered.size == 1) return clampSpeed(ordered[0].speed)
        val clampedT = t.coerceIn(0f, 1f)

        // Before the first / after the last key the curve holds flat, so a curve that does not span
        // the full domain still answers everywhere.
        if (clampedT <= ordered.first().t) return clampSpeed(ordered.first().speed)
        if (clampedT >= ordered.last().t) return clampSpeed(ordered.last().speed)

        for (i in 0 until ordered.size - 1) {
            val a = ordered[i]
            val b = ordered[i + 1]
            if (clampedT in a.t..b.t) {
                val span = b.t - a.t
                // Coincident keys (a vertical step the user dragged into a corner): take the later
                // one rather than dividing by zero.
                if (span <= 0f) return clampSpeed(b.speed)
                val fraction = (clampedT - a.t) / span
                return clampSpeed(a.speed + (b.speed - a.speed) * fraction)
            }
        }
        return clampSpeed(ordered.last().speed)
    }

    /** The fastest point on the curve. Keys the render's output frame-rate cap (PRD §5.6). */
    fun maxSpeed(): Float =
        ordered.maxOfOrNull { clampSpeed(it.speed) } ?: NEUTRAL_SPEED

    /**
     * The single constant speed that plays this loop in **the same time the curve does** — the loop's
     * true average playback rate, `sourceConsumed / outputDuration`.
     *
     * That makes it the *harmonic* mean of the curve weighted by input time, not the arithmetic one.
     * The difference is not academic: a 0.5× → 2× ramp arithmetic-averages to 1.25× but actually
     * plays at ~1.08×, so an arithmetic "flatten" would visibly *shorten* the loop at the exact
     * moment the user asked to simplify it — the one moment nothing should jump. It also matches what
     * the encoder will do, since `SpeedProviderUtil.getDurationAfterSpeedProviderApplied` sums
     * `segment / speed` over the same domain.
     */
    fun flatten(): Float {
        val outputSpan = outputSpanFraction()
        if (outputSpan <= 0f) return NEUTRAL_SPEED
        return clampSpeed(1f / outputSpan)
    }

    /**
     * Output duration as a fraction of the input span, i.e. `∫ dt / speed(t)` over `0f..1f`.
     *
     * Closed form per linear segment — no numeric integration needed. For a segment rising from
     * `s0` to `s1` over `dt`: `∫ dt / (s0 + m·t) = dt · ln(s1/s0) / (s1 − s0)`, degenerating to
     * `dt / s0` when the segment is flat.
     */
    private fun outputSpanFraction(): Float {
        if (ordered.isEmpty()) return 1f
        if (ordered.size == 1) return 1f / clampSpeed(ordered[0].speed)

        var total = 0f
        // A curve that starts after 0f / ends before 1f holds flat there — account for those tails
        // so the mean covers the whole loop, matching speedAt's behavior.
        val head = ordered.first().t.coerceIn(0f, 1f)
        if (head > 0f) total += head / clampSpeed(ordered.first().speed)
        val tail = 1f - ordered.last().t.coerceIn(0f, 1f)
        if (tail > 0f) total += tail / clampSpeed(ordered.last().speed)

        for (i in 0 until ordered.size - 1) {
            val a = ordered[i]
            val b = ordered[i + 1]
            val dt = (b.t - a.t).coerceAtLeast(0f)
            if (dt <= 0f) continue
            val s0 = clampSpeed(a.speed)
            val s1 = clampSpeed(b.speed)
            total += if (abs(s1 - s0) < FLAT_EPSILON) {
                dt / s0
            } else {
                dt * ln(s1 / s0) / (s1 - s0)
            }
        }
        return total
    }

    /**
     * Sample the slice of this curve covering one clip of the loop into the piecewise-constant steps
     * Media3 needs: `clip-local start time (µs) → speed`.
     *
     * @param clipStartUs where this clip begins on the loop's **1× input** timeline.
     * @param clipDurationUs this clip's length on that timeline, **after** any seam-frame drop.
     * @param totalUs the whole loop's length on that timeline, also after seam drops.
     * @param stepUs sampling granularity — one source frame; a finer step is unobservable.
     *
     * Consecutive equal speeds are collapsed, which keeps the map small and, more importantly, keeps
     * every key a *real* speed change: `SpeedProviderUtil` walks `getNextSpeedChangeTimeUs` and
     * requires strictly increasing times, and the shader re-times once per reported change.
     */
    fun sampleClip(
        clipStartUs: Long,
        clipDurationUs: Long,
        totalUs: Long,
        stepUs: Long,
    ): LinkedHashMap<Long, Float> {
        val steps = LinkedHashMap<Long, Float>()
        if (clipDurationUs <= 0L || totalUs <= 0L) return steps
        val step = stepUs.coerceAtLeast(1L)

        var localUs = 0L
        var previous = Float.NaN
        while (localUs < clipDurationUs) {
            val speed = speedAt((clipStartUs + localUs).toFloat() / totalUs.toFloat())
            if (previous.isNaN() || abs(speed - previous) >= STEP_EPSILON) {
                steps[localUs] = speed
                previous = speed
            }
            localUs += step
        }
        // A clip shorter than one step still needs its speed, or the provider is empty and the clip
        // silently plays at 1×.
        if (steps.isEmpty()) {
            steps[0L] = speedAt(clipStartUs.toFloat() / totalUs.toFloat())
        }
        return steps
    }

    companion object {
        /**
         * Playback-speed bounds. Single source of truth for the slider, the curve graph, and every
         * clamp in between — `OpenLoopViewModel.MIN_SPEED`/`MAX_SPEED` alias these so the two speed
         * modes can never drift onto different scales (and so Flatten round-trips losslessly).
         */
        const val MIN_SPEED = 0.25f
        const val MAX_SPEED = 3.0f

        /** Unmodified playback — the fallback whenever a curve is too degenerate to read. */
        const val NEUTRAL_SPEED = 1.0f

        /**
         * Fewest keys a curve may hold — the two locked endpoints. `removeKey` refuses below this, so
         * a curve always spans the whole loop.
         */
        const val MIN_KEYS = 2

        /**
         * Most keys a curve may hold: the two locked endpoints plus **one** free interior point.
         *
         * Was 6 during the build; cut to 3 after the owner used it on device (2026-08-17). Three points
         * is one bend — enough for every shape the presets offer — and it makes the editor legible on a
         * 120 dp graph where six handles crowd into each other. It also removes any ambiguity from
         * "Remove point": there is only ever one point that can be removed.
         */
        const val MAX_KEYS = 3

        /** Speed deltas below this are treated as equal (flatness checks, segment degeneration). */
        private const val FLAT_EPSILON = 1e-4f

        /** Sampling emits a new step only past this delta — collapses long near-flat runs. */
        private const val STEP_EPSILON = 1e-3f

        fun clampSpeed(speed: Float): Float =
            if (speed.isNaN()) NEUTRAL_SPEED else speed.coerceIn(MIN_SPEED, MAX_SPEED)

        /** A flat curve at [speed] — what Curve mode seeds from the slider so nothing jumps. */
        fun flat(speed: Float): SpeedCurve {
            val clamped = clampSpeed(speed)
            return SpeedCurve(listOf(SpeedKey(0f, clamped), SpeedKey(1f, clamped)))
        }
    }
}
