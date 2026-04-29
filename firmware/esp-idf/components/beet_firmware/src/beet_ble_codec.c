#include "beet_ble_codec.h"

#include <ctype.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "beet_types.h"

static const char *beet_ble_run_source_name(beet_run_source_t source)
{
    switch (source) {
    case BEET_RUN_SOURCE_AUTOMATIC:
        return "AUTOMATIC";
    case BEET_RUN_SOURCE_MANUAL:
        return "MANUAL";
    case BEET_RUN_SOURCE_TEST:
        return "TEST";
    case BEET_RUN_SOURCE_NONE:
    default:
        return "NONE";
    }
}

static const char *beet_ble_calibration_source_name(beet_calibration_source_t source)
{
    switch (source) {
    case BEET_CALIBRATION_SOURCE_USER:
        return "USER";
    case BEET_CALIBRATION_SOURCE_DEFAULT:
    default:
        return "DEFAULT";
    }
}

int beet_ble_format_controller_info_json(
    char *buf,
    size_t len,
    const char *device_id,
    const char *firmware_version,
    unsigned protocol_version,
    unsigned pair_count)
{
    return snprintf(
        buf,
        len,
        "{\"type\":\"controller_info\",\"data\":{\"device_id\":\"%s\",\"protocol_version\":%u,\"firmware_version\":\"%s\",\"pair_count\":%u}}",
        device_id,
        protocol_version,
        firmware_version,
        pair_count);
}

int beet_ble_format_device_frame_json(
    char *buf,
    size_t len,
    const beet_iface_device_state_t *state)
{
    return snprintf(
        buf,
        len,
        "{\"type\":\"device\",\"data\":{\"battery_state\":\"%s\",\"battery_mv\":%u,\"time_valid\":%s,"
        "\"next_check_in_s\":%lu,\"active_pumps\":%u,\"wifi_connected\":%s,\"mqtt_connected\":%s}}",
        beet_battery_state_name(state->battery_state),
        state->battery_mv,
        state->time_valid ? "true" : "false",
        (unsigned long)state->next_check_in_s,
        state->active_pumps,
        state->wifi_connected ? "true" : "false",
        state->mqtt_connected ? "true" : "false");
}

int beet_ble_format_pair_frame_json(
    char *buf,
    size_t len,
    const beet_iface_pair_state_t *state)
{
    return snprintf(
        buf,
        len,
        "{\"type\":\"pair\",\"data\":{\"pair\":%u,\"state\":\"%s\",\"moisture_pct\":%u,\"sensor_mv\":%u,"
        "\"blocked\":%s,\"block_reason\":\"%s\",\"remaining_s\":%u,\"source\":\"%s\","
        "\"enabled\":%s,\"sensor_valid\":%s}}",
        state->pair_index,
        beet_pair_state_name(state->pair_state),
        state->moisture_pct,
        state->sensor_mv,
        state->blocked ? "true" : "false",
        beet_block_reason_name(state->block_reason),
        state->remaining_s,
        beet_ble_run_source_name(state->source),
        state->enabled ? "true" : "false",
        state->sensor_valid ? "true" : "false");
}

int beet_ble_format_command_result_json(
    char *buf,
    size_t len,
    const beet_iface_command_response_t *response)
{
    if (response->command == BEET_IFACE_COMMAND_GET_CALIBRATION &&
        response->status == BEET_IFACE_STATUS_ACCEPTED &&
        response->has_calibration) {
        return snprintf(
            buf,
            len,
            "{\"cmd\":\"%s\",\"status\":\"%s\",\"reason\":\"%s\",\"data\":{\"pair\":%u,\"dry_mv\":%u,\"wet_mv\":%u,"
            "\"source\":\"%s\",\"calibrated_at_unix_s\":%lu}}",
            beet_iface_command_name(response->command),
            beet_iface_status_name(response->status),
            beet_iface_reason_name(response->reason),
            response->calibration.pair_index,
            response->calibration.dry_mv,
            response->calibration.wet_mv,
            beet_ble_calibration_source_name(response->calibration.source),
            (unsigned long)response->calibration.calibrated_at_unix_s);
    }

    if (response->command == BEET_IFACE_COMMAND_GET_HISTORY_SUMMARY &&
        response->status == BEET_IFACE_STATUS_ACCEPTED &&
        response->has_history_summary) {
        return snprintf(
            buf,
            len,
            "{\"cmd\":\"%s\",\"status\":\"%s\",\"reason\":\"%s\",\"data\":{\"latest_seq_no\":%llu,\"event_count\":%u,"
            "\"pair_totals_s\":[%lu,%lu,%lu,%lu,%lu,%lu,%lu,%lu]}}",
            beet_iface_command_name(response->command),
            beet_iface_status_name(response->status),
            beet_iface_reason_name(response->reason),
            (unsigned long long)response->latest_event_seq_no,
            response->event_count,
            (unsigned long)response->pair_totals_s[0],
            (unsigned long)response->pair_totals_s[1],
            (unsigned long)response->pair_totals_s[2],
            (unsigned long)response->pair_totals_s[3],
            (unsigned long)response->pair_totals_s[4],
            (unsigned long)response->pair_totals_s[5],
            (unsigned long)response->pair_totals_s[6],
            (unsigned long)response->pair_totals_s[7]);
    }

    if (response->command == BEET_IFACE_COMMAND_GET_EVENT &&
        response->status == BEET_IFACE_STATUS_ACCEPTED &&
        response->has_event) {
        return snprintf(
            buf,
            len,
            "{\"cmd\":\"%s\",\"status\":\"%s\",\"reason\":\"%s\",\"data\":{\"seq\":%llu,\"pair\":%u,"
            "\"src\":%u,\"start\":%lu,\"end\":%lu,\"tv\":%u,\"mb\":%u,\"ma\":%u,\"sb\":%u,\"sa\":%u,"
            "\"req\":%u,\"act\":%u,\"stop\":%u,\"block\":%u,\"bs\":%u,\"be\":%u}}",
            beet_iface_command_name(response->command),
            beet_iface_status_name(response->status),
            beet_iface_reason_name(response->reason),
            (unsigned long long)response->event.seq_no,
            response->event.pair_index,
            response->event.trigger_source,
            (unsigned long)response->event.started_at_unix_s,
            (unsigned long)response->event.ended_at_unix_s,
            response->event.time_valid,
            response->event.moisture_before_pct,
            response->event.moisture_after_pct,
            response->event.sensor_before_mv,
            response->event.sensor_after_mv,
            response->event.requested_duration_s,
            response->event.actual_duration_s,
            response->event.stop_reason,
            response->event.block_reason,
            response->event.battery_start_mv,
            response->event.battery_end_mv);
    }

    if (response->accepted_duration_s > 0U) {
        return snprintf(
            buf,
            len,
            "{\"cmd\":\"%s\",\"status\":\"%s\",\"reason\":\"%s\",\"data\":{\"pair\":%u,\"duration_s\":%u}}",
            beet_iface_command_name(response->command),
            beet_iface_status_name(response->status),
            beet_iface_reason_name(response->reason),
            response->pair_index,
            response->accepted_duration_s);
    }

    if (response->pair_index > 0U) {
        return snprintf(
            buf,
            len,
            "{\"cmd\":\"%s\",\"status\":\"%s\",\"reason\":\"%s\",\"data\":{\"pair\":%u}}",
            beet_iface_command_name(response->command),
            beet_iface_status_name(response->status),
            beet_iface_reason_name(response->reason),
            response->pair_index);
    }

    return snprintf(
        buf,
        len,
        "{\"cmd\":\"%s\",\"status\":\"%s\",\"reason\":\"%s\",\"data\":{}}",
        beet_iface_command_name(response->command),
        beet_iface_status_name(response->status),
        beet_iface_reason_name(response->reason));
}

static bool beet_ble_extract_string(
    const char *json,
    const char *key,
    char *out,
    size_t out_len)
{
    char pattern[32];
    const char *start;
    const char *end;
    size_t value_len;

    if (out_len == 0U) {
        return false;
    }

    snprintf(pattern, sizeof(pattern), "\"%s\"", key);
    start = strstr(json, pattern);
    if (start == NULL) {
        return false;
    }
    start = strchr(start + strlen(pattern), ':');
    if (start == NULL) {
        return false;
    }
    ++start;
    while (*start != '\0' && isspace((unsigned char)*start)) {
        ++start;
    }
    if (*start != '"') {
        return false;
    }
    ++start;
    end = strchr(start, '"');
    if (end == NULL) {
        return false;
    }
    value_len = (size_t)(end - start);
    if (value_len >= out_len) {
        return false;
    }
    memcpy(out, start, value_len);
    out[value_len] = '\0';
    return true;
}

static bool beet_ble_extract_u16(const char *json, const char *key, uint16_t *value)
{
    char pattern[32];
    const char *start;
    unsigned long parsed;
    char *endptr;

    snprintf(pattern, sizeof(pattern), "\"%s\"", key);
    start = strstr(json, pattern);
    if (start == NULL) {
        return false;
    }
    start = strchr(start + strlen(pattern), ':');
    if (start == NULL) {
        return false;
    }
    ++start;
    while (*start != '\0' && isspace((unsigned char)*start)) {
        ++start;
    }
    if (!isdigit((unsigned char)*start)) {
        return false;
    }

    parsed = strtoul(start, &endptr, 10);
    if (endptr == start || parsed > 65535UL) {
        return false;
    }

    *value = (uint16_t)parsed;
    return true;
}

static bool beet_ble_extract_u64(const char *json, const char *key, uint64_t *value)
{
    char pattern[32];
    const char *start;
    unsigned long long parsed;
    char *endptr;

    snprintf(pattern, sizeof(pattern), "\"%s\"", key);
    start = strstr(json, pattern);
    if (start == NULL) {
        return false;
    }
    start = strchr(start + strlen(pattern), ':');
    if (start == NULL) {
        return false;
    }
    ++start;
    while (*start != '\0' && isspace((unsigned char)*start)) {
        ++start;
    }
    if (!isdigit((unsigned char)*start)) {
        return false;
    }

    parsed = strtoull(start, &endptr, 10);
    if (endptr == start) {
        return false;
    }

    *value = (uint64_t)parsed;
    return true;
}

static bool beet_ble_extract_object(
    const char *json,
    const char *key,
    char *out,
    size_t out_len)
{
    char pattern[32];
    const char *start;
    const char *cursor;
    bool in_string = false;
    bool escaping = false;
    unsigned depth = 0U;
    size_t value_len;

    if (out_len == 0U) {
        return false;
    }

    snprintf(pattern, sizeof(pattern), "\"%s\"", key);
    start = strstr(json, pattern);
    if (start == NULL) {
        return false;
    }
    start = strchr(start + strlen(pattern), ':');
    if (start == NULL) {
        return false;
    }
    ++start;
    while (*start != '\0' && isspace((unsigned char)*start)) {
        ++start;
    }
    if (*start != '{') {
        return false;
    }

    cursor = start;
    while (*cursor != '\0') {
        char current = *cursor;

        if (escaping) {
            escaping = false;
        } else if (current == '\\' && in_string) {
            escaping = true;
        } else if (current == '"') {
            in_string = !in_string;
        } else if (!in_string && current == '{') {
            ++depth;
        } else if (!in_string && current == '}') {
            if (depth == 0U) {
                return false;
            }
            --depth;
            if (depth == 0U) {
                value_len = (size_t)(cursor - start) + 1U;
                if (value_len >= out_len) {
                    return false;
                }
                memcpy(out, start, value_len);
                out[value_len] = '\0';
                return true;
            }
        }

        ++cursor;
    }

    return false;
}

bool beet_ble_parse_command_json(
    const char *json,
    beet_iface_command_request_t *request)
{
    char cmd[24];
    char data_json[256];
    uint16_t pair_index;

    memset(request, 0, sizeof(*request));

    if (!beet_ble_extract_string(json, "cmd", cmd, sizeof(cmd)) ||
        !beet_ble_extract_object(json, "data", data_json, sizeof(data_json))) {
        return false;
    }

    if (strcmp(cmd, "manual_start") == 0) {
        request->command = BEET_IFACE_COMMAND_MANUAL_START;
        if (!beet_ble_extract_u16(data_json, "pair", &pair_index)) {
            return false;
        }
        request->pair_index = (uint8_t)pair_index;
        request->has_duration_s = beet_ble_extract_u16(data_json, "duration_s", &request->duration_s);
        return true;
    }

    if (strcmp(cmd, "manual_stop") == 0) {
        request->command = BEET_IFACE_COMMAND_MANUAL_STOP;
        if (!beet_ble_extract_u16(data_json, "pair", &pair_index)) {
            return false;
        }
        request->pair_index = (uint8_t)pair_index;
        return true;
    }

    if (strcmp(cmd, "relay_test_start") == 0) {
        request->command = BEET_IFACE_COMMAND_RELAY_TEST_START;
        if (!beet_ble_extract_u16(data_json, "pair", &pair_index)) {
            return false;
        }
        request->pair_index = (uint8_t)pair_index;
        return true;
    }

    if (strcmp(cmd, "relay_test_stop") == 0) {
        request->command = BEET_IFACE_COMMAND_RELAY_TEST_STOP;
        if (!beet_ble_extract_u16(data_json, "pair", &pair_index)) {
            return false;
        }
        request->pair_index = (uint8_t)pair_index;
        return true;
    }

    if (strcmp(cmd, "moisture_test_start") == 0) {
        request->command = BEET_IFACE_COMMAND_MOISTURE_TEST_START;
        if (!beet_ble_extract_u16(data_json, "pair", &pair_index)) {
            return false;
        }
        request->pair_index = (uint8_t)pair_index;
        return true;
    }

    if (strcmp(cmd, "reset_block") == 0) {
        request->command = BEET_IFACE_COMMAND_RESET_BLOCK;
        if (!beet_ble_extract_u16(data_json, "pair", &pair_index)) {
            return false;
        }
        request->pair_index = (uint8_t)pair_index;
        return true;
    }

    if (strcmp(cmd, "store_calibration") == 0) {
        request->command = BEET_IFACE_COMMAND_STORE_CALIBRATION;
        if (!beet_ble_extract_u16(data_json, "pair", &pair_index) ||
            !beet_ble_extract_u16(data_json, "dry_mv", &request->dry_mv) ||
            !beet_ble_extract_u16(data_json, "wet_mv", &request->wet_mv)) {
            return false;
        }
        request->pair_index = (uint8_t)pair_index;
        return true;
    }

    if (strcmp(cmd, "start_ota") == 0) {
        request->command = BEET_IFACE_COMMAND_START_OTA;
        return true;
    }

    if (strcmp(cmd, "clear_ble_bonds") == 0) {
        request->command = BEET_IFACE_COMMAND_CLEAR_BLE_BONDS;
        return true;
    }

    if (strcmp(cmd, "get_calibration") == 0) {
        request->command = BEET_IFACE_COMMAND_GET_CALIBRATION;
        if (!beet_ble_extract_u16(data_json, "pair", &pair_index)) {
            return false;
        }
        request->pair_index = (uint8_t)pair_index;
        return true;
    }

    if (strcmp(cmd, "get_history_summary") == 0) {
        request->command = BEET_IFACE_COMMAND_GET_HISTORY_SUMMARY;
        return true;
    }

    if (strcmp(cmd, "get_event") == 0) {
        request->command = BEET_IFACE_COMMAND_GET_EVENT;
        return beet_ble_extract_u64(data_json, "seq_no", &request->seq_no);
    }

    if (strcmp(cmd, "disable_pair") == 0) {
        request->command = BEET_IFACE_COMMAND_DISABLE_PAIR;
        if (!beet_ble_extract_u16(data_json, "pair", &pair_index)) {
            return false;
        }
        request->pair_index = (uint8_t)pair_index;
        return true;
    }

    if (strcmp(cmd, "enable_pair") == 0) {
        request->command = BEET_IFACE_COMMAND_ENABLE_PAIR;
        if (!beet_ble_extract_u16(data_json, "pair", &pair_index)) {
            return false;
        }
        request->pair_index = (uint8_t)pair_index;
        return true;
    }

    return false;
}
