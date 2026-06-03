package io.github.stozo04.openloop.media

import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build

/**
 * Pure ranking for AVC encoder selection during [VideoReverser] pass 1/2.
 *
 * Policy is informed by:
 * - [MediaCodecList.findEncoderForFormat] / [MediaCodecInfo.CodecCapabilities.isFormatSupported]
 *   (developer.android.com — query encoders, do not hardcode OMX/c2 names)
 * - Surface encoders often publish [MediaFormat] only after the first frame (bigflake/mediacodec FAQ;
 *   sisik.eu decode→surface→encode loop handles [MediaCodec.INFO_OUTPUT_FORMAT_CHANGED] in the drain)
 * - Media3 [androidx.media3.transformer.EncoderSelector] prefers HW when present; Samsung RTL (Jun 2026)
 *   showed Exynos surface encoders stalling on a pre-decode format probe — software `c2.google` first.
 */
/**
 * Drop legacy OMX encoders when any Codec2 (`c2.*`) candidate exists — RTL SM-S921B (Jun 2026)
 * tied `OMX.google.h264.encoder` with `c2.google.avc.encoder` on rank and picked OMX, which never
 * published an output format on a pre-frame probe.
 */
internal fun filterOmxcEncodersWhenCodec2Available(codecNames: List<String>): List<String> {
    val hasCodec2 = codecNames.any { it.startsWith("c2.", ignoreCase = true) }
    return if (hasCodec2) codecNames.filter { !it.startsWith("OMX.", ignoreCase = true) } else codecNames
}

/** Tried first on Samsung even when strict [MediaFormat] checks omit it (RTL Jun 2026). */
internal const val SAMSUNG_REVERSE_AVC_SOFTWARE_ENCODER = "c2.google.avc.encoder"

/** Pair with [SAMSUNG_REVERSE_AVC_SOFTWARE_ENCODER] — avoids second Exynos decoder after ExoPlayer. */
internal const val SAMSUNG_REVERSE_AVC_SOFTWARE_DECODER = "c2.google.h264.decoder"

/** Samsung vendor codecs to skip for preview reverse (RTL: Exynos pass1 fails ~20ms after encoder ready). */
internal fun isSamsungVendorAvcCodec(codecName: String): Boolean =
    codecName.contains("exynos", ignoreCase = true) ||
        codecName.contains("c2.sec", ignoreCase = true) ||
        codecName.contains("android.avc", ignoreCase = true)

internal fun samsungSoftwareAvcDecoderTryOrder(installedDecoderNames: Set<String>): List<String> =
    buildList {
        if (SAMSUNG_REVERSE_AVC_SOFTWARE_DECODER in installedDecoderNames) {
            add(SAMSUNG_REVERSE_AVC_SOFTWARE_DECODER)
        }
        if ("c2.android.avc.decoder" in installedDecoderNames) {
            add("c2.android.avc.decoder")
        }
    }

internal fun supportsAvcSurfaceEncode(info: MediaCodecInfo, mimeAvc: String = MediaFormat.MIMETYPE_VIDEO_AVC): Boolean {
    if (!info.isEncoder) return false
    val caps = runCatching { info.getCapabilitiesForType(mimeAvc) }.getOrNull() ?: return false
    return caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
}

/**
 * Non-Samsung: honor the platform's [MediaCodecList.findEncoderForFormat] pick ahead of heuristics.
 * Samsung preview reverse keeps [SAMSUNG_REVERSE_AVC_SOFTWARE_ENCODER] first (RTL regressions).
 */
internal fun mergePlatformEncoderPick(
    tryOrder: List<String>,
    platformPick: String?,
    isSamsung: Boolean,
): List<String> {
    if (platformPick.isNullOrBlank() || isSamsung) return tryOrder
    return buildList {
        add(platformPick)
        addAll(tryOrder.filter { it != platformPick })
    }.distinct()
}

internal fun avcEncoderPreferenceRank(
    codecName: String,
    isHardwareAccelerated: Boolean,
    isSamsung: Boolean,
    sdkInt: Int = Build.VERSION.SDK_INT,
): Int {
    var rank = 0
    if (codecName.startsWith("OMX.", ignoreCase = true)) {
        rank += 1_000
    }
    if (isSamsung) {
        // RTL 20:32:28.346: c2.android.avc.encoder → dequeueOutputBuffer cancelled ~113ms later.
        if (codecName.contains("android.avc", ignoreCase = true)) {
            rank += 400
        }
        // RTL 20:46:15: c2.exynos.h264.encoder → no output format on pre-frame probe (needs surface feed).
        if (isHardwareAccelerated) {
            rank += 500
        }
        if (codecName == SAMSUNG_REVERSE_AVC_SOFTWARE_ENCODER) {
            rank -= 250
        }
    }
    if (sdkInt >= Build.VERSION_CODES.Q && !isHardwareAccelerated) {
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

/**
 * Ordered codec names to try for surface AVC encode. On Samsung, [SAMSUNG_REVERSE_AVC_SOFTWARE_ENCODER]
 * is always first — RTL picked Exynos when Google was absent from strict `isFormatSupported` only.
 */
internal fun avcEncoderTryOrderForReverse(
    formatSupportedNames: List<String>,
    installedEncoderNames: Set<String>,
    isSamsung: Boolean,
    isHardwareAccelerated: (String) -> Boolean,
    sdkInt: Int = Build.VERSION.SDK_INT,
): List<String> {
    val ranked = filterOmxcEncodersWhenCodec2Available(formatSupportedNames)
        .sortedBy { avcEncoderPreferenceRank(it, isHardwareAccelerated(it), isSamsung, sdkInt) }
    if (!isSamsung) return ranked
    return buildList {
        if (SAMSUNG_REVERSE_AVC_SOFTWARE_ENCODER in installedEncoderNames) {
            add(SAMSUNG_REVERSE_AVC_SOFTWARE_ENCODER)
        }
        addAll(
            ranked.filter {
                it != SAMSUNG_REVERSE_AVC_SOFTWARE_ENCODER && !isSamsungVendorAvcCodec(it)
            },
        )
    }.distinct()
}
