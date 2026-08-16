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

        /** How far an eye sits off the centre line: interpupillary is ~0.8 of eye-to-mouth. */
        const val EYE_OFFSET_UNITS = 0.40f
    }

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

        val circle = LensAnchor.warps(snapshot, frame, spec).single()

        assertEquals(0.5f, circle.centerX, tolerance)
        assertEquals(0.60f, circle.centerY, tolerance)
        assertEquals(1.2f * frame.unit, circle.radius, tolerance)
        assertEquals(0.7f, circle.strength, tolerance)
    }

    @Test
    fun warps_eyeTargetUsesBothTrackedEyes() {
        val snapshot = face()
        val frame = frameOf(snapshot)
        val circles = LensAnchor.warps(
            snapshot,
            frame,
            WarpSpec(radiusInUnits = 0.36f, strength = 0.75f, target = WarpTarget.EYES),
        )

        assertEquals(2, circles.size)
        assertEquals(snapshot.leftEyeX, circles[0].centerX, tolerance)
        assertEquals(snapshot.leftEyeY, circles[0].centerY, tolerance)
        assertEquals(snapshot.rightEyeX, circles[1].centerX, tolerance)
        assertEquals(snapshot.rightEyeY, circles[1].centerY, tolerance)
        circles.forEach { circle ->
            assertEquals(0.36f * frame.unit, circle.radius, tolerance)
            assertEquals(0.75f, circle.strength, tolerance)
        }
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
    fun everyLensIsEitherArtOrWarp_andNamed() {
        Lens.entries.forEach { lens ->
            assertTrue(
                "${lens.name} must do something: art, a warp, or both",
                lens.art.isNotEmpty() || lens.warp != null,
            )
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
                "a lens centred ${offsetUnits} units from the eyes is off the head",
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
        // against `face()` — that fixture is a convenient synthetic head with an eye span chosen to
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
    fun twistedTongue_rootStaysTuckedAboveTheTeethsLowerEdge() {
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
        assertNull("the eyeballs are drawn art, not a pixel bulge", Lens.TwistedTongue.warp)
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

    @Test
    fun faceFrameRotation_matchesTheRightVector() {
        val frame = frameOf(face().tiltedBy(-25f, frameAspect))
        val quad = LensAnchor.sticker(face(), frame, onEyes, frameAspect)

        assertEquals(atan2(frame.rightY, frame.rightX), quad.rotationRadians, tolerance)
        assertNotNull(quad)
    }
}
