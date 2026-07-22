package com.gigagochi.app.feature.create

import com.gigagochi.app.core.database.AccountPetLifecycle
import com.gigagochi.app.core.database.OwnedPetSnapshot
import com.gigagochi.app.core.database.PetSnapshotStore
import com.gigagochi.app.core.database.OwnerRecoveryStore
import com.gigagochi.app.core.database.TestOwnerRecoveryStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CreateFinalizationTest {
    @Test
    fun successfulFinalizationPersistsBeforeNavigationCanRun() = runBlocking {
        val events = mutableListOf<String>()
        val store = RecordingStore(events)
        val coordinator = CreateFinalizationCoordinator(
            ownerId = "acct_owner_a",
            lifecycle = AccountPetLifecycle(store, nowEpochMillis = { 55 }),
            store = store,
            nowEpochMillis = { 55 },
        )

        val result = coordinator.finalize(readyState())
        if (result is CreateFinalizationResult.Success) events += "navigate"

        assertTrue(result is CreateFinalizationResult.Success)
        assertEquals(listOf("persist:acct_owner_a:pet-real", "navigate"), events)
        assertEquals("Тото", store.persisted?.pet?.name)
        assertEquals(InitialPetStatValue, store.persisted?.pet?.hunger)
        assertEquals(InitialPetStatValue, store.persisted?.pet?.happiness)
        assertEquals(InitialPetStatValue, store.persisted?.pet?.energy)
        assertEquals(55L, store.persisted?.updatedAtEpochMillis)
    }

    @Test
    fun failedDatabaseWriteNeverAllowsNavigation() = runBlocking {
        val store = RecordingStore(mutableListOf(), fail = true)
        val result = CreateFinalizationCoordinator(
            "acct_owner_a",
            AccountPetLifecycle(store),
            store,
        ).finalize(readyState())

        assertEquals(CreateFinalizationResult.Failure, result)
    }

    @Test
    fun foregroundFinalizationKeepsPendingCreateForBackgroundRecovery() = runBlocking {
        val store = RecordingStore(mutableListOf())

        val result = CreateFinalizationCoordinator(
            "acct_owner_a",
            AccountPetLifecycle(store),
            store,
        ).finalize(readyState(backgroundPending = true))

        assertTrue(result is CreateFinalizationResult.Success)
        assertEquals(1, store.snapshotWrites)
        assertEquals(0, store.pendingDeletes)
    }

    @Test
    fun invalidStatePerformsNoSnapshotWriteOrPendingDelete() = runBlocking {
        val store = RecordingStore(mutableListOf())
        val result = CreateFinalizationCoordinator(
            "acct_owner_a",
            AccountPetLifecycle(store),
            store,
        ).finalize(readyState().copy(pending = null))

        assertEquals(CreateFinalizationResult.Failure, result)
        assertEquals(0, store.snapshotWrites)
        assertEquals(0, store.pendingDeletes)
    }

    @Test
    fun snapshotWinsStartupWhenDeleteFailsAfterSnapshotWrite() = runBlocking {
        val store = RecordingStore(mutableListOf(), failDelete = true)
        val coordinator = CreateFinalizationCoordinator(
            "acct_owner_a",
            AccountPetLifecycle(store),
            store,
        )

        assertEquals(CreateFinalizationResult.Failure, coordinator.finalize(readyState()))
        val startup = AccountPetLifecycle(store).startup("acct_owner_a")
        assertTrue(startup is com.gigagochi.app.core.database.AccountStartupDestination.Dashboard)
        assertEquals("pet-real", (startup as com.gigagochi.app.core.database.AccountStartupDestination.Dashboard).pet.petId)
    }

    private fun readyState(backgroundPending: Boolean = false) = CreatePetState(
        step = FinalCreationStep,
        answers = listOf("Ледяной дракон", "Тото", "Добрый", "Пауков", "Вантуз"),
        description = "Ледяной дракон",
        generation = GenerationStatus.Ready(
            GeneratedPetFixture(
                description = "Ледяной дракон",
                petId = "pet-real",
                assetSetId = "assets-real",
                backgroundGenerationPending = backgroundPending,
            ),
        ),
        pending = PendingPetGeneration(
            petId = "pet-real",
            description = "Ледяной дракон",
            requestKey = "create-request",
        ),
    )

    private class RecordingStore(
        private val events: MutableList<String>,
        private val fail: Boolean = false,
        private val failDelete: Boolean = false,
    ) : TestOwnerRecoveryStore() {
        var persisted: OwnedPetSnapshot? = null
        var snapshotWrites = 0
        var pendingDeletes = 0

        override suspend fun replacePetSnapshot(snapshot: OwnedPetSnapshot) {
            if (fail) error("database unavailable")
            snapshotWrites += 1
            persisted = snapshot
            events += "persist:${snapshot.ownerId}:${snapshot.pet.petId}"
        }

        override suspend fun replacePetSnapshotIfAssetCurrent(snapshot: OwnedPetSnapshot): Boolean {
            replacePetSnapshot(snapshot)
            return true
        }

        override suspend fun getPetSnapshots(ownerId: String): List<OwnedPetSnapshot> =
            listOfNotNull(persisted?.takeIf { it.ownerId == ownerId })

        override suspend fun loadOwnerRecovery(ownerId: String) =
            com.gigagochi.app.core.database.OwnerRecoveryData(
                getPetSnapshots(ownerId), emptyList(), emptyList(), emptyList(), emptyList(),
            )

        override suspend fun deletePendingCreate(ownerId: String, requestKey: String): Boolean {
            pendingDeletes += 1
            if (failDelete) error("crash after snapshot")
            return true
        }
    }
}
