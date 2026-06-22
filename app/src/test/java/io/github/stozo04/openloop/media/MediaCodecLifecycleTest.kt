package io.github.stozo04.openloop.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCodecLifecycleTest {

    @Test
    fun `detects released state message`() {
        assertTrue(
            isMediaCodecLifecycleFailure(
                IllegalArgumentException("Invalid to call at Released state; only valid in executing state"),
            ),
        )
    }

    @Test
    fun `detects pending dequeue cancelled`() {
        assertTrue(
            isMediaCodecLifecycleFailure(
                IllegalStateException("Pending dequeue output buffer request cancelled"),
            ),
        )
    }

    @Test
    fun `detects empty IllegalStateException at dequeue`() {
        assertTrue(isMediaCodecLifecycleFailure(IllegalStateException()))
    }

    @Test
    fun `ignores unrelated IllegalStateException`() {
        assertFalse(
            isMediaCodecLifecycleFailure(
                IllegalStateException("Pass-1 decode loop exceeded 500000 iterations"),
            ),
        )
    }

    @Test
    fun `detects surface has been released`() {
        val error = IllegalArgumentException("The surface has been released")
        assertTrue(isMediaCodecSurfaceReleasedFailure(error))
        assertTrue(isMediaCodecLifecycleFailure(error))
    }

    @Test
    fun `retries Samsung contention on first failure only`() {
        val error = IllegalArgumentException("Invalid to call at Released state")
        assertTrue(shouldRetryMediaCodecContention(error, failedAttemptIndex = 0, maxAttempts = 2, samsung = true))
        assertFalse(shouldRetryMediaCodecContention(error, failedAttemptIndex = 1, maxAttempts = 2, samsung = true))
        assertFalse(shouldRetryMediaCodecContention(error, failedAttemptIndex = 0, maxAttempts = 2, samsung = false))
    }

    @Test
    fun `retries surface released on non-Samsung devices`() {
        val error = IllegalArgumentException("The surface has been released")
        assertTrue(shouldRetryMediaCodecContention(error, failedAttemptIndex = 0, maxAttempts = 2, samsung = false))
    }

    // ── Codec-initialization failure (LG LM-X540 / Crashlytics 47233ad7) ────────────────────────

    @Test
    fun `detects start failed as codec initialization failure`() {
        // The exact signature from the LG LM-X540 report: MediaCodec.native_start() → BAD_VALUE →
        // IllegalArgumentException("start failed").
        assertTrue(isMediaCodecInitializationFailure(IllegalArgumentException("start failed")))
    }

    @Test
    fun `detects configure and codec failure messages`() {
        assertTrue(isMediaCodecInitializationFailure(IllegalArgumentException("configure failed for c2.mtk.avc.encoder")))
        assertTrue(isMediaCodecInitializationFailure(IllegalStateException("Error initializing codec")))
        assertTrue(isMediaCodecInitializationFailure(IllegalStateException("Failed to initialize OMX.MTK.VIDEO.ENCODER.AVC")))
    }

    @Test
    fun `ignores benign lifecycle teardown as init failure`() {
        // Released / cancelled / empty-message ISE are teardown noise, not a codec that can't start —
        // they must NOT trip the software-codec fallback (that path is the contention retry instead).
        assertFalse(isMediaCodecInitializationFailure(IllegalArgumentException("Invalid to call at Released state")))
        assertFalse(isMediaCodecInitializationFailure(IllegalStateException("Pending dequeue output buffer request cancelled")))
        assertFalse(isMediaCodecInitializationFailure(IllegalStateException()))
    }

    @Test
    fun `ignores unrelated failures as init failure`() {
        assertFalse(isMediaCodecInitializationFailure(IllegalStateException("Pass-1 decode loop exceeded 500000 iterations")))
        assertFalse(isMediaCodecInitializationFailure(RuntimeException("disk full")))
    }

    @Test
    fun `software codec retry fires for start failed while an attempt remains`() {
        val error = IllegalArgumentException("start failed")
        assertTrue(shouldRetryReverseWithSoftwareCodec(error, failedAttemptIndex = 0, maxAttempts = 2))
        // No retry once the last attempt has failed.
        assertFalse(shouldRetryReverseWithSoftwareCodec(error, failedAttemptIndex = 1, maxAttempts = 2))
    }

    @Test
    fun `software codec retry is device-agnostic unlike contention retry`() {
        // Regression guard for 47233ad7: the OLD decision (contention) never recovered `start failed`
        // on this non-Samsung device, so the reverse died on the first attempt with no fallback.
        val error = IllegalArgumentException("start failed")
        assertFalse(
            "start failed must not be misread as benign contention",
            shouldRetryMediaCodecContention(error, failedAttemptIndex = 0, maxAttempts = 2, samsung = false),
        )
        // The new decision recovers it regardless of manufacturer.
        assertTrue(shouldRetryReverseWithSoftwareCodec(error, failedAttemptIndex = 0, maxAttempts = 2))
    }
}
