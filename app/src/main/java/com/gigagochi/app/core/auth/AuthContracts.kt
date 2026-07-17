package com.gigagochi.app.core.auth

import com.gigagochi.app.BuildConfig
import com.gigagochi.app.core.model.Session
import com.gigagochi.app.core.model.SensitiveToken

data class GoogleAuthCredential(
    val idToken: SensitiveToken,
    val nonce: String,
)

data class GoogleAuthRuntimeConfiguration(
    val webClientId: String,
    val backendBaseUrl: String,
    val allowDebugLoopbackHttp: Boolean,
)

enum class MissingGoogleAuthConfiguration {
    WebClientId,
    BackendBaseUrl,
}

sealed interface GoogleAuthConfiguration {
    data class Missing(
        val reasons: Set<MissingGoogleAuthConfiguration>,
    ) : GoogleAuthConfiguration

    data class Ready(
        val runtime: GoogleAuthRuntimeConfiguration,
    ) : GoogleAuthConfiguration
}

enum class AuthFailureKind {
    UserCancelled,
    NoCredential,
    CredentialParse,
    Configuration,
    Network,
    BadRequest,
    Unauthorized,
    Conflict,
    RateLimited,
    Server,
    Storage,
    Unknown,
}

data class AuthFailure(
    val kind: AuthFailureKind,
    val message: String = authFailureMessage(kind),
)

fun authFailureMessage(kind: AuthFailureKind): String = when (kind) {
    AuthFailureKind.UserCancelled -> "Вход отменён. Можно попробовать снова."
    AuthFailureKind.NoCredential -> "На устройстве не найден подходящий аккаунт Google."
    AuthFailureKind.CredentialParse ->
        "Google вернул неподдерживаемые данные. Попробуйте ещё раз."
    AuthFailureKind.Configuration -> "Проверьте настройки Google OAuth и адрес backend."
    AuthFailureKind.Network -> "Не удалось связаться с сервером. Проверьте подключение к интернету."
    AuthFailureKind.BadRequest -> "Сервер отклонил запрос входа."
    AuthFailureKind.Unauthorized -> "Не удалось подтвердить аккаунт Google."
    AuthFailureKind.Conflict -> "Этот Google-аккаунт уже связан с другим профилем."
    AuthFailureKind.RateLimited -> "Слишком много попыток. Попробуйте позже."
    AuthFailureKind.Server -> "Сервис входа временно недоступен. Попробуйте позже."
    AuthFailureKind.Storage -> "Не удалось безопасно сохранить вход. Попробуйте ещё раз."
    AuthFailureKind.Unknown -> "Не получилось войти. Попробуйте ещё раз."
}

sealed interface GoogleCredentialResult {
    data class Success(val credential: GoogleAuthCredential) : GoogleCredentialResult
    data class Failure(val failure: AuthFailure) : GoogleCredentialResult
}

sealed interface SessionExchangeResult {
    data class Success(val session: Session) : SessionExchangeResult
    data class Failure(val failure: AuthFailure) : SessionExchangeResult
}

interface GoogleCredentialProvider {
    suspend fun requestCredential(webClientId: String): GoogleCredentialResult
}

interface SessionExchange {
    suspend fun exchangeGoogleCredential(
        credential: GoogleAuthCredential,
    ): SessionExchangeResult
}

fun googleAuthConfiguration(
    webClientId: String = BuildConfig.GOOGLE_WEB_CLIENT_ID,
    backendBaseUrl: String = BuildConfig.BACKEND_BASE_URL,
    allowDebugLoopbackHttp: Boolean = BuildConfig.DEBUG,
): GoogleAuthConfiguration {
    val missing = buildSet {
        if (webClientId.isBlank()) add(MissingGoogleAuthConfiguration.WebClientId)
        if (
            backendBaseUrl.isBlank() ||
            backendBaseUrl == "https://api.example.invalid/" ||
            GoogleAuthEndpoint.fromBaseUrl(
                baseUrl = backendBaseUrl,
                allowDebugLoopbackHttp = allowDebugLoopbackHttp,
            ) == null
        ) {
            add(MissingGoogleAuthConfiguration.BackendBaseUrl)
        }
    }
    return if (missing.isEmpty()) {
        GoogleAuthConfiguration.Ready(
            GoogleAuthRuntimeConfiguration(
                webClientId = webClientId.trim(),
                backendBaseUrl = backendBaseUrl.trim(),
                allowDebugLoopbackHttp = allowDebugLoopbackHttp,
            ),
        )
    } else {
        GoogleAuthConfiguration.Missing(missing)
    }
}
