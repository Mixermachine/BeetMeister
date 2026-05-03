package de.aarondietz.beetmeister.ui.feature.events

import de.aarondietz.beetmeister.R
import de.aarondietz.beetmeister.model.event.BeetSystemEvent
import de.aarondietz.beetmeister.model.event.BeetWateringEvent
import de.aarondietz.beetmeister.model.repository.BeetRepositoryState
import de.aarondietz.beetmeister.strings.BeetStringResolver
import de.aarondietz.beetmeister.ui.core.formatting.formatEventTime
import de.aarondietz.beetmeister.ui.core.formatting.formatUnixSeconds
import de.aarondietz.beetmeister.ui.core.formatting.stopReasonLabel

internal fun EventFilter.acceptsSystem(event: BeetSystemEvent): Boolean = when (this) {
    EventFilter.All, EventFilter.System -> true
    EventFilter.Bluetooth -> event.eventType.startsWith("BLE_")
    EventFilter.Sleep -> event.eventType == "SLEEP"
    EventFilter.Startup -> event.eventType == "STARTUP"
    EventFilter.MQTT -> event.eventType.startsWith("MQTT_")
    EventFilter.Ota -> event.eventType.startsWith("OTA_")
    EventFilter.Watering -> false
}

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
