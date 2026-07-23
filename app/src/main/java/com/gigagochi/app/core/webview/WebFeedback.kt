package com.gigagochi.app.core.webview

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal enum class WebFeedbackKind {
    @SerialName("createAnswer")
    CreateAnswer,

    @SerialName("createCustom")
    CreateCustom,

    @SerialName("createRetry")
    CreateRetry,

    @SerialName("dashboardAction")
    DashboardAction,

    @SerialName("chatSubmit")
    ChatSubmit,

    @SerialName("buttonPress")
    ButtonPress,
}

@Serializable
internal data class WebFeedbackPayload(
    val kind: WebFeedbackKind,
    val eventId: String,
)

internal fun interface WebFeedbackHandler {
    fun handle(kind: WebFeedbackKind)
}
