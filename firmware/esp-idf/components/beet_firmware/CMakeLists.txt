find_package(Python3 REQUIRED COMPONENTS Interpreter)

set(BEET_METADATA_GENERATED_HEADER "${CMAKE_CURRENT_LIST_DIR}/include/beet_generated_metadata.h")
set(BEET_PROTOCOL_VERSIONS_FILE "${CMAKE_CURRENT_LIST_DIR}/../../../../config/protocol_versions.properties")

file(STRINGS "${BEET_PROTOCOL_VERSIONS_FILE}" BEET_PROTOCOL_VERSION_LINES REGEX "^[A-Za-z0-9_]+=")
foreach(BEET_PROTOCOL_LINE IN LISTS BEET_PROTOCOL_VERSION_LINES)
    if(BEET_PROTOCOL_LINE MATCHES "^maintenance_protocol_version=([0-9]+)$")
        set(BEET_SHARED_MAINTENANCE_PROTOCOL_VERSION "${CMAKE_MATCH_1}")
    elseif(BEET_PROTOCOL_LINE MATCHES "^runtime_protocol_version=([0-9]+)$")
        set(BEET_SHARED_RUNTIME_PROTOCOL_VERSION "${CMAKE_MATCH_1}")
    endif()
endforeach()

if(NOT DEFINED BEET_SHARED_MAINTENANCE_PROTOCOL_VERSION)
    message(FATAL_ERROR "Missing maintenance_protocol_version in ${BEET_PROTOCOL_VERSIONS_FILE}")
endif()

if(NOT DEFINED BEET_SHARED_RUNTIME_PROTOCOL_VERSION)
    message(FATAL_ERROR "Missing runtime_protocol_version in ${BEET_PROTOCOL_VERSIONS_FILE}")
endif()

if(NOT DEFINED BEET_FIRMWARE_VERSION)
    set(BEET_FIRMWARE_VERSION "${PROJECT_VER}")
endif()
if(NOT DEFINED BEET_BUILD_LABEL)
    set(BEET_BUILD_LABEL "${PROJECT_VER}")
endif()

execute_process(
    COMMAND
        ${Python3_EXECUTABLE}
        "${CMAKE_CURRENT_LIST_DIR}/tools/gen_beet_metadata_header.py"
        --output "${BEET_METADATA_GENERATED_HEADER}"
        --firmware-version "${BEET_FIRMWARE_VERSION}"
    --build-label "${BEET_BUILD_LABEL}"
    --product-id "beetmeister"
    --hardware-rev "rev_a"
    --maintenance-protocol-version "${BEET_SHARED_MAINTENANCE_PROTOCOL_VERSION}"
    --runtime-protocol-version "${BEET_SHARED_RUNTIME_PROTOCOL_VERSION}"
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

target_compile_definitions(${COMPONENT_LIB} PUBLIC
    BEET_MAINTENANCE_PROTOCOL_VERSION=${BEET_SHARED_MAINTENANCE_PROTOCOL_VERSION}
    BEET_RUNTIME_PROTOCOL_VERSION=${BEET_SHARED_RUNTIME_PROTOCOL_VERSION}
)
