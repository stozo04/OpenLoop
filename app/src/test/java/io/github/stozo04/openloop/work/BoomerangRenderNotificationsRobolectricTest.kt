package io.github.stozo04.openloop.work

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import io.github.stozo04.openloop.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Robolectric coverage for [BoomerangRenderNotifications] channel setup, progress notification
 * content, and [PendingIntent] immutability — complementing FGS-type tests in
 * [BoomerangRenderForegroundInfoRobolectricTest] and pure SDK mapping in
 * [BoomerangRenderNotificationsTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BoomerangRenderNotificationsRobolectricTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val notificationManager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)!!

    @Before
    fun deleteExportChannel() {
        notificationManager.deleteNotificationChannel(BoomerangRenderNotifications.CHANNEL_ID)
    }

    @Test
    fun ensureChannel_createsSingleImportanceLowChannel() {
        BoomerangRenderNotifications.ensureChannel(context)

        val channel = notificationManager.getNotificationChannel(BoomerangRenderNotifications.CHANNEL_ID)
        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel!!.importance)
        assertEquals(
            context.getString(R.string.notification_loop_export_channel_name),
            channel.name.toString(),
        )
        assertEquals(
            context.getString(R.string.notification_loop_export_channel_desc),
            channel.description,
        )
        assertFalse(channel.canShowBadge())
    }

    @Test
    fun ensureChannel_isIdempotent() {
        BoomerangRenderNotifications.ensureChannel(context)
        val first = notificationManager.getNotificationChannel(BoomerangRenderNotifications.CHANNEL_ID)

        BoomerangRenderNotifications.ensureChannel(context)
        val second = notificationManager.getNotificationChannel(BoomerangRenderNotifications.CHANNEL_ID)

        assertEquals(first?.importance, second?.importance)
        assertEquals(first?.name, second?.name)
    }

    @Test
    fun buildProgressNotification_clampsProgressAndSetsContent() {
        val notification = BoomerangRenderNotifications.buildProgressNotification(context, 150)

        assertEquals(100, notification.extras.getInt(Notification.EXTRA_PROGRESS))
        assertEquals(100, notification.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertEquals(
            context.getString(R.string.notification_loop_export_title),
            NotificationCompat.getContentTitle(notification)?.toString(),
        )
        assertEquals(
            context.getString(R.string.notification_loop_export_progress, 100),
            NotificationCompat.getContentText(notification)?.toString(),
        )
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
    }

    @Test
    fun buildProgressNotification_clampsNegativeProgressToZero() {
        val notification = BoomerangRenderNotifications.buildProgressNotification(context, -20)

        assertEquals(0, notification.extras.getInt(Notification.EXTRA_PROGRESS))
        assertEquals(
            context.getString(R.string.notification_loop_export_progress, 0),
            NotificationCompat.getContentText(notification)?.toString(),
        )
    }

    @Test
    fun buildProgressNotification_usesImmutablePendingIntent() {
        val notification = BoomerangRenderNotifications.buildProgressNotification(context, 50)
        val pendingIntent = requireNotNull(notification.contentIntent)

        val flags = Shadows.shadowOf(pendingIntent).flags
        assertTrue(flags and PendingIntent.FLAG_IMMUTABLE != 0)
    }

    @Test
    fun buildCompleteNotification_usesImmutablePendingIntent() {
        val notification = BoomerangRenderNotifications.buildCompleteNotification(context)
        val pendingIntent = requireNotNull(notification.contentIntent)

        val flags = Shadows.shadowOf(pendingIntent).flags
        assertTrue(flags and PendingIntent.FLAG_IMMUTABLE != 0)
    }
}
