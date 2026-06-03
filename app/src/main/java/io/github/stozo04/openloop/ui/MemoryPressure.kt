package io.github.stozo04.openloop.ui

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context

/**
 * Memory-pressure interpretation for the editor's effects-preview degradation (editor-memory-oom
 * WS-3, hardened after the PR #58 review).
 *
 * Two delivery regimes exist for [ComponentCallbacks2.onTrimMemory]:
 *
 *  - **API <= 33:** the granular foreground-pressure levels (`TRIM_MEMORY_RUNNING_LOW` /
 *    `TRIM_MEMORY_RUNNING_CRITICAL`) are delivered while the app is visible and the device is
 *    genuinely under memory pressure — exactly the signal WS-3 wants.
 *  - **API 34+:** the system **no longer delivers any legacy constants** — "Beginning with
 *    Android 14, the system no longer delivers notifications for the other, legacy constants.
 *    Those constants were formally deprecated in Android 15."
 *    (https://developer.android.com/topic/performance/memory). Only `TRIM_MEMORY_UI_HIDDEN` (20)
 *    and `TRIM_MEMORY_BACKGROUND` (40) still fire, and they mean "the UI is not visible" — a
 *    lifecycle fact, NOT memory pressure. Modern devices are instead covered by the proactive
 *    [lowMemoryProbe] polled by `OpenLoopViewModel` at the moments that matter (editor entry,
 *    applying a non-Original look).
 *
 * The PR #58 review FAIL this guards against: `level >= TRIM_MEMORY_RUNNING_LOW` also matched
 * `UI_HIDDEN`/`BACKGROUND`, silently disabling the Looks preview on every routine backgrounding,
 * while never firing for real foreground pressure on the Android 14+/16 devices that produced the
 * production OOM (Crashlytics ef2823cf...).
 */
object MemoryPressure {

    /**
     * True only for the legacy *foreground memory pressure* trim levels. Exact matches — never a
     * `>=` comparison, which would also match the lifecycle levels (`TRIM_MEMORY_UI_HIDDEN` = 20,
     * `TRIM_MEMORY_BACKGROUND` = 40, `TRIM_MEMORY_MODERATE` = 60, `TRIM_MEMORY_COMPLETE` = 80)
     * that fire on every backgrounding / cache-trim, not on pressure while editing.
     */
    @Suppress("DEPRECATION") // Legacy constants: still delivered on API <= 33, undelivered on 34+.
    fun isForegroundPressureLevel(level: Int): Boolean =
        level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL

    /**
     * Proactive low-memory probe for API 34+ (and a cross-version backstop): polls
     * [ActivityManager.getMemoryInfo] as the memory guidance recommends now that the foreground
     * trim callbacks are gone. Built at the Context bridge (MainActivity) and injected into the
     * ViewModel as a plain `() -> Boolean`, so the ViewModel stays Context-free (Lesson 004).
     */
    fun lowMemoryProbe(context: Context): () -> Boolean {
        val activityManager =
            context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return { false }
        return {
            val info = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(info)
            info.lowMemory
        }
    }
}
