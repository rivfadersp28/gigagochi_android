package com.gigagochi.app.core.webview

import com.gigagochi.app.core.database.LocalTravelVideoAsset
import com.gigagochi.app.core.security.TravelVideoShareLookupResult
import com.gigagochi.app.feature.events.TravelVideoShareResult
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WebTravelShareCoordinatorTest {
    @Test
    fun `payload is strict and contains only request key`() {
        assertEquals(
            WebTravelSharePayload(RequestKey),
            BridgeCodec.json.decodeFromString<WebTravelSharePayload>(
                """{"requestKey":"$RequestKey"}""",
            ),
        )

        listOf(
            "{}",
            """{"requestKey":null}""",
            """{"requestKey":3}""",
            """{"requestKey":"$RequestKey","extra":true}""",
        ).forEach { payload ->
            try {
                BridgeCodec.json.decodeFromString<WebTravelSharePayload>(payload)
                fail("Expected strict payload rejection for $payload")
            } catch (_: SerializationException) {
                // Expected.
            }
        }
    }

    @Test
    fun `invalid request key is rejected synchronously without launching work`() {
        val resolverCalls = AtomicInteger()
        val shareCalls = AtomicInteger()
        val failures = AtomicInteger()
        val fixture = coordinator(
            resolver = {
                resolverCalls.incrementAndGet()
                TravelVideoShareLookupResult.Ready(readyAsset())
            },
            sharer = {
                shareCalls.incrementAndGet()
                TravelVideoShareResult.Opened
            },
            presentFailure = failures::incrementAndGet,
        )

        listOf(
            null,
            "",
            " $RequestKey",
            RequestKey.uppercase(),
            "123e4567-e89b-12d3-a456-426614174000",
        ).forEach { requestKey ->
            assertSame(WebTravelShareAcceptResult.Invalid, fixture.coordinator.acceptKey(requestKey))
        }

        assertEquals(0, resolverCalls.get())
        assertEquals(0, shareCalls.get())
        assertEquals(0, failures.get())
        fixture.close()
    }

    @Test
    fun `accept returns while resolver and download remain suspended`() = runBlocking {
        val resolverStarted = CompletableDeferred<Unit>()
        val resolverGate = CompletableDeferred<Unit>()
        val shareCalls = AtomicInteger()
        val fixture = coordinator(
            resolver = {
                resolverStarted.complete(Unit)
                resolverGate.await()
                TravelVideoShareLookupResult.Ready(readyAsset())
            },
            sharer = {
                shareCalls.incrementAndGet()
                TravelVideoShareResult.Opened
            },
        )

        assertSame(WebTravelShareAcceptResult.Accepted, fixture.coordinator.acceptKey(RequestKey))
        withTimeout(1_000L) { resolverStarted.await() }
        assertFalse(resolverGate.isCompleted)
        assertEquals(0, shareCalls.get())

        fixture.close()
    }

    @Test
    fun `parent scope cancellation stops sharing without failure and rejects later work`() =
        runBlocking {
            val resolverStarted = CompletableDeferred<Unit>()
            val resolverCancelled = CompletableDeferred<Unit>()
            val failures = AtomicInteger()
            val fixture = coordinator(
                resolver = {
                    resolverStarted.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        resolverCancelled.complete(Unit)
                    }
                },
                sharer = { error("share must not run") },
                presentFailure = failures::incrementAndGet,
            )

            assertSame(WebTravelShareAcceptResult.Accepted, fixture.coordinator.acceptKey(RequestKey))
            withTimeout(1_000L) { resolverStarted.await() }
            fixture.parentScope.cancel()
            withTimeout(1_000L) { resolverCancelled.await() }

            assertEquals(0, failures.get())
            assertSame(WebTravelShareAcceptResult.Invalid, fixture.coordinator.acceptKey(RequestKey))
            fixture.close()
        }

    @Test
    fun `invalid missing and not ready lookup each publish one failed completion and toast`() =
        runBlocking {
            listOf(
                TravelVideoShareLookupResult.Invalid,
                TravelVideoShareLookupResult.Missing,
                TravelVideoShareLookupResult.NotReady,
            ).forEach { result ->
                val failures = AtomicInteger()
                val presented = CompletableDeferred<Unit>()
                val completion = CompletableDeferred<
                    Pair<BridgeDocumentFence, WebTravelShareCompletionPayload>,
                    >()
                val fixture = coordinator(
                    resolver = { result },
                    sharer = { error("share must not run") },
                    presentFailure = {
                        failures.incrementAndGet()
                        presented.complete(Unit)
                    },
                    publishCompletion = WebTravelShareCompletionPublisher { fence, payload ->
                        completion.complete(fence to payload)
                    },
                )

                assertSame(
                    WebTravelShareAcceptResult.Accepted,
                    fixture.coordinator.acceptKey(RequestKey),
                )
                withTimeout(1_000L) { presented.await() }
                assertEquals(1, failures.get())
                assertEquals(
                    BridgeDocumentFence(1L) to WebTravelShareCompletionPayload(
                        RequestKey,
                        WebTravelShareCompletionStatus.Failed,
                    ),
                    withTimeout(1_000L) { completion.await() },
                )
                fixture.close()
            }
        }

    @Test
    fun `opened and failed share publish their exact terminal status once`() = runBlocking {
        suspend fun verify(result: TravelVideoShareResult, expectedFailures: Int) {
            val failures = AtomicInteger()
            val completions = mutableListOf<WebTravelShareCompletionPayload>()
            val completionPublished = CompletableDeferred<Unit>()
            val fixture = coordinator(
                resolver = { TravelVideoShareLookupResult.Ready(readyAsset()) },
                sharer = { result },
                presentFailure = failures::incrementAndGet,
                publishCompletion = WebTravelShareCompletionPublisher { _, payload ->
                    completions += payload
                    completionPublished.complete(Unit)
                },
            )

            assertSame(WebTravelShareAcceptResult.Accepted, fixture.coordinator.acceptKey(RequestKey))
            withTimeout(1_000L) { completionPublished.await() }
            assertEquals(expectedFailures, failures.get())
            assertEquals(
                listOf(
                    WebTravelShareCompletionPayload(
                        RequestKey,
                        if (result == TravelVideoShareResult.Opened) {
                            WebTravelShareCompletionStatus.Opened
                        } else {
                            WebTravelShareCompletionStatus.Failed
                        },
                    ),
                ),
                completions,
            )
            fixture.close()
        }

        verify(TravelVideoShareResult.Opened, expectedFailures = 0)
        verify(TravelVideoShareResult.Failed, expectedFailures = 1)
    }

    @Test
    fun `parallel duplicate is coalesced and key is released after completion`() = runBlocking {
        val resolverCalls = AtomicInteger()
        val failures = AtomicInteger()
        val resolverStarted = CompletableDeferred<Unit>()
        val completions = mutableListOf<Pair<BridgeDocumentFence, WebTravelShareCompletionPayload>>()
        var gate = CompletableDeferred<Unit>()
        val fixture = coordinator(
            resolver = {
                resolverCalls.incrementAndGet()
                resolverStarted.complete(Unit)
                gate.await()
                TravelVideoShareLookupResult.Missing
            },
            sharer = { error("share must not run") },
            presentFailure = failures::incrementAndGet,
            publishCompletion = WebTravelShareCompletionPublisher { fence, payload ->
                completions += fence to payload
            },
        )

        assertSame(WebTravelShareAcceptResult.Accepted, fixture.coordinator.acceptKey(RequestKey))
        assertSame(WebTravelShareAcceptResult.Accepted, fixture.coordinator.acceptKey(RequestKey))
        assertSame(
            WebTravelShareAcceptResult.Accepted,
            fixture.coordinator.acceptKey(RequestKey, documentEpoch = 2L),
        )
        withTimeout(1_000L) { resolverStarted.await() }
        assertEquals(1, resolverCalls.get())

        gate.complete(Unit)
        withTimeout(1_000L) {
            while (completions.size < 2) kotlinx.coroutines.yield()
        }
        assertEquals(1, failures.get())
        assertEquals(
            listOf(BridgeDocumentFence(1L), BridgeDocumentFence(2L)),
            completions.map { it.first },
        )
        assertTrue(completions.all {
            it.second == WebTravelShareCompletionPayload(
                RequestKey,
                WebTravelShareCompletionStatus.Failed,
            )
        })
        gate = CompletableDeferred()

        assertSame(
            WebTravelShareAcceptResult.Accepted,
            fixture.coordinator.acceptKey(RequestKey, documentEpoch = 3L),
        )
        withTimeout(1_000L) {
            while (resolverCalls.get() < 2) kotlinx.coroutines.yield()
        }
        assertEquals(2, resolverCalls.get())
        gate.complete(Unit)
        withTimeout(1_000L) {
            while (completions.size < 3) kotlinx.coroutines.yield()
        }
        assertEquals(2, failures.get())
        assertEquals(BridgeDocumentFence(3L), completions.last().first)
        fixture.close()
    }

    @Test
    fun `close cancels suspended sharing without failure and rejects later work`() = runBlocking {
        val shareStarted = CompletableDeferred<Unit>()
        val shareCancelled = CompletableDeferred<Unit>()
        val failures = AtomicInteger()
        val fixture = coordinator(
            resolver = { TravelVideoShareLookupResult.Ready(readyAsset()) },
            sharer = {
                shareStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    shareCancelled.complete(Unit)
                }
            },
            presentFailure = failures::incrementAndGet,
        )

        assertSame(WebTravelShareAcceptResult.Accepted, fixture.coordinator.acceptKey(RequestKey))
        withTimeout(1_000L) { shareStarted.await() }
        fixture.coordinator.close()
        withTimeout(1_000L) { shareCancelled.await() }

        assertEquals(0, failures.get())
        assertSame(WebTravelShareAcceptResult.Invalid, fixture.coordinator.acceptKey(RequestKey))
        fixture.close()
    }

    private fun coordinator(
        resolver: suspend (String) -> TravelVideoShareLookupResult,
        sharer: suspend (LocalTravelVideoAsset) -> TravelVideoShareResult,
        presentFailure: () -> Unit = {},
        publishCompletion: WebTravelShareCompletionPublisher =
            WebTravelShareCompletionPublisher { _, _ -> },
    ): Fixture {
        val parentScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        return Fixture(
            parentScope = parentScope,
            coordinator = WebTravelShareCoordinator(
                scope = parentScope,
                resolver = resolver,
                sharer = sharer,
                presentFailure = presentFailure,
                publishCompletion = publishCompletion,
            ),
        )
    }

    private fun WebTravelShareCoordinator.acceptKey(
        requestKey: String?,
        documentEpoch: Long = 1L,
    ): WebTravelShareAcceptResult = accept(
        WebTravelShareRequest(requestKey, BridgeDocumentFence(documentEpoch)),
    )

    private fun readyAsset() = LocalTravelVideoAsset(
        ownerId = "owner-current",
        petId = "pet-current",
        requestKey = RequestKey,
        backendJobId = "travel-video-prototype-${"a".repeat(32)}",
        prompt = "Полететь к морю",
        title = "Море",
        scenario = null,
        imageUrl = null,
        videoUrl = "https://gigagochi.test/static/travel.mp4",
        completedAtEpochMillis = 100L,
        consumedAtEpochMillis = 101L,
    )

    private data class Fixture(
        val parentScope: CoroutineScope,
        val coordinator: WebTravelShareCoordinator,
    ) {
        fun close() {
            coordinator.close()
            parentScope.cancel()
        }
    }

    private companion object {
        const val RequestKey = "123e4567-e89b-42d3-a456-426614174000"
    }
}
