package com.gigagochi.app.core.security

import com.gigagochi.app.core.database.LocalScheduledStory
import com.gigagochi.app.core.database.LocalTravelVideoAsset
import com.gigagochi.app.core.database.PetLocalRepository

internal const val NotificationStoryIdExtra = "gigagochi.storyId"
internal const val NotificationTravelRequestKeyExtra = "gigagochi.travelRequestKey"

private const val NotificationStoryIdMaxLength = 96

internal enum class NotificationDeepLinkExtraState {
    Absent,
    Present,
    Invalid,
}

/** Values stay nullable so presence/type information can be extracted outside this pure module. */
internal data class NotificationDeepLinkExtras(
    val storyId: String? = null,
    val travelRequestKey: String? = null,
    val storyIdState: NotificationDeepLinkExtraState =
        if (storyId == null) NotificationDeepLinkExtraState.Absent
        else NotificationDeepLinkExtraState.Present,
    val travelRequestKeyState: NotificationDeepLinkExtraState =
        if (travelRequestKey == null) NotificationDeepLinkExtraState.Absent
        else NotificationDeepLinkExtraState.Present,
) {
    companion object {
        fun fromPresence(
            storyIdPresent: Boolean = false,
            storyId: String? = null,
            storyIdTypeValid: Boolean = true,
            travelRequestKeyPresent: Boolean = false,
            travelRequestKey: String? = null,
            travelRequestKeyTypeValid: Boolean = true,
        ): NotificationDeepLinkExtras = NotificationDeepLinkExtras(
            storyId = storyId,
            travelRequestKey = travelRequestKey,
            storyIdState = extraState(storyIdPresent, storyIdTypeValid),
            travelRequestKeyState = extraState(
                travelRequestKeyPresent,
                travelRequestKeyTypeValid,
            ),
        )

        private fun extraState(
            present: Boolean,
            typeValid: Boolean,
        ): NotificationDeepLinkExtraState = when {
            !present -> NotificationDeepLinkExtraState.Absent
            !typeValid -> NotificationDeepLinkExtraState.Invalid
            else -> NotificationDeepLinkExtraState.Present
        }
    }
}

/**
 * Pure boundary for Android Bundle values. A present non-String, null, or unreadable value is
 * intentionally preserved as invalid instead of being confused with an absent notification.
 */
internal fun notificationDeepLinkExtrasFromUntypedPresence(
    storyIdPresent: Boolean = false,
    storyIdValue: Any? = null,
    storyIdReadable: Boolean = true,
    travelRequestKeyPresent: Boolean = false,
    travelRequestKeyValue: Any? = null,
    travelRequestKeyReadable: Boolean = true,
): NotificationDeepLinkExtras? {
    if (!storyIdPresent && !travelRequestKeyPresent) return null
    return NotificationDeepLinkExtras.fromPresence(
        storyIdPresent = storyIdPresent,
        storyId = storyIdValue as? String,
        storyIdTypeValid = storyIdReadable && storyIdValue is String,
        travelRequestKeyPresent = travelRequestKeyPresent,
        travelRequestKey = travelRequestKeyValue as? String,
        travelRequestKeyTypeValid = travelRequestKeyReadable &&
            travelRequestKeyValue is String,
    )
}

internal sealed interface ParsedNotificationDeepLink {
    data object None : ParsedNotificationDeepLink
    data object Invalid : ParsedNotificationDeepLink
    data class Story(val storyId: String) : ParsedNotificationDeepLink
    data class Travel(val requestKey: String) : ParsedNotificationDeepLink
}

/** Story is intentionally selected first when both supported extras are present. */
internal fun parseNotificationDeepLink(
    extras: NotificationDeepLinkExtras,
): ParsedNotificationDeepLink {
    when (extras.storyIdState) {
        NotificationDeepLinkExtraState.Invalid -> return ParsedNotificationDeepLink.Invalid
        NotificationDeepLinkExtraState.Present -> {
            val storyId = extras.storyId ?: return ParsedNotificationDeepLink.Invalid
            if (!storyId.isBoundedStoryId()) return ParsedNotificationDeepLink.Invalid
            return ParsedNotificationDeepLink.Story(storyId)
        }
        NotificationDeepLinkExtraState.Absent -> Unit
    }
    when (extras.travelRequestKeyState) {
        NotificationDeepLinkExtraState.Invalid -> return ParsedNotificationDeepLink.Invalid
        NotificationDeepLinkExtraState.Present -> {
            val requestKey = extras.travelRequestKey ?: return ParsedNotificationDeepLink.Invalid
            val canonicalRequestKey = canonicalTravelRequestKeyOrNull(requestKey)
                ?: return ParsedNotificationDeepLink.Invalid
            return ParsedNotificationDeepLink.Travel(canonicalRequestKey)
        }
        NotificationDeepLinkExtraState.Absent -> Unit
    }
    return ParsedNotificationDeepLink.None
}

private fun String.isBoundedStoryId(): Boolean =
    isNotBlank() &&
        this == trim() &&
        length <= NotificationStoryIdMaxLength &&
        none(Char::isISOControl)

internal interface ScopedNotificationDeepLinkStore {
    suspend fun findStory(
        ownerId: String,
        petId: String,
        storyId: String,
    ): LocalScheduledStory?

    suspend fun findTravelVideo(
        ownerId: String,
        petId: String,
        requestKey: String,
    ): LocalTravelVideoAsset?
}

internal class PetLocalRepositoryNotificationDeepLinkStore(
    private val repository: PetLocalRepository,
) : ScopedNotificationDeepLinkStore {
    override suspend fun findStory(
        ownerId: String,
        petId: String,
        storyId: String,
    ): LocalScheduledStory? = repository.getScheduledStory(ownerId, storyId)
        ?.takeIf { localStory -> localStory.story.petId == petId }

    override suspend fun findTravelVideo(
        ownerId: String,
        petId: String,
        requestKey: String,
    ): LocalTravelVideoAsset? = repository.getTravelVideoAsset(ownerId, requestKey)
        ?.takeIf { asset -> asset.petId == petId }
}

internal sealed interface NotificationDeepLinkDestination {
    data object Dashboard : NotificationDeepLinkDestination
    data class Story(val item: LocalScheduledStory) : NotificationDeepLinkDestination
    data class Events(val item: LocalTravelVideoAsset) : NotificationDeepLinkDestination
}

internal class NotificationDeepLinkResolver(
    private val ownerId: String,
    private val activePetId: String,
    private val store: ScopedNotificationDeepLinkStore,
) {
    init {
        require(ownerId.isNotBlank())
        require(activePetId.isNotBlank())
    }

    suspend fun resolve(
        extras: NotificationDeepLinkExtras,
    ): NotificationDeepLinkDestination = when (val target = parseNotificationDeepLink(extras)) {
        ParsedNotificationDeepLink.None,
        ParsedNotificationDeepLink.Invalid,
        -> NotificationDeepLinkDestination.Dashboard

        is ParsedNotificationDeepLink.Story -> resolveStory(target.storyId)
        is ParsedNotificationDeepLink.Travel -> resolveTravel(target.requestKey)
    }

    private suspend fun resolveStory(storyId: String): NotificationDeepLinkDestination {
        val item = store.findStory(
            ownerId = ownerId,
            petId = activePetId,
            storyId = storyId,
        ) ?: return NotificationDeepLinkDestination.Dashboard
        if (
            item.ownerId != ownerId ||
            item.story.petId != activePetId ||
            item.story.storyId != storyId
        ) {
            return NotificationDeepLinkDestination.Dashboard
        }
        return NotificationDeepLinkDestination.Story(item)
    }

    private suspend fun resolveTravel(requestKey: String): NotificationDeepLinkDestination {
        val item = store.findTravelVideo(
            ownerId = ownerId,
            petId = activePetId,
            requestKey = requestKey,
        ) ?: return NotificationDeepLinkDestination.Dashboard
        if (
            item.ownerId != ownerId ||
            item.petId != activePetId ||
            item.requestKey != requestKey ||
            item.consumedAtEpochMillis == null ||
            item.videoUrl.isBlank()
        ) {
            return NotificationDeepLinkDestination.Dashboard
        }
        return NotificationDeepLinkDestination.Events(item)
    }
}
