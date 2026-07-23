package com.gigagochi.app.core.webview

import com.gigagochi.app.core.database.LocalTravelVideoAsset
import com.gigagochi.app.core.security.TravelVideoShareLookupResult
import com.gigagochi.app.core.security.canonicalTravelRequestKeyOrNull
import com.gigagochi.app.feature.events.TravelVideoShareResult
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.encodeToJsonElement

/** Strict payload for the `shareTravelVideo` bridge method. */
@Serializable
internal data class WebTravelSharePayload(
    val requestKey: String,
)

internal sealed interface WebTravelShareAcceptResult {
    data object Accepted : WebTravelShareAcceptResult
    data object Invalid : WebTravelShareAcceptResult
}

internal data class WebTravelShareRequest(
    val requestKey: String?,
    val documentFence: BridgeDocumentFence,
)

internal fun interface WebTravelShareRequestHandler {
    fun accept(request: WebTravelShareRequest): WebTravelShareAcceptResult
}

@Serializable
internal enum class WebTravelShareCompletionStatus {
    @SerialName("opened")
    Opened,

    @SerialName("failed")
    Failed,
}

@Serializable
internal data class WebTravelShareCompletionPayload(
    val requestKey: String,
    val status: WebTravelShareCompletionStatus,
)

internal fun webTravelShareCompletedEvent(
    payload: WebTravelShareCompletionPayload,
): WebAppRuntimeEvent = WebAppRuntimeEvent(
    type = "travelShareCompleted",
    payload = BridgeCodec.json.encodeToJsonElement(
        WebTravelShareCompletionPayload.serializer(),
        payload,
    ),
)

internal fun interface WebTravelShareCompletionPublisher {
    fun publish(
        documentFence: BridgeDocumentFence,
        payload: WebTravelShareCompletionPayload,
    )
}

/**
 * Moves share lookup/download work out of the synchronous bridge request path.
 *
 * The resolver remains the authority for the current native owner and active pet. This class only
 * validates the untrusted request key, deduplicates concurrent requests and owns the launched work.
 */
internal class WebTravelShareCoordinator(
    scope: CoroutineScope,
    private val resolver: suspend (String) -> TravelVideoShareLookupResult,
    private val sharer: suspend (LocalTravelVideoAsset) -> TravelVideoShareResult,
    private val presentFailure: () -> Unit,
    private val publishCompletion: WebTravelShareCompletionPublisher =
        WebTravelShareCompletionPublisher { _, _ -> },
) : WebTravelShareRequestHandler, AutoCloseable {
    private val lock = Any()
    private val coordinatorJob = SupervisorJob(scope.coroutineContext[Job])
    private val coordinatorScope = CoroutineScope(scope.coroutineContext.minusKey(Job) + coordinatorJob)
    private val inFlight = mutableMapOf<String, InFlightShare>()
    private val closed = AtomicBoolean(false)

    override fun accept(request: WebTravelShareRequest): WebTravelShareAcceptResult {
        val canonical = canonicalTravelRequestKeyOrNull(request.requestKey)
            ?: return WebTravelShareAcceptResult.Invalid
        val inFlightShare = synchronized(lock) {
            if (closed.get() || !coordinatorJob.isActive) {
                return WebTravelShareAcceptResult.Invalid
            }
            inFlight[canonical]?.let { existing ->
                existing.documentFences += request.documentFence
                return WebTravelShareAcceptResult.Accepted
            }
            InFlightShare(mutableSetOf(request.documentFence)).also { created ->
                inFlight[canonical] = created
            }
        }

        val job = coordinatorScope.launch {
            // Even an immediate dispatcher must leave the synchronous bridge/mutex turn first.
            yield()
            process(canonical, inFlightShare)
        }
        job.invokeOnCompletion {
            synchronized(lock) {
                if (inFlight[canonical] === inFlightShare) inFlight.remove(canonical)
            }
        }
        return WebTravelShareAcceptResult.Accepted
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        coordinatorJob.cancel()
        synchronized(lock) {
            inFlight.clear()
        }
    }

    private suspend fun process(
        requestKey: String,
        inFlightShare: InFlightShare,
    ) {
        val status = try {
            when (val lookup = resolver(requestKey)) {
                TravelVideoShareLookupResult.Invalid,
                TravelVideoShareLookupResult.Missing,
                TravelVideoShareLookupResult.NotReady,
                -> WebTravelShareCompletionStatus.Failed
                is TravelVideoShareLookupResult.Ready -> when (sharer(lookup.asset)) {
                    TravelVideoShareResult.Opened -> WebTravelShareCompletionStatus.Opened
                    TravelVideoShareResult.Failed -> WebTravelShareCompletionStatus.Failed
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            WebTravelShareCompletionStatus.Failed
        }
        complete(requestKey, inFlightShare, status)
    }

    private fun complete(
        requestKey: String,
        inFlightShare: InFlightShare,
        status: WebTravelShareCompletionStatus,
    ) {
        val documentFences = synchronized(lock) {
            if (closed.get() || inFlight[requestKey] !== inFlightShare) return
            inFlight.remove(requestKey)
            inFlightShare.documentFences.toList()
        }
        if (status == WebTravelShareCompletionStatus.Failed) {
            runCatching(presentFailure)
        }
        val payload = WebTravelShareCompletionPayload(requestKey, status)
        documentFences.forEach { documentFence ->
            runCatching { publishCompletion.publish(documentFence, payload) }
        }
    }

    private data class InFlightShare(
        val documentFences: MutableSet<BridgeDocumentFence>,
    )
}
