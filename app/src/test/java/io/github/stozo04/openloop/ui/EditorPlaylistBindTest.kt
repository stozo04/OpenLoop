package io.github.stozo04.openloop.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
        assertTrue(EditorPlaylistBind.PLAYLIST_DEBOUNCE_MS in 100L..200L)
    }
}
