#include "beet_iface.h"

const char *beet_iface_command_name(beet_iface_command_t command)
{
    switch (command) {
    case BEET_IFACE_COMMAND_MANUAL_START:
        return "manual_start";
    case BEET_IFACE_COMMAND_MANUAL_STOP:
        return "manual_stop";
    case BEET_IFACE_COMMAND_RELAY_TEST_START:
        return "relay_test_start";
    case BEET_IFACE_COMMAND_RELAY_TEST_STOP:
        return "relay_test_stop";
    case BEET_IFACE_COMMAND_RESET_BLOCK:
        return "reset_block";
    case BEET_IFACE_COMMAND_STORE_CALIBRATION:
        return "store_calibration";
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
    case BEET_IFACE_COMMAND_MOISTURE_TEST_START:
        return "moisture_test_start";
    case BEET_IFACE_COMMAND_GET_SYSTEM_HISTORY_SUMMARY:
        return "get_system_history_summary";
    case BEET_IFACE_COMMAND_GET_SYSTEM_EVENT:
        return "get_system_event";
    case BEET_IFACE_COMMAND_GET_WATERING_HISTORY_SUMMARY:
        return "get_watering_history_summary";
    case BEET_IFACE_COMMAND_GET_WATERING_EVENT:
        return "get_watering_event";
    case BEET_IFACE_COMMAND_SET_TIME:
        return "set_time";
    case BEET_IFACE_COMMAND_GET_VALVE_CONFIG:
        return "get_valve_config";
    case BEET_IFACE_COMMAND_STORE_VALVE_CONFIG:
        return "store_valve_config";
    case BEET_IFACE_COMMAND_OPEN_VALVE:
        return "open_valve";
    case BEET_IFACE_COMMAND_CLOSE_VALVE:
        return "close_valve";
    case BEET_IFACE_COMMAND_PREVIEW_VALVE_POSITION:
        return "preview_valve_position";
    case BEET_IFACE_COMMAND_GET_WATERING_INTERVAL:
        return "get_watering_interval";
    case BEET_IFACE_COMMAND_STORE_WATERING_INTERVAL:
        return "store_watering_interval";
    case BEET_IFACE_COMMAND_REBOOT_CONTROLLER:
        return "reboot_controller";
    case BEET_IFACE_COMMAND_FACTORY_RESET:
        return "factory_reset";
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
    case BEET_IFACE_REASON_RELAY_TEST_STARTED:
        return "relay_test_started";
    case BEET_IFACE_REASON_RELAY_TEST_STOPPED:
        return "relay_test_stopped";
    case BEET_IFACE_REASON_MOISTURE_TEST_STARTED:
        return "moisture_test_started";
    case BEET_IFACE_REASON_TIME_UPDATED:
        return "time_updated";
    case BEET_IFACE_REASON_INVALID_TIME:
        return "invalid_time";
    case BEET_IFACE_REASON_BUSY:
        return "busy";
    case BEET_IFACE_REASON_RATE_LIMITED:
        return "rate_limited";
    case BEET_IFACE_REASON_TIME_NOT_SET:
        return "time_not_set";
    case BEET_IFACE_REASON_VALVE_OPENED:
        return "valve_opened";
    case BEET_IFACE_REASON_VALVE_CLOSED:
        return "valve_closed";
    case BEET_IFACE_REASON_VALVE_DISABLED:
        return "valve_disabled";
    case BEET_IFACE_REASON_VALVE_BUSY:
        return "valve_busy";
    case BEET_IFACE_REASON_INVALID_VALVE_CONFIG:
        return "invalid_valve_config";
    case BEET_IFACE_REASON_WATERING_ACTIVE:
        return "watering_active";
    case BEET_IFACE_REASON_REBOOTING:
        return "rebooting";
    case BEET_IFACE_REASON_FACTORY_RESET_STARTED:
        return "factory_reset_started";
    default:
        return "unknown";
    }
}
