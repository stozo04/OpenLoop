package io.github.stozo04.openloop.diagnostics

import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.CustomKeysAndValues
import com.google.firebase.crashlytics.FirebaseCrashlytics
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

    fun reportPreviewFailure(
        versionName: String,
        versionCode: Int,
        source: File,
        trimStartMs: Long,
        trimEndMs: Long,
        outcome: String,
        cause: Throwable,
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
        )
        runCatching {
            crashlytics.log("reverse_preview_failure: $outcome")
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
    ): String = buildReverseSupportReport(
        versionName = versionName,
        versionCode = versionCode,
        source = source,
        trimStartMs = trimStartMs,
        trimEndMs = trimEndMs,
        outcome = outcome,
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
    ): CustomKeysAndValues {
        val builder = CustomKeysAndValues.Builder()
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
        return builder.build()
    }
}
