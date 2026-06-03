package io.github.stozo04.openloop.ui

import android.content.ComponentCallbacks2
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard for the PR #58 review FAIL: the editor's memory degradation must key off the
 * legacy *foreground pressure* trim levels only (exact match), never the lifecycle levels — a
 * `>= TRIM_MEMORY_RUNNING_LOW` comparison silently matched `UI_HIDDEN`/`BACKGROUND` and disabled
 * the Looks preview on every routine backgrounding.
 */
@Suppress("DEPRECATION") // The legacy constants are the subject under test (delivered on API <= 33).
class MemoryPressureTest {

    @Test
    fun `legacy foreground pressure levels match`() {
        assertTrue(
            MemoryPressure.isForegroundPressureLevel(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW),
        )
        assertTrue(
            MemoryPressure.isForegroundPressureLevel(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL),
        )
    }

    @Test
    fun `lifecycle levels never match - backgrounding must not degrade the editor`() {
        // These are the only levels still delivered on Android 14+, and they mean "UI not visible",
        // not "memory pressure" (developer.android.com/topic/performance/memory).
        assertFalse(
            MemoryPressure.isForegroundPressureLevel(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN),
        )
        assertFalse(
            MemoryPressure.isForegroundPressureLevel(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND),
        )
    }

    @Test
    fun `cached and mild trim levels never match`() {
        assertFalse(
            MemoryPressure.isForegroundPressureLevel(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE),
        )
        assertFalse(
            MemoryPressure.isForegroundPressureLevel(ComponentCallbacks2.TRIM_MEMORY_MODERATE),
        )
        assertFalse(
            MemoryPressure.isForegroundPressureLevel(ComponentCallbacks2.TRIM_MEMORY_COMPLETE),
        )
    }
}
