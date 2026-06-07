package io.github.stozo04.openloop.data

import android.content.ContentResolver
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import io.github.stozo04.openloop.diagnostics.ReverseCrashlytics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Pulls an external video (from the Android Photo Picker) into the app's own scratch storage so the
 * existing capture pipeline can consume it as if it had just been recorded (slice 07).
 *
 * This is a deliberately thin, `Context`-bearing helper kept *outside* the `Context`-free
 * [VideoStorageRepository] (Lesson 004): reading a `content://` URI needs a [ContentResolver], which
 * only a `Context` can supply, and the repository must never hold one. The
 * [OpenLoopViewModel][io.github.stozo04.openloop.ui.OpenLoopViewModel]'s `Factory` bridges
 * `applicationContext` into [VideoImporterImpl] once, in `MainActivity`.
 */
interface VideoImporter {
    /**
     * Best-effort source duration in milliseconds (via [MediaMetadataRetriever] over the content
     * [source]), or `0L` if it can't be read. Used to enforce the ≤30 s import rule *before* copying,
     * so a too-long clip is never copied just to be rejected.
     */
    suspend fun probeDurationMs(source: Uri): Long

    /**
     * Copy the picked [source] content into [dest] off the main thread. Returns `false` on any I/O
     * failure (unreadable/revoked URI, low storage) — never throws to the caller, so the ViewModel
     * can fall back to a friendly snackbar rather than crashing.
     */
    suspend fun importToFile(source: Uri, dest: File): Boolean
}

class VideoImporterImpl(context: Context) : VideoImporter {

    private val appContext = context.applicationContext
    private val contentResolver: ContentResolver = appContext.contentResolver

    override suspend fun probeDurationMs(source: Uri): Long = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            readDurationMs(retriever, source)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "probe failed", e)
            ReverseCrashlytics.reportMediaRetrieverFailure(
                "import_duration_probe", "illegal_argument", e,
                sourceLabel = source.lastPathSegment ?: "content_uri",
            )
            0L
        } catch (e: IOException) {
            Log.e(TAG, "probe open failed", e)
            ReverseCrashlytics.reportMediaRetrieverFailure(
                "import_duration_probe", "io", e,
                sourceLabel = source.lastPathSegment ?: "content_uri",
            )
            0L
        } catch (e: SecurityException) {
            Log.e(TAG, "probe not permitted", e)
            ReverseCrashlytics.reportMediaRetrieverFailure(
                "import_duration_probe", "security", e,
                sourceLabel = source.lastPathSegment ?: "content_uri",
            )
            0L
        } catch (e: RuntimeException) {
            // MediaMetadataRetriever surfaces decode failures as bare RuntimeExceptions.
            Log.e(TAG, "probe decode failed", e)
            ReverseCrashlytics.reportMediaRetrieverFailure(
                "import_duration_probe", "runtime", e,
                sourceLabel = source.lastPathSegment ?: "content_uri",
            )
            0L
        } finally {
            retriever.release()
        }
    }

    /**
     * Tries the AssetFileDescriptor path first (offset-aware for embedded regions), then falls back to
     * [MediaMetadataRetriever.setDataSource] with the content [Uri] when the AFD path fails or the
     * provider reports [AssetFileDescriptor.UNKNOWN_LENGTH] — a common Photo Picker case that would
     * otherwise make the pre-copy probe return 0 and reject the clip with a generic failure.
     */
    private fun readDurationMs(retriever: MediaMetadataRetriever, source: Uri): Long {
        contentResolver.openAssetFileDescriptor(source, "r")?.use { afd ->
            bindRetrieverToAsset(retriever, afd)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let {
                if (it > 0L) return it
            }
        }
        retriever.setDataSource(appContext, source)
        return retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    }

    private fun bindRetrieverToAsset(retriever: MediaMetadataRetriever, afd: AssetFileDescriptor) {
        val length = afd.length
        if (length > 0L && length != AssetFileDescriptor.UNKNOWN_LENGTH) {
            // Offset-aware overload for providers that expose a region inside a larger file.
            retriever.setDataSource(afd.fileDescriptor, afd.startOffset, length)
        } else {
            // UNKNOWN_LENGTH is legal for many content providers; the 3-arg overload rejects it.
            retriever.setDataSource(afd.fileDescriptor)
        }
    }

    override suspend fun importToFile(source: Uri, dest: File): Boolean = withContext(Dispatchers.IO) {
        try {
            contentResolver.openInputStream(source)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } != null
        } catch (e: IOException) {
            Log.e(TAG, "import copy failed", e); false
        } catch (e: SecurityException) {
            Log.e(TAG, "import URI not readable", e); false
        }
    }

    private companion object {
        const val TAG = "VideoImporter"
    }
}
