package com.gigagochi.app.debugmenu

import com.gigagochi.app.core.database.LocalNotificationKind
import org.junit.Assert.assertEquals
import org.junit.Test

class DebugPushWorkerTest {
    @Test
    fun testPushUsesOneMinuteDelayAndDedicatedCopy() {
        val notification = debugPushNotification("worker-id")

        assertEquals(1L, DebugPushDelayMinutes)
        assertEquals(LocalNotificationKind.Proactive, notification.kind)
        assertEquals("worker-id", notification.stableKey)
        assertEquals(DebugPushTitle, notification.title)
        assertEquals(DebugPushBody, notification.body)
    }
}
