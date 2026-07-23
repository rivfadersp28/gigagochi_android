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
import com.gigagochi.app.GigagochiWebViewActivity
import com.gigagochi.app.R
import com.gigagochi.app.core.database.LocalCompletionNotification
import com.gigagochi.app.core.database.LocalNotificationKind

private const val CompletionChannelId = "gigagochi-completions"

internal const val PetReadyTitle = "Ваш друг родился"
internal const val PetReadyBody = "Скорее познакомьтесь с ним"

enum class ManualGenerationKind(
    val failureTitle: String,
    val failureBody: String,
) {
    Create(
        "Персонаж не создался",
        "Не получилось создать персонажа, попробуй еще раз",
    ),
    Outfit(
        "Переодевание не получилось",
        "Не получилось переодеть питомца, попробуй еще раз",
    ),
    Travel(
        "Путешествие не получилось",
        "Не получилось отправиться в путешествие, попробуй еще раз",
    ),
}

fun petReadyNotification(requestKey: String) = LocalCompletionNotification(
    kind = LocalNotificationKind.PetReady,
    stableKey = requestKey,
    title = PetReadyTitle,
    body = PetReadyBody,
)

fun manualGenerationFailedNotification(
    generation: ManualGenerationKind,
    requestKey: String,
) = LocalCompletionNotification(
    kind = LocalNotificationKind.GenerationFailed,
    stableKey = "${generation.name.lowercase()}:$requestKey",
    title = generation.failureTitle,
    body = generation.failureBody,
)

fun notificationsAllowed(context: Context): Boolean =
    (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED) && NotificationManagerCompat.from(context)
        .areNotificationsEnabled()

class AndroidLocalNotificationEmitter(
    private val context: Context,
) : LocalNotificationEmitter {
    override fun emit(notification: LocalCompletionNotification): Boolean {
        if (
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) return false
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        ensureChannel()
        val notificationId = stableNotificationId(notification)
        val intent = Intent(context, GigagochiWebViewActivity::class.java).apply {
            notification.storyId?.let { putExtra("gigagochi.storyId", it) }
            notification.travelRequestKey?.let { putExtra("gigagochi.travelRequestKey", it) }
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        try {
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
            return true
        } catch (_: SecurityException) {
            // Permission can be revoked after the explicit check.
            return false
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CompletionChannelId,
                "Сообщения, истории и готовые медиа",
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }
}

internal fun stableNotificationId(notification: LocalCompletionNotification): Int =
    "${notification.kind}:${notification.stableKey}".hashCode() and Int.MAX_VALUE
