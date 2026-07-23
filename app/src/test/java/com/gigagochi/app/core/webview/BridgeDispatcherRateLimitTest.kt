package com.gigagochi.app.core.webview

import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeDispatcherRateLimitTest {
    @Test
    fun `monotonic refill survives signed nano time wraparound`() {
        val clock = MutableBridgeClock(Long.MAX_VALUE - NanosPerToken / 2L)
        val limiter = BridgeRequestRateLimiter(
            clock = clock,
            burst = 1,
            sustainedPerSecond = BridgeRateLimitSustainedPerSecond,
        )

        assertTrue(limiter.tryAcquire())
        assertFalse(limiter.tryAcquire())
        clock.advance(NanosPerToken)

        assertTrue(limiter.tryAcquire())
        assertFalse(limiter.tryAcquire())
    }

    @Test
    fun `backwards monotonic sample cannot mint tokens`() {
        val clock = MutableBridgeClock(100L)
        val limiter = BridgeRequestRateLimiter(
            clock = clock,
            burst = 1,
            sustainedPerSecond = BridgeRateLimitSustainedPerSecond,
        )

        assertTrue(limiter.tryAcquire())
        clock.set(50L)
        assertFalse(limiter.tryAcquire())
        clock.set(100L + NanosPerToken)

        assertTrue(limiter.tryAcquire())
    }

    @Test
    fun `burst edge is exact and fractional refill is deterministic`() = runBlocking {
        val clock = MutableBridgeClock()
        val dispatcher = BridgeDispatcher(CountingRuntime(), monotonicClock = clock)

        repeat(BridgeRateLimitBurst) {
            assertError(dispatcher.handle(requestJson(method = "unknown")), "UNSUPPORTED_METHOD")
        }
        assertRateLimited(dispatcher.handle(requestJson(method = "unknown")))

        clock.advance(NanosPerToken - 1L)
        assertRateLimited(dispatcher.handle(requestJson(method = "unknown")))
        clock.advance(1L)
        assertError(dispatcher.handle(requestJson(method = "unknown")), "UNSUPPORTED_METHOD")
        assertRateLimited(dispatcher.handle(requestJson(method = "unknown")))

        clock.advance(NanosPerSecond * BridgeRateLimitBurst / BridgeRateLimitSustainedPerSecond)
        repeat(BridgeRateLimitBurst) {
            assertError(dispatcher.handle(requestJson(method = "unknown")), "UNSUPPORTED_METHOD")
        }
        assertRateLimited(dispatcher.handle(requestJson(method = "unknown")))
    }

    @Test
    fun `transport duplicate replays before rate admission and consumes no second token`() =
        runBlocking {
            val dispatcher = BridgeDispatcher(CountingRuntime(), monotonicClock = MutableBridgeClock())
            val requestId = UUID.randomUUID().toString()
            val duplicate = requestJson(method = "unknown", requestId = requestId)

            val first = dispatcher.handle(duplicate)
            repeat(BridgeRateLimitBurst - 1) {
                assertError(dispatcher.handle(requestJson(method = "unknown")), "UNSUPPORTED_METHOD")
            }

            assertEquals(first, dispatcher.handle(duplicate))
            assertRateLimited(dispatcher.handle(requestJson(method = "unknown")))
        }

    @Test
    fun `rate limited receipt stays deterministic while a fresh request can use refilled budget`() =
        runBlocking {
            val clock = MutableBridgeClock()
            val dispatcher = BridgeDispatcher(CountingRuntime(), monotonicClock = clock)
            repeat(BridgeRateLimitBurst) {
                assertError(dispatcher.handle(requestJson(method = "unknown")), "UNSUPPORTED_METHOD")
            }
            val requestId = UUID.randomUUID().toString()
            val limitedRequest = requestJson(method = "unknown", requestId = requestId)
            val limited = dispatcher.handle(limitedRequest)
            assertRateLimited(limited)

            clock.advance(NanosPerToken)

            assertEquals(limited, dispatcher.handle(limitedRequest))
            assertError(dispatcher.handle(requestJson(method = "unknown")), "UNSUPPORTED_METHOD")
        }

    @Test
    fun `pre-dispatch ingress rejection is replayed under the same request id`() = runBlocking {
        val clock = MutableBridgeClock()
        val runtime = CountingRuntime()
        val dispatcher = BridgeDispatcher(runtime, monotonicClock = clock)
        val raw = requestJson(method = "bootstrap", payload = "{}")
        val rejected = dispatcher.ingressFailure(
            raw = raw,
            code = "BRIDGE_QUEUE_FULL",
            retryable = true,
        )

        clock.advance(NanosPerSecond)

        assertEquals(rejected, dispatcher.handle(raw))
        assertError(rejected, "BRIDGE_QUEUE_FULL")
        assertEquals(0, runtime.snapshotCalls)
    }

    @Test
    fun `bootstrap document id rotation cannot replenish the native generation bucket`() =
        runBlocking {
            val clock = MutableBridgeClock()
            val runtime = CountingRuntime()
            val dispatcher = BridgeDispatcher(runtime, monotonicClock = clock)
            val firstDocument = UUID.randomUUID().toString()
            val secondDocument = UUID.randomUUID().toString()
            val thirdDocument = UUID.randomUUID().toString()

            assertTrue(response(dispatcher.handle(bootstrapRequest(firstDocument))).ok)
            repeat(BridgeRateLimitBurst - 2) {
                assertError(
                    dispatcher.handle(requestJson(method = "unknown", documentId = firstDocument)),
                    "UNSUPPORTED_METHOD",
                )
            }
            assertTrue(response(dispatcher.handle(bootstrapRequest(secondDocument))).ok)
            assertRateLimited(dispatcher.handle(bootstrapRequest(thirdDocument)))

            assertEquals(2, runtime.snapshotCalls)
        }

    @Test
    fun `native document invalidation resets a depleted bucket`() = runBlocking {
        val clock = MutableBridgeClock()
        val runtime = CountingRuntime()
        val dispatcher = BridgeDispatcher(runtime, monotonicClock = clock)

        repeat(BridgeRateLimitBurst) {
            assertError(dispatcher.handle(requestJson(method = "unknown")), "UNSUPPORTED_METHOD")
        }
        assertRateLimited(dispatcher.handle(bootstrapRequest(UUID.randomUUID().toString())))

        dispatcher.invalidateDocument()

        val bootstrap = response(
            dispatcher.handle(bootstrapRequest(UUID.randomUUID().toString())),
        )
        assertTrue(bootstrap.ok)
        assertEquals(1, runtime.snapshotCalls)
    }

    @Test
    fun `unknown methods and malformed sessions both consume the same budget`() = runBlocking {
        val clock = MutableBridgeClock()
        val feedback = mutableListOf<WebFeedbackKind>()
        val dispatcher = BridgeDispatcher(
            runtime = CountingRuntime(),
            feedbackHandler = WebFeedbackHandler(feedback::add),
            monotonicClock = clock,
        )
        val documentId = UUID.randomUUID().toString()
        val sessionId = requireNotNull(
            response(dispatcher.handle(bootstrapRequest(documentId))).bridgeSessionId,
        )

        repeat(BridgeRateLimitBurst - 2) {
            assertError(
                dispatcher.handle(requestJson(method = "unknown", documentId = documentId)),
                "UNSUPPORTED_METHOD",
            )
        }
        assertError(
            dispatcher.handle(
                requestJson(
                    method = "feedback",
                    payload = feedbackPayload(),
                    documentId = documentId,
                    bridgeSessionId = UUID.randomUUID().toString(),
                ),
            ),
            "STALE_DOCUMENT",
        )
        assertRateLimited(
            dispatcher.handle(
                requestJson(
                    method = "feedback",
                    payload = feedbackPayload(),
                    documentId = documentId,
                    bridgeSessionId = sessionId,
                ),
            ),
        )
        assertTrue(feedback.isEmpty())
    }

    @Test
    fun `limited methods cannot reach runtime or native side effect handlers`() = runBlocking {
        val clock = MutableBridgeClock()
        val runtime = CountingRuntime()
        val feedback = mutableListOf<WebFeedbackKind>()
        val permissionFences = mutableListOf<BridgeDocumentFence>()
        val sharedKeys = mutableListOf<String?>()
        val dispatcher = BridgeDispatcher(
            runtime = runtime,
            feedbackHandler = WebFeedbackHandler(feedback::add),
            notificationPermissionHandler = WebNotificationPermissionRequestHandler(
                permissionFences::add,
            ),
            travelShareHandler = WebTravelShareRequestHandler { request ->
                sharedKeys += request.requestKey
                WebTravelShareAcceptResult.Accepted
            },
            monotonicClock = clock,
        )
        val documentId = UUID.randomUUID().toString()
        val sessionId = requireNotNull(
            response(dispatcher.handle(bootstrapRequest(documentId))).bridgeSessionId,
        )
        repeat(BridgeRateLimitBurst - 1) {
            assertError(
                dispatcher.handle(requestJson(method = "unknown", documentId = documentId)),
                "UNSUPPORTED_METHOD",
            )
        }

        val limitedRequests = listOf(
            bootstrapRequest(documentId),
            requestJson(
                method = "dispatch",
                payload = productCommandPayload(),
                documentId = documentId,
                bridgeSessionId = sessionId,
            ),
            requestJson(
                method = "navigationReady",
                payload = """{"canHandleBack":true,"sequence":1}""",
                documentId = documentId,
                bridgeSessionId = sessionId,
            ),
            requestJson(
                method = "feedback",
                payload = feedbackPayload(),
                documentId = documentId,
                bridgeSessionId = sessionId,
            ),
            requestJson(
                method = "requestNotificationPermission",
                documentId = documentId,
                bridgeSessionId = sessionId,
            ),
            requestJson(
                method = "shareTravelVideo",
                payload = """{"requestKey":"${UUID.randomUUID()}"}""",
                documentId = documentId,
                bridgeSessionId = sessionId,
            ),
            requestJson(method = "unknown", documentId = documentId),
        )
        limitedRequests.forEach { raw -> assertRateLimited(dispatcher.handle(raw)) }

        assertEquals(1, runtime.snapshotCalls)
        assertEquals(0, runtime.dispatchCalls)
        assertTrue(feedback.isEmpty())
        assertTrue(permissionFences.isEmpty())
        assertTrue(sharedKeys.isEmpty())
        assertEquals(WebSystemBackDecision.Unhandled, dispatcher.requestSystemBack())
    }

    @Test
    fun `concurrent decoded requests cannot oversubscribe the bucket`() = runBlocking {
        val clock = MutableBridgeClock()
        val runtime = CountingRuntime()
        val dispatcher = BridgeDispatcher(runtime, monotonicClock = clock)
        val requestCount = BridgeRateLimitBurst + 64

        val responses = coroutineScope {
            val start = CompletableDeferred<Unit>()
            val jobs = List(requestCount) {
                async(Dispatchers.Default) {
                    start.await()
                    response(dispatcher.handle(requestJson(method = "unknown")))
                }
            }
            start.complete(Unit)
            jobs.awaitAll()
        }

        assertEquals(
            BridgeRateLimitBurst,
            responses.count { it.error?.code == "UNSUPPORTED_METHOD" },
        )
        assertEquals(64, responses.count { it.error?.code == "RATE_LIMITED" })
        assertTrue(
            responses.filter { it.error?.code == "RATE_LIMITED" }
                .all { it.error?.retryable == true },
        )
        assertEquals(0, runtime.snapshotCalls)
        assertEquals(0, runtime.dispatchCalls)
    }

    private class MutableBridgeClock(initialNanos: Long = 0L) : BridgeMonotonicClock {
        private var nanos = initialNanos

        override fun nowNanos(): Long = nanos

        fun advance(deltaNanos: Long) {
            require(deltaNanos >= 0L)
            nanos += deltaNanos
        }

        fun set(value: Long) {
            nanos = value
        }
    }

    private class CountingRuntime : WebAppRuntime {
        var snapshotCalls = 0
        var dispatchCalls = 0

        override suspend fun snapshot(): WebAppSnapshot {
            snapshotCalls += 1
            return snapshot("rate-$snapshotCalls")
        }

        override suspend fun dispatch(command: BridgeProductCommand): WebAppSnapshot {
            dispatchCalls += 1
            return snapshot("rate-dispatch-$dispatchCalls")
        }

        private fun snapshot(revision: String) = WebAppSnapshot(
            appVersion = "test",
            webBundleVersion = BridgeWebBundleVersion,
            revision = revision,
            route = "dashboard",
            reducedMotion = false,
            safeArea = WebSafeAreaSnapshot(),
            notificationPermission = "unknown",
            pet = WebPetSnapshot(
                name = "Тото",
                stageLabel = "Уровень 1",
                experience = 0,
                hunger = 100,
                happiness = 100,
                energy = 100,
                message = "Привет",
                petTapProgress = 0,
                media = WebPetMediaSnapshot(),
            ),
        )
    }

    private fun bootstrapRequest(documentId: String): String = requestJson(
        method = "bootstrap",
        payload = """{"supportedProtocolVersions":[1],"webBundleVersion":"$BridgeWebBundleVersion","schemaHash":"$BridgeSchemaHash"}""",
        documentId = documentId,
    )

    private fun requestJson(
        method: String,
        payload: String = "{}",
        documentId: String = UUID.randomUUID().toString(),
        bridgeSessionId: String? = null,
        requestId: String = UUID.randomUUID().toString(),
    ): String = """
        {
          "kind":"request",
          "protocolVersion":1,
          "documentId":"$documentId",
          ${bridgeSessionId?.let { "\"bridgeSessionId\":\"$it\"," }.orEmpty()}
          "requestId":"$requestId",
          "method":"$method",
          "payload":$payload
        }
    """.trimIndent()

    private fun productCommandPayload(): String = """
        {
          "type":"PET_TAP",
          "requestKey":"${UUID.randomUUID()}",
          "expectedSnapshotRevision":"rate-1",
          "payload":{}
        }
    """.trimIndent()

    private fun feedbackPayload(): String =
        """{"kind":"buttonPress","eventId":"${UUID.randomUUID()}"}"""

    private fun response(raw: String): BridgeResponseEnvelope =
        BridgeCodec.json.decodeFromString(raw)

    private fun assertError(raw: String, code: String) {
        val response = response(raw)
        assertFalse(response.ok)
        assertEquals(code, response.error?.code)
    }

    private fun assertRateLimited(raw: String) {
        val response = response(raw)
        assertFalse(response.ok)
        assertEquals("RATE_LIMITED", response.error?.code)
        assertTrue(response.error?.retryable == true)
    }

    private companion object {
        const val NanosPerSecond = 1_000_000_000L
        const val NanosPerToken = NanosPerSecond / BridgeRateLimitSustainedPerSecond
    }
}
