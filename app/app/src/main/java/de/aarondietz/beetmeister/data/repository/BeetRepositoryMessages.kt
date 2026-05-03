package de.aarondietz.beetmeister.data.repository

import de.aarondietz.beetmeister.model.command.BeetCommandResult

internal fun commandMessageForResult(result: BeetCommandResult): String {
    val base = reasonLabel(result.reason)
    return when {
        result.command == "manual_start" && result.acceptedDurationSeconds != null ->
            "$base (${result.acceptedDurationSeconds}s)"
        result.command == "store_calibration" && result.pairIndex != null ->
            "Calibration saved for pair ${result.pairIndex}."
        result.command == "disable_pair" && result.pairIndex != null ->
            "Pair ${result.pairIndex} disabled."
        result.command == "enable_pair" && result.pairIndex != null ->
            "Pair ${result.pairIndex} enabled."
        result.command == "clear_ble_bonds" ->
            when (result.reason) {
                "bonds_cleared" -> "Bluetooth bonds cleared."
                "no_bonds" -> "No Bluetooth bonds to clear."
                else -> base
            }
        result.command == "reset_block" && result.pairIndex != null ->
            "Pair ${result.pairIndex}: $base."
        result.pairIndex != null -> "Pair ${result.pairIndex}: $base."
        else -> base
    }
}

private fun reasonLabel(reason: String): String = when (reason) {
    "slot_allocated" -> "Watering started"
    "queued_waiting_for_slot" -> "Queued until a pump slot is free"
    "already_stopped" -> "Already stopped"
    "stopped" -> "Stopped"
    "not_blocked" -> "No error to clear"
    "block_reset" -> "Error cleared"
    "calibration_saved" -> "Calibration saved"
    "pair_blocked" -> "Pair is blocked"
    "pair_faulted" -> "Pair is faulted"
    "low_battery" -> "Battery too low"
    "slot_unavailable" -> "No pump slot available"
    "invalid_calibration" -> "Invalid calibration values"
    "invalid_duration" -> "Invalid duration"
    "ota_in_progress" -> "OTA is in progress"
    "bonds_cleared" -> "Bluetooth bonds cleared"
    "no_bonds" -> "No Bluetooth bonds to clear"
    "outputs_disabled" -> "Pump outputs are disabled"
    "sensor_invalid" -> "Sensor reading is invalid"
    "already_active" -> "Already active"
    "invalid_pair" -> "Invalid pair"
    "event_not_found" -> "Event not found"
    "pair_disabled" -> "Pair is disabled"
    "pair_enabled" -> "Pair enabled"
    "moisture_test_started" -> "Moisture response test started"
    "time_updated" -> "Controller time updated"
    "invalid_time" -> "Invalid controller time"
    "time_not_set" -> "Controller time is not set yet"
    "busy" -> "Controller is busy"
    "rate_limited" -> "Too many commands; try again"
    else -> reason.replace('_', ' ').replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
}
