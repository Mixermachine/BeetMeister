#ifndef BEET_BLE_H
#define BEET_BLE_H

#include <stdbool.h>
#include <stdint.h>

#include "esp_err.h"
#include "beet_types.h"

#ifndef BEET_BLE_FORCE_ENABLE_DIAGNOSTICS
#define BEET_BLE_FORCE_ENABLE_DIAGNOSTICS 0
#endif

typedef struct {
    bool initialized;
    bool host_synced;
    bool enabled;
    bool advertising;
    bool connected;
    bool bonded;
    uint8_t own_addr_type;
    int64_t last_activity_us;
} beet_ble_diag_status_t;

typedef struct {
    bool active;
    uint16_t conn_handle;
    uint32_t passkey;
    uint8_t remaining_s;
} beet_ble_pairing_display_t;

typedef struct {
    beet_system_event_type_t type;
    uint16_t reason;
    uint8_t peer_addr[6];
    uint8_t peer_addr_type;
    bool known_peer;
    uint32_t detail;
} beet_ble_system_event_t;

typedef void (*beet_ble_system_event_callback_t)(const beet_ble_system_event_t *event);

esp_err_t beet_ble_init(const char *device_name);
void beet_ble_get_diag_status(beet_ble_diag_status_t *status);
void beet_ble_get_pairing_display(beet_ble_pairing_display_t *display);
void beet_ble_set_enabled(bool enabled);
void beet_ble_set_system_event_callback(beet_ble_system_event_callback_t callback);
esp_err_t beet_ble_clear_bonds(uint16_t *removed_count);
void beet_ble_publish_system_event(const beet_system_event_record_t *event);
void beet_ble_service(void);

#endif
