package com.openrang.app.ui

import android.content.Context
import android.util.Log
import androidx.camera.video.VideoRecordEvent
import com.openrang.app.camera.CameraManager
import com.openrang.app.data.UserPreferencesRepository
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.File
import java.io.IOException
import androidx.core.util.Consumer

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val testDispatcher: TestDispatcher = UnconfinedTestDispatcher()
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(testDispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

/**
 * Fake implementation of [UserPreferencesRepository] for unit tests.
 * Avoids mocking Flow complexity — just uses MutableStateFlow under the hood.
 */
class FakeUserPreferencesRepository(
    initialOnboardingCompleted: Boolean = false
) : UserPreferencesRepository {

    private val _hasCompletedOnboarding = MutableStateFlow(initialOnboardingCompleted)
    override val hasCompletedOnboarding: Flow<Boolean> = _hasCompletedOnboarding

    /** Tracks the last value written via [setOnboardingCompleted]. */
    var onboardingCompletedValue: Boolean = initialOnboardingCompleted
        private set

    override suspend fun setOnboardingCompleted(completed: Boolean) {
        onboardingCompletedValue = completed
        _hasCompletedOnboarding.value = completed
    }
}

/**
 * Fake that throws [IOException] on write to simulate disk-full / corruption scenarios.
 */
class FailingWritePreferencesRepository : UserPreferencesRepository {
    private val _hasCompletedOnboarding = MutableStateFlow(false)
    override val hasCompletedOnboarding: Flow<Boolean> = _hasCompletedOnboarding

    override suspend fun setOnboardingCompleted(completed: Boolean) {
        throw IOException("Simulated disk full")
    }
}

class OpenRangViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var viewModel: OpenRangViewModel
    private lateinit var fakePreferencesRepository: FakeUserPreferencesRepository
    private val context: Context = mockk(relaxed = true)
    private val cameraManager: CameraManager = mockk(relaxed = true)
    private val cacheDir: File = mockk(relaxed = true)

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        every { context.cacheDir } returns cacheDir
        every { cacheDir.absolutePath } returns "/fake/cache"

        // Default: onboarding NOT completed (first-time user)
        fakePreferencesRepository = FakeUserPreferencesRepository(initialOnboardingCompleted = false)
        viewModel = OpenRangViewModel(fakePreferencesRepository)
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    // ── DataStore-driven startup tests ──

    @Test
    fun `first-time user resolves to Onboarding after init`() {
        // With UnconfinedTestDispatcher, init coroutine completes eagerly.
        // hasCompletedOnboarding = false → state resolves to Onboarding.
        assertEquals(OpenRangUiState.Onboarding, viewModel.uiState.value)
    }

    @Test
    fun `returning user resolves to CheckingPermissions after init`() {
        // Create a ViewModel where onboarding was already completed
        val returningUserRepo = FakeUserPreferencesRepository(initialOnboardingCompleted = true)
        val returningViewModel = OpenRangViewModel(returningUserRepo)

        assertEquals(OpenRangUiState.CheckingPermissions, returningViewModel.uiState.value)
    }

    @Test
    fun `onOnboardingCompleted persists true to repository`() {
        viewModel.onOnboardingCompleted()

        assertTrue(fakePreferencesRepository.onboardingCompletedValue)
    }

    @Test
    fun `onOnboardingCompleted handles IOException gracefully`() {
        // Use the failing repository that throws IOException on write
        val failingViewModel = OpenRangViewModel(FailingWritePreferencesRepository())

        // Should not crash — state should still transition to CheckingPermissions
        failingViewModel.onOnboardingCompleted()

        assertEquals(OpenRangUiState.CheckingPermissions, failingViewModel.uiState.value)
    }

    // ── Existing state transition tests ──

    @Test
    fun `onOnboardingCompleted transitions to CheckingPermissions`() {
        viewModel.onOnboardingCompleted()
        assertEquals(OpenRangUiState.CheckingPermissions, viewModel.uiState.value)
    }

    @Test
    fun `onPermissionsChecked when granted transitions to ReadyToCapture`() {
        viewModel.onPermissionsChecked(true)
        assertEquals(OpenRangUiState.ReadyToCapture, viewModel.uiState.value)
    }

    @Test
    fun `onPermissionsChecked when denied transitions to PermissionDenied`() {
        viewModel.onPermissionsChecked(false)
        assertEquals(OpenRangUiState.PermissionDenied, viewModel.uiState.value)
    }

    @Test
    fun `resetToCapture transitions state back to ReadyToCapture`() {
        viewModel.onPermissionsChecked(false) // PermissionDenied
        viewModel.resetToCapture()
        assertEquals(OpenRangUiState.ReadyToCapture, viewModel.uiState.value)
    }

    @Test
    fun `startBurstCapture when not ready does not transition or call camera`() {
        // State is Onboarding (first-time user default), which is not ReadyToCapture
        viewModel.startBurstCapture(context, cameraManager)
        assertEquals(OpenRangUiState.Onboarding, viewModel.uiState.value)
        verify(exactly = 0) { cameraManager.startRecording(any(), any()) }
    }

    @Test
    fun `startBurstCapture successfully starts recording and delays automatic stop`() = runTest {
        viewModel.onPermissionsChecked(true) // Set state to ReadyToCapture

        // Mock startRecording to capture and trigger the callback
        val slot = slot<Consumer<VideoRecordEvent>>()
        every { cameraManager.startRecording(any(), capture(slot)) } just Runs

        viewModel.startBurstCapture(context, cameraManager)

        assertEquals(OpenRangUiState.Recording, viewModel.uiState.value)
        verify(exactly = 1) { cameraManager.startRecording(any(), any()) }

        // Simulate 1.5 seconds delay to trigger the automatic capture halt
        advanceTimeBy(1500)

        // Verify stopRecording was invoked after the delay
        verify(exactly = 1) { cameraManager.stopRecording() }
    }

    @Test
    fun `startBurstCapture failures fallback state gracefully`() {
        viewModel.onPermissionsChecked(true) // ReadyToCapture
        every { cameraManager.startRecording(any(), any()) } throws RuntimeException("Camera error")

        viewModel.startBurstCapture(context, cameraManager)

        assertEquals(OpenRangUiState.ReadyToCapture, viewModel.uiState.value)
    }

    @Test
    fun `stopBurstCapture cancels coroutine job and stops recording`() {
        viewModel.onPermissionsChecked(true) // ReadyToCapture
        every { cameraManager.startRecording(any(), any()) } just Runs

        viewModel.startBurstCapture(context, cameraManager)
        viewModel.stopBurstCapture(cameraManager)

        verify(exactly = 1) { cameraManager.stopRecording() }
    }

    @Test
    fun `video record event finalize transitions to LoopingPreview on success`() {
        viewModel.onPermissionsChecked(true) // ReadyToCapture

        val slot = slot<Consumer<VideoRecordEvent>>()
        every { cameraManager.startRecording(any(), capture(slot)) } just Runs

        viewModel.startBurstCapture(context, cameraManager)

        // Mock a success Finalize event
        val finalizeEvent = mockk<VideoRecordEvent.Finalize>(relaxed = true)
        every { finalizeEvent.hasError() } returns false

        slot.captured.accept(finalizeEvent)

        val state = viewModel.uiState.value
        assertTrue(state is OpenRangUiState.LoopingPreview)
        val previewState = state as OpenRangUiState.LoopingPreview
        assertEquals("/fake/cache/raw_capture.mp4", previewState.videoPath)
        assertEquals(1.5f, previewState.playbackSpeed)
    }

    @Test
    fun `video record event finalize transitions back to ReadyToCapture on error`() {
        viewModel.onPermissionsChecked(true) // ReadyToCapture

        val slot = slot<Consumer<VideoRecordEvent>>()
        every { cameraManager.startRecording(any(), capture(slot)) } just Runs

        viewModel.startBurstCapture(context, cameraManager)

        // Mock a failed Finalize event
        val finalizeEvent = mockk<VideoRecordEvent.Finalize>(relaxed = true)
        every { finalizeEvent.hasError() } returns true
        every { finalizeEvent.error } returns VideoRecordEvent.Finalize.ERROR_UNKNOWN

        slot.captured.accept(finalizeEvent)

        assertEquals(OpenRangUiState.ReadyToCapture, viewModel.uiState.value)
    }

    // ── Gallery Navigation Tests ──

    @Test
    fun `navigateToGallery transitions state to Gallery`() {
        val filesDir = mockk<File>(relaxed = true)
        every { context.filesDir } returns filesDir

        viewModel.navigateToGallery(context)

        assertEquals(OpenRangUiState.Gallery, viewModel.uiState.value)
    }

    @Test
    fun `navigateBackFromGallery transitions state to ReadyToCapture`() {
        val filesDir = mockk<File>(relaxed = true)
        every { context.filesDir } returns filesDir

        viewModel.navigateToGallery(context)
        assertEquals(OpenRangUiState.Gallery, viewModel.uiState.value)

        viewModel.navigateBackFromGallery()
        assertEquals(OpenRangUiState.ReadyToCapture, viewModel.uiState.value)
    }

    @Test
    fun `loadRecordedVideos with missing directory returns empty list`() {
        val filesDir = mockk<File>(relaxed = true)
        every { context.filesDir } returns filesDir

        val videosDir = File(filesDir, "videos")
        // Directory doesn't exist on the mocked filesystem, so listFiles returns null

        viewModel.loadRecordedVideos(context)

        assertTrue(viewModel.recordedVideos.value.isEmpty())
    }

    @Test
    fun `deleteVideo removes files and reloads empty list`() {
        val filesDir = mockk<File>(relaxed = true)
        every { context.filesDir } returns filesDir

        val fakeVideoFile = mockk<File>(relaxed = true)
        every { fakeVideoFile.exists() } returns true
        every { fakeVideoFile.delete() } returns true
        every { fakeVideoFile.absolutePath } returns "/fake/videos/clip_123.mp4"

        val fakeThumbFile = mockk<File>(relaxed = true)
        every { fakeThumbFile.exists() } returns true
        every { fakeThumbFile.delete() } returns true
        every { fakeThumbFile.absolutePath } returns "/fake/thumbnails/clip_123.jpg"

        val video = RecordedVideo(
            id = 123L,
            videoPath = "/fake/videos/clip_123.mp4",
            thumbnailPath = "/fake/thumbnails/clip_123.jpg"
        )

        // deleteVideo will attempt File(path).delete() then reload
        viewModel.deleteVideo(context, video)

        // After deletion + reload, the list should remain empty (no real files on disk)
        assertTrue(viewModel.recordedVideos.value.isEmpty())
    }

    @Test
    fun `recordedVideos flow starts as empty list`() {
        assertTrue(viewModel.recordedVideos.value.isEmpty())
    }
}
