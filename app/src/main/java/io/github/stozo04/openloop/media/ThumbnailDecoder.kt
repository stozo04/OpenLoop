package io.github.stozo04.openloop.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import kotlin.math.max

/**
 * Bounded gallery thumbnail decode — avoids holding full-resolution JPEGs in heap (Google memory
 * guidance: sample large bitmaps before decode).
 */
object ThumbnailDecoder {

    /** Long edge cap for grid tiles (~2× 110dp @ xhdpi; see editor-memory-oom IMPLEMENTATION Q-3). */
    const val MAX_LONG_EDGE_PX = 256

    /**
     * Computes [BitmapFactory.Options.inSampleSize] so the decoded bitmap's long edge is ≤ [maxLongEdge].
     */
    fun computeInSampleSize(width: Int, height: Int, maxLongEdge: Int = MAX_LONG_EDGE_PX): Int {
        require(width > 0 && height > 0 && maxLongEdge > 0)
        val longEdge = max(width, height)
        if (longEdge <= maxLongEdge) return 1
        var sample = 1
        while (longEdge / sample > maxLongEdge) {
            sample *= 2
        }
        return sample.coerceAtLeast(1)
    }

    /**
     * Decodes [path] subsampled to ≤ [maxLongEdge] on the long edge. Returns null when missing/corrupt.
     */
    fun decodeSampled(path: String, maxLongEdge: Int = MAX_LONG_EDGE_PX): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, maxLongEdge)
        }
        return try {
            BitmapFactory.decodeFile(path, opts)
        } catch (_: Exception) {
            null
        }
    }

    /** Convenience for gallery grid tiles. */
    fun decodeGalleryThumbnail(file: File): Bitmap? = decodeSampled(file.absolutePath)
}
