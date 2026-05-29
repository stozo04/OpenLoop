package com.openrang.app.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.openrang.app.camera.CameraManager
import com.openrang.app.data.RecordedVideo
import com.openrang.app.data.UserPreferencesRepository
import com.openrang.app.data.VideoStorageRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.IOException
import androidx.camera.video.VideoRecordEvent

class OpenRangViewModel(
    private val userPreferencesRepository: UserPreferencesRepository,
    private val videoStorage: VideoStorageRepository,
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

    fun startBurstCapture(cameraManager: CameraManager) {
        if (_uiState.value != OpenRangUiState.ReadyToCapture) return

        _uiState.value = OpenRangUiState.Recording

        val outputFile = videoStorage.rawCaptureFile
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
                            _uiState.value = OpenRangUiState.ReadyToCapture
                        } else {
                            val savedFile = videoStorage.saveFinalizedVideo(outputFile)
                            val finalPath = savedFile?.absolutePath ?: outputFile.absolutePath
                            Log.d("OpenRangViewModel", "Video burst recording finalized successfully: $finalPath")

                            // Transition to LoopingPreview state for verification
                            _uiState.value = OpenRangUiState.LoopingPreview(
                                videoPath = finalPath,
                                playbackSpeed = 1.5f
                            )
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
        _recordedVideos.value = videoStorage.loadRecordedVideos()
    }

    fun deleteVideo(video: RecordedVideo) {
        videoStorage.deleteVideo(video)
        loadRecordedVideos()
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
    }

    class Factory(
        private val userPreferencesRepository: UserPreferencesRepository,
        private val videoStorage: VideoStorageRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(OpenRangViewModel::class.java)) {
                return OpenRangViewModel(userPreferencesRepository, videoStorage) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
