#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "beet_ble_codec.h"
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

static beet_event_record_t beet_make_event(uint64_t seq_no, uint8_t pair_index, uint16_t duration_s)
{
    beet_event_record_t record;

    memset(&record, 0, sizeof(record));
    record.schema_version = BEET_EVENT_RECORD_VERSION;
    record.seq_no = seq_no;
    record.pair_index = pair_index;
    record.trigger_source = BEET_RUN_SOURCE_MANUAL;
    record.started_at_unix_s = 1000U;
    record.ended_at_unix_s = 1100U;
    record.time_valid = 1U;
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

static beet_event_record_t beet_make_sleep_event(uint64_t seq_no, beet_stop_reason_t stop_reason, uint16_t battery_mv)
{
    beet_event_record_t record;

    memset(&record, 0, sizeof(record));
    record.schema_version = BEET_EVENT_RECORD_VERSION;
    record.seq_no = seq_no;
    record.pair_index = 0U;
    record.trigger_source = BEET_RUN_SOURCE_NONE;
    record.stop_reason = stop_reason;
    record.block_reason = BEET_BLOCK_REASON_NONE;
    record.battery_start_mv = battery_mv;
    record.battery_end_mv = battery_mv;
    record.crc32 = beet_event_crc32(&record);
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

    record = beet_make_sleep_event(8U, BEET_STOP_REASON_IDLE_LOW_POWER_SLEEP, 3270U);
    TEST_ASSERT_TRUE(beet_validate_event_record(&record));
    record.pair_index = 2U;
    record.crc32 = beet_event_crc32(&record);
    TEST_ASSERT_FALSE(beet_validate_event_record(&record));
}

static void test_event_ring_reconstruction_and_summary(void)
{
    beet_event_ring_state_t state;
    uint16_t event_count = 0U;
    uint32_t totals[BEET_PAIR_COUNT];
    beet_event_record_t a = beet_make_event(4U, 1U, 30U);
    beet_event_record_t b = beet_make_event(7U, 3U, 50U);
    beet_event_record_t sleep = beet_make_sleep_event(8U, BEET_STOP_REASON_DEEP_LOW_BATTERY_SLEEP, 3260U);
    beet_event_record_t c = beet_make_event(999U, 2U, 20U);
    beet_event_record_t test = beet_make_event(10U, 2U, 10U);
    beet_event_record_t bad = beet_make_event(8U, 4U, 15U);

    test.trigger_source = BEET_RUN_SOURCE_TEST;
    test.crc32 = beet_event_crc32(&test);
    bad.crc32 ^= 1U;

    beet_event_ring_reset(&state);
    TEST_ASSERT_U32_EQ(1U, state.next_write_slot);

    beet_event_ring_accept_record(&state, &a);
    beet_event_ring_accept_record(&state, &bad);
    beet_event_ring_accept_record(&state, &b);
    beet_event_ring_accept_record(&state, &sleep);
    beet_event_ring_finalize(&state);
    TEST_ASSERT_TRUE(state.has_valid_records);
    TEST_ASSERT_U32_EQ(8U, state.highest_valid_seq_no);
    TEST_ASSERT_U32_EQ(9U, state.next_write_slot);

    beet_event_ring_reset(&state);
    beet_event_ring_accept_record(&state, &c);
    beet_event_ring_finalize(&state);
    TEST_ASSERT_U32_EQ(999U, state.highest_valid_seq_no);
    TEST_ASSERT_U32_EQ(0U, state.next_write_slot);

    memset(totals, 0, sizeof(totals));
    beet_event_ring_accumulate_summary(&a, &event_count, totals);
    beet_event_ring_accumulate_summary(&bad, &event_count, totals);
    beet_event_ring_accumulate_summary(&b, &event_count, totals);
    beet_event_ring_accumulate_summary(&sleep, &event_count, totals);
    beet_event_ring_accumulate_summary(&test, &event_count, totals);
    TEST_ASSERT_U32_EQ(4U, event_count);
    TEST_ASSERT_U32_EQ(30U, totals[0]);
    TEST_ASSERT_U32_EQ(0U, totals[1]);
    TEST_ASSERT_U32_EQ(50U, totals[2]);
    TEST_ASSERT_U32_EQ(0U, totals[3]);
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

    TEST_ASSERT_FALSE(beet_ble_parse_command_json(
        "{\"cmd\":\"manual_start\",\"data\":{\"duration_s\":1200}}", &request));
    TEST_ASSERT_FALSE(beet_ble_parse_command_json(
        "{\"cmd\":\"unknown\",\"data\":{\"pair\":1}}", &request));
}

static void test_ble_json_formatting(void)
{
    char json[512];
    beet_iface_device_state_t device = {
        .battery_state = BEET_BATTERY_STATE_ACTIVE,
        .battery_mv = 3325U,
        .time_valid = false,
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
        "{\"type\":\"device\",\"data\":{\"battery_state\":\"ACTIVE\",\"battery_mv\":3325,\"time_valid\":false,\"next_check_in_s\":71995,\"active_pumps\":0,\"wifi_connected\":false,\"mqtt_connected\":false}}",
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
}

static void test_iface_name_mapping(void)
{
    TEST_ASSERT_STR_EQ("manual_start", beet_iface_command_name(BEET_IFACE_COMMAND_MANUAL_START));
    TEST_ASSERT_STR_EQ("relay_test_start", beet_iface_command_name(BEET_IFACE_COMMAND_RELAY_TEST_START));
    TEST_ASSERT_STR_EQ("moisture_test_start", beet_iface_command_name(BEET_IFACE_COMMAND_MOISTURE_TEST_START));
    TEST_ASSERT_STR_EQ("accepted", beet_iface_status_name(BEET_IFACE_STATUS_ACCEPTED));
    TEST_ASSERT_STR_EQ("invalid_duration", beet_iface_reason_name(BEET_IFACE_REASON_INVALID_DURATION));
    TEST_ASSERT_STR_EQ("relay_test_stopped", beet_iface_reason_name(BEET_IFACE_REASON_RELAY_TEST_STOPPED));
    TEST_ASSERT_STR_EQ("moisture_test_started", beet_iface_reason_name(BEET_IFACE_REASON_MOISTURE_TEST_STARTED));
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
        {"sanity_threshold_and_battery_classification", test_sanity_threshold_and_battery_classification},
        {"battery_percentage_and_recovery_cadence", test_battery_percentage_and_recovery_cadence},
        {"event_record_validation", test_event_record_validation},
        {"event_ring_reconstruction_and_summary", test_event_ring_reconstruction_and_summary},
        {"ble_command_parsing", test_ble_command_parsing},
        {"ble_json_formatting", test_ble_json_formatting},
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
