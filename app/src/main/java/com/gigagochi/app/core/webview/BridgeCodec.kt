package com.gigagochi.app.core.webview

import java.util.UUID
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal data class BridgeRequestIdentity(
    val documentId: String,
    val bridgeSessionId: String?,
    val requestId: String,
)

internal object BridgeCodec {
    val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        explicitNulls = true
        encodeDefaults = true
        allowStructuredMapKeys = false
    }

    fun decodeRequest(raw: String): BridgeRequestEnvelope {
        if (raw.toByteArray(Charsets.UTF_8).size > BridgeMaxMessageBytes) {
            throw WebAppRuntimeException("PAYLOAD_TOO_LARGE")
        }
        val request = try {
            json.decodeFromString<BridgeRequestEnvelope>(raw)
        } catch (_: SerializationException) {
            throw WebAppRuntimeException("BAD_MESSAGE")
        } catch (_: IllegalArgumentException) {
            throw WebAppRuntimeException("BAD_MESSAGE")
        }
        if (request.kind != "request") throw WebAppRuntimeException("BAD_MESSAGE")
        if (request.protocolVersion != BridgeProtocolVersion) {
            throw WebAppRuntimeException("UNSUPPORTED_PROTOCOL")
        }
        requireUuid(request.documentId)
        requireUuid(request.requestId)
        request.bridgeSessionId?.let(::requireUuid)
        return request
    }

    fun encodeResponse(response: BridgeResponseEnvelope): String = json.encodeToString(response)

    fun encodeEvent(event: BridgeEventEnvelope): String = json.encodeToString(event)

    fun requestFingerprint(request: BridgeRequestEnvelope): String =
        json.encodeToString(BridgeRequestEnvelope.serializer(), request)

    /**
     * Extracts only correlation identifiers from the bounded prefix of an ingress message.
     *
     * This is used exclusively to correlate deterministic transport rejection responses. It does
     * not admit a request or replace the strict full decoder above. NativeGigagochiBridge emits
     * all three identifiers before `payload`, so a legitimate oversized request remains
     * correlatable without parsing or copying attacker-controlled payload bytes.
     */
    fun requestIdentityFromPrefix(raw: String): BridgeRequestIdentity? {
        val prefix = raw.take(BridgeIdentityPrefixMaxChars)
        val documentId = uniqueCanonicalUuidField(prefix, "documentId") ?: return null
        val requestId = uniqueCanonicalUuidField(prefix, "requestId") ?: return null
        val bridgeSessionMatches = uuidFieldPattern("bridgeSessionId").findAll(prefix).toList()
        if (bridgeSessionMatches.size > 1) return null
        val bridgeSessionId = bridgeSessionMatches.singleOrNull()?.groupValues?.get(1)?.let {
            canonicalUuidOrNull(it) ?: return null
        }
        return BridgeRequestIdentity(
            documentId = documentId,
            bridgeSessionId = bridgeSessionId,
            requestId = requestId,
        )
    }

    fun encodeIngressFailure(
        raw: String,
        code: String,
        retryable: Boolean,
    ): String {
        val identity = requestIdentityFromPrefix(raw)
        return encodeResponse(
            BridgeResponseEnvelope(
                documentId = identity?.documentId.orEmpty(),
                bridgeSessionId = identity?.bridgeSessionId,
                requestId = identity?.requestId.orEmpty(),
                ok = false,
                error = BridgeErrorPayload(code, retryable),
            ),
        )
    }

    private fun uniqueCanonicalUuidField(prefix: String, field: String): String? {
        val matches = uuidFieldPattern(field).findAll(prefix).toList()
        if (matches.size != 1) return null
        return canonicalUuidOrNull(matches.single().groupValues[1])
    }

    private fun uuidFieldPattern(field: String): Regex =
        Regex("\"$field\"\\s*:\\s*\"([0-9A-Fa-f-]{36})\"")

    private fun canonicalUuidOrNull(value: String): String? {
        val parsed = runCatching { UUID.fromString(value) }.getOrNull() ?: return null
        return parsed.toString().takeIf { it == value.lowercase() }
    }

    private fun requireUuid(value: String) {
        try {
            UUID.fromString(value)
        } catch (_: IllegalArgumentException) {
            throw WebAppRuntimeException("INVALID_PAYLOAD")
        }
    }

    private const val BridgeIdentityPrefixMaxChars = 4_096
}
