#ifndef BLE_TEST_SHIM_H
#define BLE_TEST_SHIM_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "esp_err.h"
#include "beet_iface.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef int BaseType_t;
typedef uint32_t TickType_t;
typedef struct queue_stub *QueueHandle_t;

#define pdTRUE 1
#define pdFALSE 0
#define pdMS_TO_TICKS(ms) (ms)

#define BLE_HS_CONN_HANDLE_NONE 0xFFFFU
#define BLE_HS_EDONE 14
#define BLE_HS_EALREADY 1
#define BLE_HS_ENOTCONN 2
#define BLE_HS_ENOMEM 3
#define BLE_HS_EINVAL 4
#define BLE_HS_FOREVER 0

#define BLE_ATT_ERR_INSUFFICIENT_AUTHEN 0x05
#define BLE_ATT_ERR_INSUFFICIENT_ENC 0x0F
#define BLE_ATT_ERR_INSUFFICIENT_RES 0x11
#define BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN 0x0D
#define BLE_ATT_ERR_UNLIKELY 0x0E

#define BLE_GATT_ACCESS_OP_READ_CHR 1
#define BLE_GATT_ACCESS_OP_WRITE_CHR 2
#define BLE_GATT_SVC_TYPE_PRIMARY 1
#define BLE_GATT_CHR_F_READ 0x01
#define BLE_GATT_CHR_F_READ_ENC 0x02
#define BLE_GATT_CHR_F_READ_AUTHEN 0x04
#define BLE_GATT_CHR_F_NOTIFY 0x10
#define BLE_GATT_CHR_F_WRITE 0x08
#define BLE_GATT_CHR_F_WRITE_ENC 0x40
#define BLE_GATT_CHR_F_WRITE_AUTHEN 0x80
#define BLE_GATT_CHR_F_INDICATE 0x20

#define BLE_GAP_EVENT_CONNECT 1
#define BLE_GAP_EVENT_DISCONNECT 2
#define BLE_GAP_EVENT_ADV_COMPLETE 3
#define BLE_GAP_EVENT_SUBSCRIBE 4
#define BLE_GAP_EVENT_NOTIFY_TX 5
#define BLE_GAP_EVENT_ENC_CHANGE 6
#define BLE_GAP_EVENT_PASSKEY_ACTION 7
#define BLE_GAP_EVENT_REPEAT_PAIRING 8
#define BLE_GAP_EVENT_MTU 15
#define BLE_GAP_EVENT_PARING_COMPLETE 27
#define BLE_GAP_REPEAT_PAIRING_RETRY 0
#define BLE_GAP_REPEAT_PAIRING_IGNORE 1
#define BLE_GAP_CONN_MODE_UND 0
#define BLE_GAP_DISC_MODE_GEN 0
#define BLE_ERR_REM_USER_CONN_TERM 0x13

#define BLE_SM_IOACT_DISP 1
#define BLE_SM_IOACT_STATIC 2
#define BLE_HS_IO_NO_INPUT_OUTPUT 3
#define BLE_SM_IO_CAP_DISP_ONLY 1
#define BLE_SM_PAIR_KEY_DIST_ENC 0x01
#define BLE_SM_PAIR_KEY_DIST_ID 0x02

#define BLE_HS_ADV_F_DISC_GEN 0x02
#define BLE_HS_ADV_F_BREDR_UNSUP 0x04
#define BLE_HS_ADV_TX_PWR_LVL_AUTO 0

#define OS_MBUF_PKTLEN(om) ((om)->len)

typedef struct {
    const char *version;
} esp_app_desc_t;

typedef struct {
    uint8_t type;
    uint8_t value[16];
} ble_uuid_any_t;

typedef struct {
    ble_uuid_any_t u;
} ble_uuid128_t;

#define BLE_UUID128_INIT(...) { { 0, { __VA_ARGS__ } } }

typedef struct os_mbuf {
    char *data;
    uint16_t len;
    uint16_t capacity;
} os_mbuf;

typedef struct {
    uint8_t val[6];
    uint8_t type;
} ble_addr_t;

typedef struct ble_gap_conn_desc {
    struct {
        bool bonded;
        bool encrypted;
    } sec_state;
    ble_addr_t peer_id_addr;
} ble_gap_conn_desc;

typedef struct ble_sm_io {
    uint8_t action;
    uint32_t passkey;
} ble_sm_io;

struct ble_gatt_access_ctxt {
    int op;
    struct os_mbuf *om;
};

struct ble_gatt_register_ctxt {
    int unused;
};

struct ble_gatt_chr_def {
    const ble_uuid_any_t *uuid;
    int (*access_cb)(uint16_t, uint16_t, struct ble_gatt_access_ctxt *, void *);
    uint16_t flags;
    uint16_t *val_handle;
};

struct ble_gatt_svc_def {
    uint8_t type;
    const ble_uuid_any_t *uuid;
    struct ble_gatt_chr_def *characteristics;
};

struct ble_gap_adv_params {
    uint8_t conn_mode;
    uint8_t disc_mode;
};

struct ble_hs_adv_fields {
    uint8_t flags;
    uint8_t tx_pwr_lvl_is_present;
    int8_t tx_pwr_lvl;
    ble_uuid128_t *uuids128;
    uint8_t num_uuids128;
    uint8_t uuids128_is_complete;
    uint8_t *name;
    uint8_t name_len;
    uint8_t name_is_complete;
};

struct ble_gap_event {
    int type;
    union {
        struct {
            int status;
            uint16_t conn_handle;
        } connect;
        struct {
            int reason;
            struct {
                uint16_t conn_handle;
                struct {
                    bool bonded;
                    bool encrypted;
                } sec_state;
            } conn;
        } disconnect;
        struct {
            int reason;
        } adv_complete;
        struct {
            uint16_t attr_handle;
            uint16_t conn_handle;
            uint8_t cur_notify;
            uint8_t cur_indicate;
        } subscribe;
        struct {
            uint16_t conn_handle;
            uint8_t indication;
            uint16_t attr_handle;
            int status;
        } notify_tx;
        struct {
            uint16_t conn_handle;
            int status;
        } enc_change;
        struct {
            uint16_t conn_handle;
            struct {
                uint8_t action;
            } params;
        } passkey;
        struct {
            uint16_t conn_handle;
        } repeat_pairing;
        struct {
            uint16_t conn_handle;
            int status;
        } pairing_complete;
    };
};

typedef struct {
    void (*reset_cb)(int reason);
    void (*sync_cb)(void);
    void (*gatts_register_cb)(struct ble_gatt_register_ctxt *ctxt, void *arg);
    void (*store_status_cb)(int status, void *arg);
    uint8_t sm_io_cap;
    uint8_t sm_bonding;
    uint8_t sm_mitm;
    uint8_t sm_sc;
    uint8_t sm_our_key_dist;
    uint8_t sm_their_key_dist;
} ble_hs_cfg_t;

extern ble_hs_cfg_t ble_hs_cfg;

const esp_app_desc_t *esp_app_get_description(void);
uint32_t esp_random(void);
int64_t esp_timer_get_time(void);

QueueHandle_t xQueueCreate(uint32_t length, size_t item_size);
BaseType_t xQueueSend(QueueHandle_t queue, const void *item, uint32_t ticks_to_wait);
BaseType_t xQueueReceive(QueueHandle_t queue, void *item, uint32_t ticks_to_wait);
void xQueueDelete(QueueHandle_t queue);

struct os_mbuf *os_msys_get_pkthdr(uint16_t len, uint16_t reserve);
int os_mbuf_append(struct os_mbuf *om, const void *data, uint16_t len);
void os_mbuf_free_chain(struct os_mbuf *om);

uint16_t ble_att_mtu(uint16_t conn_handle);
int ble_gap_conn_find(uint16_t conn_handle, struct ble_gap_conn_desc *desc);
int ble_gap_adv_set_fields(const struct ble_hs_adv_fields *fields);
int ble_gap_adv_rsp_set_fields(const struct ble_hs_adv_fields *fields);
int ble_gap_adv_start(uint8_t own_addr_type, const void *direct_addr, int32_t duration_ms, const struct ble_gap_adv_params *params, int (*cb)(struct ble_gap_event *, void *), void *arg);
int ble_gap_adv_stop(void);
int ble_gap_terminate(uint16_t conn_handle, uint8_t reason);
int ble_gatts_count_cfg(const struct ble_gatt_svc_def *svcs);
int ble_gatts_add_svcs(const struct ble_gatt_svc_def *svcs);
int ble_gatts_notify_custom(uint16_t conn_handle, uint16_t attr_handle, struct os_mbuf *om);
int ble_gatts_indicate_custom(uint16_t conn_handle, uint16_t attr_handle, struct os_mbuf *om);
int ble_hs_mbuf_to_flat(const struct os_mbuf *om, void *dst, uint16_t max_len, uint16_t *out_len);
int ble_hs_util_ensure_addr(int prefer_random);
int ble_hs_id_infer_auto(int privacy, uint8_t *own_addr_type);
int ble_sm_inject_io(uint16_t conn_handle, const struct ble_sm_io *io);
int nimble_port_init(void);
void nimble_port_run(void);
void nimble_port_freertos_deinit(void);
void nimble_port_freertos_init(void (*task_fn)(void *));
int ble_svc_gap_init(void);
int ble_svc_gatt_init(void);
int ble_svc_gap_device_name_set(const char *name);
int ble_store_clear(void);
void ble_store_util_status_rr(int status, void *arg);
int ble_store_util_bonded_peers(ble_addr_t *peers, int *peer_count, int max_peers);
int ble_store_util_delete_peer(const ble_addr_t *peer_addr);

void ble_host_test_reset(void);
void ble_host_test_set_att_mtu(uint16_t mtu);
void ble_host_test_set_conn_desc(uint16_t conn_handle, bool bonded);
void ble_host_test_advance_time_us(int64_t delta_us);
void ble_host_test_set_ota_end_result(int rc);
unsigned ble_host_test_restart_count(void);
void ble_host_test_reset_ota_state(void);
void ble_host_test_set_indicate_result(int rc);
void ble_host_test_set_notify_result(int rc);
void ble_host_test_set_device_state(const beet_iface_device_state_t *state);
void ble_host_test_set_pair_state(uint8_t pair_index, const beet_iface_pair_state_t *state);
void ble_host_test_clear_captures(void);
const char *ble_host_test_last_indication(void);
unsigned ble_host_test_indication_count(void);
const char *ble_host_test_last_notification(void);
unsigned ble_host_test_notification_count(void);

#ifdef __cplusplus
}
#endif

#endif
