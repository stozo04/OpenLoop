package io.github.stozo04.openloop.camera.lens

import kotlin.math.hypot

/**
 * One frame of hand landmarks from [HandTracker], in the **camera buffer's** normalized space —
 * the same space every [FaceSnapshot] lives in, so the sticker quads need no further mapping
 * (`docs/PRD-lens-hand-flick.md` §3.2: the whole view→buffer transform the touch verb needed is
 * gone).
 *
 * Twenty-one MediaPipe landmarks, index-aligned: 0 wrist; 1–4 thumb; 5–8 index; 9–12 middle;
 * 13–16 ring; 17–20 pinky — the last of each finger is its tip.
 */
class HandSnapshot(
    val xs: FloatArray,
    val ys: FloatArray,
    /** Width / height of the buffer these points were measured in — see [FaceSnapshot.sourceAspect]. */
    val sourceAspect: Float,
    /** The detector's frame timestamp, monotonic per MediaPipe's live-stream contract. */
    val timestampMs: Long,
) {
    init {
        require(xs.size == LANDMARK_COUNT && ys.size == LANDMARK_COUNT) {
            "a hand has $LANDMARK_COUNT landmarks, got ${xs.size}/${ys.size}"
        }
    }

    companion object {
        const val LANDMARK_COUNT = 21
    }
}

/**
 * Turns the stream of [HandSnapshot]s into spin impulses — "hand velocity near sticker"
 * (`docs/PRD-lens-hand-flick.md` §3.3). Pure and JVM-tested (`HandFlickTest`); the renderer owns
 * one, calls [evaluate] once per frame on the GL thread with the quads it is about to draw, and
 * hands any impulse to [LensMotion.flick] — exactly where a finger fling used to enter.
 *
 * The rules, in the order [evaluate] applies them:
 *
 * 1. **Contact** — the first landmark (fingertips first) inside a [Target]'s quad, targets in the
 *    order given (the renderer passes them topmost-first, so what the user sees under their hand
 *    wins). No contact re-arms every quad.
 * 2. **Velocity** — the palm centroid (wrist + four finger bases: stable while fingers flutter)
 *    differenced against the previous snapshot over the detector's own clock, in face units per
 *    second in square space — the dimensionless currency [LensPhysics.spinImpulse] takes.
 * 3. **Threshold** — a contact slower than the target's `minHandSpeed` is a hand resting on the
 *    ball, not a flick. Nothing fires and nothing is blocked.
 * 4. **One impulse per contact** — after a hit the same quad stays blocked until the hand leaves
 *    it or [REARM_MS] elapse, whichever is first. A hand waving *inside* the ball pumps the spin
 *    at most every [REARM_MS]; a hand passing through gives exactly one impulse.
 */
class HandFlick {

    /** A spin-capable sticker as the renderer will draw it this frame. */
    class Target(
        val trackingId: Int,
        val layerIndex: Int,
        val quad: StickerQuad,
        /** The face's unit (eye line to mouth, square space) — the divisor that makes the impulse dimensionless. */
        val faceUnit: Float,
        /** Face units per second a contact must move to count as a flick ([LensPhysics.SpinSpec.minHandSpeed]). */
        val minHandSpeed: Float,
    )

    /** A flick that landed: lever from the point of contact, velocity from the palm — face units, square space. */
    class Impulse(
        val trackingId: Int,
        val layerIndex: Int,
        val leverX: Float,
        val leverY: Float,
        val velocityX: Float,
        val velocityY: Float,
        val speed: Float,
    )

    private var previous: HandSnapshot? = null

    /** The quad the hand touched last frame, or [NO_CONTACT]; a change of quad re-arms. */
    private var contactKey = NO_CONTACT

    /** Detector time before which the quad in [contactKey] cannot fire again. */
    private var blockedUntilMs = Long.MIN_VALUE

    /**
     * Evaluates one renderer frame. [hand] is the newest snapshot (or `null` while no hand is in
     * frame); [targets] are this frame's spin-capable quads in hit priority order; [frameAspect]
     * is the renderer's frame shape, which the snapshot's y coordinates are re-framed onto.
     *
     * Returns at most one impulse. The same snapshot seen on consecutive renderer frames — the
     * detector runs slower than the camera — is evaluated once.
     */
    fun evaluate(hand: HandSnapshot?, targets: List<Target>, frameAspect: Float): Impulse? {
        if (hand == null) {
            previous = null
            contactKey = NO_CONTACT
            return null
        }
        val before = previous
        if (hand === before) return null
        previous = hand

        var target: Target? = null
        var contact = -1
        search@ for (candidate in targets) {
            for (index in CONTACT_ORDER) {
                val x = hand.xs[index]
                val y = LensAnchor.reframeY(hand.ys[index], hand.sourceAspect, frameAspect)
                if (LensHitTest.contains(candidate.quad, x, y, frameAspect)) {
                    target = candidate
                    contact = index
                    break@search
                }
            }
        }
        if (target == null) {
            contactKey = NO_CONTACT
            return null
        }
        val key = keyOf(target)
        val newContact = key != contactKey
        contactKey = key

        // Velocity needs a previous frame from the same detector run, close enough in time that
        // the difference is motion rather than a teleport across a stall.
        if (before == null) return null
        val dtSeconds = (hand.timestampMs - before.timestampMs) / MILLIS_PER_SECOND
        if (dtSeconds <= 0f || dtSeconds > MAX_GAP_SECONDS) return null
        val unit = target.faceUnit
        if (unit <= 0f || !unit.isFinite()) return null
        val velocityX = (palmX(hand) - palmX(before)) / dtSeconds / unit
        val velocityY = LensAnchor.toSquareY(
            palmY(hand, frameAspect) - palmY(before, frameAspect),
            frameAspect,
        ) / dtSeconds / unit
        val speed = hypot(velocityX, velocityY)
        if (!speed.isFinite() || speed < target.minHandSpeed) return null
        if (!newContact && hand.timestampMs < blockedUntilMs) return null
        blockedUntilMs = hand.timestampMs + REARM_MS

        val quad = target.quad
        val contactY = LensAnchor.reframeY(hand.ys[contact], hand.sourceAspect, frameAspect)
        return Impulse(
            trackingId = target.trackingId,
            layerIndex = target.layerIndex,
            leverX = (hand.xs[contact] - quad.centerX) / unit,
            leverY = LensAnchor.toSquareY(contactY - quad.centerY, frameAspect) / unit,
            velocityX = velocityX,
            velocityY = velocityY,
            speed = speed,
        )
    }

    /** Forgets the hand's history — the renderer's release / lens-change path. */
    fun reset() {
        previous = null
        contactKey = NO_CONTACT
        blockedUntilMs = Long.MIN_VALUE
    }

    private fun palmX(hand: HandSnapshot): Float {
        var sum = 0f
        for (index in PALM) sum += hand.xs[index]
        return sum / PALM.size
    }

    private fun palmY(hand: HandSnapshot, frameAspect: Float): Float {
        var sum = 0f
        for (index in PALM) sum += LensAnchor.reframeY(hand.ys[index], hand.sourceAspect, frameAspect)
        return sum / PALM.size
    }

    private fun keyOf(target: Target): Long =
        (target.trackingId.toLong() shl Int.SIZE_BITS) or (target.layerIndex.toLong() and 0xFFFFFFFFL)

    companion object {
        /**
         * How long a quad stays blocked after firing while the hand keeps touching it. Long enough
         * that one pass through the ball is one impulse at any frame rate; short enough that a
         * deliberate sustained wave still pumps the spin (`docs/PRD-lens-hand-flick.md` D4).
         */
        const val REARM_MS = 400L

        /** A detector gap longer than this is a stall, not motion — no velocity across it. */
        private const val MAX_GAP_SECONDS = 0.5f

        private const val MILLIS_PER_SECOND = 1000f

        private const val NO_CONTACT = Long.MIN_VALUE

        /** Wrist and the four finger bases — the palm, which does not flutter the way tips do. */
        private val PALM = intArrayOf(0, 5, 9, 13, 17)

        /** Fingertips first (index, middle, ring, pinky, thumb), then the rest of the hand. */
        internal val CONTACT_ORDER = intArrayOf(
            8, 12, 16, 20, 4,
            7, 11, 15, 19, 3,
            6, 10, 14, 18, 2,
            5, 9, 13, 17, 1,
            0,
        )
    }
}
