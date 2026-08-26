package io.github.stozo04.openloop.camera.lens

import kotlin.math.max

/**
 * Pure mapping from a touch on the viewfinder to the lens renderer's own coordinate space —
 * the R1 of `docs/PRD-lens-interactions.md` (§3.2), isolated the same way `LensAnchor` isolated
 * the original R1.
 *
 * ## The three transforms
 *
 * A flick lands in **view** pixels on the `PreviewView`; the sticker quads live in the effect
 * output buffer's **normalized** space. `PreviewView` sits between them, applying exactly three
 * things when it displays the processed stream:
 *
 * 1. **Rotation** — the buffer is the camera's own orientation (landscape on a portrait phone);
 *    the view shows it upright. The inverse here is the same quarter-turn family as
 *    [LensAnchor.uprightToBuffer], and the two must stay in step: a touch must land exactly where
 *    a face measured in the upright image landed (`LensTouchMathTest` asserts that consistency).
 * 2. **`FILL_CENTER`** — the upright image is uniformly scaled to fill the view and the overflow
 *    is cropped symmetrically. Inverted by adding the crop back and dividing the scale out.
 * 3. **Mirroring** — the front camera's preview is mirrored by the view layer *after* the effect
 *    (measured in Lesson 032), so a front-camera touch un-mirrors here, in upright display space,
 *    before the rotation. Velocity flips with it: a mirrored view must flip the spin direction too.
 *
 * Velocity maps through the same linear parts as position (scale, mirror, rotation), so direction
 * and magnitude stay honest in buffer space; conversion to face units happens at the consumer
 * against the face's own frame.
 *
 * No Android types, JVM-tested with the asymmetric assertions Lesson 032 demands — a round-trip
 * test alone passes happily on an inverted transform.
 */

/** A fling on the viewfinder, in view pixels (velocities in px/s). */
data class ViewFlick(
    val downX: Float,
    val downY: Float,
    val velocityX: Float,
    val velocityY: Float,
    val viewWidth: Float,
    val viewHeight: Float,
)

/** The same fling in the effect output buffer's normalized space (velocities per second). */
data class BufferFlick(
    val x: Float,
    val y: Float,
    val velocityX: Float,
    val velocityY: Float,
)

object LensTouchMath {

    /**
     * Maps [flick] into the buffer of [bufferWidth]x[bufferHeight] pixels, displayed through a
     * `FILL_CENTER` view rotated by [rotationDegrees] and mirrored when [mirrored].
     *
     * Returns `null` for degenerate sizes or non-finite input — a flick worth dropping is better
     * than a NaN reaching the physics (the [LensPhysics.spinImpulse] guard is the second net).
     */
    fun viewToBuffer(
        flick: ViewFlick,
        bufferWidth: Float,
        bufferHeight: Float,
        rotationDegrees: Int,
        mirrored: Boolean,
    ): BufferFlick? {
        if (flick.viewWidth <= 0f || flick.viewHeight <= 0f ||
            bufferWidth <= 0f || bufferHeight <= 0f
        ) {
            return null
        }
        if (!flick.downX.isFinite() || !flick.downY.isFinite() ||
            !flick.velocityX.isFinite() || !flick.velocityY.isFinite()
        ) {
            return null
        }

        val rotation = ((rotationDegrees % 360) + 360) % 360
        val quarterTurn = rotation == 90 || rotation == 270
        val uprightWidth = if (quarterTurn) bufferHeight else bufferWidth
        val uprightHeight = if (quarterTurn) bufferWidth else bufferHeight

        // FILL_CENTER: uniform scale to cover the view, symmetric crop of the overflow.
        val scale = max(flick.viewWidth / uprightWidth, flick.viewHeight / uprightHeight)
        val cropX = (uprightWidth * scale - flick.viewWidth) / 2f
        val cropY = (uprightHeight * scale - flick.viewHeight) / 2f
        var x = ((flick.downX + cropX) / scale) / uprightWidth
        var y = ((flick.downY + cropY) / scale) / uprightHeight
        var velocityX = (flick.velocityX / scale) / uprightWidth
        var velocityY = (flick.velocityY / scale) / uprightHeight

        // Un-mirror in upright display space — where PreviewView applies it — before the rotation.
        if (mirrored) {
            x = 1f - x
            velocityX = -velocityX
        }

        // Inverse of the display rotation; the point formulas mirror LensAnchor.uprightToBuffer,
        // the velocity formulas are their linear parts.
        return when (rotation) {
            90 -> BufferFlick(y, 1f - x, velocityY, -velocityX)
            180 -> BufferFlick(1f - x, 1f - y, -velocityX, -velocityY)
            270 -> BufferFlick(1f - y, x, -velocityY, velocityX)
            else -> BufferFlick(x, y, velocityX, velocityY)
        }
    }
}
