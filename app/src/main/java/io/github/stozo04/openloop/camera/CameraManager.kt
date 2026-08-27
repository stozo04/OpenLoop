package io.github.stozo04.openloop.camera

import android.content.Context
import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.UseCase
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
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
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import io.github.stozo04.openloop.camera.lens.FaceTracker
import io.github.stozo04.openloop.camera.lens.HandTracker
import io.github.stozo04.openloop.camera.lens.Lens
import io.github.stozo04.openloop.camera.lens.LensSurfaceProcessor
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class CameraManager(private val context: Context) {
    private var cameraProvider: ProcessCameraProvider? = null
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var currentLensFacing = CameraSelector.LENS_FACING_BACK
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var camera: Camera? = null

    /**
     * The lens renderer. Created once and attached on EVERY bind, whether a lens is
     * selected — see [LensSurfaceProcessor]'s header. Attaching or removing a [CameraEffect]
     * requires a rebind, and a rebind during capture finalizes the recording with
     * `ERROR_SOURCE_INACTIVE` (Lesson 012), so "no lens" is an identity pass-through rather than
     * an absent effect.
     */
    private val lensProcessor = LensSurfaceProcessor(context)
    private val faceTracker = FaceTracker(lensProcessor::setFaces)

    /**
     * Hand tracking for the flick verb (`docs/PRD-lens-hand-flick.md`). Shares [cameraExecutor]
     * with the face tracker so its create/submit/close all happen on one thread, and is alive only
     * while a flickable lens is active — see [setLens].
     */
    private val handTracker = HandTracker(context, cameraExecutor, lensProcessor::setHand)
    private var zoomStateObserver: Observer<ZoomState>? = null
    private var observedZoomState: LiveData<ZoomState>? = null
    /** Per-pinch anchor ratio — avoids stale [ZoomState] reads during a fast scale stream. */
    private var pinchSessionRatio: Float? = null
    /** Max ratio reached this pinch — detects zoom-in-then-out undershoot below 1.0x. */
    private var pinchSessionPeakRatio: Float? = null
    /** One-shot: enforce PRD D3's 1.0x default on the FIRST ZoomState emission after each bind. */
    private var resetToDefaultOnNextEmission = false

    /** Live zoom snapshot for the UI; `null` while no camera is bound. */
    private val _zoomUi = MutableStateFlow<ZoomUi?>(null)
    val zoomUi: StateFlow<ZoomUi?> = _zoomUi.asStateFlow()

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

                // Face tracking for lenses. Pinned to 4:3 — the sensor's native shape, and the
                // shape CameraX picks for the shared effect stream (measured: 1280x960). Two
                // streams of the SAME aspect cover the same sensor rectangle, so a landmark's
                // normalized position means the same thing in both and no correction is needed.
                //
                // A 16:9 analysis stream was tried first and put every lens visibly off sideways:
                // 16:9 and 4:3 see different parts of the sensor, and modeling that difference is
                // guesswork about how the device derives one from the other. Matching the shapes
                // removes the question instead of answering it.
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(
                                AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
                            )
                            .build()
                    )
                    .build()
                    .also { analysis ->
                        // One stream, two detectors (PRD-lens-hand-flick §3.2): the hand tracker
                        // takes its copy of the frame first, then ML Kit owns — and closes — the
                        // proxy exactly as before. A failed hand submit (toBitmap(), detectAsync)
                        // is logged and the frame skipped; the finally hands the proxy to the face
                        // path even when something worse than an Exception escapes (an OOM in the
                        // bitmap copy), because an unclosed proxy under KEEP_ONLY_LATEST stalls
                        // the whole analysis stream until the next bind.
                        analysis.setAnalyzer(
                            cameraExecutor,
                            ImageAnalysis.Analyzer { proxy ->
                                try {
                                    handTracker.submit(proxy)
                                } catch (exc: Exception) {
                                    Log.w(TAG, "Hand tracker submit failed; hand tracking skipped this frame", exc)
                                } finally {
                                    faceTracker.analyze(proxy)
                                }
                            },
                        )
                    }

                // A lens flip lands here without releaseCamera(); the roster must not carry the
                // other sensor's faces into this bind (see FaceTracker.reset), and the hand
                // tracker drops its landmarker so the first frame of this bind rebuilds it on the
                // new sensor's clock.
                cameraProvider?.unbindAll()
                detachZoomObserver()
                faceTracker.reset()
                handTracker.reset()
                camera = bindWithLensEffect(
                    lifecycleOwner = lifecycleOwner,
                    cameraSelector = cameraSelector,
                    preview = preview,
                    analysis = analysis,
                )
                attachZoomObserver()
                // The build marker suffix is the stale-APK tripwire (Lesson 012's environment
                // notes): a logcat whose bind lines lack it was made by a build without the
                // flick feature, whatever the checkout says.
                Log.i(
                    TAG,
                    "Camera bound (lens=${if (lensFacing == CameraSelector.LENS_FACING_BACK) "back" else "front"}) " +
                        "build=hand-flick-v1"
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
     * Binds preview + video + face analysis with the lens effect attached.
     *
     * `Preview + VideoCapture + ImageAnalysis` is only *guaranteed* on `LEVEL_3` hardware. CameraX
     * 1.5+ normally absorbs that with a shared stream, but on devices where the combination still
     * fails we drop face tracking rather than the camera: lenses go inert, everything else works.
     * A black viewfinder is not an acceptable trade for a novelty feature.
     */
    private fun bindWithLensEffect(
        lifecycleOwner: LifecycleOwner,
        cameraSelector: CameraSelector,
        preview: Preview,
        analysis: ImageAnalysis,
    ): Camera? {
        val provider = cameraProvider ?: return null
        return try {
            provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                lensUseCaseGroup(preview, analysis),
            )
        } catch (exc: IllegalArgumentException) {
            Log.w(TAG, "Face analysis not supported alongside preview + video; lenses disabled", exc)
            analysis.clearAnalyzer()
            lensProcessor.setFaces(emptyList())
            provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                lensUseCaseGroup(preview, analysis = null),
            )
        }
    }

    private fun lensUseCaseGroup(preview: Preview, analysis: ImageAnalysis?): UseCaseGroup =
        UseCaseGroup.Builder().apply {
            addUseCase(preview)
            videoCapture?.let { addUseCase(it as UseCase) }
            analysis?.let { addUseCase(it) }
            addEffect(LensCameraEffect(lensProcessor.glExecutor, lensProcessor))
        }.build()

    /**
     * Selects the active lens (or `null` to clear it). Deliberately a field write on the always
     * attached effect, not a rebind — so this is safe to call mid-recording.
     */
    fun setLens(lens: Lens?) {
        lensProcessor.setLens(lens)
        // The hand tracker exists only for a lens something can be flicked on (PRD D5) — every
        // other lens, and no lens, never pays for the second detector.
        handTracker.setEnabled(lens?.isFlickable == true)
    }

    /** Whether a [Camera] handle is currently bound — safe to gate pinch gestures on this alone. */
    fun isCameraBound(): Boolean = camera != null

    /**
     * Device-reported zoom bounds and live ratio. Prefer CameraX [ZoomState] when its [LiveData.value]
     * is populated; fall back to the mirrored [_zoomUi] snapshot so pinch ticks still apply when
     * the synchronous `.value` read lags the observer (seen on physical hardware after rebind).
     */
    private fun currentZoomBounds(): ZoomBounds? {
        val state = camera?.cameraInfo?.zoomState?.value
        val ui = _zoomUi.value
        val min = state?.minZoomRatio ?: ui?.minRatio ?: return null
        val max = state?.maxZoomRatio ?: ui?.maxRatio ?: return null
        val ratio = state?.zoomRatio ?: ui?.ratio ?: DEFAULT_ZOOM_RATIO
        return ZoomBounds(min = min, max = max, ratio = ratio)
    }

    /**
     * Seeds the per-pinch anchor from the live [ZoomState] at gesture start. Call from
     * [android.view.ScaleGestureDetector.SimpleOnScaleGestureListener.onScaleBegin].
     */
    fun onPinchZoomBegin() {
        val bounds = currentZoomBounds()
        if (bounds == null) {
            Log.w(TAG, "onPinchZoomBegin: zoom bounds unavailable")
            return
        }
        val start = bounds.ratio
        pinchSessionRatio = start
        pinchSessionPeakRatio = start
        Log.i(TAG, "Pinch session started at ${start}x")
    }

    /**
     * Applies one pinch tick against the session anchor (not the possibly stale LiveData snapshot).
     * Each [scaleFactor] multiplies the running session ratio, then clamps to the device range.
     * Lazily opens a session on the first [onScale] tick if [onScaleBegin] was skipped.
     */
    fun applyPinchZoom(scaleFactor: Float) {
        val boundCamera = camera ?: return
        val bounds = currentZoomBounds() ?: run {
            Log.w(TAG, "applyPinchZoom: zoom bounds unavailable — tick ignored")
            return
        }
        if (pinchSessionRatio == null) {
            pinchSessionRatio = bounds.ratio
            pinchSessionPeakRatio = bounds.ratio
        }
        val session = pinchSessionRatio ?: return
        val requested = clampZoom(session * scaleFactor, bounds.min, bounds.max)
        pinchSessionRatio = requested
        if (requested > (pinchSessionPeakRatio ?: 0f)) {
            pinchSessionPeakRatio = requested
        }
        boundCamera.cameraControl.setZoomRatio(requested)
    }

    /**
     * Ends the pinch session and snaps to [DEFAULT_ZOOM_RATIO] when the final ratio qualifies
     * (near-default magnetic snap, or zoom-in-then-out multiplicative undershoot).
     */
    fun onPinchZoomEnd() {
        if (camera == null) {
            pinchSessionRatio = null
            pinchSessionPeakRatio = null
            return
        }
        val bounds = currentZoomBounds()
        val finalRatio = pinchSessionRatio ?: bounds?.ratio
        val peakRatio = pinchSessionPeakRatio ?: finalRatio
        val minRatio = bounds?.min
        pinchSessionRatio = null
        pinchSessionPeakRatio = null
        if (finalRatio == null || peakRatio == null || minRatio == null) return
        val snapped = snapZoomOnGestureEnd(peakRatio, finalRatio, minRatio)
        if (snapped != finalRatio) {
            setZoomRatio(snapped)
            // LiveData lags behind setZoomRatio during the chip's 1 s linger — mirror immediately.
            _zoomUi.value = _zoomUi.value?.copy(ratio = snapped)
        }
    }

    /**
     * Clamps [requested] to the device-reported [ZoomState] range and applies it to all bound use
     * cases (Preview *and* VideoCapture — this is what bakes zoom into the recording). Safe no-op
     * when no camera is bound, e.g. a late gesture event arriving after [releaseCamera].
     *
     * The future returned by [androidx.camera.core.CameraControl.setZoomRatio] is intentionally not
     * awaited: during a pinch stream each new call cancels the previous outstanding request
     * (documented CameraX behavior), so per-event verification would only report expected
     * cancellations.
     */
    fun setZoomRatio(requested: Float) {
        val boundCamera = camera ?: return
        val bounds = currentZoomBounds() ?: return
        boundCamera.cameraControl.setZoomRatio(
            clampZoom(requested, bounds.min, bounds.max)
        )
    }

    private data class ZoomBounds(val min: Float, val max: Float, val ratio: Float)

    /**
     * Mirrors the bound camera's [ZoomState] LiveData into [zoomUi]. [CameraManager] is not a
     * [LifecycleOwner], so this observes with `observeForever`; [detachZoomObserver] removes it
     * before every rebind and in [releaseCamera] — a leaked observer across rebinds is the failure
     * mode. Runs on the main thread (the [startCamera] listener uses the main executor), which
     * LiveData observation requires.
     */
    private fun attachZoomObserver() {
        val zoomState = camera?.cameraInfo?.zoomState
        if (zoomState == null) {
            Log.w(TAG, "attachZoomObserver: cameraInfo.zoomState unavailable — pinch zoom disabled")
            return
        }
        resetToDefaultOnNextEmission = true
        val observer = Observer<ZoomState> { state ->
            val previous = _zoomUi.value
            if (previous == null ||
                previous.minRatio != state.minZoomRatio ||
                previous.maxRatio != state.maxZoomRatio
            ) {
                // Range discovery is device-dependent (OEMs hide lenses; PRD-capture-zoom §5.1) —
                // log it once per bind/range change, never per ratio tick.
                Log.i(
                    TAG,
                    "Zoom range [${state.minZoomRatio}, ${state.maxZoomRatio}], " +
                        "ratio ${state.zoomRatio}"
                )
            }
            if (resetToDefaultOnNextEmission) {
                // PRD D3: enforce the 1.0x default here, on the first emission, rather than a
                // posted one-shot that races this observer and silently no-ops when the range is
                // not yet known (the pre-fix failure mode on Fold-class hardware).
                resetToDefaultOnNextEmission = false
                val target = zoomResetTargetAfterBind(
                    reportedRatio = state.zoomRatio,
                    minRatio = state.minZoomRatio,
                    maxRatio = state.maxZoomRatio,
                )
                if (target != null) {
                    camera?.cameraControl?.setZoomRatio(target)
                    // Mirror the forced default, not the stale pre-reset ratio; CameraX's
                    // confirming emission follows with the same value.
                    _zoomUi.value = ZoomUi(
                        ratio = target,
                        minRatio = state.minZoomRatio,
                        maxRatio = state.maxZoomRatio,
                    )
                    Log.i(TAG, "Reset zoom to ${target}x on first emission after bind (was ${state.zoomRatio})")
                    return@Observer
                }
            }
            _zoomUi.value = ZoomUi(
                ratio = state.zoomRatio,
                minRatio = state.minZoomRatio,
                maxRatio = state.maxZoomRatio
            )
        }
        zoomStateObserver = observer
        observedZoomState = zoomState
        zoomState.observeForever(observer)
        // LiveData may already hold a value; observeForever does not always synchronously dispatch.
        zoomState.value?.let { observer.onChanged(it) }
            ?: Log.w(TAG, "attachZoomObserver: zoomState.value null at bind — awaiting first emission")
    }

    private fun detachZoomObserver() {
        zoomStateObserver?.let { observedZoomState?.removeObserver(it) }
        zoomStateObserver = null
        observedZoomState = null
        pinchSessionRatio = null
        pinchSessionPeakRatio = null
        resetToDefaultOnNextEmission = false
        _zoomUi.value = null
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
            detachZoomObserver()
            // Stop the analysis stream before reset() so no in-flight analyze() can re-seed the
            // roster after the epoch bump (the race fixed by swapping this order). faceTracker
            // outlives both unbind and the rebuilt ImageAnalysis, and holds the last faces for
            // 350 ms — drop them so the next bind starts with a clean viewfinder.
            cameraProvider?.unbindAll()
            faceTracker.reset()
            handTracker.reset()
            videoCapture = null
            camera = null
        } catch (exc: Exception) {
            Log.e(TAG, "releaseCamera failed", exc)
        }
    }

    fun shutdown() {
        releaseCamera()
        // The lens renderer outlives individual binds (it must — see bindWithLensEffect), so it is
        // torn down here rather than in releaseCamera(). The hand tracker's close lands on
        // cameraExecutor ahead of the shutdown below, which still drains queued work.
        handTracker.close()
        faceTracker.close()
        lensProcessor.release()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "OpenLoopCameraManager"
    }
}

/**
 * The one effect OpenLoop attaches. Targets preview *and* video capture so what the user sees is
 * exactly what lands in the `.mp4` — the lens is baked into the recording and flows untouched
 * through trim / reverse / speed / Looks downstream (PRD-camera-lenses §5.4).
 */
private class LensCameraEffect(
    executor: Executor,
    processor: SurfaceProcessor,
) : CameraEffect(
    PREVIEW or VIDEO_CAPTURE,
    executor,
    processor,
    { throwable -> Log.e("OpenLoopLens", "Lens effect failed", throwable) },
)
