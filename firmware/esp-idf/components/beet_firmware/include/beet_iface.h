#ifndef BEET_IFACE_H
#define BEET_IFACE_H

#include <stdbool.h>
#include <stdint.h>

#include "esp_err.h"
#include "beet_types.h"

typedef enum {
    BEET_IFACE_COMMAND_MANUAL_START = 0,
    BEET_IFACE_COMMAND_MANUAL_STOP = 1,
    BEET_IFACE_COMMAND_RELAY_TEST_START = 2,
    BEET_IFACE_COMMAND_RELAY_TEST_STOP = 3,
    BEET_IFACE_COMMAND_RESET_BLOCK = 4,
    BEET_IFACE_COMMAND_STORE_CALIBRATION = 5,
    BEET_IFACE_COMMAND_START_OTA = 6,
    BEET_IFACE_COMMAND_CLEAR_BLE_BONDS = 7,
    BEET_IFACE_COMMAND_GET_CALIBRATION = 8,
    BEET_IFACE_COMMAND_GET_HISTORY_SUMMARY = 9,
    BEET_IFACE_COMMAND_GET_EVENT = 10,
    BEET_IFACE_COMMAND_DISABLE_PAIR = 11,
    BEET_IFACE_COMMAND_ENABLE_PAIR = 12,
    BEET_IFACE_COMMAND_MOISTURE_TEST_START = 13,
    BEET_IFACE_COMMAND_GET_SYSTEM_HISTORY_SUMMARY = 14,
    BEET_IFACE_COMMAND_GET_SYSTEM_EVENT = 15,
    BEET_IFACE_COMMAND_GET_WATERING_HISTORY_SUMMARY = 16,
    BEET_IFACE_COMMAND_GET_WATERING_EVENT = 17,
    BEET_IFACE_COMMAND_SET_TIME = 18,
} beet_iface_command_t;

typedef enum {
    BEET_IFACE_STATUS_ACCEPTED = 0,
    BEET_IFACE_STATUS_REJECTED = 1,
} beet_iface_status_t;

typedef enum {
    BEET_IFACE_REASON_NONE = 0,
    BEET_IFACE_REASON_SLOT_ALLOCATED = 1,
    BEET_IFACE_REASON_QUEUED_WAITING_FOR_SLOT = 2,
    BEET_IFACE_REASON_ALREADY_STOPPED = 3,
    BEET_IFACE_REASON_STOPPED = 4,
    BEET_IFACE_REASON_NOT_BLOCKED = 5,
    BEET_IFACE_REASON_BLOCK_RESET = 6,
    BEET_IFACE_REASON_CALIBRATION_SAVED = 7,
    BEET_IFACE_REASON_BONDS_CLEARED = 8,
    BEET_IFACE_REASON_NO_BONDS = 9,
    BEET_IFACE_REASON_PAIR_BLOCKED = 10,
    BEET_IFACE_REASON_PAIR_FAULTED = 11,
    BEET_IFACE_REASON_LOW_BATTERY = 12,
    BEET_IFACE_REASON_SLOT_UNAVAILABLE = 13,
    BEET_IFACE_REASON_INVALID_CALIBRATION = 14,
    BEET_IFACE_REASON_INVALID_DURATION = 15,
    BEET_IFACE_REASON_UNSUPPORTED_COMMAND = 16,
    BEET_IFACE_REASON_OTA_IN_PROGRESS = 17,
    BEET_IFACE_REASON_OUTPUTS_DISABLED = 18,
    BEET_IFACE_REASON_SENSOR_INVALID = 19,
    BEET_IFACE_REASON_ALREADY_ACTIVE = 20,
    BEET_IFACE_REASON_INVALID_PAIR = 21,
    BEET_IFACE_REASON_EVENT_NOT_FOUND = 22,
    BEET_IFACE_REASON_PAIR_DISABLED = 23,
    BEET_IFACE_REASON_PAIR_ENABLED = 24,
    BEET_IFACE_REASON_RELAY_TEST_STARTED = 25,
    BEET_IFACE_REASON_RELAY_TEST_STOPPED = 26,
    BEET_IFACE_REASON_MOISTURE_TEST_STARTED = 27,
    BEET_IFACE_REASON_TIME_UPDATED = 28,
    BEET_IFACE_REASON_INVALID_TIME = 29,
    BEET_IFACE_REASON_BUSY = 30,
    BEET_IFACE_REASON_RATE_LIMITED = 31,
} beet_iface_reason_t;

typedef struct {
    beet_iface_command_t command;
    uint8_t pair_index;
    bool has_duration_s;
    uint16_t duration_s;
    uint16_t dry_mv;
    uint16_t wet_mv;
    uint64_t seq_no;
    uint64_t unix_s;
} beet_iface_command_request_t;

typedef struct {
    beet_iface_command_t command;
    uint8_t pair_index;
    beet_iface_status_t status;
    beet_iface_reason_t reason;
    uint16_t accepted_duration_s;
    bool has_calibration;
    beet_pair_calibration_t calibration;
    bool has_history_summary;
    uint64_t latest_event_seq_no;
    uint16_t event_count;
    uint32_t pair_totals_s[BEET_PAIR_COUNT];
    bool has_event;
    beet_event_record_t event;
    bool has_system_history_summary;
    uint64_t latest_system_event_seq_no;
    uint16_t system_event_count;
    bool has_system_event;
    beet_system_event_record_t system_event;
} beet_iface_command_response_t;

typedef struct {
    char device_id[BEET_DEVICE_ID_MAX_LEN + 1U];
    beet_battery_state_t battery_state;
    uint16_t battery_mv;
    bool time_valid;
    uint32_t boot_id;
    uint32_t next_check_in_s;
    uint8_t active_pumps;
    bool wifi_connected;
    bool mqtt_connected;
    uint32_t uptime_s;
} beet_iface_device_state_t;

typedef struct {
    uint8_t pair_index;
    beet_pair_state_t pair_state;
    uint8_t moisture_pct;
    uint16_t sensor_mv;
    bool pump_active;
    uint16_t remaining_s;
    bool blocked;
    beet_block_reason_t block_reason;
    beet_run_source_t source;
    bool enabled;
    bool sensor_valid;
} beet_iface_pair_state_t;

typedef struct {
    beet_event_record_t record;
} beet_iface_event_t;

typedef struct {
    beet_system_event_record_t record;
} beet_iface_system_event_t;

esp_err_t beet_iface_get_device_state(beet_iface_device_state_t *state);
esp_err_t beet_iface_get_pair_state(uint8_t pair_index, beet_iface_pair_state_t *state);
esp_err_t beet_iface_get_all_pair_states(beet_iface_pair_state_t states[BEET_PAIR_COUNT]);
esp_err_t beet_iface_get_event(uint64_t seq_no, beet_iface_event_t *event);
esp_err_t beet_iface_get_system_event(uint64_t seq_no, beet_iface_system_event_t *event);
uint64_t beet_iface_get_latest_event_seq_no(void);
uint64_t beet_iface_get_latest_system_event_seq_no(void);
esp_err_t beet_iface_submit_command(
    const beet_iface_command_request_t *request,
    beet_iface_command_response_t *response);
const char *beet_iface_command_name(beet_iface_command_t command);
const char *beet_iface_status_name(beet_iface_status_t status);
const char *beet_iface_reason_name(beet_iface_reason_t reason);

#endif
