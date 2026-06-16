#ifndef ESP_OTA_OPS_H
#define ESP_OTA_OPS_H

#include <stddef.h>
#include <stdint.h>

#include "esp_err.h"
#include "esp_app_desc.h"
#include "esp_partition.h"

#define OTA_SIZE_UNKNOWN 0xffffffffU
#define OTA_WITH_SEQUENTIAL_WRITES 0xfffffffeU

#define ESP_ERR_OTA_BASE 0x1500
#define ESP_ERR_OTA_PARTITION_CONFLICT (ESP_ERR_OTA_BASE + 0x01)
#define ESP_ERR_OTA_SELECT_INFO_INVALID (ESP_ERR_OTA_BASE + 0x02)
#define ESP_ERR_OTA_VALIDATE_FAILED (ESP_ERR_OTA_BASE + 0x03)
#define ESP_ERR_OTA_SMALL_SEC_VER (ESP_ERR_OTA_BASE + 0x04)
#define ESP_ERR_OTA_ROLLBACK_FAILED (ESP_ERR_OTA_BASE + 0x05)
#define ESP_ERR_OTA_ROLLBACK_INVALID_STATE (ESP_ERR_OTA_BASE + 0x06)

typedef uint32_t esp_ota_handle_t;

esp_err_t esp_ota_begin(const esp_partition_t *partition, size_t image_size, esp_ota_handle_t *out_handle);
esp_err_t esp_ota_write(esp_ota_handle_t handle, const void *data, size_t size);
esp_err_t esp_ota_end(esp_ota_handle_t handle);
esp_err_t esp_ota_abort(esp_ota_handle_t handle);
esp_err_t esp_ota_set_boot_partition(const esp_partition_t *partition);
const esp_partition_t *esp_ota_get_next_update_partition(const esp_partition_t *start_from);
esp_err_t esp_ota_get_partition_description(const esp_partition_t *partition, esp_app_desc_t *app_desc);

#endif
