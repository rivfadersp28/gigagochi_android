package com.gigagochi.app.core.webview

import android.os.Process
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gigagochi.app.feature.events.createVerifiedMediaShareHandoff
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidVerifiedMediaFileOpsTest {
    @Test
    fun verifiedCopyAtomicMoveAndDirectorySyncPublishOneCompleteIndependentFile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "media-ops-${UUID.randomUUID()}")
        assertTrue(directory.mkdirs())
        try {
            val source = File(directory, "source.media")
            val partial = File(directory, "handoff.part")
            val destination = File(directory, "handoff.mp4")
            val bytes = byteArrayOf(1, 3, 3, 7)
            source.writeBytes(bytes)

            assertEquals(Process.myUid().toLong(), Os.stat(source.path).st_uid.toLong())
            AndroidVerifiedMediaFileOps.copyVerified(
                source = source,
                destination = partial,
                expectedByteLength = bytes.size.toLong(),
                expectedSha256 = sha256(bytes),
            )
            assertNotEquals(Os.stat(source.path).st_ino, Os.stat(partial.path).st_ino)
            assertTrue(source.delete())
            AndroidVerifiedMediaFileOps.atomicMove(partial, destination)
            AndroidVerifiedMediaFileOps.syncDirectory(directory)

            assertFalse(source.exists())
            assertFalse(partial.exists())
            assertArrayEquals(bytes, destination.readBytes())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun verifiedCopyCreatesDestinationExclusivelyWithoutOverwritingExistingData() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "media-ops-exclusive-${UUID.randomUUID()}")
        assertTrue(directory.mkdirs())
        try {
            val source = File(directory, "source.media")
            val destination = File(directory, "handoff.part")
            val sourceBytes = byteArrayOf(1, 2, 3, 4)
            val existingBytes = byteArrayOf(9, 9, 9)
            source.writeBytes(sourceBytes)
            destination.writeBytes(existingBytes)

            expectIOException {
                AndroidVerifiedMediaFileOps.copyVerified(
                    source = source,
                    destination = destination,
                    expectedByteLength = sourceBytes.size.toLong(),
                    expectedSha256 = sha256(sourceBytes),
                )
            }

            assertArrayEquals(existingBytes, destination.readBytes())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun verifiedCopyRejectsChangedLengthOrDigestAndRemovesPartialData() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "media-ops-validation-${UUID.randomUUID()}")
        assertTrue(directory.mkdirs())
        try {
            val source = File(directory, "source.media")
            val partial = File(directory, "handoff.part")
            val verifiedBytes = byteArrayOf(1, 2, 3, 4)
            val changedBytes = byteArrayOf(4, 3, 2, 1)
            source.writeBytes(changedBytes)

            expectIOException {
                AndroidVerifiedMediaFileOps.copyVerified(
                    source = source,
                    destination = partial,
                    expectedByteLength = verifiedBytes.size.toLong(),
                    expectedSha256 = sha256(verifiedBytes),
                )
            }
            assertFalse(partial.exists())

            expectIOException {
                AndroidVerifiedMediaFileOps.copyVerified(
                    source = source,
                    destination = partial,
                    expectedByteLength = changedBytes.size.toLong() + 1L,
                    expectedSha256 = sha256(changedBytes),
                )
            }
            assertFalse(partial.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun shareHandoffAcrossCacheSubdirectoriesSurvivesSourceDeletionWithoutStagingLeak() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val testRoot = File(context.cacheDir, "media-handoff-${UUID.randomUUID()}")
        val verifiedDirectory = File(testRoot, "verified")
        val shareDirectory = File(testRoot, "shared")
        assertTrue(verifiedDirectory.mkdirs())
        try {
            val bytes = byteArrayOf(7, 3, 1, 9)
            val source = File(verifiedDirectory, "source.media").apply { writeBytes(bytes) }
            val asset = VerifiedWebMediaAsset(
                file = source,
                mimeType = "video/mp4",
                byteLength = bytes.size.toLong(),
                version = sha256(bytes),
            )

            val published = createVerifiedMediaShareHandoff(
                asset = asset,
                directory = shareDirectory,
                fileOps = AndroidVerifiedMediaFileOps,
            )
            assertTrue(source.delete())

            assertArrayEquals(bytes, published.readBytes())
            assertFalse(shareDirectory.listFiles().orEmpty().any { it.extension == "part" })
        } finally {
            testRoot.deleteRecursively()
        }
    }

    private inline fun expectIOException(operation: () -> Unit) {
        try {
            operation()
            fail("Expected IOException")
        } catch (_: IOException) {
            // Expected.
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }
}
