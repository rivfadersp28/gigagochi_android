package com.gigagochi.app.feature.auth

import com.gigagochi.app.core.auth.AuthFailure
import com.gigagochi.app.core.auth.AuthFailureKind
import com.gigagochi.app.core.auth.GoogleAuthConfiguration
import com.gigagochi.app.core.auth.GoogleAuthCredential
import com.gigagochi.app.core.auth.GoogleAuthRuntimeConfiguration
import com.gigagochi.app.core.auth.GoogleCredentialResult
import com.gigagochi.app.core.auth.MissingGoogleAuthConfiguration
import com.gigagochi.app.core.auth.SessionExchangeResult
import com.gigagochi.app.core.model.Session

sealed interface GoogleAuthState {
    data class MissingConfiguration(
        val reasons: Set<MissingGoogleAuthConfiguration>,
    ) : GoogleAuthState

    data class Ready(
        val configuration: GoogleAuthRuntimeConfiguration,
    ) : GoogleAuthState

    data class CredentialPending(
        val configuration: GoogleAuthRuntimeConfiguration,
        val requestKey: String,
    ) : GoogleAuthState

    data class ExchangePending(
        val configuration: GoogleAuthRuntimeConfiguration,
        val requestKey: String,
        val credential: GoogleAuthCredential,
    ) : GoogleAuthState

    data class PersistencePending(
        val configuration: GoogleAuthRuntimeConfiguration,
        val requestKey: String,
        val session: Session,
    ) : GoogleAuthState

    data class Error(
        val configuration: GoogleAuthRuntimeConfiguration,
        val failure: AuthFailure,
    ) : GoogleAuthState

    data class Authenticated(
        val session: Session,
    ) : GoogleAuthState
}

sealed interface GoogleAuthEvent {
    data class BeginSignIn(val requestKey: String) : GoogleAuthEvent
    data class CredentialFinished(
        val requestKey: String,
        val result: GoogleCredentialResult,
    ) : GoogleAuthEvent
    data class ExchangeFinished(
        val requestKey: String,
        val result: SessionExchangeResult,
    ) : GoogleAuthEvent
    data class PersistenceFinished(
        val requestKey: String,
        val result: com.gigagochi.app.core.auth.SessionMutationResult,
    ) : GoogleAuthEvent
    data class Retry(val requestKey: String) : GoogleAuthEvent
}

fun initialGoogleAuthState(configuration: GoogleAuthConfiguration): GoogleAuthState =
    when (configuration) {
        is GoogleAuthConfiguration.Missing -> GoogleAuthState.MissingConfiguration(
            reasons = configuration.reasons,
        )
        is GoogleAuthConfiguration.Ready -> GoogleAuthState.Ready(configuration.runtime)
    }

fun reduceGoogleAuth(
    state: GoogleAuthState,
    event: GoogleAuthEvent,
): GoogleAuthState = when (event) {
    is GoogleAuthEvent.BeginSignIn -> if (
        state is GoogleAuthState.Ready && event.requestKey.isNotBlank()
    ) {
        GoogleAuthState.CredentialPending(
            configuration = state.configuration,
            requestKey = event.requestKey,
        )
    } else {
        state
    }

    is GoogleAuthEvent.CredentialFinished -> if (
        state is GoogleAuthState.CredentialPending &&
        state.requestKey == event.requestKey
    ) {
        when (val result = event.result) {
            is GoogleCredentialResult.Failure -> if (
                result.failure.kind == AuthFailureKind.UserCancelled
            ) {
                GoogleAuthState.Ready(state.configuration)
            } else {
                GoogleAuthState.Error(
                    configuration = state.configuration,
                    failure = result.failure,
                )
            }
            is GoogleCredentialResult.Success -> if (
                result.credential.idToken.isBlank() || result.credential.nonce.isBlank()
            ) {
                GoogleAuthState.Error(
                    configuration = state.configuration,
                    failure = AuthFailure(AuthFailureKind.CredentialParse),
                )
            } else {
                GoogleAuthState.ExchangePending(
                    configuration = state.configuration,
                    requestKey = state.requestKey,
                    credential = result.credential,
                )
            }
        }
    } else {
        state
    }

    is GoogleAuthEvent.ExchangeFinished -> if (
        state is GoogleAuthState.ExchangePending &&
        state.requestKey == event.requestKey
    ) {
        when (val result = event.result) {
            is SessionExchangeResult.Failure -> GoogleAuthState.Error(
                configuration = state.configuration,
                failure = result.failure,
            )
            is SessionExchangeResult.Success -> GoogleAuthState.PersistencePending(
                configuration = state.configuration,
                requestKey = state.requestKey,
                session = result.session,
            )
        }
    } else {
        state
    }

    is GoogleAuthEvent.Retry -> if (
        state is GoogleAuthState.Error && event.requestKey.isNotBlank()
    ) {
        GoogleAuthState.CredentialPending(
            configuration = state.configuration,
            requestKey = event.requestKey,
        )
    } else {
        state
    }

    is GoogleAuthEvent.PersistenceFinished -> if (
        state is GoogleAuthState.PersistencePending && state.requestKey == event.requestKey
    ) {
        when (event.result) {
            com.gigagochi.app.core.auth.SessionMutationResult.Success ->
                GoogleAuthState.Authenticated(state.session)
            com.gigagochi.app.core.auth.SessionMutationResult.Failure -> GoogleAuthState.Error(
                configuration = state.configuration,
                failure = AuthFailure(AuthFailureKind.Storage),
            )
        }
    } else {
        state
    }
}
