package io.github.stozo04.openloop.camera

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraManager(private val context: Context) {
    private var cameraProvider: ProcessCameraProvider? = null
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var currentLensFacing = CameraSelector.LENS_FACING_BACK
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null

    fun startCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        lensFacing: Int = currentLensFacing,
        onCameraReady: () -> Unit = {}
    ) {
        currentLensFacing = lensFacing
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder()
                    .build()
                    .also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }

                val cameraSelector = CameraSelector.Builder()
                    .requireLensFacing(lensFacing)
                    .build()

                // Set up VideoCapture use-case
                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.HD))
                    .build()
                videoCapture = VideoCapture.withOutput(recorder)

                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    videoCapture
                )

                onCameraReady()
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun toggleCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        onCameraReady: () -> Unit = {}
    ) {
        val newLensFacing = if (currentLensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        startCamera(lifecycleOwner, previewView, newLensFacing, onCameraReady)
    }

    fun startRecording(
        outputFile: File,
        onRecordEvent: (VideoRecordEvent) -> Unit
    ): Recording? {
        val capture = videoCapture ?: return null

        val fileOutputOptions = FileOutputOptions.Builder(outputFile).build()
        val recording = capture.output
            .prepareRecording(context, fileOutputOptions)
            .start(ContextCompat.getMainExecutor(context), onRecordEvent)

        activeRecording = recording
        return recording
    }

    fun stopRecording() {
        activeRecording?.stop()
        activeRecording = null
    }

    /**
     * Detach preview and video use cases when the [PreviewView] leaves composition (e.g. navigating
     * to Trim / Gallery / Editor) while the [LifecycleOwner] may stay RESUMED. Without this, the HAL
     * can queue buffers into an abandoned [PreviewView] surface → log noise (`BufferQueue has been
     * abandoned`, surface disconnect races). Re-bind via [startCamera] when [CameraScreen] returns.
     *
     * Skipped while [activeRecording] is non-null so we do not unbind mid-capture
     * ([VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE], Lesson 012).
     */
    fun releaseCamera() {
        if (activeRecording != null) {
            Log.d(TAG, "releaseCamera skipped: recording in progress")
            return
        }
        try {
            cameraProvider?.unbindAll()
            videoCapture = null
        } catch (exc: Exception) {
            Log.e(TAG, "releaseCamera failed", exc)
        }
    }

    fun shutdown() {
        releaseCamera()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "CameraManager"
    }
}
