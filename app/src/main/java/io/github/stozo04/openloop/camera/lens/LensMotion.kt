package io.github.stozo04.openloop.camera.lens

/**
 * The per-face animation state behind a lens — the wobble springs and the eased mouth — stepped
 * once per camera frame. Pure, no GL, JVM-tested (`LensMotionTest`); [LensSurfaceProcessor] owns
 * one and reads it back per face while drawing.
 *
 * ## Why per face
 *
 * With two people wearing the lens (`docs/PRD-multi-face-lenses.md` D5) the state cannot describe
 * "the subject" any more. One spring driven by two heads swings on the average of their motion;
 * one eased mouth opens both characters' mouths when either person talks. So everything here is
 * keyed by [FaceSnapshot.trackingId]: a face that leaves takes its springs with it, a face that
 * arrives starts at rest, and a solo selfie is exactly the single-subject behaviour it always was.
 *
 * ## The three rules (unchanged from the single-face version)
 *
 * 1. **Once per frame, not once per output.** [step] runs before the renderer's output loop, so
 *    the preview and the recording see the same swing rather than a simulation advanced twice.
 * 2. **In the tracker's own space.** The drive is measured on the raw snapshot against its own
 *    `sourceAspect`, never an output's shape. [LensAnchor.lateralShiftInUnits] divides by the face
 *    unit, so the result is dimensionless and means the same thing to every output.
 * 3. **A dropped frame settles, never snaps.** [FaceRoster] rides out a blink by re-publishing
 *    the last snapshot, so the shift is zero and the spring decays toward rest rather than
 *    teleporting. A face the roster actually drops takes its state with it and returns at rest —
 *    nothing was drawn for it in between, so there is no swing to preserve.
 *
 * Not thread-safe by design: the processor calls it from the GL thread only.
 */
class LensMotion(
    /** Wall-clock fallback for devices whose camera timestamps are zero or run backwards. */
    private val nanoTime: () -> Long = System::nanoTime,
) {

    /** One face's live animation, read by the renderer after [step]. */
    class FaceMotion internal constructor(layerCount: Int) {
        /** This frame's swing angle per art layer, index-aligned with the lens's art. Reused. */
        var wobbleAngles = FloatArray(layerCount)
            internal set

        /**
         * Eased mouth openness, `0f`..`1f`. The detector's raw value jitters frame to frame; easing
         * it is what turns a twitchy number into an animation.
         */
        var openFraction = 0f
            internal set

        internal val wobbles = HashMap<Int, LensPhysics.Wobble>()

        /** Previous frame's snapshot — what a spring step measures its pivot shift from. */
        internal var previousFace: FaceSnapshot? = null

        /** Set on each face seen this frame; anything still false after the pass is evicted. */
        internal var touched = false
    }

    /** The lens the states belong to; a change clears them so a swing cannot carry over. */
    private var lens: Lens? = null

    private val faces = HashMap<Int, FaceMotion>()

    private var previousFrameNs = 0L

    /**
     * Advances every tracked face by one frame.
     *
     * @param lens the active lens, or `null` — with no lens the springs are dropped, since nothing
     *   will draw them and a lens re-selected later must start from rest.
     * @param snapshots this frame's roster from [FaceTracker], in slot order.
     * @param timestampNs the camera frame's timestamp — nanoseconds, monotonic — which ties the
     *   swing to the video's own timeline rather than to how fast the GL thread happens to run.
     */
    fun step(lens: Lens?, snapshots: List<FaceSnapshot>, timestampNs: Long) {
        if (lens !== this.lens) {
            // Springs belong to the lens's layers, so they cannot carry over; the eased mouth
            // describes the *subject* and survives the switch, as it always has.
            faces.values.forEach { motion ->
                motion.wobbles.clear()
                motion.previousFace = null
            }
            this.lens = lens
        }

        // One dt for every face this frame. A device that reports 0 (or steps backwards) falls back
        // to the wall clock rather than freezing the animation.
        val nowNs = if (timestampNs > 0L) timestampNs else nanoTime()
        val elapsedNs = nowNs - previousFrameNs
        val dtSeconds = if (previousFrameNs == 0L || elapsedNs <= 0L) {
            0f
        } else {
            elapsedNs / NANOS_PER_SECOND
        }
        previousFrameNs = nowNs

        val layers = lens?.art.orEmpty()
        for (snapshot in snapshots) {
            val motion = faces.getOrPut(snapshot.trackingId) { FaceMotion(layers.size) }
            motion.touched = true
            stepFace(motion, snapshot, layers, dtSeconds)
        }

        // Evict faces the roster no longer carries, so a departed face's mid-swing spring cannot
        // be inherited by a newcomer that happens to reuse the id.
        val iterator = faces.values.iterator()
        while (iterator.hasNext()) {
            val motion = iterator.next()
            if (motion.touched) motion.touched = false else iterator.remove()
        }
    }

    /** The animation for one face after [step], or `null` if that face was not in the roster. */
    fun forFace(trackingId: Int): FaceMotion? = faces[trackingId]

    /** Drops every face's state — the renderer's release path. */
    fun clear() {
        faces.clear()
        lens = null
        previousFrameNs = 0L
    }

    private fun stepFace(
        motion: FaceMotion,
        face: FaceSnapshot,
        layers: List<LensArt>,
        dtSeconds: Float,
    ) {
        if (motion.wobbleAngles.size != layers.size) motion.wobbleAngles = FloatArray(layers.size)

        // Same clamped dt as the spring, so a dropped frame cannot make the reveal jump either.
        motion.openFraction = LensPhysics.ease(
            current = motion.openFraction,
            target = face.mouthOpenness,
            dtSeconds = dtSeconds,
            halfLifeSeconds = MOUTH_EASE_HALF_LIFE_SECONDS,
        )

        val previous = motion.previousFace
        motion.previousFace = face
        val shift = if (previous != null) {
            LensAnchor.faceFrame(face, face.sourceAspect)
                ?.let { LensAnchor.lateralShiftInUnits(previous, face, it, face.sourceAspect) }
                ?: 0f
        } else {
            0f
        }

        layers.forEachIndexed { index, art ->
            val spec = art.placement.wobble
            if (spec == null) {
                motion.wobbleAngles[index] = 0f
                return@forEachIndexed
            }
            val stepped = LensPhysics.step(
                state = motion.wobbles[index] ?: LensPhysics.Wobble.REST,
                pivotShiftInUnits = shift,
                dtSeconds = dtSeconds,
                spec = spec,
            )
            motion.wobbles[index] = stepped
            motion.wobbleAngles[index] = stepped.offsetRadians
        }
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000f

        /**
         * How fast a mouth-driven layer follows the jaw. 80 ms is under a fifth of a second, so the
         * reveal still feels immediate, while smoothing the detector's frame-to-frame jitter.
         */
        const val MOUTH_EASE_HALF_LIFE_SECONDS = 0.08f
    }
}
