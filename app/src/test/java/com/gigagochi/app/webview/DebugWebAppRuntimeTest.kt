package com.gigagochi.app.webview

import com.gigagochi.app.core.webview.BridgeCodec
import com.gigagochi.app.core.webview.BridgeProductCommand
import com.gigagochi.app.core.webview.WebAppRuntimeException
import com.gigagochi.app.core.webview.WebAppSnapshot
import com.gigagochi.app.core.webview.WebAppGenerationPolicy
import com.gigagochi.app.core.webview.WebDurableStoryKind
import com.gigagochi.app.core.webview.WebDurableStoryOrigin
import com.gigagochi.app.core.webview.WebDurableStoryPhase
import com.gigagochi.app.feature.create.CreateGenerationFailureMessage
import com.gigagochi.app.feature.dashboard.ChatFailureMessage
import com.gigagochi.app.feature.dashboard.FeedFailureMessage
import com.gigagochi.app.feature.travel.TravelStoryChoiceFailureMessage
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugWebAppRuntimeTest {
    @Test
    fun `user invocation isolation is accepted only by the production runtime preview`() {
        assertEquals(
            WebAppGenerationPolicy.Production,
            DebugProductionRuntimeRouting.generationPolicy(
                useProductionRuntime = false,
                userInvocationsOnlyRequested = true,
            ),
        )
        assertEquals(
            WebAppGenerationPolicy.Production,
            DebugProductionRuntimeRouting.generationPolicy(
                useProductionRuntime = true,
                userInvocationsOnlyRequested = false,
            ),
        )
        assertEquals(
            WebAppGenerationPolicy.UserInvocationsOnly,
            DebugProductionRuntimeRouting.generationPolicy(
                useProductionRuntime = true,
                userInvocationsOnlyRequested = true,
            ),
        )
    }

    @Test
    fun `preview fixture routing accepts the complete QA matrix and defaults safely`() {
        val requiredFixtures = setOf(
            "dashboard",
            "dashboard-reduced-motion",
            "dashboard-chat",
            "dashboard-chat-thinking",
            "dashboard-chat-queued",
            "dashboard-chat-error",
            "dashboard-feed",
            "dashboard-feed-thinking",
            "dashboard-feed-error",
            "dashboard-outfit",
            "outfit-thinking",
            "dashboard-travel",
            "travel-thinking",
            "outfit-pending",
            "outfit-ready",
            "outfit-outcome-unknown",
            "outfit-apply-conflict",
            "outfit-error",
            "travel-pending",
            "travel-ready",
            "travel-outcome-unknown",
            "travel-apply-conflict",
            "travel-error",
            "create",
            "create-initial",
            "create-name",
            "create-custom",
            "create-custom-ime",
            "create-loader",
            "create-final",
            "create-error",
            "create-recovery",
            "create-retry",
            "events",
            "events-notification-focus",
            "notification-permission-granted",
            "notification-permission-denied",
            "story",
            "story-question",
            "story-retryable",
            "story-result",
            "onboarding-story",
        )
        assertEquals(requiredFixtures, DebugWebAppFixtureRouting.supportedFixtures)
        requiredFixtures.forEach { fixture ->
            assertEquals(fixture, DebugWebAppFixtureRouting.resolve("  $fixture  "))
        }
        assertEquals("dashboard", DebugWebAppFixtureRouting.resolve(null))
        assertEquals("dashboard", DebugWebAppFixtureRouting.resolve("unknown-fixture"))
    }

    @Test
    fun `durable visual fixtures expose every bridge-visible pending status and recovery target`() =
        runBlocking {
            val chatThinking = DebugWebAppRuntime("dashboard-chat-thinking").snapshot()
            assertEquals("chat", chatThinking.dashboardMode)
            assertTrue(chatThinking.dashboard?.chat?.thinking == true)
            assertNotNull(chatThinking.dashboard?.chat?.activeRequestKey)
            assertNull(chatThinking.dashboard?.chat?.queuedRequestKey)
            assertEquals(
                chatThinking.dashboard?.chat?.activeRequestKey,
                chatThinking.pending.chat?.requestKey,
            )

            val chatQueued = DebugWebAppRuntime("dashboard-chat-queued").snapshot()
            assertTrue(chatQueued.dashboard?.chat?.thinking == true)
            assertNotNull(chatQueued.dashboard?.chat?.queuedRequestKey)

            val chatError = DebugWebAppRuntime("dashboard-chat-error").snapshot()
            assertFalse(chatError.dashboard?.chat?.thinking == true)
            assertNotNull(chatError.dashboard?.chat?.activeRequestKey)
            assertNotNull(chatError.dashboard?.chat?.error)

            val feedThinking = DebugWebAppRuntime("dashboard-feed-thinking").snapshot()
            assertEquals("feed", feedThinking.dashboardMode)
            assertTrue(feedThinking.dashboard?.feed?.thinking == true)
            assertEquals("berry-bowl", feedThinking.dashboard?.feed?.activeFood)
            assertNotNull(feedThinking.dashboard?.feed?.activeRequestKey)

            val feedError = DebugWebAppRuntime("dashboard-feed-error").snapshot()
            assertFalse(feedError.dashboard?.feed?.thinking == true)
            assertNotNull(feedError.dashboard?.feed?.error)

            mapOf(
                "outfit-pending" to "pending",
                "outfit-ready" to "ready",
                "outfit-outcome-unknown" to "outcomeUnknown",
                "outfit-apply-conflict" to "applyConflict",
                "outfit-error" to "retryable",
            ).forEach { (fixture, status) ->
                val snapshot = DebugWebAppRuntime(fixture).snapshot()
                assertEquals("outfit", snapshot.dashboardMode)
                assertEquals(status, snapshot.dashboard?.outfit?.pending?.status)
                assertEquals(status, snapshot.pending.outfit?.status)
            }
            val outfitThinking = DebugWebAppRuntime("outfit-thinking").snapshot()
            assertTrue(outfitThinking.dashboard?.outfit?.thinking == true)
            assertNotNull(outfitThinking.dashboard?.outfit?.activeRequestKey)

            mapOf(
                "travel-pending" to "pending",
                "travel-ready" to "ready",
                "travel-outcome-unknown" to "outcomeUnknown",
                "travel-apply-conflict" to "applyConflict",
                "travel-error" to "retryable",
            ).forEach { (fixture, status) ->
                val snapshot = DebugWebAppRuntime(fixture).snapshot()
                assertEquals("travel", snapshot.dashboardMode)
                assertEquals(status, snapshot.dashboard?.travel?.pending?.status)
                assertEquals(status, snapshot.pending.travel?.status)
            }
            val travelThinking = DebugWebAppRuntime("travel-thinking").snapshot()
            assertTrue(travelThinking.dashboard?.travel?.thinking == true)
            assertNotNull(travelThinking.dashboard?.travel?.activeRequestKey)

            val retryableStory = DebugWebAppRuntime("story-retryable").snapshot()
            assertEquals(WebDurableStoryPhase.Retryable, retryableStory.story?.phase)
            assertNotNull(retryableStory.story?.durableRequestKey)
            assertNotNull(retryableStory.story?.pendingChoice)
            assertNotNull(retryableStory.story?.error)
        }

    @Test
    fun `failure fixtures mirror the native Android production copy verbatim`() = runBlocking {
        assertEquals(
            ChatFailureMessage,
            DebugWebAppRuntime("dashboard-chat-error").snapshot().dashboard?.chat?.error,
        )
        assertEquals(
            FeedFailureMessage,
            DebugWebAppRuntime("dashboard-feed-error").snapshot().dashboard?.feed?.error,
        )
        assertEquals(
            CreateGenerationFailureMessage,
            DebugWebAppRuntime("create-error").snapshot().create?.error,
        )
        assertEquals(
            TravelStoryChoiceFailureMessage,
            DebugWebAppRuntime("story-retryable").snapshot().story?.error,
        )
    }

    @Test
    fun `debug runtime implements the same closed draft command as production`() = runBlocking {
        val runtime = DebugWebAppRuntime("dashboard")
        var snapshot = runtime.snapshot()
        snapshot = runtime.dispatch(command("DASHBOARD_OPEN_MODE", snapshot.revision) {
            put("mode", "chat")
        })
        snapshot = runtime.dispatch(command("DASHBOARD_UPDATE_DRAFT", snapshot.revision) {
            put("mode", "chat")
            put("value", "Новый черновик")
        })

        assertEquals("Новый черновик", snapshot.dashboard?.chat?.draft)
    }

    @Test
    fun `platform presentation fixtures expose reduced motion permission and notification focus`() =
        runBlocking {
            assertTrue(DebugWebAppRuntime("dashboard-reduced-motion").snapshot().reducedMotion)
            assertEquals(
                "granted",
                DebugWebAppRuntime("notification-permission-granted")
                    .snapshot()
                    .notificationPermission,
            )
            assertEquals(
                "denied",
                DebugWebAppRuntime("notification-permission-denied")
                    .snapshot()
                    .notificationPermission,
            )
            val focused = DebugWebAppRuntime("events-notification-focus").snapshot()
            assertEquals("events", focused.route)
            assertEquals(
                focused.events?.travelVideos?.single()?.requestKey,
                focused.events?.initialFocusTravelRequestKey,
            )
        }

    @Test
    fun `custom and custom ime aliases remain source fixtures for UI automation`() = runBlocking {
        listOf("create-custom", "create-custom-ime").forEach { fixture ->
            val snapshot = DebugWebAppRuntime(fixture).snapshot()
            assertEquals("create", snapshot.route)
            assertEquals(0, snapshot.create?.step)
            assertEquals("Кого хочешь создать?", snapshot.create?.title)
            assertEquals("idle", snapshot.create?.generation)
        }
    }

    @Test
    fun `dashboard fixture follows production dto and interaction commands`() = runBlocking {
        val runtime = DebugWebAppRuntime("dashboard")
        var snapshot = runtime.snapshot()
        assertNotNull(snapshot.dashboard)

        snapshot = runtime.dispatch(command("DASHBOARD_OPEN_MODE", snapshot.revision) {
            put("mode", "chat")
        })
        snapshot = runtime.dispatch(command("CHAT_SEND", snapshot.revision) {
            put("message", "Привет")
        })
        assertEquals("chat", snapshot.dashboardMode)
        assertEquals("chat", snapshot.dashboard?.reply?.source)
        assertEquals("completed", snapshot.pending.chat?.status)

        snapshot = runtime.dispatch(command("DASHBOARD_CLOSE_MODE", snapshot.revision))
        snapshot = runtime.dispatch(command("DASHBOARD_OPEN_MODE", snapshot.revision) {
            put("mode", "feed")
        })
        val hungerBefore = requireNotNull(snapshot.pet).hunger
        snapshot = runtime.dispatch(command("FEED_CONSUME", snapshot.revision) {
            put("food", "berry-bowl")
        })
        assertEquals("feed", snapshot.dashboardMode)
        assertEquals("feed", snapshot.dashboard?.reply?.source)
        assertEquals(1, snapshot.dashboard?.feed?.pulseId)
        assertEquals("berry-bowl", snapshot.dashboard?.feed?.activeFood)
        assertEquals(0, snapshot.dashboard?.feed?.audioIndex)
        assertTrue(requireNotNull(snapshot.pet).hunger >= hungerBefore)

        snapshot = runtime.dispatch(command("DASHBOARD_CLOSE_MODE", snapshot.revision))
        val experienceBefore = requireNotNull(snapshot.pet).experience
        snapshot = runtime.dispatch(command("OUTFIT_SUBMIT", snapshot.revision) {
            put("prompt", "  красный шарф  ")
        })
        assertEquals(experienceBefore - 200, snapshot.pet?.experience)
        assertEquals("attached", snapshot.dashboard?.outfit?.pending?.status)
        assertEquals("красный шарф", snapshot.dashboard?.outfit?.pending?.prompt)
        assertEquals(200, snapshot.dashboard?.outfit?.pending?.experienceCost)
        assertEquals(snapshot.pending.outfit?.requestKey, snapshot.dashboard?.outfit?.pending?.requestKey)

        snapshot = runtime.dispatch(command("TRAVEL_SUBMIT", snapshot.revision) {
            put("prompt", "  ночной рынок духов  ")
        })
        assertEquals("attached", snapshot.dashboard?.travel?.pending?.status)
        assertEquals("ночной рынок духов", snapshot.dashboard?.travel?.pending?.prompt)
        assertEquals(snapshot.pending.travel?.requestKey, snapshot.dashboard?.travel?.pending?.requestKey)
    }

    @Test
    fun `dashboard QA fixtures project deterministic modes pending and error states`() = runBlocking {
        mapOf(
            "dashboard-chat" to "chat",
            "dashboard-feed" to "feed",
            "dashboard-outfit" to "outfit",
            "dashboard-travel" to "travel",
        ).forEach { (fixture, mode) ->
            val snapshot = DebugWebAppRuntime(fixture).snapshot()
            assertEquals("dashboard", snapshot.route)
            assertEquals(mode, snapshot.dashboardMode)
            assertNotNull(snapshot.pet)
            assertNotNull(snapshot.dashboard)
            assertNotNull(snapshot.events)
        }

        val chat = DebugWebAppRuntime("dashboard-chat").snapshot()
        assertTrue(chat.dashboard?.chat?.draft?.isNotBlank() == true)
        assertEquals("chat", chat.dashboard?.reply?.source)
        assertTrue(DebugWebAppRuntime("dashboard-outfit").snapshot().dashboard
            ?.outfit?.draft?.isNotBlank() == true)
        assertTrue(DebugWebAppRuntime("dashboard-travel").snapshot().dashboard
            ?.travel?.draft?.isNotBlank() == true)

        val outfitPending = DebugWebAppRuntime("outfit-pending").snapshot()
        assertEquals("outfit", outfitPending.dashboardMode)
        assertEquals("pending", outfitPending.dashboard?.outfit?.pending?.status)
        assertNull(outfitPending.dashboard?.outfit?.error)
        assertEquals(
            outfitPending.dashboard?.outfit?.pending?.requestKey,
            outfitPending.pending.outfit?.requestKey,
        )
        assertEquals("pending", outfitPending.pending.outfit?.status)
        assertEquals(40, outfitPending.pet?.experience)

        val outfitError = DebugWebAppRuntime("outfit-error").snapshot()
        assertEquals("outfit", outfitError.dashboardMode)
        assertEquals("retryable", outfitError.dashboard?.outfit?.pending?.status)
        assertNotNull(outfitError.dashboard?.outfit?.error)
        assertEquals("retryable", outfitError.pending.outfit?.status)

        val travelPending = DebugWebAppRuntime("travel-pending").snapshot()
        assertEquals("travel", travelPending.dashboardMode)
        assertEquals("pending", travelPending.dashboard?.travel?.pending?.status)
        assertNull(travelPending.dashboard?.travel?.error)
        assertEquals(
            travelPending.dashboard?.travel?.pending?.requestKey,
            travelPending.pending.travel?.requestKey,
        )

        val travelError = DebugWebAppRuntime("travel-error").snapshot()
        assertEquals("travel", travelError.dashboardMode)
        assertEquals("retryable", travelError.dashboard?.travel?.pending?.status)
        assertNotNull(travelError.dashboard?.travel?.error)
        assertEquals("retryable", travelError.pending.travel?.status)
    }

    @Test
    fun `create fixture projects next question and completes transition locally`() = runBlocking {
        val runtime = DebugWebAppRuntime("create")
        var snapshot = runtime.snapshot()
        assertEquals("Как его будут звать?", snapshot.create?.nextQuestion?.title)

        snapshot = runtime.dispatch(command("CREATE_ANSWER", snapshot.revision) {
            put("answer", "Ледяного дракона")
            put("step", 0)
        })
        assertEquals("transition", snapshot.create?.phase)
        assertEquals("Какой у него характер?", snapshot.create?.nextQuestion?.title)

        snapshot = runtime.dispatch(command("CREATE_BACKGROUND_COMPLETE", snapshot.revision))
        assertEquals("formed", snapshot.create?.phase)
    }

    @Test
    fun `create retry fixture validates stage and clears deterministic failure`() = runBlocking {
        val runtime = DebugWebAppRuntime("create-retry")
        var snapshot = runtime.snapshot()
        assertEquals("create", snapshot.route)
        assertEquals("debug-0", snapshot.revision)
        assertEquals(5, snapshot.create?.step)
        assertEquals("retryable", snapshot.create?.generation)
        assertEquals("generation", snapshot.create?.retryTarget)
        assertNotNull(snapshot.create?.error)

        val malformed = runCatching {
            runtime.dispatch(command("CREATE_RETRY", snapshot.revision) {
                put("extra", true)
            })
        }.exceptionOrNull() as WebAppRuntimeException
        assertEquals("INVALID_PAYLOAD", malformed.bridgeCode)
        assertEquals("debug-0", runtime.snapshot().revision)

        snapshot = runtime.dispatch(command("CREATE_RETRY", snapshot.revision))
        assertEquals("debug-1", snapshot.revision)
        assertEquals("running", snapshot.create?.generation)
        assertNull(snapshot.create?.retryTarget)
        assertNull(snapshot.create?.error)

        val repeated = runCatching {
            runtime.dispatch(command("CREATE_RETRY", snapshot.revision))
        }.exceptionOrNull() as WebAppRuntimeException
        assertEquals("WRONG_STAGE", repeated.bridgeCode)
        assertEquals("debug-1", runtime.snapshot().revision)
    }

    @Test
    fun `create QA fixtures project deterministic exact phases`() = runBlocking {
        data class ExpectedCreate(
            val step: Int,
            val phase: String,
            val generation: String,
            val hasError: Boolean = false,
        )

        mapOf(
            "create-name" to ExpectedCreate(1, "formed", "running"),
            "create-custom" to ExpectedCreate(0, "initial", "idle"),
            "create-loader" to ExpectedCreate(5, "formed", "running"),
            "create-final" to ExpectedCreate(5, "formed", "ready"),
            "create-error" to ExpectedCreate(5, "formed", "retryable", hasError = true),
            "create-recovery" to ExpectedCreate(5, "formed", "running"),
        ).forEach { (fixture, expected) ->
            val snapshot = DebugWebAppRuntime(fixture).snapshot()
            assertEquals("create", snapshot.route)
            assertEquals("idle", snapshot.dashboardMode)
            assertEquals(expected.step, snapshot.create?.step)
            assertEquals(expected.phase, snapshot.create?.phase)
            assertEquals(expected.generation, snapshot.create?.generation)
            assertEquals(expected.hasError, snapshot.create?.error != null)
            assertEquals(
                if (expected.hasError) "generation" else null,
                snapshot.create?.retryTarget,
            )
            assertNull(snapshot.pet)
            assertNull(snapshot.dashboard)
            assertNull(snapshot.events)
        }
        assertEquals(
            "Как его будут звать?",
            DebugWebAppRuntime("create-name").snapshot().create?.title,
        )
        assertEquals(
            "Кого хочешь создать?",
            DebugWebAppRuntime("create-custom").snapshot().create?.title,
        )
    }

    @Test
    fun `events and story fixture routes retain the dashboard base state`() = runBlocking {
        val dashboard = DebugWebAppRuntime("").snapshot()
        assertEquals("dashboard", dashboard.route)
        assertNotNull(dashboard.pet)
        assertNotNull(dashboard.dashboard)
        assertNotNull(dashboard.events)
        assertNull(dashboard.story)

        val events = DebugWebAppRuntime("events").snapshot()
        assertEquals("events", events.route)
        assertNotNull(events.pet)
        assertNotNull(events.dashboard)
        assertEquals(2, events.events?.badgeCount)
        assertEquals(1, events.events?.stories?.size)
        assertEquals(1, events.events?.travelVideos?.size)
        assertNull(events.story)

        val question = DebugWebAppRuntime("story-question").snapshot()
        assertEquals("story", question.route)
        assertNotNull(question.pet)
        assertNotNull(question.dashboard)
        assertNotNull(question.events)
        assertEquals(WebDurableStoryPhase.Question, question.story?.phase)
        assertEquals(WebDurableStoryKind.Scheduled, question.story?.kind)
        assertEquals(WebDurableStoryOrigin.Events, question.story?.origin)

        val result = DebugWebAppRuntime("story-result").snapshot()
        assertEquals(WebDurableStoryPhase.Result, result.story?.phase)
        assertEquals(WebDurableStoryKind.Scheduled, result.story?.kind)
        assertEquals(WebDurableStoryOrigin.Events, result.story?.origin)
        assertNotNull(result.story?.result)
        assertEquals(1, result.events?.badgeCount)
        assertNotNull(result.events?.stories?.single()?.story?.result)

        val onboarding = DebugWebAppRuntime("onboarding-story").snapshot()
        assertEquals(WebDurableStoryPhase.Question, onboarding.story?.phase)
        assertEquals(WebDurableStoryKind.OnboardingBat, onboarding.story?.kind)
        assertEquals(WebDurableStoryOrigin.Dashboard, onboarding.story?.origin)
        assertEquals("Млекопитающие", onboarding.story?.story?.enabledChoice)
    }

    @Test
    fun `events navigation mark viewed and back advance one revision per command`() = runBlocking {
        val runtime = DebugWebAppRuntime("dashboard")
        var snapshot = runtime.snapshot()
        assertEquals("debug-0", snapshot.revision)

        snapshot = runtime.dispatch(command("NAVIGATE", snapshot.revision) {
            put("route", "events")
        })
        assertEquals("events", snapshot.route)
        assertEquals("debug-1", snapshot.revision)
        val latestEventAt = requireNotNull(snapshot.events?.latestEventAtEpochMillis)

        snapshot = runtime.dispatch(command("EVENTS_MARK_VIEWED", snapshot.revision) {
            put("viewedAt", latestEventAt)
        })
        assertEquals("debug-2", snapshot.revision)
        assertEquals(0, snapshot.events?.badgeCount)
        assertEquals(latestEventAt, snapshot.events?.lastViewedAtEpochMillis)

        snapshot = runtime.dispatch(command("BACK", snapshot.revision))
        assertEquals("dashboard", snapshot.route)
        assertEquals("debug-3", snapshot.revision)
        assertNotNull(snapshot.dashboard)
        assertNotNull(snapshot.events)
        assertNull(snapshot.story)

        snapshot = runtime.dispatch(command("NAVIGATE", snapshot.revision) {
            put("route", "travel")
        })
        assertEquals("story", snapshot.route)
        assertEquals("debug-4", snapshot.revision)
        assertEquals(WebDurableStoryKind.OnboardingBat, snapshot.story?.kind)
        assertEquals(WebDurableStoryOrigin.Dashboard, snapshot.story?.origin)

        snapshot = runtime.dispatch(command("BACK", snapshot.revision))
        assertEquals("dashboard", snapshot.route)
        assertEquals("debug-5", snapshot.revision)
        assertNull(snapshot.story)
    }

    @Test
    fun `scheduled story preserves durable request through retry result and finish`() = runBlocking {
        val runtime = DebugWebAppRuntime("events")
        var snapshot = runtime.snapshot()
        val storyId = requireNotNull(snapshot.events?.stories?.single()?.story?.storyId)

        snapshot = runtime.dispatch(command("STORY_OPEN", snapshot.revision) {
            put("storyId", storyId)
        })
        assertEquals("debug-1", snapshot.revision)
        assertEquals(WebDurableStoryPhase.Question, snapshot.story?.phase)
        assertEquals(WebDurableStoryOrigin.Events, snapshot.story?.origin)

        val choose = command("STORY_CHOOSE", snapshot.revision) {
            put("storyId", storyId)
            put("choice", "Спрятаться")
        }
        snapshot = runtime.dispatch(choose)
        assertEquals("debug-2", snapshot.revision)
        assertEquals(WebDurableStoryPhase.Retryable, snapshot.story?.phase)
        assertEquals(choose.requestKey, snapshot.story?.durableRequestKey)
        assertEquals("Спрятаться", snapshot.story?.pendingChoice)
        assertEquals(TravelStoryChoiceFailureMessage, snapshot.story?.error)
        assertEquals(2, snapshot.events?.badgeCount)

        snapshot = runtime.dispatch(command("STORY_RETRY", snapshot.revision) {
            put("storyId", storyId)
        })
        assertEquals("debug-3", snapshot.revision)
        assertEquals(WebDurableStoryPhase.Result, snapshot.story?.phase)
        assertEquals(choose.requestKey, snapshot.story?.durableRequestKey)
        assertEquals(choose.requestKey, snapshot.story?.result?.requestKey)
        assertEquals("Спрятаться", snapshot.story?.result?.answer)
        assertNull(snapshot.story?.pendingChoice)
        assertNull(snapshot.story?.error)
        assertEquals(1, snapshot.events?.badgeCount)

        snapshot = runtime.dispatch(command("STORY_FINISH", snapshot.revision) {
            put("storyId", storyId)
        })
        assertEquals("events", snapshot.route)
        assertEquals("debug-4", snapshot.revision)
        assertNull(snapshot.story)
        assertEquals("Спрятаться", snapshot.events?.stories?.single()?.story?.selectedChoice)
        assertNotNull(snapshot.events?.stories?.single()?.story?.result)

        snapshot = runtime.dispatch(command("BACK", snapshot.revision))
        assertEquals("dashboard", snapshot.route)
        assertEquals("debug-5", snapshot.revision)
    }

    @Test
    fun `onboarding story choose result and finish return to dashboard`() = runBlocking {
        val runtime = DebugWebAppRuntime("dashboard")
        var snapshot = runtime.snapshot()

        snapshot = runtime.dispatch(command("NAVIGATE", snapshot.revision) {
            put("route", "travel")
        })
        val storyId = requireNotNull(snapshot.story?.story?.storyId)
        assertEquals(WebDurableStoryPhase.Question, snapshot.story?.phase)
        assertEquals("Млекопитающие", snapshot.story?.story?.enabledChoice)

        val choose = command("STORY_CHOOSE", snapshot.revision) {
            put("storyId", storyId)
            put("choice", "Млекопитающие")
        }
        snapshot = runtime.dispatch(choose)
        assertEquals("debug-2", snapshot.revision)
        assertEquals(WebDurableStoryPhase.Result, snapshot.story?.phase)
        assertEquals(choose.requestKey, snapshot.story?.result?.requestKey)
        assertEquals(200, snapshot.story?.result?.experienceGained)

        snapshot = runtime.dispatch(command("STORY_FINISH", snapshot.revision) {
            put("storyId", storyId)
        })
        assertEquals("dashboard", snapshot.route)
        assertEquals("debug-3", snapshot.revision)
        assertNull(snapshot.story)
        assertNotNull(snapshot.events)
    }

    @Test
    fun `all preview fixtures expose exact v3 shapes without private ids or raw urls`() = runBlocking {
        DebugWebAppFixtureRouting.supportedFixtures.forEach { fixture ->
            val snapshot = DebugWebAppRuntime(fixture).snapshot()
            val encoded = BridgeCodec.json.encodeToString(snapshot)
            assertFalse("$fixture leaked an http URL", encoded.contains("http://"))
            assertFalse("$fixture leaked an https URL", encoded.contains("https://"))
            assertFalse("$fixture leaked a static path", encoded.contains("/static/"))
            assertFalse("$fixture leaked imageUrl", encoded.contains("\"imageUrl\""))
            assertFalse("$fixture leaked videoUrl", encoded.contains("\"videoUrl\""))
            assertFalse("$fixture leaked petId", encoded.contains("\"petId\""))
            assertFalse("$fixture leaked assetSetId", encoded.contains("\"assetSetId\""))
            assertExactV3Shape(encoded, snapshot)

            val references = mediaReferences(snapshot)
            if (snapshot.route == "create") {
                assertTrue("$fixture must not expose dashboard media", references.isEmpty())
            } else {
                assertTrue("$fixture should contain fixture media", references.isNotEmpty())
            }
            references.forEach { reference ->
                assertTrue(
                    "$fixture contains unsafe media reference $reference",
                    reference.startsWith("/res/") || reference.startsWith("/assets/"),
                )
            }
        }
    }

    private fun assertExactV3Shape(encoded: String, snapshot: WebAppSnapshot) {
        val root = BridgeCodec.json.parseToJsonElement(encoded).jsonObject
        assertEquals(
            setOf(
                "protocolVersion",
                "appVersion",
                "webBundleVersion",
                "revision",
                "route",
                "dashboardMode",
                "capabilities",
                "pendingDeepLinkTarget",
                "reducedMotion",
                "safeArea",
                "notificationPermission",
                "create",
                "pet",
                "firstSession",
                "dashboard",
                "events",
                "story",
                "pending",
                "petTapFeedback",
            ),
            root.keys,
        )
        assertEquals(
            setOf(
                "requestNotificationPermission",
                "shareTravelVideo",
                "feedback",
                "navigationReady",
                "opaqueMedia",
            ),
            root.getValue("capabilities").jsonObject.keys,
        )
        assertEquals(
            setOf("chat", "outfit", "travel"),
            root.getValue("pending").jsonObject.keys,
        )
        snapshot.pet?.let {
            val pet = root.getValue("pet").jsonObject
            assertEquals(
                setOf(
                    "name",
                    "stageLabel",
                    "experience",
                    "hunger",
                    "happiness",
                    "energy",
                    "message",
                    "petTapProgress",
                    "media",
                ),
                pet.keys,
            )
            assertEquals(
                setOf("videoRef", "posterRef", "sadVideoRef", "happyVideoRef"),
                pet.getValue("media").jsonObject.keys,
            )
        }
        snapshot.create?.let {
            assertEquals(
                setOf(
                    "step",
                    "title",
                    "options",
                    "nextQuestion",
                    "phase",
                    "generation",
                    "error",
                    "retryTarget",
                ),
                root.getValue("create").jsonObject.keys,
            )
        }
        snapshot.dashboard?.let {
            val dashboard = root.getValue("dashboard").jsonObject
            assertEquals(setOf("reply", "chat", "feed", "outfit", "travel"), dashboard.keys)
            assertEquals(
                setOf("draft", "error", "activeRequestKey", "queuedRequestKey", "thinking"),
                dashboard.getValue("chat").jsonObject.keys,
            )
            assertEquals(
                setOf(
                    "error",
                    "activeRequestKey",
                    "activeFood",
                    "audioIndex",
                    "pulseId",
                    "thinking",
                ),
                dashboard.getValue("feed").jsonObject.keys,
            )
            assertEquals(
                setOf(
                    "draft",
                    "error",
                    "activeRequestKey",
                    "thinking",
                    "experienceCost",
                    "pending",
                ),
                dashboard.getValue("outfit").jsonObject.keys,
            )
            assertEquals(
                setOf("draft", "error", "activeRequestKey", "thinking", "pending"),
                dashboard.getValue("travel").jsonObject.keys,
            )
            snapshot.dashboard?.outfit?.pending?.let {
                assertEquals(
                    setOf(
                        "requestKey",
                        "status",
                        "prompt",
                        "displayItem",
                        "experienceCost",
                    ),
                    dashboard.getValue("outfit").jsonObject
                        .getValue("pending").jsonObject.keys,
                )
            }
            snapshot.dashboard?.travel?.pending?.let {
                assertEquals(
                    setOf("requestKey", "status", "prompt"),
                    dashboard.getValue("travel").jsonObject
                        .getValue("pending").jsonObject.keys,
                )
            }
        }
        val pending = root.getValue("pending").jsonObject
        snapshot.pending.outfit?.let {
            assertEquals(
                setOf("requestKey", "status", "prompt"),
                pending.getValue("outfit").jsonObject.keys,
            )
        }
        snapshot.pending.travel?.let {
            assertEquals(
                setOf("requestKey", "status", "prompt"),
                pending.getValue("travel").jsonObject.keys,
            )
        }
    }

    private fun mediaReferences(snapshot: WebAppSnapshot): List<String> = buildList {
        snapshot.pet?.media?.let { media ->
            listOfNotNull(
                media.videoRef,
                media.posterRef,
                media.sadVideoRef,
                media.happyVideoRef,
            ).forEach { reference -> add(reference) }
        }
        snapshot.events?.stories?.forEach { event ->
            listOfNotNull(
                event.story.imageRef,
                event.story.videoRef,
                event.story.resultImageRef,
                event.story.resultVideoRef,
            ).forEach { reference -> add(reference) }
        }
        snapshot.events?.travelVideos?.forEach { event ->
            listOfNotNull(event.imageRef, event.videoRef).forEach { reference -> add(reference) }
        }
        snapshot.story?.let { story ->
            listOfNotNull(
                story.story.imageRef,
                story.story.videoRef,
                story.result?.imageRef,
                story.result?.videoRef,
            ).forEach { reference -> add(reference) }
        }
    }

    private fun command(
        type: String,
        revision: String,
        payload: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {},
    ) = BridgeProductCommand(
        type = type,
        requestKey = UUID.randomUUID().toString(),
        expectedSnapshotRevision = revision,
        payload = buildJsonObject(payload),
    )
}
