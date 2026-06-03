package io.github.stozo04.openloop.ui

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.stozo04.openloop.camera.CameraManager
import io.github.stozo04.openloop.data.RecordedVideo
import io.github.stozo04.openloop.data.ScratchCapture
import io.github.stozo04.openloop.data.UserPreferencesRepository
import io.github.stozo04.openloop.data.VideoImporter
import io.github.stozo04.openloop.data.VideoStorageRepository
import io.github.stozo04.openloop.BuildConfig
import io.github.stozo04.openloop.media.BoomerangMode
import io.github.stozo04.openloop.media.VideoFilter
import io.github.stozo04.openloop.media.VideoProcessor
import io.github.stozo04.openloop.diagnostics.AnalyticsReporter
import io.github.stozo04.openloop.diagnostics.NoOpAnalyticsReporter
import io.github.stozo04.openloop.diagnostics.ReverseCrashlytics
import io.github.stozo04.openloop.media.ReversePreviewLog
import io.github.stozo04.openloop.media.SAMSUNG_PREVIEW_REVERSE_MAX_SHORT_SIDE
import io.github.stozo04.openloop.media.isSamsungDevice
import io.github.stozo04.openloop.media.needsReverse
import io.github.stozo04.openloop.work.BoomerangRenderRequest
import io.github.stozo04.openloop.work.BoomerangRenderScheduler
import io.github.stozo04.openloop.work.BoomerangRenderWorkResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import androidx.camera.video.VideoRecordEvent

/**
 * One-shot events the [OpenLoopViewModel] emits for transient UI (snackbars). Delivered over a
 * [Channel] (not a StateFlow) so they fire exactly once and never replay on recomposition.
 */
sealed interface BoomerangEvent {
    /**
     * Boomerang rendered + saved; carries the rendered [file] (a `filesDir/videos/boom_*.mp4` loop) so the
     * UI can hand it to the Android share sheet (slice 06). The "Saved — view in gallery" snackbar is
     * deferred until the share sheet is dismissed (see [BoomerangEvent.Saved] / [onShareSheetClosed]).
     */
    data class Share(
        val file: File,
        /** When false (gallery re-share), the share sheet still opens but no "Saved" snackbar follows. */
        val showSavedSnackbarAfterDismiss: Boolean = true,
    ) : BoomerangEvent
    /**
     * Show the "Saved — view in gallery" snackbar (with a "View" action into the gallery). Emitted
     * *after* the share sheet returns control — see [onShareSheetClosed] — so the snackbar isn't wasted
     * behind the chooser.
     */
    object Saved : BoomerangEvent
    /** Boomerang render failed. Snackbar invites a retry; the trim selection is preserved. */
    object Failed : BoomerangEvent

    /**
     * A picked library video was longer than the import limit (slice 07). Drives the friendly
     * "That clip's a bit long" dialog; nothing was copied.
     */
    object ImportTooLong : BoomerangEvent

    /**
     * Importing a picked library video failed for a non-length reason — unreadable/revoked URI, an
     * unreadable duration, or a copy I/O error (slice 07). Drives a "Couldn't import that video."
     * snackbar; the user is returned to the gallery, never wedged.
     */
    object ImportFailed : BoomerangEvent

    /**
     * Preview reverse failed or timed out; editor fell back to [BoomerangMode.FORWARD] so the user
     * can preview and save. Ping-pong can be retried from the Loop direction tab.
     */
    object ReversePreviewFallbackForward : BoomerangEvent

    /**
     * One or more gallery loops were marked for deletion (Issue #35). Drives the Undo snackbar; the
     * real file delete is **deferred** until the snackbar is dismissed (see [commitPendingDeletion]).
     * [count] is how many loops the user removed in this batch, so the snackbar can pluralize.
     */
    data class LoopsDeleted(val count: Int) : BoomerangEvent
}

class OpenLoopViewModel(
    private val userPreferencesRepository: UserPreferencesRepository,
    private val videoStorage: VideoStorageRepository,
    private val videoProcessor: VideoProcessor,
    private val videoImporter: VideoImporter,
    private val renderScheduler: BoomerangRenderScheduler,
    // 6th param wired in for the staged Firebase Analytics rollout — see
    // docs/active/firebase-analytics/IMPLEMENTATION.md. Option 1 ships the abstraction only;
    // options 2 (screen tracking) and 3 (custom events) populate call sites incrementally. The
    // production impl comes from FirebaseAnalyticsReporterImpl.create(applicationContext); tests and
    // CI builds without google-services.json fall back to NoOpAnalyticsReporter.
    private val analytics: AnalyticsReporter = NoOpAnalyticsReporter,
    /**
     * Proactive low-memory probe (production: [MemoryPressure.lowMemoryProbe] →
     * `ActivityManager.getMemoryInfo().lowMemory`). Polled at editor entry and before applying a
     * non-Original look, because Android 14+ no longer delivers the foreground `onTrimMemory`
     * pressure levels (editor-memory-oom WS-3, PR #58 review). Injected as a lambda so the
     * ViewModel stays Context-free (Lesson 004) and tests can flip it deterministically.
     */
    private val isLowMemoryNow: () -> Boolean = { false },
) : ViewModel() {

    // Start in Initializing — DataStore read decides Onboarding vs CheckingPermissions
    private val _uiState = MutableStateFlow<OpenLoopUiState>(OpenLoopUiState.Initializing)
    val uiState: StateFlow<OpenLoopUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val onboardingDone = userPreferencesRepository.hasCompletedOnboarding.first()
            _uiState.value = if (onboardingDone) {
                OpenLoopUiState.CheckingPermissions
            } else {
                OpenLoopUiState.Onboarding
            }
        }
        // Best-effort prune of orphaned scratch copies older than 24 h (parent D-8). Imports raise
        // scratch churn (an abandoned import can leave a whole library-video-sized copy), so reclaim
        // it deterministically at launch rather than waiting on Android's cache eviction. Fire-and-
        // forget on Dispatchers.IO inside the repo — never blocks startup or the UI thread.
        viewModelScope.launch {
            try {
                videoStorage.pruneStaleScratch(STALE_SCRATCH_MAX_AGE_MS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("OpenLoopViewModel", "Stale-scratch prune failed", e)
            }
        }
    }

    fun onOnboardingCompleted() {
        _uiState.value = OpenLoopUiState.CheckingPermissions
        viewModelScope.launch {
            try {
                userPreferencesRepository.setOnboardingCompleted(true)
            } catch (e: IOException) {
                Log.e("OpenLoopViewModel", "Failed to persist onboarding state", e)
                // Non-fatal: user will just see onboarding again next launch
            }
        }
    }

    fun onPermissionsChecked(granted: Boolean) {
        _uiState.value = if (granted) {
            OpenLoopUiState.ReadyToCapture
        } else {
            OpenLoopUiState.PermissionDenied
        }
    }

    /** User denied a required permission once; show the educational rationale screen. */
    fun showPermissionRationale() {
        _uiState.value = OpenLoopUiState.PermissionRationale
    }

    /**
     * User acknowledged the rationale. Return to [OpenLoopUiState.CheckingPermissions] so the
     * permission flow has a single source of truth; MainActivity then launches the system dialog
     * directly to avoid re-entering the rationale branch (see MainActivity.checkPermissions).
     */
    fun onRationaleAcknowledged() {
        _uiState.value = OpenLoopUiState.CheckingPermissions
    }

    /**
     * User dismissed the rationale ("Not now") instead of granting. Move to the blocked-but-
     * recoverable [OpenLoopUiState.PermissionDenied] screen rather than nagging — the user can
     * still retry or open Settings from there. Satisfies Google's "always provide the option to
     * cancel an educational UI flow" guidance.
     */
    fun onRationaleDeclined() {
        _uiState.value = OpenLoopUiState.PermissionDenied
    }

    private var recordingJob: Job? = null

    /**
     * Elapsed recording time in milliseconds, driven by the capture timer while in
     * [OpenLoopUiState.Recording]. The UI reads this to draw the shutter progress ring and the
     * `00:00 / 00:30` countdown chip. It re-emits roughly every [TICK_MS] ms and is reset to 0
     * whenever a capture stops. Value is clamped to [MAX_RECORDING_MS].
     */
    private val _recordingElapsedMs = MutableStateFlow(0L)
    val recordingElapsedMs: StateFlow<Long> = _recordingElapsedMs.asStateFlow()

    private val _recordedVideos = MutableStateFlow<List<RecordedVideo>>(emptyList())
    val recordedVideos: StateFlow<List<RecordedVideo>> = _recordedVideos.asStateFlow()

    /**
     * Ids of loops marked for deletion but not yet committed to disk (Issue #35). They are hidden
     * from the gallery immediately (optimistic delete via [visibleVideos]) while the Undo snackbar is
     * up; an Undo clears this set (the tiles reappear), a dismiss commits the real delete.
     */
    private val _pendingDeletionIds = MutableStateFlow<Set<Long>>(emptySet())
    val pendingDeletionIds: StateFlow<Set<Long>> = _pendingDeletionIds.asStateFlow()

    /**
     * The batch backing the current pending deletion, held in memory (NOT on disk). Safe-by-design:
     * because the real `videoStorage.deleteVideo` is deferred to [commitPendingDeletion], process
     * death before the commit leaves every file intact — an implicit Undo, never data loss.
     */
    private var pendingBatch: List<RecordedVideo> = emptyList()

    /**
     * The gallery's view of storage with any pending-deletion ids filtered out, so removed tiles
     * vanish instantly and reappear on Undo. Collected with [SharingStarted.WhileSubscribed] (Lesson
     * 002 — lifecycle-aware) so it stops combining when the gallery isn't on screen.
     */
    val visibleVideos: StateFlow<List<RecordedVideo>> =
        combine(recordedVideos, pendingDeletionIds) { videos, pending ->
            videos.filterNot { it.id in pending }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The Trim screen's working state (source file, duration, handle positions), or `null` when no
     * clip is being edited. Held alongside (not inside) [OpenLoopUiState.Trim] so the routed state
     * stays a slim discriminator and the trim selection survives a failed render.
     */
    private val _editorState = MutableStateFlow<TrimState?>(null)
    val editorState: StateFlow<TrimState?> = _editorState.asStateFlow()

    /**
     * The boomerang editor's tab selections (slice 03: direction only). Held alongside [editorState]
     * so [OpenLoopUiState.BoomerangEditor] stays a slim discriminator. Defaults apply on the first
     * [onNextFromTrim] for a clip; returning from [backToTrim] preserves the session (mode, speed,
     * look, cached reverse).
     */
    private val _editorTabState = MutableStateFlow(EditorTabState())
    val editorTabState: StateFlow<EditorTabState> = _editorTabState.asStateFlow()

    /**
     * True after the first [onNextFromTrim] for the active scratch. [backToTrim] keeps this set so
     * toolbar hops Trim ↔ editor do not wipe [EditorTabState] or restart reverse unnecessarily.
     */
    private var editorSessionActive = false

    /** In-flight reverse-generation for the preview; canceled when the editing session ends. */
    private var reverseJob: Job? = null

    /** Bumped when reverse work is canceled or superseded so stale completions are ignored. */
    private var reverseGeneration = 0

    /** Brief overlay for speed/filter tweaks (does not block reverse generation). */
    private var effectLoadingJob: Job? = null

    /**
     * Full-screen overlay on Trim/Editor during discard (and any future session-wide blocking work).
     */
    private val _sessionOverlayLoading = MutableStateFlow<EditorLoadingKind?>(null)
    val sessionOverlayLoading: StateFlow<EditorLoadingKind?> = _sessionOverlayLoading.asStateFlow()

    /** Observes WorkManager progress/completion for the active Loopifying export (Issue #40). */
    private var renderObserveJob: Job? = null

    /** Scratch UUID of the render currently enqueued — used by [cancelRenderWork] (P2 cancel). */
    private var activeRenderScratchUuid: String? = null

    /** Render progress (0f..1f) for the [OpenLoopUiState.Processing] spinner. */
    private val _renderProgress = MutableStateFlow(0f)
    val renderProgress: StateFlow<Float> = _renderProgress.asStateFlow()

    /** One-shot snackbar events (see [BoomerangEvent]); collected once by MainActivity. */
    private val _events = Channel<BoomerangEvent>(Channel.BUFFERED)
    val events: Flow<BoomerangEvent> = _events.receiveAsFlow()

    /**
     * Friendly "That clip's a bit long" dialog (slice 07). Held in a [StateFlow] (not a one-shot
     * event) so the dialog survives Activity recreation after the Photo Picker returns — a Channel
     * event can be emitted before MainActivity's collector is subscribed and then never shown.
     */
    private val _showImportTooLongDialog = MutableStateFlow(false)
    val showImportTooLongDialog: StateFlow<Boolean> = _showImportTooLongDialog.asStateFlow()

    /** Dismisses the import-too-long guidance dialog after the user taps "Got it". */
    fun dismissImportTooLongDialog() {
        _showImportTooLongDialog.value = false
    }

    /**
     * One-shot signal for MainActivity to request [android.Manifest.permission.POST_NOTIFICATIONS]
     * on first Save (API 33+). Activity checks grant state before showing the system dialog.
     */
    private val _requestPostNotifications = Channel<Unit>(Channel.CONFLATED)
    val requestPostNotifications: Flow<Unit> = _requestPostNotifications.receiveAsFlow()

    /** The in-flight capture's scratch file; non-null between capture start and Trim discard/save. */
    private var activeScratch: ScratchCapture? = null

    /** The raw the active scratch was promoted to (cached so a failed-render retry doesn't re-promote). */
    private var promotedRaw: RecordedVideo? = null

    /**
     * Whether the active editing session began as a library import ([onVideoPicked]) rather than a
     * fresh camera capture (slice 07). The pipeline is otherwise reused byte-for-byte; this flag only
     * changes where the user lands when the session ends — saving or discarding an imported clip
     * returns to the [OpenLoopUiState.Gallery] they imported from, not the camera. Reset in
     * [clearEditorSession] and on every camera capture.
     */
    private var importedSession: Boolean = false

    fun startBurstCapture(cameraManager: CameraManager) {
        if (_uiState.value != OpenLoopUiState.ReadyToCapture) return

        _uiState.value = OpenLoopUiState.Recording

        // Per-capture scratch file (cacheDir/scratch/raw_<uuid>.mp4) instead of a single fixed path,
        // so the captured clip has a stable identity for the Trim screen and back-to-back captures
        // can't clobber each other.
        val scratch = videoStorage.createScratchCapture()
        activeScratch = scratch
        promotedRaw = null
        importedSession = false // a fresh capture; this session ends back on the camera
        val outputFile = scratch.file
        if (outputFile.exists()) {
            outputFile.delete()
        }

        try {
            val recording = cameraManager.startRecording(outputFile) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        Log.d("OpenLoopViewModel", "Video burst recording started.")
                    }
                    is VideoRecordEvent.Finalize -> {
                        // Whichever path finalized us (user tap or 30 s auto-cap), the timer is done.
                        clearRecordingTimers()
                        if (event.hasError()) {
                            Log.e("OpenLoopViewModel", "Video burst recording failed: ${event.error}")
                            videoStorage.discardScratch(scratch)
                            activeScratch = null
                            _uiState.value = OpenLoopUiState.ReadyToCapture
                        } else {
                            // Auto-route straight to the Trim screen (no preview landing pad).
                            // The scratch stays in cache until the user saves (promote→raw) or discards.
                            // durationOf does a MediaMetadataRetriever decode and this callback runs on
                            // CameraX's main executor, so read it on a coroutine (Dispatchers.IO inside the
                            // repo) before routing — never block the main thread (ANDROID_STANDARDS §9).
                            viewModelScope.launch {
                                val durationMs = videoStorage.durationOf(outputFile)
                                Log.d("OpenLoopViewModel", "Capture finalized (${durationMs}ms): ${outputFile.absolutePath}")
                                resetEditorTabForNewClip()
                                _editorState.value = TrimState(
                                    sourceFile = outputFile,
                                    sourceDurationMs = durationMs,
                                    trimStartMs = 0L,
                                    trimEndMs = durationMs,
                                )
                                _uiState.value = OpenLoopUiState.Trim(EditorSource.ScratchClip(scratch.uuid))
                            }
                        }
                    }
                }
            }

            // startRecording returns null when the VideoCapture use case isn't bound yet (REC-2).
            // If we launched the timer anyway, no Finalize would ever fire, the auto-cap's
            // stopRecording() would be a no-op, and the UI would sit stuck in Recording with a full
            // ring for 30 s. Revert to ReadyToCapture and bail BEFORE starting the timer coroutine.
            if (recording == null) {
                Log.e("OpenLoopViewModel", "startRecording returned null (camera not bound); aborting capture")
                clearRecordingTimers()
                _uiState.value = OpenLoopUiState.ReadyToCapture
                return
            }

            // Drive the elapsed-time flow (for the progress ring + countdown chip) and enforce the
            // 30 s hard cap. When elapsed reaches MAX_RECORDING_MS with no user tap, finalize via the
            // same stopBurstCapture() path as a tap. The loop is bounded by the cap, so a virtual-time
            // test can advanceUntilIdle() without spinning forever (Lesson 008).
            _recordingElapsedMs.value = 0L
            recordingJob = viewModelScope.launch {
                var elapsed = 0L
                while (elapsed < MAX_RECORDING_MS) {
                    delay(TICK_MS)
                    elapsed = (elapsed + TICK_MS).coerceAtMost(MAX_RECORDING_MS)
                    _recordingElapsedMs.value = elapsed
                }
                stopBurstCapture(cameraManager)
            }
        } catch (e: IllegalStateException) {
            // prepareRecording/start: the Recorder already has an unfinished active recording
            // (PendingRecording.start docs). Recover to idle rather than wedging in Recording.
            recoverFromFailedStart(e)
        }
        // NOTE: deliberately NOT catching Exception broadly (REC-3 / ANDROID_STANDARDS §3). The
        // synchronous start path only declares IllegalStateException; CameraX
        // surfaces IO/encoder failures asynchronously via VideoRecordEvent.Finalize (handled above),
        // not as a throw. Letting any other throwable propagate keeps real programming errors visible.
    }

    /** Shared recovery for a synchronous start-recording failure: log, cancel timers, go idle. */
    private fun recoverFromFailedStart(e: Exception) {
        Log.e("OpenLoopViewModel", "Failed to start burst capture", e)
        clearRecordingTimers()
        _uiState.value = OpenLoopUiState.ReadyToCapture
    }

    fun loadRecordedVideos() {
        // Directory scan + lazy thumbnail decode runs on Dispatchers.IO inside the repo; launch so
        // the read never blocks the caller's (main) thread (ANDROID_STANDARDS §9).
        viewModelScope.launch {
            _recordedVideos.value = videoStorage.loadRecordedVideos()
        }
    }

    /**
     * Mark [videos] for deletion (Issue #35). Hides them from [visibleVideos] immediately and emits
     * [BoomerangEvent.LoopsDeleted] so the UI can offer Undo — but does NOT touch disk yet. If a prior
     * batch is still pending it is committed first (a new delete supersedes the old one's Undo window).
     */
    fun requestDeleteVideos(videos: List<RecordedVideo>) {
        if (videos.isEmpty()) return
        if (pendingBatch.isNotEmpty()) commitPendingDeletion() // supersede: commit the prior batch
        pendingBatch = videos
        _pendingDeletionIds.value = videos.map { it.id }.toSet()
        viewModelScope.launch { _events.send(BoomerangEvent.LoopsDeleted(videos.size)) }
    }

    /** Re-open the Android share sheet for an existing saved loop (gallery preview). */
    fun shareLoop(video: RecordedVideo) {
        viewModelScope.launch {
            _events.send(
                BoomerangEvent.Share(
                    file = File(video.videoPath),
                    showSavedSnackbarAfterDismiss = false,
                ),
            )
        }
    }

    /** Undo the pending deletion: forget the batch + restore the hidden tiles. Nothing was deleted. */
    fun undoPendingDeletion() {
        pendingBatch = emptyList()
        _pendingDeletionIds.value = emptySet()
    }

    /**
     * Commit the pending deletion: delete each file from storage off the main thread, then reload the
     * gallery. Clears the in-memory batch + hidden ids first so a racing [requestDeleteVideos] starts
     * clean. A no-op when nothing is pending (e.g. an Undo already cleared it, or the snackbar is
     * dismissed twice).
     */
    fun commitPendingDeletion() {
        val batch = pendingBatch
        if (batch.isEmpty()) return
        pendingBatch = emptyList()
        _pendingDeletionIds.value = emptySet()
        viewModelScope.launch {
            batch.forEach { videoStorage.deleteVideo(it) }
            _recordedVideos.value = videoStorage.loadRecordedVideos()
        }
    }

    fun navigateToGallery() {
        _uiState.value = OpenLoopUiState.Gallery
        loadRecordedVideos()
    }

    fun navigateBackFromGallery() {
        _uiState.value = OpenLoopUiState.ReadyToCapture
    }

    // ── Import from library (slice 07) ──────────────────────────────────────────────────────────

    /**
     * Result of the Android Photo Picker (launched `VideoOnly` from the gallery). [uri] is the picked
     * video, or `null` if the user backed out. On a valid pick we probe the duration *before* copying
     * (so a >30 s clip is rejected with a friendly dialog without ever being copied), then copy the
     * bytes into a fresh scratch file and enter the existing [OpenLoopUiState.Trim] flow exactly as a
     * fresh capture would — the imported clip is just "a scratch that came from the picker." Any I/O
     * or unreadable-duration failure routes back to the gallery with a snackbar; never a crash.
     */
    fun onVideoPicked(uri: Uri?) {
        if (uri == null) return // user backed out of the picker
        _uiState.value = OpenLoopUiState.ImportingVideo
        viewModelScope.launch {
            val durationMs = videoImporter.probeDurationMs(uri)
            when {
                // Unreadable duration → we can't enforce the ≤30 s rule, so don't import it.
                durationMs <= 0L -> failImport()
                // Enforce the dialog's advertised "up to 30 s" cap LENIENTLY: the small grace
                // (IMPORT_DURATION_GRACE_MS) accepts a clip the user thinks is "30 s" but whose
                // container duration reads 30.2–30.5 s. The grace only ever makes us *more* permissive
                // than the promise, never stricter — so no user is surprised by a rejection, and a clip
                // comfortably past 30 s is still rejected, exactly matching the "up to 30 seconds" copy.
                exceedsImportDurationLimit(durationMs) -> warnTooLong()
                else -> {
                    val scratch = videoStorage.createScratchCapture()
                    if (!videoImporter.importToFile(uri, scratch.file)) {
                        videoStorage.discardScratch(scratch)
                        failImport()
                        return@launch
                    }
                    val dur = videoStorage.durationOf(scratch.file)
                    when {
                        dur <= 0L -> {
                            videoStorage.discardScratch(scratch)
                            failImport()
                            return@launch
                        }
                        exceedsImportDurationLimit(dur) -> {
                            // Pre-copy probe can under-read; enforce the cap again on the local copy.
                            videoStorage.discardScratch(scratch)
                            warnTooLong()
                            return@launch
                        }
                    }
                    // Defensive: replacing activeScratch must not orphan a previous session's scratch
                    // copy. In practice it's already null here — the import action lives only on the
                    // gallery, and you can't reach the gallery mid-edit (save/discard both run
                    // clearEditorSession) — but if one ever lingered we'd otherwise leak a whole
                    // library-video-sized file until the 24h prune. discardScratch is a no-op on a
                    // missing file, so this is safe even in the normal null case.
                    activeScratch?.let { videoStorage.discardScratch(it) }
                    activeScratch = scratch
                    promotedRaw = null
                    importedSession = true // saving/discarding returns to the gallery, not the camera
                    resetEditorTabForNewClip()
                    _editorState.value = TrimState(
                        sourceFile = scratch.file,
                        sourceDurationMs = dur,
                        trimStartMs = 0L,
                        trimEndMs = dur, // whole clip ≤30 s; no trim-window cap needed
                    )
                    _uiState.value = OpenLoopUiState.Trim(EditorSource.ScratchClip(scratch.uuid))
                }
            }
        }
    }

    /** Non-length import failure: snackbar + back to the gallery (nothing left in flight). */
    private suspend fun failImport() {
        _events.send(BoomerangEvent.ImportFailed)
        _uiState.value = OpenLoopUiState.Gallery
    }

    /** Picked clip exceeded the import limit: friendly dialog + back to the gallery (nothing copied). */
    private suspend fun warnTooLong() {
        _showImportTooLongDialog.value = true
        _events.send(BoomerangEvent.ImportTooLong)
        _uiState.value = OpenLoopUiState.Gallery
    }

    /** True when [durationMs] is past the advertised "up to 30 s" import cap (including grace). */
    private fun exceedsImportDurationLimit(durationMs: Long): Boolean =
        durationMs > IMPORT_MAX_DURATION_MS + IMPORT_DURATION_GRACE_MS

    /**
     * Finalize the current burst. Called from both the user-tap path and the 30 s auto-cap path.
     *
     * Idempotent by design: [recordingJob] is non-null only between [startBurstCapture] and the
     * `Finalize` callback. The first call cancels the timer and stops the recording; any later call
     * (e.g. a user tap landing on the same scheduler tick as the auto-cap) finds a null job and
     * returns, so `cameraManager.stopRecording()` is invoked exactly once per capture.
     */
    fun stopBurstCapture(cameraManager: CameraManager) {
        if (recordingJob == null) return
        clearRecordingTimers()
        cameraManager.stopRecording()
    }

    /** Cancel the elapsed-time / auto-cap timer and reset the progress ring to empty. */
    private fun clearRecordingTimers() {
        recordingJob?.cancel()
        recordingJob = null
        _recordingElapsedMs.value = 0L
    }

    /** Return to the live camera ([OpenLoopUiState.ReadyToCapture]) — a generic "start over" reset. */
    fun resetToCapture() {
        _uiState.value = OpenLoopUiState.ReadyToCapture
    }

    // ── Trim screen (slice 02) ──────────────────────────────────────────────────────────────────

    /**
     * Update the trim handles. Positions are clamped to `[0, sourceDuration]`; an update that would
     * shrink the window below [MIN_TRIM_MS] is ignored (the handles can't cross within the minimum).
     */
    fun updateTrim(startMs: Long, endMs: Long) {
        val current = _editorState.value ?: return
        val start = startMs.coerceIn(0L, current.sourceDurationMs)
        val end = endMs.coerceIn(0L, current.sourceDurationMs)
        if (end - start < MIN_TRIM_MS) return
        if (start == current.trimStartMs && end == current.trimEndMs) return
        _editorState.value = current.copy(trimStartMs = start, trimEndMs = end)

        // Trim changed while the editor is open — invalidate the cached reverse and rebuild it.
        if (_uiState.value is OpenLoopUiState.BoomerangEditor) {
            cancelReverseJob()
            val tab = _editorTabState.value
            _editorTabState.value = tab.copy(
                reversedFile = null,
                reverseFailed = false,
                previewLoading = null,
            )
            if (tab.mode.needsReverse) {
                ensureReversedSegment(EditorLoadingKind.TRIMMING)
            }
        }
    }

    /**
     * Discard the scratch clip and leave the editor (the Trim back-arrow / confirm-discard path). A
     * fresh capture returns to the camera; an imported clip returns to the [OpenLoopUiState.Gallery]
     * it was imported from (slice 07). The original library video is untouched — we only delete our
     * own scratch copy.
     */
    fun discardTrim() {
        viewModelScope.launch {
            val returnToGallery = importedSession
            _sessionOverlayLoading.value = EditorLoadingKind.DELETING
            try {
                activeScratch?.let { videoStorage.discardScratch(it) }
                clearEditorSession()
                _sessionOverlayLoading.value = null
                if (returnToGallery) {
                    navigateToGallery()
                } else {
                    _uiState.value = OpenLoopUiState.ReadyToCapture
                }
            } catch (e: CancellationException) {
                _sessionOverlayLoading.value = null
                throw e
            } catch (e: Exception) {
                Log.e("OpenLoopViewModel", "Discard clip failed", e)
                _sessionOverlayLoading.value = null
            }
        }
    }

    /**
     * NEXT on the Trim screen: open the tabbed boomerang editor over the current trim (slice 03).
     * Resets the editor tabs to defaults (`FORWARD_THEN_REVERSE`), routes to
     * [OpenLoopUiState.BoomerangEditor], and eagerly kicks off reverse generation so the default
     * direction's preview is ready ASAP. The actual save now happens from the editor's checkmark
     * ([saveBoomerang]); slice 02's default-render-on-NEXT is gone.
     */
    fun onNextFromTrim(initialTab: EditorTab = EditorTab.DIRECTION) {
        val scratch = activeScratch ?: return
        if (_editorState.value == null) return
        val enteringFresh = !editorSessionActive
        val priorTab = _editorTabState.value
        val reverseLoadingKind =
            if (enteringFresh) EditorLoadingKind.TRIMMING else EditorLoadingKind.LOOPIFYING
        val willNeedReverse =
            priorTab.mode.needsReverse &&
                priorTab.reversedFile == null &&
                !priorTab.reverseFailed
        if (enteringFresh) {
            // Set TRIMMING before the editor composes so ExoPlayer never grabs a decoder on frame 1
            // (BoomerangEditorScreen gates prepare() on isReversePreviewLoading()).
            // effectsPreviewEnabled: probe at entry — a session that begins under memory pressure
            // never opens the Looks preview gate (Android 14+ has no foreground trim callback to
            // close it later; see MemoryPressure).
            _editorTabState.value = EditorTabState(
                activeTab = initialTab,
                previewLoading = if (willNeedReverse) reverseLoadingKind else null,
                effectsPreviewEnabled = !isLowMemoryNow(),
            )
            editorSessionActive = true
        } else {
            _editorTabState.value = priorTab.copy(
                activeTab = initialTab,
                previewLoading = if (willNeedReverse) reverseLoadingKind else priorTab.previewLoading,
            )
        }
        _uiState.value = OpenLoopUiState.BoomerangEditor(EditorSource.ScratchClip(scratch.uuid))
        if (willNeedReverse) {
            ensureReversedSegment(reverseLoadingKind)
        }
    }

    /** Back arrow / back gesture from the editor: return to Trim, preserving the trim selection. */
    fun backToTrim() {
        val scratch = activeScratch ?: run {
            _uiState.value = OpenLoopUiState.ReadyToCapture
            return
        }
        cancelReverseJob()
        _uiState.value = OpenLoopUiState.Trim(EditorSource.ScratchClip(scratch.uuid))
    }

    /**
     * Select a boomerang direction in the editor's Direction tab. Updating to a reverse-containing
     * mode kicks off [ensureReversedSegment] (idempotent — a no-op if the reversed file is already
     * ready or in flight); `FORWARD` needs no reversed clip.
     */
    fun updateMode(mode: BoomerangMode) {
        val current = _editorTabState.value
        if (current.mode == mode) return
        _editorTabState.value = current.copy(mode = mode)
        if (mode.needsReverse) {
            ensureReversedSegment(EditorLoadingKind.LOOPIFYING)
        } else {
            cancelReverseJob()
        }
    }

    /**
     * Set the playback speed from the editor's Speed tab (slice 04). Clamped to [MIN_SPEED]..[MAX_SPEED]
     * so neither the player nor the renderer ever sees an out-of-range value, regardless of what the
     * slider emits. Speed is a player-side effect on the preview and a per-clip render effect at save —
     * it never touches the cached [EditorTabState.reversedFile], so no reverse regeneration is needed.
     */
    fun updateSpeed(speed: Float) {
        val clamped = speed.coerceIn(MIN_SPEED, MAX_SPEED)
        val current = _editorTabState.value
        if (current.speed == clamped) return
        _editorTabState.value = current.copy(speed = clamped)
        showBriefPreviewLoading(EditorLoadingKind.HOLD_TIGHT)
    }

    /**
     * Set the color look from the editor's Looks tab (slice 05). Like [updateSpeed] it's a pure
     * effect selection — applied live in the preview via `setVideoEffects` and baked into the render;
     * it never touches the cached [EditorTabState.reversedFile] or the output duration.
     */
    fun updateFilter(filter: VideoFilter) {
        val current = _editorTabState.value
        if (current.filter == filter) return
        if (filter != VideoFilter.ORIGINAL) {
            // Gate closed (reverse failure / memory pressure): the chips are disabled in the UI,
            // but never trust the UI alone — a non-Original look must not reach the preview.
            if (!current.effectsPreviewEnabled) return
            // Proactive probe at the exact moment DefaultVideoFrameProcessor would spin up.
            // Android 14+ delivers no foreground onTrimMemory pressure levels (MemoryPressure),
            // so this poll is the only mid-session pressure signal on modern devices: under
            // pressure, close the gate instead of applying the look (WS-3, PR #58 review).
            if (isLowMemoryNow()) {
                _editorTabState.value = current.copy(effectsPreviewEnabled = false)
                return
            }
        }
        val overlay = if (current.previewLoading.isReversePreviewLoading()) {
            current.previewLoading
        } else {
            EditorLoadingKind.FILTERING
        }
        _editorTabState.value = current.copy(filter = filter, previewLoading = overlay)
    }

    /** Called after the preview player has applied the new filter (or cleared effects for Original). */
    fun onFilterPreviewSettled() {
        clearPreviewLoading(EditorLoadingKind.FILTERING)
    }

    /** Switch the editor's active tab (Direction / Speed / Looks); pure UI state, no side effects. */
    fun switchTab(tab: EditorTab) {
        val current = _editorTabState.value
        if (current.activeTab == tab) return
        _editorTabState.value = current.copy(activeTab = tab)
    }

    /**
     * Ensure the reversed clip for the current trim exists (for the preview, and reused by the render).
     * Serialized against fast chip-taps: once the reversed file is ready or a generation is already in
     * flight, further calls are ignored (KICKOFF §4 — the trim is fixed for the session, so one run
     * per session suffices). Failure clears the loading flag and leaves [EditorTabState.reversedFile]
     * null; the preview then falls back to forward playback and the user can retry by reelecting.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun ensureReversedSegment(loadingKind: EditorLoadingKind = EditorLoadingKind.LOOPIFYING) {
        val trim = _editorState.value ?: return
        var tab = _editorTabState.value
        if (!tab.mode.needsReverse) return
        if (tab.reversedFile != null) {
            clearReversePreviewLoadingIfSet()
            return
        }
        if (reverseJob?.isActive == true) {
            if (!tab.previewLoading.isReversePreviewLoading()) {
                _editorTabState.value = tab.copy(previewLoading = loadingKind, reverseFailed = false)
            }
            return
        }
        if (tab.previewLoading.isReversePreviewLoading() && reverseJob?.isActive != true) {
            _editorTabState.value = tab.copy(previewLoading = null)
            tab = _editorTabState.value
        }

        val generation = ++reverseGeneration
        effectLoadingJob?.cancel()
        _editorTabState.value = tab.copy(
            previewLoading = loadingKind,
            reverseFailed = false,
            reverseSupportReport = null,
        )
        ReversePreviewLog.i(
            "viewModel.ensureReversed.start",
            "gen=$generation loading=$loadingKind source=${trim.sourceFile.name} " +
                "trim=${trim.trimStartMs}..${trim.trimEndMs}ms samsung=${isSamsungDevice()}",
        )
        reverseJob = viewModelScope.launch {
            try {
                // withTimeoutOrNull waits for cancellation to finish; a wedged MediaCodec/Transformer
                // on some Samsung devices never returns, so the failure UI never appears. select +
                // onTimeout returns immediately; do not use coroutineScope here — it would wait for
                // the cancelled worker and can surface CancellationException without reverseFailed.
                // runCatching + Result in select so a failed async child does not cancel this
                // launch before we can set reverseFailed (Lesson 013 still applies to the job itself).
                val previewReverseCap =
                    if (isSamsungDevice()) SAMSUNG_PREVIEW_REVERSE_MAX_SHORT_SIDE else null
                val outcome = if (reversePreviewTimeoutDisabledForTests()) {
                    // JVM tests: avoid Main awaiting an IO [async] child (deadlocks with Unconfined).
                    withContext(Dispatchers.IO) {
                        runCatching {
                            videoProcessor.ensureReversed(
                                trim.sourceFile,
                                trim.trimStartMs,
                                trim.trimEndMs,
                                maxReverseShortSide = previewReverseCap,
                            )
                        }
                    }
                } else {
                    val worker = async(Dispatchers.IO) {
                        runCatching {
                            videoProcessor.ensureReversed(
                                trim.sourceFile,
                                trim.trimStartMs,
                                trim.trimEndMs,
                                maxReverseShortSide = previewReverseCap,
                            )
                        }
                    }
                    select {
                        worker.onAwait { it }
                        onTimeout(reversePreviewTimeoutMs()) {
                            worker.cancel()
                            Result.failure(PreviewReverseTimeoutException())
                        }
                    }
                }
                if (generation != reverseGeneration) {
                    ReversePreviewLog.d(
                        "viewModel.ensureReversed.stale",
                        "gen=$generation current=$reverseGeneration",
                    )
                    return@launch
                }
                outcome.onSuccess { reversed ->
                    ReversePreviewLog.i(
                        "viewModel.ensureReversed.ok",
                        "gen=$generation file=${reversed.name} bytes=${reversed.length()}",
                    )
                    val latest = _editorTabState.value
                    _editorTabState.value = latest.copy(
                        reversedFile = reversed,
                        previewLoading = clearReversePreviewLoadingValue(latest.previewLoading),
                        reverseSupportReport = null,
                    )
                }.onFailure { error ->
                    if (error is PreviewReverseTimeoutException) {
                        ReversePreviewLog.e(
                            "viewModel.ensureReversed.timeout",
                            "gen=$generation after ${reversePreviewTimeoutMs()}ms " +
                                "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} " +
                                "source=${trim.sourceFile.name}",
                        )
                        Log.e(
                            "OpenLoopViewModel",
                            "Reverse generation for preview timed out after ${reversePreviewTimeoutMs()}ms " +
                                "(${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, " +
                                "source=${trim.sourceFile.name}, ${trim.trimEndMs - trim.trimStartMs}ms trim)",
                        )
                        markReversePreviewFailed(
                            trim,
                            "Timed out after ${reversePreviewTimeoutMs() / 1000}s",
                            PreviewReverseTimeoutException(),
                        )
                    } else {
                        ReversePreviewLog.e(
                            "viewModel.ensureReversed.fail",
                            "gen=$generation ${error.javaClass.simpleName}: ${error.message}",
                            error,
                        )
                        Log.e(
                            "OpenLoopViewModel",
                            "Reverse generation for preview failed: ${error.javaClass.simpleName}: ${error.message}",
                            error,
                        )
                        markReversePreviewFailed(
                            trim,
                            "${error.javaClass.simpleName}: ${error.message}",
                            error,
                        )
                    }
                }
            } catch (e: CancellationException) {
                if (generation == reverseGeneration) {
                    clearReversePreviewLoadingIfSet()
                }
                throw e // never swallow cancellation (Lesson 013)
            }
        }
    }

    /** Marker for [select] timeout — not shown to users. */
    private class PreviewReverseTimeoutException : Exception()

    private fun markReversePreviewFailed(trim: TrimState, outcome: String, cause: Throwable) {
        ReverseCrashlytics.reportPreviewFailure(
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            source = trim.sourceFile,
            trimStartMs = trim.trimStartMs,
            trimEndMs = trim.trimEndMs,
            outcome = outcome,
            cause = cause,
        )
        val latest = _editorTabState.value
        val supportReport = ReverseCrashlytics.supportReportForShare(
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            source = trim.sourceFile,
            trimStartMs = trim.trimStartMs,
            trimEndMs = trim.trimEndMs,
            outcome = outcome,
        )
        // Let Samsung (and other slow-reverse) users preview and save a forward loop instead of blocking
        // on ping-pong. They can pick a reverse mode again from the Loop tab (Try again / direction).
        _editorTabState.value = latest.copy(
            mode = BoomerangMode.FORWARD,
            previewLoading = clearReversePreviewLoadingValue(latest.previewLoading),
            reverseFailed = false,
            reverseSupportReport = supportReport,
            effectsPreviewEnabled = false,
            // Reset the look with the gate: the chips lock to disabled, the UI recreates the player
            // to drop any already-applied effects, and the export must match what the preview now
            // shows — a non-Original filter left behind would bake a look the user can't see
            // ("the chip can't lie about the export", VideoFilter doc; PR #58 review).
            filter = VideoFilter.ORIGINAL,
        )
        reverseJob = null
        cleanupReverseScratchAfterCancel()
        viewModelScope.launch {
            _events.send(BoomerangEvent.ReversePreviewFallbackForward)
        }
    }

    /** Retry reverse generation after [EditorTabState.reverseFailed] (Loop tab). */
    fun retryReverseSegment() {
        cancelReverseJob()
        val tab = _editorTabState.value
        val pingPongMode = when (tab.mode) {
            BoomerangMode.FORWARD -> BoomerangMode.FORWARD_THEN_REVERSE
            else -> tab.mode
        }
        _editorTabState.value = tab.copy(
            mode = pingPongMode,
            reversedFile = null,
            reverseFailed = false,
            previewLoading = null,
            reverseSupportReport = null,
        )
        if (pingPongMode.needsReverse) {
            ensureReversedSegment(EditorLoadingKind.LOOPIFYING)
        }
    }

    private fun showBriefPreviewLoading(kind: EditorLoadingKind) {
        val tab = _editorTabState.value
        if (tab.previewLoading == EditorLoadingKind.TRIMMING ||
            tab.previewLoading == EditorLoadingKind.LOOPIFYING
        ) {
            return
        }
        _editorTabState.value = tab.copy(previewLoading = kind)
        effectLoadingJob?.cancel()
        effectLoadingJob = viewModelScope.launch {
            delay(EFFECT_LOADING_MIN_MS)
            clearPreviewLoading(kind)
        }
    }

    private fun clearPreviewLoading(kind: EditorLoadingKind) {
        val tab = _editorTabState.value
        if (tab.previewLoading == kind) {
            _editorTabState.value = tab.copy(previewLoading = null)
        }
    }

    /**
     * Save the boomerang in the editor's current direction + speed + look (reps stays hard-wired at 1
     * — the reps tab was dropped for the Looks tab). Flips to [OpenLoopUiState.Processing]; on success promotes the scratch to a persistent
     * raw, registers the boomerang, emits [BoomerangEvent.Share] (handing the rendered file to the
     * share sheet — slice 06) and returns to capture. The render
     * sources the **scratch** file — the same path the preview reversed — so a reverse-containing mode
     * hits the cached reversed clip instead of regenerating it (speed is applied per clip at render and
     * doesn't invalidate that cache). On failure, it emits [BoomerangEvent.Failed] and routes back to
     * [OpenLoopUiState.BoomerangEditor] with the direction + speed selection intact.
     */
    fun saveBoomerang() {
        val editor = _editorState.value ?: return
        val scratch = activeScratch ?: return
        val tab = _editorTabState.value
        val mode = tab.mode

        viewModelScope.launch {
            try {
                // Promote once and cache it, so a retry after a failed render doesn't create a 2nd raw.
                val raw = promotedRaw
                    ?: (videoStorage.promoteScratchToRaw(scratch)?.also { promotedRaw = it }
                        ?: throw IOException("Failed to promote scratch ${scratch.uuid} to a raw"))

                val output = videoStorage.allocateBoomerangFile(raw.id)
                val returnToGallery = importedSession // capture before clearEditorSession() resets it

                _requestPostNotifications.trySend(Unit)

                val request = BoomerangRenderRequest(
                    scratch = scratch,
                    trimStartMs = editor.trimStartMs,
                    trimEndMs = editor.trimEndMs,
                    mode = mode,
                    speed = tab.speed,
                    filter = tab.filter,
                    repetitions = DEFAULT_REPS,
                    rawId = raw.id,
                    outputFile = output,
                    returnToGallery = returnToGallery,
                )

                _uiState.value = OpenLoopUiState.Processing
                _renderProgress.value = 0f

                renderObserveJob?.cancel()
                activeRenderScratchUuid = scratch.uuid
                val workId = renderScheduler.enqueue(request)
                observeRenderWork(workId, scratch)
            } catch (e: CancellationException) {
                throw e // never swallow cancellation (Lesson 013)
            } catch (e: IOException) {
                Log.e("OpenLoopViewModel", "Boomerang save failed before render enqueue (IO)", e)
                failBackToEditor(scratch)
            }
        }
    }

    /**
     * Cancel the in-flight Loopifying export for the active scratch (P2 cancel coordination).
     * No-op when nothing is rendering.
     */
    fun cancelRenderWork() {
        activeRenderScratchUuid?.let { renderScheduler.cancelRenderWork(it) }
    }

    private fun observeRenderWork(workId: UUID, scratch: ScratchCapture) {
        renderObserveJob = viewModelScope.launch {
            launch {
                renderScheduler.observeProgress(workId).collect { fraction ->
                    _renderProgress.value = fraction
                }
            }
            renderScheduler.observeResult(workId).collect { result ->
                when (result) {
                    is BoomerangRenderWorkResult.Success -> onRenderSucceeded(result)
                    BoomerangRenderWorkResult.Failure -> failBackToEditor(scratch)
                }
            }
        }
    }

    private suspend fun onRenderSucceeded(result: BoomerangRenderWorkResult.Success) {
        // End the WorkManager observer without canceling this coroutine mid-collect.
        renderObserveJob = null
        activeRenderScratchUuid = null
        cancelReverseJob()
        activeScratch = null
        promotedRaw = null
        importedSession = false
        _editorState.value = null
        _editorTabState.value = EditorTabState()
        _renderProgress.value = 0f
        loadRecordedVideos()
        _events.send(BoomerangEvent.Share(result.outputFile))
        _uiState.value = if (result.returnToGallery) {
            OpenLoopUiState.Gallery
        } else {
            OpenLoopUiState.ReadyToCapture
        }
    }

    /**
     * The share sheet for a just-saved boomerang has returned control (the user shared, canceled, or
     * backed out — all the same to us). Emit [BoomerangEvent.Saved] so the "Saved — view in gallery"
     * snackbar shows now that the user is back on the camera. Called by MainActivity from its next
     * `onResume()` after the chooser dismisses — not `withResumed { }`, which would fire immediately
     * because the activity is still RESUMED at the moment the chooser is launched (slice 06).
     */
    fun onShareSheetClosed() {
        viewModelScope.launch { _events.send(BoomerangEvent.Saved) }
    }

    /** Emit [BoomerangEvent.Failed] and route back to the editor, preserving the direction selection. */
    private suspend fun failBackToEditor(scratch: ScratchCapture) {
        renderObserveJob?.cancel()
        renderObserveJob = null
        activeRenderScratchUuid = null
        _renderProgress.value = 0f
        _events.send(BoomerangEvent.Failed)
        _uiState.value = OpenLoopUiState.BoomerangEditor(EditorSource.ScratchClip(scratch.uuid))
    }

    /** Cancel any in-flight reverse generation (editor left or session cleared). */
    private fun resetEditorTabForNewClip() {
        editorSessionActive = false
        cancelReverseJob()
        _editorTabState.value = EditorTabState()
    }

    private fun clearReversePreviewLoadingValue(kind: EditorLoadingKind?): EditorLoadingKind? =
        if (kind.isReversePreviewLoading()) null else kind

    private fun clearReversePreviewLoadingIfSet() {
        val tab = _editorTabState.value
        if (tab.previewLoading.isReversePreviewLoading()) {
            _editorTabState.value = tab.copy(previewLoading = null)
        }
    }

    private fun cancelReverseJob() {
        reverseGeneration++
        val job = reverseJob
        reverseJob = null
        job?.cancel()
        clearReversePreviewLoadingIfSet()
        if (job != null) cleanupReverseScratchAfterCancel()
    }

    private fun cleanupReverseScratchAfterCancel() {
        val result = videoProcessor.cleanupReverseIntermediates()
        ReverseCrashlytics.logReversePreviewCleanup(result.deletedCount, result.bytesDeleted)
    }

    /**
     * Called from [android.app.Activity.onTrimMemory] while the editor is active — only for the
     * legacy *foreground pressure* levels ([MemoryPressure.isForegroundPressureLevel]; API <= 33).
     * Also resets the look to [VideoFilter.ORIGINAL]: the UI tears down an already-running effects
     * pipeline by recreating the player (`setVideoEffects(emptyList())` is forbidden — see the
     * HDR-seam comment in BoomerangEditorScreen), so chips, preview, and export must agree on
     * "no look" once the gate closes (PR #58 review).
     */
    fun onTrimMemory() {
        if (_editorState.value == null) return
        val tab = _editorTabState.value
        if (!tab.effectsPreviewEnabled) return
        _editorTabState.value = tab.copy(
            effectsPreviewEnabled = false,
            filter = VideoFilter.ORIGINAL,
        )
    }

    private fun cancelRenderObserveJob() {
        renderObserveJob?.cancel()
        renderObserveJob = null
        activeRenderScratchUuid = null
    }

    /** Clear the active editing session (after discard or navigation away). Does NOT touch on-disk files. */
    private fun clearEditorSession() {
        editorSessionActive = false
        cancelReverseJob()
        effectLoadingJob?.cancel()
        effectLoadingJob = null
        _sessionOverlayLoading.value = null
        cancelRenderObserveJob()
        activeScratch = null
        promotedRaw = null
        importedSession = false
        _editorState.value = null
        _editorTabState.value = EditorTabState()
        _renderProgress.value = 0f
    }

    /**
     * Factory for creating [OpenLoopViewModel] with its repository dependencies.
     * Used in MainActivity since we don't have a DI framework. Note it takes the
     * already-constructed repositories (not a Context) — MainActivity bridges
     * Context → repositories, keeping this Factory and the ViewModel Context-free.
     */
    companion object {
        /** Hard cap on a single burst capture; recording auto-finalizes at this elapsed time. */
        const val MAX_RECORDING_MS = 30_000L

        /** Elapsed-time emit cadence (~30 fps) for a smooth progress ring without over-emitting. */
        const val TICK_MS = 33L

        /** Minimum trimmed duration; below this the NEXT action is disabled (slice 02). */
        const val MIN_TRIM_MS = 400L

        /** Default boomerang config. Direction picker shipped slice 03, speed slider slice 04, Looks
         *  (filters) slice 05 — the Reps tab was dropped in favor of Looks, so [DEFAULT_REPS] stays
         *  hard-wired at 1. [DEFAULT_SPEED] is the speed slider's starting value. */
        const val DEFAULT_SPEED = 2.0f
        const val DEFAULT_REPS = 1

        /** Playback-speed slider bounds (slice 04); [updateSpeed] clamps to this range. */
        const val MIN_SPEED = 0.25f

        /** Minimum time the speed/filter preview overlay stays visible so the caption is readable. */
        private const val EFFECT_LOADING_MIN_MS = 400L

        /**
         * Max wall time for editor preview reverse (library imports can be slow). On timeout the editor
         * surfaces [EditorTabState.reverseFailed] instead of infinite "Trimming..".
         */
        const val REVERSE_PREVIEW_TIMEOUT_MS = 120_000L

        /**
         * When true, preview reverse has no [select] timeout (unit tests only). Virtual-time
         * [kotlinx.coroutines.test.advanceUntilIdle] otherwise elapses the 120s deadline before IO mocks finish.
         */
        @Volatile
        var reversePreviewTimeoutDisabledForTests: Boolean = false

        /** Non-null replaces [REVERSE_PREVIEW_TIMEOUT_MS] for timeout-duration tests only. */
        @Volatile
        var reversePreviewTimeoutMsOverride: Long? = null

        internal fun reversePreviewTimeoutDisabledForTests(): Boolean =
            reversePreviewTimeoutDisabledForTests

        internal fun reversePreviewTimeoutMs(): Long =
            reversePreviewTimeoutMsOverride ?: REVERSE_PREVIEW_TIMEOUT_MS

        const val MAX_SPEED = 3.0f

        /** Max duration of an imported library clip (slice 07); same 30 s ceiling as a capture. */
        const val IMPORT_MAX_DURATION_MS = MAX_RECORDING_MS

        /**
         * Grace added to [IMPORT_MAX_DURATION_MS] before rejecting an import, so a clip the user
         * thinks of as "30 seconds" (often 30.2–30.5 s of actual container duration) isn't rejected
         * for being a few hundred ms over (slice 07).
         */
        const val IMPORT_DURATION_GRACE_MS = 1_000L

        /** Scratch files older than this are pruned at launch (parent D-8); 24 h. */
        const val STALE_SCRATCH_MAX_AGE_MS = 24L * 60 * 60 * 1_000
    }

    class Factory(
        private val userPreferencesRepository: UserPreferencesRepository,
        private val videoStorage: VideoStorageRepository,
        private val videoProcessor: VideoProcessor,
        private val videoImporter: VideoImporter,
        private val renderScheduler: BoomerangRenderScheduler,
        private val analytics: AnalyticsReporter,
        /** See the constructor doc — MainActivity passes [MemoryPressure.lowMemoryProbe]. */
        private val isLowMemoryNow: () -> Boolean = { false },
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(OpenLoopViewModel::class.java)) {
                return OpenLoopViewModel(
                    userPreferencesRepository,
                    videoStorage,
                    videoProcessor,
                    videoImporter,
                    renderScheduler,
                    analytics,
                    isLowMemoryNow,
                ) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
