#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "beet_ble_host_test.h"
#include "beet_ble_codec.h"
#include "beet_generated_metadata.h"
#include "beet_types.h"

static int s_failures = 0;

#define BEET_STRINGIFY_INNER(value) #value
#define BEET_STRINGIFY(value) BEET_STRINGIFY_INNER(value)

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

typedef struct {
    uint16_t count;
    beet_ble_system_event_t events[8];
} beet_system_event_capture_t;

static beet_system_event_capture_t s_system_events;

static void beet_capture_system_event(const beet_ble_system_event_t *event)
{
    if (event == NULL || s_system_events.count >= (sizeof(s_system_events.events) / sizeof(s_system_events.events[0]))) {
        return;
    }

    s_system_events.events[s_system_events.count++] = *event;
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
    record.detail = 0x12345678U;
    record.crc32 = beet_system_event_crc32(&record);
    return record;
}

static void beet_prepare_session(uint16_t mtu)
{
    ble_host_test_reset();
    beet_ble_host_test_reset();
    memset(&s_system_events, 0, sizeof(s_system_events));
    beet_ble_set_system_event_callback(beet_capture_system_event);
    ble_host_test_set_att_mtu(mtu);
    ble_host_test_set_conn_desc(1U, true);
    beet_ble_host_test_set_session(true, true, true, 1U, 23U);
    ble_host_test_clear_captures();
}

static const char *beet_begin_update_json(void)
{
    return
    "{\"cmd\":\"begin_update\",\"data\":{\"firmware_version\":\"v0.2.0\",\"build_label\":\"v0.2.0\","
    "\"image_size\":1234,\"image_sha256\":\"00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff\","
    "\"product_id\":\"beetmeister\",\"hardware_revs\":[\"rev_a\"],\"runtime_protocol_version\":" BEET_STRINGIFY(BEET_RUNTIME_PROTOCOL_VERSION) ","
    "\"asset_id\":\"bundled-test\",\"image_kind\":\"bundled\"}}";
}

/* Maintenance protocol values are intentionally hardcoded.
   If the generated metadata block changes, this test WILL fail
   because the SHA256 and image_size won't match. That failure is
   the canary: update these values only with an intentional,
   reviewed change to the maintenance protocol surface. */
static const char *beet_stage4_begin_update_json(void)
{
    return
    "{\"cmd\":\"begin_update\",\"data\":{\"firmware_version\":\"f5146cc-dirty\",\"build_label\":\"f5146cc-dirty\","
    "\"image_size\":122,\"image_sha256\":\"9a346f4491334ac9a5d608120b979fd304b6a981fece7663f172a91a0e25c3be\","
    "\"product_id\":\"beetmeister\",\"hardware_revs\":[\"rev_a\"],\"runtime_protocol_version\":11,"
    "\"asset_id\":\"bundled-dev\",\"image_kind\":\"bundled\"}}";
}

static void beet_write_maintenance_chunk(
    uint32_t session_id,
    uint32_t offset,
    const uint8_t *payload,
    size_t payload_len,
    uint8_t *chunk_buf,
    size_t *chunk_len)
{
    chunk_buf[0] = (uint8_t)(session_id & 0xFFU);
    chunk_buf[1] = (uint8_t)((session_id >> 8) & 0xFFU);
    chunk_buf[2] = (uint8_t)((session_id >> 16) & 0xFFU);
    chunk_buf[3] = (uint8_t)((session_id >> 24) & 0xFFU);
    chunk_buf[4] = (uint8_t)(offset & 0xFFU);
    chunk_buf[5] = (uint8_t)((offset >> 8) & 0xFFU);
    chunk_buf[6] = (uint8_t)((offset >> 16) & 0xFFU);
    chunk_buf[7] = (uint8_t)((offset >> 24) & 0xFFU);
    memcpy(chunk_buf + 8U, payload, payload_len);
    *chunk_len = payload_len + 8U;
}

static void beet_start_maintenance_update(const char *begin_update_json)
{
    TEST_ASSERT_U32_EQ(0U, beet_ble_host_test_write_maintenance_control(begin_update_json, true));
    beet_ble_service();
}

static void beet_flush_maintenance_service(unsigned iterations)
{
    for (unsigned i = 0U; i < iterations; ++i) {
        beet_ble_service();
    }
}

static void beet_flush_state_stream_initial_sync(void)
{
    for (unsigned i = 0U; i < (unsigned)BEET_PAIR_COUNT + 2U; ++i) {
        beet_ble_service();
    }
}

static beet_iface_command_response_t beet_make_single_result(void)
{
    beet_iface_command_response_t response;
    memset(&response, 0, sizeof(response));
    response.command = BEET_IFACE_COMMAND_MANUAL_START;
    response.status = BEET_IFACE_STATUS_ACCEPTED;
    response.reason = BEET_IFACE_REASON_SLOT_ALLOCATED;
    response.pair_index = 3U;
    response.accepted_duration_s = 120U;
    return response;
}

static beet_iface_command_response_t beet_make_chunked_result(void)
{
    beet_iface_command_response_t response;
    memset(&response, 0, sizeof(response));
    response.command = BEET_IFACE_COMMAND_GET_SYSTEM_EVENT;
    response.status = BEET_IFACE_STATUS_ACCEPTED;
    response.reason = BEET_IFACE_REASON_NONE;
    response.has_system_event = true;
    response.system_event = beet_make_system_event(77U, BEET_SYSTEM_EVENT_BLE_DISCONNECT);
    response.system_event_unix_s = 1700000000U;
    return response;
}

static void test_single_frame_result_transport(void)
{
    beet_iface_command_response_t response = beet_make_single_result();

    beet_prepare_session(247U);
    beet_ble_host_test_set_pending_result(&response);
    beet_ble_service();

    TEST_ASSERT_U32_EQ(1U, ble_host_test_indication_count());
    TEST_ASSERT_STR_CONTAINS(ble_host_test_last_indication(), "\"cmd\":\"manual_start\"");
    TEST_ASSERT_FALSE(strstr(ble_host_test_last_indication(), "\"type\":\"cmd_chunk\"") != NULL);
    TEST_ASSERT_TRUE(beet_ble_host_test_result_active());
    TEST_ASSERT_TRUE(beet_ble_host_test_result_in_flight());

    beet_ble_host_test_notify_tx(0);
    beet_ble_service();
    TEST_ASSERT_U32_EQ(1U, ble_host_test_indication_count());
    TEST_ASSERT_TRUE(beet_ble_host_test_result_in_flight());

    beet_ble_host_test_notify_tx(BLE_HS_EDONE);
    TEST_ASSERT_FALSE(beet_ble_host_test_result_active());
    TEST_ASSERT_FALSE(beet_ble_host_test_result_in_flight());
}

static void test_chunked_result_waits_for_confirmation(void)
{
    beet_iface_command_response_t response = beet_make_chunked_result();
    uint16_t chunk_count = 0U;

    beet_prepare_session(100U);
    beet_ble_host_test_set_pending_result(&response);
    beet_ble_service();

    TEST_ASSERT_U32_EQ(1U, ble_host_test_indication_count());
    TEST_ASSERT_STR_CONTAINS(ble_host_test_last_indication(), "\"type\":\"cmd_chunk\"");
    TEST_ASSERT_STR_CONTAINS(ble_host_test_last_indication(), "\"i\":0");
    TEST_ASSERT_TRUE(beet_ble_host_test_result_in_flight());

    beet_ble_host_test_notify_tx(0);
    beet_ble_service();
    TEST_ASSERT_U32_EQ(1U, ble_host_test_indication_count());
    TEST_ASSERT_U32_EQ(0U, beet_ble_host_test_result_chunk_index());

    beet_ble_host_test_notify_tx(BLE_HS_EDONE);
    TEST_ASSERT_TRUE(beet_ble_host_test_result_active());
    TEST_ASSERT_FALSE(beet_ble_host_test_result_in_flight());
    TEST_ASSERT_U32_EQ(1U, beet_ble_host_test_result_chunk_index());
    chunk_count = beet_ble_host_test_result_chunk_count();
    TEST_ASSERT_TRUE(chunk_count > 1U);

    beet_ble_service();
    TEST_ASSERT_U32_EQ(2U, ble_host_test_indication_count());
    TEST_ASSERT_STR_CONTAINS(ble_host_test_last_indication(), "\"i\":1");

    beet_ble_host_test_notify_tx(0);
    beet_ble_service();
    TEST_ASSERT_U32_EQ(2U, ble_host_test_indication_count());

    for (uint16_t sent = 2U; sent <= chunk_count; ++sent) {
        beet_ble_host_test_notify_tx(BLE_HS_EDONE);
        if (sent == chunk_count) {
            break;
        }
        beet_ble_service();
        TEST_ASSERT_U32_EQ(sent + 1U, ble_host_test_indication_count());
    }
    TEST_ASSERT_FALSE(beet_ble_host_test_result_active());
    TEST_ASSERT_FALSE(beet_ble_host_test_result_in_flight());
}

static void test_chunked_result_resets_on_unsubscribe(void)
{
    beet_iface_command_response_t chunked = beet_make_chunked_result();
    beet_iface_command_response_t single = beet_make_single_result();

    beet_prepare_session(100U);
    beet_ble_host_test_set_pending_result(&chunked);
    beet_ble_service();
    TEST_ASSERT_TRUE(beet_ble_host_test_result_active());

    beet_ble_host_test_set_command_result_subscription(false);
    TEST_ASSERT_FALSE(beet_ble_host_test_result_active());

    beet_ble_host_test_set_command_result_subscription(true);
    ble_host_test_set_att_mtu(247U);
    ble_host_test_clear_captures();
    beet_ble_host_test_set_pending_result(&single);
    beet_ble_service();
    TEST_ASSERT_U32_EQ(1U, ble_host_test_indication_count());
    TEST_ASSERT_STR_CONTAINS(ble_host_test_last_indication(), "\"cmd\":\"manual_start\"");
}

static void test_chunked_result_resets_on_disconnect(void)
{
    beet_iface_command_response_t chunked = beet_make_chunked_result();
    beet_iface_command_response_t single = beet_make_single_result();

    beet_prepare_session(100U);
    beet_ble_host_test_set_pending_result(&chunked);
    beet_ble_service();
    TEST_ASSERT_TRUE(beet_ble_host_test_result_active());

    beet_ble_host_test_disconnect();
    TEST_ASSERT_FALSE(beet_ble_host_test_result_active());

    beet_ble_host_test_set_session(true, true, true, 1U, 23U);
    ble_host_test_set_att_mtu(247U);
    ble_host_test_clear_captures();
    beet_ble_host_test_set_pending_result(&single);
    beet_ble_service();
    TEST_ASSERT_U32_EQ(1U, ble_host_test_indication_count());
    TEST_ASSERT_STR_CONTAINS(ble_host_test_last_indication(), "\"cmd\":\"manual_start\"");
}

static void test_chunked_result_resets_on_failed_completion(void)
{
    beet_iface_command_response_t chunked = beet_make_chunked_result();
    beet_iface_command_response_t single = beet_make_single_result();

    beet_prepare_session(100U);
    beet_ble_host_test_set_pending_result(&chunked);
    beet_ble_service();
    TEST_ASSERT_TRUE(beet_ble_host_test_result_active());

    beet_ble_host_test_notify_tx(99);
    TEST_ASSERT_FALSE(beet_ble_host_test_result_active());
    TEST_ASSERT_FALSE(beet_ble_host_test_result_in_flight());

    ble_host_test_set_att_mtu(247U);
    ble_host_test_clear_captures();
    beet_ble_host_test_set_pending_result(&single);
    beet_ble_service();
    TEST_ASSERT_U32_EQ(1U, ble_host_test_indication_count());
    TEST_ASSERT_STR_CONTAINS(ble_host_test_last_indication(), "\"cmd\":\"manual_start\"");
}

static void test_maintenance_info_read_allowed_without_bond(void)
{
    char json[512];

    beet_prepare_session(247U);
    TEST_ASSERT_U32_EQ(0U, beet_ble_host_test_read_maintenance_info(json, sizeof(json), false));
    TEST_ASSERT_STR_CONTAINS(json, "\"type\":\"maintenance_info\"");
    TEST_ASSERT_STR_CONTAINS(json, "\"product_id\":\"beetmeister\"");
}

static void test_maintenance_query_status_requires_bond(void)
{
    beet_prepare_session(247U);
    beet_ble_host_test_set_maintenance_status_subscription(true);
    TEST_ASSERT_U32_EQ(
        BLE_ATT_ERR_INSUFFICIENT_AUTHEN,
        beet_ble_host_test_write_maintenance_control("{\"cmd\":\"query_status\"}", false));
    TEST_ASSERT_U32_EQ(0U, ble_host_test_indication_count());
}

static void test_maintenance_query_status_indicates_idle_status(void)
{
    beet_prepare_session(247U);
    beet_ble_host_test_set_maintenance_status_subscription(true);
    TEST_ASSERT_U32_EQ(
        0U,
        beet_ble_host_test_write_maintenance_control("{\"cmd\":\"query_status\"}", true));
    TEST_ASSERT_U32_EQ(1U, ble_host_test_indication_count());
    TEST_ASSERT_STR_EQ(
        "{\"type\":\"maintenance_status\",\"data\":{\"state\":\"idle\",\"next_offset\":0,\"bytes_received\":0,\"total_bytes\":0}}",
        ble_host_test_last_indication());
}

static void test_maintenance_query_status_requires_subscription(void)
{
    beet_prepare_session(247U);
    TEST_ASSERT_U32_EQ(
        BLE_ATT_ERR_UNLIKELY,
        beet_ble_host_test_write_maintenance_control("{\"cmd\":\"query_status\"}", true));
    TEST_ASSERT_U32_EQ(0U, ble_host_test_indication_count());
}

static void test_maintenance_begin_update_creates_session(void)
{
    beet_iface_device_state_t device = { 0 };

    beet_prepare_session(247U);
    beet_ble_host_test_set_state_stream_subscription(true);
    device.battery_state = BEET_BATTERY_STATE_ACTIVE;
    device.valve_state = BEET_VALVE_STATE_CLOSED;
    ble_host_test_set_device_state(&device);
    beet_ble_host_test_set_maintenance_status_subscription(true);
    beet_start_maintenance_update(beet_begin_update_json());
    TEST_ASSERT_U32_EQ(1U, ble_host_test_indication_count());
    TEST_ASSERT_STR_CONTAINS(ble_host_test_last_indication(), "\"state\":\"awaiting_data\"");
    TEST_ASSERT_STR_CONTAINS(ble_host_test_last_indication(), "\"session_id\":1");
    TEST_ASSERT_STR_CONTAINS(ble_host_test_last_indication(), "\"total_bytes\":1234");
    TEST_ASSERT_TRUE(beet_ble_maintenance_runtime_blocking());
    TEST_ASSERT_U32_EQ(1U, s_system_events.count);
    TEST_ASSERT_U32_EQ(BEET_SYSTEM_EVENT_UPDATE_STARTED, s_system_events.events[0].type);
    TEST_ASSERT_U32_EQ(1U, s_system_events.events[0].detail);
}

static void test_maintenance_begin_update_rejects_low_battery(void)
{
    beet_iface_device_state_t device = { 0 };

    beet_prepare_session(247U);
    device.battery_state = BEET_BATTERY_STATE_IDLE_LOW_POWER;
    ble_host_test_set_device_state(&device);
    beet_ble_host_test_set_maintenance_status_subscription(true);
    TEST_ASSERT_U32_EQ(0U, beet_ble_host_test_write_maintenance_control(beet_begin_update_json(), true));
    TEST_ASSERT_U32_EQ(1U, ble_host_test_indication_count());
    TEST_ASSERT_STR_CONTAINS(ble_host_test_last_indication(), "\"state\":\"failed\"");
    TEST_ASSERT_STR_CONTAINS(ble_host_test_last_indication(), "\"failure_reason\":\"update_low_battery\"");
    TEST_ASSERT_FALSE(beet_ble_maintenance_runtime_blocking());
}

static void test_maintenance_second_begin_update_invalidates_previous_session(void)
{
    beet_iface_device_state_t device = { 0 };

    beet_prepare_session(247U);
    beet_ble_host_test_set_state_stream_subscription(true);
    device.battery_state = BEET_BATTERY_STATE_ACTIVE;
    device.valve_state = BEET_VALVE_STATE_CLOSED;
    ble_host_test_set_device_state(&device);
    beet_ble_host_test_set_maintenance_status_subscription(true);
    beet_start_maintenance_update(beet_begin_update_json());
    TEST_ASSERT_U32_EQ(1U, ble_host_test_indication_count());

    ble_host_test_clear_captures();
    beet_start_maintenance_update(beet_begin_update_json());
    TEST_ASSERT_U32_EQ(1U, ble_host_test_indication_count());
    TEST_ASSERT_STR_CONTAINS(ble_host_test_last_indication(), "\"session_id\":2");
    TEST_ASSERT_U32_EQ(3U, s_system_events.count);
    TEST_ASSERT_U32_EQ(BEET_SYSTEM_EVENT_UPDATE_STARTED, s_system_events.events[0].type);
    TEST_ASSERT_U32_EQ(1U, s_system_events.events[0].detail);
    TEST_ASSERT_U32_EQ(BEET_SYSTEM_EVENT_UPDATE_INVALIDATED, s_system_events.events[1].type);
    TEST_ASSERT_U32_EQ(1U, s_system_events.events[1].detail);
    TEST_ASSERT_U32_EQ(BEET_SYSTEM_EVENT_UPDATE_STARTED, s_system_events.events[2].type);
    TEST_ASSERT_U32_EQ(2U, s_system_events.events[2].detail);
}

static void test_maintenance_finish_update_fails_when_upload_incomplete(void)
{
    beet_iface_device_state_t device = { 0 };

    beet_prepare_session(247U);
    device.battery_state = BEET_BATTERY_STATE_ACTIVE;
    device.valve_state = BEET_VALVE_STATE_CLOSED;
    ble_host_test_set_device_state(&device);
    beet_ble_host_test_set_maintenance_status_subscription(true);
    beet_start_maintenance_update(beet_begin_update_json());

    ble_host_test_clear_captures();
    TEST_ASSERT_U32_EQ(0U, beet_ble_host_test_write_maintenance_control("{\"cmd\":\"finish_update\"}", true));
    TEST_ASSERT_U32_EQ(1U, ble_host_test_indication_count());
    TEST_ASSERT_STR_CONTAINS(ble_host_test_last_indication(), "\"state\":\"failed\"");
    TEST_ASSERT_STR_CONTAINS(ble_host_test_last_indication(), "\"failure_reason\":\"image_upload_incomplete\"");
    TEST_ASSERT_FALSE(beet_ble_maintenance_runtime_blocking());
}

static void test_maintenance_session_suppresses_runtime_state_stream(void)
{
    beet_iface_device_state_t device = { 0 };

    beet_prepare_session(247U);
    device.battery_state = BEET_BATTERY_STATE_ACTIVE;
    device.valve_state = BEET_VALVE_STATE_CLOSED;
    device.battery_mv = 3348U;
    ble_host_test_set_device_state(&device);
    beet_ble_service();
    beet_ble_host_test_set_maintenance_status_subscription(true);
    beet_start_maintenance_update(beet_begin_update_json());
    TEST_ASSERT_TRUE(beet_ble_maintenance_runtime_blocking());

    ble_host_test_clear_captures();
    device.battery_mv = 3352U;
    ble_host_test_set_device_state(&device);
    beet_ble_service();
    TEST_ASSERT_U32_EQ(0U, ble_host_test_notification_count());
}

static void test_runtime_state_stream_suppresses_minor_jitter(void)
{
    beet_iface_device_state_t device = { 0 };
    beet_iface_pair_state_t pair = { 0 };

    beet_prepare_session(247U);
    beet_ble_host_test_set_state_stream_subscription(true);
    device.battery_state = BEET_BATTERY_STATE_ACTIVE;
    device.valve_state = BEET_VALVE_STATE_CLOSED;
    device.battery_mv = 3348U;
    device.next_check_in_s = 4812U;
    strcpy(device.device_id, "beetmeister-01");
    ble_host_test_set_device_state(&device);

    pair.pair_state = BEET_PAIR_STATE_IDLE;
    pair.moisture_pct = 58U;
    pair.sensor_mv = 1680U;
    pair.sensor_valid = true;
    ble_host_test_set_pair_state(1U, &pair);

    beet_flush_state_stream_initial_sync();
    TEST_ASSERT_TRUE(ble_host_test_notification_count() > 0U);

    ble_host_test_clear_captures();
    device.battery_mv = 3354U;
    device.next_check_in_s = 4801U;
    ble_host_test_set_device_state(&device);
    pair.sensor_mv = 1692U;
    ble_host_test_set_pair_state(1U, &pair);
    ble_host_test_advance_time_us(250000LL);
    beet_ble_service();
    TEST_ASSERT_U32_EQ(0U, ble_host_test_notification_count());
}

static void test_runtime_state_stream_notifies_meaningful_changes(void)
{
    beet_iface_device_state_t device = { 0 };
    beet_iface_pair_state_t pair = { 0 };

    beet_prepare_session(247U);
    beet_ble_host_test_set_state_stream_subscription(true);
    device.battery_state = BEET_BATTERY_STATE_ACTIVE;
    device.valve_state = BEET_VALVE_STATE_CLOSED;
    device.battery_mv = 3348U;
    device.next_check_in_s = 4812U;
    strcpy(device.device_id, "beetmeister-01");
    ble_host_test_set_device_state(&device);

    pair.pair_state = BEET_PAIR_STATE_IDLE;
    pair.moisture_pct = 58U;
    pair.sensor_mv = 1680U;
    pair.sensor_valid = true;
    ble_host_test_set_pair_state(1U, &pair);

    beet_flush_state_stream_initial_sync();
    ble_host_test_clear_captures();

    device.next_check_in_s = 4690U;
    ble_host_test_set_device_state(&device);
    ble_host_test_advance_time_us(1000000LL);
    beet_ble_service();
    TEST_ASSERT_TRUE(ble_host_test_notification_count() > 0U);

    ble_host_test_clear_captures();
    pair.sensor_mv = 1710U;
    ble_host_test_set_pair_state(1U, &pair);
    ble_host_test_advance_time_us(1000000LL);
    beet_ble_service();
    TEST_ASSERT_TRUE(ble_host_test_notification_count() > 0U);
}

static void test_mutating_command_triggers_immediate_state(void)
{
    beet_iface_device_state_t device = { 0 };
    beet_iface_pair_state_t pair = { 0 };
    beet_iface_command_response_t response;

    beet_prepare_session(247U);
    beet_ble_host_test_set_state_stream_subscription(true);
    device.battery_state = BEET_BATTERY_STATE_ACTIVE;
    device.valve_state = BEET_VALVE_STATE_CLOSED;
    device.battery_mv = 3348U;
    device.next_check_in_s = 4812U;
    strcpy(device.device_id, "beetmeister-01");
    ble_host_test_set_device_state(&device);

    pair.pair_index = 3U;
    pair.pair_state = BEET_PAIR_STATE_IDLE;
    pair.moisture_pct = 58U;
    pair.sensor_mv = 1680U;
    pair.sensor_valid = true;
    pair.enabled = true;
    ble_host_test_set_pair_state(3U, &pair);

    beet_flush_state_stream_initial_sync();
    TEST_ASSERT_TRUE(ble_host_test_notification_count() > 0U);
    ble_host_test_clear_captures();

    memset(&response, 0, sizeof(response));
    response.command = BEET_IFACE_COMMAND_DISABLE_PAIR;
    response.status = BEET_IFACE_STATUS_ACCEPTED;
    response.reason = BEET_IFACE_REASON_PAIR_DISABLED;
    response.pair_index = 3U;
    beet_ble_host_test_set_pending_result(&response);

    ble_host_test_advance_time_us(1000LL);
    beet_ble_service();

    TEST_ASSERT_TRUE(ble_host_test_notification_count() > 0U);
}

static void test_readonly_command_does_not_trigger_immediate_state(void)
{
    beet_iface_device_state_t device = { 0 };
    beet_iface_command_response_t response;

    beet_prepare_session(247U);
    beet_ble_host_test_set_state_stream_subscription(true);
    device.battery_state = BEET_BATTERY_STATE_ACTIVE;
    device.valve_state = BEET_VALVE_STATE_CLOSED;
    device.battery_mv = 3348U;
    device.next_check_in_s = 4812U;
    strcpy(device.device_id, "beetmeister-01");
    ble_host_test_set_device_state(&device);
    beet_flush_state_stream_initial_sync();
    ble_host_test_clear_captures();

    memset(&response, 0, sizeof(response));
    response.command = BEET_IFACE_COMMAND_GET_CALIBRATION;
    response.status = BEET_IFACE_STATUS_ACCEPTED;
    response.reason = BEET_IFACE_REASON_NONE;
    response.pair_index = 1U;
    beet_ble_host_test_set_pending_result(&response);

    ble_host_test_advance_time_us(1000LL);
    beet_ble_service();

    TEST_ASSERT_U32_EQ(0U, ble_host_test_notification_count());
}

static void test_rejected_mutating_command_does_not_trigger_immediate_state(void)
{
    beet_iface_device_state_t device = { 0 };
    beet_iface_command_response_t response;

    beet_prepare_session(247U);
    beet_ble_host_test_set_state_stream_subscription(true);
    device.battery_state = BEET_BATTERY_STATE_ACTIVE;
    device.valve_state = BEET_VALVE_STATE_CLOSED;
    device.battery_mv = 3348U;
    device.next_check_in_s = 4812U;
    strcpy(device.device_id, "beetmeister-01");
    ble_host_test_set_device_state(&device);
    beet_flush_state_stream_initial_sync();
    ble_host_test_clear_captures();

    memset(&response, 0, sizeof(response));
    response.command = BEET_IFACE_COMMAND_DISABLE_PAIR;
    response.status = BEET_IFACE_STATUS_REJECTED;
    response.reason = BEET_IFACE_REASON_INVALID_PAIR;
    response.pair_index = 99U;
    beet_ble_host_test_set_pending_result(&response);

    ble_host_test_advance_time_us(1000LL);
    beet_ble_service();

    TEST_ASSERT_U32_EQ(0U, ble_host_test_notification_count());
}

static void test_maintenance_session_expires_after_disconnect(void)
{
    beet_iface_device_state_t device = { 0 };

    beet_prepare_session(247U);
    device.battery_state = BEET_BATTERY_STATE_ACTIVE;
    device.valve_state = BEET_VALVE_STATE_CLOSED;
    ble_host_test_set_device_state(&device);
    beet_ble_host_test_set_maintenance_status_subscription(true);
    beet_start_maintenance_update(beet_begin_update_json());
    TEST_ASSERT_TRUE(beet_ble_maintenance_runtime_blocking());

    beet_ble_host_test_disconnect();
    ble_host_test_advance_time_us(15LL * 60LL * 1000000LL + 1LL);
    beet_ble_service();
    TEST_ASSERT_FALSE(beet_ble_maintenance_runtime_blocking());

    beet_ble_host_test_set_session(true, true, true, 1U, 23U);
    beet_ble_host_test_set_maintenance_status_subscription(true);
    ble_host_test_clear_captures();
    TEST_ASSERT_U32_EQ(0U, beet_ble_host_test_write_maintenance_control("{\"cmd\":\"query_status\"}", true));
    TEST_ASSERT_U32_EQ(1U, ble_host_test_indication_count());
    TEST_ASSERT_STR_CONTAINS(ble_host_test_last_indication(), "\"state\":\"failed\"");
    TEST_ASSERT_STR_CONTAINS(ble_host_test_last_indication(), "\"failure_reason\":\"update_session_expired\"");
}

static void test_maintenance_session_resumes_before_expiry(void)
{
    beet_iface_device_state_t device = { 0 };
    uint16_t reconnect_events = 0U;

    beet_prepare_session(247U);
    device.battery_state = BEET_BATTERY_STATE_ACTIVE;
    device.valve_state = BEET_VALVE_STATE_CLOSED;
    ble_host_test_set_device_state(&device);
    beet_ble_host_test_set_maintenance_status_subscription(true);
    beet_start_maintenance_update(beet_begin_update_json());
    TEST_ASSERT_TRUE(beet_ble_maintenance_runtime_blocking());

    beet_ble_host_test_disconnect();
    ble_host_test_advance_time_us(5LL * 1000000LL);

    beet_ble_host_test_set_session(true, true, true, 1U, 23U);
    beet_ble_host_test_set_maintenance_status_subscription(true);
    ble_host_test_clear_captures();
    TEST_ASSERT_U32_EQ(0U, beet_ble_host_test_write_maintenance_control("{\"cmd\":\"query_status\"}", true));
    TEST_ASSERT_U32_EQ(1U, ble_host_test_indication_count());
    TEST_ASSERT_STR_CONTAINS(ble_host_test_last_indication(), "\"state\":\"awaiting_data\"");
    TEST_ASSERT_STR_CONTAINS(ble_host_test_last_indication(), "\"session_id\":1");
    TEST_ASSERT_FALSE(strstr(ble_host_test_last_indication(), "\"failure_reason\"") != NULL);
    TEST_ASSERT_TRUE(beet_ble_maintenance_runtime_blocking());
    TEST_ASSERT_U32_EQ(3U, s_system_events.count);
    TEST_ASSERT_U32_EQ(BEET_SYSTEM_EVENT_UPDATE_STARTED, s_system_events.events[0].type);
    TEST_ASSERT_U32_EQ(BEET_SYSTEM_EVENT_BLE_DISCONNECT, s_system_events.events[1].type);
    TEST_ASSERT_U32_EQ(BEET_SYSTEM_EVENT_UPDATE_RECONNECT, s_system_events.events[2].type);
    TEST_ASSERT_U32_EQ(1U, s_system_events.events[2].detail);
    for (uint16_t i = 0U; i < s_system_events.count; ++i) {
        if (s_system_events.events[i].type == BEET_SYSTEM_EVENT_UPDATE_RECONNECT) {
            reconnect_events++;
        }
    }
    TEST_ASSERT_U32_EQ(1U, reconnect_events);

    TEST_ASSERT_U32_EQ(0U, beet_ble_host_test_write_maintenance_control("{\"cmd\":\"query_status\"}", true));
    TEST_ASSERT_U32_EQ(3U, s_system_events.count);
}

static void test_maintenance_begin_update_requires_bond(void)
{
    beet_iface_device_state_t device = { 0 };

    beet_prepare_session(247U);
    device.battery_state = BEET_BATTERY_STATE_ACTIVE;
    device.valve_state = BEET_VALVE_STATE_CLOSED;
    ble_host_test_set_device_state(&device);
    beet_ble_host_test_set_maintenance_status_subscription(true);
    TEST_ASSERT_U32_EQ(
        BLE_ATT_ERR_INSUFFICIENT_AUTHEN,
        beet_ble_host_test_write_maintenance_control(beet_begin_update_json(), false));
    TEST_ASSERT_U32_EQ(0U, ble_host_test_indication_count());
    TEST_ASSERT_U32_EQ(0U, s_system_events.count);
}

static void test_maintenance_data_requires_bond(void)
{
    beet_iface_device_state_t device = { 0 };
    uint8_t chunk[32];
    size_t chunk_len = 0U;
    const uint8_t payload[] = { 1U, 2U, 3U, 4U };

    beet_prepare_session(247U);
    device.battery_state = BEET_BATTERY_STATE_ACTIVE;
    device.valve_state = BEET_VALVE_STATE_CLOSED;
    ble_host_test_set_device_state(&device);
    beet_ble_host_test_set_maintenance_status_subscription(true);
    beet_start_maintenance_update(beet_stage4_begin_update_json());

    beet_write_maintenance_chunk(1U, 0U, payload, sizeof(payload), chunk, &chunk_len);
    TEST_ASSERT_U32_EQ(
        BLE_ATT_ERR_INSUFFICIENT_AUTHEN,
        beet_ble_host_test_write_maintenance_data(chunk, chunk_len, false));
}

static void test_maintenance_data_upload_and_finish_reboots(void)
{
    beet_iface_device_state_t device = { 0 };
    uint8_t image[16U + sizeof(g_beet_generated_metadata_block)];
    uint8_t chunk[8U + 16U + sizeof(g_beet_generated_metadata_block)];
    size_t chunk_len = 0U;

    memset(image, 0, 16U);
    memcpy(image + 16U, g_beet_generated_metadata_block, sizeof(g_beet_generated_metadata_block));

    beet_prepare_session(247U);
    device.battery_state = BEET_BATTERY_STATE_ACTIVE;
    device.valve_state = BEET_VALVE_STATE_CLOSED;
    ble_host_test_set_device_state(&device);
    beet_ble_host_test_set_maintenance_status_subscription(true);

    beet_start_maintenance_update(beet_stage4_begin_update_json());
    beet_write_maintenance_chunk(1U, 0U, image, sizeof(image), chunk, &chunk_len);
    TEST_ASSERT_U32_EQ(0U, beet_ble_host_test_write_maintenance_data(chunk, chunk_len, true));
    beet_flush_maintenance_service(2U);

    ble_host_test_clear_captures();
    TEST_ASSERT_U32_EQ(0U, beet_ble_host_test_write_maintenance_control("{\"cmd\":\"finish_update\"}", true));
    beet_flush_maintenance_service(2U);
    TEST_ASSERT_U32_EQ(1U, ble_host_test_indication_count());
    TEST_ASSERT_STR_CONTAINS(ble_host_test_last_indication(), "\"state\":\"rebooting\"");
    TEST_ASSERT_U32_EQ(0U, ble_host_test_restart_count());

    beet_ble_host_test_notify_maintenance_status_tx(0);
    beet_ble_service();
    TEST_ASSERT_U32_EQ(0U, ble_host_test_restart_count());

    beet_ble_host_test_notify_maintenance_status_tx(BLE_HS_EDONE);
    ble_host_test_advance_time_us(90000LL);
    beet_ble_service();
    TEST_ASSERT_U32_EQ(0U, ble_host_test_restart_count());

    ble_host_test_advance_time_us(20000LL);
    beet_ble_service();
    TEST_ASSERT_U32_EQ(1U, ble_host_test_restart_count());
}

static void test_maintenance_reboot_falls_back_without_confirmation(void)
{
    beet_iface_device_state_t device = { 0 };
    uint8_t image[16U + sizeof(g_beet_generated_metadata_block)];
    uint8_t chunk[8U + 16U + sizeof(g_beet_generated_metadata_block)];
    size_t chunk_len = 0U;

    memset(image, 0, 16U);
    memcpy(image + 16U, g_beet_generated_metadata_block, sizeof(g_beet_generated_metadata_block));

    beet_prepare_session(247U);
    device.battery_state = BEET_BATTERY_STATE_ACTIVE;
    device.valve_state = BEET_VALVE_STATE_CLOSED;
    ble_host_test_set_device_state(&device);
    beet_ble_host_test_set_maintenance_status_subscription(true);

    beet_start_maintenance_update(beet_stage4_begin_update_json());
    beet_write_maintenance_chunk(1U, 0U, image, sizeof(image), chunk, &chunk_len);
    TEST_ASSERT_U32_EQ(0U, beet_ble_host_test_write_maintenance_data(chunk, chunk_len, true));
    beet_flush_maintenance_service(2U);

    ble_host_test_clear_captures();
    TEST_ASSERT_U32_EQ(0U, beet_ble_host_test_write_maintenance_control("{\"cmd\":\"finish_update\"}", true));
    beet_flush_maintenance_service(2U);
    TEST_ASSERT_U32_EQ(1U, ble_host_test_indication_count());
    TEST_ASSERT_STR_CONTAINS(ble_host_test_last_indication(), "\"state\":\"rebooting\"");

    ble_host_test_advance_time_us(1900000LL);
    beet_ble_service();
    TEST_ASSERT_U32_EQ(0U, ble_host_test_restart_count());

    ble_host_test_advance_time_us(100000LL);
    beet_ble_service();
    TEST_ASSERT_U32_EQ(1U, ble_host_test_restart_count());
}

static void test_maintenance_data_rejects_offset_mismatch(void)
{
    beet_iface_device_state_t device = { 0 };
    uint8_t chunk[32];
    size_t chunk_len = 0U;
    const uint8_t payload[] = { 1U, 2U, 3U, 4U };

    beet_prepare_session(247U);
    device.battery_state = BEET_BATTERY_STATE_ACTIVE;
    device.valve_state = BEET_VALVE_STATE_CLOSED;
    ble_host_test_set_device_state(&device);
    beet_ble_host_test_set_maintenance_status_subscription(true);
    beet_start_maintenance_update(beet_stage4_begin_update_json());

    beet_write_maintenance_chunk(1U, 7U, payload, sizeof(payload), chunk, &chunk_len);
    TEST_ASSERT_U32_EQ(BLE_ATT_ERR_UNLIKELY, beet_ble_host_test_write_maintenance_data(chunk, chunk_len, true));
}

int main(void)
{
    const beet_test_case_t tests[] = {
        {"single_frame_result_transport", test_single_frame_result_transport},
        {"chunked_result_waits_for_confirmation", test_chunked_result_waits_for_confirmation},
        {"chunked_result_resets_on_unsubscribe", test_chunked_result_resets_on_unsubscribe},
        {"chunked_result_resets_on_disconnect", test_chunked_result_resets_on_disconnect},
        {"chunked_result_resets_on_failed_completion", test_chunked_result_resets_on_failed_completion},
        {"maintenance_info_read_allowed_without_bond", test_maintenance_info_read_allowed_without_bond},
        {"maintenance_query_status_requires_bond", test_maintenance_query_status_requires_bond},
        {"maintenance_query_status_indicates_idle_status", test_maintenance_query_status_indicates_idle_status},
        {"maintenance_query_status_requires_subscription", test_maintenance_query_status_requires_subscription},
        {"maintenance_begin_update_requires_bond", test_maintenance_begin_update_requires_bond},
        {"maintenance_begin_update_creates_session", test_maintenance_begin_update_creates_session},
        {"maintenance_begin_update_rejects_low_battery", test_maintenance_begin_update_rejects_low_battery},
        {"maintenance_second_begin_update_invalidates_previous_session", test_maintenance_second_begin_update_invalidates_previous_session},
        {"maintenance_finish_update_fails_when_upload_incomplete", test_maintenance_finish_update_fails_when_upload_incomplete},
        {"maintenance_session_suppresses_runtime_state_stream", test_maintenance_session_suppresses_runtime_state_stream},
        {"runtime_state_stream_suppresses_minor_jitter", test_runtime_state_stream_suppresses_minor_jitter},
        {"runtime_state_stream_notifies_meaningful_changes", test_runtime_state_stream_notifies_meaningful_changes},
        {"mutating_command_triggers_immediate_state", test_mutating_command_triggers_immediate_state},
        {"readonly_command_does_not_trigger_immediate_state", test_readonly_command_does_not_trigger_immediate_state},
        {"rejected_mutating_command_does_not_trigger_immediate_state", test_rejected_mutating_command_does_not_trigger_immediate_state},
        {"maintenance_session_expires_after_disconnect", test_maintenance_session_expires_after_disconnect},
        {"maintenance_session_resumes_before_expiry", test_maintenance_session_resumes_before_expiry},
        {"maintenance_data_requires_bond", test_maintenance_data_requires_bond},
        {"maintenance_data_upload_and_finish_reboots", test_maintenance_data_upload_and_finish_reboots},
        {"maintenance_reboot_falls_back_without_confirmation", test_maintenance_reboot_falls_back_without_confirmation},
        {"maintenance_data_rejects_offset_mismatch", test_maintenance_data_rejects_offset_mismatch},
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

    printf("All BLE transport host tests passed (%zu cases)\n", sizeof(tests) / sizeof(tests[0]));
    return 0;
}
