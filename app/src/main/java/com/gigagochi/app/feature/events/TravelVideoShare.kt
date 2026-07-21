@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.gigagochi.app.feature.events

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.gigagochi.app.core.database.LocalTravelVideoAsset
import com.gigagochi.app.core.network.StaticMediaCache
import com.gigagochi.app.core.network.StaticMediaUrlPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

sealed interface TravelVideoShareResult {
    data object Opened : TravelVideoShareResult
    data object Failed : TravelVideoShareResult
}

fun interface TravelVideoSharer {
    suspend fun share(asset: LocalTravelVideoAsset): TravelVideoShareResult
}

class AndroidTravelVideoSharer(
    private val context: Context,
    private val mediaUrlPolicy: StaticMediaUrlPolicy,
) : TravelVideoSharer {
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
        val directory = File(context.cacheDir, ShareDirectory).apply { mkdirs() }
        directory.listFiles()
            ?.filter { System.currentTimeMillis() - it.lastModified() > ShareRetentionMillis }
            ?.forEach(File::delete)
        val stem = MessageDigest.getInstance("SHA-256")
            .digest(asset.requestKey.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(24)
        val destination = File(directory, "$stem.mp4")
        if (destination.isFile && destination.length() > 0L) return destination
        val partial = File(directory, "$stem.part")
        StaticMediaCache.copyToFile(
            context = context,
            url = asset.videoUrl,
            urlPolicy = mediaUrlPolicy,
            destination = partial,
        )
        check(partial.renameTo(destination))
        return destination
    }

    private companion object {
        const val ShareDirectory = "shared-travel-videos"
        const val ShareRetentionMillis = 24L * 60L * 60L * 1000L
    }
}
