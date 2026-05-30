package com.openrang.app.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.openrang.app.camera.CameraManager
import com.openrang.app.data.RecordedVideo
import com.openrang.app.data.ScratchCapture
import com.openrang.app.data.UserPreferencesRepository
import com.openrang.app.data.VideoStorageRepository
import com.openrang.app.media.BoomerangMode
import com.openrang.app.media.VideoFilter
import com.openrang.app.media.VideoProcessor
import com.openrang.app.media.needsReverse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.io.IOException
import androidx.camera.video.VideoRecordEvent

/**
 * One-shot events the [OpenRangViewModel] emits for transient UI (snackbars). Delivered over a
 * [Channel] (not a StateFlow) so they fire exactly once and never replay on recomposition.
 */
sealed interface BoomerangEvent {
    /** Boomerang rendered + saved. Snackbar offers a "View" action into the gallery. */
    object Saved : BoomerangEvent
    /** Boomerang render failed. Snackbar invites a retry; the trim selection is preserved. */
    object Failed : BoomerangEvent
}

class OpenRangViewModel(
    private val userPreferencesRepository: UserPreferencesRepository,
    private val videoStorage: VideoStorageRepository,
    private val videoProcessor: VideoProcessor,
) : ViewModel() {

    // Start in Initializing — DataStore read decides Onboarding vs CheckingPermissions
    private val _uiState = MutableStateFlow<OpenRangUiState>(OpenRangUiState.Initializing)
    val uiState: StateFlow<OpenRangUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val onboardingDone = userPreferencesRepository.hasCompletedOnboarding.first()
            _uiState.value = if (onboardingDone) {
                OpenRangUiState.CheckingPermissions
            } else {
                OpenRangUiState.Onboarding
            }
        }
    }

    fun onOnboardingCompleted() {
        _uiState.value = OpenRangUiState.CheckingPermissions
        viewModelScope.launch {
            try {
                userPreferencesRepository.setOnboardingCompleted(true)
            } catch (e: IOException) {
                Log.e("OpenRangViewModel", "Failed to persist onboarding state", e)
                // Non-fatal: user will just see onboarding again next launch
            }
        }
    }

    fun onPermissionsChecked(granted: Boolean) {
        _uiState.value = if (granted) {
            OpenRangUiState.ReadyToCapture
        } else {
            OpenRangUiState.PermissionDenied
        }
    }

    /** User denied a required permission once; show the educational rationale screen. */
    fun showPermissionRationale() {
        _uiState.value = OpenRangUiState.PermissionRationale
    }

    /**
     * User acknowledged the rationale. Return to [OpenRangUiState.CheckingPermissions] so the
     * permission flow has a single source of truth; MainActivity then launches the system dialog
     * directly to avoid re-entering the rationale branch (see MainActivity.checkPermissions).
     */
    fun onRationaleAcknowledged() {
        _uiState.value = OpenRangUiState.CheckingPermissions
    }

    /**
     * User dismissed the rationale ("Not now") instead of granting. Move to the blocked-but-
     * recoverable [OpenRangUiState.PermissionDenied] screen rather than nagging — the user can
     * still retry or open Settings from there. Satisfies Google's "always provide the option to
     * cancel an educational UI flow" guidance.
     */
    fun onRationaleDeclined() {
        _uiState.value = OpenRangUiState.PermissionDenied
    }

    private var recordingJob: Job? = null

    /**
     * Elapsed recording time in milliseconds, driven by the capture timer while in
     * [OpenRangUiState.Recording]. The UI reads this to draw the shutter progress ring and the
     * `00:00 / 00:30` countdown chip. It re-emits roughly every [TICK_MS] ms and is reset to 0
     * whenever a capture stops. Value is clamped to [MAX_RECORDING_MS].
     */
    private val _recordingElapsedMs = MutableStateFlow(0L)
    val recordingElapsedMs: StateFlow<Long> = _recordingElapsedMs.asStateFlow()

    private val _recordedVideos = MutableStateFlow<List<RecordedVideo>>(emptyList())
    val recordedVideos: StateFlow<List<RecordedVideo>> = _recordedVideos.asStateFlow()

    /**
     * The Trim screen's working state (source file, duration, handle positions), or `null` when no
     * clip is being edited. Held alongside (not inside) [OpenRangUiState.Trim] so the routed state
     * stays a slim discriminator and the trim selection survives a failed render.
     */
    private val _editorState = MutableStateFlow<TrimState?>(null)
    val editorState: StateFlow<TrimState?> = _editorState.asStateFlow()

    /**
     * The boomerang editor's tab selections (slice 03: direction only). Held alongside [editorState]
     * so [OpenRangUiState.BoomerangEditor] stays a slim discriminator. Reset to defaults each time the
     * editor opens (see [onNextFromTrim]).
     */
    private val _editorTabState = MutableStateFlow(EditorTabState())
    val editorTabState: StateFlow<EditorTabState> = _editorTabState.asStateFlow()

    /** In-flight reverse-generation for the preview; canceled when the editing session ends. */
    private var reverseJob: Job? = null

    /** Render progress (0f..1f) for the [OpenRangUiState.Processing] spinner. */
    private val _renderProgress = MutableStateFlow(0f)
    val renderProgress: StateFlow<Float> = _renderProgress.asStateFlow()

    /** One-shot snackbar events (see [BoomerangEvent]); collected once by MainActivity. */
    private val _events = Channel<BoomerangEvent>(Channel.BUFFERED)
    val events: Flow<BoomerangEvent> = _events.receiveAsFlow()

    /** The in-flight capture's scratch file; non-null between capture start and Trim discard/save. */
    private var activeScratch: ScratchCapture? = null

    /** The raw the active scratch was promoted to (cached so a failed-render retry doesn't re-promote). */
    private var promotedRaw: RecordedVideo? = null

    fun startBurstCapture(cameraManager: CameraManager) {
        if (_uiState.value != OpenRangUiState.ReadyToCapture) return

        _uiState.value = OpenRangUiState.Recording

        // Per-capture scratch file (cacheDir/scratch/raw_<uuid>.mp4) instead of a single fixed path,
        // so the captured clip has a stable identity for the Trim screen and back-to-back captures
        // can't clobber each other.
        val scratch = videoStorage.createScratchCapture()
        activeScratch = scratch
        promotedRaw = null
        val outputFile = scratch.file
        if (outputFile.exists()) {
            outputFile.delete()
        }

        try {
            val recording = cameraManager.startRecording(outputFile) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        Log.d("OpenRangViewModel", "Video burst recording started.")
                    }
                    is VideoRecordEvent.Finalize -> {
                        // Whichever path finalized us (user tap or 30 s auto-cap), the timer is done.
                        clearRecordingTimers()
                        if (event.hasError()) {
                            Log.e("OpenRangViewModel", "Video burst recording failed: ${event.error}")
                            videoStorage.discardScratch(scratch)
                            activeScratch = null
                            _uiState.value = OpenRangUiState.ReadyToCapture
                        } else {
                            // Auto-route straight to the Trim screen (no LoopingPreview landing pad).
                            // The scratch stays in cache until the user saves (promote→raw) or discards.
                            // durationOf does a MediaMetadataRetriever decode and this callback runs on
                            // CameraX's main executor, so read it on a coroutine (Dispatchers.IO inside the
                            // repo) before routing — never block the main thread (ANDROID_STANDARDS §9).
                            viewModelScope.launch {
                                val durationMs = videoStorage.durationOf(outputFile)
                                Log.d("OpenRangViewModel", "Capture finalized (${durationMs}ms): ${outputFile.absolutePath}")
                                _editorState.value = TrimState(
                                    sourceFile = outputFile,
                                    sourceDurationMs = durationMs,
                                    trimStartMs = 0L,
                                    trimEndMs = durationMs,
                                )
                                _uiState.value = OpenRangUiState.Trim(EditorSource.ScratchClip(scratch.uuid))
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
                Log.e("OpenRangViewModel", "startRecording returned null (camera not bound); aborting capture")
                clearRecordingTimers()
                _uiState.value = OpenRangUiState.ReadyToCapture
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
            // (PendingRecording.start docs). Also, the path withAudioEnabled() takes when the
            // Recorder doesn't support audio. Recover to idle rather than wedging in Recording.
            recoverFromFailedStart(e)
        } catch (e: SecurityException) {
            // withAudioEnabled() throws this if RECORD_AUDIO was revoked between our permission
            // check and start() (PendingRecording.withAudioEnabled docs). Recover to idle.
            recoverFromFailedStart(e)
        }
        // NOTE: deliberately NOT catching Exception broadly (REC-3 / ANDROID_STANDARDS §3). The
        // synchronous start path only declares IllegalStateException + SecurityException; CameraX
        // surfaces IO/encoder failures asynchronously via VideoRecordEvent.Finalize (handled above),
        // not as a throw. Letting any other throwable propagate keeps real programming errors visible.
    }

    /** Shared recovery for a synchronous start-recording failure: log, cancel timers, go idle. */
    private fun recoverFromFailedStart(e: Exception) {
        Log.e("OpenRangViewModel", "Failed to start burst capture", e)
        clearRecordingTimers()
        _uiState.value = OpenRangUiState.ReadyToCapture
    }

    fun loadRecordedVideos() {
        // Directory scan + lazy thumbnail decode runs on Dispatchers.IO inside the repo; launch so
        // the read never blocks the caller's (main) thread (ANDROID_STANDARDS §9).
        viewModelScope.launch {
            _recordedVideos.value = videoStorage.loadRecordedVideos()
        }
    }

    fun deleteVideo(video: RecordedVideo) {
        viewModelScope.launch {
            videoStorage.deleteVideo(video)
            _recordedVideos.value = videoStorage.loadRecordedVideos()
        }
    }

    fun navigateToGallery() {
        _uiState.value = OpenRangUiState.Gallery
        loadRecordedVideos()
    }

    fun navigateBackFromGallery() {
        _uiState.value = OpenRangUiState.ReadyToCapture
    }

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

    fun resetToCapture() {
        _uiState.value = OpenRangUiState.ReadyToCapture
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
        _editorState.value = current.copy(trimStartMs = start, trimEndMs = end)
    }

    /** Discard the scratch clip and return to capture (the Trim back-arrow / confirm-discard path). */
    fun discardTrim() {
        activeScratch?.let { videoStorage.discardScratch(it) }
        clearEditorSession()
        _uiState.value = OpenRangUiState.ReadyToCapture
    }

    /**
     * NEXT on the Trim screen: open the tabbed boomerang editor over the current trim (slice 03).
     * Resets the editor tabs to defaults (`FORWARD_THEN_REVERSE`), routes to
     * [OpenRangUiState.BoomerangEditor], and eagerly kicks off reverse generation so the default
     * direction's preview is ready ASAP. The actual save now happens from the editor's checkmark
     * ([saveBoomerang]); slice 02's default-render-on-NEXT is gone.
     */
    fun onNextFromTrim() {
        val scratch = activeScratch ?: return
        if (_editorState.value == null) return
        _editorTabState.value = EditorTabState()
        _uiState.value = OpenRangUiState.BoomerangEditor(EditorSource.ScratchClip(scratch.uuid))
        ensureReversedSegment()
    }

    /** Back arrow / back gesture from the editor: return to Trim, preserving the trim selection. */
    fun backToTrim() {
        val scratch = activeScratch ?: run {
            _uiState.value = OpenRangUiState.ReadyToCapture
            return
        }
        cancelReverseJob()
        _uiState.value = OpenRangUiState.Trim(EditorSource.ScratchClip(scratch.uuid))
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
        if (mode.needsReverse) ensureReversedSegment()
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
    }

    /**
     * Set the color look from the editor's Looks tab (slice 05). Like [updateSpeed] it's a pure
     * effect selection — applied live in the preview via `setVideoEffects` and baked into the render;
     * it never touches the cached [EditorTabState.reversedFile] or the output duration.
     */
    fun updateFilter(filter: VideoFilter) {
        val current = _editorTabState.value
        if (current.filter == filter) return
        _editorTabState.value = current.copy(filter = filter)
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
    fun ensureReversedSegment() {
        val trim = _editorState.value ?: return
        val tab = _editorTabState.value
        if (!tab.mode.needsReverse) return
        if (tab.reversedFile != null || tab.isReversedFileLoading) return

        _editorTabState.value = tab.copy(isReversedFileLoading = true)
        reverseJob = viewModelScope.launch {
            try {
                val reversed = videoProcessor.ensureReversed(trim.sourceFile, trim.trimStartMs, trim.trimEndMs)
                _editorTabState.value = _editorTabState.value.copy(
                    reversedFile = reversed,
                    isReversedFileLoading = false,
                )
            } catch (e: CancellationException) {
                throw e // never swallow cancellation (Lesson 013)
            } catch (e: Exception) {
                Log.e("OpenRangViewModel", "Reverse generation for preview failed", e)
                _editorTabState.value = _editorTabState.value.copy(isReversedFileLoading = false)
            }
        }
    }

    /**
     * Save the boomerang in the editor's current direction + speed + look (reps stays hard-wired at 1
     * — the reps tab was dropped for the Looks tab). Flips to [OpenRangUiState.Processing]; on success promotes the scratch to a persistent
     * raw, registers the boomerang, emits [BoomerangEvent.Saved] and returns to capture. The render
     * sources the **scratch** file — the same path the preview reversed — so a reverse-containing mode
     * hits the cached reversed clip instead of regenerating it (speed is applied per clip at render and
     * doesn't invalidate that cache). On failure, it emits [BoomerangEvent.Failed] and routes back to
     * [OpenRangUiState.BoomerangEditor] with the direction + speed selection intact.
     */
    fun saveBoomerang() {
        val editor = _editorState.value ?: return
        val scratch = activeScratch ?: return
        val tab = _editorTabState.value
        val mode = tab.mode

        _uiState.value = OpenRangUiState.Processing
        _renderProgress.value = 0f

        viewModelScope.launch {
            try {
                // Promote once and cache it, so a retry after a failed render doesn't create a 2nd raw.
                val raw = promotedRaw
                    ?: (videoStorage.promoteScratchToRaw(scratch)?.also { promotedRaw = it }
                        ?: throw IOException("Failed to promote scratch ${scratch.uuid} to a raw"))

                val output = videoStorage.allocateBoomerangFile(raw.id)
                videoProcessor.renderBoomerang(
                    source = scratch.file, // same path the preview reversed → shared reverse cache
                    trimStartMs = editor.trimStartMs,
                    trimEndMs = editor.trimEndMs,
                    mode = mode,
                    speed = tab.speed,
                    filter = tab.filter,
                    repetitions = DEFAULT_REPS,
                    outputFile = output,
                ) { fraction -> _renderProgress.value = fraction }

                videoStorage.registerBoomerang(output, raw.id)
                    ?: throw IOException("Failed to register boomerang ${output.name}")

                videoStorage.discardScratch(scratch)
                clearEditorSession()
                loadRecordedVideos()
                _events.send(BoomerangEvent.Saved)
                _uiState.value = OpenRangUiState.ReadyToCapture
            } catch (e: CancellationException) {
                throw e // never swallow cancellation (Lesson 013)
            } catch (e: IOException) {
                Log.e("OpenRangViewModel", "Boomerang save failed (IO)", e)
                failBackToEditor(scratch)
            } catch (e: RuntimeException) {
                // Media3 / MediaCodec surface render failures as runtime exceptions; recover the UI.
                Log.e("OpenRangViewModel", "Boomerang render failed", e)
                failBackToEditor(scratch)
            }
        }
    }

    /** Emit [BoomerangEvent.Failed] and route back to the editor, preserving the direction selection. */
    private suspend fun failBackToEditor(scratch: ScratchCapture) {
        _events.send(BoomerangEvent.Failed)
        _uiState.value = OpenRangUiState.BoomerangEditor(EditorSource.ScratchClip(scratch.uuid))
    }

    /** Cancel any in-flight reverse generation (editor left or session cleared). */
    private fun cancelReverseJob() {
        reverseJob?.cancel()
        reverseJob = null
    }

    /** Clear the active editing session (after a save or discard). Does NOT touch on-disk files. */
    private fun clearEditorSession() {
        cancelReverseJob()
        activeScratch = null
        promotedRaw = null
        _editorState.value = null
        _editorTabState.value = EditorTabState()
        _renderProgress.value = 0f
    }

    /**
     * Factory for creating [OpenRangViewModel] with its repository dependencies.
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

        /** Default boomerang config. Direction picker shipped slice 03, speed slider slice 04; reps (1) is
         *  still hard-wired until slice 05. [DEFAULT_SPEED] is the slider's starting value. */
        const val DEFAULT_SPEED = 2.0f
        const val DEFAULT_REPS = 1

        /** Playback-speed slider bounds (slice 04); [updateSpeed] clamps to this range. */
        const val MIN_SPEED = 0.25f
        const val MAX_SPEED = 3.0f
    }

    class Factory(
        private val userPreferencesRepository: UserPreferencesRepository,
        private val videoStorage: VideoStorageRepository,
        private val videoProcessor: VideoProcessor,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(OpenRangViewModel::class.java)) {
                return OpenRangViewModel(userPreferencesRepository, videoStorage, videoProcessor) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
