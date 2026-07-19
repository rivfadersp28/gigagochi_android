package com.gigagochi.app.core.network

import android.graphics.Bitmap
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

@UnstableApi
class StaticMediaCacheTest {
    @Test
    fun repeatedReadUsesDiskCacheWithoutOpeningUpstreamAgain() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val bytes = "cached-static-media".encodeToByteArray()
        var upstreamOpenCount = 0
        val upstream = DataSource.Factory {
            val delegate = ByteArrayDataSource(bytes)
            object : DataSource by delegate {
                override fun open(dataSpec: DataSpec): Long {
                    upstreamOpenCount += 1
                    return delegate.open(dataSpec)
                }
            }
        }
        val factory = StaticMediaCache.cachedDataSourceFactory(context, upstream)
        val uri = Uri.parse("https://gigagochi.serega.works/static/test-${UUID.randomUUID()}.bin")

        assertArrayEquals(bytes, readAll(factory, uri))
        assertArrayEquals(bytes, readAll(factory, uri))
        assertEquals(1, upstreamOpenCount)
    }

    @Test
    fun decodedPosterIsReusedFromMemory() {
        val key = "poster-${UUID.randomUUID()}"
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)

        StaticMediaCache.putDecodedImage(key, bitmap)

        assertSame(bitmap, StaticMediaCache.decodedImage(key))
    }

    private fun readAll(factory: DataSource.Factory, uri: Uri): ByteArray {
        val source = factory.createDataSource()
        return try {
            source.open(DataSpec(uri))
            buildList<Byte> {
                val buffer = ByteArray(32)
                while (true) {
                    val read = source.read(buffer, 0, buffer.size)
                    if (read == C.RESULT_END_OF_INPUT) break
                    repeat(read) { add(buffer[it]) }
                }
            }.toByteArray()
        } finally {
            source.close()
        }
    }
}
