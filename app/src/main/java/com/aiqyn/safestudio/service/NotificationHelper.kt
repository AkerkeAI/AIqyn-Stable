package com.aiqyn.safestudio.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.aiqyn.safestudio.MainActivity
import com.aiqyn.safestudio.R

object NotificationHelper {
    const val CHANNEL_MONITORING = "monitoring_channel"
    const val CHANNEL_DANGER = "danger_channel"
    const val MONITORING_NOTIFICATION_ID = 1001
    private const val DANGER_NOTIFICATION_BASE_ID = 2000

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(NotificationManager::class.java)
        val monitoringChannel = NotificationChannel(
            CHANNEL_MONITORING,
            "AIqyn Monitoring",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Persistent microphone monitoring status."
            setShowBadge(false)
        }
        val dangerChannel = NotificationChannel(
            CHANNEL_DANGER,
            "AIqyn Sound Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerts when YAMNet detects a configured sound event."
        }
        manager.createNotificationChannel(monitoringChannel)
        manager.createNotificationChannel(dangerChannel)
    }

    fun buildMonitoringNotification(context: Context) =
        NotificationCompat.Builder(context, CHANNEL_MONITORING)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(context.getString(R.string.notification_monitoring_title))
            .setContentText(context.getString(R.string.notification_monitoring_body))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(createLaunchIntent(context))
            .build()

    fun sendClassificationAlert(context: Context, message: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_DANGER)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(context.getString(R.string.notification_alert_title))
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(createLaunchIntent(context))
            .build()
        NotificationManagerCompat.from(context)
            .notify(DANGER_NOTIFICATION_BASE_ID + (System.currentTimeMillis() % 1000).toInt(), notification)
    }

    private fun createLaunchIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
