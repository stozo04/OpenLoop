package io.github.stozo04.openloop.ui.components

import io.github.stozo04.openloop.media.SpeedCurve
import io.github.stozo04.openloop.media.SpeedKey
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * Pure geometry and editing rules for the speed-curve graph.
 *
 * Lives outside the composable on purpose (Lesson 030): clamp math inlined in a gesture lambda is
 * unreachable from a JVM test, and this file is nothing *but* clamps with runtime-derived bounds —
 * exactly the shape that produced the `coerceIn(400, 335)` crash. Every bound here is itself clamped
 * before it reaches `coerceIn`.
 *
 * It is also the single implementation shared by the drag path **and** the TalkBack custom actions,
 * so the two input routes cannot drift apart (PRD-speed-curves.md §4.7).
 */
object SpeedCurveMath {

    /**
     * Minimum spacing between neighbouring keys, as a fraction of the loop. Keeps handles far enough
     * apart to hit on a phone and stops a drag from stacking two keys into a vertical wall.
     */
    const val MIN_KEY_GAP = 0.05f

    /** How far (in normalized graph space) a touch may land from a handle and still grab it. */
    const val TOUCH_TOLERANCE = 0.09f

    /** Step sizes for the accessibility actions. */
    const val A11Y_SPEED_STEP = 0.05f
    const val A11Y_TIME_STEP = 0.02f

    // ── Axis mapping ────────────────────────────────────────────────────────────────────────────
    //
    // The Y axis is LOGARITHMIC in speed, not linear. Speed is perceived multiplicatively — half
    // speed and double speed are equal-and-opposite moves — and a linear axis over 0.25×..3.0×
    // buries 1× at 27% of the height and crushes the whole slow-motion range into the bottom tenth.
    // On a log axis 0.5× and 2× sit symmetrically about 1×, which is what makes dragging feel right
    // and what the reference mock's proportions actually show.

    /** Speed → vertical fraction (`0f` = bottom / slowest, `1f` = top / fastest). */
    fun speedToFraction(speed: Float): Float {
        val clamped = SpeedCurve.clampSpeed(speed)
        return (ln(clamped / SpeedCurve.MIN_SPEED) / LOG_SPAN).coerceIn(0f, 1f)
    }

    /** Vertical fraction → speed. Inverse of [speedToFraction]. */
    fun fractionToSpeed(fraction: Float): Float =
        SpeedCurve.clampSpeed(SpeedCurve.MIN_SPEED * exp(fraction.coerceIn(0f, 1f) * LOG_SPAN))

    // ── Editing ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Where key [index] is allowed to sit in time.
     *
     * The first and last keys are the loop's endpoints and are **x-locked** — a curve must always
     * span the whole loop, so they pin to `0f` and `1f`. Interior keys are held [MIN_KEY_GAP] clear
     * of both neighbours.
     *
     * The upper bound is floored at the lower bound before clamping. Without that, two keys dragged
     * closer than `2 × MIN_KEY_GAP` invert the range and `coerceIn` throws (Lesson 030).
     */
    fun clampKeyT(targetT: Float, index: Int, keys: List<SpeedKey>): Float {
        if (keys.isEmpty()) return targetT.coerceIn(0f, 1f)
        if (index <= 0) return 0f
        if (index >= keys.size - 1) return 1f
        val lower = (keys[index - 1].t + MIN_KEY_GAP).coerceIn(0f, 1f)
        val upper = (keys[index + 1].t - MIN_KEY_GAP).coerceIn(0f, 1f)
        return targetT.coerceIn(lower, upper.coerceAtLeast(lower))
    }

    /** Move key [index] to ([targetT], [targetSpeed]), clamped in both axes. */
    fun moveKey(curve: SpeedCurve, index: Int, targetT: Float, targetSpeed: Float): SpeedCurve {
        val keys = curve.keys
        if (index !in keys.indices) return curve
        val moved = keys.toMutableList()
        moved[index] = SpeedKey(
            t = clampKeyT(targetT, index, keys),
            speed = SpeedCurve.clampSpeed(targetSpeed),
        )
        return SpeedCurve(moved)
    }

    /**
     * Insert a key at [t], taking its speed from the curve there so the shape does not jump — the new
     * handle appears exactly on the line the user tapped.
     *
     * Refuses when the curve is already at [SpeedCurve.MAX_KEYS] or when [t] is too close to an
     * existing key; returns the curve unchanged so callers can compare identity to detect the no-op
     * (and skip the haptic).
     */
    fun insertKeyAt(curve: SpeedCurve, t: Float): SpeedCurve {
        val keys = curve.keys
        if (keys.size >= SpeedCurve.MAX_KEYS) return curve
        val clampedT = t.coerceIn(0f, 1f)
        // Never insert on top of an endpoint or inside another key's personal space.
        if (clampedT <= MIN_KEY_GAP || clampedT >= 1f - MIN_KEY_GAP) return curve
        if (keys.any { abs(it.t - clampedT) < MIN_KEY_GAP }) return curve

        val inserted = (keys + SpeedKey(clampedT, curve.speedAt(clampedT))).sortedBy { it.t }
        return SpeedCurve(inserted)
    }

    /**
     * Insert a key in the widest gap — the ＋ Point button, and the only way a screen-reader user can
     * add one, since tapping an arbitrary canvas coordinate is not reachable via TalkBack (§4.7).
     */
    fun insertKeyInWidestGap(curve: SpeedCurve): SpeedCurve {
        val keys = curve.keys
        if (keys.size < 2 || keys.size >= SpeedCurve.MAX_KEYS) return curve
        var bestT = Float.NaN
        var bestGap = 0f
        for (i in 0 until keys.size - 1) {
            val gap = keys[i + 1].t - keys[i].t
            if (gap > bestGap) {
                bestGap = gap
                bestT = keys[i].t + gap / 2f
            }
        }
        return if (bestT.isNaN()) curve else insertKeyAt(curve, bestT)
    }

    /** Remove an interior key. Endpoints are not removable — a curve must span the whole loop. */
    fun removeKey(curve: SpeedCurve, index: Int): SpeedCurve {
        val keys = curve.keys
        if (index !in keys.indices) return curve
        if (index == 0 || index == keys.size - 1) return curve
        if (keys.size <= 2) return curve
        return SpeedCurve(keys.filterIndexed { i, _ -> i != index })
    }

    /**
     * The key nearest ([t], [speed]) within [TOUCH_TOLERANCE], or null.
     *
     * Distance is measured in **graph space** (both axes normalized to 0..1) rather than in time,
     * so a handle directly above another is not stolen by it — the same reason the axes are mapped
     * before hit-testing rather than after.
     */
    fun nearestKeyIndex(curve: SpeedCurve, t: Float, speed: Float): Int? {
        val keys = curve.keys
        if (keys.isEmpty()) return null
        val targetY = speedToFraction(speed)
        var bestIndex: Int? = null
        var bestDistance = Float.MAX_VALUE
        keys.forEachIndexed { index, key ->
            val dx = key.t - t
            val dy = speedToFraction(key.speed) - targetY
            val distance = dx * dx + dy * dy
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = index
            }
        }
        return bestIndex.takeIf { bestDistance <= TOUCH_TOLERANCE * TOUCH_TOLERANCE }
    }

    /** True when [index] may be dragged in time (interior keys only). */
    fun isTimeMovable(curve: SpeedCurve, index: Int): Boolean =
        index > 0 && index < curve.keys.size - 1

    private val LOG_SPAN: Float = ln(SpeedCurve.MAX_SPEED / SpeedCurve.MIN_SPEED)
}

/**
 * Pixel ↔ curve-space mapping for the graph canvas, inset by [insetPx] on all sides so a handle
 * sitting at `t = 0f` or at `MAX_SPEED` is still drawn whole rather than clipped in half.
 *
 * Pure so the mapping is JVM-testable: it is the other half of the geometry that Lesson 030 says
 * must not live inside a gesture lambda. Degenerate sizes (a canvas measured at zero before first
 * layout) yield a usable span of 1px rather than dividing by zero.
 */
data class SpeedGraphGeometry(
    val widthPx: Float,
    val heightPx: Float,
    val insetPx: Float,
) {
    private val usableWidth: Float get() = (widthPx - insetPx * 2f).coerceAtLeast(1f)
    private val usableHeight: Float get() = (heightPx - insetPx * 2f).coerceAtLeast(1f)

    /** Horizontal pixel → loop position `0f..1f`. */
    fun tAt(x: Float): Float = ((x - insetPx) / usableWidth).coerceIn(0f, 1f)

    /** Vertical pixel → speed. Y grows downward on a canvas, so the axis is flipped. */
    fun speedAt(y: Float): Float =
        SpeedCurveMath.fractionToSpeed(1f - ((y - insetPx) / usableHeight).coerceIn(0f, 1f))

    /** Loop position → horizontal pixel. */
    fun xOf(t: Float): Float = insetPx + t.coerceIn(0f, 1f) * usableWidth

    /** Speed → vertical pixel. */
    fun yOf(speed: Float): Float =
        insetPx + (1f - SpeedCurveMath.speedToFraction(speed)) * usableHeight
}

/**
 * The preset shapes offered under "Presets" — pure data, no behaviour.
 *
 * There is deliberately no "Linear" entry: **Reset** already returns the curve to a flat line at the
 * current speed, and shipping the same action under two names is surface for nothing.
 */
enum class SpeedPreset(val labelResId: Int, val keys: List<SpeedKey>) {
    /**
     * Slow start that accelerates away. The interior point sits *below* the straight line from end to
     * end, which is what holds the clip slow through the first stretch before it runs.
     */
    EASE_IN(
        io.github.stozo04.openloop.R.string.speed_preset_ease_in,
        listOf(SpeedKey(0f, 0.5f), SpeedKey(0.6f, 0.9f), SpeedKey(1f, 2.2f)),
    ),

    /** [EASE_IN] mirrored in time: bursts out of the gate, then settles. */
    EASE_OUT(
        io.github.stozo04.openloop.R.string.speed_preset_ease_out,
        listOf(SpeedKey(0f, 2.2f), SpeedKey(0.4f, 0.9f), SpeedKey(1f, 0.5f)),
    ),

    /** The symmetric peak — the shape three points were made for. */
    SLOW_FAST_SLOW(
        io.github.stozo04.openloop.R.string.speed_preset_slow_fast_slow,
        listOf(SpeedKey(0f, 0.6f), SpeedKey(0.5f, 2.2f), SpeedKey(1f, 0.6f)),
    ),

    /**
     * Ramps up across the first half, peaks at the midpoint — the turn, for the two-part loop
     * directions — then eases out. This is the preset that justifies the curve spanning the whole
     * loop rather than the trim window (PRD-speed-curves.md D-1): on a trim-window domain the
     * reversed half could only mirror the forward half, so the peak could never land on the turn.
     *
     * Deliberately **asymmetric** — it comes out of the turn faster than it went in. At the 3-point cap
     * a symmetric version would be indistinguishable from [SLOW_FAST_SLOW].
     */
    ACCELERATE_INTO_REVERSE(
        io.github.stozo04.openloop.R.string.speed_preset_accelerate_into_reverse,
        listOf(SpeedKey(0f, 0.5f), SpeedKey(0.5f, 2.6f), SpeedKey(1f, 1.4f)),
    ),
    ;

    fun toCurve(): SpeedCurve = SpeedCurve(keys)
}
