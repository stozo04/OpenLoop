package io.github.stozo04.openloop.media

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Issue #41 Tier 2A — instrumented timing harness for the full Loopifying pipeline
 * ([Media3VideoProcessor.renderBoomerang]). Logs wall time for Fold/emulator baselines; uses a generous
 * upper bound so CI emulators don't flake on perf variance.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class LoopifyingBenchmarkTest {

    private lateinit var context: Context
    private lateinit var scratchDir: File
    private lateinit var processor: Media3VideoProcessor
    private lateinit var fixture: File
    private var durationMs: Long = 0L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        scratchDir = File(context.cacheDir, "benchmark_${System.nanoTime()}").apply { mkdirs() }
        processor = Media3VideoProcessor(
            context = context,
            reverser = VideoReverser(File(scratchDir, "reversed")),
        )
        fixture = File(scratchDir, "fixture_720p.mp4")
        val ok = SyntheticVideoFixtures.generateLumaRampClip(
            dest = fixture,
            width = 1280,
            height = 720,
            frameCount = 36, // 3 s @ 12 fps
            fps = 12,
        )
        assumeTrue("synthetic 720p fixture unavailable on this device", ok)
        durationMs = SyntheticVideoFixtures.durationMs(fixture)
        assumeTrue("fixture has no readable duration", durationMs > 0L)
    }

    @After
    fun tearDown() {
        scratchDir.deleteRecursively()
    }

    @Test
    fun renderBoomerang_720pSyntheticForwardThenReverse_at2x_completesWithOutput() = runBlocking {
        val output = File(scratchDir, "benchmark_out.mp4")
        val startMs = System.currentTimeMillis()

        processor.renderBoomerang(
            source = fixture,
            trimStartMs = 0L,
            trimEndMs = durationMs,
            mode = BoomerangMode.FORWARD_THEN_REVERSE,
            speed = 2f,
            repetitions = 1,
            outputFile = output,
        )

        val elapsedMs = System.currentTimeMillis() - startMs
        Log.i(TAG, "LoopifyingBenchmark: F→R @2× 720p ${durationMs}ms trim → ${elapsedMs}ms wall")

        assertTrue("output should exist", output.exists())
        assertTrue("output should be non-empty", output.length() > 0L)
        assertTrue(
            "generous emulator upper bound (${elapsedMs}ms); tighten after Fold baseline",
            elapsedMs < GENEROUS_UPPER_BOUND_MS,
        )
    }

    private companion object {
        const val TAG = "LoopifyingBenchmark"
        const val GENEROUS_UPPER_BOUND_MS = 120_000L
    }
}
