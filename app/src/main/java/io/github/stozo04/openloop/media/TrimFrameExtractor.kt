package io.github.stozo04.openloop.media

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.core.graphics.scale
import io.github.stozo04.openloop.diagnostics.ReverseCrashlytics
import java.io.File

/**
 * Decodes evenly spaced frames for the Trim filmstrip, each scaled down to just cover a
 * [tileWidthPx] × [tileHeightPx] tile. The tiles live in Compose state for as long as Trim is
 * composed — backgrounded included — so a 4K source decoded at native size held ~33 MB per tile,
 * over Play's 200 MB background-bitmap line (Issue #149). Best-effort: any slot may be `null` on
 * failure. Must run off the main thread — callers use [kotlinx.coroutines.Dispatchers.IO].
 *
 * [sdkInt] is injectable so both decode paths are JVM-testable (Lesson 024 precedent).
 */
fun extractTrimFilmstripFrames(
    file: File,
    durationMs: Long,
    frameCount: Int,
    tileWidthPx: Int,
    tileHeightPx: Int,
    sdkInt: Int = Build.VERSION.SDK_INT,
): List<Bitmap?> {
    if (frameCount <= 0) return emptyList()
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.absolutePath)
        val (boxWidth, boxHeight) = filmstripDecodeBox(
            sourceWidth = retriever.metadataInt(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH),
            sourceHeight = retriever.metadataInt(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT),
            rotationDegrees = retriever.metadataInt(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION),
            tileWidthPx = tileWidthPx,
            tileHeightPx = tileHeightPx,
        )
        List(frameCount) { index ->
            val timeMs = if (frameCount == 1) {
                0L
            } else {
                (durationMs * index / (frameCount - 1).toLong()).coerceIn(0L, durationMs)
            }
            val timeUs = timeMs * 1_000L
            try {
                if (hasScaledFrameApi(sdkInt)) {
                    retriever.getScaledFrameAtTime(
                        timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, boxWidth, boxHeight,
                    )
                } else {
                    retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?.shrunkToCover(tileWidthPx, tileHeightPx)
                }
            } catch (_: RuntimeException) {
                null
            }
        }
    } catch (e: IllegalArgumentException) {
        ReverseCrashlytics.reportMediaRetrieverFailure(
            "trim_filmstrip", "illegal_argument", e, source = file,
        )
        List(frameCount) { null }
    } catch (e: IllegalStateException) {
        ReverseCrashlytics.reportMediaRetrieverFailure(
            "trim_filmstrip", "illegal_state", e, source = file,
        )
        List(frameCount) { null }
    } catch (e: RuntimeException) {
        // setDataSource surfaces native open failures as bare RuntimeExceptions ("setDataSource
        // failed: status = 0x...") — the per-frame catch above doesn't cover this one.
        ReverseCrashlytics.reportMediaRetrieverFailure(
            "trim_filmstrip", "runtime", e, source = file,
        )
        List(frameCount) { null }
    } finally {
        retriever.release()
    }
}

/**
 * The `dstWidth × dstHeight` box for `getScaledFrameAtTime`, which scales the frame to FIT inside
 * the box (aspect preserved, never upscaled). The tile draws with `ContentScale.Crop`, so this is
 * the smallest box whose fitted frame still COVERS the tile — handing it the bare tile box would
 * leave a 16:9 frame at half the tile height for Crop to blur back up. Integer math throughout:
 * a float `ceil(src * scale)` lands on either side of a whole pixel.
 *
 * Unknown source dimensions fall back to the tile box (a slightly soft tile, never a failed
 * decode); a tile of 0 px is clamped to 1 because the retriever throws on a non-positive box.
 */
internal fun filmstripDecodeBox(
    sourceWidth: Int,
    sourceHeight: Int,
    rotationDegrees: Int,
    tileWidthPx: Int,
    tileHeightPx: Int,
): Pair<Int, Int> {
    val tileW = tileWidthPx.coerceAtLeast(1)
    val tileH = tileHeightPx.coerceAtLeast(1)
    if (sourceWidth <= 0 || sourceHeight <= 0) return tileW to tileH
    // The retriever fits the UPRIGHT frame, so a 1920×1080 clip stored with rotation 90 is 1080×1920.
    val swap = rotationDegrees % 180 != 0
    val srcW = if (swap) sourceHeight else sourceWidth
    val srcH = if (swap) sourceWidth else sourceHeight
    // Cover scale = max(tileW / srcW, tileH / srcH); ≥ 1 on either axis means no downscale at all.
    if (tileW >= srcW || tileH >= srcH) return srcW to srcH
    return if (tileW.toLong() * srcH >= tileH.toLong() * srcW) {
        tileW to ceilDiv(tileW.toLong() * srcH, srcW.toLong())
    } else {
        ceilDiv(tileH.toLong() * srcW, srcH.toLong()) to tileH
    }
}

private fun ceilDiv(a: Long, b: Long): Int = ((a + b - 1) / b).toInt()

@ChecksSdkIntAtLeast(api = Build.VERSION_CODES.O_MR1, parameter = 0)
private fun hasScaledFrameApi(sdkInt: Int): Boolean = sdkInt >= Build.VERSION_CODES.O_MR1

private fun MediaMetadataRetriever.metadataInt(key: Int): Int = extractMetadata(key)?.toIntOrNull() ?: 0

/**
 * API 26 (minSdk) predates `getScaledFrameAtTime`: shrink the full decode to its cover box and
 * drop it. The decoded frame is already upright, so no rotation swap. `createScaledBitmap` (which
 * [scale] wraps) returns the receiver itself when the size is unchanged — only a genuinely new
 * copy recycles it.
 */
private fun Bitmap.shrunkToCover(tileWidthPx: Int, tileHeightPx: Int): Bitmap {
    val (w, h) = filmstripDecodeBox(width, height, 0, tileWidthPx, tileHeightPx)
    val scaled = scale(w, h)
    if (scaled !== this) recycle()
    return scaled
}
