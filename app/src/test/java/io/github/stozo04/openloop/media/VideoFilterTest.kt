package io.github.stozo04.openloop.media

import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(UnstableApi::class)
class VideoFilterTest {

    @Test
    fun original_hasNoEffects() {
        assertTrue(VideoFilter.ORIGINAL.toMediaEffects().isEmpty())
    }

    @Test
    fun playfulLooks_arePresent() {
        val names = VideoFilter.entries.map { it.name }.toSet()
        listOf("INVERT", "SEPIA", "PARTY", "PUNCH", "GLOW", "FADE", "MINT", "CANDY").forEach {
            assertTrue("$it missing from VideoFilter", it in names)
        }
        assertEquals(13, VideoFilter.entries.size)
    }

    @Test
    fun nonGlLooks_emitEffectsOnJvm() {
        // RgbAdjustment touches android.opengl.Matrix (not mocked on plain JVM). These paths don't.
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
}
