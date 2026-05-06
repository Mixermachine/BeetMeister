#ifndef BEET_BLE_CODEC_H
#define BEET_BLE_CODEC_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

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
size_t beet_ble_base64_encoded_len(size_t raw_len);
bool beet_ble_base64_encode(
    const uint8_t *raw,
    size_t raw_len,
    char *base64,
    size_t base64_len,
    size_t *written);
size_t beet_ble_command_chunk_fragment_capacity(
    size_t att_payload_len,
    uint32_t chunk_id,
    uint16_t chunk_index,
    uint16_t chunk_count);
int beet_ble_format_command_chunk_frame_json(
    char *buf,
    size_t len,
    uint32_t chunk_id,
    uint16_t chunk_index,
    uint16_t chunk_count,
    const char *base64_fragment,
    size_t base64_fragment_len);
bool beet_ble_parse_command_json(
    const char *json,
    beet_iface_command_request_t *request);

#endif
