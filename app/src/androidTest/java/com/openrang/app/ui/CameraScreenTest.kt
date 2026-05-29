package com.openrang.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
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
                mutableStateOf<OpenRangUiState>(OpenRangUiState.ReadyToCapture)
            }
            CameraScreenHost(uiState = state) {
                LaunchedEffect(Unit) { initCount++ } // stand-in for startCamera()
                Text(
                    text = "camera",
                    modifier = Modifier
                        .testTag("host_content")
                        .clickable { state = OpenRangUiState.Recording }
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
}
