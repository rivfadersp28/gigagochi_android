package com.gigagochi.app.feature.auth

import com.gigagochi.app.core.auth.EncryptedSessionEnvelope
import com.gigagochi.app.core.auth.EncryptedSessionRepository
import com.gigagochi.app.core.auth.EncryptedSessionStorage
import com.gigagochi.app.core.auth.SessionCipher
import com.gigagochi.app.core.auth.SessionLoadResult
import com.gigagochi.app.core.auth.SessionMutationResult
import com.gigagochi.app.core.model.SensitiveToken
import com.gigagochi.app.core.model.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionPersistenceTest {
    private val session = Session(
        accountId = "acct_abcdefghijklmnopqrstuvwx",
        accessToken = SensitiveToken.of("access-secret-value"),
        refreshToken = SensitiveToken.of("refresh-secret-value"),
        expiresAtEpochMillis = 2_000_000_000_000,
    )

    @Test
    fun encryptedRepositoryRoundTripsWithoutPlaintextAtRest() = runBlocking {
        val storage = FakeStorage()
        val repository = repository(storage)

        assertEquals(SessionMutationResult.Success, repository.save(session))
        assertEquals(SessionLoadResult.Success(session), repository.load())

        val envelope = requireNotNull(storage.envelope)
        val stored = envelope.iv + envelope.ciphertext
        assertFalse(stored.toString(Charsets.ISO_8859_1).contains("access-secret-value"))
        assertFalse(stored.toString(Charsets.ISO_8859_1).contains("refresh-secret-value"))
        assertFalse(envelope.toString().contains(envelope.ciphertext.contentToString()))
    }

    @Test
    fun corruptCiphertextIsReportedWithoutLeakingSecrets() = runBlocking {
        val storage = FakeStorage(
            envelope = EncryptedSessionEnvelope(2, byteArrayOf(1), byteArrayOf(2, 3)),
        )
        val repository = repository(storage)

        assertEquals(SessionLoadResult.Corrupt, repository.load())
        assertFalse(repository.load().toString().contains("secret"))
    }

    @Test
    fun preReleaseV1EnvelopeFailsClosed() = runBlocking {
        val storage = FakeStorage(
            envelope = EncryptedSessionEnvelope(1, ByteArray(12), ByteArray(32)),
        )

        assertEquals(SessionLoadResult.Corrupt, repository(storage).load())
    }

    @Test
    fun storageIoFailuresFailClosed() = runBlocking {
        val readFailure = FakeStorage().apply { failure = IllegalStateException("raw-secret") }
        assertEquals(SessionLoadResult.Failure, repository(readFailure).load())

        val writeFailure = FakeStorage(writeResult = false)
        assertEquals(SessionMutationResult.Failure, repository(writeFailure).save(session))

        val clearFailure = FakeStorage(clearResult = false)
        assertEquals(SessionMutationResult.Failure, repository(clearFailure).clear())
    }

    @Test(expected = CancellationException::class)
    fun cancellationIsNeverConvertedToStorageFailure() {
        runBlocking {
            val storage = FakeStorage().apply { failure = CancellationException("cancel") }
            repository(storage).load()
        }
    }

    private fun repository(storage: FakeStorage) = EncryptedSessionRepository(
        cipher = XorCipher(),
        storage = storage,
        dispatcher = Dispatchers.Unconfined,
    )

    private class XorCipher : SessionCipher {
        override fun encrypt(plaintext: ByteArray): EncryptedSessionEnvelope =
            EncryptedSessionEnvelope(
                version = 2,
                iv = ByteArray(12) { (it + 1).toByte() },
                ciphertext = plaintext.map { (it.toInt() xor 0x5A).toByte() }.toByteArray(),
            )

        override fun decrypt(envelope: EncryptedSessionEnvelope): ByteArray {
            require(envelope.version == 2 && envelope.iv.size == 12)
            return envelope.ciphertext.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        }
    }

    private class FakeStorage(
        var envelope: EncryptedSessionEnvelope? = null,
        private val writeResult: Boolean = true,
        private val clearResult: Boolean = true,
    ) : EncryptedSessionStorage {
        var failure: RuntimeException? = null

        override fun read(): EncryptedSessionEnvelope? {
            failure?.let { throw it }
            return envelope
        }

        override fun write(envelope: EncryptedSessionEnvelope): Boolean {
            failure?.let { throw it }
            if (writeResult) this.envelope = envelope
            return writeResult
        }

        override fun clear(): Boolean {
            failure?.let { throw it }
            if (clearResult) envelope = null
            return clearResult
        }
    }
}
