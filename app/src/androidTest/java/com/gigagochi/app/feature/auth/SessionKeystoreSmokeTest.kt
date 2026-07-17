package com.gigagochi.app.feature.auth

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.gigagochi.app.core.auth.AndroidKeystoreSessionCipher
import com.gigagochi.app.core.auth.EncryptedSessionRepository
import com.gigagochi.app.core.auth.SessionLoadResult
import com.gigagochi.app.core.auth.SessionMutationResult
import com.gigagochi.app.core.auth.SharedPreferencesSessionStorage
import com.gigagochi.app.core.model.SensitiveToken
import com.gigagochi.app.core.model.Session
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SessionKeystoreSmokeTest {
    @Test
    fun aesGcmRoundTripStoresOnlyCiphertextIvAndVersion() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences(
            "gigagochi_session_keystore_smoke",
            Context.MODE_PRIVATE,
        )
        preferences.edit().clear().commit()
        val repository = EncryptedSessionRepository(
            cipher = AndroidKeystoreSessionCipher("gigagochi.session.test.aes.v2"),
            storage = SharedPreferencesSessionStorage(preferences),
        )
        val session = Session(
            accountId = "acct_abcdefghijklmnopqrstuvwx",
            accessToken = SensitiveToken.of("instrumented-access-secret"),
            refreshToken = SensitiveToken.of("instrumented-refresh-secret"),
            expiresAtEpochMillis = 2_000_000_000_000,
        )

        assertEquals(SessionMutationResult.Success, repository.save(session))
        assertEquals(SessionLoadResult.Success(session), repository.load())
        assertEquals(setOf("version", "iv", "ciphertext"), preferences.all.keys)
        val storedText = preferences.all.values.joinToString()
        assertFalse(storedText.contains("instrumented-access-secret"))
        assertFalse(storedText.contains("instrumented-refresh-secret"))
        assertEquals(SessionMutationResult.Success, repository.clear())
        Unit
    }
}
