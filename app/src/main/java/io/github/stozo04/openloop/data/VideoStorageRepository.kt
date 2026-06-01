package io.github.stozo04.openloop.data

import java.io.File

/**
 * Whether a [RecordedVideo] is an original capture (a "raw") or a rendered boomerang.
 *
 * Inferred from the filename prefix at load time (`clip_` → [RAW], `boom_` → [BOOMERANG]) — both
 * kinds live under `filesDir/videos/`. Slice 02 displayed both in the gallery without distinction;
 * badges + a filter chip arrive in slice 07.
 */
enum class VideoKind { RAW, BOOMERANG }

/**
 * A single recorded clip, modeled as the on-disk video plus its thumbnail.
 *
 * Lives in the data layer (not the UI layer) because it is the shape the
 * [VideoStorageRepository] reads from and writes to the filesystem — the UI
 * merely consumes it.
 *
 * [kind] and [sourceRawId] are defaulted so existing raw-only call sites keep
 * compiling; a boomerang carries [sourceRawId] = the `id` of the raw it was
 * rendered from (parsed from the `boom_<ts>_from_<rawTs>.mp4` filename).
 */
data class RecordedVideo(
    val id: Long,              // epoch millis parsed from the filename (e.g. 1716825600000)
    val videoPath: String,     // absolute path to the .mp4
    val thumbnailPath: String, // absolute path to the .jpg
    val kind: VideoKind = VideoKind.RAW,
    val sourceRawId: Long? = null, // for BOOMERANG: id of the source raw; null for RAW
)

/**
 * A per-capture scratch target under `cacheDir/scratch/`, identified by a UUID.
 *
 * One [ScratchCapture] is minted per shutter press ([VideoStorageRepository.createScratchCapture]).
 * The camera records into [file]; on a successful capture the scratch is promoted to a persistent
 * raw ([VideoStorageRepository.promoteScratchToRaw]); on discard it is deleted. Per-UUID files
 * (rather than a single fixed `raw_capture.mp4`) keep concurrent / back-to-back captures from
 * clobbering each other and give each in-flight capture a stable identity for the Trim screen.
 */
data class ScratchCapture(val uuid: String, val file: File)

/**
 * Contract for persisting and reading recorded clips (raws + boomerangs).
 *
 * Backed by the app's private filesystem:
 * - `cacheDir/scratch/raw_<uuid>.mp4` — per-capture scratch (volatile; cache-evictable)
 * - `filesDir/videos/clip_<ts>.mp4` — persisted raw captures
 * - `filesDir/videos/boom_<ts>_from_<rawTs>.mp4` — rendered loops
 * - `filesDir/thumbnails/<stem>.jpg` — thumbnails for both kinds
 *
 * The ViewModel depends on this interface — never on [android.content.Context] — so the
 * architecture rule "a ViewModel must never reference Context" is enforceable by `grep`, and
 * tests can swap in a fake instead of mocking Context (see lesson 004).
 */
interface VideoStorageRepository {

    /**
     * Mint a fresh per-capture scratch target (`cacheDir/scratch/raw_<uuid>.mp4`). The returned
     * [ScratchCapture.file] does NOT yet exist on disk — the camera creates it by recording into it.
     */
    fun createScratchCapture(): ScratchCapture

    /**
     * Copy a finalized [scratch] capture into persistent raw storage and extract a thumbnail.
     * Returns the persisted [RecordedVideo] (kind = [VideoKind.RAW]), or `null` if the copy failed.
     * The scratch file is left in place; callers discard it once they no longer need it.
     *
     * `suspend` + off the main thread: copies bytes and decodes a thumbnail frame, so it must never
     * run on the UI thread (ANR risk — ANDROID_STANDARDS §9).
     */
    suspend fun promoteScratchToRaw(scratch: ScratchCapture): RecordedVideo?

    /** Delete the [scratch] file if present. Idempotent — a missing file is not an error. */
    fun discardScratch(scratch: ScratchCapture)

    /**
     * Delete scratch files under `cacheDir/scratch/` whose `lastModified()` is older than
     * [olderThanMs] (i.e. age > [olderThanMs]); returns the count deleted (parent D-8). Best-effort
     * cleanup of orphaned captures/imports left behind by an interrupted session — imports raise this
     * churn since an abandoned copy can be a whole library video. Sweeps both the top-level scratch
     * captures/imports AND the cached reversed clips in `scratch/reversed/` (one ≤1080p MP4 per
     * distinct trim window, never overwritten — the heaviest orphans), so the reclaim reaches the
     * cache that actually grows. Younger files (a session possibly still in flight) are left
     * untouched. `suspend` + off the main thread (filesystem scan).
     */
    suspend fun pruneStaleScratch(olderThanMs: Long): Int

    /**
     * Allocate (but do NOT create) the output [File] for a boomerang derived from [sourceRawId].
     * The caller (the video processor) writes the rendered MP4 to this path, then calls
     * [registerBoomerang] to make it visible to the gallery.
     */
    fun allocateBoomerangFile(sourceRawId: Long): File

    /**
     * Register an already-written boomerang [file] (produced at the path from [allocateBoomerangFile]):
     * extract its thumbnail and return its [RecordedVideo] (kind = [VideoKind.BOOMERANG],
     * [RecordedVideo.sourceRawId] = [sourceRawId]). Returns `null` if registration failed.
     *
     * `suspend` + off the main thread (decodes a thumbnail frame).
     */
    suspend fun registerBoomerang(file: File, sourceRawId: Long): RecordedVideo?

    /**
     * Duration of [file] in milliseconds, or `0L` if it cannot be read.
     * `suspend` + off the main thread (`MediaMetadataRetriever` decode).
     */
    suspend fun durationOf(file: File): Long

    /**
     * Returns the recorded clips (raws + boomerangs), newest first. Lazily extracts thumbnails if
     * missing. `suspend` + off the main thread: scans directories and may decode thumbnail frames,
     * which grows with the gallery and would jank/ANR on the UI thread (ANDROID_STANDARDS §9).
     */
    suspend fun loadRecordedVideos(): List<RecordedVideo>

    /** Removes the video file and its thumbnail. `suspend` + off the main thread (file I/O). */
    suspend fun deleteVideo(video: RecordedVideo)
}
