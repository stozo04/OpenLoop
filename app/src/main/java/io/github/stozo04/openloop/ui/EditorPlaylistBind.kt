package io.github.stozo04.openloop.ui

import kotlin.time.Duration.Companion.milliseconds

/**
 * Playlist rebind policy for [BoomerangEditorScreen]: debounce rapid trim/mode changes and always tear
 * down the prior [androidx.media3.exoplayer.ExoPlayer] playlist before preparing a new one (reduces
 * stacked ProgressiveMediaPeriod loaders).
 */
object EditorPlaylistBind {

    /** Debounce window for trim / mode / seam rebinding (similar to [SPEED_DEBOUNCE]). */
    val PLAYLIST_DEBOUNCE = 150.milliseconds

    /**
     * When true, the bind job should stop/clear the player and skip prepare until reverse preview
     * loading finishes.
     */
    fun shouldHoldPlaylist(reversePreviewLoading: Boolean): Boolean = reversePreviewLoading

    /**
     * `ExoPlayer.stop()` does not release decoder slots — only `ExoPlayer.release()` does (Media3
     * lifecycle guidance). The screen must bump [BoomerangEditorScreen]'s `playerEpoch` when reverse
     * loading starts so the prior instance is released before VideoReverser pass 1 opens codecs.
     */
    fun requiresPlayerEpochBumpForReversePreview(): Boolean = true

    /**
     * When true, the player should stop and clear items (empty playlist or superseded bind).
     */
    fun shouldClearPlaylist(itemsEmpty: Boolean): Boolean = itemsEmpty
}
