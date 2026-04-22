#ifndef BEET_BLE_H
#define BEET_BLE_H

#include <stdbool.h>

#include "esp_err.h"

esp_err_t beet_ble_init(const char *device_name);
void beet_ble_set_enabled(bool enabled);
void beet_ble_service(void);

#endif
