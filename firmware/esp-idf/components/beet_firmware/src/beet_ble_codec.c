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
        "\"boot_id\":%lu,\"next_check_in_s\":%lu,\"active_pumps\":%u,\"wifi_connected\":%s,\"mqtt_connected\":%s,\"uptime_s\":%lu}}",
        beet_battery_state_name(state->battery_state),
        state->battery_mv,
        state->time_valid ? "true" : "false",
        (unsigned long)state->boot_id,
        (unsigned long)state->next_check_in_s,
        state->active_pumps,
        state->wifi_connected ? "true" : "false",
        state->mqtt_connected ? "true" : "false",
        (unsigned long)state->uptime_s);
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

static void beet_ble_format_peer_addr(const beet_system_event_record_t *event, char out[18])
{
    snprintf(
        out,
        18,
        "%02X:%02X:%02X:%02X:%02X:%02X",
        event->peer_addr[5],
        event->peer_addr[4],
        event->peer_addr[3],
        event->peer_addr[2],
        event->peer_addr[1],
        event->peer_addr[0]);
}

int beet_ble_format_system_event_frame_json(
    char *buf,
    size_t len,
    const beet_system_event_record_t *event)
{
    char peer_addr[18];
    beet_ble_format_peer_addr(event, peer_addr);
    return snprintf(
        buf,
        len,
        "{\"type\":\"system_event\",\"data\":{\"seq\":%llu,\"event_type\":\"%s\",\"reason\":%u,"
        "\"boot_id\":%lu,\"uptime_s\":%lu,\"unix_s\":%lu,\"time_valid\":%s,\"battery_mv\":%u,"
        "\"peer_addr\":\"%s\",\"peer_addr_type\":%u,\"known_peer\":%s,\"detail\":%lu}}",
        (unsigned long long)event->seq_no,
        beet_system_event_type_name((beet_system_event_type_t)event->event_type),
        event->reason,
        (unsigned long)event->boot_id,
        (unsigned long)event->occurred_uptime_s,
        (unsigned long)event->occurred_unix_s,
        event->time_valid ? "true" : "false",
        event->battery_mv,
        peer_addr,
        event->peer_addr_type,
        event->known_peer ? "true" : "false",
        (unsigned long)event->detail);
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

    if ((response->command == BEET_IFACE_COMMAND_GET_SYSTEM_HISTORY_SUMMARY) &&
        response->status == BEET_IFACE_STATUS_ACCEPTED &&
        response->has_system_history_summary) {
        return snprintf(
            buf,
            len,
            "{\"cmd\":\"%s\",\"status\":\"%s\",\"reason\":\"%s\",\"data\":{\"latest_seq_no\":%llu,\"event_count\":%u}}",
            beet_iface_command_name(response->command),
            beet_iface_status_name(response->status),
            beet_iface_reason_name(response->reason),
            (unsigned long long)response->latest_system_event_seq_no,
            response->system_event_count);
    }

    if (response->command == BEET_IFACE_COMMAND_GET_EVENT &&
        response->status == BEET_IFACE_STATUS_ACCEPTED &&
        response->has_event) {
        return snprintf(
            buf,
            len,
            "{\"cmd\":\"%s\",\"status\":\"%s\",\"reason\":\"%s\",\"data\":{\"seq\":%llu,\"pair\":%u,"
            "\"boot_id\":%lu,\"src\":%u,\"start\":%lu,\"end\":%lu,\"tv\":%u,\"mb\":%u,\"ma\":%u,\"sb\":%u,\"sa\":%u,"
            "\"req\":%u,\"act\":%u,\"stop\":%u,\"block\":%u,\"bs\":%u,\"be\":%u,\"su\":%lu,\"eu\":%lu}}",
            beet_iface_command_name(response->command),
            beet_iface_status_name(response->status),
            beet_iface_reason_name(response->reason),
            (unsigned long long)response->event.seq_no,
            response->event.pair_index,
            (unsigned long)response->event.boot_id,
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
            response->event.battery_end_mv,
            (unsigned long)response->event.started_uptime_s,
            (unsigned long)response->event.ended_uptime_s);
    }

    if (response->command == BEET_IFACE_COMMAND_GET_SYSTEM_EVENT &&
        response->status == BEET_IFACE_STATUS_ACCEPTED &&
        response->has_system_event) {
        char system_json[320];
        int written = beet_ble_format_system_event_frame_json(system_json, sizeof(system_json), &response->system_event);
        const char *data_start = strstr(system_json, "\"data\":");
        if (written < 0 || (size_t)written >= sizeof(system_json) || data_start == NULL) {
            return -1;
        }
        data_start += strlen("\"data\":");
        return snprintf(
            buf,
            len,
            "{\"cmd\":\"%s\",\"status\":\"%s\",\"reason\":\"%s\",\"data\":%s",
            beet_iface_command_name(response->command),
            beet_iface_status_name(response->status),
            beet_iface_reason_name(response->reason),
            data_start);
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

static void beet_ble_skip_ws(const char **cursor)
{
    while (**cursor != '\0' && isspace((unsigned char)**cursor)) {
        ++(*cursor);
    }
}

static bool beet_ble_consume_char(const char **cursor, char expected)
{
    beet_ble_skip_ws(cursor);
    if (**cursor != expected) {
        return false;
    }
    ++(*cursor);
    return true;
}

static bool beet_ble_parse_string(const char **cursor, char *out, size_t out_len)
{
    size_t len = 0U;
    const char *p;

    if (out_len == 0U) {
        return false;
    }

    beet_ble_skip_ws(cursor);
    if (**cursor != '"') {
        return false;
    }

    p = *cursor + 1;
    while (*p != '\0' && *p != '"') {
        unsigned char current = (unsigned char)*p;
        if (current < 0x20U || current == '\\') {
            return false;
        }
        if (len + 1U >= out_len) {
            return false;
        }
        out[len++] = *p++;
    }

    if (*p != '"') {
        return false;
    }

    out[len] = '\0';
    *cursor = p + 1;
    return true;
}

static bool beet_ble_skip_json_string(const char **cursor)
{
    if (**cursor != '"') {
        return false;
    }
    ++(*cursor);

    while (**cursor != '\0') {
        if (**cursor == '\\') {
            ++(*cursor);
            if (**cursor == '\0') {
                return false;
            }
            ++(*cursor);
            continue;
        }
        if ((unsigned char)**cursor < 0x20U) {
            return false;
        }
        if (**cursor == '"') {
            ++(*cursor);
            return true;
        }
        ++(*cursor);
    }

    return false;
}

static bool beet_ble_skip_json_value(const char **cursor);

static bool beet_ble_skip_json_array(const char **cursor)
{
    if (!beet_ble_consume_char(cursor, '[')) {
        return false;
    }

    while (true) {
        beet_ble_skip_ws(cursor);
        if (**cursor == ']') {
            ++(*cursor);
            return true;
        }
        if (!beet_ble_skip_json_value(cursor)) {
            return false;
        }
        beet_ble_skip_ws(cursor);
        if (**cursor == ',') {
            ++(*cursor);
            continue;
        }
        if (**cursor == ']') {
            ++(*cursor);
            return true;
        }
        return false;
    }
}

static bool beet_ble_skip_json_object(const char **cursor)
{
    if (!beet_ble_consume_char(cursor, '{')) {
        return false;
    }

    while (true) {
        beet_ble_skip_ws(cursor);
        if (**cursor == '}') {
            ++(*cursor);
            return true;
        }
        if (!beet_ble_skip_json_string(cursor) ||
            !beet_ble_consume_char(cursor, ':') ||
            !beet_ble_skip_json_value(cursor)) {
            return false;
        }
        beet_ble_skip_ws(cursor);
        if (**cursor == ',') {
            ++(*cursor);
            continue;
        }
        if (**cursor == '}') {
            ++(*cursor);
            return true;
        }
        return false;
    }
}

static bool beet_ble_skip_json_number(const char **cursor)
{
    const char *start = *cursor;

    if (**cursor == '-') {
        ++(*cursor);
    }
    if (!isdigit((unsigned char)**cursor)) {
        return false;
    }
    while (isdigit((unsigned char)**cursor)) {
        ++(*cursor);
    }
    if (**cursor == '.') {
        ++(*cursor);
        if (!isdigit((unsigned char)**cursor)) {
            return false;
        }
        while (isdigit((unsigned char)**cursor)) {
            ++(*cursor);
        }
    }
    if (**cursor == 'e' || **cursor == 'E') {
        ++(*cursor);
        if (**cursor == '+' || **cursor == '-') {
            ++(*cursor);
        }
        if (!isdigit((unsigned char)**cursor)) {
            return false;
        }
        while (isdigit((unsigned char)**cursor)) {
            ++(*cursor);
        }
    }

    return *cursor > start;
}

static bool beet_ble_skip_literal(const char **cursor, const char *literal)
{
    size_t len = strlen(literal);
    if (strncmp(*cursor, literal, len) != 0) {
        return false;
    }
    *cursor += len;
    return true;
}

static bool beet_ble_skip_json_value(const char **cursor)
{
    beet_ble_skip_ws(cursor);
    if (**cursor == '"') {
        return beet_ble_skip_json_string(cursor);
    }
    if (**cursor == '{') {
        return beet_ble_skip_json_object(cursor);
    }
    if (**cursor == '[') {
        return beet_ble_skip_json_array(cursor);
    }
    if (**cursor == '-' || isdigit((unsigned char)**cursor)) {
        return beet_ble_skip_json_number(cursor);
    }
    if (**cursor == 't') {
        return beet_ble_skip_literal(cursor, "true");
    }
    if (**cursor == 'f') {
        return beet_ble_skip_literal(cursor, "false");
    }
    if (**cursor == 'n') {
        return beet_ble_skip_literal(cursor, "null");
    }
    return false;
}

static bool beet_ble_parse_u64(const char **cursor, uint64_t *value)
{
    uint64_t parsed = 0U;
    bool has_digit = false;

    beet_ble_skip_ws(cursor);
    if (!isdigit((unsigned char)**cursor)) {
        return false;
    }

    while (isdigit((unsigned char)**cursor)) {
        uint8_t digit = (uint8_t)(**cursor - '0');
        if (parsed > (UINT64_MAX / 10U) ||
            (parsed == (UINT64_MAX / 10U) && digit > (UINT64_MAX % 10U))) {
            return false;
        }
        parsed = (parsed * 10U) + digit;
        ++(*cursor);
        has_digit = true;
    }

    if (!has_digit) {
        return false;
    }

    *value = parsed;
    return true;
}

static bool beet_ble_parse_u16(const char **cursor, uint16_t *value)
{
    uint64_t parsed = 0U;
    if (!beet_ble_parse_u64(cursor, &parsed) || parsed > UINT16_MAX) {
        return false;
    }
    *value = (uint16_t)parsed;
    return true;
}

static bool beet_ble_parse_pair_data(const char **cursor, uint8_t *pair_index)
{
    bool seen_pair = false;

    if (!beet_ble_consume_char(cursor, '{')) {
        return false;
    }

    while (true) {
        char key[32];
        uint16_t parsed_pair = 0U;

        beet_ble_skip_ws(cursor);
        if (**cursor == '}') {
            ++(*cursor);
            break;
        }

        if (!beet_ble_parse_string(cursor, key, sizeof(key)) ||
            !beet_ble_consume_char(cursor, ':')) {
            return false;
        }

        if (strcmp(key, "pair") == 0 && !seen_pair) {
            if (!beet_ble_parse_u16(cursor, &parsed_pair)) {
                return false;
            }
            *pair_index = (uint8_t)parsed_pair;
            seen_pair = true;
        } else if (strcmp(key, "pair") == 0) {
            return false;
        } else {
            if (!beet_ble_skip_json_value(cursor)) {
                return false;
            }
        }

        beet_ble_skip_ws(cursor);
        if (**cursor == ',') {
            ++(*cursor);
            continue;
        }
        if (**cursor == '}') {
            ++(*cursor);
            break;
        }
        return false;
    }

    return seen_pair;
}

static bool beet_ble_parse_manual_start_data(
    const char **cursor,
    uint8_t *pair_index,
    bool *has_duration_s,
    uint16_t *duration_s)
{
    bool seen_pair = false;
    bool seen_duration = false;

    if (!beet_ble_consume_char(cursor, '{')) {
        return false;
    }

    while (true) {
        char key[32];
        uint16_t parsed_pair = 0U;
        uint16_t parsed_duration = 0U;

        beet_ble_skip_ws(cursor);
        if (**cursor == '}') {
            ++(*cursor);
            break;
        }

        if (!beet_ble_parse_string(cursor, key, sizeof(key)) ||
            !beet_ble_consume_char(cursor, ':')) {
            return false;
        }

        if (strcmp(key, "pair") == 0 && !seen_pair) {
            if (!beet_ble_parse_u16(cursor, &parsed_pair)) {
                return false;
            }
            *pair_index = (uint8_t)parsed_pair;
            seen_pair = true;
        } else if (strcmp(key, "duration_s") == 0 && !seen_duration) {
            if (!beet_ble_parse_u16(cursor, &parsed_duration)) {
                return false;
            }
            *duration_s = parsed_duration;
            *has_duration_s = true;
            seen_duration = true;
        } else if (strcmp(key, "pair") == 0 || strcmp(key, "duration_s") == 0) {
            return false;
        } else {
            if (!beet_ble_skip_json_value(cursor)) {
                return false;
            }
        }

        beet_ble_skip_ws(cursor);
        if (**cursor == ',') {
            ++(*cursor);
            continue;
        }
        if (**cursor == '}') {
            ++(*cursor);
            break;
        }
        return false;
    }

    return seen_pair;
}

static bool beet_ble_parse_calibration_data(
    const char **cursor,
    uint8_t *pair_index,
    uint16_t *dry_mv,
    uint16_t *wet_mv)
{
    bool seen_pair = false;
    bool seen_dry = false;
    bool seen_wet = false;

    if (!beet_ble_consume_char(cursor, '{')) {
        return false;
    }

    while (true) {
        char key[32];
        uint16_t parsed = 0U;

        beet_ble_skip_ws(cursor);
        if (**cursor == '}') {
            ++(*cursor);
            break;
        }

        if (!beet_ble_parse_string(cursor, key, sizeof(key)) ||
            !beet_ble_consume_char(cursor, ':')) {
            return false;
        }

        if (strcmp(key, "pair") == 0 && !seen_pair) {
            if (!beet_ble_parse_u16(cursor, &parsed)) {
                return false;
            }
            *pair_index = (uint8_t)parsed;
            seen_pair = true;
        } else if (strcmp(key, "dry_mv") == 0 && !seen_dry) {
            if (!beet_ble_parse_u16(cursor, dry_mv)) {
                return false;
            }
            seen_dry = true;
        } else if (strcmp(key, "wet_mv") == 0 && !seen_wet) {
            if (!beet_ble_parse_u16(cursor, wet_mv)) {
                return false;
            }
            seen_wet = true;
        } else if (strcmp(key, "pair") == 0 || strcmp(key, "dry_mv") == 0 || strcmp(key, "wet_mv") == 0) {
            return false;
        } else {
            if (!beet_ble_skip_json_value(cursor)) {
                return false;
            }
        }

        beet_ble_skip_ws(cursor);
        if (**cursor == ',') {
            ++(*cursor);
            continue;
        }
        if (**cursor == '}') {
            ++(*cursor);
            break;
        }
        return false;
    }

    return seen_pair && seen_dry && seen_wet;
}

static bool beet_ble_parse_seq_data(const char **cursor, uint64_t *seq_no)
{
    bool seen_seq = false;

    if (!beet_ble_consume_char(cursor, '{')) {
        return false;
    }

    while (true) {
        char key[32];

        beet_ble_skip_ws(cursor);
        if (**cursor == '}') {
            ++(*cursor);
            break;
        }

        if (!beet_ble_parse_string(cursor, key, sizeof(key)) ||
            !beet_ble_consume_char(cursor, ':')) {
            return false;
        }

        if (strcmp(key, "seq_no") == 0 && !seen_seq) {
            if (!beet_ble_parse_u64(cursor, seq_no)) {
                return false;
            }
            seen_seq = true;
        } else if (strcmp(key, "seq_no") == 0) {
            return false;
        } else {
            if (!beet_ble_skip_json_value(cursor)) {
                return false;
            }
        }

        beet_ble_skip_ws(cursor);
        if (**cursor == ',') {
            ++(*cursor);
            continue;
        }
        if (**cursor == '}') {
            ++(*cursor);
            break;
        }
        return false;
    }

    return seen_seq;
}

static bool beet_ble_parse_unix_data(const char **cursor, uint64_t *unix_s)
{
    bool seen_unix = false;

    if (!beet_ble_consume_char(cursor, '{')) {
        return false;
    }

    while (true) {
        char key[32];

        beet_ble_skip_ws(cursor);
        if (**cursor == '}') {
            ++(*cursor);
            break;
        }

        if (!beet_ble_parse_string(cursor, key, sizeof(key)) ||
            !beet_ble_consume_char(cursor, ':')) {
            return false;
        }

        if (strcmp(key, "unix_s") == 0 && !seen_unix) {
            if (!beet_ble_parse_u64(cursor, unix_s)) {
                return false;
            }
            seen_unix = true;
        } else if (strcmp(key, "unix_s") == 0) {
            return false;
        } else {
            if (!beet_ble_skip_json_value(cursor)) {
                return false;
            }
        }

        beet_ble_skip_ws(cursor);
        if (**cursor == ',') {
            ++(*cursor);
            continue;
        }
        if (**cursor == '}') {
            ++(*cursor);
            break;
        }
        return false;
    }

    return seen_unix;
}

static bool beet_ble_parse_empty_data(const char **cursor)
{
    if (!beet_ble_consume_char(cursor, '{')) {
        return false;
    }
    beet_ble_skip_ws(cursor);
    if (**cursor != '}') {
        return false;
    }
    ++(*cursor);
    return true;
}

bool beet_ble_parse_command_json(
    const char *json,
    beet_iface_command_request_t *request)
{
    const char *cursor = json;
    char cmd[40];
    bool seen_cmd = false;
    bool seen_data = false;

    memset(request, 0, sizeof(*request));

    if (!beet_ble_consume_char(&cursor, '{')) {
        return false;
    }

    while (true) {
        char key[32];

        beet_ble_skip_ws(&cursor);
        if (*cursor == '}') {
            ++cursor;
            break;
        }

        if (!beet_ble_parse_string(&cursor, key, sizeof(key)) ||
            !beet_ble_consume_char(&cursor, ':')) {
            return false;
        }

        if (strcmp(key, "cmd") == 0 && !seen_cmd) {
            if (!beet_ble_parse_string(&cursor, cmd, sizeof(cmd))) {
                return false;
            }
            seen_cmd = true;
        } else if (strcmp(key, "data") == 0 && !seen_data) {
            if (!seen_cmd) {
                return false;
            }

            if (strcmp(cmd, "manual_start") == 0) {
                request->command = BEET_IFACE_COMMAND_MANUAL_START;
                if (!beet_ble_parse_manual_start_data(
                        &cursor,
                        &request->pair_index,
                        &request->has_duration_s,
                        &request->duration_s)) {
                    return false;
                }
            } else if (strcmp(cmd, "manual_stop") == 0) {
                request->command = BEET_IFACE_COMMAND_MANUAL_STOP;
                if (!beet_ble_parse_pair_data(&cursor, &request->pair_index)) {
                    return false;
                }
            } else if (strcmp(cmd, "relay_test_start") == 0) {
                request->command = BEET_IFACE_COMMAND_RELAY_TEST_START;
                if (!beet_ble_parse_pair_data(&cursor, &request->pair_index)) {
                    return false;
                }
            } else if (strcmp(cmd, "relay_test_stop") == 0) {
                request->command = BEET_IFACE_COMMAND_RELAY_TEST_STOP;
                if (!beet_ble_parse_pair_data(&cursor, &request->pair_index)) {
                    return false;
                }
            } else if (strcmp(cmd, "moisture_test_start") == 0) {
                request->command = BEET_IFACE_COMMAND_MOISTURE_TEST_START;
                if (!beet_ble_parse_pair_data(&cursor, &request->pair_index)) {
                    return false;
                }
            } else if (strcmp(cmd, "reset_block") == 0) {
                request->command = BEET_IFACE_COMMAND_RESET_BLOCK;
                if (!beet_ble_parse_pair_data(&cursor, &request->pair_index)) {
                    return false;
                }
            } else if (strcmp(cmd, "store_calibration") == 0) {
                request->command = BEET_IFACE_COMMAND_STORE_CALIBRATION;
                if (!beet_ble_parse_calibration_data(
                        &cursor,
                        &request->pair_index,
                        &request->dry_mv,
                        &request->wet_mv)) {
                    return false;
                }
            } else if (strcmp(cmd, "start_ota") == 0) {
                request->command = BEET_IFACE_COMMAND_START_OTA;
                if (!beet_ble_parse_empty_data(&cursor)) {
                    return false;
                }
            } else if (strcmp(cmd, "clear_ble_bonds") == 0) {
                request->command = BEET_IFACE_COMMAND_CLEAR_BLE_BONDS;
                if (!beet_ble_parse_empty_data(&cursor)) {
                    return false;
                }
            } else if (strcmp(cmd, "get_calibration") == 0) {
                request->command = BEET_IFACE_COMMAND_GET_CALIBRATION;
                if (!beet_ble_parse_pair_data(&cursor, &request->pair_index)) {
                    return false;
                }
            } else if (strcmp(cmd, "get_history_summary") == 0) {
                request->command = BEET_IFACE_COMMAND_GET_HISTORY_SUMMARY;
                if (!beet_ble_parse_empty_data(&cursor)) {
                    return false;
                }
            } else if (strcmp(cmd, "get_event") == 0) {
                request->command = BEET_IFACE_COMMAND_GET_EVENT;
                if (!beet_ble_parse_seq_data(&cursor, &request->seq_no)) {
                    return false;
                }
            } else if (strcmp(cmd, "get_system_history_summary") == 0) {
                request->command = BEET_IFACE_COMMAND_GET_SYSTEM_HISTORY_SUMMARY;
                if (!beet_ble_parse_empty_data(&cursor)) {
                    return false;
                }
            } else if (strcmp(cmd, "get_system_event") == 0) {
                request->command = BEET_IFACE_COMMAND_GET_SYSTEM_EVENT;
                if (!beet_ble_parse_seq_data(&cursor, &request->seq_no)) {
                    return false;
                }
            } else if (strcmp(cmd, "set_time") == 0) {
                request->command = BEET_IFACE_COMMAND_SET_TIME;
                if (!beet_ble_parse_unix_data(&cursor, &request->unix_s)) {
                    return false;
                }
            } else if (strcmp(cmd, "disable_pair") == 0) {
                request->command = BEET_IFACE_COMMAND_DISABLE_PAIR;
                if (!beet_ble_parse_pair_data(&cursor, &request->pair_index)) {
                    return false;
                }
            } else if (strcmp(cmd, "enable_pair") == 0) {
                request->command = BEET_IFACE_COMMAND_ENABLE_PAIR;
                if (!beet_ble_parse_pair_data(&cursor, &request->pair_index)) {
                    return false;
                }
            } else {
                return false;
            }

            seen_data = true;
        } else if (strcmp(key, "cmd") == 0 || strcmp(key, "data") == 0) {
            return false;
        } else {
            if (!beet_ble_skip_json_value(&cursor)) {
                return false;
            }
        }

        beet_ble_skip_ws(&cursor);
        if (*cursor == ',') {
            ++cursor;
            continue;
        }
        if (*cursor == '}') {
            ++cursor;
            break;
        }
        return false;
    }

    beet_ble_skip_ws(&cursor);
    return seen_cmd && seen_data && *cursor == '\0';
}
