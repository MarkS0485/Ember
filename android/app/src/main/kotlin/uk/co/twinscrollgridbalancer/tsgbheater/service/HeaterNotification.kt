package uk.co.twinscrollgridbalancer.tsgbheater.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import uk.co.twinscrollgridbalancer.tsgbheater.MainActivity
import uk.co.twinscrollgridbalancer.tsgbheater.R

internal object HeaterNotification {

    const val CHANNEL_ID = "heater-link"
    const val NOTIF_ID   = 1

    fun ensureChannel(ctx: Context) {
        val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Heater link",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Persistent BLE link to the heater (required for " +
                              "Auto Start/Stop and live telemetry)."
                setShowBadge(false)
            }
        )
    }

    fun build(ctx: Context, title: String, status: String): Notification {
        val tap = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(status)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(tap)
            .build()
    }
}
