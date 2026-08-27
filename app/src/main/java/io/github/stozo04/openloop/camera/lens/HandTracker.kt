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
import io.github.stozo04.openloop.diagnostics.ReverseCrashlytics
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

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
 * lens, pays nothing for this (PRD D5). The landmarker itself is built by the first frame that
 * needs it ([submit]) and dropped on every rebind ([reset]) and on disable, so a gallery
 * round-trip or a lens flip costs one 7.8 MB model load, not two.
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
     * Bumped on every [reset] and every teardown so a result still in flight from the old
     * landmarker is dropped instead of resurrecting a hand from the previous bind — the same epoch
     * idea as [FaceTracker]. Bumped on the main thread ([reset]) and the executor thread
     * ([closeLandmarker]), read on the result thread, hence atomic.
     */
    private val epoch = AtomicInteger()

    @Volatile
    private var wanted = false

    /**
     * Set once [open] has failed. The documented failures (model missing, native lib absent for
     * this ABI, a static initializer that threw) are permanent for the process, so retrying on
     * every frame or lens toggle would only spam Crashlytics. Executor thread only.
     */
    private var broken = false

    /**
     * Timestamp of the frame MediaPipe is still working on, or [NO_FRAME]. MediaPipe ignores a
     * frame handed to it while it is busy (its documented LIVE_STREAM contract), so converting
     * that frame first is pure waste on the thread ML Kit shares. Written on the executor thread,
     * cleared on the result thread, hence volatile.
     */
    @Volatile
    private var inFlightMs = NO_FRAME

    /**
     * The appear/vanish edges, logged once each so QA can see the tracker work. Written on the
     * result thread; cleared on the executor thread at teardown (after the landmarker is closed,
     * so no result races it) — otherwise the first hand after a lens switch would log nothing.
     */
    @Volatile
    private var handVisible = false

    /**
     * Main thread. Turns hand tracking on or off. Creating the landmarker loads a 7.8 MB model,
     * so that happens on [executor] from the first frame that needs it, never here; the first
     * frames after enabling simply see no hand yet.
     */
    fun setEnabled(enabled: Boolean) {
        if (wanted == enabled) return
        wanted = enabled
        if (!enabled) executor.execute { closeLandmarker() }
    }

    /**
     * Analyzer thread, **before** ML Kit is handed the proxy. Copies the frame (YUV → ARGB, the
     * sample app's path), tells MediaPipe how to rotate it upright for the model, and returns
     * immediately. Never closes the proxy — that stays the face tracker's job.
     */
    fun submit(imageProxy: ImageProxy) {
        if (landmarker == null && wanted) {
            executor.execute { open() }
            return
        }
        val detector = landmarker ?: return
        val timestampMs = imageProxy.imageInfo.timestamp / NANOS_PER_MILLISECOND
        // A repeated or backwards timestamp would make MediaPipe throw; skipping the frame is
        // invisible. The clock restarts with the landmarker on every rebind ([reset]).
        if (timestampMs <= lastSubmittedMs) return
        // Nothing to gain from a frame MediaPipe would ignore: skip the conversion while a result
        // is outstanding. The stall bound is the escape hatch should a callback never come — hand
        // tracking may degrade to "a few frames late", never to "off for good".
        val busySince = inFlightMs
        if (busySince != NO_FRAME && timestampMs - busySince < STALL_MS) return
        lastSubmittedMs = timestampMs
        // A fresh 1.2 MB bitmap per frame is the floor: CameraX's convert-into-an-existing-bitmap
        // path is @RestrictTo, and the zero-copy route (RGBA_8888 analysis output straight into a
        // ByteBufferImageBuilder) needs the shared stream to stop being the YUV that ML Kit's
        // fromMediaImage wants. MediaPipe copies the pixels into its own packet inside detectAsync
        // (AndroidPacketCreator.createImage, synchronous — verified by bytecode and by detection
        // surviving the recycle) and never closes the MPImage, so `use` recycles it on return
        // instead of leaving one native bitmap per frame for the GC to find.
        val image = BitmapImageBuilder(imageProxy.toBitmap()).build()
        val options = ImageProcessingOptions.builder()
            .setRotationDegrees(imageProxy.imageInfo.rotationDegrees)
            .build()
        inFlightMs = timestampMs
        image.use { detector.detectAsync(it, options, timestampMs) }
    }

    /**
     * Forgets the hand and drops the landmarker; the next [submit] builds a fresh one on the new
     * bind's clock. Call on every rebind, like [FaceTracker.reset]: a different sensor's
     * timestamps may run behind the last one's, and a hand from the previous bind must not
     * linger. The epoch bump is synchronous, so a result of the old landmarker that lands between
     * this call and the executor's teardown is dropped instead of overwriting the null. Main
     * thread.
     */
    fun reset() {
        epoch.incrementAndGet()
        onHand(null)
        executor.execute { closeLandmarker() }
    }

    /** Releases the landmarker. Main thread; the work lands on [executor] before it shuts down. */
    fun close() {
        wanted = false
        executor.execute { closeLandmarker() }
    }

    /** Executor thread. */
    private fun open() {
        if (landmarker != null || !wanted || broken) return
        val startedIn = epoch.get()
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
                // MediaPipe echoes the input back as a brand-new bitmap per result (plus a
                // same-size direct ByteBuffer it drops on the floor — that one is its GC churn,
                // not ours). Only the dimensions are wanted; recycle the bitmap on the way out.
                inFlightMs = NO_FRAME
                image.use { if (startedIn == epoch.get()) publish(result, it.width, it.height) }
            }
            .setErrorListener { error ->
                inFlightMs = NO_FRAME
                Log.w(TAG, "Hand landmarker failed", error)
            }
            .build()
        landmarker = try {
            HandLandmarker.createFromOptions(appContext, options)
        } catch (error: MediaPipeException) {
            // The documented creation failure (model missing, unsupported device).
            unavailable(error)
        } catch (error: LinkageError) {
            // The JVM's own failure class for "the library could not come up": a native lib absent
            // for this ABI (UnsatisfiedLinkError) or a static initializer that threw
            // (ExceptionInInitializerError — MediaPipe's Flogger did exactly that under R8 before
            // proguard-rules.pro kept it, 2026-08-26). Not a catch-all: a bug in our own code still
            // propagates.
            unavailable(error)
        }
        lastSubmittedMs = Long.MIN_VALUE
        inFlightMs = NO_FRAME
        Log.i(TAG, "Hand tracking ${if (landmarker != null) "on" else "unavailable"}")
    }

    /** The lens still works; only the hand verb is off — a novelty must never take the camera with it. */
    private fun unavailable(error: Throwable): HandLandmarker? {
        Log.w(TAG, "Hand landmarker unavailable; hand flicks disabled", error)
        ReverseCrashlytics.reportHandTrackerUnavailable(error)
        broken = true
        return null
    }

    /** Executor thread. */
    private fun closeLandmarker() {
        val detector = landmarker ?: return
        epoch.incrementAndGet()
        landmarker = null
        detector.close()
        handVisible = false
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

        /** No frame outstanding at MediaPipe. */
        const val NO_FRAME = Long.MIN_VALUE

        /**
         * A result older than this is presumed lost and the gate reopens. CPU inference on a
         * 640x480 frame is tens of milliseconds; anything past this is a stall, not a slow frame.
         */
        const val STALL_MS = 500L
    }
}
