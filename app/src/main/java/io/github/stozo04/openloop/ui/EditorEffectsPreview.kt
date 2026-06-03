package io.github.stozo04.openloop.ui

import io.github.stozo04.openloop.media.VideoFilter

/**
 * Whether the editor should call ExoPlayer [androidx.media3.exoplayer.ExoPlayer.setVideoEffects] for
 * [filter]. Disabled after reverse preview failure or low-memory signals to avoid stacking GL/native
 * retention on an already stressed heap.
 */
fun shouldApplyVideoEffectsPreview(
    effectsPreviewEnabled: Boolean,
    filter: VideoFilter,
): Boolean = effectsPreviewEnabled && filter.toMediaEffects().isNotEmpty()
