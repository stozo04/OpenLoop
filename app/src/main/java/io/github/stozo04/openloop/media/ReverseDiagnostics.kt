package io.github.stozo04.openloop.media

import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import java.io.File
import java.io.IOException

/** Structured probe used for Crashlytics custom keys and the share-sheet report. */
internal data class ReversePreviewDiagnostics(
    val mime: String?,
    val width: Int,
    val height: Int,
    val fps: Int,
    val needsNormalize: Boolean,
)

internal fun probeReversePreviewDiagnostics(source: File): ReversePreviewDiagnostics? =
    runCatching {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(source.absolutePath)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("video/")) continue
                val w = if (format.containsKey(MediaFormat.KEY_WIDTH)) format.getInteger(MediaFormat.KEY_WIDTH) else -1
                val h = if (format.containsKey(MediaFormat.KEY_HEIGHT)) format.getInteger(MediaFormat.KEY_HEIGHT) else -1
                return@runCatching ReversePreviewDiagnostics(
                    mime = mime,
                    width = w,
                    height = h,
                    fps = format.frameRateOrDefault(),
                    needsNormalize = needsReverseNormalize(mime, format.colorTransferOrNull()),
                )
            }
            null
        } finally {
            runCatching { extractor.release() }
        }
    }.getOrNull()

/**
 * Plain-text bundle testers can share via email/WhatsApp (no Android Studio required).
 */
internal fun buildReverseSupportReport(
    versionName: String,
    versionCode: Int,
    source: File,
    trimStartMs: Long,
    trimEndMs: Long,
    outcome: String,
): String = buildString {
    appendLine("OpenLoop — loop preview diagnostic")
    appendLine("Outcome: $outcome")
    appendLine("App: $versionName ($versionCode)")
    appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
    appendLine("Android API: ${Build.VERSION.SDK_INT}")
    appendLine("Trim: ${trimStartMs}ms .. ${trimEndMs}ms (${trimEndMs - trimStartMs}ms window)")
    appendLine("Source file: ${source.name}, ${source.length()} bytes")
    probeReversePreviewDiagnostics(source)?.let { d ->
        appendLine(
            "Video track: ${d.mime} ${d.width}x${d.height} @${d.fps}fps, hevcOrHdrNormalize=${d.needsNormalize}",
        )
    }
    appendLine()
    appendLine("What you saw: stuck on \"Trimming..\" / could not save")
    appendLine("How to help: send this entire message to the developer.")
    appendLine("Crashlytics: a non-fatal report is also queued if Firebase is configured (next app open).")
}
