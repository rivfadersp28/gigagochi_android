package com.gigagochi.app.core.webview

import com.gigagochi.app.core.network.StaticImageMaxBytes
import com.gigagochi.app.core.network.StaticMediaFailure
import com.gigagochi.app.core.network.StaticMediaIOException
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VerifiedWebMediaStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `publishes only verified complete media atomically and reuses it offline`() {
        val directory = temporaryFolder.newFolder("cache")
        File(directory, "abandoned.part").writeBytes(byteArrayOf(1, 2, 3))
        val bytes = validMp4()
        var opens = 0
        val publisherObservations = mutableListOf<String>()
        val store = VerifiedWebMediaStore(
            rootDirectory = directory,
            downloadOpener = WebMediaDownloadOpener { _, _ ->
                opens += 1
                ByteDownload(bytes, "video/mp4", bytes.size.toLong())
            },
            fileOps = testFileOps { partial, destination ->
                publisherObservations +=
                    "${destination.extension}:${partial.extension}:${partial.length()}:${destination.exists()}"
                Files.move(
                    partial.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                )
            },
        )

        val first = store.materialize(MediaUrl, WebMediaKind.Video)
        val second = store.materialize(MediaUrl, WebMediaKind.Video)

        assertEquals(1, opens)
        assertEquals(first, second)
        assertEquals("video/mp4", first.mimeType)
        assertArrayEquals(bytes, first.file.readBytes())
        assertEquals(2, publisherObservations.size)
        assertEquals("media:part:${bytes.size}:false", publisherObservations.first())
        assertTrue(publisherObservations.last().startsWith("meta:part:"))
        assertTrue(publisherObservations.last().endsWith(":false"))
        assertTrue(directory.listFiles().orEmpty().none { it.extension == "part" })

        var offlineOpens = 0
        val offlineStore = VerifiedWebMediaStore(
            rootDirectory = directory,
            downloadOpener = WebMediaDownloadOpener { _, _ ->
                offlineOpens += 1
                throw IOException("offline")
            },
        )
        val cached = requireNotNull(offlineStore.acquireCached(MediaUrl, WebMediaKind.Video))
        assertEquals(first.version, cached.version)
        offlineStore.release(cached)
        assertEquals(first.version, offlineStore.materialize(MediaUrl, WebMediaKind.Video).version)
        assertEquals(0, offlineOpens)
    }

    @Test
    fun `rejects partial corrupt mismatched mime and declared oversize without residue`() {
        val cases = listOf(
            ByteDownload(validMp4(), "video/mp4", validMp4().size + 1L),
            ByteDownload("not-an-mp4".toByteArray(), "video/mp4", 10L),
            ByteDownload(validPng(), "image/jpeg", validPng().size.toLong()),
            ByteDownload(validPng(), "image/png", StaticImageMaxBytes + 1L),
        )

        cases.forEachIndexed { index, download ->
            val directory = temporaryFolder.newFolder("rejected-$index")
            val kind = if (index < 2) WebMediaKind.Video else WebMediaKind.Image
            val store = VerifiedWebMediaStore(
                rootDirectory = directory,
                downloadOpener = WebMediaDownloadOpener { _, _ -> download },
            )

            assertTrue(runCatching { store.materialize("$MediaUrl?v=$index", kind) }.isFailure)
            assertTrue(directory.listFiles().orEmpty().none { it.extension in setOf("part", "media") })
        }
    }

    @Test
    fun `failed publish never leaves an addressable destination and lru stays bounded`() {
        val failedDirectory = temporaryFolder.newFolder("atomic-failure")
        val failed = VerifiedWebMediaStore(
            rootDirectory = failedDirectory,
            downloadOpener = WebMediaDownloadOpener { _, _ ->
                ByteDownload(validMp4(), "video/mp4", validMp4().size.toLong())
            },
            fileOps = testFileOps { _, _ -> throw IOException("rename failed") },
        )
        assertTrue(runCatching { failed.materialize(MediaUrl, WebMediaKind.Video) }.isFailure)
        assertTrue(failedDirectory.listFiles().orEmpty().isEmpty())

        val boundedDirectory = temporaryFolder.newFolder("bounded")
        val firstBytes = validMp4(byteArrayOf(1, 2, 3, 4))
        val secondBytes = validMp4(byteArrayOf(5, 6, 7, 8))
        val bounded = VerifiedWebMediaStore(
            rootDirectory = boundedDirectory,
            downloadOpener = WebMediaDownloadOpener { source, _ ->
                val bytes = if (source.endsWith("one.mp4")) firstBytes else secondBytes
                ByteDownload(bytes, "video/mp4", bytes.size.toLong())
            },
            maxCacheBytes = firstBytes.size.toLong() + secondBytes.size - 1L,
        )
        val first = bounded.materialize("https://example.test/static/one.mp4", WebMediaKind.Video)
        val second = bounded.materialize("https://example.test/static/two.mp4", WebMediaKind.Video)

        assertFalse(first.file.exists())
        assertTrue(second.file.exists())
        assertTrue(boundedDirectory.listFiles().orEmpty().filter { it.extension == "media" }.sumOf(File::length) <=
            firstBytes.size.toLong() + secondBytes.size - 1L)
        assertNotEquals(first.version, second.version)
    }

    @Test
    fun `late directory sync failure is cleaned before cache readers can acquire it`() {
        val directory = temporaryFolder.newFolder("late-publish-failure")
        val bytes = validMp4()
        val store = VerifiedWebMediaStore(
            rootDirectory = directory,
            downloadOpener = WebMediaDownloadOpener { _, _ ->
                ByteDownload(bytes, "video/mp4", bytes.size.toLong())
            },
            fileOps = object : VerifiedMediaFileOps {
                override fun atomicMove(partial: File, destination: File) {
                    Files.move(
                        partial.toPath(),
                        destination.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                    )
                }

                override fun copyVerified(
                    source: File,
                    destination: File,
                    expectedByteLength: Long,
                    expectedSha256: String,
                ) = error("Verified copies are not used by this test")

                override fun syncDirectory(directory: File) {
                    throw IOException("directory sync failed")
                }
            },
        )

        assertTrue(runCatching { store.materialize(MediaUrl, WebMediaKind.Video) }.isFailure)
        assertNull(store.acquireCached(MediaUrl, WebMediaKind.Video))
        assertTrue(directory.listFiles().orEmpty().none { it.extension in setOf("media", "meta", "part") })
    }

    @Test
    fun `atomic acquire prevents lru eviction before the caller receives its lease`() {
        val directory = temporaryFolder.newFolder("lease")
        val firstBytes = validMp4(byteArrayOf(1, 2, 3, 4))
        val secondBytes = validMp4(byteArrayOf(5, 6, 7, 8))
        val store = VerifiedWebMediaStore(
            rootDirectory = directory,
            downloadOpener = WebMediaDownloadOpener { source, _ ->
                val bytes = if (source.endsWith("one.mp4")) firstBytes else secondBytes
                ByteDownload(bytes, "video/mp4", bytes.size.toLong())
            },
            maxCacheBytes = firstBytes.size.toLong(),
        )

        val first = store.acquire("https://example.test/static/one.mp4", WebMediaKind.Video)
        assertTrue(
            runCatching {
                store.materialize("https://example.test/static/two.mp4", WebMediaKind.Video)
            }.isFailure,
        )
        assertTrue(first.file.exists())

        store.release(first)
        val second = store.materialize(
            "https://example.test/static/two.mp4",
            WebMediaKind.Video,
        )
        assertFalse(first.file.exists())
        assertTrue(second.file.exists())
    }

    @Test
    fun `held download does not block cached or missing sidecar lookup`() {
        val directory = temporaryFolder.newFolder("non-blocking-cache-lookup")
        val bytes = validMp4(byteArrayOf(4, 3, 2, 1))
        val downloadStarted = CountDownLatch(1)
        val releaseDownload = CountDownLatch(1)
        val store = VerifiedWebMediaStore(
            rootDirectory = directory,
            downloadOpener = WebMediaDownloadOpener { source, _ ->
                if (source.endsWith("held.mp4")) {
                    HeldDownload(bytes, downloadStarted, releaseDownload)
                } else {
                    ByteDownload(bytes, "video/mp4", bytes.size.toLong())
                }
            },
        )
        val cachedUrl = "https://example.test/static/cached.mp4"
        val heldUrl = "https://example.test/static/held.mp4"
        store.materialize(cachedUrl, WebMediaKind.Video)
        val executor = Executors.newFixedThreadPool(3)
        try {
            val materialization = executor.submit<VerifiedWebMediaAsset> {
                store.materialize(heldUrl, WebMediaKind.Video)
            }
            assertTrue(downloadStarted.await(1, TimeUnit.SECONDS))

            val cached = executor.submit<VerifiedWebMediaAsset?> {
                store.acquireCached(cachedUrl, WebMediaKind.Video)
            }.get(500, TimeUnit.MILLISECONDS)
            val missing = executor.submit<VerifiedWebMediaAsset?> {
                store.acquireCached(heldUrl, WebMediaKind.Video)
            }.get(500, TimeUnit.MILLISECONDS)

            assertEquals(bytes.size.toLong(), requireNotNull(cached).byteLength)
            assertNull(missing)
            store.release(cached)
            releaseDownload.countDown()
            assertEquals(bytes.size.toLong(), materialization.get(1, TimeUnit.SECONDS).byteLength)
        } finally {
            releaseDownload.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `legacy metadata publish failure releases temporary verification pin`() {
        val directory = temporaryFolder.newFolder("legacy-pin-release")
        val firstBytes = validMp4(byteArrayOf(1, 2, 3, 4))
        val secondBytes = validMp4(byteArrayOf(5, 6, 7, 8))
        val firstUrl = "https://example.test/static/legacy.mp4"
        val secondUrl = "https://example.test/static/replacement.mp4"
        val seedStore = VerifiedWebMediaStore(
            rootDirectory = directory,
            downloadOpener = WebMediaDownloadOpener { _, _ ->
                ByteDownload(firstBytes, "video/mp4", firstBytes.size.toLong())
            },
            maxCacheBytes = firstBytes.size.toLong(),
        )
        val legacy = seedStore.materialize(firstUrl, WebMediaKind.Video)
        assertTrue(File(directory, "${legacy.file.name}.meta").delete())

        val failLegacyMetadataMove = AtomicBoolean(true)
        val recoveringStore = VerifiedWebMediaStore(
            rootDirectory = directory,
            downloadOpener = WebMediaDownloadOpener { source, _ ->
                val bytes = if (source == firstUrl) firstBytes else secondBytes
                ByteDownload(bytes, "video/mp4", bytes.size.toLong())
            },
            maxCacheBytes = firstBytes.size.toLong(),
            fileOps = testFileOps { partial, destination ->
                if (destination.extension == "meta" && failLegacyMetadataMove.compareAndSet(true, false)) {
                    throw IOException("metadata move failed")
                }
                Files.move(
                    partial.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                )
            },
        )

        assertTrue(runCatching { recoveringStore.materialize(firstUrl, WebMediaKind.Video) }.isFailure)
        assertTrue(legacy.file.exists())

        val replacement = recoveringStore.materialize(secondUrl, WebMediaKind.Video)

        assertFalse(legacy.file.exists())
        assertTrue(replacement.file.exists())
        assertArrayEquals(secondBytes, replacement.file.readBytes())
        assertTrue(directory.listFiles().orEmpty().none { it.extension == "part" })
    }

    @Test
    fun `pinned invalid legacy destination is never unlinked`() {
        val directory = temporaryFolder.newFolder("pinned-invalid")
        val originalBytes = validMp4(byteArrayOf(1, 2, 3, 4))
        val replacementBytes = validMp4(byteArrayOf(9, 8, 7, 6))
        var opens = 0
        val store = VerifiedWebMediaStore(
            rootDirectory = directory,
            downloadOpener = WebMediaDownloadOpener { _, _ ->
                opens += 1
                val bytes = if (opens == 1) originalBytes else replacementBytes
                ByteDownload(bytes, "video/mp4", bytes.size.toLong())
            },
            maxCacheBytes = originalBytes.size.toLong(),
        )
        val pinned = store.acquire(MediaUrl, WebMediaKind.Video)
        File(directory, "${pinned.file.name}.meta").delete()
        val invalidBytes = "invalid-active-media".toByteArray()
        pinned.file.writeBytes(invalidBytes)

        val failure = runCatching {
            store.materialize(MediaUrl, WebMediaKind.Video)
        }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertTrue(pinned.file.exists())
        assertArrayEquals(invalidBytes, pinned.file.readBytes())
        assertEquals(1, opens)

        store.release(pinned)
        val replacement = store.materialize(MediaUrl, WebMediaKind.Video)
        assertEquals(2, opens)
        assertArrayEquals(replacementBytes, replacement.file.readBytes())
    }

    @Test
    fun `redirect rejected by trusted downloader never creates a cache entry`() {
        val directory = temporaryFolder.newFolder("redirect")
        val store = VerifiedWebMediaStore(
            rootDirectory = directory,
            downloadOpener = WebMediaDownloadOpener { _, _ ->
                throw StaticMediaIOException(StaticMediaFailure.Redirect)
            },
        )

        val failure = runCatching {
            store.materialize(MediaUrl, WebMediaKind.Video)
        }.exceptionOrNull()

        assertTrue(failure is StaticMediaIOException)
        assertEquals(StaticMediaFailure.Redirect, (failure as StaticMediaIOException).reason)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    private class ByteDownload(
        private val bytes: ByteArray,
        override val declaredMimeType: String?,
        override val declaredLength: Long?,
    ) : WebMediaDownload {
        private var offset = 0

        override fun read(buffer: ByteArray): Int {
            if (offset == bytes.size) return -1
            val count = minOf(buffer.size, bytes.size - offset)
            bytes.copyInto(buffer, 0, offset, offset + count)
            offset += count
            return count
        }

        override fun close() = Unit
    }

    private class HeldDownload(
        bytes: ByteArray,
        private val started: CountDownLatch,
        private val release: CountDownLatch,
    ) : WebMediaDownload {
        private val delegate = ByteDownload(bytes, "video/mp4", bytes.size.toLong())
        override val declaredMimeType: String = "video/mp4"
        override val declaredLength: Long = bytes.size.toLong()
        private var held = false

        override fun read(buffer: ByteArray): Int {
            if (!held) {
                held = true
                started.countDown()
                check(release.await(2, TimeUnit.SECONDS)) { "held download was not released" }
            }
            return delegate.read(buffer)
        }

        override fun close() = Unit
    }

    private fun validMp4(payload: ByteArray = byteArrayOf(1, 2, 3)): ByteArray =
        box("ftyp", "isom0000".toByteArray()) + box("mdat", payload)

    private fun box(type: String, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(8 + payload.size)
            .putInt(8 + payload.size)
            .put(type.toByteArray(Charsets.US_ASCII))
            .put(payload)
            .array()

    private fun validPng(): ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        0, 0, 0, 0, 0x49, 0x45, 0x4e, 0x44, 0, 0, 0, 0,
    )

    private fun testFileOps(
        atomicMove: (partial: File, destination: File) -> Unit,
    ): VerifiedMediaFileOps = object : VerifiedMediaFileOps {
        override fun atomicMove(partial: File, destination: File) =
            atomicMove.invoke(partial, destination)

        override fun copyVerified(
            source: File,
            destination: File,
            expectedByteLength: Long,
            expectedSha256: String,
        ) = error("Verified copies are not used by this test")

        override fun syncDirectory(directory: File) = Unit
    }

    private companion object {
        const val MediaUrl = "https://example.test/static/pet.mp4"
    }
}
