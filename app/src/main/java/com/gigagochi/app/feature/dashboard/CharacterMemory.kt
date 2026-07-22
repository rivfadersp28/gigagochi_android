package com.gigagochi.app.feature.dashboard

import com.gigagochi.app.core.database.DeterministicMemoryFact
import com.gigagochi.app.core.database.LocalChatMessage
import com.gigagochi.app.core.database.LocalCharacterExperience
import com.gigagochi.app.core.database.LocalPetMemoryState
import com.gigagochi.app.core.database.LocalUserMemory
import com.gigagochi.app.core.network.ChatHistoryItemDto
import com.gigagochi.app.core.network.ChatMemoryEpisodeDto
import com.gigagochi.app.core.network.MemoryContextDto
import com.gigagochi.app.core.network.MemoryContextItemDto
import com.gigagochi.app.core.network.ProactiveCandidateDto
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

private const val DayMillis = 86_400_000L
private const val EpisodeCooldownMillis = 14L * DayMillis
private val UnsafeMemory = Regex(
    "ignore previous|system prompt|developer message|api[_-]?key|bearer|token|парол|секрет|ключ|промпт|инструкц",
    RegexOption.IGNORE_CASE,
)
private val NameStopWords = setOf(
    "голодный", "голодная", "устал", "устала", "болен", "больна", "дома", "тут",
    "здесь", "рядом", "готов", "готова", "занят", "занята",
)
private val RecallQuestion = Regex(
    "помнишь|запомнил|кто я|ты знаешь кто я|как меня зовут|что я люблю|что мне нравится|моя цель|мои планы",
    RegexOption.IGNORE_CASE,
)
private val IdentityRecall = Regex("кто я|как меня зовут|ты знаешь кто я", RegexOption.IGNORE_CASE)
private val UserNameQuestion = Regex(
    "^(?:(?:а|ну|кстати|слушай|эй)\\s+)*(?:(?:напомни|скажи)(?:\\s+мне)?[,!:]?\\s+|" +
        "мне\\s+интересно[,!:]?\\s+)?(?:как\\s+(?:мне\\s+)?тебя\\s+" +
        "(?:зовут|звать|называть)|как\\s+к\\s+тебе\\s+обращаться|тво[её]\\s+имя)",
    RegexOption.IGNORE_CASE,
)
private val RememberedUserName = Regex(
    "(?iu)зовут\\s+([\\p{L}\\p{N}_-]{2,32})",
)
private val TokenRegex = Regex("[\\p{L}\\p{N}]{3,}")
private val StopWords = setOf(
    "меня", "мне", "мой", "моя", "мои", "это", "что", "как", "зовут", "где", "когда",
    "почему", "тебя", "тебе", "твой", "твоя", "про", "для", "или", "еще", "ещё",
)

data class DeterministicMemoryChanges(
    val facts: List<DeterministicMemoryFact> = emptyList(),
    val forgetKey: String? = null,
    val forgetText: String? = null,
)

data class PreparedCharacterChat(
    val userMessage: LocalChatMessage,
    val priorHistory: List<LocalChatMessage>,
    val memoryState: LocalPetMemoryState,
    val memoryContext: MemoryContextDto,
)

fun extractDeterministicMemory(
    message: String,
    nowEpochMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
): DeterministicMemoryChanges {
    val text = clean(message, 500)
    if (text.isBlank() || UnsafeMemory.containsMatchIn(text)) return DeterministicMemoryChanges()
    val forget = Regex(
        "(?:^|\\b)(?:забудь|не запоминай|больше не помни)(?:\\s+про|\\s+о|[:,])?\\s*(.+)$",
        RegexOption.IGNORE_CASE,
    ).find(text)?.groupValues?.getOrNull(1)?.let { clean(it, 140) }
    if (!forget.isNullOrBlank()) {
        if (Regex("мо[её] имя|как меня зовут", RegexOption.IGNORE_CASE).matches(forget)) {
            return DeterministicMemoryChanges(forgetKey = "user-name")
        }
        if (Regex("вс[её] обо мне|всю память обо мне", RegexOption.IGNORE_CASE).matches(forget)) {
            return DeterministicMemoryChanges(forgetKey = "*")
        }
        return DeterministicMemoryChanges(forgetText = forget)
    }

    val facts = mutableListOf<DeterministicMemoryFact>()
    val explicitName = Regex(
        "(?:^|\\b)(?:меня (?:теперь |сейчас )?зовут|зови меня|можешь звать меня)\\s+([\\p{L}\\p{N}_-]{2,32})",
        RegexOption.IGNORE_CASE,
    ).find(text)?.groupValues?.getOrNull(1)
    val shortName = Regex("^я\\s+([\\p{L}_-]{2,32})$", RegexOption.IGNORE_CASE)
        .find(text)?.groupValues?.getOrNull(1)
        ?.takeUnless { it.lowercase() in NameStopWords }
    (explicitName ?: shortName)?.let { name ->
        facts += fact("user_fact", "Пользователя зовут ${clean(name, 40)}.", "user-name", 1.0)
    }

    Regex(
        "(?:^|\\b)у меня завтра ((?:экзамен|зач[её]т|контрольная|собеседование|встреча|дедлайн|защита)(?:\\s+[^.!?…]{0,80})?)",
        RegexOption.IGNORE_CASE,
    ).find(text)?.groupValues?.getOrNull(1)?.let { event ->
        val cleanEvent = clean(event, 120)
        val dueAt = LocalDate.ofInstant(Instant.ofEpochMilli(nowEpochMillis), zoneId)
            .plusDays(1)
            .atStartOfDay(zoneId)
            .toInstant()
            .toEpochMilli()
        facts += fact(
            "deadline",
            "У пользователя завтра $cleanEvent.",
            "deadline-${key(cleanEvent)}",
            1.0,
            dueAt,
            listOf(key(cleanEvent).substringBefore('-')),
        )
    }

    Regex("(?:^|\\b)(?:я люблю|мне нравятся|мне нравится)\\s+(.+)$", RegexOption.IGNORE_CASE)
        .find(text)?.groupValues?.getOrNull(1)?.let { value ->
            val liked = clean(value, 140)
            facts += fact("preference", "Пользователь любит $liked.", "preference-${key(liked)}", .75)
        }
    Regex("(?:^|\\b)я (?:больше |теперь |сейчас )?не люблю\\s+(.+)$", RegexOption.IGNORE_CASE)
        .find(text)?.groupValues?.getOrNull(1)?.let { value ->
            val disliked = clean(value, 140)
            facts += fact(
                "preference",
                "Пользователь не любит $disliked.",
                "preference-${key(disliked)}",
                .8,
            )
        }
    Regex("(?:^|\\b)(?:не шути|не говори|не упоминай)\\s+(?:про|о|об)?\\s*(.+)$", RegexOption.IGNORE_CASE)
        .find(text)?.groupValues?.getOrNull(1)?.let { value ->
            val boundary = clean(value, 140)
            facts += fact(
                "boundary",
                "Не шутить и не говорить про $boundary.",
                "boundary-${key(boundary)}",
                1.0,
            )
        }
    Regex("(?:^|\\b)запомни[:,]?\\s+(.+)$", RegexOption.IGNORE_CASE)
        .find(text)?.groupValues?.getOrNull(1)?.let { value ->
            val memory = clean(value, 180)
            facts += fact(
                "user_fact",
                "Пользователь просит запомнить: $memory.",
                "remember-${key(memory)}",
                .85,
            )
        }
    return DeterministicMemoryChanges(facts.distinctBy { it.normalizedKey })
}

fun buildChatMemoryContext(
    memory: LocalPetMemoryState,
    history: List<LocalChatMessage>,
    message: String,
    nowEpochMillis: Long,
    characterExperiences: List<LocalCharacterExperience> = emptyList(),
): MemoryContextDto {
    val query = tokens(message)
    val explicitRecall = RecallQuestion.containsMatchIn(message)
    val selected = memory.memories
        .asSequence()
        .filter { it.expiresAtEpochMillis == null || it.expiresAtEpochMillis > nowEpochMillis }
        .map { item -> item to memoryScore(item, message, query, explicitRecall) }
        .filter { it.second > 0.0 }
        .sortedWith(compareByDescending<Pair<LocalUserMemory, Double>> { it.second }
            .thenByDescending { it.first.updatedAtEpochMillis })
        .take(5)
        .map { it.first }
        .toList()
    val episodes = if (history.size <= 12 || query.isEmpty()) emptyList() else {
        history.dropLast(12).mapIndexedNotNull { index, item ->
            val overlap = tokens(item.text).count(query::contains)
            if (overlap == 0) null else index to overlap
        }.sortedByDescending { it.second }.take(3).map { (index, _) ->
            val start = (index - 2).coerceAtLeast(0)
            val end = (index + 3).coerceAtMost(history.size)
            val messages = history.subList(start, end)
            ChatMemoryEpisodeDto(
                "episode:${messages.first().id}:${messages.last().id}",
                messages.map(LocalChatMessage::toDto),
            )
        }
    }
    return MemoryContextDto(
        summary = memory.summary,
        userProfile = memory.userProfile,
        relevantMemories = selected.map(LocalUserMemory::toContextDto) +
            characterExperiences.map(LocalCharacterExperience::toContextDto),
        episodes = episodes,
    )
}

fun buildDailyProactiveContext(
    memory: LocalPetMemoryState,
    history: List<LocalChatMessage>,
    nowEpochMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
    characterExperiences: List<LocalCharacterExperience> = emptyList(),
): MemoryContextDto? {
    val today = LocalDate.ofInstant(Instant.ofEpochMilli(nowEpochMillis), zoneId)
    val lastDay = memory.lastProactiveAtEpochMillis?.let {
        LocalDate.ofInstant(Instant.ofEpochMilli(it), zoneId)
    }
    if (lastDay == today) return null
    if (history.any { it.role == "pet" && nowEpochMillis - it.createdAtEpochMillis < 30 * 60_000L }) {
        return null
    }
    val knownUserName = memory.activeUserNameMemories(nowEpochMillis).take(1)
    val due = memory.memories.filter { item ->
        val dueAt = item.dueAtEpochMillis ?: return@filter false
        val dueDay = LocalDate.ofInstant(Instant.ofEpochMilli(dueAt), zoneId)
        dueDay <= today && (
            item.lastMentionedAtEpochMillis == null ||
                item.lastMentionedAtEpochMillis < dueAt
            )
    }.sortedWith(compareByDescending<LocalUserMemory> { it.importance }
        .thenBy { it.dueAtEpochMillis })
        .take(5)
    if (due.isNotEmpty()) {
        val reason = due.joinToString("; ") { "мягко уточнить: ${it.text}" }.take(280)
        return MemoryContextDto(
            summary = memory.summary,
            userProfile = memory.userProfile,
            relevantMemories = (knownUserName + due)
                .distinctBy(LocalUserMemory::id)
                .map(LocalUserMemory::toContextDto) +
                characterExperiences.map(LocalCharacterExperience::toContextDto),
            proactiveCandidate = ProactiveCandidateDto(due.map { it.id }, reason = reason),
        )
    }
    val lastUserIndex = history.indexOfLast { it.role == "user" }
    if (lastUserIndex < 0) return null
    val lastText = history[lastUserIndex].text
    if (IdentityRecall.containsMatchIn(lastText)) return null
    val start = (lastUserIndex - 2).coerceAtLeast(0)
    val end = (lastUserIndex + 3).coerceAtMost(history.size)
    val episodeMessages = history.subList(start, end)
    val episode = ChatMemoryEpisodeDto(
        "episode:${episodeMessages.first().id}:${episodeMessages.last().id}",
        episodeMessages.map(LocalChatMessage::toDto),
    )
    return MemoryContextDto(
        summary = memory.summary,
        userProfile = memory.userProfile,
        relevantMemories = knownUserName.map(LocalUserMemory::toContextDto) +
            characterExperiences.map(LocalCharacterExperience::toContextDto),
        episodes = listOf(episode),
        proactiveCandidate = ProactiveCandidateDto(
            episodeIds = listOf(episode.id),
            reason = "продолжить недавний разговор: ${lastText.take(180)}",
        ),
    )
}

fun buildAmbientMemoryContext(
    memory: LocalPetMemoryState,
    nowEpochMillis: Long,
    characterExperiences: List<LocalCharacterExperience> = emptyList(),
): MemoryContextDto {
    val activeMemories = memory.memories
        .asSequence()
        .filter { it.expiresAtEpochMillis == null || it.expiresAtEpochMillis > nowEpochMillis }
        .sortedWith(
            compareByDescending<LocalUserMemory> { it.normalizedKey == "user-name" }
                .thenByDescending { it.importance }
                .thenByDescending { it.updatedAtEpochMillis },
        )
        .map(LocalUserMemory::toContextDto)
    val experiences = characterExperiences.asSequence().map(LocalCharacterExperience::toContextDto)
    return MemoryContextDto(
        summary = memory.summary,
        userProfile = memory.userProfile,
        relevantMemories = (activeMemories + experiences).take(5).toList(),
    )
}

internal fun sanitizeProactiveReply(
    reply: String,
    memory: LocalPetMemoryState,
    nowEpochMillis: Long = System.currentTimeMillis(),
): String {
    val normalized = reply.trim().withCapitalizedSentenceStarts()
    val knownUserName = memory.activeUserNameMemories(nowEpochMillis).firstOrNull()
        ?: return normalized
    if (!normalized.asksForUserName()) return normalized
    val name = RememberedUserName.find(knownUserName.text)?.groupValues?.getOrNull(1)
    return name?.let { "$it, ты снова здесь" } ?: "Хорошо, что ты снова здесь"
}

fun existingMemoryBrief(memory: LocalPetMemoryState): String = buildString {
    memory.summary?.let { append("Summary: ").append(it).append('\n') }
    memory.userProfile?.let { append("User profile: ").append(it).append('\n') }
    memory.memories.take(50).forEach {
        append("- [").append(it.normalizedKey).append("] ").append(it.text).append('\n')
    }
}.take(4_000)

fun pendingLearningPayloads(memory: LocalPetMemoryState): List<JsonObject> = memory.learnings
    .filter { it.status == "pending" }
    .take(100)
    .map { learning ->
        JsonObject(
            buildMap {
                put("id", JsonPrimitive(learning.id))
                put("status", JsonPrimitive(learning.status))
                put("observation", JsonPrimitive(learning.observation))
                learning.patternKey?.let { put("patternKey", JsonPrimitive(it)) }
                learning.kind?.let { put("kind", JsonPrimitive(it)) }
                put("confidence", JsonPrimitive(learning.confidence))
                put("importance", JsonPrimitive(learning.importance))
                put("recurrenceCount", JsonPrimitive(learning.recurrenceCount))
                put(
                    "firstSeenAt",
                    JsonPrimitive(Instant.ofEpochMilli(learning.firstSeenAtEpochMillis).toString()),
                )
                put(
                    "lastSeenAt",
                    JsonPrimitive(Instant.ofEpochMilli(learning.lastSeenAtEpochMillis).toString()),
                )
                learning.occurredAtEpochMillis?.let {
                    put("occurredAt", JsonPrimitive(Instant.ofEpochMilli(it).toString()))
                }
                learning.dueAtEpochMillis?.let {
                    put("dueAt", JsonPrimitive(Instant.ofEpochMilli(it).toString()))
                }
            },
        )
    }

fun existingMemoryPayloads(memory: LocalPetMemoryState): List<JsonObject> = memory.memories
    .take(80)
    .map { item ->
        JsonObject(
            buildMap {
                put("id", JsonPrimitive(item.id))
                put("kind", JsonPrimitive(item.kind))
                put("text", JsonPrimitive(item.text))
                put("normalizedKey", JsonPrimitive(item.normalizedKey))
                put("confidence", JsonPrimitive(item.confidence))
                put("importance", JsonPrimitive(item.importance))
                put("memoryClass", JsonPrimitive(item.memoryClass))
                put(
                    "recordedAt",
                    JsonPrimitive(Instant.ofEpochMilli(item.recordedAtEpochMillis).toString()),
                )
                put("createdAt", JsonPrimitive(Instant.ofEpochMilli(item.recordedAtEpochMillis).toString()))
                put("updatedAt", JsonPrimitive(Instant.ofEpochMilli(item.updatedAtEpochMillis).toString()))
                put("mentionCount", JsonPrimitive(item.mentionCount))
            },
        )
    }

private fun memoryScore(
    memory: LocalUserMemory,
    message: String,
    query: Set<String>,
    explicitRecall: Boolean,
): Double {
    if (IdentityRecall.containsMatchIn(message) && memory.normalizedKey == "user-name") {
        return 10.0
    }
    val overlap = tokens(memory.text).count(query::contains)
    if (overlap > 0) return overlap + memory.importance
    if (explicitRecall && (
        memory.memoryClass == "core" || memory.kind in setOf("preference", "goal", "promise", "boundary")
    )) return 2.0 + memory.importance
    if (memory.memoryClass == "episode" && memory.lastMentionedAtEpochMillis != null &&
        System.currentTimeMillis() - memory.lastMentionedAtEpochMillis < EpisodeCooldownMillis
    ) return 0.0
    return 0.0
}

private fun LocalChatMessage.toDto() = ChatHistoryItemDto(
    role,
    text,
    Instant.ofEpochMilli(createdAtEpochMillis).toString(),
)

private fun LocalUserMemory.toContextDto() = MemoryContextItemDto(
    id = id,
    kind = kind,
    text = text,
    memoryClass = memoryClass,
    recordedAt = Instant.ofEpochMilli(recordedAtEpochMillis).toString(),
    occurredAt = occurredAtEpochMillis?.let { Instant.ofEpochMilli(it).toString() },
    lastMentionedAt = lastMentionedAtEpochMillis?.let { Instant.ofEpochMilli(it).toString() },
    dueAt = dueAtEpochMillis?.let { Instant.ofEpochMilli(it).toString() },
)

private fun LocalCharacterExperience.toContextDto() = MemoryContextItemDto(
    id = id,
    kind = kind,
    text = text,
    memoryClass = "episode",
    recordedAt = Instant.ofEpochMilli(occurredAtEpochMillis).toString(),
    occurredAt = Instant.ofEpochMilli(occurredAtEpochMillis).toString(),
)

private fun LocalPetMemoryState.activeUserNameMemories(nowEpochMillis: Long): List<LocalUserMemory> =
    memories.filter {
        it.normalizedKey == "user-name" &&
            (it.expiresAtEpochMillis == null || it.expiresAtEpochMillis > nowEpochMillis)
    }

private fun String.asksForUserName(): Boolean = split(Regex("[.!?…]+"))
    .any { UserNameQuestion.containsMatchIn(it.trim()) }

private fun fact(
    kind: String,
    text: String,
    key: String,
    importance: Double,
    dueAt: Long? = null,
    tags: List<String> = emptyList(),
) = DeterministicMemoryFact(kind, text, key, .9, importance, dueAt, tags)

private fun clean(value: String, limit: Int): String = value.trim()
    .trim('"', '\'', '«', '»', '“', '”', '„')
    .replace(Regex("\\s+"), " ")
    .replace(Regex("[.!?…]+$"), "")
    .take(limit)
    .trim()

private fun key(value: String): String = value.lowercase()
    .replace(Regex("[^\\p{L}\\p{N}]+"), "-")
    .trim('-')
    .take(160)
    .ifBlank { "memory" }

private fun tokens(value: String): Set<String> = TokenRegex.findAll(value.lowercase())
    .map { it.value }
    .filterNot(StopWords::contains)
    .toSet()

fun newChatMessage(role: String, text: String, nowEpochMillis: Long) = LocalChatMessage(
    UUID.randomUUID().toString(),
    role,
    text,
    nowEpochMillis,
)
