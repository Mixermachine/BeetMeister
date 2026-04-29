#ifndef BEET_BOARD_H
#define BEET_BOARD_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include "esp_err.h"

typedef enum {
    BEET_BOARD_INDICATOR_BOOTING = 0,
    BEET_BOARD_INDICATOR_ACTIVE = 1,
    BEET_BOARD_INDICATOR_IDLE_LOW = 2,
    BEET_BOARD_INDICATOR_DEEP_LOW = 3,
    BEET_BOARD_INDICATOR_FAULT = 4,
} beet_board_indicator_t;

typedef struct {
    int raw_avg;
    uint16_t voltage_mv;
} beet_board_sensor_sample_t;

typedef struct {
    int raw_avg;
    uint16_t sensed_mv;
    uint16_t divider_mv;
    uint16_t scaled_mv;
} beet_board_battery_sample_t;

typedef struct {
    bool ble_connected;
    bool ble_wave_visible;
} beet_board_display_status_t;

esp_err_t beet_board_init(void);
void beet_board_deinit(void);
void beet_board_all_relays_off(void);
esp_err_t beet_board_set_relay(uint8_t pair_index, bool enabled);
esp_err_t beet_board_set_sensor_power_enabled(bool enabled);
bool beet_board_is_sensor_power_enabled(void);
esp_err_t beet_board_set_boost_enabled(bool enabled);
bool beet_board_is_boost_enabled(void);
esp_err_t beet_board_read_moisture_sample(uint8_t pair_index, beet_board_sensor_sample_t *sample);
esp_err_t beet_board_read_moisture_mv(uint8_t pair_index, uint16_t *out_mv);
esp_err_t beet_board_read_battery_sample(beet_board_battery_sample_t *sample);
esp_err_t beet_board_read_battery_mv(uint16_t *out_mv);
esp_err_t beet_board_set_indicator(beet_board_indicator_t indicator);
esp_err_t beet_board_set_display_enabled(bool enabled);
esp_err_t beet_board_update_display(
    const char *const *lines,
    size_t line_count,
    const beet_board_display_status_t *status);
esp_err_t beet_board_show_pairing_code(uint32_t passkey, uint8_t remaining_s);
int beet_board_relay_gpio(uint8_t pair_index);
int beet_board_moisture_gpio(uint8_t pair_index);

#endif
