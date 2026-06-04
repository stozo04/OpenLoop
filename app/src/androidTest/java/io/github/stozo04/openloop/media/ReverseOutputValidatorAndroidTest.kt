package io.github.stozo04.openloop.media

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stozo04.openloop.media.ReverseOutputValidator.REASON_NO_VIDEO_SAMPLES
import io.github.stozo04.openloop.media.ReverseOutputValidator.REASON_NO_VIDEO_TRACK
import io.github.stozo04.openloop.media.ReverseOutputValidator.REASON_UNREADABLE
import io.github.stozo04.openloop.media.ReverseOutputValidator.validateReversedOutput
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Instrumented coverage for [ReverseOutputValidator]'s [android.media.MediaExtractor] probe.
 *
 * The `reversed_empty_s23_*.mp4` assets are the **real** zero-frame artifacts pulled from a Galaxy
 * S23 (SM-S911U, API 33) on 2026-06-04 — pass 2 of [VideoReverser] exited cleanly having muxed zero
 * samples (RESEARCH.md §7c). They are the exact files the Media3 Transformer rejected with
 * "The asset loader has no audio or video track to output".
 */
@RunWith(AndroidJUnit4::class)
class ReverseOutputValidatorAndroidTest {

    private lateinit var workDir: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        workDir = File(context.cacheDir, "validator_test_${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        workDir.deleteRecursively()
    }

    @Test
    fun s23ZeroFrameShell_854x480_isInvalid() {
        val v = validateReversedOutput(copyAsset("reversed_empty_s23_854x480.mp4"))
        assertFalse("598-byte S23 shell must be invalid", v.valid)
        assertTrue(
            "expected a content-based rejection, got ${v.reason}",
            v.reason in setOf(REASON_NO_VIDEO_SAMPLES, REASON_NO_VIDEO_TRACK, REASON_UNREADABLE),
        )
        assertEquals(598L, v.fileBytes)
    }

    @Test
    fun s23ZeroFrameShell_1280x720_isInvalid() {
        val v = validateReversedOutput(copyAsset("reversed_empty_s23_1280x720.mp4"))
        assertFalse("598-byte S23 shell must be invalid", v.valid)
        assertTrue(
            "expected a content-based rejection, got ${v.reason}",
            v.reason in setOf(REASON_NO_VIDEO_SAMPLES, REASON_NO_VIDEO_TRACK, REASON_UNREADABLE),
        )
    }

    @Test
    fun missingFile_isInvalid() {
        val v = validateReversedOutput(File(workDir, "never_written.mp4"))
        assertFalse(v.valid)
        assertEquals(ReverseOutputValidator.REASON_MISSING_FILE, v.reason)
    }

    @Test
    fun garbageBytes_isInvalidNotCrash() {
        val garbage = File(workDir, "garbage.mp4").apply {
            writeBytes(ByteArray(8_192) { (it % 251).toByte() })
        }
        val v = validateReversedOutput(garbage)
        assertFalse(v.valid)
        // Device extractors differ in how they fail to sniff garbage; any invalid reason is
        // acceptable, crashing is not.
        assertTrue(v.reason == REASON_UNREADABLE || v.reason == REASON_NO_VIDEO_TRACK)
    }

    @Test
    fun healthySyntheticClip_isValid() {
        val clip = File(workDir, "healthy.mp4")
        assumeTrue(
            "device encoder rejected the synthetic fixture",
            SyntheticVideoFixtures.generateLumaRampClip(clip, width = 320, height = 240),
        )
        val v = validateReversedOutput(clip)
        assertTrue("synthetic clip should validate, got ${v.reason}", v.valid)
        assertTrue("expected >=1 counted sample", v.sampleCount >= 1)
        assertEquals(1, v.videoTrackCount)
    }

    private fun copyAsset(name: String): File {
        val dest = File(workDir, name)
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        return dest
    }
}
