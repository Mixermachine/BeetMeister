#include "beet_iface.h"

const char *beet_iface_command_name(beet_iface_command_t command)
{
    switch (command) {
    case BEET_IFACE_COMMAND_MANUAL_START:
        return "manual_start";
    case BEET_IFACE_COMMAND_MANUAL_STOP:
        return "manual_stop";
    case BEET_IFACE_COMMAND_RESET_BLOCK:
        return "reset_block";
    case BEET_IFACE_COMMAND_STORE_CALIBRATION:
        return "store_calibration";
    case BEET_IFACE_COMMAND_START_OTA:
        return "start_ota";
    case BEET_IFACE_COMMAND_CLEAR_BLE_BONDS:
        return "clear_ble_bonds";
    case BEET_IFACE_COMMAND_GET_CALIBRATION:
        return "get_calibration";
    case BEET_IFACE_COMMAND_GET_HISTORY_SUMMARY:
        return "get_history_summary";
    case BEET_IFACE_COMMAND_GET_EVENT:
        return "get_event";
    case BEET_IFACE_COMMAND_DISABLE_PAIR:
        return "disable_pair";
    case BEET_IFACE_COMMAND_ENABLE_PAIR:
        return "enable_pair";
    default:
        return "unknown";
    }
}

const char *beet_iface_status_name(beet_iface_status_t status)
{
    switch (status) {
    case BEET_IFACE_STATUS_ACCEPTED:
        return "accepted";
    case BEET_IFACE_STATUS_REJECTED:
        return "rejected";
    default:
        return "unknown";
    }
}

const char *beet_iface_reason_name(beet_iface_reason_t reason)
{
    switch (reason) {
    case BEET_IFACE_REASON_NONE:
        return "none";
    case BEET_IFACE_REASON_SLOT_ALLOCATED:
        return "slot_allocated";
    case BEET_IFACE_REASON_QUEUED_WAITING_FOR_SLOT:
        return "queued_waiting_for_slot";
    case BEET_IFACE_REASON_ALREADY_STOPPED:
        return "already_stopped";
    case BEET_IFACE_REASON_STOPPED:
        return "stopped";
    case BEET_IFACE_REASON_NOT_BLOCKED:
        return "not_blocked";
    case BEET_IFACE_REASON_BLOCK_RESET:
        return "block_reset";
    case BEET_IFACE_REASON_CALIBRATION_SAVED:
        return "calibration_saved";
    case BEET_IFACE_REASON_BONDS_CLEARED:
        return "bonds_cleared";
    case BEET_IFACE_REASON_NO_BONDS:
        return "no_bonds";
    case BEET_IFACE_REASON_PAIR_BLOCKED:
        return "pair_blocked";
    case BEET_IFACE_REASON_PAIR_FAULTED:
        return "pair_faulted";
    case BEET_IFACE_REASON_LOW_BATTERY:
        return "low_battery";
    case BEET_IFACE_REASON_SLOT_UNAVAILABLE:
        return "slot_unavailable";
    case BEET_IFACE_REASON_INVALID_CALIBRATION:
        return "invalid_calibration";
    case BEET_IFACE_REASON_INVALID_DURATION:
        return "invalid_duration";
    case BEET_IFACE_REASON_UNSUPPORTED_COMMAND:
        return "unsupported_command";
    case BEET_IFACE_REASON_OTA_IN_PROGRESS:
        return "ota_in_progress";
    case BEET_IFACE_REASON_OUTPUTS_DISABLED:
        return "outputs_disabled";
    case BEET_IFACE_REASON_SENSOR_INVALID:
        return "sensor_invalid";
    case BEET_IFACE_REASON_ALREADY_ACTIVE:
        return "already_active";
    case BEET_IFACE_REASON_INVALID_PAIR:
        return "invalid_pair";
    case BEET_IFACE_REASON_EVENT_NOT_FOUND:
        return "event_not_found";
    case BEET_IFACE_REASON_PAIR_DISABLED:
        return "pair_disabled";
    case BEET_IFACE_REASON_PAIR_ENABLED:
        return "pair_enabled";
    default:
        return "unknown";
    }
}
