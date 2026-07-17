package com.gigagochi.app.feature.travel

import kotlinx.coroutines.CancellationException

sealed interface TravelAdapterResult<out T> {
    data class Success<T>(val value: T) : TravelAdapterResult<T>
    data object Failure : TravelAdapterResult<Nothing>
}

suspend fun <T> executeTravelAdapter(block: suspend () -> T): TravelAdapterResult<T> = try {
    TravelAdapterResult.Success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    TravelAdapterResult.Failure
}
