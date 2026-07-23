package com.gigagochi.app.core.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebNavigationBackControllerTest {
    @Test
    fun `back is not handled before the active document reports readiness`() {
        val controller = WebNavigationBackController()
        val fence = BridgeDocumentFence(4L)
        controller.invalidate(fence.epoch)

        assertEquals(WebSystemBackDecision.Unhandled, controller.requestSystemBack(fence))
    }

    @Test
    fun `first ready back is dispatched and a rapid duplicate is consumed`() {
        val controller = WebNavigationBackController()
        val fence = BridgeDocumentFence(7L)
        controller.invalidate(fence.epoch)
        controller.update(fence, WebNavigationReadyPayload(canHandleBack = true, sequence = 11L))

        val first = controller.requestSystemBack(fence)

        assertTrue(first is WebSystemBackDecision.Dispatch)
        assertEquals(11L, (first as WebSystemBackDecision.Dispatch).navigationSequence)
        assertEquals(
            WebSystemBackDecision.DuplicateConsumed,
            controller.requestSystemBack(fence),
        )
    }

    @Test
    fun `newer readiness acknowledges an in-flight back and stale updates cannot revive it`() {
        val controller = WebNavigationBackController()
        val fence = BridgeDocumentFence(8L)
        controller.invalidate(fence.epoch)
        controller.update(fence, WebNavigationReadyPayload(canHandleBack = true, sequence = 20L))
        controller.requestSystemBack(fence)

        controller.update(fence, WebNavigationReadyPayload(canHandleBack = false, sequence = 21L))
        controller.update(fence, WebNavigationReadyPayload(canHandleBack = true, sequence = 20L))

        assertEquals(WebSystemBackDecision.Unhandled, controller.requestSystemBack(fence))
    }

    @Test
    fun `document invalidation clears readiness and rejects the stale fence`() {
        val controller = WebNavigationBackController()
        val staleFence = BridgeDocumentFence(12L)
        controller.invalidate(staleFence.epoch)
        controller.update(
            staleFence,
            WebNavigationReadyPayload(canHandleBack = true, sequence = 1L),
        )
        val activeFence = BridgeDocumentFence(13L)
        controller.invalidate(activeFence.epoch)

        assertEquals(
            WebSystemBackDecision.Unhandled,
            controller.requestSystemBack(staleFence),
        )
        assertEquals(
            WebSystemBackDecision.Unhandled,
            controller.requestSystemBack(activeFence),
        )
    }

    @Test
    fun `failed delivery can release the exact in-flight event for retry`() {
        val controller = WebNavigationBackController()
        val fence = BridgeDocumentFence(15L)
        controller.invalidate(fence.epoch)
        controller.update(fence, WebNavigationReadyPayload(canHandleBack = true, sequence = 3L))
        val first = controller.requestSystemBack(fence) as WebSystemBackDecision.Dispatch

        controller.releaseSystemBack("not-${first.eventId}")
        assertEquals(
            WebSystemBackDecision.DuplicateConsumed,
            controller.requestSystemBack(fence),
        )
        controller.releaseSystemBack(first.eventId)

        assertTrue(controller.requestSystemBack(fence) is WebSystemBackDecision.Dispatch)
    }
}

class WebSystemBackHostCoordinatorTest {
    @Test
    fun `host falls through only when web cannot handle back`() {
        var enqueued = false
        val coordinator = WebSystemBackHostCoordinator(
            requestSystemBack = { WebSystemBackDecision.Unhandled },
            enqueue = {
                enqueued = true
                true
            },
            releaseSystemBack = {},
        )

        assertFalse(coordinator.handleSystemBack())
        assertFalse(enqueued)
    }

    @Test
    fun `host consumes rapid duplicates without posting a second event`() {
        var enqueueCount = 0
        val coordinator = WebSystemBackHostCoordinator(
            requestSystemBack = { WebSystemBackDecision.DuplicateConsumed },
            enqueue = {
                enqueueCount += 1
                true
            },
            releaseSystemBack = {},
        )

        assertTrue(coordinator.handleSystemBack())
        assertEquals(0, enqueueCount)
    }

    @Test
    fun `host releases a reservation when its event queue is unavailable`() {
        val released = mutableListOf<String>()
        val decision = WebSystemBackDecision.Dispatch(
            eventId = "event-id",
            navigationSequence = 9L,
            documentFence = BridgeDocumentFence(2L),
        )
        val coordinator = WebSystemBackHostCoordinator(
            requestSystemBack = { decision },
            enqueue = { false },
            releaseSystemBack = released::add,
        )

        assertFalse(coordinator.handleSystemBack())
        assertEquals(listOf("event-id"), released)
    }
}
