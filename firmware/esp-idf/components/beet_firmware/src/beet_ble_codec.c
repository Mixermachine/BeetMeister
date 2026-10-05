#include "beet_ble_codec.h"

#include <ctype.h>
#include <limits.h>
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

size_t beet_ble_base64_encoded_len(size_t raw_len)
{
    if (raw_len == 0U) {
        return 0U;
    }

    return ((raw_len + 2U) / 3U) * 4U;
}

bool beet_ble_base64_encode(
    const uint8_t *raw,
    size_t raw_len,
    char *base64,
    size_t base64_len,
    size_t *written)
{
    static const char alphabet[] =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    size_t required = beet_ble_base64_encoded_len(raw_len);
    size_t out = 0U;
    size_t in = 0U;

    if (written != NULL) {
        *written = 0U;
    }
    if (required == 0U) {
        if (base64_len == 0U || base64 == NULL) {
            return false;
        }
        base64[0] = '\0';
        return true;
    }
    if (raw == NULL || base64 == NULL || base64_len <= required) {
        return false;
    }

    while (in < raw_len) {
        uint32_t block = 0U;
        size_t remain = raw_len - in;

        block |= ((uint32_t)raw[in]) << 16;
        if (remain > 1U) {
            block |= ((uint32_t)raw[in + 1U]) << 8;
        }
        if (remain > 2U) {
            block |= (uint32_t)raw[in + 2U];
        }

        base64[out++] = alphabet[(block >> 18) & 0x3FU];
        base64[out++] = alphabet[(block >> 12) & 0x3FU];
        base64[out++] = (remain > 1U) ? alphabet[(block >> 6) & 0x3FU] : '=';
        base64[out++] = (remain > 2U) ? alphabet[block & 0x3FU] : '=';

        in += (remain >= 3U) ? 3U : remain;
    }

    base64[out] = '\0';
    if (written != NULL) {
        *written = out;
    }
    return true;
}

size_t beet_ble_command_chunk_fragment_capacity(
    size_t att_payload_len,
    uint32_t chunk_id,
    uint16_t chunk_index,
    uint16_t chunk_count)
{
    char frame[96];
    int written;

    written = snprintf(
        frame,
        sizeof(frame),
        "{\"type\":\"cmd_chunk\",\"id\":%lu,\"i\":%u,\"n\":%u,\"b64\":\"\"}",
        (unsigned long)chunk_id,
        (unsigned)chunk_index,
        (unsigned)chunk_count);
    if (written < 0) {
        return 0U;
    }
    if ((size_t)written >= att_payload_len) {
        return 0U;
    }
    return att_payload_len - (size_t)written;
}

int beet_ble_format_command_chunk_frame_json(
    char *buf,
    size_t len,
    uint32_t chunk_id,
    uint16_t chunk_index,
    uint16_t chunk_count,
    const char *base64_fragment,
    size_t base64_fragment_len)
{
    if (base64_fragment == NULL) {
        return -1;
    }
    if (base64_fragment_len > (size_t)INT_MAX) {
        return -1;
    }

    return snprintf(
        buf,
        len,
        "{\"type\":\"cmd_chunk\",\"id\":%lu,\"i\":%u,\"n\":%u,\"b64\":\"%.*s\"}",
        (unsigned long)chunk_id,
        (unsigned)chunk_index,
        (unsigned)chunk_count,
        (int)base64_fragment_len,
        base64_fragment);
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

int beet_ble_format_maintenance_info_json(
    char *buf,
    size_t len,
    const beet_maintenance_info_t *info)
{
    if (buf == NULL || info == NULL) {
        return -1;
    }

    const char *kind_name = beet_maintenance_image_kind_name(info->image_kind);
    const char *uc = info->update_capable ? "true" : "false";

    return snprintf(buf, len,
        "{\"type\":\"maintenance_info\",\"data\":{"
        "\"product_id\":\"%s\",\"hardware_rev\":\"%s\","
        "\"firmware_version\":\"%s\",\"build_label\":\"%s\","
        "\"maintenance_protocol_version\":%lu,"
        "\"runtime_protocol_version\":%lu,"
        "\"update_capable\":%s,\"image_kind\":\"%s\"}}",
        info->product_id, info->hardware_rev,
        info->firmware_version, info->build_label,
        (unsigned long)info->maintenance_protocol_version,
        (unsigned long)info->runtime_protocol_version,
        uc, kind_name);
}

int beet_ble_format_maintenance_status_json(
    char *buf,
    size_t len,
    const beet_maintenance_status_t *status)
{
    int written;

    if (buf == NULL || status == NULL) {
        return -1;
    }

    written = snprintf(
        buf,
        len,
        "{\"type\":\"maintenance_status\",\"data\":{\"state\":\"%s\"",
        beet_maintenance_state_name(status->state));
    if (written < 0 || (size_t)written >= len) {
        return -1;
    }

    if (status->has_session_id) {
        int delta = snprintf(buf + written, len - (size_t)written, ",\"session_id\":%lu", (unsigned long)status->session_id);
        if (delta < 0 || (size_t)delta >= len - (size_t)written) {
            return -1;
        }
        written += delta;
    }

    {
        int delta = snprintf(
            buf + written,
            len - (size_t)written,
            ",\"next_offset\":%lu,\"bytes_received\":%lu,\"total_bytes\":%lu",
            (unsigned long)status->next_offset,
            (unsigned long)status->bytes_received,
            (unsigned long)status->total_bytes);
        if (delta < 0 || (size_t)delta >= len - (size_t)written) {
            return -1;
        }
        written += delta;
    }

    if (status->has_failure_reason) {
        int delta = snprintf(
            buf + written,
            len - (size_t)written,
            ",\"failure_reason\":\"%s\"",
            beet_maintenance_failure_reason_name(status->failure_reason));
        if (delta < 0 || (size_t)delta >= len - (size_t)written) {
            return -1;
        }
        written += delta;
    }

    if ((size_t)written + 2U >= len) {
        return -1;
    }
    buf[written++] = '}';
    buf[written++] = '}';
    buf[written] = '\0';
    return written;
}

int beet_ble_format_device_frame_json(
    char *buf,
    size_t len,
    const beet_iface_device_state_t *state)
{
    if (buf == NULL || state == NULL) {
        return -1;
    }

    const char *bs = beet_battery_state_name(state->battery_state);
    const char *vs = beet_valve_state_name(state->valve_state);

    /* Try the full format. */
    int needed = snprintf(NULL, 0,
        "{\"type\":\"device\",\"data\":{"
        "\"battery_state\":\"%s\",\"battery_mv\":%u,\"time_valid\":%d,"
        "\"boot_id\":%lu,\"next_check_in_s\":%lu,\"active_pumps\":%u,"
        "\"wifi_connected\":%d,\"mqtt_connected\":%d,"
        "\"uptime_s\":%lu,\"valve_enabled\":%d,\"valve_state\":\"%s\"}}",
        bs, state->battery_mv, (int)state->time_valid,
        (unsigned long)state->boot_id,
        (unsigned long)state->next_check_in_s,
        state->active_pumps, (int)state->wifi_connected,
        (int)state->mqtt_connected,
        (unsigned long)state->uptime_s, (int)state->valve_enabled, vs);
    if (needed < 0) return -1;
    if ((size_t)needed < len) {
        return snprintf(buf, len,
            "{\"type\":\"device\",\"data\":{"
            "\"battery_state\":\"%s\",\"battery_mv\":%u,\"time_valid\":%d,"
            "\"boot_id\":%lu,\"next_check_in_s\":%lu,\"active_pumps\":%u,"
            "\"wifi_connected\":%d,\"mqtt_connected\":%d,"
            "\"uptime_s\":%lu,\"valve_enabled\":%d,\"valve_state\":\"%s\"}}",
            bs, state->battery_mv, (int)state->time_valid,
            (unsigned long)state->boot_id,
            (unsigned long)state->next_check_in_s,
            state->active_pumps, (int)state->wifi_connected,
            (int)state->mqtt_connected,
            (unsigned long)state->uptime_s, (int)state->valve_enabled, vs);
    }

    /* Doesn't fit — shorten valve_state string to make room. */
    size_t excess = (size_t)needed - len + 1U;
    size_t vs_len = strlen(vs);
    char trunc_vs[16];
    size_t trim = (excess < vs_len) ? excess : vs_len;
    size_t n = vs_len - trim;
    memcpy(trunc_vs, vs, n);
    trunc_vs[n] = '\0';

    return snprintf(buf, len,
        "{\"type\":\"device\",\"data\":{"
        "\"battery_state\":\"%s\",\"battery_mv\":%u,\"time_valid\":%d,"
        "\"boot_id\":%lu,\"next_check_in_s\":%lu,\"active_pumps\":%u,"
        "\"wifi_connected\":%d,\"mqtt_connected\":%d,"
        "\"uptime_s\":%lu,\"valve_enabled\":%d,\"valve_state\":\"%s\"}}",
        bs, state->battery_mv, (int)state->time_valid,
        (unsigned long)state->boot_id,
        (unsigned long)state->next_check_in_s,
        state->active_pumps, (int)state->wifi_connected,
        (int)state->mqtt_connected,
        (unsigned long)state->uptime_s, (int)state->valve_enabled, trunc_vs);
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
        "\"blocked\":%d,\"block_reason\":\"%s\",\"remaining_s\":%u,\"source\":\"%s\","
        "\"enabled\":%d,\"sensor_valid\":%d}}",
        state->pair_index,
        beet_pair_state_name(state->pair_state),
        state->moisture_pct,
        state->sensor_mv,
        (int)state->blocked,
        beet_block_reason_name(state->block_reason),
        state->remaining_s,
        beet_ble_run_source_name(state->source),
        (int)state->enabled,
        (int)state->sensor_valid);
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

static int beet_ble_format_system_event_json(
    char *buf,
    size_t len,
    const char *prefix,
    const beet_system_event_record_t *event,
    uint32_t unix_s,
    const char *suffix)
{
    char peer_addr[18];

    if (buf == NULL || prefix == NULL || event == NULL || suffix == NULL) {
        return -1;
    }

    beet_ble_format_peer_addr(event, peer_addr);
    return snprintf(
        buf,
        len,
        "%s{\"seq\":%llu,\"event_type\":\"%s\",\"reason\":%u,"
        "\"boot_id\":%lu,\"uptime_s\":%lu,\"unix_s\":%lu,\"battery_mv\":%u,"
        "\"peer_addr\":\"%s\",\"peer_addr_type\":%u,\"known_peer\":%d,\"detail\":%lu}%s",
        prefix,
        (unsigned long long)event->seq_no,
        beet_system_event_type_name((beet_system_event_type_t)event->event_type),
        event->reason,
        (unsigned long)event->boot_id,
        (unsigned long)event->occurred_uptime_s,
        (unsigned long)unix_s,
        event->battery_mv,
        peer_addr,
        event->peer_addr_type,
        (int)event->known_peer,
        (unsigned long)event->detail,
        suffix);
}

int beet_ble_format_system_event_frame_json(
    char *buf,
    size_t len,
    const beet_system_event_record_t *event,
    uint32_t unix_s)
{
    return beet_ble_format_system_event_json(
        buf,
        len,
        "{\"type\":\"system_event\",\"data\":",
        event,
        unix_s,
        "}");
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
            "\"boot_id\":%lu,\"src\":%u,\"start\":%lu,\"end\":%lu,\"mb\":%u,\"ma\":%u,\"sb\":%u,\"sa\":%u,"
            "\"req\":%u,\"act\":%u,\"stop\":%u,\"block\":%u,\"bs\":%u,\"be\":%u,\"su\":%lu,\"eu\":%lu}}",
            beet_iface_command_name(response->command),
            beet_iface_status_name(response->status),
            beet_iface_reason_name(response->reason),
            (unsigned long long)response->event.seq_no,
            response->event.pair_index,
            (unsigned long)response->event.boot_id,
            response->event.trigger_source,
            (unsigned long)response->event_started_unix_s,
            (unsigned long)response->event_ended_unix_s,
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
        char prefix[160];
        int prefix_written = snprintf(
            prefix,
            sizeof(prefix),
            "{\"cmd\":\"%s\",\"status\":\"%s\",\"reason\":\"%s\",\"data\":",
            beet_iface_command_name(response->command),
            beet_iface_status_name(response->status),
            beet_iface_reason_name(response->reason));

        if (prefix_written < 0 || (size_t)prefix_written >= sizeof(prefix)) {
            return -1;
        }

        return beet_ble_format_system_event_json(
            buf,
            len,
            prefix,
            &response->system_event,
            response->system_event_unix_s,
            "}");
    }

    if ((response->command == BEET_IFACE_COMMAND_GET_WATERING_INTERVAL ||
            response->command == BEET_IFACE_COMMAND_STORE_WATERING_INTERVAL) &&
        response->status == BEET_IFACE_STATUS_ACCEPTED &&
        response->has_watering_interval) {
        return snprintf(
            buf,
            len,
            "{\"cmd\":\"%s\",\"status\":\"%s\",\"reason\":\"%s\",\"data\":{\"watering_interval_s\":%lu}}",
            beet_iface_command_name(response->command),
            beet_iface_status_name(response->status),
            beet_iface_reason_name(response->reason),
            (unsigned long)response->watering_interval_s);
    }

    if (response->command == BEET_IFACE_COMMAND_GET_PAIR_WIRING &&
        response->status == BEET_IFACE_STATUS_ACCEPTED &&
        response->has_pair_wiring) {
        return snprintf(
            buf,
            len,
            "{\"cmd\":\"%s\",\"status\":\"%s\",\"reason\":\"%s\",\"data\":{\"pair\":%u,\"moisture_gpio\":%d,\"relay_gpio\":%d}}",
            beet_iface_command_name(response->command),
            beet_iface_status_name(response->status),
            beet_iface_reason_name(response->reason),
            response->pair_index,
            (int)response->moisture_gpio,
            (int)response->relay_gpio);
    }

    if (response->command == BEET_IFACE_COMMAND_GET_PAIR_NAMES &&
        response->status == BEET_IFACE_STATUS_ACCEPTED &&
        response->has_pair_names) {
        return snprintf(
            buf,
            len,
            "{\"cmd\":\"%s\",\"status\":\"%s\",\"reason\":\"%s\",\"data\":{\"names\":[\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\"]}}",
            beet_iface_command_name(response->command),
            beet_iface_status_name(response->status),
            beet_iface_reason_name(response->reason),
            response->pair_names[0],
            response->pair_names[1],
            response->pair_names[2],
            response->pair_names[3],
            response->pair_names[4],
            response->pair_names[5],
            response->pair_names[6],
            response->pair_names[7]);
    }

    if (response->command == BEET_IFACE_COMMAND_STORE_PAIR_NAME &&
        response->status == BEET_IFACE_STATUS_ACCEPTED &&
        response->has_stored_pair_name) {
        return snprintf(
            buf,
            len,
            "{\"cmd\":\"%s\",\"status\":\"%s\",\"reason\":\"%s\",\"data\":{\"pair\":%u,\"name\":\"%s\"}}",
            beet_iface_command_name(response->command),
            beet_iface_status_name(response->status),
            beet_iface_reason_name(response->reason),
            response->stored_pair_index,
            response->stored_pair_name);
    }

    if (response->command == BEET_IFACE_COMMAND_GET_PAIR_COMBINED &&
        response->status == BEET_IFACE_STATUS_ACCEPTED &&
        response->has_combined) {
        return snprintf(
            buf,
            len,
            "{\"cmd\":\"%s\",\"status\":\"%s\",\"reason\":\"%s\",\"data\":{\"pair\":%u,\"followers\":%u}}",
            beet_iface_command_name(response->command),
            beet_iface_status_name(response->status),
            beet_iface_reason_name(response->reason),
            response->combined_pair_index,
            (unsigned int)response->combined_mask);
    }

    if (response->command == BEET_IFACE_COMMAND_STORE_PAIR_COMBINED &&
        response->status == BEET_IFACE_STATUS_ACCEPTED &&
        response->has_combined) {
        return snprintf(
            buf,
            len,
            "{\"cmd\":\"%s\",\"status\":\"%s\",\"reason\":\"%s\",\"data\":{\"pair\":%u,\"followers\":%u}}",
            beet_iface_command_name(response->command),
            beet_iface_status_name(response->status),
            beet_iface_reason_name(response->reason),
            response->combined_pair_index,
            (unsigned int)response->combined_mask);
    }

    if ((response->command == BEET_IFACE_COMMAND_GET_PAIR_CONFIG ||
         response->command == BEET_IFACE_COMMAND_STORE_PAIR_CONFIG) &&
        response->status == BEET_IFACE_STATUS_ACCEPTED &&
        response->has_pair_config) {
        const char *target_str = "medium";
        if (response->pair_config.target_level == BEET_TARGET_MOISTURE_DRY) {
            target_str = "dry";
        } else if (response->pair_config.target_level == BEET_TARGET_MOISTURE_MOIST) {
            target_str = "moist";
        }
        return snprintf(
            buf,
            len,
            "{\"cmd\":\"%s\",\"status\":\"%s\",\"reason\":\"%s\",\"data\":{\"pair\":%u,\"target_level\":\"%s\",\"duration_multiplier\":%u}}",
            beet_iface_command_name(response->command),
            beet_iface_status_name(response->status),
            beet_iface_reason_name(response->reason),
            response->pair_config.pair_index,
            target_str,
            (unsigned int)response->pair_config.duration_multiplier);
    }

    if ((response->command == BEET_IFACE_COMMAND_GET_VALVE_CONFIG ||
            response->command == BEET_IFACE_COMMAND_STORE_VALVE_CONFIG) &&
        response->status == BEET_IFACE_STATUS_ACCEPTED &&
        response->has_valve_config) {
        return snprintf(
            buf,
            len,
            "{\"cmd\":\"%s\",\"status\":\"%s\",\"reason\":\"%s\",\"data\":{\"valve_enabled\":%d,"
            "\"servo_min_pulse_us\":%u,\"servo_max_pulse_us\":%u,\"open_pulse_us\":%u,\"shut_pulse_us\":%u,\"move_duration_ms\":%u,"
            "\"settle_delay_ms\":%u,\"open_hold_ms\":%u}}",
            beet_iface_command_name(response->command),
            beet_iface_status_name(response->status),
            beet_iface_reason_name(response->reason),
            response->valve_enabled ? 1 : 0,
            response->valve_servo_min_pulse_us,
            response->valve_servo_max_pulse_us,
            response->valve_open_pulse_us,
            response->valve_shut_pulse_us,
            response->valve_move_duration_ms,
            response->valve_settle_delay_ms,
            response->valve_open_hold_ms);
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

    if ((response->command == BEET_IFACE_COMMAND_GET_MAX_ACTIVE_PUMPS ||
            response->command == BEET_IFACE_COMMAND_STORE_MAX_ACTIVE_PUMPS) &&
        response->status == BEET_IFACE_STATUS_ACCEPTED &&
        response->has_max_active_pumps) {
        return snprintf(
            buf,
            len,
            "{\"cmd\":\"%s\",\"status\":\"%s\",\"reason\":\"%s\",\"data\":{\"max_active_pumps\":%u}}",
            beet_iface_command_name(response->command),
            beet_iface_status_name(response->status),
            beet_iface_reason_name(response->reason),
            response->max_active_pumps);
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

static bool beet_ble_parse_u32(const char **cursor, uint32_t *value)
{
    uint64_t parsed = 0U;

    if (value == NULL) {
        return false;
    }
    if (!beet_ble_parse_u64(cursor, &parsed) || parsed > UINT32_MAX) {
        return false;
    }

    *value = (uint32_t)parsed;
    return true;
}

static bool beet_ble_parse_bool(const char **cursor, bool *value)
{
    beet_ble_skip_ws(cursor);
    if ((*cursor)[0] == '1' && ((*cursor)[1] == ',' || (*cursor)[1] == '}' || (*cursor)[1] == '\0' || (*cursor)[1] == ' ' || (*cursor)[1] == '\t' || (*cursor)[1] == '\r' || (*cursor)[1] == '\n')) {
        *cursor += 1;
        *value = true;
        return true;
    }
    if ((*cursor)[0] == '0' && ((*cursor)[1] == ',' || (*cursor)[1] == '}' || (*cursor)[1] == '\0' || (*cursor)[1] == ' ' || (*cursor)[1] == '\t' || (*cursor)[1] == '\r' || (*cursor)[1] == '\n')) {
        *cursor += 1;
        *value = false;
        return true;
    }
    return false;
}

static bool beet_ble_parse_pair_name_data(
    const char **cursor,
    uint8_t *pair_index,
    char *name,
    size_t name_len)
{
    bool seen_pair = false;
    bool seen_name = false;

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
        } else if (strcmp(key, "name") == 0 && !seen_name) {
            if (!beet_ble_parse_string(cursor, name, name_len)) {
                return false;
            }
            seen_name = true;
        } else if (strcmp(key, "pair") == 0 || strcmp(key, "name") == 0) {
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

    return seen_pair && seen_name;
}

static bool beet_ble_parse_combined_data(
    const char **cursor,
    uint8_t *pair_index,
    uint8_t *combined_mask)
{
    bool seen_pair = false;
    bool seen_followers = false;

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
        } else if (strcmp(key, "followers") == 0 && !seen_followers) {
            if (!beet_ble_parse_u16(cursor, &parsed)) {
                return false;
            }
            *combined_mask = (uint8_t)parsed;
            seen_followers = true;
        } else if (strcmp(key, "pair") == 0 || strcmp(key, "followers") == 0) {
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

    return seen_pair && seen_followers;
}

static bool beet_ble_parse_pair_config_data(
    const char **cursor,
    uint8_t *pair_index,
    beet_target_moisture_level_t *target_level,
    uint8_t *duration_multiplier)
{
    bool seen_pair = false;
    bool seen_target = false;
    bool seen_mult = false;

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
        } else if (strcmp(key, "target_level") == 0 && !seen_target) {
            char target_str[16];
            beet_ble_skip_ws(cursor);
            if (**cursor == '"') {
                if (!beet_ble_parse_string(cursor, target_str, sizeof(target_str))) {
                    return false;
                }
                if (strcmp(target_str, "dry") == 0) {
                    *target_level = BEET_TARGET_MOISTURE_DRY;
                } else if (strcmp(target_str, "moist") == 0) {
                    *target_level = BEET_TARGET_MOISTURE_MOIST;
                } else {
                    *target_level = BEET_TARGET_MOISTURE_MEDIUM;
                }
            } else {
                if (!beet_ble_parse_u16(cursor, &parsed)) {
                    return false;
                }
                *target_level = (beet_target_moisture_level_t)parsed;
            }
            seen_target = true;
        } else if (strcmp(key, "duration_multiplier") == 0 && !seen_mult) {
            if (!beet_ble_parse_u16(cursor, &parsed)) {
                return false;
            }
            *duration_multiplier = (uint8_t)parsed;
            seen_mult = true;
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

    return seen_pair && seen_target && seen_mult;
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

static bool beet_ble_parse_watering_interval_data(const char **cursor, uint32_t *interval_s)
{
    bool seen_interval = false;
    uint64_t parsed_interval = 0U;

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

        if (strcmp(key, "watering_interval_s") == 0 && !seen_interval) {
            if (!beet_ble_parse_u64(cursor, &parsed_interval) || parsed_interval > UINT32_MAX) {
                return false;
            }
            *interval_s = (uint32_t)parsed_interval;
            seen_interval = true;
        } else if (strcmp(key, "watering_interval_s") == 0) {
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

    return seen_interval;
}

static bool beet_ble_parse_max_active_pumps_data(const char **cursor, uint8_t *max_pumps)
{
    bool seen_max = false;
    uint64_t parsed_max = 0U;

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

        if (strcmp(key, "max") == 0 && !seen_max) {
            if (!beet_ble_parse_u64(cursor, &parsed_max) || parsed_max > UINT8_MAX) {
                return false;
            }
            *max_pumps = (uint8_t)parsed_max;
            seen_max = true;
        } else if (strcmp(key, "max") == 0) {
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

    return seen_max;
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

static bool beet_ble_parse_string_array(
    const char **cursor,
    char values[][BEET_MAINTENANCE_HARDWARE_REV_MAX_LEN + 1U],
    uint8_t *count,
    uint8_t max_count)
{
    char parsed[BEET_MAINTENANCE_HARDWARE_REV_MAX_LEN + 1U];
    uint8_t parsed_count = 0U;

    if (cursor == NULL || *cursor == NULL || values == NULL || count == NULL || max_count == 0U) {
        return false;
    }
    if (!beet_ble_consume_char(cursor, '[')) {
        return false;
    }

    while (true) {
        beet_ble_skip_ws(cursor);
        if (**cursor == ']') {
            ++(*cursor);
            break;
        }

        if (parsed_count >= max_count ||
            !beet_ble_parse_string(cursor, parsed, sizeof(parsed))) {
            return false;
        }
        memcpy(values[parsed_count], parsed, sizeof(parsed));
        parsed_count++;

        beet_ble_skip_ws(cursor);
        if (**cursor == ',') {
            ++(*cursor);
            continue;
        }
        if (**cursor == ']') {
            ++(*cursor);
            break;
        }
        return false;
    }

    *count = parsed_count;
    return true;
}

static bool beet_ble_parse_begin_update_data(
    const char **cursor,
    beet_maintenance_begin_update_request_t *request)
{
    // WARNING: This parser accepts the fixed maintenance begin_update wire
    // contract, including compact aliases such as fv/bl/sz/sh/pi/hr/ai/ik.
    // Future changes must remain backward compatible with shipped apps.
    char key[32];
    char parsed_string[BEET_MAINTENANCE_ASSET_ID_MAX_LEN + 1U];
    bool seen_firmware_version = false;
    bool seen_build_label = false;
    bool seen_image_size = false;
    bool seen_image_sha256 = false;
    bool seen_product_id = false;
    bool seen_hardware_revs = false;
    bool seen_asset_id = false;
    bool seen_image_kind = false;

    if (cursor == NULL || *cursor == NULL || request == NULL) {
        return false;
    }
    if (!beet_ble_consume_char(cursor, '{')) {
        return false;
    }

    while (true) {
        beet_ble_skip_ws(cursor);
        if (**cursor == '}') {
            ++(*cursor);
            break;
        }

        if (!beet_ble_parse_string(cursor, key, sizeof(key)) ||
            !beet_ble_consume_char(cursor, ':')) {
            return false;
        }

        if (strcmp(key, "firmware_version") == 0 || strcmp(key, "fv") == 0) {
            if (seen_firmware_version ||
                !beet_ble_parse_string(cursor, request->firmware_version, sizeof(request->firmware_version))) {
                return false;
            }
            seen_firmware_version = true;
        } else if (strcmp(key, "build_label") == 0 || strcmp(key, "bl") == 0) {
            if (seen_build_label ||
                !beet_ble_parse_string(cursor, request->build_label, sizeof(request->build_label))) {
                return false;
            }
            seen_build_label = true;
        } else if (strcmp(key, "image_size") == 0 || strcmp(key, "sz") == 0) {
            if (seen_image_size || !beet_ble_parse_u32(cursor, &request->image_size)) {
                return false;
            }
            seen_image_size = true;
        } else if (strcmp(key, "image_sha256") == 0 || strcmp(key, "sh") == 0) {
            if (seen_image_sha256 ||
                !beet_ble_parse_string(cursor, request->image_sha256, sizeof(request->image_sha256))) {
                return false;
            }
            seen_image_sha256 = true;
        } else if (strcmp(key, "product_id") == 0 || strcmp(key, "pi") == 0) {
            if (seen_product_id ||
                !beet_ble_parse_string(cursor, request->product_id, sizeof(request->product_id))) {
                return false;
            }
            seen_product_id = true;
        } else if (strcmp(key, "hardware_revs") == 0 || strcmp(key, "hr") == 0) {
            if (seen_hardware_revs ||
                !beet_ble_parse_string_array(
                    cursor,
                    request->hardware_revs,
                    &request->hardware_rev_count,
                    BEET_MAINTENANCE_COMPAT_REV_MAX_COUNT)) {
                return false;
            }
            seen_hardware_revs = true;
        } else if (strcmp(key, "runtime_protocol_version") == 0 || strcmp(key, "rp") == 0) {
            if (request->has_runtime_protocol_version ||
                !beet_ble_parse_u32(cursor, &request->runtime_protocol_version)) {
                return false;
            }
            request->has_runtime_protocol_version = true;
        } else if (strcmp(key, "asset_id") == 0 || strcmp(key, "ai") == 0) {
            if (seen_asset_id ||
                !beet_ble_parse_string(cursor, request->asset_id, sizeof(request->asset_id))) {
                return false;
            }
            seen_asset_id = true;
        } else if (strcmp(key, "image_kind") == 0 || strcmp(key, "ik") == 0) {
            if (seen_image_kind ||
                !beet_ble_parse_string(cursor, parsed_string, sizeof(parsed_string))) {
                return false;
            }
            request->image_kind = beet_maintenance_image_kind_from_name(parsed_string);
            if (request->image_kind == BEET_MAINTENANCE_IMAGE_KIND_UNKNOWN) {
                return false;
            }
            seen_image_kind = true;
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

    return seen_firmware_version &&
        seen_build_label &&
        seen_image_size &&
        seen_image_sha256 &&
        seen_product_id &&
        seen_hardware_revs &&
        seen_asset_id &&
        seen_image_kind &&
        request->hardware_rev_count > 0U;
}

static bool beet_ble_parse_valve_config_data(
    const char **cursor,
    beet_iface_command_request_t *request)
{
    bool seen_enabled = false;
    bool seen_min = false;
    bool seen_max = false;
    bool seen_open = false;
    bool seen_shut = false;
    bool seen_move = false;
    bool seen_settle = false;
    bool seen_hold = false;

    if (!beet_ble_consume_char(cursor, '{')) {
        return false;
    }

    while (true) {
        char key[32];
        uint16_t parsed_u16 = 0U;

        beet_ble_skip_ws(cursor);
        if (**cursor == '}') {
            ++(*cursor);
            break;
        }

        if (!beet_ble_parse_string(cursor, key, sizeof(key)) ||
            !beet_ble_consume_char(cursor, ':')) {
            return false;
        }

        if (strcmp(key, "valve_enabled") == 0 && !seen_enabled) {
            if (!beet_ble_parse_bool(cursor, &request->valve_enabled)) {
                return false;
            }
            seen_enabled = true;
        } else if (strcmp(key, "servo_min_pulse_us") == 0 && !seen_min) {
            if (!beet_ble_parse_u16(cursor, &parsed_u16)) {
                return false;
            }
            request->valve_servo_min_pulse_us = parsed_u16;
            seen_min = true;
        } else if (strcmp(key, "servo_max_pulse_us") == 0 && !seen_max) {
            if (!beet_ble_parse_u16(cursor, &parsed_u16)) {
                return false;
            }
            request->valve_servo_max_pulse_us = parsed_u16;
            seen_max = true;
        } else if (strcmp(key, "open_pulse_us") == 0 && !seen_open) {
            if (!beet_ble_parse_u16(cursor, &parsed_u16)) {
                return false;
            }
            request->valve_open_pulse_us = parsed_u16;
            seen_open = true;
        } else if (strcmp(key, "shut_pulse_us") == 0 && !seen_shut) {
            if (!beet_ble_parse_u16(cursor, &parsed_u16)) {
                return false;
            }
            request->valve_shut_pulse_us = parsed_u16;
            seen_shut = true;
        } else if (strcmp(key, "move_duration_ms") == 0 && !seen_move) {
            if (!beet_ble_parse_u16(cursor, &request->valve_move_duration_ms)) {
                return false;
            }
            seen_move = true;
        } else if (strcmp(key, "settle_delay_ms") == 0 && !seen_settle) {
            if (!beet_ble_parse_u16(cursor, &request->valve_settle_delay_ms)) {
                return false;
            }
            seen_settle = true;
        } else if (strcmp(key, "open_hold_ms") == 0 && !seen_hold) {
            if (!beet_ble_parse_u16(cursor, &request->valve_open_hold_ms)) {
                return false;
            }
            seen_hold = true;
        } else if (strcmp(key, "valve_enabled") == 0 ||
            strcmp(key, "servo_min_pulse_us") == 0 ||
            strcmp(key, "servo_max_pulse_us") == 0 ||
            strcmp(key, "open_pulse_us") == 0 ||
            strcmp(key, "shut_pulse_us") == 0 ||
            strcmp(key, "move_duration_ms") == 0 ||
            strcmp(key, "settle_delay_ms") == 0 ||
            strcmp(key, "open_hold_ms") == 0) {
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

    return seen_enabled && seen_min && seen_max && seen_open && seen_shut && seen_move && seen_settle && seen_hold;
}

static bool beet_ble_parse_valve_preview_data(
    const char **cursor,
    uint16_t *pulse_us)
{
    bool seen_pulse = false;

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

        if (strcmp(key, "pulse_us") == 0 && !seen_pulse) {
            if (!beet_ble_parse_u16(cursor, pulse_us)) {
                return false;
            }
            seen_pulse = true;
        } else if (strcmp(key, "pulse_us") == 0) {
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

    return seen_pulse;
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
            } else if (strcmp(cmd, "get_valve_config") == 0) {
                request->command = BEET_IFACE_COMMAND_GET_VALVE_CONFIG;
                if (!beet_ble_parse_empty_data(&cursor)) {
                    return false;
                }
            } else if (strcmp(cmd, "store_valve_config") == 0) {
                request->command = BEET_IFACE_COMMAND_STORE_VALVE_CONFIG;
                if (!beet_ble_parse_valve_config_data(&cursor, request)) {
                    return false;
                }
            } else if (strcmp(cmd, "open_valve") == 0) {
                request->command = BEET_IFACE_COMMAND_OPEN_VALVE;
                if (!beet_ble_parse_empty_data(&cursor)) {
                    return false;
                }
            } else if (strcmp(cmd, "close_valve") == 0) {
                request->command = BEET_IFACE_COMMAND_CLOSE_VALVE;
                if (!beet_ble_parse_empty_data(&cursor)) {
                    return false;
                }
            } else if (strcmp(cmd, "preview_valve_position") == 0) {
                request->command = BEET_IFACE_COMMAND_PREVIEW_VALVE_POSITION;
                if (!beet_ble_parse_valve_preview_data(&cursor, &request->valve_preview_pulse_us)) {
                    return false;
                }
            } else if (strcmp(cmd, "get_watering_interval") == 0) {
                request->command = BEET_IFACE_COMMAND_GET_WATERING_INTERVAL;
                if (!beet_ble_parse_empty_data(&cursor)) {
                    return false;
                }
            } else if (strcmp(cmd, "store_watering_interval") == 0) {
                request->command = BEET_IFACE_COMMAND_STORE_WATERING_INTERVAL;
                if (!beet_ble_parse_watering_interval_data(&cursor, &request->watering_interval_s)) {
                    return false;
                }
            } else if (strcmp(cmd, "reboot_controller") == 0) {
                request->command = BEET_IFACE_COMMAND_REBOOT_CONTROLLER;
                if (!beet_ble_parse_empty_data(&cursor)) {
                    return false;
                }
            } else if (strcmp(cmd, "factory_reset") == 0) {
                request->command = BEET_IFACE_COMMAND_FACTORY_RESET;
                if (!beet_ble_parse_empty_data(&cursor)) {
                    return false;
                }
            } else if (strcmp(cmd, "run_scheduler") == 0) {
                request->command = BEET_IFACE_COMMAND_RUN_SCHEDULER;
                if (!beet_ble_parse_empty_data(&cursor)) {
                    return false;
                }
            } else if (strcmp(cmd, "get_pair_wiring") == 0) {
                request->command = BEET_IFACE_COMMAND_GET_PAIR_WIRING;
                if (!beet_ble_parse_pair_data(&cursor, &request->pair_index)) {
                    return false;
                }
            } else if (strcmp(cmd, "get_max_active_pumps") == 0) {
                request->command = BEET_IFACE_COMMAND_GET_MAX_ACTIVE_PUMPS;
                if (!beet_ble_parse_empty_data(&cursor)) {
                    return false;
                }
            } else if (strcmp(cmd, "store_max_active_pumps") == 0) {
                request->command = BEET_IFACE_COMMAND_STORE_MAX_ACTIVE_PUMPS;
                if (!beet_ble_parse_max_active_pumps_data(&cursor, &request->max_active_pumps)) {
                    return false;
                }
            } else if (strcmp(cmd, "get_pair_names") == 0) {
                request->command = BEET_IFACE_COMMAND_GET_PAIR_NAMES;
                if (!beet_ble_parse_empty_data(&cursor)) {
                    return false;
                }
            } else if (strcmp(cmd, "store_pair_name") == 0) {
                request->command = BEET_IFACE_COMMAND_STORE_PAIR_NAME;
                if (!beet_ble_parse_pair_name_data(
                        &cursor,
                        &request->pair_index,
                        request->pair_name,
                        sizeof(request->pair_name))) {
                    return false;
                }
            } else if (strcmp(cmd, "get_pair_combined") == 0) {
                request->command = BEET_IFACE_COMMAND_GET_PAIR_COMBINED;
                if (!beet_ble_parse_pair_data(&cursor, &request->pair_index)) {
                    return false;
                }
            } else if (strcmp(cmd, "store_pair_combined") == 0) {
                request->command = BEET_IFACE_COMMAND_STORE_PAIR_COMBINED;
                if (!beet_ble_parse_combined_data(
                        &cursor,
                        &request->pair_index,
                        &request->combined_mask)) {
                    return false;
                }
            } else if (strcmp(cmd, "get_pair_config") == 0) {
                request->command = BEET_IFACE_COMMAND_GET_PAIR_CONFIG;
                if (!beet_ble_parse_pair_data(&cursor, &request->pair_index)) {
                    return false;
                }
            } else if (strcmp(cmd, "store_pair_config") == 0) {
                request->command = BEET_IFACE_COMMAND_STORE_PAIR_CONFIG;
                if (!beet_ble_parse_pair_config_data(
                        &cursor,
                        &request->pair_index,
                        &request->target_level,
                        &request->duration_multiplier)) {
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

bool beet_ble_parse_maintenance_request_json(
    const char *json,
    beet_maintenance_request_t *request)
{
    // WARNING: The maintenance JSON protocol is intended to remain stable
    // across app and firmware releases. Keep accepted command names, required
    // fields, and field meanings backward compatible for shipped versions.
    const char *cursor = json;
    char key[32];
    char cmd[32];
    bool seen_cmd = false;
    bool seen_data = false;

    if (json == NULL || request == NULL) {
        return false;
    }

    memset(request, 0, sizeof(*request));
    if (!beet_ble_consume_char(&cursor, '{')) {
        return false;
    }

    while (true) {
        beet_ble_skip_ws(&cursor);
        if (*cursor == '}') {
            ++cursor;
            break;
        }

        if (!beet_ble_parse_string(&cursor, key, sizeof(key)) ||
            !beet_ble_consume_char(&cursor, ':')) {
            return false;
        }

        if (strcmp(key, "cmd") == 0) {
            if (seen_cmd || !beet_ble_parse_string(&cursor, cmd, sizeof(cmd))) {
                return false;
            }
            seen_cmd = true;
        } else         if (strcmp(key, "data") == 0 || strcmp(key, "d") == 0) {
            if (seen_data || !seen_cmd) {
                return false;
            }
            if (strcmp(cmd, "query_status") == 0 ||
                strcmp(cmd, "abort_update") == 0 ||
                strcmp(cmd, "finish_update") == 0) {
                if (!beet_ble_parse_empty_data(&cursor)) {
                    return false;
                }
            } else if (strcmp(cmd, "begin_update") == 0) {
                if (!beet_ble_parse_begin_update_data(&cursor, &request->begin_update)) {
                    return false;
                }
            } else {
                return false;
            }
            seen_data = true;
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
    if (!seen_cmd || *cursor != '\0') {
        return false;
    }

    if (strcmp(cmd, "query_status") == 0) {
        request->command = BEET_MAINTENANCE_COMMAND_QUERY_STATUS;
    } else if (strcmp(cmd, "begin_update") == 0) {
        if (!seen_data) {
            return false;
        }
        request->command = BEET_MAINTENANCE_COMMAND_BEGIN_UPDATE;
    } else if (strcmp(cmd, "abort_update") == 0) {
        request->command = BEET_MAINTENANCE_COMMAND_ABORT_UPDATE;
    } else if (strcmp(cmd, "finish_update") == 0) {
        request->command = BEET_MAINTENANCE_COMMAND_FINISH_UPDATE;
    } else {
        return false;
    }

    return true;
}
