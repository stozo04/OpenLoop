package com.openrang.app.ui

import android.util.Log
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import com.openrang.app.camera.CameraManager
import com.openrang.app.data.RecordedVideo
import com.openrang.app.data.ScratchCapture
import com.openrang.app.data.UserPreferencesRepository
import com.openrang.app.data.VideoKind
import com.openrang.app.data.VideoStorageRepository
import com.openrang.app.media.BoomerangMode
import com.openrang.app.media.VideoProcessor
import io.mockk.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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
 * Backed by a real temp directory so [File] handles behave; tracks scratch / promote / register
 * operations so tests can assert on storage behavior without touching the Android framework.
 */
class FakeVideoStorageRepository : VideoStorageRepository {

    // A real temp directory so File handles behave; deterministic, no Android needed.
    private val tempRoot: File = File.createTempFile("fake_video_storage_", "").let { f ->
        f.delete()
        f.mkdirs()
        f
    }

    /** Saved videos (raws + boomerangs), exposed for assertions. */
    val saved = mutableListOf<RecordedVideo>()

    /** UUIDs passed to [discardScratch], for assertions. */
    val discardedScratches = mutableListOf<String>()

    /** Toggles to simulate failure paths. */
    var failPromote: Boolean = false
    var failRegister: Boolean = false

    /** Fixed duration [durationOf] reports for any file. */
    var fixedDurationMs: Long = 3_000L

    private var nextId = 1L

    override fun createScratchCapture(): ScratchCapture {
        val uuid = "uuid-${nextId++}"
        return ScratchCapture(uuid, File(tempRoot, "raw_$uuid.mp4"))
    }

    override suspend fun promoteScratchToRaw(scratch: ScratchCapture): RecordedVideo? {
        if (failPromote) return null
        val id = nextId++
        return RecordedVideo(
            id = id,
            videoPath = File(tempRoot, "clip_$id.mp4").absolutePath,
            thumbnailPath = File(tempRoot, "clip_$id.jpg").absolutePath,
            kind = VideoKind.RAW,
        ).also { saved.add(it) }
    }

    override fun discardScratch(scratch: ScratchCapture) {
        discardedScratches.add(scratch.uuid)
    }

    override fun allocateBoomerangFile(sourceRawId: Long): File =
        File(tempRoot, "boom_${nextId++}_from_$sourceRawId.mp4")

    override suspend fun registerBoomerang(file: File, sourceRawId: Long): RecordedVideo? {
        if (failRegister) return null
        val id = nextId++
        return RecordedVideo(
            id = id,
            videoPath = file.absolutePath,
            thumbnailPath = File(tempRoot, "${file.nameWithoutExtension}.jpg").absolutePath,
            kind = VideoKind.BOOMERANG,
            sourceRawId = sourceRawId,
        ).also { saved.add(it) }
    }

    override suspend fun durationOf(file: File): Long = fixedDurationMs

    override suspend fun loadRecordedVideos(): List<RecordedVideo> = saved.sortedByDescending { it.id }

    override suspend fun deleteVideo(video: RecordedVideo) {
        saved.remove(video)
    }
}

/**
 * Fake [VideoProcessor] that writes a stub output file (or throws) without invoking Media3/MediaCodec.
 */
class FakeVideoProcessor : VideoProcessor {
    var failRender: Boolean = false
    var renderCount: Int = 0

    override suspend fun renderBoomerang(
        source: File,
        trimStartMs: Long,
        trimEndMs: Long,
        mode: BoomerangMode,
        speed: Float,
        repetitions: Int,
        outputFile: File,
        onProgress: (Float) -> Unit,
    ): File {
        renderCount++
        onProgress(1f)
        if (failRender) throw RuntimeException("simulated render failure")
        outputFile.parentFile?.mkdirs()
        outputFile.writeBytes(ByteArray(4))
        return outputFile
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class OpenRangViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var viewModel: OpenRangViewModel
    private lateinit var fakePreferencesRepository: FakeUserPreferencesRepository
    private lateinit var fakeVideoStorage: FakeVideoStorageRepository
    private lateinit var fakeVideoProcessor: FakeVideoProcessor
    private val cameraManager: CameraManager = mockk(relaxed = true)

    /**
     * Stand-in for a successfully-started recording. Since REC-2, a `null` return from
     * [CameraManager.startRecording] means "could not start" (camera not bound) and aborts the
     * capture — so tests that expect recording to PROCEED must return this non-null handle, and
     * only the REC-2 test returns `null`.
     */
    private val fakeRecording: Recording = mockk(relaxed = true)

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        // Default: onboarding NOT completed (first-time user)
        fakePreferencesRepository = FakeUserPreferencesRepository(initialOnboardingCompleted = false)
        fakeVideoStorage = FakeVideoStorageRepository()
        fakeVideoProcessor = FakeVideoProcessor()
        viewModel = OpenRangViewModel(fakePreferencesRepository, fakeVideoStorage, fakeVideoProcessor)
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
        val returningViewModel =
            OpenRangViewModel(returningUserRepo, FakeVideoStorageRepository(), FakeVideoProcessor())

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
            OpenRangViewModel(FailingWritePreferencesRepository(), FakeVideoStorageRepository(), FakeVideoProcessor())

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
    fun `startBurstCapture starts recording and auto-caps at 30 seconds`() =
        runTest(mainDispatcherRule.testDispatcher) {
            viewModel.onPermissionsChecked(true) // Set state to ReadyToCapture

            // Mock startRecording to capture the callback and report a successfully-started recording.
            val slot = slot<(VideoRecordEvent) -> Unit>()
            every { cameraManager.startRecording(any(), capture(slot)) } returns fakeRecording

            viewModel.startBurstCapture(cameraManager)

            assertEquals(OpenRangUiState.Recording, viewModel.uiState.value)
            verify(exactly = 1) { cameraManager.startRecording(any(), any()) }
            // The 30 s auto-cap must not fire on start — only after the cap elapses.
            verify(exactly = 0) { cameraManager.stopRecording() }

            // Advance virtual time past the 30 s hard cap (Lesson 008: bound loop + advanceUntilIdle).
            advanceUntilIdle()

            // Auto-cap finalized exactly once, and the ring was reset to empty.
            verify(exactly = 1) { cameraManager.stopRecording() }
            assertEquals(0L, viewModel.recordingElapsedMs.value)
        }

    @Test
    fun `startBurstCapture begins emitting recordingElapsedMs`() =
        runTest(mainDispatcherRule.testDispatcher) {
            viewModel.onPermissionsChecked(true) // ReadyToCapture
            every { cameraManager.startRecording(any(), any()) } returns fakeRecording

            assertEquals(0L, viewModel.recordingElapsedMs.value)

            viewModel.startBurstCapture(cameraManager)
            // Advance just over three ~33 ms ticks; elapsed should have started climbing but
            // stay well under the cap.
            advanceTimeBy(100)

            val elapsed = viewModel.recordingElapsedMs.value
            assertTrue("elapsed should advance past 0, was $elapsed", elapsed > 0L)
            assertTrue("elapsed should be <= 100ms, was $elapsed", elapsed <= 100L)

            // Stop so the ticker doesn't run to the cap during teardown.
            viewModel.stopBurstCapture(cameraManager)
        }

    @Test
    fun `stopBurstCapture outside Recording is a no-op`() {
        // Never entered Recording, so recordingJob is null — stop must not touch the camera.
        viewModel.stopBurstCapture(cameraManager)
        verify(exactly = 0) { cameraManager.stopRecording() }
    }

    @Test
    fun `double stop (user tap racing the auto-cap) calls stopRecording exactly once`() =
        runTest(mainDispatcherRule.testDispatcher) {
            viewModel.onPermissionsChecked(true) // ReadyToCapture
            every { cameraManager.startRecording(any(), any()) } returns fakeRecording

            viewModel.startBurstCapture(cameraManager)
            // First stop wins; the second is a no-op (recordingJob already nulled). This is the
            // same guard that makes a user tap landing on the auto-cap tick safe.
            viewModel.stopBurstCapture(cameraManager)
            viewModel.stopBurstCapture(cameraManager)

            verify(exactly = 1) { cameraManager.stopRecording() }
        }

    // ── REC-2: a recording that can't start must not wedge the UI in Recording ──

    @Test
    fun `startBurstCapture reverts to ReadyToCapture when recording cannot start`() =
        runTest(mainDispatcherRule.testDispatcher) {
            viewModel.onPermissionsChecked(true)
            // null = camera not bound; no Finalize will ever fire (REC-2).
            every { cameraManager.startRecording(any(), any()) } returns null

            viewModel.startBurstCapture(cameraManager)
            advanceUntilIdle()

            assertEquals(OpenRangUiState.ReadyToCapture, viewModel.uiState.value)
            assertEquals(0L, viewModel.recordingElapsedMs.value)
            // No timer coroutine should have been launched, so the auto-cap path is never reached.
            verify(exactly = 0) { cameraManager.stopRecording() }
        }

    // ── REC-3: only the documented synchronous throwables are caught; UI recovers to idle ──

    @Test
    fun `startBurstCapture recovers to ReadyToCapture on IllegalStateException`() =
        runTest(mainDispatcherRule.testDispatcher) {
            viewModel.onPermissionsChecked(true) // ReadyToCapture
            // PendingRecording.start() throws this when the Recorder has an unfinished recording.
            every { cameraManager.startRecording(any(), any()) } throws IllegalStateException("camera busy")

            viewModel.startBurstCapture(cameraManager)
            advanceUntilIdle()

            assertEquals(OpenRangUiState.ReadyToCapture, viewModel.uiState.value)
            assertEquals(0L, viewModel.recordingElapsedMs.value)
            verify(exactly = 0) { cameraManager.stopRecording() }
        }

    @Test
    fun `startBurstCapture recovers to ReadyToCapture on SecurityException`() =
        runTest(mainDispatcherRule.testDispatcher) {
            viewModel.onPermissionsChecked(true) // ReadyToCapture
            // withAudioEnabled() throws this if RECORD_AUDIO is revoked before start().
            every { cameraManager.startRecording(any(), any()) } throws
                SecurityException("RECORD_AUDIO denied")

            viewModel.startBurstCapture(cameraManager)
            advanceUntilIdle()

            assertEquals(OpenRangUiState.ReadyToCapture, viewModel.uiState.value)
            assertEquals(0L, viewModel.recordingElapsedMs.value)
            verify(exactly = 0) { cameraManager.stopRecording() }
        }

    @Test
    fun `stopBurstCapture cancels coroutine job and stops recording`() {
        viewModel.onPermissionsChecked(true) // ReadyToCapture
        every { cameraManager.startRecording(any(), any()) } returns fakeRecording

        viewModel.startBurstCapture(cameraManager)
        viewModel.stopBurstCapture(cameraManager)

        verify(exactly = 1) { cameraManager.stopRecording() }
    }

    /** Drive a successful capture so the ViewModel lands on the Trim screen with an editor session. */
    private fun enterTrimState() {
        viewModel.onPermissionsChecked(true) // ReadyToCapture
        val slot = slot<(VideoRecordEvent) -> Unit>()
        every { cameraManager.startRecording(any(), capture(slot)) } returns fakeRecording
        viewModel.startBurstCapture(cameraManager)
        val finalizeEvent = mockk<VideoRecordEvent.Finalize>(relaxed = true)
        every { finalizeEvent.hasError() } returns false
        slot.captured.invoke(finalizeEvent)
    }

    @Test
    fun `finalize success auto-routes to Trim with a ScratchClip and initialized editorState`() {
        enterTrimState()

        val state = viewModel.uiState.value
        assertTrue("expected Trim, was $state", state is OpenRangUiState.Trim)
        val source = (state as OpenRangUiState.Trim).source
        assertTrue(source is EditorSource.ScratchClip)

        // editorState initializes to the full clip (duration from the fake = 3000ms).
        val editor = viewModel.editorState.value
        assertNotNull(editor)
        assertEquals(0L, editor!!.trimStartMs)
        assertEquals(3_000L, editor.trimEndMs)
        assertEquals(3_000L, editor.sourceDurationMs)

        // The scratch is NOT promoted yet — saving happens on NEXT.
        assertTrue(fakeVideoStorage.saved.isEmpty())
    }

    @Test
    fun `finalize error discards the scratch and returns to ReadyToCapture`() {
        viewModel.onPermissionsChecked(true) // ReadyToCapture

        val slot = slot<(VideoRecordEvent) -> Unit>()
        every { cameraManager.startRecording(any(), capture(slot)) } returns fakeRecording

        viewModel.startBurstCapture(cameraManager)

        // Mock a failed Finalize event
        val finalizeEvent = mockk<VideoRecordEvent.Finalize>(relaxed = true)
        every { finalizeEvent.hasError() } returns true
        every { finalizeEvent.error } returns VideoRecordEvent.Finalize.ERROR_UNKNOWN

        slot.captured.invoke(finalizeEvent)

        assertEquals(OpenRangUiState.ReadyToCapture, viewModel.uiState.value)
        assertEquals(1, fakeVideoStorage.discardedScratches.size) // scratch cleaned up on error
        assertNull(viewModel.editorState.value)
    }

    // ── Trim screen mutators (slice 02) ──

    @Test
    fun `updateTrim clamps to bounds and enforces the minimum window`() {
        enterTrimState()

        viewModel.updateTrim(500L, 2_500L)
        assertEquals(500L, viewModel.editorState.value!!.trimStartMs)
        assertEquals(2_500L, viewModel.editorState.value!!.trimEndMs)

        // Sub-400ms window is rejected (no change).
        viewModel.updateTrim(1_000L, 1_200L)
        assertEquals(500L, viewModel.editorState.value!!.trimStartMs)
        assertEquals(2_500L, viewModel.editorState.value!!.trimEndMs)

        // Out-of-range values clamp to [0, duration].
        viewModel.updateTrim(-100L, 9_000L)
        assertEquals(0L, viewModel.editorState.value!!.trimStartMs)
        assertEquals(3_000L, viewModel.editorState.value!!.trimEndMs)
    }

    @Test
    fun `onNextFromTrim renders saves and returns to capture, emitting Saved`() =
        runTest(mainDispatcherRule.testDispatcher) {
            enterTrimState()
            val events = mutableListOf<BoomerangEvent>()
            val job = backgroundScope.launch { viewModel.events.toList(events) }

            viewModel.onNextFromTrim()
            advanceUntilIdle()

            assertEquals(OpenRangUiState.ReadyToCapture, viewModel.uiState.value)
            assertTrue("expected a Saved event, got $events", events.contains(BoomerangEvent.Saved))
            assertEquals(1, fakeVideoProcessor.renderCount)

            // One RAW (promoted) + one BOOMERANG (registered), boomerang points at the raw.
            val raw = fakeVideoStorage.saved.single { it.kind == VideoKind.RAW }
            val boomerang = fakeVideoStorage.saved.single { it.kind == VideoKind.BOOMERANG }
            assertEquals(raw.id, boomerang.sourceRawId)
            assertEquals(1, fakeVideoStorage.discardedScratches.size) // scratch cleaned up after save
            assertNull(viewModel.editorState.value)

            job.cancel()
        }

    @Test
    fun `onNextFromTrim on render failure returns to Trim with selection preserved, emitting Failed`() =
        runTest(mainDispatcherRule.testDispatcher) {
            enterTrimState()
            viewModel.updateTrim(500L, 2_500L)
            fakeVideoProcessor.failRender = true
            val events = mutableListOf<BoomerangEvent>()
            val job = backgroundScope.launch { viewModel.events.toList(events) }

            viewModel.onNextFromTrim()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue("expected Trim after failure, was $state", state is OpenRangUiState.Trim)
            assertTrue(events.contains(BoomerangEvent.Failed))
            // No boomerang registered; trim selection intact.
            assertTrue(fakeVideoStorage.saved.none { it.kind == VideoKind.BOOMERANG })
            assertEquals(500L, viewModel.editorState.value!!.trimStartMs)
            assertEquals(2_500L, viewModel.editorState.value!!.trimEndMs)

            job.cancel()
        }

    @Test
    fun `discardTrim discards the scratch and returns to ReadyToCapture`() {
        enterTrimState()

        viewModel.discardTrim()

        assertEquals(OpenRangUiState.ReadyToCapture, viewModel.uiState.value)
        assertEquals(1, fakeVideoStorage.discardedScratches.size)
        assertNull(viewModel.editorState.value)
    }

    // ── Gallery Navigation Tests ──

    @Test
    fun `navigateToGallery transitions state to Gallery`() {
        viewModel.navigateToGallery()

        assertEquals(OpenRangUiState.Gallery, viewModel.uiState.value)
    }

    @Test
    fun `navigateToGallery loads videos from storage`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // Seed storage with a saved clip, then enter the gallery.
            fakeVideoStorage.promoteScratchToRaw(fakeVideoStorage.createScratchCapture())

            viewModel.navigateToGallery()
            advanceUntilIdle() // the gallery load now runs on a launched coroutine

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
    fun `deleteVideo removes video from storage and reloads list`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // Seed two clips so the delete leaves a non-trivial remainder.
            fakeVideoStorage.promoteScratchToRaw(fakeVideoStorage.createScratchCapture())
            fakeVideoStorage.promoteScratchToRaw(fakeVideoStorage.createScratchCapture())
            viewModel.loadRecordedVideos()
            advanceUntilIdle()
            assertEquals(2, viewModel.recordedVideos.value.size)

            val toDelete = viewModel.recordedVideos.value.first()
            viewModel.deleteVideo(toDelete)
            advanceUntilIdle()

            assertEquals(1, viewModel.recordedVideos.value.size)
            assertFalse(viewModel.recordedVideos.value.contains(toDelete))
        }

    @Test
    fun `recordedVideos flow starts as empty list`() {
        assertTrue(viewModel.recordedVideos.value.isEmpty())
    }
}
