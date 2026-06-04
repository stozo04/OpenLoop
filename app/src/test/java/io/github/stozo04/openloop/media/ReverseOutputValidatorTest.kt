package io.github.stozo04.openloop.media

import io.github.stozo04.openloop.media.ReverseOutputValidator.REASON_MISSING_FILE
import io.github.stozo04.openloop.media.ReverseOutputValidator.REASON_NO_VIDEO_SAMPLES
import io.github.stozo04.openloop.media.ReverseOutputValidator.REASON_NO_VIDEO_TRACK
import io.github.stozo04.openloop.media.ReverseOutputValidator.REASON_OK
import io.github.stozo04.openloop.media.ReverseOutputValidator.reverseOutputVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure verdict behind [ReverseOutputValidator.validateReversedOutput].
 * The [android.media.MediaExtractor] probe itself is exercised by the instrumented
 * `ReverseOutputValidatorAndroidTest` against the real S23 zero-frame artifacts.
 *
 * Validity is content-based (≥1 video track with ≥1 sample) — deliberately NOT size-based: a
 * legitimate 12-frame low-entropy clip encoded under 4 KiB on a real device (Pixel 10 Pro Fold,
 * 2026-06-04), falsifying the spec's suggested `MIN_REVERSED_BYTES` gate.
 */
class ReverseOutputValidatorTest {

    @Test
    fun `missing file is invalid`() {
        val v = reverseOutputVerdict(fileBytes = -1L, videoTrackCount = 0, sampleCount = 0)
        assertFalse(v.valid)
        assertEquals(REASON_MISSING_FILE, v.reason)
        assertEquals(0L, v.fileBytes) // never reports a negative size outward
    }

    @Test
    fun `s23 zero-frame shell shape is invalid - track with no samples`() {
        // The 598-byte moov-only artifact from RESEARCH.md §2/§7c: one video track, zero samples.
        val v = reverseOutputVerdict(fileBytes = 598L, videoTrackCount = 1, sampleCount = 0)
        assertFalse(v.valid)
        assertEquals(REASON_NO_VIDEO_SAMPLES, v.reason)
    }

    @Test
    fun `s23 zero-frame shell shape is invalid - extractor drops the empty track`() {
        // Device extractors may not surface a 0-sample track at all; still invalid.
        val v = reverseOutputVerdict(fileBytes = 598L, videoTrackCount = 0, sampleCount = 0)
        assertFalse(v.valid)
        assertEquals(REASON_NO_VIDEO_TRACK, v.reason)
    }

    @Test
    fun `large file with no video track is invalid`() {
        val v = reverseOutputVerdict(fileBytes = 1_000_000L, videoTrackCount = 0, sampleCount = 0)
        assertFalse(v.valid)
        assertEquals(REASON_NO_VIDEO_TRACK, v.reason)
    }

    @Test
    fun `large file with a video track but zero samples is invalid`() {
        val v = reverseOutputVerdict(fileBytes = 1_000_000L, videoTrackCount = 1, sampleCount = 0)
        assertFalse(v.valid)
        assertEquals(REASON_NO_VIDEO_SAMPLES, v.reason)
    }

    @Test
    fun `healthy reversed clip is valid`() {
        // S25 save-path output from RESEARCH.md §7b: 695,690 bytes, 113 samples.
        val v = reverseOutputVerdict(fileBytes = 695_690L, videoTrackCount = 1, sampleCount = 113)
        assertTrue(v.valid)
        assertEquals(REASON_OK, v.reason)
        assertEquals(113, v.sampleCount)
    }

    @Test
    fun `tiny but real clip is valid - size is diagnostics not a gate`() {
        // Pixel 10 Pro Fold encoded a 12-frame 320x240 luma ramp to <4 KiB; must validate.
        val v = reverseOutputVerdict(fileBytes = 3_500L, videoTrackCount = 1, sampleCount = 12)
        assertTrue(v.valid)
        assertEquals(REASON_OK, v.reason)
    }

    @Test
    fun `single-sample clip is valid`() {
        val v = reverseOutputVerdict(fileBytes = 50_000L, videoTrackCount = 1, sampleCount = 1)
        assertTrue(v.valid)
    }
}
