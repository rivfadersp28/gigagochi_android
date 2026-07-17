package com.gigagochi.app.feature.auth

import com.gigagochi.app.core.auth.AuthFailure
import com.gigagochi.app.core.auth.AuthFailureKind
import com.gigagochi.app.core.auth.GoogleAuthConfiguration
import com.gigagochi.app.core.auth.GoogleAuthCredential
import com.gigagochi.app.core.auth.GoogleAuthRuntimeConfiguration
import com.gigagochi.app.core.auth.GoogleCredentialResult
import com.gigagochi.app.core.auth.MissingGoogleAuthConfiguration
import com.gigagochi.app.core.auth.SecureNonceGenerator
import com.gigagochi.app.core.auth.SessionExchangeResult
import com.gigagochi.app.core.auth.SessionMutationResult
import com.gigagochi.app.core.auth.googleAuthConfiguration
import com.gigagochi.app.core.model.SensitiveToken
import com.gigagochi.app.core.model.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleAuthContractTest {
    private val configuration = GoogleAuthRuntimeConfiguration(
        webClientId = "web-client.apps.googleusercontent.com",
        backendBaseUrl = "https://api.gigagochi.example/",
        allowDebugLoopbackHttp = false,
    )
    private val credential = GoogleAuthCredential(
        idToken = SensitiveToken.of("raw-google-id-token"),
        nonce = "abcdefghijklmnopqrstuvwxyz0123456789_-nonce",
    )
    private val session = Session(
        accountId = "acct_abcdefghijklmnopqrstuvwx",
        accessToken = SensitiveToken.of("raw-access-token"),
        refreshToken = SensitiveToken.of("raw-refresh-token"),
        expiresAtEpochMillis = 2_000_000_000_000,
    )

    @Test
    fun configurationRequiresBothWebClientAndUsableBackend() {
        val missing = googleAuthConfiguration(
            webClientId = "",
            backendBaseUrl = "https://api.example.invalid/",
            allowDebugLoopbackHttp = false,
        ) as GoogleAuthConfiguration.Missing
        assertEquals(
            setOf(
                MissingGoogleAuthConfiguration.WebClientId,
                MissingGoogleAuthConfiguration.BackendBaseUrl,
            ),
            missing.reasons,
        )

        val ready = googleAuthConfiguration(
            webClientId = configuration.webClientId,
            backendBaseUrl = configuration.backendBaseUrl,
            allowDebugLoopbackHttp = false,
        )
        assertTrue(ready is GoogleAuthConfiguration.Ready)
    }

    @Test
    fun reducerRejectsDoubleSubmitAndStaleCredentialResult() {
        val ready = GoogleAuthState.Ready(configuration)
        val pending = reduceGoogleAuth(ready, GoogleAuthEvent.BeginSignIn("request-1"))
        assertTrue(pending is GoogleAuthState.CredentialPending)
        assertSame(
            pending,
            reduceGoogleAuth(pending, GoogleAuthEvent.BeginSignIn("request-2")),
        )
        assertSame(
            pending,
            reduceGoogleAuth(
                pending,
                GoogleAuthEvent.CredentialFinished(
                    "request-old",
                    GoogleCredentialResult.Success(credential),
                ),
            ),
        )
    }

    @Test
    fun cancellationReturnsToReadyWhileNoCredentialRemainsRetryableError() {
        val pending = reduceGoogleAuth(
            GoogleAuthState.Ready(configuration),
            GoogleAuthEvent.BeginSignIn("request-1"),
        )
        val cancelled = reduceGoogleAuth(
            pending,
            GoogleAuthEvent.CredentialFinished(
                "request-1",
                GoogleCredentialResult.Failure(AuthFailure(AuthFailureKind.UserCancelled)),
            ),
        )
        assertEquals(GoogleAuthState.Ready(configuration), cancelled)

        val error = reduceGoogleAuth(
            pending,
            GoogleAuthEvent.CredentialFinished(
                "request-1",
                GoogleCredentialResult.Failure(AuthFailure(AuthFailureKind.NoCredential)),
            ),
        ) as GoogleAuthState.Error
        assertEquals(AuthFailureKind.NoCredential, error.failure.kind)

        val retried = reduceGoogleAuth(error, GoogleAuthEvent.Retry("request-2"))
        assertEquals("request-2", (retried as GoogleAuthState.CredentialPending).requestKey)
    }

    @Test
    fun exchangeRequiresSuccessfulPersistenceBeforeAuthentication() {
        val credentialPending = reduceGoogleAuth(
            GoogleAuthState.Ready(configuration),
            GoogleAuthEvent.BeginSignIn("request-1"),
        )
        val exchangePending = reduceGoogleAuth(
            credentialPending,
            GoogleAuthEvent.CredentialFinished(
                "request-1",
                GoogleCredentialResult.Success(credential),
            ),
        )
        assertTrue(exchangePending is GoogleAuthState.ExchangePending)
        assertSame(
            exchangePending,
            reduceGoogleAuth(
                exchangePending,
                GoogleAuthEvent.ExchangeFinished(
                    "request-old",
                    SessionExchangeResult.Success(session),
                ),
            ),
        )

        val persistencePending = reduceGoogleAuth(
            exchangePending,
            GoogleAuthEvent.ExchangeFinished(
                "request-1",
                SessionExchangeResult.Success(session),
            ),
        )
        assertEquals(
            session,
            (persistencePending as GoogleAuthState.PersistencePending).session,
        )
        assertSame(
            persistencePending,
            reduceGoogleAuth(
                persistencePending,
                GoogleAuthEvent.PersistenceFinished(
                    "request-old",
                    SessionMutationResult.Success,
                ),
            ),
        )

        val authenticated = reduceGoogleAuth(
            persistencePending,
            GoogleAuthEvent.PersistenceFinished("request-1", SessionMutationResult.Success),
        )
        assertEquals(session, (authenticated as GoogleAuthState.Authenticated).session)

        val storageError = reduceGoogleAuth(
            persistencePending,
            GoogleAuthEvent.PersistenceFinished("request-1", SessionMutationResult.Failure),
        ) as GoogleAuthState.Error
        assertEquals(AuthFailureKind.Storage, storageError.failure.kind)
    }

    @Test
    fun nonceIsUniqueNonemptyAndUrlSafeWithoutPadding() {
        val generator = SecureNonceGenerator()
        val first = generator.generate()
        val second = generator.generate()

        assertEquals(43, first.length)
        assertFalse(first == second)
        assertTrue(first.matches(Regex("^[A-Za-z0-9_-]+$")))
        assertTrue(second.matches(Regex("^[A-Za-z0-9_-]+$")))
    }

    @Test
    fun tokenContainersNeverExposeRawValuesThroughToString() {
        val credentialText = credential.toString()
        val sessionText = session.toString()

        assertFalse(credentialText.contains("raw-google-id-token"))
        assertFalse(sessionText.contains("raw-access-token"))
        assertFalse(sessionText.contains("raw-refresh-token"))
        assertTrue(credentialText.contains("<redacted>"))
        assertTrue(sessionText.contains("<redacted>"))
    }

    @Test
    fun safeInsetsMoveOnlyForegroundAndKeepReviewGeometryAtZero() {
        assertEquals(
            AuthForegroundGeometry(wordmarkY = 72f, panelY = 528f),
            authForegroundGeometry(safeTop = 0f, safeBottom = 0f),
        )
        assertEquals(
            AuthForegroundGeometry(wordmarkY = 96f, panelY = 504f),
            authForegroundGeometry(safeTop = 80f, safeBottom = 48f),
        )
    }
}
