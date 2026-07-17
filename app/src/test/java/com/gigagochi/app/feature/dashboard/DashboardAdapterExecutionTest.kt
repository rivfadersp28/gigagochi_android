package com.gigagochi.app.feature.dashboard

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardAdapterExecutionTest {
    @Test(expected = CancellationException::class)
    fun cancellationIsNotConvertedToUiFailure() {
        runBlocking {
            executeDashboardAdapter<String> { throw CancellationException("route closed") }
        }
    }

    @Test
    fun productionKeyBoundaryUsesInjectedStableIdExactlyOnce() {
        var calls = 0
        val key = durableDashboardRequestKey("outfit") {
            calls += 1
            "123e4567-e89b-42d3-a456-426614174000"
        }

        assertEquals("123e4567-e89b-42d3-a456-426614174000", key)
        assertEquals(1, calls)
    }
}
