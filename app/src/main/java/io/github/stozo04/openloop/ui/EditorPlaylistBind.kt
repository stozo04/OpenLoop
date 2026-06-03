package io.github.stozo04.openloop.ui

/**
 * Playlist rebind policy for [BoomerangEditorScreen]: debounce rapid trim/mode changes and always tear
 * down the prior [androidx.media3.exoplayer.ExoPlayer] playlist before preparing a new one (reduces
 * stacked ProgressiveMediaPeriod loaders).
 */
object EditorPlaylistBind {

    /** Debounce window for trim / mode / seam rebinding (similar to [SPEED_DEBOUNCE_MS]). */
    const val PLAYLIST_DEBOUNCE_MS = 150L

    /**
     * When true, the bind job should stop/clear the player and skip prepare until reverse preview
     * loading finishes.
     */
    fun shouldHoldPlaylist(reversePreviewLoading: Boolean): Boolean = reversePreviewLoading

    /**
     * When true, the player should stop and clear items (empty playlist or superseded bind).
     */
    fun shouldClearPlaylist(itemsEmpty: Boolean): Boolean = itemsEmpty
}
