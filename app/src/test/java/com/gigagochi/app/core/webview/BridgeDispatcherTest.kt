package com.gigagochi.app.core.webview

import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeDispatcherTest {
    @Test
    fun `bridge schema hash is v3`() {
        assertTrue(BridgeSchemaHash.matches(Regex("^gigagochi-bridge-v3-[0-9a-f]{64}$")))
    }

    @Test
    fun `strict codec rejects unknown fields`() {
        val raw = requestJson(
            method = "bootstrap",
            payload = bootstrapPayload(),
            extra = ",\"ownerId\":\"forbidden\"",
        )

        val error = runCatching { BridgeCodec.decodeRequest(raw) }.exceptionOrNull()

        assertEquals("BAD_MESSAGE", (error as WebAppRuntimeException).bridgeCode)
    }

    @Test
    fun `strict malformed envelope preserves correlation ids in stable error`() = runBlocking {
        val dispatcher = BridgeDispatcher(FakeRuntime())
        val requestId = UUID.randomUUID().toString()
        val response = BridgeCodec.json.decodeFromString<BridgeResponseEnvelope>(
            dispatcher.handle(
                requestJson(
                    method = "bootstrap",
                    payload = bootstrapPayload(),
                    extra = ",\"ownerId\":\"forbidden\"",
                    requestId = requestId,
                ),
            ),
        )

        assertFalse(response.ok)
        assertEquals("BAD_MESSAGE", response.error?.code)
        assertEquals(StableDocumentId, response.documentId)
        assertEquals(requestId, response.requestId)
    }

    @Test
    fun `bootstrap response is fenced and contains no native secrets`() = runBlocking {
        val dispatcher = BridgeDispatcher(FakeRuntime())
        val raw = dispatcher.handle(
            requestJson(
                method = "bootstrap",
                payload = bootstrapPayload(),
            ),
        )
        val response = BridgeCodec.json.decodeFromString<BridgeResponseEnvelope>(raw)
        val snapshot = BridgeCodec.json.decodeFromJsonElement(
            WebAppSnapshot.serializer(),
            response.result,
        )

        assertTrue(response.ok)
        assertTrue(response.bridgeSessionId?.let(::isUuid) == true)
        assertEquals(BridgeProtocolVersion, snapshot.protocolVersion)
        assertTrue(snapshot.capabilities.requestNotificationPermission)
        assertTrue(snapshot.capabilities.shareTravelVideo)
        assertTrue(snapshot.capabilities.opaqueMedia)
        assertNull(snapshot.pendingDeepLinkTarget)
        assertFalse(raw.contains("ownerId"))
        assertFalse(raw.contains("accessToken"))
        assertFalse(raw.contains("backend"))
    }

    @Test
    fun `same document bootstrap reuses bridge session`() = runBlocking {
        val dispatcher = BridgeDispatcher(FakeRuntime())
        val first = BridgeCodec.json.decodeFromString<BridgeResponseEnvelope>(
            dispatcher.handle(requestJson(method = "bootstrap", payload = bootstrapPayload())),
        )
        val second = BridgeCodec.json.decodeFromString<BridgeResponseEnvelope>(
            dispatcher.handle(requestJson(method = "bootstrap", payload = bootstrapPayload())),
        )

        assertEquals(first.bridgeSessionId, second.bridgeSessionId)
    }

    @Test
    fun `valid feedback invokes typed handler`() = runBlocking {
        val handled = mutableListOf<WebFeedbackKind>()
        val dispatcher = BridgeDispatcher(FakeRuntime(), handled::add)
        val bridgeSessionId = bootstrapSession(dispatcher)

        val response = BridgeCodec.json.decodeFromString<BridgeResponseEnvelope>(
            dispatcher.handle(
                requestJson(
                    method = "feedback",
                    payload = feedbackPayload(WebFeedbackKind.CreateAnswer, UUID.randomUUID()),
                    bridgeSessionId = bridgeSessionId,
                ),
            ),
        )

        assertTrue(response.ok)
        assertEquals(listOf(WebFeedbackKind.CreateAnswer), handled)
    }

    @Test
    fun `feedback rejects unknown kinds extra keys and non canonical event ids`() = runBlocking {
        val handled = mutableListOf<WebFeedbackKind>()
        val dispatcher = BridgeDispatcher(FakeRuntime(), handled::add)
        val bridgeSessionId = bootstrapSession(dispatcher)
        val eventId = UUID.randomUUID()
        val malformedPayloads = listOf(
            """{"kind":"feed","eventId":"$eventId"}""",
            """{"kind":"buttonPress","eventId":"$eventId","extra":true}""",
            """{"kind":"buttonPress","eventId":"${eventId}0"}""",
        )

        malformedPayloads.forEach { payload ->
            val response = BridgeCodec.json.decodeFromString<BridgeResponseEnvelope>(
                dispatcher.handle(
                    requestJson(
                        method = "feedback",
                        payload = payload,
                        bridgeSessionId = bridgeSessionId,
                    ),
                ),
            )
            assertFalse(response.ok)
            assertEquals("INVALID_PAYLOAD", response.error?.code)
        }
        assertTrue(handled.isEmpty())
    }

    @Test
    fun `feedback event ids are deduplicated within the active document`() = runBlocking {
        val handled = mutableListOf<WebFeedbackKind>()
        val dispatcher = BridgeDispatcher(FakeRuntime(), handled::add)
        val bridgeSessionId = bootstrapSession(dispatcher)
        val eventId = UUID.randomUUID()

        repeat(2) {
            val response = BridgeCodec.json.decodeFromString<BridgeResponseEnvelope>(
                dispatcher.handle(
                    requestJson(
                        method = "feedback",
                        payload = feedbackPayload(WebFeedbackKind.ButtonPress, eventId),
                        bridgeSessionId = bridgeSessionId,
                    ),
                ),
            )
            assertTrue(response.ok)
        }

        assertEquals(listOf(WebFeedbackKind.ButtonPress), handled)
    }

    @Test
    fun `feedback dedupe retains only the most recent 256 event ids`() = runBlocking {
        val handled = mutableListOf<WebFeedbackKind>()
        val clock = AdvancingBridgeClock()
        val dispatcher = BridgeDispatcher(
            runtime = FakeRuntime(),
            feedbackHandler = WebFeedbackHandler(handled::add),
            monotonicClock = clock,
        )
        val bridgeSessionId = bootstrapSession(dispatcher)
        val ids = List(257) { UUID.randomUUID() }

        ids.forEach { eventId ->
            dispatcher.handle(
                requestJson(
                    method = "feedback",
                    payload = feedbackPayload(WebFeedbackKind.ChatSubmit, eventId),
                    bridgeSessionId = bridgeSessionId,
                ),
            )
        }
        dispatcher.handle(
            requestJson(
                method = "feedback",
                payload = feedbackPayload(WebFeedbackKind.ChatSubmit, ids.first()),
                bridgeSessionId = bridgeSessionId,
            ),
        )

        assertEquals(258, handled.size)
    }

    @Test
    fun `stale document cannot trigger feedback`() = runBlocking {
        val handled = mutableListOf<WebFeedbackKind>()
        val dispatcher = BridgeDispatcher(FakeRuntime(), handled::add)
        val bridgeSessionId = bootstrapSession(dispatcher)

        val response = BridgeCodec.json.decodeFromString<BridgeResponseEnvelope>(
            dispatcher.handle(
                requestJson(
                    method = "feedback",
                    payload = feedbackPayload(WebFeedbackKind.DashboardAction, UUID.randomUUID()),
                    documentId = UUID.randomUUID().toString(),
                    bridgeSessionId = bridgeSessionId,
                ),
            ),
        )

        assertFalse(response.ok)
        assertEquals("STALE_DOCUMENT", response.error?.code)
        assertTrue(handled.isEmpty())
    }

    @Test
    fun `navigation readiness is session fenced and drives one system back event`() = runBlocking {
        val dispatcher = BridgeDispatcher(FakeRuntime())
        assertEquals(WebSystemBackDecision.Unhandled, dispatcher.requestSystemBack())
        val bridgeSessionId = bootstrapSession(dispatcher)

        val response = BridgeCodec.json.decodeFromString<BridgeResponseEnvelope>(
            dispatcher.handle(
                requestJson(
                    method = "navigationReady",
                    payload = """{"canHandleBack":true,"sequence":1}""",
                    bridgeSessionId = bridgeSessionId,
                ),
            ),
        )
        val first = dispatcher.requestSystemBack()

        assertTrue(response.ok)
        assertTrue(first is WebSystemBackDecision.Dispatch)
        assertEquals(
            WebSystemBackDecision.DuplicateConsumed,
            dispatcher.requestSystemBack(),
        )
        first as WebSystemBackDecision.Dispatch
        val encoded = requireNotNull(
            dispatcher.event(
                WebAppRuntimeEvent(
                    type = "systemBack",
                    payload = BridgeCodec.json.encodeToJsonElement(
                        WebSystemBackPayload.serializer(),
                        WebSystemBackPayload(first.navigationSequence),
                    ),
                ),
                first.documentFence,
            ),
        )
        val event = BridgeCodec.json.decodeFromString<BridgeEventEnvelope>(encoded)
        val payload = BridgeCodec.json.decodeFromJsonElement(
            WebSystemBackPayload.serializer(),
            event.payload,
        )
        assertEquals("systemBack", event.type)
        assertEquals(1L, payload.navigationSequence)
    }

    @Test
    fun `stale document cannot publish navigation readiness`() = runBlocking {
        val dispatcher = BridgeDispatcher(FakeRuntime())
        val bridgeSessionId = bootstrapSession(dispatcher)

        val response = BridgeCodec.json.decodeFromString<BridgeResponseEnvelope>(
            dispatcher.handle(
                requestJson(
                    method = "navigationReady",
                    payload = """{"canHandleBack":true,"sequence":1}""",
                    documentId = UUID.randomUUID().toString(),
                    bridgeSessionId = bridgeSessionId,
                ),
            ),
        )

        assertFalse(response.ok)
        assertEquals("STALE_DOCUMENT", response.error?.code)
        assertEquals(WebSystemBackDecision.Unhandled, dispatcher.requestSystemBack())
    }

    @Test
    fun `document invalidation clears published navigation readiness`() = runBlocking {
        val dispatcher = BridgeDispatcher(FakeRuntime())
        val bridgeSessionId = bootstrapSession(dispatcher)
        dispatcher.handle(
            requestJson(
                method = "navigationReady",
                payload = """{"canHandleBack":true,"sequence":1}""",
                bridgeSessionId = bridgeSessionId,
            ),
        )

        dispatcher.invalidateDocument()

        assertEquals(WebSystemBackDecision.Unhandled, dispatcher.requestSystemBack())
    }

    @Test
    fun `navigation readiness rejects malformed payloads`() = runBlocking {
        val dispatcher = BridgeDispatcher(FakeRuntime())
        val bridgeSessionId = bootstrapSession(dispatcher)
        val malformedPayloads = listOf(
            """{"canHandleBack":true,"sequence":-1}""",
            """{"canHandleBack":true,"sequence":1,"extra":false}""",
            """{"canHandleBack":"yes","sequence":1}""",
        )

        malformedPayloads.forEach { payload ->
            val response = BridgeCodec.json.decodeFromString<BridgeResponseEnvelope>(
                dispatcher.handle(
                    requestJson(
                        method = "navigationReady",
                        payload = payload,
                        bridgeSessionId = bridgeSessionId,
                    ),
                ),
            )
            assertFalse(response.ok)
            assertEquals("INVALID_PAYLOAD", response.error?.code)
        }
        assertEquals(WebSystemBackDecision.Unhandled, dispatcher.requestSystemBack())
    }

    @Test
    fun `notification permission request is strict session fenced and returns immediately`() =
        runBlocking {
            val requestedFences = mutableListOf<BridgeDocumentFence>()
            val dispatcher = BridgeDispatcher(
                runtime = FakeRuntime(),
                notificationPermissionHandler = WebNotificationPermissionRequestHandler(
                    requestedFences::add,
                ),
            )
            val bridgeSessionId = bootstrapSession(dispatcher)

            val response = BridgeCodec.json.decodeFromString<BridgeResponseEnvelope>(
                dispatcher.handle(
                    requestJson(
                        method = "requestNotificationPermission",
                        payload = "{}",
                        bridgeSessionId = bridgeSessionId,
                    ),
                ),
            )

            assertTrue(response.ok)
            assertEquals(listOf(dispatcher.currentDocumentFence()), requestedFences)

            val stale = BridgeCodec.json.decodeFromString<BridgeResponseEnvelope>(
                dispatcher.handle(
                    requestJson(
                        method = "requestNotificationPermission",
                        payload = "{}",
                        documentId = UUID.randomUUID().toString(),
                        bridgeSessionId = bridgeSessionId,
                    ),
                ),
            )
            assertFalse(stale.ok)
            assertEquals("STALE_DOCUMENT", stale.error?.code)
            assertEquals(1, requestedFences.size)
        }

    @Test
    fun `notification permission request rejects every non-empty-object payload`() = runBlocking {
        var requests = 0
        val dispatcher = BridgeDispatcher(
            runtime = FakeRuntime(),
            notificationPermissionHandler = WebNotificationPermissionRequestHandler { requests += 1 },
        )
        val bridgeSessionId = bootstrapSession(dispatcher)

        listOf("null", "[]", "true", """{"extra":true}""").forEach { payload ->
            val response = BridgeCodec.json.decodeFromString<BridgeResponseEnvelope>(
                dispatcher.handle(
                    requestJson(
                        method = "requestNotificationPermission",
                        payload = payload,
                        bridgeSessionId = bridgeSessionId,
                    ),
                ),
            )
            assertFalse(response.ok)
            assertEquals("INVALID_PAYLOAD", response.error?.code)
        }
        assertEquals(0, requests)
    }

    @Test
    fun `permission result and lifecycle events preserve one native ordering`() = runBlocking {
        val dispatcher = BridgeDispatcher(FakeRuntime())
        val bridgeSessionId = bootstrapSession(dispatcher)
        val fence = dispatcher.currentDocumentFence()

        val permission = BridgeCodec.json.decodeFromString<BridgeEventEnvelope>(
            requireNotNull(
                dispatcher.event(
                    permissionChangedEvent(WebNotificationPermissionStatus.Granted),
                    fence,
                ),
            ),
        )
        val lifecycle = BridgeCodec.json.decodeFromString<BridgeEventEnvelope>(
            requireNotNull(
                dispatcher.event(
                    lifecycleChangedEvent(WebLifecycleState.Background),
                    fence,
                ),
            ),
        )

        assertEquals(bridgeSessionId, permission.bridgeSessionId)
        assertEquals("permissionChanged", permission.type)
        assertEquals(1L, permission.sequence)
        assertEquals("lifecycleChanged", lifecycle.type)
        assertEquals(2L, lifecycle.sequence)
    }

    @Test
    fun `bootstrap rejects a mismatched web bundle`() = runBlocking {
        val dispatcher = BridgeDispatcher(FakeRuntime())
        val response = BridgeCodec.json.decodeFromString<BridgeResponseEnvelope>(
            dispatcher.handle(
                requestJson(
                    method = "bootstrap",
                    payload = """{"supportedProtocolVersions":[1],"webBundleVersion":"stale","schemaHash":"$BridgeSchemaHash"}""",
                ),
            ),
        )

        assertFalse(response.ok)
        assertEquals("UNSUPPORTED_PROTOCOL", response.error?.code)
    }

    @Test
    fun `native events share the active session and use a monotonic sequence`() = runBlocking {
        val dispatcher = BridgeDispatcher(FakeRuntime())
        val bootstrap = BridgeCodec.json.decodeFromString<BridgeResponseEnvelope>(
            dispatcher.handle(requestJson(method = "bootstrap", payload = bootstrapPayload())),
        )

        val first = BridgeCodec.json.decodeFromString<BridgeEventEnvelope>(
            requireNotNull(
                dispatcher.event(
                    WebAppRuntimeEvent(
                        type = "stateChanged",
                        payload = buildJsonObject { put("revision", "debug-1") },
                    ),
                ),
            ),
        )
        val second = BridgeCodec.json.decodeFromString<BridgeEventEnvelope>(
            requireNotNull(
                dispatcher.event(
                    WebAppRuntimeEvent(
                        type = "insetsChanged",
                        payload = buildJsonObject { put("top", 24) },
                    ),
                ),
            ),
        )

        assertEquals(bootstrap.bridgeSessionId, first.bridgeSessionId)
        assertEquals(StableDocumentId, first.documentId)
        assertEquals(1L, first.sequence)
        assertEquals(2L, second.sequence)
        dispatcher.invalidateDocument()
        assertNull(dispatcher.event(WebAppRuntimeEvent("stateChanged", buildJsonObject {})))
    }

    @Test
    fun `event captured by a stale document fence cannot attach to a new session`() = runBlocking {
        val dispatcher = BridgeDispatcher(FakeRuntime())
        bootstrapSession(dispatcher)
        val staleFence = dispatcher.currentDocumentFence()

        dispatcher.invalidateDocument()
        bootstrapSession(dispatcher)

        assertNull(
            dispatcher.event(
                WebAppRuntimeEvent("insetsChanged", buildJsonObject { put("imeProgress", 0.5) }),
                staleFence,
            ),
        )
    }

    @Test
    fun `stale document cannot dispatch product command`() = runBlocking {
        val dispatcher = BridgeDispatcher(FakeRuntime())
        val bootstrapRequest = BridgeCodec.decodeRequest(
            requestJson(
                method = "bootstrap",
                payload = bootstrapPayload(),
            ),
        )
        val bootstrap = BridgeCodec.json.decodeFromString<BridgeResponseEnvelope>(
            dispatcher.handle(BridgeCodec.json.encodeToString(BridgeRequestEnvelope.serializer(), bootstrapRequest)),
        )
        val stale = dispatcher.handle(
            requestJson(
                method = "dispatch",
                payload = productCommandPayload("debug-0"),
                documentId = UUID.randomUUID().toString(),
                bridgeSessionId = bootstrap.bridgeSessionId,
            ),
        )
        val response = BridgeCodec.json.decodeFromString<BridgeResponseEnvelope>(stale)

        assertFalse(response.ok)
        assertEquals("STALE_DOCUMENT", response.error?.code)
    }

    @Test
    fun `duplicate request id replays the exact transport response without repeating runtime work`() =
        runBlocking {
            var snapshotCalls = 0
            val runtime = object : WebAppRuntime {
                override suspend fun snapshot(): WebAppSnapshot {
                    snapshotCalls += 1
                    return testSnapshot("debug-$snapshotCalls")
                }

                override suspend fun dispatch(command: BridgeProductCommand): WebAppSnapshot =
                    error("not used")
            }
            val dispatcher = BridgeDispatcher(runtime)
            val requestId = UUID.randomUUID().toString()
            val raw = requestJson(
                method = "bootstrap",
                payload = bootstrapPayload(),
                requestId = requestId,
            )

            val first = dispatcher.handle(raw)
            val duplicate = dispatcher.handle(raw)

            assertEquals(first, duplicate)
            assertEquals(1, snapshotCalls)
        }

    @Test
    fun `duplicate dispatch request id cannot repeat a product mutation`() = runBlocking {
        var dispatchCalls = 0
        val runtime = object : WebAppRuntime {
            override suspend fun snapshot(): WebAppSnapshot = testSnapshot("debug-0")

            override suspend fun dispatch(command: BridgeProductCommand): WebAppSnapshot {
                dispatchCalls += 1
                return testSnapshot("debug-$dispatchCalls")
            }
        }
        val dispatcher = BridgeDispatcher(runtime)
        val bridgeSessionId = bootstrapSession(dispatcher)
        val raw = requestJson(
            method = "dispatch",
            payload = productCommandPayload("debug-0"),
            bridgeSessionId = bridgeSessionId,
        )

        val first = dispatcher.handle(raw)
        val duplicate = dispatcher.handle(raw)

        assertEquals(first, duplicate)
        assertEquals(1, dispatchCalls)
    }

    @Test
    fun `request id collision with different content is rejected without dispatch`() = runBlocking {
        var snapshotCalls = 0
        val runtime = object : WebAppRuntime {
            override suspend fun snapshot(): WebAppSnapshot {
                snapshotCalls += 1
                return testSnapshot("debug-$snapshotCalls")
            }

            override suspend fun dispatch(command: BridgeProductCommand): WebAppSnapshot =
                error("not used")
        }
        val dispatcher = BridgeDispatcher(runtime)
        val requestId = UUID.randomUUID().toString()
        dispatcher.handle(
            requestJson(
                method = "bootstrap",
                payload = bootstrapPayload(),
                requestId = requestId,
            ),
        )

        val collision = BridgeCodec.json.decodeFromString<BridgeResponseEnvelope>(
            dispatcher.handle(
                requestJson(
                    method = "feedback",
                    payload = feedbackPayload(WebFeedbackKind.ButtonPress, UUID.randomUUID()),
                    requestId = requestId,
                ),
            ),
        )

        assertFalse(collision.ok)
        assertEquals("INVALID_PAYLOAD", collision.error?.code)
        assertEquals(1, snapshotCalls)
    }

    @Test
    fun `transport receipt from an invalidated document cannot resurrect its bridge session`() =
        runBlocking {
            var snapshotCalls = 0
            val runtime = object : WebAppRuntime {
                override suspend fun snapshot(): WebAppSnapshot {
                    snapshotCalls += 1
                    return testSnapshot("debug-$snapshotCalls")
                }

                override suspend fun dispatch(command: BridgeProductCommand): WebAppSnapshot =
                    error("not used")
            }
            val dispatcher = BridgeDispatcher(runtime)
            val raw = requestJson(method = "bootstrap", payload = bootstrapPayload())
            val first = BridgeCodec.json.decodeFromString<BridgeResponseEnvelope>(
                dispatcher.handle(raw),
            )
            dispatcher.invalidateDocument()

            val replay = BridgeCodec.json.decodeFromString<BridgeResponseEnvelope>(
                dispatcher.handle(raw),
            )

            assertTrue(first.ok)
            assertFalse(replay.ok)
            assertEquals("STALE_DOCUMENT", replay.error?.code)
            assertEquals(1, snapshotCalls)
        }

    @Test
    fun `state conflict response carries the current canonical snapshot`() = runBlocking {
        val canonical = testSnapshot("canonical-revision")
        val runtime = object : WebAppRuntime {
            override suspend fun snapshot(): WebAppSnapshot = canonical

            override suspend fun dispatch(command: BridgeProductCommand): WebAppSnapshot {
                throw WebAppRuntimeException(
                    bridgeCode = "STATE_CONFLICT",
                    retryable = true,
                )
            }
        }
        val dispatcher = BridgeDispatcher(runtime)
        val bridgeSessionId = bootstrapSession(dispatcher)

        val response = BridgeCodec.json.decodeFromString<BridgeResponseEnvelope>(
            dispatcher.handle(
                requestJson(
                    method = "dispatch",
                    payload = productCommandPayload("stale-revision"),
                    bridgeSessionId = bridgeSessionId,
                ),
            ),
        )

        assertFalse(response.ok)
        assertEquals("STATE_CONFLICT", response.error?.code)
        assertTrue(response.error?.retryable == true)
        assertEquals(
            canonical,
            BridgeCodec.json.decodeFromJsonElement(WebAppSnapshot.serializer(), response.result),
        )
    }

    @Test
    fun `multibyte oversized message returns correlated stable payload error`() = runBlocking {
        val dispatcher = BridgeDispatcher(FakeRuntime())
        val requestId = UUID.randomUUID().toString()
        val raw = requestJson(
            method = "bootstrap",
            payload = """{"padding":"${"я".repeat(BridgeMaxMessageBytes / 2)}"}""",
            requestId = requestId,
        )
        assertTrue(raw.length < BridgeMaxMessageBytes)
        assertTrue(raw.toByteArray(Charsets.UTF_8).size > BridgeMaxMessageBytes)

        val response = BridgeCodec.json.decodeFromString<BridgeResponseEnvelope>(
            dispatcher.handle(raw),
        )

        assertFalse(response.ok)
        assertEquals("PAYLOAD_TOO_LARGE", response.error?.code)
        assertEquals(StableDocumentId, response.documentId)
        assertEquals(requestId, response.requestId)
    }

    @Test
    fun `dispatch rejects commands outside the closed product union before runtime`() = runBlocking {
        var dispatchCalls = 0
        val runtime = object : WebAppRuntime {
            override suspend fun snapshot(): WebAppSnapshot = testSnapshot("debug-0")

            override suspend fun dispatch(command: BridgeProductCommand): WebAppSnapshot {
                dispatchCalls += 1
                return testSnapshot("debug-1")
            }
        }
        val dispatcher = BridgeDispatcher(runtime)
        val bridgeSessionId = bootstrapSession(dispatcher)
        val payload = """
            {
              "type":"FUTURE_COMMAND",
              "requestKey":"${UUID.randomUUID()}",
              "expectedSnapshotRevision":"debug-0",
              "payload":{}
            }
        """.trimIndent()

        val response = BridgeCodec.json.decodeFromString<BridgeResponseEnvelope>(
            dispatcher.handle(
                requestJson(
                    method = "dispatch",
                    payload = payload,
                    bridgeSessionId = bridgeSessionId,
                ),
            ),
        )

        assertFalse(response.ok)
        assertEquals("INVALID_PAYLOAD", response.error?.code)
        assertEquals(0, dispatchCalls)
    }

    private class FakeRuntime : WebAppRuntime {
        override suspend fun snapshot(): WebAppSnapshot = snapshot(0)

        override suspend fun dispatch(command: BridgeProductCommand): WebAppSnapshot = snapshot(1)

        private fun snapshot(tapProgress: Int) = WebAppSnapshot(
            appVersion = "test",
            webBundleVersion = "test",
            revision = "debug-$tapProgress",
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
                petTapProgress = tapProgress,
                media = WebPetMediaSnapshot(videoRef = "/assets/media/openai-normal.mp4"),
            ),
        )
    }

    private fun requestJson(
        method: String,
        payload: String,
        documentId: String = StableDocumentId,
        bridgeSessionId: String? = null,
        extra: String = "",
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
          $extra
        }
    """.trimIndent()

    private fun productCommandPayload(revision: String): String = """
        {
          "type":"PET_TAP",
          "requestKey":"${UUID.randomUUID()}",
          "expectedSnapshotRevision":"$revision",
          "payload":{}
        }
    """.trimIndent()

    private suspend fun bootstrapSession(dispatcher: BridgeDispatcher): String {
        val response = BridgeCodec.json.decodeFromString<BridgeResponseEnvelope>(
            dispatcher.handle(requestJson(method = "bootstrap", payload = bootstrapPayload())),
        )
        return requireNotNull(response.bridgeSessionId)
    }

    private fun feedbackPayload(kind: WebFeedbackKind, eventId: UUID): String {
        val serializedKind = when (kind) {
            WebFeedbackKind.CreateAnswer -> "createAnswer"
            WebFeedbackKind.CreateCustom -> "createCustom"
            WebFeedbackKind.CreateRetry -> "createRetry"
            WebFeedbackKind.DashboardAction -> "dashboardAction"
            WebFeedbackKind.ChatSubmit -> "chatSubmit"
            WebFeedbackKind.ButtonPress -> "buttonPress"
        }
        return """{"kind":"$serializedKind","eventId":"$eventId"}"""
    }

    private fun bootstrapPayload(): String =
        """{"supportedProtocolVersions":[1],"webBundleVersion":"$BridgeWebBundleVersion","schemaHash":"$BridgeSchemaHash"}"""

    private fun isUuid(value: String): Boolean = runCatching { UUID.fromString(value) }.isSuccess

    private fun testSnapshot(revision: String) = WebAppSnapshot(
        appVersion = "test",
        webBundleVersion = "test",
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

    private class AdvancingBridgeClock : BridgeMonotonicClock {
        private var nanos = 0L

        override fun nowNanos(): Long = nanos.also {
            nanos += 1_000_000_000L / BridgeRateLimitSustainedPerSecond
        }
    }

    private companion object {
        val StableDocumentId: String = UUID.randomUUID().toString()
    }
}
