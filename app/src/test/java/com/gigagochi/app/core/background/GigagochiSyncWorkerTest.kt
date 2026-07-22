package com.gigagochi.app.core.background

import com.gigagochi.app.core.database.LocalPendingOutfit
import com.gigagochi.app.core.database.LocalPendingTravelVideo
import com.gigagochi.app.core.database.OwnerRecoveryData
import com.gigagochi.app.core.database.PendingBackendState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GigagochiSyncWorkerTest {
    @Test
    fun attachedAndReadyManualGenerationsKeepOneShotSyncRetrying() {
        assertTrue(
            recovery(
                outfit = outfit(PendingBackendState.Attached, backendJobId = "job-outfit"),
            ).hasPendingManualCompletion(PetId),
        )
        assertTrue(
            recovery(
                travel = travel(PendingBackendState.Ready, backendJobId = "job-travel"),
            ).hasPendingManualCompletion(PetId),
        )
    }

    @Test
    fun retryableUnattachedGenerationIsRetriedButTerminalOrUnsafeRowsAreNot() {
        assertTrue(
            recovery(
                outfit = outfit(PendingBackendState.Retryable),
            ).hasPendingManualCompletion(PetId),
        )
        assertFalse(
            recovery(
                outfit = outfit(PendingBackendState.Failed, backendJobId = "job-outfit"),
            ).hasPendingManualCompletion(PetId),
        )
        assertFalse(
            recovery(
                outfit = outfit(PendingBackendState.OutcomeUnknown),
            ).hasPendingManualCompletion(PetId),
        )
        assertFalse(
            recovery(
                travel = travel(PendingBackendState.Attached, backendJobId = "job-other")
                    .copy(petId = "other-pet"),
            ).hasPendingManualCompletion(PetId),
        )
    }

    private fun recovery(
        outfit: LocalPendingOutfit? = null,
        travel: LocalPendingTravelVideo? = null,
    ) = OwnerRecoveryData(
        petSnapshots = emptyList(),
        pendingCreates = emptyList(),
        pendingOutfits = listOfNotNull(outfit),
        pendingTravels = listOfNotNull(travel),
        storyReceipts = emptyList(),
    )

    private fun outfit(
        state: PendingBackendState,
        backendJobId: String? = null,
    ) = LocalPendingOutfit(
        ownerId = "owner-a",
        petId = PetId,
        requestKey = "outfit-key",
        localJobId = "local-outfit",
        backendJobId = backendJobId,
        prompt = "Футболка Mayhem",
        baseAssetSetId = "asset-a",
        acceptedAtEpochMillis = 1,
        backendState = state,
    )

    private fun travel(
        state: PendingBackendState,
        backendJobId: String? = null,
    ) = LocalPendingTravelVideo(
        ownerId = "owner-a",
        petId = PetId,
        requestKey = "travel-key",
        localJobId = "local-travel",
        backendJobId = backendJobId,
        prompt = "На концерт",
        acceptedAtEpochMillis = 1,
        backendState = state,
    )

    private companion object {
        const val PetId = "pet-a"
    }
}
