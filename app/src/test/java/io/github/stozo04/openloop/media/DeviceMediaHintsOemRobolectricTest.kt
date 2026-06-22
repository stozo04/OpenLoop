package io.github.stozo04.openloop.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild

/**
 * Device-free OEM identity tests via Robolectric [ShadowBuild].
 *
 * Stock emulators cannot spoof `Build.MANUFACTURER` (Google Play images ignore `-prop` and block
 * `setprop`). These tests exercise the same branches the ViewModel and [VideoReverser] read at
 * runtime on real Samsung hardware — preview reverse cap and Samsung-first encoder ordering.
 *
 * LG has no equivalent identity gate in OpenLoop; its Crashlytics path (47233ad7, `start failed`)
 * is covered by [io.github.stozo04.openloop.media.VideoReverserTest.reverse_recoversFromCodecStartFailure_viaSoftwareFallback].
 */
@RunWith(RobolectricTestRunner::class)
class DeviceMediaHintsOemRobolectricTest {

    @Test
    @Config(sdk = [34])
    fun samsungManufacturer_isSamsungDevice_and480pPreviewCap() {
        ShadowBuild.setManufacturer("samsung")
        ShadowBuild.setBrand("google")

        assertTrue(isSamsungDevice())
        assertEquals(SAMSUNG_PREVIEW_REVERSE_MAX_SHORT_SIDE, previewReverseMaxShortSideOrNull())
    }

    @Test
    @Config(sdk = [34])
    fun samsungBrand_isSamsungDevice_evenWhenManufacturerDiffers() {
        ShadowBuild.setManufacturer("Google")
        ShadowBuild.setBrand("samsung")

        assertTrue(isSamsungDevice())
        assertEquals(480, previewReverseMaxShortSideOrNull())
    }

    @Test
    @Config(sdk = [34])
    fun lgeManufacturer_notSamsung_noPreviewCap() {
        ShadowBuild.setManufacturer("LGE")
        ShadowBuild.setBrand("lge")

        assertFalse(isSamsungDevice())
        assertNull(previewReverseMaxShortSideOrNull())
    }

    @Test
    @Config(sdk = [34])
    fun googleEmulatorIdentity_notSamsung_noPreviewCap() {
        ShadowBuild.setManufacturer("Google")
        ShadowBuild.setBrand("google")

        assertFalse(isSamsungDevice())
        assertNull(previewReverseMaxShortSideOrNull())
    }

    @Test
    @Config(sdk = [34])
    fun samsungIdentity_encoderTryOrderPrefersC2GoogleFirst() {
        ShadowBuild.setManufacturer("samsung")
        ShadowBuild.setBrand("samsung")

        val installed = setOf(
            SAMSUNG_REVERSE_AVC_SOFTWARE_ENCODER,
            "c2.exynos.h264.encoder",
            "c2.android.avc.encoder",
        )
        val formatSupported = installed.toList()

        val order = avcEncoderTryOrderForReverse(
            formatSupportedNames = formatSupported,
            installedEncoderNames = installed,
            isSamsung = isSamsungDevice(),
            isHardwareAccelerated = { true },
            sdkInt = 34,
        )

        assertEquals(
            "Samsung preview reverse must try c2.google.avc.encoder before vendor HW",
            SAMSUNG_REVERSE_AVC_SOFTWARE_ENCODER,
            order.first(),
        )
        assertFalse(
            "Samsung vendor AVC encoders must be excluded from the try-order",
            order.any { isSamsungVendorAvcCodec(it) },
        )
    }

    @Test
    @Config(sdk = [34])
    fun lgeIdentity_encoderTryOrderDoesNotForceC2GoogleFirst() {
        ShadowBuild.setManufacturer("LGE")
        ShadowBuild.setBrand("lge")

        val installed = setOf(
            SAMSUNG_REVERSE_AVC_SOFTWARE_ENCODER,
            "c2.exynos.h264.encoder",
            "c2.android.avc.encoder",
        )
        val formatSupported = installed.toList()

        val order = avcEncoderTryOrderForReverse(
            formatSupportedNames = formatSupported,
            installedEncoderNames = installed,
            isSamsung = isSamsungDevice(),
            isHardwareAccelerated = { true },
            sdkInt = 34,
        )

        assertFalse(
            "Non-Samsung must not pin c2.google.avc.encoder first — rank by preference only",
            order.first() == SAMSUNG_REVERSE_AVC_SOFTWARE_ENCODER,
        )
    }
}
