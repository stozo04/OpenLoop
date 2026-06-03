package io.github.stozo04.openloop.ui

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import io.github.stozo04.openloop.media.VideoFilter

/**
 * Whether the editor should call ExoPlayer [androidx.media3.exoplayer.ExoPlayer.setVideoEffects] for
 * [filter]. Disabled after reverse preview failure or low-memory signals to avoid stacking GL/native
 * retention on an already stressed heap.
 */
@OptIn(UnstableApi::class)
fun shouldApplyVideoEffectsPreview(
    effectsPreviewEnabled: Boolean,
    filter: VideoFilter,
): Boolean = effectsPreviewEnabled && filter.toMediaEffects().isNotEmpty()

/**
 * Whether the editor must tear down its ExoPlayer to drop effects that are *already applied* but no
 * longer allowed (PR #58 review: `setVideoEffects` is a player-wide setting that survives
 * stop/clearMediaItems/prepare rebinds, so closing the [shouldApplyVideoEffectsPreview] gate without
 * a teardown leaves DefaultVideoFrameProcessor rendering every frame — exactly the retention WS-3
 * exists to remove).
 *
 * Teardown means **recreating the player instance**: `setVideoEffects(emptyList())` is forbidden
 * (it still routes playback through DefaultVideoFrameProcessor and freezes at the HDR→SDR seam —
 * see the comment in BoomerangEditorScreen), while a fresh player has no frame processor at all.
 *
 * @param playerHasAppliedEffects true once `setVideoEffects` has been called on the current player
 *   instance with a non-empty effect list.
 */
@OptIn(UnstableApi::class)
fun shouldTearDownEffectsPlayer(
    playerHasAppliedEffects: Boolean,
    effectsPreviewEnabled: Boolean,
    filter: VideoFilter,
): Boolean = playerHasAppliedEffects && !shouldApplyVideoEffectsPreview(effectsPreviewEnabled, filter)
