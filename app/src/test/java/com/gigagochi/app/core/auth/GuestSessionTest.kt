package com.gigagochi.app.core.auth

import com.gigagochi.app.core.model.SensitiveToken
import com.gigagochi.app.core.model.Session
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestSessionTest {
    private val now = 1_800_000_000_000L

    @Test
    fun guestExchangeAcceptsStrictSessionWithAccountId() = runBlocking {
        val transport = RecordingTransport(
            AuthHttpResponse(
                200,
                """{"accountId":"acct_abcdefghijklmnopqrstuvwx","accessToken":"access","refreshToken":"refresh","expiresAt":1800000060000}"""
                    .toByteArray(),
            ),
        )
        val result = HttpGuestSessionExchange(
            baseUrl = "https://example.com/",
            allowDebugLoopbackHttp = false,
            transport = transport,
            nowEpochMillis = { now },
        ).exchange("123e4567-e89b-42d3-a456-426614174000")

        assertTrue(result is GuestSessionExchangeResult.Success)
        assertEquals("acct_abcdefghijklmnopqrstuvwx", (result as GuestSessionExchangeResult.Success).session.accountId)
        assertEquals("https://example.com/api/auth/guest", transport.request?.url)
        assertEquals(
            "{\"installationId\":\"123e4567-e89b-42d3-a456-426614174000\"}",
            transport.request?.body?.decodeToString(),
        )
    }

    @Test
    fun guestExchangeRejectsNonV4InstallationIdBeforeNetwork() = runBlocking {
        val transport = RecordingTransport(AuthHttpResponse(500, ByteArray(0)))
        val result = HttpGuestSessionExchange(
            baseUrl = "https://example.com/",
            allowDebugLoopbackHttp = false,
            transport = transport,
        ).exchange("123e4567-e89b-12d3-a456-426614174000")

        assertEquals(
            GuestSessionExchangeResult.Failure(AuthFailureKind.BadRequest),
            result,
        )
        assertEquals(null, transport.request)
    }

    @Test
    fun localBootstrapCreatesAndPersistsGuestWhenSessionIsEmpty() = runBlocking {
        val repository = FakeSessionRepository(SessionLoadResult.Empty)
        val guest = session("acct_abcdefghijklmnopqrstuvwx")
        val outcome = LocalSessionBootstrapCoordinator(
            sessionBootstrap = SessionBootstrapCoordinator(
                repository,
                FakeRefreshExchange,
                nowEpochMillis = { now },
            ),
            sessionRepository = repository,
            installationIdProvider = InstallationIdProvider {
                "123e4567-e89b-42d3-a456-426614174000"
            },
            guestSessionExchange = GuestSessionExchange { GuestSessionExchangeResult.Success(guest) },
        ).bootstrap()

        assertEquals(LocalSessionBootstrapOutcome.Ready(guest), outcome)
        assertEquals(guest, repository.saved)
    }

    @Test
    fun localBootstrapReusesValidStoredSessionWithoutGuestCall() = runBlocking {
        val stored = session("acct_abcdefghijklmnopqrstuvwx")
        val repository = FakeSessionRepository(SessionLoadResult.Success(stored))
        var guestCalls = 0
        val outcome = LocalSessionBootstrapCoordinator(
            sessionBootstrap = SessionBootstrapCoordinator(
                repository,
                FakeRefreshExchange,
                nowEpochMillis = { now },
            ),
            sessionRepository = repository,
            installationIdProvider = InstallationIdProvider { error("not called") },
            guestSessionExchange = GuestSessionExchange {
                guestCalls += 1
                GuestSessionExchangeResult.Failure(AuthFailureKind.Server)
            },
        ).bootstrap()

        assertEquals(LocalSessionBootstrapOutcome.Ready(stored), outcome)
        assertEquals(0, guestCalls)
    }

    @Test
    fun localBootstrapUsesStoredSessionOfflineWithoutCreatingAnotherGuest() = runBlocking {
        val stored = session("acct_abcdefghijklmnopqrstuvwx").copy(
            expiresAtEpochMillis = now - 1,
        )
        val repository = FakeSessionRepository(SessionLoadResult.Success(stored))
        var guestCalls = 0
        val outcome = LocalSessionBootstrapCoordinator(
            sessionBootstrap = SessionBootstrapCoordinator(
                repository,
                FakeRefreshExchange,
                nowEpochMillis = { now },
            ),
            sessionRepository = repository,
            installationIdProvider = InstallationIdProvider { error("not called") },
            guestSessionExchange = GuestSessionExchange {
                guestCalls += 1
                GuestSessionExchangeResult.Failure(AuthFailureKind.Server)
            },
        ).bootstrap()

        assertEquals(LocalSessionBootstrapOutcome.Ready(stored), outcome)
        assertEquals(0, guestCalls)
    }

    private fun session(accountId: String) = Session(
        accountId = accountId,
        accessToken = SensitiveToken.of("access"),
        refreshToken = SensitiveToken.of("refresh"),
        expiresAtEpochMillis = now + 86_400_000,
    )

    private class RecordingTransport(
        private val response: AuthHttpResponse,
    ) : AuthHttpTransport {
        var request: AuthHttpRequest? = null

        override suspend fun execute(request: AuthHttpRequest): AuthHttpResponse {
            this.request = request
            return response
        }
    }

    private class FakeSessionRepository(
        private val loaded: SessionLoadResult,
    ) : SessionRepository {
        var saved: Session? = null

        override suspend fun load(): SessionLoadResult = loaded

        override suspend fun save(session: Session): SessionMutationResult {
            saved = session
            return SessionMutationResult.Success
        }

        override suspend fun clear(): SessionMutationResult = SessionMutationResult.Success
    }

    private data object FakeRefreshExchange : SessionRefreshExchange {
        override suspend fun refresh(
            refreshToken: SensitiveToken,
        ): SessionRefreshResult = SessionRefreshResult.Failure
    }
}
