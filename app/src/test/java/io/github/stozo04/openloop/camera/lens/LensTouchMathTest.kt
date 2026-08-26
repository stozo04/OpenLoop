package io.github.stozo04.openloop.camera.lens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Guard for [LensTouchMath] — the view-touch → effect-buffer mapping, the R1 of
 * `docs/PRD-lens-interactions.md`.
 *
 * Every case asserts **concrete asymmetric coordinates** (Lesson 032: a round-trip test alone
 * passes happily on an inverted transform), and the rotation cases are additionally pinned to
 * [LensAnchor.uprightToBuffer]: a touch must land exactly where a face measured at the same
 * upright spot landed, or flicks and lenses live in different worlds.
 */
class LensTouchMathTest {

    private val tolerance = 1e-5f

    private fun flick(
        downX: Float,
        downY: Float,
        velocityX: Float = 0f,
        velocityY: Float = 0f,
        viewWidth: Float,
        viewHeight: Float,
    ) = ViewFlick(downX, downY, velocityX, velocityY, viewWidth, viewHeight)

    // ---------------------------------------------------------------- identity and crop

    @Test
    fun anUnrotatedUncroppedView_mapsStraightThrough() {
        val mapped = LensTouchMath.viewToBuffer(
            flick(downX = 100f, downY = 40f, viewWidth = 400f, viewHeight = 400f),
            bufferWidth = 400f,
            bufferHeight = 400f,
            rotationDegrees = 0,
            mirrored = false,
        )!!

        assertEquals(0.25f, mapped.x, tolerance)
        assertEquals(0.10f, mapped.y, tolerance)
    }

    @Test
    fun fillCenter_addsTheHorizontalCropBack() {
        // Upright image 960 wide shown in a 640-wide view at scale 1: 160 px cropped per side, so
        // the view's left edge is 1/6 of the way into the image — not 0.
        val mapped = LensTouchMath.viewToBuffer(
            flick(downX = 0f, downY = 640f, viewWidth = 640f, viewHeight = 1280f),
            bufferWidth = 960f,
            bufferHeight = 1280f,
            rotationDegrees = 0,
            mirrored = false,
        )!!

        assertEquals(160f / 960f, mapped.x, tolerance)
        assertEquals(0.5f, mapped.y, tolerance)
    }

    @Test
    fun fillCenter_dividesTheScaleOutBeforeNormalizing() {
        // A 960x1280 upright image filling a 960x1600 view scales by 1.25 (the height governs),
        // displaying 1200x1600 with 120 px cropped per side. The view's origin is therefore
        // 120/1.25 = 96 image px into the row: 0.1 of the width.
        val mapped = LensTouchMath.viewToBuffer(
            flick(downX = 0f, downY = 0f, viewWidth = 960f, viewHeight = 1600f),
            bufferWidth = 960f,
            bufferHeight = 1280f,
            rotationDegrees = 0,
            mirrored = false,
        )!!

        assertEquals(0.1f, mapped.x, tolerance)
        assertEquals(0f, mapped.y, tolerance)
    }

    // ---------------------------------------------------------------- rotation

    @Test
    fun aQuarterTurnedBuffer_mapsTheTouchThroughTheSameTurnAsAFace() {
        // The consistency that makes flicks and lenses share one world: for every rotation, the
        // touch at an upright point must land exactly where uprightToBuffer put a landmark
        // measured at that same point.
        val uprightX = 0.25f
        val uprightY = 0.125f
        for (rotation in intArrayOf(0, 90, 180, 270)) {
            val quarterTurn = rotation == 90 || rotation == 270
            // Buffer is landscape 1280x960; the upright image is its quarter-turned shape.
            val uprightWidth = if (quarterTurn) 960f else 1280f
            val uprightHeight = if (quarterTurn) 1280f else 960f

            val face = FaceSnapshot(
                leftEyeX = uprightX, leftEyeY = uprightY,
                rightEyeX = 0.75f, rightEyeY = 0.125f,
                mouthLeftX = 0.4f, mouthLeftY = 0.6f,
                mouthRightX = 0.6f, mouthRightY = 0.6f,
                sourceAspect = uprightWidth / uprightHeight,
            )
            val inBuffer = LensAnchor.uprightToBuffer(face, rotation)

            val mapped = LensTouchMath.viewToBuffer(
                flick(
                    downX = uprightX * uprightWidth,
                    downY = uprightY * uprightHeight,
                    viewWidth = uprightWidth,
                    viewHeight = uprightHeight,
                ),
                bufferWidth = 1280f,
                bufferHeight = 960f,
                rotationDegrees = rotation,
                mirrored = false,
            )!!

            assertEquals("x at rotation $rotation", inBuffer.leftEyeX, mapped.x, tolerance)
            assertEquals("y at rotation $rotation", inBuffer.leftEyeY, mapped.y, tolerance)
        }
    }

    @Test
    fun rotation90_concreteAsymmetricPoint() {
        // Portrait phone, back camera: upright 960x1280 view over a 1280x960 buffer. The
        // upright point (0.25, 0.25) must land at buffer (0.25, 0.75) — y from x, not x from y.
        val mapped = LensTouchMath.viewToBuffer(
            flick(downX = 240f, downY = 320f, viewWidth = 960f, viewHeight = 1280f),
            bufferWidth = 1280f,
            bufferHeight = 960f,
            rotationDegrees = 90,
            mirrored = false,
        )!!

        assertEquals(0.25f, mapped.x, tolerance)
        assertEquals(0.75f, mapped.y, tolerance)
    }

    // ---------------------------------------------------------------- mirroring

    @Test
    fun aMirroredView_flipsTheTouchAcrossTheVerticalCenterLine() {
        val mapped = LensTouchMath.viewToBuffer(
            flick(downX = 100f, downY = 40f, viewWidth = 400f, viewHeight = 400f),
            bufferWidth = 400f,
            bufferHeight = 400f,
            rotationDegrees = 0,
            mirrored = true,
        )!!

        assertEquals(0.75f, mapped.x, tolerance)
        assertEquals(0.10f, mapped.y, tolerance)
    }

    @Test
    fun aMirroredView_flipsTheSpinDirectionWithIt() {
        // A rightward fling on a mirrored preview is a leftward fling on the buffer — miss this
        // and every front-camera spin goes the wrong way.
        val mapped = LensTouchMath.viewToBuffer(
            flick(downX = 200f, downY = 200f, velocityX = 400f, viewWidth = 400f, viewHeight = 400f),
            bufferWidth = 400f,
            bufferHeight = 400f,
            rotationDegrees = 0,
            mirrored = true,
        )!!

        assertEquals(-1f, mapped.velocityX, tolerance)
        assertEquals(0f, mapped.velocityY, tolerance)
    }

    // ---------------------------------------------------------------- velocity

    @Test
    fun velocityMapsThroughTheSameTransformAsPosition() {
        // Rotation 90: a downward fling in the view is a +x fling in the buffer, and a rightward
        // fling is -y. Velocities normalize against the same axes as positions.
        val mapped = LensTouchMath.viewToBuffer(
            flick(
                downX = 480f, downY = 640f,
                velocityX = 960f, velocityY = 1280f,
                viewWidth = 960f, viewHeight = 1280f,
            ),
            bufferWidth = 1280f,
            bufferHeight = 960f,
            rotationDegrees = 90,
            mirrored = false,
        )!!

        // View velocity (960, 1280) px/s is (1, 1) in upright-normalized/s; rotation 90 maps
        // (vx, vy) → (vy, -vx).
        assertEquals(1f, mapped.velocityX, tolerance)
        assertEquals(-1f, mapped.velocityY, tolerance)
    }

    // ---------------------------------------------------------------- garbage

    @Test
    fun degenerateSizes_dropTheFlick() {
        assertNull(
            LensTouchMath.viewToBuffer(
                flick(1f, 1f, viewWidth = 0f, viewHeight = 400f), 400f, 400f, 0, false,
            ),
        )
        assertNull(
            LensTouchMath.viewToBuffer(
                flick(1f, 1f, viewWidth = 400f, viewHeight = 400f), 0f, 400f, 0, false,
            ),
        )
    }

    @Test
    fun nonFiniteInput_dropsTheFlick() {
        assertNull(
            LensTouchMath.viewToBuffer(
                flick(Float.NaN, 1f, viewWidth = 400f, viewHeight = 400f), 400f, 400f, 0, false,
            ),
        )
        assertNull(
            LensTouchMath.viewToBuffer(
                flick(1f, 1f, velocityX = Float.POSITIVE_INFINITY, viewWidth = 400f, viewHeight = 400f),
                400f, 400f, 0, false,
            ),
        )
    }

    @Test
    fun rotationNormalizes_soNegativeDegreesStillMap() {
        val positive = LensTouchMath.viewToBuffer(
            flick(240f, 320f, viewWidth = 960f, viewHeight = 1280f), 1280f, 960f, 90, false,
        )!!
        val negative = LensTouchMath.viewToBuffer(
            flick(240f, 320f, viewWidth = 960f, viewHeight = 1280f), 1280f, 960f, -270, false,
        )!!

        assertEquals(positive.x, negative.x, tolerance)
        assertEquals(positive.y, negative.y, tolerance)
    }
}
