package de.aarondietz.beetmeister.beet

import com.squareup.moshi.Json

enum class BeetConnectionPhase {
    PermissionsRequired,
    BluetoothDisabled,
    Idle,
    Scanning,
    Bonding,
    Connecting,
    DiscoveringServices,
    Syncing,
    Connected,
    Disconnected,
    Error,
}

data class BeetConnectionState(
    val phase: BeetConnectionPhase = BeetConnectionPhase.Idle,
    val detail: String? = null,
)

data class BeetDiscoveredDevice(
    val name: String,
    val address: String,
    val rssi: Int,
    val bondState: Int,
    val lastSeenMillis: Long,
)

data class BeetControllerInfo(
    @param:Json(name = "device_id") val deviceId: String,
    @param:Json(name = "protocol_version") val protocolVersion: Int,
    @param:Json(name = "firmware_version") val firmwareVersion: String,
    @param:Json(name = "pair_count") val pairCount: Int,
)

sealed interface BeetStateMessage {
    data class DeviceStateUpdate(val data: BeetDeviceState) : BeetStateMessage

    data class PairStateUpdate(val data: BeetPairState) : BeetStateMessage

    data class SystemEventUpdate(val data: BeetSystemEvent) : BeetStateMessage
}

data class BeetDeviceState(
    @param:Json(name = "battery_state") val batteryState: String,
    @param:Json(name = "battery_mv") val batteryMillivolts: Int,
    @param:Json(name = "time_valid") val timeValid: Boolean,
    @param:Json(name = "boot_id") val bootId: Long = 0L,
    @param:Json(name = "next_check_in_s") val nextCheckInSeconds: Int,
    @param:Json(name = "active_pumps") val activePumps: Int,
    @param:Json(name = "wifi_connected") val wifiConnected: Boolean,
    @param:Json(name = "mqtt_connected") val mqttConnected: Boolean,
    @param:Json(name = "uptime_s") val uptimeSeconds: Long = 0L,
) {
    val batteryPercentApprox: Int
        get() {
            val clamped = batteryMillivolts.coerceIn(3100, 3600)
            return ((clamped - 3100) * 100) / 500
        }
}

data class BeetPairState(
    @param:Json(name = "pair") val pairIndex: Int,
    val state: String,
    @param:Json(name = "moisture_pct") val moisturePercent: Int,
    @param:Json(name = "sensor_mv") val sensorMillivolts: Int,
    val enabled: Boolean,
    @param:Json(name = "sensor_valid") val sensorValid: Boolean,
    val blocked: Boolean,
    @param:Json(name = "block_reason") val blockReason: String,
    @param:Json(name = "remaining_s") val remainingSeconds: Int,
    val source: String,
)

data class BeetCalibration(
    @param:Json(name = "pair") val pairIndex: Int,
    @param:Json(name = "dry_mv") val dryMillivolts: Int,
    @param:Json(name = "wet_mv") val wetMillivolts: Int,
    val source: String,
    @param:Json(name = "calibrated_at_unix_s") val calibratedAtUnixSeconds: Long,
)

data class BeetHistorySummary(
    @param:Json(name = "latest_seq_no") val latestSequenceNumber: Long,
    @param:Json(name = "event_count") val eventCount: Int,
    @param:Json(name = "pair_totals_s") val pairTotalsSeconds: List<Int>,
)

data class BeetSystemHistorySummary(
    @param:Json(name = "latest_seq_no") val latestSequenceNumber: Long,
    @param:Json(name = "event_count") val eventCount: Int,
)

data class BeetWateringEvent(
    @param:Json(name = "seq") val sequenceNumber: Long,
    @param:Json(name = "pair") val pairIndex: Int,
    @param:Json(name = "boot_id") val bootId: Long = 0L,
    @param:Json(name = "src") val triggerSource: Int,
    @param:Json(name = "start") val startedAtUnixSeconds: Long,
    @param:Json(name = "end") val endedAtUnixSeconds: Long,
    @param:Json(name = "mb") val moistureBeforePercent: Int,
    @param:Json(name = "ma") val moistureAfterPercent: Int,
    @param:Json(name = "sb") val sensorBeforeMillivolts: Int,
    @param:Json(name = "sa") val sensorAfterMillivolts: Int,
    @param:Json(name = "req") val requestedDurationSeconds: Int,
    @param:Json(name = "act") val actualDurationSeconds: Int,
    @param:Json(name = "stop") val stopReason: Int,
    @param:Json(name = "block") val blockReason: Int,
    @param:Json(name = "bs") val batteryStartMillivolts: Int,
    @param:Json(name = "be") val batteryEndMillivolts: Int,
    @param:Json(name = "su") val startedUptimeSeconds: Long = 0L,
    @param:Json(name = "eu") val endedUptimeSeconds: Long = 0L,
) {
    val timeValid: Boolean
        get() = startedAtUnixSeconds > 0L && endedAtUnixSeconds > 0L

    val isControllerSleepEvent: Boolean
        get() = pairIndex == 0
}

data class BeetSystemEvent(
    @param:Json(name = "seq") val sequenceNumber: Long,
    @param:Json(name = "event_type") val eventType: String,
    val reason: Int,
    @param:Json(name = "boot_id") val bootId: Long = 0L,
    @param:Json(name = "uptime_s") val uptimeSeconds: Long,
    @param:Json(name = "unix_s") val unixSeconds: Long,
    @param:Json(name = "battery_mv") val batteryMillivolts: Int,
    @param:Json(name = "peer_addr") val peerAddress: String,
    @param:Json(name = "peer_addr_type") val peerAddressType: Int,
    @param:Json(name = "known_peer") val knownPeer: Boolean,
    val detail: Long,
) {
    val timeValid: Boolean
        get() = unixSeconds > 0L
}

enum class BeetEventSyncPhase {
    Idle,
    CatchingUp,
    PausedForCommand,
    Completed,
}

data class BeetEventSyncState(
    val active: Boolean = false,
    val downloaded: Int = 0,
    val total: Int = 0,
    val phase: BeetEventSyncPhase = BeetEventSyncPhase.Idle,
) {
    val progress: Float
        get() = if (total <= 0) 0f else downloaded.toFloat() / total.toFloat()
}

internal data class BeetCommandAckData(
    @param:Json(name = "pair") val pairIndex: Int? = null,
    @param:Json(name = "duration_s") val durationSeconds: Int? = null,
)

internal data class BeetManualStartCommandData(
    @param:Json(name = "pair") val pairIndex: Int,
    @param:Json(name = "duration_s") val durationSeconds: Int? = null,
)

internal data class BeetPairCommandData(
    @param:Json(name = "pair") val pairIndex: Int,
)

internal data class BeetCalibrationCommandData(
    @param:Json(name = "pair") val pairIndex: Int,
    @param:Json(name = "dry_mv") val dryMillivolts: Int,
    @param:Json(name = "wet_mv") val wetMillivolts: Int,
)

internal data class BeetEventRequestData(
    @param:Json(name = "seq_no") val sequenceNumber: Long,
)

internal class BeetEmptyCommandData

internal data class BeetSetTimeCommandData(
    @param:Json(name = "unix_s") val unixSeconds: Long,
)

data class BeetCommandResult(
    val command: String,
    val pairIndex: Int?,
    val status: String,
    val reason: String,
    val acceptedDurationSeconds: Int? = null,
    val calibration: BeetCalibration? = null,
    val historySummary: BeetHistorySummary? = null,
    val event: BeetWateringEvent? = null,
    val systemHistorySummary: BeetSystemHistorySummary? = null,
    val systemEvent: BeetSystemEvent? = null,
)

data class BeetRepositoryState(
    val connection: BeetConnectionState = BeetConnectionState(),
    val discoveredDevices: List<BeetDiscoveredDevice> = emptyList(),
    val controllerInfo: BeetControllerInfo? = null,
    val deviceState: BeetDeviceState? = null,
    val pairStates: List<BeetPairState> = List(8) { index ->
        BeetPairState(
            pairIndex = index + 1,
            state = "IDLE",
            moisturePercent = 0,
            sensorMillivolts = 0,
            enabled = true,
            sensorValid = true,
            blocked = false,
            blockReason = "NONE",
            remainingSeconds = 0,
            source = "NONE",
        )
    },
    val calibrations: Map<Int, BeetCalibration> = emptyMap(),
    val historySummary: BeetHistorySummary? = null,
    val systemHistorySummary: BeetSystemHistorySummary? = null,
    val recentEvents: List<BeetWateringEvent> = emptyList(),
    val systemEvents: List<BeetSystemEvent> = emptyList(),
    val eventsLoading: Boolean = false,
    val eventSync: BeetEventSyncState = BeetEventSyncState(),
    val connectedAtMillis: Long = 0L,
    val connectedAtControllerUptimeSeconds: Long = 0L,
    val lastCommandMessage: String? = null,
    val selectedAddress: String? = null,
)

object BeetEventMappings {
    fun triggerSourceLabel(value: Int): String = when (value) {
        1 -> "Automatic"
        2 -> "Manual"
        3 -> "Moisture test"
        else -> "None"
    }

    fun stopReasonLabel(value: Int): String = when (value) {
        0 -> "Completed"
        1 -> "Manual stop"
        2 -> "Low battery abort"
        3 -> "Sanity failure"
        4 -> "Sensor invalid abort"
        5 -> "System abort"
        6 -> "Idle low-power sleep"
        7 -> "Deep low-battery sleep"
        else -> "Unknown"
    }

    fun blockReasonLabel(value: Int): String = when (value) {
        0 -> "None"
        1 -> "Moisture response test failed"
        2 -> "Sensor invalid"
        3 -> "Low battery abort"
        else -> "Unknown"
    }

    fun systemEventLabel(value: String): String = when (value) {
        "STARTUP" -> "Startup"
        "SLEEP" -> "Sleep"
        "BLE_CONNECT" -> "Bluetooth connected"
        "BLE_DISCONNECT" -> "Bluetooth disconnected"
        "BLE_BOND_SUCCESS" -> "Bluetooth bonded"
        "BLE_BOND_FAILED" -> "Bluetooth bond failed"
        "BLE_BONDS_CLEARED" -> "Bluetooth bonds cleared"
        "MQTT_CONNECT" -> "MQTT connected"
        "MQTT_DISCONNECT" -> "MQTT disconnected"
        "MQTT_PUBLISH_FAILED" -> "MQTT publish failed"
        "OTA_STARTED" -> "OTA started"
        "OTA_FAILED" -> "OTA failed"
        "OTA_READY" -> "OTA ready"
        else -> value
    }
}
