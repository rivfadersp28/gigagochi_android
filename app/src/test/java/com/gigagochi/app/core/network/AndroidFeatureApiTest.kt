package com.gigagochi.app.core.network

import com.gigagochi.app.core.auth.InMemoryAuthHeaderProvider
import com.gigagochi.app.core.auth.SessionLoadResult
import com.gigagochi.app.core.auth.SessionMutationResult
import com.gigagochi.app.core.auth.SessionRefreshExchange
import com.gigagochi.app.core.auth.SessionRefreshResult
import com.gigagochi.app.core.auth.SessionRepository
import com.gigagochi.app.core.model.SensitiveToken
import com.gigagochi.app.core.model.Session
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidFeatureApiTest {
    @Test
    fun additiveUnknownFieldsDecodeButMissingRequiredOrInvalidEnumFailClosed() = runBlocking {
        val unknown = api(200, validEnvelope().replace("\"jobId\"", "\"future\":123,\"jobId\""))
            .submitCreate(CreateJobRequestDto(Key, "pet-a", "dragon"))
        assertTrue(unknown is FeatureApiResult.Success)

        val missing = api(200, validEnvelope().replace("\"phase\":\"queued\",", ""))
            .submitCreate(CreateJobRequestDto(Key, "pet-a", "dragon"))
        assertFailure(missing, FeatureFailureKind.Protocol)

        val invalid = api(200, validEnvelope().replace("\"queued\"", "\"invented\""))
            .submitCreate(CreateJobRequestDto(Key, "pet-a", "dragon"))
        assertFailure(invalid, FeatureFailureKind.Protocol)
    }

    @Test
    fun api23SafeTimestampValidationAcceptsIsoAndRejectsInvalidCalendarDate() = runBlocking {
        assertTrue(
            api(200, validEnvelope()).submitCreate(
                CreateJobRequestDto(Key, "pet-a", "dragon"),
            ) is FeatureApiResult.Success,
        )
        val invalidDate = validEnvelope().replace("2026-07-17T10:11:12Z", "2026-02-31T10:11:12Z")
        assertFailure(
            api(200, invalidDate).submitCreate(CreateJobRequestDto(Key, "pet-a", "dragon")),
            FeatureFailureKind.Protocol,
        )
    }

    @Test
    fun fastApiValidationListUsesStatusFallbackWithoutLeakingRawDetail() = runBlocking {
        val result = api(
            422,
            """{"detail":[{"loc":["body","petId"],"msg":"secret input","type":"missing"}]}""",
        ).submitCreate(CreateJobRequestDto(Key, "pet-a", "dragon"))
        assertFailure(result, FeatureFailureKind.BadRequest)
        assertTrue(result.toString().contains("BadRequest"))
        assertTrue(!result.toString().contains("secret input"))
    }

    @Test
    fun pollRejectsUnsafeJobIdBeforeTransportAndSubmitRejectsMaliciousJobId() = runBlocking {
        var calls = 0
        val api = api(200, validEnvelope(), onCall = { calls += 1 })
        assertFailure(api.pollCreate("job/../../x?token=y"), FeatureFailureKind.Protocol)
        assertEquals(0, calls)

        val malicious = api(200, validEnvelope().replace(Job, "job%2fescape"))
            .submitCreate(CreateJobRequestDto(Key, "pet-a", "dragon"))
        assertFailure(malicious, FeatureFailureKind.Protocol)
    }

    @Test
    fun mediaUrlsAllowOnlySameOriginStaticAndSingleBoundedVersionQuery() {
        val api = api(200, "{}")
        assertEquals(
            "https://gigagochi.serega.works/static/pet.png?v=172345",
            api.resolveMediaUrl("/static/pet.png?v=172345"),
        )
        assertEquals(
            "https://gigagochi.serega.works/static/pet.png?v=cache_1",
            api.resolveMediaUrl("https://gigagochi.serega.works:443/static/pet.png?v=cache_1"),
        )
        listOf(
            "/static/%2e%2e/private",
            "/static/a%2Fb.png",
            "/static/a%5Cb.png",
            "/static/a.png?v=1&v=2",
            "/static/a.png?token=secret",
            "https://other.example/static/a.png?v=1",
            "http://gigagochi.serega.works/static/a.png?v=1",
            "https://user@gigagochi.serega.works/static/a.png?v=1",
        ).forEach { assertNull("expected rejection: $it", api.resolveMediaUrl(it)) }
    }

    @Test
    fun mediaRequiresCompleteThreeByFourSetAndRejectsPresentInvalidOptionalUrl() {
        val api = api(200, "{}")
        assertNotNull(api.media(asset()))
        assertNull(api.media(asset().copy(images = asset().images - "adult")))
        assertNull(api.media(asset().copy(videoUrl = "javascript:alert(1)")))
        assertNotNull(api.media(asset().copy(videoUrl = null)))
    }

    @Test
    fun redactedProtocolFailuresNeverContainBody() = runBlocking {
        val result = api(200, "{\"accessToken\":\"super-secret\"}")
            .submitCreate(CreateJobRequestDto(Key, "pet-a", "dragon"))
        assertFailure(result, FeatureFailureKind.Protocol)
        assertTrue(!result.toString().contains("super-secret"))
    }

    @Test
    fun outcomeUnknownCodesHaveStableNonRetryableMapping() = runBlocking {
        listOf("OUTCOME_UNKNOWN", "SESSION_OUTCOME_UNKNOWN").forEach { code ->
            val result = api(409, """{"detail":{"code":"$code"}}""")
                .submitCreate(CreateJobRequestDto(Key, "pet-a", "dragon"))
            assertFailure(result, FeatureFailureKind.OutcomeUnknown)
            assertEquals(code, (result as FeatureApiResult.Failure).failure.code)
        }
    }

    @Test
    fun malformedUtf8SuccessIsProtocolFailure() = runBlocking {
        val result = apiBytes(200, byteArrayOf(0x7b, 0xc3.toByte(), 0x28, 0x7d))
            .submitCreate(CreateJobRequestDto(Key, "pet-a", "dragon"))
        assertFailure(result, FeatureFailureKind.Protocol)
    }

    @Test
    fun scheduledStoryAllowsAdditiveFieldsButRejectsMalformedChoicesAndUnsafeMedia() = runBlocking {
        val accepted = api(200, dueStoryJson().replace("\"storyId\"", "\"future\":true,\"storyId\""))
            .dueStory(DueStoryRequestDto(featurePet()))
        assertTrue(accepted is FeatureApiResult.Success)
        val dto = (accepted as FeatureApiResult.Success).value.story
        assertNotNull(api(200, "{}").story(requireNotNull(dto)))

        val duplicate = dueStoryJson().replace("[\"a\",\"b\",\"c\",\"d\"]", "[\"a\",\"a\",\"c\",\"d\"]")
        assertFailure(
            api(200, duplicate).dueStory(DueStoryRequestDto(featurePet())),
            FeatureFailureKind.Protocol,
        )
        val unsafe = dueStoryJson().replace("/static/story.png?v=1", "https://evil.example/static/story.png")
        val unsafeResponse = api(200, unsafe).dueStory(DueStoryRequestDto(featurePet()))
        assertTrue(unsafeResponse is FeatureApiResult.Success)
        assertNull(api(200, "{}").story(requireNotNull((unsafeResponse as FeatureApiResult.Success).value.story)))
    }

    @Test
    fun scheduledChoiceSerializesExactRequestKeyAndChoice() = runBlocking {
        var body = ""
        val result = api(200, dueStoryJson(selected = true).substringAfter("\"story\":" ).dropLast(1)) {
            body = it.body?.toString(Charsets.UTF_8).orEmpty()
        }.chooseStory(
            "android-story-1234567890abcdef1234567890abcdef",
            ScheduledStoryChoiceRequestDto(Key, "b"),
        )
        assertTrue(result is FeatureApiResult.Success)
        assertTrue(body.contains("\"requestKey\":\"$Key\""))
        assertTrue(body.contains("\"choice\":\"b\""))
    }

    @Test
    fun chatSerializesComplimentLedgerAndDecodesRewardContract() = runBlocking {
        var body = ""
        val result = api(
            200,
            """{"reply":"Спасибо!","happinessDelta":100,"complimentKey":"смелый хранитель"}""",
            onRequest = { body = it.body?.toString(Charsets.UTF_8).orEmpty() },
        ).chat(
            ChatRequestDto(
                requestKey = Key,
                message = "Ты невероятно смелый",
                pet = featurePet(),
                complimentHistory = listOf("добрый друг", "верный спутник"),
            ),
        )

        assertTrue(result is FeatureApiResult.Success)
        val response = (result as FeatureApiResult.Success).value
        assertEquals(100, response.happinessDelta)
        assertEquals("смелый хранитель", response.complimentKey)
        assertTrue(body.contains("\"complimentHistory\":[\"добрый друг\",\"верный спутник\"]"))
    }

    private fun api(
        status: Int,
        body: String,
        onCall: () -> Unit = {},
        onRequest: (FeatureHttpRequest) -> Unit = {},
    ): AndroidFeatureApi = apiBytes(status, body.toByteArray(Charsets.UTF_8), onCall, onRequest)

    private fun apiBytes(
        status: Int,
        body: ByteArray,
        onCall: () -> Unit = {},
        onRequest: (FeatureHttpRequest) -> Unit = {},
    ): AndroidFeatureApi {
        val repository = object : SessionRepository {
            override suspend fun load() = SessionLoadResult.Success(
                Session("account-a", SensitiveToken.of("access"), null, Long.MAX_VALUE),
            )
            override suspend fun save(session: Session) = SessionMutationResult.Success
            override suspend fun clear() = SessionMutationResult.Success
        }
        val refresh = object : SessionRefreshExchange {
            override suspend fun refresh(refreshToken: SensitiveToken) = SessionRefreshResult.InvalidSession
        }
        val transport = object : FeatureHttpTransport {
            override suspend fun execute(
                request: FeatureHttpRequest,
                accessToken: SensitiveToken,
            ): FeatureHttpResponse {
                onCall()
                onRequest(request)
                return FeatureHttpResponse(status, body)
            }
        }
        return AndroidFeatureApi(
            AuthenticatedFeatureClient(repository, refresh, transport, InMemoryAuthHeaderProvider()),
            "https://gigagochi.serega.works/",
            false,
        )
    }

    private fun asset(): GenerationAssetDto {
        val moods = mapOf(
            "idle" to "/static/idle.png?v=1",
            "happy" to "/static/happy.png?v=1",
            "hungry" to "/static/hungry.png?v=1",
            "sad" to "/static/sad.png?v=1",
        )
        return GenerationAssetDto(
            "asset-a",
            "2026-07-17T10:11:12.123456Z",
            mapOf("baby" to moods, "teen" to moods, "adult" to moods),
            characterBible = JsonObject(emptyMap()),
        )
    }

    private fun validEnvelope() = """
        {
          "requestKey":"$Key",
          "petId":"pet-a",
          "job":{
            "jobId":"$Job",
            "status":"queued",
            "phase":"queued",
            "createdAt":"2026-07-17T10:11:12Z",
            "updatedAt":"2026-07-17T10:11:12.123456Z"
          }
        }
    """.trimIndent()

    private fun featurePet() = FeaturePetDto(
        petId = "pet-a",
        description = "dragon",
        stage = "baby",
        mood = "idle",
        stats = FeaturePetStatsDto(50, 50, 50),
    )

    private fun dueStoryJson(selected: Boolean = false): String {
        val selection = if (selected) """,
          "selectedChoice":"b",
          "result":{
            "text":"result","adviceAssessment":"helpful","reaction":"yay",
            "reactionTone":"enthusiastic","consequence":"safe",
            "outcomeValence":"positive","experienceGained":20
          }""" else ""
        return """{"story":{
          "storyId":"android-story-1234567890abcdef1234567890abcdef",
          "petId":"pet-a","title":"История","text":"Ситуация",
          "question":"Что делать?","choices":["a","b","c","d"],
          "createdAt":"2026-07-17T10:11:12Z",
          "imageUrl":"/static/story.png?v=1"$selection
        }}"""
    }

    private fun assertFailure(result: FeatureApiResult<*>, kind: FeatureFailureKind) {
        assertTrue(result is FeatureApiResult.Failure)
        assertEquals(kind, (result as FeatureApiResult.Failure).failure.kind)
    }

    private companion object {
        const val Key = "123e4567-e89b-42d3-a456-426614174000"
        const val Job = "job-safe_1"
    }
}
