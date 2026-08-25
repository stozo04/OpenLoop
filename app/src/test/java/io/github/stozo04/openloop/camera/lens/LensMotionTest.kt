package io.github.stozo04.openloop.camera.lens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guard for [LensMotion] — the per-face bookkeeping around [LensPhysics].
 *
 * [LensPhysicsTest] proves one spring behaves; this file proves that with two people in frame
 * each person gets *their own* spring and eased mouth (`docs/PRD-multi-face-lenses.md` D5), that a
 * face leaving takes its state with it, and that a solo face is the single-subject behaviour it
 * always was. The emulator cannot show the detector two moving heads, so it is asserted here or
 * nowhere.
 */
class LensMotionTest {

    private val tolerance = 1e-5f

    /** 30 fps in nanoseconds — what the camera stamps on consecutive frames. */
    private val frameNs = 1_000_000_000L / 30L

    /** The shipping lens with a wobbling layer (the tongue) and a mouth-driven one. */
    private val tongue = Lens.TwistedTongue

    private fun face(id: Int, centerX: Float = 0.5f, openness: Float = 0f) = FaceSnapshot(
        leftEyeX = centerX - 0.08f,
        leftEyeY = 0.40f,
        rightEyeX = centerX + 0.08f,
        rightEyeY = 0.40f,
        mouthLeftX = centerX - 0.05f,
        mouthLeftY = 0.60f,
        mouthRightX = centerX + 0.05f,
        mouthRightY = 0.60f,
        sourceAspect = 4f / 3f,
        mouthOpenness = openness,
        trackingId = id,
    )

    /** Steps [frames] camera frames, feeding [roster] for each, and returns the last timestamp. */
    private fun LensMotion.run(
        lens: Lens?,
        frames: Int,
        startNs: Long = frameNs,
        roster: (frame: Int) -> List<FaceSnapshot>,
    ): Long {
        var stamp = startNs
        repeat(frames) { frame ->
            step(lens, roster(frame), stamp)
            stamp += frameNs
        }
        return stamp
    }

    private fun LensMotion.swingOf(id: Int): Float =
        forFace(id)!!.wobbleAngles.maxByOrNull { kotlin.math.abs(it) } ?: 0f

    // ---------------------------------------------------------------- independence

    @Test
    fun twoFaces_haveIndependentSprings_soOneHeadMovingDoesNotSwingTheOther() {
        val motion = LensMotion()

        // A slides sideways every frame; B stands still.
        motion.run(tongue, frames = 12) { frame ->
            listOf(face(id = 1, centerX = 0.3f + frame * 0.02f), face(id = 2, centerX = 0.7f))
        }

        assertTrue("the moving head must swing its tongue", motion.swingOf(1) != 0f)
        assertEquals("the still head's tongue must hang straight", 0f, motion.swingOf(2), tolerance)
    }

    @Test
    fun twoFaces_haveIndependentMouths() {
        val motion = LensMotion()

        motion.run(tongue, frames = 30) {
            listOf(face(id = 1, openness = 1f), face(id = 2, openness = 0f))
        }

        assertTrue("an open mouth eases toward open", motion.forFace(1)!!.openFraction > 0.9f)
        assertEquals("a shut mouth stays shut", 0f, motion.forFace(2)!!.openFraction, tolerance)
    }

    @Test
    fun rosterOrder_doesNotMatter_stateFollowsTheId() {
        val motion = LensMotion()

        // Same two faces, but the roster flips its order halfway through.
        motion.run(tongue, frames = 6) { frame ->
            listOf(face(1, centerX = 0.3f + frame * 0.02f), face(2, centerX = 0.7f))
        }
        motion.run(tongue, frames = 6, startNs = 7L * frameNs) { frame ->
            listOf(face(2, centerX = 0.7f), face(1, centerX = 0.42f + frame * 0.02f))
        }

        assertTrue(motion.swingOf(1) != 0f)
        assertEquals(0f, motion.swingOf(2), tolerance)
    }

    // ---------------------------------------------------------------- arrival and departure

    @Test
    fun aFaceThatLeaves_isEvicted_andComesBackAtRest() {
        val motion = LensMotion()

        val gone = motion.run(tongue, frames = 12) { frame ->
            listOf(face(1, centerX = 0.3f + frame * 0.02f))
        }
        assertTrue(motion.swingOf(1) != 0f)

        motion.step(tongue, emptyList(), gone)
        assertNull("a face out of the roster keeps no state", motion.forFace(1))

        // Back in the roster: no previous frame to measure a shift from, so the spring is at rest.
        motion.step(tongue, listOf(face(1, centerX = 0.9f)), gone + frameNs)
        assertNotNull(motion.forFace(1))
        assertEquals(0f, motion.swingOf(1), tolerance)
        assertEquals(0f, motion.forFace(1)!!.openFraction, tolerance)
    }

    @Test
    fun aNewcomer_startsAtRest_whileTheOtherFaceKeepsSwinging() {
        val motion = LensMotion()

        val stamp = motion.run(tongue, frames = 12) { frame ->
            listOf(face(1, centerX = 0.3f + frame * 0.02f))
        }
        val swingBefore = motion.swingOf(1)

        motion.step(tongue, listOf(face(1, centerX = 0.3f + 12 * 0.02f), face(2)), stamp)

        assertTrue(motion.swingOf(1) != 0f)
        assertTrue("the first face's spring carried on", motion.swingOf(1) != swingBefore)
        assertEquals(0f, motion.swingOf(2), tolerance)
    }

    // ---------------------------------------------------------------- lens changes

    @Test
    fun changingLens_resetsTheSprings_butKeepsTheEasedMouth() {
        val motion = LensMotion()

        motion.run(tongue, frames = 12) { frame ->
            listOf(face(1, centerX = 0.3f + frame * 0.02f, openness = 1f))
        }
        val mouthBefore = motion.forFace(1)!!.openFraction
        assertTrue(motion.swingOf(1) != 0f)

        // Switch to a lens with no wobbling layer (so the angles array is resized), then straight
        // back — the reset is asserted on the way back, where a spring exists to be reset.
        motion.step(Lens.Broccoli, listOf(face(1, centerX = 0.6f, openness = 1f)), 13L * frameNs)
        assertEquals(Lens.Broccoli.art.size, motion.forFace(1)!!.wobbleAngles.size)
        assertTrue(
            "the mouth describes the subject, not the lens",
            motion.forFace(1)!!.openFraction >= mouthBefore,
        )

        motion.step(tongue, listOf(face(1, centerX = 0.9f, openness = 1f)), 14L * frameNs)
        assertEquals("no previous frame after a switch, so no drive", 0f, motion.swingOf(1), tolerance)
    }

    @Test
    fun noLens_stillTracksFaces_withNoLayersToSwing() {
        val motion = LensMotion()

        motion.run(lens = null, frames = 3) { listOf(face(1), face(2)) }

        assertEquals(0, motion.forFace(1)!!.wobbleAngles.size)
        assertEquals(0, motion.forFace(2)!!.wobbleAngles.size)
    }

    // ---------------------------------------------------------------- time

    @Test
    fun theFirstFrame_hasNoElapsedTime_soNothingMoves() {
        val motion = LensMotion()

        motion.step(tongue, listOf(face(1, openness = 1f)), frameNs)

        assertEquals(0f, motion.swingOf(1), tolerance)
        assertEquals(0f, motion.forFace(1)!!.openFraction, tolerance)
    }

    @Test
    fun aZeroTimestamp_fallsBackToTheClock_ratherThanFreezing() {
        var clock = 1_000L
        val motion = LensMotion(nanoTime = { clock })

        repeat(30) {
            motion.step(tongue, listOf(face(1, openness = 1f)), timestampNs = 0L)
            clock += frameNs
        }

        assertTrue(
            "the mouth must still ease on a device that stamps 0",
            motion.forFace(1)!!.openFraction > 0.9f,
        )
    }

    @Test
    fun aTimestampGap_isClamped_soASpringCannotBlowUp() {
        // A paused preview or a dropped batch of frames hands the physics seconds of dt at once.
        val motion = LensMotion()
        val stamp = motion.run(tongue, frames = 12) { frame ->
            listOf(face(1, centerX = 0.3f + frame * 0.02f))
        }

        motion.step(tongue, listOf(face(1, centerX = 0.9f)), stamp + 5_000_000_000L)

        val swing = motion.swingOf(1)
        assertTrue("finite", swing.isFinite())
        val limit = tongue.art.mapNotNull { it.placement.wobble?.limitRadians }.max()
        assertTrue("within the lens's own limit", kotlin.math.abs(swing) <= limit + tolerance)
    }

    @Test
    fun clear_dropsEveryFace() {
        val motion = LensMotion()
        motion.run(tongue, frames = 3) { listOf(face(1), face(2)) }

        motion.clear()

        assertNull(motion.forFace(1))
        assertNull(motion.forFace(2))
    }
}
