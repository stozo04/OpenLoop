package io.github.stozo04.openloop.camera.lens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guard for [HandFlick] — "hand velocity near sticker" (`docs/PRD-lens-hand-flick.md` §3.3).
 *
 * **This file is the primary verification of the gesture.** The emulator's virtual scene has no
 * hand to wave, so contact, threshold, re-arm and the velocity arithmetic are asserted here or
 * nowhere; the phone proves only that the tracker delivers snapshots into this class.
 */
class HandFlickTest {

    private val aspect = 4f / 3f

    /** A ball centred in the frame: 0.3 wide (square units), 0.2 tall (normalized y). */
    private val ball = StickerQuad(
        centerX = 0.5f,
        centerY = 0.5f,
        halfWidth = 0.15f,
        halfHeight = 0.1f,
        rotationRadians = 0f,
    )

    private fun target(
        trackingId: Int = 7,
        layerIndex: Int = 0,
        quad: StickerQuad = ball,
        faceUnit: Float = 0.1f,
        minHandSpeed: Float = 3f,
    ) = HandFlick.Target(trackingId, layerIndex, quad, faceUnit, minHandSpeed)

    /**
     * A whole hand parked at ([x], [y]) — every landmark on one point, so "the hand touches the
     * ball" and "the palm is at" are the same statement.
     */
    private fun hand(x: Float, y: Float, atMs: Long, sourceAspect: Float = aspect) = HandSnapshot(
        xs = FloatArray(HandSnapshot.LANDMARK_COUNT) { x },
        ys = FloatArray(HandSnapshot.LANDMARK_COUNT) { y },
        sourceAspect = sourceAspect,
        timestampMs = atMs,
    )

    /** Frames 33 ms apart moving [stepX] per frame — a wave of `stepX / 0.033` normalized units/s. */
    private fun wave(flick: HandFlick, targets: List<HandFlick.Target>, fromX: Float, stepX: Float, frames: Int): List<HandFlick.Impulse> {
        val impulses = ArrayList<HandFlick.Impulse>()
        repeat(frames) { index ->
            val snapshot = hand(fromX + stepX * index, 0.5f, atMs = 1_000L + FRAME_MS * index)
            flick.evaluate(snapshot, targets, aspect)?.let(impulses::add)
        }
        return impulses
    }

    // ---------------------------------------------------------------- the basic verb

    @Test
    fun aFastHandCrossingTheBall_firesExactlyOnce() {
        // 0.03/frame ≈ 0.9 units/s of frame → 9 face units/s at unit 0.1: a clear wave.
        val impulses = wave(HandFlick(), listOf(target()), fromX = 0.2f, stepX = 0.03f, frames = 20)

        assertEquals(1, impulses.size)
        assertEquals(7, impulses[0].trackingId)
        assertEquals(0, impulses[0].layerIndex)
    }

    @Test
    fun theImpulseCarriesThePalmVelocity_inFaceUnitsPerSecond() {
        val impulses = wave(HandFlick(), listOf(target(faceUnit = 0.1f)), fromX = 0.2f, stepX = 0.03f, frames = 20)

        val perSecond = 0.03f / (FRAME_MS / 1000f) // normalized x per second
        assertEquals(perSecond / 0.1f, impulses[0].velocityX, 0.05f)
        assertEquals(0f, impulses[0].velocityY, 1e-4f)
        assertEquals(impulses[0].velocityX, impulses[0].speed, 1e-4f)
    }

    @Test
    fun theLeverIsFromTheQuadCentreToThePointOfContact() {
        // Hand parked on the ball's top edge (y = 0.5 − 0.1, on the quad) moving right.
        val flick = HandFlick()
        flick.evaluate(hand(0.40f, 0.41f, atMs = 1_000L), listOf(target(faceUnit = 0.1f)), aspect)
        val impulse = flick.evaluate(hand(0.44f, 0.41f, atMs = 1_033L), listOf(target(faceUnit = 0.1f)), aspect)

        assertNotNull(impulse)
        assertEquals((0.44f - 0.5f) / 0.1f, impulse!!.leverX, 1e-4f)
        // Above the centre → negative y in square space, divided by the face unit.
        assertEquals(LensAnchor.toSquareY(0.41f - 0.5f, aspect) / 0.1f, impulse.leverY, 1e-4f)
        assertTrue(impulse.leverY < 0f)
    }

    // ---------------------------------------------------------------- what does NOT fire

    @Test
    fun aHandRestingOnTheBall_neverFires() {
        val flick = HandFlick()
        repeat(30) { index ->
            // Barely drifting: 0.001/frame → 0.3 units/s, far under the 3 units/s threshold.
            val snapshot = hand(0.5f + 0.001f * index, 0.5f, atMs = 1_000L + FRAME_MS * index)
            assertNull(flick.evaluate(snapshot, listOf(target()), aspect))
        }
    }

    @Test
    fun aFastHandThatMissesTheBall_neverFires() {
        // Same wave as the basic case, a hand-height above the ball.
        val flick = HandFlick()
        repeat(20) { index ->
            val snapshot = hand(0.2f + 0.03f * index, 0.2f, atMs = 1_000L + FRAME_MS * index)
            assertNull(flick.evaluate(snapshot, listOf(target()), aspect))
        }
    }

    @Test
    fun theFirstFrameOfAHand_hasNoVelocityAndCannotFire() {
        val flick = HandFlick()

        assertNull(flick.evaluate(hand(0.5f, 0.5f, atMs = 1_000L), listOf(target()), aspect))
    }

    @Test
    fun noHand_isNotAContact_andForgetsTheHistory() {
        val flick = HandFlick()
        flick.evaluate(hand(0.2f, 0.5f, atMs = 1_000L), listOf(target()), aspect)
        assertNull(flick.evaluate(null, listOf(target()), aspect))
        // The next hand starts from scratch: one frame, no velocity, even though it moved a lot.
        assertNull(flick.evaluate(hand(0.5f, 0.5f, atMs = 1_066L), listOf(target()), aspect))
    }

    @Test
    fun theSameSnapshotOnConsecutiveRendererFrames_isEvaluatedOnce() {
        val flick = HandFlick()
        val targets = listOf(target())
        flick.evaluate(hand(0.40f, 0.5f, atMs = 1_000L), targets, aspect)
        val moving = hand(0.44f, 0.5f, atMs = 1_033L)

        assertNotNull(flick.evaluate(moving, targets, aspect))
        // The detector is slower than the camera: the renderer sees the same object again.
        assertNull(flick.evaluate(moving, targets, aspect))
        assertNull(flick.evaluate(moving, targets, aspect))
    }

    @Test
    fun aDetectorStall_isNotAVelocity() {
        val flick = HandFlick()
        flick.evaluate(hand(0.40f, 0.5f, atMs = 1_000L), listOf(target()), aspect)
        // Two seconds later, on the other side of the ball: a teleport, not a wave.
        assertNull(flick.evaluate(hand(0.60f, 0.5f, atMs = 3_000L), listOf(target()), aspect))
    }

    @Test
    fun noTargets_neverFires_andKeepsTrackingTheHand() {
        val flick = HandFlick()
        flick.evaluate(hand(0.40f, 0.5f, atMs = 1_000L), emptyList(), aspect)
        assertNull(flick.evaluate(hand(0.44f, 0.5f, atMs = 1_033L), emptyList(), aspect))
        // A target appearing later (lens selected mid-wave) fires from the tracked history.
        assertNotNull(flick.evaluate(hand(0.48f, 0.5f, atMs = 1_066L), listOf(target()), aspect))
    }

    // ---------------------------------------------------------------- re-arm

    @Test
    fun aHandWavingInsideTheBall_pumpsAtMostOncePerRearmWindow() {
        val flick = HandFlick()
        val targets = listOf(target())
        var fired = 0
        // 40 frames ≈ 1.3 s of fast side-to-side motion that never leaves the ball.
        repeat(40) { index ->
            val x = 0.5f + if (index % 2 == 0) 0.04f else -0.04f
            if (flick.evaluate(hand(x, 0.5f, atMs = 1_000L + FRAME_MS * index), targets, aspect) != null) fired++
        }

        val windowMs = FRAME_MS * 39
        val atMost = windowMs / HandFlick.REARM_MS + 1
        assertTrue("fired $fired times in $windowMs ms", fired in 2..atMost.toInt())
    }

    @Test
    fun leavingTheBall_reArmsItImmediately() {
        val flick = HandFlick()
        val targets = listOf(target())
        // Cross once (fires), step out, come straight back fast: fires again inside the window.
        val first = wave(flick, targets, fromX = 0.30f, stepX = 0.04f, frames = 4) // 0.30→0.42, enters at 0.38
        assertEquals(1, first.size)
        flick.evaluate(hand(0.70f, 0.5f, atMs = 1_000L + FRAME_MS * 4), targets, aspect) // out (right)
        flick.evaluate(hand(0.66f, 0.5f, atMs = 1_000L + FRAME_MS * 5), targets, aspect) // still out
        val back = flick.evaluate(hand(0.62f, 0.5f, atMs = 1_000L + FRAME_MS * 6), targets, aspect) // in again

        assertNotNull("re-entry within ${HandFlick.REARM_MS} ms must fire once the hand left", back)
        assertTrue(back!!.velocityX < 0f)
    }

    @Test
    fun aDifferentBall_isArmedEvenWhileTheFirstIsBlocked() {
        val flick = HandFlick()
        val other = StickerQuad(centerX = 0.5f, centerY = 0.8f, halfWidth = 0.15f, halfHeight = 0.1f, rotationRadians = 0f)
        val targets = listOf(target(trackingId = 1, quad = ball), target(trackingId = 2, quad = other))
        flick.evaluate(hand(0.40f, 0.5f, atMs = 1_000L), targets, aspect)
        val onFirst = flick.evaluate(hand(0.44f, 0.5f, atMs = 1_033L), targets, aspect)
        // Jump to the second ball on the very next frame, still fast.
        val onSecond = flick.evaluate(hand(0.48f, 0.8f, atMs = 1_066L), targets, aspect)

        assertEquals(1, onFirst?.trackingId)
        assertEquals(2, onSecond?.trackingId)
    }

    // ---------------------------------------------------------------- contact precedence

    @Test
    fun theFirstTargetWins_soTheRendererControlsStacking() {
        val flick = HandFlick()
        val front = target(trackingId = 9, layerIndex = 1)
        val behind = target(trackingId = 3, layerIndex = 0)
        val targets = listOf(front, behind) // same quad, front listed first
        flick.evaluate(hand(0.40f, 0.5f, atMs = 1_000L), targets, aspect)
        val impulse = flick.evaluate(hand(0.44f, 0.5f, atMs = 1_033L), targets, aspect)

        assertEquals(9, impulse?.trackingId)
        assertEquals(1, impulse?.layerIndex)
    }

    @Test
    fun aFingertipTouchingTheBall_isAContactEvenWhenThePalmIsOutside() {
        val flick = HandFlick()
        val targets = listOf(target(faceUnit = 0.1f))
        fun reaching(x: Float, atMs: Long): HandSnapshot {
            // Palm and everything else parked well left of the ball; only the index tip reaches in.
            val xs = FloatArray(HandSnapshot.LANDMARK_COUNT) { 0.10f }
            val ys = FloatArray(HandSnapshot.LANDMARK_COUNT) { 0.5f }
            xs[8] = x
            return HandSnapshot(xs, ys, aspect, atMs)
        }
        flick.evaluate(reaching(0.40f, 1_000L), targets, aspect)
        // The palm did not move, so there is contact but no velocity: honest — a poke is not a wave.
        assertNull(flick.evaluate(reaching(0.44f, 1_033L), targets, aspect))

        // Now the whole hand sweeps while the tip is inside: contact via the tip, velocity via the palm.
        fun sweeping(offset: Float, atMs: Long): HandSnapshot {
            val xs = FloatArray(HandSnapshot.LANDMARK_COUNT) { 0.10f + offset }
            val ys = FloatArray(HandSnapshot.LANDMARK_COUNT) { 0.5f }
            xs[8] = 0.44f + offset
            return HandSnapshot(xs, ys, aspect, atMs)
        }
        val impulse = flick.evaluate(sweeping(0.04f, 1_066L), targets, aspect)
        assertNotNull(impulse)
        assertEquals((0.48f - 0.5f) / 0.1f, impulse!!.leverX, 1e-4f)
    }

    // ---------------------------------------------------------------- frames of reference

    @Test
    fun aHandMeasuredOnADifferentlyShapedStream_isReframedBeforeTheHitTest() {
        // The hand's y is measured on a 16:9 stream; the ball lives on a 4:3 frame. Un-reframed,
        // y = 0.62 would fall just inside the ball (edge at 0.6); reframed by 4/3 ÷ 16/9 = 0.75
        // about the centre it lands at 0.59 — inside. The reverse case (0.64 → 0.605) is out.
        val flick = HandFlick()
        val targets = listOf(target())
        flick.evaluate(hand(0.40f, 0.62f, atMs = 1_000L, sourceAspect = 16f / 9f), targets, aspect)
        assertNotNull(flick.evaluate(hand(0.44f, 0.62f, atMs = 1_033L, sourceAspect = 16f / 9f), targets, aspect))

        val miss = HandFlick()
        miss.evaluate(hand(0.40f, 0.64f, atMs = 1_000L, sourceAspect = 16f / 9f), targets, aspect)
        assertNull(miss.evaluate(hand(0.44f, 0.64f, atMs = 1_033L, sourceAspect = 16f / 9f), targets, aspect))
    }

    @Test
    fun garbledLandmarks_neverFire() {
        val flick = HandFlick()
        val targets = listOf(target())
        flick.evaluate(hand(0.40f, 0.5f, atMs = 1_000L), targets, aspect)
        assertNull(flick.evaluate(hand(Float.NaN, 0.5f, atMs = 1_033L), targets, aspect))
        assertNull(flick.evaluate(hand(0.44f, 0.5f, atMs = 1_066L), listOf(target(faceUnit = 0f)), aspect))
    }

    @Test
    fun reset_forgetsTheHandAndTheBlock() {
        val flick = HandFlick()
        val targets = listOf(target())
        wave(flick, targets, fromX = 0.30f, stepX = 0.04f, frames = 4)
        flick.reset()
        // Same position continues, but the history is gone: first frame, no velocity.
        assertNull(flick.evaluate(hand(0.46f, 0.5f, atMs = 1_000L + FRAME_MS * 4), targets, aspect))
        // …and the block is gone too: the next moving frame fires inside the old window.
        assertNotNull(flick.evaluate(hand(0.50f, 0.5f, atMs = 1_000L + FRAME_MS * 5), targets, aspect))
    }

    private companion object {
        const val FRAME_MS = 33L
    }
}
