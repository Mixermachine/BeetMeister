package de.aarondietz.beetmeister.ui.feature.events

import de.aarondietz.beetmeister.R
import de.aarondietz.beetmeister.model.event.BeetSystemEvent
import de.aarondietz.beetmeister.model.event.BeetWateringEvent
import de.aarondietz.beetmeister.model.repository.BeetRepositoryState
import de.aarondietz.beetmeister.strings.BeetStringResolver
import de.aarondietz.beetmeister.ui.core.formatting.formatDuration
import de.aarondietz.beetmeister.ui.core.formatting.formatEventTime
import de.aarondietz.beetmeister.ui.core.formatting.formatMillivolts
import de.aarondietz.beetmeister.ui.core.formatting.formatUnixSeconds
import de.aarondietz.beetmeister.ui.core.formatting.stopReasonLabel
import de.aarondietz.beetmeister.ui.core.formatting.systemEventLabel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

internal data class SystemEventSection(
    val key: String,
    val title: String,
    val events: List<BeetSystemEvent>,
)

internal fun EventFilter.acceptsSystem(event: BeetSystemEvent): Boolean = when (this) {
    EventFilter.All, EventFilter.System -> true
    EventFilter.Bluetooth -> event.eventType.startsWith("BLE_")
    EventFilter.Sleep -> event.eventType == "SLEEP"
    EventFilter.Startup -> event.eventType == "STARTUP"
    EventFilter.MQTT -> event.eventType.startsWith("MQTT_")
    EventFilter.Ota -> event.eventType.startsWith("OTA_")
    EventFilter.Watering -> false
}

internal fun systemEventFilters(): List<EventFilter> = listOf(
    EventFilter.All,
    EventFilter.Bluetooth,
    EventFilter.Sleep,
    EventFilter.Startup,
    EventFilter.MQTT,
    EventFilter.Ota,
)

internal fun wateringTotals(events: List<BeetWateringEvent>, window: WateringWindow, nowSeconds: Long): List<Int> {
    val cutoff = nowSeconds - window.seconds
    val totals = MutableList(8) { 0 }
    events.forEach { event ->
        if (event.triggerSource == 3 || event.pairIndex !in 1..8) {
            return@forEach
        }
        val eventSeconds = event.endedAtUnixSeconds.takeIf { event.timeValid && it > 0L } ?: return@forEach
        if (eventSeconds >= cutoff) {
            totals[event.pairIndex - 1] += event.actualDurationSeconds
        }
    }
    return totals
}

internal fun groupSystemEvents(
    events: List<BeetSystemEvent>,
    state: BeetRepositoryState,
    strings: BeetStringResolver,
    now: Instant = Instant.now(),
    zoneId: ZoneId = ZoneId.systemDefault(),
): List<SystemEventSection> {
    val today = now.atZone(zoneId).toLocalDate()
    val grouped = events.groupBy { event -> systemEventSectionKey(event, state, strings, today, zoneId) }
    return grouped.entries
        .sortedByDescending { (_, sectionEvents) -> sectionEvents.maxOf { event -> event.sequenceNumber } }
        .map { (key, sectionEvents) ->
            SystemEventSection(
                key = key.key,
                title = key.title,
                events = sectionEvents.sortedByDescending { event -> event.sequenceNumber },
            )
        }
}

internal fun formatSystemEventTime(
    event: BeetSystemEvent,
    state: BeetRepositoryState,
    strings: BeetStringResolver,
): String {
    if (event.timeValid && event.unixSeconds > 0L) {
        return formatUnixSeconds(event.unixSeconds, strings)
    }
    val currentBootId = state.deviceState?.bootId ?: 0L
    return strings.get(
        if (event.bootId > 0L && event.bootId == currentBootId) {
            R.string.events_pending_time_sync
        } else {
            R.string.events_ignored_legacy
        },
    )
}

internal fun formatSystemEventTimelineTime(
    event: BeetSystemEvent,
    strings: BeetStringResolver,
): String {
    if (event.timeValid && event.unixSeconds > 0L) {
        return formatUnixSeconds(event.unixSeconds, strings)
    }
    return strings.get(
        R.string.events_relative_uptime,
        formatDuration(event.uptimeSeconds.toInt(), strings),
    )
}

internal fun formatWateringTime(
    event: BeetWateringEvent,
    state: BeetRepositoryState,
    strings: BeetStringResolver,
): String {
    if (event.timeValid) {
        return formatEventTime(event, strings)
    }
    val currentBootId = state.deviceState?.bootId ?: 0L
    return strings.get(
        if (event.bootId > 0L && event.bootId == currentBootId) {
            R.string.events_pending_time_sync
        } else {
            R.string.events_ignored_legacy
        },
    )
}

internal fun systemEventTitle(event: BeetSystemEvent, strings: BeetStringResolver): String =
    systemEventLabel(event.eventType, strings)

internal fun systemEventCategoryLabel(event: BeetSystemEvent, strings: BeetStringResolver): String = strings.get(
    when {
        event.eventType.startsWith("BLE_") -> R.string.events_filter_bluetooth
        event.eventType.startsWith("MQTT_") -> R.string.events_filter_mqtt
        event.eventType.startsWith("OTA_") -> R.string.events_filter_ota
        event.eventType == "SLEEP" -> R.string.events_filter_sleep
        event.eventType == "STARTUP" -> R.string.events_filter_startup
        else -> R.string.events_filter_system
    },
)

internal fun systemEventCompactDetails(
    event: BeetSystemEvent,
    strings: BeetStringResolver,
): String {
    val tokens = buildList {
        val reason = systemEventReasonLabel(event, strings)
        if (reason != strings.get(R.string.common_none)) {
            add(reason)
        }

        val peer = systemEventPeerLabel(event, strings)
        if (peer != strings.get(R.string.placeholder_dash)) {
            add(peer)
        }

        add(formatMillivolts(event.batteryMillivolts, strings))
    }
    return tokens.joinToString(" | ")
}

internal fun systemEventReasonLabel(event: BeetSystemEvent, strings: BeetStringResolver): String = when (event.eventType) {
    "SLEEP" -> stopReasonLabel(event.reason, strings)
    "BLE_DISCONNECT" -> if (event.reason == 0) {
        strings.get(R.string.events_disconnected_reason)
    } else {
        strings.get(R.string.events_disconnect_code, event.reason)
    }
    "BLE_BOND_FAILED" -> strings.get(R.string.events_bond_status, event.reason)
    "MQTT_PUBLISH_FAILED" -> if (event.reason == 0) {
        strings.get(R.string.events_publish_failed)
    } else {
        strings.get(R.string.events_publish_status, event.reason)
    }
    else -> if (event.reason == 0) strings.get(R.string.common_none) else event.reason.toString()
}

internal fun systemEventPeerLabel(event: BeetSystemEvent, strings: BeetStringResolver): String {
    if (!event.eventType.startsWith("BLE_")) {
        return strings.get(R.string.placeholder_dash)
    }
    if (event.peerAddress.isBlank() || event.peerAddress == "00:00:00:00:00:00") {
        return strings.get(
            if (event.knownPeer) {
                R.string.events_known_peer
            } else {
                R.string.events_new_peer
            },
        )
    }
    return strings.get(
        if (event.knownPeer) {
            R.string.events_known_peer_address
        } else {
            R.string.events_new_peer_address
        },
        event.peerAddress,
    )
}

private data class SystemEventSectionKey(
    val key: String,
    val title: String,
)

private fun systemEventSectionKey(
    event: BeetSystemEvent,
    state: BeetRepositoryState,
    strings: BeetStringResolver,
    today: LocalDate,
    zoneId: ZoneId,
): SystemEventSectionKey {
    if (event.timeValid && event.unixSeconds > 0L) {
        val eventDate = Instant.ofEpochSecond(event.unixSeconds).atZone(zoneId).toLocalDate()
        val title = when (eventDate) {
            today -> strings.get(R.string.common_today)
            today.minusDays(1) -> strings.get(R.string.common_yesterday)
            else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                .withLocale(strings.locale)
                .format(eventDate)
        }
        return SystemEventSectionKey(
            key = "date:$eventDate",
            title = title,
        )
    }

    return SystemEventSectionKey(
        key = "boot:${event.bootId}",
        title = strings.get(R.string.events_boot_section_title, event.bootId),
    )
}
