package com.gigagochi.app.core.auth

import com.gigagochi.app.core.model.Session
import kotlinx.coroutines.CancellationException

const val SessionRefreshWindowMillis: Long = 60_000

sealed interface SessionBootstrapState {
    data object Loading : SessionBootstrapState
    data class RefreshPending(
        val storedSession: Session,
        val requestKey: String,
    ) : SessionBootstrapState
    data class RefreshPersistencePending(
        val refreshedSession: Session,
        val requestKey: String,
    ) : SessionBootstrapState
    data class Authenticated(val session: Session) : SessionBootstrapState
    data class Offline(val session: Session) : SessionBootstrapState
    data class Unauthenticated(val wipeStoredSession: Boolean) : SessionBootstrapState
}

sealed interface SessionBootstrapEvent {
    data class RepositoryLoaded(
        val result: SessionLoadResult,
        val nowEpochMillis: Long,
        val requestKey: String = "bootstrap-refresh",
    ) : SessionBootstrapEvent
    data class RefreshFinished(
        val requestKey: String,
        val result: SessionRefreshResult,
    ) : SessionBootstrapEvent
    data class RefreshPersisted(
        val requestKey: String,
        val result: SessionMutationResult,
    ) : SessionBootstrapEvent
}

fun reduceSessionBootstrap(
    state: SessionBootstrapState,
    event: SessionBootstrapEvent,
    refreshWindowMillis: Long = SessionRefreshWindowMillis,
): SessionBootstrapState = when (event) {
    is SessionBootstrapEvent.RepositoryLoaded -> if (state is SessionBootstrapState.Loading) {
        when (val result = event.result) {
            SessionLoadResult.Empty -> SessionBootstrapState.Unauthenticated(false)
            SessionLoadResult.Corrupt -> SessionBootstrapState.Unauthenticated(true)
            SessionLoadResult.Failure -> SessionBootstrapState.Unauthenticated(false)
            is SessionLoadResult.Success -> if (
                result.session.expiresAtEpochMillis > event.nowEpochMillis + refreshWindowMillis
            ) {
                SessionBootstrapState.Authenticated(result.session)
            } else if (result.session.refreshToken != null) {
                SessionBootstrapState.RefreshPending(result.session, event.requestKey)
            } else {
                SessionBootstrapState.Unauthenticated(true)
            }
        }
    } else {
        state
    }

    is SessionBootstrapEvent.RefreshFinished -> if (
        state is SessionBootstrapState.RefreshPending && state.requestKey == event.requestKey
    ) {
        when (val result = event.result) {
            SessionRefreshResult.InvalidSession -> SessionBootstrapState.Unauthenticated(true)
            SessionRefreshResult.Failure -> SessionBootstrapState.Offline(state.storedSession)
            is SessionRefreshResult.Success -> SessionBootstrapState.RefreshPersistencePending(
                refreshedSession = result.session.copy(accountId = state.storedSession.accountId),
                requestKey = state.requestKey,
            )
        }
    } else {
        state
    }

    is SessionBootstrapEvent.RefreshPersisted -> if (
        state is SessionBootstrapState.RefreshPersistencePending &&
        state.requestKey == event.requestKey
    ) {
        when (event.result) {
            SessionMutationResult.Success -> SessionBootstrapState.Authenticated(
                state.refreshedSession,
            )
            SessionMutationResult.Failure -> SessionBootstrapState.Unauthenticated(true)
        }
    } else {
        state
    }
}

sealed interface SessionBootstrapOutcome {
    data class Authenticated(val session: Session) : SessionBootstrapOutcome
    data class Offline(val session: Session) : SessionBootstrapOutcome
    data object Unauthenticated : SessionBootstrapOutcome
}

class SessionBootstrapCoordinator(
    private val repository: SessionRepository,
    private val refreshExchange: SessionRefreshExchange,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun bootstrap(): SessionBootstrapOutcome {
        var state: SessionBootstrapState = SessionBootstrapState.Loading
        val loaded = try {
            repository.load()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            SessionLoadResult.Failure
        }
        state = reduceSessionBootstrap(
            state,
            SessionBootstrapEvent.RepositoryLoaded(loaded, nowEpochMillis()),
        )
        while (true) {
            when (val current = state) {
                SessionBootstrapState.Loading -> error("bootstrap did not consume repository result")
                is SessionBootstrapState.Authenticated -> {
                    return SessionBootstrapOutcome.Authenticated(current.session)
                }
                is SessionBootstrapState.Offline -> {
                    return SessionBootstrapOutcome.Offline(current.session)
                }
                is SessionBootstrapState.Unauthenticated -> {
                    if (current.wipeStoredSession) repository.clear()
                    return SessionBootstrapOutcome.Unauthenticated
                }
                is SessionBootstrapState.RefreshPending -> {
                    val refreshToken = current.storedSession.refreshToken
                        ?: return SessionBootstrapOutcome.Unauthenticated
                    val result = try {
                        refreshExchange.refresh(refreshToken)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        SessionRefreshResult.Failure
                    }
                    state = reduceSessionBootstrap(
                        current,
                        SessionBootstrapEvent.RefreshFinished(current.requestKey, result),
                    )
                }
                is SessionBootstrapState.RefreshPersistencePending -> {
                    val result = try {
                        repository.save(current.refreshedSession)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        SessionMutationResult.Failure
                    }
                    state = reduceSessionBootstrap(
                        current,
                        SessionBootstrapEvent.RefreshPersisted(current.requestKey, result),
                    )
                }
            }
        }
    }
}

interface AuthHeaderProvider {
    fun authorizationHeader(): String?
}

class InMemoryAuthHeaderProvider : AuthHeaderProvider {
    @Volatile
    private var session: Session? = null

    fun update(session: Session) {
        this.session = session
    }

    fun clear() {
        session = null
    }

    override fun authorizationHeader(): String? = session?.accessToken?.reveal()?.let {
        "Bearer $it"
    }

    override fun toString(): String = "InMemoryAuthHeaderProvider(<redacted>)"
}
