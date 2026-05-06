#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "beet_ble_host_test.h"
#include "beet_ble_codec.h"
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
    ble_host_test_set_att_mtu(mtu);
    ble_host_test_set_conn_desc(1U, true);
    beet_ble_host_test_set_session(true, true, true, 1U, 23U);
    ble_host_test_clear_captures();
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

int main(void)
{
    const beet_test_case_t tests[] = {
        {"single_frame_result_transport", test_single_frame_result_transport},
        {"chunked_result_waits_for_confirmation", test_chunked_result_waits_for_confirmation},
        {"chunked_result_resets_on_unsubscribe", test_chunked_result_resets_on_unsubscribe},
        {"chunked_result_resets_on_disconnect", test_chunked_result_resets_on_disconnect},
        {"chunked_result_resets_on_failed_completion", test_chunked_result_resets_on_failed_completion},
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
