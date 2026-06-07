package io.github.stozo04.openloop.media

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import io.github.stozo04.openloop.diagnostics.ReverseCrashlytics
import java.io.File

/**
 * Decodes evenly spaced frames for the Trim filmstrip. Best-effort: any slot may be `null` on failure.
 * Must run off the main thread — callers use [kotlinx.coroutines.Dispatchers.IO].
 */
fun extractTrimFilmstripFrames(
    file: File,
    durationMs: Long,
    frameCount: Int,
): List<Bitmap?> {
    if (frameCount <= 0) return emptyList()
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.absolutePath)
        List(frameCount) { index ->
            val timeMs = if (frameCount == 1) {
                0L
            } else {
                (durationMs * index / (frameCount - 1).toLong()).coerceIn(0L, durationMs)
            }
            try {
                retriever.getFrameAtTime(timeMs * 1_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
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
        // failed: status = 0x...") — the getFrameAtTime catch above doesn't cover this one.
        ReverseCrashlytics.reportMediaRetrieverFailure(
            "trim_filmstrip", "runtime", e, source = file,
        )
        List(frameCount) { null }
    } finally {
        retriever.release()
    }
}
