package com.gigagochi.app.core.webview

import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.Serializable

@Serializable
internal data class WebNavigationReadyPayload(
    val canHandleBack: Boolean,
    val sequence: Long,
)

@Serializable
internal data class WebSystemBackPayload(
    val navigationSequence: Long,
)

internal sealed interface WebSystemBackDecision {
    data object Unhandled : WebSystemBackDecision

    data object DuplicateConsumed : WebSystemBackDecision

    data class Dispatch(
        val eventId: String,
        val navigationSequence: Long,
        val documentFence: BridgeDocumentFence,
    ) : WebSystemBackDecision
}

internal data class PendingWebSystemBack(
    val eventId: String,
    val navigationSequence: Long,
    val documentFence: BridgeDocumentFence,
)

internal class WebSystemBackHostCoordinator(
    private val requestSystemBack: () -> WebSystemBackDecision,
    private val enqueue: (PendingWebSystemBack) -> Boolean,
    private val releaseSystemBack: (String) -> Unit,
) {
    fun handleSystemBack(): Boolean = when (val decision = requestSystemBack()) {
        WebSystemBackDecision.Unhandled -> false
        WebSystemBackDecision.DuplicateConsumed -> true
        is WebSystemBackDecision.Dispatch -> {
            val pending = PendingWebSystemBack(
                eventId = decision.eventId,
                navigationSequence = decision.navigationSequence,
                documentFence = decision.documentFence,
            )
            enqueue(pending).also { queued ->
                if (!queued) releaseSystemBack(decision.eventId)
            }
        }
    }
}

/**
 * Synchronous document-scoped state used from Activity's main-thread Back callback.
 * Bridge updates still pass through [BridgeDispatcher]'s session validation first.
 */
internal class WebNavigationBackController {
    private val state = AtomicReference<State>(State.Unavailable(documentEpoch = 0L))

    fun invalidate(documentEpoch: Long) {
        state.set(State.Unavailable(documentEpoch))
    }

    fun update(
        documentFence: BridgeDocumentFence,
        payload: WebNavigationReadyPayload,
    ) {
        require(payload.sequence >= 0L)
        while (true) {
            val current = state.get()
            if (current.documentEpoch != documentFence.epoch) return
            if (payload.sequence <= current.navigationSequence) return
            val next = if (payload.canHandleBack) {
                State.Ready(documentFence.epoch, payload.sequence)
            } else {
                State.NotReady(documentFence.epoch, payload.sequence)
            }
            if (state.compareAndSet(current, next)) return
        }
    }

    fun requestSystemBack(documentFence: BridgeDocumentFence): WebSystemBackDecision {
        while (true) {
            val current = state.get()
            if (current.documentEpoch != documentFence.epoch) {
                return WebSystemBackDecision.Unhandled
            }
            when (current) {
                is State.Unavailable,
                is State.NotReady,
                -> return WebSystemBackDecision.Unhandled

                is State.InFlight -> return WebSystemBackDecision.DuplicateConsumed
                is State.Ready -> {
                    val eventId = UUID.randomUUID().toString()
                    val inFlight = State.InFlight(
                        documentEpoch = current.documentEpoch,
                        navigationSequence = current.navigationSequence,
                        eventId = eventId,
                    )
                    if (state.compareAndSet(current, inFlight)) {
                        return WebSystemBackDecision.Dispatch(
                            eventId = eventId,
                            navigationSequence = current.navigationSequence,
                            documentFence = documentFence,
                        )
                    }
                }
            }
        }
    }

    fun releaseSystemBack(eventId: String) {
        while (true) {
            val current = state.get()
            if (current !is State.InFlight || current.eventId != eventId) return
            val ready = State.Ready(
                documentEpoch = current.documentEpoch,
                navigationSequence = current.navigationSequence,
            )
            if (state.compareAndSet(current, ready)) return
        }
    }

    private sealed interface State {
        val documentEpoch: Long
        val navigationSequence: Long

        data class Unavailable(
            override val documentEpoch: Long,
        ) : State {
            override val navigationSequence: Long = -1L
        }

        data class NotReady(
            override val documentEpoch: Long,
            override val navigationSequence: Long,
        ) : State

        data class Ready(
            override val documentEpoch: Long,
            override val navigationSequence: Long,
        ) : State

        data class InFlight(
            override val documentEpoch: Long,
            override val navigationSequence: Long,
            val eventId: String,
        ) : State
    }
}
