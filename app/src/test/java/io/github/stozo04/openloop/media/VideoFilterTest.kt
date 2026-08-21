package io.github.stozo04.openloop.media

import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Propagation (matches KeyframeSpeedProviderTest / SpeedCurveDurationTest) rather than @OptIn:
// kotlin @OptIn warns "has no effect" against media3's Java-declared opt-in marker.
@UnstableApi
class VideoFilterTest {

    @Test
    fun original_hasNoEffects() {
        assertTrue(VideoFilter.ORIGINAL.toMediaEffects().isEmpty())
    }

    @Test
    fun playfulLooks_arePresentAlongsideBaseline() {
        val names = VideoFilter.entries.map { it.name }
        assertEquals(
            listOf(
                "ORIGINAL", "NOIR", "WARM", "COOL", "POP",
                "INVERT", "SEPIA", "PARTY", "PUNCH", "GLOW", "FADE", "MINT", "CANDY",
            ),
            names,
        )
    }

    @Test
    fun nonRgbAdjustmentLooks_emitEffectsOnJvm() {
        // RgbAdjustment touches android.opengl.Matrix (not available on plain JVM). Paths that
        // only use RgbFilter / HslAdjustment / Contrast / RgbMatrix are safe here.
        listOf(
            VideoFilter.NOIR,
            VideoFilter.POP,
            VideoFilter.INVERT,
            VideoFilter.SEPIA,
            VideoFilter.PARTY,
            VideoFilter.PUNCH,
            VideoFilter.GLOW,
            VideoFilter.FADE,
        ).forEach { look ->
            assertTrue(look.name, look.toMediaEffects().isNotEmpty())
        }
    }

    @Test
    fun toMediaEffects_neverUsesBrightnessClass() {
        // Guard against regressions that reintroduce Brightness (HDR assert in Media3 1.10.1).
        // Skip RgbAdjustment looks — they touch android.opengl.Matrix on construction (JVM-only).
        listOf(
            VideoFilter.ORIGINAL,
            VideoFilter.NOIR,
            VideoFilter.POP,
            VideoFilter.INVERT,
            VideoFilter.SEPIA,
            VideoFilter.PARTY,
            VideoFilter.PUNCH,
            VideoFilter.GLOW,
            VideoFilter.FADE,
        ).forEach { look ->
            look.toMediaEffects().forEach { effect ->
                assertTrue(
                    "${look.name} must not use Brightness (HDR-unsafe)",
                    effect.javaClass.name != "androidx.media3.effect.Brightness",
                )
            }
        }
    }
}
