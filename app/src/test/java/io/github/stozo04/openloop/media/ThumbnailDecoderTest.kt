package io.github.stozo04.openloop.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailDecoderTest {

    @Test
    fun `computeInSampleSize returns 1 when already within cap`() {
        assertEquals(1, ThumbnailDecoder.computeInSampleSize(200, 150))
        assertEquals(1, ThumbnailDecoder.computeInSampleSize(256, 144))
    }

    @Test
    fun `computeInSampleSize downsamples 4000x3000 toward 256px long edge`() {
        val sample = ThumbnailDecoder.computeInSampleSize(4000, 3000)
        assertTrue(sample >= 8)
        assertTrue(4000 / sample <= ThumbnailDecoder.MAX_LONG_EDGE_PX)
        assertTrue(3000 / sample <= ThumbnailDecoder.MAX_LONG_EDGE_PX)
    }

    @Test
    fun `computeInSampleSize uses long edge for portrait`() {
        val sample = ThumbnailDecoder.computeInSampleSize(1080, 4000)
        assertTrue(4000 / sample <= ThumbnailDecoder.MAX_LONG_EDGE_PX)
    }
}
