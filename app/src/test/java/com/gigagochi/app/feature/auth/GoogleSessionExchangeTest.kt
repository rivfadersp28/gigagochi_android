package com.gigagochi.app.feature.auth

import com.gigagochi.app.core.auth.AuthFailureKind
import com.gigagochi.app.core.auth.AuthHttpRequest
import com.gigagochi.app.core.auth.AuthHttpResponse
import com.gigagochi.app.core.auth.AuthHttpTransport
import com.gigagochi.app.core.auth.GoogleAuthCredential
import com.gigagochi.app.core.auth.GoogleAuthEndpoint
import com.gigagochi.app.core.auth.HttpGoogleSessionExchange
import com.gigagochi.app.core.auth.SessionExchangeResult
import com.gigagochi.app.core.auth.StrictSessionJsonParser
import com.gigagochi.app.core.model.SensitiveToken
import java.io.IOException
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleSessionExchangeTest {
    private val credential = GoogleAuthCredential(
        idToken = SensitiveToken.of("google-token-secret"),
        nonce = "abcdefghijklmnopqrstuvwxyz0123456789_-nonce",
    )

    @Test
    fun endpointRequiresHttpsExceptExplicitDebugLoopback() {
        assertNull(GoogleAuthEndpoint.fromBaseUrl("http://api.example.com", false))
        assertNull(GoogleAuthEndpoint.fromBaseUrl("http://api.example.com", true))
        assertEquals(
            "http://10.0.2.2:8000/api/auth/google",
            GoogleAuthEndpoint.fromBaseUrl("http://10.0.2.2:8000/", true)?.url,
        )
        assertEquals(
            "https://api.example.com/v1/api/auth/google",
            GoogleAuthEndpoint.fromBaseUrl("https://api.example.com/v1/", false)?.url,
        )
        assertNull(GoogleAuthEndpoint.fromBaseUrl("https://user@api.example.com/", false))
        assertNull(GoogleAuthEndpoint.fromBaseUrl("not a url", false))
    }

    @Test
    fun strictParserAcceptsOnlyTypedSessionShape() {
        val valid = StrictSessionJsonParser.parse(
            """{"accessToken":"access","refreshToken":"refresh","expiresAt":2000}"""
                .toByteArray(),
        )
        assertEquals(SensitiveToken.of("access"), valid?.accessToken)
        assertEquals(SensitiveToken.of("refresh"), valid?.refreshToken)
        assertEquals(2000L, valid?.expiresAtEpochMillis)
        assertEquals("", valid?.accountId)

        assertNull(StrictSessionJsonParser.parse("{}".toByteArray()))
        assertNull(
            StrictSessionJsonParser.parse(
                """{"accessToken":"access","expiresAt":"2000"}""".toByteArray(),
            ),
        )
        assertNull(
            StrictSessionJsonParser.parse(
                """{"accessToken":"access","expiresAt":2000,"extra":true}""".toByteArray(),
            ),
        )
        assertNull(
            StrictSessionJsonParser.parse(
                """{"accessToken":"access","refreshToken":" ","expiresAt":2000}"""
                    .toByteArray(),
            ),
        )
    }

    @Test
    fun successfulExchangePostsTokenAndNonceAndRequiresFutureExpiry() = runBlocking {
        val transport = FakeTransport(
            AuthHttpResponse(
                200,
                """{"accessToken":"session","refreshToken":null,"expiresAt":2001}"""
                    .toByteArray(),
            ),
        )
        val exchange = exchange(transport, now = 2000)
        val result = exchange.exchangeGoogleCredential(credential)

        assertTrue(result is SessionExchangeResult.Success)
        assertEquals("acct_abcdefghijklmnopqrstuvwx", (result as SessionExchangeResult.Success).session.accountId)
        assertEquals("https://api.example.com/api/auth/google", transport.requests.first().url)
        assertEquals("https://api.example.com/api/auth/me", transport.requests.last().url)
        assertEquals("GET", transport.requests.last().method)
        assertEquals("Bearer session", transport.requests.last().authorizationHeader)
        val requestBody = transport.requests.first().body.toString(StandardCharsets.UTF_8)
        assertEquals(
            """{"idToken":"google-token-secret","nonce":"abcdefghijklmnopqrstuvwxyz0123456789_-nonce"}""",
            requestBody,
        )

        transport.response = AuthHttpResponse(
            200,
            """{"accessToken":"session","expiresAt":2000}""".toByteArray(),
        )
        assertFailure(exchange.exchangeGoogleCredential(credential), AuthFailureKind.Server)
        transport.response = AuthHttpResponse(
            200,
            """{"accessToken":"session","expiresAt":1999}""".toByteArray(),
        )
        assertFailure(exchange.exchangeGoogleCredential(credential), AuthFailureKind.Server)
    }

    @Test
    fun mapsHttpAndNetworkFailuresWithoutUsingResponseBody() = runBlocking {
        val transport = FakeTransport(AuthHttpResponse(400, "secret server body".toByteArray()))
        val exchange = exchange(transport)
        val mappings = listOf(
            400 to AuthFailureKind.BadRequest,
            401 to AuthFailureKind.Unauthorized,
            409 to AuthFailureKind.Conflict,
            429 to AuthFailureKind.RateLimited,
            500 to AuthFailureKind.Server,
            503 to AuthFailureKind.Server,
        )
        mappings.forEach { (status, kind) ->
            transport.response = AuthHttpResponse(status, "secret server body".toByteArray())
            val result = exchange.exchangeGoogleCredential(credential)
            assertFailure(result, kind)
            assertFalse(result.toString().contains("secret server body"))
        }

        transport.failure = IOException("network detail must not escape")
        val network = exchange.exchangeGoogleCredential(credential)
        assertFailure(network, AuthFailureKind.Network)
        assertFalse(network.toString().contains("network detail"))
    }

    @Test
    fun identityFetchPreservesNetworkUnauthorizedAndServerFailures() = runBlocking {
        val transport = FakeTransport(
            AuthHttpResponse(
                200,
                """{"accessToken":"session","refreshToken":null,"expiresAt":2001}"""
                    .toByteArray(),
            ),
        )
        val exchange = exchange(transport, now = 2000)

        transport.identityFailure = IOException("offline")
        assertFailure(exchange.exchangeGoogleCredential(credential), AuthFailureKind.Network)

        transport.identityFailure = null
        transport.identityResponse = AuthHttpResponse(401, ByteArray(0))
        assertFailure(exchange.exchangeGoogleCredential(credential), AuthFailureKind.Unauthorized)

        transport.identityResponse = AuthHttpResponse(503, ByteArray(0))
        assertFailure(exchange.exchangeGoogleCredential(credential), AuthFailureKind.Server)

        transport.identityResponse = AuthHttpResponse(200, "{}".toByteArray())
        assertFailure(exchange.exchangeGoogleCredential(credential), AuthFailureKind.Server)
    }

    @Test(expected = CancellationException::class)
    fun coroutineCancellationIsRethrown() {
        runBlocking {
            val transport = FakeTransport(AuthHttpResponse(500, ByteArray(0))).apply {
                failure = CancellationException("cancel")
            }
            exchange(transport).exchangeGoogleCredential(credential)
        }
    }

    @Test
    fun malformedNonceNeverReachesTransport() = runBlocking {
        val transport = FakeTransport(AuthHttpResponse(500, ByteArray(0)))
        val invalid = credential.copy(nonce = "x")
        assertFailure(
            exchange(transport).exchangeGoogleCredential(invalid),
            AuthFailureKind.CredentialParse,
        )
        assertTrue(transport.requests.isEmpty())
    }

    private fun exchange(
        transport: FakeTransport,
        now: Long = 1000,
    ) = HttpGoogleSessionExchange(
        baseUrl = "https://api.example.com/",
        allowDebugLoopbackHttp = false,
        transport = transport,
        nowEpochMillis = { now },
    )

    private fun assertFailure(result: SessionExchangeResult, expected: AuthFailureKind) {
        assertTrue(result is SessionExchangeResult.Failure)
        assertEquals(expected, (result as SessionExchangeResult.Failure).failure.kind)
    }

    private class FakeTransport(
        var response: AuthHttpResponse,
    ) : AuthHttpTransport {
        val requests = mutableListOf<AuthHttpRequest>()
        var failure: Throwable? = null
        var identityFailure: Throwable? = null
        var identityResponse = AuthHttpResponse(
            200,
            """{"accountId":"acct_abcdefghijklmnopqrstuvwx"}""".toByteArray(),
        )

        override suspend fun execute(request: AuthHttpRequest): AuthHttpResponse {
            requests += request
            if (request.url.endsWith("/me")) identityFailure?.let { throw it }
            failure?.let { throw it }
            return if (request.url.endsWith("/me")) {
                identityResponse
            } else {
                response
            }
        }
    }
}
