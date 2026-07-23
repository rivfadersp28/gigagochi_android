package com.gigagochi.app.core.webview

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

internal const val BridgeProtocolVersion = 1
internal const val BridgeMaxMessageBytes = 65_536
internal const val BridgeWebBundleVersion = "0.1.0"
internal const val BridgeSchemaHash = "gigagochi-bridge-v3-cde4d836b9d139ed4fcdff1b6db8e2769ce736a954500d15b4b1baeef06924ee"

internal val BridgeProductCommandTypes = setOf(
    "CREATE_ANSWER",
    "CREATE_BACKGROUND_COMPLETE",
    "CREATE_RETRY",
    "CREATE_FINISH",
    "CHAT_SEND",
    "CHAT_RETRY",
    "DASHBOARD_OPEN_MODE",
    "DASHBOARD_CLOSE_MODE",
    "DASHBOARD_UPDATE_DRAFT",
    "REPLY_ADVANCE",
    "REPLY_COMPLETE",
    "CHAT_REPLY_PRESENTED",
    "FEED_CONSUME",
    "OUTFIT_SUBMIT",
    "OUTFIT_RETRY",
    "TRAVEL_SUBMIT",
    "TRAVEL_RETRY",
    "STORY_OPEN",
    "STORY_CHOOSE",
    "STORY_RETRY",
    "STORY_FINISH",
    "EVENTS_MARK_VIEWED",
    "PET_TAP",
    "NAVIGATE",
    "BACK",
)

@Serializable
internal data class BridgeRequestEnvelope(
    val kind: String,
    val protocolVersion: Int,
    val documentId: String,
    val bridgeSessionId: String? = null,
    val requestId: String,
    val method: String,
    val payload: JsonElement = JsonObject(emptyMap()),
)

@Serializable
internal data class BridgeErrorPayload(
    val code: String,
    val retryable: Boolean,
)

@Serializable
internal data class BridgeResponseEnvelope(
    val kind: String = "response",
    val protocolVersion: Int = BridgeProtocolVersion,
    val documentId: String,
    val bridgeSessionId: String? = null,
    val requestId: String,
    val ok: Boolean,
    val result: JsonElement = JsonNull,
    val error: BridgeErrorPayload? = null,
)

@Serializable
internal data class BridgeEventEnvelope(
    val kind: String = "event",
    val protocolVersion: Int = BridgeProtocolVersion,
    val documentId: String,
    val bridgeSessionId: String,
    val subscriptionId: String = "app-state",
    val sequence: Long,
    val type: String,
    val payload: JsonElement = JsonObject(emptyMap()),
)

@Serializable
internal data class BridgeBootstrapPayload(
    val supportedProtocolVersions: List<Int>,
    val webBundleVersion: String,
    val schemaHash: String,
)

@Serializable
internal data class BridgeProductCommand(
    val type: String,
    val requestKey: String,
    val expectedSnapshotRevision: String,
    val payload: JsonObject,
)

@Serializable
internal data class WebSafeAreaSnapshot(
    val top: Double = 0.0,
    val right: Double = 0.0,
    val bottom: Double = 0.0,
    val left: Double = 0.0,
    val imeTop: Double = 874.0,
    val imeHeight: Double = 0.0,
    val imeProgress: Double = 0.0,
)

@Serializable
internal data class WebPetMediaSnapshot(
    val videoRef: String? = null,
    val posterRef: String? = null,
    val sadVideoRef: String? = null,
    val happyVideoRef: String? = null,
)

@Serializable
internal data class WebPetSnapshot(
    val name: String,
    val stageLabel: String,
    val experience: Int,
    val hunger: Int,
    val happiness: Int,
    val energy: Int,
    val message: String,
    val petTapProgress: Int,
    val media: WebPetMediaSnapshot,
)

@Serializable
internal data class WebCreateQuestionSnapshot(
    val title: String,
    val options: List<String>,
)

@Serializable
internal data class WebCreateSnapshot(
    val step: Int,
    val title: String,
    val options: List<String>,
    val nextQuestion: WebCreateQuestionSnapshot? = null,
    val phase: String,
    val generation: String,
    val error: String? = null,
    val retryTarget: String? = null,
)

@Serializable
internal data class WebFirstSessionSnapshot(
    val stage: String,
    val allowedAction: String? = null,
    val messagePortions: List<String> = emptyList(),
    val selectedDestination: String? = null,
)

@Serializable
internal data class WebPetTapFeedbackSnapshot(
    val eventId: String,
    val rewarded: Boolean,
    val thanks: String? = null,
    val visibleMillis: Long,
)

@Serializable
internal data class WebPendingOperationSnapshot(
    val requestKey: String,
    val status: String,
    val prompt: String? = null,
)

@Serializable
internal data class WebPendingOperationsSnapshot(
    val chat: WebPendingOperationSnapshot? = null,
    val outfit: WebPendingOperationSnapshot? = null,
    val travel: WebPendingOperationSnapshot? = null,
)

@Serializable
internal data class WebBridgeCapabilities(
    val requestNotificationPermission: Boolean = true,
    val shareTravelVideo: Boolean = true,
    val feedback: Boolean = true,
    val navigationReady: Boolean = true,
    val opaqueMedia: Boolean = true,
)

@Serializable
internal data class WebDashboardReplySnapshot(
    val source: String,
    val requestKey: String,
    val portions: List<String>,
    val portionIndex: Int,
    val hasNextPortion: Boolean,
    val autoAdvanceDelayMillis: Long,
)

@Serializable
internal data class WebDashboardChatSnapshot(
    val draft: String = "",
    val error: String? = null,
    val activeRequestKey: String? = null,
    val queuedRequestKey: String? = null,
    val thinking: Boolean = false,
)

@Serializable
internal data class WebDashboardFeedSnapshot(
    val error: String? = null,
    val activeRequestKey: String? = null,
    val activeFood: String? = null,
    val audioIndex: Int? = null,
    val pulseId: Int = 0,
    val thinking: Boolean = false,
)

@Serializable
internal data class WebDashboardOutfitPendingSnapshot(
    val requestKey: String,
    val status: String,
    val prompt: String,
    val displayItem: String,
    val experienceCost: Int,
)

@Serializable
internal data class WebDashboardOutfitSnapshot(
    val draft: String = "",
    val error: String? = null,
    val activeRequestKey: String? = null,
    val thinking: Boolean = false,
    val experienceCost: Int = 200,
    val pending: WebDashboardOutfitPendingSnapshot? = null,
)

@Serializable
internal data class WebDashboardTravelPendingSnapshot(
    val requestKey: String,
    val status: String,
    val prompt: String,
)

@Serializable
internal data class WebDashboardTravelSnapshot(
    val draft: String = "",
    val error: String? = null,
    val activeRequestKey: String? = null,
    val thinking: Boolean = false,
    val pending: WebDashboardTravelPendingSnapshot? = null,
)

@Serializable
internal data class WebDashboardSnapshot(
    val reply: WebDashboardReplySnapshot? = null,
    val chat: WebDashboardChatSnapshot = WebDashboardChatSnapshot(),
    val feed: WebDashboardFeedSnapshot = WebDashboardFeedSnapshot(),
    val outfit: WebDashboardOutfitSnapshot = WebDashboardOutfitSnapshot(),
    val travel: WebDashboardTravelSnapshot = WebDashboardTravelSnapshot(),
)

@Serializable
internal data class WebAppSnapshot(
    val protocolVersion: Int = BridgeProtocolVersion,
    val appVersion: String,
    val webBundleVersion: String,
    val revision: String,
    val route: String,
    val dashboardMode: String = "idle",
    val capabilities: WebBridgeCapabilities = WebBridgeCapabilities(),
    val pendingDeepLinkTarget: String? = null,
    val reducedMotion: Boolean,
    val safeArea: WebSafeAreaSnapshot,
    val notificationPermission: String,
    val create: WebCreateSnapshot? = null,
    val pet: WebPetSnapshot? = null,
    val firstSession: WebFirstSessionSnapshot? = null,
    val dashboard: WebDashboardSnapshot? = null,
    val events: WebEventsSnapshot? = null,
    val story: WebOpenedStorySnapshot? = null,
    val pending: WebPendingOperationsSnapshot = WebPendingOperationsSnapshot(),
    val petTapFeedback: WebPetTapFeedbackSnapshot? = null,
)

internal interface WebAppRuntime {
    val events: Flow<WebAppRuntimeEvent>
        get() = emptyFlow()

    suspend fun snapshot(): WebAppSnapshot

    suspend fun dispatch(command: BridgeProductCommand): WebAppSnapshot

    fun updateSafeArea(safeArea: WebSafeAreaSnapshot) = Unit

    fun onForeground() = Unit

    fun onBackground() = Unit
}

internal data class WebAppRuntimeEvent(
    val type: String,
    val payload: JsonElement,
)

internal class WebAppRuntimeException(
    val bridgeCode: String,
    val retryable: Boolean = false,
    val snapshot: WebAppSnapshot? = null,
) : IllegalStateException(bridgeCode)
