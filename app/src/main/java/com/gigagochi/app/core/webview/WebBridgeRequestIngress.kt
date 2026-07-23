package com.gigagochi.app.core.webview

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

internal const val WebBridgeRequestQueueCapacity = 64

internal data class PendingWebBridgeRequest<Reply>(
    val raw: String,
    val reply: Reply,
    val hostGeneration: Long,
)

internal enum class WebBridgeIngressAdmission {
    Accepted,
    RateLimited,
    PayloadTooLarge,
    QueueFull,
    Closed,
}

/**
 * Host-lifetime bridge ingress. It owns exactly one bounded queue and exactly one consumer.
 *
 * Admission consumes a token before enqueueing, so malformed and oversized strings are counted
 * without parsing. A reload only changes [currentGeneration]; it deliberately cannot replenish
 * this bucket. The host maps each rejection to a stable transport error and extracts only bounded
 * correlation metadata; no rejected payload reaches the product dispatcher.
 */
internal class WebBridgeRequestIngress<Reply>(
    scope: CoroutineScope,
    private val currentGeneration: () -> Long,
    private val handleRequest: suspend (PendingWebBridgeRequest<Reply>) -> String,
    private val postResponse: (PendingWebBridgeRequest<Reply>, String) -> Unit,
    monotonicClock: BridgeMonotonicClock,
    capacity: Int = WebBridgeRequestQueueCapacity,
    rateLimitBurst: Int = BridgeRateLimitBurst,
    rateLimitSustainedPerSecond: Int = BridgeRateLimitSustainedPerSecond,
) : AutoCloseable {
    init {
        require(capacity > 0)
    }

    private val closed = AtomicBoolean(false)
    private val requests = Channel<PendingWebBridgeRequest<Reply>>(capacity)
    private val rateLimiter = BridgeRequestRateLimiter(
        clock = monotonicClock,
        burst = rateLimitBurst,
        sustainedPerSecond = rateLimitSustainedPerSecond,
    )
    private val consumerJob: Job = scope.launch {
        for (pending in requests) process(pending)
    }.also { job ->
        job.invokeOnCompletion {
            closed.set(true)
            requests.close()
            drainQueuedRequests()
        }
    }

    fun offer(
        raw: String,
        reply: Reply,
        hostGeneration: Long,
    ): WebBridgeIngressAdmission {
        if (closed.get()) return WebBridgeIngressAdmission.Closed
        val hasAdmissionToken = rateLimiter.tryAcquire()
        // UTF-8 is never shorter than this UTF-16 code-unit count. This O(1) guard therefore
        // rejects definitely oversized strings without allocating an encoded copy. Exact byte
        // validation remains in BridgeCodec for bounded strings that contain multibyte codepoints.
        if (raw.length > BridgeMaxMessageBytes) {
            return WebBridgeIngressAdmission.PayloadTooLarge
        }
        if (!hasAdmissionToken) return WebBridgeIngressAdmission.RateLimited

        val result = requests.trySend(
            PendingWebBridgeRequest(
                raw = raw,
                reply = reply,
                hostGeneration = hostGeneration,
            ),
        )
        return when {
            result.isSuccess -> WebBridgeIngressAdmission.Accepted
            closed.get() || result.isClosed -> WebBridgeIngressAdmission.Closed
            else -> WebBridgeIngressAdmission.QueueFull
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        requests.close()
        consumerJob.cancel()
        drainQueuedRequests()
    }

    private suspend fun process(pending: PendingWebBridgeRequest<Reply>) {
        if (closed.get() || pending.hostGeneration != currentGeneration()) return
        val response = try {
            handleRequest(pending)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return
        }
        if (closed.get() || pending.hostGeneration != currentGeneration()) return
        runCatching { postResponse(pending, response) }
    }

    private fun drainQueuedRequests() {
        while (requests.tryReceive().isSuccess) Unit
    }
}
