package io.github.stozo04.openloop.media

import android.media.MediaCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

/**
 * Maps benign [MediaCodec] teardown failures (cancel, [MediaCodec.stop]/[MediaCodec.release] in
 * [finally], or Samsung "pending dequeue cancelled" after Transformer/ExoPlayer churn) into
 * [CancellationException] so preview reverse does not surface as a user failure or a Crashlytics
 * non-fatal (issue 3a506c4ecc5bfeff0ab2b56d58f6e1d6).
 */
internal fun isMediaCodecLifecycleFailure(error: Throwable): Boolean =
    when (error) {
        // MediaCodec.CodecException extends IllegalStateException — matches here when inactive
        // (discarded via CancellationException); active-job CodecException is not retried unless Samsung/surface-released.
        is IllegalStateException, is IllegalArgumentException -> {
            val message = error.message.orEmpty()
            message.contains("Released", ignoreCase = true) ||
                message.contains("surface has been released", ignoreCase = true) ||
                message.contains("cancelled", ignoreCase = true) ||
                message.contains("executing state", ignoreCase = true) ||
                // S24 / Crashlytics 1.0.9: empty IllegalStateException at dequeueOutputBuffer after churn.
                (error is IllegalStateException && message.isEmpty())
        }
        else -> false
    }

/**
 * Re-throws [CancellationException] when [error] is a codec lifecycle failure during a
 * cancellable reverse pass; otherwise rethrows [error] unchanged.
 */
internal suspend fun rethrowMediaCodecLifecycleAsCancellation(error: Throwable): Nothing {
    if (isMediaCodecLifecycleFailure(error) && !coroutineContext.isActive) {
        throw CancellationException("MediaCodec torn down during reverse preview", error)
    }
    throw error
}

/**
 * Runs [block] and converts lifecycle [MediaCodec] failures into [CancellationException].
 */
internal suspend inline fun <T> runMediaCodecCancellable(block: () -> T): T =
    try {
        block()
    } catch (error: Throwable) {
        rethrowMediaCodecLifecycleAsCancellation(error)
    }

/**
 * Whether [VideoReverser.reverse] should run another pass after [error] (Samsung codec slot churn
 * while the job is still active — ExoPlayer + pass 1).
 */
/** Encoder input [android.view.Surface] invalidated before [android.media.MediaCodec.configure] (Crashlytics b09e527). */
internal fun isMediaCodecSurfaceReleasedFailure(error: Throwable): Boolean =
    error is IllegalArgumentException &&
        error.message.orEmpty().contains("surface has been released", ignoreCase = true)

internal fun shouldRetryMediaCodecContention(
    error: Throwable,
    failedAttemptIndex: Int,
    maxAttempts: Int,
    samsung: Boolean,
): Boolean {
    if (failedAttemptIndex >= maxAttempts - 1 || !isMediaCodecLifecycleFailure(error)) return false
    return samsung || isMediaCodecSurfaceReleasedFailure(error)
}

/**
 * A [MediaCodec] that fails to **initialize** — `configure()` / `start()` rejecting the request —
 * as opposed to a benign teardown ([isMediaCodecLifecycleFailure]) or a healthy-codec data problem.
 *
 * The motivating signature is `IllegalArgumentException: start failed` (LG LM-X540 / Android 10,
 * Crashlytics 47233ad7): "start failed" is the JNI message `android.media.MediaCodec.native_start()`
 * passes to `throwExceptionAsNecessary`, and a `BAD_VALUE` native status surfaces it as an
 * [IllegalArgumentException]. On a low-end (often MediaTek) device under memory pressure the *hardware*
 * AVC codec can reject start outright; the software codec usually still comes up. These failures are
 * NOT Samsung-specific, so [shouldRetryMediaCodecContention] never recovered them — the reverse died
 * on the first attempt with no fallback.
 *
 * [MediaCodec.CodecException] (the documented codec-error type) also counts, except when it presents
 * as a benign lifecycle teardown (already filtered out above).
 */
internal fun isMediaCodecInitializationFailure(error: Throwable): Boolean {
    if (isMediaCodecLifecycleFailure(error)) return false
    return when (error) {
        is MediaCodec.CodecException -> true
        is IllegalArgumentException, is IllegalStateException -> {
            val message = error.message.orEmpty()
            message.contains("start failed", ignoreCase = true) ||
                message.contains("configure failed", ignoreCase = true) ||
                message.contains("codec failed", ignoreCase = true) ||
                message.contains("Failed to initialize", ignoreCase = true) ||
                message.contains("Error initializing", ignoreCase = true)
        }
        else -> false
    }
}

/**
 * Whether [VideoReverser.reverse] should retry the failed pass with **software** codecs after a
 * codec-initialization failure ([isMediaCodecInitializationFailure]). Unlike
 * [shouldRetryMediaCodecContention], this is device-agnostic — the hardware codec rejecting
 * `start()` is the failure, so the only useful next move is the software encoder + decoder, on any
 * manufacturer. Retries only while an attempt remains (mirrors the contention/zero-frame guards).
 */
internal fun shouldRetryReverseWithSoftwareCodec(
    error: Throwable,
    failedAttemptIndex: Int,
    maxAttempts: Int,
): Boolean = failedAttemptIndex < maxAttempts - 1 && isMediaCodecInitializationFailure(error)
