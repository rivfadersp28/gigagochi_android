package com.gigagochi.app.feature.create

import com.gigagochi.app.core.database.LocalOperationResult
import com.gigagochi.app.core.database.LocalPendingCreateGeneration
import com.gigagochi.app.core.database.OwnerRecoveryData
import com.gigagochi.app.core.database.PendingBackendState
import com.gigagochi.app.core.database.TestOwnerRecoveryStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CreatePendingCoordinatorTest {
    @Test
    fun progressUpdatesSamePendingAndRestoreKeepsIdentity() = runBlocking {
        val store = Store()
        val coordinator = CreatePendingCoordinator("owner-a", store, nowEpochMillis = { 10 })
        val first = CreatePetState().answer(
            "Ледяной дракон",
            requestKeyFactory = { "request-stable" },
            petIdFactory = { "pet-stable" },
        )
        assertTrue(coordinator.persist(first) is LocalOperationResult.Success)

        val second = first.answer("Тото")
        assertTrue(coordinator.persist(second) is LocalOperationResult.Success)

        assertEquals(1, store.pending.size)
        assertEquals("request-stable", store.pending.single().requestKey)
        assertEquals("pet-stable", store.pending.single().petId)
        assertEquals("Тото", store.pending.single().name)
        val restored = coordinator.restore(store.pending.single())
        assertEquals("request-stable", restored.pending?.requestKey)
        assertEquals("pet-stable", restored.pending?.petId)
        assertEquals(2, restored.step)
        assertTrue(restored.generation is GenerationStatus.Error)
    }

    @Test
    fun finalRevisionFailureBlocksGenerationUntilRetryWithoutChangingIdentity() = runBlocking {
        val store = Store()
        val coordinator = CreatePendingCoordinator("owner-a", store, nowEpochMillis = { 10 })
        val adapterRequests = mutableListOf<PendingPetGeneration>()
        val adapter = object : PetGenerationAdapter {
            override suspend fun generate(request: PendingPetGeneration): GeneratedPetFixture {
                adapterRequests += request
                return GeneratedPetFixture(request.description, request.petId)
            }
        }
        val first = CreatePetState().answer(
            "Ледяной дракон",
            requestKeyFactory = { "request-stable" },
            petIdFactory = { "pet-stable" },
        )
        assertTrue(coordinator.persist(first) is LocalOperationResult.Success)
        var persistedRevision = first.pendingRevision()

        val final = listOf("Тото", "Добрый", "Пауков", "Вантуз")
            .fold(first) { state, answer -> state.answer(answer) }
        store.failWrites = true
        assertEquals(LocalOperationResult.Failure, coordinator.persist(final))
        assertNull(
            executePetGenerationIfCurrentRevisionPersisted(adapter, final, persistedRevision),
        )
        assertEquals(0, adapterRequests.size)

        store.failWrites = false
        assertTrue(coordinator.persist(final) is LocalOperationResult.Success)
        persistedRevision = final.pendingRevision()
        assertTrue(
            executePetGenerationIfCurrentRevisionPersisted(adapter, final, persistedRevision) is
                PetGenerationExecutionResult.Success,
        )
        assertEquals(1, adapterRequests.size)
        assertEquals("request-stable", adapterRequests.single().requestKey)
        assertEquals("pet-stable", adapterRequests.single().petId)
    }

    @Test
    fun laterAnswersCannotResetAttachedOrOutcomeState() = runBlocking {
        val store = Store()
        val coordinator = CreatePendingCoordinator("owner-a", store)
        val first = CreatePetState().answer(
            "Ледяной дракон",
            requestKeyFactory = { "request-stable" },
            petIdFactory = { "pet-stable" },
        )
        coordinator.persist(first)
        store.pending[0] = store.pending.single().copy(
            backendJobId = "job-safe",
            backendState = PendingBackendState.OutcomeUnknown,
            backendErrorCode = "OUTCOME_UNKNOWN",
        )

        coordinator.persist(first.answer("Тото"))

        assertEquals("job-safe", store.pending.single().backendJobId)
        assertEquals(PendingBackendState.OutcomeUnknown, store.pending.single().backendState)
        assertEquals("OUTCOME_UNKNOWN", store.pending.single().backendErrorCode)
    }

    @Test
    fun finalAttachedPendingRestoresAsRunningForPollWithoutNewSubmitIdentity() {
        val pending = LocalPendingCreateGeneration(
            "owner-a",
            "pet-stable",
            "request-stable",
            "job-safe",
            com.gigagochi.app.core.database.PendingCreateStage.Generating,
            "Ледяной дракон",
            "Тото",
            "Добрый",
            "Пауков",
            "Вантуз",
            FinalCreationStep,
            10,
            PendingBackendState.Attached,
        )

        val restored = CreatePendingCoordinator("owner-a", Store()).restore(pending)

        assertTrue(restored.generation is GenerationStatus.Running)
        assertEquals(1, restored.generationAttempt)
        assertEquals("request-stable", restored.pending?.requestKey)
        assertEquals("pet-stable", restored.pending?.petId)
    }

    @Test
    fun terminalRetryReplacesFailedPendingWithFreshRequestKey() = runBlocking {
        val store = Store()
        val coordinator = CreatePendingCoordinator("owner-a", store, nowEpochMillis = { 20 })
        val failedState = CreatePetState().answer(
            "Ледяной дракон",
            requestKeyFactory = { "failed-request" },
            petIdFactory = { "pet-stable" },
        ).markGenerationFailed(newRequestRequired = true)
        coordinator.persist(failedState)
        store.pending[0] = store.pending.single().copy(
            backendJobId = "failed-job",
            backendState = PendingBackendState.Failed,
            backendErrorCode = "GENERATION_FAILED",
        )

        val retried = failedState.retryGeneration { "retry-request" }
        assertTrue(coordinator.persist(retried) is LocalOperationResult.Success)

        assertEquals(1, store.pending.size)
        assertEquals("retry-request", store.pending.single().requestKey)
        assertEquals("pet-stable", store.pending.single().petId)
        assertNull(store.pending.single().backendJobId)
        assertEquals(PendingBackendState.Pending, store.pending.single().backendState)
    }

    private class Store : TestOwnerRecoveryStore() {
        val pending = mutableListOf<LocalPendingCreateGeneration>()
        var failWrites = false

        override suspend fun loadOwnerRecovery(ownerId: String) = OwnerRecoveryData(
            emptyList(), pending.filter { it.ownerId == ownerId }, emptyList(), emptyList(), emptyList(),
        )

        override suspend fun savePendingCreate(pending: LocalPendingCreateGeneration) {
            if (failWrites) error("database unavailable")
            this.pending.removeAll {
                it.ownerId == pending.ownerId && it.requestKey == pending.requestKey
            }
            this.pending += pending
        }

        override suspend fun replaceFailedPendingCreate(
            ownerId: String,
            failedRequestKey: String,
            replacement: LocalPendingCreateGeneration,
        ): Boolean {
            val failed = pending.singleOrNull {
                it.ownerId == ownerId &&
                    it.requestKey == failedRequestKey &&
                    it.petId == replacement.petId &&
                    it.backendState == PendingBackendState.Failed
            } ?: return false
            pending.remove(failed)
            pending += replacement
            return true
        }
    }
}
