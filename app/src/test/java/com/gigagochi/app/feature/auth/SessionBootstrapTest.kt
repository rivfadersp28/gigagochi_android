package com.gigagochi.app.feature.auth

import com.gigagochi.app.core.auth.InMemoryAuthHeaderProvider
import com.gigagochi.app.core.auth.SessionBootstrapCoordinator
import com.gigagochi.app.core.auth.SessionBootstrapEvent
import com.gigagochi.app.core.auth.SessionBootstrapOutcome
import com.gigagochi.app.core.auth.SessionBootstrapState
import com.gigagochi.app.core.auth.SessionLoadResult
import com.gigagochi.app.core.auth.SessionMutationResult
import com.gigagochi.app.core.auth.SessionRefreshExchange
import com.gigagochi.app.core.auth.SessionRefreshResult
import com.gigagochi.app.core.auth.SessionRepository
import com.gigagochi.app.core.auth.reduceSessionBootstrap
import com.gigagochi.app.core.model.SensitiveToken
import com.gigagochi.app.core.model.Session
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionBootstrapTest {
    private val now = 1_800_000_000_000L
    private val valid = session("access-valid", "refresh-valid", now + 120_000)
    private val nearExpiry = session("access-old", "refresh-old", now + 30_000)
    private val refreshed = session(
        "access-new",
        "refresh-new",
        now + 900_000,
        accountId = "acct_untrusted_refresh_payload",
    )

    @Test
    fun validEncryptedSessionRestoresWithoutRefresh() = runBlocking {
        val repository = FakeRepository(SessionLoadResult.Success(valid))
        val exchange = FakeRefreshExchange(SessionRefreshResult.Failure)

        val outcome = coordinator(repository, exchange).bootstrap()

        assertEquals(SessionBootstrapOutcome.Authenticated(valid), outcome)
        assertEquals(0, exchange.calls)
        assertEquals(0, repository.saveCalls)
    }

    @Test
    fun nearExpirySessionRefreshesAndPersistsRotationBeforeUse() = runBlocking {
        val repository = FakeRepository(SessionLoadResult.Success(nearExpiry))
        val exchange = FakeRefreshExchange(SessionRefreshResult.Success(refreshed))

        val outcome = coordinator(repository, exchange).bootstrap()

        val expected = refreshed.copy(accountId = nearExpiry.accountId)
        assertEquals(SessionBootstrapOutcome.Authenticated(expected), outcome)
        assertEquals(1, exchange.calls)
        assertEquals(expected, repository.savedSession)
        assertEquals(0, repository.clearCalls)
    }

    @Test
    fun invalidOrReplayedRefreshWipesStoredSession() = runBlocking {
        val repository = FakeRepository(SessionLoadResult.Success(nearExpiry))
        val exchange = FakeRefreshExchange(SessionRefreshResult.InvalidSession)

        assertEquals(SessionBootstrapOutcome.Unauthenticated, coordinator(repository, exchange).bootstrap())
        assertEquals(1, repository.clearCalls)
    }

    @Test
    fun unavailableRefreshKeepsStoredSessionForOfflineStartup() = runBlocking {
        val repository = FakeRepository(SessionLoadResult.Success(nearExpiry))
        val exchange = FakeRefreshExchange(SessionRefreshResult.Failure)

        val outcome = coordinator(repository, exchange).bootstrap()

        assertEquals(SessionBootstrapOutcome.Offline(nearExpiry), outcome)
        assertEquals(1, exchange.calls)
        assertEquals(0, repository.clearCalls)
        assertEquals(0, repository.saveCalls)
    }

    @Test
    fun corruptedCiphertextIsWipedWhileIoFailureOnlyFailsClosed() = runBlocking {
        val corrupt = FakeRepository(SessionLoadResult.Corrupt)
        assertEquals(
            SessionBootstrapOutcome.Unauthenticated,
            coordinator(corrupt, FakeRefreshExchange(SessionRefreshResult.Failure)).bootstrap(),
        )
        assertEquals(1, corrupt.clearCalls)

        val ioFailure = FakeRepository(SessionLoadResult.Failure)
        assertEquals(
            SessionBootstrapOutcome.Unauthenticated,
            coordinator(ioFailure, FakeRefreshExchange(SessionRefreshResult.Failure)).bootstrap(),
        )
        assertEquals(0, ioFailure.clearCalls)
    }

    @Test
    fun failedAtomicPersistenceNeverAuthenticatesAndWipesOldRotation() = runBlocking {
        val repository = FakeRepository(
            loadResult = SessionLoadResult.Success(nearExpiry),
            saveResult = SessionMutationResult.Failure,
        )

        val outcome = coordinator(
            repository,
            FakeRefreshExchange(SessionRefreshResult.Success(refreshed)),
        ).bootstrap()

        assertEquals(SessionBootstrapOutcome.Unauthenticated, outcome)
        assertEquals(1, repository.saveCalls)
        assertEquals(1, repository.clearCalls)
    }

    @Test
    fun reducerIgnoresStaleRefreshAndPersistenceResults() {
        val pending = reduceSessionBootstrap(
            SessionBootstrapState.Loading,
            SessionBootstrapEvent.RepositoryLoaded(
                SessionLoadResult.Success(nearExpiry),
                now,
                requestKey = "refresh-1",
            ),
        )
        assertTrue(pending is SessionBootstrapState.RefreshPending)
        assertSame(
            pending,
            reduceSessionBootstrap(
                pending,
                SessionBootstrapEvent.RefreshFinished(
                    "stale",
                    SessionRefreshResult.Success(refreshed),
                ),
            ),
        )
        val persistence = reduceSessionBootstrap(
            pending,
            SessionBootstrapEvent.RefreshFinished(
                "refresh-1",
                SessionRefreshResult.Success(refreshed),
            ),
        )
        assertTrue(persistence is SessionBootstrapState.RefreshPersistencePending)
        assertSame(
            persistence,
            reduceSessionBootstrap(
                persistence,
                SessionBootstrapEvent.RefreshPersisted(
                    "stale",
                    SessionMutationResult.Success,
                ),
            ),
        )
    }

    @Test
    fun authHeaderProviderKeepsTokenOnlyInMemoryAndRedactsItself() {
        val provider = InMemoryAuthHeaderProvider()
        assertEquals(null, provider.authorizationHeader())

        provider.update(valid)
        assertEquals("Bearer access-valid", provider.authorizationHeader())
        assertFalse(provider.toString().contains("access-valid"))

        provider.clear()
        assertEquals(null, provider.authorizationHeader())
    }

    private fun coordinator(repository: FakeRepository, exchange: FakeRefreshExchange) =
        SessionBootstrapCoordinator(
            repository = repository,
            refreshExchange = exchange,
            nowEpochMillis = { now },
        )

    private fun session(
        access: String,
        refresh: String?,
        expiry: Long,
        accountId: String = "acct_abcdefghijklmnopqrstuvwx",
    ) = Session(
        accountId = accountId,
        accessToken = SensitiveToken.of(access),
        refreshToken = refresh?.let(SensitiveToken::of),
        expiresAtEpochMillis = expiry,
    )

    private class FakeRepository(
        var loadResult: SessionLoadResult,
        var saveResult: SessionMutationResult = SessionMutationResult.Success,
        var clearResult: SessionMutationResult = SessionMutationResult.Success,
    ) : SessionRepository {
        var saveCalls = 0
        var clearCalls = 0
        var savedSession: Session? = null

        override suspend fun load(): SessionLoadResult = loadResult

        override suspend fun save(session: Session): SessionMutationResult {
            saveCalls += 1
            savedSession = session
            return saveResult
        }

        override suspend fun clear(): SessionMutationResult {
            clearCalls += 1
            return clearResult
        }
    }

    private class FakeRefreshExchange(
        var result: SessionRefreshResult,
    ) : SessionRefreshExchange {
        var calls = 0

        override suspend fun refresh(refreshToken: SensitiveToken): SessionRefreshResult {
            calls += 1
            return result
        }
    }
}
