package com.gigagochi.app.core.auth

import android.app.Activity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.gigagochi.app.core.model.SensitiveToken
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException

class SecureNonceGenerator(
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    fun generate(byteLength: Int = 32): String {
        require(byteLength >= 16)
        val bytes = ByteArray(byteLength)
        secureRandom.nextBytes(bytes)
        return encodeUrlSafeWithoutPadding(bytes)
    }

    private fun encodeUrlSafeWithoutPadding(bytes: ByteArray): String {
        val alphabet =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val output = StringBuilder((bytes.size * 4 + 2) / 3)
        var index = 0
        while (index + 2 < bytes.size) {
            val value =
                ((bytes[index].toInt() and 0xff) shl 16) or
                    ((bytes[index + 1].toInt() and 0xff) shl 8) or
                    (bytes[index + 2].toInt() and 0xff)
            output.append(alphabet[value ushr 18])
            output.append(alphabet[(value ushr 12) and 0x3f])
            output.append(alphabet[(value ushr 6) and 0x3f])
            output.append(alphabet[value and 0x3f])
            index += 3
        }
        when (bytes.size - index) {
            1 -> {
                val value = (bytes[index].toInt() and 0xff) shl 16
                output.append(alphabet[value ushr 18])
                output.append(alphabet[(value ushr 12) and 0x3f])
            }
            2 -> {
                val value =
                    ((bytes[index].toInt() and 0xff) shl 16) or
                        ((bytes[index + 1].toInt() and 0xff) shl 8)
                output.append(alphabet[value ushr 18])
                output.append(alphabet[(value ushr 12) and 0x3f])
                output.append(alphabet[(value ushr 6) and 0x3f])
            }
        }
        return output.toString()
    }
}

class CredentialManagerGoogleCredentialProvider(
    private val activity: Activity,
    private val credentialManager: CredentialManager = CredentialManager.create(activity),
    private val nonceGenerator: SecureNonceGenerator = SecureNonceGenerator(),
) : GoogleCredentialProvider {
    override suspend fun requestCredential(webClientId: String): GoogleCredentialResult {
        if (webClientId.isBlank()) {
            return GoogleCredentialResult.Failure(AuthFailure(AuthFailureKind.Configuration))
        }
        val nonce = nonceGenerator.generate()
        val option = GetSignInWithGoogleOption.Builder(
            serverClientId = webClientId,
        ).setNonce(nonce)
            .build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(option)
            .build()

        return try {
            val response = credentialManager.getCredential(
                context = activity,
                request = request,
            )
            val credential = response.credential
            if (
                credential !is CustomCredential ||
                credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                GoogleCredentialResult.Failure(AuthFailure(AuthFailureKind.CredentialParse))
            } else {
                val googleCredential = GoogleIdTokenCredential.createFrom(credential.data)
                val idToken = googleCredential.idToken
                if (idToken.isBlank()) {
                    GoogleCredentialResult.Failure(AuthFailure(AuthFailureKind.CredentialParse))
                } else {
                    GoogleCredentialResult.Success(
                        GoogleAuthCredential(
                            idToken = SensitiveToken.of(idToken),
                            nonce = nonce,
                        ),
                    )
                }
            }
        } catch (_: GetCredentialCancellationException) {
            GoogleCredentialResult.Failure(AuthFailure(AuthFailureKind.UserCancelled))
        } catch (_: NoCredentialException) {
            GoogleCredentialResult.Failure(AuthFailure(AuthFailureKind.NoCredential))
        } catch (_: GoogleIdTokenParsingException) {
            GoogleCredentialResult.Failure(AuthFailure(AuthFailureKind.CredentialParse))
        } catch (_: GetCredentialProviderConfigurationException) {
            GoogleCredentialResult.Failure(AuthFailure(AuthFailureKind.Configuration))
        } catch (_: GetCredentialUnsupportedException) {
            GoogleCredentialResult.Failure(AuthFailure(AuthFailureKind.Configuration))
        } catch (_: GetCredentialInterruptedException) {
            GoogleCredentialResult.Failure(AuthFailure(AuthFailureKind.Unknown))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            GoogleCredentialResult.Failure(AuthFailure(AuthFailureKind.Unknown))
        }
    }
}
