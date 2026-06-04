package io.github.stozo04.openloop.media

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.IOException

/**
 * A reverse pass completed without error but its output is unusable (zero muxed samples / no video
 * track — the S23 wedge, RESEARCH.md §7c). Extends [IOException] so existing failure paths
 * (worker `catch (e: IOException)`, editor forward-fallback) handle it without new plumbing;
 * [validation] carries the probe facts for diagnostics when the failure came from the post-write
 * probe (null when detected by the in-pipeline sample counter before the file was finalized).
 */
class ReverseOutputInvalidException(
    message: String,
    val validation: ReverseOutputValidation? = null,
) : IOException(message)

/**
 * Result of validating a candidate reversed MP4 (reverse-output-validation spec §5.1).
 *
 * @param reason machine-readable verdict, one of the `REASON_*` constants in
 *   [ReverseOutputValidator] — `"ok"` iff [valid].
 * @param fileBytes file size, diagnostics only — never a validity gate (a legitimate short
 *   low-entropy clip can be under 4 KiB; proven by a real device, see the class KDoc).
 * @param sampleCount exact video sample count up to [ReverseOutputValidator.SAMPLE_COUNT_CAP]
 *   (spec calls this `estimatedFrameCount`; we count real container samples, no estimation).
 */
data class ReverseOutputValidation(
    val valid: Boolean,
    val reason: String,
    val fileBytes: Long,
    val videoTrackCount: Int,
    val sampleCount: Int,
)

/**
 * Validates that a reversed MP4 is actually usable downstream (ExoPlayer preview, Media3
 * Transformer). Exists because a wedged pass 2 can exit cleanly with a ~598-byte moov-only shell —
 * zero samples — which the Transformer rejects only later as the cryptic
 * `IllegalStateException: The asset loader has no audio or video track to output`
 * (S23/API 33, RESEARCH.md §2–§3). **Never gate on `length() > 0` alone.**
 *
 * Validity is decided by *content*, not size: ≥1 video track with ≥1 readable sample. The spec's
 * suggested `MIN_REVERSED_BYTES = 4096` size gate was dropped after a Pixel 10 Pro Fold encoded a
 * legitimate 12-frame 320x240 clip to under 4 KiB (instrumented run, 2026-06-04) — size gates
 * false-positive on small real clips while the zero-sample probe already rejects the shells.
 */
object ReverseOutputValidator {

    /** Stop counting samples once validity is provable and diagnostics are ample. */
    const val SAMPLE_COUNT_CAP = 500

    const val REASON_OK = "ok"
    const val REASON_MISSING_FILE = "missing_file"
    const val REASON_NO_VIDEO_TRACK = "no_video_track"
    const val REASON_NO_VIDEO_SAMPLES = "no_video_samples"
    const val REASON_UNREADABLE = "unreadable"

    /**
     * Probe [file] with [MediaExtractor] and return the verdict. Never throws — an unparseable
     * file is an *invalid* file ([REASON_UNREADABLE]), not a crash.
     */
    fun validateReversedOutput(file: File): ReverseOutputValidation {
        if (!file.exists()) return reverseOutputVerdict(fileBytes = -1L, videoTrackCount = 0, sampleCount = 0)
        val bytes = file.length()
        val probe = try {
            probeVideoTrack(file)
        } catch (e: IOException) {
            return ReverseOutputValidation(false, REASON_UNREADABLE, bytes, 0, 0)
        } catch (e: IllegalArgumentException) {
            // MediaExtractor.setDataSource throws IAE for content it cannot sniff.
            return ReverseOutputValidation(false, REASON_UNREADABLE, bytes, 0, 0)
        }
        return reverseOutputVerdict(bytes, probe.videoTrackCount, probe.sampleCount)
    }

    /**
     * Pure verdict over probed facts — JVM-unit-tested ([ReverseOutputValidatorTest]) without
     * Android. `fileBytes < 0` encodes "file missing".
     */
    internal fun reverseOutputVerdict(
        fileBytes: Long,
        videoTrackCount: Int,
        sampleCount: Int,
    ): ReverseOutputValidation {
        val reason = when {
            fileBytes < 0L -> REASON_MISSING_FILE
            videoTrackCount < 1 -> REASON_NO_VIDEO_TRACK
            sampleCount < 1 -> REASON_NO_VIDEO_SAMPLES
            else -> REASON_OK
        }
        return ReverseOutputValidation(
            valid = reason == REASON_OK,
            reason = reason,
            fileBytes = fileBytes.coerceAtLeast(0L),
            videoTrackCount = videoTrackCount,
            sampleCount = sampleCount,
        )
    }

    private data class VideoTrackProbe(val videoTrackCount: Int, val sampleCount: Int)

    private fun probeVideoTrack(file: File): VideoTrackProbe {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            var videoTracks = 0
            var firstVideoTrack = -1
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    videoTracks++
                    if (firstVideoTrack < 0) firstVideoTrack = i
                }
            }
            if (firstVideoTrack < 0) return VideoTrackProbe(0, 0)

            extractor.selectTrack(firstVideoTrack)
            var samples = 0
            while (samples < SAMPLE_COUNT_CAP) {
                if (extractor.sampleTime < 0L) break
                samples++
                if (!extractor.advance()) break
            }
            return VideoTrackProbe(videoTracks, samples)
        } finally {
            extractor.release()
        }
    }
}
