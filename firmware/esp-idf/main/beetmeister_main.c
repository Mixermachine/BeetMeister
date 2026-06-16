#include "esp_err.h"
#include "esp_log.h"
#include "esp_ota_ops.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "beet_controller.h"

static const char *TAG = "beetmeister";

void app_main(void)
{
    esp_err_t err = beet_controller_init();
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "controller init failed: %s", esp_err_to_name(err));
        while (1) {
            vTaskDelay(pdMS_TO_TICKS(1000));
        }
    }

    {
        const esp_partition_t *running = esp_ota_get_running_partition();
        esp_ota_img_states_t ota_state = 0;

        if (running != NULL &&
            esp_ota_get_state_partition(running, &ota_state) == ESP_OK &&
            ota_state == ESP_OTA_IMG_PENDING_VERIFY) {
            err = esp_ota_mark_app_valid_cancel_rollback();
            if (err != ESP_OK) {
                ESP_LOGE(TAG, "app rollback confirmation failed: %s", esp_err_to_name(err));
            }
        }
    }

    ESP_LOGI(TAG, "BeetMeister controller runtime started");

    while (1) {
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}
