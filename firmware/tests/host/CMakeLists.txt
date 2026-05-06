cmake_minimum_required(VERSION 3.20)

project(beetmeister_host_tests C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_C_EXTENSIONS OFF)

enable_testing()

set(FW_ROOT ${CMAKE_CURRENT_LIST_DIR}/../../esp-idf/components/beet_firmware)
set(SUPPORT_DIR ${CMAKE_CURRENT_LIST_DIR}/support)

add_executable(
    beet_host_tests
    ${CMAKE_CURRENT_LIST_DIR}/host_tests.c
    ${FW_ROOT}/src/beet_ble_codec.c
    ${FW_ROOT}/src/beet_ble_guard.c
    ${FW_ROOT}/src/beet_core.c
    ${FW_ROOT}/src/beet_event_ring.c
    ${FW_ROOT}/src/beet_iface_names.c
    ${SUPPORT_DIR}/esp_rom_crc.c
)

target_include_directories(
    beet_host_tests
    PRIVATE
        ${SUPPORT_DIR}
        ${FW_ROOT}/include
)

if (CMAKE_C_COMPILER_ID MATCHES "Clang|GNU")
    target_compile_options(beet_host_tests PRIVATE -Wall -Wextra -Werror)
endif()

add_test(NAME beet_host_tests COMMAND beet_host_tests)

add_executable(
    beet_ble_transport_host_tests
    ${CMAKE_CURRENT_LIST_DIR}/host_ble_transport_tests.c
    ${FW_ROOT}/src/beet_ble.c
    ${FW_ROOT}/src/beet_ble_codec.c
    ${FW_ROOT}/src/beet_ble_guard.c
    ${FW_ROOT}/src/beet_core.c
    ${FW_ROOT}/src/beet_iface_names.c
    ${SUPPORT_DIR}/esp_rom_crc.c
    ${SUPPORT_DIR}/ble_test_stubs.c
)

target_include_directories(
    beet_ble_transport_host_tests
    PRIVATE
        ${SUPPORT_DIR}
        ${FW_ROOT}/include
)

target_compile_definitions(beet_ble_transport_host_tests PRIVATE BEET_HOST_TEST=1)

if (CMAKE_C_COMPILER_ID MATCHES "Clang|GNU")
    target_compile_options(beet_ble_transport_host_tests PRIVATE -Wall -Wextra -Werror)
endif()

add_test(NAME beet_ble_transport_host_tests COMMAND beet_ble_transport_host_tests)
