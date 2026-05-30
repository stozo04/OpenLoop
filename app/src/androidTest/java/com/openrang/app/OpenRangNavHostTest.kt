package com.openrang.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openrang.app.camera.CameraManager
import android.net.Uri
import com.openrang.app.data.RecordedVideo
import com.openrang.app.data.ScratchCapture
import com.openrang.app.data.UserPreferencesRepository
import com.openrang.app.data.VideoImporter
import com.openrang.app.data.VideoStorageRepository
import com.openrang.app.media.BoomerangMode
import com.openrang.app.media.VideoFilter
import com.openrang.app.media.VideoProcessor
import com.openrang.app.ui.EditorSource
import com.openrang.app.ui.OpenRangUiState
import com.openrang.app.ui.OpenRangViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Routing guard for [OpenRangNavHost] (WARNING-1 / Lesson 012 / Lesson 014). The host's `when` is
 * exhaustive with no `else`, so every state must resolve to its own screen rather than silently
 * falling through to a bare [com.openrang.app.ui.CameraScreen] (the second `CameraScreen` call site
 * the `ERROR_SOURCE_INACTIVE` fix closed). This proves the slice-02 states — [OpenRangUiState.Trim]
 * and [OpenRangUiState.Processing] — route to their own surfaces and NOT the camera.
 */
@RunWith(AndroidJUnit4::class)
class OpenRangNavHostTest {

    // ComponentActivity (not the plain compose rule) so the host gets a real OnBackPressedDispatcher
    // — required to dispatch Back for the predictive-back swallow tests below (Lessons 015 / 017).
    // The v1 factory is the API Lesson 015 documents; the newer v2 variant flips the test dispatcher
    // (Standard vs Unconfined), which changes effect-execution timing across the whole class — that
    // migration is a separate, codebase-wide change, so the deprecation is deliberately suppressed here.
    @Suppress("DEPRECATION")
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun setContent(uiState: OpenRangUiState) {
        composeTestRule.setContent {
            OpenRangNavHost(
                uiState = uiState,
                viewModel = OpenRangViewModel(
                    NoopPreferencesRepository(),
                    NoopVideoStorageRepository(),
                    NoopVideoProcessor(),
                    NoopVideoImporter(),
                ),
                cameraManager = CameraManager(ApplicationProvider.getApplicationContext()),
                onCheckPermissions = {},
                onRationaleAcknowledged = {},
                onOpenAppSettings = {},
                onImportVideo = {},
            )
        }
    }

    @Test
    fun processing_rendersProcessingScreen_notCameraScreen() {
        setContent(OpenRangUiState.Processing)

        composeTestRule.onNodeWithTag("processing_screen").assertIsDisplayed()
        // …and NONE of CameraScreen's controls are mounted.
        composeTestRule.onNodeWithContentDescription("Start recording").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Stop recording").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Gallery").assertDoesNotExist()
    }

    @Test
    fun trim_doesNotFallThroughToCameraScreen() {
        // editorState is null here (no capture driven), so TrimScreen renders nothing — but the key
        // guarantee is that Trim does NOT route to the camera-bound screen.
        setContent(OpenRangUiState.Trim(EditorSource.ScratchClip("test-uuid")))

        composeTestRule.onNodeWithContentDescription("Start recording").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Stop recording").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Gallery").assertDoesNotExist()
    }

    @Test
    fun processing_swallowsBack_andDoesNotFinishTheActivity() {
        // Back during an in-flight render must NOT finish the Activity (which would discard the
        // boomerang mid-encode); the BackHandler in the Processing branch swallows it (review R1 /
        // Lesson 015). Dispatch synchronously on the UI thread (onBackPressed runs callbacks inline).
        setContent(OpenRangUiState.Processing)

        composeTestRule.runOnUiThread {
            composeTestRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeTestRule.waitForIdle()

        assertFalse("Back must be swallowed mid-render, not finish the Activity", composeTestRule.activity.isFinishing)
        composeTestRule.onNodeWithTag("processing_screen").assertIsDisplayed()
    }

    @Test
    fun importingVideo_swallowsBack_andDoesNotFinishTheActivity() {
        // Same guarantee for the import copy: Back can't finish the Activity mid-copy (review R1).
        setContent(OpenRangUiState.ImportingVideo)

        composeTestRule.runOnUiThread {
            composeTestRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeTestRule.waitForIdle()

        assertFalse("Back must be swallowed mid-import, not finish the Activity", composeTestRule.activity.isFinishing)
        composeTestRule.onNodeWithContentDescription("Loading").assertIsDisplayed()
    }

    // ── Minimal fakes (androidTest can't see the JVM-unit fakes or mockk — Lesson 017) ──

    private class NoopPreferencesRepository : UserPreferencesRepository {
        override val hasCompletedOnboarding: Flow<Boolean> = MutableStateFlow(true)
        override suspend fun setOnboardingCompleted(completed: Boolean) {}
    }

    private class NoopVideoStorageRepository : VideoStorageRepository {
        override fun createScratchCapture(): ScratchCapture =
            ScratchCapture("noop", File.createTempFile("navhost_scratch", ".mp4"))
        override suspend fun promoteScratchToRaw(scratch: ScratchCapture): RecordedVideo? = null
        override fun discardScratch(scratch: ScratchCapture) {}
        override fun allocateBoomerangFile(sourceRawId: Long): File =
            File.createTempFile("navhost_boom", ".mp4")
        override suspend fun registerBoomerang(file: File, sourceRawId: Long): RecordedVideo? = null
        override suspend fun durationOf(file: File): Long = 0L
        override suspend fun loadRecordedVideos(): List<RecordedVideo> = emptyList()
        override suspend fun deleteVideo(video: RecordedVideo) {}
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
            speed: Float,
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
        ): File = source
    }
}
