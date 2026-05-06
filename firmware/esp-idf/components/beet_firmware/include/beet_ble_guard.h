#ifndef BEET_BLE_GUARD_H
#define BEET_BLE_GUARD_H

#include <stdbool.h>
#include <stdint.h>

#include "beet_iface.h"

typedef struct {
    int64_t window_started_us;
    int64_t window_us;
    uint8_t commands_in_window;
    uint8_t max_per_window;
} beet_ble_rate_guard_t;

typedef enum {
    BEET_BLE_COMMAND_LANE_REAL = 0,
    BEET_BLE_COMMAND_LANE_SYNC_READ = 1,
} beet_ble_command_lane_t;

void beet_ble_rate_guard_init(
    beet_ble_rate_guard_t *guard,
    int64_t window_us,
    uint8_t max_per_window);

bool beet_ble_rate_guard_allow(beet_ble_rate_guard_t *guard, int64_t now_us);

beet_ble_command_lane_t beet_ble_classify_command_lane(beet_iface_command_t command);

const char *beet_ble_command_lane_name(beet_ble_command_lane_t lane);

void beet_ble_build_rejection(
    const beet_iface_command_request_t *request,
    beet_iface_reason_t reason,
    beet_iface_command_response_t *response);

#endif
