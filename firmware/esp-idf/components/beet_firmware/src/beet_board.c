#include "beet_board.h"

#include <inttypes.h>
#include <ctype.h>
#include <string.h>

#include "driver/i2c_master.h"
#include "driver/gpio.h"
#include "esp_adc/adc_cali.h"
#include "esp_adc/adc_cali_scheme.h"
#include "esp_adc/adc_oneshot.h"
#include "esp_check.h"
#include "esp_lcd_io_i2c.h"
#include "esp_lcd_panel_io.h"
#include "esp_lcd_panel_ops.h"
#include "esp_lcd_panel_vendor.h"
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
static i2c_master_bus_handle_t s_oled_i2c_bus;
static esp_lcd_panel_io_handle_t s_oled_io_handle;
static esp_lcd_panel_handle_t s_oled_panel_handle;
static bool s_oled_ready;
static bool s_oled_enabled;
static bool s_sensor_power_enabled;
static bool s_boost_enabled;
static uint8_t s_oled_buffer[128 * 64 / 8];
static bool s_initialized;

#define BEET_OLED_WIDTH 128
#define BEET_OLED_HEIGHT 64
#define BEET_OLED_LINE_HEIGHT 8
#define BEET_OLED_CHAR_WIDTH 6
#define BEET_OLED_CHAR_HEIGHT 7
#define BEET_OLED_MAX_LINES (BEET_OLED_HEIGHT / BEET_OLED_LINE_HEIGHT)
#define BEET_OLED_MAX_CHARS_PER_LINE (BEET_OLED_WIDTH / BEET_OLED_CHAR_WIDTH)
#define BEET_OLED_COLUMN2_X 69

static const uint8_t *beet_oled_glyph(char c)
{
    static const uint8_t blank[5] = { 0, 0, 0, 0, 0 };
    switch (toupper((unsigned char)c)) {
    case '0': { static const uint8_t g[5] = { 0x3E, 0x51, 0x49, 0x45, 0x3E }; return g; }
    case '1': { static const uint8_t g[5] = { 0x00, 0x42, 0x7F, 0x40, 0x00 }; return g; }
    case '2': { static const uint8_t g[5] = { 0x42, 0x61, 0x51, 0x49, 0x46 }; return g; }
    case '3': { static const uint8_t g[5] = { 0x21, 0x41, 0x45, 0x4B, 0x31 }; return g; }
    case '4': { static const uint8_t g[5] = { 0x18, 0x14, 0x12, 0x7F, 0x10 }; return g; }
    case '5': { static const uint8_t g[5] = { 0x27, 0x45, 0x45, 0x45, 0x39 }; return g; }
    case '6': { static const uint8_t g[5] = { 0x3C, 0x4A, 0x49, 0x49, 0x30 }; return g; }
    case '7': { static const uint8_t g[5] = { 0x01, 0x71, 0x09, 0x05, 0x03 }; return g; }
    case '8': { static const uint8_t g[5] = { 0x36, 0x49, 0x49, 0x49, 0x36 }; return g; }
    case '9': { static const uint8_t g[5] = { 0x06, 0x49, 0x49, 0x29, 0x1E }; return g; }
    case 'A': { static const uint8_t g[5] = { 0x7E, 0x11, 0x11, 0x11, 0x7E }; return g; }
    case 'B': { static const uint8_t g[5] = { 0x7F, 0x49, 0x49, 0x49, 0x36 }; return g; }
    case 'C': { static const uint8_t g[5] = { 0x3E, 0x41, 0x41, 0x41, 0x22 }; return g; }
    case 'D': { static const uint8_t g[5] = { 0x7F, 0x41, 0x41, 0x22, 0x1C }; return g; }
    case 'E': { static const uint8_t g[5] = { 0x7F, 0x49, 0x49, 0x49, 0x41 }; return g; }
    case 'F': { static const uint8_t g[5] = { 0x7F, 0x09, 0x09, 0x09, 0x01 }; return g; }
    case 'G': { static const uint8_t g[5] = { 0x3E, 0x41, 0x49, 0x49, 0x7A }; return g; }
    case 'H': { static const uint8_t g[5] = { 0x7F, 0x08, 0x08, 0x08, 0x7F }; return g; }
    case 'I': { static const uint8_t g[5] = { 0x00, 0x41, 0x7F, 0x41, 0x00 }; return g; }
    case 'J': { static const uint8_t g[5] = { 0x20, 0x40, 0x41, 0x3F, 0x01 }; return g; }
    case 'K': { static const uint8_t g[5] = { 0x7F, 0x08, 0x14, 0x22, 0x41 }; return g; }
    case 'L': { static const uint8_t g[5] = { 0x7F, 0x40, 0x40, 0x40, 0x40 }; return g; }
    case 'M': { static const uint8_t g[5] = { 0x7F, 0x02, 0x0C, 0x02, 0x7F }; return g; }
    case 'N': { static const uint8_t g[5] = { 0x7F, 0x04, 0x08, 0x10, 0x7F }; return g; }
    case 'O': { static const uint8_t g[5] = { 0x3E, 0x41, 0x41, 0x41, 0x3E }; return g; }
    case 'P': { static const uint8_t g[5] = { 0x7F, 0x09, 0x09, 0x09, 0x06 }; return g; }
    case 'Q': { static const uint8_t g[5] = { 0x3E, 0x41, 0x51, 0x21, 0x5E }; return g; }
    case 'R': { static const uint8_t g[5] = { 0x7F, 0x09, 0x19, 0x29, 0x46 }; return g; }
    case 'S': { static const uint8_t g[5] = { 0x46, 0x49, 0x49, 0x49, 0x31 }; return g; }
    case 'T': { static const uint8_t g[5] = { 0x01, 0x01, 0x7F, 0x01, 0x01 }; return g; }
    case 'U': { static const uint8_t g[5] = { 0x3F, 0x40, 0x40, 0x40, 0x3F }; return g; }
    case 'V': { static const uint8_t g[5] = { 0x1F, 0x20, 0x40, 0x20, 0x1F }; return g; }
    case 'W': { static const uint8_t g[5] = { 0x3F, 0x40, 0x38, 0x40, 0x3F }; return g; }
    case 'X': { static const uint8_t g[5] = { 0x63, 0x14, 0x08, 0x14, 0x63 }; return g; }
    case 'Y': { static const uint8_t g[5] = { 0x07, 0x08, 0x70, 0x08, 0x07 }; return g; }
    case 'Z': { static const uint8_t g[5] = { 0x61, 0x51, 0x49, 0x45, 0x43 }; return g; }
    case ':': { static const uint8_t g[5] = { 0x00, 0x36, 0x36, 0x00, 0x00 }; return g; }
    case '-': { static const uint8_t g[5] = { 0x08, 0x08, 0x08, 0x08, 0x08 }; return g; }
    case '.': { static const uint8_t g[5] = { 0x00, 0x60, 0x60, 0x00, 0x00 }; return g; }
    case '%': { static const uint8_t g[5] = { 0x63, 0x13, 0x08, 0x64, 0x63 }; return g; }
    case '/': { static const uint8_t g[5] = { 0x20, 0x10, 0x08, 0x04, 0x02 }; return g; }
    case ' ': return blank;
    default: { static const uint8_t g[5] = { 0x7F, 0x41, 0x5D, 0x41, 0x7F }; return g; }
    }
}

static void beet_oled_set_pixel(int x, int y, bool on)
{
    if (x < 0 || x >= BEET_OLED_WIDTH || y < 0 || y >= BEET_OLED_HEIGHT) {
        return;
    }

    size_t index = (size_t)x + ((size_t)(y / 8) * BEET_OLED_WIDTH);
    uint8_t mask = (uint8_t)(1U << (y % 8));
    if (on) {
        s_oled_buffer[index] |= mask;
    } else {
        s_oled_buffer[index] &= (uint8_t)~mask;
    }
}

static void beet_oled_draw_char(int x, int y, char c)
{
    const uint8_t *glyph = beet_oled_glyph(c);
    for (int col = 0; col < 5; ++col) {
        uint8_t bits = glyph[col];
        for (int row = 0; row < BEET_OLED_CHAR_HEIGHT; ++row) {
            beet_oled_set_pixel(x + col, y + row, (bits & (1U << row)) != 0U);
        }
    }
}

static void beet_oled_draw_scaled_char(int x, int y, char c, int scale)
{
    const uint8_t *glyph = beet_oled_glyph(c);

    for (int col = 0; col < 5; ++col) {
        uint8_t bits = glyph[col];
        for (int row = 0; row < BEET_OLED_CHAR_HEIGHT; ++row) {
            if ((bits & (1U << row)) == 0U) {
                continue;
            }

            for (int dx = 0; dx < scale; ++dx) {
                for (int dy = 0; dy < scale; ++dy) {
                    beet_oled_set_pixel(x + (col * scale) + dx, y + (row * scale) + dy, true);
                }
            }
        }
    }
}

static void beet_oled_draw_scaled_text_centered(
    int y,
    const char *text,
    int scale,
    int digit_spacing)
{
    size_t len = strlen(text);
    int digit_width = (5 * scale) + digit_spacing;
    int total_width = (int)len * digit_width - digit_spacing;
    int x = (BEET_OLED_WIDTH - total_width) / 2;

    for (size_t i = 0; i < len; ++i) {
        beet_oled_draw_scaled_char(x, y, text[i], scale);
        x += digit_width;
    }
}

static void beet_oled_draw_text_line(int line_index, const char *text)
{
    int y = line_index * BEET_OLED_LINE_HEIGHT;
    int x = 0;
    for (int i = 0; text != NULL && text[i] != '\0'; ++i) {
        if (text[i] == '\t') {
            x = BEET_OLED_COLUMN2_X;
            continue;
        }
        if (x > (BEET_OLED_WIDTH - 5)) {
            break;
        }
        beet_oled_draw_char(x, y, text[i]);
        x += BEET_OLED_CHAR_WIDTH;
    }
}

static esp_err_t beet_board_init_oled(void)
{
#if CONFIG_BEET_ENABLE_OLED_DISPLAY
    i2c_master_bus_config_t bus_config = {
        .clk_source = I2C_CLK_SRC_DEFAULT,
        .glitch_ignore_cnt = 7,
        .i2c_port = I2C_NUM_0,
        .sda_io_num = CONFIG_BEET_OLED_SDA_GPIO,
        .scl_io_num = CONFIG_BEET_OLED_SCL_GPIO,
        .flags.enable_internal_pullup = true,
    };
    esp_err_t err = i2c_new_master_bus(&bus_config, &s_oled_i2c_bus);
    if (err != ESP_OK) {
        ESP_LOGW(TAG, "oled i2c bus init failed: %s", esp_err_to_name(err));
        return ESP_OK;
    }

    esp_lcd_panel_io_i2c_config_t io_config = {
        .dev_addr = CONFIG_BEET_OLED_I2C_ADDRESS,
        .scl_speed_hz = 400000,
        .control_phase_bytes = 1,
        .dc_bit_offset = 6,
        .lcd_cmd_bits = 8,
        .lcd_param_bits = 8,
    };
    err = esp_lcd_new_panel_io_i2c(s_oled_i2c_bus, &io_config, &s_oled_io_handle);
    if (err != ESP_OK) {
        ESP_LOGW(TAG, "oled io init failed: %s", esp_err_to_name(err));
        return ESP_OK;
    }

    esp_lcd_panel_ssd1306_config_t ssd1306_config = {
        .height = BEET_OLED_HEIGHT,
    };
    esp_lcd_panel_dev_config_t panel_config = {
        .bits_per_pixel = 1,
        .reset_gpio_num = -1,
        .vendor_config = &ssd1306_config,
    };
    err = esp_lcd_new_panel_ssd1306(s_oled_io_handle, &panel_config, &s_oled_panel_handle);
    if (err != ESP_OK) {
        ESP_LOGW(TAG, "oled panel init failed: %s", esp_err_to_name(err));
        return ESP_OK;
    }

    ESP_RETURN_ON_ERROR(esp_lcd_panel_reset(s_oled_panel_handle), TAG, "oled reset failed");
    ESP_RETURN_ON_ERROR(esp_lcd_panel_init(s_oled_panel_handle), TAG, "oled panel init failed");
    ESP_RETURN_ON_ERROR(esp_lcd_panel_disp_on_off(s_oled_panel_handle, true), TAG, "oled display enable failed");

    memset(s_oled_buffer, 0, sizeof(s_oled_buffer));
    ESP_RETURN_ON_ERROR(
        esp_lcd_panel_draw_bitmap(s_oled_panel_handle, 0, 0, BEET_OLED_WIDTH, BEET_OLED_HEIGHT, s_oled_buffer),
        TAG,
        "oled clear failed");
    s_oled_ready = true;
    s_oled_enabled = true;
    return ESP_OK;
#else
    return ESP_OK;
#endif
}

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

static esp_err_t beet_board_init_power_controls(void)
{
    uint64_t pin_mask = (1ULL << CONFIG_BEET_SENSOR_POWER_GPIO) | (1ULL << CONFIG_BEET_BOOST_ENABLE_GPIO);
    gpio_config_t io_config = {
        .pin_bit_mask = pin_mask,
        .mode = GPIO_MODE_OUTPUT,
        .pull_up_en = GPIO_PULLUP_DISABLE,
        .pull_down_en = GPIO_PULLDOWN_ENABLE,
        .intr_type = GPIO_INTR_DISABLE,
    };

    ESP_RETURN_ON_ERROR(gpio_config(&io_config), TAG, "power control gpio config failed");
    ESP_RETURN_ON_ERROR(gpio_set_level(CONFIG_BEET_SENSOR_POWER_GPIO, 0), TAG, "sensor power init failed");
    ESP_RETURN_ON_ERROR(gpio_set_level(CONFIG_BEET_BOOST_ENABLE_GPIO, 0), TAG, "boost init failed");
    s_sensor_power_enabled = false;
    s_boost_enabled = false;
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
    ESP_RETURN_ON_ERROR(beet_board_init_power_controls(), TAG, "power control init failed");
    ESP_RETURN_ON_ERROR(beet_board_init_adc(), TAG, "adc init failed");
    ESP_RETURN_ON_ERROR(beet_board_init_led(), TAG, "led init failed");
    ESP_RETURN_ON_ERROR(beet_board_init_oled(), TAG, "oled init failed");

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
    if (s_initialized) {
        beet_board_set_boost_enabled(false);
        beet_board_set_sensor_power_enabled(false);
        beet_board_all_relays_off();
    }
    if (s_oled_panel_handle != NULL) {
        esp_lcd_panel_disp_on_off(s_oled_panel_handle, false);
        esp_lcd_panel_del(s_oled_panel_handle);
        s_oled_panel_handle = NULL;
    }
    if (s_oled_io_handle != NULL) {
        esp_lcd_panel_io_del(s_oled_io_handle);
        s_oled_io_handle = NULL;
    }
    if (s_oled_i2c_bus != NULL) {
        i2c_del_master_bus(s_oled_i2c_bus);
        s_oled_i2c_bus = NULL;
    }
    s_oled_ready = false;
    s_oled_enabled = false;
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

esp_err_t beet_board_set_sensor_power_enabled(bool enabled)
{
    ESP_RETURN_ON_FALSE(s_initialized, ESP_ERR_INVALID_STATE, TAG, "board not initialized");
    ESP_RETURN_ON_ERROR(gpio_set_level(CONFIG_BEET_SENSOR_POWER_GPIO, enabled ? 1 : 0), TAG, "sensor power set failed");
    s_sensor_power_enabled = enabled;
    return ESP_OK;
}

bool beet_board_is_sensor_power_enabled(void)
{
    return s_sensor_power_enabled;
}

esp_err_t beet_board_set_boost_enabled(bool enabled)
{
    ESP_RETURN_ON_FALSE(s_initialized, ESP_ERR_INVALID_STATE, TAG, "board not initialized");
    ESP_RETURN_ON_ERROR(gpio_set_level(CONFIG_BEET_BOOST_ENABLE_GPIO, enabled ? 1 : 0), TAG, "boost set failed");
    s_boost_enabled = enabled;
    return ESP_OK;
}

bool beet_board_is_boost_enabled(void)
{
    return s_boost_enabled;
}

esp_err_t beet_board_read_moisture_sample(uint8_t pair_index, beet_board_sensor_sample_t *sample)
{
    ESP_RETURN_ON_FALSE(beet_is_valid_pair_index(pair_index), ESP_ERR_INVALID_ARG, TAG, "invalid pair");
    ESP_RETURN_ON_FALSE(sample != NULL, ESP_ERR_INVALID_ARG, TAG, "sample is null");
    ESP_RETURN_ON_FALSE(s_sensor_power_enabled, ESP_ERR_INVALID_STATE, TAG, "sensor power is off");

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
    uint32_t total_raw = 0U;
    uint32_t total_sensed_mv = 0U;
    uint32_t total_divider_mv = 0U;
    uint32_t total_scaled_mv = 0U;

    ESP_RETURN_ON_FALSE(sample != NULL, ESP_ERR_INVALID_ARG, TAG, "sample is null");

    for (int i = 0; i < CONFIG_BEET_BATTERY_SAMPLE_OVERLAY_COUNT; ++i) {
        int raw_avg = 0;
        uint16_t sensed_mv = 0U;
        uint16_t divider_mv = 0U;
        uint16_t scaled_mv = 0U;

        ESP_RETURN_ON_ERROR(
            beet_board_read_channel_sample(s_battery_channel, &raw_avg, &sensed_mv),
            TAG,
            "battery read failed");

        divider_mv = beet_scale_permille(sensed_mv, CONFIG_BEET_BATTERY_DIVIDER_SCALE_PERMILLE);
        scaled_mv = beet_scale_permille(divider_mv, CONFIG_BEET_BATTERY_ADC_CALIBRATION_PERMILLE);

        total_raw += (uint32_t)raw_avg;
        total_sensed_mv += sensed_mv;
        total_divider_mv += divider_mv;
        total_scaled_mv += scaled_mv;
    }

    sample->raw_avg = (int)(total_raw / CONFIG_BEET_BATTERY_SAMPLE_OVERLAY_COUNT);
    sample->sensed_mv = (uint16_t)(total_sensed_mv / CONFIG_BEET_BATTERY_SAMPLE_OVERLAY_COUNT);
    sample->divider_mv = (uint16_t)(total_divider_mv / CONFIG_BEET_BATTERY_SAMPLE_OVERLAY_COUNT);
    sample->scaled_mv = (uint16_t)(total_scaled_mv / CONFIG_BEET_BATTERY_SAMPLE_OVERLAY_COUNT);
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

esp_err_t beet_board_set_display_enabled(bool enabled)
{
    if (!s_oled_ready) {
        return ESP_OK;
    }
    if (s_oled_enabled == enabled) {
        return ESP_OK;
    }

    ESP_RETURN_ON_ERROR(esp_lcd_panel_disp_on_off(s_oled_panel_handle, enabled), TAG, "oled display switch failed");
    s_oled_enabled = enabled;
    return ESP_OK;
}

esp_err_t beet_board_update_display(const char *const *lines, size_t line_count)
{
    if (!s_oled_ready || !s_oled_enabled) {
        return ESP_OK;
    }

    memset(s_oled_buffer, 0, sizeof(s_oled_buffer));
    if (line_count > BEET_OLED_MAX_LINES) {
        line_count = BEET_OLED_MAX_LINES;
    }
    for (size_t i = 0; i < line_count; ++i) {
        beet_oled_draw_text_line((int)i, lines[i]);
    }

    return esp_lcd_panel_draw_bitmap(
        s_oled_panel_handle,
        0,
        0,
        BEET_OLED_WIDTH,
        BEET_OLED_HEIGHT,
        s_oled_buffer);
}

esp_err_t beet_board_show_pairing_code(uint32_t passkey, uint8_t remaining_s)
{
    char code[7];
    char remaining[16];
    const char *lines[8];

    if (!s_oled_ready || !s_oled_enabled) {
        return ESP_OK;
    }

    snprintf(code, sizeof(code), "%06" PRIu32, passkey);
    snprintf(remaining, sizeof(remaining), "VISIBLE %2us", remaining_s);
    lines[0] = "  BLE PAIRING";
    lines[1] = remaining;
    lines[2] = " PAIR CODE";
    lines[3] = "";
    lines[4] = "";
    lines[5] = "";
    lines[6] = "";
    lines[7] = "ENTER ON PHONE";

    memset(s_oled_buffer, 0, sizeof(s_oled_buffer));
    for (size_t i = 0; i < 8; ++i) {
        if (i == 3 || i == 4 || i == 5 || i == 6) {
            continue;
        }
        beet_oled_draw_text_line((int)i, lines[i]);
    }

    beet_oled_draw_scaled_text_centered(24, code, 2, 2);
    return esp_lcd_panel_draw_bitmap(
        s_oled_panel_handle,
        0,
        0,
        BEET_OLED_WIDTH,
        BEET_OLED_HEIGHT,
        s_oled_buffer);
}

int beet_board_relay_gpio(uint8_t pair_index)
{
    return beet_is_valid_pair_index(pair_index) ? s_pair_pins[pair_index - 1U].relay_gpio : -1;
}

int beet_board_moisture_gpio(uint8_t pair_index)
{
    return beet_is_valid_pair_index(pair_index) ? s_pair_pins[pair_index - 1U].moisture_gpio : -1;
}
