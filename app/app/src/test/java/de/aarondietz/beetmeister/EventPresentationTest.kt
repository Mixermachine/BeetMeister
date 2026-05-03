package de.aarondietz.beetmeister

import de.aarondietz.beetmeister.model.controller.BeetDeviceState
import de.aarondietz.beetmeister.model.event.BeetSystemEvent
import de.aarondietz.beetmeister.model.event.BeetWateringEvent
import de.aarondietz.beetmeister.model.repository.BeetRepositoryState
import de.aarondietz.beetmeister.ui.feature.events.EventFilter
import de.aarondietz.beetmeister.ui.feature.events.WateringWindow
import de.aarondietz.beetmeister.ui.feature.events.acceptsSystem
import de.aarondietz.beetmeister.ui.feature.events.formatSystemEventTime
import de.aarondietz.beetmeister.ui.feature.events.formatWateringTime
import de.aarondietz.beetmeister.ui.feature.events.systemEventPeerLabel
import de.aarondietz.beetmeister.ui.feature.events.systemEventReasonLabel
import de.aarondietz.beetmeister.ui.feature.events.wateringTotals
import de.aarondietz.beetmeister.ui.feature.overview.runningSinceUnixSeconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventPresentationTest {
    private val strings = TestBeetStringResolver()

    @Test
    fun wateringTotalsUsesOnlyResolvedEventsInsideSelectedWindow() {
        val nowSeconds = 1_000_000L
        val events = listOf(
            wateringEvent(seq = 1, pair = 1, endUnix = nowSeconds - 60, actualDuration = 120, timeValid = true, triggerSource = 1),
            wateringEvent(seq = 2, pair = 1, endUnix = nowSeconds - (9L * 24L * 60L * 60L), actualDuration = 240, timeValid = true, triggerSource = 1),
            wateringEvent(seq = 3, pair = 2, endUnix = nowSeconds - 120, actualDuration = 180, timeValid = false, triggerSource = 1),
            wateringEvent(seq = 4, pair = 3, endUnix = nowSeconds - 120, actualDuration = 30, timeValid = true, triggerSource = 3),
        )

        val totals = wateringTotals(events, WateringWindow.Day, nowSeconds)

        assertEquals(120, totals[0])
        assertEquals(0, totals[1])
        assertEquals(0, totals[2])
    }

    @Test
    fun eventFilterSeparatesBluetoothAndOtaSystemEvents() {
        val bluetoothEvent = systemEvent(eventType = "BLE_CONNECT")
        val otaEvent = systemEvent(eventType = "OTA_READY")
        val mqttEvent = systemEvent(eventType = "MQTT_CONNECT")

        assertTrue(EventFilter.Bluetooth.acceptsSystem(bluetoothEvent))
        assertFalse(EventFilter.Bluetooth.acceptsSystem(otaEvent))
        assertTrue(EventFilter.Ota.acceptsSystem(otaEvent))
        assertFalse(EventFilter.Ota.acceptsSystem(mqttEvent))
        assertTrue(EventFilter.MQTT.acceptsSystem(mqttEvent))
    }

    @Test
    fun systemEventReasonAndPeerLabelsAreHumanReadable() {
        val sleep = systemEvent(eventType = "SLEEP", reason = 6)
        val disconnect = systemEvent(eventType = "BLE_DISCONNECT", reason = 531, peer = "AA:BB:CC:DD:EE:FF", knownPeer = true)
        val newPeer = systemEvent(eventType = "BLE_CONNECT", reason = 0, peer = "11:22:33:44:55:66", knownPeer = false)

        assertEquals("Idle low-power sleep", systemEventReasonLabel(sleep, strings))
        assertEquals("Disconnect code 531", systemEventReasonLabel(disconnect, strings))
        assertEquals("Known AA:BB:CC:DD:EE:FF", systemEventPeerLabel(disconnect, strings))
        assertEquals("New 11:22:33:44:55:66", systemEventPeerLabel(newPeer, strings))
    }

    @Test
    fun unresolvedEventsDifferentiateCurrentBootFromIgnoredLegacy() {
        val state = BeetRepositoryState(
            deviceState = BeetDeviceState(
                batteryState = "ACTIVE",
                batteryMillivolts = 3330,
                timeValid = false,
                bootId = 41,
                nextCheckInSeconds = 100,
                activePumps = 0,
                wifiConnected = false,
                mqttConnected = false,
                uptimeSeconds = 123,
            ),
        )
        val currentBootSystem = systemEvent(eventType = "STARTUP", bootId = 41, unix = 0, timeValid = false)
        val oldBootSystem = systemEvent(eventType = "STARTUP", bootId = 40, unix = 0, timeValid = false)
        val currentBootWatering = wateringEvent(seq = 10, pair = 2, bootId = 41, endUnix = 0, actualDuration = 30, timeValid = false, triggerSource = 2)
        val oldBootWatering = wateringEvent(seq = 11, pair = 2, bootId = 40, endUnix = 0, actualDuration = 30, timeValid = false, triggerSource = 2)

        assertEquals("Pending time sync", formatSystemEventTime(currentBootSystem, state, strings))
        assertEquals("Ignored legacy", formatSystemEventTime(oldBootSystem, state, strings))
        assertEquals("Pending time sync", formatWateringTime(currentBootWatering, state, strings))
        assertEquals("Ignored legacy", formatWateringTime(oldBootWatering, state, strings))
    }

    @Test
    fun runningSinceUsesConnectionAnchorWhenAvailable() {
        val runningSince = runningSinceUnixSeconds(
            connectedAtMillis = 2_000_000L,
            connectedAtControllerUptimeSeconds = 300L,
            fallbackUptimeSeconds = 999L,
            nowMillis = 9_999_999L,
        )

        assertEquals(1700L, runningSince)
    }

    private fun wateringEvent(
        seq: Long,
        pair: Int,
        bootId: Long = 41L,
        endUnix: Long,
        actualDuration: Int,
        timeValid: Boolean,
        triggerSource: Int,
    ) = BeetWateringEvent(
        sequenceNumber = seq,
        pairIndex = pair,
        bootId = bootId,
        triggerSource = triggerSource,
        startedAtUnixSeconds = if (timeValid) endUnix - actualDuration else 0L,
        endedAtUnixSeconds = if (timeValid) endUnix else 0L,
        moistureBeforePercent = 40,
        moistureAfterPercent = 55,
        sensorBeforeMillivolts = 1400,
        sensorAfterMillivolts = 900,
        requestedDurationSeconds = actualDuration,
        actualDurationSeconds = actualDuration,
        stopReason = 0,
        blockReason = 0,
        batteryStartMillivolts = 3340,
        batteryEndMillivolts = 3330,
        startedUptimeSeconds = 100,
        endedUptimeSeconds = 100L + actualDuration.toLong(),
    )

    private fun systemEvent(
        eventType: String,
        reason: Int = 0,
        bootId: Long = 41L,
        unix: Long = 0L,
        timeValid: Boolean = false,
        peer: String = "",
        knownPeer: Boolean = false,
    ) = BeetSystemEvent(
        sequenceNumber = 1,
        eventType = eventType,
        reason = reason,
        bootId = bootId,
        uptimeSeconds = 123,
        unixSeconds = if (timeValid) unix else 0L,
        batteryMillivolts = 3330,
        peerAddress = peer,
        peerAddressType = 1,
        knownPeer = knownPeer,
        detail = 0,
    )
}
