package io.github.stozo04.openloop.camera.lens

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

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
     * How a layer spins when flicked (`docs/PRD-lens-interactions.md`). Like [WobbleSpec], every
     * number is in the dimensionless face-unit / radian space, so one set behaves identically at
     * any distance from the camera and on any device.
     *
     * @param gain flick torque → angular velocity multiplier. Tuned per lens so a comfortable
     *   flick travels about one full revolution (total travel under exponential decay is
     *   `ω₀ × frictionHalfLifeSeconds / ln 2`).
     * @param frictionHalfLifeSeconds time for the spin to lose half its speed. The whole gesture
     *   plays out in a few of these, then the landing ease takes over.
     * @param maxAngularVelocity hard cap, rad/s — an absurd fling or a garbled velocity can never
     *   make the art an unwatchable blur, the same job [WobbleSpec.limitRadians] does for swings.
     * @param minHandSpeed face units per second a hand touching the art must move to count as a
     *   flick ([HandFlick]); slower is a hand resting on it. A face unit is eye line to mouth, so
     *   3 is roughly 20 cm/s — a wave clears it, a hand adjusting hair does not.
     */
    data class SpinSpec(
        val gain: Float,
        val frictionHalfLifeSeconds: Float,
        val maxAngularVelocity: Float,
        val minHandSpeed: Float,
    )

    /**
     * A layer's live spin: accumulated angle and signed angular velocity. Positive is clockwise on
     * screen — the same y-down convention as [LensAnchor.sticker]'s rotation.
     */
    data class Spin(val angleRadians: Float, val velocity: Float) {
        companion object {
            /** Not spinning, landed square on its tracked orientation. */
            val REST = Spin(angleRadians = 0f, velocity = 0f)
        }
    }

    /**
     * Adds a flick's torque to a spin, as if the finger grabbed the art where it touched.
     *
     * [leverX]/[leverY] is the vector from the quad's center to the touch point and
     * [velocityX]/[velocityY] the flick velocity — both in face units (square space, y down), so
     * the same flick reads the same at any distance from the camera. The cross product makes the
     * direction physical: flick the top of the ball rightward and it spins clockwise, flick the
     * bottom rightward and it spins the other way. A flick on an already-spinning layer *adds* its
     * impulse, so repeated flicks pump the spin up (to the cap) or brake it.
     *
     * [MIN_LEVER_UNITS] floors the lever arm so a near-center flick cannot divide toward
     * infinity; an exactly dead-center flick has zero torque and does nothing, which is the
     * physically honest outcome. Non-finite inputs leave the state untouched — the same
     * garbage-in guard as [step].
     */
    fun spinImpulse(
        state: Spin,
        leverX: Float,
        leverY: Float,
        velocityX: Float,
        velocityY: Float,
        spec: SpinSpec,
    ): Spin {
        if (!leverX.isFinite() || !leverY.isFinite() ||
            !velocityX.isFinite() || !velocityY.isFinite()
        ) {
            return state
        }
        val lever = max(hypot(leverX, leverY), MIN_LEVER_UNITS)
        val torque = leverX * velocityY - leverY * velocityX
        val velocity = (state.velocity + spec.gain * torque / (lever * lever))
            .coerceIn(-spec.maxAngularVelocity, spec.maxAngularVelocity)
        return Spin(state.angleRadians, velocity)
    }

    /**
     * Advances a spin by one frame: exponential friction on the velocity while it is fast, then a
     * landing ease that always ends **on a whole revolution**.
     *
     * The half-life form is frame-rate independent (like [ease]) and [dtSeconds] is clamped by the
     * same [MAX_STEP_SECONDS] rule, so a dropped frame cannot teleport the angle. Once the
     * velocity falls under [SPIN_LANDING_VELOCITY], the angle eases to the **nearest multiple of
     * 2π** — a correction of at most half a turn, whichever direction is shorter — and snaps to
     * [Spin.REST] when it arrives. Landing on a whole revolution is what lets the renderer hide a
     * character's features during the spin and bring them back exactly where they vanished
     * (PRD-lens-interactions §3.5, owner decision D2).
     */
    fun spinStep(state: Spin, dtSeconds: Float, spec: SpinSpec): Spin {
        if (state == Spin.REST) return state
        val dt = dtSeconds.coerceIn(0f, MAX_STEP_SECONDS)
        if (dt <= 0f) return state
        val decayed = if (spec.frictionHalfLifeSeconds > 0f) {
            state.velocity * 0.5f.pow(dt / spec.frictionHalfLifeSeconds)
        } else {
            0f
        }
        if (abs(decayed) >= SPIN_LANDING_VELOCITY) {
            return Spin(state.angleRadians + decayed * dt, decayed)
        }
        val target = (state.angleRadians / TWO_PI).roundToInt() * TWO_PI
        val eased = ease(state.angleRadians, target, dt, SPIN_LANDING_HALF_LIFE_SECONDS)
        if (abs(eased - target) <= SPIN_LANDING_EPSILON_RADIANS) return Spin.REST
        return Spin(eased, velocity = 0f)
    }

    /** One full turn — the landing quantum. */
    private const val TWO_PI = (2.0 * PI).toFloat()

    /**
     * Below this angular speed the spin stops coasting and starts its landing ease. High enough
     * that the hand-off is invisible (the last coasting frames and the ease move comparably),
     * low enough that a real spin plays out fully before it fires.
     */
    private const val SPIN_LANDING_VELOCITY = 1.5f

    /** Half-life of the landing ease — the ~150 ms glide onto the whole revolution. */
    private const val SPIN_LANDING_HALF_LIFE_SECONDS = 0.06f

    /** Close enough to the landing target to snap to [Spin.REST] — ~1/6 of a degree. */
    private const val SPIN_LANDING_EPSILON_RADIANS = 0.003f

    /**
     * Floor for the flick's lever arm, in face units. Keeps the torque division bounded when the
     * finger lands almost exactly on the quad's center.
     */
    private const val MIN_LEVER_UNITS = 0.3f

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

/** Convenience alias so a lens can name a spin spec without importing the object. */
typealias SpinSpec = LensPhysics.SpinSpec
