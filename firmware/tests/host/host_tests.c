#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "beet_ble_codec.h"
#include "beet_ble_guard.h"
#include "beet_event_ring.h"
#include "beet_iface.h"
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
    TEST_ASSERT_STR_EQ("VALVE_OPENED", beet_system_event_type_name(BEET_SYSTEM_EVENT_VALVE_OPENED));
    TEST_ASSERT_STR_EQ("VALVE_CLOSED", beet_system_event_type_name(BEET_SYSTEM_EVENT_VALVE_CLOSED));
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
        "{\"cmd\":\"store_valve_config\",\"data\":{\"valve_enabled\":true,\"servo_min_pulse_us\":700,"
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
        "{\"type\":\"device\",\"data\":{\"battery_state\":\"ACTIVE\",\"battery_mv\":3325,\"time_valid\":false,\"boot_id\":9,\"next_check_in_s\":71995,\"active_pumps\":0,\"wifi_connected\":false,\"mqtt_connected\":false,\"uptime_s\":0,\"valve_enabled\":false,\"valve_state\":\"CLOSED\"}}",
        json);

    TEST_ASSERT_TRUE(beet_ble_format_pair_frame_json(json, sizeof(json), &pair) > 0);
    TEST_ASSERT_STR_EQ(
        "{\"type\":\"pair\",\"data\":{\"pair\":1,\"state\":\"IDLE\",\"moisture_pct\":1,\"sensor_mv\":2745,\"blocked\":false,\"block_reason\":\"NONE\",\"remaining_s\":0,\"source\":\"NONE\",\"enabled\":false,\"sensor_valid\":true}}",
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
        "\"known_peer\":true,\"detail\":0}}",
        json);

    beet_system_event_record_t system = beet_make_system_event(9U, BEET_SYSTEM_EVENT_BLE_CONNECT);
    TEST_ASSERT_TRUE(beet_ble_format_system_event_frame_json(json, sizeof(json), &system, 0U) > 0);
    TEST_ASSERT_STR_EQ(
        "{\"type\":\"system_event\",\"data\":{\"seq\":9,\"event_type\":\"BLE_CONNECT\",\"reason\":22,\"boot_id\":9,\"uptime_s\":123,\"unix_s\":0,\"battery_mv\":3340,\"peer_addr\":\"AA:BB:CC:DD:EE:FF\",\"peer_addr_type\":1,\"known_peer\":true,\"detail\":0}}",
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
        "{\"valve_enabled\":true,\"servo_min_pulse_us\":700,\"servo_max_pulse_us\":2400,"
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
    TEST_ASSERT_STR_EQ("time_updated", beet_iface_reason_name(BEET_IFACE_REASON_TIME_UPDATED));
    TEST_ASSERT_STR_EQ("busy", beet_iface_reason_name(BEET_IFACE_REASON_BUSY));
    TEST_ASSERT_STR_EQ("rate_limited", beet_iface_reason_name(BEET_IFACE_REASON_RATE_LIMITED));
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
