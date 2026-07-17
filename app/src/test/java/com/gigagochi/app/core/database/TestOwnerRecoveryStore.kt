package com.gigagochi.app.core.database

open class TestOwnerRecoveryStore : OwnerRecoveryStore {
    override suspend fun replacePetSnapshot(snapshot: OwnedPetSnapshot) = Unit
    override suspend fun replacePetSnapshotIfAssetCurrent(snapshot: OwnedPetSnapshot) = true
    override suspend fun getPetSnapshots(ownerId: String): List<OwnedPetSnapshot> = emptyList()
    override suspend fun loadOwnerRecovery(ownerId: String) = OwnerRecoveryData(
        petSnapshots = getPetSnapshots(ownerId),
        pendingCreates = emptyList(),
        pendingOutfits = emptyList(),
        pendingTravels = emptyList(),
        storyReceipts = emptyList(),
    )
    override suspend fun savePendingCreate(pending: LocalPendingCreateGeneration) = Unit
    override suspend fun deletePendingCreate(ownerId: String, requestKey: String) = false
    override suspend fun attachCreateBackendJob(
        ownerId: String,
        requestKey: String,
        backendJobId: String,
    ) = BackendJobAttachmentResult.PendingMissing
    override suspend fun acceptOutfit(
        pending: LocalPendingOutfit,
    ): OutfitAcceptanceResult = OutfitAcceptanceResult.PetMissing
    override suspend fun getPendingOutfits(ownerId: String) = emptyList<LocalPendingOutfit>()
    override suspend fun attachOutfitBackendJob(
        ownerId: String,
        requestKey: String,
        backendJobId: String,
    ) = BackendJobAttachmentResult.PendingMissing
    override suspend fun savePendingTravel(pending: LocalPendingTravelVideo) =
        IdempotentInsertResult.Inserted
    override suspend fun getPendingTravels(ownerId: String) = emptyList<LocalPendingTravelVideo>()
    override suspend fun attachTravelBackendJob(
        ownerId: String,
        requestKey: String,
        backendJobId: String,
    ) = BackendJobAttachmentResult.PendingMissing
    override suspend fun applyInteractiveStoryReceipt(
        receipt: InteractiveStoryReceipt,
    ): StoryApplicationResult = StoryApplicationResult.PetMissing
}
