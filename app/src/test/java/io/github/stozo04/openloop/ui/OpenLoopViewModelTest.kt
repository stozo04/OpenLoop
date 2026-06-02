package io.github.stozo04.openloop.ui

import android.net.Uri
import android.util.Log
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import io.github.stozo04.openloop.camera.CameraManager
import io.github.stozo04.openloop.data.RecordedVideo
import io.github.stozo04.openloop.data.ScratchCapture
import io.github.stozo04.openloop.data.UserPreferencesRepository
import io.github.stozo04.openloop.data.VideoImporter
import io.github.stozo04.openloop.data.VideoKind
import io.github.stozo04.openloop.data.VideoStorageRepository
import io.github.stozo04.openloop.media.BoomerangMode
import io.github.stozo04.openloop.media.needsReverse
import io.github.stozo04.openloop.media.VideoFilter
import io.github.stozo04.openloop.media.VideoProcessor
import io.github.stozo04.openloop.work.FakeBoomerangRenderScheduler
import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withTimeout
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
    val testDispatcher: TestDispatcher = UnconfinedTestDispatcher(),
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

    /** Count of [createScratchCapture] calls, so import tests can assert "no scratch was minted". */
    var createScratchCount: Int = 0
        private set

    /** Count of [pruneStaleScratch] calls + the last threshold, for the init-prune assertion (D-8). */
    var pruneCallCount: Int = 0
        private set
    var lastPruneOlderThanMs: Long = -1L
        private set

    /** Toggles to simulate failure paths. */
    var failPromote: Boolean = false
    var failRegister: Boolean = false

    /** Fixed duration [durationOf] reports for any file. */
    var fixedDurationMs: Long = 3_000L

    private var nextId = 1L

    override fun createScratchCapture(): ScratchCapture {
        createScratchCount++
        val uuid = "uuid-${nextId++}"
        return ScratchCapture(uuid, File(tempRoot, "raw_$uuid.mp4"))
    }

    override suspend fun pruneStaleScratch(olderThanMs: Long): Int {
        pruneCallCount++
        lastPruneOlderThanMs = olderThanMs
        return 0
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
    var failReverse: Boolean = false
    var renderCount: Int = 0

    /** The speed passed to the most recent [renderBoomerang] call, for asserting save wiring (slice 04). */
    var lastRenderSpeed: Float = Float.NaN

    /** The filter passed to the most recent [renderBoomerang] call, for asserting save wiring (slice 05). */
    var lastRenderFilter: VideoFilter? = null

    /** Counts [ensureReversed] calls so tests can assert the reversed clip is generated once + reused. */
    var ensureReversedCount: Int = 0

    /** Stub reversed file returned by [ensureReversed]; a real temp file so File handles behave. */
    val reversedStub: File = File.createTempFile("fake_reversed_", ".mp4").apply { writeBytes(ByteArray(4)) }

    /** When set, [ensureReversed] suspends until [releaseReverseGate] completes it (in-flight reverse tests). */
    var reverseGate: CompletableDeferred<Unit>? = null

    /** When true, [ensureReversed] never completes (timeout / wedged-native regression tests). */
    var hangReverse: Boolean = false

    fun releaseReverseGate() {
        reverseGate?.complete(Unit)
    }

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
    ): File {
        renderCount++
        lastRenderSpeed = speed
        lastRenderFilter = filter
        onProgress(1f)
        if (failRender) throw RuntimeException("simulated render failure")
        outputFile.parentFile?.mkdirs()
        outputFile.writeBytes(ByteArray(4))
        return outputFile
    }

    var lastEnsureReversedMaxShortSide: Int? = null

    override suspend fun ensureReversed(
        source: File,
        trimStartMs: Long,
        trimEndMs: Long,
        onProgress: (Float) -> Unit,
        maxReverseShortSide: Int?,
    ): File {
        lastEnsureReversedMaxShortSide = maxReverseShortSide
        ensureReversedCount++
        if (hangReverse) {
            kotlinx.coroutines.awaitCancellation()
        }
        reverseGate?.await()
        onProgress(1f)
        if (failReverse) throw RuntimeException("simulated reverse failure")
        return reversedStub
    }
}

/**
 * Fake [VideoImporter] (slice 07). [probeMs] is what the pre-copy duration probe reports; [copyOk]
 * decides whether [importToFile] "succeeds" (writing a few stub bytes to the dest) or fails. Tracks
 * [importCallCount] so tests can assert a too-long clip is never copied.
 */
class FakeVideoImporter : VideoImporter {
    var probeMs: Long = 3_000L
    var copyOk: Boolean = true
    var importCallCount: Int = 0
        private set

    override suspend fun probeDurationMs(source: Uri): Long = probeMs

    override suspend fun importToFile(source: Uri, dest: File): Boolean {
        importCallCount++
        if (copyOk) {
            dest.parentFile?.mkdirs()
            dest.writeBytes(ByteArray(4))
        }
        return copyOk
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class OpenLoopViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var viewModel: OpenLoopViewModel
    private lateinit var fakePreferencesRepository: FakeUserPreferencesRepository
    private lateinit var fakeVideoStorage: FakeVideoStorageRepository
    private lateinit var fakeVideoProcessor: FakeVideoProcessor
    private lateinit var fakeVideoImporter: FakeVideoImporter
    private lateinit var fakeRenderScheduler: FakeBoomerangRenderScheduler
    private val cameraManager: CameraManager = mockk(relaxed = true)

    /** A stand-in picked-video Uri; mockk avoids needing the android framework in a JVM test. */
    private val fakeUri: Uri = mockk(relaxed = true)

    /**
     * Stand-in for a successfully-started recording. Since REC-2, a `null` return from
     * [CameraManager.startRecording] means "could not start" (camera not bound) and aborts the
     * capture — so tests that expect recording to PROCEED must return this non-null handle, and
     * only the REC-2 test returns `null`.
     */
    private val fakeRecording: Recording = mockk(relaxed = true)

    @Before
    fun setUp() {
        OpenLoopViewModel.reversePreviewTimeoutDisabledForTests = true
        OpenLoopViewModel.reversePreviewTimeoutMsOverride = null
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        // Default: onboarding NOT completed (first-time user)
        fakePreferencesRepository = FakeUserPreferencesRepository(initialOnboardingCompleted = false)
        fakeVideoStorage = FakeVideoStorageRepository()
        fakeVideoProcessor = FakeVideoProcessor()
        fakeVideoImporter = FakeVideoImporter()
        fakeRenderScheduler = FakeBoomerangRenderScheduler(
            processor = fakeVideoProcessor,
            storage = fakeVideoStorage,
            scope = CoroutineScope(mainDispatcherRule.testDispatcher),
        )
        viewModel = OpenLoopViewModel(
            fakePreferencesRepository,
            fakeVideoStorage,
            fakeVideoProcessor,
            fakeVideoImporter,
            fakeRenderScheduler,
            // Default NoOp arg keeps these constructions compiling; assert on a FakeAnalyticsReporter
            // in new tests that care about analytics events (see firebase-analytics PRD §6).
        )
    }

    @After
    fun tearDown() {
        OpenLoopViewModel.reversePreviewTimeoutDisabledForTests = false
        OpenLoopViewModel.reversePreviewTimeoutMsOverride = null
        unmockkStatic(Log::class)
    }

    // ── DataStore-driven startup tests ──

    @Test
    fun `first-time user resolves to Onboarding after init`() {
        // With UnconfinedTestDispatcher, init coroutine completes eagerly.
        // hasCompletedOnboarding = false → state resolves to Onboarding.
        assertEquals(OpenLoopUiState.Onboarding, viewModel.uiState.value)
    }

    @Test
    fun `returning user resolves to CheckingPermissions after init`() {
        // Create a ViewModel where onboarding was already completed
        val returningUserRepo = FakeUserPreferencesRepository(initialOnboardingCompleted = true)
        val returningViewModel =
            OpenLoopViewModel(
                returningUserRepo,
                FakeVideoStorageRepository(),
                FakeVideoProcessor(),
                FakeVideoImporter(),
                FakeBoomerangRenderScheduler(
                    FakeVideoProcessor(),
                    FakeVideoStorageRepository(),
                    CoroutineScope(mainDispatcherRule.testDispatcher),
                ),
            )

        assertEquals(OpenLoopUiState.CheckingPermissions, returningViewModel.uiState.value)
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
            OpenLoopViewModel(
                FailingWritePreferencesRepository(),
                FakeVideoStorageRepository(),
                FakeVideoProcessor(),
                FakeVideoImporter(),
                FakeBoomerangRenderScheduler(
                    FakeVideoProcessor(),
                    FakeVideoStorageRepository(),
                    CoroutineScope(mainDispatcherRule.testDispatcher),
                ),
            )

        // Should not crash — state should still transition to CheckingPermissions
        failingViewModel.onOnboardingCompleted()

        assertEquals(OpenLoopUiState.CheckingPermissions, failingViewModel.uiState.value)
    }

    // ── Existing state transition tests ──

    @Test
    fun `onOnboardingCompleted transitions to CheckingPermissions`() {
        viewModel.onOnboardingCompleted()
        assertEquals(OpenLoopUiState.CheckingPermissions, viewModel.uiState.value)
    }

    @Test
    fun `onPermissionsChecked when granted transitions to ReadyToCapture`() {
        viewModel.onPermissionsChecked(true)
        assertEquals(OpenLoopUiState.ReadyToCapture, viewModel.uiState.value)
    }

    @Test
    fun `onPermissionsChecked when denied transitions to PermissionDenied`() {
        viewModel.onPermissionsChecked(false)
        assertEquals(OpenLoopUiState.PermissionDenied, viewModel.uiState.value)
    }

    // ── Permission rationale flow (Issue #11) ──

    @Test
    fun `showPermissionRationale transitions to PermissionRationale`() {
        viewModel.showPermissionRationale()
        assertEquals(OpenLoopUiState.PermissionRationale, viewModel.uiState.value)
    }

    @Test
    fun `onRationaleAcknowledged transitions to CheckingPermissions`() {
        viewModel.showPermissionRationale()
        viewModel.onRationaleAcknowledged()
        assertEquals(OpenLoopUiState.CheckingPermissions, viewModel.uiState.value)
    }

    @Test
    fun `onRationaleDeclined transitions to PermissionDenied`() {
        viewModel.showPermissionRationale()
        viewModel.onRationaleDeclined()
        assertEquals(OpenLoopUiState.PermissionDenied, viewModel.uiState.value)
    }

    @Test
    fun `rationale flow ending in grant reaches ReadyToCapture`() {
        // Full path: denied-once → rationale → acknowledge → system grant.
        viewModel.showPermissionRationale()
        viewModel.onRationaleAcknowledged()
        viewModel.onPermissionsChecked(true)
        assertEquals(OpenLoopUiState.ReadyToCapture, viewModel.uiState.value)
    }

    @Test
    fun `rationale flow ending in denial reaches PermissionDenied`() {
        // Full path: denied-once → rationale → acknowledge → system denial.
        viewModel.showPermissionRationale()
        viewModel.onRationaleAcknowledged()
        viewModel.onPermissionsChecked(false)
        assertEquals(OpenLoopUiState.PermissionDenied, viewModel.uiState.value)
    }

    @Test
    fun `resetToCapture transitions state back to ReadyToCapture`() {
        viewModel.onPermissionsChecked(false) // PermissionDenied
        viewModel.resetToCapture()
        assertEquals(OpenLoopUiState.ReadyToCapture, viewModel.uiState.value)
    }

    @Test
    fun `startBurstCapture when not ready does not transition or call camera`() {
        // State is Onboarding (first-time user default), which is not ReadyToCapture
        viewModel.startBurstCapture(cameraManager)
        assertEquals(OpenLoopUiState.Onboarding, viewModel.uiState.value)
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

            assertEquals(OpenLoopUiState.Recording, viewModel.uiState.value)
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

            assertEquals(OpenLoopUiState.ReadyToCapture, viewModel.uiState.value)
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

            assertEquals(OpenLoopUiState.ReadyToCapture, viewModel.uiState.value)
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
        assertTrue("expected Trim, was $state", state is OpenLoopUiState.Trim)
        val source = (state as OpenLoopUiState.Trim).source
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

        assertEquals(OpenLoopUiState.ReadyToCapture, viewModel.uiState.value)
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

    // ── Boomerang editor (slice 03) ──

    @Test
    fun `onNextFromTrim opens the editor and pre-generates the default reversed clip`() =
        runTest(mainDispatcherRule.testDispatcher) {
            enterTrimState()

            viewModel.onNextFromTrim()
            awaitEditorReverseReady()

            val state = viewModel.uiState.value
            assertTrue("expected BoomerangEditor, was $state", state is OpenLoopUiState.BoomerangEditor)
            // Editor opens on the default direction; the reversed clip is generated eagerly for preview.
            assertEquals(BoomerangMode.FORWARD_THEN_REVERSE, viewModel.editorTabState.value.mode)
            assertEquals(1, fakeVideoProcessor.ensureReversedCount)
            assertNotNull(viewModel.editorTabState.value.reversedFile)
            // Nothing rendered or promoted yet — saving happens from the editor's checkmark.
            assertEquals(0, fakeVideoProcessor.renderCount)
            assertTrue(fakeVideoStorage.saved.isEmpty())
        }

    @Test
    fun `updateMode to FORWARD does not generate a reversed clip`() =
        runTest(mainDispatcherRule.testDispatcher) {
            enterTrimState()
            viewModel.onNextFromTrim() // default FORWARD_THEN_REVERSE → one generation
            awaitEditorReverseReady()
            val baseline = fakeVideoProcessor.ensureReversedCount

            viewModel.updateMode(BoomerangMode.FORWARD)
            advanceUntilIdle()

            assertEquals(BoomerangMode.FORWARD, viewModel.editorTabState.value.mode)
            assertEquals("FORWARD needs no reverse", baseline, fakeVideoProcessor.ensureReversedCount)
        }

    @Test
    fun `reversed clip is generated once and reused across reverse-containing modes`() =
        runTest(mainDispatcherRule.testDispatcher) {
            enterTrimState()
            viewModel.onNextFromTrim() // FORWARD_THEN_REVERSE → generate once
            awaitEditorReverseReady()

            viewModel.updateMode(BoomerangMode.REVERSE)
            viewModel.updateMode(BoomerangMode.REVERSE_THEN_FORWARD)
            advanceUntilIdle()

            // The cached reversedFile is reused (guard in ensureReversedSegment) — no re-generation.
            assertEquals(1, fakeVideoProcessor.ensureReversedCount)
        }

    @Test
    fun `reverse preview times out when ensureReversed never completes`() =
        runTest(mainDispatcherRule.testDispatcher) {
            OpenLoopViewModel.reversePreviewTimeoutDisabledForTests = false
            OpenLoopViewModel.reversePreviewTimeoutMsOverride = null
            enterTrimState()
            fakeVideoProcessor.hangReverse = true
            viewModel.onNextFromTrim()
            runCurrent()
            assertEquals(EditorLoadingKind.TRIMMING, viewModel.editorTabState.value.previewLoading)

            advanceTimeBy(OpenLoopViewModel.REVERSE_PREVIEW_TIMEOUT_MS + 500)
            advanceUntilIdle()

            val tab = viewModel.editorTabState.value
            assertFalse(
                "wedged reverse must not block the editor",
                tab.reverseFailed,
            )
            assertEquals(BoomerangMode.FORWARD, tab.mode)
            assertNull(tab.previewLoading)
            assertNotNull(tab.reverseSupportReport)
        }

    @Test
    fun `reverse generation failure flags reverseFailed and clears the loading shimmer`() =
        runTest(mainDispatcherRule.testDispatcher) {
            enterTrimState()
            fakeVideoProcessor.failReverse = true

            viewModel.onNextFromTrim() // default FORWARD_THEN_REVERSE → reverse generation runs + fails
            // Reverse runs on Dispatchers.IO (real pool in JVM tests) — poll until the UI updates.
            withTimeout(5_000) {
                while (viewModel.editorTabState.value.previewLoading != null) {
                    delay(10)
                }
            }

            val tab = viewModel.editorTabState.value
            assertFalse("user can continue with forward-only preview", tab.reverseFailed)
            assertEquals(BoomerangMode.FORWARD, tab.mode)
            assertNull(tab.previewLoading)
            assertNull(tab.reversedFile)
        }

    @Test
    fun `retrying reverse after a failure clears the flag and succeeds`() =
        runTest(mainDispatcherRule.testDispatcher) {
            enterTrimState()
            fakeVideoProcessor.failReverse = true
            viewModel.onNextFromTrim()
            awaitReversePreviewFailedFallback()
            assertEquals(BoomerangMode.FORWARD, viewModel.editorTabState.value.mode)

            fakeVideoProcessor.failReverse = false
            viewModel.retryReverseSegment()
            awaitEditorReverseReady()

            assertFalse("retry clears the failure flag", viewModel.editorTabState.value.reverseFailed)
            assertEquals(BoomerangMode.FORWARD_THEN_REVERSE, viewModel.editorTabState.value.mode)
            assertNotNull("retry produces the reversed clip", viewModel.editorTabState.value.reversedFile)
        }

    @Test
    fun `backToTrim returns to Trim preserving the trim selection`() =
        runTest(mainDispatcherRule.testDispatcher) {
            enterTrimState()
            viewModel.updateTrim(500L, 2_500L)
            viewModel.onNextFromTrim()
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value is OpenLoopUiState.BoomerangEditor)

            viewModel.backToTrim()

            assertTrue("expected Trim, was ${viewModel.uiState.value}", viewModel.uiState.value is OpenLoopUiState.Trim)
            assertEquals(500L, viewModel.editorState.value!!.trimStartMs)
            assertEquals(2_500L, viewModel.editorState.value!!.trimEndMs)
        }

    @Test
    fun `returning from trim via toolbar preserves editor session and cached reverse`() =
        runTest(mainDispatcherRule.testDispatcher) {
            enterTrimState()
            viewModel.onNextFromTrim()
            awaitEditorReverseReady()
            val reversed = viewModel.editorTabState.value.reversedFile
            assertNotNull(reversed)
            viewModel.updateSpeed(0.5f)
            val reverseCountAfterFirstOpen = fakeVideoProcessor.ensureReversedCount

            viewModel.backToTrim()
            viewModel.onNextFromTrim(EditorTab.SPEED)
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value is OpenLoopUiState.BoomerangEditor)
            assertEquals(EditorTab.SPEED, viewModel.editorTabState.value.activeTab)
            assertEquals(0.5f, viewModel.editorTabState.value.speed, 0f)
            assertEquals(reversed, viewModel.editorTabState.value.reversedFile)
            assertEquals(
                "toolbar return must not restart reverse when cache is warm",
                reverseCountAfterFirstOpen,
                fakeVideoProcessor.ensureReversedCount,
            )
            assertNull(viewModel.editorTabState.value.previewLoading)
        }

    @Test
    fun `importing a library clip resets editor tab state for a fresh session`() =
        runTest(mainDispatcherRule.testDispatcher) {
            enterTrimState()
            viewModel.onNextFromTrim()
            awaitEditorReverseReady()
            viewModel.updateSpeed(0.5f)
            assertNotNull(viewModel.editorTabState.value.reversedFile)

            fakeVideoImporter.probeMs = 5_000L
            fakeVideoStorage.fixedDurationMs = 5_000L

            viewModel.onVideoPicked(fakeUri)
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value is OpenLoopUiState.Trim)
            assertEquals(2.0f, viewModel.editorTabState.value.speed, 0f)
            assertNull(viewModel.editorTabState.value.reversedFile)
            assertNull(viewModel.editorTabState.value.previewLoading)
        }

    @Test
    fun `ensureReversedSegment does not restart reverse while a job is already in flight`() =
        runTest(mainDispatcherRule.testDispatcher) {
            enterTrimState()
            fakeVideoProcessor.reverseGate = CompletableDeferred()
            viewModel.onNextFromTrim()
            advanceUntilIdle()
            assertEquals(1, fakeVideoProcessor.ensureReversedCount)
            assertEquals(EditorLoadingKind.TRIMMING, viewModel.editorTabState.value.previewLoading)

            viewModel.ensureReversedSegment(EditorLoadingKind.LOOPIFYING)
            runCurrent()
            assertEquals(
                "must not cancel and restart an active reverse",
                1,
                fakeVideoProcessor.ensureReversedCount,
            )

            fakeVideoProcessor.releaseReverseGate()
            var spins = 0
            while (viewModel.editorTabState.value.reversedFile == null && spins++ < 200) {
                Thread.sleep(25)
                runCurrent()
            }
            advanceUntilIdle()
            assertNotNull(viewModel.editorTabState.value.reversedFile)
            assertNull(viewModel.editorTabState.value.previewLoading)
        }

    @Test
    fun `updateFilter during reverse prep keeps Trimming overlay`() =
        runTest(mainDispatcherRule.testDispatcher) {
            enterTrimState()
            fakeVideoProcessor.reverseGate = CompletableDeferred()
            viewModel.onNextFromTrim()
            runCurrent()
            assertEquals(EditorLoadingKind.TRIMMING, viewModel.editorTabState.value.previewLoading)

            viewModel.updateFilter(VideoFilter.WARM)
            runCurrent()
            assertEquals(EditorLoadingKind.TRIMMING, viewModel.editorTabState.value.previewLoading)

            fakeVideoProcessor.releaseReverseGate()
            advanceUntilIdle()
        }

    @Test
    fun `updateTrim with unchanged handles is a no-op in the editor`() =
        runTest(mainDispatcherRule.testDispatcher) {
            enterTrimState()
            viewModel.updateTrim(500L, 2_500L)
            viewModel.onNextFromTrim()
            advanceUntilIdle()
            val reverseCount = fakeVideoProcessor.ensureReversedCount

            viewModel.updateTrim(500L, 2_500L)
            advanceUntilIdle()

            assertEquals(reverseCount, fakeVideoProcessor.ensureReversedCount)
            assertNull(viewModel.editorTabState.value.previewLoading)
        }

    @Test
    fun `saveBoomerang renders the chosen direction, saves, returns to capture emitting Share with the rendered file`() =
        runTest(mainDispatcherRule.testDispatcher) {
            enterTrimState()
            viewModel.onNextFromTrim()
            advanceUntilIdle()
            val events = mutableListOf<BoomerangEvent>()
            val job = backgroundScope.launch { viewModel.events.toList(events) }

            viewModel.saveBoomerang()
            advanceUntilIdle()

            assertEquals(OpenLoopUiState.ReadyToCapture, viewModel.uiState.value)
            assertEquals(1, fakeRenderScheduler.enqueueCount)
            assertEquals(1, fakeVideoProcessor.renderCount)

            // One RAW (promoted) + one BOOMERANG (registered), boomerang points at the raw.
            val raw = fakeVideoStorage.saved.single { it.kind == VideoKind.RAW }
            val boomerang = fakeVideoStorage.saved.single { it.kind == VideoKind.BOOMERANG }
            assertEquals(raw.id, boomerang.sourceRawId)
            assertEquals(1, fakeVideoStorage.discardedScratches.size) // scratch cleaned up after save
            assertNull(viewModel.editorState.value)

            // Success now emits Share(file) — the rendered boomerang handed to the share sheet (slice
            // 06) — and NOT Saved. The Saved snackbar is deferred until the share sheet returns
            // (onShareSheetClosed). The shared file must be the registered boomerang's path.
            val share = events.filterIsInstance<BoomerangEvent.Share>().single()
            assertEquals(boomerang.videoPath, share.file.absolutePath)
            assertFalse("Saved must be deferred, not emitted during save", events.contains(BoomerangEvent.Saved))

            job.cancel()
        }

    @Test
    fun `onShareSheetClosed emits the deferred Saved snackbar event`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val events = mutableListOf<BoomerangEvent>()
            val job = backgroundScope.launch { viewModel.events.toList(events) }

            viewModel.onShareSheetClosed()
            advanceUntilIdle()

            assertTrue("expected a Saved event, got $events", events.contains(BoomerangEvent.Saved))
            job.cancel()
        }

    @Test
    fun `saveBoomerang on render failure returns to the editor with direction preserved, emitting Failed`() =
        runTest(mainDispatcherRule.testDispatcher) {
            enterTrimState()
            viewModel.onNextFromTrim()
            advanceUntilIdle()
            viewModel.updateMode(BoomerangMode.REVERSE_THEN_FORWARD)
            fakeVideoProcessor.failRender = true
            val events = mutableListOf<BoomerangEvent>()
            val job = backgroundScope.launch { viewModel.events.toList(events) }

            viewModel.saveBoomerang()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue("expected BoomerangEditor after failure, was $state", state is OpenLoopUiState.BoomerangEditor)
            assertTrue(events.contains(BoomerangEvent.Failed))
            // No boomerang registered; the chosen direction survives the failure.
            assertTrue(fakeVideoStorage.saved.none { it.kind == VideoKind.BOOMERANG })
            assertEquals(BoomerangMode.REVERSE_THEN_FORWARD, viewModel.editorTabState.value.mode)

            job.cancel()
        }

    // ── Speed tab (slice 04) ──

    @Test
    fun `updateSpeed updates editorTabState speed`() {
        viewModel.updateSpeed(1.5f)
        assertEquals(1.5f, viewModel.editorTabState.value.speed, 0f)
    }

    @Test
    fun `updateSpeed clamps above the maximum to 3x`() {
        viewModel.updateSpeed(5.0f)
        assertEquals(OpenLoopViewModel.MAX_SPEED, viewModel.editorTabState.value.speed, 0f)
    }

    @Test
    fun `updateSpeed clamps below the minimum to 0_25x`() {
        viewModel.updateSpeed(0.1f)
        assertEquals(OpenLoopViewModel.MIN_SPEED, viewModel.editorTabState.value.speed, 0f)
    }

    @Test
    fun `editor opens at the default 2x speed on the Direction tab`() =
        runTest(mainDispatcherRule.testDispatcher) {
            enterTrimState()
            viewModel.onNextFromTrim()
            advanceUntilIdle()

            assertEquals(OpenLoopViewModel.DEFAULT_SPEED, viewModel.editorTabState.value.speed, 0f)
            assertEquals(EditorTab.DIRECTION, viewModel.editorTabState.value.activeTab)
        }

    @Test
    fun `switchTab updates the active tab`() {
        viewModel.switchTab(EditorTab.SPEED)
        assertEquals(EditorTab.SPEED, viewModel.editorTabState.value.activeTab)

        viewModel.switchTab(EditorTab.DIRECTION)
        assertEquals(EditorTab.DIRECTION, viewModel.editorTabState.value.activeTab)
    }

    @Test
    fun `saveBoomerang passes the selected speed to the processor`() =
        runTest(mainDispatcherRule.testDispatcher) {
            enterTrimState()
            viewModel.onNextFromTrim()
            advanceUntilIdle()
            viewModel.updateSpeed(0.5f)

            viewModel.saveBoomerang()
            advanceUntilIdle()

            assertEquals(0.5f, fakeVideoProcessor.lastRenderSpeed, 0f)
        }

    // ── Looks tab (slice 05) ──

    @Test
    fun `updateFilter updates editorTabState filter`() {
        viewModel.updateFilter(VideoFilter.WARM)
        assertEquals(VideoFilter.WARM, viewModel.editorTabState.value.filter)
    }

    @Test
    fun `editor opens on the Original look`() =
        runTest(mainDispatcherRule.testDispatcher) {
            enterTrimState()
            viewModel.onNextFromTrim()
            advanceUntilIdle()

            assertEquals(VideoFilter.ORIGINAL, viewModel.editorTabState.value.filter)
        }

    @Test
    fun `saveBoomerang passes the selected filter to the processor`() =
        runTest(mainDispatcherRule.testDispatcher) {
            enterTrimState()
            viewModel.onNextFromTrim()
            advanceUntilIdle()
            viewModel.updateFilter(VideoFilter.NOIR)

            viewModel.saveBoomerang()
            advanceUntilIdle()

            assertEquals(VideoFilter.NOIR, fakeVideoProcessor.lastRenderFilter)
        }

    @Test
    fun `discardTrim discards the scratch and returns to ReadyToCapture`() =
        runTest(mainDispatcherRule.testDispatcher) {
        enterTrimState()

        viewModel.discardTrim()
        advanceUntilIdle()

        assertEquals(OpenLoopUiState.ReadyToCapture, viewModel.uiState.value)
        assertEquals(1, fakeVideoStorage.discardedScratches.size)
        assertNull(viewModel.editorState.value)
        }

    // ── Gallery Navigation Tests ──

    @Test
    fun `navigateToGallery transitions state to Gallery`() {
        viewModel.navigateToGallery()

        assertEquals(OpenLoopUiState.Gallery, viewModel.uiState.value)
    }

    @Test
    fun `navigateToGallery loads videos from storage`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // Seed storage with a saved clip, then enter the gallery.
            fakeVideoStorage.promoteScratchToRaw(fakeVideoStorage.createScratchCapture())

            viewModel.navigateToGallery()
            advanceUntilIdle() // the gallery load now runs on a launched coroutine

            assertEquals(OpenLoopUiState.Gallery, viewModel.uiState.value)
            assertEquals(1, viewModel.recordedVideos.value.size)
        }

    @Test
    fun `navigateBackFromGallery transitions state to ReadyToCapture`() {
        viewModel.navigateToGallery()
        assertEquals(OpenLoopUiState.Gallery, viewModel.uiState.value)

        viewModel.navigateBackFromGallery()
        assertEquals(OpenLoopUiState.ReadyToCapture, viewModel.uiState.value)
    }

    @Test
    fun `loadRecordedVideos with empty storage returns empty list`() {
        viewModel.loadRecordedVideos()

        assertTrue(viewModel.recordedVideos.value.isEmpty())
    }

    @Test
    fun `recordedVideos flow starts as empty list`() {
        assertTrue(viewModel.recordedVideos.value.isEmpty())
    }

    @Test
    fun `shareLoop emits Share for the gallery clip without a Saved snackbar follow-up`() =
        runTest(mainDispatcherRule.testDispatcher) {
            seedAndLoad(1)
            val clip = viewModel.recordedVideos.value.single()
            val events = mutableListOf<BoomerangEvent>()
            backgroundScope.launch { viewModel.events.toList(events) }

            viewModel.shareLoop(clip)
            advanceUntilIdle()

            val share = events.filterIsInstance<BoomerangEvent.Share>().single()
            assertEquals(File(clip.videoPath), share.file)
            assertFalse(share.showSavedSnackbarAfterDismiss)
        }

    // ── Deferred deletion + Undo (Issue #35) ──

    /** Seed [count] raw clips into storage and load them so the gallery flows are populated. */
    private suspend fun seedAndLoad(count: Int) {
        repeat(count) {
            fakeVideoStorage.promoteScratchToRaw(fakeVideoStorage.createScratchCapture())
        }
        viewModel.loadRecordedVideos()
    }

    @Test
    fun `requestDeleteVideos hides ids from visibleVideos and emits LoopsDeleted`() =
        runTest(mainDispatcherRule.testDispatcher) {
            seedAndLoad(2)
            advanceUntilIdle()
            // visibleVideos is WhileSubscribed — keep a hot collector so .value reflects the combine.
            val visibleCollector = backgroundScope.launch { viewModel.visibleVideos.collect {} }
            val events = mutableListOf<BoomerangEvent>()
            val eventCollector = backgroundScope.launch { viewModel.events.toList(events) }
            advanceUntilIdle()
            assertEquals(2, viewModel.visibleVideos.value.size)

            val toDelete = viewModel.recordedVideos.value.first()
            viewModel.requestDeleteVideos(listOf(toDelete))
            advanceUntilIdle()

            // Optimistically hidden, but NOT yet deleted from storage (deferred until commit).
            assertTrue(toDelete.id in viewModel.pendingDeletionIds.value)
            assertEquals(1, viewModel.visibleVideos.value.size)
            assertFalse(viewModel.visibleVideos.value.contains(toDelete))
            assertEquals(2, fakeVideoStorage.saved.size) // still on disk
            assertEquals(LoopsDeleted_count(events), 1)

            visibleCollector.cancel()
            eventCollector.cancel()
        }

    @Test
    fun `undoPendingDeletion restores the hidden tiles without deleting anything`() =
        runTest(mainDispatcherRule.testDispatcher) {
            seedAndLoad(2)
            advanceUntilIdle()
            val visibleCollector = backgroundScope.launch { viewModel.visibleVideos.collect {} }
            advanceUntilIdle()

            val toDelete = viewModel.recordedVideos.value.first()
            viewModel.requestDeleteVideos(listOf(toDelete))
            advanceUntilIdle()
            assertEquals(1, viewModel.visibleVideos.value.size)

            viewModel.undoPendingDeletion()
            advanceUntilIdle()

            assertTrue(viewModel.pendingDeletionIds.value.isEmpty())
            assertEquals(2, viewModel.visibleVideos.value.size) // tiles reappear
            assertEquals(2, fakeVideoStorage.saved.size) // nothing was deleted

            visibleCollector.cancel()
        }

    @Test
    fun `commitPendingDeletion deletes each file from storage and reloads`() =
        runTest(mainDispatcherRule.testDispatcher) {
            seedAndLoad(3)
            advanceUntilIdle()
            val visibleCollector = backgroundScope.launch { viewModel.visibleVideos.collect {} }
            advanceUntilIdle()

            val toDelete = viewModel.recordedVideos.value.take(2)
            viewModel.requestDeleteVideos(toDelete)
            advanceUntilIdle()

            viewModel.commitPendingDeletion()
            advanceUntilIdle()

            // The two files are gone from storage; the pending set cleared; the remainder is reloaded.
            assertEquals(1, fakeVideoStorage.saved.size)
            assertTrue(viewModel.pendingDeletionIds.value.isEmpty())
            assertEquals(1, viewModel.recordedVideos.value.size)
            assertFalse(viewModel.recordedVideos.value.any { it in toDelete })

            visibleCollector.cancel()
        }

    @Test
    fun `requesting a new deletion commits the prior pending batch first`() =
        runTest(mainDispatcherRule.testDispatcher) {
            seedAndLoad(3)
            advanceUntilIdle()
            val visibleCollector = backgroundScope.launch { viewModel.visibleVideos.collect {} }
            advanceUntilIdle()

            val first = viewModel.recordedVideos.value[0]
            val second = viewModel.recordedVideos.value[1]

            viewModel.requestDeleteVideos(listOf(first))
            advanceUntilIdle()
            // Supersede: a new delete commits the prior (first) batch before pending the new one.
            viewModel.requestDeleteVideos(listOf(second))
            advanceUntilIdle()

            // `first` was committed (gone from storage); `second` is the new pending batch (still on disk).
            assertFalse(fakeVideoStorage.saved.contains(first))
            assertTrue(fakeVideoStorage.saved.contains(second))
            assertEquals(setOf(second.id), viewModel.pendingDeletionIds.value)

            visibleCollector.cancel()
        }

    private suspend fun TestScope.awaitReversePreviewFailedFallback() {
        advanceUntilIdle()
        var spins = 0
        while (viewModel.editorTabState.value.mode != BoomerangMode.FORWARD && spins++ < 200) {
            Thread.sleep(25)
            runCurrent()
        }
        advanceUntilIdle()
    }

    /** [ensureReversed] runs on [Dispatchers.IO]; yield real time so the pool can finish. */
    private suspend fun TestScope.awaitEditorReverseReady() {
        advanceUntilIdle()
        var spins = 0
        while (spins++ < 200) {
            val tab = viewModel.editorTabState.value
            val waiting = tab.previewLoading != null ||
                (tab.mode.needsReverse && tab.reversedFile == null)
            if (!waiting) break
            Thread.sleep(25)
            runCurrent()
        }
        advanceUntilIdle()
    }

    /** Count carried by the single [BoomerangEvent.LoopsDeleted] in [events] (fails if absent). */
    private fun LoopsDeleted_count(events: List<BoomerangEvent>): Int =
        events.filterIsInstance<BoomerangEvent.LoopsDeleted>().single().count

    // ── Import from library (slice 07) ──

    /** Drive a successful import so the ViewModel lands on Trim with an imported editor session. */
    private fun enterTrimViaImport(durationMs: Long = 3_000L) {
        fakeVideoImporter.probeMs = durationMs
        fakeVideoImporter.copyOk = true
        fakeVideoStorage.fixedDurationMs = durationMs
        viewModel.onVideoPicked(fakeUri) // runs eagerly under the unconfined main dispatcher
    }

    @Test
    fun `init prunes stale scratch with the 24h threshold`() {
        // The ViewModel constructed in setUp() prunes on init (D-8).
        assertEquals(1, fakeVideoStorage.pruneCallCount)
        assertEquals(OpenLoopViewModel.STALE_SCRATCH_MAX_AGE_MS, fakeVideoStorage.lastPruneOlderThanMs)
    }

    @Test
    fun `onVideoPicked null is a no-op (user backed out of the picker)`() {
        viewModel.navigateToGallery()
        viewModel.onVideoPicked(null)

        assertEquals(OpenLoopUiState.Gallery, viewModel.uiState.value)
        assertNull(viewModel.editorState.value)
        assertEquals(0, fakeVideoImporter.importCallCount)
        assertEquals(0, fakeVideoStorage.createScratchCount)
    }

    @Test
    fun `onVideoPicked with a short clip copies to scratch and routes to Trim`() =
        runTest(mainDispatcherRule.testDispatcher) {
            fakeVideoImporter.probeMs = 5_000L
            fakeVideoStorage.fixedDurationMs = 5_000L

            viewModel.onVideoPicked(fakeUri)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue("expected Trim, was $state", state is OpenLoopUiState.Trim)
            assertTrue((state as OpenLoopUiState.Trim).source is EditorSource.ScratchClip)

            val editor = viewModel.editorState.value
            assertNotNull(editor)
            assertEquals(0L, editor!!.trimStartMs)
            assertEquals(5_000L, editor.trimEndMs) // whole clip; no window cap
            assertEquals(5_000L, editor.sourceDurationMs)
            assertEquals(1, fakeVideoImporter.importCallCount)
        }

    @Test
    fun `onVideoPicked just within the grace window is accepted`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // 30.5 s ≤ 30 s + 1 s grace → accepted, not rejected.
            fakeVideoImporter.probeMs = 30_500L
            fakeVideoStorage.fixedDurationMs = 30_500L

            viewModel.onVideoPicked(fakeUri)
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value is OpenLoopUiState.Trim)
            assertEquals(1, fakeVideoImporter.importCallCount)
        }

    @Test
    fun `onVideoPicked over the limit warns and copies nothing`() =
        runTest(mainDispatcherRule.testDispatcher) {
            fakeVideoImporter.probeMs = 32_000L // > 30 s + 1 s grace
            val events = mutableListOf<BoomerangEvent>()
            val job = backgroundScope.launch { viewModel.events.toList(events) }

            viewModel.onVideoPicked(fakeUri)
            advanceUntilIdle()

            assertEquals(OpenLoopUiState.Gallery, viewModel.uiState.value)
            assertTrue(events.contains(BoomerangEvent.ImportTooLong))
            assertTrue(viewModel.showImportTooLongDialog.value)
            // Caught before any copy or scratch mint.
            assertEquals(0, fakeVideoImporter.importCallCount)
            assertEquals(0, fakeVideoStorage.createScratchCount)
            assertNull(viewModel.editorState.value)
            job.cancel()
        }

    @Test
    fun `onVideoPicked when post-copy duration exceeds the limit warns and discards the scratch`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // Pre-copy probe under-reads; post-copy durationOf is authoritative.
            fakeVideoImporter.probeMs = 25_000L
            fakeVideoStorage.fixedDurationMs = 45_000L
            val events = mutableListOf<BoomerangEvent>()
            val job = backgroundScope.launch { viewModel.events.toList(events) }

            viewModel.onVideoPicked(fakeUri)
            advanceUntilIdle()

            assertEquals(OpenLoopUiState.Gallery, viewModel.uiState.value)
            assertTrue(events.contains(BoomerangEvent.ImportTooLong))
            assertTrue(viewModel.showImportTooLongDialog.value)
            assertEquals(1, fakeVideoImporter.importCallCount)
            assertEquals(1, fakeVideoStorage.discardedScratches.size)
            assertNull(viewModel.editorState.value)
            job.cancel()
        }

    @Test
    fun `onVideoPicked with unreadable duration fails to import without copying`() =
        runTest(mainDispatcherRule.testDispatcher) {
            fakeVideoImporter.probeMs = 0L
            val events = mutableListOf<BoomerangEvent>()
            val job = backgroundScope.launch { viewModel.events.toList(events) }

            viewModel.onVideoPicked(fakeUri)
            advanceUntilIdle()

            assertEquals(OpenLoopUiState.Gallery, viewModel.uiState.value)
            assertTrue(events.contains(BoomerangEvent.ImportFailed))
            assertEquals(0, fakeVideoImporter.importCallCount)
            assertNull(viewModel.editorState.value)
            job.cancel()
        }

    @Test
    fun `onVideoPicked when the copy fails reports failure and discards the scratch`() =
        runTest(mainDispatcherRule.testDispatcher) {
            fakeVideoImporter.probeMs = 4_000L
            fakeVideoImporter.copyOk = false
            val events = mutableListOf<BoomerangEvent>()
            val job = backgroundScope.launch { viewModel.events.toList(events) }

            viewModel.onVideoPicked(fakeUri)
            advanceUntilIdle()

            assertEquals(OpenLoopUiState.Gallery, viewModel.uiState.value)
            assertTrue(events.contains(BoomerangEvent.ImportFailed))
            assertEquals(1, fakeVideoStorage.discardedScratches.size) // no orphan scratch
            assertNull(viewModel.editorState.value)
            job.cancel()
        }

    @Test
    fun `onVideoPicked with an unreadable post-copy duration discards the scratch and fails`() =
        runTest(mainDispatcherRule.testDispatcher) {
            fakeVideoImporter.probeMs = 4_000L     // passes the pre-copy probe…
            fakeVideoStorage.fixedDurationMs = 0L  // …but durationOf on the copy reads 0
            val events = mutableListOf<BoomerangEvent>()
            val job = backgroundScope.launch { viewModel.events.toList(events) }

            viewModel.onVideoPicked(fakeUri)
            advanceUntilIdle()

            assertEquals(OpenLoopUiState.Gallery, viewModel.uiState.value)
            assertTrue(events.contains(BoomerangEvent.ImportFailed))
            assertEquals(1, fakeVideoStorage.discardedScratches.size)
            assertNull(viewModel.editorState.value)
            job.cancel()
        }

    @Test
    fun `discarding an imported clip returns to the gallery`() =
        runTest(mainDispatcherRule.testDispatcher) {
            enterTrimViaImport()
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value is OpenLoopUiState.Trim)

            viewModel.discardTrim()
            advanceUntilIdle()

            // Imported sessions return to the gallery they came from, not the camera (slice 07).
            assertEquals(OpenLoopUiState.Gallery, viewModel.uiState.value)
            assertEquals(1, fakeVideoStorage.discardedScratches.size)
            assertNull(viewModel.editorState.value)
        }

    @Test
    fun `saving an imported boomerang returns to the gallery and still emits Share`() =
        runTest(mainDispatcherRule.testDispatcher) {
            enterTrimViaImport()
            advanceUntilIdle()
            viewModel.onNextFromTrim()
            advanceUntilIdle()
            val events = mutableListOf<BoomerangEvent>()
            val job = backgroundScope.launch { viewModel.events.toList(events) }

            viewModel.saveBoomerang()
            advanceUntilIdle()

            assertEquals(OpenLoopUiState.Gallery, viewModel.uiState.value)
            assertEquals(1, fakeVideoProcessor.renderCount)
            assertTrue("share sheet still pops for an import", events.any { it is BoomerangEvent.Share })
            job.cancel()
        }

    @Test
    fun `a fresh capture still returns to the camera on save (not the gallery)`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // Guard the import flag doesn't leak into the capture path.
            enterTrimState()
            viewModel.onNextFromTrim()
            advanceUntilIdle()

            viewModel.saveBoomerang()
            advanceUntilIdle()

            assertEquals(OpenLoopUiState.ReadyToCapture, viewModel.uiState.value)
        }
}
