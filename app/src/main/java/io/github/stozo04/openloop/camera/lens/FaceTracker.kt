package io.github.stozo04.openloop.camera.lens

import android.annotation.SuppressLint
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import io.github.stozo04.openloop.diagnostics.ReverseCrashlytics
import kotlin.math.hypot

/**
 * Turns the [ImageAnalysis] stream into [FaceSnapshot]s for the lens renderer.
 *
 * Uses ML Kit's **stable** face detector, not the beta face-mesh API: landmark mode already gives
 * the eyes and mouth corners the face frame is built from, at a fraction of the cost of 468 mesh
 * points, and it carries an SLA. See `docs/PRD-camera-lenses.md` §5.1 for the comparison.
 *
 * Up to [MAX_TRACKED_FACES] faces are followed at once, so two people in a selfie both get the
 * lens (`docs/PRD-multi-face-lenses.md`). Which faces hold a slot, and how a face rides out a
 * dropped frame, is [FaceRoster]'s decision — this class is the ML Kit glue in front of it.
 * [onFaces] receives the roster in slot order, empty when nobody is in frame.
 */
class FaceTracker(private val onFaces: (List<FaceSnapshot>) -> Unit) : ImageAnalysis.Analyzer {

    private var loggedGeometry = false

    /**
     * Slot assignment and the per-face hold. Touched only on the detector's callback thread — the
     * main thread, since ML Kit's `Task` listeners below are registered without an executor — which
     * is also the thread [reset] is called on.
     */
    private val roster = FaceRoster(maxFaces = MAX_TRACKED_FACES, holdMs = HOLD_MS)

    /** This frame's detections, reused across frames. */
    private val sightings = ArrayList<FaceRoster.Sighting>(MAX_TRACKED_FACES * 2)

    /**
     * Bumped by [reset]. A detection that was in flight when the camera was unbound completes on
     * the callback thread afterward; comparing its epoch drops it instead of letting it re-seed
     * the roster with faces from the previous bind. Written on the main thread, read on the
     * analyzer's executor, hence volatile.
     */
    @Volatile
    private var epoch = 0

    /**
     * ML Kit's bundled detector, or `null` when its native library will not load on this device —
     * see [createDetector]. Null means the lenses are inert and nothing else changes.
     */
    private val detector: FaceDetector? = createDetector()

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        // No detector on this device (see [createDetector]). Close the proxy anyway and publish
        // nothing: under KEEP_ONLY_LATEST an unclosed proxy stalls the stream the hand tracker
        // rides on too, so "lenses are inert" would become "the analyzer is dead".
        val detector = detector
        val mediaImage = imageProxy.image
        if (detector == null || mediaImage == null) {
            imageProxy.close()
            return
        }

        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        // ML Kit reports coordinates in the *rotated* image, so normalize against the rotated
        // dimensions — not the buffer's. Getting this backwards is a 90°-off lens on every phone
        // held upright.
        val quarterTurned = rotationDegrees == 90 || rotationDegrees == 270
        val uprightWidth = (if (quarterTurned) imageProxy.height else imageProxy.width).toFloat()
        val uprightHeight = (if (quarterTurned) imageProxy.width else imageProxy.height).toFloat()
        logGeometryOnce(imageProxy.width, imageProxy.height, rotationDegrees, uprightWidth, uprightHeight)

        val startedIn = epoch
        detector.process(InputImage.fromMediaImage(mediaImage, rotationDegrees))
            .addOnSuccessListener { faces ->
                if (startedIn != epoch) return@addOnSuccessListener
                sightings.clear()
                for (face in faces) {
                    // Without a tracking id there is nothing to key a slot or a spring on. ML Kit
                    // always assigns one with tracking enabled; this is belt and braces.
                    val id = face.trackingId ?: continue
                    val snapshot = face.toSnapshot(uprightWidth, uprightHeight, id)
                        // ML Kit answers in the upright image; the renderer draws in the camera
                        // buffer's own orientation. Undo the rotation here so exactly one place in
                        // the app knows about the quarter turn.
                        ?.let { LensAnchor.uprightToBuffer(it, rotationDegrees) }
                        ?: continue
                    val area = face.boundingBox.width().toFloat() * face.boundingBox.height()
                    sightings.add(FaceRoster.Sighting(snapshot, area))
                }
                publish()
            }
            .addOnFailureListener { error ->
                Log.w(TAG, "Face detection failed", error)
                if (startedIn != epoch) return@addOnFailureListener
                sightings.clear()
                publish()
            }
            .addOnCompleteListener { imageProxy.close() }
    }

    /** Folds [sightings] into the roster and hands the slot holders to the renderer. */
    private fun publish() {
        onFaces(roster.update(sightings, SystemClock.elapsedRealtime()))
    }

    /**
     * Forgets every tracked face and publishes an empty roster. Call whenever the camera is
     * unbound — a lens flip, leaving the camera screen. The hold would otherwise carry the last
     * faces, with their geometry from the *previous* sensor, into the next bind's first frames;
     * and a detection still in flight from the old bind is discarded rather than resurrecting
     * them. Main thread, like the callbacks that feed [roster].
     */
    fun reset() {
        epoch++
        roster.clear()
        sightings.clear()
        onFaces(emptyList())
    }

    /** Releases the detector. Call when the analyzer is unbound. No-op when it never came up. */
    fun close() {
        detector?.close()
    }

    /**
     * Builds the ML Kit detector, or returns `null` when the bundled model's native library will
     * not load on this device.
     *
     * That the reported crash was **fatal** is the tell. ML Kit loads
     * `libface_detector_v2_jni.so` from the static initializer of its own
     * `ThickFaceDetectorCreator`, and it runs that off the caller's thread — on its own model-load
     * worker, inside a GMS `Task`. `Task` funnels only `Exception` into `addOnFailureListener`, so
     * the `UnsatisfiedLinkError` escapes the worker's `Runnable` and kills the process instead of
     * surfacing as a failed task. A `try` around [FaceDetection.getClient] would have caught
     * nothing (Issue #195, Crashlytics `37081b24…`, first seen 1.0.52).
     *
     * So the library is loaded **here** first, on the caller's own thread, where the failure is
     * catchable. A load that succeeds makes ML Kit's own `System.loadLibrary` a no-op; one that
     * fails means ML Kit would have crashed, so the detector is never built. The lenses then go
     * inert and the camera, capture, trim and save paths are untouched — the same trade
     * [io.github.stozo04.openloop.camera.CameraManager] already makes when the analysis use case
     * cannot bind, and the one [HandTracker] makes for MediaPipe (Lesson 040).
     */
    private fun createDetector(): FaceDetector? = try {
        System.loadLibrary(NATIVE_LIBRARY)
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                // FAST over ACCURATE: this runs per preview frame, and a lens that lags is worse
                // than a lens that is a pixel off.
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                // Landmarks (not contours) — the eyes, MOUTH_LEFT/RIGHT and MOUTH_BOTTOM are the
                // whole input to LensAnchor, and contour mode is several times the work per frame.
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
                .setMinFaceSize(MIN_FACE_SIZE)
                // Tracking ids are what let a slot follow a person across frames, and what keys
                // every per-face state downstream (FaceSnapshot.trackingId).
                .enableTracking()
                .build(),
        )
    } catch (error: LinkageError) {
        // The JVM's own class for "the library could not come up": UnsatisfiedLinkError when the
        // .so is missing for this ABI or the install lost its native split, and
        // ExceptionInInitializerError / NoClassDefFoundError when a static initializer threw.
        // Not a catch-all — a bug in our own code still propagates (Lesson 013).
        Log.w(TAG, "ML Kit face detection unavailable; face lenses will be inert", error)
        ReverseCrashlytics.reportFaceTrackerUnavailable(error)
        null
    }

    /**
     * Logs the analysis stream's geometry once. Pair it with `OpenLoopLens`'s per-output line to
     * confirm the tracker and the renderer agree on which way is up: the upright analysis size
     * should match the lens output's orientation, or every lens is a quarter-turn off.
     */
    private fun logGeometryOnce(
        bufferWidth: Int,
        bufferHeight: Int,
        rotationDegrees: Int,
        uprightWidth: Float,
        uprightHeight: Float,
    ) {
        if (loggedGeometry) return
        loggedGeometry = true
        Log.i(
            TAG,
            "Analysis buffer=${bufferWidth}x$bufferHeight rotation=$rotationDegrees " +
                "upright=${uprightWidth.toInt()}x${uprightHeight.toInt()}",
        )
    }

    /**
     * Converts a detection into the four landmarks the face frame is built from.
     *
     * Deliberately **no bounding box, no Euler angle**. Both were in an earlier version and both
     * were wrong to use: a box is axis-aligned to the frame rather than to the head, and an Euler
     * angle needs sign and mirror conventions that landmarks make unnecessary. Four points carry
     * position, orientation and scale at once — see [LensAnchor].
     *
     * Returns `null` if any landmark is missing (steep angles, profile views), so the renderer
     * shows a clean pass-through for that face instead of a lens guessing at where it might be.
     */
    private fun Face.toSnapshot(frameWidth: Float, frameHeight: Float, trackingId: Int): FaceSnapshot? {
        if (frameWidth <= 0f || frameHeight <= 0f) return null
        val leftEye = getLandmark(FaceLandmark.LEFT_EYE)?.position ?: return null
        val rightEye = getLandmark(FaceLandmark.RIGHT_EYE)?.position ?: return null
        val mouthLeft = getLandmark(FaceLandmark.MOUTH_LEFT)?.position ?: return null
        val mouthRight = getLandmark(FaceLandmark.MOUTH_RIGHT)?.position ?: return null

        // Mouth openness, measured in the tracker's own PIXELS. A ratio of two distances on the
        // same face is invariant to distance, rotation, mirroring and stream shape, so it can be
        // carried downstream as a bare scalar where a normalized one could not (Lesson 032).
        //
        // MOUTH_BOTTOM comes free with LANDMARK_MODE_ALL, which is already on. Contour mode would
        // give a better lip line and was rejected in PRD §5.1 on per-frame cost; this keeps that
        // decision intact instead of quietly reopening it. Missing on steep angles, in which case
        // the mouth simply reads as shut rather than the whole face being dropped.
        val mouthBottom = getLandmark(FaceLandmark.MOUTH_BOTTOM)?.position
        val mouthMidX = (mouthLeft.x + mouthRight.x) / 2f
        val mouthMidY = (mouthLeft.y + mouthRight.y) / 2f
        val eyeMidX = (leftEye.x + rightEye.x) / 2f
        val eyeMidY = (leftEye.y + rightEye.y) / 2f
        val openness = if (mouthBottom == null) {
            0f
        } else {
            LensAnchor.mouthOpenness(
                eyeToMouth = hypot(eyeMidX - mouthMidX, eyeMidY - mouthMidY),
                mouthToBottom = hypot(mouthBottom.x - mouthMidX, mouthBottom.y - mouthMidY),
            )
        }

        return FaceSnapshot(
            leftEyeX = leftEye.x / frameWidth,
            leftEyeY = leftEye.y / frameHeight,
            rightEyeX = rightEye.x / frameWidth,
            rightEyeY = rightEye.y / frameHeight,
            mouthLeftX = mouthLeft.x / frameWidth,
            mouthLeftY = mouthLeft.y / frameHeight,
            mouthRightX = mouthRight.x / frameWidth,
            mouthRightY = mouthRight.y / frameHeight,
            sourceAspect = frameWidth / frameHeight,
            mouthOpenness = openness,
            trackingId = trackingId,
        )
    }

    companion object {
        private const val TAG = "OpenLoopFaceTracker"

        /**
         * The bundled model's native library, loaded by [createDetector] before ML Kit can load it
         * somewhere uncatchable. **Coupled to the `mlkit-face-detection` pin in
         * `gradle/libs.versions.toml`** (16.1.7): re-check the name in the AAR's `jni/` folder when
         * that pin moves, because a rename would read here as "absent" and take face lenses off
         * every device. The lens loop of `scripts/run-verification-loops.py` is where that shows up.
         */
        private const val NATIVE_LIBRARY = "face_detector_v2_jni"

        /**
         * How many people can wear the lens at once — `docs/PRD-multi-face-lenses.md` D1. Two is a
         * selfie with a kid or a friend; three is a group photo. Everything downstream is keyed by
         * face, so raising this is a one-line change, but each extra face is one more landmark
         * pass per frame and a full set of sticker/feature draws per output.
         */
        const val MAX_TRACKED_FACES = 2

        /** Ignore faces smaller than this fraction of the frame — background bystanders. */
        private const val MIN_FACE_SIZE = 0.15f

        /**
         * How long to keep showing the last snapshot of a face after its detection drops out. Long
         * enough to ride through a blink or a blurred frame, short enough that walking out of shot
         * clears the lens without a visible lag. See [FaceRoster] for how the hold plays out.
         */
        private const val HOLD_MS = 350L
    }
}
