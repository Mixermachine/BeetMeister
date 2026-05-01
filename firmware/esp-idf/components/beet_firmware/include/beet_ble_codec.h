#ifndef BEET_BLE_CODEC_H
#define BEET_BLE_CODEC_H

#include <stdbool.h>
#include <stddef.h>

#include "beet_iface.h"

int beet_ble_format_controller_info_json(
    char *buf,
    size_t len,
    const char *device_id,
    const char *firmware_version,
    unsigned protocol_version,
    unsigned pair_count);
int beet_ble_format_device_frame_json(
    char *buf,
    size_t len,
    const beet_iface_device_state_t *state);
int beet_ble_format_pair_frame_json(
    char *buf,
    size_t len,
    const beet_iface_pair_state_t *state);
int beet_ble_format_system_event_frame_json(
    char *buf,
    size_t len,
    const beet_system_event_record_t *event,
    uint32_t unix_s);
int beet_ble_format_command_result_json(
    char *buf,
    size_t len,
    const beet_iface_command_response_t *response);
bool beet_ble_parse_command_json(
    const char *json,
    beet_iface_command_request_t *request);

#endif
