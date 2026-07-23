package com.gigagochi.app.core.webview

import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebPlatformEventsTest {
    @Test
    fun `permission status follows pre 33 granted and asked denied semantics`() {
        assertEquals(
            WebNotificationPermissionStatus.Granted,
            resolveWebNotificationPermissionStatus(
                sdkInt = 32,
                permissionGranted = false,
                asked = true,
            ),
        )
        assertEquals(
            WebNotificationPermissionStatus.Granted,
            resolveWebNotificationPermissionStatus(
                sdkInt = 33,
                permissionGranted = true,
                asked = false,
            ),
        )
        assertEquals(
            WebNotificationPermissionStatus.Denied,
            resolveWebNotificationPermissionStatus(
                sdkInt = 33,
                permissionGranted = false,
                asked = true,
            ),
        )
        assertEquals(
            WebNotificationPermissionStatus.Unknown,
            resolveWebNotificationPermissionStatus(
                sdkInt = 33,
                permissionGranted = false,
                asked = false,
            ),
        )
    }

    @Test
    fun `permission prompt result is published to the latest fenced document without relaunch`() {
        val coordinator = WebNotificationPermissionCoordinator()
        val firstFence = BridgeDocumentFence(4L)
        val latestFence = BridgeDocumentFence(5L)

        assertTrue(
            coordinator.request(firstFence, WebNotificationPermissionStatus.Unknown) is
                WebNotificationPermissionDecision.LaunchPrompt,
        )
        assertEquals(
            WebNotificationPermissionDecision.AwaitResult,
            coordinator.request(latestFence, WebNotificationPermissionStatus.Denied),
        )

        assertEquals(
            WebPermissionPublication(
                latestFence,
                WebNotificationPermissionStatus.Granted,
            ),
            coordinator.result(granted = true),
        )
        assertNull(coordinator.result(granted = false))
    }

    @Test
    fun `known permission publishes immediately without prompt`() {
        val coordinator = WebNotificationPermissionCoordinator()
        val fence = BridgeDocumentFence(8L)

        val decision = coordinator.request(fence, WebNotificationPermissionStatus.Denied)

        assertEquals(
            WebNotificationPermissionDecision.Publish(
                WebPermissionPublication(fence, WebNotificationPermissionStatus.Denied),
            ),
            decision,
        )
    }

    @Test
    fun `lifecycle controller emits only real pause resume transitions`() {
        val controller = WebLifecycleEventController()
        controller.markObserverReady()

        val foreground = requireNotNull(controller.transition(WebLifecycleState.Foreground))
        assertNull(controller.transition(WebLifecycleState.Foreground))
        val background = requireNotNull(controller.transition(WebLifecycleState.Background))
        val resumed = requireNotNull(controller.transition(WebLifecycleState.Foreground))

        assertEquals("lifecycleChanged", foreground.type)
        assertEquals(
            "foreground",
            BridgeCodec.json.decodeFromJsonElement<WebLifecycleChangedPayload>(foreground.payload)
                .state,
        )
        assertEquals(
            "background",
            BridgeCodec.json.decodeFromJsonElement<WebLifecycleChangedPayload>(background.payload)
                .state,
        )
        assertEquals(
            "foreground",
            BridgeCodec.json.decodeFromJsonElement<WebLifecycleChangedPayload>(resumed.payload)
                .state,
        )
    }

    @Test
    fun `replacement document receives the current background lifecycle again`() {
        val controller = WebLifecycleEventController()
        controller.markObserverReady()
        val first = requireNotNull(controller.transition(WebLifecycleState.Background))
        assertNull(controller.transition(WebLifecycleState.Background))

        controller.resetForDocument()
        controller.markObserverReady()

        val replacement = requireNotNull(
            controller.transition(WebLifecycleState.Background),
        )
        assertEquals(first, replacement)
        assertNull(controller.transition(WebLifecycleState.Background))
    }

    @Test
    fun `background between native bootstrap and posted response is replayed after observer ready`() {
        val controller = WebLifecycleEventController()

        // BridgeDispatcher has established its native session, but the bootstrap response has not
        // reached JS yet. Consuming this transition here would make the later replay a duplicate.
        assertNull(controller.transition(WebLifecycleState.Background))
        assertFalse(controller.isObserverReady())

        // GigagochiWebViewHost opens the fence only after postMessage(response) succeeds.
        controller.markObserverReady()
        val replay = requireNotNull(controller.transition(WebLifecycleState.Background))

        assertTrue(controller.isObserverReady())
        assertEquals(
            "background",
            BridgeCodec.json.decodeFromJsonElement<WebLifecycleChangedPayload>(replay.payload).state,
        )
        assertNull(controller.transition(WebLifecycleState.Background))
    }

    @Test
    fun `abandoned stale generation cannot close replacement observer gate`() {
        val controller = WebLifecycleEventController()
        controller.markObserverReady()
        requireNotNull(controller.transition(WebLifecycleState.Background))

        controller.abandonTransition(eventGeneration = 4L, currentGeneration = 5L)

        assertTrue(controller.isObserverReady())
        val resumed = requireNotNull(controller.transition(WebLifecycleState.Foreground))
        assertEquals(
            "foreground",
            BridgeCodec.json.decodeFromJsonElement<WebLifecycleChangedPayload>(resumed.payload).state,
        )
    }

    @Test
    fun `travel share host event has the exact terminal payload`() {
        listOf(
            WebTravelShareCompletionStatus.Opened,
            WebTravelShareCompletionStatus.Failed,
        ).forEach { status ->
            val expected = WebTravelShareCompletionPayload(RequestKey, status)

            val event = webTravelShareCompletedEvent(expected)

            assertEquals("travelShareCompleted", event.type)
            assertEquals(setOf("requestKey", "status"), event.payload.jsonObject.keys)
            assertEquals(
                expected,
                BridgeCodec.json.decodeFromJsonElement<WebTravelShareCompletionPayload>(
                    event.payload,
                ),
            )
        }
    }

    private companion object {
        const val RequestKey = "123e4567-e89b-42d3-a456-426614174000"
    }
}
