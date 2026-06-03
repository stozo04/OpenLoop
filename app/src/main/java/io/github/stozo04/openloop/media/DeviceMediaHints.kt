package io.github.stozo04.openloop.media

import android.os.Build

/**
 * Max short side for **editor preview** reverse on Samsung — pass 1/2 at 720p+ often exceeds the
 * 120s deadline on Exynos devices; 480p keeps ping-pong preview within a practical budget.
 * Export ([Media3VideoProcessor.renderBoomerang]) still uses [MAX_OUTPUT_SHORT_SIDE].
 */
internal const val SAMSUNG_PREVIEW_REVERSE_MAX_SHORT_SIDE = 480

/** Let Media3 Transformer / ExoPlayer codecs fully release before [VideoReverser] pass 1 on Samsung. */
internal const val SAMSUNG_POST_TRANSFORM_CODEC_SETTLE_MS = 500L

/** Extra settle inside [VideoReverser.reverse] before opening pass-1 codecs (RTL Jun 2026). */
internal const val SAMSUNG_PRE_REVERSE_CODEC_SETTLE_MS = 400L

internal fun isSamsungDevice(): Boolean =
    Build.MANUFACTURER.equals("samsung", ignoreCase = true) ||
        Build.BRAND.equals("samsung", ignoreCase = true)
