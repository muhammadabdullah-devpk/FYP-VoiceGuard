package com.voiceguard.app.audio

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.voiceguard.app.MainActivity
import com.voiceguard.app.R
import com.voiceguard.app.data.SettingsStore
import com.voiceguard.app.model.AlertEvent

/** Notifications, vibration and the floating banner that warn the user during a call. */
object AlertNotifier {

    const val CHANNEL_LIVE = "voiceguard_live_channel"
    const val CHANNEL_ALERT = "voiceguard_alert_channel"
    const val ID_LIVE = 101
    const val ID_ALERT = 102

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_LIVE,
                context.getString(R.string.channel_live),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        val alertChannel = NotificationChannel(
            CHANNEL_ALERT,
            context.getString(R.string.channel_alert),
            NotificationManager.IMPORTANCE_HIGH
        )
        alertChannel.enableVibration(true)
        manager.createNotificationChannel(alertChannel)
    }

    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** The ongoing notification of the foreground service. */
    fun buildOngoing(context: Context, text: String): Notification {
        val stopIntent = Intent(context, VoiceGuardService::class.java).setAction(VoiceGuardService.ACTION_STOP)
        val stopPending = PendingIntent.getService(
            context, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(context, CHANNEL_LIVE)
            .setContentTitle(context.getString(R.string.notif_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent(context))
            .addAction(0, context.getString(R.string.notif_stop), stopPending)
            .build()
    }

    /** Shows the warning: heads-up notification, vibration and (optionally) the floating banner. */
    fun showAlert(context: Context, event: AlertEvent, settings: SettingsStore) {
        val percent = (event.confidence * 100).toInt()
        val title = context.getString(R.string.alert_title)
        val detail = context.getString(R.string.alert_detail, percent, event.trend.label)
        val reminder = settings.familyReminder

        val canNotify = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (canNotify) {
            val notification = NotificationCompat.Builder(context, CHANNEL_ALERT)
                .setContentTitle(title)
                .setContentText("$detail $reminder")
                .setStyle(NotificationCompat.BigTextStyle().bigText("$detail\n${event.reason}\n$reminder"))
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(openAppIntent(context))
                .build()
            context.getSystemService(NotificationManager::class.java)?.notify(ID_ALERT, notification)
        }

        if (settings.vibrate) vibrate(context)
        if (settings.overlayEnabled) OverlayBanner.show(context, title, detail, reminder)
    }

    private fun vibrate(context: Context) {
        val vibrator = context.getSystemService(Vibrator::class.java) ?: return
        if (!vibrator.hasVibrator()) return
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400, 200, 400), -1))
    }
}
