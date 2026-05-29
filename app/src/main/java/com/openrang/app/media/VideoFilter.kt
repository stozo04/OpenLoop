package com.openrang.app.media

import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.RgbAdjustment
import androidx.media3.effect.RgbFilter

/**
 * A one-tap color "look" applied to the boomerang (slice 05). Each value owns the parameters its
 * look is built from, so the Media3 render/preview effect ([toMediaEffects]) and the editor's chip
 * thumbnail (a Compose `ColorMatrix` derived from the *same* numbers in `BoomerangEditorScreen`)
 * stay visually in sync — the chip can't lie about the export.
 *
 * Parameters (all default to "no change", i.e. [ORIGINAL]):
 *  - [redScale] / [blueScale]: per-channel multipliers for warm / cool tints
 *    (`RgbAdjustment`, verified `RgbMatrix` API in Media3 1.10.1).
 *  - [saturation]: HSL saturation adjustment in `[-100, 100]` for the vibrant "pop" look
 *    (`HslAdjustment.adjustSaturation`).
 *  - [grayscale]: `true` → full desaturate via `RgbFilter.createGrayscaleFilter()`.
 *
 * Speed (a player-side effect / `SpeedChangeEffect`) and a look compose cleanly: at render both go
 * in the `videoEffects` list; in the preview the look rides `ExoPlayer.setVideoEffects` while speed
 * stays `setPlaybackSpeed`. A look does **not** touch the cached reversed clip or the duration.
 */
enum class VideoFilter(
    val label: String,
    val redScale: Float = 1f,
    val blueScale: Float = 1f,
    val saturation: Float = 0f,
    val grayscale: Boolean = false,
) {
    ORIGINAL("Original"),
    NOIR("B&W", grayscale = true),
    WARM("Warm", redScale = 1.15f, blueScale = 0.85f),
    COOL("Cool", redScale = 0.85f, blueScale = 1.15f),
    POP("Pop", saturation = 40f),
    ;

    /**
     * Media3 video effects for this look — the same objects used for the live preview
     * (`ExoPlayer.setVideoEffects`) and the render (`Composition` `videoEffects`). [ORIGINAL] is the
     * identity look, so it contributes no effects.
     */
    @UnstableApi
    fun toMediaEffects(): List<Effect> = when {
        grayscale -> listOf(RgbFilter.createGrayscaleFilter())
        saturation != 0f -> listOf(HslAdjustment.Builder().adjustSaturation(saturation).build())
        redScale != 1f || blueScale != 1f ->
            listOf(RgbAdjustment.Builder().setRedScale(redScale).setBlueScale(blueScale).build())
        else -> emptyList()
    }
}
