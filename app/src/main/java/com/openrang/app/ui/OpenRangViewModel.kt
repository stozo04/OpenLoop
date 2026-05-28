package com.openrang.app.ui

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.openrang.app.camera.CameraManager
import com.openrang.app.data.UserPreferencesRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import androidx.camera.video.VideoRecordEvent
import android.media.MediaMetadataRetriever
import android.graphics.Bitmap

data class RecordedVideo(
    val id: Long,
    val videoPath: String,
    val thumbnailPath: String
)

class OpenRangViewModel(
    private val userPreferencesRepository: UserPreferencesRepository
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
                            val savedFile = saveFinalizedVideo(context, outputFile)
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

    private fun saveFinalizedVideo(context: Context, tempFile: File): File? {
        val timestamp = System.currentTimeMillis()
        val videoDir = File(context.filesDir, "videos").apply { mkdirs() }
        val thumbDir = File(context.filesDir, "thumbnails").apply { mkdirs() }

        val destVideo = File(videoDir, "clip_$timestamp.mp4")
        val destThumb = File(thumbDir, "clip_$timestamp.jpg")

        return try {
            tempFile.copyTo(destVideo, overwrite = true)

            // Extract thumbnail using MediaMetadataRetriever
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(destVideo.absolutePath)
            val bitmap = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            if (bitmap != null) {
                destThumb.outputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
            }
            retriever.release()
            destVideo
        } catch (e: Exception) {
            Log.e("OpenRangViewModel", "Failed to save video loop persistent copy", e)
            null
        }
    }

    fun loadRecordedVideos(context: Context) {
        val videoDir = File(context.filesDir, "videos")
        val thumbDir = File(context.filesDir, "thumbnails")

        if (!videoDir.exists()) {
            _recordedVideos.value = emptyList()
            return
        }

        val files = videoDir.listFiles { _, name -> name.startsWith("clip_") && name.endsWith(".mp4") }
        if (files == null) {
            _recordedVideos.value = emptyList()
            return
        }

        val list = files.mapNotNull { file ->
            val name = file.name
            val timestampStr = name.removePrefix("clip_").removeSuffix(".mp4")
            val id = timestampStr.toLongOrNull() ?: 0L

            val thumbFile = File(thumbDir, "clip_$timestampStr.jpg")
            if (thumbFile.exists()) {
                RecordedVideo(id, file.absolutePath, thumbFile.absolutePath)
            } else {
                // If thumbnail doesn't exist, extract it now on demand!
                try {
                    thumbDir.mkdirs()
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(file.absolutePath)
                    val bitmap = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    if (bitmap != null) {
                        thumbFile.outputStream().use { out ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                        }
                    }
                    retriever.release()
                    RecordedVideo(id, file.absolutePath, thumbFile.absolutePath)
                } catch (e: Exception) {
                    Log.e("OpenRangViewModel", "Failed to extract on-demand thumbnail for ${file.name}", e)
                    null
                }
            }
        }.sortedByDescending { it.id } // Newest first

        _recordedVideos.value = list
    }

    fun deleteVideo(context: Context, video: RecordedVideo) {
        try {
            val videoFile = File(video.videoPath)
            if (videoFile.exists()) {
                videoFile.delete()
            }
            val thumbFile = File(video.thumbnailPath)
            if (thumbFile.exists()) {
                thumbFile.delete()
            }
            loadRecordedVideos(context)
        } catch (e: Exception) {
            Log.e("OpenRangViewModel", "Failed to delete video ${video.id}", e)
        }
    }

    fun navigateToGallery(context: Context) {
        _uiState.value = OpenRangUiState.Gallery
        loadRecordedVideos(context)
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
     * Factory for creating [OpenRangViewModel] with its [UserPreferencesRepository] dependency.
     * Used in MainActivity since we don't have a DI framework.
     */
    class Factory(
        private val userPreferencesRepository: UserPreferencesRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(OpenRangViewModel::class.java)) {
                return OpenRangViewModel(userPreferencesRepository) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
