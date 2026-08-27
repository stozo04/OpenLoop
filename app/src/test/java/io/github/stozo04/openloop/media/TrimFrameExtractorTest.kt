package io.github.stozo04.openloop.media

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * JVM tests for the Trim filmstrip decode-size contract (Issue #149): frames are decoded at tile
 * size, not source size. The box math is pure; the retriever contract is pinned through a mocked
 * [MediaMetadataRetriever] constructor (the same seam as `VideoStorageRepositoryImplTest`).
 *
 * The box is what gets *requested*, never the returned bitmap's exact size — the platform's own
 * fit rounds by a pixel.
 */
class TrimFrameExtractorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @After
    fun tearDown() = unmockkAll()

    private companion object {
        /** A 48 dp × 56 dp tile at the Pixel 8's 2.625× density. */
        const val TILE_W = 126
        const val TILE_H = 147
    }

    // ── filmstripDecodeBox: pure ──────────────────────────────────────────────────────────────

    @Test
    fun `a 4K landscape source decodes to the smallest box that still covers the tile`() {
        // Height is the limiting axis (147 / 2160 > 126 / 3840): 3840 × 147 / 2160 = 261.3 → 262.
        assertEquals(262 to 147, filmstripDecodeBox(3840, 2160, 0, TILE_W, TILE_H))
    }

    @Test
    fun `a clip stored with a rotation hint is fitted upright`() {
        // 1920×1080 + rotation 90 displays as 1080×1920: width limits, 126 × 1920 / 1080 = 224.
        assertEquals(126 to 224, filmstripDecodeBox(1920, 1080, 90, TILE_W, TILE_H))
        assertEquals(126 to 224, filmstripDecodeBox(1920, 1080, 270, TILE_W, TILE_H))
        assertEquals(262 to 147, filmstripDecodeBox(1920, 1080, 180, TILE_W, TILE_H))
    }

    @Test
    fun `a source no bigger than the tile on either axis is never asked to upscale`() {
        assertEquals(100 to 60, filmstripDecodeBox(100, 60, 0, TILE_W, TILE_H))
        assertEquals(126 to 147, filmstripDecodeBox(126, 147, 0, TILE_W, TILE_H))
        assertEquals(5000 to 100, filmstripDecodeBox(5000, 100, 0, TILE_W, TILE_H))
    }

    @Test
    fun `unknown source dimensions fall back to the tile box and a zero tile is still legal`() {
        assertEquals(TILE_W to TILE_H, filmstripDecodeBox(0, 0, 0, TILE_W, TILE_H))
        assertEquals(TILE_W to TILE_H, filmstripDecodeBox(-1, 2160, 0, TILE_W, TILE_H))
        // The retriever throws on a non-positive box — a 0 px tile (pre-layout) must still yield ≥ 1.
        assertEquals(2 to 1, filmstripDecodeBox(3840, 2160, 0, 0, 0))
        assertEquals(1 to 1, filmstripDecodeBox(0, 0, 0, 0, 0))
    }

    @Test
    fun `the box always covers the tile and never exceeds the upright source`() {
        for (srcW in intArrayOf(1, 60, 126, 640, 1080, 1920, 3840, 7680)) {
            for (srcH in intArrayOf(1, 56, 147, 480, 1080, 1920, 2160, 4320)) {
                val (w, h) = filmstripDecodeBox(srcW, srcH, 0, TILE_W, TILE_H)
                val coversOrIsNative = (w >= TILE_W && h >= TILE_H) || (w == srcW && h == srcH)
                assert(coversOrIsNative) { "$srcW×$srcH → $w×$h neither covers the tile nor is native" }
                assert(w <= srcW && h <= srcH) { "$srcW×$srcH → $w×$h asks for an upscale" }
            }
        }
    }

    // ── extractTrimFilmstripFrames: the retriever contract ────────────────────────────────────

    private fun clip(): File = tempFolder.newFile("clip.mp4")

    private fun mockRetriever(width: String? = "3840", height: String? = "2160", rotation: String? = "0") {
        mockkConstructor(MediaMetadataRetriever::class)
        every { anyConstructed<MediaMetadataRetriever>().setDataSource(any<String>()) } just Runs
        every {
            anyConstructed<MediaMetadataRetriever>().extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
        } returns width
        every {
            anyConstructed<MediaMetadataRetriever>().extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
        } returns height
        every {
            anyConstructed<MediaMetadataRetriever>().extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
        } returns rotation
        every { anyConstructed<MediaMetadataRetriever>().release() } just Runs
    }

    @Test
    fun `on API 27 and up every frame is requested through getScaledFrameAtTime at the cover box`() {
        mockRetriever()
        val tile = mockk<Bitmap>()
        every {
            anyConstructed<MediaMetadataRetriever>().getScaledFrameAtTime(any(), any(), any(), any())
        } returns tile

        val frames = extractTrimFilmstripFrames(
            clip(), durationMs = 6_000L, frameCount = 3, tileWidthPx = TILE_W, tileHeightPx = TILE_H, sdkInt = 27,
        )

        assertEquals(listOf(tile, tile, tile), frames)
        for (timeUs in longArrayOf(0L, 3_000_000L, 6_000_000L)) {
            verify(exactly = 1) {
                anyConstructed<MediaMetadataRetriever>()
                    .getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 262, 147)
            }
        }
        verify(exactly = 0) { anyConstructed<MediaMetadataRetriever>().getFrameAtTime(any(), any()) }
        verify(exactly = 1) { anyConstructed<MediaMetadataRetriever>().release() }
    }

    @Test
    fun `on API 26 the full-size frame is shrunk to the cover box and recycled`() {
        mockRetriever()
        val full = mockk<Bitmap> {
            every { width } returns 3840
            every { height } returns 2160
            every { recycle() } just Runs
        }
        val shrunk = mockk<Bitmap>()
        every { anyConstructed<MediaMetadataRetriever>().getFrameAtTime(any(), any()) } returns full
        mockkStatic(Bitmap::class)
        every { Bitmap.createScaledBitmap(full, 262, 147, true) } returns shrunk

        val frames = extractTrimFilmstripFrames(
            clip(), durationMs = 6_000L, frameCount = 2, tileWidthPx = TILE_W, tileHeightPx = TILE_H, sdkInt = 26,
        )

        assertEquals(listOf(shrunk, shrunk), frames)
        verify(exactly = 2) { full.recycle() }
        verify(exactly = 0) {
            anyConstructed<MediaMetadataRetriever>().getScaledFrameAtTime(any(), any(), any(), any())
        }
    }

    @Test
    fun `on API 26 a frame already within the box is kept as-is and not recycled`() {
        mockRetriever(width = "100", height = "60")
        val small = mockk<Bitmap> {
            every { width } returns 100
            every { height } returns 60
        }
        every { anyConstructed<MediaMetadataRetriever>().getFrameAtTime(any(), any()) } returns small
        mockkStatic(Bitmap::class)
        // The platform returns the receiver itself for an unchanged size; the extractor must not recycle it.
        every { Bitmap.createScaledBitmap(small, 100, 60, true) } returns small

        val frames = extractTrimFilmstripFrames(
            clip(), durationMs = 1_000L, frameCount = 1, tileWidthPx = TILE_W, tileHeightPx = TILE_H, sdkInt = 26,
        )

        assertEquals(listOf(small), frames)
        verify(exactly = 0) { small.recycle() }
    }

    @Test
    fun `a frame that fails to decode leaves a null slot without aborting the strip`() {
        mockRetriever()
        val tile = mockk<Bitmap>()
        every {
            anyConstructed<MediaMetadataRetriever>().getScaledFrameAtTime(any(), any(), any(), any())
        } returns tile
        every {
            anyConstructed<MediaMetadataRetriever>().getScaledFrameAtTime(2_000_000L, any(), any(), any())
        } throws IllegalStateException("decoder gave up")

        val frames = extractTrimFilmstripFrames(
            clip(), durationMs = 4_000L, frameCount = 3, tileWidthPx = TILE_W, tileHeightPx = TILE_H, sdkInt = 30,
        )

        assertEquals(listOf(tile, null, tile), frames)
    }

    @Test
    fun `a zero frame count decodes nothing`() {
        assertEquals(emptyList<Bitmap?>(), extractTrimFilmstripFrames(clip(), 1_000L, 0, TILE_W, TILE_H, sdkInt = 30))
    }
}
