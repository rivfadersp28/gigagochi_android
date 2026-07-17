package com.gigagochi.app.feature.travel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Test

class TravelAdapterExecutionTest {
    @Test(expected = CancellationException::class)
    fun cancellationIsNotConvertedForAnyTravelAdapterBoundary() {
        runBlocking {
            executeTravelAdapter<String> { throw CancellationException("route closed") }
        }
    }

}
