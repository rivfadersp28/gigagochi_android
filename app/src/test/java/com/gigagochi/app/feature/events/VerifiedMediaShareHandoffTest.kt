package com.gigagochi.app.feature.events

import com.gigagochi.app.core.webview.VerifiedWebMediaAsset
import com.gigagochi.app.core.webview.VerifiedMediaFileOps
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VerifiedMediaShareHandoffTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `verified share copy survives source eviction and never exposes a partial`() {
        val source = temporaryFolder.newFile("verified.media")
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        source.writeBytes(bytes)
        val directory = temporaryFolder.newFolder("share")
        val asset = VerifiedWebMediaAsset(
            file = source,
            mimeType = "video/mp4",
            byteLength = bytes.size.toLong(),
            version = sha256(bytes),
        )
        File(directory, "${asset.version}.mp4").writeBytes(ByteArray(bytes.size) { 9 })

        val handoff = createVerifiedMediaShareHandoff(
            asset,
            directory,
            object : VerifiedMediaFileOps {
                override fun atomicMove(partial: File, destination: File) {
                    Files.move(
                        partial.toPath(),
                        destination.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }

                override fun copyVerified(
                    source: File,
                    destination: File,
                    expectedByteLength: Long,
                    expectedSha256: String,
                ) {
                    assertEquals(bytes.size.toLong(), expectedByteLength)
                    assertEquals(sha256(bytes), expectedSha256)
                    Files.copy(source.toPath(), destination.toPath())
                }

                override fun syncDirectory(directory: File) = Unit
            },
        )

        assertFalse(Files.isSameFile(source.toPath(), handoff.toPath()))
        assertTrue(source.delete())
        assertArrayEquals(bytes, handoff.readBytes())
        assertFalse(directory.listFiles().orEmpty().any { it.extension == "part" })
    }

    @Test
    fun `share preparation removes every abandoned partial and preserves unrelated files`() {
        val source = temporaryFolder.newFile("verified-cleanup.media")
        source.writeBytes(byteArrayOf(5, 4, 3, 2, 1))
        val directory = temporaryFolder.newFolder("share-cleanup")
        val nowMillis = 200_000_000L
        val stalePartial = File(directory, "abandoned.part").apply {
            writeBytes(byteArrayOf(9))
            assertTrue(setLastModified(nowMillis - 25L * 60L * 60L * 1000L))
        }
        val recentPartial = File(directory, "in-flight.part").apply {
            writeBytes(byteArrayOf(8))
            assertTrue(setLastModified(nowMillis - 60L * 1000L))
        }
        val unrelated = File(directory, "keep.txt").apply {
            writeText("not a share handoff")
            assertTrue(setLastModified(0L))
        }
        val asset = VerifiedWebMediaAsset(
            file = source,
            mimeType = "video/mp4",
            byteLength = source.length(),
            version = sha256(source.readBytes()),
        )

        createVerifiedMediaShareHandoff(
            asset = asset,
            directory = directory,
            fileOps = JvmVerifiedMediaFileOps,
            nowMillis = nowMillis,
            maxPublishedBytes = 1L,
            maxPublishedFiles = 1,
        )

        assertFalse(stalePartial.exists())
        assertFalse(recentPartial.exists())
        assertTrue(unrelated.exists())
    }

    @Test
    fun `repeat handoff atomically replaces the published copy without staging leaks`() {
        val source = temporaryFolder.newFile("verified-repeat.media")
        source.writeBytes(byteArrayOf(1, 3, 3, 7))
        val directory = temporaryFolder.newFolder("share-repeat")
        val asset = VerifiedWebMediaAsset(
            file = source,
            mimeType = "video/mp4",
            byteLength = source.length(),
            version = sha256(source.readBytes()),
        )

        val first = createVerifiedMediaShareHandoff(
            asset = asset,
            directory = directory,
            fileOps = JvmVerifiedMediaFileOps,
            nowMillis = 1_000L,
            maxPublishedBytes = source.length(),
            maxPublishedFiles = 1,
        )
        val competing = File(directory, "competing.mp4").apply {
            writeBytes(byteArrayOf(9, 9, 9, 9))
            assertTrue(setLastModified(2_000L))
        }
        val repeated = createVerifiedMediaShareHandoff(
            asset = asset,
            directory = directory,
            fileOps = JvmVerifiedMediaFileOps,
            nowMillis = 3_000L,
            maxPublishedBytes = source.length(),
            maxPublishedFiles = 1,
        )

        assertEquals(first, repeated)
        assertFalse(competing.exists())
        assertArrayEquals(source.readBytes(), repeated.readBytes())
        assertEquals(1, publishedFiles(directory).size)
        assertFalse(directory.listFiles().orEmpty().any { it.extension == "part" })
    }

    @Test
    fun `byte budget evicts oldest completed copies and retains the newest that fits`() {
        val source = temporaryFolder.newFile("verified-byte-budget.media")
        val incomingBytes = byteArrayOf(1, 2, 3, 4, 5, 6)
        source.writeBytes(incomingBytes)
        val directory = temporaryFolder.newFolder("share-byte-budget")
        val oldest = publishedFile(directory, "oldest.mp4", 4, 1_000L)
        val middle = publishedFile(directory, "middle.mp4", 4, 2_000L)
        val newest = publishedFile(directory, "newest.mp4", 4, 3_000L)
        val asset = verifiedAsset(source, incomingBytes)

        val handoff = createVerifiedMediaShareHandoff(
            asset = asset,
            directory = directory,
            fileOps = JvmVerifiedMediaFileOps,
            nowMillis = 4_000L,
            maxPublishedBytes = 10L,
            maxPublishedFiles = 8,
        )

        assertFalse(oldest.exists())
        assertFalse(middle.exists())
        assertTrue(newest.exists())
        assertTrue(handoff.exists())
        assertEquals(10L, publishedFiles(directory).sumOf(File::length))
        assertFalse(directory.listFiles().orEmpty().any { it.extension == "part" })
    }

    @Test
    fun `file count budget evicts oldest completed copies deterministically`() {
        val source = temporaryFolder.newFile("verified-count-budget.media")
        val incomingBytes = byteArrayOf(7)
        source.writeBytes(incomingBytes)
        val directory = temporaryFolder.newFolder("share-count-budget")
        val oldest = publishedFile(directory, "oldest.mp4", 1, 1_000L)
        val middle = publishedFile(directory, "middle.mp4", 1, 2_000L)
        val newest = publishedFile(directory, "newest.mp4", 1, 3_000L)
        val asset = verifiedAsset(source, incomingBytes)

        val handoff = createVerifiedMediaShareHandoff(
            asset = asset,
            directory = directory,
            fileOps = JvmVerifiedMediaFileOps,
            nowMillis = 4_000L,
            maxPublishedBytes = 100L,
            maxPublishedFiles = 2,
        )

        assertFalse(oldest.exists())
        assertFalse(middle.exists())
        assertTrue(newest.exists())
        assertTrue(handoff.exists())
        assertEquals(2, publishedFiles(directory).size)
        assertFalse(directory.listFiles().orEmpty().any { it.extension == "part" })
    }

    @Test
    fun `one verified video fits even when it exceeds the configured byte budget`() {
        val source = temporaryFolder.newFile("verified-single-fit.media")
        val incomingBytes = byteArrayOf(1, 2, 3, 4, 5, 6)
        source.writeBytes(incomingBytes)
        val directory = temporaryFolder.newFolder("share-single-fit")
        val previous = publishedFile(directory, "previous.mp4", 3, 1_000L)
        val asset = verifiedAsset(source, incomingBytes)

        val handoff = createVerifiedMediaShareHandoff(
            asset = asset,
            directory = directory,
            fileOps = JvmVerifiedMediaFileOps,
            nowMillis = 2_000L,
            maxPublishedBytes = 4L,
            maxPublishedFiles = 8,
        )

        assertFalse(previous.exists())
        assertArrayEquals(incomingBytes, handoff.readBytes())
        assertEquals(incomingBytes.size.toLong(), publishedFiles(directory).sumOf(File::length))
        assertFalse(directory.listFiles().orEmpty().any { it.extension == "part" })
    }

    @Test
    fun `failed verification preserves the previous published copy and removes staging`() {
        val source = temporaryFolder.newFile("verified-mutated.media")
        val verifiedBytes = byteArrayOf(1, 2, 3, 4)
        val mutatedBytes = byteArrayOf(4, 3, 2, 1)
        source.writeBytes(verifiedBytes)
        val directory = temporaryFolder.newFolder("share-mutated")
        val asset = VerifiedWebMediaAsset(
            file = source,
            mimeType = "video/mp4",
            byteLength = verifiedBytes.size.toLong(),
            version = sha256(verifiedBytes),
        )
        val published = File(directory, "${asset.version}.mp4").apply {
            writeBytes(verifiedBytes)
        }
        source.writeBytes(mutatedBytes)

        try {
            createVerifiedMediaShareHandoff(
                asset = asset,
                directory = directory,
                fileOps = JvmVerifiedMediaFileOps,
                nowMillis = 1_000L,
                maxPublishedBytes = verifiedBytes.size.toLong(),
                maxPublishedFiles = 1,
            )
            throw AssertionError("Expected verified copy to reject mutated source")
        } catch (_: IOException) {
            // Expected: the source no longer matches the acquired verified asset.
        }

        assertArrayEquals(verifiedBytes, published.readBytes())
        assertFalse(directory.listFiles().orEmpty().any { it.extension == "part" })
    }

    private fun verifiedAsset(source: File, bytes: ByteArray): VerifiedWebMediaAsset =
        VerifiedWebMediaAsset(
            file = source,
            mimeType = "video/mp4",
            byteLength = bytes.size.toLong(),
            version = sha256(bytes),
        )

    private fun publishedFile(
        directory: File,
        name: String,
        byteCount: Int,
        lastModified: Long,
    ): File = File(directory, name).apply {
        writeBytes(ByteArray(byteCount) { byteCount.toByte() })
        assertTrue(setLastModified(lastModified))
    }

    private fun publishedFiles(directory: File): List<File> =
        directory.listFiles().orEmpty().filter { it.isFile && it.extension == "mp4" }

    private object JvmVerifiedMediaFileOps : VerifiedMediaFileOps {
        override fun atomicMove(partial: File, destination: File) {
            Files.move(
                partial.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }

        override fun copyVerified(
            source: File,
            destination: File,
            expectedByteLength: Long,
            expectedSha256: String,
        ) {
            try {
                Files.copy(source.toPath(), destination.toPath())
                if (
                    destination.length() != expectedByteLength ||
                    sha256(destination.readBytes()) != expectedSha256
                ) {
                    throw IOException("Verified media changed while creating handoff")
                }
            } catch (failure: Throwable) {
                destination.delete()
                throw failure
            }
        }

        override fun syncDirectory(directory: File) = Unit
    }

    private companion object {
        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { byte -> "%02x".format(byte) }
    }
}
