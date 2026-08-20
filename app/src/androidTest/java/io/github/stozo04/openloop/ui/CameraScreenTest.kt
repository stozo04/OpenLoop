package io.github.stozo04.openloop.ui

import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for slice 01's capture controls — the [ShutterButton] toggle, the progress
 * ring, and the [RecordingCountdownChip].
 *
 * These drive the hoisted, stateless composables directly (mirroring [OnboardingNavigationTest])
 * rather than the full [CameraScreen], which binds the camera and needs a real CameraManager.
 */
@RunWith(AndroidJUnit4::class)
class CameraScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ── Shutter button: tap toggles idle ↔ recording glyph ──

    @Test
    fun shutterButton_tap_togglesBetweenStartAndStop() {
        composeTestRule.setContent {
            var recording by remember { mutableStateOf(false) }
            ShutterButton(
                isRecording = recording,
                progressFraction = { if (recording) 0.25f else 0f },
                onClick = { recording = !recording }
            )
        }

        // Idle: shows the "Start recording" glyph and no ring.
        composeTestRule.onNodeWithContentDescription("Start recording").assertIsDisplayed()
        composeTestRule.onNodeWithTag("progress_ring").assertDoesNotExist()

        // Tap → recording: glyph flips to "Stop recording" and the ring appears.
        composeTestRule.onNodeWithContentDescription("Start recording").performClick()
        composeTestRule.onNodeWithContentDescription("Stop recording").assertIsDisplayed()
        composeTestRule.onNodeWithTag("progress_ring").assertIsDisplayed()

        // Tap again → back to idle: ring gone.
        composeTestRule.onNodeWithContentDescription("Stop recording").performClick()
        composeTestRule.onNodeWithContentDescription("Start recording").assertIsDisplayed()
        composeTestRule.onNodeWithTag("progress_ring").assertDoesNotExist()
    }

    // ── Progress ring: visible only while recording ──

    @Test
    fun progressRing_isHidden_whenIdle() {
        composeTestRule.setContent {
            ShutterButton(isRecording = false, progressFraction = { 0f }, onClick = {})
        }
        composeTestRule.onNodeWithTag("progress_ring").assertDoesNotExist()
    }

    @Test
    fun progressRing_isVisible_whenRecording() {
        composeTestRule.setContent {
            ShutterButton(isRecording = true, progressFraction = { 0.5f }, onClick = {})
        }
        composeTestRule.onNodeWithTag("progress_ring").assertIsDisplayed()
    }

    // ── Countdown chip: hidden in idle, shows the supplied MM:SS / 00:30 text while recording ──

    @Test
    fun countdownChip_isHidden_whenNotRecording() {
        composeTestRule.setContent {
            RecordingCountdownChip(visible = false, text = { "00:05 / 00:30" })
        }
        composeTestRule.onNodeWithTag("countdown_chip").assertDoesNotExist()
    }

    @Test
    fun countdownChip_showsElapsedAndCapText_whenRecording() {
        composeTestRule.setContent {
            RecordingCountdownChip(visible = true, text = { "00:05 / 00:30" })
        }
        composeTestRule.onNodeWithTag("countdown_chip").assertIsDisplayed()
        composeTestRule.onNodeWithText("00:05 / 00:30").assertIsDisplayed()
    }

    // ── Home button: meets the 48dp minimum touch target (WARNING-3) ──

    @Test
    fun homeButton_meetsMinimumTouchTarget() {
        composeTestRule.setContent {
            HomeButton(onClick = {})
        }
        // Material/accessibility minimum interactive target is 48x48dp; the button was 44dp.
        composeTestRule.onNodeWithContentDescription("Gallery")
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun homeButton_bounceCompletesOnce() {
        var completions = 0
        composeTestRule.setContent {
            HomeButton(
                onClick = {},
                bounce = true,
                onBounceFinished = { completions++ },
            )
        }

        composeTestRule.waitUntil(timeoutMillis = 5_000) { completions == 1 }
        composeTestRule.runOnIdle { assertEquals(1, completions) }
    }

    // ── Zoom ratio chip: hidden by default, shows the live ratio, lingers after the pinch ──

    @Test
    fun zoomChip_isHidden_whenNotVisible() {
        composeTestRule.setContent {
            ZoomRatioChip(visible = false, text = { "2.3x" })
        }
        composeTestRule.onNodeWithTag("zoom_chip").assertDoesNotExist()
    }

    @Test
    fun zoomChip_showsRatioText_whenVisible() {
        composeTestRule.setContent {
            ZoomRatioChip(visible = true, text = { "2.3x" })
        }
        composeTestRule.onNodeWithTag("zoom_chip").assertIsDisplayed()
        composeTestRule.onNodeWithText("2.3x").assertIsDisplayed()
        // Informational semantics only (never a touch target) — and TalkBack must announce the
        // live value, not just "Zoom level".
        composeTestRule.onNodeWithContentDescription("Zoom level, 2.3x").assertIsDisplayed()
    }

    /**
     * Drives [rememberZoomChipVisible] through a full gesture: appear on pinch start, linger after
     * the pinch ends ([ZOOM_CHIP_LINGER_MS]), then fade away. The linger `delay` runs on the real
     * effect dispatcher (the Compose test clock does not drive `LaunchedEffect` delays), so the
     * disappearance is awaited with [androidx.compose.ui.test.junit4.ComposeTestRule.waitUntil]
     * rather than clock advancement; the "still visible" assertion runs immediately after the
     * gesture ends, well inside the 1 s window.
     */
    @Test
    fun zoomChip_lingersAfterPinchEnds_thenFades() {
        val pinchInProgress = mutableStateOf(false)
        val pinchEndCount = mutableIntStateOf(0)
        composeTestRule.setContent {
            ZoomRatioChip(
                visible = rememberZoomChipVisible(pinchInProgress.value, pinchEndCount.intValue),
                text = { "1.5x" }
            )
        }

        // Idle: no chip.
        composeTestRule.onNodeWithTag("zoom_chip").assertDoesNotExist()

        // Pinch begins → chip appears.
        composeTestRule.runOnIdle { pinchInProgress.value = true }
        composeTestRule.onNodeWithTag("zoom_chip").assertIsDisplayed()

        // Pinch ends → chip lingers…
        composeTestRule.runOnIdle {
            pinchInProgress.value = false
            pinchEndCount.intValue++
        }
        composeTestRule.onNodeWithTag("zoom_chip").assertIsDisplayed()

        // …then is gone once the linger window and fade-out elapse.
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag("zoom_chip").fetchSemanticsNodes().isEmpty()
        }
    }

    // ── Regression: camera must NOT remount on the ReadyToCapture → Recording transition ──

    /**
     * Guards the [CameraScreenHost] fix for the `ERROR_SOURCE_INACTIVE` bug. The host's content —
     * which on the real screen runs `CameraScreen`'s `startCamera()` in a `LaunchedEffect` — must
     * mount exactly ONCE across the ReadyToCapture → Recording transition. If the two states were
     * routed through separate call sites, Compose would dispose and rebuild the content, re-running
     * the camera init and unbinding the in-flight recording. Here a `LaunchedEffect(Unit)` counter
     * stands in for that init: it must read 1, not 2, after flipping the state to Recording.
     */
    @Test
    fun cameraScreenHost_keepsContentMounted_acrossCaptureTransition() {
        var initCount = 0
        composeTestRule.setContent {
            var state by remember {
                mutableStateOf<OpenLoopUiState>(OpenLoopUiState.ReadyToCapture)
            }
            CameraScreenHost(uiState = state) {
                LaunchedEffect(Unit) { initCount++ } // stand-in for startCamera()
                Text(
                    text = "camera",
                    modifier = Modifier
                        .testTag("host_content")
                        .clickable { state = OpenLoopUiState.Recording }
                )
            }
        }

        composeTestRule.onNodeWithTag("host_content").assertIsDisplayed()
        composeTestRule.runOnIdle { assertEquals(1, initCount) }

        // Flip ReadyToCapture → Recording. Same call site → content stays mounted, init stays 1.
        composeTestRule.onNodeWithTag("host_content").performClick()
        composeTestRule.onNodeWithTag("host_content").assertIsDisplayed()
        composeTestRule.runOnIdle {
            assertEquals(
                "startCamera()-equivalent must run once across the capture transition, not per state",
                1,
                initCount
            )
        }
    }

    // ── Capture-mode selector (issue #126 — supersedes the PRD §5.2 single toggling icon) ──

    @Test
    fun captureModeSelector_showsCurrentMode_andFlipsOnTap() {
        // The active segment IS the state readout, and it never sits under the finger that taps
        // the other segment — the core issue-#126 fix.
        composeTestRule.setContent {
            var mode by remember { mutableStateOf(CaptureMode.VIDEO) }
            CaptureModeSelector(photoMode = mode == CaptureMode.PHOTO, onSelect = { mode = it })
        }

        composeTestRule.onNodeWithTag("capture_mode_selector").assertIsDisplayed()
        composeTestRule.onNodeWithText("Video").assertIsSelected()
        composeTestRule.onNodeWithText("Camera").assertIsNotSelected()

        composeTestRule.onNodeWithText("Camera").performClick()
        composeTestRule.onNodeWithText("Camera").assertIsSelected()
        composeTestRule.onNodeWithText("Video").assertIsNotSelected()

        composeTestRule.onNodeWithText("Video").performClick()
        composeTestRule.onNodeWithText("Video").assertIsSelected()
        composeTestRule.onNodeWithText("Camera").assertIsNotSelected()
    }

    @Test
    fun captureModeSelector_tapNamesTargetMode_andSelectedSegmentIsInert() {
        // Each segment selects its OWN mode (never "the other one"), and re-tapping the selected
        // segment must not re-emit — repeated taps can no longer land you on a random mode.
        val selections = mutableListOf<CaptureMode>()
        composeTestRule.setContent {
            var mode by remember { mutableStateOf(CaptureMode.VIDEO) }
            CaptureModeSelector(
                photoMode = mode == CaptureMode.PHOTO,
                onSelect = {
                    selections += it
                    mode = it
                },
            )
        }

        composeTestRule.onNodeWithText("Video").performClick() // already selected → inert
        composeTestRule.onNodeWithText("Camera").performClick()
        composeTestRule.onNodeWithText("Camera").performClick() // now selected → inert
        composeTestRule.onNodeWithText("Video").performClick()
        composeTestRule.runOnIdle {
            assertEquals(listOf(CaptureMode.PHOTO, CaptureMode.VIDEO), selections)
        }
    }

    @Test
    fun captureModeSelector_performsToggleHaptic_onlyOnModeChange() {
        // The tap must confirm itself through touch: ToggleOn entering photo mode (the non-default
        // state), ToggleOff returning to video — and NO haptic when nothing changed.
        val performed = mutableListOf<HapticFeedbackType>()
        val recordingHaptics = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                performed += hapticFeedbackType
            }
        }
        composeTestRule.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides recordingHaptics) {
                var mode by remember { mutableStateOf(CaptureMode.VIDEO) }
                CaptureModeSelector(photoMode = mode == CaptureMode.PHOTO, onSelect = { mode = it })
            }
        }

        composeTestRule.onNodeWithText("Video").performClick() // inert tap → no haptic lie
        composeTestRule.runOnIdle { assertEquals(emptyList<HapticFeedbackType>(), performed) }

        composeTestRule.onNodeWithText("Camera").performClick()
        composeTestRule.runOnIdle {
            assertEquals(listOf(HapticFeedbackType.ToggleOn), performed)
        }

        composeTestRule.onNodeWithText("Video").performClick()
        composeTestRule.runOnIdle {
            assertEquals(listOf(HapticFeedbackType.ToggleOn, HapticFeedbackType.ToggleOff), performed)
        }
    }

    @Test
    fun shutterButton_inPhotoMode_announcesTakePhoto() {
        // The lime gradient is shared with video mode (owner's call), so the spoken label is what
        // tells a TalkBack user which mode the shutter is in.
        composeTestRule.setContent {
            ShutterButton(
                isRecording = false,
                photoMode = true,
                progressFraction = { 0f },
                onClick = {},
            )
        }

        composeTestRule.onNodeWithContentDescription("Take photo").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Start recording").assertDoesNotExist()
        composeTestRule.onNodeWithTag("progress_ring").assertDoesNotExist()
    }

    // ── Photo booth (docs/PRD-photo-booth.md — hoisted controls, no camera bind needed) ──

    @Test
    fun boothButton_hasA11yLabel_andTapShowsCountdownOverlay() {
        // Mirrors the CameraScreen wiring: Booth idle-row → tap → countdown overlay. The digits
        // and shot progress are what a sighted user sees; their live-region announcements are
        // asserted implicitly by the nodes existing with the spoken text.
        composeTestRule.setContent {
            var boothActive by remember { mutableStateOf(false) }
            if (boothActive) {
                BoothCountdownOverlay(digit = { 5 }, shot = { 1 })
            } else {
                BoothControls(monochrome = false, onToggleMonochrome = {}, onStart = { boothActive = true })
            }
        }

        composeTestRule.onNodeWithContentDescription("Start photo booth").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Start photo booth").performClick()

        composeTestRule.onNodeWithTag("booth_countdown_digit").assertIsDisplayed()
        composeTestRule.onNodeWithText("5").assertIsDisplayed()
        composeTestRule.onNodeWithText("Shot 1 of 3").assertIsDisplayed()
    }

    @Test
    fun boothBwChip_togglesBothWays_withSwitchSemantics() {
        // D4: color is the default; the chip flips to B&W and back. Driving it twice guards
        // against the frozen-lambda trap (Lesson 034 — one tap can pass against a stale callback).
        val toggles = mutableListOf<Boolean>()
        composeTestRule.setContent {
            var monochrome by remember { mutableStateOf(false) }
            BoothControls(
                monochrome = monochrome,
                onToggleMonochrome = {
                    toggles += it
                    monochrome = it
                },
                onStart = {},
            )
        }

        composeTestRule.onNodeWithContentDescription("Black and white strip").performClick()
        composeTestRule.onNodeWithContentDescription("Black and white strip").performClick()
        composeTestRule.runOnIdle { assertEquals(listOf(true, false), toggles) }
    }

    @Test
    fun boothCancelButton_invokesTheAbort() {
        var cancels = 0
        composeTestRule.setContent {
            BoothCancelButton(onCancel = { cancels++ })
        }

        composeTestRule.onNodeWithContentDescription("Cancel photo booth").performClick()
        composeTestRule.runOnIdle { assertEquals(1, cancels) }
    }
}
