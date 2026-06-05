package io.github.stozo04.openloop.ui

import io.github.stozo04.openloop.media.PRE_REVERSE_CODEC_SETTLE
import io.github.stozo04.openloop.media.SAMSUNG_CODEC_CONTENTION_RETRY
import io.github.stozo04.openloop.media.SAMSUNG_REVERSE_PASS_MAX_ATTEMPTS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

class EditorPlaylistBindTest {

    @Test
    fun `shouldHoldPlaylist while reverse preview is loading`() {
        assertTrue(EditorPlaylistBind.shouldHoldPlaylist(reversePreviewLoading = true))
        assertFalse(EditorPlaylistBind.shouldHoldPlaylist(reversePreviewLoading = false))
    }

    @Test
    fun `shouldClearPlaylist when item list is empty`() {
        assertTrue(EditorPlaylistBind.shouldClearPlaylist(itemsEmpty = true))
        assertFalse(EditorPlaylistBind.shouldClearPlaylist(itemsEmpty = false))
    }

    @Test
    fun `playlist debounce is within spec range`() {
        assertTrue(EditorPlaylistBind.PLAYLIST_DEBOUNCE in 100.milliseconds..200.milliseconds)
    }

    @Test
    fun `reverse preview requires player epoch bump not stop alone`() {
        assertTrue(EditorPlaylistBind.requiresPlayerEpochBumpForReversePreview())
    }

    @Test
    fun `samsung contention retry constants are ordered`() {
        assertTrue(SAMSUNG_CODEC_CONTENTION_RETRY >= PRE_REVERSE_CODEC_SETTLE)
        assertEquals(2, SAMSUNG_REVERSE_PASS_MAX_ATTEMPTS)
    }
}
