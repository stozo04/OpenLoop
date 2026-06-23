package io.github.stozo04.openloop

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Robolectric coverage for the API-33 [Manifest.permission.POST_NOTIFICATIONS] gate extracted
 * from [MainActivity.maybeRequestPostNotificationsPermission] and
 * [rememberNotificationExportHint] (Tier 2 #5). ViewModel permission *state* stays in
 * [io.github.stozo04.openloop.ui.OpenLoopViewModelTest].
 */
@RunWith(RobolectricTestRunner::class)
class PostNotificationsGateRobolectricTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    @Config(sdk = [32])
    fun api32_deniedPermission_requestAndHintAreNoOp() {
        denyPostNotifications()

        val granted = isPostNotificationsGranted()
        assertFalse(shouldRequestPostNotificationsPermission(Build.VERSION.SDK_INT, granted))
        assertFalse(shouldShowNotificationExportHint(Build.VERSION.SDK_INT, granted))
    }

    @Test
    @Config(sdk = [32])
    fun api32_grantedPermission_requestAndHintAreNoOp() {
        grantPostNotifications()

        val granted = isPostNotificationsGranted()
        assertFalse(shouldRequestPostNotificationsPermission(Build.VERSION.SDK_INT, granted))
        assertFalse(shouldShowNotificationExportHint(Build.VERSION.SDK_INT, granted))
    }

    @Test
    @Config(sdk = [33])
    fun api33_deniedPermission_gateIsActive() {
        denyPostNotifications()

        val granted = isPostNotificationsGranted()
        assertTrue(shouldRequestPostNotificationsPermission(Build.VERSION.SDK_INT, granted))
        assertTrue(shouldShowNotificationExportHint(Build.VERSION.SDK_INT, granted))
    }

    @Test
    @Config(sdk = [33])
    fun api33_grantedPermission_gateIsInactive() {
        grantPostNotifications()

        val granted = isPostNotificationsGranted()
        assertFalse(shouldRequestPostNotificationsPermission(Build.VERSION.SDK_INT, granted))
        assertFalse(shouldShowNotificationExportHint(Build.VERSION.SDK_INT, granted))
    }

    private fun isPostNotificationsGranted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun grantPostNotifications() {
        Shadows.shadowOf(context.applicationContext as Application)
            .grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun denyPostNotifications() {
        Shadows.shadowOf(context.applicationContext as Application)
            .denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }
}
