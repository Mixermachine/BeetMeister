idf_component_register(
    SRCS
        "src/beet_board.c"
        "src/beet_controller.c"
        "src/beet_core.c"
        "src/beet_storage.c"
    INCLUDE_DIRS "include"
    REQUIRES
        esp_adc
        esp_driver_gpio
        esp_partition
        esp_rom
        esp_timer
        espressif__led_strip
        nvs_flash
)
