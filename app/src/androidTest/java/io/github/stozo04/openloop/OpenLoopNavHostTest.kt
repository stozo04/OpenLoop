package io.github.stozo04.openloop

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.stozo04.openloop.camera.CameraManager
import android.net.Uri
import io.github.stozo04.openloop.data.RecordedVideo
import io.github.stozo04.openloop.data.ScratchCapture
import io.github.stozo04.openloop.data.UserPreferencesRepository
import io.github.stozo04.openloop.data.VideoImporter
import io.github.stozo04.openloop.data.VideoStorageRepository
import io.github.stozo04.openloop.media.BoomerangMode
import io.github.stozo04.openloop.media.VideoFilter
import io.github.stozo04.openloop.media.VideoProcessor
import io.github.stozo04.openloop.work.BoomerangRenderRequest
import io.github.stozo04.openloop.work.BoomerangRenderScheduler
import io.github.stozo04.openloop.work.BoomerangRenderWorkResult
import io.github.stozo04.openloop.ui.EditorSource
import io.github.stozo04.openloop.ui.OpenLoopUiState
import io.github.stozo04.openloop.ui.OpenLoopViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Routing guard for [OpenLoopNavHost] (WARNING-1 / Lesson 012 / Lesson 014). The host's `when` is
 * exhaustive with no `else`, so every state must resolve to its own screen rather than silently
 * falling through to a bare [io.github.stozo04.openloop.ui.CameraScreen] (the second `CameraScreen` call site
 * the `ERROR_SOURCE_INACTIVE` fix closed). This proves the slice-02 states — [OpenLoopUiState.Trim]
 * and [OpenLoopUiState.Processing] — route to their own surfaces and NOT the camera.
 */
@RunWith(AndroidJUnit4::class)
class OpenLoopNavHostTest {

    // ComponentActivity (not the plain compose rule) so the host gets a real OnBackPressedDispatcher
    // — required to dispatch Back for the predictive-back swallow tests below (Lessons 015 / 017).
    // The v1 factory is the API Lesson 015 documents; the newer v2 variant flips the test dispatcher
    // (Standard vs Unconfined), which changes effect-execution timing across the whole class — that
    // migration is a separate, codebase-wide change, so the deprecation is deliberately suppressed here.
    @Suppress("DEPRECATION")
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun setContent(uiState: OpenLoopUiState) {
        composeTestRule.setContent {
            OpenLoopNavHost(
                uiState = uiState,
                viewModel = OpenLoopViewModel(
                    NoopPreferencesRepository(),
                    NoopVideoStorageRepository(),
                    NoopVideoProcessor(),
                    NoopVideoImporter(),
                    NoopBoomerangRenderScheduler(),
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
        setContent(OpenLoopUiState.Processing)

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
        setContent(OpenLoopUiState.Trim(EditorSource.ScratchClip("test-uuid")))

        composeTestRule.onNodeWithContentDescription("Start recording").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Stop recording").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Gallery").assertDoesNotExist()
    }

    @Test
    fun processing_swallowsBack_andDoesNotFinishTheActivity() {
        // Back during an in-flight render must NOT finish the Activity (which would discard the
        // boomerang mid-encode); the BackHandler in the Processing branch swallows it (review R1 /
        // Lesson 015). Dispatch synchronously on the UI thread (onBackPressed runs callbacks inline).
        setContent(OpenLoopUiState.Processing)

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
        setContent(OpenLoopUiState.ImportingVideo)

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

    private class NoopBoomerangRenderScheduler : BoomerangRenderScheduler {
        override fun enqueue(request: BoomerangRenderRequest): UUID = UUID.randomUUID()
        override fun observeProgress(workId: UUID): Flow<Float> = MutableStateFlow(0f)
        override fun observeResult(workId: UUID): Flow<BoomerangRenderWorkResult> = MutableStateFlow(
            BoomerangRenderWorkResult.Failure,
        )
        override fun cancelRenderWork(scratchUuid: String) = Unit
    }
}
