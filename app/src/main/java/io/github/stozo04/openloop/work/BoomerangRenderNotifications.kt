package io.github.stozo04.openloop.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import io.github.stozo04.openloop.MainActivity
import io.github.stozo04.openloop.R

/**
 * Low-importance "Loop export" channel + progress notifications for [BoomerangRenderWorker].
 * https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running
 */
object BoomerangRenderNotifications {

    const val CHANNEL_ID = "loop_export"
    const val NOTIFICATION_ID = 40

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_loop_export_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_loop_export_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun buildProgressNotification(context: Context, progressPercent: Int): Notification {
        ensureChannel(context)
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val clamped = progressPercent.coerceIn(0, 100)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(context.getString(R.string.notification_loop_export_title))
            .setContentText(context.getString(R.string.notification_loop_export_progress, clamped))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(100, clamped, false)
            .build()
    }

    fun buildCompleteNotification(context: Context): Notification {
        ensureChannel(context)
        val contentIntent = PendingIntent.getActivity(
            context,
            1,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(context.getString(R.string.notification_loop_export_complete_title))
            .setContentText(context.getString(R.string.notification_loop_export_complete_text))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun createForegroundInfo(context: Context, progressPercent: Int): ForegroundInfo {
        val notification = buildProgressNotification(context, progressPercent)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, foregroundServiceTypeForSdk(Build.VERSION.SDK_INT))
        } else {
            @Suppress("DEPRECATION")
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    /**
     * Foreground-service type to pass to `startForeground()` for the OS version actually running.
     *
     * `mediaProcessing` ([ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING] = 8192) is only a
     * *recognized* FGS type from **API 35 (Android 15)** — the version that ADDED it. Passing it on
     * API 34 (Android 14) makes the platform reject it as an unknown type and abort the service with
     * `InvalidForegroundServiceTypeException: Starting FGS with type unknown ... has been prohibited`
     * — a fatal crash (Crashlytics 9663c743…, Samsung Galaxy A55 / Android 14, v1.0.23). The original
     * code gated this on `UPSIDE_DOWN_CAKE` (API 34), one version too low: the constant *compiles*
     * against compileSdk 36 but the value isn't honored by an API-34 device's service validator.
     *
     * On API 29–34 fall back to `dataSync` ([ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC] = 1,
     * added API 29) — Google's documented type for "import/export … transfer over network" work and
     * the type WorkManager's own long-running-worker sample uses. Both types are declared on the
     * merged `SystemForegroundService` in the manifest, so the requested type is always a subset of
     * what's declared. Below API 29 there is no typed FGS, so 0 (untyped).
     */
    // InlinedApi: the FGS-type constants (DATA_SYNC API 29, MEDIA_PROCESSING API 35) are above
    // minSdk 26, but they're compile-time int literals chosen behind an explicit SDK_INT guard here,
    // so inlining them is exactly the intent.
    @VisibleForTesting
    @Suppress("InlinedApi")
    fun foregroundServiceTypeForSdk(sdkInt: Int): Int = when {
        sdkInt >= Build.VERSION_CODES.VANILLA_ICE_CREAM ->
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        sdkInt >= Build.VERSION_CODES.Q -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        else -> 0
    }
}
