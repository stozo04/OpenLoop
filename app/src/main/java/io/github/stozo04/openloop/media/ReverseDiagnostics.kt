package io.github.stozo04.openloop.media

import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import java.io.File
import java.io.IOException

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
    appendVideoTrackSummary(source)?.let { appendLine(it) }
    appendLine()
    appendLine("What you saw: stuck on \"Trimming..\" / could not save")
    appendLine("How to help: send this entire message to the developer.")
}

private fun appendVideoTrackSummary(source: File): String? =
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
                val fps = format.frameRateOrDefault()
                val normalize = needsReverseNormalize(mime, format.colorTransferOrNull())
                return@runCatching "Video track: $mime ${w}x$h @${fps}fps, hevcOrHdrNormalize=$normalize"
            }
            "Video track: (none found)"
        } finally {
            runCatching { extractor.release() }
        }
    }.getOrElse { e -> "Video track: unavailable (${e.message})" }
