package com.gigagochi.app.core.database

import androidx.room.withTransaction
import com.gigagochi.app.core.model.CharacterBibleMaxUtf8Bytes
import com.gigagochi.app.core.model.PetDashboardState
import com.gigagochi.app.core.model.PetGeneratedMedia
import com.gigagochi.app.core.model.PetMoodImage
import com.gigagochi.app.core.model.ScheduledStory
import com.gigagochi.app.core.model.ScheduledStoryResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.util.UUID

interface PetSnapshotStore {
    suspend fun replacePetSnapshot(snapshot: OwnedPetSnapshot)
    suspend fun replacePetSnapshotIfAssetCurrent(snapshot: OwnedPetSnapshot): Boolean
    suspend fun getPetSnapshots(ownerId: String): List<OwnedPetSnapshot>
}

data class OwnerRecoveryData(
    val petSnapshots: List<OwnedPetSnapshot>,
    val pendingCreates: List<LocalPendingCreateGeneration>,
    val pendingOutfits: List<LocalPendingOutfit>,
    val pendingTravels: List<LocalPendingTravelVideo>,
    val storyReceipts: List<InteractiveStoryReceipt>,
    val outfitOutcomes: List<LocalOutfitMediaOutcome> = emptyList(),
    val travelVideoAssets: List<LocalTravelVideoAsset> = emptyList(),
    val firstSessions: List<LocalFirstSession> = emptyList(),
)

interface FirstSessionStore {
    suspend fun getFirstSession(ownerId: String, petId: String): LocalFirstSession? = null
    suspend fun advanceFirstSession(
        ownerId: String,
        petId: String,
        expected: FirstSessionStage,
        next: FirstSessionStage,
        actionKey: String,
        selectedDestination: String? = null,
        nowEpochMillis: Long,
    ): FirstSessionMutationResult = FirstSessionMutationResult.Missing

    suspend fun applyFirstSessionFood(
        ownerId: String,
        petId: String,
        food: String,
        actionKey: String,
        nowEpochMillis: Long,
    ): FirstSessionMutationResult = FirstSessionMutationResult.Missing

    suspend fun commitFirstSessionBatChoice(
        ownerId: String,
        petId: String,
        requestKey: String,
        nowEpochMillis: Long,
    ): FirstSessionMutationResult = FirstSessionMutationResult.Missing

    suspend fun finishFirstSessionBat(
        ownerId: String,
        petId: String,
        actionKey: String,
        nowEpochMillis: Long,
    ): FirstSessionMutationResult = FirstSessionMutationResult.Missing
}

interface OwnerRecoveryStore : PetSnapshotStore, FirstSessionStore {
    suspend fun loadOwnerRecovery(ownerId: String): OwnerRecoveryData
    suspend fun savePendingCreate(pending: LocalPendingCreateGeneration)
    suspend fun replaceFailedPendingCreate(
        ownerId: String,
        failedRequestKey: String,
        replacement: LocalPendingCreateGeneration,
    ): Boolean
    suspend fun deletePendingCreate(ownerId: String, requestKey: String): Boolean
    suspend fun attachCreateBackendJob(
        ownerId: String,
        requestKey: String,
        backendJobId: String,
    ): BackendJobAttachmentResult
    suspend fun acceptOutfit(pending: LocalPendingOutfit): OutfitAcceptanceResult
    suspend fun getPendingOutfits(ownerId: String): List<LocalPendingOutfit>
    suspend fun attachOutfitBackendJob(
        ownerId: String,
        requestKey: String,
        backendJobId: String,
    ): BackendJobAttachmentResult
    suspend fun savePendingTravel(pending: LocalPendingTravelVideo): IdempotentInsertResult
    suspend fun getPendingTravels(ownerId: String): List<LocalPendingTravelVideo>
    suspend fun attachTravelBackendJob(
        ownerId: String,
        requestKey: String,
        backendJobId: String,
    ): BackendJobAttachmentResult
    suspend fun applyInteractiveStoryReceipt(
        receipt: InteractiveStoryReceipt,
    ): StoryApplicationResult

    suspend fun finalizeCreatedPet(snapshot: OwnedPetSnapshot, pendingRequestKey: String): Boolean {
        if (!replacePetSnapshotIfAssetCurrent(snapshot)) return false
        return deletePendingCreate(snapshot.ownerId, pendingRequestKey)
    }
}

interface DashboardOutcomeStore {
    suspend fun applyOutfitOutcome(
        ownerId: String,
        petId: String,
        requestKey: String,
    ): OutfitOutcomeApplicationResult

    suspend fun consumeTravelAsset(
        ownerId: String,
        petId: String,
        requestKey: String,
        consumedAtEpochMillis: Long,
    ): TravelAssetConsumptionResult
}

class PetLocalRepository(
    private val database: GigagochiDatabase,
) : OwnerRecoveryStore, PendingBackendStateStore, FeatureMediaOutcomeStore, DashboardOutcomeStore,
    ScheduledStoryStore {
    private val dao: GigagochiDao = database.gigagochiDao()

    suspend fun appendChatMessages(
        ownerId: String,
        petId: String,
        messages: List<LocalChatMessage>,
    ) {
        if (messages.isEmpty()) return
        LocalPersistenceValidation.ownerId(ownerId)
        LocalPersistenceValidation.petId(petId)
        database.withTransaction {
            dao.insertChatMessages(messages.map { it.toEntity(ownerId, petId) })
            dao.trimChatMessages(ownerId, petId, 200)
        }
    }

    suspend fun recentChatMessages(
        ownerId: String,
        petId: String,
        limit: Int = 12,
    ): List<LocalChatMessage> {
        LocalPersistenceValidation.ownerId(ownerId)
        LocalPersistenceValidation.petId(petId)
        return dao.getRecentChatMessages(ownerId, petId, limit.coerceIn(1, 200))
            .asReversed()
            .map(ChatMessageEntity::toModel)
    }

    suspend fun memoryState(ownerId: String, petId: String): LocalPetMemoryState {
        LocalPersistenceValidation.ownerId(ownerId)
        LocalPersistenceValidation.petId(petId)
        val state = dao.getPetMemoryState(ownerId, petId)
        return LocalPetMemoryState(
            summary = state?.summary,
            userProfile = state?.userProfile,
            lastExtractionAtEpochMillis = state?.lastExtractionAtEpochMillis,
            lastConsolidationAtEpochMillis = state?.lastConsolidationAtEpochMillis,
            lastProactiveAtEpochMillis = state?.lastProactiveAtEpochMillis,
            proactiveLogJson = state?.proactiveLogJson ?: "[]",
            memories = dao.getUserMemories(ownerId, petId).map(UserMemoryEntity::toModel),
            learnings = dao.getMemoryLearnings(ownerId, petId).map(MemoryLearningEntity::toModel),
        )
    }

    suspend fun rememberDeterministicFacts(
        ownerId: String,
        petId: String,
        facts: List<DeterministicMemoryFact>,
        nowEpochMillis: Long,
    ) {
        if (facts.isEmpty()) return
        database.withTransaction {
            facts.forEach { fact ->
                dao.deleteUserMemoriesByKey(ownerId, petId, fact.normalizedKey)
                dao.upsertUserMemory(
                    UserMemoryEntity(
                        ownerId = ownerId,
                        petId = petId,
                        memoryId = UUID.randomUUID().toString(),
                        kind = fact.kind,
                        text = fact.text.take(500),
                        normalizedKey = fact.normalizedKey.take(160),
                        confidence = fact.confidence.coerceIn(0.0, 1.0),
                        importance = fact.importance.coerceIn(0.0, 1.0),
                        memoryClass = memoryClass(fact.kind, fact.normalizedKey),
                        recordedAtEpochMillis = nowEpochMillis,
                        occurredAtEpochMillis = null,
                        dueAtEpochMillis = fact.dueAtEpochMillis,
                        expiresAtEpochMillis = null,
                        lastMentionedAtEpochMillis = null,
                        mentionCount = 0,
                        tagsJson = tagsJson(fact.tags),
                        updatedAtEpochMillis = nowEpochMillis,
                    ),
                )
            }
            ensureMemoryState(ownerId, petId, nowEpochMillis)
        }
    }

    suspend fun forgetMemory(
        ownerId: String,
        petId: String,
        normalizedKey: String? = null,
        matchText: String? = null,
    ) {
        database.withTransaction {
            when {
                normalizedKey == "*" -> dao.deleteUserMemories(ownerId, petId)
                !normalizedKey.isNullOrBlank() -> dao.deleteUserMemoriesByKey(
                    ownerId,
                    petId,
                    normalizedKey,
                )
                !matchText.isNullOrBlank() -> {
                    val needle = matchText.trim().lowercase()
                    dao.getUserMemories(ownerId, petId)
                        .filter { needle in it.text.lowercase() }
                        .forEach { dao.deleteUserMemoriesByKey(ownerId, petId, it.normalizedKey) }
                }
            }
        }
    }

    suspend fun applyMemoryOperations(
        ownerId: String,
        petId: String,
        operations: List<JsonObject>,
        nowEpochMillis: Long,
        isConsolidation: Boolean = false,
    ) {
        database.withTransaction {
            operations.take(120).forEach { operation ->
                when (operation.text("type")) {
                    "capture_learning" -> captureLearning(ownerId, petId, operation, nowEpochMillis)
                    "remember_user_fact", "replace_user_fact" -> rememberOperation(
                        ownerId,
                        petId,
                        operation,
                        nowEpochMillis,
                    )
                    "forget_user_fact" -> {
                        val key = operation.text("normalizedKey")
                        val match = operation.text("matchText")
                        when {
                            key == "*" -> dao.deleteUserMemories(ownerId, petId)
                            !key.isNullOrBlank() -> dao.deleteUserMemoriesByKey(ownerId, petId, key)
                            !match.isNullOrBlank() -> dao.getUserMemories(ownerId, petId)
                                .filter { match.lowercase() in it.text.lowercase() }
                                .forEach {
                                    dao.deleteUserMemoriesByKey(ownerId, petId, it.normalizedKey)
                                }
                        }
                    }
                    "promote_learning" -> {
                        val learningId = operation.text("learningId") ?: return@forEach
                        val memory = operation["memory"] as? JsonObject ?: return@forEach
                        rememberOperation(ownerId, petId, memory, nowEpochMillis)
                        dao.updateMemoryLearningStatus(
                            ownerId,
                            petId,
                            learningId,
                            "promoted",
                            nowEpochMillis,
                        )
                    }
                    "prune_learning" -> operation.text("learningId")?.let { learningId ->
                        dao.updateMemoryLearningStatus(
                            ownerId,
                            petId,
                            learningId,
                            "pruned",
                            nowEpochMillis,
                        )
                    }
                }
            }
            val previous = dao.getPetMemoryState(ownerId, petId)
            val summary = operations.asReversed()
                .firstOrNull { it.text("type") == "rewrite_summary" }
                ?.text("content") ?: previous?.summary
            val profile = operations.asReversed()
                .firstOrNull { it.text("type") == "rewrite_user_profile" }
                ?.text("content") ?: previous?.userProfile
            dao.upsertPetMemoryState(
                PetMemoryStateEntity(
                    ownerId,
                    petId,
                    summary,
                    profile,
                    lastExtractionAtEpochMillis = if (isConsolidation) {
                        previous?.lastExtractionAtEpochMillis
                    } else nowEpochMillis,
                    lastConsolidationAtEpochMillis = if (isConsolidation) {
                        nowEpochMillis
                    } else previous?.lastConsolidationAtEpochMillis,
                    lastProactiveAtEpochMillis = previous?.lastProactiveAtEpochMillis,
                    proactiveLogJson = previous?.proactiveLogJson ?: "[]",
                    updatedAtEpochMillis = nowEpochMillis,
                ),
            )
        }
    }

    suspend fun markMemoriesMentioned(
        ownerId: String,
        petId: String,
        memoryIds: Set<String>,
        nowEpochMillis: Long,
    ) {
        if (memoryIds.isEmpty()) return
        database.withTransaction {
            dao.getUserMemories(ownerId, petId)
                .filter { it.memoryId in memoryIds }
                .forEach {
                    dao.upsertUserMemory(
                        it.copy(
                            lastMentionedAtEpochMillis = nowEpochMillis,
                            mentionCount = it.mentionCount + 1,
                            updatedAtEpochMillis = nowEpochMillis,
                        ),
                    )
                }
        }
    }

    suspend fun markProactiveDelivered(
        ownerId: String,
        petId: String,
        memoryIds: Set<String>,
        reply: String,
        nowEpochMillis: Long,
    ) {
        database.withTransaction {
            markProactiveDeliveredInTransaction(ownerId, petId, memoryIds, reply, nowEpochMillis)
        }
    }

    suspend fun saveProactiveNotification(
        ownerId: String,
        petId: String,
        reply: String,
        memoryIds: Set<String>,
        nowEpochMillis: Long,
    ): Boolean {
        val notificationId = "proactive-${localDateKey(nowEpochMillis)}"
        return database.withTransaction {
            val inserted = dao.insertProactiveNotification(
                ProactiveNotificationEntity(
                    ownerId,
                    petId,
                    notificationId,
                    reply.take(500),
                    tagsJson(memoryIds.toList()),
                    nowEpochMillis,
                    null,
                ),
            ) != -1L
            if (inserted) {
                markProactiveDeliveredInTransaction(
                    ownerId,
                    petId,
                    memoryIds,
                    reply,
                    nowEpochMillis,
                )
            }
            inserted
        }
    }

    private suspend fun markProactiveDeliveredInTransaction(
        ownerId: String,
        petId: String,
        memoryIds: Set<String>,
        reply: String,
        nowEpochMillis: Long,
    ) {
        dao.getUserMemories(ownerId, petId)
            .filter { it.memoryId in memoryIds }
            .forEach {
                dao.upsertUserMemory(
                    it.copy(
                        lastMentionedAtEpochMillis = nowEpochMillis,
                        mentionCount = it.mentionCount + 1,
                        updatedAtEpochMillis = nowEpochMillis,
                    ),
                )
            }
        val previous = dao.getPetMemoryState(ownerId, petId)
        val entry = JsonObject(
            mapOf(
                "id" to JsonPrimitive(UUID.randomUUID().toString()),
                "createdAt" to JsonPrimitive(Instant.ofEpochMilli(nowEpochMillis).toString()),
                "text" to JsonPrimitive(reply.take(500)),
                "deliveredVia" to JsonPrimitive("local_notification"),
            ),
        )
        val oldLog = runCatching {
            Json.parseToJsonElement(previous?.proactiveLogJson ?: "[]").jsonArray
        }.getOrDefault(JsonArray(emptyList()))
        dao.upsertPetMemoryState(
            PetMemoryStateEntity(
                ownerId,
                petId,
                previous?.summary,
                previous?.userProfile,
                previous?.lastExtractionAtEpochMillis,
                previous?.lastConsolidationAtEpochMillis,
                nowEpochMillis,
                JsonArray((oldLog + entry).takeLast(30)).toString(),
                nowEpochMillis,
            ),
        )
    }

    private suspend fun ensureMemoryState(ownerId: String, petId: String, nowEpochMillis: Long) {
        if (dao.getPetMemoryState(ownerId, petId) != null) return
        dao.upsertPetMemoryState(
            PetMemoryStateEntity(
                ownerId,
                petId,
                null,
                null,
                null,
                null,
                null,
                "[]",
                nowEpochMillis,
            ),
        )
    }

    private suspend fun captureLearning(
        ownerId: String,
        petId: String,
        operation: JsonObject,
        nowEpochMillis: Long,
    ) {
        val observation = operation.text("observation")?.take(500) ?: return
        dao.upsertMemoryLearning(
            MemoryLearningEntity(
                ownerId,
                petId,
                UUID.randomUUID().toString(),
                "pending",
                observation,
                operation.text("patternKey")?.take(120),
                operation.text("kind"),
                operation.number("confidence", 0.5),
                operation.number("importance", 0.5),
                1,
                nowEpochMillis,
                nowEpochMillis,
                operation.instant("occurredAt"),
                operation.instant("dueAt"),
            ),
        )
    }

    private suspend fun rememberOperation(
        ownerId: String,
        petId: String,
        operation: JsonObject,
        nowEpochMillis: Long,
    ) {
        val text = operation.text("text")?.take(500) ?: return
        val key = operation.text("normalizedKey")?.take(160) ?: return
        val kind = operation.text("kind") ?: "user_fact"
        dao.deleteUserMemoriesByKey(ownerId, petId, key)
        val tags = (operation["tags"] as? JsonArray).orEmpty().mapNotNull {
            (it as? JsonPrimitive)?.contentOrNull?.take(120)
        }.take(12)
        dao.upsertUserMemory(
            UserMemoryEntity(
                ownerId,
                petId,
                UUID.randomUUID().toString(),
                kind,
                text,
                key,
                operation.number("confidence", 0.5),
                operation.number("importance", 0.5),
                memoryClass(kind, key),
                nowEpochMillis,
                operation.instant("occurredAt"),
                operation.instant("dueAt"),
                operation.instant("expiresAt"),
                null,
                0,
                tagsJson(tags),
                nowEpochMillis,
            ),
        )
    }

    override suspend fun finalizeCreatedPet(
        snapshot: OwnedPetSnapshot,
        pendingRequestKey: String,
    ): Boolean {
        LocalPersistenceValidation.petSnapshot(snapshot)
        LocalPersistenceValidation.requestKey(pendingRequestKey)
        return database.withTransaction {
            if (dao.getPet(snapshot.ownerId, snapshot.pet.petId) != null) return@withTransaction false
            dao.upsertPet(snapshot.toEntity())
            val images = snapshot.pet.generatedMedia.moodImages.map {
                PetMoodImageEntity(snapshot.ownerId, snapshot.pet.petId, it.stage, it.mood, it.url)
            }
            if (images.isNotEmpty()) dao.upsertMoodImages(images)
            check(
                dao.insertFirstSession(
                    FirstSessionEntity(
                        snapshot.ownerId,
                        snapshot.pet.petId,
                        FirstSessionStage.AwaitingChat.storageValue,
                        null,
                        null,
                        snapshot.updatedAtEpochMillis,
                    ),
                ) != -1L,
            )
            check(dao.deletePendingCreate(snapshot.ownerId, pendingRequestKey) == 1)
            true
        }
    }

    override suspend fun getFirstSession(ownerId: String, petId: String): LocalFirstSession? {
        LocalPersistenceValidation.ownerId(ownerId)
        LocalPersistenceValidation.petId(petId)
        return dao.getFirstSession(ownerId, petId)?.toModel()
    }

    suspend fun restartFirstSession(
        ownerId: String,
        petId: String,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): LocalFirstSession? = database.withTransaction {
        LocalPersistenceValidation.ownerId(ownerId)
        LocalPersistenceValidation.petId(petId)
        if (dao.getPet(ownerId, petId) == null) return@withTransaction null
        dao.deleteFirstSessionActionReceipts(ownerId, petId)
        val entity = FirstSessionEntity(
            ownerId = ownerId,
            petId = petId,
            stage = FirstSessionStage.AwaitingChat.storageValue,
            selectedDestination = null,
            lastActionKey = null,
            updatedAtEpochMillis = nowEpochMillis,
        )
        dao.upsertFirstSession(entity)
        entity.toModel()
    }

    suspend fun disableFirstSession(ownerId: String, petId: String): Boolean =
        database.withTransaction {
            LocalPersistenceValidation.ownerId(ownerId)
            LocalPersistenceValidation.petId(petId)
            dao.deleteFirstSessionActionReceipts(ownerId, petId)
            dao.deleteFirstSession(ownerId, petId) == 1
        }

    override suspend fun advanceFirstSession(
        ownerId: String,
        petId: String,
        expected: FirstSessionStage,
        next: FirstSessionStage,
        actionKey: String,
        selectedDestination: String?,
        nowEpochMillis: Long,
    ): FirstSessionMutationResult = database.withTransaction {
        LocalPersistenceValidation.firstSessionAction(ownerId, petId, actionKey, selectedDestination)
        firstSessionReplay(ownerId, petId, actionKey)?.let { return@withTransaction it }
        val current = dao.getFirstSession(ownerId, petId) ?: return@withTransaction FirstSessionMutationResult.Missing
        val pet = dao.getPet(ownerId, petId) ?: return@withTransaction FirstSessionMutationResult.Missing
        if (current.stage != expected.storageValue || !isAllowedFirstSessionTransition(expected, next)) {
            return@withTransaction FirstSessionMutationResult.WrongStage
        }
        check(dao.insertFirstSessionActionReceipt(
            FirstSessionActionReceiptEntity(ownerId, petId, actionKey, "stage", nowEpochMillis),
        ) != -1L)
        check(dao.advanceFirstSession(
            ownerId, petId, expected.storageValue, next.storageValue,
            selectedDestination?.trim()?.take(500), actionKey, nowEpochMillis,
        ) == 1)
        FirstSessionMutationResult.Applied(
            requireNotNull(dao.getFirstSession(ownerId, petId)).toModel(),
            pet.toModel(dao.getMoodImages(ownerId, petId)).pet,
        )
    }

    override suspend fun applyFirstSessionFood(
        ownerId: String,
        petId: String,
        food: String,
        actionKey: String,
        nowEpochMillis: Long,
    ): FirstSessionMutationResult = database.withTransaction {
        LocalPersistenceValidation.firstSessionAction(ownerId, petId, actionKey, null)
        firstSessionReplay(ownerId, petId, actionKey)?.let { return@withTransaction it }
        val session = dao.getFirstSession(ownerId, petId) ?: return@withTransaction FirstSessionMutationResult.Missing
        val pet = dao.getPet(ownerId, petId) ?: return@withTransaction FirstSessionMutationResult.Missing
        val stage = FirstSessionStage.fromStorage(session.stage)
        val nextStage = when {
            stage == FirstSessionStage.AwaitingFirstFood && food == "berry-bowl" -> FirstSessionStage.AwaitingRemedy
            stage == FirstSessionStage.AwaitingRemedy && food == "leaf-crunch" -> FirstSessionStage.AwaitingTravel
            stage == FirstSessionStage.AwaitingRemedy && food == "berry-bowl" -> FirstSessionStage.AwaitingRemedy
            else -> return@withTransaction FirstSessionMutationResult.WrongStage
        }
        check(dao.insertFirstSessionActionReceipt(
            FirstSessionActionReceiptEntity(ownerId, petId, actionKey, "food:$food", nowEpochMillis),
        ) != -1L)
        val hunger = if (food == "berry-bowl") (pet.hunger + 25).coerceAtMost(100) else pet.hunger
        val energy = if (food == "leaf-crunch") (pet.energy + 25).coerceAtMost(100) else pet.energy
        check(dao.updatePetProgress(ownerId, petId, pet.experience, hunger, pet.happiness, energy, nowEpochMillis) == 1)
        check(dao.advanceFirstSession(ownerId, petId, stage.storageValue, nextStage.storageValue, null, actionKey, nowEpochMillis) == 1)
        FirstSessionMutationResult.Applied(
            requireNotNull(dao.getFirstSession(ownerId, petId)).toModel(),
            requireNotNull(dao.getPet(ownerId, petId)).toModel(dao.getMoodImages(ownerId, petId)).pet,
        )
    }

    override suspend fun commitFirstSessionBatChoice(
        ownerId: String,
        petId: String,
        requestKey: String,
        nowEpochMillis: Long,
    ): FirstSessionMutationResult = database.withTransaction {
        LocalPersistenceValidation.firstSessionAction(ownerId, petId, requestKey, null)
        val session = dao.getFirstSession(ownerId, petId) ?: return@withTransaction FirstSessionMutationResult.Missing
        val pet = dao.getPet(ownerId, petId) ?: return@withTransaction FirstSessionMutationResult.Missing
        val travelId = "onboarding-bat-help-v1-$petId".take(160)
        val prior = dao.getStoryReceipt(ownerId, requestKey)
            ?: dao.getStoryReceiptByPart(ownerId, travelId, "choice-result")
        if (prior != null) {
            return@withTransaction FirstSessionMutationResult.AlreadyApplied(
                session.toModel(), pet.toModel(dao.getMoodImages(ownerId, petId)).pet,
            )
        }
        if (session.stage != FirstSessionStage.AwaitingTravel.storageValue) {
            return@withTransaction FirstSessionMutationResult.WrongStage
        }
        check(dao.insertStoryReceipt(
            AppliedStoryReceiptEntity(ownerId, petId, requestKey, travelId, "choice-result", 200, 0, 0, 0, nowEpochMillis),
        ) != -1L)
        check(dao.updatePetProgress(ownerId, petId, (pet.experience + 200).coerceAtMost(3_000), pet.hunger, pet.happiness, pet.energy, nowEpochMillis) == 1)
        FirstSessionMutationResult.Applied(
            session.toModel(),
            requireNotNull(dao.getPet(ownerId, petId)).toModel(dao.getMoodImages(ownerId, petId)).pet,
        )
    }

    override suspend fun finishFirstSessionBat(
        ownerId: String,
        petId: String,
        actionKey: String,
        nowEpochMillis: Long,
    ): FirstSessionMutationResult = database.withTransaction {
        LocalPersistenceValidation.firstSessionAction(ownerId, petId, actionKey, null)
        firstSessionReplay(ownerId, petId, actionKey)?.let { return@withTransaction it }
        val travelId = "onboarding-bat-help-v1-$petId".take(160)
        if (dao.getStoryReceiptByPart(ownerId, travelId, "choice-result") == null) {
            return@withTransaction FirstSessionMutationResult.WrongStage
        }
        val session = dao.getFirstSession(ownerId, petId) ?: return@withTransaction FirstSessionMutationResult.Missing
        val pet = dao.getPet(ownerId, petId) ?: return@withTransaction FirstSessionMutationResult.Missing
        if (session.stage != FirstSessionStage.AwaitingTravel.storageValue) {
            return@withTransaction FirstSessionMutationResult.WrongStage
        }
        check(dao.insertFirstSessionActionReceipt(
            FirstSessionActionReceiptEntity(ownerId, petId, actionKey, "bat-finish", nowEpochMillis),
        ) != -1L)
        check(dao.advanceFirstSession(
            ownerId, petId, FirstSessionStage.AwaitingTravel.storageValue,
            FirstSessionStage.AwaitingCompletionMessage.storageValue, null, actionKey, nowEpochMillis,
        ) == 1)
        FirstSessionMutationResult.Applied(
            requireNotNull(dao.getFirstSession(ownerId, petId)).toModel(),
            pet.toModel(dao.getMoodImages(ownerId, petId)).pet,
        )
    }

    private suspend fun firstSessionReplay(ownerId: String, petId: String, actionKey: String): FirstSessionMutationResult? {
        if (dao.getFirstSessionActionReceipt(ownerId, petId, actionKey) == null) return null
        val session = dao.getFirstSession(ownerId, petId) ?: return FirstSessionMutationResult.Missing
        val pet = dao.getPet(ownerId, petId) ?: return FirstSessionMutationResult.Missing
        return FirstSessionMutationResult.AlreadyApplied(
            session.toModel(), pet.toModel(dao.getMoodImages(ownerId, petId)).pet,
        )
    }

    override suspend fun replacePetSnapshot(snapshot: OwnedPetSnapshot) {
        LocalPersistenceValidation.petSnapshot(snapshot)
        database.withTransaction {
            dao.upsertPet(snapshot.toEntity())
            dao.deleteMoodImages(snapshot.ownerId, snapshot.pet.petId)
            val images = snapshot.pet.generatedMedia.moodImages.map {
                PetMoodImageEntity(snapshot.ownerId, snapshot.pet.petId, it.stage, it.mood, it.url)
            }
            if (images.isNotEmpty()) dao.upsertMoodImages(images)
        }
    }

    override suspend fun replacePetSnapshotIfAssetCurrent(snapshot: OwnedPetSnapshot): Boolean {
        LocalPersistenceValidation.petSnapshot(snapshot)
        return database.withTransaction {
            val current = dao.getPet(snapshot.ownerId, snapshot.pet.petId)
            if (current != null && current.assetSetId != snapshot.pet.assetSetId) {
                return@withTransaction false
            }
            dao.upsertPet(snapshot.toEntity())
            dao.deleteMoodImages(snapshot.ownerId, snapshot.pet.petId)
            val images = snapshot.pet.generatedMedia.moodImages.map {
                PetMoodImageEntity(snapshot.ownerId, snapshot.pet.petId, it.stage, it.mood, it.url)
            }
            if (images.isNotEmpty()) dao.upsertMoodImages(images)
            true
        }
    }

    suspend fun getPetSnapshot(ownerId: String, petId: String): OwnedPetSnapshot? {
        LocalPersistenceValidation.ownerId(ownerId)
        LocalPersistenceValidation.petId(petId)
        return dao.getPet(ownerId, petId)?.let {
            it.toModel(dao.getMoodImages(ownerId, petId))
        }
    }

    override suspend fun getPetSnapshots(ownerId: String): List<OwnedPetSnapshot> {
        LocalPersistenceValidation.ownerId(ownerId)
        return dao.getPets(ownerId).map { entity ->
            entity.toModel(dao.getMoodImages(ownerId, entity.petId))
        }
    }

    suspend fun deletePetSnapshot(ownerId: String, petId: String): Boolean {
        LocalPersistenceValidation.ownerId(ownerId)
        LocalPersistenceValidation.petId(petId)
        return dao.deletePet(ownerId, petId) == 1
    }

    override suspend fun savePendingCreate(pending: LocalPendingCreateGeneration) {
        LocalPersistenceValidation.pendingCreate(pending)
        dao.upsertPendingCreate(pending.toEntity())
    }

    override suspend fun replaceFailedPendingCreate(
        ownerId: String,
        failedRequestKey: String,
        replacement: LocalPendingCreateGeneration,
    ): Boolean {
        LocalPersistenceValidation.ownerId(ownerId)
        LocalPersistenceValidation.requestKey(failedRequestKey)
        LocalPersistenceValidation.pendingCreate(replacement)
        require(replacement.ownerId == ownerId && replacement.requestKey != failedRequestKey)
        return database.withTransaction {
            val failed = dao.getPendingCreate(ownerId, failedRequestKey)
                ?: return@withTransaction false
            if (
                failed.petId != replacement.petId ||
                failed.backendState != PendingBackendState.Failed
            ) {
                return@withTransaction false
            }
            dao.upsertPendingCreate(replacement.toEntity())
            check(dao.deletePendingCreate(ownerId, failedRequestKey) == 1)
            true
        }
    }

    override suspend fun updateCreateBackendState(
        ownerId: String,
        requestKey: String,
        state: PendingBackendState,
        errorCode: String?,
    ): Boolean {
        LocalPersistenceValidation.backendState(ownerId, requestKey, errorCode)
        return dao.updateCreateBackendState(ownerId, requestKey, state, errorCode) == 1
    }

    override suspend fun updateOutfitBackendState(
        ownerId: String,
        requestKey: String,
        state: PendingBackendState,
        errorCode: String?,
    ): Boolean {
        LocalPersistenceValidation.backendState(ownerId, requestKey, errorCode)
        return dao.updateOutfitBackendState(ownerId, requestKey, state, errorCode) == 1
    }

    override suspend fun updateTravelBackendState(
        ownerId: String,
        requestKey: String,
        state: PendingBackendState,
        errorCode: String?,
    ): Boolean {
        LocalPersistenceValidation.backendState(ownerId, requestKey, errorCode)
        return dao.updateTravelBackendState(ownerId, requestKey, state, errorCode) == 1
    }

    override suspend fun prepareOutfitDisplayItem(
        ownerId: String,
        requestKey: String,
        displayItem: String,
    ): Boolean {
        LocalPersistenceValidation.backendState(ownerId, requestKey, null)
        require(displayItem.isNotBlank() && displayItem.length <= 160)
        return dao.prepareOutfitDisplayItem(ownerId, requestKey, displayItem) == 1
    }

    override suspend fun markOutfitApplyConflict(ownerId: String, requestKey: String): Boolean {
        LocalPersistenceValidation.backendState(ownerId, requestKey, "APPLY_CONFLICT")
        return dao.markOutfitApplyConflict(ownerId, requestKey) == 1
    }

    override suspend fun markTravelApplyConflict(ownerId: String, requestKey: String): Boolean {
        LocalPersistenceValidation.backendState(ownerId, requestKey, "APPLY_CONFLICT")
        return dao.markTravelApplyConflict(ownerId, requestKey) == 1
    }

    suspend fun getPendingCreates(ownerId: String): List<LocalPendingCreateGeneration> {
        LocalPersistenceValidation.ownerId(ownerId)
        return dao.getPendingCreates(ownerId).map(PendingCreateGenerationEntity::toModel)
    }

    override suspend fun deletePendingCreate(ownerId: String, requestKey: String): Boolean {
        LocalPersistenceValidation.ownerId(ownerId)
        LocalPersistenceValidation.requestKey(requestKey)
        return dao.deletePendingCreate(ownerId, requestKey) == 1
    }

    override suspend fun attachCreateBackendJob(
        ownerId: String,
        requestKey: String,
        backendJobId: String,
    ): BackendJobAttachmentResult {
        LocalPersistenceValidation.ownerId(ownerId)
        LocalPersistenceValidation.requestKey(requestKey)
        LocalPersistenceValidation.backendJobId(backendJobId)
        return database.withTransaction {
            val existing = dao.getPendingCreate(ownerId, requestKey)
                ?: return@withTransaction BackendJobAttachmentResult.PendingMissing
            when (existing.backendJobId) {
                backendJobId -> BackendJobAttachmentResult.AlreadyAttached
                null -> if (dao.attachCreateBackendJob(ownerId, requestKey, backendJobId) == 1) {
                    BackendJobAttachmentResult.Attached
                } else BackendJobAttachmentResult.Conflict
                else -> BackendJobAttachmentResult.Conflict
            }
        }
    }

    override suspend fun acceptOutfit(pending: LocalPendingOutfit): OutfitAcceptanceResult {
        LocalPersistenceValidation.pendingOutfit(pending)
        return database.withTransaction {
            if (dao.getAppliedOutfitReceipt(pending.ownerId, pending.requestKey) != null) {
                return@withTransaction OutfitAcceptanceResult.AlreadyApplied
            }
            if (dao.getPendingOutfit(pending.ownerId, pending.requestKey) != null) {
                return@withTransaction OutfitAcceptanceResult.AlreadyApplied
            }
            val pet = dao.getPet(pending.ownerId, pending.petId)
                ?: return@withTransaction OutfitAcceptanceResult.PetMissing
            val firstSession = dao.getFirstSession(pending.ownerId, pending.petId)
            if (firstSession != null && firstSession.stage !in setOf(
                    FirstSessionStage.AwaitingCompletionMessage.storageValue,
                    FirstSessionStage.Completed.storageValue,
                )
            ) return@withTransaction OutfitAcceptanceResult.PetMissing
            if (pet.experience < OutfitAcceptanceCost) {
                return@withTransaction OutfitAcceptanceResult.InsufficientExperience
            }
            if (dao.insertPendingOutfit(pending.toEntity()) == -1L) {
                return@withTransaction OutfitAcceptanceResult.AlreadyApplied
            }
            check(
                dao.updatePetProgress(
                    ownerId = pending.ownerId,
                    petId = pending.petId,
                    experience = pet.experience - OutfitAcceptanceCost,
                    hunger = pet.hunger,
                    happiness = pet.happiness,
                    energy = pet.energy,
                    updatedAtEpochMillis = pending.acceptedAtEpochMillis,
                ) == 1,
            ) { "Pet disappeared during outfit acceptance" }
            OutfitAcceptanceResult.Applied
        }
    }

    override suspend fun attachOutfitBackendJob(
        ownerId: String,
        requestKey: String,
        backendJobId: String,
    ): BackendJobAttachmentResult {
        LocalPersistenceValidation.ownerId(ownerId)
        LocalPersistenceValidation.requestKey(requestKey)
        LocalPersistenceValidation.backendJobId(backendJobId)
        return database.withTransaction {
            val existing = dao.getPendingOutfit(ownerId, requestKey)
                ?: return@withTransaction BackendJobAttachmentResult.PendingMissing
            when (existing.backendJobId) {
                backendJobId -> BackendJobAttachmentResult.AlreadyAttached
                null -> if (dao.attachOutfitBackendJob(ownerId, requestKey, backendJobId) == 1) {
                    val firstSession = dao.getFirstSession(ownerId, existing.petId)
                    if (firstSession?.stage == FirstSessionStage.AwaitingCompletionMessage.storageValue) {
                        check(dao.insertFirstSessionActionReceipt(
                            FirstSessionActionReceiptEntity(
                                ownerId, existing.petId, requestKey, "outfit", existing.acceptedAtEpochMillis,
                            ),
                        ) != -1L)
                        check(dao.advanceFirstSession(
                            ownerId, existing.petId,
                            FirstSessionStage.AwaitingCompletionMessage.storageValue,
                            FirstSessionStage.Completed.storageValue,
                            null, requestKey, existing.acceptedAtEpochMillis,
                        ) == 1)
                    }
                    BackendJobAttachmentResult.Attached
                } else {
                    BackendJobAttachmentResult.Conflict
                }
                else -> BackendJobAttachmentResult.Conflict
            }
        }
    }

    override suspend fun getPendingOutfits(ownerId: String): List<LocalPendingOutfit> {
        LocalPersistenceValidation.ownerId(ownerId)
        return dao.getPendingOutfits(ownerId).map(PendingOutfitEntity::toModel)
    }

    suspend fun deletePendingOutfit(ownerId: String, requestKey: String): Boolean {
        LocalPersistenceValidation.ownerId(ownerId)
        LocalPersistenceValidation.requestKey(requestKey)
        return dao.deletePendingOutfit(ownerId, requestKey) == 1
    }

    override suspend fun savePendingTravel(
        pending: LocalPendingTravelVideo,
    ): IdempotentInsertResult {
        LocalPersistenceValidation.pendingTravel(pending)
        return if (dao.insertPendingTravel(pending.toEntity()) == -1L) {
            IdempotentInsertResult.AlreadyPresent
        } else {
            IdempotentInsertResult.Inserted
        }
    }

    override suspend fun attachTravelBackendJob(
        ownerId: String,
        requestKey: String,
        backendJobId: String,
    ): BackendJobAttachmentResult {
        LocalPersistenceValidation.ownerId(ownerId)
        LocalPersistenceValidation.requestKey(requestKey)
        LocalPersistenceValidation.backendJobId(backendJobId)
        return database.withTransaction {
            val existing = dao.getPendingTravel(ownerId, requestKey)
                ?: return@withTransaction BackendJobAttachmentResult.PendingMissing
            when (existing.backendJobId) {
                backendJobId -> BackendJobAttachmentResult.AlreadyAttached
                null -> if (dao.attachTravelBackendJob(ownerId, requestKey, backendJobId) == 1) {
                    BackendJobAttachmentResult.Attached
                } else {
                    BackendJobAttachmentResult.Conflict
                }
                else -> BackendJobAttachmentResult.Conflict
            }
        }
    }

    override suspend fun getPendingTravels(ownerId: String): List<LocalPendingTravelVideo> {
        LocalPersistenceValidation.ownerId(ownerId)
        return dao.getPendingTravels(ownerId).map(PendingTravelVideoEntity::toModel)
    }

    suspend fun deletePendingTravel(ownerId: String, requestKey: String): Boolean {
        LocalPersistenceValidation.ownerId(ownerId)
        LocalPersistenceValidation.requestKey(requestKey)
        return dao.deletePendingTravel(ownerId, requestKey) == 1
    }

    override suspend fun saveTravelVideoAsset(asset: LocalTravelVideoAsset) {
        LocalPersistenceValidation.travelVideoAsset(asset)
        database.withTransaction {
            val existing = dao.getTravelVideoAsset(asset.ownerId, asset.requestKey)
            if (existing == null) {
                check(dao.insertTravelVideoAsset(asset.toEntity()) != -1L)
            } else {
                require(
                    existing.toModel().copy(
                        consumedAtEpochMillis = asset.consumedAtEpochMillis,
                        notifiedAtEpochMillis = asset.notifiedAtEpochMillis,
                    ) == asset,
                ) {
                    "travel asset replay conflicts with durable result"
                }
            }
        }
    }

    suspend fun getTravelVideoAssets(ownerId: String, petId: String): List<LocalTravelVideoAsset> {
        LocalPersistenceValidation.ownerId(ownerId)
        LocalPersistenceValidation.petId(petId)
        return dao.getTravelVideoAssets(ownerId, petId).map(TravelVideoAssetEntity::toModel)
    }

    fun observeTravelVideoAssets(
        ownerId: String,
        petId: String,
    ): Flow<List<LocalTravelVideoAsset>> {
        LocalPersistenceValidation.ownerId(ownerId)
        LocalPersistenceValidation.petId(petId)
        return dao.observeTravelVideoAssets(ownerId, petId).map { assets ->
            assets.map(TravelVideoAssetEntity::toModel)
        }
    }

    suspend fun getTravelVideoAsset(
        ownerId: String,
        requestKey: String,
    ): LocalTravelVideoAsset? {
        LocalPersistenceValidation.ownerId(ownerId)
        LocalPersistenceValidation.requestKey(requestKey)
        return dao.getTravelVideoAsset(ownerId, requestKey)?.toModel()
    }

    override suspend fun saveOutfitMediaOutcome(outcome: LocalOutfitMediaOutcome) {
        LocalPersistenceValidation.outfitMediaOutcome(outcome)
        database.withTransaction {
            val existing = dao.getOutfitMediaOutcome(outcome.ownerId, outcome.requestKey)
            if (existing != null) {
                val existingImages = dao.getOutfitMoodImages(outcome.ownerId, outcome.requestKey)
                    .map(OutfitMoodImageEntity::toModel)
                require(
                    existing == outcome.toEntity() &&
                        existingImages.toSet() == outcome.media.moodImages.toSet(),
                ) {
                    "outfit outcome replay conflicts with durable result"
                }
                return@withTransaction
            }
            check(dao.insertOutfitMediaOutcome(outcome.toEntity()) != -1L)
            dao.deleteOutfitMoodImages(outcome.ownerId, outcome.requestKey)
            val images = outcome.media.moodImages.map {
                OutfitMoodImageEntity(outcome.ownerId, outcome.requestKey, it.stage, it.mood, it.url)
            }
            if (images.isNotEmpty()) dao.upsertOutfitMoodImages(images)
        }
    }

    suspend fun getOutfitMediaOutcomes(
        ownerId: String,
        petId: String,
    ): List<LocalOutfitMediaOutcome> {
        LocalPersistenceValidation.ownerId(ownerId)
        LocalPersistenceValidation.petId(petId)
        return dao.getOutfitMediaOutcomes(ownerId, petId).map { outcome ->
            val images = dao.getOutfitMoodImages(ownerId, outcome.requestKey)
                .map(OutfitMoodImageEntity::toModel)
            outcome.toModel(images)
        }
    }

    override suspend fun applyOutfitOutcome(
        ownerId: String,
        petId: String,
        requestKey: String,
    ): OutfitOutcomeApplicationResult {
        LocalPersistenceValidation.ownerId(ownerId)
        LocalPersistenceValidation.petId(petId)
        LocalPersistenceValidation.requestKey(requestKey)
        return database.withTransaction {
            val existingReceipt = dao.getAppliedOutfitReceipt(ownerId, requestKey)
            if (existingReceipt != null) {
                if (existingReceipt.petId != petId) {
                    return@withTransaction OutfitOutcomeApplicationResult.Conflict
                }
                val appliedPet = dao.getPet(ownerId, petId)
                    ?.toModel(dao.getMoodImages(ownerId, petId))?.pet
                    ?: return@withTransaction OutfitOutcomeApplicationResult.Conflict
                if (appliedPet.assetSetId != existingReceipt.assetSetId) {
                    return@withTransaction OutfitOutcomeApplicationResult.Conflict
                }
                return@withTransaction OutfitOutcomeApplicationResult.AlreadyApplied(appliedPet)
            }
            val pending = dao.getPendingOutfit(ownerId, requestKey)
                ?: return@withTransaction OutfitOutcomeApplicationResult.NotReady
            val outcome = dao.getOutfitMediaOutcome(ownerId, requestKey)
                ?: return@withTransaction OutfitOutcomeApplicationResult.NotReady
            if (pending.petId != petId || outcome.petId != petId ||
                pending.backendJobId != outcome.backendJobId || pending.backendState != PendingBackendState.Ready
            ) {
                return@withTransaction OutfitOutcomeApplicationResult.Conflict
            }
            val current = dao.getPet(ownerId, petId)
                ?.toModel(dao.getMoodImages(ownerId, petId))
                ?: return@withTransaction OutfitOutcomeApplicationResult.Conflict
            val outcomeImages = dao.getOutfitMoodImages(ownerId, requestKey)
                .map(OutfitMoodImageEntity::toModel)
            val appliedPet = current.pet.copy(
                assetSetId = outcome.assetSetId,
                generatedMedia = outcome.toModel(outcomeImages).media,
            )
            LocalPersistenceValidation.petSnapshot(
                OwnedPetSnapshot(ownerId, appliedPet, outcome.completedAtEpochMillis),
            )
            if (dao.insertAppliedOutfitReceipt(
                    AppliedOutfitReceiptEntity(
                        ownerId,
                        petId,
                        requestKey,
                        outcome.assetSetId,
                        outcome.completedAtEpochMillis,
                    ),
                ) == -1L
            ) {
                return@withTransaction OutfitOutcomeApplicationResult.Conflict
            }
            dao.upsertPet(OwnedPetSnapshot(ownerId, appliedPet, outcome.completedAtEpochMillis).toEntity())
            dao.deleteMoodImages(ownerId, petId)
            if (outcomeImages.isNotEmpty()) {
                dao.upsertMoodImages(outcomeImages.map {
                    PetMoodImageEntity(ownerId, petId, it.stage, it.mood, it.url)
                })
            }
            check(dao.deletePendingOutfit(ownerId, requestKey) == 1)
            check(dao.deleteOutfitMediaOutcome(ownerId, requestKey) == 1)
            dao.deleteOutfitMoodImages(ownerId, requestKey)
            OutfitOutcomeApplicationResult.Applied(appliedPet)
        }
    }

    override suspend fun consumeTravelAsset(
        ownerId: String,
        petId: String,
        requestKey: String,
        consumedAtEpochMillis: Long,
    ): TravelAssetConsumptionResult {
        LocalPersistenceValidation.ownerId(ownerId)
        LocalPersistenceValidation.petId(petId)
        LocalPersistenceValidation.requestKey(requestKey)
        require(consumedAtEpochMillis >= 0L)
        return database.withTransaction {
            val asset = dao.getTravelVideoAsset(ownerId, requestKey)
                ?: return@withTransaction TravelAssetConsumptionResult.NotReady
            if (asset.petId != petId) return@withTransaction TravelAssetConsumptionResult.Conflict
            if (asset.consumedAtEpochMillis != null) {
                return@withTransaction TravelAssetConsumptionResult.AlreadyConsumed(asset.toModel())
            }
            val pending = dao.getPendingTravel(ownerId, requestKey)
                ?: return@withTransaction TravelAssetConsumptionResult.Conflict
            if (pending.petId != petId || pending.backendJobId != asset.backendJobId ||
                pending.backendState != PendingBackendState.Ready
            ) {
                return@withTransaction TravelAssetConsumptionResult.Conflict
            }
            check(dao.consumeTravelVideoAsset(ownerId, requestKey, consumedAtEpochMillis) == 1)
            check(dao.deletePendingTravel(ownerId, requestKey) == 1)
            TravelAssetConsumptionResult.Consumed(
                asset.copy(consumedAtEpochMillis = consumedAtEpochMillis).toModel(),
            )
        }
    }

    override suspend fun applyInteractiveStoryReceipt(
        receipt: InteractiveStoryReceipt,
    ): StoryApplicationResult {
        LocalPersistenceValidation.storyReceipt(receipt)
        return database.withTransaction {
            if (dao.getStoryReceipt(receipt.ownerId, receipt.receiptKey) != null ||
                dao.getStoryReceiptByPart(
                    receipt.ownerId,
                    receipt.travelId,
                    receipt.partKey,
                ) != null
            ) {
                return@withTransaction StoryApplicationResult.AlreadyApplied
            }
            val pet = dao.getPet(receipt.ownerId, receipt.petId)
                ?: return@withTransaction StoryApplicationResult.PetMissing
            val nextExperience = checkedProgressValue(
                pet.experience,
                receipt.experienceDelta,
                0..3_000,
            )
            val nextHunger = checkedProgressValue(
                pet.hunger,
                receipt.hungerDelta,
                0..100,
            )
            val nextHappiness = checkedProgressValue(
                pet.happiness,
                receipt.happinessDelta,
                0..100,
            )
            val nextEnergy = checkedProgressValue(
                pet.energy,
                receipt.energyDelta,
                0..100,
            )
            if (dao.insertStoryReceipt(receipt.toEntity()) == -1L) {
                return@withTransaction StoryApplicationResult.AlreadyApplied
            }
            check(
                dao.updatePetProgress(
                    ownerId = receipt.ownerId,
                    petId = receipt.petId,
                    experience = nextExperience,
                    hunger = nextHunger,
                    happiness = nextHappiness,
                    energy = nextEnergy,
                    updatedAtEpochMillis = receipt.appliedAtEpochMillis,
                ) == 1,
            ) { "Pet disappeared during story receipt application" }
            StoryApplicationResult.Applied
        }
    }

    suspend fun getStoryReceipts(ownerId: String): List<InteractiveStoryReceipt> {
        LocalPersistenceValidation.ownerId(ownerId)
        return dao.getStoryReceipts(ownerId).map(AppliedStoryReceiptEntity::toModel)
    }

    suspend fun deleteStoryReceipt(ownerId: String, receiptKey: String): Boolean {
        LocalPersistenceValidation.ownerId(ownerId)
        LocalPersistenceValidation.receiptKey(receiptKey)
        return dao.deleteStoryReceipt(ownerId, receiptKey) == 1
    }

    override suspend fun saveScheduledStory(story: LocalScheduledStory): Boolean {
        LocalPersistenceValidation.scheduledStory(story)
        return database.withTransaction {
            val current = dao.getScheduledStory(story.ownerId, story.story.storyId)
            if (current == null) {
                return@withTransaction dao.insertScheduledStory(story.toEntity()) != -1L
            }
            val existing = current.toModel()
            if (existing.story.withoutOutcome() != story.story.withoutOutcome()) {
                return@withTransaction false
            }
            val merged = when {
                existing.story.selectedChoice != null -> {
                    if (story.story.selectedChoice != null && existing.story != story.story) {
                        return@withTransaction false
                    }
                    existing
                }
                story.story.selectedChoice != null -> {
                    if (existing.pendingChoice != null && existing.pendingChoice != story.story.selectedChoice) {
                        return@withTransaction false
                    }
                    story.copy(
                        choiceRequestKey = existing.choiceRequestKey ?: story.choiceRequestKey,
                        pendingChoice = null,
                        notifiedAtEpochMillis = existing.notifiedAtEpochMillis,
                    )
                }
                else -> existing
            }
            dao.upsertScheduledStory(merged.toEntity())
            true
        }
    }

    override suspend fun getScheduledStory(ownerId: String, storyId: String): LocalScheduledStory? {
        LocalPersistenceValidation.ownerId(ownerId)
        LocalPersistenceValidation.storyId(storyId)
        return dao.getScheduledStory(ownerId, storyId)?.toModel()
    }

    override suspend fun deleteScheduledStory(ownerId: String, storyId: String): Boolean {
        LocalPersistenceValidation.ownerId(ownerId)
        LocalPersistenceValidation.storyId(storyId)
        return dao.deleteScheduledStory(ownerId, storyId) == 1
    }

    override suspend fun getScheduledStories(ownerId: String, petId: String): List<LocalScheduledStory> {
        LocalPersistenceValidation.ownerId(ownerId)
        LocalPersistenceValidation.petId(petId)
        return dao.getScheduledStories(ownerId, petId).map(ScheduledStoryEntity::toModel)
    }

    fun observeScheduledStories(
        ownerId: String,
        petId: String,
    ): Flow<List<LocalScheduledStory>> {
        LocalPersistenceValidation.ownerId(ownerId)
        LocalPersistenceValidation.petId(petId)
        return dao.observeScheduledStories(ownerId, petId).map { rows ->
            rows.map(ScheduledStoryEntity::toModel)
        }
    }

    override suspend fun claimScheduledStoryChoice(
        ownerId: String,
        storyId: String,
        requestKey: String,
        choice: String,
    ): ScheduledStoryChoiceClaim {
        LocalPersistenceValidation.ownerId(ownerId)
        LocalPersistenceValidation.storyId(storyId)
        LocalPersistenceValidation.requestKey(requestKey)
        return database.withTransaction {
            val current = dao.getScheduledStory(ownerId, storyId)?.toModel()
                ?: return@withTransaction ScheduledStoryChoiceClaim.Missing
            if (choice !in current.story.choices) return@withTransaction ScheduledStoryChoiceClaim.Conflict
            current.story.selectedChoice?.let {
                return@withTransaction if (it == choice && current.choiceRequestKey != null) {
                    ScheduledStoryChoiceClaim.Completed(current.story, current.choiceRequestKey)
                }
                else ScheduledStoryChoiceClaim.Conflict
            }
            current.choiceRequestKey?.let { winner ->
                return@withTransaction if (current.pendingChoice == choice) {
                    ScheduledStoryChoiceClaim.Existing(winner, choice)
                } else ScheduledStoryChoiceClaim.Conflict
            }
            if (dao.claimScheduledStoryChoice(ownerId, storyId, requestKey, choice) == 1) {
                ScheduledStoryChoiceClaim.Claimed(requestKey, choice)
            } else {
                val winner = dao.getScheduledStory(ownerId, storyId)?.toModel()
                    ?: return@withTransaction ScheduledStoryChoiceClaim.Missing
                if (winner.pendingChoice == choice && winner.choiceRequestKey != null) {
                    ScheduledStoryChoiceClaim.Existing(winner.choiceRequestKey, choice)
                } else ScheduledStoryChoiceClaim.Conflict
            }
        }
    }

    suspend fun getUnnotifiedNotifications(
        ownerId: String,
        petId: String,
    ): List<LocalCompletionNotification> {
        LocalPersistenceValidation.ownerId(ownerId)
        LocalPersistenceValidation.petId(petId)
        return database.withTransaction {
            buildList {
                dao.getUnnotifiedScheduledStories(ownerId, petId).forEach {
                    add(
                        LocalCompletionNotification(
                            LocalNotificationKind.ScheduledStory,
                            it.storyId,
                            it.title,
                            it.text.take(180),
                            it.storyId,
                        ),
                    )
                }
                dao.getUnnotifiedAppliedOutfitReceipts(ownerId, petId).forEach {
                    add(
                        LocalCompletionNotification(
                            LocalNotificationKind.OutfitReady,
                            it.requestKey,
                            "Новый образ готов",
                            "Загляни к питомцу и посмотри результат.",
                        ),
                    )
                }
                dao.getUnnotifiedTravelVideoAssets(ownerId, petId).forEach {
                    add(
                        LocalCompletionNotification(
                            kind = LocalNotificationKind.TravelReady,
                            stableKey = it.requestKey,
                            title = "Я вернулся из путешествия",
                            body = "Открой моё новое видео.",
                            travelRequestKey = it.requestKey,
                        ),
                    )
                }
                dao.getUnnotifiedProactiveNotifications(ownerId, petId).forEach {
                    add(
                        LocalCompletionNotification(
                            kind = LocalNotificationKind.Proactive,
                            stableKey = it.notificationId,
                            title = "Сообщение от питомца",
                            body = it.reply.take(180),
                        ),
                    )
                }
            }
        }
    }

    suspend fun markNotificationSent(
        ownerId: String,
        notification: LocalCompletionNotification,
        notifiedAtEpochMillis: Long,
    ): Boolean {
        LocalPersistenceValidation.ownerId(ownerId)
        require(notifiedAtEpochMillis >= 0)
        return when (notification.kind) {
            LocalNotificationKind.ScheduledStory -> {
                LocalPersistenceValidation.storyId(notification.stableKey)
                dao.markScheduledStoryNotified(
                    ownerId,
                    notification.stableKey,
                    notifiedAtEpochMillis,
                ) == 1
            }
            LocalNotificationKind.OutfitReady -> {
                LocalPersistenceValidation.requestKey(notification.stableKey)
                dao.markAppliedOutfitReceiptNotified(
                    ownerId,
                    notification.stableKey,
                    notifiedAtEpochMillis,
                ) == 1
            }
            LocalNotificationKind.TravelReady -> {
                LocalPersistenceValidation.requestKey(notification.stableKey)
                dao.markTravelVideoAssetNotified(
                    ownerId,
                    notification.stableKey,
                    notifiedAtEpochMillis,
                ) == 1
            }
            LocalNotificationKind.Proactive -> dao.markProactiveNotificationNotified(
                ownerId,
                notification.stableKey,
                notifiedAtEpochMillis,
            ) == 1
        }
    }

    suspend fun deleteOwnerData(ownerId: String) {
        LocalPersistenceValidation.ownerId(ownerId)
        database.withTransaction {
            dao.deleteOwnerPendingCreates(ownerId)
            dao.deleteOwnerPendingOutfits(ownerId)
            dao.deleteOwnerPendingTravels(ownerId)
            dao.deleteOwnerStoryReceipts(ownerId)
            dao.deleteOwnerScheduledStories(ownerId)
            dao.deleteOwnerMoodImages(ownerId)
            dao.deleteOwnerOutfitMoodImages(ownerId)
            dao.deleteOwnerOutfitMediaOutcomes(ownerId)
            dao.deleteOwnerAppliedOutfitReceipts(ownerId)
            dao.deleteOwnerTravelVideoAssets(ownerId)
            dao.deleteOwnerFirstSessionActionReceipts(ownerId)
            dao.deleteOwnerFirstSessions(ownerId)
            dao.deleteOwnerChatMessages(ownerId)
            dao.deleteOwnerUserMemories(ownerId)
            dao.deleteOwnerMemoryLearnings(ownerId)
            dao.deleteOwnerPetMemoryStates(ownerId)
            dao.deleteOwnerProactiveNotifications(ownerId)
            dao.deleteOwnerPets(ownerId)
        }
    }

    override suspend fun loadOwnerRecovery(ownerId: String): OwnerRecoveryData {
        LocalPersistenceValidation.ownerId(ownerId)
        return database.withTransaction {
            OwnerRecoveryData(
                petSnapshots = dao.getPets(ownerId).map { entity ->
                    entity.toModel(dao.getMoodImages(ownerId, entity.petId))
                },
                pendingCreates = dao.getPendingCreates(ownerId)
                    .map(PendingCreateGenerationEntity::toModel),
                pendingOutfits = dao.getPendingOutfits(ownerId).map(PendingOutfitEntity::toModel),
                pendingTravels = dao.getPendingTravels(ownerId)
                    .map(PendingTravelVideoEntity::toModel),
                storyReceipts = dao.getStoryReceipts(ownerId)
                    .map(AppliedStoryReceiptEntity::toModel),
                outfitOutcomes = dao.getPets(ownerId).flatMap { entity ->
                    dao.getOutfitMediaOutcomes(ownerId, entity.petId).map { outcome ->
                        val images = dao.getOutfitMoodImages(ownerId, outcome.requestKey)
                            .map(OutfitMoodImageEntity::toModel)
                        outcome.toModel(images)
                    }
                },
                travelVideoAssets = dao.getPets(ownerId).flatMap { entity ->
                    dao.getTravelVideoAssets(ownerId, entity.petId).map(TravelVideoAssetEntity::toModel)
                },
                firstSessions = dao.getFirstSessions(ownerId).map(FirstSessionEntity::toModel),
            )
        }
    }
}

object LocalPersistenceValidation {
    private const val OwnerIdMax = 255
    private const val PetIdMax = 160
    private const val RequestKeyMax = 160
    private const val JobIdMax = 255
    private const val AssetSetIdMax = 255
    private const val DescriptionMax = 1_000
    private const val NameMax = 160
    private const val StateLabelMax = 160
    private const val MessageMax = 4_000
    private const val PromptMax = 1_000
    private const val CreateAnswerMax = 300
    private const val ReceiptKeyMax = 255
    private const val TravelIdMax = 255
    private const val PartKeyMax = 160
    private const val StoryIdMax = 96

    fun ownerId(value: String) = identifier("ownerId", value, OwnerIdMax)
    fun petId(value: String) = identifier("petId", value, PetIdMax)
    fun requestKey(value: String) = identifier("requestKey", value, RequestKeyMax)
    fun backendJobId(value: String) = identifier("backendJobId", value, JobIdMax)
    fun receiptKey(value: String) = identifier("receiptKey", value, ReceiptKeyMax)
    fun storyId(value: String) = identifier("storyId", value, StoryIdMax)

    fun scheduledStory(value: LocalScheduledStory) {
        ownerId(value.ownerId)
        storyId(value.story.storyId)
        petId(value.story.petId)
        bounded("story title", value.story.title, 120)
        bounded("story text", value.story.text, 700)
        bounded("story question", value.story.question, 280)
        require(value.story.choices.size == 4 && value.story.choices.toSet().size == 4)
        value.story.choices.forEach { bounded("story choice", it, 280) }
        bounded("createdAt", value.story.createdAt, 80)
        listOfNotNull(
            value.story.imageUrl,
            value.story.videoUrl,
            value.story.resultImageUrl,
            value.story.resultVideoUrl,
        ).forEach { safeUrl("story media", it) }
        require((value.story.selectedChoice == null) == (value.story.result == null))
        require(value.story.selectedChoice == null || value.story.selectedChoice in value.story.choices)
        value.story.result?.let {
            bounded("result text", it.text, 700)
            bounded("result reaction", it.reaction, 220)
            bounded("result consequence", it.consequence, 500)
            require(it.experienceGained in 0..150)
        }
        require(value.pendingChoice == null || value.choiceRequestKey != null)
        require(value.pendingChoice == null || value.story.selectedChoice == null)
        value.choiceRequestKey?.let(::requestKey)
        require(value.pendingChoice == null || value.pendingChoice in value.story.choices)
    }

    fun backendState(ownerId: String, requestKey: String, errorCode: String?) {
        ownerId(ownerId)
        requestKey(requestKey)
        require(
            errorCode == null ||
                (errorCode.length in 1..80 && errorCode.all {
                    it.isLetterOrDigit() || it == '_' || it == '-'
                }),
        ) { "backend error code is invalid" }
    }

    fun petSnapshot(value: OwnedPetSnapshot) {
        ownerId(value.ownerId)
        petId(value.pet.petId)
        bounded("assetSetId", value.pet.assetSetId, AssetSetIdMax)
        bounded("description", value.pet.description, DescriptionMax)
        bounded("name", value.pet.name, NameMax)
        bounded("stage", value.pet.stage, StateLabelMax)
        bounded("stageLabel", value.pet.stageLabel, StateLabelMax)
        bounded("mood", value.pet.mood, StateLabelMax)
        require(value.pet.experience in 0..3_000) { "experience must be in 0..3000" }
        require(value.pet.hunger in 0..100) { "hunger must be in 0..100" }
        require(value.pet.happiness in 0..100) { "happiness must be in 0..100" }
        require(value.pet.energy in 0..100) { "energy must be in 0..100" }
        require(value.pet.petTapProgress in 0..4) { "petTapProgress must be in 0..4" }
        require(value.pet.message.length <= MessageMax) { "message is too long" }
        value.pet.generatedMedia.moodImages.forEach {
            bounded("media stage", it.stage, StateLabelMax)
            bounded("media mood", it.mood, StateLabelMax)
            safeUrl("media url", it.url)
        }
        listOfNotNull(
            value.pet.generatedMedia.videoUrl,
            value.pet.generatedMedia.sadVideoUrl,
            value.pet.generatedMedia.happyVideoUrl,
            value.pet.generatedMedia.blinkImageUrl,
            value.pet.generatedMedia.spriteSheetUrl,
        ).forEach { safeUrl("media url", it) }
        validateCharacterBible(value.pet.generatedMedia.characterBibleJson)
        timestamp("updatedAtEpochMillis", value.updatedAtEpochMillis)
    }

    fun pendingCreate(value: LocalPendingCreateGeneration) {
        ownerId(value.ownerId)
        petId(value.petId)
        requestKey(value.requestKey)
        optionalIdentifier("backendJobId", value.backendJobId, JobIdMax)
        bounded("description", value.description, CreateAnswerMax)
        optionalBounded("name", value.name, CreateAnswerMax)
        optionalBounded("personality", value.personality, CreateAnswerMax)
        optionalBounded("fear", value.fear, CreateAnswerMax)
        optionalBounded("favoriteItem", value.favoriteItem, CreateAnswerMax)
        require(value.currentStep in 1..5) { "currentStep must be in 1..5" }
        timestamp("updatedAtEpochMillis", value.updatedAtEpochMillis)
    }

    fun pendingOutfit(value: LocalPendingOutfit) {
        ownerId(value.ownerId)
        petId(value.petId)
        requestKey(value.requestKey)
        identifier("localJobId", value.localJobId, JobIdMax)
        optionalIdentifier("backendJobId", value.backendJobId, JobIdMax)
        bounded("prompt", value.prompt, PromptMax)
        bounded("baseAssetSetId", value.baseAssetSetId, AssetSetIdMax)
        require(value.experienceCost == OutfitAcceptanceCost) {
            "outfit experienceCost must be exactly $OutfitAcceptanceCost"
        }
        timestamp("acceptedAtEpochMillis", value.acceptedAtEpochMillis)
    }

    fun pendingTravel(value: LocalPendingTravelVideo) {
        ownerId(value.ownerId)
        petId(value.petId)
        requestKey(value.requestKey)
        identifier("localJobId", value.localJobId, JobIdMax)
        optionalIdentifier("backendJobId", value.backendJobId, JobIdMax)
        bounded("prompt", value.prompt, PromptMax)
        timestamp("acceptedAtEpochMillis", value.acceptedAtEpochMillis)
    }

    fun firstSessionAction(ownerId: String, petId: String, actionKey: String, destination: String?) {
        ownerId(ownerId)
        petId(petId)
        requestKey(actionKey)
        require(destination == null || destination.trim().length in 1..500)
    }

    fun storyReceipt(value: InteractiveStoryReceipt) {
        ownerId(value.ownerId)
        petId(value.petId)
        receiptKey(value.receiptKey)
        identifier("travelId", value.travelId, TravelIdMax)
        identifier("partKey", value.partKey, PartKeyMax)
        require(value.experienceDelta in -3_000..3_000) { "experienceDelta is out of bounds" }
        require(value.hungerDelta in -100..100) { "hungerDelta is out of bounds" }
        require(value.happinessDelta in -100..100) { "happinessDelta is out of bounds" }
        require(value.energyDelta in -100..100) { "energyDelta is out of bounds" }
        timestamp("appliedAtEpochMillis", value.appliedAtEpochMillis)
    }

    fun travelVideoAsset(value: LocalTravelVideoAsset) {
        ownerId(value.ownerId)
        petId(value.petId)
        requestKey(value.requestKey)
        backendJobId(value.backendJobId)
        bounded("prompt", value.prompt, PromptMax)
        value.imageUrl?.let { safeUrl("imageUrl", it) }
        safeUrl("videoUrl", value.videoUrl)
        timestamp("completedAtEpochMillis", value.completedAtEpochMillis)
        value.consumedAtEpochMillis?.let { timestamp("consumedAtEpochMillis", it) }
    }

    fun outfitMediaOutcome(value: LocalOutfitMediaOutcome) {
        ownerId(value.ownerId)
        petId(value.petId)
        requestKey(value.requestKey)
        backendJobId(value.backendJobId)
        bounded("displayItem", value.displayItem, PromptMax)
        bounded("assetSetId", value.assetSetId, AssetSetIdMax)
        value.media.moodImages.forEach { safeUrl("media url", it.url) }
        listOfNotNull(
            value.media.videoUrl,
            value.media.sadVideoUrl,
            value.media.happyVideoUrl,
            value.media.blinkImageUrl,
            value.media.spriteSheetUrl,
        ).forEach { safeUrl("media url", it) }
        validateCharacterBible(value.media.characterBibleJson)
        timestamp("completedAtEpochMillis", value.completedAtEpochMillis)
    }

    private fun validateCharacterBible(value: String?) {
        if (value == null) return
        require(value.toByteArray(Charsets.UTF_8).size <= CharacterBibleMaxUtf8Bytes) {
            "characterBible exceeds UTF-8 byte limit"
        }
        val root = Json.parseToJsonElement(value)
        require(root is JsonObject) { "characterBible must be a JSON object" }
        var nodes = 0
        fun visit(element: kotlinx.serialization.json.JsonElement, depth: Int) {
            require(depth <= 20) { "characterBible exceeds depth limit" }
            nodes += 1
            require(nodes <= 10_000) { "characterBible exceeds node limit" }
            when (element) {
                is JsonObject -> element.values.forEach { visit(it, depth + 1) }
                is JsonArray -> element.forEach { visit(it, depth + 1) }
                else -> Unit
            }
        }
        visit(root, 1)
    }

    private fun safeUrl(name: String, value: String) {
        val uri = java.net.URI(value)
        require(value.length <= 2_000 && uri.scheme == "https" && !uri.host.isNullOrBlank()) {
            "$name must be an HTTPS URL"
        }
    }

    private fun identifier(name: String, value: String, maxLength: Int) {
        require(value.isNotBlank() && value == value.trim() && value.length <= maxLength) {
            "$name must be trimmed, nonblank and at most $maxLength characters"
        }
    }

    private fun optionalIdentifier(name: String, value: String?, maxLength: Int) {
        if (value != null) identifier(name, value, maxLength)
    }

    private fun bounded(name: String, value: String, maxLength: Int) {
        require(value.isNotBlank() && value.length <= maxLength) {
            "$name must be nonblank and at most $maxLength characters"
        }
    }

    private fun optionalBounded(name: String, value: String?, maxLength: Int) {
        if (value != null) bounded(name, value, maxLength)
    }

    private fun timestamp(name: String, value: Long) {
        require(value >= 0L) { "$name must not be negative" }
    }
}

private fun checkedProgressValue(
    current: Int,
    delta: Int,
    allowed: IntRange,
): Int {
    val next = current.toLong() + delta.toLong()
    return next.coerceIn(allowed.first.toLong(), allowed.last.toLong()).toInt()
}

private fun ScheduledStory.withoutOutcome() = copy(
    selectedChoice = null,
    result = null,
    resultImageUrl = null,
    resultVideoUrl = null,
)

private fun LocalScheduledStory.toEntity() = ScheduledStoryEntity(
    ownerId = ownerId,
    storyId = story.storyId,
    petId = story.petId,
    title = story.title,
    text = story.text,
    question = story.question,
    choice0 = story.choices[0],
    choice1 = story.choices[1],
    choice2 = story.choices[2],
    choice3 = story.choices[3],
    createdAt = story.createdAt,
    imageUrl = story.imageUrl,
    videoUrl = story.videoUrl,
    choiceRequestKey = choiceRequestKey,
    pendingChoice = pendingChoice,
    selectedChoice = story.selectedChoice,
    resultText = story.result?.text,
    resultReaction = story.result?.reaction,
    resultConsequence = story.result?.consequence,
    resultExperienceGained = story.result?.experienceGained,
    resultImageUrl = story.resultImageUrl,
    resultVideoUrl = story.resultVideoUrl,
    notifiedAtEpochMillis = notifiedAtEpochMillis,
)

private fun ScheduledStoryEntity.toModel() = LocalScheduledStory(
    ownerId = ownerId,
    story = ScheduledStory(
        storyId = storyId,
        petId = petId,
        title = title,
        text = text,
        question = question,
        choices = listOf(choice0, choice1, choice2, choice3),
        createdAt = createdAt,
        imageUrl = imageUrl,
        videoUrl = videoUrl,
        selectedChoice = selectedChoice,
        result = resultText?.let {
            ScheduledStoryResult(
                text = it,
                reaction = requireNotNull(resultReaction),
                consequence = requireNotNull(resultConsequence),
                experienceGained = requireNotNull(resultExperienceGained),
            )
        },
        resultImageUrl = resultImageUrl,
        resultVideoUrl = resultVideoUrl,
    ),
    choiceRequestKey = choiceRequestKey,
    pendingChoice = pendingChoice,
    notifiedAtEpochMillis = notifiedAtEpochMillis,
).also(LocalPersistenceValidation::scheduledStory)

private fun OwnedPetSnapshot.toEntity() = PetSnapshotEntity(
    ownerId = ownerId,
    petId = pet.petId,
    assetSetId = pet.assetSetId,
    description = pet.description,
    name = pet.name,
    stage = pet.stage,
    stageLabel = pet.stageLabel,
    mood = pet.mood,
    experience = pet.experience,
    hunger = pet.hunger,
    happiness = pet.happiness,
    energy = pet.energy,
    message = pet.message,
    petTapProgress = pet.petTapProgress,
    generatedAt = pet.generatedMedia.generatedAt,
    videoUrl = pet.generatedMedia.videoUrl,
    sadVideoUrl = pet.generatedMedia.sadVideoUrl,
    happyVideoUrl = pet.generatedMedia.happyVideoUrl,
    blinkImageUrl = pet.generatedMedia.blinkImageUrl,
    spriteSheetUrl = pet.generatedMedia.spriteSheetUrl,
    characterBibleJson = pet.generatedMedia.characterBibleJson,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

private fun PetSnapshotEntity.toModel(images: List<PetMoodImageEntity>) = OwnedPetSnapshot(
    ownerId = ownerId,
    pet = PetDashboardState(
        petId = petId,
        assetSetId = assetSetId,
        description = description,
        name = name,
        stage = stage,
        stageLabel = stageLabel,
        mood = mood,
        experience = experience,
        hunger = hunger,
        happiness = happiness,
        energy = energy,
        message = message,
        petTapProgress = petTapProgress,
        generatedMedia = PetGeneratedMedia(
            generatedAt = generatedAt,
            videoUrl = videoUrl,
            sadVideoUrl = sadVideoUrl,
            happyVideoUrl = happyVideoUrl,
            blinkImageUrl = blinkImageUrl,
            spriteSheetUrl = spriteSheetUrl,
            characterBibleJson = characterBibleJson,
            moodImages = images.map(PetMoodImageEntity::toModel),
        ),
    ),
    updatedAtEpochMillis = updatedAtEpochMillis,
)

private fun LocalChatMessage.toEntity(ownerId: String, petId: String) = ChatMessageEntity(
    ownerId,
    petId,
    id,
    role,
    text.take(8_000),
    createdAtEpochMillis,
)

private fun ChatMessageEntity.toModel() = LocalChatMessage(
    messageId,
    role,
    text,
    createdAtEpochMillis,
)

private fun UserMemoryEntity.toModel() = LocalUserMemory(
    id = memoryId,
    kind = kind,
    text = text,
    normalizedKey = normalizedKey,
    confidence = confidence,
    importance = importance,
    memoryClass = memoryClass,
    recordedAtEpochMillis = recordedAtEpochMillis,
    occurredAtEpochMillis = occurredAtEpochMillis,
    dueAtEpochMillis = dueAtEpochMillis,
    expiresAtEpochMillis = expiresAtEpochMillis,
    lastMentionedAtEpochMillis = lastMentionedAtEpochMillis,
    mentionCount = mentionCount,
    tags = runCatching {
        Json.parseToJsonElement(tagsJson).jsonArray.mapNotNull {
            (it as? JsonPrimitive)?.contentOrNull
        }
    }.getOrDefault(emptyList()),
    updatedAtEpochMillis = updatedAtEpochMillis,
)

private fun MemoryLearningEntity.toModel() = LocalMemoryLearning(
    id = learningId,
    status = status,
    observation = observation,
    patternKey = patternKey,
    kind = kind,
    confidence = confidence,
    importance = importance,
    recurrenceCount = recurrenceCount,
    firstSeenAtEpochMillis = firstSeenAtEpochMillis,
    lastSeenAtEpochMillis = lastSeenAtEpochMillis,
    occurredAtEpochMillis = occurredAtEpochMillis,
    dueAtEpochMillis = dueAtEpochMillis,
)

private fun memoryClass(kind: String, normalizedKey: String): String = when {
    normalizedKey in setOf("user-name", "pet-nickname") || kind == "boundary" -> "core"
    kind in setOf("event", "emotion") -> "episode"
    else -> "fact"
}

private fun tagsJson(tags: List<String>): String = JsonArray(
    tags.map { JsonPrimitive(it.take(120)) },
).toString()

private fun JsonObject.text(key: String): String? =
    (get(key) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)

private fun JsonObject.number(key: String, fallback: Double): Double =
    (get(key) as? JsonPrimitive)?.doubleOrNull?.coerceIn(0.0, 1.0) ?: fallback

private fun JsonObject.instant(key: String): Long? = text(key)?.let { value ->
    runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
}

private fun localDateKey(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).toString().take(10)

private fun PetMoodImageEntity.toModel() = PetMoodImage(stage, mood, url)
private fun OutfitMoodImageEntity.toModel() = PetMoodImage(stage, mood, url)

private fun LocalTravelVideoAsset.toEntity() = TravelVideoAssetEntity(
    ownerId, petId, requestKey, backendJobId, prompt, title, scenario,
    imageUrl, videoUrl, completedAtEpochMillis, consumedAtEpochMillis, notifiedAtEpochMillis,
)

private fun TravelVideoAssetEntity.toModel() = LocalTravelVideoAsset(
    ownerId, petId, requestKey, backendJobId, prompt, title, scenario,
    imageUrl, videoUrl, completedAtEpochMillis, consumedAtEpochMillis, notifiedAtEpochMillis,
)

private fun LocalOutfitMediaOutcome.toEntity() = OutfitMediaOutcomeEntity(
    ownerId = ownerId,
    petId = petId,
    requestKey = requestKey,
    backendJobId = backendJobId,
    displayItem = displayItem,
    assetSetId = assetSetId,
    generatedAt = media.generatedAt,
    videoUrl = media.videoUrl,
    sadVideoUrl = media.sadVideoUrl,
    happyVideoUrl = media.happyVideoUrl,
    blinkImageUrl = media.blinkImageUrl,
    spriteSheetUrl = media.spriteSheetUrl,
    characterBibleJson = media.characterBibleJson,
    completedAtEpochMillis = completedAtEpochMillis,
)

private fun OutfitMediaOutcomeEntity.toModel(images: List<PetMoodImage>) = LocalOutfitMediaOutcome(
    ownerId = ownerId,
    petId = petId,
    requestKey = requestKey,
    backendJobId = backendJobId,
    displayItem = displayItem,
    assetSetId = assetSetId,
    media = PetGeneratedMedia(
        generatedAt, videoUrl, sadVideoUrl, happyVideoUrl, blinkImageUrl,
        spriteSheetUrl, characterBibleJson, images,
    ),
    completedAtEpochMillis = completedAtEpochMillis,
)

private fun LocalPendingCreateGeneration.toEntity() = PendingCreateGenerationEntity(
    ownerId = ownerId,
    petId = petId,
    requestKey = requestKey,
    backendJobId = backendJobId,
    stage = stage,
    description = description,
    name = name,
    personality = personality,
    fear = fear,
    favoriteItem = favoriteItem,
    currentStep = currentStep,
    updatedAtEpochMillis = updatedAtEpochMillis,
    backendState = backendState,
    backendErrorCode = backendErrorCode,
)

private fun PendingCreateGenerationEntity.toModel() = LocalPendingCreateGeneration(
    ownerId = ownerId,
    petId = petId,
    requestKey = requestKey,
    backendJobId = backendJobId,
    stage = stage,
    description = description,
    name = name,
    personality = personality,
    fear = fear,
    favoriteItem = favoriteItem,
    currentStep = currentStep,
    updatedAtEpochMillis = updatedAtEpochMillis,
    backendState = backendState,
    backendErrorCode = backendErrorCode,
)

private fun LocalPendingOutfit.toEntity() = PendingOutfitEntity(
    ownerId = ownerId,
    petId = petId,
    requestKey = requestKey,
    localJobId = localJobId,
    backendJobId = backendJobId,
    prompt = prompt,
    baseAssetSetId = baseAssetSetId,
    acceptedAtEpochMillis = acceptedAtEpochMillis,
    experienceCost = experienceCost,
    backendState = backendState,
    backendErrorCode = backendErrorCode,
    preparedDisplayItem = preparedDisplayItem,
)

private fun PendingOutfitEntity.toModel() = LocalPendingOutfit(
    ownerId = ownerId,
    petId = petId,
    requestKey = requestKey,
    localJobId = localJobId,
    backendJobId = backendJobId,
    prompt = prompt,
    baseAssetSetId = baseAssetSetId,
    acceptedAtEpochMillis = acceptedAtEpochMillis,
    experienceCost = experienceCost,
    backendState = backendState,
    backendErrorCode = backendErrorCode,
    preparedDisplayItem = preparedDisplayItem,
)

private fun LocalPendingTravelVideo.toEntity() = PendingTravelVideoEntity(
    ownerId = ownerId,
    petId = petId,
    requestKey = requestKey,
    localJobId = localJobId,
    backendJobId = backendJobId,
    prompt = prompt,
    acceptedAtEpochMillis = acceptedAtEpochMillis,
    backendState = backendState,
    backendErrorCode = backendErrorCode,
)

private fun PendingTravelVideoEntity.toModel() = LocalPendingTravelVideo(
    ownerId = ownerId,
    petId = petId,
    requestKey = requestKey,
    localJobId = localJobId,
    backendJobId = backendJobId,
    prompt = prompt,
    acceptedAtEpochMillis = acceptedAtEpochMillis,
    backendState = backendState,
    backendErrorCode = backendErrorCode,
)

private fun InteractiveStoryReceipt.toEntity() = AppliedStoryReceiptEntity(
    ownerId = ownerId,
    petId = petId,
    receiptKey = receiptKey,
    travelId = travelId,
    partKey = partKey,
    experienceDelta = experienceDelta,
    hungerDelta = hungerDelta,
    happinessDelta = happinessDelta,
    energyDelta = energyDelta,
    appliedAtEpochMillis = appliedAtEpochMillis,
)

private fun AppliedStoryReceiptEntity.toModel() = InteractiveStoryReceipt(
    ownerId = ownerId,
    petId = petId,
    receiptKey = receiptKey,
    travelId = travelId,
    partKey = partKey,
    experienceDelta = experienceDelta,
    hungerDelta = hungerDelta,
    happinessDelta = happinessDelta,
    energyDelta = energyDelta,
    appliedAtEpochMillis = appliedAtEpochMillis,
)

private fun FirstSessionEntity.toModel() = LocalFirstSession(
    ownerId = ownerId,
    petId = petId,
    stage = FirstSessionStage.fromStorage(stage),
    selectedDestination = selectedDestination,
    lastActionKey = lastActionKey,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

private fun isAllowedFirstSessionTransition(
    expected: FirstSessionStage,
    next: FirstSessionStage,
): Boolean = next in when (expected) {
    FirstSessionStage.AwaitingChat -> setOf(FirstSessionStage.AwaitingChatFollowup)
    FirstSessionStage.AwaitingChatFollowup -> setOf(FirstSessionStage.AwaitingFirstFood)
    FirstSessionStage.AwaitingFirstFood -> setOf(FirstSessionStage.AwaitingRemedy)
    FirstSessionStage.AwaitingRemedy -> setOf(FirstSessionStage.AwaitingRemedy, FirstSessionStage.AwaitingTravel)
    FirstSessionStage.AwaitingTravel -> setOf(
        FirstSessionStage.ConfirmingTravel,
        FirstSessionStage.AwaitingCompletionMessage,
    )
    FirstSessionStage.ConfirmingTravel -> setOf(FirstSessionStage.AwaitingTravel)
    FirstSessionStage.AwaitingCompletionMessage -> setOf(FirstSessionStage.Completed)
    FirstSessionStage.Completed -> emptySet()
}
