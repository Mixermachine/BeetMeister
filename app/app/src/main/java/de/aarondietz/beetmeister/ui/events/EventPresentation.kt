package de.aarondietz.beetmeister.ui.events

import de.aarondietz.beetmeister.beet.BeetEventMappings
import de.aarondietz.beetmeister.beet.BeetRepositoryState
import de.aarondietz.beetmeister.beet.BeetSystemEvent
import de.aarondietz.beetmeister.beet.BeetWateringEvent
import de.aarondietz.beetmeister.ui.formatting.formatEventTime
import de.aarondietz.beetmeister.ui.formatting.formatUnixSeconds

internal enum class WateringWindow(val label: String, val seconds: Long) {
    Day("Last 24 hours", 24L * 60L * 60L),
    Week("Last 7 days", 7L * 24L * 60L * 60L),
}

internal enum class EventFilter(val label: String) {
    All("All"),
    System("System"),
    Watering("Watering"),
    Bluetooth("Bluetooth"),
    Sleep("Sleep"),
    Startup("Startup"),
    MQTT("MQTT"),
    Ota("OTA"),
}

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

internal fun formatSystemEventTime(event: BeetSystemEvent, state: BeetRepositoryState): String {
    if (event.timeValid && event.unixSeconds > 0L) {
        return formatUnixSeconds(event.unixSeconds)
    }
    val currentBootId = state.deviceState?.bootId ?: 0L
    return if (event.bootId > 0L && event.bootId == currentBootId) "Pending time sync" else "Ignored legacy"
}

internal fun formatWateringTime(event: BeetWateringEvent, state: BeetRepositoryState): String {
    if (event.timeValid) {
        return formatEventTime(event)
    }
    val currentBootId = state.deviceState?.bootId ?: 0L
    return if (event.bootId > 0L && event.bootId == currentBootId) "Pending time sync" else "Ignored legacy"
}

internal fun systemEventReasonLabel(event: BeetSystemEvent): String = when (event.eventType) {
    "SLEEP" -> BeetEventMappings.stopReasonLabel(event.reason)
    "BLE_DISCONNECT" -> if (event.reason == 0) "Disconnected" else "Disconnect code ${event.reason}"
    "BLE_BOND_FAILED" -> "Bond status ${event.reason}"
    "MQTT_PUBLISH_FAILED" -> if (event.reason == 0) "Publish failed" else "Publish status ${event.reason}"
    else -> if (event.reason == 0) "None" else event.reason.toString()
}

internal fun systemEventPeerLabel(event: BeetSystemEvent): String {
    if (!event.eventType.startsWith("BLE_")) {
        return "-"
    }
    if (event.peerAddress.isBlank() || event.peerAddress == "00:00:00:00:00:00") {
        return if (event.knownPeer) "Known peer" else "New peer"
    }
    val peerType = if (event.knownPeer) "Known" else "New"
    return "$peerType ${event.peerAddress}"
}
