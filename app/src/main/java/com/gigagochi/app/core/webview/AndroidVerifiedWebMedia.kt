@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.gigagochi.app.core.webview

import android.content.Context
import android.net.Uri
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import com.gigagochi.app.core.network.SecureStaticMediaDataSource
import com.gigagochi.app.core.network.StaticMediaUrlPolicy
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.security.MessageDigest

internal class AndroidVerifiedWebMediaDownloader(
    private val urlPolicy: StaticMediaUrlPolicy,
) : WebMediaDownloadOpener {
    override fun open(sourceUrl: String, maxResourceBytes: Long): WebMediaDownload {
        val source = SecureStaticMediaDataSource(urlPolicy, maxResourceBytes)
        return try {
            val length = source.open(DataSpec(Uri.parse(sourceUrl)))
            object : WebMediaDownload {
                override val declaredMimeType: String? = source.responseHeader("Content-Type")
                override val declaredLength: Long? = length.takeUnless {
                    it == C.LENGTH_UNSET.toLong()
                }

                override fun read(buffer: ByteArray): Int = source.read(buffer, 0, buffer.size)

                override fun close() = source.close()
            }
        } catch (failure: Throwable) {
            source.close()
            throw failure
        }
    }
}

/** API-21 Linux file primitives used instead of API-26 `java.nio.file` on minSdk 23 devices. */
internal object AndroidVerifiedMediaFileOps : VerifiedMediaFileOps {
    override fun atomicMove(partial: File, destination: File) {
        requireSameDirectory(partial, destination)
        errnoAsIo("Could not atomically publish verified media") {
            Os.rename(partial.path, destination.path)
        }
    }

    override fun copyVerified(
        source: File,
        destination: File,
        expectedByteLength: Long,
        expectedSha256: String,
    ) {
        require(expectedByteLength > 0L) { "Verified media must not be empty" }
        require(Sha256Pattern.matches(expectedSha256)) { "Invalid verified media digest" }

        var sourceDescriptor: FileDescriptor? = null
        var destinationDescriptor: FileDescriptor? = null
        var destinationCreated = false
        var copyCompleted = false
        try {
            val openedSource = Os.open(
                source.path,
                OsConstants.O_RDONLY,
                0,
            )
            sourceDescriptor = openedSource
            val openedDestination = Os.open(
                destination.path,
                OsConstants.O_WRONLY or
                    OsConstants.O_CREAT or
                    OsConstants.O_EXCL,
                OsConstants.S_IRUSR or OsConstants.S_IWUSR,
            )
            destinationDescriptor = openedDestination
            destinationCreated = true

            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(CopyBufferBytes)
            var copied = 0L
            while (true) {
                val read = Os.read(openedSource, buffer, 0, buffer.size)
                if (read == 0) break
                if (read < 0 || read.toLong() > expectedByteLength - copied) {
                    throw IOException("Verified media changed while creating handoff")
                }
                digest.update(buffer, 0, read)
                writeFully(openedDestination, buffer, read)
                copied += read
            }
            if (copied != expectedByteLength || digest.digest().toHex() != expectedSha256) {
                throw IOException("Verified media changed while creating handoff")
            }
            Os.fsync(openedDestination)
            copyCompleted = true
        } catch (failure: ErrnoException) {
            throw IOException("Could not copy verified media handoff", failure)
        } finally {
            destinationDescriptor?.let { opened -> runCatching { Os.close(opened) } }
            sourceDescriptor?.let { opened -> runCatching { Os.close(opened) } }
            if (destinationCreated && !copyCompleted) destination.delete()
        }
    }

    override fun syncDirectory(directory: File) {
        var descriptor: FileDescriptor? = null
        try {
            descriptor = Os.open(
                directory.path,
                OsConstants.O_RDONLY,
                0,
            )
            Os.fsync(descriptor)
        } catch (failure: ErrnoException) {
            throw IOException("Could not sync verified media directory", failure)
        } finally {
            descriptor?.let { opened -> runCatching { Os.close(opened) } }
        }
    }

    private fun requireSameDirectory(source: File, destination: File) {
        require(source.parentFile?.canonicalFile == destination.parentFile?.canonicalFile) {
            "Atomic media publication must remain on one filesystem"
        }
    }

    private inline fun errnoAsIo(message: String, operation: () -> Unit) {
        try {
            operation()
        } catch (failure: ErrnoException) {
            throw IOException(message, failure)
        }
    }

    private fun writeFully(descriptor: FileDescriptor, buffer: ByteArray, byteCount: Int) {
        var offset = 0
        while (offset < byteCount) {
            val written = Os.write(descriptor, buffer, offset, byteCount - offset)
            if (written <= 0) throw IOException("Could not copy verified media handoff")
            offset += written
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

    private const val CopyBufferBytes = 32 * 1024
    private val Sha256Pattern = Regex("^[a-f0-9]{64}$")
}

internal object VerifiedWebMediaCache {
    @Volatile
    private var instance: VerifiedWebMediaStore? = null

    @Synchronized
    fun store(context: Context, urlPolicy: StaticMediaUrlPolicy): VerifiedWebMediaStore =
        instance ?: VerifiedWebMediaStore(
            rootDirectory = File(context.applicationContext.cacheDir, VerifiedMediaDirectory),
            downloadOpener = AndroidVerifiedWebMediaDownloader(urlPolicy),
            fileOps = AndroidVerifiedMediaFileOps,
        ).also { instance = it }

    private const val VerifiedMediaDirectory = "verified-web-media"
}
