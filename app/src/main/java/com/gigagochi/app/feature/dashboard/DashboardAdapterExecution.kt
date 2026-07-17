package com.gigagochi.app.feature.dashboard

import java.util.UUID
import kotlinx.coroutines.CancellationException

sealed interface DashboardAdapterResult<out T> {
    data class Success<T>(val value: T) : DashboardAdapterResult<T>
    data object Failure : DashboardAdapterResult<Nothing>
}

suspend fun <T> executeDashboardAdapter(block: suspend () -> T): DashboardAdapterResult<T> = try {
    DashboardAdapterResult.Success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    DashboardAdapterResult.Failure
}

fun durableDashboardRequestKey(
    @Suppress("UNUSED_PARAMETER") prefix: String,
    idFactory: () -> String = { UUID.randomUUID().toString() },
): String {
    val value = idFactory()
    val uuid = UUID.fromString(value)
    require(uuid.version() == 4 && uuid.toString() == value.lowercase()) {
        "Production request key must be a canonical UUID v4"
    }
    return value.lowercase()
}
