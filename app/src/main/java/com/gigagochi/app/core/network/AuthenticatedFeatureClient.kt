package com.gigagochi.app.core.network

import com.gigagochi.app.core.auth.InMemoryAuthHeaderProvider
import com.gigagochi.app.core.auth.SessionLoadResult
import com.gigagochi.app.core.auth.SessionMutationResult
import com.gigagochi.app.core.auth.SessionRefreshExchange
import com.gigagochi.app.core.auth.SessionRefreshResult
import com.gigagochi.app.core.auth.SessionRefreshWindowMillis
import com.gigagochi.app.core.auth.SessionRepository
import com.gigagochi.app.core.model.SensitiveToken
import com.gigagochi.app.core.model.Session
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface AuthenticatedFeatureResult {
    data class Response(val response: FeatureHttpResponse) : AuthenticatedFeatureResult
    data object NetworkFailure : AuthenticatedFeatureResult
    data object RefreshUnavailable : AuthenticatedFeatureResult
    data object SessionUnavailable : AuthenticatedFeatureResult
    data object SessionInvalid : AuthenticatedFeatureResult
}

class AuthenticatedFeatureClient(
    private val repository: SessionRepository,
    private val refreshExchange: SessionRefreshExchange,
    private val transport: FeatureHttpTransport,
    private val headerProvider: InMemoryAuthHeaderProvider,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val onSessionInvalid: suspend () -> Unit = {},
) {
    private val refreshMutex = Mutex()

    suspend fun execute(request: FeatureHttpRequest): AuthenticatedFeatureResult {
        val session = when (val result = sessionForRequest(forceRefreshOf = null)) {
            is SessionResolution.Ready -> result.session
            SessionResolution.RefreshUnavailable -> return AuthenticatedFeatureResult.RefreshUnavailable
            SessionResolution.Unavailable -> return AuthenticatedFeatureResult.SessionUnavailable
            SessionResolution.Invalid -> return AuthenticatedFeatureResult.SessionInvalid
        }
        val first = executeOnce(request, session.accessToken)
        if (first !is AuthenticatedFeatureResult.Response || first.response.statusCode != 401) {
            return first
        }
        val refreshed = when (val result = sessionForRequest(forceRefreshOf = session.accessToken)) {
            is SessionResolution.Ready -> result.session
            SessionResolution.RefreshUnavailable -> return AuthenticatedFeatureResult.RefreshUnavailable
            SessionResolution.Unavailable -> return AuthenticatedFeatureResult.SessionUnavailable
            SessionResolution.Invalid -> return AuthenticatedFeatureResult.SessionInvalid
        }
        val retry = executeOnce(request, refreshed.accessToken)
        if (retry is AuthenticatedFeatureResult.Response && retry.response.statusCode == 401) {
            invalidateSession()
            return AuthenticatedFeatureResult.SessionInvalid
        }
        return retry
    }

    private suspend fun executeOnce(
        request: FeatureHttpRequest,
        token: SensitiveToken,
    ): AuthenticatedFeatureResult = try {
        AuthenticatedFeatureResult.Response(transport.execute(request, token))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: IOException) {
        AuthenticatedFeatureResult.NetworkFailure
    }

    private suspend fun sessionForRequest(forceRefreshOf: SensitiveToken?): SessionResolution =
        refreshMutex.withLock {
            val loaded = try {
                repository.load()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                SessionLoadResult.Failure
            }
            val current = when (loaded) {
                SessionLoadResult.Empty, SessionLoadResult.Corrupt -> {
                    return@withLock invalidateSessionAndReturn()
                }
                SessionLoadResult.Failure -> {
                    headerProvider.clear()
                    return@withLock SessionResolution.Unavailable
                }
                is SessionLoadResult.Success -> loaded.session
            }
            val mustRefresh = if (forceRefreshOf != null) {
                current.accessToken == forceRefreshOf
            } else {
                current.expiresAtEpochMillis <= nowEpochMillis() + SessionRefreshWindowMillis
            }
            if (!mustRefresh) {
                headerProvider.update(current)
                return@withLock SessionResolution.Ready(current)
            }
            val refreshToken = current.refreshToken
            if (refreshToken == null) {
                return@withLock if (
                    forceRefreshOf == null && current.expiresAtEpochMillis > nowEpochMillis()
                ) {
                    headerProvider.update(current)
                    SessionResolution.Ready(current)
                } else {
                    invalidateSessionAndReturn()
                }
            }
            when (val refreshed = refreshExchange.refresh(refreshToken)) {
                SessionRefreshResult.InvalidSession -> invalidateSessionAndReturn()
                SessionRefreshResult.Failure -> SessionResolution.RefreshUnavailable
                is SessionRefreshResult.Success -> {
                    val rotated = refreshed.session.copy(accountId = current.accountId)
                    if (repository.save(rotated) != SessionMutationResult.Success) {
                        invalidateSessionAndReturn()
                    } else {
                        headerProvider.update(rotated)
                        SessionResolution.Ready(rotated)
                    }
                }
            }
        }

    private suspend fun invalidateSessionAndReturn(): SessionResolution {
        invalidateSession()
        return SessionResolution.Invalid
    }

    private suspend fun invalidateSession() {
        try {
            repository.clear()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Header invalidation remains fail-closed even if encrypted storage is unavailable.
        }
        headerProvider.clear()
        onSessionInvalid()
    }

    private sealed interface SessionResolution {
        data class Ready(val session: Session) : SessionResolution
        data object RefreshUnavailable : SessionResolution
        data object Unavailable : SessionResolution
        data object Invalid : SessionResolution
    }

    override fun toString(): String = "AuthenticatedFeatureClient(<redacted>)"
}
