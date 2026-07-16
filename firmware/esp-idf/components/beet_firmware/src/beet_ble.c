#include "beet_ble.h"

#include <ctype.h>
#include <inttypes.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "esp_app_desc.h"
#include "esp_check.h"
#include "esp_log.h"
#include "esp_ota_ops.h"
#include "esp_partition.h"
#include "esp_random.h"
#include "esp_system.h"
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
#include "beet_maintenance.h"
#include "beet_maintenance_tlv.h"

static const char *TAG = "beet_ble";

#define BEET_BLE_COMMAND_QUEUE_LEN 4U
// BEET_BLE_JSON_MAX_LEN defined in beet_ble_codec.h
#define BEET_BLE_MAINTENANCE_CONTROL_JSON_MAX_LEN 512U
#define BEET_BLE_RESULT_STAGE_MAX_LEN 1024U
#define BEET_BLE_RESULT_STAGE_BASE64_MAX_LEN ((((BEET_BLE_RESULT_STAGE_MAX_LEN) + 2U) / 3U) * 4U + 1U)
#define BEET_BLE_RESULT_FRAME_MAX_LEN BEET_BLE_RESULT_STAGE_MAX_LEN
#define BEET_BLE_COMMAND_RATE_WINDOW_US (1000000LL)
#define BEET_BLE_REAL_COMMAND_RATE_MAX_PER_WINDOW 4U
#define BEET_BLE_SYNC_READ_RATE_MAX_PER_WINDOW 12U
#define BEET_BLE_STATE_STREAM_MIN_INTERVAL_US (1000000LL)
#define BEET_BLE_MAINTENANCE_CHUNK_QUEUE_CAPACITY 256U
#define BEET_BLE_PAIRING_DISPLAY_TIMEOUT_US (30LL * 1000000LL)
#define BEET_BLE_PAIRING_CODE_MAX 1000000U
#define BEET_BLE_BOND_SCAN_CAPACITY 16U

#if BEET_BLE_FORCE_ENABLE_DIAGNOSTICS
#define BEET_BLE_DIAGNOSTIC_VERBOSE 1
#else
#define BEET_BLE_DIAGNOSTIC_VERBOSE 0
#endif
#define BEET_BLE_MAINTENANCE_SESSION_RESUME_TIMEOUT_US (15LL * 60LL * 1000000LL)
#define BEET_BLE_MAINTENANCE_REBOOT_CONFIRM_GRACE_US (100000LL)
#define BEET_BLE_MAINTENANCE_REBOOT_FALLBACK_US (2000000LL)

typedef enum {
    BEET_BLE_RESULT_TX_IDLE = 0,
    BEET_BLE_RESULT_TX_SINGLE_PENDING,
    BEET_BLE_RESULT_TX_CHUNKED_PENDING,
} beet_ble_result_tx_mode_t;

typedef struct {
    uint32_t state[8];
    uint64_t bit_count;
    uint8_t buffer[64];
    size_t buffer_len;
} beet_ble_sha256_context_t;

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
    uint32_t session_id;
    uint32_t offset;
    size_t payload_len;
    uint8_t payload[BEET_BLE_JSON_MAX_LEN];
} beet_ble_maintenance_chunk_t;

typedef struct {
    bool active;
    bool disconnected_waiting_resume;
    bool reconnect_event_pending;
    bool ota_handle_active;
    bool reboot_pending;
    bool abort_request_pending;
    bool finish_request_pending;
    bool status_indication_pending;
    bool status_indication_in_flight;
    bool conn_param_update_requested;
    beet_maintenance_status_t status;
    int64_t resume_expires_at_us;
    int64_t reboot_due_at_us;
    const esp_partition_t *target_partition;
    esp_ota_handle_t ota_handle;
    beet_ble_sha256_context_t image_sha256_context;
    beet_maintenance_begin_update_request_t begin_request;
    uint16_t chunk_queue_head;
    uint16_t chunk_queue_tail;
    uint16_t chunk_queue_count;
    beet_ble_maintenance_chunk_t chunk_queue[BEET_BLE_MAINTENANCE_CHUNK_QUEUE_CAPACITY];
} beet_ble_maintenance_session_state_t;

typedef struct {
    bool initialized;
    bool host_synced;
    bool enabled;
    bool advertising;
    bool connected;
    bool bonded;
    bool state_stream_subscribed;
    bool command_result_subscribed;
    bool maintenance_status_subscribed;
    bool initial_sync_pending;
    uint8_t initial_sync_next_pair;
    uint8_t state_stream_next_pair;
    int64_t state_stream_next_allowed_us;
    uint8_t own_addr_type;
    uint16_t conn_handle;
    char device_name[BEET_DEVICE_ID_MAX_LEN + 1U];
    QueueHandle_t command_queue;
    beet_iface_device_state_t last_device_state;
    beet_iface_pair_state_t last_pair_states[BEET_PAIR_COUNT];
    bool have_last_device_state;
    bool have_last_pair_states;
    bool force_send_device;
    bool force_send_pair[BEET_PAIR_COUNT];
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
    beet_ble_maintenance_session_state_t maintenance_session;
    bool maintenance_terminal_status_valid;
    beet_maintenance_status_t maintenance_terminal_status;
    uint32_t next_maintenance_session_id;
    bool maintenance_begin_request_pending;
    bool maintenance_begin_invalidates_active_session;
    uint32_t maintenance_begin_invalidated_session_id;
    beet_maintenance_begin_update_request_t maintenance_begin_request;
} beet_ble_state_t;

static beet_ble_state_t s_ble;
void ble_store_config_init(void);

static uint16_t s_controller_info_handle;
static uint16_t s_state_stream_handle;
static uint16_t s_control_point_handle;
static uint16_t s_command_result_handle;
static uint16_t s_maintenance_info_handle;
static uint16_t s_maintenance_control_handle;
static uint16_t s_maintenance_status_handle;
static uint16_t s_maintenance_data_handle;

static void beet_ble_reset_state_stream_sync(void);
static void beet_ble_start_initial_state_stream_sync(void);

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
static const ble_uuid128_t BEET_BLE_MAINTENANCE_SERVICE_UUID =
    BLE_UUID128_INIT(0xb0, 0xc4, 0x94, 0x7d, 0x2a, 0x3f, 0x57, 0x9d,
                     0x6b, 0x4a, 0x7a, 0x6d, 0x06, 0x00, 0x2a, 0x8f);
static const ble_uuid128_t BEET_BLE_MAINTENANCE_INFO_UUID =
    BLE_UUID128_INIT(0xb0, 0xc4, 0x94, 0x7d, 0x2a, 0x3f, 0x57, 0x9d,
                     0x6b, 0x4a, 0x7a, 0x6d, 0x07, 0x00, 0x2a, 0x8f);
static const ble_uuid128_t BEET_BLE_MAINTENANCE_CONTROL_UUID =
    BLE_UUID128_INIT(0xb0, 0xc4, 0x94, 0x7d, 0x2a, 0x3f, 0x57, 0x9d,
                     0x6b, 0x4a, 0x7a, 0x6d, 0x08, 0x00, 0x2a, 0x8f);
static const ble_uuid128_t BEET_BLE_MAINTENANCE_STATUS_UUID =
    BLE_UUID128_INIT(0xb0, 0xc4, 0x94, 0x7d, 0x2a, 0x3f, 0x57, 0x9d,
                     0x6b, 0x4a, 0x7a, 0x6d, 0x09, 0x00, 0x2a, 0x8f);
static const ble_uuid128_t BEET_BLE_MAINTENANCE_DATA_UUID =
    BLE_UUID128_INIT(0xb0, 0xc4, 0x94, 0x7d, 0x2a, 0x3f, 0x57, 0x9d,
                     0x6b, 0x4a, 0x7a, 0x6d, 0x0a, 0x00, 0x2a, 0x8f);

static int beet_ble_gatt_access(
    uint16_t conn_handle,
    uint16_t attr_handle,
    struct ble_gatt_access_ctxt *ctxt,
    void *arg);
static int beet_ble_maintenance_gatt_access(
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
static size_t beet_ble_att_payload_budget(void);
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
static void beet_ble_on_maintenance_status_indication_complete(int status);
static void beet_ble_send_pending_result(void);
static esp_err_t beet_ble_send_maintenance_status_indication(void);
static void beet_ble_fill_maintenance_status(beet_maintenance_status_t *status);
static void beet_ble_note_maintenance_reconnect_if_pending(void);
static void beet_ble_clear_maintenance_session(void);
static void beet_ble_set_maintenance_terminal_status(
    beet_maintenance_state_t state,
    beet_maintenance_failure_reason_t reason,
    bool has_failure_reason,
    bool preserve_session_id,
    uint32_t session_id);
static void beet_ble_emit_maintenance_event(
    beet_system_event_type_t type,
    uint16_t reason,
    uint32_t detail);
static bool beet_ble_is_runtime_mutating_command(beet_iface_command_t command);
static bool beet_ble_is_update_eligible(
    const beet_maintenance_begin_update_request_t *request,
    beet_maintenance_failure_reason_t *failure_reason);
static bool beet_ble_metadata_sets_match(
    const beet_maintenance_image_metadata_t *metadata,
    const beet_maintenance_begin_update_request_t *request);
static esp_err_t beet_ble_read_partition_metadata(
    const esp_partition_t *partition,
    size_t image_size,
    beet_maintenance_image_metadata_t *metadata_out);
static bool beet_ble_parse_chunk_header(
    const uint8_t *data,
    size_t len,
    uint32_t *session_id_out,
    uint32_t *offset_out,
    const uint8_t **payload_out,
    size_t *payload_len_out);
static bool beet_ble_queue_maintenance_chunk(
    uint32_t session_id,
    uint32_t offset,
    const uint8_t *payload,
    size_t payload_len);
static bool beet_ble_peek_maintenance_chunk(beet_ble_maintenance_chunk_t *chunk_out);
static void beet_ble_pop_maintenance_chunk(void);
static void beet_ble_format_sha256_hex(const uint8_t digest[32], char out[BEET_MAINTENANCE_SHA256_HEX_LEN + 1U]);
static void beet_ble_image_sha256_init(beet_ble_sha256_context_t *context);
static void beet_ble_image_sha256_update(
    beet_ble_sha256_context_t *context,
    const uint8_t *data,
    size_t len);
static void beet_ble_image_sha256_finish(
    beet_ble_sha256_context_t *context,
    uint8_t digest[32]);
static void beet_ble_service_maintenance_session(void);
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
#if BEET_BLE_FORCE_ENABLE_DIAGNOSTICS
                .flags = BLE_GATT_CHR_F_READ,
#else
                .flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_READ_ENC,
#endif
                .val_handle = &s_controller_info_handle,
            },
            {
                .uuid = &BEET_BLE_STATE_STREAM_UUID.u,
                .access_cb = beet_ble_gatt_access,
#if BEET_BLE_FORCE_ENABLE_DIAGNOSTICS
                .flags = BLE_GATT_CHR_F_NOTIFY,
#else
                .flags = BLE_GATT_CHR_F_NOTIFY | BLE_GATT_CHR_F_READ_ENC,
#endif
                .val_handle = &s_state_stream_handle,
            },
            {
                .uuid = &BEET_BLE_CONTROL_POINT_UUID.u,
                .access_cb = beet_ble_gatt_access,
#if BEET_BLE_FORCE_ENABLE_DIAGNOSTICS
                .flags = BLE_GATT_CHR_F_WRITE,
#else
                .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_WRITE_ENC,
#endif
                .val_handle = &s_control_point_handle,
            },
            {
                .uuid = &BEET_BLE_COMMAND_RESULT_UUID.u,
                .access_cb = beet_ble_gatt_access,
#if BEET_BLE_FORCE_ENABLE_DIAGNOSTICS
                .flags = BLE_GATT_CHR_F_INDICATE,
#else
                .flags = BLE_GATT_CHR_F_INDICATE | BLE_GATT_CHR_F_READ_ENC,
#endif
                .val_handle = &s_command_result_handle,
            },
            { 0 },
        },
    },
    {
        .type = BLE_GATT_SVC_TYPE_PRIMARY,
        .uuid = &BEET_BLE_MAINTENANCE_SERVICE_UUID.u,
        .characteristics = (struct ble_gatt_chr_def[]) {
            {
                .uuid = &BEET_BLE_MAINTENANCE_INFO_UUID.u,
                .access_cb = beet_ble_maintenance_gatt_access,
                .flags = BLE_GATT_CHR_F_READ,
                .val_handle = &s_maintenance_info_handle,
            },
            {
                .uuid = &BEET_BLE_MAINTENANCE_CONTROL_UUID.u,
                .access_cb = beet_ble_maintenance_gatt_access,
                .flags = BLE_GATT_CHR_F_WRITE,
                .val_handle = &s_maintenance_control_handle,
            },
            {
                .uuid = &BEET_BLE_MAINTENANCE_STATUS_UUID.u,
                .access_cb = beet_ble_maintenance_gatt_access,
                .flags = BLE_GATT_CHR_F_INDICATE,
                .val_handle = &s_maintenance_status_handle,
            },
            {
                .uuid = &BEET_BLE_MAINTENANCE_DATA_UUID.u,
                .access_cb = beet_ble_maintenance_gatt_access,
                .flags = BLE_GATT_CHR_F_WRITE,
                .val_handle = &s_maintenance_data_handle,
            },
            { 0 },
        },
    },
    { 0 },
};

#define BEET_BLE_BATTERY_MV_NOTIFY_DELTA 10U
#define BEET_BLE_SENSOR_MV_NOTIFY_DELTA 25U
#define BEET_BLE_NEXT_CHECK_IN_NOTIFY_BUCKET_S 60U

static bool beet_ble_states_equal(
    const beet_iface_pair_state_t *a,
    const beet_iface_pair_state_t *b)
{
    uint16_t sensor_mv_delta =
        (a->sensor_mv > b->sensor_mv) ? (uint16_t)(a->sensor_mv - b->sensor_mv) : (uint16_t)(b->sensor_mv - a->sensor_mv);

    return a->pair_index == b->pair_index &&
        a->pair_state == b->pair_state &&
        a->moisture_pct == b->moisture_pct &&
        sensor_mv_delta < BEET_BLE_SENSOR_MV_NOTIFY_DELTA &&
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
    uint16_t battery_mv_delta =
        (a->battery_mv > b->battery_mv) ? (uint16_t)(a->battery_mv - b->battery_mv) : (uint16_t)(b->battery_mv - a->battery_mv);
    uint32_t a_check_in_bucket = a->next_check_in_s / BEET_BLE_NEXT_CHECK_IN_NOTIFY_BUCKET_S;
    uint32_t b_check_in_bucket = b->next_check_in_s / BEET_BLE_NEXT_CHECK_IN_NOTIFY_BUCKET_S;

    return strcmp(a->device_id, b->device_id) == 0 &&
        a->battery_state == b->battery_state &&
        battery_mv_delta < BEET_BLE_BATTERY_MV_NOTIFY_DELTA &&
        a->time_valid == b->time_valid &&
        a->boot_id == b->boot_id &&
        a_check_in_bucket == b_check_in_bucket &&
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

static int beet_ble_require_encrypted(uint16_t conn_handle)
{
#if BEET_BLE_FORCE_ENABLE_DIAGNOSTICS
    (void)conn_handle;
    return 0;
#else
    struct ble_gap_conn_desc desc;

    if (conn_handle == BLE_HS_CONN_HANDLE_NONE) {
        return BLE_ATT_ERR_INSUFFICIENT_AUTHEN;
    }

    if (ble_gap_conn_find(conn_handle, &desc) != 0) {
        return BLE_ATT_ERR_INSUFFICIENT_AUTHEN;
    }

    return desc.sec_state.encrypted ? 0 : BLE_ATT_ERR_INSUFFICIENT_ENC;
#endif
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
        BEET_RUNTIME_PROTOCOL_VERSION,
        BEET_PAIR_COUNT);
}

static int beet_ble_format_maintenance_info(char *buf, size_t len)
{
    beet_maintenance_info_t info;
    esp_err_t err;

    err = beet_maintenance_get_info(&info);
    if (err != ESP_OK) {
        ESP_LOGW(TAG, "maintenance info unavailable err=0x%x", (unsigned)err);
        return -1;
    }

    return beet_ble_format_maintenance_info_json(buf, len, &info);
}

static void beet_ble_fill_maintenance_status(beet_maintenance_status_t *status)
{
    if (status == NULL) {
        return;
    }

    if (s_ble.maintenance_session.active) {
        *status = s_ble.maintenance_session.status;
        return;
    }
    if (s_ble.maintenance_terminal_status_valid) {
        *status = s_ble.maintenance_terminal_status;
        return;
    }

    beet_maintenance_fill_idle_status(status);
}

static int beet_ble_format_maintenance_status(char *buf, size_t len)
{
    beet_maintenance_status_t status;

    beet_ble_fill_maintenance_status(&status);
    return beet_ble_format_maintenance_status_json(buf, len, &status);
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

static int beet_ble_read_maintenance_info(struct ble_gatt_access_ctxt *ctxt)
{
    char json[BEET_BLE_JSON_MAX_LEN];
    int written;

    written = beet_ble_format_maintenance_info(json, sizeof(json));
    if (written < 0) {
        ESP_LOGW(TAG, "maintenance_info format failed written=%d", written);
        return BLE_ATT_ERR_INSUFFICIENT_RES;
    }

    if (os_mbuf_append(ctxt->om, json, (uint16_t)written) != 0) {
        ESP_LOGW(TAG, "maintenance_info mbuf append fail written=%d", written);
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

    if (beet_ble_maintenance_runtime_blocking() &&
        beet_ble_is_runtime_mutating_command(request.command)) {
        beet_ble_set_immediate_rejection(&request, BEET_IFACE_REASON_OTA_IN_PROGRESS);
        beet_ble_mark_activity();
        return 0;
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

static int beet_ble_write_maintenance_control(
    uint16_t conn_handle,
    struct ble_gatt_access_ctxt *ctxt)
{
    char json[BEET_BLE_MAINTENANCE_CONTROL_JSON_MAX_LEN];
    uint16_t copied = 0U;
    beet_maintenance_request_t request;

    ESP_LOGI(TAG, "maintenance control write: starting conn=%u", (unsigned)conn_handle);

#if !BEET_BLE_FORCE_ENABLE_DIAGNOSTICS
    if (beet_ble_require_encrypted(conn_handle) != 0) {
        ESP_LOGW(TAG, "maintenance control rejected: conn_handle=%u not encrypted", conn_handle);
        return BLE_ATT_ERR_INSUFFICIENT_AUTHEN;
    }
#endif
    if (OS_MBUF_PKTLEN(ctxt->om) >= sizeof(json)) {
        ESP_LOGW(
            TAG,
            "maintenance control rejected: payload too large len=%u max=%u",
            (unsigned)OS_MBUF_PKTLEN(ctxt->om),
            (unsigned)(sizeof(json) - 1U));
        return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
    }
    if (ble_hs_mbuf_to_flat(ctxt->om, json, sizeof(json) - 1U, &copied) != 0) {
        ESP_LOGW(TAG, "maintenance control rejected: flatten failed len=%u", (unsigned)OS_MBUF_PKTLEN(ctxt->om));
        return BLE_ATT_ERR_UNLIKELY;
    }
    json[copied] = '\0';

    if (!beet_ble_parse_maintenance_request_json(json, &request)) {
        ESP_LOGW(TAG, "maintenance control rejected: parse failed json=%s", json);
        return BLE_ATT_ERR_UNLIKELY;
    }
    if (!s_ble.maintenance_status_subscribed) {
        ESP_LOGW(
            TAG,
            "maintenance control rejected: status not subscribed cmd=%d connected=%d bonded=%d",
            (int)request.command,
            (int)s_ble.connected,
            (int)s_ble.bonded);
        return BLE_ATT_ERR_UNLIKELY;
    }

    switch (request.command) {
    case BEET_MAINTENANCE_COMMAND_QUERY_STATUS:
        beet_ble_note_maintenance_reconnect_if_pending();
        if (beet_ble_send_maintenance_status_indication() != ESP_OK) {
            return BLE_ATT_ERR_UNLIKELY;
        }
        beet_ble_mark_activity();
        return 0;

    case BEET_MAINTENANCE_COMMAND_BEGIN_UPDATE: {
        beet_maintenance_failure_reason_t failure_reason = BEET_MAINTENANCE_FAILURE_NONE;
        bool had_active_session = s_ble.maintenance_session.active;
        uint32_t previous_session_id = had_active_session ? s_ble.maintenance_session.status.session_id : 0U;

        if (!beet_ble_is_update_eligible(&request.begin_update, &failure_reason)) {
            ESP_LOGW(
                TAG,
                "BEGIN_UPDATE rejected: failure=%s size=%lu product=%s build=%s asset=%s image_kind=%d compat_count=%u",
                beet_maintenance_failure_reason_name(failure_reason),
                (unsigned long)request.begin_update.image_size,
                request.begin_update.product_id,
                request.begin_update.build_label,
                request.begin_update.asset_id,
                (int)request.begin_update.image_kind,
                (unsigned)request.begin_update.hardware_rev_count);
            beet_ble_set_maintenance_terminal_status(
                BEET_MAINTENANCE_STATE_FAILED,
                failure_reason,
                true,
                false,
                0U);
            (void)beet_ble_send_maintenance_status_indication();
            return 0;
        }

        s_ble.maintenance_begin_request = request.begin_update;
        s_ble.maintenance_begin_request_pending = true;
        s_ble.maintenance_begin_invalidates_active_session = had_active_session;
        s_ble.maintenance_begin_invalidated_session_id = previous_session_id;
        ESP_LOGI(
            TAG,
            "BEGIN_UPDATE queued invalidates_active=%d previous_session_id=%lu image_size=%lu",
            (int)had_active_session,
            (unsigned long)previous_session_id,
            (unsigned long)request.begin_update.image_size);
        beet_ble_mark_activity();
        return 0;
    }

    case BEET_MAINTENANCE_COMMAND_ABORT_UPDATE:
        beet_ble_note_maintenance_reconnect_if_pending();
        if (!s_ble.maintenance_session.active) {
            beet_ble_set_maintenance_terminal_status(
                BEET_MAINTENANCE_STATE_FAILED,
                BEET_MAINTENANCE_FAILURE_UPDATE_SESSION_NOT_FOUND,
                true,
                false,
                0U);
            (void)beet_ble_send_maintenance_status_indication();
            return 0;
        }
        s_ble.maintenance_session.abort_request_pending = true;
        beet_ble_mark_activity();
        return 0;

    case BEET_MAINTENANCE_COMMAND_FINISH_UPDATE:
        beet_ble_note_maintenance_reconnect_if_pending();
        if (!s_ble.maintenance_session.active) {
            beet_ble_set_maintenance_terminal_status(
                BEET_MAINTENANCE_STATE_FAILED,
                BEET_MAINTENANCE_FAILURE_UPDATE_SESSION_NOT_FOUND,
                true,
                false,
                0U);
            (void)beet_ble_send_maintenance_status_indication();
            return 0;
        }
        if (s_ble.maintenance_session.status.bytes_received !=
            s_ble.maintenance_session.status.total_bytes) {
            uint32_t session_id = s_ble.maintenance_session.status.session_id;
            beet_ble_set_maintenance_terminal_status(
                BEET_MAINTENANCE_STATE_FAILED,
                BEET_MAINTENANCE_FAILURE_IMAGE_UPLOAD_INCOMPLETE,
                true,
                true,
                session_id);
            beet_ble_clear_maintenance_session();
            beet_ble_emit_maintenance_event(
                BEET_SYSTEM_EVENT_UPDATE_FAILED,
                (uint16_t)BEET_MAINTENANCE_FAILURE_IMAGE_UPLOAD_INCOMPLETE,
                session_id);
            (void)beet_ble_send_maintenance_status_indication();
            beet_ble_mark_activity();
            return 0;
        }
        s_ble.maintenance_session.finish_request_pending = true;
        beet_ble_mark_activity();
        return 0;

    default:
        return BLE_ATT_ERR_UNLIKELY;
    }
}

static int beet_ble_write_maintenance_data(
    uint16_t conn_handle,
    struct ble_gatt_access_ctxt *ctxt)
{
    uint8_t chunk_buf[BEET_BLE_JSON_MAX_LEN];
    uint16_t copied = 0U;
    uint32_t session_id = 0U;
    uint32_t offset = 0U;
    const uint8_t *payload = NULL;
    size_t payload_len = 0U;

    // P5 SUB #R37 debug: log every data write entry so we can see
    // if the callback is even being invoked after a BLE reconnect.
    ESP_LOGI(TAG, "data write entered conn_handle=%u om_len=%u active=%d ota_active=%d reboot_pending=%d",
             (unsigned)conn_handle,
             (unsigned)OS_MBUF_PKTLEN(ctxt->om),
             (int)s_ble.maintenance_session.active,
             (int)s_ble.maintenance_session.ota_handle_active,
             (int)s_ble.maintenance_session.reboot_pending);

    if (beet_ble_require_encrypted(conn_handle) != 0) {
        ESP_LOGW(TAG, "data write rejected: conn_handle=%u not encrypted", conn_handle);
        return BLE_ATT_ERR_INSUFFICIENT_AUTHEN;
    }
    if (!s_ble.maintenance_session.active) {
        ESP_LOGW(TAG, "data write rejected: no active maintenance session");
        return BLE_ATT_ERR_UNLIKELY;
    }
    if (!s_ble.maintenance_session.ota_handle_active) {
        ESP_LOGW(TAG, "data write rejected: ota_handle_active=%d",
                 (int)s_ble.maintenance_session.ota_handle_active);
        return BLE_ATT_ERR_UNLIKELY;
    }
    if (s_ble.maintenance_session.reboot_pending) {
        ESP_LOGW(TAG, "data write rejected: reboot pending session_id=%lu",
                 (unsigned long)s_ble.maintenance_session.status.session_id);
        return BLE_ATT_ERR_UNLIKELY;
    }
    if (OS_MBUF_PKTLEN(ctxt->om) > sizeof(chunk_buf)) {
        ESP_LOGW(TAG, "data write rejected: payload too large len=%u max=%u",
                 (unsigned)OS_MBUF_PKTLEN(ctxt->om),
                 (unsigned)sizeof(chunk_buf));
        return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
    }
    if (ble_hs_mbuf_to_flat(ctxt->om, chunk_buf, sizeof(chunk_buf), &copied) != 0) {
        ESP_LOGW(TAG, "data write rejected: flatten failed len=%u",
                 (unsigned)OS_MBUF_PKTLEN(ctxt->om));
        return BLE_ATT_ERR_UNLIKELY;
    }
    if (!beet_ble_parse_chunk_header(
            chunk_buf,
            copied,
            &session_id,
            &offset,
            &payload,
            &payload_len)) {
        ESP_LOGW(TAG, "data write rejected: parse failed copied=%u", (unsigned)copied);
        return BLE_ATT_ERR_UNLIKELY;
    }
    beet_ble_note_maintenance_reconnect_if_pending();
    if (session_id != s_ble.maintenance_session.status.session_id ||
        offset != s_ble.maintenance_session.status.next_offset ||
        s_ble.maintenance_session.status.bytes_received + payload_len >
            s_ble.maintenance_session.status.total_bytes) {
        ESP_LOGW(
            TAG,
            "data write rejected session_id=%lu expected_session_id=%lu offset=%lu expected_offset=%lu bytes=%lu payload_len=%lu total=%lu",
            (unsigned long)session_id,
            (unsigned long)s_ble.maintenance_session.status.session_id,
            (unsigned long)offset,
            (unsigned long)s_ble.maintenance_session.status.next_offset,
            (unsigned long)s_ble.maintenance_session.status.bytes_received,
            (unsigned long)payload_len,
            (unsigned long)s_ble.maintenance_session.status.total_bytes);
        return BLE_ATT_ERR_UNLIKELY;
    }
    if (!beet_ble_queue_maintenance_chunk(session_id, offset, payload, payload_len)) {
        ESP_LOGW(TAG, "data write rejected queue full session_id=%lu offset=%lu payload_len=%lu",
                 (unsigned long)session_id,
                 (unsigned long)offset,
                 (unsigned long)payload_len);
        return BLE_ATT_ERR_UNLIKELY;
    }

    s_ble.maintenance_session.status.state = BEET_MAINTENANCE_STATE_TRANSFERRING;
    s_ble.maintenance_session.status.bytes_received += (uint32_t)payload_len;
    s_ble.maintenance_session.status.next_offset += (uint32_t)payload_len;
    s_ble.maintenance_session.status.total_bytes = s_ble.maintenance_session.begin_request.image_size;
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

    if (beet_ble_require_encrypted(conn_handle) != 0) {
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

static int beet_ble_maintenance_gatt_access(
    uint16_t conn_handle,
    uint16_t attr_handle,
    struct ble_gatt_access_ctxt *ctxt,
    void *arg)
{
    (void)arg;

    // P5 SUB #R37 debug: log every GATT access to the maintenance
    // service so we can see if data writes are even reaching the
    // GATT server after a BLE reconnect.
    ESP_LOGI(TAG, "maint_gatt_access op=%d attr_handle=%u data_handle=%u ctrl_handle=%u",
             (int)ctxt->op, (unsigned)attr_handle,
             (unsigned)s_maintenance_data_handle,
             (unsigned)s_maintenance_control_handle);

    switch (ctxt->op) {
    case BLE_GATT_ACCESS_OP_READ_CHR:
        if (attr_handle == s_maintenance_info_handle) {
            return beet_ble_read_maintenance_info(ctxt);
        }
        if (beet_ble_require_encrypted(conn_handle) != 0) {
            return BLE_ATT_ERR_INSUFFICIENT_AUTHEN;
        }
        return BLE_ATT_ERR_UNLIKELY;

    case BLE_GATT_ACCESS_OP_WRITE_CHR:
        if (attr_handle == s_maintenance_control_handle) {
            return beet_ble_write_maintenance_control(conn_handle, ctxt);
        }
        if (attr_handle == s_maintenance_data_handle) {
            return beet_ble_write_maintenance_data(conn_handle, ctxt);
        }
        return BLE_ATT_ERR_UNLIKELY;

    default:
        return BLE_ATT_ERR_UNLIKELY;
    }
}

static void beet_ble_emit_maintenance_event(
    beet_system_event_type_t type,
    uint16_t reason,
    uint32_t detail)
{
    beet_ble_emit_system_event(
        type,
        reason,
        s_ble.connected ? s_ble.conn_handle : BLE_HS_CONN_HANDLE_NONE,
        s_ble.bonded,
        detail);
}

static void beet_ble_clear_maintenance_session(void)
{
    if (s_ble.maintenance_session.ota_handle_active) {
        (void)esp_ota_abort(s_ble.maintenance_session.ota_handle);
    }
    memset(&s_ble.maintenance_session, 0, sizeof(s_ble.maintenance_session));
}

static void beet_ble_set_maintenance_terminal_status(
    beet_maintenance_state_t state,
    beet_maintenance_failure_reason_t reason,
    bool has_failure_reason,
    bool preserve_session_id,
    uint32_t session_id)
{
    memset(&s_ble.maintenance_terminal_status, 0, sizeof(s_ble.maintenance_terminal_status));
    s_ble.maintenance_terminal_status.state = state;
    s_ble.maintenance_terminal_status.has_failure_reason = has_failure_reason;
    s_ble.maintenance_terminal_status.failure_reason = reason;
    if (preserve_session_id) {
        s_ble.maintenance_terminal_status.has_session_id = true;
        s_ble.maintenance_terminal_status.session_id = session_id;
    }
    s_ble.maintenance_terminal_status_valid = true;
}

static void beet_ble_note_maintenance_reconnect_if_pending(void)
{
    if (!s_ble.maintenance_session.active ||
        !s_ble.maintenance_session.disconnected_waiting_resume) {
        return;
    }

    s_ble.maintenance_session.disconnected_waiting_resume = false;
    s_ble.maintenance_session.resume_expires_at_us = 0LL;
    if (s_ble.maintenance_session.reconnect_event_pending) {
        beet_ble_emit_maintenance_event(
            BEET_SYSTEM_EVENT_UPDATE_RECONNECT,
            0U,
            s_ble.maintenance_session.status.session_id);
        s_ble.maintenance_session.reconnect_event_pending = false;
    }
}

static bool beet_ble_metadata_sets_match(
    const beet_maintenance_image_metadata_t *metadata,
    const beet_maintenance_begin_update_request_t *request)
{
    if (metadata == NULL || request == NULL) {
        return false;
    }
    if (strcmp(metadata->firmware_version, request->firmware_version) != 0 ||
        strcmp(metadata->build_label, request->build_label) != 0 ||
        strcmp(metadata->product_id, request->product_id) != 0 ||
        metadata->image_kind != request->image_kind ||
        metadata->compatible_hardware_rev_count != request->hardware_rev_count) {
        return false;
    }
    if (request->has_runtime_protocol_version &&
        metadata->runtime_protocol_version != request->runtime_protocol_version) {
        return false;
    }

    for (uint8_t i = 0U; i < request->hardware_rev_count; ++i) {
        bool found = false;
        for (uint8_t j = 0U; j < metadata->compatible_hardware_rev_count; ++j) {
            if (strcmp(request->hardware_revs[i], metadata->compatible_hardware_revs[j]) == 0) {
                found = true;
                break;
            }
        }
        if (!found) {
            return false;
        }
    }

    return true;
}

static esp_err_t beet_ble_read_partition_metadata(
    const esp_partition_t *partition,
    size_t image_size,
    beet_maintenance_image_metadata_t *metadata_out)
{
    uint8_t scan_buf[512U + 16U];
    size_t scan_offset = 0U;

    if (partition == NULL || metadata_out == NULL || image_size < 12U) {
        return ESP_ERR_INVALID_ARG;
    }

    while (scan_offset < image_size) {
        size_t read_len = sizeof(scan_buf);
        if (scan_offset + read_len > image_size) {
            read_len = image_size - scan_offset;
        }
        if (esp_partition_read(partition, scan_offset, scan_buf, read_len) != ESP_OK) {
            return ESP_FAIL;
        }

        for (size_t i = 0U; i + 12U <= read_len; ++i) {
            uint32_t magic = ((uint32_t)scan_buf[i]) |
                ((uint32_t)scan_buf[i + 1U] << 8) |
                ((uint32_t)scan_buf[i + 2U] << 16) |
                ((uint32_t)scan_buf[i + 3U] << 24);

            if (magic == BEET_MAINTENANCE_METADATA_MAGIC) {
                uint16_t total_length = (uint16_t)scan_buf[i + 6U] |
                    ((uint16_t)scan_buf[i + 7U] << 8);
                uint8_t block_buf[512];
                size_t absolute_offset = scan_offset + i;

                if (total_length < 12U || total_length > sizeof(block_buf) ||
                    absolute_offset + total_length > image_size) {
                    continue;
                }
                if (esp_partition_read(partition, absolute_offset, block_buf, total_length) != ESP_OK) {
                    return ESP_FAIL;
                }
                if (beet_maintenance_metadata_parse(block_buf, total_length, metadata_out) == ESP_OK) {
                    return ESP_OK;
                }
            }
        }

        if (read_len <= 12U) {
            break;
        }
        scan_offset += read_len - 12U;
    }

    return ESP_ERR_NOT_FOUND;
}

static const uint32_t s_beet_ble_sha256_round_constants[64] = {
    0x428a2f98U, 0x71374491U, 0xb5c0fbcfU, 0xe9b5dba5U,
    0x3956c25bU, 0x59f111f1U, 0x923f82a4U, 0xab1c5ed5U,
    0xd807aa98U, 0x12835b01U, 0x243185beU, 0x550c7dc3U,
    0x72be5d74U, 0x80deb1feU, 0x9bdc06a7U, 0xc19bf174U,
    0xe49b69c1U, 0xefbe4786U, 0x0fc19dc6U, 0x240ca1ccU,
    0x2de92c6fU, 0x4a7484aaU, 0x5cb0a9dcU, 0x76f988daU,
    0x983e5152U, 0xa831c66dU, 0xb00327c8U, 0xbf597fc7U,
    0xc6e00bf3U, 0xd5a79147U, 0x06ca6351U, 0x14292967U,
    0x27b70a85U, 0x2e1b2138U, 0x4d2c6dfcU, 0x53380d13U,
    0x650a7354U, 0x766a0abbU, 0x81c2c92eU, 0x92722c85U,
    0xa2bfe8a1U, 0xa81a664bU, 0xc24b8b70U, 0xc76c51a3U,
    0xd192e819U, 0xd6990624U, 0xf40e3585U, 0x106aa070U,
    0x19a4c116U, 0x1e376c08U, 0x2748774cU, 0x34b0bcb5U,
    0x391c0cb3U, 0x4ed8aa4aU, 0x5b9cca4fU, 0x682e6ff3U,
    0x748f82eeU, 0x78a5636fU, 0x84c87814U, 0x8cc70208U,
    0x90befffaU, 0xa4506cebU, 0xbef9a3f7U, 0xc67178f2U,
};

static uint32_t beet_ble_sha256_rotr(uint32_t value, unsigned shift)
{
    return (value >> shift) | (value << (32U - shift));
}

static void beet_ble_sha256_transform(beet_ble_sha256_context_t *context, const uint8_t block[64])
{
    uint32_t w[64];
    uint32_t a, b, c, d, e, f, g, h;

    for (size_t i = 0; i < 16U; ++i) {
        w[i] = ((uint32_t)block[i * 4U] << 24) |
            ((uint32_t)block[i * 4U + 1U] << 16) |
            ((uint32_t)block[i * 4U + 2U] << 8) |
            ((uint32_t)block[i * 4U + 3U]);
    }
    for (size_t i = 16U; i < 64U; ++i) {
        uint32_t s0 = beet_ble_sha256_rotr(w[i - 15U], 7U) ^
            beet_ble_sha256_rotr(w[i - 15U], 18U) ^
            (w[i - 15U] >> 3U);
        uint32_t s1 = beet_ble_sha256_rotr(w[i - 2U], 17U) ^
            beet_ble_sha256_rotr(w[i - 2U], 19U) ^
            (w[i - 2U] >> 10U);
        w[i] = w[i - 16U] + s0 + w[i - 7U] + s1;
    }

    a = context->state[0];
    b = context->state[1];
    c = context->state[2];
    d = context->state[3];
    e = context->state[4];
    f = context->state[5];
    g = context->state[6];
    h = context->state[7];

    for (size_t i = 0; i < 64U; ++i) {
        uint32_t s1 = beet_ble_sha256_rotr(e, 6U) ^
            beet_ble_sha256_rotr(e, 11U) ^
            beet_ble_sha256_rotr(e, 25U);
        uint32_t ch = (e & f) ^ ((~e) & g);
        uint32_t temp1 = h + s1 + ch + s_beet_ble_sha256_round_constants[i] + w[i];
        uint32_t s0 = beet_ble_sha256_rotr(a, 2U) ^
            beet_ble_sha256_rotr(a, 13U) ^
            beet_ble_sha256_rotr(a, 22U);
        uint32_t maj = (a & b) ^ (a & c) ^ (b & c);
        uint32_t temp2 = s0 + maj;

        h = g;
        g = f;
        f = e;
        e = d + temp1;
        d = c;
        c = b;
        b = a;
        a = temp1 + temp2;
    }

    context->state[0] += a;
    context->state[1] += b;
    context->state[2] += c;
    context->state[3] += d;
    context->state[4] += e;
    context->state[5] += f;
    context->state[6] += g;
    context->state[7] += h;
}

static void beet_ble_image_sha256_init(beet_ble_sha256_context_t *context)
{
    if (context == NULL) {
        return;
    }
    context->state[0] = 0x6a09e667U;
    context->state[1] = 0xbb67ae85U;
    context->state[2] = 0x3c6ef372U;
    context->state[3] = 0xa54ff53aU;
    context->state[4] = 0x510e527fU;
    context->state[5] = 0x9b05688cU;
    context->state[6] = 0x1f83d9abU;
    context->state[7] = 0x5be0cd19U;
    context->bit_count = 0U;
    context->buffer_len = 0U;
}

static void beet_ble_image_sha256_update(
    beet_ble_sha256_context_t *context,
    const uint8_t *data,
    size_t len)
{
    if (context == NULL || data == NULL || len == 0U) {
        return;
    }
    context->bit_count += (uint64_t)len * 8U;
    while (len > 0U) {
        size_t copy_len = 64U - context->buffer_len;
        if (copy_len > len) {
            copy_len = len;
        }
        memcpy(context->buffer + context->buffer_len, data, copy_len);
        context->buffer_len += copy_len;
        data += copy_len;
        len -= copy_len;

        if (context->buffer_len == 64U) {
            beet_ble_sha256_transform(context, context->buffer);
            context->buffer_len = 0U;
        }
    }
}

static void beet_ble_image_sha256_finish(
    beet_ble_sha256_context_t *context,
    uint8_t digest[32])
{
    if (context == NULL || digest == NULL) {
        return;
    }
    uint8_t length_bytes[8];
    uint8_t pad_byte = 0x80U;
    uint8_t zero = 0U;

    for (size_t i = 0; i < sizeof(length_bytes); ++i) {
        length_bytes[7U - i] = (uint8_t)((context->bit_count >> (i * 8U)) & 0xFFU);
    }

    beet_ble_image_sha256_update(context, &pad_byte, 1U);
    while (context->buffer_len != 56U) {
        beet_ble_image_sha256_update(context, &zero, 1U);
    }
    beet_ble_image_sha256_update(context, length_bytes, sizeof(length_bytes));

    for (size_t i = 0; i < 8U; ++i) {
        digest[i * 4U] = (uint8_t)(context->state[i] >> 24U);
        digest[i * 4U + 1U] = (uint8_t)(context->state[i] >> 16U);
        digest[i * 4U + 2U] = (uint8_t)(context->state[i] >> 8U);
        digest[i * 4U + 3U] = (uint8_t)(context->state[i]);
    }
}

static bool beet_ble_parse_chunk_header(
    const uint8_t *data,
    size_t len,
    uint32_t *session_id_out,
    uint32_t *offset_out,
    const uint8_t **payload_out,
    size_t *payload_len_out)
{
    if (data == NULL || session_id_out == NULL || offset_out == NULL ||
        payload_out == NULL || payload_len_out == NULL || len <= 8U) {
        return false;
    }

    *session_id_out = ((uint32_t)data[0]) |
        (((uint32_t)data[1]) << 8) |
        (((uint32_t)data[2]) << 16) |
        (((uint32_t)data[3]) << 24);
    *offset_out = ((uint32_t)data[4]) |
        (((uint32_t)data[5]) << 8) |
        (((uint32_t)data[6]) << 16) |
        (((uint32_t)data[7]) << 24);
    *payload_out = data + 8U;
    *payload_len_out = len - 8U;
    return true;
}

static bool beet_ble_queue_maintenance_chunk(
    uint32_t session_id,
    uint32_t offset,
    const uint8_t *payload,
    size_t payload_len)
{
    beet_ble_maintenance_chunk_t *chunk;

    if (payload == NULL ||
        payload_len > sizeof(s_ble.maintenance_session.chunk_queue[0].payload) ||
        s_ble.maintenance_session.chunk_queue_count >= BEET_BLE_MAINTENANCE_CHUNK_QUEUE_CAPACITY) {
        return false;
    }

    chunk = &s_ble.maintenance_session.chunk_queue[s_ble.maintenance_session.chunk_queue_tail];
    chunk->session_id = session_id;
    chunk->offset = offset;
    chunk->payload_len = payload_len;
    memcpy(chunk->payload, payload, payload_len);
    s_ble.maintenance_session.chunk_queue_tail =
        (uint8_t)((s_ble.maintenance_session.chunk_queue_tail + 1U) %
                  BEET_BLE_MAINTENANCE_CHUNK_QUEUE_CAPACITY);
    s_ble.maintenance_session.chunk_queue_count++;
    return true;
}

static bool beet_ble_peek_maintenance_chunk(beet_ble_maintenance_chunk_t *chunk_out)
{
    if (chunk_out == NULL || s_ble.maintenance_session.chunk_queue_count == 0U) {
        return false;
    }

    *chunk_out = s_ble.maintenance_session.chunk_queue[s_ble.maintenance_session.chunk_queue_head];
    return true;
}

static void beet_ble_pop_maintenance_chunk(void)
{
    if (s_ble.maintenance_session.chunk_queue_count == 0U) {
        return;
    }

    s_ble.maintenance_session.chunk_queue_head =
        (uint8_t)((s_ble.maintenance_session.chunk_queue_head + 1U) %
                  BEET_BLE_MAINTENANCE_CHUNK_QUEUE_CAPACITY);
    s_ble.maintenance_session.chunk_queue_count--;
}

static void beet_ble_format_sha256_hex(const uint8_t digest[32], char out[BEET_MAINTENANCE_SHA256_HEX_LEN + 1U])
{
    static const char hex[] = "0123456789abcdef";

    for (size_t i = 0U; i < 32U; ++i) {
        out[i * 2U] = hex[(digest[i] >> 4U) & 0x0FU];
        out[i * 2U + 1U] = hex[digest[i] & 0x0FU];
    }
    out[BEET_MAINTENANCE_SHA256_HEX_LEN] = '\0';
}

static bool beet_ble_is_runtime_mutating_command(beet_iface_command_t command)
{
    switch (command) {
    case BEET_IFACE_COMMAND_GET_CALIBRATION:
    case BEET_IFACE_COMMAND_GET_HISTORY_SUMMARY:
    case BEET_IFACE_COMMAND_GET_EVENT:
    case BEET_IFACE_COMMAND_GET_SYSTEM_HISTORY_SUMMARY:
    case BEET_IFACE_COMMAND_GET_SYSTEM_EVENT:
    case BEET_IFACE_COMMAND_GET_WATERING_HISTORY_SUMMARY:
    case BEET_IFACE_COMMAND_GET_WATERING_EVENT:
    case BEET_IFACE_COMMAND_GET_VALVE_CONFIG:
    case BEET_IFACE_COMMAND_GET_WATERING_INTERVAL:
    case BEET_IFACE_COMMAND_GET_PAIR_WIRING:
    case BEET_IFACE_COMMAND_GET_PAIR_NAMES:
        return false;

    default:
        return true;
    }
}

static bool beet_ble_is_update_eligible(
    const beet_maintenance_begin_update_request_t *request,
    beet_maintenance_failure_reason_t *failure_reason)
{
    beet_iface_device_state_t device_state;
    beet_iface_pair_state_t pair_states[BEET_PAIR_COUNT];
    beet_maintenance_info_t maintenance_info;
    bool hardware_match = false;

    if (failure_reason == NULL || request == NULL) {
        return false;
    }

    *failure_reason = BEET_MAINTENANCE_FAILURE_NONE;
    if (request->image_size == 0U ||
        request->hardware_rev_count == 0U ||
        request->firmware_version[0] == '\0' ||
        request->build_label[0] == '\0' ||
        request->product_id[0] == '\0' ||
        request->asset_id[0] == '\0' ||
        !beet_maintenance_is_valid_sha256_hex(request->image_sha256) ||
        request->image_kind == BEET_MAINTENANCE_IMAGE_KIND_UNKNOWN) {
        *failure_reason = BEET_MAINTENANCE_FAILURE_UPDATE_INVALID_METADATA;
        return false;
    }

    if (beet_maintenance_get_info(&maintenance_info) != ESP_OK) {
        *failure_reason = BEET_MAINTENANCE_FAILURE_UPDATE_INTERNAL_ERROR;
        return false;
    }
    if (strcmp(request->product_id, maintenance_info.product_id) != 0) {
        *failure_reason = BEET_MAINTENANCE_FAILURE_IMAGE_PRODUCT_MISMATCH;
        return false;
    }
    for (uint8_t i = 0U; i < request->hardware_rev_count; ++i) {
        if (strcmp(request->hardware_revs[i], maintenance_info.hardware_rev) == 0) {
            hardware_match = true;
            break;
        }
    }
    if (!hardware_match) {
        *failure_reason = BEET_MAINTENANCE_FAILURE_IMAGE_HARDWARE_REVISION_INCOMPATIBLE;
        return false;
    }

    if (beet_iface_get_device_state(&device_state) != ESP_OK ||
        beet_iface_get_all_pair_states(pair_states) != ESP_OK) {
        *failure_reason = BEET_MAINTENANCE_FAILURE_UPDATE_INTERNAL_ERROR;
        return false;
    }
    if (device_state.battery_state != BEET_BATTERY_STATE_ACTIVE &&
        !(s_ble.maintenance_session.active &&
            device_state.battery_state == BEET_BATTERY_STATE_OTA_IN_PROGRESS)) {
        *failure_reason = BEET_MAINTENANCE_FAILURE_UPDATE_LOW_BATTERY;
        return false;
    }
    if (device_state.active_pumps > 0U) {
        *failure_reason = BEET_MAINTENANCE_FAILURE_UPDATE_WATERING_ACTIVE;
        return false;
    }
    if (device_state.valve_state != BEET_VALVE_STATE_CLOSED) {
        *failure_reason = BEET_MAINTENANCE_FAILURE_UPDATE_RUNTIME_BUSY;
        return false;
    }
    for (uint8_t pair = 0U; pair < BEET_PAIR_COUNT; ++pair) {
        if (pair_states[pair].pair_state == BEET_PAIR_STATE_WATERING) {
            *failure_reason = BEET_MAINTENANCE_FAILURE_UPDATE_WATERING_ACTIVE;
            return false;
        }
        if (pair_states[pair].pair_state == BEET_PAIR_STATE_WAITING_FOR_SLOT ||
            pair_states[pair].pair_state == BEET_PAIR_STATE_SANITY_CHECK ||
            pair_states[pair].pair_state == BEET_PAIR_STATE_MOISTURE_TEST) {
            *failure_reason = BEET_MAINTENANCE_FAILURE_UPDATE_RUNTIME_BUSY;
            return false;
        }
    }

    return true;
}

static void beet_ble_service_maintenance_session(void)
{
    int64_t now_us;
    uint32_t session_id;

    now_us = esp_timer_get_time();

    if (s_ble.maintenance_begin_request_pending) {
        bool invalidates_active_session = s_ble.maintenance_begin_invalidates_active_session;
        uint32_t invalidated_session_id = s_ble.maintenance_begin_invalidated_session_id;
        beet_maintenance_begin_update_request_t begin_request = s_ble.maintenance_begin_request;
        const esp_partition_t *target_partition = esp_ota_get_next_update_partition(NULL);
        esp_ota_handle_t ota_handle = 0U;

        s_ble.maintenance_begin_request_pending = false;
        s_ble.maintenance_begin_invalidates_active_session = false;
        s_ble.maintenance_begin_invalidated_session_id = 0U;

        if (invalidates_active_session) {
            beet_ble_emit_maintenance_event(
                BEET_SYSTEM_EVENT_UPDATE_INVALIDATED,
                0U,
                invalidated_session_id);
        }
        if (s_ble.maintenance_session.active) {
            beet_ble_clear_maintenance_session();
        }

        if (target_partition != NULL &&
            esp_ota_begin(target_partition, begin_request.image_size, &ota_handle) == ESP_OK) {
            memset(&s_ble.maintenance_session, 0, sizeof(s_ble.maintenance_session));
            s_ble.maintenance_terminal_status_valid = false;
            s_ble.maintenance_session.active = true;
            s_ble.maintenance_session.target_partition = target_partition;
            s_ble.maintenance_session.ota_handle = ota_handle;
            s_ble.maintenance_session.ota_handle_active = true;
            s_ble.maintenance_session.begin_request = begin_request;
            beet_ble_image_sha256_init(&s_ble.maintenance_session.image_sha256_context);
            s_ble.maintenance_session.status.state = BEET_MAINTENANCE_STATE_AWAITING_DATA;
            s_ble.maintenance_session.status.has_session_id = true;
            s_ble.maintenance_session.status.session_id = ++s_ble.next_maintenance_session_id;
            s_ble.maintenance_session.status.next_offset = 0U;
            s_ble.maintenance_session.status.bytes_received = 0U;
            s_ble.maintenance_session.status.total_bytes = begin_request.image_size;
            s_ble.maintenance_session.status_indication_pending = true;
            beet_ble_emit_maintenance_event(
                BEET_SYSTEM_EVENT_UPDATE_STARTED,
                0U,
                s_ble.maintenance_session.status.session_id);
            beet_ble_mark_activity();
            /* Request faster BLE connection parameters for OTA throughput.
             * Default 30-50ms interval gives ~22 writes/s at 244 bytes =
             * ~5.4 KB/s. At 7.5-15ms + longer CE window, throughput
             * increases ~3-4x (the Android BLE stack may negotiate
             * slightly wider, but any improvement is a win). This is a
             * standard GAP procedure — no wire protocol change. */
            if (s_ble.connected && s_ble.conn_handle != BLE_HS_CONN_HANDLE_NONE) {
                struct ble_gap_upd_params upd_params = {
                    .itvl_min = 6,                /* 7.5 ms */
                    .itvl_max = 12,               /* 15 ms */
                    .latency = 0,                 /* no slave latency */
                    .supervision_timeout = 500,   /* 5 s */
                    .min_ce_len = 0,              /* any CE length accepted */
                    .max_ce_len = 0xFFFF,         /* max CE window */
                };
                int rc = ble_gap_update_params(s_ble.conn_handle, &upd_params);
                if (rc == 0) {
                    s_ble.maintenance_session.conn_param_update_requested = true;
                    ESP_LOGI(TAG, "conn param update requested for OTA conn_handle=%u interval=%.1f-%.1fms",
                             (unsigned)s_ble.conn_handle, 7.5, 15.0);
                } else {
                    ESP_LOGW(TAG, "conn param update request failed conn_handle=%u rc=%d",
                             (unsigned)s_ble.conn_handle, rc);
                }
            }
            ESP_LOGI(TAG, "begin update ready session_id=%lu partition=%s size=%lu",
                     (unsigned long)s_ble.maintenance_session.status.session_id,
                     target_partition->label,
                     (unsigned long)begin_request.image_size);
        } else {
            ESP_LOGE(TAG, "begin update ota setup failed partition=%s total_bytes=%lu",
                     target_partition ? target_partition->label : "NULL",
                     (unsigned long)begin_request.image_size);
            beet_ble_set_maintenance_terminal_status(
                BEET_MAINTENANCE_STATE_FAILED,
                BEET_MAINTENANCE_FAILURE_UPDATE_INTERNAL_ERROR,
                true,
                false,
                0U);
            beet_ble_emit_maintenance_event(
                BEET_SYSTEM_EVENT_UPDATE_FAILED,
                (uint16_t)BEET_MAINTENANCE_FAILURE_UPDATE_INTERNAL_ERROR,
                0U);
            (void)beet_ble_send_maintenance_status_indication();
        }
    }

    if (!s_ble.maintenance_session.active) {
        return;
    }

    if (s_ble.maintenance_session.abort_request_pending) {
        uint32_t aborted_session_id = s_ble.maintenance_session.status.session_id;

        s_ble.maintenance_session.abort_request_pending = false;
        beet_ble_emit_maintenance_event(
            BEET_SYSTEM_EVENT_UPDATE_INTERRUPTED,
            0U,
            aborted_session_id);
        s_ble.maintenance_terminal_status_valid = false;
        beet_ble_clear_maintenance_session();
        (void)beet_ble_send_maintenance_status_indication();
        return;
    }

    /* Process queued chunks in batches so the main loop can
     * yield to NimBLE between batches. Without batching, the
     * entire queue drains at once (esp_ota_write blocks), and
     * the BLE stack cannot send ACKs for new writes, causing
     * the write queue to back up and overflow. */
    uint16_t chunks_processed_this_call = 0U;
    while (s_ble.maintenance_session.chunk_queue_count > 0U &&
           chunks_processed_this_call < 32U) {
        beet_ble_maintenance_chunk_t chunk;

        if (!beet_ble_peek_maintenance_chunk(&chunk)) {
            break;
        }
        if (chunk.session_id != s_ble.maintenance_session.status.session_id ||
            chunk.offset + chunk.payload_len > s_ble.maintenance_session.status.total_bytes) {
            uint32_t failed_session_id = s_ble.maintenance_session.status.session_id;

            beet_ble_set_maintenance_terminal_status(
                BEET_MAINTENANCE_STATE_FAILED,
                BEET_MAINTENANCE_FAILURE_UPDATE_INTERNAL_ERROR,
                true,
                true,
                failed_session_id);
            s_ble.maintenance_session.ota_handle_active = false;
            beet_ble_clear_maintenance_session();
            beet_ble_emit_maintenance_event(
                BEET_SYSTEM_EVENT_UPDATE_FAILED,
                (uint16_t)BEET_MAINTENANCE_FAILURE_UPDATE_INTERNAL_ERROR,
                failed_session_id);
            (void)beet_ble_send_maintenance_status_indication();
            return;
        }
        if (esp_ota_write(s_ble.maintenance_session.ota_handle, chunk.payload, chunk.payload_len) != ESP_OK) {
            uint32_t failed_session_id = s_ble.maintenance_session.status.session_id;

            beet_ble_set_maintenance_terminal_status(
                BEET_MAINTENANCE_STATE_FAILED,
                BEET_MAINTENANCE_FAILURE_UPDATE_INTERNAL_ERROR,
                true,
                true,
                failed_session_id);
            s_ble.maintenance_session.ota_handle_active = false;
            beet_ble_clear_maintenance_session();
            beet_ble_emit_maintenance_event(
                BEET_SYSTEM_EVENT_UPDATE_FAILED,
                (uint16_t)BEET_MAINTENANCE_FAILURE_UPDATE_INTERNAL_ERROR,
                failed_session_id);
            (void)beet_ble_send_maintenance_status_indication();
            return;
        }
        beet_ble_image_sha256_update(
            &s_ble.maintenance_session.image_sha256_context,
            chunk.payload,
            chunk.payload_len);
        beet_ble_pop_maintenance_chunk();
        chunks_processed_this_call++;
    }

    if (s_ble.maintenance_session.finish_request_pending) {
        uint32_t finished_session_id = s_ble.maintenance_session.status.session_id;
        uint8_t digest[32];
        char digest_hex[BEET_MAINTENANCE_SHA256_HEX_LEN + 1U];
        beet_maintenance_image_metadata_t metadata;
        esp_err_t err;

        if (s_ble.maintenance_session.chunk_queue_count > 0U) {
            return;
        }
        if (s_ble.maintenance_session.status.bytes_received !=
            s_ble.maintenance_session.status.total_bytes) {
            beet_ble_set_maintenance_terminal_status(
                BEET_MAINTENANCE_STATE_FAILED,
                BEET_MAINTENANCE_FAILURE_IMAGE_UPLOAD_INCOMPLETE,
                true,
                true,
                finished_session_id);
            beet_ble_clear_maintenance_session();
            beet_ble_emit_maintenance_event(
                BEET_SYSTEM_EVENT_UPDATE_FAILED,
                (uint16_t)BEET_MAINTENANCE_FAILURE_IMAGE_UPLOAD_INCOMPLETE,
                finished_session_id);
            (void)beet_ble_send_maintenance_status_indication();
            return;
        }

        s_ble.maintenance_session.finish_request_pending = false;
        err = esp_ota_end(s_ble.maintenance_session.ota_handle);
        s_ble.maintenance_session.ota_handle_active = false;
        if (err != ESP_OK) {
            beet_ble_set_maintenance_terminal_status(
                BEET_MAINTENANCE_STATE_FAILED,
                BEET_MAINTENANCE_FAILURE_UPDATE_INTERNAL_ERROR,
                true,
                true,
                finished_session_id);
            beet_ble_clear_maintenance_session();
            beet_ble_emit_maintenance_event(
                BEET_SYSTEM_EVENT_UPDATE_FAILED,
                (uint16_t)BEET_MAINTENANCE_FAILURE_UPDATE_INTERNAL_ERROR,
                finished_session_id);
            (void)beet_ble_send_maintenance_status_indication();
            return;
        }

        beet_ble_image_sha256_finish(&s_ble.maintenance_session.image_sha256_context, digest);
        beet_ble_format_sha256_hex(digest, digest_hex);
        if (strcmp(digest_hex, s_ble.maintenance_session.begin_request.image_sha256) != 0) {
            ESP_LOGW(TAG,
                     "maintenance image sha mismatch expected=%s actual=%s",
                     s_ble.maintenance_session.begin_request.image_sha256,
                     digest_hex);
            beet_ble_set_maintenance_terminal_status(
                BEET_MAINTENANCE_STATE_FAILED,
                BEET_MAINTENANCE_FAILURE_IMAGE_SHA256_MISMATCH,
                true,
                true,
                finished_session_id);
            beet_ble_clear_maintenance_session();
            beet_ble_emit_maintenance_event(
                BEET_SYSTEM_EVENT_UPDATE_FAILED,
                (uint16_t)BEET_MAINTENANCE_FAILURE_IMAGE_SHA256_MISMATCH,
                finished_session_id);
            (void)beet_ble_send_maintenance_status_indication();
            return;
        }

        err = beet_ble_read_partition_metadata(
            s_ble.maintenance_session.target_partition,
            s_ble.maintenance_session.status.total_bytes,
            &metadata);
        if (err != ESP_OK ||
            !beet_ble_metadata_sets_match(&metadata, &s_ble.maintenance_session.begin_request)) {
            beet_ble_set_maintenance_terminal_status(
                BEET_MAINTENANCE_STATE_FAILED,
                (err == ESP_OK) ?
                    BEET_MAINTENANCE_FAILURE_UPDATE_INVALID_METADATA :
                    BEET_MAINTENANCE_FAILURE_IMAGE_METADATA_MISSING,
                true,
                true,
                finished_session_id);
            beet_ble_clear_maintenance_session();
            beet_ble_emit_maintenance_event(
                BEET_SYSTEM_EVENT_UPDATE_FAILED,
                (uint16_t)((err == ESP_OK) ?
                    BEET_MAINTENANCE_FAILURE_UPDATE_INVALID_METADATA :
                    BEET_MAINTENANCE_FAILURE_IMAGE_METADATA_MISSING),
                finished_session_id);
            (void)beet_ble_send_maintenance_status_indication();
            return;
        }

        if (esp_ota_set_boot_partition(s_ble.maintenance_session.target_partition) != ESP_OK) {
            beet_ble_set_maintenance_terminal_status(
                BEET_MAINTENANCE_STATE_FAILED,
                BEET_MAINTENANCE_FAILURE_UPDATE_INTERNAL_ERROR,
                true,
                true,
                finished_session_id);
            beet_ble_clear_maintenance_session();
            beet_ble_emit_maintenance_event(
                BEET_SYSTEM_EVENT_UPDATE_FAILED,
                (uint16_t)BEET_MAINTENANCE_FAILURE_UPDATE_INTERNAL_ERROR,
                finished_session_id);
            (void)beet_ble_send_maintenance_status_indication();
            return;
        }

        s_ble.maintenance_session.status.state = BEET_MAINTENANCE_STATE_REBOOTING;
        s_ble.maintenance_session.reboot_pending = true;
        s_ble.maintenance_session.reboot_due_at_us =
            esp_timer_get_time() + BEET_BLE_MAINTENANCE_REBOOT_FALLBACK_US;
        s_ble.maintenance_session.status_indication_pending = true;
        beet_ble_emit_maintenance_event(
            BEET_SYSTEM_EVENT_UPDATE_COMPLETED,
            0U,
            finished_session_id);
        beet_ble_mark_activity();
    }

    if (s_ble.maintenance_session.status_indication_pending) {
        s_ble.maintenance_session.status_indication_pending = false;
        if (beet_ble_send_maintenance_status_indication() != ESP_OK &&
            s_ble.maintenance_session.reboot_pending) {
            ESP_LOGW(TAG, "final maintenance status indication send failed; reboot fallback remains armed");
        }
    }

    if (s_ble.maintenance_session.reboot_pending &&
        now_us >= s_ble.maintenance_session.reboot_due_at_us) {
        esp_restart();
        return;
    }
    if (!s_ble.maintenance_session.disconnected_waiting_resume) {
        return;
    }
    if (now_us < s_ble.maintenance_session.resume_expires_at_us) {
        return;
    }

    session_id = s_ble.maintenance_session.status.session_id;
    beet_ble_set_maintenance_terminal_status(
        BEET_MAINTENANCE_STATE_FAILED,
        BEET_MAINTENANCE_FAILURE_UPDATE_SESSION_EXPIRED,
        true,
        true,
        session_id);
    beet_ble_clear_maintenance_session();
    beet_ble_emit_maintenance_event(
        BEET_SYSTEM_EVENT_UPDATE_INTERRUPTED,
        (uint16_t)BEET_MAINTENANCE_FAILURE_UPDATE_SESSION_EXPIRED,
        session_id);
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

static size_t beet_ble_att_payload_budget(void)
{
    uint16_t mtu;

    if (!s_ble.connected || s_ble.conn_handle == BLE_HS_CONN_HANDLE_NONE) {
        return 0U;
    }

    mtu = ble_att_mtu(s_ble.conn_handle);
    if (mtu <= 3U) {
        return 0U;
    }

    // ATT notifications and indications consume 3 bytes of protocol overhead outside the payload.
    return (size_t)mtu - 3U;
}

static size_t beet_ble_command_result_payload_budget(void)
{
    return beet_ble_att_payload_budget();
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

static void beet_ble_on_maintenance_status_indication_complete(int status)
{
    if (!s_ble.maintenance_session.status_indication_in_flight) {
        return;
    }

    // NimBLE reports indications twice: once when transmitted, then again when the peer confirms.
    if (status == 0) {
        ESP_LOGD(
            TAG,
            "maintenance status indication transmitted state=%d session_id=%lu awaiting confirmation",
            (int)s_ble.maintenance_session.status.state,
            (unsigned long)s_ble.maintenance_session.status.session_id);
        return;
    }

    s_ble.maintenance_session.status_indication_in_flight = false;
    if (status != BLE_HS_EDONE) {
        ESP_LOGW(TAG, "maintenance status indication completion failed status=%d", status);
        return;
    }

    ESP_LOGD(
        TAG,
        "maintenance status indication confirmed state=%d session_id=%lu",
        (int)s_ble.maintenance_session.status.state,
        (unsigned long)s_ble.maintenance_session.status.session_id);
    if (s_ble.maintenance_session.reboot_pending) {
        s_ble.maintenance_session.reboot_due_at_us =
            esp_timer_get_time() + BEET_BLE_MAINTENANCE_REBOOT_CONFIRM_GRACE_US;
    }
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
    fields.uuids128 = (ble_uuid128_t[]){ BEET_BLE_MAINTENANCE_SERVICE_UUID };
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

#if BEET_BLE_DIAGNOSTIC_VERBOSE
static const char *beet_ble_gap_event_name(uint8_t type)
{
    switch (type) {
    case BLE_GAP_EVENT_CONNECT:         return "CONNECT";
    case BLE_GAP_EVENT_DISCONNECT:      return "DISCONNECT";
    case BLE_GAP_EVENT_CONN_UPDATE:     return "CONN_UPDATE";
    case BLE_GAP_EVENT_ADV_COMPLETE:    return "ADV_COMPLETE";
    case BLE_GAP_EVENT_ENC_CHANGE:      return "ENC_CHANGE";
    case BLE_GAP_EVENT_PASSKEY_ACTION:  return "PASSKEY_ACTION";
    case BLE_GAP_EVENT_NOTIFY_TX:       return "NOTIFY_TX";
    case BLE_GAP_EVENT_SUBSCRIBE:       return "SUBSCRIBE";
    case BLE_GAP_EVENT_MTU:             return "MTU";
    case BLE_GAP_EVENT_REPEAT_PAIRING:  return "REPEAT_PAIRING";
    case BLE_GAP_EVENT_PARING_COMPLETE:return "PARING_COMPLETE";
    default:                            return "?";
    }
}

static const char *beet_ble_passkey_action_name(uint8_t action)
{
    switch (action) {
    case BLE_SM_IOACT_NONE:   return "NONE";
    case BLE_SM_IOACT_OOB:    return "OOB";
    case BLE_SM_IOACT_INPUT:  return "INPUT";
    case BLE_SM_IOACT_DISP:   return "DISP";
    case BLE_SM_IOACT_NUMCMP: return "NUMCMP";
    case BLE_SM_IOACT_OOB_SC: return "OOB_SC";
    case BLE_SM_IOACT_STATIC: return "STATIC";
    default:                  return "?";
    }
}
#endif

#ifndef BEET_HOST_TEST
static void beet_ble_log_bond_store_on_init(void)
{
#if BEET_BLE_DIAGNOSTIC_VERBOSE
    ble_addr_t bonded_peers[BEET_BLE_BOND_SCAN_CAPACITY];
    int peer_count = 0;

    int rc = ble_store_util_bonded_peers(bonded_peers, &peer_count, (int)BEET_BLE_BOND_SCAN_CAPACITY);
    if (rc == 0 || rc == BLE_HS_ENOMEM) {
        ESP_LOGI(TAG, "bond store on init count=%d (capacity=%u)", peer_count, (unsigned)BEET_BLE_BOND_SCAN_CAPACITY);
        for (int i = 0; i < peer_count; ++i) {
            ESP_LOGI(TAG, "  bond[%d] addr=%02x:%02x:%02x:%02x:%02x:%02x type=%u",
                i,
                bonded_peers[i].val[5], bonded_peers[i].val[4], bonded_peers[i].val[3],
                bonded_peers[i].val[2], bonded_peers[i].val[1], bonded_peers[i].val[0],
                (unsigned)bonded_peers[i].type);
        }
    } else {
        ESP_LOGW(TAG, "bond store enum failed rc=%d", rc);
    }
#else
    (void)0;
#endif
}
#endif /* BEET_HOST_TEST */

#if BEET_BLE_DIAGNOSTIC_VERBOSE
static void beet_ble_log_gap_event_verbose(const char *event_name, struct ble_gap_event *event)
{
    struct ble_gap_conn_desc desc;
    uint16_t conn_handle = BLE_HS_CONN_HANDLE_NONE;
    bool have_conn = false;

    switch (event->type) {
    case BLE_GAP_EVENT_CONNECT:
        conn_handle = event->connect.conn_handle;
        break;
    case BLE_GAP_EVENT_DISCONNECT:
        conn_handle = event->disconnect.conn.conn_handle;
        break;
    case BLE_GAP_EVENT_ENC_CHANGE:
        conn_handle = event->enc_change.conn_handle;
        break;
    case BLE_GAP_EVENT_PASSKEY_ACTION:
        conn_handle = event->passkey.conn_handle;
        break;
    case BLE_GAP_EVENT_REPEAT_PAIRING:
        conn_handle = event->repeat_pairing.conn_handle;
        break;
    case BLE_GAP_EVENT_MTU:
        conn_handle = event->mtu.conn_handle;
        break;
    case BLE_GAP_EVENT_PARING_COMPLETE:
        conn_handle = event->pairing_complete.conn_handle;
        break;
    default:
        break;
    }
    have_conn = (conn_handle != BLE_HS_CONN_HANDLE_NONE && ble_gap_conn_find(conn_handle, &desc) == 0);

    switch (event->type) {
    case BLE_GAP_EVENT_CONNECT:
        ESP_LOGI(TAG, "gap_event %s status=%d handle=%u", event_name, event->connect.status, event->connect.conn_handle);
        break;
    case BLE_GAP_EVENT_DISCONNECT:
        ESP_LOGI(TAG, "gap_event %s reason=%d handle=%u bonded=%d encrypted=%d",
            event_name, event->disconnect.reason, event->disconnect.conn.conn_handle,
            event->disconnect.conn.sec_state.bonded, event->disconnect.conn.sec_state.encrypted);
        break;
    case BLE_GAP_EVENT_ENC_CHANGE:
        ESP_LOGI(TAG, "gap_event %s status=%d handle=%u bonded=%d encrypted=%d",
            event_name, event->enc_change.status, event->enc_change.conn_handle,
            have_conn ? desc.sec_state.bonded : false, have_conn ? desc.sec_state.encrypted : false);
        break;
    case BLE_GAP_EVENT_PASSKEY_ACTION:
        ESP_LOGI(TAG, "gap_event %s action=%s(%u) handle=%u numcmp=%" PRIu32,
            event_name, beet_ble_passkey_action_name(event->passkey.params.action),
            (unsigned)event->passkey.params.action, event->passkey.conn_handle,
            event->passkey.params.numcmp);
        break;
    case BLE_GAP_EVENT_REPEAT_PAIRING:
        ESP_LOGI(TAG, "gap_event %s handle=%u", event_name, event->repeat_pairing.conn_handle);
        break;
    case BLE_GAP_EVENT_MTU:
        ESP_LOGI(TAG, "gap_event %s handle=%u mtu=%u", event_name, event->mtu.conn_handle, event->mtu.value);
        break;
    case BLE_GAP_EVENT_PARING_COMPLETE:
        ESP_LOGI(TAG, "gap_event %s handle=%u status=%d bonded=%d",
            event_name, event->pairing_complete.conn_handle, event->pairing_complete.status,
            have_conn ? desc.sec_state.bonded : false);
        break;
    default:
        ESP_LOGI(TAG, "gap_event %s type=%u", event_name, (unsigned)event->type);
        break;
    }
}
#endif

static int beet_ble_gap_event(struct ble_gap_event *event, void *arg)
{
    (void)arg;

#if BEET_BLE_DIAGNOSTIC_VERBOSE
    const char *event_name = beet_ble_gap_event_name(event->type);
    beet_ble_log_gap_event_verbose(event_name, event);
#endif

    switch (event->type) {
    case BLE_GAP_EVENT_CONNECT:
        s_ble.advertising = false;
        if (event->connect.status == 0) {
            s_ble.conn_handle = event->connect.conn_handle;
            s_ble.connected = true;
            s_ble.bonded = beet_ble_is_bonded_conn(event->connect.conn_handle);
            s_ble.state_stream_subscribed = false;
            s_ble.command_result_subscribed = false;
            s_ble.maintenance_status_subscribed = false;
            beet_ble_clear_result_send_state();
            beet_ble_reset_state_stream_sync();
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
        if (s_ble.maintenance_session.active) {
            s_ble.maintenance_session.disconnected_waiting_resume = true;
            s_ble.maintenance_session.reconnect_event_pending = true;
            s_ble.maintenance_session.resume_expires_at_us =
                esp_timer_get_time() + BEET_BLE_MAINTENANCE_SESSION_RESUME_TIMEOUT_US;
        }
        s_ble.connected = false;
        s_ble.bonded = false;
        s_ble.conn_handle = BLE_HS_CONN_HANDLE_NONE;
        s_ble.state_stream_subscribed = false;
        s_ble.command_result_subscribed = false;
        s_ble.maintenance_status_subscribed = false;
        beet_ble_clear_result_send_state();
        beet_ble_reset_state_stream_sync();
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
#if BEET_BLE_FORCE_ENABLE_DIAGNOSTICS
        if (event->subscribe.attr_handle == s_state_stream_handle) {
            s_ble.state_stream_subscribed = event->subscribe.cur_notify;
            if (s_ble.state_stream_subscribed) {
                beet_ble_start_initial_state_stream_sync();
            } else {
                beet_ble_reset_state_stream_sync();
            }
            beet_ble_mark_activity();
            ESP_LOGI(TAG, "state stream subscribe notify=%d bonded=%d", event->subscribe.cur_notify, s_ble.state_stream_subscribed);
        } else if (event->subscribe.attr_handle == s_command_result_handle) {
            s_ble.command_result_subscribed = event->subscribe.cur_indicate;
            if (!s_ble.command_result_subscribed) {
                beet_ble_clear_result_send_state();
            }
            beet_ble_mark_activity();
            ESP_LOGI(TAG, "command result subscribe indicate=%d bonded=%d", event->subscribe.cur_indicate, s_ble.command_result_subscribed);
        } else if (event->subscribe.attr_handle == s_maintenance_status_handle) {
            s_ble.maintenance_status_subscribed = event->subscribe.cur_indicate;
            beet_ble_mark_activity();
            ESP_LOGI(TAG, "maintenance status subscribe indicate=%d bonded=%d", event->subscribe.cur_indicate, s_ble.maintenance_status_subscribed);
        }
#else
        if (event->subscribe.attr_handle == s_state_stream_handle) {
            s_ble.state_stream_subscribed = event->subscribe.cur_notify;
            if (s_ble.state_stream_subscribed) {
                beet_ble_start_initial_state_stream_sync();
            } else {
                beet_ble_reset_state_stream_sync();
            }
            beet_ble_mark_activity();
            ESP_LOGI(TAG, "state stream subscribe notify=%d bonded=%d", event->subscribe.cur_notify, s_ble.state_stream_subscribed);
        } else if (event->subscribe.attr_handle == s_command_result_handle) {
            s_ble.command_result_subscribed = event->subscribe.cur_indicate;
            if (!s_ble.command_result_subscribed) {
                beet_ble_clear_result_send_state();
            }
            beet_ble_mark_activity();
            ESP_LOGI(TAG, "command result subscribe indicate=%d bonded=%d", event->subscribe.cur_indicate, s_ble.command_result_subscribed);
        } else if (event->subscribe.attr_handle == s_maintenance_status_handle) {
            s_ble.maintenance_status_subscribed = event->subscribe.cur_indicate;
            beet_ble_mark_activity();
            ESP_LOGI(TAG, "maintenance status subscribe indicate=%d bonded=%d", event->subscribe.cur_indicate, s_ble.maintenance_status_subscribed);
        }
#endif
        return 0;

    case BLE_GAP_EVENT_NOTIFY_TX:
        if (event->notify_tx.conn_handle == s_ble.conn_handle &&
            event->notify_tx.indication) {
            if (event->notify_tx.attr_handle == s_command_result_handle) {
                beet_ble_on_result_indication_complete(event->notify_tx.status);
            } else if (event->notify_tx.attr_handle == s_maintenance_status_handle) {
                beet_ble_on_maintenance_status_indication_complete(event->notify_tx.status);
            }
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
    {
        struct ble_gap_conn_desc desc;
        int rc;

        beet_ble_clear_pairing_display("repeat_pairing");
        rc = ble_gap_conn_find(event->repeat_pairing.conn_handle, &desc);
        if (rc != 0) {
            ESP_LOGW(
                TAG,
                "repeat pairing conn lookup failed handle=%u rc=%d",
                (unsigned)event->repeat_pairing.conn_handle,
                rc);
            return BLE_GAP_REPEAT_PAIRING_IGNORE;
        }

        rc = ble_store_util_delete_peer(&desc.peer_id_addr);
        if (rc != 0) {
            ESP_LOGW(TAG, "repeat pairing delete peer failed rc=%d", rc);
            return BLE_GAP_REPEAT_PAIRING_IGNORE;
        }
        return BLE_GAP_REPEAT_PAIRING_RETRY;
    }

    case BLE_GAP_EVENT_MTU:
#if BEET_BLE_DIAGNOSTIC_VERBOSE
        ESP_LOGI(TAG, "mtu negotiated handle=%u mtu=%u", event->mtu.conn_handle, event->mtu.value);
#endif
        return 0;

    case BLE_GAP_EVENT_PARING_COMPLETE:
        ESP_LOGI(TAG, "pairing complete handle=%u status=%d", event->pairing_complete.conn_handle, event->pairing_complete.status);
        return 0;

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

static esp_err_t beet_ble_send_maintenance_status_indication(void)
{
    char json[BEET_BLE_JSON_MAX_LEN];
    int written;
    struct os_mbuf *om;
    int rc;

    if (!s_ble.connected || !s_ble.maintenance_status_subscribed) {
        return ESP_ERR_INVALID_STATE;
    }

    written = beet_ble_format_maintenance_status(json, sizeof(json));
    if (written < 0 || (size_t)written >= sizeof(json)) {
        return ESP_FAIL;
    }

    om = beet_ble_json_mbuf(json);
    if (om == NULL) {
        return ESP_ERR_NO_MEM;
    }

    rc = ble_gatts_indicate_custom(s_ble.conn_handle, s_maintenance_status_handle, om);
    if (rc != 0) {
        ESP_LOGW(TAG, "maintenance indicate failed: rc=%d", rc);
        return ESP_FAIL;
    }

    s_ble.maintenance_session.status_indication_in_flight = true;
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

static bool beet_ble_stream_device_state(void)
{
    beet_iface_device_state_t device_state;
    char json[BEET_BLE_JSON_MAX_LEN];
    size_t payload_budget;
    int written;

    if (beet_iface_get_device_state(&device_state) != ESP_OK) {
        s_ble.force_send_device = false;
        return false;
    }

    if (s_ble.force_send_device) {
        s_ble.force_send_device = false;
    } else if (s_ble.have_last_device_state &&
               !s_ble.initial_sync_pending &&
               beet_ble_device_states_equal(&device_state, &s_ble.last_device_state)) {
        return false;
    }

    payload_budget = beet_ble_att_payload_budget();
    if (payload_budget == 0U) {
        return false;
    }
    written = beet_ble_format_device_frame_json(json, payload_budget < sizeof(json) ? payload_budget : sizeof(json), &device_state);
    if (written < 0) {
        ESP_LOGW(
            TAG,
            "device frame exceeds mtu payload frame_len=%u mtu_payload=%u battery_state=%s",
            (unsigned)written,
            (unsigned)payload_budget,
            beet_battery_state_name(device_state.battery_state));
        return false;
    }

    if (beet_ble_send_notify_json(s_state_stream_handle, json) == ESP_OK) {
        s_ble.last_device_state = device_state;
        s_ble.have_last_device_state = true;
        return true;
    }
    return false;
}

static bool beet_ble_stream_pair_state(uint8_t pair_index)
{
    beet_iface_pair_state_t pair_state;
    char json[BEET_BLE_JSON_MAX_LEN];
    int written;

    if (beet_iface_get_pair_state(pair_index, &pair_state) != ESP_OK) {
        s_ble.force_send_pair[pair_index - 1U] = false;
        return false;
    }

    if (s_ble.force_send_pair[pair_index - 1U]) {
        s_ble.force_send_pair[pair_index - 1U] = false;
    } else if (s_ble.have_last_pair_states &&
               !s_ble.initial_sync_pending &&
               beet_ble_states_equal(&pair_state, &s_ble.last_pair_states[pair_index - 1U])) {
        return false;
    }

    written = beet_ble_format_pair_frame(json, sizeof(json), &pair_state);
    if (written < 0 || (size_t)written >= sizeof(json)) {
        ESP_LOGW(TAG, "pair frame too large for pair %u", pair_index);
        return false;
    }

    if (beet_ble_send_notify_json(s_state_stream_handle, json) == ESP_OK) {
        s_ble.last_pair_states[pair_index - 1U] = pair_state;
        return true;
    }
    return false;
}

static void beet_ble_reset_state_stream_sync(void)
{
    s_ble.initial_sync_pending = false;
    s_ble.initial_sync_next_pair = 0U;
    s_ble.state_stream_next_pair = 1U;
    s_ble.state_stream_next_allowed_us = 0LL;
    s_ble.have_last_device_state = false;
    s_ble.have_last_pair_states = false;
    s_ble.force_send_device = false;
    memset(s_ble.force_send_pair, 0, sizeof(s_ble.force_send_pair));
}

static void beet_ble_start_initial_state_stream_sync(void)
{
    s_ble.initial_sync_pending = true;
    s_ble.initial_sync_next_pair = 0U;
    s_ble.state_stream_next_pair = 1U;
    s_ble.state_stream_next_allowed_us = 0LL;
    s_ble.have_last_device_state = false;
    s_ble.have_last_pair_states = false;
    s_ble.force_send_device = false;
    memset(s_ble.force_send_pair, 0, sizeof(s_ble.force_send_pair));
}

static void beet_ble_service_initial_state_stream(void)
{
    if (!s_ble.initial_sync_pending) {
        return;
    }

    if (s_ble.initial_sync_next_pair == 0U) {
        if (beet_ble_stream_device_state() || s_ble.maintenance_session.active) {
            s_ble.initial_sync_next_pair = 1U;
        }
        return;
    }

    if (s_ble.initial_sync_next_pair <= BEET_PAIR_COUNT) {
        if (beet_ble_stream_pair_state(s_ble.initial_sync_next_pair)) {
            s_ble.initial_sync_next_pair++;
        }
        return;
    }

    s_ble.have_last_pair_states = true;
    s_ble.initial_sync_pending = false;
    s_ble.initial_sync_next_pair = 0U;
    s_ble.state_stream_next_pair = 1U;
    s_ble.state_stream_next_allowed_us =
        esp_timer_get_time() + BEET_BLE_STATE_STREAM_MIN_INTERVAL_US;
}

static void beet_ble_service_live_state_stream(int64_t now_us)
{
    if (s_ble.maintenance_session.active) {
        return;
    }

    {
        bool has_force = s_ble.force_send_device;
        if (!has_force) {
            for (uint8_t i = 0U; i < BEET_PAIR_COUNT; ++i) {
                if (s_ble.force_send_pair[i]) {
                    has_force = true;
                    break;
                }
            }
        }
        if (has_force) {
            s_ble.state_stream_next_allowed_us = now_us;
        }
    }

    if (now_us < s_ble.state_stream_next_allowed_us) {
        return;
    }

    if (beet_ble_stream_device_state()) {
        s_ble.state_stream_next_allowed_us = now_us + BEET_BLE_STATE_STREAM_MIN_INTERVAL_US;
        return;
    }

    for (uint8_t i = 0U; i < BEET_PAIR_COUNT; ++i) {
        uint8_t pair = s_ble.state_stream_next_pair;
        if (pair == 0U || pair > BEET_PAIR_COUNT) {
            pair = 1U;
        }
        s_ble.state_stream_next_pair = (pair >= BEET_PAIR_COUNT) ? 1U : (uint8_t)(pair + 1U);
        if (beet_ble_stream_pair_state(pair)) {
            s_ble.state_stream_next_allowed_us = now_us + BEET_BLE_STATE_STREAM_MIN_INTERVAL_US;
            return;
        }
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
    ble_hs_cfg.sm_io_cap = BLE_HS_IO_NO_INPUT_OUTPUT;
    ble_hs_cfg.sm_bonding = 1;
    ble_hs_cfg.sm_mitm = 0;
    ble_hs_cfg.sm_sc = 1;
    ble_hs_cfg.sm_our_key_dist = BLE_SM_PAIR_KEY_DIST_ENC | BLE_SM_PAIR_KEY_DIST_ID;
    ble_hs_cfg.sm_their_key_dist = BLE_SM_PAIR_KEY_DIST_ENC | BLE_SM_PAIR_KEY_DIST_ID;

    /*
     * MTU is FROZEN at 247. DO NOT INCREASE.
     * The device frame JSON fits within the 244-byte ATT payload budget
     * only at this MTU. Larger MTU on new firmware breaks backward
     * compatibility with older Android apps that request 247.
     */
    ble_att_set_preferred_mtu(247);

    ble_svc_gap_init();
    ble_svc_gatt_init();
    rc = ble_svc_gap_device_name_set(s_ble.device_name);
    ESP_RETURN_ON_FALSE(rc == 0, ESP_FAIL, TAG, "gap name set failed");

    rc = ble_gatts_count_cfg(beet_ble_svcs);
    ESP_RETURN_ON_FALSE(rc == 0, ESP_FAIL, TAG, "gatt count failed");
    rc = ble_gatts_add_svcs(beet_ble_svcs);
    ESP_RETURN_ON_FALSE(rc == 0, ESP_FAIL, TAG, "gatt add failed");

    ble_store_config_init();
#if BEET_BLE_FORCE_ENABLE_DIAGNOSTICS
    beet_ble_log_bond_store_on_init();
#endif
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
        beet_ble_clear_maintenance_session();
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

bool beet_ble_maintenance_runtime_blocking(void)
{
    return s_ble.maintenance_session.active;
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
    s_ble.maintenance_status_subscribed = false;
    beet_ble_clear_result_send_state();
    beet_ble_reset_state_stream_sync();
    beet_ble_clear_maintenance_session();
    beet_ble_clear_pairing_display("bonds_cleared");
    beet_ble_disconnect();
    return ESP_OK;
}

void beet_ble_publish_system_event(const beet_system_event_record_t *event, uint32_t unix_s)
{
    char json[BEET_BLE_JSON_MAX_LEN];
    int written;

    if (event == NULL || !s_ble.enabled || !s_ble.connected || !s_ble.state_stream_subscribed) {
        return;
    }
#if !BEET_BLE_FORCE_ENABLE_DIAGNOSTICS
    if (!s_ble.bonded) {
        return;
    }
#endif

    written = beet_ble_format_system_event_frame_json(json, sizeof(json), event, unix_s);
    if (written < 0 || (size_t)written >= sizeof(json)) {
        ESP_LOGW(TAG, "system event frame too large");
        return;
    }

    (void)beet_ble_send_notify_json(s_state_stream_handle, json);
}

void beet_ble_service(void)
{
    int64_t now_us;

    if (!s_ble.initialized) {
        return;
    }

    beet_ble_service_pairing_display();
    beet_ble_drain_commands();
    if (s_ble.pending_result_valid &&
        s_ble.pending_result.status == BEET_IFACE_STATUS_ACCEPTED &&
        beet_ble_is_runtime_mutating_command(s_ble.pending_result.command)) {
        s_ble.force_send_device = true;
        if (s_ble.pending_result.pair_index >= 1U &&
            s_ble.pending_result.pair_index <= BEET_PAIR_COUNT) {
            s_ble.force_send_pair[s_ble.pending_result.pair_index - 1U] = true;
        }
    }
    beet_ble_send_pending_result();
    beet_ble_service_maintenance_session();

    if (!s_ble.enabled || !s_ble.connected || !s_ble.state_stream_subscribed) {
        return;
    }
#if !BEET_BLE_FORCE_ENABLE_DIAGNOSTICS
    if (!s_ble.bonded) {
        return;
    }
#endif

    if (beet_ble_maintenance_runtime_blocking()) {
        return;
    }

    if (s_ble.initial_sync_pending) {
        beet_ble_service_initial_state_stream();
        return;
    }

    now_us = esp_timer_get_time();
    beet_ble_service_live_state_stream(now_us);
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
    s_ble.maintenance_status_subscribed = false;
    s_ble.conn_handle = 1U;
    s_ble.command_queue = xQueueCreate(BEET_BLE_COMMAND_QUEUE_LEN, sizeof(beet_iface_command_request_t));
    s_command_result_handle = 23U;
    s_state_stream_handle = 22U;
    s_control_point_handle = 21U;
    s_controller_info_handle = 20U;
    s_maintenance_info_handle = 24U;
    s_maintenance_control_handle = 25U;
    s_maintenance_status_handle = 26U;
    s_maintenance_data_handle = 27U;
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

void beet_ble_host_test_notify_maintenance_status_tx(int status)
{
    struct ble_gap_event event;

    memset(&event, 0, sizeof(event));
    event.type = BLE_GAP_EVENT_NOTIFY_TX;
    event.notify_tx.conn_handle = s_ble.conn_handle;
    event.notify_tx.indication = 1U;
    event.notify_tx.attr_handle = s_maintenance_status_handle;
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

void beet_ble_host_test_set_state_stream_subscription(bool subscribed)
{
    struct ble_gap_event event;

    memset(&event, 0, sizeof(event));
    event.type = BLE_GAP_EVENT_SUBSCRIBE;
    event.subscribe.attr_handle = s_state_stream_handle;
    event.subscribe.conn_handle = s_ble.conn_handle;
    event.subscribe.cur_notify = subscribed ? 1U : 0U;
    (void)beet_ble_gap_event(&event, NULL);
}

int beet_ble_host_test_read_maintenance_info(char *buf, size_t len, bool bonded)
{
    struct ble_gatt_access_ctxt ctxt;
    struct os_mbuf *om;
    int rc;

    if (buf == NULL || len == 0U) {
        return BLE_ATT_ERR_UNLIKELY;
    }

    ble_host_test_set_conn_desc(s_ble.conn_handle, bonded);
    om = os_msys_get_pkthdr((uint16_t)(len - 1U), 0U);
    if (om == NULL) {
        return BLE_ATT_ERR_INSUFFICIENT_RES;
    }

    memset(&ctxt, 0, sizeof(ctxt));
    ctxt.op = BLE_GATT_ACCESS_OP_READ_CHR;
    ctxt.om = om;
    rc = beet_ble_maintenance_gatt_access(s_ble.conn_handle, s_maintenance_info_handle, &ctxt, NULL);
    if (rc == 0) {
        size_t copy_len = om->len < (uint16_t)(len - 1U) ? om->len : (uint16_t)(len - 1U);
        memcpy(buf, om->data, copy_len);
        buf[copy_len] = '\0';
    }

    os_mbuf_free_chain(om);
    return rc;
}

int beet_ble_host_test_write_maintenance_control(const char *json, bool bonded)
{
    struct ble_gatt_access_ctxt ctxt;
    struct os_mbuf *om;
    size_t json_len;
    int rc;

    if (json == NULL) {
        return BLE_ATT_ERR_UNLIKELY;
    }

    ble_host_test_set_conn_desc(s_ble.conn_handle, bonded);
    json_len = strlen(json);
    om = os_msys_get_pkthdr((uint16_t)json_len, 0U);
    if (om == NULL) {
        return BLE_ATT_ERR_INSUFFICIENT_RES;
    }
    if (os_mbuf_append(om, json, (uint16_t)json_len) != 0) {
        os_mbuf_free_chain(om);
        return BLE_ATT_ERR_INSUFFICIENT_RES;
    }

    memset(&ctxt, 0, sizeof(ctxt));
    ctxt.op = BLE_GATT_ACCESS_OP_WRITE_CHR;
    ctxt.om = om;
    rc = beet_ble_maintenance_gatt_access(s_ble.conn_handle, s_maintenance_control_handle, &ctxt, NULL);
    os_mbuf_free_chain(om);
    return rc;
}

int beet_ble_host_test_write_maintenance_data(const uint8_t *data, size_t len, bool bonded)
{
    struct ble_gatt_access_ctxt ctxt;
    struct os_mbuf *om;
    int rc;

    if (data == NULL || len == 0U || len > UINT16_MAX) {
        return BLE_ATT_ERR_UNLIKELY;
    }

    ble_host_test_set_conn_desc(s_ble.conn_handle, bonded);
    om = os_msys_get_pkthdr((uint16_t)len, 0U);
    if (om == NULL) {
        return BLE_ATT_ERR_INSUFFICIENT_RES;
    }
    if (os_mbuf_append(om, data, (uint16_t)len) != 0) {
        os_mbuf_free_chain(om);
        return BLE_ATT_ERR_INSUFFICIENT_RES;
    }

    memset(&ctxt, 0, sizeof(ctxt));
    ctxt.op = BLE_GATT_ACCESS_OP_WRITE_CHR;
    ctxt.om = om;
    rc = beet_ble_maintenance_gatt_access(s_ble.conn_handle, s_maintenance_data_handle, &ctxt, NULL);
    os_mbuf_free_chain(om);
    return rc;
}

void beet_ble_host_test_set_maintenance_status_subscription(bool subscribed)
{
    struct ble_gap_event event;

    memset(&event, 0, sizeof(event));
    event.type = BLE_GAP_EVENT_SUBSCRIBE;
    event.subscribe.attr_handle = s_maintenance_status_handle;
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
