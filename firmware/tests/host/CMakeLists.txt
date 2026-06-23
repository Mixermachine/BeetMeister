cmake_minimum_required(VERSION 3.20)

project(beetmeister_host_tests C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_C_EXTENSIONS OFF)

enable_testing()

set(FW_ROOT ${CMAKE_CURRENT_LIST_DIR}/../../esp-idf/components/beet_firmware)
set(SUPPORT_DIR ${CMAKE_CURRENT_LIST_DIR}/support)
set(PROTOCOL_VERSIONS_FILE ${CMAKE_CURRENT_LIST_DIR}/../../../config/protocol_versions.properties)
set(GENERATED_METADATA_HEADER ${FW_ROOT}/include/beet_generated_metadata.h)

file(STRINGS ${PROTOCOL_VERSIONS_FILE} PROTOCOL_VERSION_LINES REGEX "^[A-Za-z0-9_]+=")
foreach(PROTOCOL_LINE IN LISTS PROTOCOL_VERSION_LINES)
    if(PROTOCOL_LINE MATCHES "^maintenance_protocol_version=([0-9]+)$")
        set(BEET_SHARED_MAINTENANCE_PROTOCOL_VERSION ${CMAKE_MATCH_1})
    elseif(PROTOCOL_LINE MATCHES "^runtime_protocol_version=([0-9]+)$")
        set(BEET_SHARED_RUNTIME_PROTOCOL_VERSION ${CMAKE_MATCH_1})
    endif()
endforeach()

if(NOT DEFINED BEET_SHARED_MAINTENANCE_PROTOCOL_VERSION)
    message(FATAL_ERROR "Missing maintenance_protocol_version in ${PROTOCOL_VERSIONS_FILE}")
endif()

if(NOT DEFINED BEET_SHARED_RUNTIME_PROTOCOL_VERSION)
    message(FATAL_ERROR "Missing runtime_protocol_version in ${PROTOCOL_VERSIONS_FILE}")
endif()

find_package(Python3 REQUIRED COMPONENTS Interpreter)

execute_process(
    COMMAND
    ${Python3_EXECUTABLE}
    ${FW_ROOT}/tools/gen_beet_metadata_header.py
    --output ${GENERATED_METADATA_HEADER}
    --firmware-version f5146cc-dirty
    --build-label f5146cc-dirty
    --product-id beetmeister
    --hardware-rev rev_a
    --maintenance-protocol-version ${BEET_SHARED_MAINTENANCE_PROTOCOL_VERSION}
    --runtime-protocol-version ${BEET_SHARED_RUNTIME_PROTOCOL_VERSION}
    --image-kind bundled
    --compatible-hardware-rev rev_a
    COMMAND_ERROR_IS_FATAL ANY
)

add_executable(
    beet_host_tests
    ${CMAKE_CURRENT_LIST_DIR}/host_tests.c
    ${FW_ROOT}/src/beet_ble_codec.c
    ${FW_ROOT}/src/beet_ble_guard.c
    ${FW_ROOT}/src/beet_maintenance.c
    ${FW_ROOT}/src/beet_core.c
    ${FW_ROOT}/src/beet_event_ring.c
    ${FW_ROOT}/src/beet_iface_names.c
    ${SUPPORT_DIR}/esp_rom_crc.c
    ${SUPPORT_DIR}/ota_test_stubs.c
)

target_include_directories(
beet_host_tests
PRIVATE
${SUPPORT_DIR}
${FW_ROOT}/include
)
target_compile_definitions(beet_host_tests PRIVATE
    BEET_MAINTENANCE_PROTOCOL_VERSION=${BEET_SHARED_MAINTENANCE_PROTOCOL_VERSION}
    BEET_RUNTIME_PROTOCOL_VERSION=${BEET_SHARED_RUNTIME_PROTOCOL_VERSION}
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
    ${FW_ROOT}/src/beet_maintenance.c
    ${FW_ROOT}/src/beet_core.c
    ${FW_ROOT}/src/beet_iface_names.c
    ${SUPPORT_DIR}/esp_rom_crc.c
    ${SUPPORT_DIR}/ble_test_stubs.c
${SUPPORT_DIR}/ota_test_stubs.c
)
target_compile_definitions(beet_ble_transport_host_tests PRIVATE
    BEET_MAINTENANCE_PROTOCOL_VERSION=${BEET_SHARED_MAINTENANCE_PROTOCOL_VERSION}
    BEET_RUNTIME_PROTOCOL_VERSION=${BEET_SHARED_RUNTIME_PROTOCOL_VERSION}
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
