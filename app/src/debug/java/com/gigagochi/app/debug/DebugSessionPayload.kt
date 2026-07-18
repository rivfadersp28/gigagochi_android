package com.gigagochi.app.debug

import com.gigagochi.app.core.model.SensitiveToken
import com.gigagochi.app.core.model.Session
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

internal const val DebugSessionPayloadExtra = "gigagochi.debug.sessionPayload"
private const val MaxEncodedPayloadChars = 48 * 1024
private const val MaxAccountIdBytes = 255
private const val MaxTokenBytes = 16 * 1024

@Serializable
private data class DebugSessionPayloadWire(
    val accountId: String,
    val accessToken: String,
    val refreshToken: String?,
    val expiresAt: Long,
) {
    override fun toString(): String = "DebugSessionPayloadWire(<redacted>)"
}

internal object DebugSessionPayloadParser {
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun parse(encodedPayload: String?, nowEpochMillis: Long): Session? = try {
        require(encodedPayload != null)
        require(encodedPayload.length in 4..MaxEncodedPayloadChars)
        require(encodedPayload.none(Char::isWhitespace))
        val decoded = Base64.Default.decode(encodedPayload)
        require(Base64.Default.encode(decoded) == encodedPayload)
        val rawJson = strictUtf8(decoded)
        val wire = json.decodeFromString<DebugSessionPayloadWire>(rawJson)
        requireBoundedValue(wire.accountId, MaxAccountIdBytes)
        requireBoundedValue(wire.accessToken, MaxTokenBytes)
        wire.refreshToken?.let { requireBoundedValue(it, MaxTokenBytes) }
        require(wire.expiresAt > nowEpochMillis)
        Session(
            accountId = wire.accountId,
            accessToken = SensitiveToken.of(wire.accessToken),
            refreshToken = wire.refreshToken?.let(SensitiveToken::of),
            expiresAtEpochMillis = wire.expiresAt,
        )
    } catch (_: Exception) {
        null
    }

    private fun strictUtf8(bytes: ByteArray): String {
        require(bytes.isNotEmpty())
        return StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }

    private fun requireBoundedValue(value: String, maxUtf8Bytes: Int) {
        require(value.isNotBlank() && value == value.trim())
        require(value.none(Char::isISOControl))
        require(value.toByteArray(StandardCharsets.UTF_8).size in 1..maxUtf8Bytes)
    }
}
