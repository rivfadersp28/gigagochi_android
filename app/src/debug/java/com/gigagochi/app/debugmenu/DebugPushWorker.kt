package com.gigagochi.app.debugmenu

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gigagochi.app.core.background.AndroidLocalNotificationEmitter
import com.gigagochi.app.core.database.LocalCompletionNotification
import com.gigagochi.app.core.database.LocalNotificationKind
import java.util.concurrent.TimeUnit

internal const val DebugPushDelayMinutes = 1L
internal const val DebugPushTitle = "Тестовый пуш"
internal const val DebugPushBody = "WorkManager доставил уведомление через 1 минуту"

fun scheduleDebugPush(context: Context) {
    val request = OneTimeWorkRequestBuilder<DebugPushWorker>()
        .setInitialDelay(DebugPushDelayMinutes, TimeUnit.MINUTES)
        .addTag("gigagochi-debug-push")
        .build()
    WorkManager.getInstance(context).enqueue(request)
    recordDebugEvent(
        kind = "notification",
        title = "Тестовый пуш запланирован",
        text = "WorkRequest ${request.id} будет готов к запуску через 1 минуту.",
    )
}

class DebugPushWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val delivered = AndroidLocalNotificationEmitter(applicationContext).emit(
            debugPushNotification(id.toString()),
        )
        recordDebugEvent(
            kind = if (delivered) "notification" else "error",
            title = if (delivered) "Тестовый пуш отправлен" else "Тестовый пуш не отправлен",
            text = if (delivered) id.toString() else "Нет разрешения или уведомления отключены в системе.",
        )
        return Result.success()
    }
}

internal fun debugPushNotification(stableKey: String) = LocalCompletionNotification(
    kind = LocalNotificationKind.Proactive,
    stableKey = stableKey,
    title = DebugPushTitle,
    body = DebugPushBody,
)
