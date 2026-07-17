package com.gigagochi.app.feature.auth

import com.gigagochi.app.core.auth.AuthHttpRequest
import com.gigagochi.app.core.auth.AuthHttpResponse
import com.gigagochi.app.core.auth.AuthHttpTransport
import com.gigagochi.app.core.auth.HttpSessionRefreshExchange
import com.gigagochi.app.core.auth.SessionRefreshEndpoint
import com.gigagochi.app.core.auth.SessionRefreshResult
import com.gigagochi.app.core.model.SensitiveToken
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRefreshExchangeTest {
    private val token = SensitiveToken.of("refresh-secret")

    @Test
    fun endpointUsesRefreshPathAndKeepsReleaseHttpsOnly() {
        assertEquals(
            "https://api.example.com/v1/api/auth/refresh",
            SessionRefreshEndpoint.fromBaseUrl(
                "https://api.example.com/v1/",
                false,
            )?.url,
        )
        assertEquals(
            "http://10.0.2.2:8000/api/auth/refresh",
            SessionRefreshEndpoint.fromBaseUrl("http://10.0.2.2:8000", true)?.url,
        )
        assertEquals(null, SessionRefreshEndpoint.fromBaseUrl("http://api.example.com", true))
    }

    @Test
    fun refreshPostsTokenAndRequiresFutureEpochMillis() = runBlocking {
        val transport = FakeTransport(
            AuthHttpResponse(
                200,
                """{"accessToken":"new-access","refreshToken":"new-refresh","expiresAt":2001}"""
                    .toByteArray(),
            ),
        )
        val exchange = exchange(transport, now = 2000)

        val result = exchange.refresh(token)

        assertTrue(result is SessionRefreshResult.Success)
        assertEquals(
            "{\"refreshToken\":\"refresh-secret\"}",
            transport.lastRequest?.body?.decodeToString(),
        )
        transport.response = AuthHttpResponse(
            200,
            """{"accessToken":"new-access","refreshToken":"new-refresh","expiresAt":2000}"""
                .toByteArray(),
        )
        assertEquals(SessionRefreshResult.Failure, exchange.refresh(token))
    }

    @Test
    fun unauthorizedRefreshIsInvalidWhileNetworkFailureIsRetryable() = runBlocking {
        val transport = FakeTransport(AuthHttpResponse(401, "secret body".toByteArray()))
        val exchange = exchange(transport)
        assertEquals(SessionRefreshResult.InvalidSession, exchange.refresh(token))

        transport.failure = IOException("secret network detail")
        assertEquals(SessionRefreshResult.Failure, exchange.refresh(token))
    }

    @Test(expected = CancellationException::class)
    fun cancellationIsRethrown() {
        runBlocking {
            val transport = FakeTransport(AuthHttpResponse(500, ByteArray(0))).apply {
                failure = CancellationException("cancel")
            }
            exchange(transport).refresh(token)
        }
    }

    private fun exchange(transport: FakeTransport, now: Long = 1000) =
        HttpSessionRefreshExchange(
            baseUrl = "https://api.example.com/",
            allowDebugLoopbackHttp = false,
            transport = transport,
            nowEpochMillis = { now },
        )

    private class FakeTransport(var response: AuthHttpResponse) : AuthHttpTransport {
        var lastRequest: AuthHttpRequest? = null
        var failure: Throwable? = null

        override suspend fun execute(request: AuthHttpRequest): AuthHttpResponse {
            lastRequest = request
            failure?.let { throw it }
            return response
        }
    }
}
