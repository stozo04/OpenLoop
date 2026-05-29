package com.openrang.app.media

import android.media.MediaFormat

/** Fallback frame rate when a track carries none (or an unreadable one). */
internal const val DEFAULT_FRAME_RATE = 30

/**
 * Resolve a video track's frame rate defensively.
 *
 * `MediaFormat.KEY_FRAME_RATE` is *usually* an Integer, but some codecs/sources store it as a
 * **Float** — and `getInteger` then throws [ClassCastException] (the value-type cannot be queried
 * portably below API 29, which is under our `minSdk`, so we probe by type instead). This swallows
 * that mismatch: try the integer read, fall back to the float read, and only then the [default].
 * A non-positive result also falls back to [default].
 *
 * Pulled out of [MediaFormat] (which the JVM stubs in unit tests) so the branching logic is
 * exercised by a pure-JVM test via lambdas — see `MediaFormatUtilsTest`.
 */
internal fun resolveFrameRate(
    containsFrameRate: Boolean,
    readInt: () -> Int,
    readFloat: () -> Float,
    default: Int = DEFAULT_FRAME_RATE,
): Int {
    if (!containsFrameRate) return default
    val raw = try {
        readInt()
    } catch (e: ClassCastException) {
        try {
            readFloat().toInt()
        } catch (e2: ClassCastException) {
            default
        }
    }
    return if (raw > 0) raw else default
}

/**
 * Resolve a video track's rotation hint (`MediaFormat.KEY_ROTATION`) in degrees, normalized to
 * `[0, 360)`. Returns `0` when absent or unreadable. Same pure-logic split as [resolveFrameRate]
 * so it's unit-testable without a real [MediaFormat].
 */
internal fun resolveRotationDegrees(
    containsRotation: Boolean,
    readInt: () -> Int,
): Int {
    if (!containsRotation) return 0
    val raw = try {
        readInt()
    } catch (e: ClassCastException) {
        0
    }
    return ((raw % 360) + 360) % 360
}

/** Type-tolerant `KEY_FRAME_RATE` read (see [resolveFrameRate]). */
internal fun MediaFormat.frameRateOrDefault(default: Int = DEFAULT_FRAME_RATE): Int =
    resolveFrameRate(
        containsFrameRate = containsKey(MediaFormat.KEY_FRAME_RATE),
        readInt = { getInteger(MediaFormat.KEY_FRAME_RATE) },
        readFloat = { getFloat(MediaFormat.KEY_FRAME_RATE) },
        default = default,
    )

/**
 * Source rotation hint in degrees (`0` when absent). Preserved through the reverse pipeline via
 * [android.media.MediaMuxer.setOrientationHint] so the reversed half carries the same orientation
 * metadata the forward half's source does — otherwise Media3 would render the two halves with
 * mismatched rotation at the seam.
 */
internal fun MediaFormat.rotationDegreesOrZero(): Int =
    resolveRotationDegrees(
        containsRotation = containsKey(MediaFormat.KEY_ROTATION),
        readInt = { getInteger(MediaFormat.KEY_ROTATION) },
    )
