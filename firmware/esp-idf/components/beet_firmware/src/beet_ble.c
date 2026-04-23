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
#include "store/config/ble_store_config.h"
#include "beet_ble_codec.h"
#include "beet_iface.h"

static const char *TAG = "beet_ble";

#define BEET_BLE_PROTOCOL_VERSION 2U
#define BEET_BLE_COMMAND_QUEUE_LEN 4U
#define BEET_BLE_JSON_MAX_LEN 320U
#define BEET_BLE_PAIRING_DISPLAY_TIMEOUT_US (30LL * 1000000LL)
#define BEET_BLE_PAIRING_CODE_MAX 1000000U

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
    bool pairing_display_active;
    uint16_t pairing_display_conn_handle;
    uint32_t pairing_display_passkey;
    int64_t pairing_display_expires_at_us;
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
        a->next_check_in_s == b->next_check_in_s &&
        a->active_pumps == b->active_pumps &&
        a->wifi_connected == b->wifi_connected &&
        a->mqtt_connected == b->mqtt_connected;
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

    return os_mbuf_append(ctxt->om, json, (uint16_t)written) == 0 ? 0 : BLE_ATT_ERR_INSUFFICIENT_RES;
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

    if (xQueueSend(s_ble.command_queue, &request, 0) != pdTRUE) {
        return BLE_ATT_ERR_INSUFFICIENT_RES;
    }

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
}

static void beet_ble_clear_pairing_display(const char *reason)
{
    if (!s_ble.pairing_display_active) {
        return;
    }

    ESP_LOGI(
        TAG,
        "pairing display cleared reason=%s handle=%u passkey=%06" PRIu32,
        reason,
        (unsigned)s_ble.pairing_display_conn_handle,
        s_ble.pairing_display_passkey);
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
        "pairing display active handle=%u passkey=%06" PRIu32 " timeout_s=30",
        (unsigned)conn_handle,
        passkey);
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
            s_ble.initial_sync_pending = false;
            s_ble.have_last_device_state = false;
            s_ble.have_last_pair_states = false;
            ESP_LOGI(TAG, "ble connected handle=%u bonded=%d", s_ble.conn_handle, s_ble.bonded);
            beet_ble_log_diag_status("gap_connect");
        } else if (s_ble.enabled) {
            ESP_LOGW(TAG, "ble connect failed status=%d", event->connect.status);
            beet_ble_clear_pairing_display("connect_failed");
            beet_ble_advertise();
        }
        return 0;

    case BLE_GAP_EVENT_DISCONNECT:
        ESP_LOGI(TAG, "ble disconnected reason=%d", event->disconnect.reason);
        beet_ble_clear_pairing_display("disconnect");
        s_ble.connected = false;
        s_ble.bonded = false;
        s_ble.conn_handle = BLE_HS_CONN_HANDLE_NONE;
        s_ble.state_stream_subscribed = false;
        s_ble.command_result_subscribed = false;
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
            ESP_LOGI(TAG, "state stream subscribe notify=%d bonded=%d", event->subscribe.cur_notify, s_ble.state_stream_subscribed);
        } else if (event->subscribe.attr_handle == s_command_result_handle) {
            s_ble.command_result_subscribed = event->subscribe.cur_indicate && beet_ble_is_bonded_conn(event->subscribe.conn_handle);
            ESP_LOGI(TAG, "command result subscribe indicate=%d bonded=%d", event->subscribe.cur_indicate, s_ble.command_result_subscribed);
        }
        return 0;

    case BLE_GAP_EVENT_ENC_CHANGE:
        s_ble.bonded = beet_ble_is_bonded_conn(event->enc_change.conn_handle);
        ESP_LOGI(TAG, "encryption change status=%d bonded=%d", event->enc_change.status, s_ble.bonded);
        if (event->enc_change.status == 0 && s_ble.bonded) {
            beet_ble_clear_pairing_display("bonded");
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
    char json[BEET_BLE_JSON_MAX_LEN];
    int written;

    if (!s_ble.pending_result_valid) {
        return;
    }

    written = beet_ble_format_command_result(json, sizeof(json), &s_ble.pending_result);
    if (written < 0 || (size_t)written >= sizeof(json)) {
        ESP_LOGW(TAG, "command result payload too large");
        s_ble.pending_result_valid = false;
        return;
    }

    if (beet_ble_send_indicate_json(s_command_result_handle, json) == ESP_OK) {
        s_ble.pending_result_valid = false;
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
        beet_ble_stop_advertising();
        beet_ble_disconnect();
        beet_ble_log_diag_status("set_enabled_false");
        return;
    }

    beet_ble_log_diag_status("set_enabled_true");
    beet_ble_advertise();
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
