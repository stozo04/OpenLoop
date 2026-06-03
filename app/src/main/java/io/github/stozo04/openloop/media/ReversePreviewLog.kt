package io.github.stozo04.openloop.media

import android.util.Log

/**
 * Grep-friendly logging for editor ping-pong / reverse preview.
 *
 * **Capture:** start logcat *before* Trim → NEXT, reproduce, then Ctrl+C.
 * ```
 * adb logcat -c
 * adb logcat -v time -s OpenLoopReverse:I OpenLoopReverse:W OpenLoopReverse:E OpenLoopReverse:D
 * ```
 * Or unfiltered: `adb logcat -v time | grep OpenLoopReverse`
 *
 * Timeline: `grep OpenLoopReverse ~/openloop-reverse.txt`
 */
internal object ReversePreviewLog {
    const val TAG = "OpenLoopReverse"

    fun d(event: String, detail: String = "") {
        safeLog { Log.d(TAG, format(event, detail)) }
    }

    fun i(event: String, detail: String = "") {
        safeLog { Log.i(TAG, format(event, detail)) }
    }

    fun e(event: String, detail: String = "", error: Throwable? = null) {
        safeLog {
            val msg = format(event, detail)
            if (error != null) Log.e(TAG, msg, error) else Log.e(TAG, msg)
        }
    }

    private fun format(event: String, detail: String): String =
        if (detail.isEmpty()) event else "$event | $detail"

    /** JVM unit tests do not mock [Log]; swallow so ViewModel tests stay Robolectric-free. */
    private inline fun safeLog(block: () -> Unit) {
        try {
            block()
        } catch (_: RuntimeException) {
            // "Method X in android.util.Log not mocked"
        }
    }
}
