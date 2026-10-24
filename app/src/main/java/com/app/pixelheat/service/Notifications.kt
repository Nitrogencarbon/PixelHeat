package com.app.pixelheat.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.app.pixelheat.MainActivity
import com.app.pixelheat.R
import com.app.pixelheat.model.PhoneTempSource
import com.app.pixelheat.model.ThermalStatus
import com.app.pixelheat.util.formatDuration
import com.app.pixelheat.util.formatTemp

object Notifications {
    const val CHANNEL_MONITOR = "monitor"
    const val CHANNEL_ALERTS = "alerts"
    const val ID_MONITOR = 1001
    const val ID_ALERT = 1002

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(
                    CHANNEL_MONITOR,
                    context.getString(R.string.channel_monitor_name),
                    NotificationManager.IMPORTANCE_MIN,
                ).apply {
                    description = context.getString(R.string.channel_monitor_desc)
                    setShowBadge(false)
                },
                NotificationChannel(
                    CHANNEL_ALERTS,
                    context.getString(R.string.channel_alerts_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = context.getString(R.string.channel_alerts_desc) },
            ),
        )
    }

    fun monitor(context: Context, title: String, text: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_MONITOR)
            .setSmallIcon(R.drawable.ic_stat_heat)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .setContentIntent(openApp(context))
            .build()

    /**
     * Ongoing score. Identical text is skipped so a live 2-second refresh does not repost the same number.
     */
    fun showScore(
        context: Context,
        temp: Float,
        source: PhoneTempSource,
        status: Int,
        intervalMs: Long,
    ) {
        if (!temp.isFinite()) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val title = "${formatTemp(temp)} · ${ThermalStatus.label(status)}"
        val text = "${source.label} · next check in ${formatDuration(intervalMs)}"
        val key = "$title\u0000$text"
        synchronized(this) {
            if (key == lastScoreKey) return
            val posted = runCatching {
                NotificationManagerCompat.from(context).notify(ID_MONITOR, monitor(context, title, text))
            }.isSuccess
            if (posted) lastScoreKey = key
        }
    }

    fun alert(context: Context, title: String, text: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_heat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()

    private var lastScoreKey: String? = null

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
