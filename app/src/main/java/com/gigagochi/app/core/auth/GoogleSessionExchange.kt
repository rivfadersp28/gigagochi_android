package com.gigagochi.app.core.auth

import com.gigagochi.app.core.model.Session
import com.gigagochi.app.core.model.SensitiveToken
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

private const val GoogleAuthResponseMaxBytes = 64 * 1024
private const val SessionTokenMaxLength = 16 * 1024

class GoogleAuthEndpoint private constructor(
    val url: String,
) {
    companion object {
        fun fromBaseUrl(
            baseUrl: String,
            allowDebugLoopbackHttp: Boolean,
        ): GoogleAuthEndpoint? = runCatching {
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
            val endpointPath = "$basePath/api/auth/google".let {
                if (it.startsWith('/')) it else "/$it"
            }
            val endpoint = URI(
                normalized.scheme,
                null,
                normalized.host,
                normalized.port,
                endpointPath,
                null,
                null,
            )
            GoogleAuthEndpoint(endpoint.toASCIIString())
        }.getOrNull()
    }
}

data class AuthHttpRequest(
    val url: String,
    val body: ByteArray,
    val method: String = "POST",
    val authorizationHeader: String? = null,
)

data class AuthHttpResponse(
    val statusCode: Int,
    val body: ByteArray,
)

interface AuthHttpTransport {
    suspend fun execute(request: AuthHttpRequest): AuthHttpResponse
}

private class AuthResponseTooLargeException : IOException()

class UrlConnectionAuthHttpTransport(
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 15_000,
    private val maxResponseBytes: Int = GoogleAuthResponseMaxBytes,
) : AuthHttpTransport {
    override suspend fun execute(request: AuthHttpRequest): AuthHttpResponse =
        withContext(Dispatchers.IO) {
            val connection = URL(request.url).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = request.method
                connection.connectTimeout = connectTimeoutMillis
                connection.readTimeout = readTimeoutMillis
                connection.instanceFollowRedirects = false
                connection.doInput = true
                connection.doOutput = request.method == "POST"
                connection.useCaches = false
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.setRequestProperty("Cache-Control", "no-store")
                connection.setRequestProperty("Pragma", "no-cache")
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                request.authorizationHeader?.let {
                    connection.setRequestProperty("Authorization", it)
                }
                if (connection.doOutput) {
                    connection.setFixedLengthStreamingMode(request.body.size)
                    connection.outputStream.use { it.write(request.body) }
                }

                val statusCode = connection.responseCode
                val declaredLength = connection.getHeaderField("Content-Length")
                    ?.trim()
                    ?.toLongOrNull()
                    ?: -1L
                if (declaredLength > maxResponseBytes) throw AuthResponseTooLargeException()
                val stream = if (statusCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }
                val body = stream?.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    var total = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > maxResponseBytes) throw AuthResponseTooLargeException()
                        output.write(buffer, 0, read)
                    }
                    output.toByteArray()
                } ?: ByteArray(0)
                AuthHttpResponse(statusCode = statusCode, body = body)
            } finally {
                connection.disconnect()
            }
        }
}

class HttpGoogleSessionExchange(
    baseUrl: String,
    allowDebugLoopbackHttp: Boolean,
    private val transport: AuthHttpTransport = UrlConnectionAuthHttpTransport(),
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) : SessionExchange {
    private val endpoint = GoogleAuthEndpoint.fromBaseUrl(
        baseUrl = baseUrl,
        allowDebugLoopbackHttp = allowDebugLoopbackHttp,
    )
    private val identityEndpoint = AccountIdentityEndpoint.fromBaseUrl(
        baseUrl = baseUrl,
        allowDebugLoopbackHttp = allowDebugLoopbackHttp,
    )

    override suspend fun exchangeGoogleCredential(
        credential: GoogleAuthCredential,
    ): SessionExchangeResult {
        val resolvedEndpoint = endpoint
            ?: return SessionExchangeResult.Failure(AuthFailure(AuthFailureKind.Configuration))
        if (
            credential.idToken.isBlank() ||
            credential.idToken.length > SessionTokenMaxLength ||
            credential.nonce.length !in 22..256 ||
            credential.nonce.any { it !in UrlSafeNonceCharacters }
        ) {
            return SessionExchangeResult.Failure(AuthFailure(AuthFailureKind.CredentialParse))
        }
        val body = buildString {
            append("{\"idToken\":\"")
            append(credential.idToken.reveal().escapeJsonString())
            append("\",\"nonce\":\"")
            append(credential.nonce.escapeJsonString())
            append("\"}")
        }.toByteArray(StandardCharsets.UTF_8)

        val response = try {
            transport.execute(AuthHttpRequest(url = resolvedEndpoint.url, body = body))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: AuthResponseTooLargeException) {
            return SessionExchangeResult.Failure(AuthFailure(AuthFailureKind.Server))
        } catch (_: IOException) {
            return SessionExchangeResult.Failure(AuthFailure(AuthFailureKind.Network))
        } catch (_: Exception) {
            return SessionExchangeResult.Failure(AuthFailure(AuthFailureKind.Network))
        }

        return when (response.statusCode) {
            in 200..299 -> {
                val session = StrictSessionJsonParser.parse(response.body)
                    ?.takeIf { it.expiresAtEpochMillis > nowEpochMillis() }
                    ?: return SessionExchangeResult.Failure(AuthFailure(AuthFailureKind.Server))
                when (val identity = fetchAccountId(session.accessToken)) {
                    is AccountIdentityFetchResult.Success -> SessionExchangeResult.Success(
                        session.copy(accountId = identity.accountId),
                    )
                    is AccountIdentityFetchResult.Failure -> SessionExchangeResult.Failure(
                        AuthFailure(identity.kind),
                    )
                }
            }
            400 -> SessionExchangeResult.Failure(AuthFailure(AuthFailureKind.BadRequest))
            401 -> SessionExchangeResult.Failure(AuthFailure(AuthFailureKind.Unauthorized))
            409 -> SessionExchangeResult.Failure(AuthFailure(AuthFailureKind.Conflict))
            429 -> SessionExchangeResult.Failure(AuthFailure(AuthFailureKind.RateLimited))
            in 500..599 -> SessionExchangeResult.Failure(AuthFailure(AuthFailureKind.Server))
            else -> SessionExchangeResult.Failure(AuthFailure(AuthFailureKind.Server))
        }
    }

    private suspend fun fetchAccountId(accessToken: SensitiveToken): AccountIdentityFetchResult {
        val resolvedEndpoint = identityEndpoint
            ?: return AccountIdentityFetchResult.Failure(AuthFailureKind.Configuration)
        val response = try {
            transport.execute(
                AuthHttpRequest(
                    url = resolvedEndpoint.url,
                    body = ByteArray(0),
                    method = "GET",
                    authorizationHeader = "Bearer ${accessToken.reveal()}",
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: AuthResponseTooLargeException) {
            return AccountIdentityFetchResult.Failure(AuthFailureKind.Server)
        } catch (_: IOException) {
            return AccountIdentityFetchResult.Failure(AuthFailureKind.Network)
        } catch (_: Exception) {
            return AccountIdentityFetchResult.Failure(AuthFailureKind.Network)
        }
        return when (response.statusCode) {
            in 200..299 -> StrictAccountIdentityJsonParser.parse(response.body)
                ?.let(AccountIdentityFetchResult::Success)
                ?: AccountIdentityFetchResult.Failure(AuthFailureKind.Server)
            401 -> AccountIdentityFetchResult.Failure(AuthFailureKind.Unauthorized)
            else -> AccountIdentityFetchResult.Failure(AuthFailureKind.Server)
        }
    }
}

private sealed interface AccountIdentityFetchResult {
    data class Success(val accountId: String) : AccountIdentityFetchResult
    data class Failure(val kind: AuthFailureKind) : AccountIdentityFetchResult
}

class AccountIdentityEndpoint private constructor(val url: String) {
    companion object {
        fun fromBaseUrl(baseUrl: String, allowDebugLoopbackHttp: Boolean): AccountIdentityEndpoint? =
            GoogleAuthEndpoint.fromBaseUrl(baseUrl, allowDebugLoopbackHttp)?.let { google ->
                AccountIdentityEndpoint(google.url.removeSuffix("/google") + "/me")
            }
    }
}

internal object StrictAccountIdentityJsonParser {
    private val AccountIdPattern = Regex("acct_[A-Za-z0-9_-]{20,64}")

    fun parse(bytes: ByteArray): String? = runCatching {
        require(bytes.isNotEmpty() && bytes.size <= GoogleAuthResponseMaxBytes)
        val text = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
        val match = Regex("""^\s*\{\s*"accountId"\s*:\s*"([^"]+)"\s*}\s*$""")
            .matchEntire(text)
            ?: return null
        match.groupValues[1].takeIf(AccountIdPattern::matches)
    }.getOrNull()
}

internal object StrictSessionJsonParser {
    fun parse(bytes: ByteArray): Session? = runCatching {
        require(bytes.isNotEmpty() && bytes.size <= GoogleAuthResponseMaxBytes)
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = decoder.decode(ByteBuffer.wrap(bytes)).toString()
        SessionObjectParser(text).parse()
    }.getOrNull()
}

private class SessionObjectParser(
    private val source: String,
) {
    private var position: Int = 0

    fun parse(): Session {
        skipWhitespace()
        expect('{')
        skipWhitespace()
        val values = mutableMapOf<String, Any?>()
        if (!consume('}')) {
            while (true) {
                val key = parseString()
                require(key in setOf("accessToken", "refreshToken", "expiresAt"))
                require(key !in values)
                skipWhitespace()
                expect(':')
                skipWhitespace()
                values[key] = when (key) {
                    "accessToken", "refreshToken" -> parseStringOrNull()
                    "expiresAt" -> parseLong()
                    else -> error("unreachable")
                }
                skipWhitespace()
                if (consume('}')) break
                expect(',')
                skipWhitespace()
            }
        }
        skipWhitespace()
        require(position == source.length)
        val accessToken = values["accessToken"] as? String
        val expiresAt = values["expiresAt"] as? Long
        require(!accessToken.isNullOrBlank() && accessToken.length <= SessionTokenMaxLength)
        require(expiresAt != null && expiresAt > 0)
        val refreshToken = values["refreshToken"] as? String
        require(
            refreshToken == null ||
                (refreshToken.isNotBlank() && refreshToken.length <= SessionTokenMaxLength),
        )
        return Session(
            accountId = "",
            accessToken = SensitiveToken.of(accessToken),
            refreshToken = refreshToken?.let(SensitiveToken::of),
            expiresAtEpochMillis = expiresAt,
        )
    }

    private fun parseStringOrNull(): String? {
        if (source.startsWith("null", position)) {
            position += 4
            return null
        }
        return parseString()
    }

    private fun parseString(): String {
        expect('"')
        val result = StringBuilder()
        while (position < source.length) {
            val character = source[position++]
            when {
                character == '"' -> return result.toString()
                character == '\\' -> {
                    require(position < source.length)
                    when (val escaped = source[position++]) {
                        '"', '\\', '/' -> result.append(escaped)
                        'b' -> result.append('\b')
                        'f' -> result.append('\u000c')
                        'n' -> result.append('\n')
                        'r' -> result.append('\r')
                        't' -> result.append('\t')
                        'u' -> {
                            require(position + 4 <= source.length)
                            val value = source.substring(position, position + 4).toInt(16)
                            result.append(value.toChar())
                            position += 4
                        }
                        else -> error("Invalid JSON escape")
                    }
                }
                character.code < 0x20 -> error("Invalid JSON control character")
                else -> result.append(character)
            }
        }
        error("Unterminated JSON string")
    }

    private fun parseLong(): Long {
        val start = position
        if (peek() == '-') position += 1
        require(position < source.length && source[position].isDigit())
        if (source[position] == '0') {
            position += 1
            require(position == source.length || !source[position].isDigit())
        } else {
            while (position < source.length && source[position].isDigit()) position += 1
        }
        require(position == source.length || source[position] !in listOf('.', 'e', 'E', '+'))
        return source.substring(start, position).toLong()
    }

    private fun skipWhitespace() {
        while (position < source.length && source[position] in " \n\r\t") position += 1
    }

    private fun consume(expected: Char): Boolean {
        if (peek() != expected) return false
        position += 1
        return true
    }

    private fun expect(expected: Char) {
        require(consume(expected))
    }

    private fun peek(): Char? = source.getOrNull(position)
}

private fun String.escapeJsonString(): String = buildString(length) {
    this@escapeJsonString.forEach { character ->
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

private val UrlSafeNonceCharacters =
    ('A'..'Z').toSet() + ('a'..'z') + ('0'..'9') + setOf('-', '_')
