package com.openrang.app.data

import java.io.File

/**
 * A single recorded burst, modeled as the on-disk video plus its thumbnail.
 *
 * Lives in the data layer (not the UI layer) because it is the shape the
 * [VideoStorageRepository] reads from and writes to the filesystem — the UI
 * merely consumes it.
 */
data class RecordedVideo(
    val id: Long,              // epoch millis parsed from the filename (e.g. 1716825600000)
    val videoPath: String,     // absolute path to the .mp4
    val thumbnailPath: String  // absolute path to the .jpg
)

/**
 * Contract for persisting and reading recorded video bursts.
 *
 * Backed by the app's private filesystem (`cacheDir` for the scratch capture,
 * `filesDir` for finalized clips + thumbnails). The ViewModel depends on this
 * interface — never on [android.content.Context] — so the architecture rule
 * "a ViewModel must never reference Context" is enforceable by `grep`, and tests
 * can swap in a fake instead of mocking Context (see lesson 004).
 */
interface VideoStorageRepository {

    /**
     * The path the camera writes raw bursts to (`cacheDir/raw_capture.mp4`).
     * Stable for the lifetime of the repository; overwritten on each capture.
     */
    val rawCaptureFile: File

    /**
     * Copy a finalized raw capture into persistent storage and extract a thumbnail.
     * Returns the persisted video [File], or `null` if the save failed.
     */
    fun saveFinalizedVideo(rawCapture: File): File?

    /** Returns the recorded videos, newest first. Lazily extracts thumbnails if missing. */
    fun loadRecordedVideos(): List<RecordedVideo>

    /** Removes the video file and its thumbnail. */
    fun deleteVideo(video: RecordedVideo)
}
