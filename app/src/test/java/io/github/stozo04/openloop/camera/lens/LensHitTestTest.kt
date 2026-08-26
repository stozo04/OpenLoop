package io.github.stozo04.openloop.camera.lens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guard for [LensHitTest] — did the flick land on the sticker?
 *
 * The rotated cases are the load-bearing ones: the quad's corners rotate in square space
 * ([LensSurfaceProcessor.writeStickerCorners]), so the hit test must invert in square space too,
 * and the aspect-flip case below fails on any implementation that compares in raw normalized
 * coordinates.
 */
class LensHitTestTest {

    private val aspect = 4f / 3f

    private fun quad(
        rotationRadians: Float = 0f,
        halfWidth: Float = 0.2f,
        halfHeight: Float = 0.1f,
    ) = StickerQuad(
        centerX = 0.5f,
        centerY = 0.5f,
        halfWidth = halfWidth,
        halfHeight = halfHeight,
        rotationRadians = rotationRadians,
    )

    // ---------------------------------------------------------------- axis-aligned

    @Test
    fun theCenterIsInside_andTheFarOutsideIsNot() {
        assertTrue(LensHitTest.contains(quad(), 0.5f, 0.5f, aspect))
        assertFalse(LensHitTest.contains(quad(), 0.95f, 0.5f, aspect))
        assertFalse(LensHitTest.contains(quad(), 0.5f, 0.95f, aspect))
    }

    @Test
    fun theHorizontalEdgeIsWhereHalfWidthSays() {
        // halfWidth is square-space, and x is the square axis, so the edge sits at 0.5 ± 0.2.
        assertTrue(LensHitTest.contains(quad(), 0.5f + 0.19f, 0.5f, aspect))
        assertFalse(LensHitTest.contains(quad(), 0.5f + 0.21f, 0.5f, aspect))
    }

    @Test
    fun theVerticalEdgeIsWhereHalfHeightSays() {
        // halfHeight is normalized-y; for an unrotated quad the aspect cancels between the point's
        // conversion and the extent's, so the edge sits at 0.5 ± 0.1 on any frame.
        assertTrue(LensHitTest.contains(quad(), 0.5f, 0.5f + 0.09f, aspect))
        assertFalse(LensHitTest.contains(quad(), 0.5f, 0.5f + 0.11f, aspect))
        assertTrue(LensHitTest.contains(quad(), 0.5f, 0.5f + 0.09f, frameAspect = 1f))
        assertFalse(LensHitTest.contains(quad(), 0.5f, 0.5f + 0.11f, frameAspect = 1f))
    }

    // ---------------------------------------------------------------- rotation

    @Test
    fun aQuarterTurnedQuad_carriesItsLongAxisWithIt() {
        val turned = quad(rotationRadians = (Math.PI / 2).toFloat(), halfWidth = 0.3f, halfHeight = 0.05f)

        // Above the center at a square distance well past the short extent but inside the long
        // one: inside only because the quad turned. (Aspect 1 keeps the numbers readable.)
        assertTrue(LensHitTest.contains(turned, 0.5f, 0.5f - 0.25f, frameAspect = 1f))
        // Beside the center where the unrotated quad would have reached: now outside.
        assertFalse(LensHitTest.contains(turned, 0.5f + 0.25f, 0.5f, frameAspect = 1f))
    }

    @Test
    fun aRotatedQuadHitDependsOnTheFrameAspect_theSquareSpaceProof() {
        // Quarter-turned quad: a point offset horizontally lands on the quad's SHORT axis, whose
        // extent is toSquareY(halfHeight, aspect) — 0.075 at 4:3 but 0.1 at square. The same
        // point is out on one frame and in on the other; raw normalized comparison gets one wrong.
        val turned = quad(rotationRadians = (Math.PI / 2).toFloat(), halfWidth = 0.3f, halfHeight = 0.1f)

        assertFalse(LensHitTest.contains(turned, 0.5f + 0.08f, 0.5f, aspect))
        assertTrue(LensHitTest.contains(turned, 0.5f + 0.08f, 0.5f, frameAspect = 1f))
    }

    // ---------------------------------------------------------------- degenerates

    @Test
    fun aCollapsedOrGarbledQuadNeverHits() {
        assertFalse(LensHitTest.contains(quad(halfWidth = 0f), 0.5f, 0.5f, aspect))
        assertFalse(LensHitTest.contains(quad(halfHeight = 0f), 0.5f, 0.5f, aspect))
        assertFalse(LensHitTest.contains(quad(), Float.NaN, 0.5f, aspect))
        assertFalse(LensHitTest.contains(quad(), 0.5f, Float.POSITIVE_INFINITY, aspect))
    }
}
