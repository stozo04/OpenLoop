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
import com.openrang.app.media.VideoProcessor
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
import java.io.File
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
     * Render the default boomerang (`FORWARD_THEN_REVERSE`, 2×, 1 rep) from the current trim window
     * and save it. Flips to [OpenRangUiState.Processing] for the spinner, then on success promotes
     * the scratch to a persistent raw, registers the boomerang, emits [BoomerangEvent.Saved] and
     * returns to capture. On failure, it emits [BoomerangEvent.Failed] and routes back to [OpenRangUiState.Trim]
     * with the trim selection intact (the editor state is left untouched on the failure path).
     */
    fun onNextFromTrim() {
        val editor = _editorState.value ?: return
        val scratch = activeScratch ?: return

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
                    source = File(raw.videoPath),
                    trimStartMs = editor.trimStartMs,
                    trimEndMs = editor.trimEndMs,
                    mode = BoomerangMode.FORWARD_THEN_REVERSE,
                    speed = DEFAULT_SPEED,
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
                failBackToTrim(scratch)
            } catch (e: RuntimeException) {
                // Media3 / MediaCodec surface render failures as runtime exceptions; recover the UI.
                Log.e("OpenRangViewModel", "Boomerang render failed", e)
                failBackToTrim(scratch)
            }
        }
    }

    /** Emit [BoomerangEvent.Failed] and route back to Trim, preserving the (untouched) trim selection. */
    private suspend fun failBackToTrim(scratch: ScratchCapture) {
        _events.send(BoomerangEvent.Failed)
        _uiState.value = OpenRangUiState.Trim(EditorSource.ScratchClip(scratch.uuid))
    }

    /** Clear the active editing session (after a save or discard). Does NOT touch on-disk files. */
    private fun clearEditorSession() {
        activeScratch = null
        promotedRaw = null
        _editorState.value = null
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

        /** Hard-wired default boomerang config for slice 02 (direction/speed/reps pickers arrive 03–05). */
        const val DEFAULT_SPEED = 2.0f
        const val DEFAULT_REPS = 1
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
