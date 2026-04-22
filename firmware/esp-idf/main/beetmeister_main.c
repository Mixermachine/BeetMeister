#include "esp_err.h"
#include "esp_log.h"
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

    ESP_LOGI(TAG, "BeetMeister controller runtime started");

    while (1) {
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}
