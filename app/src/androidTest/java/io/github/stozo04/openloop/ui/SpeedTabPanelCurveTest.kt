package io.github.stozo04.openloop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.down
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import io.github.stozo04.openloop.media.SpeedCurve
import io.github.stozo04.openloop.media.SpeedKey
import io.github.stozo04.openloop.ui.components.GRAPH_INSET
import io.github.stozo04.openloop.ui.components.SpeedGraphGeometry
import io.github.stozo04.openloop.ui.components.SpeedTabPanel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Compose coverage for the Speed tab's `Constant | Curve` toggle (PRD-speed-curves.md §7).
 *
 * Drives the stateless [SpeedTabPanel] directly, hoisting the mode into the test the way the
 * ViewModel does — no ViewModel, no player, no codec (Lesson 017: `androidTest` has no mockk, so
 * state lives in the test).
 */
class SpeedTabPanelCurveTest {

    // v1 rule kept deliberately (matches the rest of the suite); the v2 variant flips the test
    // dispatcher and is a separate migration (see OpenLoopNavHostTest).
    @Suppress("DEPRECATION")
    @get:Rule
    val composeTestRule = createComposeRule()

    /** Hosts the panel with the same `curve == null means Constant` contract the ViewModel uses. */
    private fun setContent(
        initialCurve: SpeedCurve? = null,
        hasSeenIntro: Boolean = true,
        onFlattenOverride: (() -> Unit)? = null,
        scrubs: MutableList<Float?>? = null,
        playheadFraction: () -> Float = { 0f },
        lockedSpeeds: MutableList<Float>? = null,
    ): () -> SpeedCurve? {
        var curve by mutableStateOf(initialCurve)
        var speed by mutableStateOf(2.0f)
        composeTestRule.setContent {
            SpeedTabPanel(
                speed = speed,
                onSpeedChange = { speed = it },
                curve = curve,
                playheadFraction = playheadFraction,
                loopDurationMs = 4_000L,
                hasSeenCurveIntro = hasSeenIntro,
                onEnterCurveMode = { curve = SpeedCurve.flat(speed) },
                onCurveChange = { curve = it },
                onFlattenCurve = {
                    onFlattenOverride?.invoke()
                    curve?.let { speed = it.flatten() }
                    curve = null
                },
                onUseCurrentAsConstant = { curve = null; speed = it; lockedSpeeds?.add(it) },
                onScrubToFraction = { scrubs?.add(it) },
            )
        }
        return { curve }
    }

    @Test
    fun constantIsTheDefaultAndShowsTheSlider() {
        setContent()
        composeTestRule.onNodeWithTag("speed_slider").assertIsDisplayed()
        composeTestRule.onNodeWithTag("speed_current_pill").assertIsDisplayed()
        composeTestRule.onNodeWithTag("speed_curve_graph").assertDoesNotExist()
    }

    @Test
    fun tappingCurveSwitchesToTheGraphAndSeedsFromTheConstantSpeed() {
        val curve = setContent()
        composeTestRule.onNodeWithTag("speed_mode_curve").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("speed_curve_graph").assertIsDisplayed()
        composeTestRule.onNodeWithTag("speed_slider").assertDoesNotExist()

        // Seeded flat at the previous constant so the preview does not jump (PRD §4.1).
        val seeded = curve()
        assertNotNull(seeded)
        assertTrue("expected a flat seed, got $seeded", seeded!!.isFlat)
        assertEquals(2.0f, seeded.speedAt(0.5f), 1e-4f)
    }

    @Test
    fun tappingConstantReturnsToTheSliderAtTheCurvesAverage() {
        // 0.5x -> 2x ramp: harmonic mean ~1.08x, NOT the 1.25x arithmetic mean.
        val ramp = SpeedCurve(listOf(SpeedKey(0f, 0.5f), SpeedKey(1f, 2f)))
        val curve = setContent(initialCurve = ramp)

        composeTestRule.onNodeWithTag("speed_mode_constant").performClick()
        composeTestRule.waitForIdle()

        assertNull("Constant mode means a null curve", curve())
        composeTestRule.onNodeWithTag("speed_slider").assertIsDisplayed()
        composeTestRule.onNodeWithTag("speed_curve_graph").assertDoesNotExist()
    }

    @Test
    fun theCurveSurvivesTogglingAwayAndBackAsItsFlattenedConstant() {
        val ramp = SpeedCurve(listOf(SpeedKey(0f, 0.5f), SpeedKey(1f, 2f)))
        val curve = setContent(initialCurve = ramp)
        val expected = ramp.flatten()

        composeTestRule.onNodeWithTag("speed_mode_constant").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("speed_mode_curve").performClick()
        composeTestRule.waitForIdle()

        // Re-entering seeds flat at the speed flatten produced — a lossless round trip.
        val reseeded = curve()
        assertNotNull(reseeded)
        assertEquals(expected, reseeded!!.speedAt(0.5f), 1e-3f)
    }

    @Test
    fun theIntroShowsOnceOnTheFirstSwitchToCurve() {
        setContent(hasSeenIntro = false)
        composeTestRule.onNodeWithTag("speed_curve_intro").assertDoesNotExist()

        composeTestRule.onNodeWithTag("speed_mode_curve").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("speed_curve_intro").assertIsDisplayed()
    }

    @Test
    fun theIntroStaysHiddenOnceSeen() {
        setContent(hasSeenIntro = true)
        composeTestRule.onNodeWithTag("speed_mode_curve").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("speed_curve_intro").assertDoesNotExist()
    }

    @Test
    fun curveModeOffersPresetsAddPointAndResetButNoFlattenButton() {
        setContent(initialCurve = SpeedCurve.flat(1.5f))
        composeTestRule.onNodeWithTag("speed_curve_presets").assertIsDisplayed()
        composeTestRule.onNodeWithTag("speed_curve_add_point").assertIsDisplayed()
        composeTestRule.onNodeWithTag("speed_curve_reset").assertIsDisplayed()
        // Removed at the owner's request — the Constant segment carries the action (PRD R-4).
        composeTestRule.onNodeWithTag("speed_curve_flatten").assertDoesNotExist()
    }

    @Test
    fun addPointBecomesRemovePointAtTheCapAndBackAgain() {
        val curve = setContent(initialCurve = SpeedCurve.flat(1.5f))
        assertEquals(SpeedCurve.MIN_KEYS, curve()!!.keys.size)

        composeTestRule.onNodeWithTag("speed_curve_add_point").performClick()
        composeTestRule.waitForIdle()
        assertEquals(SpeedCurve.MAX_KEYS, curve()!!.keys.size)

        // At the cap the control inverts rather than going dead — that is the visible way to delete a
        // point, so it must actually be there and the add affordance must be gone.
        composeTestRule.onNodeWithTag("speed_curve_add_point").assertDoesNotExist()
        composeTestRule.onNodeWithTag("speed_curve_remove_point").assertIsDisplayed()

        composeTestRule.onNodeWithTag("speed_curve_remove_point").performClick()
        composeTestRule.waitForIdle()
        assertEquals(
            "removing must land back on the two locked ends",
            SpeedCurve.MIN_KEYS,
            curve()!!.keys.size,
        )
        assertTrue("the ends must be the survivors", curve()!!.keys.map { it.t } == listOf(0f, 1f))
        composeTestRule.onNodeWithTag("speed_curve_add_point").assertIsDisplayed()
        composeTestRule.onNodeWithTag("speed_curve_remove_point").assertDoesNotExist()
    }

    @Test
    fun aPresetReplacesTheCurveAndIsNotFlat() {
        val curve = setContent(initialCurve = SpeedCurve.flat(1.5f))
        composeTestRule.onNodeWithTag("speed_curve_presets").performClick()
        composeTestRule.waitForIdle()

        // A real click action, so TalkBack can activate a preset — not just a raw tap detector.
        composeTestRule.onNodeWithTag("speed_preset_EASE_IN").assertHasClickAction()
        composeTestRule.onNodeWithText("Ease In").performClick()
        composeTestRule.waitForIdle()

        val applied = curve()
        assertNotNull(applied)
        assertTrue("a preset must actually vary", !applied!!.isFlat)
    }

    /**
     * The "Current: N×" pill recomposes on every playhead tick of a non-flat curve. A tap handler
     * keyed on that value (`pointerInput(current)`) re-installs itself mid-press and drops the tap,
     * so locking the loop to the live speed mostly failed while the preview was playing (Lesson 034).
     * This lands a tick *between* down and up — the case a plain `performClick` never exercises.
     */
    @Test
    fun tappingCurrentLocksTheLiveSpeedEvenWhenThePlayheadTicksMidPress() {
        val playhead = mutableFloatStateOf(0.25f)
        val ramp = SpeedCurve(listOf(SpeedKey(0f, 0.5f), SpeedKey(1f, 2f)))
        val locked = mutableListOf<Float>()
        val curve = setContent(
            initialCurve = ramp,
            playheadFraction = { playhead.floatValue },
            lockedSpeeds = locked,
        )
        val pill = composeTestRule.onNodeWithTag("speed_curve_current")
        pill.assertHasClickAction()

        pill.performTouchInput { down(center) }
        playhead.floatValue = 0.75f
        composeTestRule.waitForIdle()
        pill.performTouchInput { up() }
        composeTestRule.waitForIdle()

        assertNull("the tap must survive the tick and leave Curve mode", curve())
        // And it locks the speed under the playhead *now*, not the one captured at first composition.
        assertEquals(listOf(ramp.speedAt(0.75f)), locked)
    }

    @Test
    fun resetFlattensTheCurveWithoutLeavingCurveMode() {
        val ramp = SpeedCurve(listOf(SpeedKey(0f, 0.5f), SpeedKey(1f, 2f)))
        val curve = setContent(initialCurve = ramp)

        composeTestRule.onNodeWithTag("speed_curve_reset").performClick()
        composeTestRule.waitForIdle()

        val reset = curve()
        assertNotNull("Reset must stay in Curve mode", reset)
        assertTrue("Reset must produce a flat curve", reset!!.isFlat)
        // Flat at the average, so the loop's length does not jump.
        assertEquals(ramp.flatten(), reset.speedAt(0.5f), 1e-3f)
        composeTestRule.onNodeWithTag("speed_curve_graph").assertIsDisplayed()
    }

    /**
     * The graph's gesture detectors are keyed on `sizePx`, which settles at first layout and then never
     * changes — so everything they capture is frozen there unless it is read through
     * `rememberUpdatedState` (Lesson 034). A drag that emitted the *captured* curve would silently
     * revert any edit made since, which is why this drags **after** changing the curve's shape rather
     * than from a pristine one: dragging a fresh curve looks identical whether or not the capture is stale.
     */
    @Test
    fun draggingAHandleActsOnTheLatestCurveNotTheOneCapturedAtFirstComposition() {
        val scrubs = mutableListOf<Float?>()
        val curve = setContent(initialCurve = SpeedCurve.flat(1.5f), scrubs = scrubs)

        // Add the interior point first, so the curve the detectors must act on differs from the one
        // present when they were installed. Keys become 0 / 0.5 / 1.0, all at 1.5x.
        composeTestRule.onNodeWithTag("speed_curve_add_point").performClick()
        composeTestRule.waitForIdle()
        assertEquals(3, curve()!!.keys.size)

        // Press exactly where the production geometry puts the t=0.5 handle rather than guessing at a
        // screen position — the hit test is a tolerance in graph space, not in pixels.
        val insetPx = with(composeTestRule.density) { GRAPH_INSET.toPx() }
        composeTestRule.onNodeWithTag("speed_curve_graph").performTouchInput {
            val geo = SpeedGraphGeometry(width.toFloat(), height.toFloat(), insetPx)
            val handle = Offset(geo.xOf(0.5f), geo.yOf(1.5f))
            down(handle)
            repeat(6) { step -> moveTo(Offset(handle.x, handle.y + height * 0.04f * (step + 1))) }
            up()
        }
        composeTestRule.waitForIdle()

        val dragged = curve()!!
        // Drag start parks the preview on the handle; release hands playback back to the loop.
        assertTrue("drag start must scrub to the handle (scrubs=$scrubs)", scrubs.isNotEmpty())
        assertNull("releasing must resume the loop (scrubs=$scrubs)", scrubs.last())
        assertEquals("a stale capture would resurrect the pre-add 2-key curve", 3, dragged.keys.size)
        assertTrue(
            "dragging down must slow the handle, got ${dragged.keys.map { it.speed }} scrubs=$scrubs",
            dragged.keys[1].speed < 1.5f,
        )
    }

    /**
     * The third gesture the intro dialog promises ("Hold a point to remove it"), and the last
     * `onCurveChange` call site inside the graph's long-lived detectors. A stationary hold should never
     * reach the drag detector — but "it should never" is exactly the reasoning that missed both
     * Lesson 034 and Lesson 035 in this same file, so it gets a test rather than an argument.
     */
    @Test
    fun longPressingAHandleRemovesIt() {
        val curve = setContent(
            initialCurve = SpeedCurve(
                listOf(SpeedKey(0f, 0.5f), SpeedKey(0.5f, 2f), SpeedKey(1f, 0.5f)),
            ),
        )
        val insetPx = with(composeTestRule.density) { GRAPH_INSET.toPx() }

        composeTestRule.onNodeWithTag("speed_curve_graph").performTouchInput {
            val geo = SpeedGraphGeometry(width.toFloat(), height.toFloat(), insetPx)
            longClick(Offset(geo.xOf(0.5f), geo.yOf(2f)))
        }
        composeTestRule.waitForIdle()

        val remaining = curve()!!
        assertEquals("the held interior point must be gone", 2, remaining.keys.size)
        assertTrue("the endpoints must survive", remaining.keys.map { it.t } == listOf(0f, 1f))
    }

    /** Endpoints anchor the loop, so holding one must be refused rather than shortening the curve. */
    @Test
    fun longPressingAnEndpointLeavesTheCurveAlone() {
        val ramp = SpeedCurve(listOf(SpeedKey(0f, 0.5f), SpeedKey(0.5f, 2f), SpeedKey(1f, 0.5f)))
        val curve = setContent(initialCurve = ramp)
        val insetPx = with(composeTestRule.density) { GRAPH_INSET.toPx() }

        composeTestRule.onNodeWithTag("speed_curve_graph").performTouchInput {
            val geo = SpeedGraphGeometry(width.toFloat(), height.toFloat(), insetPx)
            longClick(Offset(geo.xOf(0f), geo.yOf(0.5f)))
        }
        composeTestRule.waitForIdle()

        assertEquals(3, curve()!!.keys.size)
    }

    @Test
    fun everyKeyframeIsReachableByScreenReader() {
        val ramp = SpeedCurve(
            listOf(SpeedKey(0f, 0.5f), SpeedKey(0.5f, 2f), SpeedKey(1f, 1f)),
        )
        setContent(initialCurve = ramp)
        // A gesture-only Canvas would leave a TalkBack user with no way to reach a point at all.
        repeat(ramp.keys.size) { index ->
            composeTestRule.onNodeWithTag("speed_curve_point_$index").assertExists()
        }
    }
}
