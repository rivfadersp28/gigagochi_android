package com.gigagochi.app.core.background

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.gigagochi.app.MainActivity
import com.gigagochi.app.R
import com.gigagochi.app.core.database.LocalCompletionNotification

private const val CompletionChannelId = "gigagochi-completions"

fun notificationsAllowed(context: Context): Boolean =
    (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED) && NotificationManagerCompat.from(context)
        .areNotificationsEnabled()

class AndroidLocalNotificationEmitter(
    private val context: Context,
) : LocalNotificationEmitter {
    override fun emit(notification: LocalCompletionNotification) {
        ensureChannel()
        val notificationId = stableNotificationId(notification)
        val intent = Intent(context, MainActivity::class.java).apply {
            notification.storyId?.let { putExtra("gigagochi.storyId", it) }
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        NotificationManagerCompat.from(context).notify(
            notificationId,
            NotificationCompat.Builder(context, CompletionChannelId)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(notification.title)
                .setContentText(notification.body)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CompletionChannelId,
                "Готовые истории и медиа",
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }
}

internal fun stableNotificationId(notification: LocalCompletionNotification): Int =
    "${notification.kind}:${notification.stableKey}".hashCode() and Int.MAX_VALUE
