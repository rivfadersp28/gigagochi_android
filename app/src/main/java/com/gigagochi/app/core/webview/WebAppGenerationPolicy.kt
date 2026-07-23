package com.gigagochi.app.core.webview

/**
 * Controls generation work that is not the direct result of a product command.
 *
 * [UserInvocationsOnly] has no release Activity entry point. The debug production-runtime host
 * uses it to keep a bounded live E2E run from starting ambient, due-story, or proactive
 * generation alongside the Create/Outfit/Travel actions being measured.
 */
internal enum class WebAppGenerationPolicy(
    val automaticGenerationEnabled: Boolean,
    val backgroundFeatureSyncEnabled: Boolean,
) {
    Production(
        automaticGenerationEnabled = true,
        backgroundFeatureSyncEnabled = true,
    ),
    UserInvocationsOnly(
        automaticGenerationEnabled = false,
        backgroundFeatureSyncEnabled = false,
    ),
}
