package io.github.stozo04.openloop.diagnostics

import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.CustomKeysAndValues
import com.google.firebase.crashlytics.FirebaseCrashlytics
import io.github.stozo04.openloop.media.REVERSE_REPORT_PHASE_PREVIEW
import io.github.stozo04.openloop.media.REVERSE_REPORT_PHASE_SAVE
import io.github.stozo04.openloop.media.ReverseOutputInvalidException
import io.github.stozo04.openloop.media.ReversePreviewDiagnostics
import io.github.stozo04.openloop.media.buildReverseSupportReport
import io.github.stozo04.openloop.media.probeReversePreviewDiagnostics
import java.io.File

/**
 * Reports preview-reverse failures to Firebase Crashlytics as **non-fatal** events.
 *
 * Events upload on the next app launch (or with the next fatal), per
 * [Firebase Crashlytics](https://firebase.google.com/docs/crashlytics/android/customize-crash-reports).
 * In-app **Send debug info** remains the immediate tester path; Crashlytics is for aggregate triage.
 */
internal object ReverseCrashlytics {
    private const val TAG = "ReverseCrashlytics"

    const val PHASE_PREVIEW = REVERSE_REPORT_PHASE_PREVIEW
    const val PHASE_SAVE = REVERSE_REPORT_PHASE_SAVE

    fun reportPreviewFailure(
        versionName: String,
        versionCode: Int,
        source: File,
        trimStartMs: Long,
        trimEndMs: Long,
        outcome: String,
        cause: Throwable,
    ) = reportFailure(versionName, versionCode, source, trimStartMs, trimEndMs, outcome, cause, PHASE_PREVIEW)

    /** Save/render failure (reverse-output-validation spec §5.3) — same keys, `reverse_phase=save`. */
    fun reportSaveFailure(
        versionName: String,
        versionCode: Int,
        source: File,
        trimStartMs: Long,
        trimEndMs: Long,
        outcome: String,
        cause: Throwable,
    ) = reportFailure(versionName, versionCode, source, trimStartMs, trimEndMs, outcome, cause, PHASE_SAVE)

    private fun reportFailure(
        versionName: String,
        versionCode: Int,
        source: File,
        trimStartMs: Long,
        trimEndMs: Long,
        outcome: String,
        cause: Throwable,
        phase: String,
    ) {
        val crashlytics = crashlyticsOrNull() ?: return
        val diagnostics = probeReversePreviewDiagnostics(source)
        val keys = customKeys(
            versionName = versionName,
            versionCode = versionCode,
            source = source,
            trimStartMs = trimStartMs,
            trimEndMs = trimEndMs,
            outcome = outcome,
            diagnostics = diagnostics,
            phase = phase,
            cause = cause,
        )
        runCatching {
            crashlytics.log("reverse_${phase}_failure: $outcome")
            crashlytics.recordException(cause, keys)
        }.onFailure { e ->
            Log.w(TAG, "Crashlytics recordException failed", e)
        }
    }

    /** Plain-text report for the in-app share sheet (same facts as Crashlytics keys). */
    fun logReversePreviewCleanup(deletedCount: Int, bytesDeleted: Long) {
        val crashlytics = crashlyticsOrNull() ?: return
        runCatching {
            crashlytics.log(
                "reverse_preview_cleanup: deleted=$deletedCount bytes=$bytesDeleted",
            )
        }.onFailure { e ->
            Log.w(TAG, "Crashlytics log failed", e)
        }
    }

    fun logEditorDispose(playlistRebindCount: Int, editorDurationSec: Long) {
        val crashlytics = crashlyticsOrNull() ?: return
        runCatching {
            crashlytics.log(
                "editor_dispose: playlist_rebind_count=$playlistRebindCount " +
                    "editor_duration_sec=$editorDurationSec",
            )
        }.onFailure { e ->
            Log.w(TAG, "Crashlytics log failed", e)
        }
    }

    fun supportReportForShare(
        versionName: String,
        versionCode: Int,
        source: File,
        trimStartMs: Long,
        trimEndMs: Long,
        outcome: String,
        phase: String = PHASE_PREVIEW,
    ): String = buildReverseSupportReport(
        versionName = versionName,
        versionCode = versionCode,
        source = source,
        trimStartMs = trimStartMs,
        trimEndMs = trimEndMs,
        outcome = outcome,
        phase = phase,
    )

    private fun crashlyticsOrNull(): FirebaseCrashlytics? =
        runCatching {
            FirebaseApp.getInstance()
            FirebaseCrashlytics.getInstance()
        }.getOrNull()

    private fun customKeys(
        versionName: String,
        versionCode: Int,
        source: File,
        trimStartMs: Long,
        trimEndMs: Long,
        outcome: String,
        diagnostics: ReversePreviewDiagnostics?,
        phase: String,
        cause: Throwable?,
    ): CustomKeysAndValues {
        val builder = CustomKeysAndValues.Builder()
            .putString("reverse_phase", phase.take(1024))
            .putString("reverse_outcome", outcome.take(1024))
            .putString("app_version", versionName.take(1024))
            .putInt("app_version_code", versionCode)
            .putLong("trim_start_ms", trimStartMs)
            .putLong("trim_end_ms", trimEndMs)
            .putLong("trim_window_ms", (trimEndMs - trimStartMs).coerceAtLeast(0L))
            .putLong("source_bytes", source.length())
            .putString("source_name", source.name.take(1024))
        if (diagnostics != null) {
            builder
                .putString("video_mime", (diagnostics.mime ?: "unknown").take(1024))
                .putInt("video_width", diagnostics.width)
                .putInt("video_height", diagnostics.height)
                .putInt("video_fps", diagnostics.fps)
                .putBoolean("hevc_or_hdr_normalize", diagnostics.needsNormalize)
        }
        // Zero-frame pass-2 detection (S23/API 33) carries the probe facts — surface them for
        // aggregate triage of which devices produce sample-less reversed output.
        (cause as? ReverseOutputInvalidException)?.validation?.let { v ->
            builder
                .putString("reverse_validation_reason", v.reason.take(1024))
                .putLong("reversed_file_bytes", v.fileBytes)
                .putInt("reversed_sample_count", v.sampleCount)
        }
        return builder.build()
    }
}
