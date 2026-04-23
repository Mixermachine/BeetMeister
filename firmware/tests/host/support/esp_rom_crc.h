#ifndef ESP_ROM_CRC_H
#define ESP_ROM_CRC_H

#include <stdint.h>

uint32_t esp_rom_crc32_le(uint32_t crc, const uint8_t *buf, uint32_t len);

#endif
