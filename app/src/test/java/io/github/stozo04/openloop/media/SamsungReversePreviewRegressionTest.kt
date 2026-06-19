package io.github.stozo04.openloop.media

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** RTL device API (SM-S921B); JVM unit tests default to SDK 0 without this. */
private const val RTL_SDK_INT = Build.VERSION_CODES.BAKLAVA
/**
 * JVM regressions derived from Samsung RTL logcat (SM-S921B, 2026-06-03).
 *
 * These tests do **not** run [MediaCodec] — they lock in policy we inferred from:
 * - 20:32:27 ExoPlayer Init → Release before reverse (editor must not hold decoders during Trimming)
 * - 20:32:28.346 `selectAvcEncoder: c2.android.avc.encoder` → 20:32:28.437
 *   `Pending dequeue output buffer request cancelled` on pass 1
 * - 20:42:11.464 `selectAvcEncoder: OMX.google.h264.encoder` → 20:42:16.596 preview failed (~5s hard wait)
 * - 20:46:15.016 `selectAvcEncoder: c2.exynos.h264.encoder` → 20:46:20.067 preview failed (~5s hard wait)
 * Fix (1.0.12+): defer output format to pass1 [drainToMuxer] per MediaCodec FAQ; try c2.google first on Samsung.
 * - 20:51:59.418 `c2.exynos.h264.encoder` (format ready) → 20:51:59.437 preview failed (~19ms): Exynos decoder contention.
 * Fix (1.0.13+): Samsung uses only Google SW encoder+decoder; vendor HW excluded from try-order.
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
        assertTrue(SAMSUNG_POST_TRANSFORM_CODEC_SETTLE.inWholeMilliseconds >= 200L)
    }

    /**
     * RTL selected `c2.android.avc.encoder` and failed in ~113ms. Ranking must prefer
     * `c2.google.avc.encoder` over `c2.android.avc.encoder` on Samsung for pass 1.
     */
    @Test
    fun filterOmxc_dropsLegacyWhenCodec2Present() {
        val names = listOf(
            "OMX.google.h264.encoder",
            "c2.google.avc.encoder",
            "c2.exynos.h264.encoder",
        )
        assertEquals(
            listOf("c2.google.avc.encoder", "c2.exynos.h264.encoder"),
            filterOmxcEncodersWhenCodec2Available(names),
        )
    }

    @Test
    fun encoderRank_c2GooglePreferredOverOmxcGoogle_onSamsung() {
        val c2 = avcEncoderPreferenceRank("c2.google.avc.encoder", false, true, RTL_SDK_INT)
        val omx = avcEncoderPreferenceRank("OMX.google.h264.encoder", false, true, RTL_SDK_INT)
        assertTrue("c2.google rank=$c2 should beat OMX.google rank=$omx", c2 < omx)
    }

    @Test
    fun encoderRank_googlePreferredOverAndroidAvc_onSamsung() {
        val google = avcEncoderPreferenceRank("c2.google.avc.encoder", false, true, RTL_SDK_INT)
        val android = avcEncoderPreferenceRank("c2.android.avc.encoder", false, true, RTL_SDK_INT)
        assertTrue(
            "google rank=$google should beat android.avc rank=$android (RTL 20:32:28 regression)",
            google < android,
        )
    }

    @Test
    fun encoderRank_googlePreferredOverExynos_onSamsung() {
        val google = avcEncoderPreferenceRank("c2.google.avc.encoder", false, true, RTL_SDK_INT)
        val exynos = avcEncoderPreferenceRank("c2.exynos.h264.encoder", true, true, RTL_SDK_INT)
        assertTrue(
            "google rank=$google should beat exynos rank=$exynos (RTL 20:46:15 regression)",
            google < exynos,
        )
    }

    @Test
    fun mergePlatformPick_samsung_keepsGoogleFirst() {
        val order = listOf(SAMSUNG_REVERSE_AVC_SOFTWARE_ENCODER, "c2.exynos.h264.encoder")
        assertEquals(
            order,
            mergePlatformEncoderPick(order, platformPick = "c2.exynos.h264.encoder", isSamsung = true),
        )
    }

    @Test
    fun tryOrder_samsung_excludesVendorHwEncoders() {
        val installed = setOf(
            SAMSUNG_REVERSE_AVC_SOFTWARE_ENCODER,
            "c2.exynos.h264.encoder",
            "c2.android.avc.encoder",
        )
        val order = avcEncoderTryOrderForReverse(
            formatSupportedNames = listOf("c2.exynos.h264.encoder", "c2.android.avc.encoder"),
            installedEncoderNames = installed,
            isSamsung = true,
            isHardwareAccelerated = { it.contains("exynos", ignoreCase = true) },
            sdkInt = RTL_SDK_INT,
        )
        assertEquals(listOf(SAMSUNG_REVERSE_AVC_SOFTWARE_ENCODER), order)
    }

    @Test
    fun tryOrder_samsung_omitsGoogleWhenNotInstalled() {
        val order = avcEncoderTryOrderForReverse(
            formatSupportedNames = listOf("c2.exynos.h264.encoder"),
            installedEncoderNames = setOf("c2.exynos.h264.encoder"),
            isSamsung = true,
            isHardwareAccelerated = { true },
            sdkInt = RTL_SDK_INT,
        )
        assertTrue(order.isEmpty())
    }

    @Test
    fun samsungDecoderTryOrder_prefersGoogleH264WhenInstalled() {
        val order = samsungSoftwareAvcDecoderTryOrder(
            setOf(SAMSUNG_REVERSE_AVC_SOFTWARE_DECODER, "c2.android.avc.decoder"),
        )
        assertEquals(SAMSUNG_REVERSE_AVC_SOFTWARE_DECODER, order.first())
    }

    /**
     * Exynos HW is a fallback when Google cannot start; still must beat android.avc on Samsung.
     */
    @Test
    fun encoderRank_exynosPreferredOverAndroidAvc_onSamsung() {
        val exynos = avcEncoderPreferenceRank("c2.exynos.h264.encoder", true, true, RTL_SDK_INT)
        val android = avcEncoderPreferenceRank("c2.android.avc.encoder", false, true, RTL_SDK_INT)
        assertTrue(exynos < android)
    }

    @Test
    fun encoderRank_nonSamsung_doesNotPenalizeAndroidAvc() {
        val androidSamsung = avcEncoderPreferenceRank("c2.android.avc.encoder", false, true, RTL_SDK_INT)
        val androidOther = avcEncoderPreferenceRank("c2.android.avc.encoder", false, false, RTL_SDK_INT)
        assertTrue(androidSamsung > androidOther)
    }
}
