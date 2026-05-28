package com.openrang.app.data

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import java.io.File

/**
 * Production implementation of [VideoStorageRepository].
 *
 * Holds only raw [File] handles ([cacheDir] / [filesDir]) — never a
 * [android.content.Context]. The [OpenRangViewModel][com.openrang.app.ui.OpenRangViewModel]'s
 * `Factory` bridges Context to these paths once, in `MainActivity`.
 *
 * File logic mirrors the original ViewModel implementation:
 * - finalized clips: `filesDir/videos/clip_<timestamp>.mp4`
 * - thumbnails:      `filesDir/thumbnails/clip_<timestamp>.jpg` (JPEG, 90% quality)
 * - scratch capture: `cacheDir/raw_capture.mp4` (overwritten each capture)
 */
class VideoStorageRepositoryImpl(
    private val cacheDir: File,
    private val filesDir: File,
) : VideoStorageRepository {

    private val videosDir = File(filesDir, "videos")
    private val thumbnailsDir = File(filesDir, "thumbnails")

    override val rawCaptureFile: File = File(cacheDir, "raw_capture.mp4")

    override fun saveFinalizedVideo(rawCapture: File): File? {
        val timestamp = System.currentTimeMillis()
        val videoDir = videosDir.apply { mkdirs() }
        val thumbDir = thumbnailsDir.apply { mkdirs() }

        val destVideo = File(videoDir, "clip_$timestamp.mp4")
        val destThumb = File(thumbDir, "clip_$timestamp.jpg")

        return try {
            rawCapture.copyTo(destVideo, overwrite = true)

            // Extract thumbnail using MediaMetadataRetriever
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(destVideo.absolutePath)
            val bitmap = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            if (bitmap != null) {
                destThumb.outputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
            }
            retriever.release()
            destVideo
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save video loop persistent copy", e)
            null
        }
    }

    override fun loadRecordedVideos(): List<RecordedVideo> {
        if (!videosDir.exists()) {
            return emptyList()
        }

        val files = videosDir.listFiles { _, name -> name.startsWith("clip_") && name.endsWith(".mp4") }
            ?: return emptyList()

        return files.mapNotNull { file ->
            val name = file.name
            val timestampStr = name.removePrefix("clip_").removeSuffix(".mp4")
            val id = timestampStr.toLongOrNull() ?: 0L

            val thumbFile = File(thumbnailsDir, "clip_$timestampStr.jpg")
            if (thumbFile.exists()) {
                RecordedVideo(id, file.absolutePath, thumbFile.absolutePath)
            } else {
                // If thumbnail doesn't exist, extract it now on demand!
                try {
                    thumbnailsDir.mkdirs()
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(file.absolutePath)
                    val bitmap = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    if (bitmap != null) {
                        thumbFile.outputStream().use { out ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                        }
                    }
                    retriever.release()
                    RecordedVideo(id, file.absolutePath, thumbFile.absolutePath)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to extract on-demand thumbnail for ${file.name}", e)
                    null
                }
            }
        }.sortedByDescending { it.id } // Newest first
    }

    override fun deleteVideo(video: RecordedVideo) {
        try {
            val videoFile = File(video.videoPath)
            if (videoFile.exists()) {
                videoFile.delete()
            }
            val thumbFile = File(video.thumbnailPath)
            if (thumbFile.exists()) {
                thumbFile.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete video ${video.id}", e)
        }
    }

    private companion object {
        const val TAG = "VideoStorageRepository"
    }
}
