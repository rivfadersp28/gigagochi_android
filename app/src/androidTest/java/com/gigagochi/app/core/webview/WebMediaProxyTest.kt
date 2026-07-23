package com.gigagochi.app.core.webview

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gigagochi.app.core.network.StaticMediaUrlPolicy
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebMediaProxyTest {
    @Test
    fun getHeadAndRangeServeOnlyTheSameCompleteOfflineFileAndRevocationStopsNewRequests() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "proxy-test-${UUID.randomUUID()}")
        val bytes = validMp4()
        var opens = 0
        val onlineStore = VerifiedWebMediaStore(
            rootDirectory = directory,
            downloadOpener = WebMediaDownloadOpener { _, _ ->
                opens += 1
                ByteDownload(bytes)
            },
            fileOps = AndroidVerifiedMediaFileOps,
        )
        val onlineRegistry = registry(onlineStore)
        onlineStore.materialize(MediaUrl, WebMediaKind.Video)
        val reference = requireNotNull(onlineRegistry.register(MediaUrl, WebMediaKind.Video))
        val absolute = "$GigagochiWebOrigin$reference"
        val proxy = WebMediaProxy(context, onlineRegistry)

        val range = requireNotNull(proxy.intercept(absolute, "GET", mapOf("Range" to "bytes=8-11")))
        assertEquals(206, range.statusCode)
        assertEquals("video/mp4", range.mimeType)
        assertEquals("4", range.responseHeaders["Content-Length"])
        assertEquals("bytes 8-11/${bytes.size}", range.responseHeaders["Content-Range"])
        assertArrayEquals(bytes.copyOfRange(8, 12), range.data.use { it.readBytes() })
        assertEquals(1, opens)

        val head = requireNotNull(proxy.intercept(absolute, "HEAD", emptyMap()))
        assertEquals(200, head.statusCode)
        assertEquals(bytes.size.toString(), head.responseHeaders["Content-Length"])
        assertTrue(head.data.use { it.readBytes() }.isEmpty())
        assertEquals(1, opens)

        onlineRegistry.invalidateDocument()
        assertEquals(404, proxy.intercept(absolute, "GET", emptyMap())?.statusCode)

        val offlineStore = VerifiedWebMediaStore(
            rootDirectory = directory,
            downloadOpener = WebMediaDownloadOpener { _, _ -> throw IOException("offline") },
            fileOps = AndroidVerifiedMediaFileOps,
        )
        val offlineRegistry = registry(offlineStore)
        val offlineRef = requireNotNull(offlineRegistry.register(MediaUrl, WebMediaKind.Video))
        val offlineResponse = requireNotNull(
            WebMediaProxy(context, offlineRegistry).intercept(
                "$GigagochiWebOrigin$offlineRef",
                "GET",
                emptyMap(),
            ),
        )
        assertEquals(200, offlineResponse.statusCode)
        assertArrayEquals(bytes, offlineResponse.data.use { it.readBytes() })
        onlineRegistry.close()
        offlineRegistry.close()
        directory.deleteRecursively()
    }

    private fun registry(store: VerifiedWebMediaStore) = WebMediaReferenceRegistry(
        urlPolicy = StaticMediaUrlPolicy("https://gigagochi.serega.works/", false),
        materializer = store,
        scopeProvider = { WebMediaOwnerScope("owner", "pet") },
    )

    private class ByteDownload(
        private val bytes: ByteArray,
    ) : WebMediaDownload {
        private var offset = 0
        override val declaredMimeType: String = "video/mp4"
        override val declaredLength: Long = bytes.size.toLong()

        override fun read(buffer: ByteArray): Int {
            if (offset == bytes.size) return -1
            val count = minOf(buffer.size, bytes.size - offset)
            bytes.copyInto(buffer, 0, offset, offset + count)
            offset += count
            return count
        }

        override fun close() = Unit
    }

    private fun validMp4(): ByteArray =
        box("ftyp", "isom0000".toByteArray()) + box("mdat", byteArrayOf(1, 2, 3, 4))

    private fun box(type: String, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(8 + payload.size)
            .putInt(8 + payload.size)
            .put(type.toByteArray(Charsets.US_ASCII))
            .put(payload)
            .array()

    private companion object {
        const val MediaUrl = "https://gigagochi.serega.works/static/pet.mp4?v=verified"
    }
}
