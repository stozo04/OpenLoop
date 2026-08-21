package io.github.stozo04.openloop.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

/**
 * JVM tests for the pure booth-strip geometry (docs/PRD-photo-booth.md §5.2). The D3 regression
 * these lock in: the square crop of a tall viewfinder grab must be **top-biased**, because a
 * geometric center crop clips the crown and every lens that sits above the eyes.
 */
class BoothStripLayoutTest {

    // ── Crop window (PRD D3) ──

    @Test
    fun `tall source crops a top-biased square, not a centered one`() {
        // 1080×2340 — the 9:19.5 viewfinder grab the PRD names.
        val crop = boothCropRect(1080, 2340)
        assertEquals(1080, crop.width)
        assertEquals(1080, crop.height)
        assertEquals(0, crop.left)
        // BOOTH_TOP_BIAS puts a quarter of the cropped-away slack above the window: 1260 × 0.25.
        assertEquals(315, crop.top)
        // The crop the PRD forbids: vertical center ((2340−1080)/2 = 630) clips the crown.
        val centeredTop = (2340 - 1080) / 2
        assertNotEquals(centeredTop, crop.top)
        assertTrue("crop window must sit above center", crop.top < centeredTop)
    }

    @Test
    fun `wide source crops a horizontally centered square`() {
        // Sideways there is no crown to protect — the long-axis crop centers.
        assertEquals(BoothRect(630, 0, 1080, 1080), boothCropRect(2340, 1080))
    }

    @Test
    fun `square source is not cropped`() {
        assertEquals(BoothRect(0, 0, 720, 720), boothCropRect(720, 720))
    }

    @Test
    fun `crop stays inside the source bounds across shapes`() {
        val shapes = listOf(1080 to 2340, 2340 to 1080, 720 to 1280, 1 to 1000, 1000 to 1, 3 to 3)
        for ((w, h) in shapes) {
            val crop = boothCropRect(w, h)
            assertTrue("origin non-negative for ${w}x$h", crop.left >= 0 && crop.top >= 0)
            assertTrue("right edge inside ${w}x$h", crop.left + crop.width <= w)
            assertTrue("bottom edge inside ${w}x$h", crop.top + crop.height <= h)
            assertEquals("crop must be square for ${w}x$h", crop.width, crop.height)
        }
    }

    // ── Strip geometry (PRD §5.2 rough numbers at a 1080² frame) ──

    @Test
    fun `strip at a 1080 frame matches the PRD geometry`() {
        val layout = boothStripLayout(1080)
        assertEquals(32, layout.borderPx)
        assertEquals(160, layout.footerHeightPx)
        assertEquals(1144, layout.width) // 1080 + 2×32
        assertEquals(3528, layout.height) // 3×1080 + 4×32 + 160
        assertEquals(
            listOf(
                BoothRect(32, 32, 1080, 1080),
                BoothRect(32, 1144, 1080, 1080),
                BoothRect(32, 2256, 1080, 1080),
            ),
            layout.frameRects,
        )
    }

    @Test
    fun `borders separate every frame and the footer fills the bottom, at any frame size`() {
        for (frameSize in listOf(540, 720, 1080, 1440)) {
            val layout = boothStripLayout(frameSize)
            assertEquals(frameSize + 2 * layout.borderPx, layout.width)
            layout.frameRects.zipWithNext { above, below ->
                assertEquals(
                    "one border between frames at $frameSize",
                    layout.borderPx,
                    below.top - (above.top + above.height),
                )
            }
            val last = layout.frameRects.last()
            assertEquals(
                "footer band starts one border below the last frame at $frameSize",
                layout.height - layout.footerHeightPx,
                last.top + last.height + layout.borderPx,
            )
        }
    }

    // ── Footer print (PRD D6) ──

    @Test
    fun `footer prints the wordmark and the capture date`() {
        assertEquals(
            "OPENLOOP · AUG 20 2026",
            boothFooterText(LocalDate.of(2026, 8, 20), Locale.US),
        )
    }
}
