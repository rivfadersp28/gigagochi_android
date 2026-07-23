package com.gigagochi.app.core.webview

import com.gigagochi.app.core.network.StaticMediaDiskCacheBytes
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

internal data class VerifiedWebMediaAsset(
    val file: File,
    val mimeType: String,
    val byteLength: Long,
    val version: String,
)

internal interface WebMediaDownload : Closeable {
    val declaredMimeType: String?
    val declaredLength: Long?

    fun read(buffer: ByteArray): Int
}

internal fun interface WebMediaDownloadOpener {
    fun open(sourceUrl: String, maxResourceBytes: Long): WebMediaDownload
}

internal interface WebMediaMaterializer {
    fun materialize(sourceUrl: String, kind: WebMediaKind): VerifiedWebMediaAsset

    /**
     * Atomically acquires an already verified cache entry without hashing or reading its payload.
     * Implementations must return `null` rather than performing network or large-file work.
     */
    fun acquireCached(sourceUrl: String, kind: WebMediaKind): VerifiedWebMediaAsset? = null

    /** Acquires an eviction lease atomically with materialization when the implementation can. */
    fun acquire(sourceUrl: String, kind: WebMediaKind): VerifiedWebMediaAsset =
        materialize(sourceUrl, kind).also(::pin)

    fun pin(asset: VerifiedWebMediaAsset) = Unit

    fun release(asset: VerifiedWebMediaAsset) = Unit
}

internal interface VerifiedMediaFileOps {
    fun atomicMove(partial: File, destination: File)

    /**
     * Exclusively creates [destination], copies [source] while checking its expected identity, and
     * durably flushes the complete copy before returning. A failed copy must remove a destination
     * it created and must never overwrite a pre-existing file.
     */
    fun copyVerified(
        source: File,
        destination: File,
        expectedByteLength: Long,
        expectedSha256: String,
    )

    fun syncDirectory(directory: File)
}

private class SourceMaterializationLock(
    var users: Int = 0,
)

/**
 * A bounded cache of complete, content-verified media files.
 *
 * The only writable download target is a unique `.part` file. A file becomes addressable only
 * after its byte count, declared content type and actual container signature have all passed and
 * the same-filesystem atomic move has completed.
 */
internal class VerifiedWebMediaStore(
    private val rootDirectory: File,
    private val downloadOpener: WebMediaDownloadOpener,
    private val maxCacheBytes: Long = StaticMediaDiskCacheBytes,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val fileOps: VerifiedMediaFileOps = PortableVerifiedMediaFileOps,
) : WebMediaMaterializer {
    private val stateLock = Any()
    private val pinCounts = mutableMapOf<String, Int>()
    private val sourceLocks = mutableMapOf<String, SourceMaterializationLock>()

    init {
        require(maxCacheBytes > 0L)
        check(rootDirectory.mkdirs() || rootDirectory.isDirectory) {
            "Could not create verified media cache"
        }
        cleanupPartFiles()
    }

    override fun materialize(sourceUrl: String, kind: WebMediaKind): VerifiedWebMediaAsset =
        materializeInternal(sourceUrl, kind, acquireLease = false)

    override fun acquire(sourceUrl: String, kind: WebMediaKind): VerifiedWebMediaAsset =
        materializeInternal(sourceUrl, kind, acquireLease = true)

    override fun acquireCached(
        sourceUrl: String,
        kind: WebMediaKind,
    ): VerifiedWebMediaAsset? {
        val destination = File(rootDirectory, "${cacheKey(sourceUrl, kind)}.media")
        return synchronized(stateLock) {
            verifiedMetadataAssetOrNull(destination, kind)?.also { asset ->
                touch(destination)
                pinLocked(asset)
            }
        }
    }

    override fun pin(asset: VerifiedWebMediaAsset) {
        synchronized(stateLock) { pinLocked(asset) }
    }

    override fun release(asset: VerifiedWebMediaAsset) {
        synchronized(stateLock) { releaseLocked(asset) }
    }

    private fun materializeInternal(
        sourceUrl: String,
        kind: WebMediaKind,
        acquireLease: Boolean,
    ): VerifiedWebMediaAsset {
        val key = cacheKey(sourceUrl, kind)
        val destination = File(rootDirectory, "$key.media")
        cachedAsset(destination, kind, acquireLease)?.let { return it }

        val sourceLock = synchronized(stateLock) {
            sourceLocks.getOrPut(key, ::SourceMaterializationLock).also { lock ->
                lock.users = Math.addExact(lock.users, 1)
            }
        }
        try {
            return synchronized(sourceLock) {
                cachedAsset(destination, kind, acquireLease)?.let { return@synchronized it }
                recoverLegacyEntry(destination, kind, acquireLease)?.let {
                    return@synchronized it
                }
                downloadAndPublish(sourceUrl, kind, destination, acquireLease)
            }
        } finally {
            synchronized(stateLock) {
                sourceLock.users -= 1
                if (sourceLock.users == 0 && sourceLocks[key] === sourceLock) {
                    sourceLocks.remove(key)
                }
            }
        }
    }

    private fun cachedAsset(
        destination: File,
        kind: WebMediaKind,
        acquireLease: Boolean,
    ): VerifiedWebMediaAsset? = synchronized(stateLock) {
        verifiedMetadataAssetOrNull(destination, kind)?.also { asset ->
            touch(destination)
            if (acquireLease) pinLocked(asset)
        }
    }

    private fun recoverLegacyEntry(
        destination: File,
        kind: WebMediaKind,
        acquireLease: Boolean,
    ): VerifiedWebMediaAsset? {
        val path = canonicalPath(destination)
        val temporarilyPinned = synchronized(stateLock) {
            if (!destination.isFile) {
                metadataFile(destination).delete()
                false
            } else {
                pinPathLocked(path)
                true
            }
        }
        if (!temporarilyPinned) return null
        var temporaryLeaseHeld = true
        var keepAsAcquiredLease = false
        var metadataPartial: File? = null
        try {
            val verified = verifiedAssetOrNull(destination, kind)
            if (verified == null) {
                synchronized(stateLock) {
                    releasePathLocked(path)
                    temporaryLeaseHeld = false
                    if ((pinCounts[path] ?: 0) > 0) {
                        throw IOException("Cannot replace active invalid verified media")
                    }
                    deleteCacheEntry(destination)
                }
                return null
            }
            metadataPartial = prepareMetadataPartial(verified)
            synchronized(stateLock) {
                if (!destination.isFile || destination.length() != verified.byteLength) {
                    throw IOException("Legacy verified media changed during migration")
                }
                publishMetadataLocked(metadataPartial, metadataFile(destination))
                metadataPartial = null
                fileOps.syncDirectory(rootDirectory)
                touch(destination)
            }
            keepAsAcquiredLease = acquireLease
            return verified
        } finally {
            metadataPartial?.delete()
            if (temporaryLeaseHeld && !keepAsAcquiredLease) {
                synchronized(stateLock) {
                    releasePathLocked(path)
                }
            }
        }
    }

    private fun downloadAndPublish(
        sourceUrl: String,
        kind: WebMediaKind,
        destination: File,
        acquireLease: Boolean,
    ): VerifiedWebMediaAsset {
        val partial = File(
            rootDirectory,
            "${destination.nameWithoutExtension}-${UUID.randomUUID().toString().replace("-", "")}.part",
        )
        var metadataPartial: File? = null
        var destinationPublished = false
        try {
            val verified = downloadOpener.open(sourceUrl, kind.maxResourceBytes).use { download ->
                val declaredMime = normalizeMimeType(download.declaredMimeType)
                    ?.takeIf(kind::acceptsMimeType)
                    ?: throw IOException("Missing or unsupported media Content-Type")
                download.declaredLength?.let { declaredLength ->
                    if (declaredLength <= 0L || declaredLength > kind.maxResourceBytes) {
                        throw IOException("Invalid media Content-Length")
                    }
                }
                val copied = copyAndSync(download, partial, kind.maxResourceBytes)
                if (copied <= 0L || download.declaredLength?.let { it != copied } == true) {
                    throw IOException("Media response is empty or truncated")
                }
                val actualMime = detectMediaMimeType(partial)
                    ?.takeIf(kind::acceptsMimeType)
                    ?: throw IOException("Media container signature does not match its kind")
                if (actualMime != declaredMime) {
                    throw IOException("Declared and actual media MIME types differ")
                }
                VerifiedWebMediaAsset(
                    file = destination,
                    mimeType = actualMime,
                    byteLength = copied,
                    version = sha256(partial),
                )
            }
            metadataPartial = prepareMetadataPartial(verified)
            synchronized(stateLock) {
                try {
                    cachedAssetLocked(destination, kind, acquireLease)?.let { cached ->
                        partial.delete()
                        metadataPartial.delete()
                        return cached
                    }
                    if (destination.exists() || metadataFile(destination).exists()) {
                        if ((pinCounts[canonicalPath(destination)] ?: 0) > 0) {
                            throw IOException("Cannot replace active invalid verified media")
                        }
                        deleteCacheEntry(destination)
                    }
                    evictToFit(verified.byteLength, destination)
                    fileOps.atomicMove(partial, destination)
                    destinationPublished = true
                    publishMetadataLocked(metadataPartial, metadataFile(destination))
                    fileOps.syncDirectory(rootDirectory)
                    touch(destination)
                    if (acquireLease) pinLocked(verified)
                } catch (failure: Throwable) {
                    if (destinationPublished) {
                        deleteCacheEntry(destination)
                        destinationPublished = false
                    }
                    throw failure
                }
            }
            return verified
        } catch (failure: Throwable) {
            partial.delete()
            metadataPartial?.delete()
            if (destinationPublished) {
                synchronized(stateLock) { deleteCacheEntry(destination) }
            }
            throw failure
        }
    }

    private fun cachedAssetLocked(
        destination: File,
        kind: WebMediaKind,
        acquireLease: Boolean,
    ): VerifiedWebMediaAsset? = verifiedMetadataAssetOrNull(destination, kind)?.also { asset ->
        touch(destination)
        if (acquireLease) pinLocked(asset)
    }

    private fun pinLocked(asset: VerifiedWebMediaAsset) =
        pinPathLocked(canonicalPath(asset.file))

    private fun pinPathLocked(path: String) {
        pinCounts[path] = Math.addExact(pinCounts[path] ?: 0, 1)
    }

    private fun releaseLocked(asset: VerifiedWebMediaAsset) =
        releasePathLocked(canonicalPath(asset.file))

    private fun releasePathLocked(path: String) {
        val current = pinCounts[path] ?: return
        if (current <= 1) pinCounts.remove(path) else pinCounts[path] = current - 1
    }

    private fun verifiedAssetOrNull(file: File, kind: WebMediaKind): VerifiedWebMediaAsset? {
        if (!file.isFile || file.length() !in 1L..kind.maxResourceBytes) return null
        val actualMime = detectMediaMimeType(file)?.takeIf(kind::acceptsMimeType) ?: return null
        return verifiedAsset(file, actualMime)
    }

    private fun verifiedMetadataAssetOrNull(
        file: File,
        kind: WebMediaKind,
    ): VerifiedWebMediaAsset? {
        val metadata = metadataFile(file)
        if (!file.isFile || !metadata.isFile || metadata.length() !in 1L..MaxMetadataBytes) {
            return null
        }
        val fields = runCatching {
            metadata.readLines(Charsets.UTF_8)
        }.getOrNull() ?: return null
        if (fields.size != 4 || fields[0] != MetadataVersion) return null
        val mimeType = fields[1].takeIf(kind::acceptsMimeType) ?: return null
        val byteLength = fields[2].toLongOrNull()
            ?.takeIf { it in 1L..kind.maxResourceBytes }
            ?: return null
        val version = fields[3].takeIf(Sha256Hex::matches) ?: return null
        if (file.length() != byteLength) return null
        return VerifiedWebMediaAsset(file, mimeType, byteLength, version)
    }

    private fun prepareMetadataPartial(asset: VerifiedWebMediaAsset): File {
        val destination = metadataFile(asset.file)
        val partial = File(
            rootDirectory,
            "${destination.name}-${UUID.randomUUID().toString().replace("-", "")}.part",
        )
        try {
            FileOutputStream(partial).use { output ->
                output.write(
                    listOf(
                        MetadataVersion,
                        asset.mimeType,
                        asset.byteLength.toString(),
                        asset.version,
                    ).joinToString("\n").toByteArray(Charsets.UTF_8),
                )
                output.fd.sync()
            }
            return partial
        } catch (failure: Throwable) {
            partial.delete()
            throw failure
        }
    }

    private fun publishMetadataLocked(partial: File, destination: File) {
        if (destination.exists() && !destination.delete()) {
            throw IOException("Could not replace verified media metadata")
        }
        fileOps.atomicMove(partial, destination)
    }

    private fun verifiedAsset(file: File, mimeType: String): VerifiedWebMediaAsset =
        VerifiedWebMediaAsset(
            file = file,
            mimeType = mimeType,
            byteLength = file.length(),
            version = sha256(file),
        )

    private fun copyAndSync(
        download: WebMediaDownload,
        partial: File,
        maxResourceBytes: Long,
    ): Long {
        var total = 0L
        FileOutputStream(partial).use { output ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val read = download.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                if (read > buffer.size || total > maxResourceBytes - read) {
                    throw IOException("Media exceeds its byte limit")
                }
                output.write(buffer, 0, read)
                total += read
            }
            output.fd.sync()
        }
        return total
    }

    private fun evictToFit(incomingBytes: Long, destination: File) {
        require(incomingBytes in 1L..maxCacheBytes)
        val candidates = rootDirectory.listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension == "media" && it != destination }
            .sortedBy(File::lastModified)
        var occupied = rootDirectory.listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension == "media" && it != destination }
            .sumOf(File::length)
        for (candidate in candidates) {
            if (occupied <= maxCacheBytes - incomingBytes) break
            if ((pinCounts[canonicalPath(candidate)] ?: 0) > 0) continue
            val length = candidate.length()
            if (candidate.delete()) {
                metadataFile(candidate).delete()
                occupied -= length
            }
        }
        if (occupied > maxCacheBytes - incomingBytes) {
            throw IOException("Verified media cache is full of active assets")
        }
    }

    private fun cleanupPartFiles() {
        rootDirectory.listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension == "part" }
            .forEach(File::delete)
    }

    private fun touch(file: File) {
        if (!file.setLastModified(nowMillis())) {
            throw IOException("Could not update verified media LRU metadata")
        }
    }

    private fun canonicalPath(file: File): String = file.canonicalFile.path

    private fun metadataFile(media: File): File = File(media.parentFile, "${media.name}.meta")

    private fun deleteCacheEntry(media: File) {
        media.delete()
        metadataFile(media).delete()
    }

    private fun cacheKey(sourceUrl: String, kind: WebMediaKind): String = sha256(
        (kind.name + "\u0000" + sourceUrl).toByteArray(Charsets.UTF_8),
    )

    private companion object {
        const val MetadataVersion = "gigagochi-verified-media-v1"
        const val MaxMetadataBytes = 256L
        val Sha256Hex = Regex("^[a-f0-9]{64}$")
    }
}

private fun WebMediaKind.acceptsMimeType(mimeType: String): Boolean = when (this) {
    WebMediaKind.Video -> mimeType == "video/mp4"
    WebMediaKind.Image -> mimeType in SupportedImageMimeTypes
}

private fun normalizeMimeType(value: String?): String? = value
    ?.substringBefore(';')
    ?.trim()
    ?.lowercase(Locale.ROOT)
    ?.takeIf(String::isNotEmpty)
    ?.let { if (it == "image/jpg") "image/jpeg" else it }

private fun detectMediaMimeType(file: File): String? {
    val prefix = ByteArray(minOf(file.length(), 16L).toInt())
    FileInputStream(file).use { input ->
        var offset = 0
        while (offset < prefix.size) {
            val read = input.read(prefix, offset, prefix.size - offset)
            if (read < 0) break
            offset += read
        }
    }
    return when {
        isPng(file, prefix) -> "image/png"
        isJpeg(file, prefix) -> "image/jpeg"
        isWebP(file, prefix) -> "image/webp"
        isMp4(file) -> "video/mp4"
        else -> null
    }
}

private fun isPng(file: File, prefix: ByteArray): Boolean {
    if (prefix.size < PngSignature.size || !prefix.copyOf(PngSignature.size).contentEquals(PngSignature)) {
        return false
    }
    RandomAccessFile(file, "r").use { input ->
        var offset = PngSignature.size.toLong()
        while (offset <= input.length() - 12L) {
            input.seek(offset)
            val chunkLength = input.readInt().toLong() and 0xffff_ffffL
            val type = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
            val chunkEnd = offset + 12L + chunkLength
            if (chunkEnd < offset || chunkEnd > input.length()) return false
            if (type == "IEND") return chunkLength == 0L && chunkEnd == input.length()
            offset = chunkEnd
        }
    }
    return false
}

private fun isJpeg(file: File, prefix: ByteArray): Boolean {
    if (prefix.size < 3 || prefix[0] != 0xff.toByte() || prefix[1] != 0xd8.toByte() ||
        prefix[2] != 0xff.toByte() || file.length() < 4L
    ) {
        return false
    }
    RandomAccessFile(file, "r").use { input ->
        input.seek(input.length() - 2L)
        return input.readUnsignedByte() == 0xff && input.readUnsignedByte() == 0xd9
    }
}

private fun isWebP(file: File, prefix: ByteArray): Boolean {
    if (prefix.size < 16 || prefix.copyOfRange(0, 4).toString(Charsets.US_ASCII) != "RIFF" ||
        prefix.copyOfRange(8, 12).toString(Charsets.US_ASCII) != "WEBP"
    ) {
        return false
    }
    val declaredPayload = ByteBuffer.wrap(prefix, 4, 4)
        .order(ByteOrder.LITTLE_ENDIAN)
        .int
        .toLong() and 0xffff_ffffL
    if (declaredPayload + 8L != file.length()) return false
    return prefix.copyOfRange(12, 16).toString(Charsets.US_ASCII) in WebPChunkTypes
}

private fun isMp4(file: File): Boolean {
    if (file.length() < 16L) return false
    var sawFileType = false
    var sawMediaPayload = false
    RandomAccessFile(file, "r").use { input ->
        var offset = 0L
        while (offset <= input.length() - 8L) {
            input.seek(offset)
            val shortSize = input.readInt().toLong() and 0xffff_ffffL
            val type = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
            var headerSize = 8L
            val boxSize = when (shortSize) {
                0L -> input.length() - offset
                1L -> {
                    if (offset > input.length() - 16L) return false
                    headerSize = 16L
                    input.readLong().takeIf { it >= 16L } ?: return false
                }
                else -> shortSize
            }
            if (boxSize < headerSize || boxSize > input.length() - offset) return false
            if (type == "ftyp") sawFileType = true
            if (type == "moov" || type == "mdat") sawMediaPayload = true
            offset += boxSize
            if (offset == input.length()) return sawFileType && sawMediaPayload
        }
    }
    return false
}

/**
 * JVM/test fallback without API-26 `java.nio.file` references in the production DEX. Android
 * construction always injects [AndroidVerifiedMediaFileOps], which uses exclusive/atomic Linux
 * file primitives and fsync on every supported API (minSdk 23).
 */
private object PortableVerifiedMediaFileOps : VerifiedMediaFileOps {
    override fun atomicMove(partial: File, destination: File) {
        if (destination.exists() || !partial.renameTo(destination)) {
            throw IOException("Could not atomically publish verified media")
        }
    }

    override fun copyVerified(
        source: File,
        destination: File,
        expectedByteLength: Long,
        expectedSha256: String,
    ): Unit = throw IOException("Verified copies require platform media file operations")

    override fun syncDirectory(directory: File) = Unit
}

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).use { input ->
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().toHex()
}

private fun sha256(value: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(value).toHex()

private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

private val SupportedImageMimeTypes = setOf("image/png", "image/jpeg", "image/webp")
private val WebPChunkTypes = setOf("VP8 ", "VP8L", "VP8X")
private val PngSignature = byteArrayOf(
    0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
)
