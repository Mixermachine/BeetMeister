#include "beet_ble_guard.h"

#include <string.h>

void beet_ble_rate_guard_init(
    beet_ble_rate_guard_t *guard,
    int64_t window_us,
    uint8_t max_per_window)
{
    if (guard == NULL) {
        return;
    }

    memset(guard, 0, sizeof(*guard));
    guard->window_us = window_us;
    guard->max_per_window = max_per_window;
}

bool beet_ble_rate_guard_allow(beet_ble_rate_guard_t *guard, int64_t now_us)
{
    if (guard == NULL || guard->window_us <= 0 || guard->max_per_window == 0U) {
        return false;
    }

    if (guard->window_started_us == 0 ||
        (now_us - guard->window_started_us) >= guard->window_us) {
        guard->window_started_us = now_us;
        guard->commands_in_window = 0U;
    }

    if (guard->commands_in_window >= guard->max_per_window) {
        return false;
    }

    guard->commands_in_window++;
    return true;
}

beet_ble_command_lane_t beet_ble_classify_command_lane(beet_iface_command_t command)
{
    switch (command) {
    case BEET_IFACE_COMMAND_GET_HISTORY_SUMMARY:
    case BEET_IFACE_COMMAND_GET_EVENT:
    case BEET_IFACE_COMMAND_GET_SYSTEM_HISTORY_SUMMARY:
    case BEET_IFACE_COMMAND_GET_SYSTEM_EVENT:
    case BEET_IFACE_COMMAND_GET_WATERING_HISTORY_SUMMARY:
    case BEET_IFACE_COMMAND_GET_WATERING_EVENT:
        return BEET_BLE_COMMAND_LANE_SYNC_READ;

    case BEET_IFACE_COMMAND_MANUAL_START:
    case BEET_IFACE_COMMAND_MANUAL_STOP:
    case BEET_IFACE_COMMAND_RELAY_TEST_START:
    case BEET_IFACE_COMMAND_RELAY_TEST_STOP:
    case BEET_IFACE_COMMAND_RESET_BLOCK:
    case BEET_IFACE_COMMAND_STORE_CALIBRATION:
    case BEET_IFACE_COMMAND_START_OTA:
    case BEET_IFACE_COMMAND_CLEAR_BLE_BONDS:
    case BEET_IFACE_COMMAND_GET_CALIBRATION:
    case BEET_IFACE_COMMAND_DISABLE_PAIR:
    case BEET_IFACE_COMMAND_ENABLE_PAIR:
    case BEET_IFACE_COMMAND_MOISTURE_TEST_START:
    case BEET_IFACE_COMMAND_SET_TIME:
    case BEET_IFACE_COMMAND_GET_VALVE_CONFIG:
    case BEET_IFACE_COMMAND_STORE_VALVE_CONFIG:
    case BEET_IFACE_COMMAND_OPEN_VALVE:
    case BEET_IFACE_COMMAND_CLOSE_VALVE:
    case BEET_IFACE_COMMAND_PREVIEW_VALVE_POSITION:
    default:
        return BEET_BLE_COMMAND_LANE_REAL;
    }
}

const char *beet_ble_command_lane_name(beet_ble_command_lane_t lane)
{
    switch (lane) {
    case BEET_BLE_COMMAND_LANE_SYNC_READ:
        return "sync_read";

    case BEET_BLE_COMMAND_LANE_REAL:
    default:
        return "real";
    }
}

void beet_ble_build_rejection(
    const beet_iface_command_request_t *request,
    beet_iface_reason_t reason,
    beet_iface_command_response_t *response)
{
    if (request == NULL || response == NULL) {
        return;
    }

    memset(response, 0, sizeof(*response));
    response->command = request->command;
    response->pair_index = request->pair_index;
    response->status = BEET_IFACE_STATUS_REJECTED;
    response->reason = reason;
}
