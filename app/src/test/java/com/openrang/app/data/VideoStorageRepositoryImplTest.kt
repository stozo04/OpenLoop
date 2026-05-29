package com.openrang.app.data

import android.media.MediaMetadataRetriever
import android.util.Log
import io.mockk.every
import io.mockk.just
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.Runs
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    /** Mocks the MediaMetadataRetriever constructor so thumbnail extraction / duration are deterministic. */
    private fun mockRetriever(durationMs: String? = "1234") {
        mockkConstructor(MediaMetadataRetriever::class)
        every { anyConstructed<MediaMetadataRetriever>().setDataSource(any<String>()) } just Runs
        every { anyConstructed<MediaMetadataRetriever>().getFrameAtTime(any(), any()) } returns null
        every {
            anyConstructed<MediaMetadataRetriever>().extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
        } returns durationMs
        every { anyConstructed<MediaMetadataRetriever>().release() } just Runs
    }

    @Test
    fun `createScratchCapture returns a unique cacheDir scratch file that does not yet exist`() {
        val a = repository.createScratchCapture()
        val b = repository.createScratchCapture()

        assertEquals(File(cacheDir, "scratch").absolutePath, a.file.parentFile?.absolutePath)
        assertTrue(a.file.name.startsWith("raw_") && a.file.name.endsWith(".mp4"))
        assertFalse(a.file.exists()) // the camera creates it later by recording into it
        assertNotEquals(a.uuid, b.uuid)
    }

    @Test
    fun `promoteScratchToRaw copies the scratch into videos and reports a RAW`() = runBlocking {
        mockRetriever()
        val scratch = repository.createScratchCapture()
        scratch.file.parentFile?.mkdirs()
        scratch.file.writeBytes(byteArrayOf(1, 2, 3, 4))

        val raw = repository.promoteScratchToRaw(scratch)

        assertNotNull(raw)
        assertEquals(VideoKind.RAW, raw!!.kind)
        assertNull(raw.sourceRawId)
        val dest = File(raw.videoPath)
        assertTrue(dest.exists())
        assertEquals(videosDir().absolutePath, dest.parentFile?.absolutePath)
        assertTrue(dest.name.startsWith("clip_") && dest.name.endsWith(".mp4"))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), dest.readBytes())
    }

    @Test
    fun `discardScratch deletes the file and is idempotent`() {
        val scratch = repository.createScratchCapture()
        scratch.file.parentFile?.mkdirs()
        scratch.file.writeBytes(ByteArray(4))
        assertTrue(scratch.file.exists())

        repository.discardScratch(scratch)
        assertFalse(scratch.file.exists())
        // Second call on an already-missing file must not throw.
        repository.discardScratch(scratch)
        assertFalse(scratch.file.exists())
    }

    @Test
    fun `allocateBoomerangFile names the file under boomerangs encoding the source raw id`() {
        val file = repository.allocateBoomerangFile(sourceRawId = 777L)

        assertEquals(File(filesDir, "boomerangs").absolutePath, file.parentFile?.absolutePath)
        assertTrue(file.name.startsWith("boom_") && file.name.endsWith("_from_777.mp4"))
        assertFalse(file.exists())
    }

    @Test
    fun `allocateBoomerangFile mints unique filenames even within the same millisecond`() {
        // A tight loop hits same-wall-clock-ms collisions; the monotonic id generator must still
        // hand out distinct timestamps, or two boomerangs saved together would overwrite each other.
        val names = (0 until 1_000).map { repository.allocateBoomerangFile(sourceRawId = 42L).name }

        assertEquals(names.size, names.toSet().size) // all unique
    }

    @Test
    fun `registerBoomerang reports a BOOMERANG carrying its source raw id`() = runBlocking {
        mockRetriever()
        val file = repository.allocateBoomerangFile(sourceRawId = 777L)
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(4))

        val boomerang = repository.registerBoomerang(file, sourceRawId = 777L)

        assertNotNull(boomerang)
        assertEquals(VideoKind.BOOMERANG, boomerang!!.kind)
        assertEquals(777L, boomerang.sourceRawId)
        assertEquals(file.absolutePath, boomerang.videoPath)
    }

    @Test
    fun `durationOf reads the media duration metadata`() = runBlocking {
        mockRetriever(durationMs = "2500")
        val f = File(cacheDir, "any.mp4").apply { writeBytes(ByteArray(4)) }

        assertEquals(2500L, repository.durationOf(f))
    }

    @Test
    fun `loadRecordedVideos includes boomerangs and parses the source raw id`() = runBlocking {
        // Seed a raw + a boomerang (both with thumbnails so the lazy retriever path is skipped).
        val videos = videosDir().apply { mkdirs() }
        val booms = File(filesDir, "boomerangs").apply { mkdirs() }
        val thumbs = thumbnailsDir().apply { mkdirs() }
        File(videos, "clip_100.mp4").writeBytes(ByteArray(4))
        File(thumbs, "clip_100.jpg").writeBytes(ByteArray(4))
        File(booms, "boom_200_from_100.mp4").writeBytes(ByteArray(4))
        File(thumbs, "boom_200_from_100.jpg").writeBytes(ByteArray(4))

        val result = repository.loadRecordedVideos()

        assertEquals(listOf(200L, 100L), result.map { it.id }) // newest first
        val raw = result.single { it.kind == VideoKind.RAW }
        val boomerang = result.single { it.kind == VideoKind.BOOMERANG }
        assertEquals(100L, raw.id)
        assertNull(raw.sourceRawId)
        assertEquals(200L, boomerang.id)
        assertEquals(100L, boomerang.sourceRawId)
    }

    @Test
    fun `loadRecordedVideos returns empty when videos dir is missing`() = runBlocking {
        assertTrue(repository.loadRecordedVideos().isEmpty())
    }

    @Test
    fun `loadRecordedVideos returns clips newest first`() = runBlocking {
        seedClipWithThumbnail(100L)
        seedClipWithThumbnail(300L)
        seedClipWithThumbnail(200L)

        val result = repository.loadRecordedVideos()

        assertEquals(listOf(300L, 200L, 100L), result.map { it.id })
        // Paths resolve to the real seeded files.
        assertTrue(result.all { File(it.videoPath).exists() && File(it.thumbnailPath).exists() })
    }

    @Test
    fun `loadRecordedVideos ignores non-clip files`() = runBlocking {
        val videos = videosDir().apply { mkdirs() }
        thumbnailsDir().mkdirs()
        File(videos, "notes.txt").writeBytes(ByteArray(4))
        File(videos, "clip_123.png").writeBytes(ByteArray(4)) // wrong extension
        seedClipWithThumbnail(123L)

        val result = repository.loadRecordedVideos()

        assertEquals(listOf(123L), result.map { it.id })
    }

    @Test
    fun `loadRecordedVideos still lists a clip whose thumbnail is missing`() = runBlocking {
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
    fun `deleteVideo removes both the video and its thumbnail`() = runBlocking {
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
    fun `deleteVideo with already-missing files does not throw`() = runBlocking {
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

}
