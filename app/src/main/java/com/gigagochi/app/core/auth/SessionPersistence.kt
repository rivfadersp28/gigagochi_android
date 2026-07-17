package com.gigagochi.app.core.auth

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.gigagochi.app.core.model.SensitiveToken
import com.gigagochi.app.core.model.Session
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val SessionEnvelopeVersion = 2
private const val SessionPayloadMagic = 0x47475332
private const val SessionAccountIdMaxBytes = 255
private const val SessionTokenMaxBytes = 16 * 1024
private const val SessionIvMaxBytes = 64
private const val SessionCiphertextMaxBytes = 64 * 1024
private const val SessionPreferencesName = "gigagochi_secure_session"
private const val SessionKeyAlias = "gigagochi.session.aes.v2"
private val SessionAssociatedData = "gigagochi-session-envelope-v2".toByteArray()

data class EncryptedSessionEnvelope(
    val version: Int,
    val iv: ByteArray,
    val ciphertext: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is EncryptedSessionEnvelope &&
            version == other.version &&
            iv.contentEquals(other.iv) &&
            ciphertext.contentEquals(other.ciphertext)

    override fun hashCode(): Int =
        31 * (31 * version + iv.contentHashCode()) + ciphertext.contentHashCode()

    override fun toString(): String = "EncryptedSessionEnvelope(version=$version, <redacted>)"
}

interface SessionCipher {
    fun encrypt(plaintext: ByteArray): EncryptedSessionEnvelope
    fun decrypt(envelope: EncryptedSessionEnvelope): ByteArray
}

interface EncryptedSessionStorage {
    fun read(): EncryptedSessionEnvelope?
    fun write(envelope: EncryptedSessionEnvelope): Boolean
    fun clear(): Boolean
}

sealed interface SessionLoadResult {
    data object Empty : SessionLoadResult
    data class Success(val session: Session) : SessionLoadResult
    data object Corrupt : SessionLoadResult
    data object Failure : SessionLoadResult
}

sealed interface SessionMutationResult {
    data object Success : SessionMutationResult
    data object Failure : SessionMutationResult
}

interface SessionRepository {
    suspend fun load(): SessionLoadResult
    suspend fun save(session: Session): SessionMutationResult
    suspend fun clear(): SessionMutationResult
}

class EncryptedSessionRepository(
    private val cipher: SessionCipher,
    private val storage: EncryptedSessionStorage,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SessionRepository {
    override suspend fun load(): SessionLoadResult = withContext(dispatcher) {
        val envelope = try {
            storage.read()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SessionStorageCorruptException) {
            return@withContext SessionLoadResult.Corrupt
        } catch (_: Exception) {
            return@withContext SessionLoadResult.Failure
        } ?: return@withContext SessionLoadResult.Empty

        try {
            val plaintext = cipher.decrypt(envelope)
            try {
                SessionLoadResult.Success(SessionPayloadCodec.decode(plaintext))
            } finally {
                plaintext.fill(0)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            SessionLoadResult.Corrupt
        }
    }

    override suspend fun save(session: Session): SessionMutationResult = withContext(dispatcher) {
        val plaintext = try {
            SessionPayloadCodec.encode(session)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return@withContext SessionMutationResult.Failure
        }
        try {
            val envelope = cipher.encrypt(plaintext)
            if (storage.write(envelope)) {
                SessionMutationResult.Success
            } else {
                SessionMutationResult.Failure
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            SessionMutationResult.Failure
        } finally {
            plaintext.fill(0)
        }
    }

    override suspend fun clear(): SessionMutationResult = withContext(dispatcher) {
        try {
            if (storage.clear()) SessionMutationResult.Success else SessionMutationResult.Failure
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            SessionMutationResult.Failure
        }
    }
}

class AndroidKeystoreSessionCipher(
    private val keyAlias: String = SessionKeyAlias,
) : SessionCipher {
    override fun encrypt(plaintext: ByteArray): EncryptedSessionEnvelope {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(SessionAssociatedData)
        return EncryptedSessionEnvelope(
            version = SessionEnvelopeVersion,
            iv = cipher.iv.copyOf(),
            ciphertext = cipher.doFinal(plaintext),
        )
    }

    override fun decrypt(envelope: EncryptedSessionEnvelope): ByteArray {
        require(envelope.version == SessionEnvelopeVersion)
        require(envelope.iv.size in 12..SessionIvMaxBytes)
        require(envelope.ciphertext.size in 16..SessionCiphertextMaxBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(),
            GCMParameterSpec(128, envelope.iv),
        )
        cipher.updateAAD(SessionAssociatedData)
        return cipher.doFinal(envelope.ciphertext)
    }

    private fun key(): SecretKey = synchronized(AndroidKeystoreSessionCipher::class.java) {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey) ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore",
        ).run {
            init(
                KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }
}

class SharedPreferencesSessionStorage(
    private val preferences: SharedPreferences,
) : EncryptedSessionStorage {
    override fun read(): EncryptedSessionEnvelope? {
        val hasAny = listOf(VersionKey, IvKey, CiphertextKey).any(preferences::contains)
        if (!hasAny) return null
        if (!listOf(VersionKey, IvKey, CiphertextKey).all(preferences::contains)) {
            throw SessionStorageCorruptException()
        }
        return try {
            EncryptedSessionEnvelope(
                version = preferences.getInt(VersionKey, -1),
                iv = Base64.decode(preferences.getString(IvKey, null), Base64.NO_WRAP),
                ciphertext = Base64.decode(
                    preferences.getString(CiphertextKey, null),
                    Base64.NO_WRAP,
                ),
            )
        } catch (_: Exception) {
            throw SessionStorageCorruptException()
        }
    }

    override fun write(envelope: EncryptedSessionEnvelope): Boolean = preferences.edit()
        .clear()
        .putInt(VersionKey, envelope.version)
        .putString(IvKey, Base64.encodeToString(envelope.iv, Base64.NO_WRAP))
        .putString(CiphertextKey, Base64.encodeToString(envelope.ciphertext, Base64.NO_WRAP))
        .commit()

    override fun clear(): Boolean = preferences.edit().clear().commit()

    private companion object {
        const val VersionKey = "version"
        const val IvKey = "iv"
        const val CiphertextKey = "ciphertext"
    }
}

class SessionStorageCorruptException : RuntimeException()

fun androidSessionRepository(context: Context): SessionRepository = EncryptedSessionRepository(
    cipher = AndroidKeystoreSessionCipher(),
    storage = SharedPreferencesSessionStorage(
        context.getSharedPreferences(SessionPreferencesName, Context.MODE_PRIVATE),
    ),
)

internal object SessionPayloadCodec {
    fun encode(session: Session): ByteArray {
        require(session.expiresAtEpochMillis > 0)
        val access = session.accessToken.reveal().toByteArray(StandardCharsets.UTF_8)
        val refresh = session.refreshToken?.reveal()?.toByteArray(StandardCharsets.UTF_8)
        val accountId = session.accountId.toByteArray(StandardCharsets.UTF_8)
        try {
            require(accountId.size in 1..SessionAccountIdMaxBytes)
            require(access.size in 1..SessionTokenMaxBytes)
            require(refresh == null || refresh.size in 1..SessionTokenMaxBytes)
            return ByteArrayOutputStream().use { bytes ->
                DataOutputStream(bytes).use { output ->
                    output.writeInt(SessionPayloadMagic)
                    output.writeInt(SessionEnvelopeVersion)
                    output.writeLong(session.expiresAtEpochMillis)
                    output.writeSized(accountId)
                    output.writeSized(access)
                    output.writeBoolean(refresh != null)
                    if (refresh != null) output.writeSized(refresh)
                }
                bytes.toByteArray()
            }
        } finally {
            accountId.fill(0)
            access.fill(0)
            refresh?.fill(0)
        }
    }

    fun decode(bytes: ByteArray): Session = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        require(input.readInt() == SessionPayloadMagic)
        require(input.readInt() == SessionEnvelopeVersion)
        val expiresAt = input.readLong()
        require(expiresAt > 0)
        val accountId = input.readSized(SessionAccountIdMaxBytes)
        val access = input.readSized()
        val hasRefresh = input.readBoolean()
        val refresh = if (hasRefresh) input.readSized() else null
        require(input.available() == 0)
        try {
            Session(
                accountId = String(accountId, StandardCharsets.UTF_8),
                accessToken = SensitiveToken.of(String(access, StandardCharsets.UTF_8)),
                refreshToken = refresh?.let {
                    SensitiveToken.of(String(it, StandardCharsets.UTF_8))
                },
                expiresAtEpochMillis = expiresAt,
            ).also {
                require(it.accountId.isNotBlank() && it.accountId == it.accountId.trim())
                require(!it.accessToken.isBlank())
                require(it.refreshToken?.isBlank() != true)
            }
        } finally {
            accountId.fill(0)
            access.fill(0)
            refresh?.fill(0)
        }
    }

    private fun DataOutputStream.writeSized(bytes: ByteArray) {
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readSized(maxBytes: Int = SessionTokenMaxBytes): ByteArray {
        val size = readInt()
        require(size in 1..maxBytes)
        return ByteArray(size).also(::readFully)
    }
}
