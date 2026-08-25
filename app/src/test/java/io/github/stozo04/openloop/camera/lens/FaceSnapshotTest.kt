package io.github.stozo04.openloop.camera.lens

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guard for the one non-geometric field on [FaceSnapshot]: its identity.
 *
 * [FaceSnapshot.trackingId] is what every per-face state hangs off — the roster slot in
 * [FaceRoster], the springs and eased mouth in [LensMotion]. A transform that dropped it would
 * silently merge two people into one, so it must ride through the same two transforms that carry
 * `mouthOpenness` untouched (see `LensAnchorTest.openness_isCarriedThroughRotationAndReframingUntouched`).
 */
class FaceSnapshotTest {

    private val subject = FaceSnapshot(
        leftEyeX = 0.42f,
        leftEyeY = 0.40f,
        rightEyeX = 0.58f,
        rightEyeY = 0.40f,
        mouthLeftX = 0.45f,
        mouthLeftY = 0.60f,
        mouthRightX = 0.55f,
        mouthRightY = 0.60f,
        sourceAspect = 4f / 3f,
        trackingId = 7,
    )

    @Test
    fun trackingId_defaultsToNobody() {
        assertEquals(FaceSnapshot.NO_TRACKING_ID, subject.copy(trackingId = FaceSnapshot.NO_TRACKING_ID).trackingId)
        assertEquals(-1, FaceSnapshot.NO_TRACKING_ID)
    }

    @Test
    fun trackingId_isCarriedThroughRotationUntouched() {
        assertEquals(7, LensAnchor.uprightToBuffer(subject, 90).trackingId)
        assertEquals(7, LensAnchor.uprightToBuffer(subject, 180).trackingId)
        assertEquals(7, LensAnchor.uprightToBuffer(subject, 270).trackingId)
    }

    @Test
    fun trackingId_isCarriedThroughReframingUntouched() {
        assertEquals(7, LensAnchor.reframe(subject, targetAspect = 16f / 9f).trackingId)
    }
}
