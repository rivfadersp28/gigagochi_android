package com.gigagochi.app.core.webview

import org.junit.Assert.assertEquals
import org.junit.Test

class WebViewHostQaHooksTest {
    @Test
    fun `font readiness publishes ready only after document fonts promise marker settles`() {
        val scheduler = TestScheduler()
        val states = ArrayDeque(
            listOf(
                WebDocumentFontProbeState.Pending,
                WebDocumentFontProbeState.Ready,
            ),
        )
        val results = mutableListOf<Pair<WebDocumentFontReadiness, String?>>()
        val coordinator = coordinator(
            scheduler = scheduler,
            readState = { callback -> callback(states.removeFirst()) },
            publish = { result, code -> results += result to code },
        )

        coordinator.start()
        assertEquals(emptyList<Pair<WebDocumentFontReadiness, String?>>(), results)

        scheduler.advanceBy(32L)

        assertEquals(listOf(WebDocumentFontReadiness.Ready to null), results)
        scheduler.advanceBy(1_000L)
        assertEquals(1, results.size)
    }

    @Test
    fun `font readiness has a native timeout even when javascript callback never returns`() {
        val scheduler = TestScheduler()
        val results = mutableListOf<Pair<WebDocumentFontReadiness, String?>>()
        val coordinator = coordinator(
            scheduler = scheduler,
            timeoutMillis = 300L,
            readState = { _ -> Unit },
            publish = { result, code -> results += result to code },
        )

        coordinator.start()
        scheduler.advanceBy(299L)
        assertEquals(emptyList<Pair<WebDocumentFontReadiness, String?>>(), results)

        scheduler.advanceBy(1L)

        assertEquals(
            listOf(WebDocumentFontReadiness.TimedOut to "timeout"),
            results,
        )
    }

    @Test
    fun `font readiness reports rejected or unavailable document fonts as failure`() {
        val scheduler = TestScheduler()
        val results = mutableListOf<Pair<WebDocumentFontReadiness, String?>>()
        val coordinator = coordinator(
            scheduler = scheduler,
            readState = { callback -> callback(WebDocumentFontProbeState.Failed) },
            publish = { result, code -> results += result to code },
        )

        coordinator.start()

        assertEquals(
            listOf(
                WebDocumentFontReadiness.Failed to "document-fonts-ready-failed",
            ),
            results,
        )
    }

    @Test
    fun `superseded document terminates without being mistaken for screenshot readiness`() {
        val scheduler = TestScheduler()
        val results = mutableListOf<Pair<WebDocumentFontReadiness, String?>>()
        var current = true
        val coordinator = coordinator(
            scheduler = scheduler,
            isDocumentCurrent = { current },
            readState = { callback -> callback(WebDocumentFontProbeState.Pending) },
            publish = { result, code -> results += result to code },
        )

        coordinator.start()
        current = false
        scheduler.advanceBy(32L)

        assertEquals(
            listOf(
                WebDocumentFontReadiness.Superseded to "document-superseded",
            ),
            results,
        )
    }

    @Test
    fun `javascript result parser is closed and fails unknown values`() {
        assertEquals(
            WebDocumentFontProbeState.Pending,
            parseWebDocumentFontProbeState("\"pending\""),
        )
        assertEquals(
            WebDocumentFontProbeState.Ready,
            parseWebDocumentFontProbeState("\"ready\""),
        )
        listOf(null, "null", "\"failed\"", "\"unexpected\"", "").forEach { result ->
            assertEquals(
                result,
                WebDocumentFontProbeState.Failed,
                parseWebDocumentFontProbeState(result),
            )
        }
    }

    private fun coordinator(
        scheduler: TestScheduler,
        timeoutMillis: Long = 500L,
        readState: (callback: (WebDocumentFontProbeState) -> Unit) -> Unit,
        isDocumentCurrent: () -> Boolean = { true },
        publish: (WebDocumentFontReadiness, String?) -> Unit,
    ) = WebDocumentFontReadinessCoordinator(
        timeoutMillis = timeoutMillis,
        monotonicClockMillis = scheduler::now,
        schedule = scheduler::schedule,
        readState = readState,
        isDocumentCurrent = isDocumentCurrent,
        publish = publish,
    )

    private class TestScheduler {
        private data class ScheduledTask(
            val atMillis: Long,
            val order: Long,
            val task: () -> Unit,
        )

        private var currentMillis = 0L
        private var nextOrder = 0L
        private val tasks = mutableListOf<ScheduledTask>()

        fun now(): Long = currentMillis

        fun schedule(delayMillis: Long, task: () -> Unit) {
            tasks += ScheduledTask(
                atMillis = currentMillis + delayMillis,
                order = nextOrder++,
                task = task,
            )
        }

        fun advanceBy(durationMillis: Long) {
            val target = currentMillis + durationMillis
            while (true) {
                val next = tasks
                    .filter { it.atMillis <= target }
                    .minWithOrNull(compareBy(ScheduledTask::atMillis, ScheduledTask::order))
                    ?: break
                tasks.remove(next)
                currentMillis = next.atMillis
                next.task()
            }
            currentMillis = target
        }
    }
}
