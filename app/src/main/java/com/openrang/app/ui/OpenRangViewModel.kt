package com.openrang.app.ui

import androidx.lifecycle.ViewModel
import android.content.Context
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.openrang.app.camera.CameraManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import androidx.camera.video.VideoRecordEvent

class OpenRangViewModel : ViewModel() {
    // Start with the beautiful Onboarding Carousel
    private val _uiState = MutableStateFlow<OpenRangUiState>(OpenRangUiState.Onboarding)
    val uiState: StateFlow<OpenRangUiState> = _uiState.asStateFlow()

    fun onOnboardingCompleted() {
        _uiState.value = OpenRangUiState.CheckingPermissions
    }

    fun onPermissionsChecked(granted: Boolean) {
        _uiState.value = if (granted) {
            OpenRangUiState.ReadyToCapture
        } else {
            OpenRangUiState.PermissionDenied
        }
    }

    private var recordingJob: Job? = null

    fun startBurstCapture(context: Context, cameraManager: CameraManager) {
        if (_uiState.value != OpenRangUiState.ReadyToCapture) return

        _uiState.value = OpenRangUiState.Recording

        val outputFile = File(context.cacheDir, "raw_capture.mp4")
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
                            Log.d("OpenRangViewModel", "Video burst recording finalized successfully: ${outputFile.length()} bytes")
                            // Transition to LoopingPreview state for verification in Phase 2
                            _uiState.value = OpenRangUiState.LoopingPreview(
                                videoPath = outputFile.absolutePath,
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

    fun stopBurstCapture(cameraManager: CameraManager) {
        recordingJob?.cancel()
        recordingJob = null
        cameraManager.stopRecording()
    }

    fun resetToCapture() {
        _uiState.value = OpenRangUiState.ReadyToCapture
    }
}
