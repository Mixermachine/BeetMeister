#ifndef ESP_PARTITION_H
#define ESP_PARTITION_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "esp_err.h"

typedef struct esp_partition_t {
    const char *label;
    size_t size;
    uint32_t address;
} esp_partition_t;

esp_err_t esp_partition_read(const esp_partition_t *partition, size_t src_offset, void *dst, size_t size);
esp_err_t esp_partition_get_sha256(const esp_partition_t *partition, uint8_t *sha_256);

#endif
