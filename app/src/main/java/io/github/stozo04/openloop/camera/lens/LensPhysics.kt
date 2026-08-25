package io.github.stozo04.openloop.camera.lens

import kotlin.math.pow

/**
 * Secondary motion for lens parts that hang off the face — a tongue, an ear, a jowl.
 *
 * ## Why this exists
 *
 * A sticker pinned rigidly to a landmark reads as a decal: it is *on* the face rather than *of* it.
 * The reference effect this was reverse-engineered from (`twisted-tounge/GUIDE.md`)
 * hangs its tongue off a five-joint chain and puts a `simplePendulumPhysics` component on four of
 * them, plus one on each eyeball. That lag is most of what sells the joke.
 *
 * This is the 2D stand-in: **one damped spring per hanging part**, driven by how far the head slid
 * sideways since the last frame ([LensAnchor.lateralShiftInUnits]). A five-joint chain would be five
 * of these in series; one is enough for a part that reads as a single flap, and it is the whole
 * reason this file is arithmetic rather than a physics engine.
 *
 * ## The model
 *
 * The pivot moves; the mass has inertia and does not, so it falls behind by some fraction of the
 * move. A spring then pulls it back toward rest while damping bleeds off the energy:
 *
 * ```
 * lagged   = offset - drive * pivotShift        // the mass missed the move
 * velocity = velocity + (-stiffness * lagged - damping * velocity) * dt
 * offset   = lagged + velocity * dt
 * ```
 *
 * Driving on the pivot's **displacement** rather than its velocity or acceleration is deliberate.
 * Landmark positions from a per-frame detector are noisy, and each derivative multiplies that noise
 * by `1/dt` — a divide that explodes on exactly the short frames a camera produces most of. A
 * displacement drive needs no division at all, so a jittery detector produces a jittery *drive*
 * rather than an unbounded one.
 *
 * Everything here is pure: state in, state out, no clock, no Android types. `LensPhysicsTest` is
 * therefore the real verification for this feature — the emulator's virtual scene is a *static*
 * poster, so it can prove the lens binds, renders and records, but it can never move a head.
 */
object LensPhysics {

    /**
     * How a hanging part swings. All four numbers are in the dimensionless face-unit space, so one
     * set of them behaves identically at any distance from the camera and on any device.
     *
     * @param stiffness pull back toward rest, per unit of displacement. Higher = snappier, and sets
     *   the wobble frequency (`≈ sqrt(stiffness) / 2π` Hz).
     * @param damping energy bled off per unit of speed. Higher = fewer bounces before it settles.
     * @param drive how much of the head's sideways movement the part fails to follow. `0f` is
     *   welded on; `1f` leaves the part behind entirely.
     * @param limitRadians how far it may swing either way. A hard stop, so a detector glitch can
     *   never fling the art across the frame.
     */
    data class WobbleSpec(
        val stiffness: Float,
        val damping: Float,
        val drive: Float,
        val limitRadians: Float,
    )

    /** A spring's live state: where it is, and how fast it is going. */
    data class Wobble(val offsetRadians: Float, val velocity: Float) {
        companion object {
            /** Hanging straight down, motionless — where a part starts and returns to. */
            val REST = Wobble(offsetRadians = 0f, velocity = 0f)
        }
    }

    /**
     * Advances one spring by [dtSeconds], given how far its pivot slid ([pivotShiftInUnits]).
     *
     * [dtSeconds] is clamped to [MAX_STEP_SECONDS]. Camera frame timestamps gap on a dropped frame,
     * a paused preview or the very first frame after a bind, and explicit integration over an
     * unclamped gap is exactly how a spring goes to infinity. Clamping trades a moment of slow
     * motion for never blowing up, which is the right trade for something drawn over a face.
     */
    fun step(
        state: Wobble,
        pivotShiftInUnits: Float,
        dtSeconds: Float,
        spec: WobbleSpec,
    ): Wobble {
        val dt = dtSeconds.coerceIn(0f, MAX_STEP_SECONDS)
        // A non-finite shift means the tracker handed us garbage; hold position rather than
        // propagating a NaN into the vertex buffer, where it would erase the whole quad.
        if (dt <= 0f || !pivotShiftInUnits.isFinite()) return state

        val lagged = state.offsetRadians - spec.drive * pivotShiftInUnits
        val velocity = state.velocity +
            (-spec.stiffness * lagged - spec.damping * state.velocity) * dt
        val offset = lagged + velocity * dt

        // Hitting the stop kills the velocity into it, the way a real hinge would. Without this the
        // clamp holds the offset while the spring keeps winding up, and it snaps back later.
        if (offset > spec.limitRadians) return Wobble(spec.limitRadians, velocity.coerceAtMost(0f))
        if (offset < -spec.limitRadians) return Wobble(-spec.limitRadians, velocity.coerceAtLeast(0f))
        return Wobble(offset, velocity)
    }

    /**
     * Advances a spring with no pivot movement — what to call when the face is lost.
     *
     * Deliberately not a snap to [Wobble.REST]: the detector drops the odd frame on a blink or a
     * fast turn ([FaceTracker] rides those out for 350 ms), and a part that teleports straight on
     * every drop-out is more visible than one that keeps swinging for a beat.
     */
    fun settle(state: Wobble, dtSeconds: Float, spec: WobbleSpec): Wobble =
        step(state, pivotShiftInUnits = 0f, dtSeconds = dtSeconds, spec = spec)

    /**
     * Eases [current] toward [target] with a frame-rate-independent half-life — the animation
     * state behind a mouth-driven reveal.
     *
     * Deliberately **not** [step]. A spring overshoots, and a tongue that springs *past* full
     * extension and back on every mouth-open reads as a glitch rather than as weight. This is a
     * pure exponential approach: no overshoot, no oscillation, and it cannot leave `[0,1]` if both
     * inputs are inside it.
     *
     * Half-life rather than a per-frame factor because a per-frame factor silently changes speed
     * with frame rate — the same constant would ease twice as fast on a 60 fps preview as on a
     * 30 fps one. [dtSeconds] is clamped by the same [MAX_STEP_SECONDS] rule as [step], so a
     * dropped frame cannot make the reveal jump.
     */
    fun ease(current: Float, target: Float, dtSeconds: Float, halfLifeSeconds: Float): Float {
        val dt = dtSeconds.coerceIn(0f, MAX_STEP_SECONDS)
        if (dt <= 0f || !target.isFinite()) return current
        if (halfLifeSeconds <= 0f) return target
        // 2^(-dt/halfLife): the fraction of the remaining distance still left after this step.
        val remaining = 0.5f.pow(dt / halfLifeSeconds)
        return target + (current - target) * remaining
    }

    /**
     * Longest step the integrator will take, in seconds — a little over two frames at 30 fps.
     *
     * The explicit-Euler stability bound is `dt < 2 / sqrt(stiffness)`; at the stiffest spec that
     * ships (`stiffness = 150`) that is 163 ms, so this leaves a ~2.4x margin.
     */
    const val MAX_STEP_SECONDS = 0.067f
}

/** Convenience alias so a lens can name a spec without importing the object. */
typealias WobbleSpec = LensPhysics.WobbleSpec
