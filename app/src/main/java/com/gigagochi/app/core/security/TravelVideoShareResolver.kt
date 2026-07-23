package com.gigagochi.app.core.security

import com.gigagochi.app.core.database.LocalTravelVideoAsset
import com.gigagochi.app.core.database.PetLocalRepository

private val CanonicalUuidV4 = Regex(
    "^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$",
)

/**
 * The only identity accepted from an untrusted WebView or notification payload.
 * Owner, pet and media URL always come from the current native session and local storage.
 */
internal fun canonicalTravelRequestKeyOrNull(value: String?): String? =
    value?.takeIf(CanonicalUuidV4::matches)

internal sealed interface TravelVideoShareLookupResult {
    data object Invalid : TravelVideoShareLookupResult
    data object Missing : TravelVideoShareLookupResult
    data object NotReady : TravelVideoShareLookupResult
    data class Ready(val asset: LocalTravelVideoAsset) : TravelVideoShareLookupResult
}

internal fun interface ScopedTravelVideoAssetStore {
    suspend fun find(
        ownerId: String,
        petId: String,
        requestKey: String,
    ): LocalTravelVideoAsset?
}

internal class PetLocalRepositoryTravelVideoAssetStore(
    private val repository: PetLocalRepository,
) : ScopedTravelVideoAssetStore {
    override suspend fun find(
        ownerId: String,
        petId: String,
        requestKey: String,
    ): LocalTravelVideoAsset? = repository.getTravelVideoAsset(ownerId, requestKey)
        ?.takeIf { asset -> asset.petId == petId }
}

internal class TravelVideoShareResolver(
    private val ownerId: String,
    private val activePetId: String,
    private val store: ScopedTravelVideoAssetStore,
) {
    init {
        require(ownerId.isNotBlank())
        require(activePetId.isNotBlank())
    }

    suspend fun resolve(requestKeyFromWeb: String?): TravelVideoShareLookupResult {
        val requestKey = canonicalTravelRequestKeyOrNull(requestKeyFromWeb)
            ?: return TravelVideoShareLookupResult.Invalid
        val asset = store.find(
            ownerId = ownerId,
            petId = activePetId,
            requestKey = requestKey,
        ) ?: return TravelVideoShareLookupResult.Missing
        if (
            asset.ownerId != ownerId ||
            asset.petId != activePetId ||
            asset.requestKey != requestKey
        ) {
            return TravelVideoShareLookupResult.Missing
        }
        if (asset.consumedAtEpochMillis == null || asset.videoUrl.isBlank()) {
            return TravelVideoShareLookupResult.NotReady
        }
        return TravelVideoShareLookupResult.Ready(asset)
    }
}
