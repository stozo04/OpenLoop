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

    private val tolerance = 1e-3f

    /** 4:3, the shape CameraX gives the lens effect on real hardware. */
    private val frameAspect = 1280f / 960f

    /**
     * An upright face: eyes level, mouth centred below. Distances are chosen so the eye-to-mouth
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

    /** Rotates a face about the frame centre, in square space, as a real head tilt would. */
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
        val quad = LensAnchor.sticker(frameOf(face()), onEyes, frameAspect)

        assertEquals(0.5f, quad.centerX, tolerance)
        assertEquals(0.40f, quad.centerY, tolerance)
        assertEquals(0f, quad.rotationRadians, tolerance)
    }

    @Test
    fun sticker_positiveUp_movesTowardTheCrown() {
        val quad = LensAnchor.sticker(frameOf(face()), hat, frameAspect)

        assertTrue("a hat belongs above the eyes, got ${quad.centerY}", quad.centerY < 0.40f)
        assertEquals(0.5f, quad.centerX, tolerance)
    }

    @Test
    fun sticker_sizeScalesWithTheFace_notTheFrame() {
        // The same lens on a face twice as far away must be half the size.
        val near = frameOf(face(eyeY = 0.30f, mouthY = 0.70f))
        val far = frameOf(face(eyeY = 0.45f, mouthY = 0.55f))

        val nearQuad = LensAnchor.sticker(near, onEyes, frameAspect)
        val farQuad = LensAnchor.sticker(far, onEyes, frameAspect)

        assertEquals(4f, nearQuad.halfWidth / farQuad.halfWidth, tolerance)
        assertEquals(onEyes.widthInUnits * near.unit / 2f, nearQuad.halfWidth, tolerance)
    }

    @Test
    fun sticker_keepsArtProportions_onANonSquareFrame() {
        val square = LensAnchor.sticker(frameOf(face()), onEyes, frameAspect)
        val tall = LensAnchor.sticker(frameOf(face()), onEyes.copy(artAspect = 2f), frameAspect)

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

        val quad = LensAnchor.sticker(frameOf(tilted), onEyes, frameAspect)

        assertEquals(30f * PI.toFloat() / 180f, quad.rotationRadians, 1e-2f)
    }

    @Test
    fun sticker_offsetOrbitsWithTheHead_keepingItsDistance() {
        // A hat stays on the crown when the head tilts: same distance from the eyes, new direction.
        val upright = frameOf(face())
        val tilted = frameOf(face().tiltedBy(90f, frameAspect))

        val uprightQuad = LensAnchor.sticker(upright, hat, frameAspect)
        val tiltedQuad = LensAnchor.sticker(tilted, hat, frameAspect)

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
        val upright = LensAnchor.sticker(frameOf(face()), onEyes, frameAspect)
        val tilted = LensAnchor.sticker(frameOf(face().tiltedBy(37f, frameAspect)), onEyes, frameAspect)

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
        val normal = LensAnchor.sticker(frameOf(face(centerX = 0.35f)), hat, frameAspect)
        val mirrored = LensAnchor.sticker(frameOf(face(centerX = 0.35f).mirrored()), hat, frameAspect)

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
        val uprightQuad = LensAnchor.sticker(uprightFrame, hat, uprightAspect)
        val bufferQuad = LensAnchor.sticker(bufferFrame, hat, 1f / uprightAspect)

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

    // ---------------------------------------------------------------- warp

    @Test
    fun warp_sitsOnTheMouth_andScalesWithTheFace() {
        val snapshot = face()
        val frame = frameOf(snapshot)
        val spec = WarpSpec(radiusInUnits = 1.2f, strength = 0.7f)

        val circle = LensAnchor.warp(snapshot, frame, spec, frameAspect)

        assertEquals(0.5f, circle.centerX, tolerance)
        assertEquals(0.60f, circle.centerY, tolerance)
        assertEquals(1.2f * frame.unit, circle.radius, tolerance)
        assertEquals(0.7f, circle.strength, tolerance)
    }

    @Test
    fun warpNone_isAnIdentityTheRendererCanBindUnconditionally() {
        assertEquals(0f, WarpCircle.NONE.strength, tolerance)
        assertEquals(0f, WarpCircle.NONE.radius, tolerance)
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
        assertNull("Big Mouth warps the real face", Lens.BigMouth.features)
    }

    @Test
    fun characterLensesKeepTheirFeaturesOnTheirArt() {
        // Guards a sign flip that would strand the eyes off the vegetable entirely.
        val frame = frameOf(face())
        Lens.entries.forEach { lens ->
            val featureLayout = lens.features ?: return@forEach
            val art = requireNotNull(lens.art) { "${lens.name} needs art to carry features" }
            val quad = LensAnchor.sticker(frame, art.placement, frameAspect)
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
    fun everyLensIsEitherArtOrWarp_andNamed() {
        Lens.entries.forEach { lens ->
            assertTrue(
                "${lens.name} must do something: art, a warp, or both",
                lens.art != null || lens.warp != null,
            )
            assertTrue("${lens.name} needs a display name", lens.displayName.isNotBlank())
        }
    }

    @Test
    fun everyStickerLensHasSaneProportions() {
        Lens.entries.mapNotNull { it.art }.forEach { art ->
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
        Lens.entries.mapNotNull { it.art }.forEach { art ->
            val quad = LensAnchor.sticker(frame, art.placement, frameAspect)
            val offsetUnits = abs(
                LensAnchor.toSquareY(quad.centerY - frame.originY, frameAspect),
            ) / frame.unit
            assertTrue(
                "a lens centred ${offsetUnits} units from the eyes is off the head",
                offsetUnits < 3f,
            )
        }
    }

    @Test
    fun faceFrameRotation_matchesTheRightVector() {
        val frame = frameOf(face().tiltedBy(-25f, frameAspect))
        val quad = LensAnchor.sticker(frame, onEyes, frameAspect)

        assertEquals(atan2(frame.rightY, frame.rightX), quad.rotationRadians, tolerance)
        assertNotNull(quad)
    }
}
