package io.github.stozo04.openloop.media

import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build

/**
 * Pure ranking for AVC encoder selection during [VideoReverser] pass 1/2.
 *
 * Extracted so RTL log regressions (Samsung SM-S921B, Jun 2026) stay testable on the JVM without
 * [MediaCodecList].
 */
internal fun avcEncoderPreferenceRank(
    codecName: String,
    isHardwareAccelerated: Boolean,
    isSamsung: Boolean,
): Int {
    var rank = 0
    if (isSamsung) {
        // RTL 20:32:28.346: c2.android.avc.encoder → dequeueOutputBuffer cancelled ~113ms later.
        if (codecName.contains("android.avc", ignoreCase = true)) {
            rank += 400
        }
        if (isHardwareAccelerated) {
            rank += 300
        }
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !isHardwareAccelerated) {
        rank += 100
    }
    if (codecName.contains("google", ignoreCase = true)) {
        rank += 50
    }
    if (codecName.contains(".sw.", ignoreCase = true) || codecName.contains("software", ignoreCase = true)) {
        rank += 100
    }
    if (
        codecName.contains("exynos", ignoreCase = true) ||
            codecName.contains("c2.sec", ignoreCase = true) ||
            codecName.contains("qcom", ignoreCase = true) ||
            codecName.contains("qti", ignoreCase = true)
    ) {
        rank -= 25
    }
    return rank
}

internal fun MediaCodecInfo.encoderPreferenceRankForReverse(isSamsung: Boolean): Int =
    avcEncoderPreferenceRank(
        codecName = name,
        isHardwareAccelerated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            isHardwareAccelerated
        } else {
            !name.contains(".sw.", ignoreCase = true) &&
                !name.contains("software", ignoreCase = true) &&
                !name.contains("google", ignoreCase = true)
        },
        isSamsung = isSamsung,
    )
