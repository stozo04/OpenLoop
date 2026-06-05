package io.github.stozo04.openloop.data

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Production implementation of [VideoStorageRepository].
 *
 * Holds only raw [File] handles ([cacheDir] / [filesDir]) — never a
 * [android.content.Context]. The [OpenLoopViewModel][io.github.stozo04.openloop.ui.OpenLoopViewModel]'s
 * `Factory` bridges Context to these paths once, in `MainActivity`.
 *
 * On-disk layout:
 * - scratch capture:  `cacheDir/scratch/raw_<uuid>.mp4` (per capture; cache-evictable)
 * - persisted clips:  `filesDir/videos/` — raws as `clip_<timestamp>.mp4`, rendered loops as
 *   `boom_<timestamp>_from_<rawTimestamp>.mp4` (same directory; distinguished by filename prefix)
 * - thumbnails:       `filesDir/thumbnails/<same-stem>.jpg` (JPEG, 90% quality)
 */
class VideoStorageRepositoryImpl(
    private val cacheDir: File,
    private val filesDir: File,
) : VideoStorageRepository {

    private val videosDir = File(filesDir, "videos")
    private val thumbnailsDir = File(filesDir, "thumbnails")
    private val scratchDir = File(cacheDir, "scratch")

    /**
     * Last minted millis-timestamp id. Two saves in the same wall-clock millisecond would otherwise
     * collide on the `clip_<ts>` / `boom_<ts>` filename and `id`; [nextTimestamp] guarantees a
     * strictly increasing value across both `promoteScratchToRaw` and `allocateBoomerangFile`.
     */
    private var lastTimestamp = 0L

    @Synchronized
    private fun nextTimestamp(): Long {
        val now = System.currentTimeMillis()
        val ts = if (now > lastTimestamp) now else lastTimestamp + 1
        lastTimestamp = ts
        return ts
    }

    override fun createScratchCapture(): ScratchCapture {
        scratchDir.mkdirs()
        val uuid = UUID.randomUUID().toString()
        return ScratchCapture(uuid, File(scratchDir, "raw_$uuid.mp4"))
    }

    override suspend fun promoteScratchToRaw(scratch: ScratchCapture): RecordedVideo? =
        withContext(Dispatchers.IO) {
            val timestamp = nextTimestamp()
            val destVideo = File(videosDir.apply { mkdirs() }, "clip_$timestamp.mp4")
            val destThumb = File(thumbnailsDir.apply { mkdirs() }, "clip_$timestamp.jpg")

            try {
                scratch.file.copyTo(destVideo, overwrite = true)
                extractThumbnail(destVideo, destThumb)
                RecordedVideo(
                    id = timestamp,
                    videoPath = destVideo.absolutePath,
                    thumbnailPath = destThumb.absolutePath,
                    kind = VideoKind.RAW,
                )
            } catch (e: IOException) {
                Log.e(TAG, "Failed to promote scratch capture ${scratch.uuid} to raw", e)
                null
            } catch (e: IllegalArgumentException) {
                // MediaMetadataRetriever.setDataSource on an unreadable scratch file.
                Log.e(TAG, "Failed to promote scratch capture ${scratch.uuid} to raw", e)
                null
            } catch (e: RuntimeException) {
                // MediaMetadataRetriever surfaces decode failures as bare RuntimeExceptions
                // ("setDataSource failed: status = 0x...") — extractThumbnail has no catch of
                // its own, so an unreadable copy must fail promotion here, not crash (PR #66).
                Log.e(TAG, "Failed to promote scratch capture ${scratch.uuid} to raw", e)
                null
            }
        }

    override fun discardScratch(scratch: ScratchCapture) {
        try {
            if (scratch.file.exists()) {
                scratch.file.delete()
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to discard scratch ${scratch.uuid}", e)
        }
    }

    override suspend fun pruneStaleScratch(olderThanMs: Long): Int = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - olderThanMs
        // Prune BOTH the per-capture/import scratch files directly under scratch/ (raw_<uuid>.mp4)
        // AND the cached reversed clips under scratch/reversed/. The reversed cache is where the
        // heaviest orphans accumulate — one ≤1080p reversed MP4 per distinct trim window, written
        // once and never overwritten (keyed by sha1(path_trimStart_trimEnd) in VideoReverser) — so
        // D-8's reclaim must reach it. The prior sweep only looked at the top level and skipped the
        // reversed/ directory entirely (isFile == false for it), leaving it to grow until opaque OS
        // cache eviction. (Dir name mirrors VideoReverser's scratchDir = cacheDir/scratch/reversed.)
        val deleted = pruneStaleFilesIn(scratchDir, cutoff) +
            pruneStaleFilesIn(File(scratchDir, REVERSED_SUBDIR), cutoff)
        if (deleted > 0) Log.d(TAG, "Pruned $deleted stale scratch file(s)")
        deleted
    }

    /**
     * Delete regular files directly in [dir] whose `lastModified()` is older than [cutoff]; returns
     * the count deleted. Shallow by design (the `isFile` guard skips any nested directory), and
     * tolerant of a missing [dir] (`listFiles()` → null). A per-file `SecurityException` is logged
     * and skipped rather than aborting the whole sweep.
     */
    private fun pruneStaleFilesIn(dir: File, cutoff: Long): Int {
        val files = dir.listFiles() ?: return 0
        var deleted = 0
        for (file in files) {
            try {
                if (file.isFile && file.lastModified() < cutoff && file.delete()) deleted++
            } catch (e: SecurityException) {
                Log.e(TAG, "Failed to prune stale scratch ${file.name}", e)
            }
        }
        return deleted
    }

    override fun allocateBoomerangFile(sourceRawId: Long): File {
        val timestamp = nextTimestamp()
        return File(videosDir.apply { mkdirs() }, "boom_${timestamp}_from_$sourceRawId.mp4")
    }

    override suspend fun registerBoomerang(file: File, sourceRawId: Long): RecordedVideo? =
        withContext(Dispatchers.IO) {
            try {
                val id = parseTimestamp(file.name, prefix = "boom_") ?: nextTimestamp()
                val destThumb = File(thumbnailsDir.apply { mkdirs() }, "${file.nameWithoutExtension}.jpg")
                extractThumbnail(file, destThumb)
                RecordedVideo(
                    id = id,
                    videoPath = file.absolutePath,
                    thumbnailPath = destThumb.absolutePath,
                    kind = VideoKind.BOOMERANG,
                    sourceRawId = sourceRawId,
                )
            } catch (e: IOException) {
                Log.e(TAG, "Failed to register boomerang ${file.name}", e)
                null
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "Failed to register boomerang ${file.name}", e)
                null
            } catch (e: RuntimeException) {
                // MediaMetadataRetriever surfaces decode failures as bare RuntimeExceptions
                // ("setDataSource failed: status = 0x...") — extractThumbnail has no catch of
                // its own, so a corrupt rendered file must fail registration here, not crash (PR #66).
                Log.e(TAG, "Failed to register boomerang ${file.name}", e)
                null
            }
        }

    override suspend fun durationOf(file: File): Long = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: IllegalArgumentException) {
            // setDataSource throws this for an unreadable/invalid file.
            Log.e(TAG, "Failed to read duration for ${file.name}", e)
            0L
        } catch (e: RuntimeException) {
            // MediaMetadataRetriever surfaces decode failures as bare RuntimeExceptions.
            Log.e(TAG, "Failed to read duration for ${file.name}", e)
            0L
        } finally {
            retriever.release()
        }
    }

    override suspend fun loadRecordedVideos(): List<RecordedVideo> = withContext(Dispatchers.IO) {
        migrateLegacyBoomerangsDir()
        val raws = loadFrom(videosDir, prefix = "clip_", kind = VideoKind.RAW)
        val boomerangs = loadFrom(videosDir, prefix = "boom_", kind = VideoKind.BOOMERANG)
        (raws + boomerangs).sortedByDescending { it.id } // Newest first
    }

    /**
     * One-time move of rendered loops from the retired `filesDir/boomerangs/` directory into
     * `filesDir/videos/` (same filename). Safe to run on every load — no-op once migrated.
     */
    private fun migrateLegacyBoomerangsDir() {
        val legacyDir = File(filesDir, "boomerangs")
        if (!legacyDir.isDirectory) return
        videosDir.mkdirs()
        legacyDir.listFiles { _, name -> name.startsWith("boom_") && name.endsWith(".mp4") }
            ?.forEach { legacyFile ->
                val dest = File(videosDir, legacyFile.name)
                try {
                    when {
                        !dest.exists() -> legacyFile.renameTo(dest)
                        legacyFile.delete() -> Unit // duplicate already in videos/
                        else -> Log.w(TAG, "Could not migrate legacy boomerang ${legacyFile.name}")
                    }
                } catch (e: SecurityException) {
                    Log.e(TAG, "Failed to migrate legacy boomerang ${legacyFile.name}", e)
                }
            }
        legacyDir.listFiles()?.takeIf { it.isEmpty() }?.let { legacyDir.delete() }
    }

    /**
     * Scan [dir] for `<prefix>*.mp4` clips and map each to a [RecordedVideo] of [kind], lazily
     * extracting a missing thumbnail. A clip whose thumbnail can't be produced still appears in the
     * list (resilience — never a silently-dropped clip).
     */
    private fun loadFrom(dir: File, prefix: String, kind: VideoKind): List<RecordedVideo> {
        if (!dir.exists()) return emptyList()
        val files = dir.listFiles { _, name -> name.startsWith(prefix) && name.endsWith(".mp4") }
            ?: return emptyList()

        return files.mapNotNull { file ->
            val id = parseTimestamp(file.name, prefix) ?: 0L
            val sourceRawId = if (kind == VideoKind.BOOMERANG) parseSourceRawId(file.name) else null
            val thumbFile = File(thumbnailsDir, "${file.nameWithoutExtension}.jpg")

            if (!thumbFile.exists()) {
                try {
                    thumbnailsDir.mkdirs()
                    extractThumbnail(file, thumbFile)
                } catch (e: IOException) {
                    Log.e(TAG, "Failed to extract on-demand thumbnail for ${file.name}", e)
                    // Fall through: still list the clip, just with a (possibly missing) thumb path.
                } catch (e: RuntimeException) {
                    // MediaMetadataRetriever.setDataSource / decode failures surface as runtime
                    // exceptions (e.g. IllegalArgumentException) — still list the clip.
                    Log.e(TAG, "Failed to extract on-demand thumbnail for ${file.name}", e)
                }
            }
            RecordedVideo(id, file.absolutePath, thumbFile.absolutePath, kind, sourceRawId)
        }
    }

    override suspend fun deleteVideo(video: RecordedVideo) = withContext(Dispatchers.IO) {
        try {
            File(video.videoPath).takeIf { it.exists() }?.delete()
            File(video.thumbnailPath).takeIf { it.exists() }?.delete()
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to delete video ${video.id}", e)
        }
        Unit
    }

    override suspend fun deleteRawVideo(id: Long) = withContext(Dispatchers.IO) {
        val videoFile = File(videosDir, "clip_$id.mp4")
        val thumbFile = File(thumbnailsDir, "clip_$id.jpg")
        try {
            if (videoFile.exists()) videoFile.delete()
            if (thumbFile.exists()) thumbFile.delete()
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to delete raw video $id", e)
        }
        Unit
    }

    /**
     * Extract the first frame of [video] to [dest] as a 90%-quality JPEG. The retriever is released
     * in a `finally` so a decode failure can't leak the native handle (the prior implementation
     * leaked it on the exception path).
     */
    private fun extractThumbnail(video: File, dest: File) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(video.absolutePath)
            val bitmap = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            if (bitmap != null) {
                dest.outputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
            }
        } finally {
            retriever.release()
        }
    }

    /** Parse the leading epoch-millis timestamp out of `<prefix><ts>...mp4`. */
    private fun parseTimestamp(name: String, prefix: String): Long? =
        name.removePrefix(prefix).substringBefore('_').substringBefore('.').toLongOrNull()

    /** Parse `<rawTs>` out of a `boom_<ts>_from_<rawTs>.mp4` boomerang filename. */
    private fun parseSourceRawId(name: String): Long? =
        name.substringAfter("_from_", "").substringBefore(".mp4").toLongOrNull()

    private companion object {
        const val TAG = "VideoStorageRepository"

        /** Subdirectory of scratch/ holding VideoReverser's cached reversed clips (see MainActivity). */
        const val REVERSED_SUBDIR = "reversed"
    }
}
