package io.github.stozo04.openloop.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.core.graphics.createBitmap

/**
 * The thin Canvas pass over [boothStripLayout] (docs/PRD-photo-booth.md §5.2). All geometry
 * decisions live in the pure, JVM-tested `BoothStripLayout.kt`; this file only draws them, so it
 * carries no logic worth a device test of its own.
 */

/** Footer text height as a fraction of the footer band — leaves print-like margins around it. */
private const val FOOTER_TEXT_SCALE = 0.34f

/** Letter tracking for the footer wordmark, so it reads as a printed strip, not a caption (D6). */
private const val FOOTER_LETTER_SPACING = 0.18f

/**
 * Crops a viewfinder grab to the booth square at grab time (retaining ~1080² ARGB per shot instead
 * of the full-screen grab — PRD §5.1). `Bitmap.createBitmap(source, …)` is documented to sometimes
 * return the source object itself (when the crop is the whole bitmap), so the source is recycled
 * only when a copy was actually made.
 */
internal fun cropToBoothSquare(source: Bitmap): Bitmap {
    val crop = boothCropRect(source.width, source.height)
    val square = Bitmap.createBitmap(source, crop.left, crop.top, crop.width, crop.height)
    if (square !== source) source.recycle()
    return square
}

/**
 * Composites the cropped [frames] into one strip: white ground, three frame draws, and the
 * "OPENLOOP · date" footer. [monochrome] applies a zero-saturation color filter to the **frame
 * draws only** — the borders and footer stay crisp white/black either way (D4).
 */
internal fun renderBoothStrip(frames: List<Bitmap>, monochrome: Boolean, footerText: String): Bitmap {
    require(frames.isNotEmpty()) { "Booth strip needs at least one frame" }
    val frameSize = frames.minOf { minOf(it.width, it.height) }
    val layout = boothStripLayout(frameSize, frames.size)
    val strip = createBitmap(layout.width, layout.height)
    val canvas = Canvas(strip)
    canvas.drawColor(Color.WHITE)

    val framePaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        if (monochrome) {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        }
    }
    frames.forEachIndexed { index, frame ->
        val dst = layout.frameRects[index]
        canvas.drawBitmap(
            frame,
            null,
            Rect(dst.left, dst.top, dst.left + dst.width, dst.top + dst.height),
            framePaint,
        )
    }

    val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        letterSpacing = FOOTER_LETTER_SPACING
        textAlign = Paint.Align.CENTER
        textSize = layout.footerHeightPx * FOOTER_TEXT_SCALE
    }
    val footerCenterY = layout.height - layout.footerHeightPx / 2f
    val baseline = footerCenterY - (footerPaint.ascent() + footerPaint.descent()) / 2f
    canvas.drawText(footerText, layout.width / 2f, baseline, footerPaint)
    return strip
}
