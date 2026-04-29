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
}

data class BeetDeviceState(
    @param:Json(name = "battery_state") val batteryState: String,
    @param:Json(name = "battery_mv") val batteryMillivolts: Int,
    @param:Json(name = "time_valid") val timeValid: Boolean,
    @param:Json(name = "next_check_in_s") val nextCheckInSeconds: Int,
    @param:Json(name = "active_pumps") val activePumps: Int,
    @param:Json(name = "wifi_connected") val wifiConnected: Boolean,
    @param:Json(name = "mqtt_connected") val mqttConnected: Boolean,
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

data class BeetWateringEvent(
    @param:Json(name = "seq") val sequenceNumber: Long,
    @param:Json(name = "pair") val pairIndex: Int,
    @param:Json(name = "src") val triggerSource: Int,
    @param:Json(name = "start") val startedAtUnixSeconds: Long,
    @param:Json(name = "end") val endedAtUnixSeconds: Long,
    @param:Json(name = "tv") val timeValidRaw: Int,
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
) {
    val timeValid: Boolean
        get() = timeValidRaw != 0

    val isControllerSleepEvent: Boolean
        get() = pairIndex == 0
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

data class BeetCommandResult(
    val command: String,
    val pairIndex: Int?,
    val status: String,
    val reason: String,
    val acceptedDurationSeconds: Int? = null,
    val calibration: BeetCalibration? = null,
    val historySummary: BeetHistorySummary? = null,
    val event: BeetWateringEvent? = null,
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
    val recentEvents: List<BeetWateringEvent> = emptyList(),
    val eventsLoading: Boolean = false,
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
}
