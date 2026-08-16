package io.github.stozo04.openloop.camera.lens

import android.annotation.SuppressLint
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlin.math.hypot

/**
 * Turns the [ImageAnalysis] stream into [FaceSnapshot]s for the lens renderer.
 *
 * Uses ML Kit's **stable** face detector, not the beta face-mesh API: landmark mode already gives
 * the mouth corners a warp lens needs, at a fraction of the cost of 468 mesh points, and it carries
 * an SLA. See `docs/PRD-camera-lenses.md` §5.1 for the comparison.
 *
 * Only the most prominent face is tracked. Lenses are a selfie feature; picking the largest face
 * keeps a bystander in the background from stealing the broccoli.
 */
class FaceTracker(private val onFace: (FaceSnapshot?) -> Unit) : ImageAnalysis.Analyzer {

    private var loggedGeometry = false

    /** The face we are following, so the lens does not hop between people. */
    private var lockedTrackingId: Int? = null

    /** When a face was last seen, for the drop-out hold in [publish]. */
    private var lastFaceAtMs = 0L

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            // FAST over ACCURATE: this runs per preview frame, and a lens that lags is worse than
            // a lens that is a pixel off.
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            // Landmarks (not contours) — MOUTH_LEFT/RIGHT is all the Big Mouth warp needs, and
            // contour mode is several times the work per frame.
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setMinFaceSize(MIN_FACE_SIZE)
            .enableTracking()
            .build(),
    )

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
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

        detector.process(InputImage.fromMediaImage(mediaImage, rotationDegrees))
            .addOnSuccessListener { faces ->
                val snapshot = pickTrackedFace(faces)
                    ?.toSnapshot(uprightWidth, uprightHeight)
                    // ML Kit answers in the upright image; the renderer draws in the camera
                    // buffer's own orientation. Undo the rotation here so exactly one place in
                    // the app knows about the quarter turn.
                    ?.let { LensAnchor.uprightToBuffer(it, rotationDegrees) }
                publish(snapshot)
            }
            .addOnFailureListener { error ->
                Log.w(TAG, "Face detection failed", error)
                publish(null)
            }
            .addOnCompleteListener { imageProxy.close() }
    }

    /**
     * Keeps the lens on **one** subject instead of re-choosing every frame.
     *
     * ML Kit's tracking id follows a face across frames, so once a face is locked it stays locked
     * even if someone larger walks past. The lock is only re-taken when the tracked id genuinely
     * disappears, at which point the most prominent face wins.
     */
    private fun pickTrackedFace(faces: List<Face>): Face? {
        if (faces.isEmpty()) return null
        val stillHere = lockedTrackingId?.let { locked -> faces.firstOrNull { it.trackingId == locked } }
        val chosen = stillHere ?: faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
        lockedTrackingId = chosen?.trackingId
        return chosen
    }

    /**
     * Publishes a snapshot, holding the previous one briefly when detection drops out.
     *
     * A detector at `PERFORMANCE_MODE_FAST` misses the odd frame — on a blink, a fast turn, or a
     * motion-blurred frame. Publishing `null` for those makes the lens blink off and back on, which
     * reads as broken. Holding the last good face for [HOLD_MS] rides through the gap without
     * adding any latency while the face IS being found, because a fresh detection always wins.
     */
    private fun publish(snapshot: FaceSnapshot?) {
        val now = SystemClock.elapsedRealtime()
        if (snapshot != null) {
            lastFaceAtMs = now
            onFace(snapshot)
            return
        }
        if (now - lastFaceAtMs < HOLD_MS) return // keep showing the last face
        lockedTrackingId = null
        onFace(null)
    }

    /** Releases the detector. Call when the analyzer is unbound. */
    fun close() {
        detector.close()
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
     * shows a clean pass-through instead of a lens guessing at where a face might be.
     */
    private fun Face.toSnapshot(frameWidth: Float, frameHeight: Float): FaceSnapshot? {
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
        )
    }

    private companion object {
        const val TAG = "OpenLoopFaceTracker"

        /** Ignore faces smaller than this fraction of the frame — background bystanders. */
        const val MIN_FACE_SIZE = 0.15f

        /**
         * How long to keep showing the last face after detection drops out. Long enough to ride
         * through a blink or a blurred frame, short enough that walking out of shot clears the
         * lens without a visible lag.
         */
        const val HOLD_MS = 350L
    }
}
