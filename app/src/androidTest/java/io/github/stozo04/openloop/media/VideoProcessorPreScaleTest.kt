package io.github.stozo04.openloop.media

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Issue #41 Tier 1A regression — pre-scale via Media3 Transformer before reverse (Lesson 021: never
 * downscale inside [VideoReverser]'s decode→encode Surface path).
 */
@RunWith(AndroidJUnit4::class)
class VideoProcessorPreScaleTest {

    private lateinit var context: Context
    private lateinit var scratchDir: File
    private lateinit var processor: Media3VideoProcessor
    private lateinit var above1080Fixture: File
    private var durationMs: Long = 0L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        scratchDir = File(context.cacheDir, "prescale_test_${System.nanoTime()}").apply { mkdirs() }
        processor = Media3VideoProcessor(
            context = context,
            reverser = VideoReverser(File(scratchDir, "reversed")),
        )
        // Short side 1280 (> MAX_OUTPUT_SHORT_SIDE) — first dimension combo that exercises pre-scale.
        above1080Fixture = File(scratchDir, "fixture_1920x1280.mp4")
        val ok = SyntheticVideoFixtures.generateLumaRampClip(
            dest = above1080Fixture,
            width = 1920,
            height = 1280,
            frameCount = 12,
            fps = 12,
        )
        assumeTrue("synthetic >1080p fixture unavailable on this device", ok)
        durationMs = SyntheticVideoFixtures.durationMs(above1080Fixture)
        assumeTrue("fixture has no readable duration", durationMs > 0L)
        assumeTrue(
            "fixture must exceed 1080p short side to exercise pre-scale",
            SyntheticVideoFixtures.videoShortSide(above1080Fixture) > MAX_OUTPUT_SHORT_SIDE,
        )
    }

    @After
    fun tearDown() {
        scratchDir.deleteRecursively()
    }

    @Test
    fun scaleSourceForReverse_capsShortSide_at1080() = runBlocking {
        val scaled = processor.scaleSourceForReverse(above1080Fixture, 0L, durationMs)

        assertTrue("scaled output should exist", scaled.exists())
        assertTrue("scaled output should be non-empty", scaled.length() > 0L)
        val shortSide = SyntheticVideoFixtures.videoShortSide(scaled)
        assertTrue("scaled short side was $shortSide; expected ≤ $MAX_OUTPUT_SHORT_SIDE", shortSide in 1..MAX_OUTPUT_SHORT_SIDE)
    }

    @Test
    fun ensureReversed_above1080Source_usesScaledInputForReverse() = runBlocking {
        assumeTrue(
            "source short side must exceed cap",
            processor.sourceShortSide(above1080Fixture) > MAX_OUTPUT_SHORT_SIDE,
        )

        val reversed = processor.ensureReversed(above1080Fixture, 0L, durationMs)

        assertTrue("reversed output should exist", reversed.exists())
        assertTrue("reversed output should be non-empty", reversed.length() > 0L)
        val reversedShortSide = SyntheticVideoFixtures.videoShortSide(reversed)
        assertTrue(
            "reversed clip short side was $reversedShortSide; pre-scale should keep reverse ≤ $MAX_OUTPUT_SHORT_SIDE",
            reversedShortSide in 1..MAX_OUTPUT_SHORT_SIDE,
        )
    }
}
