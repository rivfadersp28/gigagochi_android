@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.gigagochi.app.feature.events

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.gigagochi.app.core.database.LocalTravelVideoAsset
import com.gigagochi.app.core.network.StaticMediaDiskCacheBytes
import com.gigagochi.app.core.webview.VerifiedWebMediaAsset
import com.gigagochi.app.core.webview.AndroidVerifiedMediaFileOps
import com.gigagochi.app.core.webview.VerifiedWebMediaStore
import com.gigagochi.app.core.webview.VerifiedMediaFileOps
import com.gigagochi.app.core.webview.WebMediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

sealed interface TravelVideoShareResult {
    data object Opened : TravelVideoShareResult
    data object Failed : TravelVideoShareResult
}

fun interface TravelVideoSharer {
    suspend fun share(asset: LocalTravelVideoAsset): TravelVideoShareResult
}

class AndroidTravelVideoSharer internal constructor(
    private val context: Context,
    private val verifiedMedia: VerifiedWebMediaStore,
    private val fileOps: VerifiedMediaFileOps = AndroidVerifiedMediaFileOps,
) : TravelVideoSharer {
    private val shareDirectory = File(context.cacheDir, ShareDirectory)

    init {
        runCatching {
            maintainVerifiedMediaShareHandoffs(shareDirectory, fileOps)
        }
    }

    override suspend fun share(asset: LocalTravelVideoAsset): TravelVideoShareResult = try {
        val video = withContext(Dispatchers.IO) { prepareShareFile(asset) }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            video,
        )
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("Видео путешествия", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(sendIntent, "Показать друзьям"))
        TravelVideoShareResult.Opened
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        TravelVideoShareResult.Failed
    }

    private fun prepareShareFile(asset: LocalTravelVideoAsset): File {
        val verified = verifiedMedia.acquire(asset.videoUrl, WebMediaKind.Video)
        return try {
            createVerifiedMediaShareHandoff(
                asset = verified,
                directory = shareDirectory,
                fileOps = fileOps,
            )
        } finally {
            verifiedMedia.release(verified)
        }
    }

    private companion object {
        const val ShareDirectory = "shared-travel-videos"
    }
}

/**
 * Copies the pinned verified asset to a private staging name, revalidates its length and digest,
 * then atomically publishes a stable FileProvider handoff. LRU may unlink the cache source after
 * the lease is released without creating a partial-file window for an external chooser.
 */
internal fun createVerifiedMediaShareHandoff(
    asset: VerifiedWebMediaAsset,
    directory: File,
    fileOps: VerifiedMediaFileOps,
    nowMillis: Long = System.currentTimeMillis(),
    maxPublishedBytes: Long = StaticMediaDiskCacheBytes,
    maxPublishedFiles: Int = MaxPublishedShareFiles,
): File = synchronized(ShareHandoffLock) {
    require(asset.file.isFile && asset.file.length() == asset.byteLength)
    require(Regex("^[a-f0-9]{64}$").matches(asset.version))
    require(nowMillis >= 0L)
    require(maxPublishedBytes > 0L)
    require(maxPublishedFiles > 0)
    check(directory.mkdirs() || directory.isDirectory)

    val destination = File(directory, "${asset.version}.mp4")
    val removedAbandonedOrExpired = cleanupAbandonedAndExpiredShareHandoffsLocked(
        directory = directory,
        nowMillis = nowMillis,
        protectedPublished = destination,
    )
    val evictedForCapacity = evictPublishedShareHandoffsToFitLocked(
        directory = directory,
        protectedPublished = destination,
        reservedBytes = asset.byteLength,
        reservedFiles = 1,
        maxPublishedBytes = maxPublishedBytes,
        maxPublishedFiles = maxPublishedFiles,
    )
    if (removedAbandonedOrExpired || evictedForCapacity) fileOps.syncDirectory(directory)

    val partial = File(
        directory,
        "${asset.version}-${UUID.randomUUID().toString().replace("-", "")}.part",
    )
    try {
        fileOps.copyVerified(
            source = asset.file,
            destination = partial,
            expectedByteLength = asset.byteLength,
            expectedSha256 = asset.version,
        )
        fileOps.atomicMove(partial, destination)
        // A conforming move consumes the random staging name. Keep cleanup defensive so a broken
        // or vendor-specific implementation cannot leave an addressable cache leak behind.
        if (partial.exists() && !partial.delete()) {
            throw IOException("Could not remove published share staging copy")
        }
        runCatching { destination.setLastModified(nowMillis) }
        fileOps.syncDirectory(directory)
        check(destination.isFile && destination.length() == asset.byteLength)
        destination
    } catch (failure: Throwable) {
        partial.delete()
        throw failure
    }
}

private fun maintainVerifiedMediaShareHandoffs(
    directory: File,
    fileOps: VerifiedMediaFileOps,
    nowMillis: Long = System.currentTimeMillis(),
    maxPublishedBytes: Long = StaticMediaDiskCacheBytes,
    maxPublishedFiles: Int = MaxPublishedShareFiles,
) = synchronized(ShareHandoffLock) {
    require(nowMillis >= 0L)
    require(maxPublishedBytes > 0L)
    require(maxPublishedFiles > 0)
    check(directory.mkdirs() || directory.isDirectory)
    val removedAbandonedOrExpired = cleanupAbandonedAndExpiredShareHandoffsLocked(
        directory = directory,
        nowMillis = nowMillis,
        protectedPublished = null,
    )
    val evictedForCapacity = evictPublishedShareHandoffsToFitLocked(
        directory = directory,
        protectedPublished = null,
        reservedBytes = 0L,
        reservedFiles = 0,
        maxPublishedBytes = maxPublishedBytes,
        maxPublishedFiles = maxPublishedFiles,
    )
    if (removedAbandonedOrExpired || evictedForCapacity) fileOps.syncDirectory(directory)
}

private fun cleanupAbandonedAndExpiredShareHandoffsLocked(
    directory: File,
    nowMillis: Long,
    protectedPublished: File?,
): Boolean {
    var removedAny = false
    directory.listFiles()
        .orEmpty()
        .filter { file ->
            file.isFile &&
                file.name != protectedPublished?.name &&
                (
                    file.extension == "part" ||
                        (
                            file.extension == "mp4" &&
                                nowMillis > file.lastModified() &&
                                nowMillis - file.lastModified() > ShareRetentionMillis
                        )
                )
        }
        .forEach { file ->
            if (file.delete()) {
                removedAny = true
            } else if (file.exists()) {
                throw IOException("Could not remove abandoned verified media share file")
            }
        }
    return removedAny
}

private fun evictPublishedShareHandoffsToFitLocked(
    directory: File,
    protectedPublished: File?,
    reservedBytes: Long,
    reservedFiles: Int,
    maxPublishedBytes: Long,
    maxPublishedFiles: Int,
): Boolean {
    val effectiveMaxBytes = maxOf(maxPublishedBytes, reservedBytes)
    val effectiveMaxFiles = maxOf(maxPublishedFiles, reservedFiles)
    val retained = directory.listFiles()
        .orEmpty()
        .filter { file ->
            file.isFile &&
                file.extension == "mp4" &&
                file.name != protectedPublished?.name
        }
        .toMutableList()
    val evictionOrder = retained
        .sortedWith(compareBy<File>({ it.lastModified() }, { it.name }))
        .iterator()
    var removedAny = false
    while (
        retained.size > effectiveMaxFiles - reservedFiles ||
        exceedsPublishedByteBudget(retained, reservedBytes, effectiveMaxBytes)
    ) {
        if (!evictionOrder.hasNext()) {
            throw IOException("Could not reserve verified media share capacity")
        }
        val oldest = evictionOrder.next()
        if (oldest.delete()) {
            retained.remove(oldest)
            removedAny = true
        } else if (!oldest.exists()) {
            retained.remove(oldest)
        }
    }
    return removedAny
}

private fun exceedsPublishedByteBudget(
    published: List<File>,
    reservedBytes: Long,
    maxPublishedBytes: Long,
): Boolean {
    var remainingBytes = maxPublishedBytes - reservedBytes
    published.forEach { file ->
        val length = file.length()
        if (length > remainingBytes) return true
        remainingBytes -= length
    }
    return false
}

/**
 * All directory mutation is serialized. Therefore every `.part` present when this lock is acquired
 * is abandoned, while the current operation's staging file cannot race maintenance or eviction.
 */
private val ShareHandoffLock = Any()

private const val MaxPublishedShareFiles = 8
private const val ShareRetentionMillis = 24L * 60L * 60L * 1000L
