package com.gigagochi.app.debug

import com.gigagochi.app.core.network.StaticMediaUrlPolicy
import com.gigagochi.app.core.network.toFeaturePetDto
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class DebugTestPetFixtureTest {
    @Test
    fun fixtureMatchesWebIdentityMediaAndFeaturePayload() {
        val pet = debugTestPetFixture()
        val policy = StaticMediaUrlPolicy("$DebugTestPetOrigin/", true)

        assertEquals(DebugTestPetId, pet.petId)
        assertEquals(DebugTestPetAssetSetId, pet.assetSetId)
        assertEquals("Человек-яблоко", pet.description)
        assertEquals("Тото", pet.name)
        assertEquals(DebugTestExperienceBalance, pet.experience)
        assertEquals(0, pet.experience)
        assertEquals(listOf("adult", "baby", "teen"), pet.generatedMedia.moodImages.map { it.stage }.distinct().sorted())
        listOf("adult", "baby", "teen").forEach { stage ->
            assertEquals(
                setOf("idle", "sad", "happy", "hungry"),
                pet.generatedMedia.moodImages.filter { it.stage == stage }.map { it.mood }.toSet(),
            )
        }
        (pet.generatedMedia.moodImages.map { it.url } + listOfNotNull(
            pet.generatedMedia.videoUrl,
            pet.generatedMedia.sadVideoUrl,
            pet.generatedMedia.happyVideoUrl,
        )).forEach { assertNotNull(it, policy.resolve(it)) }

        val dto = pet.toFeaturePetDto()
        assertEquals(setOf("baby", "teen", "adult"), dto.assetImages?.keys)
        val identity = dto.characterBible?.get("identity")?.jsonObject
        assertEquals("маскот-существо-яблоко", identity?.get("species")?.jsonPrimitive?.content)
    }
}
