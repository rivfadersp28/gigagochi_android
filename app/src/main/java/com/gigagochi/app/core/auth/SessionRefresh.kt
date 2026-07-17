package com.gigagochi.app.core.auth

import com.gigagochi.app.core.model.Session
import java.io.IOException
import java.net.URI
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException

private const val RefreshTokenMaxLength = 16 * 1024

class SessionRefreshEndpoint private constructor(
    val url: String,
) {
    companion object {
        fun fromBaseUrl(
            baseUrl: String,
            allowDebugLoopbackHttp: Boolean,
        ): SessionRefreshEndpoint? = runCatching {
            val parsed = URI(baseUrl.trim())
            val normalized = parsed.normalize()
            val scheme = normalized.scheme?.lowercase()
            val host = normalized.host?.lowercase()
            val isDebugLoopback =
                allowDebugLoopbackHttp &&
                    scheme == "http" &&
                    host in setOf("localhost", "127.0.0.1", "10.0.2.2", "::1")
            require(scheme == "https" || isDebugLoopback)
            require(!host.isNullOrBlank())
            require(normalized.userInfo == null)
            require(normalized.query == null)
            require(normalized.fragment == null)
            require(parsed.rawPath == normalized.rawPath)
            val basePath = normalized.path.orEmpty().trimEnd('/')
            val endpointPath = "$basePath/api/auth/refresh".let {
                if (it.startsWith('/')) it else "/$it"
            }
            SessionRefreshEndpoint(
                URI(
                    normalized.scheme,
                    null,
                    normalized.host,
                    normalized.port,
                    endpointPath,
                    null,
                    null,
                ).toASCIIString(),
            )
        }.getOrNull()
    }
}

sealed interface SessionRefreshResult {
    data class Success(val session: Session) : SessionRefreshResult
    data object InvalidSession : SessionRefreshResult
    data object Failure : SessionRefreshResult
}

interface SessionRefreshExchange {
    suspend fun refresh(refreshToken: com.gigagochi.app.core.model.SensitiveToken): SessionRefreshResult
}

class HttpSessionRefreshExchange(
    baseUrl: String,
    allowDebugLoopbackHttp: Boolean,
    private val transport: AuthHttpTransport = UrlConnectionAuthHttpTransport(),
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) : SessionRefreshExchange {
    private val endpoint = SessionRefreshEndpoint.fromBaseUrl(
        baseUrl = baseUrl,
        allowDebugLoopbackHttp = allowDebugLoopbackHttp,
    )

    override suspend fun refresh(
        refreshToken: com.gigagochi.app.core.model.SensitiveToken,
    ): SessionRefreshResult {
        val resolvedEndpoint = endpoint ?: return SessionRefreshResult.Failure
        if (refreshToken.isBlank() || refreshToken.length > RefreshTokenMaxLength) {
            return SessionRefreshResult.InvalidSession
        }
        val body = "{\"refreshToken\":\"${refreshToken.reveal().escapeRefreshJson()}\"}"
            .toByteArray(StandardCharsets.UTF_8)
        val response = try {
            transport.execute(AuthHttpRequest(resolvedEndpoint.url, body))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            return SessionRefreshResult.Failure
        } catch (_: Exception) {
            return SessionRefreshResult.Failure
        }
        return when (response.statusCode) {
            in 200..299 -> StrictSessionJsonParser.parse(response.body)
                ?.takeIf { it.expiresAtEpochMillis > nowEpochMillis() }
                ?.let(SessionRefreshResult::Success)
                ?: SessionRefreshResult.Failure
            400, 401 -> SessionRefreshResult.InvalidSession
            else -> SessionRefreshResult.Failure
        }
    }
}

private fun String.escapeRefreshJson(): String = buildString(length) {
    this@escapeRefreshJson.forEach { character ->
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000c' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < 0x20) {
                append("\\u")
                append(character.code.toString(16).padStart(4, '0'))
            } else {
                append(character)
            }
        }
    }
}
