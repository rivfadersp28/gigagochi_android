package com.gigagochi.app.debug

import com.gigagochi.app.core.model.PetDashboardState
import com.gigagochi.app.core.model.PetGeneratedMedia
import com.gigagochi.app.core.model.PetMoodImage

internal const val DebugTestPetId = "debug-test-pet"
internal const val DebugTestPetAssetSetId = "debug-test-pet-seedance-forest-mouse-v1"
internal const val DebugTestPetOrigin = "https://gigagochi.serega.works"
internal const val DebugTestExperienceBalance = 1_000
private const val DebugTestPetVideoVersion = "20260713-seedance-preroll-v3"

private const val CharacterBibleJson = """{"extensions":{"recent_story_events":[{"id":"test-story-cracked-light","title":"Трещина в старом свете","summary":"Мышонок спасся из запирающегося коридора, но повредил фонарь.","storyText":"Под древним каменным мостом мышонок-исследователь шел по заросшему подземному коридору, держа старый фонарь перед носом. Когда он задел плечом выступающий корень, тяжелая плита впереди поползла вниз и начала запирать путь назад. Мышонок подложил фонарь в щель, успел протиснуться в боковой лаз и вытащил его следом. Он выбрался наружу, но стекло фонаря треснуло, и теперь свет ложится перед ним узкой дрожащей полосой.","imageUrl":"https://gigagochi.serega.works/test-pet/story-cracked-light.png","generatedAt":"2026-07-10T14:12:30.021044Z","createdAt":"2026-07-10T14:12:30.021044Z","source":"test_fixture"},{"id":"test-story-grey-hail","title":"Вмятина от серого града","summary":"Мышонок переждал каменный град, но на шлеме осталась вмятина.","storyText":"На узкой горной тропе мышонок увидел, как низкая туча осыпает склон серым градом. Мелкие камни застучали по плитам, и идти дальше стало нельзя. Он поддел плоский сланец, поставил его ребром перед выемкой и забрался за каменную заслонку. Когда град стих, на его сшитом шлеме осталась новая вмятина, зато дыхание стало ровнее.","imageUrl":"https://gigagochi.serega.works/test-pet/story-grey-hail.png","generatedAt":"2026-07-10T14:13:29.359694Z","createdAt":"2026-07-10T14:13:29.359694Z","source":"test_fixture"},{"id":"test-story-foggy-hollow","title":"Кочка под туманом","summary":"Мышонок выбрался из скрытой канавы, но повредил компас.","storyText":"На туманном лугу мокрая осока скрывала под собой тонкую травяную корку. Мышонок ступил на неё и провалился в холодную канаву почти по грудь. Он вжал медный корпус компаса между корнями осоки и, подтягиваясь на ремешке, выбрался на плотную кочку. Мышонок ушёл дальше промокшим и дрожащим, а крышка компаса осталась погнутой, и стрелка теперь заедала.","imageUrl":"https://gigagochi.serega.works/test-pet/story-foggy-hollow.png","generatedAt":"2026-07-10T14:18:31.011926Z","createdAt":"2026-07-10T14:18:31.011926Z","source":"test_fixture"}]}}"""

internal fun debugTestPetFixture(): PetDashboardState {
    val normalImage = "$DebugTestPetOrigin/test-pet/openai-normal.png"
    val sadImage = "$DebugTestPetOrigin/test-pet/openai-sad.png"
    val happyImage = "$DebugTestPetOrigin/test-pet/openai-happy.png"
    val moodImages = listOf("baby", "teen", "adult").flatMap { stage ->
        listOf(
            PetMoodImage(stage, "idle", normalImage),
            PetMoodImage(stage, "happy", happyImage),
            PetMoodImage(stage, "hungry", normalImage),
            PetMoodImage(stage, "sad", sadImage),
        )
    }
    return PetDashboardState(
        petId = DebugTestPetId,
        assetSetId = DebugTestPetAssetSetId,
        description = "Ледяной дракон",
        name = "Без имени",
        stage = "baby",
        stageLabel = "Малыш",
        mood = "idle",
        experience = DebugTestExperienceBalance,
        hunger = 100,
        happiness = 100,
        energy = 100,
        message = "Как тебя зовут?",
        firstSessionActive = false,
        generatedMedia = PetGeneratedMedia(
            generatedAt = "2026-07-09T15:04:31.000Z",
            videoUrl = "$DebugTestPetOrigin/test-pet/openai-normal.mp4?v=$DebugTestPetVideoVersion",
            sadVideoUrl = "$DebugTestPetOrigin/test-pet/openai-sad.mp4?v=$DebugTestPetVideoVersion",
            characterBibleJson = CharacterBibleJson,
            moodImages = moodImages,
        ),
    )
}
