#include "esp_rom_crc.h"

uint32_t esp_rom_crc32_le(uint32_t crc, const uint8_t *buf, uint32_t len)
{
    crc = ~crc;
    for (uint32_t i = 0; i < len; ++i) {
        crc ^= buf[i];
        for (int bit = 0; bit < 8; ++bit) {
            crc = (crc & 1U) ? ((crc >> 1) ^ 0xedb88320U) : (crc >> 1);
        }
    }
    return ~crc;
}
