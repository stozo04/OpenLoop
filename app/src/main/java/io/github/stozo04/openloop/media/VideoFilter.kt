package io.github.stozo04.openloop.media

import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.RgbAdjustment
import androidx.media3.effect.RgbFilter
import androidx.media3.effect.RgbMatrix

/**
 * A one-tap color "look" applied to the boomerang (slice 05). [toMediaEffects] drives live preview
 * and export; the Looks-tab chip thumbnails approximate the same numbers in
 * `FilterTabPanel` so the chip can't lie about the export.
 *
 * Speed (player-side / `SpeedChangeEffect`) and a look compose cleanly. A look does **not** touch
 * the cached reversed clip or the output duration.
 */
enum class VideoFilter(val label: String) {
    ORIGINAL("Original"),
    NOIR("B&W"),
    WARM("Warm"),
    COOL("Cool"),
    POP("Pop"),
    INVERT("Invert"),
    SEPIA("Sepia"),
    PARTY("Party"),
    PUNCH("Punch"),
    GLOW("Glow"),
    FADE("Fade"),
    MINT("Mint"),
    CANDY("Candy"),
    ;

    /**
     * Media3 video effects for this look — live preview (`ExoPlayer.setVideoEffects`) and render
     * (`Composition` `videoEffects`). [ORIGINAL] contributes none.
     *
     * Prefer [HslAdjustment]/[Contrast]/[RgbAdjustment]/[RgbFilter] over
     * [androidx.media3.effect.Brightness] — Brightness asserts non-HDR and would break imported
     * HDR clips.
     */
    @UnstableApi
    fun toMediaEffects(): List<Effect> = when (this) {
        ORIGINAL -> emptyList()
        NOIR -> listOf(RgbFilter.createGrayscaleFilter())
        WARM -> listOf(RgbAdjustment.Builder().setRedScale(1.15f).setBlueScale(0.85f).build())
        COOL -> listOf(RgbAdjustment.Builder().setRedScale(0.85f).setBlueScale(1.15f).build())
        POP -> listOf(HslAdjustment.Builder().adjustSaturation(40f).build())
        INVERT -> listOf(RgbFilter.createInvertedFilter())
        SEPIA -> listOf(SepiaRgbMatrix)
        PARTY -> listOf(HslAdjustment.Builder().adjustHue(140f).build())
        PUNCH -> listOf(Contrast(0.45f))
        GLOW -> listOf(HslAdjustment.Builder().adjustLightness(18f).build())
        FADE -> listOf(
            Contrast(-0.35f),
            HslAdjustment.Builder().adjustSaturation(-25f).adjustLightness(8f).build(),
        )
        MINT -> listOf(
            RgbAdjustment.Builder().setRedScale(0.88f).setGreenScale(1.18f).setBlueScale(1.05f).build(),
        )
        CANDY -> listOf(
            RgbAdjustment.Builder().setRedScale(1.22f).setGreenScale(0.88f).setBlueScale(1.15f).build(),
        )
    }
}

/** Classic sepia as a column-major 4×4 RGB matrix (Media3 / OpenGL layout). */
@UnstableApi
private object SepiaRgbMatrix : RgbMatrix {
    private val matrix = floatArrayOf(
        0.393f, 0.349f, 0.272f, 0f,
        0.769f, 0.686f, 0.534f, 0f,
        0.189f, 0.168f, 0.131f, 0f,
        0f, 0f, 0f, 1f,
    )

    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray = matrix
}
