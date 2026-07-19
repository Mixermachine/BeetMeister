#include "ble_test_shim.h"

#include <stdio.h>

#include "beet_ble.h"
#include "beet_iface.h"

ble_hs_cfg_t ble_hs_cfg;

static esp_app_desc_t s_app_desc = { "host-test" };
static int64_t s_now_us = 1000;
static uint16_t s_att_mtu = 247U;
static uint16_t s_conn_handle = 1U;
static bool s_conn_bonded = true;
static int s_indicate_rc = 0;
static int s_notify_rc = 0;
static unsigned s_indication_count = 0U;
static unsigned s_notification_count = 0U;
static char s_last_indication[2048];
static char s_last_notification[2048];
static beet_iface_device_state_t s_device_state;
static beet_iface_pair_state_t s_pair_states[BEET_PAIR_COUNT];

typedef struct queue_stub {
    uint32_t length;
    size_t item_size;
    uint32_t count;
    uint32_t head;
    uint32_t tail;
    uint8_t *items;
} queue_stub_t;

const esp_app_desc_t *esp_app_get_description(void)
{
    return &s_app_desc;
}

uint32_t esp_random(void)
{
    return 123456U;
}

int64_t esp_timer_get_time(void)
{
    return s_now_us++;
}

QueueHandle_t xQueueCreate(uint32_t length, size_t item_size)
{
    queue_stub_t *queue = (queue_stub_t *)calloc(1U, sizeof(*queue));
    if (queue == NULL) {
        return NULL;
    }

    queue->length = length;
    queue->item_size = item_size;
    queue->items = (uint8_t *)calloc(length, item_size);
    if (queue->items == NULL) {
        free(queue);
        return NULL;
    }
    return queue;
}

BaseType_t xQueueSend(QueueHandle_t handle, const void *item, uint32_t ticks_to_wait)
{
    queue_stub_t *queue = (queue_stub_t *)handle;
    (void)ticks_to_wait;

    if (queue == NULL || item == NULL || queue->count >= queue->length) {
        return pdFALSE;
    }

    memcpy(queue->items + (queue->tail * queue->item_size), item, queue->item_size);
    queue->tail = (queue->tail + 1U) % queue->length;
    queue->count++;
    return pdTRUE;
}

BaseType_t xQueueReceive(QueueHandle_t handle, void *item, uint32_t ticks_to_wait)
{
    queue_stub_t *queue = (queue_stub_t *)handle;
    (void)ticks_to_wait;

    if (queue == NULL || item == NULL || queue->count == 0U) {
        return pdFALSE;
    }

    memcpy(item, queue->items + (queue->head * queue->item_size), queue->item_size);
    queue->head = (queue->head + 1U) % queue->length;
    queue->count--;
    return pdTRUE;
}

void xQueueDelete(QueueHandle_t handle)
{
    queue_stub_t *queue = (queue_stub_t *)handle;
    if (queue == NULL) {
        return;
    }

    free(queue->items);
    free(queue);
}

struct os_mbuf *os_msys_get_pkthdr(uint16_t len, uint16_t reserve)
{
    struct os_mbuf *om = (struct os_mbuf *)calloc(1U, sizeof(*om));
    (void)reserve;
    if (om == NULL) {
        return NULL;
    }

    om->capacity = len + 1U;
    om->data = (char *)calloc(om->capacity, sizeof(char));
    if (om->data == NULL) {
        free(om);
        return NULL;
    }
    return om;
}

int os_mbuf_append(struct os_mbuf *om, const void *data, uint16_t len)
{
    char *grown;
    if (om == NULL || data == NULL) {
        return -1;
    }

    if ((size_t)om->len + len + 1U > om->capacity) {
        uint16_t new_capacity = (uint16_t)(om->len + len + 1U);
        grown = (char *)realloc(om->data, new_capacity);
        if (grown == NULL) {
            return -1;
        }
        om->data = grown;
        om->capacity = new_capacity;
    }

    memcpy(om->data + om->len, data, len);
    om->len = (uint16_t)(om->len + len);
    om->data[om->len] = '\0';
    return 0;
}

void os_mbuf_free_chain(struct os_mbuf *om)
{
    if (om == NULL) {
        return;
    }
    free(om->data);
    free(om);
}

uint16_t ble_att_mtu(uint16_t conn_handle)
{
    (void)conn_handle;
    return s_att_mtu;
}

int ble_att_set_preferred_mtu(uint16_t mtu)
{
    s_att_mtu = mtu;
    return 0;
}

int ble_gap_conn_find(uint16_t conn_handle, struct ble_gap_conn_desc *desc)
{
    if (conn_handle != s_conn_handle || desc == NULL) {
        return -1;
    }

    memset(desc, 0, sizeof(*desc));
    desc->sec_state.bonded = s_conn_bonded;
    desc->sec_state.encrypted = s_conn_bonded;
    desc->peer_id_addr.type = 1U;
    return 0;
}

int ble_gap_adv_set_fields(const struct ble_hs_adv_fields *fields)
{
    (void)fields;
    return 0;
}

int ble_gap_adv_rsp_set_fields(const struct ble_hs_adv_fields *fields)
{
    (void)fields;
    return 0;
}

int ble_gap_adv_start(uint8_t own_addr_type, const void *direct_addr, int32_t duration_ms, const struct ble_gap_adv_params *params, int (*cb)(struct ble_gap_event *, void *), void *arg)
{
    (void)own_addr_type;
    (void)direct_addr;
    (void)duration_ms;
    (void)params;
    (void)cb;
    (void)arg;
    return 0;
}

int ble_gap_adv_stop(void)
{
    return 0;
}

int ble_gap_terminate(uint16_t conn_handle, uint8_t reason)
{
    (void)conn_handle;
    (void)reason;
    return 0;
}

int ble_gatts_count_cfg(const struct ble_gatt_svc_def *svcs)
{
    (void)svcs;
    return 0;
}

int ble_gatts_add_svcs(const struct ble_gatt_svc_def *svcs)
{
    uint16_t handle = 20U;

    if (svcs == NULL) {
        return 0;
    }

    for (const struct ble_gatt_svc_def *svc = svcs; svc->type != 0; ++svc) {
        if (svc->characteristics == NULL) {
            continue;
        }
        for (struct ble_gatt_chr_def *chr = svc->characteristics; chr->uuid != NULL; ++chr) {
            if (chr->val_handle != NULL) {
                *chr->val_handle = handle++;
            }
        }
    }
    return 0;
}

static int ble_capture_payload(struct os_mbuf *om, char *out, size_t out_len, unsigned *count)
{
    if (om == NULL || out == NULL || out_len == 0U || count == NULL) {
        return ESP_FAIL;
    }
    if (om->len >= out_len) {
        os_mbuf_free_chain(om);
        return ESP_FAIL;
    }

    memcpy(out, om->data, om->len + 1U);
    (*count)++;
    os_mbuf_free_chain(om);
    return ESP_OK;
}

int ble_gatts_notify_custom(uint16_t conn_handle, uint16_t attr_handle, struct os_mbuf *om)
{
    (void)conn_handle;
    (void)attr_handle;
    if (s_notify_rc != 0) {
        os_mbuf_free_chain(om);
        return s_notify_rc;
    }
    return ble_capture_payload(om, s_last_notification, sizeof(s_last_notification), &s_notification_count);
}

int ble_gatts_indicate_custom(uint16_t conn_handle, uint16_t attr_handle, struct os_mbuf *om)
{
    (void)conn_handle;
    (void)attr_handle;
    if (s_indicate_rc != 0) {
        os_mbuf_free_chain(om);
        return s_indicate_rc;
    }
    return ble_capture_payload(om, s_last_indication, sizeof(s_last_indication), &s_indication_count);
}

int ble_hs_mbuf_to_flat(const struct os_mbuf *om, void *dst, uint16_t max_len, uint16_t *out_len)
{
    uint16_t copy_len;
    if (om == NULL || dst == NULL) {
        return -1;
    }

    copy_len = om->len < max_len ? om->len : max_len;
    memcpy(dst, om->data, copy_len);
    if (out_len != NULL) {
        *out_len = copy_len;
    }
    return 0;
}

int ble_hs_util_ensure_addr(int prefer_random)
{
    (void)prefer_random;
    return 0;
}

int ble_hs_id_infer_auto(int privacy, uint8_t *own_addr_type)
{
    (void)privacy;
    if (own_addr_type != NULL) {
        *own_addr_type = 0U;
    }
    return 0;
}

int ble_sm_inject_io(uint16_t conn_handle, const struct ble_sm_io *io)
{
    (void)conn_handle;
    (void)io;
    return 0;
}

int nimble_port_init(void)
{
    return ESP_OK;
}

void nimble_port_run(void)
{
}

void nimble_port_freertos_deinit(void)
{
}

void nimble_port_freertos_init(void (*task_fn)(void *))
{
    (void)task_fn;
}

int ble_svc_gap_init(void)
{
    return 0;
}

int ble_svc_gatt_init(void)
{
    return 0;
}

int ble_svc_gap_device_name_set(const char *name)
{
    (void)name;
    return 0;
}

int ble_store_clear(void)
{
    return 0;
}

void ble_store_util_status_rr(int status, void *arg)
{
    (void)status;
    (void)arg;
}

int ble_store_util_bonded_peers(ble_addr_t *peers, int *peer_count, int max_peers)
{
    (void)peers;
    (void)max_peers;
    if (peer_count != NULL) {
        *peer_count = 0;
    }
    return 0;
}

int ble_store_util_delete_peer(const ble_addr_t *peer_addr)
{
    (void)peer_addr;
    return 0;
}

void ble_store_config_init(void)
{
}

void ble_host_test_reset(void)
{
    s_now_us = 1000;
    s_att_mtu = 247U;
    s_conn_handle = 1U;
    s_conn_bonded = true;
    s_indicate_rc = 0;
    s_notify_rc = 0;
    s_indication_count = 0U;
    s_notification_count = 0U;
    s_last_indication[0] = '\0';
    s_last_notification[0] = '\0';
    memset(&s_device_state, 0, sizeof(s_device_state));
    memset(s_pair_states, 0, sizeof(s_pair_states));
    for (uint8_t pair = 1U; pair <= BEET_PAIR_COUNT; ++pair) {
        s_pair_states[pair - 1U].pair_index = pair;
    }
    ble_host_test_reset_ota_state();
    memset(&ble_hs_cfg, 0, sizeof(ble_hs_cfg));
}

void ble_host_test_set_att_mtu(uint16_t mtu)
{
    s_att_mtu = mtu;
}

void ble_host_test_set_conn_desc(uint16_t conn_handle, bool bonded)
{
    s_conn_handle = conn_handle;
    s_conn_bonded = bonded;
}

void ble_host_test_advance_time_us(int64_t delta_us)
{
    if (delta_us > 0) {
        s_now_us += delta_us;
    }
}

void ble_host_test_set_indicate_result(int rc)
{
    s_indicate_rc = rc;
}

void ble_host_test_set_notify_result(int rc)
{
    s_notify_rc = rc;
}

void ble_host_test_set_device_state(const beet_iface_device_state_t *state)
{
    if (state == NULL) {
        memset(&s_device_state, 0, sizeof(s_device_state));
        return;
    }
    s_device_state = *state;
}

void ble_host_test_set_pair_state(uint8_t pair_index, const beet_iface_pair_state_t *state)
{
    if (pair_index == 0U || pair_index > BEET_PAIR_COUNT) {
        return;
    }

    if (state == NULL) {
        memset(&s_pair_states[pair_index - 1U], 0, sizeof(s_pair_states[pair_index - 1U]));
        s_pair_states[pair_index - 1U].pair_index = pair_index;
        return;
    }

    s_pair_states[pair_index - 1U] = *state;
    s_pair_states[pair_index - 1U].pair_index = pair_index;
}

void ble_host_test_clear_captures(void)
{
    s_indication_count = 0U;
    s_notification_count = 0U;
    s_last_indication[0] = '\0';
    s_last_notification[0] = '\0';
}

const char *ble_host_test_last_indication(void)
{
    return s_last_indication;
}

unsigned ble_host_test_indication_count(void)
{
    return s_indication_count;
}

const char *ble_host_test_last_notification(void)
{
    return s_last_notification;
}

unsigned ble_host_test_notification_count(void)
{
    return s_notification_count;
}

esp_err_t beet_iface_get_device_state(beet_iface_device_state_t *state)
{
    if (state == NULL) {
        return ESP_ERR_INVALID_ARG;
    }
    *state = s_device_state;
    return ESP_OK;
}

esp_err_t beet_iface_get_pair_state(uint8_t pair_index, beet_iface_pair_state_t *state)
{
    if (state == NULL) {
        return ESP_ERR_INVALID_ARG;
    }
    *state = s_pair_states[pair_index - 1U];
    state->pair_index = pair_index;
    return ESP_OK;
}

esp_err_t beet_iface_get_all_pair_states(beet_iface_pair_state_t states[BEET_PAIR_COUNT])
{
    if (states == NULL) {
        return ESP_ERR_INVALID_ARG;
    }
    memcpy(states, s_pair_states, sizeof(s_pair_states));
    return ESP_OK;
}

esp_err_t beet_iface_get_event(uint64_t seq_no, beet_iface_event_t *event)
{
    (void)seq_no;
    (void)event;
    return ESP_ERR_NOT_FOUND;
}

esp_err_t beet_iface_get_system_event(uint64_t seq_no, beet_iface_system_event_t *event)
{
    (void)seq_no;
    (void)event;
    return ESP_ERR_NOT_FOUND;
}

uint64_t beet_iface_get_latest_event_seq_no(void)
{
    return 0U;
}

uint64_t beet_iface_get_latest_system_event_seq_no(void)
{
    return 0U;
}

esp_err_t beet_iface_submit_command(const beet_iface_command_request_t *request, beet_iface_command_response_t *response)
{
    (void)request;
    if (response == NULL) {
        return ESP_ERR_INVALID_ARG;
    }
    memset(response, 0, sizeof(*response));
    return ESP_OK;
}

int ble_gap_update_params(uint16_t conn_handle, const struct ble_gap_upd_params *params)
{
    (void)conn_handle;
    (void)params;
    return 0;
}
