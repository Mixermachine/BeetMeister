#include "beet_ble.h"

#include <ctype.h>
#include <inttypes.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "esp_app_desc.h"
#include "esp_check.h"
#include "esp_log.h"
#include "esp_random.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
#include "host/ble_att.h"
#include "host/ble_gatt.h"
#include "host/ble_gap.h"
#include "host/ble_hs.h"
#include "host/ble_sm.h"
#include "host/util/util.h"
#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "os/os_mbuf.h"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"
#include "host/ble_store.h"
#include "store/config/ble_store_config.h"
#include "beet_ble_codec.h"
#include "beet_ble_guard.h"
#include "beet_iface.h"

static const char *TAG = "beet_ble";

#define BEET_BLE_PROTOCOL_VERSION 7U
#define BEET_BLE_COMMAND_QUEUE_LEN 4U
#define BEET_BLE_JSON_MAX_LEN 320U
#define BEET_BLE_RESULT_STAGE_MAX_LEN 1024U
#define BEET_BLE_RESULT_STAGE_BASE64_MAX_LEN ((((BEET_BLE_RESULT_STAGE_MAX_LEN) + 2U) / 3U) * 4U + 1U)
#define BEET_BLE_RESULT_FRAME_MAX_LEN BEET_BLE_RESULT_STAGE_MAX_LEN
#define BEET_BLE_COMMAND_RATE_WINDOW_US (1000000LL)
#define BEET_BLE_REAL_COMMAND_RATE_MAX_PER_WINDOW 4U
#define BEET_BLE_SYNC_READ_RATE_MAX_PER_WINDOW 12U
#define BEET_BLE_PAIRING_DISPLAY_TIMEOUT_US (30LL * 1000000LL)
#define BEET_BLE_PAIRING_CODE_MAX 1000000U
#define BEET_BLE_BOND_SCAN_CAPACITY 16U

typedef enum {
    BEET_BLE_RESULT_TX_IDLE = 0,
    BEET_BLE_RESULT_TX_SINGLE_PENDING,
    BEET_BLE_RESULT_TX_CHUNKED_PENDING,
} beet_ble_result_tx_mode_t;

typedef struct {
    beet_ble_result_tx_mode_t mode;
    bool indication_in_flight;
    uint32_t chunk_id;
    uint16_t chunk_index;
    uint16_t chunk_count;
    size_t chunk_offset;
    size_t chunk_fragment_len;
    size_t chunk_frame_len;
    char chunk_frame[BEET_BLE_RESULT_FRAME_MAX_LEN];
    size_t staged_json_len;
    char staged_json[BEET_BLE_RESULT_STAGE_MAX_LEN];
    size_t staged_b64_len;
    char staged_b64[BEET_BLE_RESULT_STAGE_BASE64_MAX_LEN];
} beet_ble_result_tx_state_t;

typedef struct {
    bool initialized;
    bool host_synced;
    bool enabled;
    bool advertising;
    bool connected;
    bool bonded;
    bool state_stream_subscribed;
    bool command_result_subscribed;
    bool initial_sync_pending;
    uint8_t own_addr_type;
    uint16_t conn_handle;
    char device_name[BEET_DEVICE_ID_MAX_LEN + 1U];
    QueueHandle_t command_queue;
    beet_iface_device_state_t last_device_state;
    beet_iface_pair_state_t last_pair_states[BEET_PAIR_COUNT];
    bool have_last_device_state;
    bool have_last_pair_states;
    bool pending_result_valid;
    beet_iface_command_response_t pending_result;
    uint32_t next_chunk_id;
    beet_ble_result_tx_state_t result_tx;
    bool pairing_display_active;
    uint16_t pairing_display_conn_handle;
    uint32_t pairing_display_passkey;
    int64_t pairing_display_expires_at_us;
    int64_t last_activity_us;
    beet_ble_rate_guard_t command_rate_guard;
    beet_ble_rate_guard_t sync_read_rate_guard;
    beet_ble_system_event_callback_t system_event_callback;
} beet_ble_state_t;

static beet_ble_state_t s_ble;
void ble_store_config_init(void);

static uint16_t s_controller_info_handle;
static uint16_t s_state_stream_handle;
static uint16_t s_control_point_handle;
static uint16_t s_command_result_handle;

static const ble_uuid128_t BEET_BLE_SERVICE_UUID =
    BLE_UUID128_INIT(0xb0, 0xc4, 0x94, 0x7d, 0x2a, 0x3f, 0x57, 0x9d,
                     0x6b, 0x4a, 0x7a, 0x6d, 0x01, 0x00, 0x2a, 0x8f);
static const ble_uuid128_t BEET_BLE_CONTROLLER_INFO_UUID =
    BLE_UUID128_INIT(0xb0, 0xc4, 0x94, 0x7d, 0x2a, 0x3f, 0x57, 0x9d,
                     0x6b, 0x4a, 0x7a, 0x6d, 0x02, 0x00, 0x2a, 0x8f);
static const ble_uuid128_t BEET_BLE_STATE_STREAM_UUID =
    BLE_UUID128_INIT(0xb0, 0xc4, 0x94, 0x7d, 0x2a, 0x3f, 0x57, 0x9d,
                     0x6b, 0x4a, 0x7a, 0x6d, 0x03, 0x00, 0x2a, 0x8f);
static const ble_uuid128_t BEET_BLE_CONTROL_POINT_UUID =
    BLE_UUID128_INIT(0xb0, 0xc4, 0x94, 0x7d, 0x2a, 0x3f, 0x57, 0x9d,
                     0x6b, 0x4a, 0x7a, 0x6d, 0x04, 0x00, 0x2a, 0x8f);
static const ble_uuid128_t BEET_BLE_COMMAND_RESULT_UUID =
    BLE_UUID128_INIT(0xb0, 0xc4, 0x94, 0x7d, 0x2a, 0x3f, 0x57, 0x9d,
                     0x6b, 0x4a, 0x7a, 0x6d, 0x05, 0x00, 0x2a, 0x8f);

static int beet_ble_gatt_access(
    uint16_t conn_handle,
    uint16_t attr_handle,
    struct ble_gatt_access_ctxt *ctxt,
    void *arg);
static int beet_ble_gap_event(struct ble_gap_event *event, void *arg);
static void beet_ble_advertise(void);
static void beet_ble_log_diag_status(const char *reason);
static void beet_ble_log_advertise_skip(const char *reason);
static void beet_ble_clear_pairing_display(const char *reason);
static void beet_ble_set_pairing_display(uint16_t conn_handle, uint32_t passkey);
static void beet_ble_fill_pairing_display(beet_ble_pairing_display_t *display);
static void beet_ble_service_pairing_display(void);
static void beet_ble_mark_activity(void);
static bool beet_ble_allow_command_now(
    const beet_iface_command_request_t *request,
    beet_ble_command_lane_t *lane_out);
static void beet_ble_reset_result_tx_state(void);
static void beet_ble_clear_result_send_state(void);
static size_t beet_ble_command_result_payload_budget(void);
static bool beet_ble_prepare_chunk_frame(void);
static bool beet_ble_format_pending_result_json(void);
static bool beet_ble_stage_single_result(size_t payload_budget);
static uint32_t beet_ble_allocate_chunk_id(void);
static bool beet_ble_compute_chunk_count(
    size_t payload_budget,
    uint32_t chunk_id,
    size_t staged_b64_len,
    uint16_t *chunk_count_out);
static bool beet_ble_stage_chunked_result(size_t payload_budget);
static bool beet_ble_advance_result_chunk(void);
static bool beet_ble_stage_pending_result(void);
static void beet_ble_on_result_indication_complete(int status);
static void beet_ble_send_pending_result(void);
static void beet_ble_set_immediate_rejection(
    const beet_iface_command_request_t *request,
    beet_iface_reason_t reason);
static void beet_ble_emit_system_event(
    beet_system_event_type_t type,
    uint16_t reason,
    uint16_t conn_handle,
    bool known_peer,
    uint32_t detail);

static const struct ble_gatt_svc_def beet_ble_svcs[] = {
    {
        .type = BLE_GATT_SVC_TYPE_PRIMARY,
        .uuid = &BEET_BLE_SERVICE_UUID.u,
        .characteristics = (struct ble_gatt_chr_def[]) {
            {
                .uuid = &BEET_BLE_CONTROLLER_INFO_UUID.u,
                .access_cb = beet_ble_gatt_access,
                .flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_READ_AUTHEN,
                .val_handle = &s_controller_info_handle,
            },
            {
                .uuid = &BEET_BLE_STATE_STREAM_UUID.u,
                .access_cb = beet_ble_gatt_access,
                .flags = BLE_GATT_CHR_F_NOTIFY | BLE_GATT_CHR_F_READ_AUTHEN,
                .val_handle = &s_state_stream_handle,
            },
            {
                .uuid = &BEET_BLE_CONTROL_POINT_UUID.u,
                .access_cb = beet_ble_gatt_access,
                .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_WRITE_AUTHEN,
                .val_handle = &s_control_point_handle,
            },
            {
                .uuid = &BEET_BLE_COMMAND_RESULT_UUID.u,
                .access_cb = beet_ble_gatt_access,
                .flags = BLE_GATT_CHR_F_INDICATE | BLE_GATT_CHR_F_READ_AUTHEN,
                .val_handle = &s_command_result_handle,
            },
            { 0 },
        },
    },
    { 0 },
};

static bool beet_ble_states_equal(
    const beet_iface_pair_state_t *a,
    const beet_iface_pair_state_t *b)
{
    return a->pair_index == b->pair_index &&
        a->pair_state == b->pair_state &&
        a->moisture_pct == b->moisture_pct &&
        a->sensor_mv == b->sensor_mv &&
        a->pump_active == b->pump_active &&
        a->remaining_s == b->remaining_s &&
        a->blocked == b->blocked &&
        a->block_reason == b->block_reason &&
        a->source == b->source &&
        a->enabled == b->enabled &&
        a->sensor_valid == b->sensor_valid;
}

static bool beet_ble_device_states_equal(
    const beet_iface_device_state_t *a,
    const beet_iface_device_state_t *b)
{
    return strcmp(a->device_id, b->device_id) == 0 &&
        a->battery_state == b->battery_state &&
        a->battery_mv == b->battery_mv &&
        a->time_valid == b->time_valid &&
        a->boot_id == b->boot_id &&
        a->next_check_in_s == b->next_check_in_s &&
        a->active_pumps == b->active_pumps &&
        a->wifi_connected == b->wifi_connected &&
        a->mqtt_connected == b->mqtt_connected &&
        a->valve_enabled == b->valve_enabled &&
        a->valve_state == b->valve_state;
}

static bool beet_ble_is_bonded_conn(uint16_t conn_handle)
{
    struct ble_gap_conn_desc desc;

    if (conn_handle == BLE_HS_CONN_HANDLE_NONE) {
        return false;
    }

    int rc = ble_gap_conn_find(conn_handle, &desc);
    if (rc != 0) {
        return false;
    }

    return desc.sec_state.bonded;
}

static int beet_ble_require_bonded(uint16_t conn_handle)
{
    return beet_ble_is_bonded_conn(conn_handle) ? 0 : BLE_ATT_ERR_INSUFFICIENT_AUTHEN;
}

static struct os_mbuf *beet_ble_json_mbuf(const char *json)
{
    struct os_mbuf *om;
    size_t len = strlen(json);

    om = os_msys_get_pkthdr((uint16_t)len, 0);
    if (om == NULL) {
        return NULL;
    }

    if (os_mbuf_append(om, json, (uint16_t)len) != 0) {
        os_mbuf_free_chain(om);
        return NULL;
    }

    return om;
}

static int beet_ble_format_controller_info(char *buf, size_t len)
{
    const esp_app_desc_t *app = esp_app_get_description();

    return beet_ble_format_controller_info_json(
        buf,
        len,
        s_ble.device_name,
        app->version,
        BEET_BLE_PROTOCOL_VERSION,
        BEET_PAIR_COUNT);
}

static int beet_ble_format_device_frame(
    char *buf,
    size_t len,
    const beet_iface_device_state_t *state)
{
    return beet_ble_format_device_frame_json(buf, len, state);
}

static int beet_ble_format_pair_frame(
    char *buf,
    size_t len,
    const beet_iface_pair_state_t *state)
{
    return beet_ble_format_pair_frame_json(buf, len, state);
}

static int beet_ble_format_command_result(
    char *buf,
    size_t len,
    const beet_iface_command_response_t *response)
{
    return beet_ble_format_command_result_json(buf, len, response);
}

static int beet_ble_read_controller_info(struct ble_gatt_access_ctxt *ctxt)
{
    char json[BEET_BLE_JSON_MAX_LEN];
    int written;

    written = beet_ble_format_controller_info(json, sizeof(json));
    if (written < 0 || (size_t)written >= sizeof(json)) {
        return BLE_ATT_ERR_INSUFFICIENT_RES;
    }

    if (os_mbuf_append(ctxt->om, json, (uint16_t)written) != 0) {
        return BLE_ATT_ERR_INSUFFICIENT_RES;
    }

    beet_ble_mark_activity();
    return 0;
}

static int beet_ble_write_control_point(struct ble_gatt_access_ctxt *ctxt)
{
    char json[BEET_BLE_JSON_MAX_LEN];
    uint16_t copied = 0U;
    beet_iface_command_request_t request;

    if (OS_MBUF_PKTLEN(ctxt->om) >= sizeof(json)) {
        return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
    }

    if (ble_hs_mbuf_to_flat(ctxt->om, json, sizeof(json) - 1U, &copied) != 0) {
        return BLE_ATT_ERR_UNLIKELY;
    }
    json[copied] = '\0';

    if (!beet_ble_parse_command_json(json, &request)) {
        return BLE_ATT_ERR_UNLIKELY;
    }

    {
        beet_ble_command_lane_t lane = BEET_BLE_COMMAND_LANE_REAL;

        if (!beet_ble_allow_command_now(&request, &lane)) {
            ESP_LOGW(
                TAG,
                "control point rate limited cmd=%s lane=%s",
                beet_iface_command_name(request.command),
                beet_ble_command_lane_name(lane));
            beet_ble_set_immediate_rejection(&request, BEET_IFACE_REASON_RATE_LIMITED);
            beet_ble_mark_activity();
            return 0;
        }

        ESP_LOGD(
            TAG,
            "control point admitted cmd=%s lane=%s",
            beet_iface_command_name(request.command),
            beet_ble_command_lane_name(lane));
    }

    if (xQueueSend(s_ble.command_queue, &request, 0) != pdTRUE) {
        beet_ble_set_immediate_rejection(&request, BEET_IFACE_REASON_BUSY);
        beet_ble_mark_activity();
        return 0;
    }

    beet_ble_mark_activity();
    return 0;
}

static int beet_ble_gatt_access(
    uint16_t conn_handle,
    uint16_t attr_handle,
    struct ble_gatt_access_ctxt *ctxt,
    void *arg)
{
    (void)arg;

    if (beet_ble_require_bonded(conn_handle) != 0) {
        return BLE_ATT_ERR_INSUFFICIENT_AUTHEN;
    }

    switch (ctxt->op) {
    case BLE_GATT_ACCESS_OP_READ_CHR:
        if (attr_handle == s_controller_info_handle) {
            return beet_ble_read_controller_info(ctxt);
        }
        return BLE_ATT_ERR_UNLIKELY;

    case BLE_GATT_ACCESS_OP_WRITE_CHR:
        if (attr_handle == s_control_point_handle) {
            return beet_ble_write_control_point(ctxt);
        }
        return BLE_ATT_ERR_UNLIKELY;

    default:
        return BLE_ATT_ERR_UNLIKELY;
    }
}

static void beet_ble_fill_diag_status(beet_ble_diag_status_t *status)
{
    if (status == NULL) {
        return;
    }

    status->initialized = s_ble.initialized;
    status->host_synced = s_ble.host_synced;
    status->enabled = s_ble.enabled;
    status->advertising = s_ble.advertising;
    status->connected = s_ble.connected;
    status->bonded = s_ble.bonded;
    status->own_addr_type = s_ble.own_addr_type;
    status->last_activity_us = s_ble.last_activity_us;
}

static void beet_ble_clear_pairing_display(const char *reason)
{
    if (!s_ble.pairing_display_active) {
        return;
    }

    ESP_LOGI(
        TAG,
        "pairing display cleared reason=%s handle=%u",
        reason,
        (unsigned)s_ble.pairing_display_conn_handle);
    s_ble.pairing_display_active = false;
    s_ble.pairing_display_conn_handle = BLE_HS_CONN_HANDLE_NONE;
    s_ble.pairing_display_passkey = 0U;
    s_ble.pairing_display_expires_at_us = 0;
}

static void beet_ble_set_pairing_display(uint16_t conn_handle, uint32_t passkey)
{
    s_ble.pairing_display_active = true;
    s_ble.pairing_display_conn_handle = conn_handle;
    s_ble.pairing_display_passkey = passkey;
    s_ble.pairing_display_expires_at_us = esp_timer_get_time() + BEET_BLE_PAIRING_DISPLAY_TIMEOUT_US;
    ESP_LOGI(
        TAG,
        "pairing display active handle=%u timeout_s=30",
        (unsigned)conn_handle);
}

static void beet_ble_fill_pairing_display(beet_ble_pairing_display_t *display)
{
    int64_t now_us;
    int64_t remaining_us;

    if (display == NULL) {
        return;
    }

    memset(display, 0, sizeof(*display));
    if (!s_ble.pairing_display_active) {
        return;
    }

    now_us = esp_timer_get_time();
    if (now_us >= s_ble.pairing_display_expires_at_us) {
        beet_ble_clear_pairing_display("timeout");
        return;
    }

    remaining_us = s_ble.pairing_display_expires_at_us - now_us;
    display->active = true;
    display->conn_handle = s_ble.pairing_display_conn_handle;
    display->passkey = s_ble.pairing_display_passkey;
    display->remaining_s = (uint8_t)((remaining_us + 999999LL) / 1000000LL);
}

static void beet_ble_log_diag_status(const char *reason)
{
    beet_ble_diag_status_t status;

    beet_ble_fill_diag_status(&status);
    ESP_LOGI(
        TAG,
        "diag %s initialized=%d host_synced=%d enabled=%d advertising=%d connected=%d bonded=%d own_addr_type=%u",
        reason,
        status.initialized,
        status.host_synced,
        status.enabled,
        status.advertising,
        status.connected,
        status.bonded,
        (unsigned)status.own_addr_type);
}

static void beet_ble_service_pairing_display(void)
{
    if (s_ble.pairing_display_active &&
        esp_timer_get_time() >= s_ble.pairing_display_expires_at_us) {
        beet_ble_clear_pairing_display("timeout");
    }
}

static void beet_ble_mark_activity(void)
{
    s_ble.last_activity_us = esp_timer_get_time();
}

static bool beet_ble_allow_command_now(
    const beet_iface_command_request_t *request,
    beet_ble_command_lane_t *lane_out)
{
    beet_ble_command_lane_t lane;

    if (request == NULL) {
        return false;
    }

    lane = beet_ble_classify_command_lane(request->command);
    if (lane_out != NULL) {
        *lane_out = lane;
    }

    if (lane == BEET_BLE_COMMAND_LANE_SYNC_READ) {
        return beet_ble_rate_guard_allow(&s_ble.sync_read_rate_guard, esp_timer_get_time());
    }

    return beet_ble_rate_guard_allow(&s_ble.command_rate_guard, esp_timer_get_time());
}

static void beet_ble_reset_result_tx_state(void)
{
    memset(&s_ble.result_tx, 0, sizeof(s_ble.result_tx));
}

static void beet_ble_clear_result_send_state(void)
{
    s_ble.pending_result_valid = false;
    beet_ble_reset_result_tx_state();
}

static size_t beet_ble_command_result_payload_budget(void)
{
    uint16_t mtu;

    if (!s_ble.connected || s_ble.conn_handle == BLE_HS_CONN_HANDLE_NONE) {
        return 0U;
    }

    mtu = ble_att_mtu(s_ble.conn_handle);
    if (mtu <= 3U) {
        return 0U;
    }

    // ATT indications consume 3 bytes of protocol overhead outside the payload.
    return (size_t)mtu - 3U;
}

static bool beet_ble_prepare_chunk_frame(void)
{
    size_t payload_budget;
    size_t fragment_capacity;
    size_t frame_overhead;
    size_t buffer_fragment_capacity;
    size_t remaining;
    size_t fragment_len;
    int written;

    if (s_ble.result_tx.mode != BEET_BLE_RESULT_TX_CHUNKED_PENDING) {
        return false;
    }
    if (s_ble.result_tx.chunk_index >= s_ble.result_tx.chunk_count) {
        return false;
    }

    payload_budget = beet_ble_command_result_payload_budget();
    if (payload_budget == 0U) {
        return false;
    }

    fragment_capacity = beet_ble_command_chunk_fragment_capacity(
        payload_budget,
        s_ble.result_tx.chunk_id,
        s_ble.result_tx.chunk_index,
        s_ble.result_tx.chunk_count);
    if (fragment_capacity == 0U) {
        ESP_LOGE(TAG, "chunk frame has no base64 payload budget mtu_payload=%u", (unsigned)payload_budget);
        return false;
    }

    if (s_ble.result_tx.chunk_offset > s_ble.result_tx.staged_b64_len) {
        return false;
    }
    frame_overhead = payload_budget - fragment_capacity;
    if (sizeof(s_ble.result_tx.chunk_frame) <= frame_overhead + 1U) {
        ESP_LOGE(TAG, "chunk frame buffer too small overhead=%u", (unsigned)frame_overhead);
        return false;
    }
    buffer_fragment_capacity = sizeof(s_ble.result_tx.chunk_frame) - frame_overhead - 1U;
    remaining = s_ble.result_tx.staged_b64_len - s_ble.result_tx.chunk_offset;
    fragment_len = remaining < fragment_capacity ? remaining : fragment_capacity;
    if (fragment_len > buffer_fragment_capacity) {
        fragment_len = buffer_fragment_capacity;
    }
    if (fragment_len == 0U) {
        return false;
    }

    written = beet_ble_format_command_chunk_frame_json(
        s_ble.result_tx.chunk_frame,
        sizeof(s_ble.result_tx.chunk_frame),
        s_ble.result_tx.chunk_id,
        s_ble.result_tx.chunk_index,
        s_ble.result_tx.chunk_count,
        s_ble.result_tx.staged_b64 + s_ble.result_tx.chunk_offset,
        fragment_len);
    if (written < 0 || (size_t)written >= sizeof(s_ble.result_tx.chunk_frame)) {
        ESP_LOGE(TAG, "chunk frame overflow index=%u count=%u", s_ble.result_tx.chunk_index, s_ble.result_tx.chunk_count);
        return false;
    }
    if ((size_t)written > payload_budget) {
        ESP_LOGE(
            TAG,
            "chunk frame exceeds mtu payload index=%u count=%u frame_len=%u mtu_payload=%u",
            s_ble.result_tx.chunk_index,
            s_ble.result_tx.chunk_count,
            (unsigned)written,
            (unsigned)payload_budget);
        return false;
    }

    s_ble.result_tx.chunk_frame_len = (size_t)written;
    s_ble.result_tx.chunk_fragment_len = fragment_len;
    return true;
}

static bool beet_ble_format_pending_result_json(void)
{
    int written;

    written = beet_ble_format_command_result(
        s_ble.result_tx.staged_json,
        sizeof(s_ble.result_tx.staged_json),
        &s_ble.pending_result);
    if (written < 0 || (size_t)written >= sizeof(s_ble.result_tx.staged_json)) {
        ESP_LOGE(
            TAG,
            "command result staging overflow bytes=%u max=%u",
            written < 0 ? 0U : (unsigned)written,
            (unsigned)(sizeof(s_ble.result_tx.staged_json) - 1U));
        s_ble.pending_result_valid = false;
        beet_ble_reset_result_tx_state();
        return false;
    }
    s_ble.result_tx.staged_json_len = (size_t)written;
    return true;
}

static bool beet_ble_stage_single_result(size_t payload_budget)
{
    if (s_ble.result_tx.staged_json_len > payload_budget) {
        return false;
    }

    memcpy(s_ble.result_tx.chunk_frame, s_ble.result_tx.staged_json, s_ble.result_tx.staged_json_len + 1U);
    s_ble.result_tx.chunk_frame_len = s_ble.result_tx.staged_json_len;
    s_ble.result_tx.mode = BEET_BLE_RESULT_TX_SINGLE_PENDING;
    s_ble.pending_result_valid = false;
    return true;
}

static uint32_t beet_ble_allocate_chunk_id(void)
{
    if (s_ble.next_chunk_id == UINT32_MAX) {
        s_ble.next_chunk_id = 1U;
    } else {
        s_ble.next_chunk_id += 1U;
    }
    return s_ble.next_chunk_id;
}

static bool beet_ble_compute_chunk_count(
    size_t payload_budget,
    uint32_t chunk_id,
    size_t staged_b64_len,
    uint16_t *chunk_count_out)
{
    uint16_t chunk_count = 1U;

    if (chunk_count_out == NULL) {
        return false;
    }

    while (true) {
        size_t fragment_capacity = beet_ble_command_chunk_fragment_capacity(
            payload_budget,
            chunk_id,
            (uint16_t)(chunk_count - 1U),
            chunk_count);
        size_t needed_chunks;

        if (fragment_capacity == 0U) {
            ESP_LOGE(TAG, "no chunk payload budget for mtu_payload=%u", (unsigned)payload_budget);
            return false;
        }

        needed_chunks = (staged_b64_len + fragment_capacity - 1U) / fragment_capacity;
        if (needed_chunks == 0U || needed_chunks > UINT16_MAX) {
            ESP_LOGE(TAG, "chunk count out of range needed=%u", (unsigned)needed_chunks);
            return false;
        }
        if (needed_chunks <= chunk_count) {
            *chunk_count_out = (uint16_t)needed_chunks;
            return true;
        }
        chunk_count = (uint16_t)needed_chunks;
    }
}

static bool beet_ble_stage_chunked_result(size_t payload_budget)
{
    uint32_t chunk_id;
    uint16_t chunk_count = 0U;

    if (!beet_ble_base64_encode(
            (const uint8_t *)s_ble.result_tx.staged_json,
            s_ble.result_tx.staged_json_len,
            s_ble.result_tx.staged_b64,
            sizeof(s_ble.result_tx.staged_b64),
            &s_ble.result_tx.staged_b64_len)) {
        ESP_LOGE(TAG, "base64 encode failed staged_len=%u", (unsigned)s_ble.result_tx.staged_json_len);
        s_ble.pending_result_valid = false;
        beet_ble_reset_result_tx_state();
        return false;
    }

    chunk_id = beet_ble_allocate_chunk_id();
    if (!beet_ble_compute_chunk_count(
            payload_budget,
            chunk_id,
            s_ble.result_tx.staged_b64_len,
            &chunk_count)) {
        s_ble.pending_result_valid = false;
        beet_ble_reset_result_tx_state();
        return false;
    }

    s_ble.result_tx.chunk_id = chunk_id;
    s_ble.result_tx.chunk_index = 0U;
    s_ble.result_tx.chunk_offset = 0U;
    s_ble.result_tx.chunk_count = chunk_count;
    s_ble.result_tx.mode = BEET_BLE_RESULT_TX_CHUNKED_PENDING;
    ESP_LOGD(
        TAG,
        "staged chunked command result id=%lu chunks=%u json_len=%u b64_len=%u mtu_payload=%u",
        (unsigned long)s_ble.result_tx.chunk_id,
        (unsigned)s_ble.result_tx.chunk_count,
        (unsigned)s_ble.result_tx.staged_json_len,
        (unsigned)s_ble.result_tx.staged_b64_len,
        (unsigned)payload_budget);
    if (!beet_ble_prepare_chunk_frame()) {
        s_ble.pending_result_valid = false;
        beet_ble_reset_result_tx_state();
        return false;
    }

    s_ble.pending_result_valid = false;
    return true;
}

static bool beet_ble_stage_pending_result(void)
{
    size_t payload_budget;

    if (s_ble.result_tx.mode != BEET_BLE_RESULT_TX_IDLE) {
        return true;
    }
    if (!s_ble.pending_result_valid) {
        return false;
    }
    if (!beet_ble_format_pending_result_json()) {
        return false;
    }

    payload_budget = beet_ble_command_result_payload_budget();
    if (payload_budget == 0U) {
        return false;
    }
    if (beet_ble_stage_single_result(payload_budget)) {
        return true;
    }

    return beet_ble_stage_chunked_result(payload_budget);
}

static bool beet_ble_advance_result_chunk(void)
{
    if (s_ble.result_tx.mode == BEET_BLE_RESULT_TX_SINGLE_PENDING) {
        beet_ble_reset_result_tx_state();
        return false;
    }
    if (s_ble.result_tx.mode != BEET_BLE_RESULT_TX_CHUNKED_PENDING) {
        return false;
    }

    s_ble.result_tx.chunk_offset += s_ble.result_tx.chunk_fragment_len;
    s_ble.result_tx.chunk_index += 1U;
    if (s_ble.result_tx.chunk_index >= s_ble.result_tx.chunk_count) {
        beet_ble_reset_result_tx_state();
        return false;
    }

    if (!beet_ble_prepare_chunk_frame()) {
        beet_ble_reset_result_tx_state();
        return false;
    }
    return true;
}

static void beet_ble_on_result_indication_complete(int status)
{
    if (!s_ble.result_tx.indication_in_flight) {
        return;
    }

    // NimBLE reports command-result indications twice: first with status=0 once transmitted,
    // then again with BLE_HS_EDONE once the peer confirms the indication.
    if (status == 0) {
        ESP_LOGD(
            TAG,
            "command result indication transmitted id=%lu index=%u count=%u awaiting confirmation",
            (unsigned long)s_ble.result_tx.chunk_id,
            (unsigned)s_ble.result_tx.chunk_index,
            (unsigned)s_ble.result_tx.chunk_count);
        return;
    }

    s_ble.result_tx.indication_in_flight = false;
    if (status != BLE_HS_EDONE) {
        ESP_LOGW(TAG, "command result indication completion failed status=%d", status);
        beet_ble_reset_result_tx_state();
        return;
    }

    ESP_LOGD(
        TAG,
        "command result indication confirmed id=%lu index=%u count=%u",
        (unsigned long)s_ble.result_tx.chunk_id,
        (unsigned)s_ble.result_tx.chunk_index,
        (unsigned)s_ble.result_tx.chunk_count);
    if (!beet_ble_advance_result_chunk()) {
        return;
    }

    // Queue the next chunk for the normal service loop instead of recursively indicating from
    // the NimBLE callback path.
    ESP_LOGD(
        TAG,
        "next chunk ready id=%lu index=%u count=%u; send deferred to service loop",
        (unsigned long)s_ble.result_tx.chunk_id,
        (unsigned)s_ble.result_tx.chunk_index,
        (unsigned)s_ble.result_tx.chunk_count);
}

static void beet_ble_set_immediate_rejection(
    const beet_iface_command_request_t *request,
    beet_iface_reason_t reason)
{
    beet_ble_build_rejection(request, reason, &s_ble.pending_result);
    s_ble.pending_result_valid = true;
}

static void beet_ble_emit_system_event(
    beet_system_event_type_t type,
    uint16_t reason,
    uint16_t conn_handle,
    bool known_peer,
    uint32_t detail)
{
    beet_ble_system_event_t event;
    struct ble_gap_conn_desc desc;

    if (s_ble.system_event_callback == NULL) {
        return;
    }

    memset(&event, 0, sizeof(event));
    event.type = type;
    event.reason = reason;
    event.known_peer = known_peer;
    event.detail = detail;
    if (conn_handle != BLE_HS_CONN_HANDLE_NONE && ble_gap_conn_find(conn_handle, &desc) == 0) {
        memcpy(event.peer_addr, desc.peer_id_addr.val, sizeof(event.peer_addr));
        event.peer_addr_type = desc.peer_id_addr.type;
    }

    s_ble.system_event_callback(&event);
}

static void beet_ble_log_advertise_skip(const char *reason)
{
    ESP_LOGI(
        TAG,
        "advertise skipped reason=%s initialized=%d host_synced=%d enabled=%d connected=%d advertising=%d",
        reason,
        s_ble.initialized,
        s_ble.host_synced,
        s_ble.enabled,
        s_ble.connected,
        s_ble.advertising);
}

static void beet_ble_advertise(void)
{
    struct ble_gap_adv_params adv_params;
    struct ble_hs_adv_fields fields;
    struct ble_hs_adv_fields rsp_fields;
    int rc;

    if (!s_ble.initialized) {
        beet_ble_log_advertise_skip("not_initialized");
        return;
    }
    if (!s_ble.host_synced) {
        beet_ble_log_advertise_skip("host_not_synced");
        return;
    }
    if (!s_ble.enabled) {
        beet_ble_log_advertise_skip("disabled");
        return;
    }
    if (s_ble.connected) {
        beet_ble_log_advertise_skip("already_connected");
        return;
    }
    if (s_ble.advertising) {
        beet_ble_log_advertise_skip("already_advertising");
        return;
    }

    ESP_LOGI(TAG, "advertise start requested name=%s own_addr_type=%u", s_ble.device_name, (unsigned)s_ble.own_addr_type);

    memset(&fields, 0, sizeof(fields));
    fields.flags = BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP;
    fields.tx_pwr_lvl_is_present = 1;
    fields.tx_pwr_lvl = BLE_HS_ADV_TX_PWR_LVL_AUTO;
    fields.uuids128 = (ble_uuid128_t[]){ BEET_BLE_SERVICE_UUID };
    fields.num_uuids128 = 1;
    fields.uuids128_is_complete = 1;

    rc = ble_gap_adv_set_fields(&fields);
    if (rc != 0) {
        ESP_LOGW(TAG, "advertising fields setup failed: rc=%d", rc);
        return;
    }

    memset(&rsp_fields, 0, sizeof(rsp_fields));
    rsp_fields.name = (uint8_t *)s_ble.device_name;
    rsp_fields.name_len = strlen(s_ble.device_name);
    rsp_fields.name_is_complete = 1;

    rc = ble_gap_adv_rsp_set_fields(&rsp_fields);
    if (rc != 0) {
        ESP_LOGW(TAG, "advertising scan response setup failed: rc=%d", rc);
        return;
    }

    memset(&adv_params, 0, sizeof(adv_params));
    adv_params.conn_mode = BLE_GAP_CONN_MODE_UND;
    adv_params.disc_mode = BLE_GAP_DISC_MODE_GEN;
    rc = ble_gap_adv_start(
        s_ble.own_addr_type,
        NULL,
        BLE_HS_FOREVER,
        &adv_params,
        beet_ble_gap_event,
        NULL);
    if (rc != 0) {
        ESP_LOGW(TAG, "advertising start failed: rc=%d", rc);
        return;
    }

    s_ble.advertising = true;
    beet_ble_log_diag_status("advertising_started");
}

static void beet_ble_stop_advertising(void)
{
    int rc;

    if (!s_ble.advertising) {
        return;
    }

    rc = ble_gap_adv_stop();
    if (rc != 0 && rc != BLE_HS_EALREADY) {
        ESP_LOGW(TAG, "advertising stop failed: rc=%d", rc);
    }
    s_ble.advertising = false;
}

static void beet_ble_disconnect(void)
{
    int rc;

    if (!s_ble.connected) {
        return;
    }

    rc = ble_gap_terminate(s_ble.conn_handle, BLE_ERR_REM_USER_CONN_TERM);
    if (rc != 0 && rc != BLE_HS_ENOTCONN) {
        ESP_LOGW(TAG, "disconnect failed: rc=%d", rc);
    }
}

static int beet_ble_gap_event(struct ble_gap_event *event, void *arg)
{
    (void)arg;

    switch (event->type) {
    case BLE_GAP_EVENT_CONNECT:
        s_ble.advertising = false;
        if (event->connect.status == 0) {
            s_ble.conn_handle = event->connect.conn_handle;
            s_ble.connected = true;
            s_ble.bonded = beet_ble_is_bonded_conn(event->connect.conn_handle);
            s_ble.state_stream_subscribed = false;
            s_ble.command_result_subscribed = false;
            beet_ble_clear_result_send_state();
            s_ble.initial_sync_pending = false;
            s_ble.have_last_device_state = false;
            s_ble.have_last_pair_states = false;
            ESP_LOGI(TAG, "ble connected handle=%u bonded=%d", s_ble.conn_handle, s_ble.bonded);
            beet_ble_emit_system_event(
                BEET_SYSTEM_EVENT_BLE_CONNECT,
                0U,
                s_ble.conn_handle,
                s_ble.bonded,
                0U);
            beet_ble_log_diag_status("gap_connect");
        } else if (s_ble.enabled) {
            ESP_LOGW(TAG, "ble connect failed status=%d", event->connect.status);
            beet_ble_clear_pairing_display("connect_failed");
            beet_ble_advertise();
        }
        return 0;

    case BLE_GAP_EVENT_DISCONNECT:
        ESP_LOGI(TAG, "ble disconnected reason=%d", event->disconnect.reason);
        beet_ble_emit_system_event(
            BEET_SYSTEM_EVENT_BLE_DISCONNECT,
            (uint16_t)event->disconnect.reason,
            event->disconnect.conn.conn_handle,
            event->disconnect.conn.sec_state.bonded,
            0U);
        beet_ble_clear_pairing_display("disconnect");
        s_ble.connected = false;
        s_ble.bonded = false;
        s_ble.conn_handle = BLE_HS_CONN_HANDLE_NONE;
        s_ble.state_stream_subscribed = false;
        s_ble.command_result_subscribed = false;
        beet_ble_clear_result_send_state();
        s_ble.initial_sync_pending = false;
        s_ble.have_last_device_state = false;
        s_ble.have_last_pair_states = false;
        if (s_ble.enabled) {
            beet_ble_advertise();
        }
        beet_ble_log_diag_status("gap_disconnect");
        return 0;

    case BLE_GAP_EVENT_ADV_COMPLETE:
        ESP_LOGI(TAG, "advertising complete reason=%d", event->adv_complete.reason);
        s_ble.advertising = false;
        if (s_ble.enabled && !s_ble.connected) {
            beet_ble_advertise();
        }
        return 0;

    case BLE_GAP_EVENT_SUBSCRIBE:
        if (event->subscribe.attr_handle == s_state_stream_handle) {
            s_ble.state_stream_subscribed = event->subscribe.cur_notify && beet_ble_is_bonded_conn(event->subscribe.conn_handle);
            s_ble.initial_sync_pending = s_ble.state_stream_subscribed;
            s_ble.have_last_device_state = false;
            s_ble.have_last_pair_states = false;
            beet_ble_mark_activity();
            ESP_LOGI(TAG, "state stream subscribe notify=%d bonded=%d", event->subscribe.cur_notify, s_ble.state_stream_subscribed);
        } else if (event->subscribe.attr_handle == s_command_result_handle) {
            s_ble.command_result_subscribed = event->subscribe.cur_indicate && beet_ble_is_bonded_conn(event->subscribe.conn_handle);
            if (!s_ble.command_result_subscribed) {
                beet_ble_clear_result_send_state();
            }
            beet_ble_mark_activity();
            ESP_LOGI(TAG, "command result subscribe indicate=%d bonded=%d", event->subscribe.cur_indicate, s_ble.command_result_subscribed);
        }
        return 0;

    case BLE_GAP_EVENT_NOTIFY_TX:
        if (event->notify_tx.conn_handle == s_ble.conn_handle &&
            event->notify_tx.indication &&
            event->notify_tx.attr_handle == s_command_result_handle) {
            beet_ble_on_result_indication_complete(event->notify_tx.status);
        }
        return 0;

    case BLE_GAP_EVENT_ENC_CHANGE:
        s_ble.bonded = beet_ble_is_bonded_conn(event->enc_change.conn_handle);
        ESP_LOGI(TAG, "encryption change status=%d bonded=%d", event->enc_change.status, s_ble.bonded);
        if (event->enc_change.status == 0 && s_ble.bonded) {
            beet_ble_clear_pairing_display("bonded");
            beet_ble_emit_system_event(
                BEET_SYSTEM_EVENT_BLE_BOND_SUCCESS,
                0U,
                event->enc_change.conn_handle,
                true,
                0U);
        } else if (event->enc_change.status != 0) {
            beet_ble_emit_system_event(
                BEET_SYSTEM_EVENT_BLE_BOND_FAILED,
                (uint16_t)event->enc_change.status,
                event->enc_change.conn_handle,
                false,
                0U);
        }
        beet_ble_log_diag_status("enc_change");
        return 0;

    case BLE_GAP_EVENT_PASSKEY_ACTION:
        if (event->passkey.params.action == BLE_SM_IOACT_DISP ||
            event->passkey.params.action == BLE_SM_IOACT_STATIC) {
            struct ble_sm_io io = { 0 };
            uint32_t passkey = esp_random() % BEET_BLE_PAIRING_CODE_MAX;
            int rc;

            io.action = event->passkey.params.action;
            io.passkey = passkey;
            rc = ble_sm_inject_io(event->passkey.conn_handle, &io);
            if (rc != 0) {
                ESP_LOGW(
                    TAG,
                    "passkey inject failed action=%u handle=%u rc=%d",
                    (unsigned)event->passkey.params.action,
                    (unsigned)event->passkey.conn_handle,
                    rc);
                return rc;
            }

            beet_ble_set_pairing_display(event->passkey.conn_handle, passkey);
            return 0;
        }

        ESP_LOGW(
            TAG,
            "unsupported passkey action=%u handle=%u",
            (unsigned)event->passkey.params.action,
            (unsigned)event->passkey.conn_handle);
        return BLE_HS_EINVAL;

    case BLE_GAP_EVENT_REPEAT_PAIRING:
        beet_ble_clear_pairing_display("repeat_pairing");
        return BLE_GAP_REPEAT_PAIRING_RETRY;

    default:
        return 0;
    }
}

static void beet_ble_on_reset(int reason)
{
    ESP_LOGW(TAG, "nimble reset reason=%d", reason);
}

static void beet_ble_on_sync(void)
{
    int rc;

    ESP_LOGI(TAG, "nimble host synced");

    rc = ble_hs_util_ensure_addr(0);
    if (rc != 0) {
        ESP_LOGE(TAG, "ensure addr failed: rc=%d", rc);
        return;
    }

    rc = ble_hs_id_infer_auto(0, &s_ble.own_addr_type);
    if (rc != 0) {
        ESP_LOGE(TAG, "infer addr type failed: rc=%d", rc);
        return;
    }

    s_ble.host_synced = true;
    beet_ble_log_diag_status("host_sync");
    beet_ble_advertise();
}

static void beet_ble_host_task(void *param)
{
    (void)param;
    nimble_port_run();
    nimble_port_freertos_deinit();
}

static void beet_ble_register_cb(struct ble_gatt_register_ctxt *ctxt, void *arg)
{
    (void)ctxt;
    (void)arg;
}

static esp_err_t beet_ble_send_notify_json(uint16_t attr_handle, const char *json)
{
    struct os_mbuf *om;
    int rc;

    if (!s_ble.connected || !s_ble.state_stream_subscribed) {
        return ESP_ERR_INVALID_STATE;
    }

    om = beet_ble_json_mbuf(json);
    if (om == NULL) {
        return ESP_ERR_NO_MEM;
    }

    rc = ble_gatts_notify_custom(s_ble.conn_handle, attr_handle, om);
    if (rc != 0) {
        ESP_LOGW(TAG, "notify failed: rc=%d", rc);
        return ESP_FAIL;
    }

    beet_ble_mark_activity();
    return ESP_OK;
}

static esp_err_t beet_ble_send_indicate_json(uint16_t attr_handle, const char *json)
{
    struct os_mbuf *om;
    int rc;

    if (!s_ble.connected || !s_ble.command_result_subscribed) {
        return ESP_ERR_INVALID_STATE;
    }

    om = beet_ble_json_mbuf(json);
    if (om == NULL) {
        return ESP_ERR_NO_MEM;
    }

    rc = ble_gatts_indicate_custom(s_ble.conn_handle, attr_handle, om);
    if (rc != 0) {
        ESP_LOGW(TAG, "indicate failed: rc=%d", rc);
        return ESP_FAIL;
    }

    beet_ble_mark_activity();
    return ESP_OK;
}

static void beet_ble_drain_commands(void)
{
    beet_iface_command_request_t request;

    while (xQueueReceive(s_ble.command_queue, &request, 0) == pdTRUE) {
        beet_iface_command_response_t response;

        if (beet_iface_submit_command(&request, &response) != ESP_OK) {
            memset(&response, 0, sizeof(response));
            response.command = request.command;
            response.pair_index = request.pair_index;
            response.status = BEET_IFACE_STATUS_REJECTED;
            response.reason = BEET_IFACE_REASON_UNSUPPORTED_COMMAND;
        }

        s_ble.pending_result = response;
        s_ble.pending_result_valid = true;
    }
}

static void beet_ble_send_pending_result(void)
{
    if (!s_ble.connected || !s_ble.command_result_subscribed) {
        return;
    }
    if (s_ble.result_tx.indication_in_flight) {
        return;
    }

    if (!beet_ble_stage_pending_result()) {
        return;
    }
    if (s_ble.result_tx.mode == BEET_BLE_RESULT_TX_IDLE) {
        return;
    }

    if (s_ble.result_tx.mode == BEET_BLE_RESULT_TX_CHUNKED_PENDING) {
        ESP_LOGD(
            TAG,
            "sending chunked command result id=%lu index=%u count=%u len=%u",
            (unsigned long)s_ble.result_tx.chunk_id,
            (unsigned)s_ble.result_tx.chunk_index,
            (unsigned)s_ble.result_tx.chunk_count,
            (unsigned)s_ble.result_tx.chunk_frame_len);
    }
    if (beet_ble_send_indicate_json(s_command_result_handle, s_ble.result_tx.chunk_frame) == ESP_OK) {
        s_ble.result_tx.indication_in_flight = true;
    }
}

static void beet_ble_stream_device_state(void)
{
    beet_iface_device_state_t device_state;
    char json[BEET_BLE_JSON_MAX_LEN];
    int written;

    if (beet_iface_get_device_state(&device_state) != ESP_OK) {
        return;
    }

    if (s_ble.have_last_device_state &&
        !s_ble.initial_sync_pending &&
        beet_ble_device_states_equal(&device_state, &s_ble.last_device_state)) {
        return;
    }

    written = beet_ble_format_device_frame(json, sizeof(json), &device_state);
    if (written < 0 || (size_t)written >= sizeof(json)) {
        ESP_LOGW(TAG, "device frame too large");
        return;
    }

    if (beet_ble_send_notify_json(s_state_stream_handle, json) == ESP_OK) {
        s_ble.last_device_state = device_state;
        s_ble.have_last_device_state = true;
    }
}

static void beet_ble_stream_pair_state(uint8_t pair_index)
{
    beet_iface_pair_state_t pair_state;
    char json[BEET_BLE_JSON_MAX_LEN];
    int written;

    if (beet_iface_get_pair_state(pair_index, &pair_state) != ESP_OK) {
        return;
    }

    if (s_ble.have_last_pair_states &&
        !s_ble.initial_sync_pending &&
        beet_ble_states_equal(&pair_state, &s_ble.last_pair_states[pair_index - 1U])) {
        return;
    }

    written = beet_ble_format_pair_frame(json, sizeof(json), &pair_state);
    if (written < 0 || (size_t)written >= sizeof(json)) {
        ESP_LOGW(TAG, "pair frame too large for pair %u", pair_index);
        return;
    }

    if (beet_ble_send_notify_json(s_state_stream_handle, json) == ESP_OK) {
        s_ble.last_pair_states[pair_index - 1U] = pair_state;
    }
}

esp_err_t beet_ble_init(const char *device_name)
{
    int rc;

    ESP_RETURN_ON_FALSE(device_name != NULL, ESP_ERR_INVALID_ARG, TAG, "device name null");
    if (s_ble.initialized) {
        beet_ble_log_diag_status("init_already_initialized");
        return ESP_OK;
    }

    ESP_LOGI(TAG, "ble init start device_name=%s force_enable=%d", device_name, BEET_BLE_FORCE_ENABLE_DIAGNOSTICS);

    memset(&s_ble, 0, sizeof(s_ble));
    s_ble.conn_handle = BLE_HS_CONN_HANDLE_NONE;
    beet_ble_rate_guard_init(
        &s_ble.command_rate_guard,
        BEET_BLE_COMMAND_RATE_WINDOW_US,
        BEET_BLE_REAL_COMMAND_RATE_MAX_PER_WINDOW);
    beet_ble_rate_guard_init(
        &s_ble.sync_read_rate_guard,
        BEET_BLE_COMMAND_RATE_WINDOW_US,
        BEET_BLE_SYNC_READ_RATE_MAX_PER_WINDOW);
    s_ble.command_queue = xQueueCreate(BEET_BLE_COMMAND_QUEUE_LEN, sizeof(beet_iface_command_request_t));
    ESP_RETURN_ON_FALSE(s_ble.command_queue != NULL, ESP_ERR_NO_MEM, TAG, "ble queue create failed");
    strncpy(s_ble.device_name, device_name, sizeof(s_ble.device_name) - 1U);

    ESP_RETURN_ON_ERROR(nimble_port_init(), TAG, "nimble init failed");

    ble_hs_cfg.reset_cb = beet_ble_on_reset;
    ble_hs_cfg.sync_cb = beet_ble_on_sync;
    ble_hs_cfg.gatts_register_cb = beet_ble_register_cb;
    ble_hs_cfg.store_status_cb = ble_store_util_status_rr;
    ble_hs_cfg.sm_io_cap = BLE_SM_IO_CAP_DISP_ONLY;
    ble_hs_cfg.sm_bonding = 1;
    ble_hs_cfg.sm_mitm = 1;
    ble_hs_cfg.sm_sc = 1;
    ble_hs_cfg.sm_our_key_dist |= BLE_SM_PAIR_KEY_DIST_ENC | BLE_SM_PAIR_KEY_DIST_ID;
    ble_hs_cfg.sm_their_key_dist |= BLE_SM_PAIR_KEY_DIST_ENC | BLE_SM_PAIR_KEY_DIST_ID;

    ble_svc_gap_init();
    ble_svc_gatt_init();
    rc = ble_svc_gap_device_name_set(s_ble.device_name);
    ESP_RETURN_ON_FALSE(rc == 0, ESP_FAIL, TAG, "gap name set failed");

    rc = ble_gatts_count_cfg(beet_ble_svcs);
    ESP_RETURN_ON_FALSE(rc == 0, ESP_FAIL, TAG, "gatt count failed");
    rc = ble_gatts_add_svcs(beet_ble_svcs);
    ESP_RETURN_ON_FALSE(rc == 0, ESP_FAIL, TAG, "gatt add failed");

    ble_store_config_init();
    nimble_port_freertos_init(beet_ble_host_task);

    s_ble.initialized = true;
    s_ble.enabled = true;
    beet_ble_log_diag_status("init_complete");
    return ESP_OK;
}

void beet_ble_get_diag_status(beet_ble_diag_status_t *status)
{
    beet_ble_fill_diag_status(status);
}

void beet_ble_get_pairing_display(beet_ble_pairing_display_t *display)
{
    beet_ble_fill_pairing_display(display);
}

void beet_ble_set_enabled(bool enabled)
{
    bool requested = enabled;

    if (!s_ble.initialized) {
        ESP_LOGW(TAG, "set_enabled ignored before init requested=%d", enabled);
        return;
    }

#if BEET_BLE_FORCE_ENABLE_DIAGNOSTICS
    enabled = true;
#endif

    ESP_LOGI(
        TAG,
        "set_enabled requested=%d effective=%d previous=%d force_enable=%d",
        requested,
        enabled,
        s_ble.enabled,
        BEET_BLE_FORCE_ENABLE_DIAGNOSTICS);
    s_ble.enabled = enabled;
    if (!enabled) {
        beet_ble_clear_result_send_state();
        beet_ble_stop_advertising();
        beet_ble_disconnect();
        beet_ble_log_diag_status("set_enabled_false");
        return;
    }

    beet_ble_log_diag_status("set_enabled_true");
    beet_ble_advertise();
}

void beet_ble_set_system_event_callback(beet_ble_system_event_callback_t callback)
{
    s_ble.system_event_callback = callback;
}

esp_err_t beet_ble_clear_bonds(uint16_t *removed_count)
{
    ble_addr_t bonded_peers[BEET_BLE_BOND_SCAN_CAPACITY];
    int peer_count = 0;
    bool truncated = false;
    int rc;

    ESP_RETURN_ON_FALSE(s_ble.initialized, ESP_ERR_INVALID_STATE, TAG, "ble not initialized");

    rc = ble_store_util_bonded_peers(
        bonded_peers,
        &peer_count,
        (int)BEET_BLE_BOND_SCAN_CAPACITY);
    if (rc != 0 && rc != BLE_HS_ENOMEM) {
        ESP_LOGW(TAG, "bonded peer enumeration failed rc=%d", rc);
        return ESP_FAIL;
    }
    truncated = (rc == BLE_HS_ENOMEM);

    rc = ble_store_clear();
    if (rc != 0) {
        ESP_LOGW(TAG, "bond clear failed rc=%d", rc);
        return ESP_FAIL;
    }

    if (removed_count != NULL) {
        *removed_count = truncated
            ? (uint16_t)BEET_BLE_BOND_SCAN_CAPACITY
            : (uint16_t)peer_count;
    }

    s_ble.bonded = false;
    s_ble.state_stream_subscribed = false;
    s_ble.command_result_subscribed = false;
    beet_ble_clear_result_send_state();
    s_ble.have_last_device_state = false;
    s_ble.have_last_pair_states = false;
    s_ble.initial_sync_pending = false;
    beet_ble_clear_pairing_display("bonds_cleared");
    beet_ble_disconnect();
    return ESP_OK;
}

void beet_ble_publish_system_event(const beet_system_event_record_t *event, uint32_t unix_s)
{
    char json[BEET_BLE_JSON_MAX_LEN];
    int written;

    if (event == NULL || !s_ble.enabled || !s_ble.connected || !s_ble.bonded || !s_ble.state_stream_subscribed) {
        return;
    }

    written = beet_ble_format_system_event_frame_json(json, sizeof(json), event, unix_s);
    if (written < 0 || (size_t)written >= sizeof(json)) {
        ESP_LOGW(TAG, "system event frame too large");
        return;
    }

    (void)beet_ble_send_notify_json(s_state_stream_handle, json);
}

void beet_ble_service(void)
{
    if (!s_ble.initialized) {
        return;
    }

    beet_ble_service_pairing_display();
    beet_ble_drain_commands();
    beet_ble_send_pending_result();

    if (!s_ble.enabled || !s_ble.connected || !s_ble.bonded || !s_ble.state_stream_subscribed) {
        return;
    }

    beet_ble_stream_device_state();
    for (uint8_t pair = 1U; pair <= BEET_PAIR_COUNT; ++pair) {
        beet_ble_stream_pair_state(pair);
    }
    s_ble.have_last_pair_states = true;
    s_ble.initial_sync_pending = false;
}

#ifdef BEET_HOST_TEST
void beet_ble_host_test_reset(void)
{
    if (s_ble.command_queue != NULL) {
        xQueueDelete(s_ble.command_queue);
    }

    memset(&s_ble, 0, sizeof(s_ble));
    s_ble.initialized = true;
    s_ble.enabled = true;
    s_ble.connected = true;
    s_ble.bonded = true;
    s_ble.command_result_subscribed = true;
    s_ble.conn_handle = 1U;
    s_ble.command_queue = xQueueCreate(BEET_BLE_COMMAND_QUEUE_LEN, sizeof(beet_iface_command_request_t));
    s_command_result_handle = 23U;
    s_state_stream_handle = 22U;
    s_control_point_handle = 21U;
    s_controller_info_handle = 20U;
    beet_ble_reset_result_tx_state();
}

void beet_ble_host_test_set_session(
    bool connected,
    bool bonded,
    bool subscribed,
    uint16_t conn_handle,
    uint16_t command_result_handle)
{
    s_ble.connected = connected;
    s_ble.bonded = bonded;
    s_ble.command_result_subscribed = subscribed;
    s_ble.conn_handle = conn_handle;
    s_command_result_handle = command_result_handle;
}

void beet_ble_host_test_set_pending_result(const beet_iface_command_response_t *response)
{
    if (response == NULL) {
        s_ble.pending_result_valid = false;
        memset(&s_ble.pending_result, 0, sizeof(s_ble.pending_result));
        return;
    }

    s_ble.pending_result = *response;
    s_ble.pending_result_valid = true;
}

void beet_ble_host_test_notify_tx(int status)
{
    struct ble_gap_event event;

    memset(&event, 0, sizeof(event));
    event.type = BLE_GAP_EVENT_NOTIFY_TX;
    event.notify_tx.conn_handle = s_ble.conn_handle;
    event.notify_tx.indication = 1U;
    event.notify_tx.attr_handle = s_command_result_handle;
    event.notify_tx.status = status;
    (void)beet_ble_gap_event(&event, NULL);
}

void beet_ble_host_test_disconnect(void)
{
    struct ble_gap_event event;

    memset(&event, 0, sizeof(event));
    event.type = BLE_GAP_EVENT_DISCONNECT;
    event.disconnect.conn.conn_handle = s_ble.conn_handle;
    event.disconnect.conn.sec_state.bonded = s_ble.bonded;
    (void)beet_ble_gap_event(&event, NULL);
}

void beet_ble_host_test_set_command_result_subscription(bool subscribed)
{
    struct ble_gap_event event;

    memset(&event, 0, sizeof(event));
    event.type = BLE_GAP_EVENT_SUBSCRIBE;
    event.subscribe.attr_handle = s_command_result_handle;
    event.subscribe.conn_handle = s_ble.conn_handle;
    event.subscribe.cur_indicate = subscribed ? 1U : 0U;
    (void)beet_ble_gap_event(&event, NULL);
}

bool beet_ble_host_test_result_active(void)
{
    return s_ble.result_tx.mode != BEET_BLE_RESULT_TX_IDLE;
}

bool beet_ble_host_test_result_in_flight(void)
{
    return s_ble.result_tx.indication_in_flight;
}

uint16_t beet_ble_host_test_result_chunk_index(void)
{
    return s_ble.result_tx.chunk_index;
}

uint16_t beet_ble_host_test_result_chunk_count(void)
{
    return s_ble.result_tx.chunk_count;
}
#endif
