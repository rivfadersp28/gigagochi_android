package com.gigagochi.app.core.webview

import com.gigagochi.app.core.security.canonicalTravelRequestKeyOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement

internal class BridgeDispatcher(
    private val runtime: WebAppRuntime,
    private val feedbackHandler: WebFeedbackHandler = WebFeedbackHandler { },
    private val navigationBackController: WebNavigationBackController = WebNavigationBackController(),
    private val notificationPermissionHandler: WebNotificationPermissionRequestHandler =
        WebNotificationPermissionRequestHandler { },
    private val travelShareHandler: WebTravelShareRequestHandler =
        WebTravelShareRequestHandler { WebTravelShareAcceptResult.Invalid },
    monotonicClock: BridgeMonotonicClock = BridgeMonotonicClock { System.nanoTime() },
) {
    private val mutex = Mutex()
    private val requestRateLimiter = BridgeRequestRateLimiter(monotonicClock)
    private val transportReceiptLock = Any()
    private val transportReceipts =
        object : LinkedHashMap<String, BridgeTransportReceipt>(
            TransportReceiptCapacity,
            .75f,
            true,
        ) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, BridgeTransportReceipt>?,
            ): Boolean = size > TransportReceiptCapacity
        }
    private val documentEpoch = AtomicLong(0L)
    private var activeDocumentId: String? = null
    private var activeBridgeSessionId: String? = null
    private var eventSequence = 0L
    private val feedbackEventIds = HashSet<String>(FeedbackEventCapacity)
    private val feedbackEventOrder = ArrayDeque<String>(FeedbackEventCapacity)

    suspend fun handle(raw: String): String = mutex.withLock {
        var request: BridgeRequestEnvelope? = null
        var fingerprint: String? = null
        val response = try {
            request = BridgeCodec.decodeRequest(raw)
            fingerprint = BridgeCodec.requestFingerprint(request)
            transportReceipt(request.requestId)?.let { receipt ->
                return@withLock if (receipt.documentEpoch != documentEpoch.get()) {
                    failure(request, "STALE_DOCUMENT", retryable = false)
                } else if (receipt.fingerprint == fingerprint) {
                    receipt.response
                } else {
                    failure(request, "INVALID_PAYLOAD", retryable = false)
                }
            }
            if (!requestRateLimiter.tryAcquire()) {
                failure(request, "RATE_LIMITED", retryable = true)
            } else {
                when (request.method) {
                    "bootstrap" -> bootstrap(request)
                    "dispatch" -> dispatch(request)
                    "navigationReady" -> navigationReady(request)
                    "feedback" -> feedback(request)
                    "requestNotificationPermission" -> requestNotificationPermission(request)
                    "shareTravelVideo" -> shareTravelVideo(request)
                    else -> failure(request, "UNSUPPORTED_METHOD", retryable = false)
                }
            }
        } catch (error: WebAppRuntimeException) {
            val conflictSnapshot = if (error.bridgeCode == "STATE_CONFLICT") {
                error.snapshot ?: try {
                    withContext(Dispatchers.IO) { runtime.snapshot() }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    null
                }
            } else {
                null
            }
            failure(
                request = request,
                code = if (error.bridgeCode == "STATE_CONFLICT" && conflictSnapshot == null) {
                    "INTERNAL"
                } else {
                    error.bridgeCode
                },
                retryable = if (error.bridgeCode == "STATE_CONFLICT") {
                    error.retryable && conflictSnapshot != null
                } else {
                    error.retryable
                },
                result = conflictSnapshot?.let { BridgeCodec.json.encodeToJsonElement(it) },
                identity = if (request == null) BridgeCodec.requestIdentityFromPrefix(raw) else null,
            )
        } catch (_: SerializationException) {
            failure(
                request,
                "INVALID_PAYLOAD",
                retryable = false,
                identity = if (request == null) BridgeCodec.requestIdentityFromPrefix(raw) else null,
            )
        } catch (_: IllegalArgumentException) {
            failure(
                request,
                "INVALID_PAYLOAD",
                retryable = false,
                identity = if (request == null) BridgeCodec.requestIdentityFromPrefix(raw) else null,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            failure(
                request,
                "INTERNAL",
                retryable = false,
                identity = if (request == null) BridgeCodec.requestIdentityFromPrefix(raw) else null,
            )
        }
        if (request != null && fingerprint != null) {
            storeTransportReceipt(
                request.requestId,
                BridgeTransportReceipt(
                    documentEpoch = documentEpoch.get(),
                    fingerprint = fingerprint,
                    response = response,
                ),
            )
        }
        response
    }

    /**
     * Produces and remembers a pre-dispatch ingress rejection for a valid bounded envelope.
     *
     * This shares the same request-id receipt store as [handle], so a retry cannot turn a
     * RATE_LIMITED/BRIDGE_QUEUE_FULL call into a mutation under the same transport identity.
     * Oversized or malformed messages cannot be fully fingerprinted; they still receive a
     * deterministic correlated error and can never reach product code.
     */
    fun ingressFailure(
        raw: String,
        code: String,
        retryable: Boolean,
    ): String {
        val request = runCatching { BridgeCodec.decodeRequest(raw) }.getOrNull()
            ?: return BridgeCodec.encodeIngressFailure(raw, code, retryable)
        val fingerprint = BridgeCodec.requestFingerprint(request)
        transportReceipt(request.requestId)?.let { receipt ->
            return if (receipt.documentEpoch != documentEpoch.get()) {
                BridgeCodec.encodeResponse(
                    BridgeResponseEnvelope(
                        documentId = request.documentId,
                        bridgeSessionId = request.bridgeSessionId,
                        requestId = request.requestId,
                        ok = false,
                        error = BridgeErrorPayload("STALE_DOCUMENT", retryable = false),
                    ),
                )
            } else if (receipt.fingerprint == fingerprint) {
                receipt.response
            } else {
                BridgeCodec.encodeResponse(
                    BridgeResponseEnvelope(
                        documentId = request.documentId,
                        bridgeSessionId = request.bridgeSessionId,
                        requestId = request.requestId,
                        ok = false,
                        error = BridgeErrorPayload("INVALID_PAYLOAD", retryable = false),
                    ),
                )
            }
        }
        val response = BridgeCodec.encodeIngressFailure(raw, code, retryable)
        storeTransportReceipt(
            request.requestId,
            BridgeTransportReceipt(
                documentEpoch = documentEpoch.get(),
                fingerprint = fingerprint,
                response = response,
            ),
        )
        return response
    }

    fun invalidateDocument() {
        val nextEpoch = documentEpoch.incrementAndGet()
        activeDocumentId = null
        activeBridgeSessionId = null
        eventSequence = 0L
        feedbackEventIds.clear()
        feedbackEventOrder.clear()
        requestRateLimiter.reset()
        navigationBackController.invalidate(nextEpoch)
    }

    fun currentDocumentFence(): BridgeDocumentFence = BridgeDocumentFence(documentEpoch.get())

    fun requestSystemBack(): WebSystemBackDecision =
        navigationBackController.requestSystemBack(currentDocumentFence())

    fun releaseSystemBack(eventId: String) {
        navigationBackController.releaseSystemBack(eventId)
    }

    suspend fun event(
        event: WebAppRuntimeEvent,
        fence: BridgeDocumentFence? = null,
    ): String? = mutex.withLock {
        if (fence != null && fence.epoch != documentEpoch.get()) return@withLock null
        val documentId = activeDocumentId ?: return@withLock null
        val bridgeSessionId = activeBridgeSessionId ?: return@withLock null
        eventSequence += 1
        BridgeCodec.encodeEvent(
            BridgeEventEnvelope(
                documentId = documentId,
                bridgeSessionId = bridgeSessionId,
                sequence = eventSequence,
                type = event.type,
                payload = event.payload,
            ),
        )
    }

    private suspend fun bootstrap(request: BridgeRequestEnvelope): String {
        val payload = BridgeCodec.json.decodeFromJsonElement(
            BridgeBootstrapPayload.serializer(),
            request.payload,
        )
        if (BridgeProtocolVersion !in payload.supportedProtocolVersions) {
            return failure(request, "UNSUPPORTED_PROTOCOL", retryable = false)
        }
        if (
            payload.webBundleVersion != BridgeWebBundleVersion ||
            payload.schemaHash != BridgeSchemaHash
        ) {
            return failure(request, "UNSUPPORTED_PROTOCOL", retryable = false)
        }
        if (activeDocumentId != request.documentId || activeBridgeSessionId == null) {
            val nextEpoch = documentEpoch.incrementAndGet()
            activeDocumentId = request.documentId
            activeBridgeSessionId = UUID.randomUUID().toString()
            eventSequence = 0L
            feedbackEventIds.clear()
            feedbackEventOrder.clear()
            navigationBackController.invalidate(nextEpoch)
        }
        val snapshot = withContext(Dispatchers.IO) { runtime.snapshot() }
        return success(request, BridgeCodec.json.encodeToJsonElement(snapshot))
    }

    private suspend fun dispatch(request: BridgeRequestEnvelope): String {
        validateSession(request)
        val command = BridgeCodec.json.decodeFromJsonElement(
            BridgeProductCommand.serializer(),
            request.payload,
        )
        if (command.type !in BridgeProductCommandTypes) {
            throw WebAppRuntimeException("INVALID_PAYLOAD")
        }
        requireUuid(command.requestKey)
        if (command.expectedSnapshotRevision.isBlank()) {
            throw WebAppRuntimeException("INVALID_PAYLOAD")
        }
        val snapshot = withContext(Dispatchers.IO) { runtime.dispatch(command) }
        return success(request, BridgeCodec.json.encodeToJsonElement(snapshot))
    }

    private fun feedback(request: BridgeRequestEnvelope): String {
        validateSession(request)
        val payload = BridgeCodec.json.decodeFromJsonElement(
            WebFeedbackPayload.serializer(),
            request.payload,
        )
        val eventId = requireCanonicalUuid(payload.eventId)
        if (feedbackEventIds.add(eventId)) {
            feedbackEventOrder.addLast(eventId)
            if (feedbackEventOrder.size > FeedbackEventCapacity) {
                feedbackEventIds.remove(feedbackEventOrder.removeFirst())
            }
            feedbackHandler.handle(payload.kind)
        }
        return success(request, buildJsonObject {})
    }

    private fun navigationReady(request: BridgeRequestEnvelope): String {
        validateSession(request)
        val payload = BridgeCodec.json.decodeFromJsonElement(
            WebNavigationReadyPayload.serializer(),
            request.payload,
        )
        if (payload.sequence < 0L) {
            throw WebAppRuntimeException("INVALID_PAYLOAD")
        }
        navigationBackController.update(currentDocumentFence(), payload)
        return success(request, buildJsonObject {})
    }

    private fun requestNotificationPermission(request: BridgeRequestEnvelope): String {
        validateSession(request)
        if (request.payload !is JsonObject || request.payload.isNotEmpty()) {
            throw WebAppRuntimeException("INVALID_PAYLOAD")
        }
        notificationPermissionHandler.request(currentDocumentFence())
        return success(request, buildJsonObject {})
    }

    private fun shareTravelVideo(request: BridgeRequestEnvelope): String {
        validateSession(request)
        val payload = BridgeCodec.json.decodeFromJsonElement(
            WebTravelSharePayload.serializer(),
            request.payload,
        )
        val requestKey = canonicalTravelRequestKeyOrNull(payload.requestKey)
            ?: throw WebAppRuntimeException("INVALID_PAYLOAD")
        return when (
            travelShareHandler.accept(
                WebTravelShareRequest(
                    requestKey = requestKey,
                    documentFence = currentDocumentFence(),
                ),
            )
        ) {
            WebTravelShareAcceptResult.Accepted -> success(request, JsonPrimitive("accepted"))
            WebTravelShareAcceptResult.Invalid -> throw WebAppRuntimeException("INVALID_PAYLOAD")
        }
    }

    private fun validateSession(request: BridgeRequestEnvelope) {
        if (
            request.documentId != activeDocumentId ||
            request.bridgeSessionId == null ||
            request.bridgeSessionId != activeBridgeSessionId
        ) {
            throw WebAppRuntimeException("STALE_DOCUMENT")
        }
    }

    private fun success(
        request: BridgeRequestEnvelope,
        result: kotlinx.serialization.json.JsonElement,
    ): String = BridgeCodec.encodeResponse(
        BridgeResponseEnvelope(
            documentId = request.documentId,
            bridgeSessionId = requireNotNull(activeBridgeSessionId),
            requestId = request.requestId,
            ok = true,
            result = result,
        ),
    )

    private fun failure(
        request: BridgeRequestEnvelope?,
        code: String,
        retryable: Boolean,
        result: kotlinx.serialization.json.JsonElement? = null,
        identity: BridgeRequestIdentity? = null,
    ): String = BridgeCodec.encodeResponse(
        BridgeResponseEnvelope(
            documentId = request?.documentId ?: identity?.documentId.orEmpty(),
            bridgeSessionId = request?.bridgeSessionId
                ?: identity?.bridgeSessionId
                ?: activeBridgeSessionId,
            requestId = request?.requestId ?: identity?.requestId.orEmpty(),
            ok = false,
            result = result ?: kotlinx.serialization.json.JsonNull,
            error = BridgeErrorPayload(code, retryable),
        ),
    )

    private fun requireUuid(value: String) {
        try {
            UUID.fromString(value)
        } catch (_: IllegalArgumentException) {
            throw WebAppRuntimeException("INVALID_PAYLOAD")
        }
    }

    private fun requireCanonicalUuid(value: String): String {
        if (value.length != CanonicalUuidLength) {
            throw WebAppRuntimeException("INVALID_PAYLOAD")
        }
        val parsed = try {
            UUID.fromString(value)
        } catch (_: IllegalArgumentException) {
            throw WebAppRuntimeException("INVALID_PAYLOAD")
        }
        if (parsed.toString() != value.lowercase()) {
            throw WebAppRuntimeException("INVALID_PAYLOAD")
        }
        return parsed.toString()
    }

    private fun transportReceipt(requestId: String): BridgeTransportReceipt? =
        synchronized(transportReceiptLock) { transportReceipts[requestId] }

    private fun storeTransportReceipt(
        requestId: String,
        receipt: BridgeTransportReceipt,
    ) {
        synchronized(transportReceiptLock) {
            transportReceipts[requestId] = receipt
        }
    }

    private companion object {
        const val FeedbackEventCapacity = 256
        const val TransportReceiptCapacity = 1_024
        const val CanonicalUuidLength = 36
    }
}

internal data class BridgeDocumentFence(val epoch: Long)

private data class BridgeTransportReceipt(
    val documentEpoch: Long,
    val fingerprint: String,
    val response: String,
)
