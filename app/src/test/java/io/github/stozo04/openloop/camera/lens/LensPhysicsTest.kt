package io.github.stozo04.openloop.camera.lens

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guard for [LensPhysics].
 *
 * **This file is the primary verification for the wobble**, not a supplement to a device run. The
 * emulator's virtual scene is a *static poster*: it can prove the lens binds, renders, and bakes
 * into a recording, but it cannot move a head, so it can never exercise a single line of the spring.
 * Every property below is therefore asserted here or nowhere.
 *
 * The tests are written as *properties* — settles, never exceeds, never diverges — rather than as
 * expected numbers, so retuning a lens's feel cannot silently break the guarantees that keep a
 * detector glitch from throwing art across the frame.
 */
class LensPhysicsTest {

    private val tolerance = 1e-4f

    /** The spec Twisted Tongue actually ships. Properties that matter are asserted against it. */
    private val tongue = WobbleSpec(
        stiffness = 150f,
        damping = 6f,
        drive = 1.2f,
        limitRadians = 0.22f,
    )

    /** 30 fps, the rate the analysis stream and preview actually run at. */
    private val frame = 1f / 30f

    private fun run(
        state: LensPhysics.Wobble,
        frames: Int,
        shift: Float = 0f,
        dt: Float = frame,
        spec: WobbleSpec = tongue,
    ): LensPhysics.Wobble {
        var current = state
        repeat(frames) { current = LensPhysics.step(current, shift, dt, spec) }
        return current
    }

    // ---------------------------------------------------------------- rest

    @Test
    fun aStillHeadLeavesTheSpringAtRest() {
        val settled = run(LensPhysics.Wobble.REST, frames = 120)

        assertEquals(0f, settled.offsetRadians, tolerance)
        assertEquals(0f, settled.velocity, tolerance)
    }

    @Test
    fun aZeroLengthStepChangesNothing() {
        val displaced = LensPhysics.Wobble(offsetRadians = 0.1f, velocity = 0.4f)

        assertEquals(displaced, LensPhysics.step(displaced, 0.5f, dtSeconds = 0f, spec = tongue))
    }

    @Test
    fun aNegativeStepChangesNothing() {
        // Camera timestamps can step backwards across a bind; the clamp must swallow it.
        val displaced = LensPhysics.Wobble(offsetRadians = 0.1f, velocity = 0.4f)

        assertEquals(displaced, LensPhysics.step(displaced, 0.5f, dtSeconds = -0.02f, spec = tongue))
    }

    // ---------------------------------------------------------------- the lag

    @Test
    fun thePivotMovingOneWayThrowsTheMassTheOther() {
        // This is the whole illusion: the head slides right, the tongue trails left.
        val swung = LensPhysics.step(LensPhysics.Wobble.REST, pivotShiftInUnits = 0.2f, frame, tongue)

        assertTrue("expected a negative swing, got ${swung.offsetRadians}", swung.offsetRadians < 0f)
    }

    @Test
    fun theSwingGrowsWithHowFarTheHeadMoved() {
        val small = LensPhysics.step(LensPhysics.Wobble.REST, 0.05f, frame, tongue)
        val large = LensPhysics.step(LensPhysics.Wobble.REST, 0.20f, frame, tongue)

        assertTrue(abs(large.offsetRadians) > abs(small.offsetRadians))
    }

    @Test
    fun aMirroredHeadMovementProducesAMirroredSwing() {
        val right = LensPhysics.step(LensPhysics.Wobble.REST, 0.18f, frame, tongue)
        val left = LensPhysics.step(LensPhysics.Wobble.REST, -0.18f, frame, tongue)

        assertEquals(-right.offsetRadians, left.offsetRadians, tolerance)
    }

    @Test
    fun aWeldedPartNeverMoves() {
        val welded = tongue.copy(drive = 0f)

        val swung = LensPhysics.step(LensPhysics.Wobble.REST, pivotShiftInUnits = 5f, frame, welded)

        assertEquals(0f, swung.offsetRadians, tolerance)
    }

    // ---------------------------------------------------------------- settling

    @Test
    fun theSwingReturnsToRestOnceTheHeadStops() {
        val swung = run(LensPhysics.Wobble.REST, frames = 4, shift = 0.15f)
        assertTrue("the setup must actually swing it", abs(swung.offsetRadians) > 0.01f)

        val settled = run(swung, frames = 150)

        assertEquals(0f, settled.offsetRadians, 1e-3f)
        assertEquals(0f, settled.velocity, 1e-2f)
    }

    @Test
    fun settleIsAStepWithNoPivotMovement() {
        val swung = LensPhysics.Wobble(offsetRadians = 0.12f, velocity = -0.3f)

        assertEquals(
            LensPhysics.step(swung, pivotShiftInUnits = 0f, dtSeconds = frame, spec = tongue),
            LensPhysics.settle(swung, dtSeconds = frame, spec = tongue),
        )
    }

    @Test
    fun losingTheFaceDecaysTheSwingRatherThanSnappingIt() {
        // FaceTracker rides out a dropped frame for 350ms; a part that teleports home on every
        // blink is more visible than one that keeps swinging for a beat.
        val swung = LensPhysics.Wobble(offsetRadians = 0.18f, velocity = 0f)

        val oneFrameLater = LensPhysics.settle(swung, frame, tongue)

        assertTrue("must still be swung", abs(oneFrameLater.offsetRadians) > 0.05f)
        assertTrue("must be heading home", abs(oneFrameLater.offsetRadians) < 0.18f)
    }

    @Test
    fun theShippedTongueIsUnderdampedSoItActuallyFlops() {
        // A critically-damped tongue slides home and reads as rubber. It has to overshoot at least
        // once — that bounce IS the joke. Guards a damping retune that quietly kills the effect.
        val start = LensPhysics.Wobble(offsetRadians = -0.2f, velocity = 0f)

        var current = start
        var crossedZero = false
        repeat(60) {
            val next = LensPhysics.settle(current, frame, tongue)
            if (current.offsetRadians < 0f && next.offsetRadians > 0f) crossedZero = true
            current = next
        }

        assertTrue("the tongue must overshoot rest at least once", crossedZero)
    }

    // ---------------------------------------------------------------- the safety properties

    @Test
    fun theSwingNeverExceedsItsLimit() {
        var current = LensPhysics.Wobble.REST
        // An absurd shift every frame: a whole face-width per frame, alternating direction.
        repeat(400) { index ->
            current = LensPhysics.step(current, if (index % 2 == 0) 8f else -8f, frame, tongue)
            assertTrue(
                "swing ${current.offsetRadians} escaped the ${tongue.limitRadians} limit",
                abs(current.offsetRadians) <= tongue.limitRadians + tolerance,
            )
        }
    }

    @Test
    fun hittingTheStopKillsTheVelocityIntoIt() {
        // Without this the clamp holds the offset while the spring keeps winding up, and the art
        // snaps back a beat later for no visible reason.
        val pinned = LensPhysics.step(LensPhysics.Wobble.REST, pivotShiftInUnits = 10f, frame, tongue)

        assertEquals(-tongue.limitRadians, pinned.offsetRadians, tolerance)
        assertTrue("velocity into the stop must be dropped", pinned.velocity >= 0f)
    }

    @Test
    fun aLongFrameGapCannotExplodeTheSpring() {
        // Explicit integration over an unclamped gap is exactly how a spring goes to infinity.
        // A one-second stall must be survivable, because a paused preview produces one.
        val afterStall = LensPhysics.step(LensPhysics.Wobble.REST, 0.3f, dtSeconds = 1f, spec = tongue)

        assertTrue("offset went non-finite", afterStall.offsetRadians.isFinite())
        assertTrue("velocity went non-finite", afterStall.velocity.isFinite())
        assertTrue(abs(afterStall.offsetRadians) <= tongue.limitRadians + tolerance)
    }

    @Test
    fun theShippedSpecIsStableAtTheLongestStepItWillEverTake() {
        // The real stability guarantee. Explicit Euler diverges above dt = 2/sqrt(stiffness);
        // this asserts the shipped spec plus the shipped clamp stay the right side of that.
        val settled = run(
            LensPhysics.Wobble(offsetRadians = tongue.limitRadians, velocity = 0f),
            frames = 500,
            dt = LensPhysics.MAX_STEP_SECONDS,
        )

        assertTrue(settled.offsetRadians.isFinite())
        assertEquals("a stable spring must come home", 0f, settled.offsetRadians, 1e-2f)
    }

    @Test
    fun everyShippedWobbleSpecIsStableUnderTheClamp() {
        // Catalogue-driven, so a new lens with a too-stiff spring fails here rather than on a phone.
        Lens.entries.flatMap { it.art }.mapNotNull { it.placement.wobble }.forEach { spec ->
            val settled = run(
                LensPhysics.Wobble(offsetRadians = spec.limitRadians, velocity = 0f),
                frames = 600,
                dt = LensPhysics.MAX_STEP_SECONDS,
                spec = spec,
            )
            assertTrue("$spec diverged", settled.offsetRadians.isFinite())
            assertEquals("$spec never settles", 0f, settled.offsetRadians, 2e-2f)
            assertTrue("$spec needs a positive limit", spec.limitRadians > 0f)
            assertTrue("$spec needs positive stiffness", spec.stiffness > 0f)
            assertTrue("$spec needs non-negative damping", spec.damping >= 0f)
        }
    }

    @Test
    fun aGarbledDriveIsIgnoredRatherThanPoisoningTheState() {
        // A NaN here would reach the vertex buffer and erase the whole quad, so the art would
        // vanish with nothing in logcat to explain it.
        val swung = LensPhysics.Wobble(offsetRadians = 0.1f, velocity = 0.2f)

        assertEquals(swung, LensPhysics.step(swung, Float.NaN, frame, tongue))
        assertEquals(swung, LensPhysics.step(swung, Float.POSITIVE_INFINITY, frame, tongue))
    }
}
