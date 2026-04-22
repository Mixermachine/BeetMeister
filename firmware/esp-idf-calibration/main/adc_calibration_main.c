#include <inttypes.h>
#include <stdio.h>

#include "esp_adc/adc_cali.h"
#include "esp_adc/adc_cali_scheme.h"
#include "esp_adc/adc_oneshot.h"
#include "esp_check.h"
#include "esp_chip_info.h"
#include "esp_efuse_rtc_calib.h"
#include "esp_err.h"
#include "esp_log.h"
#include "esp_rom_sys.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "hal/adc_types.h"

static const char *TAG = "adc_calibration";

static const adc_channel_t s_battery_channel = ADC_CHANNEL_1;

static const char *adc_unit_name(adc_unit_t unit)
{
    return unit == ADC_UNIT_1 ? "ADC1" : "ADC2";
}

static const char *atten_name(adc_atten_t atten)
{
    switch (atten) {
    case ADC_ATTEN_DB_0:
        return "0dB";
    case ADC_ATTEN_DB_2_5:
        return "2.5dB";
    case ADC_ATTEN_DB_6:
        return "6dB";
    case ADC_ATTEN_DB_12:
        return "12dB";
    default:
        return "unknown";
    }
}

static void log_chip_info(void)
{
    esp_chip_info_t info = {0};
    esp_chip_info(&info);
    ESP_LOGI(TAG,
             "chip model=%d revision=%" PRIu32 " cores=%d features=0x%" PRIx32,
             info.model,
             info.revision,
             info.cores,
             info.features);
}

static void log_line_fitting_status(void)
{
#if ADC_CALI_SCHEME_LINE_FITTING_SUPPORTED
    adc_cali_line_fitting_efuse_val_t cali_val = ADC_CALI_LINE_FITTING_EFUSE_VAL_DEFAULT_VREF;
    esp_err_t err = adc_cali_scheme_line_fitting_check_efuse(&cali_val);
    if (err == ESP_OK) {
        ESP_LOGI(TAG, "line_fitting_check_efuse=OK source=%d", (int)cali_val);
    } else {
        ESP_LOGW(TAG, "line_fitting_check_efuse=%s", esp_err_to_name(err));
    }
#else
    ESP_LOGI(TAG, "line_fitting_check_efuse=UNSUPPORTED");
#endif
}

static void log_efuse_calibration_points(void)
{
    const int calib_ver = esp_efuse_rtc_calib_get_ver();
    ESP_LOGI(TAG, "adc_efuse_calib_version=%d", calib_ver);
    if (calib_ver < 0) {
        ESP_LOGW(TAG, "adc efuse calibration version unavailable");
        return;
    }

    for (int unit = ADC_UNIT_1; unit <= ADC_UNIT_2; ++unit) {
        for (int atten = ADC_ATTEN_DB_0; atten <= ADC_ATTEN_DB_12; ++atten) {
            const uint32_t init_code = esp_efuse_rtc_calib_get_init_code(calib_ver, unit, atten);
            uint32_t digi = 0;
            uint32_t mv = 0;
            esp_err_t err = esp_efuse_rtc_calib_get_cal_voltage(calib_ver, unit, atten, &digi, &mv);
            if (err == ESP_OK) {
                ESP_LOGI(TAG,
                         "efuse %s atten=%s init_code=%" PRIu32 " cal_digi=%" PRIu32 " cal_mv=%" PRIu32,
                         adc_unit_name(unit),
                         atten_name(atten),
                         init_code,
                         digi,
                         mv);
            } else {
                ESP_LOGW(TAG,
                         "efuse %s atten=%s init_code=%" PRIu32 " cal_voltage=%s",
                         adc_unit_name(unit),
                         atten_name(atten),
                         init_code,
                         esp_err_to_name(err));
            }
        }
    }
}

static void log_vref_route_status(void)
{
    ESP_LOGW(TAG, "vref_gpio11_route=UNSUPPORTED on ESP32-S3 in current ESP-IDF");
    ESP_LOGW(TAG, "gpio11_measurement will not show internal ADC Vref on this target");
}

static void log_battery_channel_probe(void)
{
    adc_oneshot_unit_handle_t adc_handle = NULL;
    adc_cali_handle_t cali_handle = NULL;
    bool cali_enabled = false;

    adc_oneshot_unit_init_cfg_t unit_cfg = {
        .unit_id = ADC_UNIT_1,
        .ulp_mode = ADC_ULP_MODE_DISABLE,
    };
    ESP_ERROR_CHECK(adc_oneshot_new_unit(&unit_cfg, &adc_handle));

    adc_oneshot_chan_cfg_t chan_cfg = {
        .bitwidth = ADC_BITWIDTH_DEFAULT,
        .atten = ADC_ATTEN_DB_12,
    };
    ESP_ERROR_CHECK(adc_oneshot_config_channel(adc_handle, s_battery_channel, &chan_cfg));

#if ADC_CALI_SCHEME_CURVE_FITTING_SUPPORTED
    adc_cali_curve_fitting_config_t curve_cfg = {
        .unit_id = ADC_UNIT_1,
        .atten = ADC_ATTEN_DB_12,
        .bitwidth = ADC_BITWIDTH_DEFAULT,
    };
    if (adc_cali_create_scheme_curve_fitting(&curve_cfg, &cali_handle) == ESP_OK) {
        cali_enabled = true;
        ESP_LOGI(TAG, "adc_runtime_calibration=curve_fitting");
    }
#endif

#if ADC_CALI_SCHEME_LINE_FITTING_SUPPORTED
    if (!cali_enabled) {
        adc_cali_line_fitting_config_t line_cfg = {
            .unit_id = ADC_UNIT_1,
            .atten = ADC_ATTEN_DB_12,
            .bitwidth = ADC_BITWIDTH_DEFAULT,
        };
        if (adc_cali_create_scheme_line_fitting(&line_cfg, &cali_handle) == ESP_OK) {
            cali_enabled = true;
            ESP_LOGI(TAG, "adc_runtime_calibration=line_fitting");
        }
    }
#endif

    if (!cali_enabled) {
        ESP_LOGW(TAG, "adc_runtime_calibration=fallback");
    }

    while (true) {
        int raw = 0;
        ESP_ERROR_CHECK(adc_oneshot_read(adc_handle, s_battery_channel, &raw));

        int sensed_mv = (raw * 3300) / 4095;
        if (cali_enabled) {
            ESP_ERROR_CHECK(adc_cali_raw_to_voltage(cali_handle, raw, &sensed_mv));
        }

        ESP_LOGI(TAG,
                 "probe battery_gpio2 raw=%d sensed_mv=%d scaled_mv=%d divider_ratio=2:1",
                 raw,
                 sensed_mv,
                 sensed_mv * 2);
        vTaskDelay(pdMS_TO_TICKS(3000));
    }
}

void app_main(void)
{
    esp_rom_printf("adc_calibration: app_main start\r\n");
    printf("adc_calibration: stdout start\n");
    fflush(stdout);
    log_chip_info();
    esp_rom_printf("adc_calibration: after chip info\r\n");
    log_vref_route_status();
    esp_rom_printf("adc_calibration: after vref route status\r\n");
    log_line_fitting_status();
    esp_rom_printf("adc_calibration: after line fitting status\r\n");
    log_efuse_calibration_points();
    esp_rom_printf("adc_calibration: after efuse calibration points\r\n");
    log_battery_channel_probe();
}
