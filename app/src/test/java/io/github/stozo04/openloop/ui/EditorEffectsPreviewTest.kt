package io.github.stozo04.openloop.ui

import io.github.stozo04.openloop.media.VideoFilter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorEffectsPreviewTest {

    @Test
    fun `ORIGINAL never requests video effects`() {
        assertFalse(shouldApplyVideoEffectsPreview(effectsPreviewEnabled = true, filter = VideoFilter.ORIGINAL))
        assertFalse(shouldApplyVideoEffectsPreview(effectsPreviewEnabled = false, filter = VideoFilter.ORIGINAL))
    }

    @Test
    fun `non-original looks require effects preview enabled`() {
        assertTrue(
            shouldApplyVideoEffectsPreview(effectsPreviewEnabled = true, filter = VideoFilter.POP),
        )
        assertFalse(
            shouldApplyVideoEffectsPreview(effectsPreviewEnabled = false, filter = VideoFilter.POP),
        )
    }
}
