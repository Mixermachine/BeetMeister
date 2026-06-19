find_package(Python3 REQUIRED COMPONENTS Interpreter)

set(BEET_METADATA_GENERATED_HEADER "${CMAKE_CURRENT_LIST_DIR}/include/beet_generated_metadata.h")

execute_process(
    COMMAND
        ${Python3_EXECUTABLE}
        "${CMAKE_CURRENT_LIST_DIR}/tools/gen_beet_metadata_header.py"
        --output "${BEET_METADATA_GENERATED_HEADER}"
        --project-ver "${PROJECT_VER}"
        --product-id "beetmeister"
        --hardware-rev "rev_a"
        --maintenance-protocol-version "1"
        --runtime-protocol-version "9"
        --image-kind "bundled"
        --compatible-hardware-rev "rev_a"
    COMMAND_ERROR_IS_FATAL ANY
)

idf_component_register(
    SRCS
        "src/beet_board.c"
        "src/beet_ble.c"
        "src/beet_ble_codec.c"
        "src/beet_ble_guard.c"
        "src/beet_maintenance.c"
        "src/beet_controller.c"
        "src/beet_core.c"
        "src/beet_event_ring.c"
        "src/beet_iface_names.c"
        "src/beet_storage.c"
    INCLUDE_DIRS "include"
    REQUIRES
        app_update
        esp_adc
        esp_driver_i2c
        esp_driver_ledc
        esp_lcd
        bt
        esp_app_format
        esp_driver_gpio
        esp_partition
        esp_rom
        esp_timer
        espressif__led_strip
        nvs_flash
)
