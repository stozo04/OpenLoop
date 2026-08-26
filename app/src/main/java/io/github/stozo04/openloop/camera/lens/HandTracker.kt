package io.github.stozo04.openloop.camera.lens

import android.content.Context
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.MediaPipeException
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.util.concurrent.Executor

/**
 * MediaPipe Hand Landmarker glue in front of [HandFlick] — the hand-tracking sibling of
 * [FaceTracker] (`docs/PRD-lens-hand-flick.md` §3.2).
 *
 * Fed from the **same** `ImageAnalysis` stream as the face tracker: [submit] takes a bitmap copy
 * of the frame before ML Kit is handed the proxy, so neither detector extends the proxy's life for
 * the other. Everything that touches the landmarker — creation, submission, teardown — runs on
 * the one [executor] the analyzer already runs on, so there is nothing to lock; results arrive on
 * MediaPipe's own thread and go out through [onHand] as a latest-wins write, like faces.
 *
 * Alive only while a lens that can be flicked is active ([setEnabled]): every other lens, and no
 * lens, pays nothing for this (PRD D5).
 */
class HandTracker(
    context: Context,
    private val executor: Executor,
    private val onHand: (HandSnapshot?) -> Unit,
) {
    private val appContext = context.applicationContext

    /** Executor thread only. */
    private var landmarker: HandLandmarker? = null

    /**
     * The last timestamp handed to the landmarker — MediaPipe's live stream demands strictly
     * increasing ones. Executor thread only.
     */
    private var lastSubmittedMs = Long.MIN_VALUE

    /**
     * Bumped on every teardown so a result still in flight from the old landmarker is dropped
     * instead of resurrecting a hand from the previous bind — the same epoch idea as
     * [FaceTracker]. Written on the executor thread, read on the result thread, hence volatile.
     */
    @Volatile
    private var epoch = 0

    @Volatile
    private var wanted = false

    /** Result thread only: the appear/vanish edges, logged once each so QA can see the tracker work. */
    private var handVisible = false

    /**
     * Main thread. Turns hand tracking on or off. Creating the landmarker loads a 7.8 MB model,
     * so that happens on [executor], never here; the first frames after enabling simply see no
     * hand yet.
     */
    fun setEnabled(enabled: Boolean) {
        if (wanted == enabled) return
        wanted = enabled
        executor.execute { if (enabled) open() else closeLandmarker() }
    }

    /**
     * Analyzer thread, **before** ML Kit is handed the proxy. Copies the frame (YUV → ARGB, the
     * sample app's path), tells MediaPipe how to rotate it upright for the model, and returns
     * immediately. Never closes the proxy — that stays the face tracker's job.
     */
    fun submit(imageProxy: ImageProxy) {
        val detector = landmarker ?: return
        val timestampMs = imageProxy.imageInfo.timestamp / NANOS_PER_MILLISECOND
        // A repeated or backwards timestamp would make MediaPipe throw; skipping the frame is
        // invisible. The clock restarts with the landmarker on every rebind ([reset]).
        if (timestampMs <= lastSubmittedMs) return
        lastSubmittedMs = timestampMs
        // ponytail: a fresh 1.2 MB bitmap per frame; pool it if allocation shows up in a trace.
        val image = BitmapImageBuilder(imageProxy.toBitmap()).build()
        val options = ImageProcessingOptions.builder()
            .setRotationDegrees(imageProxy.imageInfo.rotationDegrees)
            .build()
        detector.detectAsync(image, options, timestampMs)
    }

    /**
     * Forgets the hand and restarts the landmarker's clock. Call on every rebind, like
     * [FaceTracker.reset]: a different sensor's timestamps may run behind the last one's, and a
     * hand from the previous bind must not linger. Main thread.
     */
    fun reset() {
        onHand(null)
        executor.execute {
            if (landmarker != null) {
                closeLandmarker()
                open()
            }
        }
    }

    /** Releases the landmarker. Main thread; the work lands on [executor] before it shuts down. */
    fun close() {
        wanted = false
        executor.execute { closeLandmarker() }
    }

    /** Executor thread. */
    private fun open() {
        if (landmarker != null || !wanted) return
        val startedIn = epoch
        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath(MODEL_ASSET)
                    // CPU: the GL thread is already the renderer's, and 640x480 is cheap. A
                    // one-line switch to Delegate.GPU if hardware QA measures otherwise (PRD R1).
                    .setDelegate(Delegate.CPU)
                    .build(),
            )
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(1)
            .setResultListener { result, image ->
                if (startedIn == epoch) publish(result, image.width, image.height)
            }
            .setErrorListener { error -> Log.w(TAG, "Hand landmarker failed", error) }
            .build()
        landmarker = try {
            HandLandmarker.createFromOptions(appContext, options)
        } catch (error: MediaPipeException) {
            // The documented creation failure (model missing, unsupported device). The lens still
            // works; only the hand verb is off — a novelty must never take the camera with it.
            Log.w(TAG, "Hand landmarker unavailable; hand flicks disabled", error)
            null
        }
        lastSubmittedMs = Long.MIN_VALUE
        Log.i(TAG, "Hand tracking ${if (landmarker != null) "on" else "unavailable"}")
    }

    /** Executor thread. */
    private fun closeLandmarker() {
        val detector = landmarker ?: return
        epoch++
        landmarker = null
        detector.close()
        onHand(null)
        Log.i(TAG, "Hand tracking off")
    }

    /**
     * Result thread. Builds the snapshot in the renderer's space.
     *
     * Coordinates: MediaPipe projects its landmarks back into the image **as submitted** — the
     * un-rotated camera buffer — and only uses the rotation option to orient the model's view. So
     * the points are already in the buffer space the sticker quads live in, with the buffer's own
     * aspect; nothing here rotates or mirrors (Lesson 032's "measure and draw in one frame").
     * Measured, not assumed: on the emulator's poster hand all 21 landmarks, drawn onto the
     * preview under this contract, land on the hand (`docs/PRD-lens-hand-flick.md` §1.2).
     */
    private fun publish(result: HandLandmarkerResult, width: Int, height: Int) {
        val hand = result.landmarks().firstOrNull()
        if (hand == null || hand.size < HandSnapshot.LANDMARK_COUNT || width <= 0 || height <= 0) {
            if (handVisible) {
                handVisible = false
                Log.i(TAG, "Hand lost")
            }
            onHand(null)
            return
        }
        val xs = FloatArray(HandSnapshot.LANDMARK_COUNT) { hand[it].x() }
        val ys = FloatArray(HandSnapshot.LANDMARK_COUNT) { hand[it].y() }
        val snapshot = HandSnapshot(
            xs = xs,
            ys = ys,
            sourceAspect = width.toFloat() / height.toFloat(),
            timestampMs = result.timestampMs(),
        )
        if (!handVisible) {
            handVisible = true
            // The geometry line for QA: pair it with OpenLoopLens's output line to check the
            // spaces agree, the way the face tracker's "Analysis buffer=" line is used.
            Log.i(
                TAG,
                "Hand detected in ${width}x$height wrist=(${xs[0]}, ${ys[0]}) " +
                    "indexTip=(${xs[INDEX_TIP]}, ${ys[INDEX_TIP]}) landmarks=" +
                    xs.indices.joinToString(";") { "%.3f,%.3f".format(xs[it], ys[it]) },
            )
        }
        onHand(snapshot)
    }

    private companion object {
        const val TAG = "OpenLoopHand"

        /** `src/main/assets/hand_landmarker.task` — MediaPipe's float16 bundle, Apache 2.0. */
        const val MODEL_ASSET = "hand_landmarker.task"

        const val NANOS_PER_MILLISECOND = 1_000_000L

        const val INDEX_TIP = 8
    }
}
