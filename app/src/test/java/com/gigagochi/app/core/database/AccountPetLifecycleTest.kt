package com.gigagochi.app.core.database

import com.gigagochi.app.core.model.PetDashboardState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountPetLifecycleTest {
    @Test
    fun startupRestoresOnlyLatestSnapshotForCanonicalOwner() = runBlocking {
        val store = FakeStore()
        val ownerAOld = snapshot("acct_owner_a", pet("pet-old", hunger = 10), 10)
        val ownerANew = snapshot("acct_owner_a", pet("pet-new", hunger = 70), 20)
        val ownerB = snapshot("acct_owner_b", pet("pet-other", hunger = 99), 30)
        store.snapshots += listOf(ownerAOld, ownerANew, ownerB)

        assertEquals(
            AccountStartupDestination.Dashboard(ownerANew.pet, null, null, emptyList()),
            AccountPetLifecycle(store).startup("acct_owner_a"),
        )
        assertEquals(
            AccountStartupDestination.Dashboard(ownerB.pet, null, null, emptyList()),
            AccountPetLifecycle(store).startup("acct_owner_b"),
        )
        assertEquals(
            AccountStartupDestination.Create(),
            AccountPetLifecycle(store).startup("acct_owner_empty"),
        )
        assertEquals(
            listOf("acct_owner_a", "acct_owner_b", "acct_owner_empty"),
            store.queriedOwners,
        )
    }

    @Test
    fun dashboardSaveCallbackKeepsOwnerAndVisibleState() = runBlocking {
        val store = FakeStore()
        val changed = pet("pet-1", hunger = 83)

        assertTrue(AccountPetLifecycle(store, nowEpochMillis = { 44 }).save("acct_owner_a", changed))
        assertEquals(snapshot("acct_owner_a", changed, 44), store.snapshots.single())
    }

    @Test
    fun startupReadFailureIsDistinctAndDoesNotMutateStoredOwnerData() = runBlocking {
        val store = FakeStore().apply { readFailure = true }
        val original = snapshot("acct_owner_a", pet("pet-1", hunger = 42), 10)
        store.snapshots += original

        assertEquals(
            AccountStartupDestination.Failure,
            AccountPetLifecycle(store).startup("acct_owner_a"),
        )
        assertEquals(listOf(original), store.snapshots)
    }

    @Test
    fun restartRestoresCreateOrMatchingDashboardPendingForOnlyThatOwner() = runBlocking {
        val store = FakeStore()
        val ownerAPet = pet("pet-a", hunger = 70)
        val ownerBPet = pet("pet-b", hunger = 90)
        store.snapshots += listOf(
            snapshot("acct_owner_a", pet("pet-not-active", hunger = 30), 5),
            snapshot("acct_owner_a", ownerAPet, 10),
            snapshot("acct_owner_b", ownerBPet, 20),
        )
        store.creates += pendingCreate("acct_owner_empty", "pet-new", "create-a")
        store.outfits += pendingOutfit("acct_owner_a", ownerAPet.petId, "outfit-a")
        store.outfits += pendingOutfit("acct_owner_b", ownerBPet.petId, "outfit-b")
        store.outfits += pendingOutfit("acct_owner_a", "pet-not-active", "outfit-newer")
            .copy(acceptedAtEpochMillis = 999)
        store.travels += pendingTravel("acct_owner_a", ownerAPet.petId, "travel-a")
        store.travels += pendingTravel("acct_owner_b", ownerBPet.petId, "travel-b")
        store.travels += pendingTravel("acct_owner_a", "pet-not-active", "travel-newer")
            .copy(acceptedAtEpochMillis = 999)
        store.receipts += storyReceipt("acct_owner_a", ownerAPet.petId, "receipt-a", "story-a")
        store.receipts += storyReceipt("acct_owner_b", ownerBPet.petId, "receipt-b", "story-b")
        store.receipts += storyReceipt(
            "acct_owner_a", "pet-not-active", "receipt-newer", "story-newer",
        ).copy(appliedAtEpochMillis = 999)
        val activeTravelPresentation = travelAsset(
            "acct_owner_a", ownerAPet.petId, "asset-active", consumedAt = 20,
        )
        store.assets += activeTravelPresentation
        store.assets += travelAsset("acct_owner_a", ownerAPet.petId, "asset-unconsumed", null)
        store.assets += travelAsset("acct_owner_a", "pet-not-active", "asset-old", 999)
        store.assets += travelAsset("acct_owner_b", ownerBPet.petId, "asset-foreign", 999)

        assertEquals(
            AccountStartupDestination.Create(store.creates.single()),
            AccountPetLifecycle(store).startup("acct_owner_empty"),
        )
        assertEquals(
            AccountStartupDestination.Dashboard(
                ownerAPet,
                store.outfits.first { it.ownerId == "acct_owner_a" },
                store.travels.first { it.ownerId == "acct_owner_a" },
                listOf(store.receipts.first { it.receiptKey == "receipt-a" }),
                activeTravelPresentation,
            ),
            AccountPetLifecycle(store).startup("acct_owner_a"),
        )
    }

    private fun pendingCreate(owner: String, petId: String, key: String) =
        LocalPendingCreateGeneration(
            owner, petId, key, null, PendingCreateStage.Generating, "Дракон",
            "Тото", null, null, null, 2, 10,
        )

    private fun pendingOutfit(owner: String, petId: String, key: String) = LocalPendingOutfit(
        owner, petId, key, "local-$key", null, "Наряд", "assets-$petId", 11,
    )

    private fun pendingTravel(owner: String, petId: String, key: String) = LocalPendingTravelVideo(
        owner, petId, key, "local-$key", null, "Луна", 12,
    )

    private fun storyReceipt(owner: String, petId: String, key: String, travelId: String) =
        InteractiveStoryReceipt(owner, petId, key, travelId, "part", 10, 0, 0, 0, 13)

    private fun travelAsset(owner: String, petId: String, key: String, consumedAt: Long?) =
        LocalTravelVideoAsset(
            owner, petId, key, "job-$key", "Луна", null, null, null,
            "https://gigagochi.serega.works/static/$key.mp4", 14, consumedAt,
        )

    private fun snapshot(owner: String, pet: PetDashboardState, at: Long) =
        OwnedPetSnapshot(owner, pet, at)

    private fun pet(id: String, hunger: Int) = PetDashboardState(
        petId = id,
        assetSetId = "assets-$id",
        description = "Ледяной дракон",
        name = "Тото",
        stage = "baby",
        stageLabel = "Малыш",
        mood = "idle",
        experience = 0,
        hunger = hunger,
        happiness = 100,
        energy = 100,
        message = "Привет",
        firstSessionActive = false,
    )

    private class FakeStore : TestOwnerRecoveryStore() {
        val snapshots = mutableListOf<OwnedPetSnapshot>()
        val queriedOwners = mutableListOf<String>()
        val creates = mutableListOf<LocalPendingCreateGeneration>()
        val outfits = mutableListOf<LocalPendingOutfit>()
        val travels = mutableListOf<LocalPendingTravelVideo>()
        val receipts = mutableListOf<InteractiveStoryReceipt>()
        val assets = mutableListOf<LocalTravelVideoAsset>()
        var readFailure = false

        override suspend fun replacePetSnapshot(snapshot: OwnedPetSnapshot) {
            snapshots.removeAll {
                it.ownerId == snapshot.ownerId && it.pet.petId == snapshot.pet.petId
            }
            snapshots += snapshot
        }

        override suspend fun replacePetSnapshotIfAssetCurrent(snapshot: OwnedPetSnapshot): Boolean {
            replacePetSnapshot(snapshot)
            return true
        }

        override suspend fun getPetSnapshots(ownerId: String): List<OwnedPetSnapshot> {
            queriedOwners += ownerId
            if (readFailure) error("database unavailable")
            return snapshots.filter { it.ownerId == ownerId }
        }

        override suspend fun loadOwnerRecovery(ownerId: String): OwnerRecoveryData {
            val pets = getPetSnapshots(ownerId)
            return OwnerRecoveryData(
                pets,
                creates.filter { it.ownerId == ownerId },
                outfits.filter { it.ownerId == ownerId },
                travels.filter { it.ownerId == ownerId },
                receipts.filter { it.ownerId == ownerId },
                travelVideoAssets = assets.filter { it.ownerId == ownerId },
            )
        }
    }
}
