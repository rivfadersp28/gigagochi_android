package com.gigagochi.app.core.background

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gigagochi.app.core.database.GigagochiDatabase
import com.gigagochi.app.core.database.LocalCompletionNotification
import com.gigagochi.app.core.database.LocalNotificationKind
import com.gigagochi.app.core.database.PetLocalRepository
import com.gigagochi.app.core.webview.WebNotificationPermissionStatus
import com.gigagochi.app.core.webview.webNotificationPermissionStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationDeliveryInstrumentedTest {
    private lateinit var context: Context
    private lateinit var manager: NotificationManager
    private lateinit var database: GigagochiDatabase
    private lateinit var repository: PetLocalRepository

    @Before
    fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        context = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }
        manager = context.getSystemService(NotificationManager::class.java)
        manager.cancelAll()
        if (Build.VERSION.SDK_INT >= 26) {
            manager.deleteNotificationChannel(CompletionChannelId)
        }
        context.getSharedPreferences(NotificationPermissionPreferences, 0)
            .edit()
            .clear()
            .commit()
        database = Room.inMemoryDatabaseBuilder(
            context,
            GigagochiDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = PetLocalRepository(database) { Now }
    }

    @After
    fun tearDown() {
        manager.cancelAll()
        if (Build.VERSION.SDK_INT >= 26) {
            manager.deleteNotificationChannel(CompletionChannelId)
        }
        database.close()
    }

    @Test
    fun productionTextsFactoriesAndPermissionContractAreExact() {
        val requestedPermissions = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            .orEmpty()
        assertTrue(requestedPermissions.contains(Manifest.permission.POST_NOTIFICATIONS))

        assertEquals(
            LocalCompletionNotification(
                kind = LocalNotificationKind.PetReady,
                stableKey = RequestKey,
                title = "Ваш друг родился",
                body = "Скорее познакомьтесь с ним",
            ),
            petReadyNotification(RequestKey),
        )
        assertEquals(
            "Персонаж не создался" to
                "Не получилось создать персонажа, попробуй еще раз",
            ManualGenerationKind.Create.failureTitle to ManualGenerationKind.Create.failureBody,
        )
        assertEquals(
            "Переодевание не получилось" to
                "Не получилось переодеть питомца, попробуй еще раз",
            ManualGenerationKind.Outfit.failureTitle to ManualGenerationKind.Outfit.failureBody,
        )
        assertEquals(
            "Путешествие не получилось" to
                "Не получилось отправиться в путешествие, попробуй еще раз",
            ManualGenerationKind.Travel.failureTitle to ManualGenerationKind.Travel.failureBody,
        )
        assertEquals(
            "travel:$RequestKey",
            manualGenerationFailedNotification(
                ManualGenerationKind.Travel,
                RequestKey,
            ).stableKey,
        )

        assertTrue(notificationsAllowed(context))
        assertEquals(
            PackageManager.PERMISSION_GRANTED,
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS),
        )
        assertEquals(
            WebNotificationPermissionStatus.Granted,
            webNotificationPermissionStatus(context),
        )
        assertFalse(notificationPermissionWasAsked(context))
        markNotificationPermissionAsked(context)
        assertTrue(notificationPermissionWasAsked(context))

        val deniedContext = DeniedNotificationPermissionContext(context)
        assertFalse(notificationsAllowed(deniedContext))
        assertFalse(
            AndroidLocalNotificationEmitter(deniedContext).emit(
                petReadyNotification("223e4567-e89b-42d3-a456-426614174000"),
            ),
        )
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test
    fun roomOutboxAndSystemNotificationUseStableDedupExactChannelAndContent() = runBlocking {
        val notification = LocalCompletionNotification(
            kind = LocalNotificationKind.TravelReady,
            stableKey = RequestKey,
            title = "Я вернулся из путешествия",
            body = "Открой моё новое видео.",
            travelRequestKey = RequestKey,
        )
        assertTrue(repository.enqueueNotification(OwnerId, PetId, notification, Now))
        assertTrue(repository.enqueueNotification(OwnerId, PetId, notification, Now + 1L))
        assertEquals(listOf(notification), repository.getUnnotifiedNotifications(OwnerId, PetId))

        val emitter = AndroidLocalNotificationEmitter(context)
        assertTrue(emitter.emit(notification))
        assertTrue(emitter.emit(notification))

        val delivered = waitForActiveNotifications(expectedCount = 1).single()
        assertEquals(stableNotificationId(notification), delivered.id)
        assertEquals(
            notification.title,
            delivered.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
        )
        assertEquals(
            notification.body,
            delivered.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        )
        assertEquals(CompletionChannelId, delivered.notification.channelId)
        assertNotNull(delivered.notification.contentIntent)
        assertEquals(context.packageName, delivered.notification.contentIntent.creatorPackage)
        if (Build.VERSION.SDK_INT >= 31) {
            assertTrue(delivered.notification.contentIntent.isImmutable)
        }
        assertTrue(delivered.notification.flags and Notification.FLAG_AUTO_CANCEL != 0)

        if (Build.VERSION.SDK_INT >= 26) {
            val channel = requireNotNull(manager.getNotificationChannel(CompletionChannelId))
            assertEquals("Сообщения, истории и готовые медиа", channel.name.toString())
            assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
        }

        val second = notification.copy(
            stableKey = OtherRequestKey,
            travelRequestKey = OtherRequestKey,
        )
        assertNotEquals(stableNotificationId(notification), stableNotificationId(second))
        assertTrue(emitter.emit(second))
        assertEquals(2, waitForActiveNotifications(expectedCount = 2).size)

        assertTrue(repository.markNotificationSent(OwnerId, notification, Now + 2L))
        assertTrue(repository.getUnnotifiedNotifications(OwnerId, PetId).isEmpty())
    }

    private fun waitForActiveNotifications(expectedCount: Int) =
        generateSequence {
            manager.activeNotifications.toList().also { active ->
                if (active.size < expectedCount) Thread.sleep(25L)
            }
        }.take(80).first { it.size >= expectedCount }

    private class DeniedNotificationPermissionContext(base: Context) : ContextWrapper(base) {
        override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
            if (permission == Manifest.permission.POST_NOTIFICATIONS) {
                PackageManager.PERMISSION_DENIED
            } else {
                super.checkPermission(permission, pid, uid)
            }

        override fun checkCallingOrSelfPermission(permission: String): Int =
            if (permission == Manifest.permission.POST_NOTIFICATIONS) {
                PackageManager.PERMISSION_DENIED
            } else {
                super.checkCallingOrSelfPermission(permission)
            }

        override fun checkSelfPermission(permission: String): Int =
            if (permission == Manifest.permission.POST_NOTIFICATIONS) {
                PackageManager.PERMISSION_DENIED
            } else {
                super.checkSelfPermission(permission)
            }
    }

    private companion object {
        const val CompletionChannelId = "gigagochi-completions"
        const val OwnerId = "owner-notification-qa"
        const val PetId = "pet-notification-qa"
        const val RequestKey = "123e4567-e89b-42d3-a456-426614174000"
        const val OtherRequestKey = "223e4567-e89b-42d3-a456-426614174000"
        const val Now = 1_900_000_000_000L
    }
}
