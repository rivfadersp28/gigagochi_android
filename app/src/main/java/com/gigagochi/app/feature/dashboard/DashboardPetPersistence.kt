package com.gigagochi.app.feature.dashboard

import com.gigagochi.app.core.model.PetDashboardState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

const val DashboardSaveMaxAttempts = 2
const val DashboardSaveRetryDelayMillis = 750L

class DashboardPetPersistenceCoordinator(
    private val save: suspend (PetDashboardState) -> Boolean,
    private val retryDelay: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun persist(pet: PetDashboardState): Boolean {
        repeat(DashboardSaveMaxAttempts) { attempt ->
            val saved = try {
                save(pet)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            if (saved) return true
            if (attempt + 1 < DashboardSaveMaxAttempts) {
                retryDelay(DashboardSaveRetryDelayMillis)
            }
        }
        return false
    }
}
