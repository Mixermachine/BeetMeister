package de.aarondietz.beetmeister.data.repository

import de.aarondietz.beetmeister.R
import de.aarondietz.beetmeister.model.command.BeetCommandResult
import de.aarondietz.beetmeister.strings.BeetStringResolver

internal fun commandMessageForResult(result: BeetCommandResult, strings: BeetStringResolver): String {
    val base = reasonLabel(result.reason, strings)
    return when {
        result.command == "manual_start" && result.acceptedDurationSeconds != null ->
            strings.get(R.string.command_message_duration, base, result.acceptedDurationSeconds)
        result.command == "store_calibration" && result.pairIndex != null ->
            strings.get(R.string.command_message_calibration_saved_for_pair, result.pairIndex)
        result.command == "disable_pair" && result.pairIndex != null ->
            strings.get(R.string.command_message_pair_disabled, result.pairIndex)
        result.command == "enable_pair" && result.pairIndex != null ->
            strings.get(R.string.command_message_pair_enabled, result.pairIndex)
        result.command == "clear_ble_bonds" ->
            when (result.reason) {
                "bonds_cleared" -> strings.get(R.string.command_message_bonds_cleared)
                "no_bonds" -> strings.get(R.string.command_message_no_bonds)
                else -> base
            }
        result.command == "open_valve" || result.command == "close_valve" ->
            base
        result.command == "store_watering_interval" && result.status == "accepted" ->
            strings.get(R.string.command_message_watering_interval_saved)
        result.pairIndex != null ->
            strings.get(R.string.command_message_pair_prefix, result.pairIndex, base)
        else -> base
    }
}

private fun reasonLabel(reason: String, strings: BeetStringResolver): String {
    val resId = when (reason) {
        "slot_allocated" -> R.string.command_reason_slot_allocated
        "queued_waiting_for_slot" -> R.string.command_reason_queued_waiting_for_slot
        "already_stopped" -> R.string.command_reason_already_stopped
        "stopped" -> R.string.command_reason_stopped
        "not_blocked" -> R.string.command_reason_not_blocked
        "block_reset" -> R.string.command_reason_block_reset
        "calibration_saved" -> R.string.command_reason_calibration_saved
        "pair_blocked" -> R.string.command_reason_pair_blocked
        "pair_faulted" -> R.string.command_reason_pair_faulted
        "low_battery" -> R.string.command_reason_low_battery
        "slot_unavailable" -> R.string.command_reason_slot_unavailable
        "invalid_calibration" -> R.string.command_reason_invalid_calibration
        "invalid_duration" -> R.string.command_reason_invalid_duration
        "ota_in_progress" -> R.string.command_reason_ota_in_progress
        "bonds_cleared" -> R.string.command_reason_bonds_cleared
        "no_bonds" -> R.string.command_reason_no_bonds
        "outputs_disabled" -> R.string.command_reason_outputs_disabled
        "sensor_invalid" -> R.string.command_reason_sensor_invalid
        "already_active" -> R.string.command_reason_already_active
        "invalid_pair" -> R.string.command_reason_invalid_pair
        "event_not_found" -> R.string.command_reason_event_not_found
        "pair_disabled" -> R.string.command_reason_pair_disabled
        "pair_enabled" -> R.string.command_reason_pair_enabled
        "moisture_test_started" -> R.string.command_reason_moisture_test_started
        "time_updated" -> R.string.command_reason_time_updated
        "invalid_time" -> R.string.command_reason_invalid_time
        "time_not_set" -> R.string.command_reason_time_not_set
        "busy" -> R.string.command_reason_busy
        "rate_limited" -> R.string.command_reason_rate_limited
        "valve_opened" -> R.string.command_reason_valve_opened
        "valve_closed" -> R.string.command_reason_valve_closed
        "valve_disabled" -> R.string.command_reason_valve_disabled
        "valve_busy" -> R.string.command_reason_valve_busy
        "invalid_valve_config" -> R.string.command_reason_invalid_valve_config
        "watering_active" -> R.string.command_reason_watering_active
        else -> return strings.get(R.string.common_unknown_with_code, reason)
    }
    return strings.get(resId)
}
