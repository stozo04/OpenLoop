package io.github.stozo04.openloop.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM regressions derived from Samsung RTL logcat (SM-S921B, 2026-06-03).
 *
 * These tests do **not** run [MediaCodec] — they lock in policy we inferred from:
 * - 20:32:27 ExoPlayer Init → Release before reverse (editor must not hold decoders during Trimming)
 * - 20:32:28.346 `selectAvcEncoder: c2.android.avc.encoder` → 20:32:28.437
 *   `Pending dequeue output buffer request cancelled` on pass 1
 *
 * On-device proof remains `VideoReverserTest` + manual RTL; this file prevents ranking/constant drift.
 */
class SamsungReversePreviewRegressionTest {

    /** Log: `reverse start … 854x480` — Samsung preview cap must stay 480 short side. */
    @Test
    fun previewCap_matchesRtlScaledClip() {
        assertEquals(480, SAMSUNG_PREVIEW_REVERSE_MAX_SHORT_SIDE)
    }

    /** Log: Transformer Release 28.303 → reverse start 28.324 — settle must be non-zero. */
    @Test
    fun postTransformSettleMs_isPositive() {
        assertTrue(SAMSUNG_POST_TRANSFORM_CODEC_SETTLE_MS >= 200L)
    }

    /**
     * RTL selected `c2.android.avc.encoder` and failed in ~113ms. Ranking must prefer
     * `c2.google.avc.encoder` over `c2.android.avc.encoder` on Samsung for pass 1.
     */
    @Test
    fun encoderRank_googlePreferredOverAndroidAvc_onSamsung() {
        val google = avcEncoderPreferenceRank("c2.google.avc.encoder", isHardwareAccelerated = false, isSamsung = true)
        val android = avcEncoderPreferenceRank("c2.android.avc.encoder", isHardwareAccelerated = false, isSamsung = true)
        assertTrue(
            "google rank=$google should beat android.avc rank=$android (RTL 20:32:28 regression)",
            google < android,
        )
    }

    /**
     * Exynos HW is a fallback when Google is unavailable; still must beat android.avc on Samsung.
     */
    @Test
    fun encoderRank_exynosPreferredOverAndroidAvc_onSamsung() {
        val exynos = avcEncoderPreferenceRank("c2.exynos.h264.encoder", isHardwareAccelerated = true, isSamsung = true)
        val android = avcEncoderPreferenceRank("c2.android.avc.encoder", isHardwareAccelerated = false, isSamsung = true)
        assertTrue(exynos < android)
    }

    @Test
    fun encoderRank_nonSamsung_doesNotPenalizeAndroidAvc() {
        val androidSamsung = avcEncoderPreferenceRank("c2.android.avc.encoder", false, isSamsung = true)
        val androidOther = avcEncoderPreferenceRank("c2.android.avc.encoder", false, isSamsung = false)
        assertTrue(androidSamsung > androidOther)
    }
}
