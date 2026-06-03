package io.github.stozo04.openloop.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import kotlin.math.max
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Bounded gallery thumbnail decode — avoids holding full-resolution JPEGs in heap (Google memory
 * guidance: sample large bitmaps before decode).
 *
 * The decode entry points are **main-safe `suspend` functions** (PR #58 review REC): both decode
 * passes are disk I/O and run on an injected [CoroutineDispatcher] (default [Dispatchers.IO])
 * inside this layer, so composables call them directly without hardcoding a dispatcher
 * (ANDROID_STANDARDS §3 — dispatcher switching belongs in the data/media layer, not the UI).
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
     * Decodes [path] subsampled to ≤ [maxLongEdge] on the long edge, off the main thread.
     *
     * Returns null when the file is missing or corrupt — [BitmapFactory.decodeFile] signals every
     * decode failure by **returning null**, not by throwing, so there is deliberately no catch
     * here (Lesson 013: catch only documented throwables; the previous bare `catch (Exception)`
     * was dead code that would also have hidden real bugs).
     */
    suspend fun decodeSampled(
        path: String,
        maxLongEdge: Int = MAX_LONG_EDGE_PX,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): Bitmap? = withContext(dispatcher) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, maxLongEdge)
        }
        BitmapFactory.decodeFile(path, opts)
    }

    /** Convenience for gallery grid tiles. */
    suspend fun decodeGalleryThumbnail(file: File): Bitmap? = decodeSampled(file.absolutePath)
}
