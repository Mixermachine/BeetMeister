#ifndef BEET_BLE_H
#define BEET_BLE_H

#include <stdbool.h>
#include <stdint.h>

#include "esp_err.h"

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

esp_err_t beet_ble_init(const char *device_name);
void beet_ble_get_diag_status(beet_ble_diag_status_t *status);
void beet_ble_get_pairing_display(beet_ble_pairing_display_t *display);
void beet_ble_set_enabled(bool enabled);
void beet_ble_service(void);

#endif
