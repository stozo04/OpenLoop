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
            cameraManager.startRecording(outputFile) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        Log.d("OpenRangViewModel", "Video burst recording started.")
                    }
                    is VideoRecordEvent.Finalize -> {
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

            // Start automatic timer for exactly 1.5 seconds (1500 ms)
            recordingJob = viewModelScope.launch {
                delay(1500)
                stopBurstCapture(cameraManager)
            }
        } catch (e: Exception) {
            Log.e("OpenRangViewModel", "Failed to start burst capture", e)
            _uiState.value = OpenRangUiState.ReadyToCapture
        }
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

    fun stopBurstCapture(cameraManager: CameraManager) {
        recordingJob?.cancel()
        recordingJob = null
        cameraManager.stopRecording()
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
