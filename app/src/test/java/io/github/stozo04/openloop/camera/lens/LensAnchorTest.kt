package io.github.stozo04.openloop.camera.lens

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guard for [LensAnchor]'s face frame.
 *
 * The whole point of the face frame is that position, rotation and scale come from the subject's
 * anatomy rather than from tuned constants, so these tests assert *invariances*: the same lens on a
 * tilted head, a distant head, a mirrored image, or a quarter-turned frame must land in the same
 * place on the face. Those are the properties that make one set of numbers work everywhere, and
 * they are exactly what a device cannot check for us.
 */
class LensAnchorTest {

    private companion object {
        /**
         * The anatomy the catalogue is tuned against, from `Lens.kt`'s header table. Measured off a
         * real tracked face, not assumed — a lens sized against `face()` below would be sized
         * against a synthetic head whose eye span was picked to make the unit a round number.
         */
        const val HEAD_HALF_WIDTH_UNITS = 0.775f

        /** How far an eye sits off the center line: interpupillary is ~0.8 of eye-to-mouth. */
        const val EYE_OFFSET_UNITS = 0.40f

        /** Top of the head, above the eye line. */
        const val CROWN_UNITS = 1.25f

        /**
         * Top of the brow. Added 2026-09-03 off an owner hardware capture — Cowboy's brim was
         * tuned against an assumed 0.35 and the real one measured 0.30, which left a visible band
         * of forehead. See `Lens.kt`'s header table.
         */
        const val BROW_UNITS = 0.30f

        /**
         * Bottom of the chin, below the eye line. The mouth is at 1.00 *by definition*, and the jaw
         * runs ~0.75 units further. This table said 1.00 until 2026-08-16 — the mouth's number
         * wearing the chin's label — which is what let Pizza Face and Football ship covering only
         * to the mouth. See `Lens.kt`'s header.
         */
        const val CHIN_UNITS = -1.75f
    }

    private val tolerance = 1e-3f

    /** 4:3, the shape CameraX gives the lens effect on real hardware. */
    private val frameAspect = 1280f / 960f

    /**
     * An upright face: eyes level, mouth centered below. Distances are chosen so the eye-to-mouth
     * distance (the face unit) is a round number in square space.
     */
    private fun face(
        centerX: Float = 0.5f,
        eyeY: Float = 0.40f,
        mouthY: Float = 0.60f,
        eyeHalfSpan: Float = 0.08f,
        mouthHalfSpan: Float = 0.05f,
        sourceAspect: Float = frameAspect,
    ) = FaceSnapshot(
        leftEyeX = centerX - eyeHalfSpan,
        leftEyeY = eyeY,
        rightEyeX = centerX + eyeHalfSpan,
        rightEyeY = eyeY,
        mouthLeftX = centerX - mouthHalfSpan,
        mouthLeftY = mouthY,
        mouthRightX = centerX + mouthHalfSpan,
        mouthRightY = mouthY,
        sourceAspect = sourceAspect,
    )

    /** Rotates a face about the frame center, in square space, as a real head tilt would. */
    @Suppress("SameParameterValue") // the aspect is spelled out at each call site on purpose
    private fun FaceSnapshot.tiltedBy(degrees: Float, aspect: Float): FaceSnapshot {
        val radians = degrees * PI.toFloat() / 180f
        val cos = kotlin.math.cos(radians)
        val sin = kotlin.math.sin(radians)
        fun rx(x: Float, y: Float): Float {
            val sx = x - 0.5f
            val sy = LensAnchor.toSquareY(y - 0.5f, aspect)
            return 0.5f + (sx * cos - sy * sin)
        }
        fun ry(x: Float, y: Float): Float {
            val sx = x - 0.5f
            val sy = LensAnchor.toSquareY(y - 0.5f, aspect)
            return 0.5f + LensAnchor.fromSquareY(sx * sin + sy * cos, aspect)
        }
        return FaceSnapshot(
            leftEyeX = rx(leftEyeX, leftEyeY), leftEyeY = ry(leftEyeX, leftEyeY),
            rightEyeX = rx(rightEyeX, rightEyeY), rightEyeY = ry(rightEyeX, rightEyeY),
            mouthLeftX = rx(mouthLeftX, mouthLeftY), mouthLeftY = ry(mouthLeftX, mouthLeftY),
            mouthRightX = rx(mouthRightX, mouthRightY), mouthRightY = ry(mouthRightX, mouthRightY),
            sourceAspect = sourceAspect,
        )
    }

    /** Mirrors a face about the vertical axis, as a front-camera preview would. */
    private fun FaceSnapshot.mirrored() = copy(
        // The subject's own left eye is now on the other side of the image, so the labels swap too.
        leftEyeX = 1f - rightEyeX, leftEyeY = rightEyeY,
        rightEyeX = 1f - leftEyeX, rightEyeY = leftEyeY,
        mouthLeftX = 1f - mouthRightX, mouthLeftY = mouthRightY,
        mouthRightX = 1f - mouthLeftX, mouthRightY = mouthLeftY,
    )

    private val hat = LensPlacement(widthInUnits = 2f, artAspect = 1f, upInUnits = 1.5f)
    private val onEyes = LensPlacement(widthInUnits = 2f, artAspect = 1f)

    private fun frameOf(face: FaceSnapshot, aspect: Float = frameAspect) =
        requireNotNull(LensAnchor.faceFrame(face, aspect)) { "expected a usable face frame" }

    // ---------------------------------------------------------------- the frame itself

    @Test
    fun faceFrame_originIsTheEyeMidpoint() {
        val frame = frameOf(face(centerX = 0.42f, eyeY = 0.3f))

        assertEquals(0.42f, frame.originX, tolerance)
        assertEquals(0.3f, frame.originY, tolerance)
    }

    @Test
    fun faceFrame_upPointsFromTheMouthTowardTheEyes() {
        // y is down, so "up the face" is negative y for an upright head.
        val frame = frameOf(face())

        assertEquals(0f, frame.upX, tolerance)
        assertEquals(-1f, frame.upY, tolerance)
        assertEquals("up must be a unit vector", 1f, hypot(frame.upX, frame.upY), tolerance)
    }

    @Test
    fun faceFrame_rightIsPerpendicularToUp_andPointsAcrossTheFace() {
        val frame = frameOf(face())

        assertEquals("perpendicular means zero dot product", 0f, frame.rightX * frame.upX + frame.rightY * frame.upY, tolerance)
        assertEquals(1f, hypot(frame.rightX, frame.rightY), tolerance)
        assertTrue("right should point toward +x on an upright face", frame.rightX > 0f)
    }

    @Test
    fun faceFrame_unitIsTheEyeToMouthDistanceInSquareSpace() {
        val frame = frameOf(face(eyeY = 0.40f, mouthY = 0.60f))

        assertEquals(LensAnchor.toSquareY(0.20f, frameAspect), frame.unit, tolerance)
    }

    @Test
    fun faceFrame_isNullWhenLandmarksAreDegenerate() {
        // Eyes and mouth on the same point: no scale, no direction, nothing to anchor to.
        val collapsed = face(eyeY = 0.5f, mouthY = 0.5f)

        assertNull(LensAnchor.faceFrame(collapsed, frameAspect))
    }

    // ---------------------------------------------------------------- placement

    @Test
    fun sticker_withNoOffset_sitsOnTheEyeLine() {
        val quad = LensAnchor.sticker(face(), frameOf(face()), onEyes, frameAspect)

        assertEquals(0.5f, quad.centerX, tolerance)
        assertEquals(0.40f, quad.centerY, tolerance)
        assertEquals(0f, quad.rotationRadians, tolerance)
    }

    @Test
    fun sticker_positiveUp_movesTowardTheCrown() {
        val quad = LensAnchor.sticker(face(), frameOf(face()), hat, frameAspect)

        assertTrue("a hat belongs above the eyes, got ${quad.centerY}", quad.centerY < 0.40f)
        assertEquals(0.5f, quad.centerX, tolerance)
    }

    @Test
    fun sticker_sizeScalesWithTheFace_notTheFrame() {
        // The same lens on a face twice as far away must be half the size.
        val nearFace = face(eyeY = 0.30f, mouthY = 0.70f)
        val farFace = face(eyeY = 0.45f, mouthY = 0.55f)
        val near = frameOf(nearFace)
        val far = frameOf(farFace)

        val nearQuad = LensAnchor.sticker(nearFace, near, onEyes, frameAspect)
        val farQuad = LensAnchor.sticker(farFace, far, onEyes, frameAspect)

        assertEquals(4f, nearQuad.halfWidth / farQuad.halfWidth, tolerance)
        assertEquals(onEyes.widthInUnits * near.unit / 2f, nearQuad.halfWidth, tolerance)
    }

    @Test
    fun sticker_keepsArtProportions_onANonSquareFrame() {
        val square = LensAnchor.sticker(face(), frameOf(face()), onEyes, frameAspect)
        val tall = LensAnchor.sticker(face(), frameOf(face()), onEyes.copy(artAspect = 2f), frameAspect)

        // Square art must cover equal PIXELS on both axes, which on a 4:3 frame means a larger
        // normalized y extent by exactly the aspect.
        assertEquals(frameAspect, square.halfHeight / square.halfWidth, tolerance)
        assertEquals(2f * square.halfHeight, tall.halfHeight, tolerance)
        assertEquals(square.halfWidth, tall.halfWidth, tolerance)
    }

    // ---------------------------------------------------------------- invariance under tilt

    @Test
    fun sticker_rotatesWithTheHead() {
        val tilted = face().tiltedBy(30f, frameAspect)

        val quad = LensAnchor.sticker(tilted, frameOf(tilted), onEyes, frameAspect)

        assertEquals(30f * PI.toFloat() / 180f, quad.rotationRadians, 1e-2f)
    }

    @Test
    fun sticker_offsetOrbitsWithTheHead_keepingItsDistance() {
        // A hat stays on the crown when the head tilts: same distance from the eyes, new direction.
        val tiltedFace = face().tiltedBy(90f, frameAspect)
        val upright = frameOf(face())
        val tilted = frameOf(tiltedFace)

        val uprightQuad = LensAnchor.sticker(face(), upright, hat, frameAspect)
        val tiltedQuad = LensAnchor.sticker(tiltedFace, tilted, hat, frameAspect)

        fun squareDistanceFromEyes(quad: StickerQuad, originX: Float, originY: Float) = hypot(
            quad.centerX - originX,
            LensAnchor.toSquareY(quad.centerY - originY, frameAspect),
        )

        assertEquals(
            squareDistanceFromEyes(uprightQuad, upright.originX, upright.originY),
            squareDistanceFromEyes(tiltedQuad, tilted.originX, tilted.originY),
            tolerance,
        )
        // A quarter turn moves the hat off the vertical and onto the horizontal.
        assertEquals(tilted.originY, tiltedQuad.centerY, tolerance)
    }

    @Test
    fun sticker_sizeIsUnchangedByTilt() {
        val upright = LensAnchor.sticker(face(), frameOf(face()), onEyes, frameAspect)
        val tilted = LensAnchor.sticker(face().tiltedBy(37f, frameAspect), frameOf(face().tiltedBy(37f, frameAspect)), onEyes, frameAspect)

        assertEquals(upright.halfWidth, tilted.halfWidth, tolerance)
    }

    // ---------------------------------------------------------------- invariance under mirroring

    @Test
    fun faceFrame_upSurvivesMirroring() {
        // Mirroring swaps which eye is which; deriving `up` from the mouth means it does not care.
        val frame = frameOf(face().mirrored())

        assertEquals(0f, frame.upX, tolerance)
        assertEquals(-1f, frame.upY, tolerance)
    }

    @Test
    fun sticker_onAMirroredFace_isTheMirroredPlacement() {
        val normal = LensAnchor.sticker(face(centerX = 0.35f), frameOf(face(centerX = 0.35f)), hat, frameAspect)
        val mirrored = LensAnchor.sticker(face(centerX = 0.35f).mirrored(), frameOf(face(centerX = 0.35f).mirrored()), hat, frameAspect)

        assertEquals(1f - normal.centerX, mirrored.centerX, tolerance)
        assertEquals(normal.centerY, mirrored.centerY, tolerance)
        assertEquals("a symmetric prop must not flip its size", normal.halfWidth, mirrored.halfWidth, tolerance)
    }

    // ---------------------------------------------------------------- rotation between streams

    @Test
    fun uprightToBuffer_withNoRotation_isTheIdentity() {
        val original = face()

        assertEquals(original, LensAnchor.uprightToBuffer(original, 0))
    }

    @Test
    fun uprightToBuffer_quarterTurn_movesTheFaceAndFlipsTheAspect() {
        // Portrait analysis (720x1280) -> landscape buffer. A face near the TOP of the upright
        // image sits near one SIDE of the buffer.
        val upright = face(eyeY = 0.1f, mouthY = 0.2f, sourceAspect = 720f / 1280f)

        val buffer = LensAnchor.uprightToBuffer(upright, 90)

        assertEquals(1280f / 720f, buffer.sourceAspect, tolerance)
        assertEquals(upright.leftEyeY, buffer.leftEyeX, tolerance)
        assertEquals(1f - upright.leftEyeX, buffer.leftEyeY, tolerance)
    }

    @Test
    fun uprightToBuffer_quarterTurn_leavesTheLENSUnchangedOnTheFace() {
        // THE invariant that matters: a quarter turn changes the numbers but must not move the
        // sticker relative to the face. Compare the offset from the eyes, in face units.
        val uprightAspect = 720f / 1280f
        val upright = face(sourceAspect = uprightAspect)
        val buffer = LensAnchor.uprightToBuffer(upright, 90)

        val uprightFrame = frameOf(upright, uprightAspect)
        val bufferFrame = frameOf(buffer, 1f / uprightAspect)
        val uprightQuad = LensAnchor.sticker(upright, uprightFrame, hat, uprightAspect)
        val bufferQuad = LensAnchor.sticker(buffer, bufferFrame, hat, 1f / uprightAspect)

        fun offsetInUnits(quad: StickerQuad, frame: FaceFrame, aspect: Float) = hypot(
            quad.centerX - frame.originX,
            LensAnchor.toSquareY(quad.centerY - frame.originY, aspect),
        ) / frame.unit

        assertEquals(
            offsetInUnits(uprightQuad, uprightFrame, uprightAspect),
            offsetInUnits(bufferQuad, bufferFrame, 1f / uprightAspect),
            tolerance,
        )
        assertEquals(
            "the sticker must stay the same size relative to the face",
            uprightQuad.halfWidth / uprightFrame.unit,
            bufferQuad.halfWidth / bufferFrame.unit,
            tolerance,
        )
    }

    @Test
    fun uprightToBuffer_fourQuarterTurns_returnToTheStart() {
        val original = face(centerX = 0.3f, eyeY = 0.35f)

        var turned = original
        repeat(4) { turned = LensAnchor.uprightToBuffer(turned, 90) }

        assertEquals(original.leftEyeX, turned.leftEyeX, tolerance)
        assertEquals(original.leftEyeY, turned.leftEyeY, tolerance)
        assertEquals(original.sourceAspect, turned.sourceAspect, tolerance)
    }

    @Test
    fun uprightToBuffer_180_isTwoQuarterTurns() {
        val original = face(centerX = 0.28f)

        val direct = LensAnchor.uprightToBuffer(original, 180)
        val twice = LensAnchor.uprightToBuffer(LensAnchor.uprightToBuffer(original, 90), 90)

        assertEquals(direct.leftEyeX, twice.leftEyeX, tolerance)
        assertEquals(direct.leftEyeY, twice.leftEyeY, tolerance)
    }

    // ---------------------------------------------------------------- re-framing between streams

    @Test
    fun reframeY_isThePerPointFormOfReframe() {
        val analysis = face(eyeY = 0.25f, sourceAspect = 16f / 9f)
        val reframed = LensAnchor.reframe(analysis, targetAspect = 4f / 3f)

        assertEquals(reframed.leftEyeY, LensAnchor.reframeY(analysis.leftEyeY, 16f / 9f, 4f / 3f), tolerance)
        assertEquals(reframed.mouthLeftY, LensAnchor.reframeY(analysis.mouthLeftY, 16f / 9f, 4f / 3f), tolerance)
        // Same shape, or a degenerate one: the point passes through.
        assertEquals(0.25f, LensAnchor.reframeY(0.25f, frameAspect, frameAspect), tolerance)
        assertEquals(0.25f, LensAnchor.reframeY(0.25f, 0f, frameAspect), tolerance)
    }

    @Test
    fun reframe_ontoTheSameShape_changesNothing() {
        val original = face(sourceAspect = frameAspect)

        assertEquals(original, LensAnchor.reframe(original, frameAspect))
    }

    @Test
    fun reframe_rescalesYAboutTheCentre_andLeavesXAlone() {
        val analysis = face(centerX = 0.3f, eyeY = 1f, sourceAspect = 16f / 9f)

        val reframed = LensAnchor.reframe(analysis, targetAspect = 4f / 3f)

        val ratio = (4f / 3f) / (16f / 9f)
        assertEquals(0.5f + 0.5f * ratio, reframed.leftEyeY, tolerance)
        assertEquals(analysis.leftEyeX, reframed.leftEyeX, tolerance)
        assertEquals(4f / 3f, reframed.sourceAspect, tolerance)
    }

    @Test
    fun reframe_roundTripsExactly() {
        val original = face(eyeY = 0.8f, mouthY = 0.9f, sourceAspect = 16f / 9f)

        val there = LensAnchor.reframe(original, targetAspect = 4f / 3f)
        val back = LensAnchor.reframe(there, targetAspect = 16f / 9f)

        assertEquals(original.leftEyeY, back.leftEyeY, tolerance)
        assertEquals(original.mouthLeftY, back.mouthLeftY, tolerance)
    }

    // ---------------------------------------------------------------- square space

    @Test
    fun squareSpaceConversion_roundTrips() {
        val original = 0.37f

        assertEquals(
            original,
            LensAnchor.fromSquareY(LensAnchor.toSquareY(original, frameAspect), frameAspect),
            tolerance,
        )
    }

    @Test
    fun squareSpaceConversion_shrinksYOnAWideFrame() {
        // Direction matters, and a round-trip test alone cannot catch an inverted pair.
        assertTrue(LensAnchor.toSquareY(0.5f, frameAspect) < 0.5f)
        assertEquals(0.42f, LensAnchor.toSquareY(0.42f, frameAspect = 1f), tolerance)
    }

    // ---------------------------------------------------------------- character features

    private val layout = FeatureLayout(
        eyeSpacingInUnits = 0.55f,
        eyeUpInUnits = 0.4f,
        eyeWidthInUnits = 0.8f,
        mouthUpInUnits = -0.6f,
        mouthWidthInUnits = 1.3f,
    )

    private fun featuresOf(face: FaceSnapshot, aspect: Float = frameAspect) =
        LensAnchor.features(face, frameOf(face, aspect), layout, aspect)

    @Test
    fun features_sourceTheSubjectsRealLandmarks() {
        val subject = face()
        val (leftEye, rightEye, mouth) = featuresOf(subject)

        assertEquals(subject.leftEyeX, leftEye.sourceCenterX, tolerance)
        assertEquals(subject.rightEyeX, rightEye.sourceCenterX, tolerance)
        assertEquals(
            (subject.mouthLeftX + subject.mouthRightX) / 2f,
            mouth.sourceCenterX,
            tolerance,
        )
    }

    @Test
    fun features_destinationsIgnoreTheSubjectsOwnProportions() {
        // THE point of a character lens: a subject with wide-set eyes and one with narrow-set eyes
        // must produce the SAME character face. Only the source changes.
        val wide = featuresOf(face(eyeHalfSpan = 0.16f))
        val narrow = featuresOf(face(eyeHalfSpan = 0.03f))

        wide.zip(narrow).forEach { (a, b) ->
            assertEquals(a.destCenterX, b.destCenterX, tolerance)
            assertEquals(a.destCenterY, b.destCenterY, tolerance)
            assertEquals(a.destHalfWidth, b.destHalfWidth, tolerance)
        }
        assertTrue(
            "the sources must still differ — that is where the expression comes from",
            abs(wide[0].sourceCenterX - narrow[0].sourceCenterX) > tolerance,
        )
    }

    @Test
    fun features_eyesLandOnOppositeSidesOfTheCharactersFace() {
        val (leftEye, rightEye, _) = featuresOf(face())

        assertTrue(
            "eyes must straddle the centre line, got ${leftEye.destCenterX} and ${rightEye.destCenterX}",
            (leftEye.destCenterX - 0.5f) * (rightEye.destCenterX - 0.5f) < 0f,
        )
    }

    @Test
    fun features_eyesDoNotCrossOverWhenTheImageIsMirrored() {
        // ML Kit's LEFT_EYE is the subject's own left, which changes image side under mirroring.
        // Each eye must still be drawn on the side it visually appears, or they swap over.
        val mirrored = face().mirrored()
        val (leftEye, rightEye, _) = featuresOf(mirrored)

        assertTrue(
            "the eye on the image's right must be drawn on the right",
            (leftEye.sourceCenterX - 0.5f) * (leftEye.destCenterX - 0.5f) > 0f,
        )
        assertTrue(
            (rightEye.sourceCenterX - 0.5f) * (rightEye.destCenterX - 0.5f) > 0f,
        )
    }

    @Test
    fun features_mouthSitsBelowTheEyesOnTheCharacter() {
        val (leftEye, _, mouth) = featuresOf(face())

        assertTrue(
            "mouth ${mouth.destCenterY} should be below eyes ${leftEye.destCenterY}",
            mouth.destCenterY > leftEye.destCenterY,
        )
    }

    @Test
    fun features_scaleWithTheFace() {
        val near = featuresOf(face(eyeY = 0.30f, mouthY = 0.70f))
        val far = featuresOf(face(eyeY = 0.45f, mouthY = 0.55f))

        assertEquals(4f, near[0].destHalfWidth / far[0].destHalfWidth, tolerance)
        assertEquals(4f, near[0].sourceHalfWidth / far[0].sourceHalfWidth, tolerance)
    }

    @Test
    fun features_rotateWithTheHead() {
        val tilted = face().tiltedBy(25f, frameAspect)

        featuresOf(tilted).forEach { feature ->
            assertEquals(25f * PI.toFloat() / 180f, feature.rotationRadians, 1e-2f)
        }
    }

    @Test
    fun features_areOnlyProducedForCharacterLenses() {
        // A prop lens leaves the subject's face alone; only characters replace it.
        assertNotNull("Broccoli is the character lens", Lens.Broccoli.features)
        assertNull("Shades is a prop, not a character", Lens.Sunglasses.features)
        assertNull("Dog is a prop — the subject's own face shows through", Lens.Dog.features)
    }

    @Test
    fun characterLensesKeepTheirFeaturesOnTheirArt() {
        // Guards a sign flip that would strand the eyes off the vegetable entirely.
        val frame = frameOf(face())
        Lens.entries.forEach { lens ->
            val featureLayout = lens.features ?: return@forEach
            // A character wears exactly one opaque layer; features go on that.
            val art = lens.art.singleOrNull()
                ?: error("${lens.name} carries features, so it needs exactly one art layer")
            val quad = LensAnchor.sticker(face(), frame, art.placement, frameAspect)
            LensAnchor.features(face(), frame, featureLayout, frameAspect).forEach { feature ->
                assertTrue(
                    "${lens.name}: a feature at ${feature.destCenterX} is outside its art",
                    abs(feature.destCenterX - quad.centerX) < quad.halfWidth,
                )
                assertTrue(
                    "${lens.name}: a feature at ${feature.destCenterY} is outside its art",
                    abs(feature.destCenterY - quad.centerY) < quad.halfHeight,
                )
            }
        }
    }

    // ---------------------------------------------------------------- the catalogue

    @Test
    fun everyLensHasArt_andIsNamed() {
        Lens.entries.forEach { lens ->
            // Art is now the ONLY way a lens does anything — the warp path that let Big Mouth and
            // Bug Eyes ship with an empty `art` list went with them.
            assertTrue("${lens.name} must carry at least one art layer", lens.art.isNotEmpty())
            assertTrue("${lens.name} needs a display name", lens.displayName.isNotBlank())
        }
    }

    @Test
    fun everyStickerLensHasSaneProportions() {
        Lens.entries.flatMap { it.art }.forEach { art ->
            assertTrue("width must be positive", art.placement.widthInUnits > 0f)
            assertTrue("art aspect must be positive", art.placement.artAspect > 0f)
            // A lens more than ~6 face-units across is almost certainly a units mistake.
            assertTrue("suspiciously large: ${art.placement.widthInUnits}", art.placement.widthInUnits < 6f)
        }
    }

    @Test
    fun placementsProduceQuadsNearTheFace() {
        // Catches a sign error in upInUnits that would fling a lens off the frame.
        val frame = frameOf(face())
        Lens.entries.flatMap { it.art }.forEach { art ->
            val quad = LensAnchor.sticker(face(), frame, art.placement, frameAspect)
            val offsetUnits = abs(
                LensAnchor.toSquareY(quad.centerY - frame.originY, frameAspect),
            ) / frame.unit
            assertTrue(
                "a lens centered $offsetUnits units from the eyes is off the head",
                offsetUnits < 3f,
            )
        }
    }

    // ---------------------------------------------------------------- landmark anchors

    @Test
    fun anchor_defaultsToTheFaceOrigin_soEveryOlderLensIsUnchanged() {
        val subject = face()
        val frame = frameOf(subject)

        val quad = LensAnchor.sticker(subject, frame, onEyes, frameAspect)

        assertEquals(frame.originX, quad.centerX, tolerance)
        assertEquals(frame.originY, quad.centerY, tolerance)
    }

    @Test
    fun anchor_measuresFromTheNamedLandmark() {
        val subject = face()
        val frame = frameOf(subject)
        fun centredOn(anchor: LensAnchorPoint) = LensAnchor.sticker(
            subject,
            frame,
            onEyes.copy(anchor = anchor),
            frameAspect,
        )

        val leftEye = centredOn(LensAnchorPoint.LEFT_EYE)
        val rightEye = centredOn(LensAnchorPoint.RIGHT_EYE)
        val mouth = centredOn(LensAnchorPoint.MOUTH)

        assertEquals(subject.leftEyeX, leftEye.centerX, tolerance)
        assertEquals(subject.leftEyeY, leftEye.centerY, tolerance)
        assertEquals(subject.rightEyeX, rightEye.centerX, tolerance)
        assertEquals((subject.mouthLeftX + subject.mouthRightX) / 2f, mouth.centerX, tolerance)
        assertEquals((subject.mouthLeftY + subject.mouthRightY) / 2f, mouth.centerY, tolerance)
    }

    @Test
    fun anchor_followsTheLandmarkNotAFixedSpacing() {
        // THE reason anchors exist. A character lens wants fixed destinations; a PROP that must
        // cover the subject's real eye has to move with that eye, or it misses on a wide face.
        val onLeftEye = onEyes.copy(anchor = LensAnchorPoint.LEFT_EYE)
        val wide = face(eyeHalfSpan = 0.16f)
        val narrow = face(eyeHalfSpan = 0.03f)

        val wideQuad = LensAnchor.sticker(wide, frameOf(wide), onLeftEye, frameAspect)
        val narrowQuad = LensAnchor.sticker(narrow, frameOf(narrow), onLeftEye, frameAspect)

        assertEquals(wide.leftEyeX, wideQuad.centerX, tolerance)
        assertEquals(narrow.leftEyeX, narrowQuad.centerX, tolerance)
        assertTrue(
            "the two must genuinely differ, or this test proves nothing",
            abs(wideQuad.centerX - narrowQuad.centerX) > tolerance,
        )
    }

    @Test
    fun anchor_offsetsStillRotateWithTheHead() {
        // An anchored offset must orbit its landmark the way a face-anchored one orbits the eyes.
        val below = LensPlacement(
            widthInUnits = 0.5f,
            artAspect = 1f,
            upInUnits = -1f,
            anchor = LensAnchorPoint.MOUTH,
        )
        val tiltedFace = face().tiltedBy(90f, frameAspect)
        val tilted = frameOf(tiltedFace)

        val quad = LensAnchor.sticker(tiltedFace, tilted, below, frameAspect)

        val mouthX = (tiltedFace.mouthLeftX + tiltedFace.mouthRightX) / 2f
        val mouthY = (tiltedFace.mouthLeftY + tiltedFace.mouthRightY) / 2f
        // A quarter turn puts "below the mouth" out to the side, at the same distance.
        assertEquals(mouthY, quad.centerY, tolerance)
        assertEquals(
            tilted.unit,
            hypot(quad.centerX - mouthX, LensAnchor.toSquareY(quad.centerY - mouthY, frameAspect)),
            tolerance,
        )
    }

    @Test
    fun rightInUnits_movesAcrossTheFace_andDefaultsToTheCentreLine() {
        val subject = face()
        val frame = frameOf(subject)

        val centred = LensAnchor.sticker(subject, frame, onEyes, frameAspect)
        val offset = LensAnchor.sticker(
            subject,
            frame,
            onEyes.copy(rightInUnits = 0.5f),
            frameAspect,
        )

        assertEquals(frame.originX, centred.centerX, tolerance)
        assertEquals(0.5f * frame.unit, offset.centerX - centred.centerX, tolerance)
        assertEquals(centred.centerY, offset.centerY, tolerance)
    }

    // ---------------------------------------------------------------- the wobble

    private val hanging = LensPlacement(
        widthInUnits = 0.5f,
        artAspect = 2f,
        upInUnits = -1f,
        anchor = LensAnchorPoint.MOUTH,
    )

    @Test
    fun wobble_ofZero_isExactlyTheRigidPlacement() {
        // Every non-wobbling lens must be bit-identical to before the parameter existed.
        val subject = face()
        val frame = frameOf(subject)

        val rigid = LensAnchor.sticker(subject, frame, hanging, frameAspect)
        val explicitZero = LensAnchor.sticker(subject, frame, hanging, frameAspect, wobbleRadians = 0f)

        assertEquals(rigid, explicitZero)
    }

    @Test
    fun wobble_turnsTheArtAboutItsAnchor_notItsOwnCentre() {
        // The distinction that makes a hanging part swing instead of spin in place: the art's
        // distance from the anchor is preserved while its position moves.
        val subject = face()
        val frame = frameOf(subject)
        val mouthX = (subject.mouthLeftX + subject.mouthRightX) / 2f
        val mouthY = (subject.mouthLeftY + subject.mouthRightY) / 2f
        fun distanceFromMouth(quad: StickerQuad) = hypot(
            quad.centerX - mouthX,
            LensAnchor.toSquareY(quad.centerY - mouthY, frameAspect),
        )

        val rest = LensAnchor.sticker(subject, frame, hanging, frameAspect)
        val swung = LensAnchor.sticker(subject, frame, hanging, frameAspect, wobbleRadians = 0.3f)

        assertEquals(
            "a swing must not change how far the part hangs",
            distanceFromMouth(rest),
            distanceFromMouth(swung),
            tolerance,
        )
        assertTrue(
            "a swing must actually move it sideways",
            abs(swung.centerX - rest.centerX) > 0.01f,
        )
    }

    @Test
    fun wobble_turnsTheArtByExactlyTheSwingAngle() {
        val subject = face()
        val frame = frameOf(subject)

        val swung = LensAnchor.sticker(subject, frame, hanging, frameAspect, wobbleRadians = 0.3f)
        val rest = LensAnchor.sticker(subject, frame, hanging, frameAspect)

        assertEquals(0.3f, swung.rotationRadians - rest.rotationRadians, tolerance)
    }

    @Test
    fun wobble_isSymmetricAboutRest() {
        val subject = face()
        val frame = frameOf(subject)
        val mouthX = (subject.mouthLeftX + subject.mouthRightX) / 2f

        val left = LensAnchor.sticker(subject, frame, hanging, frameAspect, wobbleRadians = -0.25f)
        val right = LensAnchor.sticker(subject, frame, hanging, frameAspect, wobbleRadians = 0.25f)

        assertEquals(mouthX - left.centerX, right.centerX - mouthX, tolerance)
        assertEquals(left.centerY, right.centerY, tolerance)
    }

    @Test
    fun wobble_neverChangesHowBigThePartIs() {
        val subject = face()
        val frame = frameOf(subject)

        val rest = LensAnchor.sticker(subject, frame, hanging, frameAspect)
        val swung = LensAnchor.sticker(subject, frame, hanging, frameAspect, wobbleRadians = 0.4f)

        assertEquals(rest.halfWidth, swung.halfWidth, tolerance)
        assertEquals(rest.halfHeight, swung.halfHeight, tolerance)
    }

    // ---------------------------------------------------------------- the physics drive

    @Test
    fun lateralShift_isZeroWhenTheHeadHoldsStill() {
        val subject = face()

        val shift = LensAnchor.lateralShiftInUnits(subject, subject, frameOf(subject), frameAspect)

        assertEquals(0f, shift, tolerance)
    }

    @Test
    fun lateralShift_isSignedAlongTheFacesOwnRightAxis() {
        val before = face(centerX = 0.40f)
        val after = face(centerX = 0.50f)

        val shift = LensAnchor.lateralShiftInUnits(before, after, frameOf(after), frameAspect)

        assertTrue("moving toward +x is a positive shift, got $shift", shift > 0f)
        assertEquals(
            -shift,
            LensAnchor.lateralShiftInUnits(after, before, frameOf(before), frameAspect),
            tolerance,
        )
    }

    @Test
    fun lateralShift_isInFaceUnits_soDistanceFromTheCameraCannotChangeIt() {
        // THE property that lets one WobbleSpec work at any distance: the same head movement,
        // measured relative to the head, must give the same number however big the head looks.
        val nearBefore = face(centerX = 0.40f, eyeY = 0.30f, mouthY = 0.70f)
        val nearAfter = face(centerX = 0.50f, eyeY = 0.30f, mouthY = 0.70f)
        // A face a QUARTER the size (eye-to-mouth 0.10 against 0.40) sliding a quarter as far:
        // the same movement relative to the head, so it must produce the same drive.
        val farBefore = face(centerX = 0.475f, eyeY = 0.45f, mouthY = 0.55f)
        val farAfter = face(centerX = 0.50f, eyeY = 0.45f, mouthY = 0.55f)

        assertEquals(
            LensAnchor.lateralShiftInUnits(nearBefore, nearAfter, frameOf(nearAfter), frameAspect),
            LensAnchor.lateralShiftInUnits(farBefore, farAfter, frameOf(farAfter), frameAspect),
            tolerance,
        )
    }

    @Test
    fun lateralShift_ignoresMovementStraightUpTheFace() {
        // A nod is not a swing. Only travel along the face's `right` axis drives the pendulum.
        val before = face(eyeY = 0.40f, mouthY = 0.60f)
        val after = face(eyeY = 0.45f, mouthY = 0.65f)

        val shift = LensAnchor.lateralShiftInUnits(before, after, frameOf(after), frameAspect)

        assertEquals(0f, shift, tolerance)
    }

    // ---------------------------------------------------------------- Twisted Tongue's own shape

    @Test
    fun twistedTongue_putsAnEyeballOnEachTrackedEye() {
        val subject = face()
        val frame = frameOf(subject)
        val eyeAnchors = Lens.TwistedTongue.art
            .map { it.placement.anchor }
            .filter { it == LensAnchorPoint.LEFT_EYE || it == LensAnchorPoint.RIGHT_EYE }

        assertEquals("one eyeball per eye", 2, eyeAnchors.size)
        assertEquals(2, eyeAnchors.toSet().size)

        val eyes = Lens.TwistedTongue.art
            .filter { it.placement.anchor in setOf(LensAnchorPoint.LEFT_EYE, LensAnchorPoint.RIGHT_EYE) }
            .map { LensAnchor.sticker(subject, frame, it.placement, frameAspect) }
        assertTrue(
            "the eyeballs must straddle the centre line",
            (eyes[0].centerX - frame.originX) * (eyes[1].centerX - frame.originX) < 0f,
        )
    }

    @Test
    fun twistedTongue_eyeballsCoverTheEyeButStayOnTheHead() {
        // Asserted against the catalogue NUMBERS and the anatomy table in Lens.kt's header, not
        // against `face()`. That fixture is a convenient synthetic head with an eye span chosen to
        // make the unit a round number, so it is not a witness to real proportions. The claim here
        // is about the shipped constants, so the constants are what the test reads.
        val halfWidthUnits = Lens.TwistedTongue.art
            .first { it.placement.anchor == LensAnchorPoint.LEFT_EYE }
            .placement.widthInUnits / 2f

        assertTrue(
            "an eyeball reaching ${EYE_OFFSET_UNITS + halfWidthUnits} units is off the head",
            EYE_OFFSET_UNITS + halfWidthUnits < HEAD_HALF_WIDTH_UNITS,
        )
        assertTrue(
            "the eyeballs must not merge across the nose bridge",
            EYE_OFFSET_UNITS - halfWidthUnits > 0f,
        )
    }

    /** The x of a quad's two TOP corners, in square space — where a hanging part is attached. */
    @Suppress("SameParameterValue") // the aspect is spelled out at each call site on purpose
    private fun topCornerXs(quad: StickerQuad, aspect: Float): List<Float> {
        val cos = kotlin.math.cos(quad.rotationRadians)
        val sin = kotlin.math.sin(quad.rotationRadians)
        val halfHeightSquare = LensAnchor.toSquareY(quad.halfHeight, aspect)
        return listOf(-1f, 1f).map { signX ->
            quad.centerX + (signX * quad.halfWidth) * cos - (-halfHeightSquare) * sin
        }
    }

    @Test
    fun twistedTongue_hidesTheTonguesRootBehindTheTeeth_atFullSwing() {
        // The check that decides whether this reads as a tongue in a mouth or a sticker on a chin,
        // and the whole reason the teeth are their own layer. Evaluated at the swing LIMIT, because
        // at rest it passes trivially and the failure only ever happens mid-swing.
        val subject = face()
        val frame = frameOf(subject)
        val tongue = Lens.TwistedTongue.art.single { it.placement.wobble != null }
        val limit = requireNotNull(tongue.placement.wobble).limitRadians
        val teeth = Lens.TwistedTongue.art.last { it.placement.anchor == LensAnchorPoint.MOUTH }

        val teethQuad = LensAnchor.sticker(subject, frame, teeth.placement, frameAspect)

        listOf(-limit, 0f, limit).forEach { swing ->
            val swung = LensAnchor.sticker(subject, frame, tongue.placement, frameAspect, swing)
            topCornerXs(swung, frameAspect).forEach { cornerX ->
                val reach = abs(cornerX - teethQuad.centerX)
                assertTrue(
                    "at swing $swing the tongue root reaches $reach, " +
                        "past the teeth's ${teethQuad.halfWidth}",
                    reach < teethQuad.halfWidth,
                )
            }
        }
    }

    @Test
    fun twistedTongue_rootStaysTuckedAboveTheLowerTeethEdge() {
        // The other half of the same claim: covered SIDEWAYS is not enough, the root also has to
        // start high enough to be behind the teeth rather than below them.
        val subject = face()
        val frame = frameOf(subject)
        val tongue = Lens.TwistedTongue.art.single { it.placement.wobble != null }
        val teeth = Lens.TwistedTongue.art.last { it.placement.anchor == LensAnchorPoint.MOUTH }

        val tongueQuad = LensAnchor.sticker(subject, frame, tongue.placement, frameAspect)
        val teethQuad = LensAnchor.sticker(subject, frame, teeth.placement, frameAspect)

        val tongueTop = tongueQuad.centerY - tongueQuad.halfHeight
        val teethBottom = teethQuad.centerY + teethQuad.halfHeight
        assertTrue(
            "the tongue starts at $tongueTop, below the teeth layer's $teethBottom — it would " +
                "emerge in front of them instead of behind",
            tongueTop < teethBottom,
        )
    }

    @Test
    fun twistedTongue_isTheOnlyLensThatSwings_andOnlyItsTongueDoes() {
        val wobbling = Lens.entries.flatMap { lens -> lens.art.map { lens to it } }
            .filter { (_, art) -> art.placement.wobble != null }

        assertEquals("exactly one layer in the catalogue swings", 1, wobbling.size)
        assertEquals(Lens.TwistedTongue, wobbling.single().first)
        assertEquals(
            "the swinging layer must hang from the mouth",
            LensAnchorPoint.MOUTH,
            wobbling.single().second.placement.anchor,
        )
    }

    @Test
    fun twistedTongue_isAProp_soTheSubjectsFaceShowsAround() {
        assertNull("features would replace the face; this lens decorates it", Lens.TwistedTongue.features)
    }

    @Test
    fun multiLayerLenses_keepEveryLayerOnTheHead() {
        // Catalogue-driven: a new layer with a sign error in upInUnits fails here, not on a phone.
        val subject = face()
        val frame = frameOf(subject)
        Lens.entries.forEach { lens ->
            lens.art.forEach { art ->
                val quad = LensAnchor.sticker(subject, frame, art.placement, frameAspect)
                val offsetUnits = hypot(
                    quad.centerX - frame.originX,
                    LensAnchor.toSquareY(quad.centerY - frame.originY, frameAspect),
                ) / frame.unit
                assertTrue(
                    "${lens.name}: a layer $offsetUnits units from the eyes is off the head",
                    offsetUnits < 3f,
                )
            }
        }
    }

    // ---------------------------------------------------------------- Elvis lens geometry (bitmap assets)

    @Test
    fun elvis_hasTwoLayersAndIsNamedCorrectly() {
        // Photoreal bitmap lens: hair (with sideburns) + shades.
        assertEquals("Elvis", Lens.Elvis.displayName)
        assertEquals(2, Lens.Elvis.art.size)
    }

    @Test
    fun elvis_mouthUncovered() {
        // No layer's AABB may reach the mouth at y = -1.00 within the mouth's x extent ±0.40.
        // Checked against the catalogue numbers and the anatomy table, not a face() fixture.
        Lens.Elvis.art.forEach { art ->
            val halfHeight = art.placement.widthInUnits * art.placement.artAspect / 2f
            val bottom = art.placement.upInUnits - halfHeight
            val halfWidth = art.placement.widthInUnits / 2f
            val leftEdge = art.placement.rightInUnits - halfWidth
            val rightEdge = art.placement.rightInUnits + halfWidth

            // If the layer's x range overlaps the mouth's ±0.40, its bottom must not reach -1.00.
            val xOverlapsMouth = (leftEdge < 0.40f && rightEdge > -0.40f)
            if (xOverlapsMouth) {
                assertTrue(
                    "Elvis layer at rightInUnits ${art.placement.rightInUnits} reaches y = $bottom, " +
                        "covering the mouth at -1.00",
                    bottom > -1.00f,
                )
            }
        }
    }

    @Test
    fun elvis_shadesContainTheEyeLine() {
        // Shades are the last layer (index 1). Measured 1420×504 PNG, aspect 0.3549.
        val shades = Lens.Elvis.art[1]
        
        // Verify measured aspect is used (not placeholder).
        assertEquals(0.3549f, shades.placement.artAspect, 0.001f)
        
        val halfHeight = shades.placement.widthInUnits * shades.placement.artAspect / 2f
        val top = shades.placement.upInUnits + halfHeight
        val bottom = shades.placement.upInUnits - halfHeight

        assertTrue(
            "Elvis shades top $top must be above the eye line (y = 0)",
            top > 0f,
        )
        assertTrue(
            "Elvis shades bottom $bottom must be below the eye line (y = 0)",
            bottom < 0f,
        )
        assertTrue(
            "Elvis shades must be wider than Sunglasses (${Lens.Sunglasses.art.first().placement.widthInUnits})",
            shades.placement.widthInUnits > Lens.Sunglasses.art.first().placement.widthInUnits,
        )
    }

    @Test
    fun elvis_hairCoversAboveTheCrown() {
        // Hair (U-wig with face hole) is layer 0. Measured 974×980 PNG, aspect 1.0062.
        // Top must clear the crown (+1.25), face hole exposes eyes/mouth.
        val hair = Lens.Elvis.art[0]
        
        // Verify measured aspect is used (not placeholder).
        assertEquals(1.0062f, hair.placement.artAspect, 0.001f)
        
        val halfHeight = hair.placement.widthInUnits * hair.placement.artAspect / 2f
        val top = hair.placement.upInUnits + halfHeight
        val bottom = hair.placement.upInUnits - halfHeight

        assertTrue(
            "Elvis hair top $top must reach well above the crown at $CROWN_UNITS",
            top >= CROWN_UNITS + 0.5f,
        )
        // Bottom can be negative (face hole in lower-center exposes eyes at 0, mouth at −1.00).
        assertTrue(
            "Elvis hair bottom $bottom extends down (face hole design)",
            bottom < 0f,
        )
    }

    @Test
    fun elvis_centeredOnFace() {
        // Both layers should be centered (rightInUnits = 0) since hair includes sideburns symmetrically.
        Lens.Elvis.art.forEach { art ->
            assertEquals(
                "Elvis ${art.drawableRes} should be centered on face",
                0f,
                art.placement.rightInUnits,
                tolerance,
            )
        }
    }

    @Test
    fun elvis_isAProp_notACharacter() {
        // Elvis is a prop lens, so the subject's face shows through.
        assertNull("Elvis is a prop, not a character replacement", Lens.Elvis.features)
    }

    // ---------------------------------------------------------------- Cowboy lens geometry

    private val cowboyHat = Lens.Cowboy.art[0]
    private val cowboyMustache = Lens.Cowboy.art[1]

    /** Where a layer's bounding box starts and stops, in face units above the eye line. */
    private fun extentInUnits(art: LensArt): ClosedFloatingPointRange<Float> {
        val anchorUp = if (art.placement.anchor == LensAnchorPoint.MOUTH) -1f else 0f
        val halfHeight = art.placement.widthInUnits * art.placement.artAspect / 2f
        val centre = anchorUp + art.placement.upInUnits
        return (centre - halfHeight)..(centre + halfHeight)
    }

    @Test
    fun cowboy_isAProp_withItsTwoPiecesOnDifferentAnatomy() {
        // A hat belongs to the skull and a mustache to the mouth, and a dropping jaw moves one
        // without the other — which is the whole reason this is two layers rather than one quad.
        assertNull("Cowboy decorates the subject's face; it does not replace it", Lens.Cowboy.features)
        assertEquals(2, Lens.Cowboy.art.size)
        assertEquals(LensAnchorPoint.FACE, cowboyHat.placement.anchor)
        assertEquals(LensAnchorPoint.MOUTH, cowboyMustache.placement.anchor)
    }

    @Test
    fun cowboy_declaredAspectsAreTheAuthoredViewports() {
        // Measured off the encoded assets, never estimated: a guessed aspect stretches the art on
        // the face and nothing else catches it. Re-measure here if render_lens_art.py changes size.
        assertEquals("encoded 1024x492", 492f / 1024f, cowboyHat.placement.artAspect, 1e-4f)
        assertEquals("encoded 1024x413", 413f / 1024f, cowboyMustache.placement.artAspect, 1e-4f)
    }

    @Test
    fun cowboy_hatIsWiderThanTheHead_andRidesDownOverIt() {
        val extent = extentInUnits(cowboyHat)

        assertTrue(
            "a brim narrower than the head is a cap, not a cowboy hat",
            cowboyHat.placement.widthInUnits / 2f > HEAD_HALF_WIDTH_UNITS,
        )
        assertTrue(
            "the crown tops out at ${extent.endInclusive}, below the $CROWN_UNITS skull",
            extent.endInclusive > CROWN_UNITS,
        )
        // The brim has to land ON the brow — that is what makes a hat read as worn. Too high and
        // it floats above the head (the 2026-09-03 hardware finding: a brim at +0.62 left 0.39
        // units of bare forehead); too low, and it covers the eyes the whole lens is tracked from.
        assertTrue(
            "the brim reaches ${extent.start}, down over the $BROW_UNITS brow and toward the eyes",
            extent.start >= BROW_UNITS,
        )
        assertTrue(
            "the brim sits at ${extent.start}, a visible band of forehead above the $BROW_UNITS brow",
            extent.start < BROW_UNITS + 0.25f,
        )
    }

    @Test
    fun cowboy_mustacheSitsOnTheUpperLip_notOnTheChin() {
        // THE sign check for a mouth-anchored layer. `upInUnits` is measured from the mouth, so a
        // flipped sign parks the mustache on the chin and still looks reasonable in a diff.
        val extent = extentInUnits(cowboyMustache)

        assertTrue(
            "the mustache is centred at ${cowboyMustache.placement.upInUnits} — below the lip line",
            cowboyMustache.placement.upInUnits >= 0f,
        )
        assertTrue(
            "the mustache reaches ${extent.endInclusive}, up past the nose and onto the eyes",
            extent.endInclusive < 0f,
        )
        assertTrue(
            "the mustache hangs to ${extent.start}, onto the $CHIN_UNITS chin",
            extent.start > CHIN_UNITS,
        )
    }

    @Test
    fun cowboy_mustacheSpansPastTheMouthCorners_butStaysOnTheFace() {
        // A handlebar's tips belong out on the cheeks — narrower, and it reads as a pencil
        // mustache, which is what the first 1.4-unit draft did on the schematic head.
        val halfWidth = cowboyMustache.placement.widthInUnits / 2f

        assertTrue("a handlebar has to clear the 0.8-unit resting mouth", halfWidth > 0.4f)
        assertTrue("the tips at $halfWidth are off the side of the head", halfWidth <= HEAD_HALF_WIDTH_UNITS + 0.05f)
    }

    @Test
    fun cowboy_leavesTheSubjectsOwnFaceShowingBetweenTheTwoPieces() {
        // What makes it a prop rather than a costume: the eyes, nose and cheeks are in the gap.
        val hatBottom = extentInUnits(cowboyHat).start
        val mustacheTop = extentInUnits(cowboyMustache).endInclusive

        assertTrue(
            "the hat stops at $hatBottom and the mustache starts at $mustacheTop — they overlap",
            hatBottom > mustacheTop,
        )
        // The gap has to span the eyes and the nose — that is the face a prop must not swallow.
        assertTrue(
            "only ${hatBottom - mustacheTop} units of face show between the pieces",
            hatBottom - mustacheTop > 0.9f,
        )
    }

    // ---------------------------------------------------------------- Vampire lens geometry

    @Test
    fun vampire_isAPropWithMeasuredLayersOnTheRightAnatomy() {
        val costume = Lens.Vampire.art[0]
        val fangs = Lens.Vampire.art[1]
        val costumeExtent = extentInUnits(costume)

        assertEquals("Vampire", Lens.Vampire.displayName)
        assertNull("Vampire frames the subject's real face", Lens.Vampire.features)
        assertEquals(LensInteraction.NONE, Lens.Vampire.interaction)
        assertEquals(LensAnchorPoint.FACE, costume.placement.anchor)
        assertEquals(LensAnchorPoint.MOUTH, fangs.placement.anchor)
        assertEquals("encoded 941x1024", 1024f / 941f, costume.placement.artAspect, 1e-4f)
        assertEquals("encoded 1024x642", 642f / 1024f, fangs.placement.artAspect, 1e-4f)
        assertTrue("the cowl stops below the $CROWN_UNITS crown", costumeExtent.endInclusive >= CROWN_UNITS)
        assertTrue("the collar stops above the $CHIN_UNITS chin", costumeExtent.start <= CHIN_UNITS)
    }

    @Test
    fun vampire_fangsExtendFromTheMouth_withoutMovingTheirRoots() {
        val subject = face()
        val frame = frameOf(subject)
        val fangs = Lens.Vampire.art[1]
        val shut = LensAnchor.sticker(subject, frame, fangs.placement, frameAspect, openFraction = 0f)
        val open = LensAnchor.sticker(subject, frame, fangs.placement, frameAspect, openFraction = 1f)
        val shutRoot = shut.centerY - shut.halfHeight
        val openRoot = open.centerY - open.halfHeight

        assertTrue("opening the mouth must visibly lengthen the fangs", open.halfHeight > shut.halfHeight * 2.4f)
        assertEquals("the upper roots must stay attached to the mouth", shutRoot, openRoot, tolerance)
    }

    // ---------------------------------------------------------------- character head coverage

    @Test
    fun characterLensesCoverTheWholeHead() {
        // THE regression for the owner-reported "my chin and mouth show under the lens" bug.
        // A character replaces the head (PRD §4b), so anything of the subject still visible below
        // the art is a failed character — and with the mouth composited onto the art as well, a
        // short lens shows the subject TWO mouths.
        //
        // This is the vertical extent only. It would have caught Football, whose art simply ended
        // at -1.24; it would NOT have caught Pizza, whose bounding box always reached past the chin
        // while the wedge inside it tapered away from the jaw. Silhouette coverage needs the
        // encoded alpha and lives in `swarm/tools/preview_lens.py`; this is the cheap half that
        // runs on every build.
        val subject = face()
        val frame = frameOf(subject)

        Lens.entries.filter { it.features != null }.forEach { lens ->
            val art = lens.art.single()
            val quad = LensAnchor.sticker(subject, frame, art.placement, frameAspect)
            fun unitsFromEyeLine(y: Float) =
                -LensAnchor.toSquareY(y - frame.originY, frameAspect) / frame.unit

            val top = unitsFromEyeLine(quad.centerY - quad.halfHeight)
            val bottom = unitsFromEyeLine(quad.centerY + quad.halfHeight)

            // The CHIN is the reported bug and is asserted with no exceptions: anything below the
            // art is the subject's own face, and on a character that means a second visible mouth.
            assertTrue(
                "${lens.name} stops at $bottom, above the $CHIN_UNITS chin — the subject's own " +
                    "chin and mouth show under the art",
                bottom <= CHIN_UNITS,
            )

            // The CROWN, with no exceptions either. This test found Broccoli topping out at +1.04
            // on 2026-08-16 — a real gap nobody had reported — and it was fixed by raising the
            // wreath 0.22 units rather than by carving out an exemption here.
            assertTrue(
                "${lens.name} stops at $top, below the $CROWN_UNITS crown — forehead on show",
                top >= CROWN_UNITS,
            )
        }
    }

    // ---------------------------------------------------------------- mouth-open reveal

    @Test
    fun mouthOpenness_isShutWhenTheLowerLipSitsOnTheCornerLine() {
        // A closed mouth still has MOUTH_BOTTOM slightly below the corners; that must read as 0.
        assertEquals(0f, LensAnchor.mouthOpenness(eyeToMouth = 100f, mouthToBottom = 20f), tolerance)
        assertEquals(0f, LensAnchor.mouthOpenness(eyeToMouth = 100f, mouthToBottom = 5f), tolerance)
    }

    @Test
    fun mouthOpenness_reachesOneOnAWideJaw_andClampsBeyond() {
        assertEquals(1f, LensAnchor.mouthOpenness(100f, 62f), tolerance)
        assertEquals("must clamp, not keep climbing", 1f, LensAnchor.mouthOpenness(100f, 200f), tolerance)
    }

    @Test
    fun mouthOpenness_isARatio_soDistanceFromTheCameraCannotChangeIt() {
        // The property that lets it be carried as a bare scalar through rotation and re-framing.
        val near = LensAnchor.mouthOpenness(eyeToMouth = 240f, mouthToBottom = 96f)
        val far = LensAnchor.mouthOpenness(eyeToMouth = 60f, mouthToBottom = 24f)

        assertEquals(near, far, tolerance)
        assertTrue("the fixture must be part-open, or this proves nothing", near > 0.1f && near < 0.9f)
    }

    @Test
    fun mouthOpenness_survivesDegenerateInput() {
        assertEquals(0f, LensAnchor.mouthOpenness(0f, 50f), tolerance)
        assertEquals(0f, LensAnchor.mouthOpenness(Float.NaN, 50f), tolerance)
        assertEquals(0f, LensAnchor.mouthOpenness(100f, Float.NaN), tolerance)
    }

    @Test
    fun openness_isCarriedThroughRotationAndReframingUntouched() {
        val subject = face().copy(mouthOpenness = 0.64f)

        assertEquals(0.64f, LensAnchor.uprightToBuffer(subject, 90).mouthOpenness, tolerance)
        assertEquals(0.64f, LensAnchor.reframe(subject, targetAspect = 16f / 9f).mouthOpenness, tolerance)
    }

    @Test
    fun mouthOpenScale_isTheIdentityForALayerThatDoesNotRespond() {
        assertEquals(1f, LensAnchor.mouthOpenScale(null, openFraction = 0f), tolerance)
        assertEquals(1f, LensAnchor.mouthOpenScale(null, openFraction = 1f), tolerance)
    }

    @Test
    fun mouthOpenScale_runsFromRestFractionToFull() {
        val spec = MouthOpenSpec(restFraction = 0.55f)

        assertEquals(0.55f, LensAnchor.mouthOpenScale(spec, 0f), tolerance)
        assertEquals(1f, LensAnchor.mouthOpenScale(spec, 1f), tolerance)
        assertEquals(0.775f, LensAnchor.mouthOpenScale(spec, 0.5f), tolerance)
    }

    @Test
    fun aMouthDrivenLayerGrowsOutOfItsAnchor_notAroundItsOwnCentre() {
        // The distinction that makes a tongue emerge from a mouth rather than inflate on a chin:
        // the anchored EDGE stays put while the far edge travels.
        val subject = face()
        val frame = frameOf(subject)
        val tongue = Lens.TwistedTongue.art.single { it.placement.mouthOpen != null }
        val mouthY = (subject.mouthLeftY + subject.mouthRightY) / 2f

        val shut = LensAnchor.sticker(subject, frame, tongue.placement, frameAspect, openFraction = 0f)
        val wide = LensAnchor.sticker(subject, frame, tongue.placement, frameAspect, openFraction = 1f)

        assertTrue("a wide mouth must show more tongue", wide.halfHeight > shut.halfHeight)
        assertTrue(
            "the tip must travel further from the mouth",
            (wide.centerY + wide.halfHeight) > (shut.centerY + shut.halfHeight),
        )
        // Root: the top edge is pinned near the mouth at both extremes, within a tenth of a unit.
        val shutRoot = abs((shut.centerY - shut.halfHeight) - mouthY)
        val wideRoot = abs((wide.centerY - wide.halfHeight) - mouthY)
        assertTrue(
            "the root wandered: shut $shutRoot vs wide $wideRoot",
            abs(shutRoot - wideRoot) < 0.1f * frame.unit / LensAnchor.toSquareY(1f, frameAspect),
        )
    }

    @Test
    fun theTongueIsStillVisibleWithTheMouthShut() {
        // The reference effect has NO trigger — its blendshape weight is a constant — so the tongue
        // being out at rest is the faithful behavior. A restFraction of 0 would be a different joke.
        val spec = requireNotNull(
            Lens.TwistedTongue.art.single { it.placement.mouthOpen != null }.placement.mouthOpen,
        )

        assertTrue("Twisted Tongue's tongue must not vanish when the mouth shuts", spec.restFraction > 0.25f)
        assertTrue("...but it must still visibly extend", spec.restFraction < 0.9f)
    }

    @Test
    fun everyMouthOpenSpecInTheCatalogueIsInRange() {
        Lens.entries.flatMap { it.art }.mapNotNull { it.placement.mouthOpen }.forEach { spec ->
            assertTrue("$spec restFraction must be 0..1", spec.restFraction in 0f..1f)
        }
    }

    @Test
    fun faceFrameRotation_matchesTheRightVector() {
        val frame = frameOf(face().tiltedBy(-25f, frameAspect))
        val quad = LensAnchor.sticker(face(), frame, onEyes, frameAspect)

        assertEquals(atan2(frame.rightY, frame.rightX), quad.rotationRadians, tolerance)
        assertNotNull(quad)
    }

    // ---------------------------------------------------------------- the flick spin

    @Test
    fun aSpinOfZero_isBitIdenticalToTheRigidPlacement() {
        // The same guarantee the wobble parameter made when it arrived: every non-spinning lens
        // renders exactly as before the parameter existed.
        val frame = frameOf(face())

        assertEquals(
            LensAnchor.sticker(face(), frame, hat, frameAspect),
            LensAnchor.sticker(face(), frame, hat, frameAspect, spinRadians = 0f),
        )
    }

    @Test
    fun theSpin_turnsTheArtAboutItsOwnCenter_notItsAnchor() {
        // Unlike the wobble (which swings the offset vector too, so a tongue hangs from its
        // root), a spun quad must keep its center pinned and only its rotation moves — a flicked
        // ball twirls in place (PRD-lens-interactions §3.5).
        val frame = frameOf(face())
        val rigid = LensAnchor.sticker(face(), frame, hat, frameAspect)
        val spun = LensAnchor.sticker(face(), frame, hat, frameAspect, spinRadians = 1.2f)

        assertEquals(rigid.centerX, spun.centerX, tolerance)
        assertEquals(rigid.centerY, spun.centerY, tolerance)
        assertEquals(rigid.halfWidth, spun.halfWidth, tolerance)
        assertEquals(rigid.halfHeight, spun.halfHeight, tolerance)
        assertEquals(rigid.rotationRadians + 1.2f, spun.rotationRadians, tolerance)
    }

    @Test
    fun spinAndWobble_compose_theSpinAddsOnTopOfTheSwungRotation() {
        val frame = frameOf(face())
        val swung = LensAnchor.sticker(face(), frame, hat, frameAspect, wobbleRadians = 0.2f)
        val both = LensAnchor.sticker(
            face(), frame, hat, frameAspect, wobbleRadians = 0.2f, spinRadians = 0.7f,
        )

        // The wobble decides where the quad sits; the spin only turns it there.
        assertEquals(swung.centerX, both.centerX, tolerance)
        assertEquals(swung.centerY, both.centerY, tolerance)
        assertEquals(swung.rotationRadians + 0.7f, both.rotationRadians, tolerance)
    }
}
