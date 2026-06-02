package io.github.stozo04.openloop.media

import android.media.MediaFormat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReverseNormalizeTest {

    @Test
    fun `HEVC mime requires normalize`() {
        assertTrue(needsReverseNormalize(MediaFormat.MIMETYPE_VIDEO_HEVC, null))
    }

    @Test
    fun `HDR HLG transfer requires normalize`() {
        assertTrue(
            needsReverseNormalize(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                MediaFormat.COLOR_TRANSFER_HLG,
            ),
        )
    }

    @Test
    fun `HDR PQ transfer requires normalize`() {
        assertTrue(
            needsReverseNormalize(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                MediaFormat.COLOR_TRANSFER_ST2084,
            ),
        )
    }

    @Test
    fun `SDR H264 does not require normalize`() {
        assertFalse(
            needsReverseNormalize(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
            ),
        )
    }

    @Test
    fun `SDR H264 without color metadata does not require normalize`() {
        assertFalse(needsReverseNormalize(MediaFormat.MIMETYPE_VIDEO_AVC, null))
    }
}
