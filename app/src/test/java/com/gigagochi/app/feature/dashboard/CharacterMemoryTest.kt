package com.gigagochi.app.feature.dashboard

import com.gigagochi.app.core.database.LocalChatMessage
import com.gigagochi.app.core.database.LocalCharacterExperience
import com.gigagochi.app.core.database.LocalPetMemoryState
import com.gigagochi.app.core.database.LocalUserMemory
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterMemoryTest {
    private val zone = ZoneId.of("Europe/Moscow")
    private val now = LocalDate.of(2026, 7, 19).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun shortSelfIntroductionBecomesCoreNameMemory() {
        val changes = extractDeterministicMemory("Я Серёбра", now, zone)

        assertEquals(1, changes.facts.size)
        assertEquals("user-name", changes.facts.single().normalizedKey)
        assertEquals("Пользователя зовут Серёбра.", changes.facts.single().text)
    }

    @Test
    fun identityQuestionSelectsRememberedName() {
        val name = memory(
            id = "name-memory",
            kind = "user_fact",
            text = "Пользователя зовут Серёбра.",
            key = "user-name",
            memoryClass = "core",
        )

        val context = buildChatMemoryContext(
            LocalPetMemoryState(memories = listOf(name)),
            emptyList(),
            "Как меня зовут?",
            now,
        )

        assertEquals(listOf("name-memory"), context.relevantMemories.map { it.id })
    }

    @Test
    fun recentCharacterExperiencesAreAlwaysIncludedInChatMemory() {
        val experiences = listOf(
            LocalCharacterExperience(
                id = "character-travel:travel-1",
                kind = "character_travel",
                text = "Недавнее путешествие персонажа: Ночной рынок.",
                occurredAtEpochMillis = now - 2_000,
            ),
            LocalCharacterExperience(
                id = "character-outfit:outfit-1",
                kind = "character_outfit",
                text = "Недавнее переодевание персонажа: плащ.",
                occurredAtEpochMillis = now - 1_000,
            ),
        )

        val context = buildChatMemoryContext(
            LocalPetMemoryState(),
            emptyList(),
            "Как дела?",
            now,
            experiences,
        )

        assertEquals(experiences.map { it.id }, context.relevantMemories.map { it.id })
        assertTrue(context.relevantMemories.all { it.memoryClass == "episode" })
        assertTrue(context.relevantMemories.all { it.occurredAt != null })
    }

    @Test
    fun tomorrowDeadlineBecomesDueProactiveQuestionOnNextDay() {
        val changes = extractDeterministicMemory("У меня завтра экзамен по физике", now, zone)
        val deadline = changes.facts.single()
        val tomorrowNoon = LocalDate.of(2026, 7, 20).atTime(12, 0).atZone(zone)
            .toInstant().toEpochMilli()
        val context = requireNotNull(buildDailyProactiveContext(
            LocalPetMemoryState(
                memories = listOf(
                    memory(
                        id = "exam",
                        kind = deadline.kind,
                        text = deadline.text,
                        key = deadline.normalizedKey,
                        dueAt = deadline.dueAtEpochMillis,
                    ),
                ),
            ),
            history(),
            tomorrowNoon,
            zone,
        ))

        assertNotNull(deadline.dueAtEpochMillis)
        assertNotNull(context)
        assertEquals(listOf("exam"), context.proactiveCandidate?.memoryIds)
        assertTrue(context.proactiveCandidate?.reason.orEmpty().contains("экзамен"))
    }

    @Test
    fun proactiveContextAlwaysIncludesRememberedUserName() {
        val context = requireNotNull(buildDailyProactiveContext(
            LocalPetMemoryState(
                memories = listOf(
                    memory(
                        id = "name-memory",
                        kind = "user_fact",
                        text = "Пользователя зовут Серёбра.",
                        key = "user-name",
                        memoryClass = "core",
                    ),
                ),
            ),
            history(),
            now,
            zone,
        ))

        assertEquals(listOf("name-memory"), context.relevantMemories.map { it.id })
    }

    @Test
    fun proactiveDoesNotAskForNameThatIsAlreadyRemembered() {
        val memory = LocalPetMemoryState(
            memories = listOf(
                memory(
                    id = "name-memory",
                    kind = "user_fact",
                    text = "Пользователя зовут Серёбра.",
                    key = "user-name",
                    memoryClass = "core",
                ),
            ),
        )

        assertEquals(
            "Серёбра, ты снова здесь",
            sanitizeProactiveReply("Привет! Как тебя зовут?", memory, now),
        )
        assertEquals(
            "Я помню, как тебя зовут",
            sanitizeProactiveReply("я помню, как тебя зовут", memory, now),
        )
    }

    @Test
    fun ambientContextAlwaysIncludesRememberedOwnerName() {
        val memory = LocalPetMemoryState(
            memories = listOf(
                memory("other", "preference", "Пользователь любит чай.", "tea"),
                memory(
                    id = "name-memory",
                    kind = "user_fact",
                    text = "Пользователя зовут Серёбра.",
                    key = "user-name",
                    memoryClass = "core",
                ),
            ),
        )

        val context = buildAmbientMemoryContext(memory, now)

        assertEquals("name-memory", context.relevantMemories.first().id)
        assertEquals("Пользователя зовут Серёбра.", context.relevantMemories.first().text)
    }

    @Test
    fun proactiveRunsOnlyOncePerLocalDay() {
        val memory = memory("exam", "deadline", "У пользователя экзамен.", "deadline-exam", dueAt = now)
        val alreadySent = LocalPetMemoryState(
            lastProactiveAtEpochMillis = now,
            memories = listOf(memory),
        )

        assertNull(buildDailyProactiveContext(alreadySent, history(), now + 60_000, zone))
    }

    private fun history() = listOf(
        LocalChatMessage("u1", "user", "У меня завтра экзамен", now - 60 * 60_000),
        LocalChatMessage("p1", "pet", "Я буду держать за тебя лапки.", now - 59 * 60_000),
    )

    private fun memory(
        id: String,
        kind: String,
        text: String,
        key: String,
        memoryClass: String = "fact",
        dueAt: Long? = null,
    ) = LocalUserMemory(
        id = id,
        kind = kind,
        text = text,
        normalizedKey = key,
        confidence = .9,
        importance = 1.0,
        memoryClass = memoryClass,
        recordedAtEpochMillis = now,
        dueAtEpochMillis = dueAt,
    )
}
