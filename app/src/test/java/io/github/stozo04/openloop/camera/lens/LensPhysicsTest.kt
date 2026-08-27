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

    // ================================================================ the flick spin
    // PRD-lens-interactions §3.4. Like the wobble above, the JVM is the primary verification:
    // the emulator's poster face can show a spin happening, but only these properties prove the
    // impulse direction, the cap, and that every spin lands on an exact whole revolution.

    private val twoPi = (2.0 * Math.PI).toFloat()

    /** The spec Football ships. */
    private val football = Lens.Football.art.first().placement.spin!!

    /** Steps a spin to REST (or gives up), returning every intermediate state. */
    private fun spinOut(start: LensPhysics.Spin, spec: SpinSpec = football): List<LensPhysics.Spin> {
        val states = mutableListOf(start)
        var current = start
        repeat(1000) {
            if (current == LensPhysics.Spin.REST) return states
            current = LensPhysics.spinStep(current, frame, spec)
            states.add(current)
        }
        return states
    }

    private fun flick(velocityX: Float, leverY: Float = -1f): LensPhysics.Spin =
        LensPhysics.spinImpulse(
            LensPhysics.Spin.REST,
            leverX = 0f,
            leverY = leverY,
            velocityX = velocityX,
            velocityY = 0f,
            spec = football,
        )

    @Test
    fun flickingTheTopOfTheBallRightward_spinsItClockwise() {
        // y is down, so the top of the ball is negative leverY, and clockwise is positive angle —
        // the renderer's own convention (LensAnchor.sticker).
        assertTrue(flick(velocityX = 4f, leverY = -1f).velocity > 0f)
    }

    @Test
    fun flickingTheBottomOfTheBallRightward_spinsItTheOtherWay() {
        assertTrue(flick(velocityX = 4f, leverY = 1f).velocity < 0f)
    }

    @Test
    fun aHarderFlick_spinsFasterAndTravelsFurther() {
        val soft = flick(velocityX = 2f)
        val hard = flick(velocityX = 6f)
        assertTrue(abs(hard.velocity) > abs(soft.velocity))

        val softTravel = spinOut(soft).maxOf { abs(it.angleRadians) }
        val hardTravel = spinOut(hard).maxOf { abs(it.angleRadians) }
        assertTrue("harder flick must travel further", hardTravel > softTravel)
    }

    @Test
    fun aSecondFlickAddsItsImpulse_soRepeatedFlicksPumpTheSpinUp() {
        val once = flick(velocityX = 3f)
        val twice = LensPhysics.spinImpulse(once, 0f, -1f, 3f, 0f, football)

        assertTrue(twice.velocity > once.velocity)
    }

    @Test
    fun theVelocityCapHoldsUnderAnAbsurdFling() {
        val flung = flick(velocityX = 100_000f)

        assertEquals(football.maxAngularVelocity, flung.velocity, tolerance)
    }

    @Test
    fun frictionHalvesTheSpeedInItsHalfLife_atAnyFrameRate() {
        // The half-life form is exactly frame-rate independent: the same wall-clock time must
        // yield the same velocity whether it was stepped at 30 or 60 fps.
        val start = LensPhysics.Spin(angleRadians = 0f, velocity = 20f)
        val halfLife = football.frictionHalfLifeSeconds

        var at30 = start
        repeat(30) { at30 = LensPhysics.spinStep(at30, halfLife / 30f, football) }
        var at60 = start
        repeat(60) { at60 = LensPhysics.spinStep(at60, halfLife / 60f, football) }

        assertEquals(10f, at30.velocity, 1e-2f)
        assertEquals(at30.velocity, at60.velocity, 1e-2f)
    }

    @Test
    fun everySpinLandsOnAnExactWholeRevolution() {
        // The load-bearing landing guarantee (owner decision D2): the features reappear exactly
        // where they vanished only because the angle always comes home to a multiple of 2π.
        for (velocity in floatArrayOf(1f, 3f, 7.5f, -4f, 18f, -25f)) {
            val states = spinOut(LensPhysics.Spin(0f, velocity))
            assertEquals("a spin at $velocity rad/s must land", LensPhysics.Spin.REST, states.last())

            // The last angle before the snap to REST sits within a whisker of a whole turn.
            val beforeRest = states[states.size - 2].angleRadians
            val offTarget = abs(beforeRest - Math.round(beforeRest / twoPi) * twoPi)
            assertTrue("landed $offTarget rad off a whole revolution", offTarget < 5e-3f)
        }
    }

    @Test
    fun theLandingNeverUnwinds_moreThanHalfATurn() {
        for (velocity in floatArrayOf(2f, 5f, 13f)) {
            val states = spinOut(LensPhysics.Spin(0f, velocity))
            val peak = states.maxOf { it.angleRadians }
            val landedAt = states[states.size - 2].angleRadians
            assertTrue(
                "landing gave back ${peak - landedAt} rad",
                peak - landedAt <= Math.PI.toFloat() + tolerance,
            )
        }
    }

    @Test
    fun theLandingGlides_itNeverTeleportsTheAngle() {
        val states = spinOut(LensPhysics.Spin(0f, 9f))
        states.zipWithNext { a, b ->
            // REST's angle 0 stands for the whole revolution it landed on; measure that hop mod 2π.
            val hop = abs(b.angleRadians - a.angleRadians)
            val hopModTurn = minOf(hop % twoPi, twoPi - hop % twoPi)
            assertTrue("angle jumped $hopModTurn rad in one frame", hopModTurn < 1f)
        }
    }

    @Test
    fun aFlickNearTheDeadCenter_cannotBlowUp() {
        // The lever floor bounds the torque division; a tiny lever means a tiny cross product, so
        // the spin comes out small and finite, never infinite.
        val nudged = LensPhysics.spinImpulse(
            LensPhysics.Spin.REST,
            leverX = 0f, leverY = -0.01f, velocityX = 8f, velocityY = 0f,
            spec = football,
        )

        assertTrue(nudged.velocity.isFinite())
        assertTrue(abs(nudged.velocity) <= football.maxAngularVelocity)
    }

    @Test
    fun anExactlyDeadCenterFlick_hasNoTorque() {
        val through = LensPhysics.spinImpulse(
            LensPhysics.Spin.REST,
            leverX = 0f, leverY = 0f, velocityX = 8f, velocityY = 0f,
            spec = football,
        )

        assertEquals(0f, through.velocity, tolerance)
    }

    @Test
    fun aGarbledFlick_leavesTheSpinUntouched() {
        val spinning = LensPhysics.Spin(1f, 6f)

        assertEquals(spinning, LensPhysics.spinImpulse(spinning, Float.NaN, 0f, 1f, 1f, football))
        assertEquals(
            spinning,
            LensPhysics.spinImpulse(spinning, 0f, 1f, Float.POSITIVE_INFINITY, 0f, football),
        )
    }

    @Test
    fun aLongFrameGap_cannotTeleportTheSpin() {
        val stalled = LensPhysics.spinStep(LensPhysics.Spin(0f, 20f), dtSeconds = 5f, football)

        assertTrue(stalled.angleRadians.isFinite())
        assertTrue(
            "one clamped step can advance at most maxVelocity × MAX_STEP",
            abs(stalled.angleRadians) <= football.maxAngularVelocity * LensPhysics.MAX_STEP_SECONDS + tolerance,
        )
    }

    @Test
    fun aZeroOrNegativeStep_changesNothing() {
        val spinning = LensPhysics.Spin(0.5f, 4f)

        assertEquals(spinning, LensPhysics.spinStep(spinning, 0f, football))
        assertEquals(spinning, LensPhysics.spinStep(spinning, -0.02f, football))
    }

    @Test
    fun everyShippedSpinSpecLands_andACommonFlickTravelsAboutOneRevolution() {
        // The interaction question is answered per lens (owner rule 2026-08-26) and must match
        // the layers: a SPIN lens with nothing to spin is dead, a NONE lens with a spin spec is a
        // decision made by omission. Either way this fails before a phone sees it.
        Lens.entries.forEach { lens ->
            val declaredSpin = lens.interaction == LensInteraction.SPIN
            assertEquals("$lens: interaction must match its layers", declaredSpin, lens.art.any { it.placement.spin != null })
            assertEquals("$lens: isFlickable is the declared decision", declaredSpin, lens.isFlickable)
        }
        assertEquals(
            "the lenses the owner asked for (2026-08-26)",
            setOf(Lens.Broccoli, Lens.PizzaFace, Lens.Football),
            Lens.entries.filter { it.isFlickable }.toSet(),
        )

        // Catalogue-driven like the wobble check: a new lens with a runaway spin spec fails here
        // rather than on a phone. The travel band pins the tuning INTENT (PRD §3.4: comfortable
        // flick ≈ one revolution) loosely enough that feel-tuning stays a one-line edit.
        Lens.entries.flatMap { it.art }.mapNotNull { it.placement.spin }.forEach { spec ->
            assertTrue("$spec needs positive gain", spec.gain > 0f)
            assertTrue("$spec needs a positive half-life", spec.frictionHalfLifeSeconds > 0f)
            assertTrue("$spec needs a positive cap", spec.maxAngularVelocity > 0f)
            // A zero threshold would let a hand resting on the art spin it from detector jitter;
            // an absurd one would make the verb unreachable. Face units/s — a wave is ~5–9.
            assertTrue("$spec needs a hand-speed threshold a wave can clear", spec.minHandSpeed in 0.5f..8f)

            val comfortable = LensPhysics.spinImpulse(
                LensPhysics.Spin.REST,
                leverX = 0f, leverY = -1f, velocityX = 5f, velocityY = 0f,
                spec = spec,
            )
            val states = spinOut(comfortable, spec)
            assertEquals("$spec never lands", LensPhysics.Spin.REST, states.last())
            val revolutions = states.maxOf { abs(it.angleRadians) } / twoPi
            assertTrue("$spec travels $revolutions rev on a comfortable flick", revolutions in 0.5f..2.5f)

            val flung = spinOut(LensPhysics.Spin(0f, spec.maxAngularVelocity), spec)
            assertEquals("$spec never lands from its cap", LensPhysics.Spin.REST, flung.last())
        }
    }
}
