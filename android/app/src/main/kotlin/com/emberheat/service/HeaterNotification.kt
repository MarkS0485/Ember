package com.emberheat.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.emberheat.MainActivity
import com.emberheat.R

internal object HeaterNotification {

    const val CHANNEL_ID = "heater-link"
    const val NOTIF_ID   = 1

    // Separate, higher-importance channel for one-off Pro/entitlement alerts
    // (e.g. a trial lapsing while automation was running). Kept distinct from
    // the ongoing link notification so it can pop as a heads-up and be
    // dismissed independently.
    const val PRO_CHANNEL_ID = "pro-status"
    const val PRO_NOTIF_ID   = 2

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

    private fun ensureProChannel(ctx: Context) {
        val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(PRO_CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(
                PRO_CHANNEL_ID,
                "Pro status",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Alerts when Pro features start or stop being available " +
                              "(e.g. a free trial ending)."
            }
        )
    }

    // Heads-up alert posted when Pro lapses while automation was in use, so
    // the user isn't silently left with a heater that's no longer managed.
    // [features] is a human phrase like "scheduled heating and Auto Start/Stop".
    fun notifyProLapsed(ctx: Context, features: String) {
        ensureProChannel(ctx)
        val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return
        val tap = PendingIntent.getActivity(
            ctx, 1,
            Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val body = "Pro is no longer active, so $features has stopped. Your heater is " +
                   "back under its own control. Unlock Pro to resume automatic operation."
        val notif = NotificationCompat.Builder(ctx, PRO_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Pro features paused")
            .setContentText("$features stopped — unlock Pro to resume.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
        mgr.notify(PRO_NOTIF_ID, notif)
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
