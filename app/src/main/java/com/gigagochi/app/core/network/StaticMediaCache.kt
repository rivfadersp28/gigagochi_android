package com.gigagochi.app.core.network

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

internal const val StaticMediaDiskCacheBytes = 256L * 1024L * 1024L
internal const val StaticImageMemoryCacheKilobytes = 32 * 1024

@UnstableApi
object StaticMediaCache {
    @Volatile
    private var diskCache: SimpleCache? = null

    private val decodedImages = object : LruCache<String, Bitmap>(StaticImageMemoryCacheKilobytes) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.allocationByteCount / 1024).coerceAtLeast(1)
    }

    fun dataSourceFactory(
        context: Context,
        urlPolicy: StaticMediaUrlPolicy,
        maxResourceBytes: Long = StaticVideoMaxBytes,
    ): DataSource.Factory = cachedDataSourceFactory(
        context,
        SecureStaticMediaDataSource.Factory(urlPolicy, maxResourceBytes),
    )

    internal fun cachedDataSourceFactory(
        context: Context,
        upstreamFactory: DataSource.Factory,
    ): DataSource.Factory = CacheDataSource.Factory()
        .setCache(cache(context.applicationContext))
        .setUpstreamDataSourceFactory(upstreamFactory)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    fun decodedImage(url: String): Bitmap? = decodedImages.get(url)

    fun putDecodedImage(url: String, bitmap: Bitmap) {
        decodedImages.put(url, bitmap)
    }

    fun readBytes(
        context: Context,
        url: String,
        urlPolicy: StaticMediaUrlPolicy,
        maxResourceBytes: Long,
    ): ByteArray {
        val source = dataSourceFactory(context, urlPolicy, maxResourceBytes).createDataSource()
        try {
            source.open(DataSpec(Uri.parse(url)))
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = source.read(buffer, 0, buffer.size)
                if (read < 0) break
                require(output.size().toLong() + read <= maxResourceBytes)
                output.write(buffer, 0, read)
            }
            return output.toByteArray().also { require(it.isNotEmpty()) }
        } finally {
            source.close()
        }
    }

    fun copyToFile(
        context: Context,
        url: String,
        urlPolicy: StaticMediaUrlPolicy,
        destination: File,
        maxResourceBytes: Long = StaticVideoMaxBytes,
    ) {
        val source = dataSourceFactory(context, urlPolicy, maxResourceBytes).createDataSource()
        destination.parentFile?.mkdirs()
        try {
            source.open(DataSpec(Uri.parse(url)))
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val read = source.read(buffer, 0, buffer.size)
                    if (read < 0) break
                    total += read
                    require(total <= maxResourceBytes)
                    output.write(buffer, 0, read)
                }
                output.fd.sync()
                require(total > 0L)
            }
        } catch (failure: Throwable) {
            destination.delete()
            throw failure
        } finally {
            source.close()
        }
    }

    @Synchronized
    private fun cache(context: Context): SimpleCache = diskCache ?: SimpleCache(
        File(context.cacheDir, "static-media"),
        LeastRecentlyUsedCacheEvictor(StaticMediaDiskCacheBytes),
        StandaloneDatabaseProvider(context),
    ).also { diskCache = it }
}
