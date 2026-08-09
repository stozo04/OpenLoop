package io.github.stozo04.openloop.camera.lens

import kotlin.math.atan2
import kotlin.math.hypot

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
 * numbers, and it did not generalise.
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
)

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
 * Where a lens sits on the face and how big it is — expressed entirely in face units, which is why
 * one set of numbers works for every human face at every distance and angle.
 *
 * @param widthInUnits sticker width as a multiple of the eye-to-mouth distance.
 * @param artAspect the art's own height / width, so it never renders squashed.
 * @param upInUnits how far above the eye line the sticker's centre sits (negative = below).
 * @param rightInUnits sideways shift; 0 for anything centred on the face.
 */
data class LensPlacement(
    val widthInUnits: Float,
    val artAspect: Float,
    val upInUnits: Float = 0f,
    val rightInUnits: Float = 0f,
)

/**
 * Where a character's eyes and mouth sit on its face, in face units.
 *
 * A lens that carries one of these becomes a **character**: the art is drawn opaque over the head,
 * and the subject's real eyes and mouth are cut out of the camera image and composited onto it.
 * The layout is fixed in the face frame, so the features stay put on the character's face while
 * the frame itself follows the head — the broccoli defines the silhouette, the human supplies only
 * the expression.
 *
 * @param eyeSpacingInUnits distance of each eye from the centre line.
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

/**
 * How a warp lens deforms the frame, in face units. [radiusInUnits] scales with the face;
 * [strength] is the peak magnification at the centre (0f = off).
 */
data class WarpSpec(
    val radiusInUnits: Float,
    val strength: Float,
)

/**
 * A resolved warp for the shader: a circle whose centre is normalized and whose [radius] is in
 * square space, so the shader can treat it as a true circle.
 */
data class WarpCircle(
    val centerX: Float,
    val centerY: Float,
    val radius: Float,
    val strength: Float,
) {
    companion object {
        /** The no-op warp the renderer binds when the active lens does not deform pixels. */
        val NONE = WarpCircle(centerX = 0.5f, centerY = 0.5f, radius = 0f, strength = 0f)
    }
}

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
     */
    fun sticker(
        frame: FaceFrame,
        placement: LensPlacement,
        frameAspect: Float,
    ): StickerQuad {
        val alongRight = placement.rightInUnits * frame.unit
        val alongUp = placement.upInUnits * frame.unit

        val centerSquareX = frame.originX + frame.rightX * alongRight + frame.upX * alongUp
        val centerSquareY = frame.originY.toSquare(frameAspect) +
            frame.rightY * alongRight + frame.upY * alongUp

        val halfWidth = placement.widthInUnits * frame.unit / 2f

        return StickerQuad(
            centerX = centerSquareX,
            centerY = fromSquareY(centerSquareY, frameAspect),
            halfWidth = halfWidth,
            halfHeight = fromSquareY(halfWidth * placement.artAspect, frameAspect),
            // The sticker's own "right" is the face's "right"; y is down, so this is the
            // clockwise-positive screen angle with no sign conventions to remember.
            rotationRadians = atan2(frame.rightY, frame.rightX),
        )
    }

    /**
     * Resolves the three features to lift onto a character: left eye, right eye, mouth.
     *
     * Sources follow the subject's real landmarks (so a blink, a smile and a head turn all carry
     * through); destinations are fixed points in the face frame (so the character's face does not
     * inherit the subject's proportions, camera perspective, or a lens-distorted nose). That split
     * is the whole trick — expression from the human, geometry from the character.
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

    /** `+1` if a point lies on the frame's `right` side of the centre line, `-1` otherwise. */
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

    /** Resolves [spec] onto the mouth, scaled by the same face unit every lens uses. */
    fun warp(
        face: FaceSnapshot,
        frame: FaceFrame,
        spec: WarpSpec,
        frameAspect: Float,
    ): WarpCircle = WarpCircle(
        centerX = (face.mouthLeftX + face.mouthRightX) / 2f,
        centerY = (face.mouthLeftY + face.mouthRightY) / 2f,
        radius = spec.radiusInUnits * frame.unit,
        strength = spec.strength,
    )

    /**
     * Rewrites a face measured in the **upright** image into **camera-buffer** coordinates.
     *
     * ML Kit is given the frame's `rotationDegrees` so it sees an upright face — detection quality
     * depends on it — and therefore answers in upright coordinates. The renderer draws in the
     * effect output's space, which is the camera buffer's own orientation. On a portrait phone
     * those differ by a quarter turn (measured: analysis upright `720x1280`, lens output
     * `1280x960`), and drawing upright coordinates there puts every lens 90° out.
     *
     * Only points move; the face frame is rebuilt from them afterwards, so orientation and scale
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
     * passes through and y is rescaled about the centre by `targetAspect / sourceAspect`. One axis,
     * one factor, and it round-trips exactly.
     */
    fun reframe(face: FaceSnapshot, targetAspect: Float): FaceSnapshot {
        val sourceAspect = face.sourceAspect
        if (sourceAspect <= 0f || targetAspect <= 0f || targetAspect == sourceAspect) return face
        val ratio = targetAspect / sourceAspect
        fun rescale(y: Float) = 0.5f + (y - 0.5f) * ratio

        return face.copy(
            leftEyeY = rescale(face.leftEyeY),
            rightEyeY = rescale(face.rightEyeY),
            mouthLeftY = rescale(face.mouthLeftY),
            mouthRightY = rescale(face.mouthRightY),
            sourceAspect = targetAspect,
        )
    }

    private fun Float.toSquare(frameAspect: Float): Float = toSquareY(this, frameAspect)
}
