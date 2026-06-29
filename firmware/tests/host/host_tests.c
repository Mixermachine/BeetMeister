#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "beet_ble_codec.h"
#include "beet_ble_guard.h"
#include "esp_rom_crc.h"
#include "beet_event_ring.h"
#include "beet_iface.h"
#include "beet_maintenance.h"
#include "beet_types.h"

static int s_failures = 0;

#define TEST_ASSERT_TRUE(cond) do { \
    if (!(cond)) { \
        printf("FAIL %s:%d expected true: %s\n", __FILE__, __LINE__, #cond); \
        s_failures++; \
        return; \
    } \
} while (0)

#define TEST_ASSERT_FALSE(cond) TEST_ASSERT_TRUE(!(cond))

#define TEST_ASSERT_U32_EQ(expected, actual) do { \
    uint32_t actual_value__ = (uint32_t)(actual); \
    uint32_t expected_value__ = (uint32_t)(expected); \
    if (actual_value__ != expected_value__) { \
        printf("FAIL %s:%d expected %lu got %lu\n", __FILE__, __LINE__, \
            (unsigned long)expected_value__, (unsigned long)actual_value__); \
        s_failures++; \
        return; \
    } \
} while (0)

#define TEST_ASSERT_STR_EQ(expected, actual) do { \
    const char *actual_value__ = (actual); \
    const char *expected_value__ = (expected); \
    if (strcmp(expected_value__, actual_value__) != 0) { \
        printf("FAIL %s:%d expected \"%s\" got \"%s\"\n", __FILE__, __LINE__, \
            expected_value__, actual_value__); \
        s_failures++; \
        return; \
    } \
} while (0)

#define TEST_ASSERT_STR_CONTAINS(haystack, needle) do { \
    if (strstr((haystack), (needle)) == NULL) { \
        printf("FAIL %s:%d expected substring %s in %s\n", __FILE__, __LINE__, (needle), (haystack)); \
        s_failures++; \
        return; \
    } \
} while (0)

typedef void (*beet_test_fn_t)(void);

typedef struct {
    const char *name;
    beet_test_fn_t fn;
} beet_test_case_t;

static bool beet_extract_data_object(const char *json, char *out, size_t out_len)
{
    const char *data = strstr(json, "\"data\":");
    const char *start;
    size_t depth = 0U;
    bool in_string = false;
    bool escaped = false;

    if (data == NULL || out == NULL || out_len == 0U) {
        return false;
    }

    start = data + strlen("\"data\":");
    if (*start != '{') {
        return false;
    }

    for (const char *cursor = start; *cursor != '\0'; ++cursor) {
        char current = *cursor;

        if (in_string) {
            if (escaped) {
                escaped = false;
            } else if (current == '\\') {
                escaped = true;
            } else if (current == '"') {
                in_string = false;
            }
            continue;
        }

        if (current == '"') {
            in_string = true;
            continue;
        }
        if (current == '{') {
            depth++;
        } else if (current == '}') {
            depth--;
            if (depth == 0U) {
                size_t object_len = (size_t)(cursor - start + 1);
                if (object_len >= out_len) {
                    return false;
                }
                memcpy(out, start, object_len);
                out[object_len] = '\0';
                return true;
            }
        }
    }

    return false;
}

static uint16_t beet_compute_chunk_count_for_payload(
    size_t mtu_payload,
    uint32_t chunk_id,
    size_t b64_len)
{
    uint16_t chunk_count = 1U;

    while (true) {
        size_t cap = beet_ble_command_chunk_fragment_capacity(
            mtu_payload,
            chunk_id,
            (uint16_t)(chunk_count - 1U),
            chunk_count);
        size_t needed = 0U;

        if (cap == 0U) {
            return 0U;
        }

        needed = (b64_len + cap - 1U) / cap;
        if (needed == 0U || needed > 65535U) {
            return 0U;
        }
        if (needed <= chunk_count) {
            return (uint16_t)needed;
        }
        chunk_count = (uint16_t)needed;
    }
}

static beet_event_record_t beet_make_event(uint64_t seq_no, uint8_t pair_index, uint16_t duration_s)
{
    beet_event_record_t record;

    memset(&record, 0, sizeof(record));
    record.schema_version = BEET_EVENT_RECORD_VERSION;
    record.seq_no = seq_no;
    record.boot_id = 9U;
    record.pair_index = pair_index;
    record.trigger_source = BEET_RUN_SOURCE_MANUAL;
    record.moisture_before_pct = 15U;
    record.moisture_after_pct = 25U;
    record.sensor_before_mv = 2100U;
    record.sensor_after_mv = 1800U;
    record.requested_duration_s = duration_s;
    record.actual_duration_s = duration_s;
    record.stop_reason = BEET_STOP_REASON_COMPLETED;
    record.block_reason = BEET_BLOCK_REASON_NONE;
    record.battery_start_mv = 3320U;
    record.battery_end_mv = 3290U;
    record.crc32 = beet_event_crc32(&record);
    return record;
}

static beet_system_event_record_t beet_make_system_event(uint64_t seq_no, beet_system_event_type_t type)
{
    beet_system_event_record_t record;

    memset(&record, 0, sizeof(record));
    record.schema_version = BEET_SYSTEM_EVENT_RECORD_VERSION;
    record.seq_no = seq_no;
    record.boot_id = 9U;
    record.event_type = (uint8_t)type;
    record.reason = 22U;
    record.occurred_uptime_s = 123U;
    record.battery_mv = 3340U;
    record.peer_addr[0] = 0xFFU;
    record.peer_addr[1] = 0xEEU;
    record.peer_addr[2] = 0xDDU;
    record.peer_addr[3] = 0xCCU;
    record.peer_addr[4] = 0xBBU;
    record.peer_addr[5] = 0xAAU;
    record.peer_addr_type = 1U;
    record.known_peer = 1U;
    record.crc32 = beet_system_event_crc32(&record);
    return record;
}

static void test_moisture_conversion_boundaries(void)
{
    TEST_ASSERT_U32_EQ(0U, beet_moisture_pct_from_mv(2765U, 900U, 2865U));
    TEST_ASSERT_U32_EQ(0U, beet_moisture_pct_from_mv(2765U, 900U, 2765U));
    TEST_ASSERT_U32_EQ(100U, beet_moisture_pct_from_mv(2765U, 900U, 900U));
    TEST_ASSERT_U32_EQ(100U, beet_moisture_pct_from_mv(2765U, 900U, 850U));
    TEST_ASSERT_U32_EQ(50U, beet_moisture_pct_from_mv(2765U, 900U, 1832U));
}

static void test_sensor_plausibility_and_supply_compensation(void)
{
    TEST_ASSERT_U32_EQ(2761U, beet_correct_moisture_sensor_mv(2642U, 3213U));
    TEST_ASSERT_U32_EQ(2765U, beet_correct_moisture_sensor_mv(2765U, 3413U));
    TEST_ASSERT_TRUE(beet_is_sensor_mv_plausible(2865U, 2765U, 900U));
    TEST_ASSERT_TRUE(beet_is_sensor_mv_plausible(500U, 2765U, 900U));
    TEST_ASSERT_FALSE(beet_is_sensor_mv_plausible(2966U, 2765U, 900U));
    TEST_ASSERT_FALSE(beet_is_sensor_mv_plausible(499U, 2765U, 900U));
}

static void test_sensor_refresh_policy(void)
{
    TEST_ASSERT_U32_EQ(30000U, beet_sensor_refresh_interval_ms(false, false));
    TEST_ASSERT_U32_EQ(5000U, beet_sensor_refresh_interval_ms(false, true));
    TEST_ASSERT_U32_EQ(1000U, beet_sensor_refresh_interval_ms(true, false));
    TEST_ASSERT_U32_EQ(1000U, beet_sensor_refresh_interval_ms(true, true));
}

static void test_default_snapshot_is_disabled(void)
{
    beet_pair_runtime_snapshot_t snapshot;

    memset(&snapshot, 0xFFU, sizeof(snapshot));
    beet_default_snapshot(1U, &snapshot);
    TEST_ASSERT_U32_EQ(1U, snapshot.pair_index);
    TEST_ASSERT_FALSE(snapshot.enabled);
    TEST_ASSERT_U32_EQ(BEET_PAIR_STATE_DISABLED, snapshot.pair_state);
    TEST_ASSERT_U32_EQ(BEET_BLOCK_REASON_NONE, snapshot.block_reason);
    TEST_ASSERT_U32_EQ(0U, snapshot.active_run_id);
    TEST_ASSERT_U32_EQ(BEET_RUN_SOURCE_NONE, snapshot.active_run_source);

    memset(&snapshot, 0xFFU, sizeof(snapshot));
    beet_default_snapshot(8U, &snapshot);
    TEST_ASSERT_U32_EQ(8U, snapshot.pair_index);
    TEST_ASSERT_FALSE(snapshot.enabled);
    TEST_ASSERT_U32_EQ(BEET_PAIR_STATE_DISABLED, snapshot.pair_state);
    TEST_ASSERT_U32_EQ(BEET_BLOCK_REASON_NONE, snapshot.block_reason);
    TEST_ASSERT_U32_EQ(0U, snapshot.active_run_id);
    TEST_ASSERT_U32_EQ(BEET_RUN_SOURCE_NONE, snapshot.active_run_source);
}

static void test_default_snapshot_avoids_legacy_migration(void)
{
    beet_pair_runtime_snapshot_t snapshot;

    beet_default_snapshot(1U, &snapshot);
    // The migration in beet_restore_snapshots() upgrades legacy snapshots by checking
    // `!enabled && pair_state != DISABLED`. The new default must set pair_state = DISABLED
    // so this condition evaluates to false on a fresh device, preventing accidental
    // re-enabling of pairs after factory reset.
    TEST_ASSERT_FALSE(!snapshot.enabled && snapshot.pair_state != BEET_PAIR_STATE_DISABLED);
}

static void test_duration_lookup_boundaries(void)
{
    const uint8_t moisture_values[] = {100U, 81U, 80U, 79U, 70U, 69U, 60U, 59U, 50U, 49U, 0U};
    const uint16_t expected[] = {0U, 0U, 10U, 60U, 60U, 120U, 120U, 180U, 180U, 240U, 240U};

    for (size_t i = 0; i < sizeof(moisture_values) / sizeof(moisture_values[0]); ++i) {
        TEST_ASSERT_U32_EQ(expected[i], beet_automatic_duration_s(moisture_values[i]));
    }

    TEST_ASSERT_U32_EQ(10U, beet_manual_duration_s(100U));
    TEST_ASSERT_U32_EQ(240U, beet_manual_duration_s(10U));
    TEST_ASSERT_TRUE(beet_is_valid_manual_duration_s(1U));
    TEST_ASSERT_TRUE(beet_is_valid_manual_duration_s(BEET_MAX_MANUAL_DURATION_S));
    TEST_ASSERT_FALSE(beet_is_valid_manual_duration_s(0U));
    TEST_ASSERT_FALSE(beet_is_valid_manual_duration_s((uint16_t)(BEET_MAX_MANUAL_DURATION_S + 1U)));
}

static void test_schema_defaults(void)
{
    beet_app_config_t config;
    beet_power_runtime_state_t power_state;

    beet_default_app_config(&config);
    beet_default_power_runtime_state(&power_state);

    TEST_ASSERT_U32_EQ(3U, BEET_APP_CONFIG_SCHEMA_VERSION);
    TEST_ASSERT_U32_EQ(1U, BEET_POWER_RUNTIME_STATE_SCHEMA_VERSION);
    TEST_ASSERT_U32_EQ(BEET_APP_CONFIG_SCHEMA_VERSION, config.schema_version);
    TEST_ASSERT_U32_EQ(BEET_POWER_RUNTIME_STATE_SCHEMA_VERSION, power_state.schema_version);
    TEST_ASSERT_TRUE(config.schema_version != power_state.schema_version);
}

static void test_sanity_threshold_and_battery_classification(void)
{
    TEST_ASSERT_TRUE(beet_sanity_check_passed(10U, 14U));
    TEST_ASSERT_TRUE(beet_sanity_check_passed(40U, 44U));
    TEST_ASSERT_FALSE(beet_sanity_check_passed(40U, 43U));
    TEST_ASSERT_FALSE(beet_sanity_check_passed(40U, 42U));

    TEST_ASSERT_U32_EQ(BEET_BATTERY_STATE_OTA_IN_PROGRESS,
        beet_classify_battery_state(3400U, true, false, 0U));
    TEST_ASSERT_U32_EQ(BEET_BATTERY_STATE_DEEP_LOW_BATTERY,
        beet_classify_battery_state(3200U, false, false, 0U));
    TEST_ASSERT_U32_EQ(BEET_BATTERY_STATE_IDLE_LOW_POWER,
        beet_classify_battery_state(3299U, false, false, 300U));
    TEST_ASSERT_U32_EQ(BEET_BATTERY_STATE_ACTIVE,
        beet_classify_battery_state(3299U, false, true, 300U));
    TEST_ASSERT_U32_EQ(BEET_BATTERY_STATE_ACTIVE,
        beet_classify_battery_state(3300U, false, false, 300U));
}

static void test_battery_percentage_and_recovery_cadence(void)
{
    TEST_ASSERT_U32_EQ(100U, beet_battery_pct_from_mv(3600U));
    TEST_ASSERT_U32_EQ(70U, beet_battery_pct_from_mv(3400U));
    TEST_ASSERT_U32_EQ(10U, beet_battery_pct_from_mv(3220U));
    TEST_ASSERT_U32_EQ(0U, beet_battery_pct_from_mv(3219U));

    TEST_ASSERT_U32_EQ(3600U, beet_deep_low_recovery_interval_s(0U));
    TEST_ASSERT_U32_EQ(3600U, beet_deep_low_recovery_interval_s(2U));
    TEST_ASSERT_U32_EQ(7200U, beet_deep_low_recovery_interval_s(3U));
    TEST_ASSERT_U32_EQ(7200U, beet_deep_low_recovery_interval_s(8U));
    TEST_ASSERT_U32_EQ(14400U, beet_deep_low_recovery_interval_s(9U));
}

static void test_event_record_validation(void)
{
    beet_event_record_t record = beet_make_event(7U, 3U, 120U);

    TEST_ASSERT_TRUE(beet_validate_event_record(&record));
    record.stop_reason = 200U;
    TEST_ASSERT_FALSE(beet_validate_event_record(&record));

    record = beet_make_event(7U, 3U, 120U);
    record.crc32 ^= 0x1234U;
    TEST_ASSERT_FALSE(beet_validate_event_record(&record));

    record = beet_make_event(7U, 3U, 120U);
    record.block_reason = BEET_BLOCK_REASON_LOW_BATTERY_ABORT;
    record.crc32 = beet_event_crc32(&record);
    TEST_ASSERT_TRUE(beet_validate_event_record(&record));

    record = beet_make_event(8U, 2U, 90U);
    record.boot_id = 0U;
    TEST_ASSERT_FALSE(beet_validate_event_record(&record));

    beet_system_event_record_t system = beet_make_system_event(9U, BEET_SYSTEM_EVENT_BLE_CONNECT);
    TEST_ASSERT_TRUE(beet_validate_system_event_record(&system));
    system.event_type = 250U;
    system.crc32 = beet_system_event_crc32(&system);
    TEST_ASSERT_FALSE(beet_validate_system_event_record(&system));
}

static void test_valve_system_event_types(void)
{
    TEST_ASSERT_TRUE(beet_is_valid_system_event_type(BEET_SYSTEM_EVENT_VALVE_OPENED));
    TEST_ASSERT_TRUE(beet_is_valid_system_event_type(BEET_SYSTEM_EVENT_VALVE_CLOSED));
    TEST_ASSERT_TRUE(beet_is_valid_system_event_type(BEET_SYSTEM_EVENT_UPDATE_STARTED));
    TEST_ASSERT_TRUE(beet_is_valid_system_event_type(BEET_SYSTEM_EVENT_UPDATE_FAILED));
    TEST_ASSERT_STR_EQ("VALVE_OPENED", beet_system_event_type_name(BEET_SYSTEM_EVENT_VALVE_OPENED));
    TEST_ASSERT_STR_EQ("VALVE_CLOSED", beet_system_event_type_name(BEET_SYSTEM_EVENT_VALVE_CLOSED));
    TEST_ASSERT_STR_EQ("UPDATE_STARTED", beet_system_event_type_name(BEET_SYSTEM_EVENT_UPDATE_STARTED));
    TEST_ASSERT_STR_EQ("UPDATE_FAILED", beet_system_event_type_name(BEET_SYSTEM_EVENT_UPDATE_FAILED));
}

static void test_event_ring_reconstruction_and_summary(void)
{
    beet_event_ring_state_t state;
    uint16_t event_count = 0U;
    uint32_t totals[BEET_PAIR_COUNT];
    beet_event_record_t a = beet_make_event(4U, 1U, 30U);
    beet_event_record_t b = beet_make_event(7U, 3U, 50U);
    beet_event_record_t unresolved_current = beet_make_event(8U, 4U, 15U);
    beet_event_record_t c = beet_make_event(999U, 2U, 20U);
    beet_event_record_t test = beet_make_event(10U, 2U, 10U);
    beet_event_record_t unresolved_old = beet_make_event(11U, 5U, 25U);
    beet_event_record_t bad = beet_make_event(12U, 6U, 15U);

    test.trigger_source = BEET_RUN_SOURCE_TEST;
    test.crc32 = beet_event_crc32(&test);
    unresolved_current.crc32 = beet_event_crc32(&unresolved_current);
    unresolved_old.boot_id = 3U;
    unresolved_old.crc32 = beet_event_crc32(&unresolved_old);
    bad.crc32 ^= 1U;

    beet_event_ring_reset(&state);
    TEST_ASSERT_U32_EQ(1U, state.next_write_slot);

    beet_event_ring_accept_record(&state, &a);
    beet_event_ring_accept_record(&state, &bad);
    beet_event_ring_accept_record(&state, &b);
    beet_event_ring_accept_record(&state, &unresolved_current);
    beet_event_ring_accept_record(&state, &unresolved_old);
    beet_event_ring_finalize(&state);
    TEST_ASSERT_TRUE(state.has_valid_records);
    TEST_ASSERT_U32_EQ(11U, state.highest_valid_seq_no);
    TEST_ASSERT_U32_EQ(12U, state.next_write_slot);

    beet_event_ring_reset(&state);
    beet_event_ring_accept_record(&state, &c);
    beet_event_ring_finalize(&state);
    TEST_ASSERT_U32_EQ(999U, state.highest_valid_seq_no);
    TEST_ASSERT_U32_EQ(0U, state.next_write_slot);

    memset(totals, 0, sizeof(totals));
    beet_event_ring_accumulate_summary(&a, &event_count, totals);
    beet_event_ring_accumulate_summary(&bad, &event_count, totals);
    beet_event_ring_accumulate_summary(&b, &event_count, totals);
    if (beet_event_record_is_visible(&unresolved_current, 9U)) {
        beet_event_ring_accumulate_summary(&unresolved_current, &event_count, totals);
    }
    TEST_ASSERT_TRUE(beet_event_record_is_visible(&unresolved_old, 9U));
    beet_event_ring_accumulate_summary(&test, &event_count, totals);
    TEST_ASSERT_U32_EQ(4U, event_count);
    TEST_ASSERT_U32_EQ(30U, totals[0]);
    TEST_ASSERT_U32_EQ(0U, totals[1]);
    TEST_ASSERT_U32_EQ(50U, totals[2]);
    TEST_ASSERT_U32_EQ(15U, totals[3]);
}

static void test_ble_command_parsing(void)
{
    beet_iface_command_request_t request;

    TEST_ASSERT_TRUE(beet_ble_parse_command_json(
        "{\"cmd\":\"manual_start\",\"data\":{\"pair\":3,\"duration_s\":1200}}", &request));
    TEST_ASSERT_U32_EQ(BEET_IFACE_COMMAND_MANUAL_START, request.command);
    TEST_ASSERT_U32_EQ(3U, request.pair_index);
    TEST_ASSERT_TRUE(request.has_duration_s);
    TEST_ASSERT_U32_EQ(1200U, request.duration_s);

    TEST_ASSERT_TRUE(beet_ble_parse_command_json(
        "{\"cmd\":\"store_calibration\",\"data\":{\"pair\":2,\"dry_mv\":2765,\"wet_mv\":900}}", &request));
    TEST_ASSERT_U32_EQ(BEET_IFACE_COMMAND_STORE_CALIBRATION, request.command);
    TEST_ASSERT_U32_EQ(2U, request.pair_index);
    TEST_ASSERT_U32_EQ(2765U, request.dry_mv);
    TEST_ASSERT_U32_EQ(900U, request.wet_mv);

    TEST_ASSERT_TRUE(beet_ble_parse_command_json(
        "{\"cmd\":\"get_event\",\"data\":{\"seq_no\":42}}", &request));
    TEST_ASSERT_U32_EQ(BEET_IFACE_COMMAND_GET_EVENT, request.command);
    TEST_ASSERT_U32_EQ(42U, request.seq_no);

    TEST_ASSERT_TRUE(beet_ble_parse_command_json(
        "{\"cmd\":\"get_system_history_summary\",\"data\":{}}", &request));
    TEST_ASSERT_U32_EQ(BEET_IFACE_COMMAND_GET_SYSTEM_HISTORY_SUMMARY, request.command);

    TEST_ASSERT_TRUE(beet_ble_parse_command_json(
        "{\"cmd\":\"get_system_event\",\"data\":{\"seq_no\":43}}", &request));
    TEST_ASSERT_U32_EQ(BEET_IFACE_COMMAND_GET_SYSTEM_EVENT, request.command);
    TEST_ASSERT_U32_EQ(43U, request.seq_no);

    TEST_ASSERT_TRUE(beet_ble_parse_command_json(
        "{\"cmd\":\"set_time\",\"data\":{\"unix_s\":1714412345}}", &request));
    TEST_ASSERT_U32_EQ(BEET_IFACE_COMMAND_SET_TIME, request.command);
    TEST_ASSERT_U32_EQ(1714412345U, request.unix_s);

    TEST_ASSERT_TRUE(beet_ble_parse_command_json(
        "{\"cmd\":\"relay_test_start\",\"data\":{\"pair\":4}}", &request));
    TEST_ASSERT_U32_EQ(BEET_IFACE_COMMAND_RELAY_TEST_START, request.command);
    TEST_ASSERT_U32_EQ(4U, request.pair_index);

    TEST_ASSERT_TRUE(beet_ble_parse_command_json(
        "{\"cmd\":\"relay_test_stop\",\"data\":{\"pair\":4}}", &request));
    TEST_ASSERT_U32_EQ(BEET_IFACE_COMMAND_RELAY_TEST_STOP, request.command);
    TEST_ASSERT_U32_EQ(4U, request.pair_index);

    TEST_ASSERT_TRUE(beet_ble_parse_command_json(
        "{\"cmd\":\"moisture_test_start\",\"data\":{\"pair\":5}}", &request));
    TEST_ASSERT_U32_EQ(BEET_IFACE_COMMAND_MOISTURE_TEST_START, request.command);
    TEST_ASSERT_U32_EQ(5U, request.pair_index);

    TEST_ASSERT_TRUE(beet_ble_parse_command_json(
        "{\"cmd\":\"clear_ble_bonds\",\"data\":{}}", &request));
    TEST_ASSERT_U32_EQ(BEET_IFACE_COMMAND_CLEAR_BLE_BONDS, request.command);

    TEST_ASSERT_TRUE(beet_ble_parse_command_json(
        "{\"cmd\":\"get_valve_config\",\"data\":{}}", &request));
    TEST_ASSERT_U32_EQ(BEET_IFACE_COMMAND_GET_VALVE_CONFIG, request.command);

    TEST_ASSERT_TRUE(beet_ble_parse_command_json(
        "{\"cmd\":\"store_valve_config\",\"data\":{\"valve_enabled\":1,\"servo_min_pulse_us\":700,"
        "\"servo_max_pulse_us\":2400,\"open_pulse_us\":850,\"shut_pulse_us\":2050,\"move_duration_ms\":700,"
        "\"settle_delay_ms\":200,\"open_hold_ms\":1500}}", &request));
    TEST_ASSERT_U32_EQ(BEET_IFACE_COMMAND_STORE_VALVE_CONFIG, request.command);
    TEST_ASSERT_TRUE(request.valve_enabled);
    TEST_ASSERT_U32_EQ(700U, request.valve_servo_min_pulse_us);
    TEST_ASSERT_U32_EQ(2400U, request.valve_servo_max_pulse_us);
    TEST_ASSERT_U32_EQ(850U, request.valve_open_pulse_us);
    TEST_ASSERT_U32_EQ(2050U, request.valve_shut_pulse_us);
    TEST_ASSERT_U32_EQ(700U, request.valve_move_duration_ms);
    TEST_ASSERT_U32_EQ(200U, request.valve_settle_delay_ms);
    TEST_ASSERT_U32_EQ(1500U, request.valve_open_hold_ms);

    TEST_ASSERT_TRUE(beet_ble_parse_command_json(
        "{\"cmd\":\"preview_valve_position\",\"data\":{\"pulse_us\":1600}}", &request));
    TEST_ASSERT_U32_EQ(BEET_IFACE_COMMAND_PREVIEW_VALVE_POSITION, request.command);
    TEST_ASSERT_U32_EQ(1600U, request.valve_preview_pulse_us);

    TEST_ASSERT_TRUE(beet_ble_parse_command_json(
        "{\"cmd\":\"get_watering_interval\",\"data\":{}}", &request));
    TEST_ASSERT_U32_EQ(BEET_IFACE_COMMAND_GET_WATERING_INTERVAL, request.command);

    TEST_ASSERT_TRUE(beet_ble_parse_command_json(
        "{\"cmd\":\"store_watering_interval\",\"data\":{\"watering_interval_s\":21600}}", &request));
    TEST_ASSERT_U32_EQ(BEET_IFACE_COMMAND_STORE_WATERING_INTERVAL, request.command);
    TEST_ASSERT_U32_EQ(21600U, request.watering_interval_s);

    TEST_ASSERT_TRUE(beet_ble_parse_command_json(
        "{\"cmd\":\"reboot_controller\",\"data\":{}}", &request));
    TEST_ASSERT_U32_EQ(BEET_IFACE_COMMAND_REBOOT_CONTROLLER, request.command);

    TEST_ASSERT_TRUE(beet_ble_parse_command_json(
        "{\"cmd\":\"factory_reset\",\"data\":{}}", &request));
    TEST_ASSERT_U32_EQ(BEET_IFACE_COMMAND_FACTORY_RESET, request.command);

    TEST_ASSERT_TRUE(beet_ble_parse_command_json(
        "{\"cmd\":\"get_pair_wiring\",\"data\":{\"pair\":6}}", &request));
    TEST_ASSERT_U32_EQ(BEET_IFACE_COMMAND_GET_PAIR_WIRING, request.command);
    TEST_ASSERT_U32_EQ(6U, request.pair_index);

    TEST_ASSERT_TRUE(beet_ble_parse_command_json(
        "{\"cmd\":\"get_max_active_pumps\",\"data\":{}}", &request));
    TEST_ASSERT_U32_EQ(BEET_IFACE_COMMAND_GET_MAX_ACTIVE_PUMPS, request.command);

    TEST_ASSERT_TRUE(beet_ble_parse_command_json(
        "{\"cmd\":\"store_max_active_pumps\",\"data\":{\"max\":5}}", &request));
    TEST_ASSERT_U32_EQ(BEET_IFACE_COMMAND_STORE_MAX_ACTIVE_PUMPS, request.command);
    TEST_ASSERT_U32_EQ(5U, request.max_active_pumps);

    TEST_ASSERT_TRUE(beet_ble_parse_command_json(
        "{\"cmd\":\"get_pair_names\",\"data\":{}}", &request));
    TEST_ASSERT_U32_EQ(BEET_IFACE_COMMAND_GET_PAIR_NAMES, request.command);

    TEST_ASSERT_TRUE(beet_ble_parse_command_json(
        "{\"cmd\":\"store_pair_name\",\"data\":{\"pair\":3,\"name\":\"Front Garden\"}}", &request));
    TEST_ASSERT_U32_EQ(BEET_IFACE_COMMAND_STORE_PAIR_NAME, request.command);
    TEST_ASSERT_U32_EQ(3U, request.pair_index);
    TEST_ASSERT_STR_EQ("Front Garden", request.pair_name);

    TEST_ASSERT_TRUE(beet_ble_parse_command_json(
        "{\"cmd\":\"store_pair_name\",\"data\":{\"pair\":7,\"name\":\"\"}}", &request));
    TEST_ASSERT_U32_EQ(BEET_IFACE_COMMAND_STORE_PAIR_NAME, request.command);
    TEST_ASSERT_U32_EQ(7U, request.pair_index);
    TEST_ASSERT_STR_EQ("", request.pair_name);

    /* 15 chars: maximum allowed, must parse */
    TEST_ASSERT_TRUE(beet_ble_parse_command_json(
        "{\"cmd\":\"store_pair_name\",\"data\":{\"pair\":1,\"name\":\"aaaaaaaaaaaaaaa\"}}",
        &request));
    TEST_ASSERT_U32_EQ(BEET_IFACE_COMMAND_STORE_PAIR_NAME, request.command);
    TEST_ASSERT_U32_EQ(1U, request.pair_index);
    TEST_ASSERT_STR_EQ("aaaaaaaaaaaaaaa", request.pair_name);

    /* 16 chars: parser must reject (exceeds max name length) */
    TEST_ASSERT_FALSE(beet_ble_parse_command_json(
        "{\"cmd\":\"store_pair_name\",\"data\":{\"pair\":1,\"name\":\"aaaaaaaaaaaaaaaa\"}}",
        &request));

    /* Missing pair must reject */
    TEST_ASSERT_FALSE(beet_ble_parse_command_json(
        "{\"cmd\":\"store_pair_name\",\"data\":{\"name\":\"x\"}}", &request));

    /* Missing name must reject */
    TEST_ASSERT_FALSE(beet_ble_parse_command_json(
        "{\"cmd\":\"store_pair_name\",\"data\":{\"pair\":2}}", &request));

    TEST_ASSERT_FALSE(beet_ble_parse_command_json(
        "{\"cmd\":\"store_max_active_pumps\",\"data\":{\"max\":999}}", &request));

    TEST_ASSERT_FALSE(beet_ble_parse_command_json(
        "{\"cmd\":\"start_ota\",\"data\":{}}", &request));
    TEST_ASSERT_FALSE(beet_ble_parse_command_json(
        "{\"cmd\":\"manual_start\",\"data\":{\"duration_s\":1200}}", &request));
    TEST_ASSERT_FALSE(beet_ble_parse_command_json(
        "{\"cmd\":\"get_watering_history_summary\",\"data\":{}}", &request));
    TEST_ASSERT_FALSE(beet_ble_parse_command_json(
        "{\"cmd\":\"get_watering_event\",\"data\":{\"seq_no\":42}}", &request));
    TEST_ASSERT_TRUE(beet_ble_parse_command_json(
        "{\"cmd\":\"manual_start\",\"data\":{\"pair\":3,\"duration_s\":10},\"x\":1}", &request));
    TEST_ASSERT_U32_EQ(BEET_IFACE_COMMAND_MANUAL_START, request.command);
    TEST_ASSERT_U32_EQ(3U, request.pair_index);
    TEST_ASSERT_TRUE(request.has_duration_s);
    TEST_ASSERT_U32_EQ(10U, request.duration_s);
    TEST_ASSERT_TRUE(beet_ble_parse_command_json(
        "{\"cmd\":\"manual_start\",\"data\":{\"pair\":3,\"duration_s\":10,\"future\":{}}}", &request));
    TEST_ASSERT_U32_EQ(BEET_IFACE_COMMAND_MANUAL_START, request.command);
    TEST_ASSERT_U32_EQ(3U, request.pair_index);
    TEST_ASSERT_TRUE(request.has_duration_s);
    TEST_ASSERT_U32_EQ(10U, request.duration_s);
    TEST_ASSERT_FALSE(beet_ble_parse_command_json(
        "{\"cmd\":\"manual_start\",\"data\":{\"pair\":3,\"duration_s\":10},\"data\":{}}", &request));
    TEST_ASSERT_FALSE(beet_ble_parse_command_json(
        "{\"cmd\":\"manual_start\",\"data\":{\"pair\":3,\"pair\":4}}", &request));
    TEST_ASSERT_FALSE(beet_ble_parse_command_json(
        "{\"cmd\":\"manual_start\",\"data\":{\"pair\":3,\"duration_s\":10}} trailing", &request));
    TEST_ASSERT_FALSE(beet_ble_parse_command_json(
        "{\"cmd\":\"unknown\",\"data\":{\"pair\":1}}", &request));
}

static void test_ble_rate_guard_windowing(void)
{
    beet_ble_rate_guard_t guard;

    beet_ble_rate_guard_init(&guard, 1000000LL, 2U);
    TEST_ASSERT_TRUE(beet_ble_rate_guard_allow(&guard, 1000LL));
    TEST_ASSERT_TRUE(beet_ble_rate_guard_allow(&guard, 2000LL));
    TEST_ASSERT_FALSE(beet_ble_rate_guard_allow(&guard, 3000LL));
    TEST_ASSERT_TRUE(beet_ble_rate_guard_allow(&guard, 1003000LL));
}

static bool test_ble_allow_for_command(
    beet_iface_command_t command,
    int64_t now_us,
    beet_ble_rate_guard_t *real_guard,
    beet_ble_rate_guard_t *sync_guard)
{
    beet_ble_command_lane_t lane = beet_ble_classify_command_lane(command);
    beet_ble_rate_guard_t *guard = (lane == BEET_BLE_COMMAND_LANE_SYNC_READ) ? sync_guard : real_guard;
    return beet_ble_rate_guard_allow(guard, now_us);
}

static void test_ble_command_lane_split(void)
{
    beet_ble_rate_guard_t real_guard;
    beet_ble_rate_guard_t sync_guard;

    beet_ble_rate_guard_init(&real_guard, 1000000LL, 4U);
    beet_ble_rate_guard_init(&sync_guard, 1000000LL, 12U);

    TEST_ASSERT_U32_EQ(BEET_BLE_COMMAND_LANE_SYNC_READ, beet_ble_classify_command_lane(BEET_IFACE_COMMAND_GET_SYSTEM_EVENT));
    TEST_ASSERT_U32_EQ(BEET_BLE_COMMAND_LANE_SYNC_READ, beet_ble_classify_command_lane(BEET_IFACE_COMMAND_GET_HISTORY_SUMMARY));
    TEST_ASSERT_U32_EQ(BEET_BLE_COMMAND_LANE_REAL, beet_ble_classify_command_lane(BEET_IFACE_COMMAND_MANUAL_STOP));
    TEST_ASSERT_U32_EQ(BEET_BLE_COMMAND_LANE_REAL, beet_ble_classify_command_lane(BEET_IFACE_COMMAND_GET_CALIBRATION));
    TEST_ASSERT_U32_EQ(BEET_BLE_COMMAND_LANE_REAL, beet_ble_classify_command_lane(BEET_IFACE_COMMAND_GET_PAIR_NAMES));
    TEST_ASSERT_U32_EQ(BEET_BLE_COMMAND_LANE_REAL, beet_ble_classify_command_lane(BEET_IFACE_COMMAND_STORE_PAIR_NAME));
    TEST_ASSERT_STR_EQ("sync_read", beet_ble_command_lane_name(BEET_BLE_COMMAND_LANE_SYNC_READ));
    TEST_ASSERT_STR_EQ("real", beet_ble_command_lane_name(BEET_BLE_COMMAND_LANE_REAL));

    for (uint8_t i = 0U; i < 12U; ++i) {
        TEST_ASSERT_TRUE(test_ble_allow_for_command(
            BEET_IFACE_COMMAND_GET_SYSTEM_EVENT,
            1000LL + i,
            &real_guard,
            &sync_guard));
    }
    TEST_ASSERT_FALSE(test_ble_allow_for_command(
        BEET_IFACE_COMMAND_GET_SYSTEM_EVENT,
        2000LL,
        &real_guard,
        &sync_guard));

    TEST_ASSERT_TRUE(test_ble_allow_for_command(
        BEET_IFACE_COMMAND_MANUAL_STOP,
        3000LL,
        &real_guard,
        &sync_guard));

    TEST_ASSERT_TRUE(test_ble_allow_for_command(
        BEET_IFACE_COMMAND_GET_CALIBRATION,
        4000LL,
        &real_guard,
        &sync_guard));
    TEST_ASSERT_TRUE(test_ble_allow_for_command(
        BEET_IFACE_COMMAND_GET_CALIBRATION,
        5000LL,
        &real_guard,
        &sync_guard));
    TEST_ASSERT_TRUE(test_ble_allow_for_command(
        BEET_IFACE_COMMAND_GET_CALIBRATION,
        6000LL,
        &real_guard,
        &sync_guard));
    TEST_ASSERT_FALSE(test_ble_allow_for_command(
        BEET_IFACE_COMMAND_GET_CALIBRATION,
        7000LL,
        &real_guard,
        &sync_guard));
    TEST_ASSERT_TRUE(test_ble_allow_for_command(
        BEET_IFACE_COMMAND_GET_CALIBRATION,
        1007000LL,
        &real_guard,
        &sync_guard));
}

static void test_ble_rejection_response_builder(void)
{
    beet_iface_command_request_t request;
    beet_iface_command_response_t response;

    memset(&request, 0, sizeof(request));
    memset(&response, 0, sizeof(response));
    request.command = BEET_IFACE_COMMAND_MANUAL_START;
    request.pair_index = 3U;

    beet_ble_build_rejection(&request, BEET_IFACE_REASON_BUSY, &response);
    TEST_ASSERT_U32_EQ(BEET_IFACE_COMMAND_MANUAL_START, response.command);
    TEST_ASSERT_U32_EQ(3U, response.pair_index);
    TEST_ASSERT_U32_EQ(BEET_IFACE_STATUS_REJECTED, response.status);
    TEST_ASSERT_U32_EQ(BEET_IFACE_REASON_BUSY, response.reason);

    beet_ble_build_rejection(&request, BEET_IFACE_REASON_RATE_LIMITED, &response);
    TEST_ASSERT_U32_EQ(BEET_IFACE_REASON_RATE_LIMITED, response.reason);
}

static void test_ble_json_formatting(void)
{
    char json[512];
    beet_iface_device_state_t device = {
        .battery_state = BEET_BATTERY_STATE_ACTIVE,
        .battery_mv = 3325U,
        .time_valid = false,
        .boot_id = 9U,
        .next_check_in_s = 71995U,
        .active_pumps = 0U,
        .wifi_connected = false,
        .mqtt_connected = false,
        .max_active_pumps = 3U,
    };
    beet_iface_pair_state_t pair = {
        .pair_index = 1U,
        .pair_state = BEET_PAIR_STATE_IDLE,
        .moisture_pct = 1U,
        .sensor_mv = 2745U,
        .pump_active = false,
        .remaining_s = 0U,
        .blocked = false,
        .block_reason = BEET_BLOCK_REASON_NONE,
        .source = BEET_RUN_SOURCE_NONE,
        .sensor_valid = true,
    };
    beet_iface_command_response_t response;

    TEST_ASSERT_TRUE(beet_ble_format_controller_info_json(
        json, sizeof(json), "beetmeister-01", "1.2.3", 2U, BEET_PAIR_COUNT) > 0);
    TEST_ASSERT_STR_EQ(
        "{\"type\":\"controller_info\",\"data\":{\"device_id\":\"beetmeister-01\",\"protocol_version\":2,\"firmware_version\":\"1.2.3\",\"pair_count\":8}}",
        json);

    TEST_ASSERT_TRUE(beet_ble_format_device_frame_json(json, sizeof(json), &device) > 0);
    TEST_ASSERT_STR_EQ(
        "{\"type\":\"device\",\"data\":{\"battery_state\":\"ACTIVE\",\"battery_mv\":3325,\"time_valid\":0,\"boot_id\":9,\"next_check_in_s\":71995,\"active_pumps\":0,\"max_active_pumps\":3,\"wifi_connected\":0,\"mqtt_connected\":0,\"uptime_s\":0,\"valve_enabled\":0,\"valve_state\":\"CLOSED\"}}",
        json);

    TEST_ASSERT_TRUE(beet_ble_format_pair_frame_json(json, sizeof(json), &pair) > 0);
    TEST_ASSERT_STR_EQ(
        "{\"type\":\"pair\",\"data\":{\"pair\":1,\"state\":\"IDLE\",\"moisture_pct\":1,\"sensor_mv\":2745,\"blocked\":0,\"block_reason\":\"NONE\",\"remaining_s\":0,\"source\":\"NONE\",\"enabled\":0,\"sensor_valid\":1}}",
        json);

    memset(&response, 0, sizeof(response));
    response.command = BEET_IFACE_COMMAND_MANUAL_START;
    response.pair_index = 3U;
    response.status = BEET_IFACE_STATUS_ACCEPTED;
    response.reason = BEET_IFACE_REASON_SLOT_ALLOCATED;
    response.accepted_duration_s = 120U;
    TEST_ASSERT_TRUE(beet_ble_format_command_result_json(json, sizeof(json), &response) > 0);
    TEST_ASSERT_STR_EQ(
        "{\"cmd\":\"manual_start\",\"status\":\"accepted\",\"reason\":\"slot_allocated\",\"data\":{\"pair\":3,\"duration_s\":120}}",
        json);

    memset(&response, 0, sizeof(response));
    response.command = BEET_IFACE_COMMAND_RELAY_TEST_START;
    response.pair_index = 2U;
    response.status = BEET_IFACE_STATUS_ACCEPTED;
    response.reason = BEET_IFACE_REASON_RELAY_TEST_STARTED;
    TEST_ASSERT_TRUE(beet_ble_format_command_result_json(json, sizeof(json), &response) > 0);
    TEST_ASSERT_STR_EQ(
        "{\"cmd\":\"relay_test_start\",\"status\":\"accepted\",\"reason\":\"relay_test_started\",\"data\":{\"pair\":2}}",
        json);

    memset(&response, 0, sizeof(response));
    response.command = BEET_IFACE_COMMAND_MOISTURE_TEST_START;
    response.pair_index = 2U;
    response.status = BEET_IFACE_STATUS_ACCEPTED;
    response.reason = BEET_IFACE_REASON_MOISTURE_TEST_STARTED;
    response.accepted_duration_s = BEET_SANITY_CHECK_DURATION_S;
    TEST_ASSERT_TRUE(beet_ble_format_command_result_json(json, sizeof(json), &response) > 0);
    TEST_ASSERT_STR_EQ(
        "{\"cmd\":\"moisture_test_start\",\"status\":\"accepted\",\"reason\":\"moisture_test_started\",\"data\":{\"pair\":2,\"duration_s\":10}}",
        json);

    memset(&response, 0, sizeof(response));
    response.command = BEET_IFACE_COMMAND_GET_EVENT;
    response.status = BEET_IFACE_STATUS_ACCEPTED;
    response.reason = BEET_IFACE_REASON_NONE;
    response.has_event = true;
    response.event = beet_make_event(77U, 5U, 120U);
    response.event.trigger_source = 2U;
    response.event_started_unix_s = 0U;
    response.event_ended_unix_s = 0U;
    response.event.started_uptime_s = 12U;
    response.event.ended_uptime_s = 132U;
    response.event.crc32 = beet_event_crc32(&response.event);
    TEST_ASSERT_TRUE(beet_ble_format_command_result_json(json, sizeof(json), &response) > 0);
    TEST_ASSERT_STR_EQ(
        "{\"cmd\":\"get_event\",\"status\":\"accepted\",\"reason\":\"none\",\"data\":{\"seq\":77,\"pair\":5,"
        "\"boot_id\":9,\"src\":2,\"start\":0,\"end\":0,\"mb\":15,\"ma\":25,\"sb\":2100,\"sa\":1800,"
        "\"req\":120,\"act\":120,\"stop\":0,\"block\":0,\"bs\":3320,\"be\":3290,\"su\":12,\"eu\":132}}",
        json);

    memset(&response, 0, sizeof(response));
    response.command = BEET_IFACE_COMMAND_GET_SYSTEM_EVENT;
    response.status = BEET_IFACE_STATUS_ACCEPTED;
    response.reason = BEET_IFACE_REASON_NONE;
    response.has_system_event = true;
    response.system_event = beet_make_system_event(9U, BEET_SYSTEM_EVENT_BLE_CONNECT);
    response.system_event_unix_s = 0U;
    TEST_ASSERT_TRUE(beet_ble_format_command_result_json(json, sizeof(json), &response) > 0);
    TEST_ASSERT_STR_EQ(
        "{\"cmd\":\"get_system_event\",\"status\":\"accepted\",\"reason\":\"none\",\"data\":"
        "{\"seq\":9,\"event_type\":\"BLE_CONNECT\",\"reason\":22,\"boot_id\":9,\"uptime_s\":123,"
        "\"unix_s\":0,\"battery_mv\":3340,\"peer_addr\":\"AA:BB:CC:DD:EE:FF\",\"peer_addr_type\":1,"
        "\"known_peer\":1,\"detail\":0}}",
        json);

    beet_system_event_record_t system = beet_make_system_event(9U, BEET_SYSTEM_EVENT_BLE_CONNECT);
    TEST_ASSERT_TRUE(beet_ble_format_system_event_frame_json(json, sizeof(json), &system, 0U) > 0);
    TEST_ASSERT_STR_EQ(
        "{\"type\":\"system_event\",\"data\":{\"seq\":9,\"event_type\":\"BLE_CONNECT\",\"reason\":22,\"boot_id\":9,\"uptime_s\":123,\"unix_s\":0,\"battery_mv\":3340,\"peer_addr\":\"AA:BB:CC:DD:EE:FF\",\"peer_addr_type\":1,\"known_peer\":1,\"detail\":0}}",
        json);

    memset(&response, 0, sizeof(response));
    response.command = BEET_IFACE_COMMAND_GET_VALVE_CONFIG;
    response.status = BEET_IFACE_STATUS_ACCEPTED;
    response.reason = BEET_IFACE_REASON_NONE;
    response.has_valve_config = true;
    response.valve_enabled = true;
    response.valve_servo_min_pulse_us = 700U;
    response.valve_servo_max_pulse_us = 2400U;
    response.valve_open_pulse_us = 880U;
    response.valve_shut_pulse_us = 2010U;
    response.valve_move_duration_ms = 700U;
    response.valve_settle_delay_ms = 200U;
    response.valve_open_hold_ms = 1500U;
    TEST_ASSERT_TRUE(beet_ble_format_command_result_json(json, sizeof(json), &response) > 0);
    TEST_ASSERT_STR_EQ(
        "{\"cmd\":\"get_valve_config\",\"status\":\"accepted\",\"reason\":\"none\",\"data\":"
        "{\"valve_enabled\":1,\"servo_min_pulse_us\":700,\"servo_max_pulse_us\":2400,"
        "\"open_pulse_us\":880,\"shut_pulse_us\":2010,\"move_duration_ms\":700,"
        "\"settle_delay_ms\":200,\"open_hold_ms\":1500}}",
        json);

    memset(&response, 0, sizeof(response));
    response.command = BEET_IFACE_COMMAND_GET_WATERING_INTERVAL;
    response.status = BEET_IFACE_STATUS_ACCEPTED;
    response.reason = BEET_IFACE_REASON_NONE;
    response.has_watering_interval = true;
    response.watering_interval_s = 21600U;
    TEST_ASSERT_TRUE(beet_ble_format_command_result_json(json, sizeof(json), &response) > 0);
    TEST_ASSERT_STR_EQ(
        "{\"cmd\":\"get_watering_interval\",\"status\":\"accepted\",\"reason\":\"none\",\"data\":{\"watering_interval_s\":21600}}",
        json);

    memset(&response, 0, sizeof(response));
    response.command = BEET_IFACE_COMMAND_GET_PAIR_WIRING;
    response.pair_index = 6U;
    response.status = BEET_IFACE_STATUS_ACCEPTED;
    response.reason = BEET_IFACE_REASON_NONE;
    response.has_pair_wiring = true;
    response.moisture_gpio = 5;
    response.relay_gpio = 40;
    TEST_ASSERT_TRUE(beet_ble_format_command_result_json(json, sizeof(json), &response) > 0);
    TEST_ASSERT_STR_EQ(
        "{\"cmd\":\"get_pair_wiring\",\"status\":\"accepted\",\"reason\":\"none\",\"data\":{\"pair\":6,\"moisture_gpio\":5,\"relay_gpio\":40}}",
        json);

    memset(&response, 0, sizeof(response));
    response.command = BEET_IFACE_COMMAND_GET_MAX_ACTIVE_PUMPS;
    response.status = BEET_IFACE_STATUS_ACCEPTED;
    response.reason = BEET_IFACE_REASON_NONE;
    response.has_max_active_pumps = true;
    response.max_active_pumps = 3U;
    TEST_ASSERT_TRUE(beet_ble_format_command_result_json(json, sizeof(json), &response) > 0);
    TEST_ASSERT_STR_EQ(
        "{\"cmd\":\"get_max_active_pumps\",\"status\":\"accepted\",\"reason\":\"none\",\"data\":{\"max_active_pumps\":3}}",
        json);

    memset(&response, 0, sizeof(response));
    response.command = BEET_IFACE_COMMAND_STORE_MAX_ACTIVE_PUMPS;
    response.status = BEET_IFACE_STATUS_ACCEPTED;
    response.reason = BEET_IFACE_REASON_NONE;
    response.has_max_active_pumps = true;
    response.max_active_pumps = 8U;
    TEST_ASSERT_TRUE(beet_ble_format_command_result_json(json, sizeof(json), &response) > 0);
    TEST_ASSERT_STR_EQ(
        "{\"cmd\":\"store_max_active_pumps\",\"status\":\"accepted\",\"reason\":\"none\",\"data\":{\"max_active_pumps\":8}}",
        json);

    memset(&response, 0, sizeof(response));
    response.command = BEET_IFACE_COMMAND_GET_PAIR_NAMES;
    response.status = BEET_IFACE_STATUS_ACCEPTED;
    response.reason = BEET_IFACE_REASON_NONE;
    response.has_pair_names = true;
    snprintf(response.pair_names[0], sizeof(response.pair_names[0]), "%s", "Front Garden");
    snprintf(response.pair_names[1], sizeof(response.pair_names[1]), "%s", "Back Lawn");
    /* slots 2..6 left empty */
    snprintf(response.pair_names[7], sizeof(response.pair_names[7]), "%s", "Herb Bed");
    TEST_ASSERT_TRUE(beet_ble_format_command_result_json(json, sizeof(json), &response) > 0);
    TEST_ASSERT_STR_EQ(
        "{\"cmd\":\"get_pair_names\",\"status\":\"accepted\",\"reason\":\"none\","
        "\"data\":{\"names\":[\"Front Garden\",\"Back Lawn\",\"\",\"\",\"\",\"\",\"\",\"Herb Bed\"]}}",
        json);

    /* 8x15-byte max-length names must still fit within the 1024-byte command result stage. */
    {
        char big[1024];
        memset(&response, 0, sizeof(response));
        response.command = BEET_IFACE_COMMAND_GET_PAIR_NAMES;
        response.status = BEET_IFACE_STATUS_ACCEPTED;
        response.reason = BEET_IFACE_REASON_NONE;
        response.has_pair_names = true;
        for (uint8_t i = 0U; i < BEET_PAIR_COUNT; ++i) {
            memset(response.pair_names[i], 'a' + (char)i, BEET_PAIR_NAME_MAX_LEN);
            response.pair_names[i][BEET_PAIR_NAME_MAX_LEN] = '\0';
        }
        int written = beet_ble_format_command_result_json(big, sizeof(big), &response);
        TEST_ASSERT_TRUE(written > 0);
        TEST_ASSERT_TRUE((size_t)written < sizeof(big));
        TEST_ASSERT_TRUE(big[written] == '\0');
    }

    memset(&response, 0, sizeof(response));
    response.command = BEET_IFACE_COMMAND_STORE_PAIR_NAME;
    response.status = BEET_IFACE_STATUS_ACCEPTED;
    response.reason = BEET_IFACE_REASON_NONE;
    response.has_stored_pair_name = true;
    response.stored_pair_index = 3U;
    snprintf(response.stored_pair_name, sizeof(response.stored_pair_name), "%s", "Front Garden");
    /* response.pair_index is intentionally left 0 so the dedicated branch (not the pair_index>0 catch-all) runs. */
    TEST_ASSERT_TRUE(beet_ble_format_command_result_json(json, sizeof(json), &response) > 0);
    TEST_ASSERT_STR_EQ(
        "{\"cmd\":\"store_pair_name\",\"status\":\"accepted\",\"reason\":\"none\",\"data\":{\"pair\":3,\"name\":\"Front Garden\"}}",
        json);

    /* Verify the real rejected store_pair_name shape includes data.pair from the pair_index>0 catch-all. */
    memset(&response, 0, sizeof(response));
    response.command = BEET_IFACE_COMMAND_STORE_PAIR_NAME;
    response.pair_index = 4U;
    response.status = BEET_IFACE_STATUS_REJECTED;
    response.reason = BEET_IFACE_REASON_NAME_TOO_LONG;
    TEST_ASSERT_TRUE(beet_ble_format_command_result_json(json, sizeof(json), &response) > 0);
    TEST_ASSERT_STR_EQ(
        "{\"cmd\":\"store_pair_name\",\"status\":\"rejected\",\"reason\":\"name_too_long\",\"data\":{\"pair\":4}}",
        json);
}

static void test_ble_maintenance_info_json_max_length(void)
{
    /* Fill all string fields to their maximum allowed length and verify
     * the formatted JSON fits within BEET_BLE_JSON_MAX_LEN. */
    char json[BEET_BLE_JSON_MAX_LEN];
    beet_maintenance_info_t info;
    int written;
    size_t i;

    memset(&info, 0, sizeof(info));

    memset(info.product_id, 'p', BEET_MAINTENANCE_PRODUCT_ID_MAX_LEN);
    info.product_id[BEET_MAINTENANCE_PRODUCT_ID_MAX_LEN] = '\0';

    memset(info.hardware_rev, 'h', BEET_MAINTENANCE_HARDWARE_REV_MAX_LEN);
    info.hardware_rev[BEET_MAINTENANCE_HARDWARE_REV_MAX_LEN] = '\0';

    memset(info.firmware_version, 'f', BEET_MAINTENANCE_FIRMWARE_VERSION_MAX_LEN);
    info.firmware_version[BEET_MAINTENANCE_FIRMWARE_VERSION_MAX_LEN] = '\0';

    memset(info.build_label, 'b', BEET_MAINTENANCE_BUILD_LABEL_MAX_LEN);
    info.build_label[BEET_MAINTENANCE_BUILD_LABEL_MAX_LEN] = '\0';

    info.maintenance_protocol_version = 999999999U;
    info.runtime_protocol_version = 999999999U;
    info.update_capable = true;
    info.image_kind = BEET_MAINTENANCE_IMAGE_KIND_BUNDLED;

    memset(json, 0xAA, sizeof(json));
    written = beet_ble_format_maintenance_info_json(json, sizeof(json), &info);

    TEST_ASSERT_TRUE(written > 0);
    TEST_ASSERT_TRUE((size_t)written < sizeof(json));
    TEST_ASSERT_TRUE(json[written] == '\0');

    /* Verify the JSON starts with the expected type. */
    TEST_ASSERT_TRUE(strncmp(json, "{\"type\":\"maintenance_info\"", 26) == 0);

    /* Verify the output is valid JSON (ends with }}). */
    TEST_ASSERT_TRUE(json[written - 1] == '}');
    TEST_ASSERT_TRUE(json[written - 2] == '}');

    /* Verify that written bytes do not exceed the buffer. */
    TEST_ASSERT_TRUE((size_t)written <= sizeof(json));

    /* The bytes after the null terminator should be untouched. */
    for (i = (size_t)(written + 1); i < sizeof(json); ++i) {
        TEST_ASSERT_U32_EQ(0xAAU, (unsigned)json[i]);
    }
}

static void test_ble_runtime_booleans_encoded_as_01(void)
{
    char json[BEET_BLE_JSON_MAX_LEN];
    int written;

    beet_iface_device_state_t ds;
    memset(&ds, 0, sizeof(ds));
    ds.battery_state = BEET_BATTERY_STATE_ACTIVE;
    ds.time_valid = true;
    ds.wifi_connected = true;
    ds.mqtt_connected = true;
    ds.valve_enabled = true;
    ds.valve_state = BEET_VALVE_STATE_CLOSED;

    written = beet_ble_format_device_frame_json(json, sizeof(json), &ds);
    TEST_ASSERT_TRUE(written > 0);
    TEST_ASSERT_TRUE(strstr(json, "\"time_valid\":1") != NULL);
    TEST_ASSERT_TRUE(strstr(json, "\"wifi_connected\":1") != NULL);
    TEST_ASSERT_TRUE(strstr(json, "\"mqtt_connected\":1") != NULL);
    TEST_ASSERT_TRUE(strstr(json, "\"valve_enabled\":1") != NULL);
    TEST_ASSERT_TRUE(strstr(json, "true") == NULL);
    TEST_ASSERT_TRUE(strstr(json, "false") == NULL);

    ds.time_valid = false;
    ds.wifi_connected = false;
    ds.mqtt_connected = false;
    ds.valve_enabled = false;

    written = beet_ble_format_device_frame_json(json, sizeof(json), &ds);
    TEST_ASSERT_TRUE(written > 0);
    TEST_ASSERT_TRUE(strstr(json, "\"time_valid\":0") != NULL);
    TEST_ASSERT_TRUE(strstr(json, "\"wifi_connected\":0") != NULL);
    TEST_ASSERT_TRUE(strstr(json, "\"mqtt_connected\":0") != NULL);
    TEST_ASSERT_TRUE(strstr(json, "\"valve_enabled\":0") != NULL);
    TEST_ASSERT_TRUE(strstr(json, "true") == NULL);

    beet_iface_pair_state_t ps;
    memset(&ps, 0, sizeof(ps));
    ps.pair_index = 1U;
    ps.blocked = true;
    ps.enabled = true;
    ps.sensor_valid = true;

    written = beet_ble_format_pair_frame_json(json, sizeof(json), &ps);
    TEST_ASSERT_TRUE(written > 0);
    TEST_ASSERT_TRUE(strstr(json, "\"blocked\":1") != NULL);
    TEST_ASSERT_TRUE(strstr(json, "\"enabled\":1") != NULL);
    TEST_ASSERT_TRUE(strstr(json, "\"sensor_valid\":1") != NULL);
    TEST_ASSERT_TRUE(strstr(json, "true") == NULL);

    beet_system_event_record_t se;
    memset(&se, 0, sizeof(se));
    se.known_peer = true;

    written = beet_ble_format_system_event_frame_json(json, sizeof(json), &se, 0U);
    TEST_ASSERT_TRUE(written > 0);
    TEST_ASSERT_TRUE(strstr(json, "\"known_peer\":1") != NULL);
    TEST_ASSERT_TRUE(strstr(json, "true") == NULL);

    beet_iface_command_response_t resp;
    memset(&resp, 0, sizeof(resp));
    resp.command = BEET_IFACE_COMMAND_GET_VALVE_CONFIG;
    resp.status = BEET_IFACE_STATUS_ACCEPTED;
    resp.has_valve_config = true;
    resp.valve_enabled = true;

    written = beet_ble_format_command_result_json(json, sizeof(json), &resp);
    TEST_ASSERT_TRUE(written > 0);
    TEST_ASSERT_TRUE(strstr(json, "\"valve_enabled\":1") != NULL);
    TEST_ASSERT_TRUE(strstr(json, "true") == NULL);

    /* maintenance_info must still use true/false. */
    beet_maintenance_info_t mi;
    memset(&mi, 0, sizeof(mi));
    mi.image_kind = BEET_MAINTENANCE_IMAGE_KIND_BUNDLED;
    mi.update_capable = true;

    written = beet_ble_format_maintenance_info_json(json, sizeof(json), &mi);
    TEST_ASSERT_TRUE(written > 0);
    TEST_ASSERT_TRUE(strstr(json, "true") != NULL);
    TEST_ASSERT_TRUE(strstr(json, "\"update_capable\":true") != NULL);
}

static void test_ble_parse_bool_strict_01(void)
{
    beet_iface_command_request_t request;
    bool ok;

    memset(&request, 0, sizeof(request));
    ok = beet_ble_parse_command_json(
        "{\"cmd\":\"store_valve_config\",\"data\":{\"valve_enabled\":1}}",
        (uint32_t)strlen("{\"cmd\":\"store_valve_config\",\"data\":{\"valve_enabled\":1}}"),
        &request);
    TEST_ASSERT_TRUE(ok);
    TEST_ASSERT_TRUE(request.valve_enabled);

    memset(&request, 0, sizeof(request));
    ok = beet_ble_parse_command_json(
        "{\"cmd\":\"store_valve_config\",\"data\":{\"valve_enabled\":0}}",
        (uint32_t)strlen("{\"cmd\":\"store_valve_config\",\"data\":{\"valve_enabled\":0}}"),
        &request);
    TEST_ASSERT_TRUE(ok);
    TEST_ASSERT_FALSE(request.valve_enabled);

    memset(&request, 0, sizeof(request));
    ok = beet_ble_parse_command_json(
        "{\"cmd\":\"store_valve_config\",\"data\":{\"valve_enabled\":true}}",
        (uint32_t)strlen("{\"cmd\":\"store_valve_config\",\"data\":{\"valve_enabled\":true}}"),
        &request);
    TEST_ASSERT_FALSE(ok);

    memset(&request, 0, sizeof(request));
    ok = beet_ble_parse_command_json(
        "{\"cmd\":\"store_valve_config\",\"data\":{\"valve_enabled\":false}}",
        (uint32_t)strlen("{\"cmd\":\"store_valve_config\",\"data\":{\"valve_enabled\":false}}"),
        &request);
    TEST_ASSERT_FALSE(ok);
}

static void test_maintenance_metadata_exact_max_firmware_version(void)
{
    /* Build a synthetic metadata block with a firmware version exactly at
     * BEET_MAINTENANCE_FIRMWARE_VERSION_MAX_LEN (32 characters). */
    uint8_t buf[512];
    beet_maintenance_image_metadata_t metadata;
    const char *max_fw = "v3.14.159-rc2-build-20260622-001";
    uint16_t fw_len = (uint16_t)strlen(max_fw);

    TEST_ASSERT_U32_EQ(BEET_MAINTENANCE_FIRMWARE_VERSION_MAX_LEN, fw_len);

    /* Manually construct a minimal valid TLV metadata block. */
    size_t off = 0;
    /* Header: magic (4) + fmt_ver (2) + total_length (2) + crc placeholder (4) = 12 */
    uint32_t magic = 0x544D5442UL;
    uint16_t fmt = 1U;

    memcpy(&buf[off], &magic, 4); off += 4;
    memcpy(&buf[off], &fmt, 2); off += 2;
    size_t total_len_pos = off;
    off += 2; /* total_length, filled later */
    size_t crc_pos = off;
    off += 4; /* crc, filled later */

    /* TLV: product_id */
    memcpy(&buf[off], (uint16_t[]){1}, 2); off += 2;  /* type=PRODUCT_ID */
    memcpy(&buf[off], (uint16_t[]){11}, 2); off += 2; /* len=11 */
    memcpy(&buf[off], "beetmeister", 11); off += 11;

    /* TLV: hardware_rev */
    memcpy(&buf[off], (uint16_t[]){2}, 2); off += 2;
    memcpy(&buf[off], (uint16_t[]){5}, 2); off += 2;
    memcpy(&buf[off], "rev_a", 5); off += 5;

    /* TLV: firmware_version (long) */
    memcpy(&buf[off], (uint16_t[]){3}, 2); off += 2;
    memcpy(&buf[off], &fw_len, 2); off += 2;
    memcpy(&buf[off], max_fw, fw_len); off += fw_len;

    /* TLV: build_label */
    memcpy(&buf[off], (uint16_t[]){4}, 2); off += 2;
    memcpy(&buf[off], &fw_len, 2); off += 2;
    memcpy(&buf[off], max_fw, fw_len); off += fw_len;

    /* TLV: maintenance_protocol_version */
    memcpy(&buf[off], (uint16_t[]){5}, 2); off += 2;
    memcpy(&buf[off], (uint16_t[]){4}, 2); off += 2;
    uint32_t mpv = 1;
    memcpy(&buf[off], &mpv, 4); off += 4;

    /* TLV: runtime_protocol_version */
    memcpy(&buf[off], (uint16_t[]){6}, 2); off += 2;
    memcpy(&buf[off], (uint16_t[]){4}, 2); off += 2;
    uint32_t rpv = 12;
    memcpy(&buf[off], &rpv, 4); off += 4;

    /* TLV: image_kind */
    memcpy(&buf[off], (uint16_t[]){7}, 2); off += 2;
    memcpy(&buf[off], (uint16_t[]){7}, 2); off += 2;
    memcpy(&buf[off], "bundled", 7); off += 7;

    /* TLV: compatible_hardware_rev */
    memcpy(&buf[off], (uint16_t[]){8}, 2); off += 2;
    memcpy(&buf[off], (uint16_t[]){5}, 2); off += 2;
    memcpy(&buf[off], "rev_a", 5); off += 5;

    /* Fill in total_length */
    uint16_t total = (uint16_t)off;
    memcpy(&buf[total_len_pos], &total, 2);

    /* Compute and fill in header CRC32 (over header fields before crc). */
    uint32_t crc = esp_rom_crc32_le(0U, buf, crc_pos);
    memcpy(&buf[crc_pos], &crc, 4);

    /* Parse the synthetic block. */
    TEST_ASSERT_U32_EQ(ESP_OK, beet_maintenance_metadata_parse(buf, total, &metadata));
    TEST_ASSERT_STR_EQ("beetmeister", metadata.product_id);
    TEST_ASSERT_STR_EQ("rev_a", metadata.hardware_rev);
    TEST_ASSERT_STR_EQ(max_fw, metadata.firmware_version);
    TEST_ASSERT_STR_EQ(max_fw, metadata.build_label);
    TEST_ASSERT_U32_EQ(1U, metadata.maintenance_protocol_version);
    TEST_ASSERT_U32_EQ(12U, metadata.runtime_protocol_version);
    TEST_ASSERT_U32_EQ(BEET_MAINTENANCE_IMAGE_KIND_BUNDLED, metadata.image_kind);
    TEST_ASSERT_U32_EQ(1U, metadata.compatible_hardware_rev_count);
    TEST_ASSERT_STR_EQ("rev_a", metadata.compatible_hardware_revs[0]);
}

static void test_maintenance_metadata_truncation_on_overflow(void)
{
    /* Build a synthetic metadata block with a firmware version longer than
     * BEET_MAINTENANCE_FIRMWARE_VERSION_MAX_LEN. The parser must truncate it
     * with a warning and still succeed. */
    uint8_t buf[512];
    beet_maintenance_image_metadata_t metadata;
    const char *overflow_fw = "v0.0.0-ci-verify-20260622-4-14-g0452c52-dirty";
    uint16_t fw_len = (uint16_t)strlen(overflow_fw);
    char truncated[BEET_MAINTENANCE_FIRMWARE_VERSION_MAX_LEN + 1U];

    TEST_ASSERT_TRUE(fw_len > BEET_MAINTENANCE_FIRMWARE_VERSION_MAX_LEN);
    memcpy(truncated, overflow_fw, BEET_MAINTENANCE_FIRMWARE_VERSION_MAX_LEN);
    truncated[BEET_MAINTENANCE_FIRMWARE_VERSION_MAX_LEN] = '\0';

    /* Manually construct a minimal valid TLV metadata block. */
    size_t off = 0;
    uint32_t magic = 0x544D5442UL;
    uint16_t fmt = 1U;

    memcpy(&buf[off], &magic, 4); off += 4;
    memcpy(&buf[off], &fmt, 2); off += 2;
    size_t total_len_pos = off;
    off += 2;
    size_t crc_pos = off;
    off += 4;

    memcpy(&buf[off], (uint16_t[]){1}, 2); off += 2;
    memcpy(&buf[off], (uint16_t[]){11}, 2); off += 2;
    memcpy(&buf[off], "beetmeister", 11); off += 11;

    memcpy(&buf[off], (uint16_t[]){2}, 2); off += 2;
    memcpy(&buf[off], (uint16_t[]){5}, 2); off += 2;
    memcpy(&buf[off], "rev_a", 5); off += 5;

    memcpy(&buf[off], (uint16_t[]){3}, 2); off += 2;
    memcpy(&buf[off], &fw_len, 2); off += 2;
    memcpy(&buf[off], overflow_fw, fw_len); off += fw_len;

    memcpy(&buf[off], (uint16_t[]){4}, 2); off += 2;
    memcpy(&buf[off], &fw_len, 2); off += 2;
    memcpy(&buf[off], overflow_fw, fw_len); off += fw_len;

    memcpy(&buf[off], (uint16_t[]){5}, 2); off += 2;
    memcpy(&buf[off], (uint16_t[]){4}, 2); off += 2;
    uint32_t mpv = 1;
    memcpy(&buf[off], &mpv, 4); off += 4;

    memcpy(&buf[off], (uint16_t[]){6}, 2); off += 2;
    memcpy(&buf[off], (uint16_t[]){4}, 2); off += 2;
    uint32_t rpv = 12;
    memcpy(&buf[off], &rpv, 4); off += 4;

    memcpy(&buf[off], (uint16_t[]){7}, 2); off += 2;
    memcpy(&buf[off], (uint16_t[]){7}, 2); off += 2;
    memcpy(&buf[off], "bundled", 7); off += 7;

    memcpy(&buf[off], (uint16_t[]){8}, 2); off += 2;
    memcpy(&buf[off], (uint16_t[]){5}, 2); off += 2;
    memcpy(&buf[off], "rev_a", 5); off += 5;

    uint16_t total = (uint16_t)off;
    memcpy(&buf[total_len_pos], &total, 2);
    uint32_t crc = esp_rom_crc32_le(0U, buf, crc_pos);
    memcpy(&buf[crc_pos], &crc, 4);

    /* Parse — must succeed despite overflow (truncation, not failure). */
    TEST_ASSERT_U32_EQ(ESP_OK, beet_maintenance_metadata_parse(buf, total, &metadata));
    TEST_ASSERT_STR_EQ("beetmeister", metadata.product_id);
    TEST_ASSERT_STR_EQ("rev_a", metadata.hardware_rev);
    TEST_ASSERT_STR_EQ(truncated, metadata.firmware_version);
    TEST_ASSERT_STR_EQ(truncated, metadata.build_label);
    TEST_ASSERT_U32_EQ(1U, metadata.maintenance_protocol_version);
    TEST_ASSERT_U32_EQ(12U, metadata.runtime_protocol_version);
    TEST_ASSERT_U32_EQ(BEET_MAINTENANCE_IMAGE_KIND_BUNDLED, metadata.image_kind);
    TEST_ASSERT_U32_EQ(1U, metadata.compatible_hardware_rev_count);
    TEST_ASSERT_STR_EQ("rev_a", metadata.compatible_hardware_revs[0]);
}

static void test_maintenance_metadata_and_info(void)
{
    const uint8_t *block = NULL;
    size_t block_len = 0U;
    beet_maintenance_image_metadata_t metadata;
    beet_maintenance_info_t info;

    block = beet_maintenance_metadata_block(&block_len);
    TEST_ASSERT_TRUE(block != NULL);
    TEST_ASSERT_TRUE(block_len > 0U);
    TEST_ASSERT_U32_EQ(ESP_OK, beet_maintenance_metadata_parse(block, block_len, &metadata));
    TEST_ASSERT_STR_EQ("beetmeister", metadata.product_id);
    TEST_ASSERT_STR_EQ("rev_a", metadata.hardware_rev);
    TEST_ASSERT_STR_EQ("f5146cc-dirty", metadata.firmware_version);
    TEST_ASSERT_STR_EQ("f5146cc-dirty", metadata.build_label);
    TEST_ASSERT_U32_EQ(BEET_MAINTENANCE_PROTOCOL_VERSION, metadata.maintenance_protocol_version);
    TEST_ASSERT_U32_EQ(BEET_RUNTIME_PROTOCOL_VERSION, metadata.runtime_protocol_version);
    TEST_ASSERT_U32_EQ(BEET_MAINTENANCE_IMAGE_KIND_BUNDLED, metadata.image_kind);
    TEST_ASSERT_U32_EQ(1U, metadata.compatible_hardware_rev_count);
    TEST_ASSERT_STR_EQ("rev_a", metadata.compatible_hardware_revs[0]);

    TEST_ASSERT_U32_EQ(ESP_OK, beet_maintenance_get_info(&info));
    TEST_ASSERT_STR_EQ(metadata.product_id, info.product_id);
    TEST_ASSERT_STR_EQ(metadata.hardware_rev, info.hardware_rev);
    TEST_ASSERT_STR_EQ(metadata.firmware_version, info.firmware_version);
    TEST_ASSERT_STR_EQ(metadata.build_label, info.build_label);
    TEST_ASSERT_U32_EQ(metadata.maintenance_protocol_version, info.maintenance_protocol_version);
    TEST_ASSERT_U32_EQ(metadata.runtime_protocol_version, info.runtime_protocol_version);
    TEST_ASSERT_TRUE(info.update_capable);
    TEST_ASSERT_U32_EQ(metadata.image_kind, info.image_kind);
}

static void test_maintenance_codec_and_request_parsing(void)
{
    char json[512];
    beet_maintenance_info_t info;
    beet_maintenance_status_t status;
    beet_maintenance_request_t request;

    TEST_ASSERT_U32_EQ(ESP_OK, beet_maintenance_get_info(&info));
    TEST_ASSERT_TRUE(beet_ble_format_maintenance_info_json(json, sizeof(json), &info) > 0);
    TEST_ASSERT_STR_CONTAINS(json, "\"type\":\"maintenance_info\"");
    TEST_ASSERT_STR_CONTAINS(json, "\"product_id\":\"beetmeister\"");
    TEST_ASSERT_STR_CONTAINS(json, "\"image_kind\":\"bundled\"");

    beet_maintenance_fill_idle_status(&status);
    TEST_ASSERT_TRUE(beet_ble_format_maintenance_status_json(json, sizeof(json), &status) > 0);
    TEST_ASSERT_STR_EQ(
        "{\"type\":\"maintenance_status\",\"data\":{\"state\":\"idle\",\"next_offset\":0,\"bytes_received\":0,\"total_bytes\":0}}",
        json);

    TEST_ASSERT_TRUE(beet_ble_parse_maintenance_request_json("{\"cmd\":\"query_status\"}", &request));
    TEST_ASSERT_U32_EQ(BEET_MAINTENANCE_COMMAND_QUERY_STATUS, request.command);
    TEST_ASSERT_TRUE(beet_ble_parse_maintenance_request_json(
        "{\"cmd\":\"query_status\",\"data\":{},\"future\":1}",
        &request));
    TEST_ASSERT_TRUE(beet_ble_parse_maintenance_request_json(
        "{\"cmd\":\"begin_update\",\"data\":{\"firmware_version\":\"v0.2.0\",\"build_label\":\"v0.2.0\","
        "\"image_size\":1234,\"image_sha256\":\"00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff\","
        "\"product_id\":\"beetmeister\",\"hardware_revs\":[\"rev_a\"],\"runtime_protocol_version\":9,"
        "\"asset_id\":\"bundled-test\",\"image_kind\":\"bundled\"}}",
        &request));
    TEST_ASSERT_TRUE(beet_ble_parse_maintenance_request_json(
        "{\"cmd\":\"begin_update\",\"d\":{\"fv\":\"bd88ef0-dirty\",\"bl\":\"bd88ef0-dirty\","
        "\"sz\":721488,\"sh\":\"10ab686331845c30569226472b44c1c8bff7a686f42dbefc9da174f6845a1177\","
        "\"pi\":\"beetmeister\",\"hr\":[\"rev_a\"],\"rp\":9,\"ai\":\"bundled-dev\",\"ik\":\"bundled\"}}",
        &request));
    TEST_ASSERT_U32_EQ(BEET_MAINTENANCE_COMMAND_BEGIN_UPDATE, request.command);
    TEST_ASSERT_STR_EQ("bd88ef0-dirty", request.begin_update.firmware_version);
    TEST_ASSERT_STR_EQ("bd88ef0-dirty", request.begin_update.build_label);
    TEST_ASSERT_U32_EQ(721488U, request.begin_update.image_size);
    TEST_ASSERT_STR_EQ("beetmeister", request.begin_update.product_id);
    TEST_ASSERT_U32_EQ(1U, request.begin_update.hardware_rev_count);
    TEST_ASSERT_STR_EQ("rev_a", request.begin_update.hardware_revs[0]);
    TEST_ASSERT_TRUE(request.begin_update.has_runtime_protocol_version);
    TEST_ASSERT_U32_EQ(9U, request.begin_update.runtime_protocol_version);
    TEST_ASSERT_STR_EQ("bundled-dev", request.begin_update.asset_id);
    TEST_ASSERT_U32_EQ(BEET_MAINTENANCE_IMAGE_KIND_BUNDLED, request.begin_update.image_kind);
    TEST_ASSERT_TRUE(beet_ble_parse_maintenance_request_json("{\"cmd\":\"abort_update\"}", &request));
    TEST_ASSERT_U32_EQ(BEET_MAINTENANCE_COMMAND_ABORT_UPDATE, request.command);
    TEST_ASSERT_TRUE(beet_ble_parse_maintenance_request_json("{\"cmd\":\"finish_update\"}", &request));
    TEST_ASSERT_U32_EQ(BEET_MAINTENANCE_COMMAND_FINISH_UPDATE, request.command);
    TEST_ASSERT_FALSE(beet_ble_parse_maintenance_request_json("{\"cmd\":\"query_status\",\"data\":{\"x\":1}}", &request));
    TEST_ASSERT_FALSE(beet_ble_parse_maintenance_request_json("{\"cmd\":\"unknown\"}", &request));
    TEST_ASSERT_FALSE(beet_ble_parse_maintenance_request_json(
        "{\"cmd\":\"begin_update\",\"data\":{\"firmware_version\":\"v0.2.0\"}}",
        &request));
}

static void test_ble_system_event_data_consistency(void)
{
    char frame_json[512];
    char result_json[512];
    char frame_data[256];
    char result_data[256];
    beet_system_event_record_t system = beet_make_system_event(42U, BEET_SYSTEM_EVENT_BLE_DISCONNECT);
    beet_iface_command_response_t response;

    memset(&response, 0, sizeof(response));
    response.command = BEET_IFACE_COMMAND_GET_SYSTEM_EVENT;
    response.status = BEET_IFACE_STATUS_ACCEPTED;
    response.reason = BEET_IFACE_REASON_NONE;
    response.has_system_event = true;
    response.system_event = system;
    response.system_event_unix_s = 1700000123U;

    TEST_ASSERT_TRUE(beet_ble_format_system_event_frame_json(
        frame_json,
        sizeof(frame_json),
        &system,
        response.system_event_unix_s) > 0);
    TEST_ASSERT_TRUE(beet_ble_format_command_result_json(
        result_json,
        sizeof(result_json),
        &response) > 0);
    TEST_ASSERT_TRUE(beet_extract_data_object(frame_json, frame_data, sizeof(frame_data)));
    TEST_ASSERT_TRUE(beet_extract_data_object(result_json, result_data, sizeof(result_data)));
    TEST_ASSERT_STR_EQ(frame_data, result_data);
}

static void test_ble_base64_helpers(void)
{
    char out[32];
    size_t written = 0U;

    TEST_ASSERT_U32_EQ(4U, beet_ble_base64_encoded_len(3U));
    TEST_ASSERT_U32_EQ(8U, beet_ble_base64_encoded_len(4U));
    TEST_ASSERT_TRUE(beet_ble_base64_encode((const uint8_t *)"ABCD", 4U, out, sizeof(out), &written));
    TEST_ASSERT_U32_EQ(8U, written);
    TEST_ASSERT_STR_EQ("QUJDRA==", out);
}

static void test_ble_chunked_command_result_formatting(void)
{
    const size_t mtu_payload = 100U;
    char json[1024];
    char b64[1600];
    char frame[256];
    char rebuilt[1600];
    size_t json_len = 0U;
    size_t b64_len = 0U;
    size_t rebuilt_len = 0U;
    uint32_t chunk_id = 12U;
    uint16_t chunk_count = 1U;
    beet_iface_command_response_t response;

    memset(&response, 0, sizeof(response));
    response.command = BEET_IFACE_COMMAND_GET_SYSTEM_EVENT;
    response.status = BEET_IFACE_STATUS_ACCEPTED;
    response.reason = BEET_IFACE_REASON_NONE;
    response.has_system_event = true;
    response.system_event = beet_make_system_event(99U, BEET_SYSTEM_EVENT_BLE_DISCONNECT);
    response.system_event_unix_s = 1700000000U;
    response.system_event.detail = 0x12345678U;
    response.system_event.crc32 = beet_system_event_crc32(&response.system_event);

    TEST_ASSERT_TRUE(beet_ble_format_command_result_json(json, sizeof(json), &response) > 0);
    json_len = strlen(json);
    TEST_ASSERT_TRUE(beet_ble_base64_encode((const uint8_t *)json, json_len, b64, sizeof(b64), &b64_len));
    TEST_ASSERT_TRUE(json_len > mtu_payload);

    chunk_count = beet_compute_chunk_count_for_payload(mtu_payload, chunk_id, b64_len);
    TEST_ASSERT_TRUE(chunk_count > 0U);
    TEST_ASSERT_TRUE(chunk_count > 1U);
    TEST_ASSERT_U32_EQ(chunk_count, beet_compute_chunk_count_for_payload(mtu_payload, chunk_id, b64_len));

    {
        size_t offset = 0U;
        for (uint16_t index = 0U; index < chunk_count; ++index) {
        size_t cap = beet_ble_command_chunk_fragment_capacity(mtu_payload, chunk_id, index, chunk_count);
        size_t remaining = b64_len - offset;
        size_t frag_len = remaining < cap ? remaining : cap;
        int frame_len = beet_ble_format_command_chunk_frame_json(
            frame,
            sizeof(frame),
            chunk_id,
            index,
            chunk_count,
            b64 + offset,
            frag_len);

        TEST_ASSERT_TRUE(frame_len > 0);
        TEST_ASSERT_TRUE((size_t)frame_len <= mtu_payload);

        {
            const char *b64_start = strstr(frame, "\"b64\":\"");
            const char *b64_end = strrchr(frame, '"');
            size_t inside_len = 0U;
            TEST_ASSERT_TRUE(b64_start != NULL);
            TEST_ASSERT_TRUE(b64_end != NULL);
            b64_start += strlen("\"b64\":\"");
            TEST_ASSERT_TRUE(b64_end >= b64_start);
            inside_len = (size_t)(b64_end - b64_start);
            TEST_ASSERT_TRUE(rebuilt_len + inside_len < sizeof(rebuilt));
            memcpy(rebuilt + rebuilt_len, b64_start, inside_len);
            rebuilt_len += inside_len;
            rebuilt[rebuilt_len] = '\0';
        }

            offset += frag_len;
        }
    }

    TEST_ASSERT_U32_EQ((uint32_t)b64_len, (uint32_t)rebuilt_len);
    TEST_ASSERT_STR_EQ(b64, rebuilt);
    TEST_ASSERT_TRUE(strstr(json, "\"cmd\":\"get_system_event\"") != NULL);
}

static void test_iface_name_mapping(void)
{
    TEST_ASSERT_STR_EQ("manual_start", beet_iface_command_name(BEET_IFACE_COMMAND_MANUAL_START));
    TEST_ASSERT_STR_EQ("relay_test_start", beet_iface_command_name(BEET_IFACE_COMMAND_RELAY_TEST_START));
    TEST_ASSERT_STR_EQ("moisture_test_start", beet_iface_command_name(BEET_IFACE_COMMAND_MOISTURE_TEST_START));
    TEST_ASSERT_STR_EQ("preview_valve_position", beet_iface_command_name(BEET_IFACE_COMMAND_PREVIEW_VALVE_POSITION));
    TEST_ASSERT_STR_EQ("accepted", beet_iface_status_name(BEET_IFACE_STATUS_ACCEPTED));
    TEST_ASSERT_STR_EQ("invalid_duration", beet_iface_reason_name(BEET_IFACE_REASON_INVALID_DURATION));
    TEST_ASSERT_STR_EQ("relay_test_stopped", beet_iface_reason_name(BEET_IFACE_REASON_RELAY_TEST_STOPPED));
    TEST_ASSERT_STR_EQ("moisture_test_started", beet_iface_reason_name(BEET_IFACE_REASON_MOISTURE_TEST_STARTED));
    TEST_ASSERT_STR_EQ("set_time", beet_iface_command_name(BEET_IFACE_COMMAND_SET_TIME));
    TEST_ASSERT_STR_EQ("reboot_controller", beet_iface_command_name(BEET_IFACE_COMMAND_REBOOT_CONTROLLER));
    TEST_ASSERT_STR_EQ("factory_reset", beet_iface_command_name(BEET_IFACE_COMMAND_FACTORY_RESET));
    TEST_ASSERT_STR_EQ("get_pair_wiring", beet_iface_command_name(BEET_IFACE_COMMAND_GET_PAIR_WIRING));
    TEST_ASSERT_STR_EQ("get_max_active_pumps", beet_iface_command_name(BEET_IFACE_COMMAND_GET_MAX_ACTIVE_PUMPS));
    TEST_ASSERT_STR_EQ("store_max_active_pumps", beet_iface_command_name(BEET_IFACE_COMMAND_STORE_MAX_ACTIVE_PUMPS));
    TEST_ASSERT_STR_EQ("get_pair_names", beet_iface_command_name(BEET_IFACE_COMMAND_GET_PAIR_NAMES));
    TEST_ASSERT_STR_EQ("store_pair_name", beet_iface_command_name(BEET_IFACE_COMMAND_STORE_PAIR_NAME));
    TEST_ASSERT_STR_EQ("invalid_max_active_pumps", beet_iface_reason_name(BEET_IFACE_REASON_INVALID_MAX_ACTIVE_PUMPS));
    TEST_ASSERT_STR_EQ("name_too_long", beet_iface_reason_name(BEET_IFACE_REASON_NAME_TOO_LONG));
    TEST_ASSERT_STR_EQ("time_updated", beet_iface_reason_name(BEET_IFACE_REASON_TIME_UPDATED));
    TEST_ASSERT_STR_EQ("busy", beet_iface_reason_name(BEET_IFACE_REASON_BUSY));
    TEST_ASSERT_STR_EQ("rate_limited", beet_iface_reason_name(BEET_IFACE_REASON_RATE_LIMITED));
    TEST_ASSERT_STR_EQ("rebooting", beet_iface_reason_name(BEET_IFACE_REASON_REBOOTING));
    TEST_ASSERT_STR_EQ(
        "factory_reset_started",
        beet_iface_reason_name(BEET_IFACE_REASON_FACTORY_RESET_STARTED));
    TEST_ASSERT_STR_EQ("LOW_BATTERY_ABORT", beet_block_reason_name(BEET_BLOCK_REASON_LOW_BATTERY_ABORT));
    TEST_ASSERT_STR_EQ(
        "MOISTURE_RESPONSE_TEST_FAILED",
        beet_block_reason_name(BEET_BLOCK_REASON_MOISTURE_RESPONSE_TEST_FAILED));
    TEST_ASSERT_STR_EQ("unknown", beet_iface_reason_name((beet_iface_reason_t)255));
}

int main(void)
{
    const beet_test_case_t tests[] = {
        {"moisture_conversion_boundaries", test_moisture_conversion_boundaries},
        {"sensor_plausibility_and_supply_compensation", test_sensor_plausibility_and_supply_compensation},
        {"sensor_refresh_policy", test_sensor_refresh_policy},
        {"default_snapshot_is_disabled", test_default_snapshot_is_disabled},
        {"default_snapshot_avoids_legacy_migration", test_default_snapshot_avoids_legacy_migration},
        {"duration_lookup_boundaries", test_duration_lookup_boundaries},
        {"schema_defaults", test_schema_defaults},
        {"sanity_threshold_and_battery_classification", test_sanity_threshold_and_battery_classification},
        {"battery_percentage_and_recovery_cadence", test_battery_percentage_and_recovery_cadence},
        {"event_record_validation", test_event_record_validation},
        {"valve_system_event_types", test_valve_system_event_types},
        {"event_ring_reconstruction_and_summary", test_event_ring_reconstruction_and_summary},
        {"ble_command_parsing", test_ble_command_parsing},
        {"ble_rate_guard_windowing", test_ble_rate_guard_windowing},
        {"ble_command_lane_split", test_ble_command_lane_split},
        {"ble_rejection_response_builder", test_ble_rejection_response_builder},
        {"ble_json_formatting", test_ble_json_formatting},
        {"ble_maintenance_info_json_max_length", test_ble_maintenance_info_json_max_length},
        {"ble_runtime_booleans_encoded_as_01", test_ble_runtime_booleans_encoded_as_01},
        {"ble_parse_bool_strict_01", test_ble_parse_bool_strict_01},
        {"maintenance_metadata_exact_max_firmware_version", test_maintenance_metadata_exact_max_firmware_version},
        {"maintenance_metadata_truncation_on_overflow", test_maintenance_metadata_truncation_on_overflow},
        {"maintenance_metadata_and_info", test_maintenance_metadata_and_info},
        {"maintenance_codec_and_request_parsing", test_maintenance_codec_and_request_parsing},
        {"ble_system_event_data_consistency", test_ble_system_event_data_consistency},
        {"ble_base64_helpers", test_ble_base64_helpers},
        {"ble_chunked_command_result_formatting", test_ble_chunked_command_result_formatting},
        {"iface_name_mapping", test_iface_name_mapping},
    };

    for (size_t i = 0; i < sizeof(tests) / sizeof(tests[0]); ++i) {
        int before = s_failures;
        tests[i].fn();
        if (s_failures == before) {
            printf("PASS %s\n", tests[i].name);
        }
    }

    if (s_failures != 0) {
        printf("FAILED %d test(s)\n", s_failures);
        return 1;
    }

    printf("All host tests passed (%zu cases)\n", sizeof(tests) / sizeof(tests[0]));
    return 0;
}
