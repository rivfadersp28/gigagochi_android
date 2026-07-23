package com.gigagochi.app.core.webview

import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebBridgeRequestIngressTest {
    @Test
    fun `ingress failures preserve bounded request correlation without parsing payload`() {
        val documentId = UUID.randomUUID().toString()
        val sessionId = UUID.randomUUID().toString()
        val requestId = UUID.randomUUID().toString()
        val raw = """
            {
              "kind":"request",
              "protocolVersion":1,
              "documentId":"$documentId",
              "bridgeSessionId":"$sessionId",
              "requestId":"$requestId",
              "method":"dispatch",
              "payload":{"message":"${"x".repeat(BridgeMaxMessageBytes)}"}
            }
        """.trimIndent()

        val response = BridgeCodec.json.decodeFromString<BridgeResponseEnvelope>(
            BridgeCodec.encodeIngressFailure(
                raw = raw,
                code = "PAYLOAD_TOO_LARGE",
                retryable = false,
            ),
        )

        assertEquals(documentId, response.documentId)
        assertEquals(sessionId, response.bridgeSessionId)
        assertEquals(requestId, response.requestId)
        assertEquals("PAYLOAD_TOO_LARGE", response.error?.code)
        assertEquals(false, response.error?.retryable)
    }

    @Test
    fun `ambiguous correlation prefix fails closed`() {
        val documentId = UUID.randomUUID().toString()
        val requestId = UUID.randomUUID().toString()
        val raw =
            """{"documentId":"$documentId","documentId":"$documentId","requestId":"$requestId"}"""

        val response = BridgeCodec.json.decodeFromString<BridgeResponseEnvelope>(
            BridgeCodec.encodeIngressFailure(raw, "RATE_LIMITED", retryable = true),
        )

        assertEquals("", response.documentId)
        assertEquals("", response.requestId)
        assertEquals("RATE_LIMITED", response.error?.code)
    }

    @Test
    fun `bounded queue has one consumer and overflow attempts still consume admission tokens`() =
        runBlocking {
            val firstStarted = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val handled = mutableListOf<String>()
            val replies = mutableListOf<String>()
            val ingress = ingress(
                capacity = 1,
                burst = 4,
                handle = { pending ->
                    handled += pending.raw
                    if (pending.raw == "active") {
                        firstStarted.complete(Unit)
                        releaseFirst.await()
                    }
                    "response:${pending.raw}"
                },
                reply = { _, response -> replies += response },
            )

            assertEquals(WebBridgeIngressAdmission.Accepted, ingress.offer("active", Unit, 0L))
            firstStarted.await()
            assertEquals(WebBridgeIngressAdmission.Accepted, ingress.offer("queued", Unit, 0L))
            assertEquals(WebBridgeIngressAdmission.QueueFull, ingress.offer("overflow-1", Unit, 0L))
            assertEquals(WebBridgeIngressAdmission.QueueFull, ingress.offer("overflow-2", Unit, 0L))
            assertEquals(WebBridgeIngressAdmission.RateLimited, ingress.offer("limited", Unit, 0L))

            ingress.close()
            releaseFirst.complete(Unit)
            assertEquals(listOf("active"), handled)
            assertTrue(replies.isEmpty())
        }

    @Test
    fun `malformed and oversized strings are rate counted before any decoder`() = runBlocking {
        val handled = mutableListOf<String>()
        val malformedHandled = CompletableDeferred<Unit>()
        val ingress = ingress(
            capacity = 4,
            burst = 2,
            handle = { pending ->
                handled += pending.raw
                malformedHandled.complete(Unit)
                "ignored"
            },
        )
        val malformed = "{"
        val oversized = "x".repeat(BridgeMaxMessageBytes + 1)

        assertEquals(WebBridgeIngressAdmission.Accepted, ingress.offer(malformed, Unit, 0L))
        assertEquals(
            WebBridgeIngressAdmission.PayloadTooLarge,
            ingress.offer(oversized, Unit, 0L),
        )
        assertEquals(
            WebBridgeIngressAdmission.RateLimited,
            ingress.offer("still-not-json", Unit, 0L),
        )
        withTimeout(1_000L) { malformedHandled.await() }

        assertEquals(listOf(malformed), handled)
        ingress.close()
    }

    @Test
    fun `page generation rotation cannot reset the host lifetime bucket`() = runBlocking {
        var generation = 0L
        val ingress = ingress(
            generation = { generation },
            capacity = 4,
            burst = 2,
        )

        assertEquals(WebBridgeIngressAdmission.Accepted, ingress.offer("one", Unit, generation))
        assertEquals(WebBridgeIngressAdmission.Accepted, ingress.offer("two", Unit, generation))
        generation += 1L

        assertEquals(
            WebBridgeIngressAdmission.RateLimited,
            ingress.offer("after-reload", Unit, generation),
        )
        ingress.close()
    }

    @Test
    fun `queued old generation is dropped before handling and active old reply is fenced`() =
        runBlocking {
            var generation = 0L
            val firstStarted = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val currentHandled = CompletableDeferred<Unit>()
            val currentReplied = CompletableDeferred<Unit>()
            val handled = mutableListOf<String>()
            val replies = mutableListOf<String>()
            val ingress = ingress(
                generation = { generation },
                capacity = 4,
                burst = 8,
                handle = { pending ->
                    handled += pending.raw
                    when (pending.raw) {
                        "active-old" -> {
                            firstStarted.complete(Unit)
                            releaseFirst.await()
                        }

                        "current" -> currentHandled.complete(Unit)
                    }
                    "response:${pending.raw}"
                },
                reply = { _, response ->
                    replies += response
                    if (response == "response:current") currentReplied.complete(Unit)
                },
            )

            assertEquals(
                WebBridgeIngressAdmission.Accepted,
                ingress.offer("active-old", Unit, generation),
            )
            firstStarted.await()
            assertEquals(
                WebBridgeIngressAdmission.Accepted,
                ingress.offer("queued-old", Unit, generation),
            )
            generation += 1L
            releaseFirst.complete(Unit)
            assertEquals(
                WebBridgeIngressAdmission.Accepted,
                ingress.offer("current", Unit, generation),
            )
            withTimeout(1_000L) { currentHandled.await() }
            withTimeout(1_000L) { currentReplied.await() }

            assertEquals(listOf("active-old", "current"), handled)
            assertEquals(listOf("response:current"), replies)
            ingress.close()
        }

    @Test
    fun `close drains queued raw messages and rejects future offers without side effects`() =
        runBlocking {
            val firstStarted = CompletableDeferred<Unit>()
            val neverReleased = CompletableDeferred<Unit>()
            val handled = mutableListOf<String>()
            val replies = mutableListOf<String>()
            val ingress = ingress(
                capacity = 2,
                burst = 8,
                handle = { pending ->
                    handled += pending.raw
                    firstStarted.complete(Unit)
                    neverReleased.await()
                    "response"
                },
                reply = { _, response -> replies += response },
            )

            assertEquals(WebBridgeIngressAdmission.Accepted, ingress.offer("active", Unit, 0L))
            firstStarted.await()
            assertEquals(WebBridgeIngressAdmission.Accepted, ingress.offer("queued-1", Unit, 0L))
            assertEquals(WebBridgeIngressAdmission.Accepted, ingress.offer("queued-2", Unit, 0L))

            ingress.close()

            assertEquals(WebBridgeIngressAdmission.Closed, ingress.offer("late", Unit, 0L))
            assertEquals(listOf("active"), handled)
            assertTrue(replies.isEmpty())
        }

    private fun CoroutineScope.ingress(
        generation: () -> Long = { 0L },
        capacity: Int,
        burst: Int,
        handle: suspend (PendingWebBridgeRequest<Unit>) -> String = { "response" },
        reply: (PendingWebBridgeRequest<Unit>, String) -> Unit = { _, _ -> },
    ): WebBridgeRequestIngress<Unit> = WebBridgeRequestIngress(
        scope = this,
        currentGeneration = generation,
        handleRequest = handle,
        postResponse = reply,
        monotonicClock = FixedBridgeClock,
        capacity = capacity,
        rateLimitBurst = burst,
        rateLimitSustainedPerSecond = 1,
    )

    private object FixedBridgeClock : BridgeMonotonicClock {
        override fun nowNanos(): Long = 0L
    }
}
