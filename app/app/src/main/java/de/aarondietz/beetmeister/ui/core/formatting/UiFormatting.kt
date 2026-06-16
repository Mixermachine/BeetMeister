package de.aarondietz.beetmeister.ui.core.formatting

import android.bluetooth.BluetoothDevice
import androidx.annotation.StringRes
import de.aarondietz.beetmeister.R
import de.aarondietz.beetmeister.model.connection.BeetConnectionPhase
import de.aarondietz.beetmeister.model.event.BeetSystemEvent
import de.aarondietz.beetmeister.model.event.BeetWateringEvent
import de.aarondietz.beetmeister.strings.BeetStringResolver
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

internal fun bondStateLabel(bondState: Int, strings: BeetStringResolver): String = when (bondState) {
    BluetoothDevice.BOND_BONDED -> strings.get(R.string.bond_state_bonded)
    BluetoothDevice.BOND_BONDING -> strings.get(R.string.bond_state_bonding)
    else -> strings.get(R.string.bond_state_not_bonded)
}

internal fun yesNo(value: Boolean, strings: BeetStringResolver): String =
    strings.get(if (value) R.string.common_yes else R.string.common_no)

internal fun formatDuration(seconds: Int, strings: BeetStringResolver): String {
    val safeSeconds = seconds.coerceAtLeast(0)
    val hours = safeSeconds / 3600
    val minutes = (safeSeconds % 3600) / 60
    val remainingSeconds = safeSeconds % 60
    return when {
        hours > 0 -> strings.get(R.string.format_duration_hours_minutes, hours, minutes)
        minutes > 0 -> strings.get(R.string.format_duration_minutes_seconds, minutes, remainingSeconds)
        else -> strings.get(R.string.format_duration_seconds_only, remainingSeconds)
    }
}

internal fun formatUnixSeconds(unixSeconds: Long, strings: BeetStringResolver): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
        .withLocale(strings.locale)
        .format(
            Instant.ofEpochSecond(unixSeconds)
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime(),
        )

internal fun formatEventTime(event: BeetWateringEvent, strings: BeetStringResolver): String {
    if (!event.timeValid) {
        return strings.get(R.string.format_event_time_unknown)
    }
    return strings.get(
        R.string.common_time_range,
        formatUnixSeconds(event.startedAtUnixSeconds, strings),
        formatUnixSeconds(event.endedAtUnixSeconds, strings),
    )
}

internal fun formatEventSubject(event: BeetWateringEvent, strings: BeetStringResolver): String =
    if (event.isControllerSleepEvent) {
        strings.get(R.string.format_event_subject_controller_sleep)
    } else {
        strings.get(R.string.common_pair_number, event.pairIndex)
    }

internal fun connectionPhaseLabel(phase: BeetConnectionPhase, strings: BeetStringResolver): String = strings.get(
    when (phase) {
        BeetConnectionPhase.PermissionsRequired -> R.string.connection_phase_permissions_required
        BeetConnectionPhase.BluetoothDisabled -> R.string.connection_phase_bluetooth_disabled
        BeetConnectionPhase.Idle -> R.string.connection_phase_idle
        BeetConnectionPhase.Scanning -> R.string.connection_phase_scanning
        BeetConnectionPhase.Bonding -> R.string.connection_phase_bonding
        BeetConnectionPhase.Connecting -> R.string.connection_phase_connecting
        BeetConnectionPhase.DiscoveringServices -> R.string.connection_phase_discovering_services
        BeetConnectionPhase.Syncing -> R.string.connection_phase_syncing
        BeetConnectionPhase.MaintenanceRequired -> R.string.connection_phase_maintenance_required
        BeetConnectionPhase.Connected -> R.string.connection_phase_connected
        BeetConnectionPhase.Disconnected -> R.string.connection_phase_disconnected
        BeetConnectionPhase.Error -> R.string.connection_phase_error
    },
)

internal fun maintenanceImageKindLabel(kind: String, strings: BeetStringResolver): String {
    val resId = when (kind) {
        "bundled" -> R.string.image_kind_bundled
        "custom" -> R.string.image_kind_custom
        else -> return strings.get(R.string.common_unknown_with_code, kind)
    }
    return strings.get(resId)
}

internal fun batteryStateLabel(state: String, strings: BeetStringResolver): String {
    val resId = when (state) {
        "ACTIVE" -> R.string.battery_state_active
        "IDLE_LOW_POWER" -> R.string.battery_state_idle_low_power
        "DEEP_LOW_BATTERY" -> R.string.battery_state_deep_low_battery
        "OTA_IN_PROGRESS" -> R.string.battery_state_ota_in_progress
        else -> return strings.get(R.string.common_unknown_with_code, state)
    }
    return strings.get(resId)
}

internal fun valveStateLabel(state: String, strings: BeetStringResolver): String {
    val resId = when (state) {
        "CLOSED" -> R.string.valve_state_closed
        "OPENING" -> R.string.valve_state_opening
        "OPEN" -> R.string.valve_state_open
        "CLOSING" -> R.string.valve_state_closing
        "FAULT" -> R.string.valve_state_fault
        else -> return strings.get(R.string.common_unknown_with_code, state)
    }
    return strings.get(resId)
}

internal fun pairStateLabel(state: String, strings: BeetStringResolver): String {
    val resId = when (state) {
        "IDLE" -> R.string.pair_state_idle
        "WAITING_FOR_SLOT" -> R.string.pair_state_waiting_for_slot
        "SANITY_CHECK" -> R.string.pair_state_sanity_check
        "WATERING" -> R.string.pair_state_watering
        "BLOCKED" -> R.string.pair_state_blocked
        "FAULT" -> R.string.pair_state_fault
        "DISABLED" -> R.string.pair_state_disabled
        "MOISTURE_TEST" -> R.string.pair_state_moisture_test
        else -> return strings.get(R.string.common_unknown_with_code, state)
    }
    return strings.get(resId)
}

internal fun runSourceLabel(source: String, strings: BeetStringResolver): String {
    val resId = when (source) {
        "NONE" -> R.string.run_source_none
        "AUTOMATIC" -> R.string.run_source_automatic
        "MANUAL" -> R.string.run_source_manual
        "TEST" -> R.string.run_source_test
        else -> return strings.get(R.string.common_unknown_with_code, source)
    }
    return strings.get(resId)
}

internal fun calibrationSourceLabel(source: String, strings: BeetStringResolver): String {
    val resId = when (source) {
        "DEFAULT" -> R.string.calibration_source_default
        "USER" -> R.string.calibration_source_user
        else -> return strings.get(R.string.common_unknown_with_code, source)
    }
    return strings.get(resId)
}

internal fun blockReasonCodeLabel(reason: String, strings: BeetStringResolver): String {
    val resId = when (reason) {
        "NONE" -> R.string.block_reason_code_none
        "MOISTURE_RESPONSE_TEST_FAILED" -> R.string.block_reason_code_moisture_response_test_failed
        "SENSOR_READING_INVALID" -> R.string.block_reason_code_sensor_reading_invalid
        "LOW_BATTERY_ABORT" -> R.string.block_reason_code_low_battery_abort
        else -> return strings.get(R.string.common_unknown_with_code, reason)
    }
    return strings.get(resId)
}

internal fun triggerSourceLabel(value: Int, strings: BeetStringResolver): String = strings.get(
    when (value) {
        1 -> R.string.trigger_source_automatic
        2 -> R.string.trigger_source_manual
        3 -> R.string.trigger_source_test
        else -> R.string.trigger_source_none
    },
)

internal fun stopReasonLabel(value: Int, strings: BeetStringResolver): String {
    val resId = when (value) {
        0 -> R.string.stop_reason_completed
        1 -> R.string.stop_reason_manual_stop
        2 -> R.string.stop_reason_low_battery_abort
        3 -> R.string.stop_reason_sensor_sanity_failure
        4 -> R.string.stop_reason_sensor_invalid_abort
        5 -> R.string.stop_reason_system_abort
        6 -> R.string.stop_reason_idle_low_power_sleep
        7 -> R.string.stop_reason_deep_low_battery_sleep
        else -> return strings.get(R.string.common_unknown)
    }
    return strings.get(resId)
}

internal fun eventBlockReasonLabel(value: Int, strings: BeetStringResolver): String {
    val resId = when (value) {
        0 -> R.string.block_reason_code_none
        1 -> R.string.block_reason_code_moisture_response_test_failed
        2 -> R.string.block_reason_code_sensor_reading_invalid
        3 -> R.string.block_reason_code_low_battery_abort
        else -> return strings.get(R.string.common_unknown)
    }
    return strings.get(resId)
}

internal fun systemEventLabel(value: String, strings: BeetStringResolver): String {
    val resId = when (value) {
        "STARTUP" -> R.string.system_event_startup
        "SLEEP" -> R.string.system_event_sleep
        "BLE_CONNECT" -> R.string.system_event_ble_connect
        "BLE_DISCONNECT" -> R.string.system_event_ble_disconnect
        "BLE_BOND_SUCCESS" -> R.string.system_event_ble_bond_success
        "BLE_BOND_FAILED" -> R.string.system_event_ble_bond_failed
        "BLE_BONDS_CLEARED" -> R.string.system_event_ble_bonds_cleared
        "VALVE_OPENED" -> R.string.system_event_valve_opened
        "VALVE_CLOSED" -> R.string.system_event_valve_closed
        "MQTT_CONNECT" -> R.string.system_event_mqtt_connect
        "MQTT_DISCONNECT" -> R.string.system_event_mqtt_disconnect
        "MQTT_PUBLISH_FAILED" -> R.string.system_event_mqtt_publish_failed
        "OTA_STARTED" -> R.string.system_event_ota_started
        "OTA_FAILED" -> R.string.system_event_ota_failed
        "OTA_READY" -> R.string.system_event_ota_ready
        else -> return strings.get(R.string.common_unknown_with_code, value)
    }
    return strings.get(resId)
}

internal fun formatMillivolts(value: Int, strings: BeetStringResolver): String =
    strings.get(R.string.common_millivolts, value)

internal fun formatPercent(value: Int, strings: BeetStringResolver): String =
    strings.get(R.string.common_percent, value)

internal fun formatRssi(value: Int, strings: BeetStringResolver): String =
    strings.get(R.string.common_rssi, value)

internal fun formatPercentAndMillivolts(percent: Int, millivolts: Int, strings: BeetStringResolver): String =
    strings.get(R.string.common_percent_and_millivolts, percent, millivolts)

internal fun formatProgress(downloaded: Int, total: Int, strings: BeetStringResolver): String =
    strings.get(R.string.common_progress_fraction, downloaded, total)
