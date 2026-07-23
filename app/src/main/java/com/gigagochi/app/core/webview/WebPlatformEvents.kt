package com.gigagochi.app.core.webview

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.gigagochi.app.core.background.notificationPermissionWasAsked
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.encodeToJsonElement

internal enum class WebNotificationPermissionStatus(val wireValue: String) {
    Unknown("unknown"),
    Granted("granted"),
    Denied("denied"),
}

internal fun resolveWebNotificationPermissionStatus(
    sdkInt: Int,
    permissionGranted: Boolean,
    asked: Boolean,
): WebNotificationPermissionStatus = when {
    sdkInt < 33 -> WebNotificationPermissionStatus.Granted
    permissionGranted -> WebNotificationPermissionStatus.Granted
    asked -> WebNotificationPermissionStatus.Denied
    else -> WebNotificationPermissionStatus.Unknown
}

internal fun webNotificationPermissionStatus(context: Context): WebNotificationPermissionStatus =
    resolveWebNotificationPermissionStatus(
        sdkInt = Build.VERSION.SDK_INT,
        permissionGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED,
        asked = notificationPermissionWasAsked(context),
    )

@Serializable
internal data class WebPermissionChangedPayload(val status: String)

@Serializable
internal data class WebLifecycleChangedPayload(val state: String)

internal fun permissionChangedEvent(status: WebNotificationPermissionStatus): WebAppRuntimeEvent =
    WebAppRuntimeEvent(
        type = "permissionChanged",
        payload = BridgeCodec.json.encodeToJsonElement(
            WebPermissionChangedPayload.serializer(),
            WebPermissionChangedPayload(status.wireValue),
        ),
    )

internal fun lifecycleChangedEvent(state: WebLifecycleState): WebAppRuntimeEvent =
    WebAppRuntimeEvent(
        type = "lifecycleChanged",
        payload = BridgeCodec.json.encodeToJsonElement(
            WebLifecycleChangedPayload.serializer(),
            WebLifecycleChangedPayload(state.wireValue),
        ),
    )

internal enum class WebLifecycleState(val wireValue: String) {
    Foreground("foreground"),
    Background("background"),
}

internal class WebLifecycleEventController {
    private var observerReady = false
    private var lastState: WebLifecycleState? = null

    fun transition(state: WebLifecycleState): WebAppRuntimeEvent? {
        if (!observerReady) return null
        if (state == lastState) return null
        lastState = state
        return lifecycleChangedEvent(state)
    }

    /**
     * A posted bridge response is the ordering fence after which the Web observer can accept
     * session-scoped events. Lifecycle changes seen while bootstrap is still in flight are not
     * consumed; the caller replays the current state immediately after opening this fence.
     */
    fun markObserverReady() {
        observerReady = true
    }

    fun isObserverReady(): Boolean = observerReady

    fun abandonTransition(eventGeneration: Long, currentGeneration: Long) {
        if (eventGeneration == currentGeneration) resetForDocument()
    }

    /** A replacement renderer is a new observer and must receive the current state again. */
    fun resetForDocument() {
        observerReady = false
        lastState = null
    }
}

internal data class WebPermissionPublication(
    val documentFence: BridgeDocumentFence,
    val status: WebNotificationPermissionStatus,
)

internal sealed interface WebNotificationPermissionDecision {
    data object AwaitResult : WebNotificationPermissionDecision

    data class LaunchPrompt(val documentFence: BridgeDocumentFence) :
        WebNotificationPermissionDecision

    data class Publish(val publication: WebPermissionPublication) :
        WebNotificationPermissionDecision
}

internal class WebNotificationPermissionCoordinator {
    private var pendingFence: BridgeDocumentFence? = null

    fun request(
        documentFence: BridgeDocumentFence,
        currentStatus: WebNotificationPermissionStatus,
    ): WebNotificationPermissionDecision {
        if (pendingFence != null) {
            pendingFence = documentFence
            return WebNotificationPermissionDecision.AwaitResult
        }
        return if (currentStatus == WebNotificationPermissionStatus.Unknown) {
            pendingFence = documentFence
            WebNotificationPermissionDecision.LaunchPrompt(documentFence)
        } else {
            WebNotificationPermissionDecision.Publish(
                WebPermissionPublication(documentFence, currentStatus),
            )
        }
    }

    fun result(granted: Boolean): WebPermissionPublication? {
        val fence = pendingFence ?: return null
        pendingFence = null
        return WebPermissionPublication(
            documentFence = fence,
            status = if (granted) {
                WebNotificationPermissionStatus.Granted
            } else {
                WebNotificationPermissionStatus.Denied
            },
        )
    }
}

internal fun interface WebNotificationPermissionRequestHandler {
    fun request(documentFence: BridgeDocumentFence)
}
