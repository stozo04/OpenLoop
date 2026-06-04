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

/** Second pass after ExoPlayer/Transformer contention (Crashlytics 3a506c4e Released-state dequeue). */
internal const val SAMSUNG_CODEC_CONTENTION_RETRY_MS = 600L

/** Pass 1 + pass 2 attempts on Samsung before surfacing preview failure. */
internal const val SAMSUNG_REVERSE_PASS_MAX_ATTEMPTS = 2

internal fun isSamsungDevice(): Boolean =
    Build.MANUFACTURER.equals("samsung", ignoreCase = true) ||
        Build.BRAND.equals("samsung", ignoreCase = true)
