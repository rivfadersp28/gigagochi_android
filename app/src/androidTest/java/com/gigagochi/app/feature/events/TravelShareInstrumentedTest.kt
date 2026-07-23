package com.gigagochi.app.feature.events

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gigagochi.app.core.database.GigagochiDatabase
import com.gigagochi.app.core.database.LocalTravelVideoAsset
import com.gigagochi.app.core.database.PetLocalRepository
import com.gigagochi.app.core.security.PetLocalRepositoryTravelVideoAssetStore
import com.gigagochi.app.core.security.TravelVideoShareLookupResult
import com.gigagochi.app.core.security.TravelVideoShareResolver
import com.gigagochi.app.core.webview.AndroidVerifiedMediaFileOps
import com.gigagochi.app.core.webview.BridgeDocumentFence
import com.gigagochi.app.core.webview.VerifiedWebMediaStore
import com.gigagochi.app.core.webview.WebMediaDownload
import com.gigagochi.app.core.webview.WebMediaDownloadOpener
import com.gigagochi.app.core.webview.WebTravelShareAcceptResult
import com.gigagochi.app.core.webview.WebTravelShareCompletionPayload
import com.gigagochi.app.core.webview.WebTravelShareCompletionPublisher
import com.gigagochi.app.core.webview.WebTravelShareCompletionStatus
import com.gigagochi.app.core.webview.WebTravelShareCoordinator
import com.gigagochi.app.core.webview.WebTravelShareRequest
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TravelShareInstrumentedTest {
    private lateinit var context: Context
    private lateinit var database: GigagochiDatabase
    private lateinit var repository: PetLocalRepository
    private lateinit var testRoot: File
    private lateinit var shareDirectory: File
    private var leaveShareHandoffForSystemEvidence = false

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(
            context,
            GigagochiDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = PetLocalRepository(database) { Now }
        testRoot = File(context.cacheDir, "travel-share-qa-${UUID.randomUUID()}")
        assertTrue(testRoot.mkdirs())
        shareDirectory = File(context.cacheDir, "shared-travel-videos")
        shareDirectory.deleteRecursively()
    }

    @After
    fun tearDown() {
        database.close()
        testRoot.deleteRecursively()
        if (!leaveShareHandoffForSystemEvidence) {
            shareDirectory.deleteRecursively()
        }
    }

    @Test
    fun productionRoomResolverSharerAndCoordinatorDeduplicateToOneExactChooser() = runBlocking {
        val asset = readyTravel()
        repository.saveTravelVideoAsset(asset)
        val resolver = TravelVideoShareResolver(
            ownerId = OwnerId,
            activePetId = PetId,
            store = PetLocalRepositoryTravelVideoAssetStore(repository),
        )
        assertEquals(
            TravelVideoShareLookupResult.Ready(asset),
            resolver.resolve(RequestKey),
        )

        val bytes = validMp4()
        val opens = AtomicInteger()
        val verifiedMedia = VerifiedWebMediaStore(
            rootDirectory = File(testRoot, "verified"),
            downloadOpener = WebMediaDownloadOpener { sourceUrl, _ ->
                assertEquals(asset.videoUrl, sourceUrl)
                opens.incrementAndGet()
                ByteDownload(bytes)
            },
            fileOps = AndroidVerifiedMediaFileOps,
        )
        val captureContext = CapturingContext(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val resolverStarted = CompletableDeferred<Unit>()
        val releaseResolver = CompletableDeferred<Unit>()
        val resolverCalls = AtomicInteger()
        val completions = CopyOnWriteArrayList<
            Pair<BridgeDocumentFence, WebTravelShareCompletionPayload>,
            >()
        val failures = AtomicInteger()
        val coordinator = WebTravelShareCoordinator(
            scope = scope,
            resolver = { requestKey ->
                resolverCalls.incrementAndGet()
                resolverStarted.complete(Unit)
                releaseResolver.await()
                resolver.resolve(requestKey)
            },
            sharer = AndroidTravelVideoSharer(
                context = captureContext,
                verifiedMedia = verifiedMedia,
            )::share,
            presentFailure = failures::incrementAndGet,
            publishCompletion = WebTravelShareCompletionPublisher { fence, payload ->
                completions += fence to payload
            },
        )
        try {
            assertSame(
                WebTravelShareAcceptResult.Accepted,
                coordinator.accept(
                    WebTravelShareRequest(RequestKey, BridgeDocumentFence(11L)),
                ),
            )
            withTimeout(5_000L) { resolverStarted.await() }
            assertSame(
                WebTravelShareAcceptResult.Accepted,
                coordinator.accept(
                    WebTravelShareRequest(RequestKey, BridgeDocumentFence(12L)),
                ),
            )
            releaseResolver.complete(Unit)
            withTimeout(5_000L) {
                while (completions.size < 2) yield()
            }

            assertEquals(0, failures.get())
            assertEquals(1, resolverCalls.get())
            assertEquals(1, opens.get())
            assertEquals(1, captureContext.startedIntents.size)
            assertEquals(
                setOf(BridgeDocumentFence(11L), BridgeDocumentFence(12L)),
                completions.map { it.first }.toSet(),
            )
            assertTrue(
                completions.all {
                    it.second == WebTravelShareCompletionPayload(
                        RequestKey,
                        WebTravelShareCompletionStatus.Opened,
                    )
                },
            )

            val chooser = captureContext.startedIntents.single()
            assertEquals(Intent.ACTION_CHOOSER, chooser.action)
            assertEquals(
                "Показать друзьям",
                chooser.getCharSequenceExtra(Intent.EXTRA_TITLE)?.toString(),
            )
            @Suppress("DEPRECATION")
            val send = requireNotNull(
                chooser.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent,
            )
            assertEquals(Intent.ACTION_SEND, send.action)
            assertEquals("video/mp4", send.type)
            assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            @Suppress("DEPRECATION")
            val stream = requireNotNull(
                send.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri,
            )
            assertEquals(stream, send.clipData?.getItemAt(0)?.uri)
            assertEquals(
                "Видео путешествия",
                send.clipData?.description?.label?.toString(),
            )
            assertEquals("${context.packageName}.fileprovider", stream.authority)
            assertEquals("video/mp4", context.contentResolver.getType(stream))
            val sharedBytes = requireNotNull(context.contentResolver.openInputStream(stream))
                .use { it.readBytes() }
            assertArrayEquals(bytes, sharedBytes)
            assertEquals(1, shareDirectory.listFiles().orEmpty().count { it.extension == "mp4" })
            assertFalse(shareDirectory.listFiles().orEmpty().any { it.extension == "part" })

            leaveShareHandoffForSystemEvidence = true
            context.startActivity(
                Intent(chooser).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            // Keep the real Android Sharesheet and its verified handoff alive for host-side
            // screenshot/UI-hierarchy evidence after instrumentation returns.
            SystemClock.sleep(1_000L)
            Unit
        } finally {
            coordinator.close()
            scope.cancel()
        }
    }

    @Test
    fun invalidForeignAndUnconsumedRoomRowsNeverReachSystemChooser() = runBlocking {
        val notReady = readyTravel().copy(consumedAtEpochMillis = null)
        repository.saveTravelVideoAsset(notReady)
        val resolver = TravelVideoShareResolver(
            ownerId = OwnerId,
            activePetId = PetId,
            store = PetLocalRepositoryTravelVideoAssetStore(repository),
        )

        assertSame(TravelVideoShareLookupResult.NotReady, resolver.resolve(RequestKey))
        assertSame(
            TravelVideoShareLookupResult.Invalid,
            resolver.resolve(RequestKey.uppercase()),
        )
        assertSame(
            TravelVideoShareLookupResult.Missing,
            TravelVideoShareResolver(
                ownerId = "owner-foreign",
                activePetId = PetId,
                store = PetLocalRepositoryTravelVideoAssetStore(repository),
            ).resolve(RequestKey),
        )
    }

    private fun readyTravel() = LocalTravelVideoAsset(
        ownerId = OwnerId,
        petId = PetId,
        requestKey = RequestKey,
        backendJobId = "travel-video-prototype-${"a".repeat(32)}",
        prompt = "Полететь к морю",
        title = "Море",
        scenario = null,
        imageUrl = null,
        videoUrl = "https://gigagochi.test/static/travel.mp4",
        completedAtEpochMillis = Now - 1L,
        consumedAtEpochMillis = Now,
    )

    private fun validMp4(): ByteArray =
        box("ftyp", "isom0000".toByteArray()) +
            box("mdat", byteArrayOf(1, 2, 3, 4, 5))

    private fun box(type: String, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(8 + payload.size)
            .putInt(8 + payload.size)
            .put(type.toByteArray(Charsets.US_ASCII))
            .put(payload)
            .array()

    private class ByteDownload(private val bytes: ByteArray) : WebMediaDownload {
        override val declaredMimeType: String = "video/mp4"
        override val declaredLength: Long = bytes.size.toLong()
        private var offset = 0

        override fun read(buffer: ByteArray): Int {
            if (offset == bytes.size) return -1
            val count = minOf(buffer.size, bytes.size - offset)
            bytes.copyInto(buffer, destinationOffset = 0, startIndex = offset, endIndex = offset + count)
            offset += count
            return count
        }

        override fun close() = Unit
    }

    private class CapturingContext(base: Context) : ContextWrapper(base) {
        val startedIntents = CopyOnWriteArrayList<Intent>()

        override fun startActivity(intent: Intent) {
            startedIntents += Intent(intent)
        }
    }

    private companion object {
        const val OwnerId = "owner-share-qa"
        const val PetId = "pet-share-qa"
        const val RequestKey = "123e4567-e89b-42d3-a456-426614174000"
        const val Now = 1_900_000_000_000L
    }
}
