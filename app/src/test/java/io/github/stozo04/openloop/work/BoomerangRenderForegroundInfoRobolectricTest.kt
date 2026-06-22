package io.github.stozo04.openloop.work

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Device-free reproduction of Crashlytics 9663c743 (Galaxy A55 / Android 14, v1.0.23) using
 * Robolectric to run the **real** [BoomerangRenderNotifications.createForegroundInfo] path under a
 * specific API level (`@Config(sdk = [...])`).
 *
 * Unlike the pure-function test, this builds the actual [androidx.work.ForegroundInfo] the worker
 * hands WorkManager — notification, channel, and the foreground-service type WorkManager passes to
 * `startForeground()`. On API 34 that type must NOT be `mediaProcessing` (8192); the OS rejects it
 * as unknown and the FGS crashes. Asserting `ForegroundInfo.foregroundServiceType` here exercises
 * the same value that crashed in production.
 *
 * Runs on JDK 21 (configured in app/build.gradle.kts) so Robolectric can load the SDK-36 jar.
 */
@RunWith(RobolectricTestRunner::class)
class BoomerangRenderForegroundInfoRobolectricTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    @Config(sdk = [34])
    fun android14_foregroundInfo_isDataSync_reproducesAndProvesFix() {
        val info = BoomerangRenderNotifications.createForegroundInfo(context, progressPercent = 0)

        // The exact production value that crashed v1.0.23. Pre-fix this was 8192 (mediaProcessing),
        // which Android 14 rejects with InvalidForegroundServiceTypeException ("type unknown").
        assertEquals(
            "Android 14 FGS must be dataSync (1)",
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            info.foregroundServiceType,
        )
        assertNotEquals(
            "Android 14 FGS must never be mediaProcessing (8192) — the OS aborts the service",
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
            info.foregroundServiceType,
        )
    }

    @Test
    @Config(sdk = [35])
    fun android15_foregroundInfo_isMediaProcessing() {
        val info = BoomerangRenderNotifications.createForegroundInfo(context, progressPercent = 0)
        assertEquals(
            "Android 15+ supports and should request mediaProcessing (8192)",
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
            info.foregroundServiceType,
        )
    }

    @Test
    @Config(sdk = [36])
    fun android16_foregroundInfo_isMediaProcessing() {
        val info = BoomerangRenderNotifications.createForegroundInfo(context, progressPercent = 0)
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
            info.foregroundServiceType,
        )
    }
}
