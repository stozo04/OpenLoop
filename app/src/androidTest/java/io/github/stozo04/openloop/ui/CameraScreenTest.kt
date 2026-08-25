package io.github.stozo04.openloop.ui

import android.graphics.Bitmap
import android.net.Uri
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
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
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
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.stozo04.openloop.camera.CameraManager
import io.github.stozo04.openloop.data.RecordedVideo
import io.github.stozo04.openloop.data.ScratchCapture
import io.github.stozo04.openloop.data.UserPreferencesRepository
import io.github.stozo04.openloop.data.VideoImporter
import io.github.stozo04.openloop.data.VideoStorageRepository
import io.github.stozo04.openloop.media.BoomerangMode
import io.github.stozo04.openloop.media.ReverseScratchJanitor
import io.github.stozo04.openloop.media.SpeedCurve
import io.github.stozo04.openloop.media.VideoFilter
import io.github.stozo04.openloop.media.VideoProcessor
import io.github.stozo04.openloop.work.BoomerangRenderRequest
import io.github.stozo04.openloop.work.BoomerangRenderScheduler
import io.github.stozo04.openloop.work.BoomerangRenderWorkResult
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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

    // The v1 factory is the API the suite is built on; the v2 variant flips the test dispatcher
    // (Standard vs Unconfined), which changes effect-execution timing across the whole class —
    // that migration is a separate, codebase-wide change, so the deprecation is deliberately
    // suppressed here (same call as OpenLoopNavHostTest).
    @Suppress("DEPRECATION")
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
    fun boothLensToggle_flipsTabs_withRadioSemantics() {
        // D2 (decided 2026-08-20): the drawer's slider arms/disarms booth. Lenses is the default
        // tab; driving the flip in BOTH directions guards the frozen-lambda trap (Lesson 034 —
        // one tap can pass against a stale callback).
        composeTestRule.setContent {
            var armed by remember { mutableStateOf(false) }
            BoothLensToggle(boothSelected = armed, onSelect = { armed = it })
        }

        composeTestRule.onNodeWithText("Lenses").assertIsSelected()
        composeTestRule.onNodeWithText("Photo Booth").assertIsNotSelected()

        composeTestRule.onNodeWithText("Photo Booth").performClick()
        composeTestRule.onNodeWithText("Photo Booth").assertIsSelected()
        composeTestRule.onNodeWithText("Lenses").assertIsNotSelected()

        composeTestRule.onNodeWithText("Lenses").performClick()
        composeTestRule.onNodeWithText("Lenses").assertIsSelected()
        composeTestRule.onNodeWithText("Photo Booth").assertIsNotSelected()
    }

    @Test
    fun boothColorRow_selectsBothWays_andCloseInvokesTheClear() {
        // D4: Colored is the default; the radio pair flips to Black & White and back (both
        // directions — Lesson 034). The ✕ carries the "clear + close" contract, so it must
        // invoke onClose exactly once per tap.
        val selections = mutableListOf<Boolean>()
        var closes = 0
        composeTestRule.setContent {
            var monochrome by remember { mutableStateOf(false) }
            BoothColorRow(
                monochrome = monochrome,
                onSelectMonochrome = {
                    selections += it
                    monochrome = it
                },
                onClose = { closes++ },
            )
        }

        composeTestRule.onNodeWithTag("booth_choice_colored").assertIsSelected()
        composeTestRule.onNodeWithTag("booth_choice_bw").assertIsNotSelected()

        composeTestRule.onNodeWithTag("booth_choice_bw").performClick()
        composeTestRule.onNodeWithTag("booth_choice_bw").assertIsSelected()
        composeTestRule.onNodeWithTag("booth_choice_colored").performClick()
        composeTestRule.onNodeWithTag("booth_choice_colored").assertIsSelected()
        // Tapping the already-selected option is inert — no phantom re-selection.
        composeTestRule.onNodeWithTag("booth_choice_colored").performClick()
        composeTestRule.runOnIdle { assertEquals(listOf(true, false), selections) }

        composeTestRule.onNodeWithContentDescription("Turn off photo booth").performClick()
        composeTestRule.runOnIdle { assertEquals(1, closes) }
    }

    // ── The REAL screen's booth wiring (PRD test plan; D2 decided 2026-08-20). The tests above
    // drive the hoisted composable and stay green even if CameraScreen's own wiring is deleted — this one
    // mounts CameraScreen itself, so drawer → arm → armed-shutter → sequence → §5.1 gating is
    // what is under test. ──

    @Test
    fun cameraScreen_armBoothInDrawer_shutterRunsSequence_andGatesTheControls() {
        composeTestRule.setContent {
            CameraScreen(
                viewModel = OpenLoopViewModel(
                    NoopPreferencesRepository(),
                    NoopVideoStorageRepository(),
                    NoopVideoProcessor(),
                    NoopVideoImporter(),
                    NoopBoomerangRenderScheduler(),
                ),
                cameraManager = CameraManager(ApplicationProvider.getApplicationContext()),
            )
        }

        // Idle: mode selector present, shutter is the plain video trigger, no drawer, no banner.
        composeTestRule.onNodeWithTag("capture_mode_selector").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Start recording").assertIsEnabled()
        composeTestRule.onNodeWithTag("booth_lens_toggle").assertDoesNotExist()
        composeTestRule.onNodeWithTag("booth_swap_hint").assertDoesNotExist()

        // Open the drawer: Lenses tab by default, carousel showing.
        composeTestRule.onNodeWithTag("lens_button").performClick()
        composeTestRule.onNodeWithText("Lenses").assertIsSelected()
        composeTestRule.onNodeWithTag("lens_carousel").assertIsDisplayed()

        // Arm booth: carousel yields to the color choice, shutter re-announces itself.
        composeTestRule.onNodeWithText("Photo Booth").performClick()
        composeTestRule.onNodeWithTag("lens_carousel").assertDoesNotExist()
        composeTestRule.onNodeWithTag("booth_choice_colored").assertIsSelected()
        composeTestRule.onNodeWithContentDescription("Start photo booth").assertIsEnabled()

        // Armed state survives closing the drawer (the lime lens button carries the cue).
        composeTestRule.onNodeWithTag("lens_button").performClick()
        composeTestRule.onNodeWithTag("booth_lens_toggle").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Start photo booth").assertIsEnabled()

        // The shutter starts the sequence. Overlay is primed to shot 1 / digit 5 before the
        // first tick, so these are stable immediately after the tap — no clock games needed.
        composeTestRule.onNodeWithContentDescription("Start photo booth").performClick()
        composeTestRule.onNodeWithTag("booth_countdown_digit").assertIsDisplayed()
        composeTestRule.onNodeWithText("Shot 1 of 3").assertIsDisplayed()
        // §5.1 gates on the real screen: shutter genuinely disabled (announced, not a silent
        // no-op), mode selector hidden, Cancel + the still-live B&W chip in the booth row.
        composeTestRule.onNodeWithContentDescription("Start photo booth").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("capture_mode_selector").assertDoesNotExist()
        // D5 banner: the countdown is the lens-swap window, and the top of the screen says so.
        composeTestRule.onNodeWithTag("booth_swap_hint").assertIsDisplayed()
        composeTestRule.onNodeWithText("Swap lenses between shots").assertIsDisplayed()

        // The drawer is the mid-sequence control surface (owner, 2026-08-20): reopen it — it
        // lands on the armed Photo Booth tab — and its ✕ is the Cancel: aborts the sequence,
        // disarms, and closes the drawer. The banner leaves with the sequence.
        composeTestRule.onNodeWithTag("lens_button").performClick()
        composeTestRule.onNodeWithTag("booth_choice_colored").assertIsSelected()
        composeTestRule.onNodeWithTag("booth_tab_close").performClick()
        composeTestRule.onNodeWithTag("booth_swap_hint").assertDoesNotExist()
        composeTestRule.onNodeWithTag("booth_lens_toggle").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Start recording").assertIsEnabled()
        composeTestRule.onNodeWithTag("capture_mode_selector").assertIsDisplayed()

        // Re-arm, then pick a capture mode: an explicit "back to normal" disarms the booth.
        composeTestRule.onNodeWithTag("lens_button").performClick()
        composeTestRule.onNodeWithText("Photo Booth").performClick()
        composeTestRule.onNodeWithTag("lens_button").performClick()
        composeTestRule.onNodeWithContentDescription("Start photo booth").assertIsEnabled()
        composeTestRule.onNodeWithText("Camera").performClick()
        composeTestRule.onNodeWithContentDescription("Take photo").assertIsEnabled()
        composeTestRule.onNodeWithContentDescription("Start photo booth").assertDoesNotExist()
    }

    // ── Minimal fakes (androidTest can't see the JVM-unit fakes or mockk — Lesson 017; kept
    // inline per that lesson rather than shared with OpenLoopNavHostTest's private copies). ──

    private class NoopPreferencesRepository : UserPreferencesRepository {
        override val hasCompletedOnboarding: Flow<Boolean> = MutableStateFlow(true)
        override suspend fun setOnboardingCompleted(completed: Boolean) {}
        override val hasSeenSpeedCurveIntro: Flow<Boolean> = MutableStateFlow(true)
        override suspend fun setSpeedCurveIntroSeen(seen: Boolean) {}
        override suspend fun incrementSavedLoopCount(): Int = 0
    }

    private class NoopVideoStorageRepository : VideoStorageRepository {
        override fun createScratchCapture(): ScratchCapture =
            ScratchCapture("noop", File.createTempFile("camerascreen_scratch", ".mp4"))
        override suspend fun promoteScratchToRaw(scratch: ScratchCapture): RecordedVideo? = null
        override fun discardScratch(scratch: ScratchCapture) {}
        override fun allocateBoomerangFile(sourceRawId: Long): File =
            File.createTempFile("camerascreen_boom", ".mp4")
        override suspend fun registerBoomerang(file: File, sourceRawId: Long): RecordedVideo? = null
        override suspend fun durationOf(file: File): Long = 0L
        override suspend fun loadRecordedVideos(): List<RecordedVideo> = emptyList()
        override suspend fun savePhoto(bitmap: Bitmap): RecordedVideo? = null
        override suspend fun deleteVideo(video: RecordedVideo) {}
        override suspend fun deleteRawVideo(id: Long) {}
        override suspend fun pruneStaleScratch(olderThanMs: Long): Int = 0
    }

    private class NoopVideoImporter : VideoImporter {
        override suspend fun probeDurationMs(source: Uri): Long = 0L
        override suspend fun importToFile(source: Uri, dest: File): Boolean = false
    }

    private class NoopVideoProcessor : VideoProcessor {
        override suspend fun renderBoomerang(
            source: File,
            trimStartMs: Long,
            trimEndMs: Long,
            mode: BoomerangMode,
            curve: SpeedCurve,
            filter: VideoFilter,
            repetitions: Int,
            outputFile: File,
            onProgress: (Float) -> Unit,
        ): File = outputFile

        override suspend fun ensureReversed(
            source: File,
            trimStartMs: Long,
            trimEndMs: Long,
            onProgress: (Float) -> Unit,
            maxReverseShortSide: Int?,
        ): File = source

        override fun cleanupReverseIntermediates() =
            ReverseScratchJanitor.CleanupResult(0, 0L)
    }

    private class NoopBoomerangRenderScheduler : BoomerangRenderScheduler {
        override fun enqueue(request: BoomerangRenderRequest): UUID = UUID.randomUUID()
        override fun observeProgress(workId: UUID): Flow<Float> = MutableStateFlow(0f)
        override fun observeResult(workId: UUID): Flow<BoomerangRenderWorkResult> = MutableStateFlow(
            BoomerangRenderWorkResult.Failure(),
        )
        override fun cancelRenderWork(scratchUuid: String) = Unit
    }
}
