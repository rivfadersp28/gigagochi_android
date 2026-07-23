package com.gigagochi.app.core.auth

import android.content.Context
import com.gigagochi.app.core.model.Session
import java.io.IOException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val InstallationPreferencesName = "gigagochi_local_installation"
private const val InstallationIdKey = "installation_id"
private val CanonicalInstallationId = Regex(
    "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}",
)

fun interface InstallationIdProvider {
    suspend fun getOrCreate(): String?
}

class AndroidInstallationIdProvider(context: Context) : InstallationIdProvider {
    private val preferences = context.applicationContext.getSharedPreferences(
        InstallationPreferencesName,
        Context.MODE_PRIVATE,
    )

    override suspend fun getOrCreate(): String? = withContext(Dispatchers.IO) {
        synchronized(InstallationIdLock) {
            preferences.getString(InstallationIdKey, null)
                ?.takeIf(CanonicalInstallationId::matches)
                ?.let { return@synchronized it }
            val generated = UUID.randomUUID().toString()
            generated.takeIf {
                preferences.edit().putString(InstallationIdKey, generated).commit()
            }
        }
    }

    private companion object {
        val InstallationIdLock = Any()
    }
}

class GuestSessionEndpoint private constructor(val url: String) {
    companion object {
        fun fromBaseUrl(
            baseUrl: String,
            allowDebugLoopbackHttp: Boolean,
        ): GuestSessionEndpoint? = runCatching {
            val endpoint = AuthApiEndpoint.fromBaseUrl(
                baseUrl,
                allowDebugLoopbackHttp,
                endpointName = "guest",
            ) ?: return null
            GuestSessionEndpoint(URI(endpoint.url).toASCIIString())
        }.getOrNull()
    }
}

sealed interface GuestSessionExchangeResult {
    data class Success(val session: Session) : GuestSessionExchangeResult
    data class Failure(val kind: AuthFailureKind) : GuestSessionExchangeResult
}

fun interface GuestSessionExchange {
    suspend fun exchange(installationId: String): GuestSessionExchangeResult
}

class HttpGuestSessionExchange(
    baseUrl: String,
    allowDebugLoopbackHttp: Boolean,
    private val transport: AuthHttpTransport = UrlConnectionAuthHttpTransport(),
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) : GuestSessionExchange {
    private val endpoint = GuestSessionEndpoint.fromBaseUrl(baseUrl, allowDebugLoopbackHttp)

    override suspend fun exchange(installationId: String): GuestSessionExchangeResult {
        val resolvedEndpoint = endpoint
            ?: return GuestSessionExchangeResult.Failure(AuthFailureKind.Configuration)
        if (!CanonicalInstallationId.matches(installationId)) {
            return GuestSessionExchangeResult.Failure(AuthFailureKind.BadRequest)
        }
        val body = "{\"installationId\":\"$installationId\"}"
            .toByteArray(StandardCharsets.UTF_8)
        val response = try {
            transport.execute(AuthHttpRequest(resolvedEndpoint.url, body))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            return GuestSessionExchangeResult.Failure(AuthFailureKind.Network)
        } catch (_: Exception) {
            return GuestSessionExchangeResult.Failure(AuthFailureKind.Network)
        }
        return when (response.statusCode) {
            in 200..299 -> StrictSessionJsonParser.parse(response.body)
                ?.takeIf {
                    it.accountId.isNotBlank() && it.expiresAtEpochMillis > nowEpochMillis()
                }
                ?.let(GuestSessionExchangeResult::Success)
                ?: GuestSessionExchangeResult.Failure(AuthFailureKind.Server)
            400 -> GuestSessionExchangeResult.Failure(AuthFailureKind.BadRequest)
            429 -> GuestSessionExchangeResult.Failure(AuthFailureKind.RateLimited)
            in 500..599 -> GuestSessionExchangeResult.Failure(AuthFailureKind.Server)
            else -> GuestSessionExchangeResult.Failure(AuthFailureKind.Server)
        }
    }
}

sealed interface LocalSessionBootstrapOutcome {
    data class Ready(val session: Session) : LocalSessionBootstrapOutcome
    data object Unavailable : LocalSessionBootstrapOutcome
}

class LocalSessionBootstrapCoordinator(
    private val sessionBootstrap: SessionBootstrapCoordinator,
    private val sessionRepository: SessionRepository,
    private val installationIdProvider: InstallationIdProvider,
    private val guestSessionExchange: GuestSessionExchange,
) {
    suspend fun bootstrap(): LocalSessionBootstrapOutcome {
        return when (val stored = sessionBootstrap.bootstrap()) {
            is SessionBootstrapOutcome.Authenticated -> LocalSessionBootstrapOutcome.Ready(
                stored.session,
            )
            is SessionBootstrapOutcome.Offline -> LocalSessionBootstrapOutcome.Ready(
                stored.session,
            )
            SessionBootstrapOutcome.Unauthenticated -> createGuestSession()
        }
    }

    private suspend fun createGuestSession(): LocalSessionBootstrapOutcome {
        val installationId = installationIdProvider.getOrCreate()
            ?: return LocalSessionBootstrapOutcome.Unavailable
        val exchanged = guestSessionExchange.exchange(installationId)
        if (exchanged !is GuestSessionExchangeResult.Success) {
            return LocalSessionBootstrapOutcome.Unavailable
        }
        return if (sessionRepository.save(exchanged.session) == SessionMutationResult.Success) {
            LocalSessionBootstrapOutcome.Ready(exchanged.session)
        } else {
            LocalSessionBootstrapOutcome.Unavailable
        }
    }
}
