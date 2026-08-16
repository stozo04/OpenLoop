package io.github.stozo04.openloop.camera.lens

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the real ML Kit detector supplies the four landmarks the face frame is built from, and
 * that a sane frame comes out of them.
 *
 * WHY AN INSTRUMENTED TEST: the Android emulator cannot present a face to the camera on its own —
 * its virtual-scene image surfaces sit outside the default camera pose and reaching them needs
 * host-side keyboard navigation. Driving the detector with a bundled still is the only *automated*
 * way to cover the tracker. See docs/PRD-camera-lenses.md §10b.
 *
 * Fixture: a crop of Leonardo's Mona Lisa (public domain) — a real face, no living person's
 * likeness committed to the repo.
 */
@RunWith(AndroidJUnit4::class)
class FaceTrackerNormalizationTest {

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setMinFaceSize(0.15f)
            .build(),
    )

    private fun detectFixtureFace(): Pair<Face, Pair<Float, Float>> {
        val context = InstrumentationRegistry.getInstrumentation().context
        val bitmap = context.assets.open(FIXTURE).use(BitmapFactory::decodeStream)
        assertNotNull("fixture asset $FIXTURE failed to decode", bitmap)

        val faces = Tasks.await(
            detector.process(InputImage.fromBitmap(bitmap, 0)),
            DETECT_TIMEOUT_SECONDS,
            TimeUnit.SECONDS,
        )
        assertTrue("ML Kit found no face in the fixture", faces.isNotEmpty())
        val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }!!
        return face to (bitmap.width.toFloat() to bitmap.height.toFloat())
    }

    private fun Face.toSnapshot(width: Float, height: Float): FaceSnapshot {
        val leftEye = getLandmark(FaceLandmark.LEFT_EYE)!!.position
        val rightEye = getLandmark(FaceLandmark.RIGHT_EYE)!!.position
        val mouthLeft = getLandmark(FaceLandmark.MOUTH_LEFT)!!.position
        val mouthRight = getLandmark(FaceLandmark.MOUTH_RIGHT)!!.position
        return FaceSnapshot(
            leftEyeX = leftEye.x / width, leftEyeY = leftEye.y / height,
            rightEyeX = rightEye.x / width, rightEyeY = rightEye.y / height,
            mouthLeftX = mouthLeft.x / width, mouthLeftY = mouthLeft.y / height,
            mouthRightX = mouthRight.x / width, mouthRightY = mouthRight.y / height,
            sourceAspect = width / height,
        )
    }

    @Test
    fun detectorSuppliesEveryLandmarkTheFaceFrameNeeds() {
        val (face, _) = detectFixtureFace()

        // Missing any one of these makes FaceTracker return null and the lens disappear, so all
        // four are load-bearing rather than nice-to-have.
        listOf(
            FaceLandmark.LEFT_EYE to "LEFT_EYE",
            FaceLandmark.RIGHT_EYE to "RIGHT_EYE",
            FaceLandmark.MOUTH_LEFT to "MOUTH_LEFT",
            FaceLandmark.MOUTH_RIGHT to "MOUTH_RIGHT",
        ).forEach { (type, name) ->
            assertNotNull("$name missing — every lens would vanish", face.getLandmark(type))
        }
    }

    @Test
    fun landmarksAreAnatomicallyOrdered() {
        val (face, size) = detectFixtureFace()
        val (width, height) = size
        val snapshot = face.toSnapshot(width, height)

        val eyeY = (snapshot.leftEyeY + snapshot.rightEyeY) / 2f
        val mouthY = (snapshot.mouthLeftY + snapshot.mouthRightY) / 2f
        assertTrue("the mouth must sit below the eyes, got eyes=$eyeY mouth=$mouthY", mouthY > eyeY)

        listOf(
            "leftEyeX" to snapshot.leftEyeX, "leftEyeY" to snapshot.leftEyeY,
            "rightEyeX" to snapshot.rightEyeX, "rightEyeY" to snapshot.rightEyeY,
            "mouthLeftX" to snapshot.mouthLeftX, "mouthRightX" to snapshot.mouthRightX,
        ).forEach { (name, value) ->
            assertEquals("$name out of the 0..1 frame: $value", value, value.coerceIn(0f, 1f), 0f)
        }
    }

    @Test
    fun aRealDetectionProducesAUsableFaceFrame() {
        val (face, size) = detectFixtureFace()
        val (width, height) = size
        val snapshot = face.toSnapshot(width, height)
        val aspect = width / height

        val frame = LensAnchor.faceFrame(snapshot, aspect)
        assertNotNull("a detected face must yield a frame", frame)
        frame!!

        // On an upright portrait the face's "up" really is up the image.
        assertTrue("up should point toward the top of the image, got ${frame.upY}", frame.upY < 0f)
        // The fixture is a tight head crop, so the face unit is a meaningful slice of the frame —
        // this catches a normalization divided by the wrong dimension.
        assertTrue("face unit implausibly small: ${frame.unit}", frame.unit > 0.05f)

        // And every shipped lens lands somewhere on the head rather than off in a corner.
        Lens.entries.flatMap { it.art }.forEach { art ->
            val quad = LensAnchor.sticker(snapshot, frame, art.placement, aspect)
            assertTrue(
                "lens centre ${quad.centerX} is far outside the frame",
                quad.centerX > -1f && quad.centerX < 2f,
            )
            assertTrue("lens has no size", quad.halfWidth > 0f)
        }
    }

    private companion object {
        const val FIXTURE = "face_fixture.jpg"
        const val DETECT_TIMEOUT_SECONDS = 30L
    }
}
