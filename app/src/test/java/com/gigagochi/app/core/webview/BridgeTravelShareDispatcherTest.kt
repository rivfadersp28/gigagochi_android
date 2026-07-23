package com.gigagochi.app.core.webview

import com.gigagochi.app.core.security.TravelVideoShareLookupResult
import com.gigagochi.app.feature.events.TravelVideoShareResult
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeTravelShareDispatcherTest {
    @Test
    fun `accepted share returns accepted string and forwards canonical key with document fence`() =
        runBlocking {
            val acceptedRequests = mutableListOf<WebTravelShareRequest>()
            val dispatcher = BridgeDispatcher(
                runtime = FakeRuntime(),
                travelShareHandler = WebTravelShareRequestHandler { request ->
                    acceptedRequests += request
                    WebTravelShareAcceptResult.Accepted
                },
            )
            val bridgeSessionId = bootstrapSession(dispatcher)
            val expectedFence = dispatcher.currentDocumentFence()

            val response = share(dispatcher, bridgeSessionId, requestKey = RequestKey)

            assertTrue(response.ok)
            assertEquals(JsonPrimitive("accepted"), response.result)
            assertEquals(
                listOf(WebTravelShareRequest(RequestKey, expectedFence)),
                acceptedRequests,
            )
        }

    @Test
    fun `default and invalid handlers fail safely`() = runBlocking {
        val defaultDispatcher = BridgeDispatcher(FakeRuntime())
        val defaultSession = bootstrapSession(defaultDispatcher)
        val defaultResponse = share(defaultDispatcher, defaultSession, RequestKey)

        val invalidDispatcher = BridgeDispatcher(
            runtime = FakeRuntime(),
            travelShareHandler = WebTravelShareRequestHandler {
                WebTravelShareAcceptResult.Invalid
            },
        )
        val invalidSession = bootstrapSession(invalidDispatcher)
        val invalidResponse = share(invalidDispatcher, invalidSession, RequestKey)

        listOf(defaultResponse, invalidResponse).forEach { response ->
            assertFalse(response.ok)
            assertEquals("INVALID_PAYLOAD", response.error?.code)
        }
    }

    @Test
    fun `share validates active document session before invoking handler`() = runBlocking {
        val handlerCalls = AtomicInteger()
        val dispatcher = BridgeDispatcher(
            runtime = FakeRuntime(),
            travelShareHandler = WebTravelShareRequestHandler {
                handlerCalls.incrementAndGet()
                WebTravelShareAcceptResult.Accepted
            },
        )

        val beforeBootstrap = share(
            dispatcher = dispatcher,
            bridgeSessionId = UUID.randomUUID().toString(),
            requestKey = RequestKey,
        )
        val bridgeSessionId = bootstrapSession(dispatcher)
        val staleDocument = share(
            dispatcher = dispatcher,
            bridgeSessionId = bridgeSessionId,
            requestKey = RequestKey,
            documentId = UUID.randomUUID().toString(),
        )
        val staleSession = share(
            dispatcher = dispatcher,
            bridgeSessionId = UUID.randomUUID().toString(),
            requestKey = RequestKey,
        )

        listOf(beforeBootstrap, staleDocument, staleSession).forEach { response ->
            assertFalse(response.ok)
            assertEquals("STALE_DOCUMENT", response.error?.code)
        }
        assertEquals(0, handlerCalls.get())
    }

    @Test
    fun `share rejects unknown missing and extra payload fields before handler`() = runBlocking {
        val handlerCalls = AtomicInteger()
        val dispatcher = BridgeDispatcher(
            runtime = FakeRuntime(),
            travelShareHandler = WebTravelShareRequestHandler {
                handlerCalls.incrementAndGet()
                WebTravelShareAcceptResult.Accepted
            },
        )
        val bridgeSessionId = bootstrapSession(dispatcher)
        val malformedPayloads = listOf(
            "{}",
            """{"request_key":"$RequestKey"}""",
            """{"requestKey":"$RequestKey","extra":true}""",
        )

        malformedPayloads.forEach { payload ->
            val response = response(
                dispatcher.handle(
                    requestJson(
                        method = "shareTravelVideo",
                        payload = payload,
                        bridgeSessionId = bridgeSessionId,
                    ),
                ),
            )
            assertFalse(response.ok)
            assertEquals("INVALID_PAYLOAD", response.error?.code)
        }
        assertEquals(0, handlerCalls.get())
    }

    @Test
    fun `share rejects uppercase and non v4 request keys before handler`() = runBlocking {
        val handlerCalls = AtomicInteger()
        val dispatcher = BridgeDispatcher(
            runtime = FakeRuntime(),
            travelShareHandler = WebTravelShareRequestHandler {
                handlerCalls.incrementAndGet()
                WebTravelShareAcceptResult.Accepted
            },
        )
        val bridgeSessionId = bootstrapSession(dispatcher)
        val invalidKeys = listOf(
            RequestKey.uppercase(),
            "123e4567-e89b-12d3-a456-426614174000",
            "123e4567-e89b-42d3-7456-426614174000",
        )

        invalidKeys.forEach { requestKey ->
            val response = share(dispatcher, bridgeSessionId, requestKey)
            assertFalse(response.ok)
            assertEquals("INVALID_PAYLOAD", response.error?.code)
        }
        assertEquals(0, handlerCalls.get())
    }

    @Test
    fun `dispatcher releases mutex while coordinator owns same key async dedupe`() = runBlocking {
        val resolverCalls = AtomicInteger()
        val resolverStarted = CompletableDeferred<Unit>()
        val resolverGate = CompletableDeferred<Unit>()
        val failurePresented = CompletableDeferred<Unit>()
        val parentScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = WebTravelShareCoordinator(
            scope = parentScope,
            resolver = {
                resolverCalls.incrementAndGet()
                resolverStarted.complete(Unit)
                resolverGate.await()
                TravelVideoShareLookupResult.Missing
            },
            sharer = { TravelVideoShareResult.Opened },
            presentFailure = { failurePresented.complete(Unit) },
        )
        try {
            val dispatcher = BridgeDispatcher(
                runtime = FakeRuntime(),
                travelShareHandler = coordinator,
            )
            val bridgeSessionId = bootstrapSession(dispatcher)

            val first = withTimeout(1_000L) {
                share(dispatcher, bridgeSessionId, RequestKey)
            }
            val second = withTimeout(1_000L) {
                share(dispatcher, bridgeSessionId, RequestKey)
            }
            withTimeout(1_000L) { resolverStarted.await() }

            assertTrue(first.ok)
            assertTrue(second.ok)
            assertEquals(1, resolverCalls.get())

            val unrelated = withTimeout(1_000L) {
                response(
                    dispatcher.handle(
                        requestJson(
                            method = "requestNotificationPermission",
                            payload = "{}",
                            bridgeSessionId = bridgeSessionId,
                        ),
                    ),
                )
            }
            assertTrue(unrelated.ok)

            resolverGate.complete(Unit)
            withTimeout(1_000L) { failurePresented.await() }
        } finally {
            coordinator.close()
            parentScope.cancel()
        }
    }

    private suspend fun share(
        dispatcher: BridgeDispatcher,
        bridgeSessionId: String,
        requestKey: String,
        documentId: String = DocumentId,
    ): BridgeResponseEnvelope = response(
        dispatcher.handle(
            requestJson(
                method = "shareTravelVideo",
                payload = """{"requestKey":"$requestKey"}""",
                bridgeSessionId = bridgeSessionId,
                documentId = documentId,
            ),
        ),
    )

    private suspend fun bootstrapSession(dispatcher: BridgeDispatcher): String = requireNotNull(
        response(
            dispatcher.handle(
                requestJson(
                    method = "bootstrap",
                    payload = bootstrapPayload(),
                ),
            ),
        ).bridgeSessionId,
    )

    private fun response(raw: String): BridgeResponseEnvelope =
        BridgeCodec.json.decodeFromString(raw)

    private fun requestJson(
        method: String,
        payload: String,
        documentId: String = DocumentId,
        bridgeSessionId: String? = null,
    ): String = """
        {
          "kind":"request",
          "protocolVersion":1,
          "documentId":"$documentId",
          ${bridgeSessionId?.let { "\"bridgeSessionId\":\"$it\"," }.orEmpty()}
          "requestId":"${UUID.randomUUID()}",
          "method":"$method",
          "payload":$payload
        }
    """.trimIndent()

    private fun bootstrapPayload(): String =
        """{"supportedProtocolVersions":[1],"webBundleVersion":"$BridgeWebBundleVersion","schemaHash":"$BridgeSchemaHash"}"""

    private class FakeRuntime : WebAppRuntime {
        override suspend fun snapshot() = WebAppSnapshot(
            appVersion = "test",
            webBundleVersion = BridgeWebBundleVersion,
            revision = "test-0",
            route = "dashboard",
            reducedMotion = false,
            safeArea = WebSafeAreaSnapshot(),
            notificationPermission = "unknown",
        )

        override suspend fun dispatch(command: BridgeProductCommand): WebAppSnapshot = snapshot()
    }

    private companion object {
        const val DocumentId = "123e4567-e89b-42d3-b456-426614174001"
        const val RequestKey = "123e4567-e89b-42d3-a456-426614174000"
    }
}
