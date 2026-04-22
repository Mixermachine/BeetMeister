#include "beet_board.h"

#include <string.h>

#include "driver/gpio.h"
#include "esp_adc/adc_cali.h"
#include "esp_adc/adc_cali_scheme.h"
#include "esp_adc/adc_oneshot.h"
#include "esp_check.h"
#include "esp_log.h"
#include "led_strip.h"
#include "sdkconfig.h"
#include "beet_types.h"

static const char *TAG = "beet_board";

typedef struct {
    int relay_gpio;
    int moisture_gpio;
    adc_channel_t moisture_channel;
} beet_pair_pin_map_t;

static const beet_pair_pin_map_t s_pair_pins[BEET_PAIR_COUNT] = {
    { .relay_gpio = GPIO_NUM_14, .moisture_gpio = GPIO_NUM_10, .moisture_channel = ADC_CHANNEL_9 },
    { .relay_gpio = GPIO_NUM_21, .moisture_gpio = GPIO_NUM_9, .moisture_channel = ADC_CHANNEL_8 },
    { .relay_gpio = GPIO_NUM_47, .moisture_gpio = GPIO_NUM_8, .moisture_channel = ADC_CHANNEL_7 },
    { .relay_gpio = GPIO_NUM_38, .moisture_gpio = GPIO_NUM_7, .moisture_channel = ADC_CHANNEL_6 },
    { .relay_gpio = GPIO_NUM_39, .moisture_gpio = GPIO_NUM_6, .moisture_channel = ADC_CHANNEL_5 },
    { .relay_gpio = GPIO_NUM_40, .moisture_gpio = GPIO_NUM_5, .moisture_channel = ADC_CHANNEL_4 },
    { .relay_gpio = GPIO_NUM_41, .moisture_gpio = GPIO_NUM_4, .moisture_channel = ADC_CHANNEL_3 },
    { .relay_gpio = GPIO_NUM_42, .moisture_gpio = GPIO_NUM_1, .moisture_channel = ADC_CHANNEL_0 },
};

static const adc_channel_t s_battery_channel = ADC_CHANNEL_1;

static adc_oneshot_unit_handle_t s_adc_handle;
static adc_cali_handle_t s_adc_cali_handle;
static bool s_adc_cali_enabled;
static led_strip_handle_t s_led_strip;
static bool s_initialized;

static uint16_t beet_scale_permille(uint16_t value_mv, uint32_t permille)
{
    uint32_t scaled = ((uint32_t)value_mv * permille + 500U) / 1000U;
    if (scaled > UINT16_MAX) {
        scaled = UINT16_MAX;
    }
    return (uint16_t)scaled;
}

static bool beet_board_adc_calibration_init(void)
{
#if ADC_CALI_SCHEME_CURVE_FITTING_SUPPORTED
    adc_cali_curve_fitting_config_t cali_config = {
        .unit_id = ADC_UNIT_1,
        .atten = ADC_ATTEN_DB_12,
        .bitwidth = ADC_BITWIDTH_DEFAULT,
    };
    if (adc_cali_create_scheme_curve_fitting(&cali_config, &s_adc_cali_handle) == ESP_OK) {
        return true;
    }
#endif
#if ADC_CALI_SCHEME_LINE_FITTING_SUPPORTED
    adc_cali_line_fitting_config_t cali_config = {
        .unit_id = ADC_UNIT_1,
        .atten = ADC_ATTEN_DB_12,
        .bitwidth = ADC_BITWIDTH_DEFAULT,
    };
    if (adc_cali_create_scheme_line_fitting(&cali_config, &s_adc_cali_handle) == ESP_OK) {
        return true;
    }
#endif
    return false;
}

static esp_err_t beet_board_read_channel_sample(adc_channel_t channel, int *out_raw_avg, uint16_t *out_mv)
{
    ESP_RETURN_ON_FALSE(s_initialized, ESP_ERR_INVALID_STATE, TAG, "board not initialized");
    ESP_RETURN_ON_FALSE(out_raw_avg != NULL, ESP_ERR_INVALID_ARG, TAG, "out_raw_avg is null");
    ESP_RETURN_ON_FALSE(out_mv != NULL, ESP_ERR_INVALID_ARG, TAG, "out_mv is null");

    int32_t total_raw = 0;
    for (int i = 0; i < CONFIG_BEET_SENSOR_SAMPLE_COUNT; ++i) {
        int raw = 0;
        ESP_RETURN_ON_ERROR(adc_oneshot_read(s_adc_handle, channel, &raw), TAG, "adc read failed");
        total_raw += raw;
    }

    int avg_raw = total_raw / CONFIG_BEET_SENSOR_SAMPLE_COUNT;
    int voltage_mv = 0;
    if (s_adc_cali_enabled) {
        ESP_RETURN_ON_ERROR(
            adc_cali_raw_to_voltage(s_adc_cali_handle, avg_raw, &voltage_mv),
            TAG,
            "adc calibration failed");
    } else {
        voltage_mv = (avg_raw * 3300) / 4095;
    }

    *out_raw_avg = avg_raw;
    *out_mv = (uint16_t)voltage_mv;
    return ESP_OK;
}

static esp_err_t beet_board_init_relays(void)
{
    uint64_t pin_mask = 0ULL;
    for (size_t i = 0; i < BEET_PAIR_COUNT; ++i) {
        pin_mask |= 1ULL << s_pair_pins[i].relay_gpio;
    }

    gpio_config_t io_config = {
        .pin_bit_mask = pin_mask,
        .mode = GPIO_MODE_OUTPUT,
        .pull_up_en = GPIO_PULLUP_DISABLE,
        .pull_down_en = GPIO_PULLDOWN_ENABLE,
        .intr_type = GPIO_INTR_DISABLE,
    };

    ESP_RETURN_ON_ERROR(gpio_config(&io_config), TAG, "relay gpio config failed");
    beet_board_all_relays_off();
    return ESP_OK;
}

static esp_err_t beet_board_init_adc(void)
{
    adc_oneshot_unit_init_cfg_t unit_cfg = {
        .unit_id = ADC_UNIT_1,
        .ulp_mode = ADC_ULP_MODE_DISABLE,
    };
    ESP_RETURN_ON_ERROR(adc_oneshot_new_unit(&unit_cfg, &s_adc_handle), TAG, "adc unit init failed");

    adc_oneshot_chan_cfg_t chan_cfg = {
        .bitwidth = ADC_BITWIDTH_DEFAULT,
        .atten = ADC_ATTEN_DB_12,
    };

    for (size_t i = 0; i < BEET_PAIR_COUNT; ++i) {
        ESP_RETURN_ON_ERROR(
            adc_oneshot_config_channel(s_adc_handle, s_pair_pins[i].moisture_channel, &chan_cfg),
            TAG,
            "moisture channel config failed");
    }

    ESP_RETURN_ON_ERROR(
        adc_oneshot_config_channel(s_adc_handle, s_battery_channel, &chan_cfg),
        TAG,
        "battery channel config failed");

    s_adc_cali_enabled = beet_board_adc_calibration_init();
    return ESP_OK;
}

static esp_err_t beet_board_init_led(void)
{
    led_strip_config_t strip_config = {
        .strip_gpio_num = CONFIG_BEET_STATUS_LED_GPIO,
        .max_leds = 1,
    };
    led_strip_rmt_config_t rmt_config = {
        .resolution_hz = 10 * 1000 * 1000,
        .flags.with_dma = false,
    };

    ESP_RETURN_ON_ERROR(
        led_strip_new_rmt_device(&strip_config, &rmt_config, &s_led_strip),
        TAG,
        "status led init failed");
    return led_strip_clear(s_led_strip);
}

esp_err_t beet_board_init(void)
{
    if (s_initialized) {
        return ESP_OK;
    }

    ESP_RETURN_ON_ERROR(beet_board_init_relays(), TAG, "relay init failed");
    ESP_RETURN_ON_ERROR(beet_board_init_adc(), TAG, "adc init failed");
    ESP_RETURN_ON_ERROR(beet_board_init_led(), TAG, "led init failed");

    gpio_config_t button_cfg = {
        .pin_bit_mask = 1ULL << GPIO_NUM_13,
        .mode = GPIO_MODE_INPUT,
        .pull_up_en = GPIO_PULLUP_ENABLE,
        .pull_down_en = GPIO_PULLDOWN_DISABLE,
        .intr_type = GPIO_INTR_DISABLE,
    };
    ESP_RETURN_ON_ERROR(gpio_config(&button_cfg), TAG, "button gpio config failed");

    s_initialized = true;
    return beet_board_set_indicator(BEET_BOARD_INDICATOR_BOOTING);
}

void beet_board_deinit(void)
{
    if (s_led_strip != NULL) {
        led_strip_del(s_led_strip);
        s_led_strip = NULL;
    }
    if (s_adc_cali_enabled) {
#if ADC_CALI_SCHEME_CURVE_FITTING_SUPPORTED
        adc_cali_delete_scheme_curve_fitting(s_adc_cali_handle);
#elif ADC_CALI_SCHEME_LINE_FITTING_SUPPORTED
        adc_cali_delete_scheme_line_fitting(s_adc_cali_handle);
#endif
        s_adc_cali_enabled = false;
    }
    if (s_adc_handle != NULL) {
        adc_oneshot_del_unit(s_adc_handle);
        s_adc_handle = NULL;
    }
    s_initialized = false;
}

void beet_board_all_relays_off(void)
{
    for (size_t i = 0; i < BEET_PAIR_COUNT; ++i) {
        gpio_set_level(s_pair_pins[i].relay_gpio, 0);
    }
}

esp_err_t beet_board_set_relay(uint8_t pair_index, bool enabled)
{
    ESP_RETURN_ON_FALSE(beet_is_valid_pair_index(pair_index), ESP_ERR_INVALID_ARG, TAG, "invalid pair");
    return gpio_set_level(s_pair_pins[pair_index - 1U].relay_gpio, enabled ? 1 : 0);
}

esp_err_t beet_board_read_moisture_sample(uint8_t pair_index, beet_board_sensor_sample_t *sample)
{
    ESP_RETURN_ON_FALSE(beet_is_valid_pair_index(pair_index), ESP_ERR_INVALID_ARG, TAG, "invalid pair");
    ESP_RETURN_ON_FALSE(sample != NULL, ESP_ERR_INVALID_ARG, TAG, "sample is null");

    return beet_board_read_channel_sample(
        s_pair_pins[pair_index - 1U].moisture_channel,
        &sample->raw_avg,
        &sample->voltage_mv);
}

esp_err_t beet_board_read_moisture_mv(uint8_t pair_index, uint16_t *out_mv)
{
    beet_board_sensor_sample_t sample;
    ESP_RETURN_ON_ERROR(beet_board_read_moisture_sample(pair_index, &sample), TAG, "moisture read failed");
    *out_mv = sample.voltage_mv;
    return ESP_OK;
}

esp_err_t beet_board_read_battery_sample(beet_board_battery_sample_t *sample)
{
    ESP_RETURN_ON_FALSE(sample != NULL, ESP_ERR_INVALID_ARG, TAG, "sample is null");
    ESP_RETURN_ON_ERROR(
        beet_board_read_channel_sample(s_battery_channel, &sample->raw_avg, &sample->sensed_mv),
        TAG,
        "battery read failed");

    sample->divider_mv = beet_scale_permille(sample->sensed_mv, CONFIG_BEET_BATTERY_DIVIDER_SCALE_PERMILLE);
    sample->scaled_mv = beet_scale_permille(sample->divider_mv, CONFIG_BEET_BATTERY_ADC_CALIBRATION_PERMILLE);
    return ESP_OK;
}

esp_err_t beet_board_read_battery_mv(uint16_t *out_mv)
{
    beet_board_battery_sample_t sample;
    ESP_RETURN_ON_FALSE(out_mv != NULL, ESP_ERR_INVALID_ARG, TAG, "out_mv is null");
    ESP_RETURN_ON_ERROR(beet_board_read_battery_sample(&sample), TAG, "battery read failed");
    *out_mv = sample.scaled_mv;
    return ESP_OK;
}

esp_err_t beet_board_set_indicator(beet_board_indicator_t indicator)
{
    ESP_RETURN_ON_FALSE(s_led_strip != NULL, ESP_ERR_INVALID_STATE, TAG, "status led not initialized");

    uint8_t r = 0;
    uint8_t g = 0;
    uint8_t b = 0;
    uint8_t level = (uint8_t)CONFIG_BEET_STATUS_LED_BRIGHTNESS;

    switch (indicator) {
    case BEET_BOARD_INDICATOR_BOOTING:
        b = level;
        break;
    case BEET_BOARD_INDICATOR_ACTIVE:
        g = level;
        break;
    case BEET_BOARD_INDICATOR_IDLE_LOW:
        r = level;
        g = level / 2U;
        break;
    case BEET_BOARD_INDICATOR_DEEP_LOW:
        r = level;
        break;
    case BEET_BOARD_INDICATOR_FAULT:
        r = level;
        b = level;
        break;
    default:
        break;
    }

    ESP_RETURN_ON_ERROR(led_strip_set_pixel(s_led_strip, 0, r, g, b), TAG, "set pixel failed");
    return led_strip_refresh(s_led_strip);
}

int beet_board_relay_gpio(uint8_t pair_index)
{
    return beet_is_valid_pair_index(pair_index) ? s_pair_pins[pair_index - 1U].relay_gpio : -1;
}

int beet_board_moisture_gpio(uint8_t pair_index)
{
    return beet_is_valid_pair_index(pair_index) ? s_pair_pins[pair_index - 1U].moisture_gpio : -1;
}
