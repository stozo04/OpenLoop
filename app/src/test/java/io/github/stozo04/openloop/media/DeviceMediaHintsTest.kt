package io.github.stozo04.openloop.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceMediaHintsTest {

    @Test
    fun samsungPreviewReverseCap_isBelowExportCap() {
        assertEquals(480, SAMSUNG_PREVIEW_REVERSE_MAX_SHORT_SIDE)
        assertEquals(500L, SAMSUNG_POST_TRANSFORM_CODEC_SETTLE_MS)
        assertTrue(PRE_REVERSE_CODEC_SETTLE_MS >= 200L)
        assert(SAMSUNG_PREVIEW_REVERSE_MAX_SHORT_SIDE < MAX_OUTPUT_SHORT_SIDE)
    }
}
