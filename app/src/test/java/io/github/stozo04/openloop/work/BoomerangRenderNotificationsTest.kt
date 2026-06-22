package io.github.stozo04.openloop.work

import android.content.pm.ServiceInfo
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Regression guard for Crashlytics issue 9663c743 (Samsung Galaxy A55 / Android 14, v1.0.23):
 *
 *   Fatal: java.lang.RuntimeException: Unable to start service SystemForegroundService …
 *   Caused by: android.app.InvalidForegroundServiceTypeException: Starting FGS with type unknown
 *              … targetSDK=36 has been prohibited
 *
 * The Loopify render runs under a WorkManager foreground service. v1.0.23 always passed
 * `mediaProcessing` ([ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING] = 8192) once the device
 * was API ≥ 34, but that type was only ADDED in API 35 (Android 15). On an Android 14 device the
 * platform doesn't recognize the type and aborts the service — a fatal crash.
 *
 * These tests pin the version → FGS-type contract. The FGS-type and SDK constants are compile-time
 * `int`s (inlined by the compiler), so this runs as a plain JVM unit test with no Robolectric.
 */
class BoomerangRenderNotificationsTest {

    @Test
    fun android14_usesDataSync_notMediaProcessing() {
        // The exact crashing condition: API 34 must NOT receive the API-35-only mediaProcessing type.
        val type = BoomerangRenderNotifications.foregroundServiceTypeForSdk(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)

        assertEquals(
            "Android 14 (API 34) must use dataSync (1), the type that actually exists on that OS",
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            type,
        )
        assertNotEquals(
            "Android 14 must never be handed mediaProcessing (8192) — the OS rejects it as unknown",
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
            type,
        )
    }

    @Test
    fun android15AndAbove_usesMediaProcessing() {
        for (sdk in intArrayOf(Build.VERSION_CODES.VANILLA_ICE_CREAM, 36, 37)) {
            assertEquals(
                "API $sdk supports mediaProcessing and should use it",
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
                BoomerangRenderNotifications.foregroundServiceTypeForSdk(sdk),
            )
        }
    }

    @Test
    fun api29Through34_useDataSync() {
        // dataSync was added in API 29 (Q); it is the broadest valid type before mediaProcessing.
        for (sdk in Build.VERSION_CODES.Q..Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            assertEquals(
                "API $sdk should fall back to dataSync",
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                BoomerangRenderNotifications.foregroundServiceTypeForSdk(sdk),
            )
        }
    }

    @Test
    fun belowApi29_isUntyped() {
        // minSdk is 26; typed foreground services don't exist before API 29, so the worker uses the
        // untyped ForegroundInfo constructor and this helper's value is 0.
        for (sdk in 26..(Build.VERSION_CODES.Q - 1)) {
            assertEquals(
                "API $sdk predates typed FGS and must be untyped (0)",
                0,
                BoomerangRenderNotifications.foregroundServiceTypeForSdk(sdk),
            )
        }
    }
}
