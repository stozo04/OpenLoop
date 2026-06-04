package io.github.stozo04.openloop.media

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
