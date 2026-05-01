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
