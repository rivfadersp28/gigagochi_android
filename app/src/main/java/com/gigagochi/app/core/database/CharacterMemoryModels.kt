package com.gigagochi.app.core.database

data class LocalChatMessage(
    val id: String,
    val role: String,
    val text: String,
    val createdAtEpochMillis: Long,
)

data class LocalUserMemory(
    val id: String,
    val kind: String,
    val text: String,
    val normalizedKey: String,
    val confidence: Double,
    val importance: Double,
    val memoryClass: String,
    val recordedAtEpochMillis: Long,
    val occurredAtEpochMillis: Long? = null,
    val dueAtEpochMillis: Long? = null,
    val expiresAtEpochMillis: Long? = null,
    val lastMentionedAtEpochMillis: Long? = null,
    val mentionCount: Int = 0,
    val tags: List<String> = emptyList(),
    val updatedAtEpochMillis: Long = recordedAtEpochMillis,
)

data class LocalMemoryLearning(
    val id: String,
    val status: String,
    val observation: String,
    val patternKey: String? = null,
    val kind: String? = null,
    val confidence: Double,
    val importance: Double,
    val recurrenceCount: Int,
    val firstSeenAtEpochMillis: Long,
    val lastSeenAtEpochMillis: Long,
    val occurredAtEpochMillis: Long? = null,
    val dueAtEpochMillis: Long? = null,
)

data class LocalPetMemoryState(
    val summary: String? = null,
    val userProfile: String? = null,
    val lastExtractionAtEpochMillis: Long? = null,
    val lastConsolidationAtEpochMillis: Long? = null,
    val lastProactiveAtEpochMillis: Long? = null,
    val proactiveLogJson: String = "[]",
    val memories: List<LocalUserMemory> = emptyList(),
    val learnings: List<LocalMemoryLearning> = emptyList(),
)

data class LocalCharacterExperience(
    val id: String,
    val kind: String,
    val text: String,
    val occurredAtEpochMillis: Long,
    val memoryClass: String = "episode",
)

data class DeterministicMemoryFact(
    val kind: String,
    val text: String,
    val normalizedKey: String,
    val confidence: Double,
    val importance: Double,
    val dueAtEpochMillis: Long? = null,
    val tags: List<String> = emptyList(),
)
