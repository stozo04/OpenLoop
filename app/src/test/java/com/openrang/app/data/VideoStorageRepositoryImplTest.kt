package com.openrang.app.data

import android.media.MediaMetadataRetriever
import android.util.Log
import io.mockk.every
import io.mockk.just
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.Runs
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Direct unit tests for [VideoStorageRepositoryImpl] against a real filesystem (lesson 008:
 * use [TemporaryFolder], never mock [File]). Thumbnail extraction uses the Android-only
 * [MediaMetadataRetriever], so those tests mock its constructor — every other assertion is
 * pure filesystem behavior.
 */
class VideoStorageRepositoryImplTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var cacheDir: File
    private lateinit var filesDir: File
    private lateinit var repository: VideoStorageRepositoryImpl

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        cacheDir = tempFolder.newFolder("cache")
        filesDir = tempFolder.newFolder("files")
        repository = VideoStorageRepositoryImpl(cacheDir = cacheDir, filesDir = filesDir)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun videosDir() = File(filesDir, "videos")
    private fun thumbnailsDir() = File(filesDir, "thumbnails")

    /** Pre-creates a clip + matching thumbnail so the lazy MediaMetadataRetriever path is skipped. */
    private fun seedClipWithThumbnail(timestamp: Long) {
        val videos = videosDir().apply { mkdirs() }
        val thumbs = thumbnailsDir().apply { mkdirs() }
        File(videos, "clip_$timestamp.mp4").writeBytes(ByteArray(4))
        File(thumbs, "clip_$timestamp.jpg").writeBytes(ByteArray(4))
    }

    @Test
    fun `rawCaptureFile points at cacheDir raw_capture mp4`() {
        assertEquals(File(cacheDir, "raw_capture.mp4").absolutePath, repository.rawCaptureFile.absolutePath)
    }

    @Test
    fun `loadRecordedVideos returns empty when videos dir is missing`() {
        assertTrue(repository.loadRecordedVideos().isEmpty())
    }

    @Test
    fun `loadRecordedVideos returns clips newest first`() {
        seedClipWithThumbnail(100L)
        seedClipWithThumbnail(300L)
        seedClipWithThumbnail(200L)

        val result = repository.loadRecordedVideos()

        assertEquals(listOf(300L, 200L, 100L), result.map { it.id })
        // Paths resolve to the real seeded files.
        assertTrue(result.all { File(it.videoPath).exists() && File(it.thumbnailPath).exists() })
    }

    @Test
    fun `loadRecordedVideos ignores non-clip files`() {
        val videos = videosDir().apply { mkdirs() }
        thumbnailsDir().mkdirs()
        File(videos, "notes.txt").writeBytes(ByteArray(4))
        File(videos, "clip_123.png").writeBytes(ByteArray(4)) // wrong extension
        seedClipWithThumbnail(123L)

        val result = repository.loadRecordedVideos()

        assertEquals(listOf(123L), result.map { it.id })
    }

    @Test
    fun `loadRecordedVideos still lists a clip whose thumbnail is missing`() {
        // No thumbnail on disk → lazy extraction path runs. Mock the retriever to yield no frame;
        // the clip must still appear in the list (resilience, not a blank gallery slot).
        mockkConstructor(MediaMetadataRetriever::class)
        every { anyConstructed<MediaMetadataRetriever>().setDataSource(any<String>()) } just Runs
        every { anyConstructed<MediaMetadataRetriever>().getFrameAtTime(any(), any()) } returns null
        every { anyConstructed<MediaMetadataRetriever>().release() } just Runs

        File(videosDir().apply { mkdirs() }, "clip_555.mp4").writeBytes(ByteArray(4))

        val result = repository.loadRecordedVideos()

        assertEquals(listOf(555L), result.map { it.id })
    }

    @Test
    fun `deleteVideo removes both the video and its thumbnail`() {
        seedClipWithThumbnail(123L)
        val videoFile = File(videosDir(), "clip_123.mp4")
        val thumbFile = File(thumbnailsDir(), "clip_123.jpg")
        assertTrue(videoFile.exists() && thumbFile.exists())

        repository.deleteVideo(
            RecordedVideo(123L, videoFile.absolutePath, thumbFile.absolutePath)
        )

        assertFalse(videoFile.exists())
        assertFalse(thumbFile.exists())
    }

    @Test
    fun `deleteVideo with already-missing files does not throw`() {
        // Should be a no-op, never crash.
        repository.deleteVideo(
            RecordedVideo(
                id = 999L,
                videoPath = File(videosDir(), "clip_999.mp4").absolutePath,
                thumbnailPath = File(thumbnailsDir(), "clip_999.jpg").absolutePath
            )
        )
        // Reaching here without an exception is the assertion.
        assertTrue(repository.loadRecordedVideos().isEmpty())
    }

    @Test
    fun `saveFinalizedVideo copies the raw capture into persistent storage`() {
        mockkConstructor(MediaMetadataRetriever::class)
        every { anyConstructed<MediaMetadataRetriever>().setDataSource(any<String>()) } just Runs
        every { anyConstructed<MediaMetadataRetriever>().getFrameAtTime(any(), any()) } returns null
        every { anyConstructed<MediaMetadataRetriever>().release() } just Runs

        val source = File(cacheDir, "raw_capture.mp4").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }

        val saved = repository.saveFinalizedVideo(source)

        assertNotNull(saved)
        assertTrue(saved!!.exists())
        assertEquals(videosDir().absolutePath, saved.parentFile?.absolutePath)
        assertTrue(saved.name.startsWith("clip_") && saved.name.endsWith(".mp4"))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), saved.readBytes())
    }
}
