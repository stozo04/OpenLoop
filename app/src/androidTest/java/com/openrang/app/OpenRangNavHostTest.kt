package com.openrang.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openrang.app.camera.CameraManager
import com.openrang.app.data.RecordedVideo
import com.openrang.app.data.UserPreferencesRepository
import com.openrang.app.data.VideoStorageRepository
import com.openrang.app.ui.OpenRangUiState
import com.openrang.app.ui.OpenRangViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Routing guard for [OpenRangNavHost] (WARNING-1). The host's `when` is exhaustive with no `else`,
 * so [OpenRangUiState.Processing] — a defined-but-not-yet-fully-built state — must resolve to its
 * own branch (the neutral loader placeholder) rather than silently falling through to a bare
 * [com.openrang.app.ui.CameraScreen]. That fall-through was the second `CameraScreen` call site the
 * `ERROR_SOURCE_INACTIVE` fix (Lesson 012) closed.
 *
 * The compile-time guard (a non-exhaustive `when` over the sealed interface fails to build) is the
 * primary protection; this test is the runtime proof that Processing shows the loader and NOT the
 * camera controls.
 */
@RunWith(AndroidJUnit4::class)
class OpenRangNavHostTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun processing_rendersLoadingPlaceholder_notCameraScreen() {
        composeTestRule.setContent {
            OpenRangNavHost(
                uiState = OpenRangUiState.Processing,
                viewModel = OpenRangViewModel(
                    NoopPreferencesRepository(),
                    NoopVideoStorageRepository(),
                ),
                cameraManager = CameraManager(ApplicationProvider.getApplicationContext()),
                onCheckPermissions = {},
                onRationaleAcknowledged = {},
                onOpenAppSettings = {},
            )
        }

        // Processing renders the neutral loader (InfinityLoadingScreen → Image contentDescription).
        composeTestRule.onNodeWithContentDescription("Loading").assertIsDisplayed()
        // …and NONE of CameraScreen's controls are mounted.
        composeTestRule.onNodeWithContentDescription("Start recording").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Stop recording").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Gallery").assertDoesNotExist()
    }

    // ── Minimal fakes (androidTest can't see the JVM-unit fakes or mockk) ──

    private class NoopPreferencesRepository : UserPreferencesRepository {
        override val hasCompletedOnboarding: Flow<Boolean> = MutableStateFlow(true)
        override suspend fun setOnboardingCompleted(completed: Boolean) {}
    }

    private class NoopVideoStorageRepository : VideoStorageRepository {
        override val rawCaptureFile: File = File.createTempFile("navhost_test_raw", ".mp4")
        override fun saveFinalizedVideo(rawCapture: File): File? = null
        override fun loadRecordedVideos(): List<RecordedVideo> = emptyList()
        override fun deleteVideo(video: RecordedVideo) {}
    }
}
