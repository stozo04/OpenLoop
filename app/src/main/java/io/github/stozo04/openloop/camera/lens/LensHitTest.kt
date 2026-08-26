package io.github.stozo04.openloop.camera.lens

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Did a touch land on a sticker? Point-in-rotated-quad, in the renderer's own terms
 * (`docs/PRD-lens-interactions.md` §3.3).
 *
 * The geometry runs in **square space** (Lesson 032): a rotated rectangle is only a rectangle
 * there, so the point converts in, the comparison happens against the quad's square-space half
 * extents, and no aspect ratio can shear the answer. The quad itself needs no tolerance
 * inflation — a spin-capable layer is required to be a generous target already (Football's quad
 * is 5.6 face units wide, hundreds of dp on screen; the Lesson 035 dp arithmetic must be re-run
 * before any small layer trusts its raw quad as a touch target). Pure, no Android types,
 * JVM-tested (`LensHitTestTest`).
 */
object LensHitTest {

    /**
     * Whether normalized point ([x], [y]) falls inside [quad] on a frame of [frameAspect].
     *
     * Mirrors the corner math of `LensSurfaceProcessor.writeStickerCorners` exactly, inverted:
     * the corners rotate by `+rotationRadians` in square space, so the point rotates by the
     * negative into the quad's local axes and compares against the unrotated half extents.
     */
    fun contains(quad: StickerQuad, x: Float, y: Float, frameAspect: Float): Boolean {
        if (!x.isFinite() || !y.isFinite()) return false
        if (quad.halfWidth <= 0f || quad.halfHeight <= 0f) return false

        val dx = x - quad.centerX
        val dy = LensAnchor.toSquareY(y - quad.centerY, frameAspect)
        val cos = cos(quad.rotationRadians)
        val sin = sin(quad.rotationRadians)
        val localX = dx * cos + dy * sin
        val localY = -dx * sin + dy * cos

        // halfWidth is already square-space; halfHeight is normalized-y (see LensAnchor.sticker).
        return abs(localX) <= quad.halfWidth &&
            abs(localY) <= LensAnchor.toSquareY(quad.halfHeight, frameAspect)
    }
}
