package com.openrang.app.ui

import android.util.Log
import androidx.camera.video.VideoRecordEvent
import com.openrang.app.camera.CameraManager
import com.openrang.app.data.RecordedVideo
import com.openrang.app.data.UserPreferencesRepository
import com.openrang.app.data.VideoStorageRepository
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

/**
 * In-memory fake of [VideoStorageRepository] (lesson 004: fakes over mocking Context/File).
 * Holds an in-memory list of saved videos and a single real scratch file for [rawCaptureFile],
 * so tests can assert on storage behavior without touching the Android framework.
 */
class FakeVideoStorageRepository : VideoStorageRepository {

    // A real temp directory so File handles behave; deterministic, no Android needed.
    private val tempRoot: File = File.createTempFile("fake_video_storage_", "").let { f ->
        f.delete()
        f.mkdirs()
        f
    }

    /** Saved videos, exposed for assertions. */
    val saved = mutableListOf<RecordedVideo>()

    /** Toggle to simulate a save failure (e.g. copy error) — [saveFinalizedVideo] returns null. */
    var failSave: Boolean = false

    override val rawCaptureFile: File = File(tempRoot, "raw_capture.mp4")

    override fun saveFinalizedVideo(rawCapture: File): File? {
        if (failSave) return null
        val id = (saved.maxOfOrNull { it.id } ?: 0L) + 1
        val dest = File(tempRoot, "clip_$id.mp4")
        saved.add(
            RecordedVideo(
                id = id,
                videoPath = dest.absolutePath,
                thumbnailPath = File(tempRoot, "clip_$id.jpg").absolutePath
            )
        )
        return dest
    }

    override fun loadRecordedVideos(): List<RecordedVideo> = saved.sortedByDescending { it.id }

    override fun deleteVideo(video: RecordedVideo) {
        saved.remove(video)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class OpenRangViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var viewModel: OpenRangViewModel
    private lateinit var fakePreferencesRepository: FakeUserPreferencesRepository
    private lateinit var fakeVideoStorage: FakeVideoStorageRepository
    private val cameraManager: CameraManager = mockk(relaxed = true)

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        // Default: onboarding NOT completed (first-time user)
        fakePreferencesRepository = FakeUserPreferencesRepository(initialOnboardingCompleted = false)
        fakeVideoStorage = FakeVideoStorageRepository()
        viewModel = OpenRangViewModel(fakePreferencesRepository, fakeVideoStorage)
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
        val returningViewModel = OpenRangViewModel(returningUserRepo, FakeVideoStorageRepository())

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
        val failingViewModel =
            OpenRangViewModel(FailingWritePreferencesRepository(), FakeVideoStorageRepository())

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

    // ── Permission rationale flow (Issue #11) ──

    @Test
    fun `showPermissionRationale transitions to PermissionRationale`() {
        viewModel.showPermissionRationale()
        assertEquals(OpenRangUiState.PermissionRationale, viewModel.uiState.value)
    }

    @Test
    fun `onRationaleAcknowledged transitions to CheckingPermissions`() {
        viewModel.showPermissionRationale()
        viewModel.onRationaleAcknowledged()
        assertEquals(OpenRangUiState.CheckingPermissions, viewModel.uiState.value)
    }

    @Test
    fun `onRationaleDeclined transitions to PermissionDenied`() {
        viewModel.showPermissionRationale()
        viewModel.onRationaleDeclined()
        assertEquals(OpenRangUiState.PermissionDenied, viewModel.uiState.value)
    }

    @Test
    fun `rationale flow ending in grant reaches ReadyToCapture`() {
        // Full path: denied-once → rationale → acknowledge → system grant.
        viewModel.showPermissionRationale()
        viewModel.onRationaleAcknowledged()
        viewModel.onPermissionsChecked(true)
        assertEquals(OpenRangUiState.ReadyToCapture, viewModel.uiState.value)
    }

    @Test
    fun `rationale flow ending in denial reaches PermissionDenied`() {
        // Full path: denied-once → rationale → acknowledge → system denial.
        viewModel.showPermissionRationale()
        viewModel.onRationaleAcknowledged()
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
        viewModel.startBurstCapture(cameraManager)
        assertEquals(OpenRangUiState.Onboarding, viewModel.uiState.value)
        verify(exactly = 0) { cameraManager.startRecording(any(), any()) }
    }

    @Test
    fun `startBurstCapture successfully starts recording and delays automatic stop`() =
        runTest(mainDispatcherRule.testDispatcher) {
            viewModel.onPermissionsChecked(true) // Set state to ReadyToCapture

            // Mock startRecording to capture and trigger the callback
            val slot = slot<(VideoRecordEvent) -> Unit>()
            every { cameraManager.startRecording(any(), capture(slot)) } returns null

            viewModel.startBurstCapture(cameraManager)

            assertEquals(OpenRangUiState.Recording, viewModel.uiState.value)
            verify(exactly = 1) { cameraManager.startRecording(any(), any()) }
            // Auto-stop is scheduled behind a delay(1500), so it must not fire yet.
            verify(exactly = 0) { cameraManager.stopRecording() }

            // Advance virtual time past the auto-stop timer.
            advanceUntilIdle()

            verify(exactly = 1) { cameraManager.stopRecording() }
        }

    @Test
    fun `startBurstCapture failures fallback state gracefully`() {
        viewModel.onPermissionsChecked(true) // ReadyToCapture
        every { cameraManager.startRecording(any(), any()) } throws RuntimeException("Camera error")

        viewModel.startBurstCapture(cameraManager)

        assertEquals(OpenRangUiState.ReadyToCapture, viewModel.uiState.value)
    }

    @Test
    fun `stopBurstCapture cancels coroutine job and stops recording`() {
        viewModel.onPermissionsChecked(true) // ReadyToCapture
        every { cameraManager.startRecording(any(), any()) } returns null

        viewModel.startBurstCapture(cameraManager)
        viewModel.stopBurstCapture(cameraManager)

        verify(exactly = 1) { cameraManager.stopRecording() }
    }

    @Test
    fun `video record event finalize transitions to LoopingPreview using saved video path`() {
        viewModel.onPermissionsChecked(true) // ReadyToCapture

        val slot = slot<(VideoRecordEvent) -> Unit>()
        every { cameraManager.startRecording(any(), capture(slot)) } returns null

        viewModel.startBurstCapture(cameraManager)

        // Mock a success Finalize event
        val finalizeEvent = mockk<VideoRecordEvent.Finalize>(relaxed = true)
        every { finalizeEvent.hasError() } returns false

        slot.captured.invoke(finalizeEvent)

        val state = viewModel.uiState.value
        assertTrue(state is OpenRangUiState.LoopingPreview)
        val previewState = state as OpenRangUiState.LoopingPreview
        // saveFinalizedVideo succeeded, so the preview points at the persisted clip path.
        val persisted = fakeVideoStorage.saved.single()
        assertEquals(persisted.videoPath, previewState.videoPath)
        assertEquals(1.5f, previewState.playbackSpeed)
    }

    @Test
    fun `video record event finalize falls back to raw capture path when save fails`() {
        viewModel.onPermissionsChecked(true) // ReadyToCapture
        fakeVideoStorage.failSave = true

        val slot = slot<(VideoRecordEvent) -> Unit>()
        every { cameraManager.startRecording(any(), capture(slot)) } returns null

        viewModel.startBurstCapture(cameraManager)

        val finalizeEvent = mockk<VideoRecordEvent.Finalize>(relaxed = true)
        every { finalizeEvent.hasError() } returns false

        slot.captured.invoke(finalizeEvent)

        val state = viewModel.uiState.value
        assertTrue(state is OpenRangUiState.LoopingPreview)
        val previewState = state as OpenRangUiState.LoopingPreview
        // Save returned null → ViewModel falls back to the raw scratch capture path.
        assertEquals(fakeVideoStorage.rawCaptureFile.absolutePath, previewState.videoPath)
        assertTrue(fakeVideoStorage.saved.isEmpty())
    }

    @Test
    fun `video record event finalize transitions back to ReadyToCapture on error`() {
        viewModel.onPermissionsChecked(true) // ReadyToCapture

        val slot = slot<(VideoRecordEvent) -> Unit>()
        every { cameraManager.startRecording(any(), capture(slot)) } returns null

        viewModel.startBurstCapture(cameraManager)

        // Mock a failed Finalize event
        val finalizeEvent = mockk<VideoRecordEvent.Finalize>(relaxed = true)
        every { finalizeEvent.hasError() } returns true
        every { finalizeEvent.error } returns VideoRecordEvent.Finalize.ERROR_UNKNOWN

        slot.captured.invoke(finalizeEvent)

        assertEquals(OpenRangUiState.ReadyToCapture, viewModel.uiState.value)
    }

    // ── Gallery Navigation Tests ──

    @Test
    fun `navigateToGallery transitions state to Gallery`() {
        viewModel.navigateToGallery()

        assertEquals(OpenRangUiState.Gallery, viewModel.uiState.value)
    }

    @Test
    fun `navigateToGallery loads videos from storage`() {
        // Seed storage with a saved clip, then enter the gallery.
        fakeVideoStorage.saveFinalizedVideo(fakeVideoStorage.rawCaptureFile)

        viewModel.navigateToGallery()

        assertEquals(OpenRangUiState.Gallery, viewModel.uiState.value)
        assertEquals(1, viewModel.recordedVideos.value.size)
    }

    @Test
    fun `navigateBackFromGallery transitions state to ReadyToCapture`() {
        viewModel.navigateToGallery()
        assertEquals(OpenRangUiState.Gallery, viewModel.uiState.value)

        viewModel.navigateBackFromGallery()
        assertEquals(OpenRangUiState.ReadyToCapture, viewModel.uiState.value)
    }

    @Test
    fun `loadRecordedVideos with empty storage returns empty list`() {
        viewModel.loadRecordedVideos()

        assertTrue(viewModel.recordedVideos.value.isEmpty())
    }

    @Test
    fun `deleteVideo removes video from storage and reloads list`() {
        // Seed two clips so the delete leaves a non-trivial remainder.
        fakeVideoStorage.saveFinalizedVideo(fakeVideoStorage.rawCaptureFile)
        fakeVideoStorage.saveFinalizedVideo(fakeVideoStorage.rawCaptureFile)
        viewModel.loadRecordedVideos()
        assertEquals(2, viewModel.recordedVideos.value.size)

        val toDelete = viewModel.recordedVideos.value.first()
        viewModel.deleteVideo(toDelete)

        assertEquals(1, viewModel.recordedVideos.value.size)
        assertFalse(viewModel.recordedVideos.value.contains(toDelete))
    }

    @Test
    fun `recordedVideos flow starts as empty list`() {
        assertTrue(viewModel.recordedVideos.value.isEmpty())
    }
}
