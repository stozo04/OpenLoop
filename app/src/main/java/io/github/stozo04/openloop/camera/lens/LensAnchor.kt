package io.github.stozo04.openloop.camera.lens

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Pure placement math for camera lenses — no Android types, so every coordinate rule is
 * JVM-testable (`LensAnchorTest`).
 *
 * ## One idea: the face frame
 *
 * A lens is not positioned by tuning numbers against a detector's bounding box. It is positioned in
 * a coordinate frame built from the subject's own anatomy:
 *
 * * **origin** — the midpoint between the eyes.
 * * **up** — the direction from the mouth midpoint toward the eye midpoint. Every human head has
 *   this axis, and it is the same axis whether the head is tilted, upside down, or mirrored.
 * * **right** — perpendicular to *up*.
 * * **unit** — the eye-to-mouth distance. This is the scale of the face, and it shrinks and grows
 *   with distance from the camera exactly as a lens should.
 *
 * A lens then declares only *where* it sits and *how big* it is, both in units of that frame
 * ([LensPlacement]). Everything else is derived:
 *
 * * **rotation** falls out of the frame's axes — no separate roll angle, no Euler conversions;
 * * **scale** falls out of `unit` — no assumptions about how tight a bounding box is;
 * * **mirroring stops mattering** — the frame is built from *up*, never from the detector's
 *   "left eye"/"right eye" labels, which swap in a mirrored image.
 *
 * That is deliberately the only mechanism here. The earlier version anchored to the bounding box
 * with per-lens offsets plus a roll angle plus a mirror flag; three error sources stacked on magic
 * numbers, and it did not generalize.
 *
 * ## The one coordinate space
 *
 * Positions in and out are **normalized**: `x` and `y` each run `0f..1f` across the frame, origin
 * top-left, **y down**. Because frames are not square, all vector work happens in **square space**,
 * where a y-distance is divided by `frameAspect` so both axes carry the same pixels per unit.
 * [toSquareY] / [fromSquareY] are that conversion and the only place it happens.
 */

/**
 * The landmarks a lens needs, normalized per the file header. Points only — no bounding box, no
 * angle, no extents. Anything else a lens wants is derived from these by [LensAnchor.faceFrame].
 *
 * "Left" and "right" are ML Kit's labels (the subject's own), and are used **only** to average into
 * midpoints, so a mirrored image cannot flip anything.
 */
data class FaceSnapshot(
    val leftEyeX: Float,
    val leftEyeY: Float,
    val rightEyeX: Float,
    val rightEyeY: Float,
    val mouthLeftX: Float,
    val mouthLeftY: Float,
    val mouthRightX: Float,
    val mouthRightY: Float,
    /**
     * Width / height of the frame these points were measured in. The renderer draws into a
     * different stream off the same sensor, often a different shape (measured on a Pixel 8:
     * analysis `1280x720`, lens output `1280x960`), so points must be re-framed first — see
     * [LensAnchor.reframe].
     */
    val sourceAspect: Float,
    /**
     * How far open the subject's mouth is, `0f` (shut) to `1f` (wide) — see
     * [LensAnchor.mouthOpenness].
     *
     * A **dimensionless scalar**, not a point, and that is deliberate. Lesson 032 warns that a
     * scalar normalized to the *frame* cannot survive a quarter turn; this one is a ratio of two
     * distances measured on the *face*, in the tracker's own pixels, so rotation, mirroring and
     * re-framing all leave it alone. That is why [uprightToBuffer] and [reframe] carry it through
     * untouched instead of transforming it.
     */
    val mouthOpenness: Float = 0f,
    /**
     * Which person this is — ML Kit's tracking id, stable for as long as the detector can follow
     * the face across frames. Every piece of per-face state downstream (the roster slot in
     * [FaceTracker], the wobble springs and eased mouth in [LensMotion]) hangs off this key, so a
     * lens follows *its* subject when two people share the frame instead of averaging them.
     *
     * An identity, not a coordinate: [LensAnchor.uprightToBuffer] and [LensAnchor.reframe] carry
     * it through untouched, exactly like [mouthOpenness]. Defaults to [NO_TRACKING_ID] for the
     * solo-face callers and tests that never needed one.
     */
    val trackingId: Int = NO_TRACKING_ID,
) {
    companion object {
        /** The id of a face nobody is tracking — ML Kit ids are non-negative. */
        const val NO_TRACKING_ID = -1
    }
}

/**
 * The face's own coordinate frame, in square space. [right] and [up] are unit vectors; [unit] is
 * the eye-to-mouth distance that scales everything.
 */
data class FaceFrame(
    val originX: Float,
    val originY: Float,
    val rightX: Float,
    val rightY: Float,
    val upX: Float,
    val upY: Float,
    val unit: Float,
)

/**
 * Which tracked point a [LensPlacement] measures its offset from.
 *
 * [FACE] is the eye midpoint — the frame's own origin, and what every lens used before anything
 * needed to sit on a specific feature. The others let one lens carry parts that track *different*
 * anatomy: an eyeball on each eye, a tongue on the mouth.
 *
 * [LEFT_EYE] / [RIGHT_EYE] are ML Kit's subject-relative labels, so which side of the *image* each
 * lands on flips under mirroring. That is harmless here and deliberately not corrected: a lens
 * anchoring to both eyes draws the same art at each, so it cannot tell them apart anyway. A lens
 * that ever needs a genuinely left-vs-right-different part must resolve sides the way
 * [LensAnchor.features] does, by comparing against the frame's `right` axis.
 */
enum class LensAnchorPoint { FACE, LEFT_EYE, RIGHT_EYE, MOUTH }

/**
 * Where a lens sits on the face and how big it is — expressed entirely in face units, which is why
 * one set of numbers works for every human face at every distance and angle.
 *
 * @param widthInUnits sticker width as a multiple of the eye-to-mouth distance.
 * @param artAspect the art's own height / width, so it never renders squashed.
 * @param upInUnits how far above [anchor] the sticker's center sits (negative = below).
 * @param rightInUnits how far along the face's `right` axis the center sits (negative = left).
 * @param anchor the point [upInUnits] / [rightInUnits] are measured from.
 * @param wobble non-null makes this layer swing about [anchor] when the head moves — see
 *   [LensPhysics]. Null is rigid, which is every lens that does not hang off the face.
 * @param mouthOpen non-null makes this layer grow and shrink as the subject opens their mouth.
 *   Null means a fixed size, which is every layer that is not coming *out* of a mouth.
 * @param spin non-null makes this layer flickable: a hand waved across it spins it about its own
 *   center, landing back on a whole revolution — see [LensPhysics.spinStep] and
 *   `docs/PRD-lens-hand-flick.md`. Null ignores hands. Which lenses carry one is the
 *   [Lens.interaction] decision, and the two must agree.
 */
data class LensPlacement(
    val widthInUnits: Float,
    val artAspect: Float,
    val upInUnits: Float = 0f,
    val rightInUnits: Float = 0f,
    val anchor: LensAnchorPoint = LensAnchorPoint.FACE,
    val wobble: WobbleSpec? = null,
    val mouthOpen: MouthOpenSpec? = null,
    val spin: SpinSpec? = null,
)

/**
 * How far a layer extends as the mouth opens.
 *
 * The layer is scaled **about its anchor** — size and offset together — so a part anchored at the
 * mouth grows *out of* the mouth rather than swelling in place.
 *
 * @param restFraction the layer's size with the mouth shut, as a fraction of full. `0f` hides it
 *   entirely until the mouth opens (the canonical dog drop-tongue); `1f` disables the response.
 */
data class MouthOpenSpec(val restFraction: Float)

/**
 * Where a character's eyes and mouth sit on its face, in face units.
 *
 * A lens that carries one of these becomes a **character**: the art is drawn opaque over the head,
 * and the subject's real eyes and mouth are cut out of the camera image and composited onto it.
 * The layout is fixed in the face frame, so the features stay put on the character's face while
 * the frame itself follows the head — the broccoli defines the silhouette, the human supplies only
 * the expression.
 *
 * @param eyeSpacingInUnits distance of each eye from the center line.
 * @param eyeUpInUnits height of the eye line on the character (0 = the subject's own eye line).
 * @param eyeWidthInUnits how wide each eye is drawn — larger than life reads as a cartoon.
 * @param mouthUpInUnits height of the mouth; negative is below the eye line.
 * @param mouthWidthInUnits how wide the mouth is drawn.
 */
data class FeatureLayout(
    val eyeSpacingInUnits: Float,
    val eyeUpInUnits: Float,
    val eyeWidthInUnits: Float,
    val mouthUpInUnits: Float,
    val mouthWidthInUnits: Float,
)

/**
 * One facial feature to lift from the camera image and paste onto a character.
 *
 * [sourceCenterX]/[sourceCenterY] is where to read from (the subject's real feature) and
 * [destCenterX]/[destCenterY] where to draw it (the character's face). Half-extents are in square
 * space; the renderer rotates both by the face frame, so a tilted head reads correctly at both ends.
 */
data class FeatureQuad(
    val sourceCenterX: Float,
    val sourceCenterY: Float,
    val sourceHalfWidth: Float,
    val sourceHalfHeight: Float,
    val destCenterX: Float,
    val destCenterY: Float,
    val destHalfWidth: Float,
    val destHalfHeight: Float,
    val rotationRadians: Float,
)

/** Where the renderer should draw a sticker, in normalized coordinates. */
data class StickerQuad(
    val centerX: Float,
    val centerY: Float,
    val halfWidth: Float,
    val halfHeight: Float,
    val rotationRadians: Float,
)

object LensAnchor {

    /**
     * y-normalized distance → square space (same pixels-per-unit as x).
     *
     * One y unit spans `height` pixels and one x unit spans `width`, so a y distance measured in
     * x units is `dy * height / width` — i.e. `dy / frameAspect`.
     */
    fun toSquareY(dy: Float, frameAspect: Float): Float = dy / frameAspect

    /** Square space → y-normalized distance. Inverse of [toSquareY]. */
    fun fromSquareY(sy: Float, frameAspect: Float): Float = sy * frameAspect

    /**
     * Builds the face's own frame (see the file header). This is the single place any lens learns
     * where the head is, which way is up, and how big it is.
     *
     * Returns `null` when the landmarks are degenerate (eyes and mouth coincident), so the renderer
     * draws nothing rather than dividing by zero.
     */
    fun faceFrame(face: FaceSnapshot, frameAspect: Float): FaceFrame? {
        val eyeX = (face.leftEyeX + face.rightEyeX) / 2f
        val eyeY = (face.leftEyeY + face.rightEyeY) / 2f
        val mouthX = (face.mouthLeftX + face.mouthRightX) / 2f
        val mouthY = (face.mouthLeftY + face.mouthRightY) / 2f

        // Work in square space so "perpendicular" is a right angle in pixels, not in normalized units.
        val upSquareX = eyeX - mouthX
        val upSquareY = toSquareY(eyeY - mouthY, frameAspect)
        val unit = hypot(upSquareX, upSquareY)
        if (unit <= 0f || !unit.isFinite()) return null

        val upX = upSquareX / unit
        val upY = upSquareY / unit
        // Rotate `up` a quarter turn. Derived from `up` alone — never from the detector's
        // left/right labels, which swap under mirroring and would flip the sticker.
        return FaceFrame(
            originX = eyeX,
            originY = eyeY,
            rightX = -upY,
            rightY = upX,
            upX = upX,
            upY = upY,
            unit = unit,
        )
    }

    /**
     * Places [placement]'s art on [frame]. Position, size and rotation all come from the frame, so
     * a tilted, distant, or mirrored face needs no special case.
     *
     * [wobbleRadians] turns the art **about its anchor** rather than about its own center: the
     * offset vector and the art rotate by the same angle, which is what makes a hanging part swing
     * from where it is attached instead of pivoting in place. Zero reproduces the rigid placement
     * exactly, so every non-wobbling lens is bit-identical to before this parameter existed.
     */
    /**
     * How far open a mouth is, `0f`..`1f`, from **two pixel distances in the tracker's own image**.
     *
     * `mouthToBottom` is the mouth-corner midpoint to ML Kit's `MOUTH_BOTTOM`; `eyeToMouth` is the
     * face unit in the same pixels. Their ratio grows as the jaw drops, and because it is a ratio
     * of two distances on the same face it is invariant to distance from the camera, to head
     * rotation, to mirroring, and to which stream measured it. That invariance is the whole reason
     * this is computed from raw pixels at the tracker rather than from normalized coordinates
     * downstream — see the note on [FaceSnapshot.mouthOpenness] and Lesson 032.
     *
     * **No `CONTOUR_MODE`.** `MOUTH_BOTTOM` is already in `LANDMARK_MODE_ALL`, which the tracker
     * already runs, so this costs nothing per frame. PRD §5.1 rejected contour mode on exactly
     * that cost, and this keeps the rejection intact rather than reopening it.
     */
    fun mouthOpenness(eyeToMouth: Float, mouthToBottom: Float): Float {
        if (eyeToMouth <= 0f || !eyeToMouth.isFinite() || !mouthToBottom.isFinite()) return 0f
        val ratio = mouthToBottom / eyeToMouth
        // Measured against a relaxed and a wide-open jaw: the lower lip sits ~0.20 units below the
        // corner line at rest and passes ~0.62 wide open. Below CLOSED it is shut, above OPEN it is
        // as open as it gets, and between them it is linear.
        return ((ratio - MOUTH_CLOSED_RATIO) / (MOUTH_OPEN_RATIO - MOUTH_CLOSED_RATIO))
            .coerceIn(0f, 1f)
    }

    /** Lower lip below the mouth-corner line, in face units, with the jaw relaxed. */
    private const val MOUTH_CLOSED_RATIO = 0.20f

    /** The same distance with the jaw dropped as far as a face comfortably goes. */
    private const val MOUTH_OPEN_RATIO = 0.62f

    /**
     * Scale a [MouthOpenSpec] layer takes at a given [openFraction] (already eased).
     *
     * Linear between [MouthOpenSpec.restFraction] and 1. Kept separate from [sticker] so the curve
     * is assertable on its own.
     */
    fun mouthOpenScale(spec: MouthOpenSpec?, openFraction: Float): Float {
        if (spec == null) return 1f
        val open = openFraction.coerceIn(0f, 1f)
        return spec.restFraction + (1f - spec.restFraction) * open
    }

    fun sticker(
        face: FaceSnapshot,
        frame: FaceFrame,
        placement: LensPlacement,
        frameAspect: Float,
        wobbleRadians: Float = 0f,
        openFraction: Float = 1f,
        spinRadians: Float = 0f,
    ): StickerQuad {
        // Screen y is down, so this is the clockwise-positive rotation used everywhere else —
        // same convention as LensSurfaceProcessor.writeStickerCorners.
        val cosW = cos(wobbleRadians)
        val sinW = sin(wobbleRadians)
        val upX = frame.upX * cosW - frame.upY * sinW
        val upY = frame.upX * sinW + frame.upY * cosW
        val rightX = frame.rightX * cosW - frame.rightY * sinW
        val rightY = frame.rightX * sinW + frame.rightY * cosW

        // Scaling the OFFSET by the same factor as the size is what makes a mouth-driven layer
        // grow out of its anchor instead of swelling around its own center.
        val openScale = mouthOpenScale(placement.mouthOpen, openFraction)
        val alongUp = placement.upInUnits * frame.unit * openScale
        val alongRight = placement.rightInUnits * frame.unit * openScale

        val centerSquareX = anchorX(face, frame, placement.anchor) +
            rightX * alongRight + upX * alongUp
        val centerSquareY = anchorSquareY(face, frame, placement.anchor, frameAspect) +
            rightY * alongRight + upY * alongUp

        val halfWidth = placement.widthInUnits * frame.unit * openScale / 2f

        return StickerQuad(
            centerX = centerSquareX,
            centerY = fromSquareY(centerSquareY, frameAspect),
            halfWidth = halfWidth,
            halfHeight = fromSquareY(halfWidth * placement.artAspect, frameAspect),
            // The sticker's own "right" is the face's "right", turned by the wobble. The spin
            // adds on top of the final rotation ONLY — unlike the wobble it does not turn the
            // offset vector, so the art twirls about its own center rather than orbiting its
            // anchor (a flicked ball spins in place; a swung tongue hangs from its root).
            rotationRadians = atan2(rightY, rightX) + spinRadians,
        )
    }

    /** Normalized x of the point [anchor] names. */
    private fun anchorX(face: FaceSnapshot, frame: FaceFrame, anchor: LensAnchorPoint): Float =
        when (anchor) {
            LensAnchorPoint.FACE -> frame.originX
            LensAnchorPoint.LEFT_EYE -> face.leftEyeX
            LensAnchorPoint.RIGHT_EYE -> face.rightEyeX
            LensAnchorPoint.MOUTH -> (face.mouthLeftX + face.mouthRightX) / 2f
        }

    /** Square-space y of the point [anchor] names — square, because the offset maths is. */
    private fun anchorSquareY(
        face: FaceSnapshot,
        frame: FaceFrame,
        anchor: LensAnchorPoint,
        frameAspect: Float,
    ): Float = toSquareY(
        when (anchor) {
            LensAnchorPoint.FACE -> frame.originY
            LensAnchorPoint.LEFT_EYE -> face.leftEyeY
            LensAnchorPoint.RIGHT_EYE -> face.rightEyeY
            LensAnchorPoint.MOUTH -> (face.mouthLeftY + face.mouthRightY) / 2f
        },
        frameAspect,
    )

    /**
     * How far the head slid **sideways** between two snapshots, in face units — the drive signal a
     * hanging lens part swings from ([LensPhysics]).
     *
     * Measured along the current frame's `right` axis and divided by the face unit, so it is
     * dimensionless: the same head movement produces the same number whether the subject is close
     * or far, and whichever output stream is being drawn. That is what lets the physics be stepped
     * once per frame in one canonical space and consumed by every output.
     *
     * Deliberately translation only. Head *roll* also swings a real pendulum, but that needs the
     * part to hang in world-down rather than face-down, which is a different and much larger model.
     * ponytail: translation-only drive; add a roll term if the swing reads dead on a head tilt.
     */
    fun lateralShiftInUnits(
        previous: FaceSnapshot,
        current: FaceSnapshot,
        frame: FaceFrame,
        frameAspect: Float,
    ): Float {
        if (frame.unit <= 0f) return 0f
        val previousX = (previous.leftEyeX + previous.rightEyeX) / 2f
        val previousY = (previous.leftEyeY + previous.rightEyeY) / 2f
        val currentX = (current.leftEyeX + current.rightEyeX) / 2f
        val currentY = (current.leftEyeY + current.rightEyeY) / 2f
        val dx = currentX - previousX
        val dy = toSquareY(currentY - previousY, frameAspect)
        return (dx * frame.rightX + dy * frame.rightY) / frame.unit
    }

    /**
     * Resolves the three features to lift onto a character. Expression from the human (real
     * landmarks), geometry from the character (fixed points in the face frame).
     */
    fun features(
        face: FaceSnapshot,
        frame: FaceFrame,
        layout: FeatureLayout,
        frameAspect: Float,
    ): List<FeatureQuad> {
        val mouthSpanX = face.mouthRightX - face.mouthLeftX
        val mouthSpanY = toSquareY(face.mouthRightY - face.mouthLeftY, frameAspect)
        val mouthWidth = hypot(mouthSpanX, mouthSpanY)

        fun quad(
            sourceX: Float,
            sourceY: Float,
            sourceHalfWidth: Float,
            sourceHalfHeight: Float,
            destRightUnits: Float,
            destUpUnits: Float,
            destWidthUnits: Float,
            destHeightUnits: Float,
        ): FeatureQuad {
            val alongRight = destRightUnits * frame.unit
            val alongUp = destUpUnits * frame.unit
            val destSquareX = frame.originX + frame.rightX * alongRight + frame.upX * alongUp
            val destSquareY = toSquareY(frame.originY, frameAspect) +
                frame.rightY * alongRight + frame.upY * alongUp
            return FeatureQuad(
                sourceCenterX = sourceX,
                sourceCenterY = sourceY,
                sourceHalfWidth = sourceHalfWidth,
                sourceHalfHeight = sourceHalfHeight,
                destCenterX = destSquareX,
                destCenterY = fromSquareY(destSquareY, frameAspect),
                destHalfWidth = destWidthUnits * frame.unit / 2f,
                destHalfHeight = destHeightUnits * frame.unit / 2f,
                rotationRadians = atan2(frame.rightY, frame.rightX),
            )
        }

        val eyeSourceHalfWidth = EYE_SOURCE_WIDTH_UNITS * frame.unit / 2f
        val eyeSourceHalfHeight = eyeSourceHalfWidth * FEATURE_ASPECT
        val eyeDestHeight = layout.eyeWidthInUnits * FEATURE_ASPECT
        val mouthSourceHalfWidth = mouthWidth * MOUTH_SOURCE_SPAN_MULTIPLE / 2f
        val mouthSourceHalfHeight = mouthSourceHalfWidth * FEATURE_ASPECT

        // ML Kit's LEFT_EYE is the subject's own left, which appears on the image's right for an
        // un-mirrored view — and on the other side when mirrored. Deciding which destination each
        // belongs to by comparing against the frame's `right` axis keeps them from crossing over.
        val leftEyeSide = sideOf(face.leftEyeX, face.leftEyeY, frame, frameAspect)
        val rightEyeSide = sideOf(face.rightEyeX, face.rightEyeY, frame, frameAspect)

        return listOf(
            quad(
                sourceX = face.leftEyeX,
                sourceY = face.leftEyeY,
                sourceHalfWidth = eyeSourceHalfWidth,
                sourceHalfHeight = eyeSourceHalfHeight,
                destRightUnits = layout.eyeSpacingInUnits * leftEyeSide,
                destUpUnits = layout.eyeUpInUnits,
                destWidthUnits = layout.eyeWidthInUnits,
                destHeightUnits = eyeDestHeight,
            ),
            quad(
                sourceX = face.rightEyeX,
                sourceY = face.rightEyeY,
                sourceHalfWidth = eyeSourceHalfWidth,
                sourceHalfHeight = eyeSourceHalfHeight,
                destRightUnits = layout.eyeSpacingInUnits * rightEyeSide,
                destUpUnits = layout.eyeUpInUnits,
                destWidthUnits = layout.eyeWidthInUnits,
                destHeightUnits = eyeDestHeight,
            ),
            quad(
                sourceX = (face.mouthLeftX + face.mouthRightX) / 2f,
                sourceY = (face.mouthLeftY + face.mouthRightY) / 2f,
                sourceHalfWidth = mouthSourceHalfWidth,
                sourceHalfHeight = mouthSourceHalfHeight,
                destRightUnits = 0f,
                destUpUnits = layout.mouthUpInUnits,
                destWidthUnits = layout.mouthWidthInUnits,
                destHeightUnits = layout.mouthWidthInUnits * FEATURE_ASPECT,
            ),
        )
    }

    /** `+1` if a point lies on the frame's `right` side of the center line, `-1` otherwise. */
    private fun sideOf(x: Float, y: Float, frame: FaceFrame, frameAspect: Float): Float {
        val dx = x - frame.originX
        val dy = toSquareY(y - frame.originY, frameAspect)
        return if (dx * frame.rightX + dy * frame.rightY >= 0f) 1f else -1f
    }

    /**
     * How much of the face an eye cut-out takes in, in face units. Tight enough to be an eye rather
     * than a patch of cheek — surrounding skin is what makes a character read as a person in a
     * costume.
     */
    private const val EYE_SOURCE_WIDTH_UNITS = 0.50f

    /** Mouth cut-outs sample this multiple of the detected mouth span, to include the lips. */
    private const val MOUTH_SOURCE_SPAN_MULTIPLE = 1.9f

    /** Height / width for every feature cut-out — eyes and mouths are both wider than they are tall. */
    private const val FEATURE_ASPECT = 0.62f

    /**
     * Rewrites a face measured in the **upright** image into **camera-buffer** coordinates.
     *
     * ML Kit is given the frame's `rotationDegrees` so it sees an upright face — detection quality
     * depends on it — and therefore answers in upright coordinates. The renderer draws in the
     * effect output's space, which is the camera buffer's own orientation. On a portrait phone
     * those differ by a quarter turn (measured: analysis upright `720x1280`, lens output
     * `1280x960`), and drawing upright coordinates there puts every lens 90° out.
     *
     * Only points move; the face frame is rebuilt from them afterward, so orientation and scale
     * correct themselves.
     */
    fun uprightToBuffer(face: FaceSnapshot, rotationDegrees: Int): FaceSnapshot =
        when (((rotationDegrees % 360) + 360) % 360) {
            90 -> face.mapPoints({ _, y -> y }, { x, _ -> 1f - x }, quarterTurn = true)
            180 -> face.mapPoints({ x, _ -> 1f - x }, { _, y -> 1f - y }, quarterTurn = false)
            270 -> face.mapPoints({ _, y -> 1f - y }, { x, _ -> x }, quarterTurn = true)
            else -> face
        }

    private inline fun FaceSnapshot.mapPoints(
        x: (Float, Float) -> Float,
        y: (Float, Float) -> Float,
        quarterTurn: Boolean,
    ) = FaceSnapshot(
        leftEyeX = x(leftEyeX, leftEyeY),
        leftEyeY = y(leftEyeX, leftEyeY),
        rightEyeX = x(rightEyeX, rightEyeY),
        rightEyeY = y(rightEyeX, rightEyeY),
        mouthLeftX = x(mouthLeftX, mouthLeftY),
        mouthLeftY = y(mouthLeftX, mouthLeftY),
        mouthRightX = x(mouthRightX, mouthRightY),
        mouthRightY = y(mouthRightX, mouthRightY),
        sourceAspect = if (quarterTurn) 1f / sourceAspect else sourceAspect,
        // A ratio of two distances on the face — a quarter turn does not change it.
        mouthOpenness = mouthOpenness,
        // Who this is does not change either.
        trackingId = trackingId,
    )

    /**
     * Re-frames a face from the stream it was measured in onto a stream of a different shape.
     *
     * The tracker and the renderer read two different streams off one sensor, and a 16:9 stream and
     * a 4:3 stream do not cover the same rectangle of it. Ignoring that shifts a lens by up to an
     * eighth of the frame.
     *
     * Model: streams cut from a common sensor **share the full width and differ only in how much
     * they keep vertically** — a 16:9 stream is a 4:3 stream with the top and bottom trimmed. So x
     * passes through and y is rescaled about the center by `targetAspect / sourceAspect`. One axis,
     * one factor, and it round-trips exactly.
     */
    fun reframe(face: FaceSnapshot, targetAspect: Float): FaceSnapshot {
        val sourceAspect = face.sourceAspect
        if (sourceAspect <= 0f || targetAspect <= 0f || targetAspect == sourceAspect) return face

        return face.copy(
            leftEyeY = reframeY(face.leftEyeY, sourceAspect, targetAspect),
            rightEyeY = reframeY(face.rightEyeY, sourceAspect, targetAspect),
            mouthLeftY = reframeY(face.mouthLeftY, sourceAspect, targetAspect),
            mouthRightY = reframeY(face.mouthRightY, sourceAspect, targetAspect),
            sourceAspect = targetAspect,
        )
    }

    /**
     * The per-point form of [reframe]: one normalized y measured on a [sourceAspect] stream,
     * re-framed onto a [targetAspect] stream. x passes through unchanged (see [reframe]'s model).
     */
    fun reframeY(y: Float, sourceAspect: Float, targetAspect: Float): Float {
        if (sourceAspect <= 0f || targetAspect <= 0f || targetAspect == sourceAspect) return y
        return 0.5f + (y - 0.5f) * (targetAspect / sourceAspect)
    }
}
