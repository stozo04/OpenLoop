package io.github.stozo04.openloop.work

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Copy a completed render into the device's public video library. */
internal suspend fun publishVideoToPhotos(context: Context, source: File): Uri = publishToPhotos(
    context,
    source,
    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
    mediaStoreValues(source, Build.VERSION.SDK_INT),
)

/**
 * Copy a photo-mode still into the device's public image library (`Pictures/OpenLoop`).
 *
 * Callers treat a throw here as non-fatal: the in-app library copy is already saved, and losing the
 * public copy must not cost the user their photo (PRD-photo-capture §5.5).
 */
internal suspend fun publishImageToPhotos(context: Context, source: File): Uri = publishToPhotos(
    context,
    source,
    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
    imageMediaStoreValues(source, Build.VERSION.SDK_INT),
)

/**
 * Insert [values] into [collection], stream [source] into the minted entry, then clear its pending
 * flag. Any failure deletes the half-written entry before rethrowing — a partially copied file must
 * never appear in the user's library.
 *
 * `IS_PENDING` is declared on [MediaStore.MediaColumns], which both `Video.Media` and `Images.Media`
 * inherit, so video and image publishes share this body with no branch. Only the collection and the
 * [ContentValues] differ, and both are the caller's job.
 */
private suspend fun publishToPhotos(
    context: Context,
    source: File,
    collection: Uri,
    values: ContentValues,
): Uri = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val uri = resolver.insert(collection, values)
        ?: throw IOException("Could not create MediaStore entry for ${source.name}")

    try {
        resolver.openOutputStream(uri)?.use { output ->
            source.inputStream().use { input -> input.copyTo(output) }
        } ?: throw IOException("Could not open MediaStore output for ${source.name}")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val published = resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            if (published == 0) throw IOException("Could not publish ${source.name} in MediaStore")
        }
        uri
    } catch (e: Exception) {
        try {
            resolver.delete(uri, null, null)
        } catch (cleanupError: RuntimeException) {
            Log.w(TAG, "Could not remove incomplete MediaStore entry $uri", cleanupError)
        }
        throw e
    }
}

internal fun imageMediaStoreValues(source: File, sdkInt: Int): ContentValues {
    val timestamp = source.name
        .removePrefix("photo_")
        .substringBefore('.')
        .toLongOrNull()
        ?: System.currentTimeMillis()

    return ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "OpenLoop_Photo_$timestamp.jpg")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.DATE_TAKEN, timestamp)
        if (sdkInt >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/OpenLoop")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }
}

internal fun mediaStoreValues(source: File, sdkInt: Int): ContentValues {
    val timestamp = source.name
        .removePrefix("boom_")
        .substringBefore('_')
        .toLongOrNull()
        ?: System.currentTimeMillis()

    return ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, "OpenLoop_Capture_$timestamp.mp4")
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        put(MediaStore.Video.Media.DATE_TAKEN, timestamp)
        if (sdkInt >= Build.VERSION_CODES.Q) {
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/OpenLoop")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
    }
}

private const val TAG = "MediaStorePublisher"
