package com.gigagochi.app.feature.events

import com.gigagochi.app.core.database.LocalScheduledStory
import com.gigagochi.app.core.database.LocalTravelVideoAsset
import java.util.GregorianCalendar
import java.util.TimeZone

sealed interface EventHistoryItem {
    val key: String
    val timestampMillis: Long

    data class TravelVideo(
        val asset: LocalTravelVideoAsset,
    ) : EventHistoryItem {
        override val key: String = travelEventKey(asset.requestKey)
        override val timestampMillis: Long = asset.completedAtEpochMillis
    }

    data class ScheduledStory(
        val item: LocalScheduledStory,
    ) : EventHistoryItem {
        override val key: String = storyEventKey(item.story.storyId)
        override val timestampMillis: Long = eventTimestampMillis(item.story.createdAt)
        val answered: Boolean = item.story.selectedChoice != null
    }
}

data class EventHistoryUiState(
    val items: List<EventHistoryItem>,
) {
    val unanswered: List<LocalScheduledStory> = items.mapNotNull {
        (it as? EventHistoryItem.ScheduledStory)?.takeUnless(EventHistoryItem.ScheduledStory::answered)
            ?.item
    }
    val answered: List<LocalScheduledStory> = items.mapNotNull {
        (it as? EventHistoryItem.ScheduledStory)?.takeIf(EventHistoryItem.ScheduledStory::answered)
            ?.item
    }
    val unansweredCount: Int = items.count {
        it is EventHistoryItem.ScheduledStory && !it.answered
    }
    val latestTimestampMillis: Long? = items.maxOfOrNull(EventHistoryItem::timestampMillis)
        ?.takeIf { it >= 0L }
    val isEmpty: Boolean = items.isEmpty()

    fun badgeCount(lastViewedAtEpochMillis: Long?): Int = items.count { item ->
        val needsAnswer = item is EventHistoryItem.ScheduledStory && !item.answered
        val isNew = lastViewedAtEpochMillis == null || item.timestampMillis > lastViewedAtEpochMillis
        needsAnswer || isNew
    }
}

fun eventHistoryUiState(
    stories: List<LocalScheduledStory>,
    travelVideos: List<LocalTravelVideoAsset> = emptyList(),
): EventHistoryUiState {
    val items = buildList {
        stories.forEach { add(EventHistoryItem.ScheduledStory(it)) }
        travelVideos
            .filter { it.consumedAtEpochMillis != null }
            .forEach { add(EventHistoryItem.TravelVideo(it)) }
    }.sortedWith(
        compareByDescending<EventHistoryItem> { it.timestampMillis }
            .thenByDescending { it.key },
    )
    return EventHistoryUiState(items)
}

fun storyEventKey(storyId: String): String = "story:$storyId"

fun travelEventKey(requestKey: String): String = "travel:$requestKey"

fun travelEventCaption(asset: LocalTravelVideoAsset): String =
    asset.title?.trim()?.takeIf(String::isNotEmpty) ?: asset.prompt.trim()

private val EventTimestampRegex = Regex(
    "^(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})(?:\\.\\d{1,9})?(Z|[+-]\\d{2}:\\d{2})$",
)

private fun eventTimestampMillis(value: String): Long = runCatching {
    val match = requireNotNull(EventTimestampRegex.matchEntire(value))
    val zoneText = match.groupValues[7]
    val zone = if (zoneText == "Z") {
        TimeZone.getTimeZone("UTC")
    } else {
        TimeZone.getTimeZone("GMT$zoneText")
    }
    GregorianCalendar(zone).apply {
        isLenient = false
        clear()
        set(
            match.groupValues[1].toInt(),
            match.groupValues[2].toInt() - 1,
            match.groupValues[3].toInt(),
            match.groupValues[4].toInt(),
            match.groupValues[5].toInt(),
            match.groupValues[6].toInt(),
        )
    }.timeInMillis
}.getOrDefault(Long.MIN_VALUE)
