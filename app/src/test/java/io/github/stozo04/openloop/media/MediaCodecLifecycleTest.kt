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
}
