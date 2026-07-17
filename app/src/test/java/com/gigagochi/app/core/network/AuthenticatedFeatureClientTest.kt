package com.gigagochi.app.core.network

import com.gigagochi.app.core.auth.InMemoryAuthHeaderProvider
import com.gigagochi.app.core.auth.SessionLoadResult
import com.gigagochi.app.core.auth.SessionMutationResult
import com.gigagochi.app.core.auth.SessionRefreshExchange
import com.gigagochi.app.core.auth.SessionRefreshResult
import com.gigagochi.app.core.auth.SessionRepository
import com.gigagochi.app.core.model.SensitiveToken
import com.gigagochi.app.core.model.Session
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthenticatedFeatureClientTest {
    @Test
    fun storageReadFailureIsRecoverableAndDoesNotClearEnvelopeOrSignalInvalid() = runBlocking {
        val repository = FakeRepository(SessionLoadResult.Failure)
        var invalidSignals = 0
        var calls = 0
        val client = client(repository, transport = { _, _ -> calls += 1; response(200) }) {
            invalidSignals += 1
        }

        assertEquals(AuthenticatedFeatureResult.SessionUnavailable, client.execute(request()))
        assertEquals(0, repository.clearCalls)
        assertEquals(0, invalidSignals)
        assertEquals(0, calls)
    }

    @Test
    fun nearExpiryWithoutRefreshTokenUsesAccessUntilItActuallyExpires() = runBlocking {
        val repository = FakeRepository(SessionLoadResult.Success(session(expiresAt = 10_001, refresh = null)))
        var calls = 0
        val client = client(repository, now = { 10_000 }, transport = { _, _ ->
            calls += 1
            response(200)
        })

        assertTrue(client.execute(request()) is AuthenticatedFeatureResult.Response)
        assertEquals(1, calls)
        assertEquals(0, repository.clearCalls)
    }

    @Test
    fun forced401WithoutRefreshTokenInvalidatesWithoutReplayingRejectedAccess() = runBlocking {
        val repository = FakeRepository(SessionLoadResult.Success(session(expiresAt = 20_000, refresh = null)))
        var calls = 0
        val client = client(repository, now = { 10_000 }, transport = { _, _ ->
            calls += 1
            response(401)
        })

        assertEquals(AuthenticatedFeatureResult.SessionInvalid, client.execute(request()))
        assertEquals(1, calls)
        assertEquals(1, repository.clearCalls)
    }

    @Test
    fun concurrentNearExpiryCallsSingleFlightRefreshAndRereadRotatedSession() = runBlocking {
        val repository = FakeRepository(SessionLoadResult.Success(session(expiresAt = 10_001)))
        val refreshCalls = AtomicInteger()
        val refresh = refreshExchange {
            refreshCalls.incrementAndGet()
            delay(30)
            SessionRefreshResult.Success(session(access = "rotated", expiresAt = 100_000))
        }
        val usedTokens = mutableListOf<String>()
        val client = client(repository, refresh, now = { 10_000 }, transport = { _, token ->
            synchronized(usedTokens) { usedTokens += token.reveal() }
            response(200)
        })

        val first = async { client.execute(request()) }
        val second = async { client.execute(request()) }
        first.await()
        second.await()

        assertEquals(1, refreshCalls.get())
        assertEquals(listOf("rotated", "rotated"), usedTokens.sorted())
        assertEquals(1, repository.saveCalls)
    }

    @Test
    fun authOnlyReplayUsesExactRequestAndPersistencePrecedesRotatedRequest() = runBlocking {
        val repository = FakeRepository(SessionLoadResult.Success(session(expiresAt = 100_000)))
        val requests = mutableListOf<FeatureHttpRequest>()
        val savedBeforeRotatedRequest = mutableListOf<Boolean>()
        val client = client(repository, transport = { request, token ->
            requests += request
            savedBeforeRotatedRequest += token.reveal() != "rotated" || repository.saveCalls == 1
            if (requests.size == 1) response(401) else response(200)
        })

        assertTrue(client.execute(request()) is AuthenticatedFeatureResult.Response)
        assertEquals(2, requests.size)
        assertSame(requests[0], requests[1])
        assertTrue(savedBeforeRotatedRequest.all { it })
    }

    @Test
    fun rotatedPersistenceFailureFailsClosedAndNeverSendsRotatedAccess() = runBlocking {
        val repository = FakeRepository(
            SessionLoadResult.Success(session(expiresAt = 10_001)),
            saveResult = SessionMutationResult.Failure,
        )
        var transportCalls = 0
        var invalidSignals = 0
        val client = client(repository, now = { 10_000 }, transport = { _, _ ->
            transportCalls += 1
            response(200)
        }) { invalidSignals += 1 }

        assertEquals(AuthenticatedFeatureResult.SessionInvalid, client.execute(request()))
        assertEquals(0, transportCalls)
        assertEquals(1, repository.clearCalls)
        assertEquals(1, invalidSignals)
    }

    @Test
    fun onlyIoIsMappedToNetworkAndCancellationOrProgrammerErrorsEscape() = runBlocking {
        val repository = FakeRepository(SessionLoadResult.Success(session(expiresAt = 100_000)))
        val network = client(repository, transport = { _, _ -> throw IOException("secret body") })
        assertEquals(AuthenticatedFeatureResult.NetworkFailure, network.execute(request()))

        val programmer = client(repository, transport = { _, _ -> error("decoder bug") })
        var escaped = false
        try {
            programmer.execute(request())
        } catch (_: IllegalStateException) {
            escaped = true
        }
        assertTrue(escaped)

        val cancelled = client(repository, transport = { _, _ -> throw CancellationException() })
        var cancellationEscaped = false
        try {
            cancelled.execute(request())
        } catch (_: CancellationException) {
            cancellationEscaped = true
        }
        assertTrue(cancellationEscaped)
    }

    @Test
    fun responseAndFailuresNeverRenderBodyOrSecrets() {
        val response = FeatureHttpResponse(500, "token=secret-body".toByteArray())
        assertFalse(response.toString().contains("secret"))
        assertFalse(FeatureFailure(FeatureFailureKind.Server, "SAFE_CODE").toString().contains("body"))
    }

    private fun client(
        repository: FakeRepository,
        refresh: SessionRefreshExchange = refreshExchange {
            SessionRefreshResult.Success(session(access = "rotated", expiresAt = 100_000))
        },
        now: () -> Long = { 10_000 },
        transport: suspend (FeatureHttpRequest, SensitiveToken) -> FeatureHttpResponse,
        onInvalid: suspend () -> Unit = {},
    ) = AuthenticatedFeatureClient(
        repository,
        refresh,
        object : FeatureHttpTransport {
            override suspend fun execute(request: FeatureHttpRequest, accessToken: SensitiveToken) =
                transport(request, accessToken)
        },
        InMemoryAuthHeaderProvider(),
        now,
        onInvalid,
    )

    private fun request() = FeatureHttpRequest("POST", "/api/android/chat", "{}".toByteArray())
    private fun response(status: Int) = FeatureHttpResponse(status, "{}".toByteArray())

    private fun session(
        access: String = "access",
        expiresAt: Long,
        refresh: String? = "refresh",
    ) = Session(
        "account-a",
        SensitiveToken.of(access),
        refresh?.let(SensitiveToken::of),
        expiresAt,
    )

    private class FakeRepository(
        initial: SessionLoadResult,
        private val saveResult: SessionMutationResult = SessionMutationResult.Success,
    ) : SessionRepository {
        private var current = initial
        var saveCalls = 0
        var clearCalls = 0

        override suspend fun load(): SessionLoadResult = current

        override suspend fun save(session: Session): SessionMutationResult {
            saveCalls += 1
            if (saveResult == SessionMutationResult.Success) current = SessionLoadResult.Success(session)
            return saveResult
        }

        override suspend fun clear(): SessionMutationResult {
            clearCalls += 1
            current = SessionLoadResult.Empty
            return SessionMutationResult.Success
        }
    }

    private companion object {
        fun refreshExchange(block: suspend () -> SessionRefreshResult) =
            object : SessionRefreshExchange {
                override suspend fun refresh(refreshToken: SensitiveToken): SessionRefreshResult = block()
            }
    }
}
