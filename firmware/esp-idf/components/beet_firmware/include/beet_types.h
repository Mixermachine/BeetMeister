#ifndef BEET_TYPES_H
#define BEET_TYPES_H

#include <stdbool.h>
#include <stdint.h>

#if defined(_MSC_VER)
#define BEET_STATIC_ASSERT_GLUE_(a, b) a##b
#define BEET_STATIC_ASSERT_GLUE(a, b) BEET_STATIC_ASSERT_GLUE_(a, b)
#define BEET_STATIC_ASSERT(condition, message) \
    typedef char BEET_STATIC_ASSERT_GLUE(beet_static_assert_, __LINE__)[(condition) ? 1 : -1]
#define BEET_PACKED_BEGIN __pragma(pack(push, 1))
#define BEET_PACKED_END __pragma(pack(pop))
#define BEET_PACKED
#else
#define BEET_STATIC_ASSERT(condition, message) _Static_assert(condition, message)
#define BEET_PACKED_BEGIN
#define BEET_PACKED_END
#define BEET_PACKED __attribute__((packed))
#endif

#define BEET_SCHEMA_VERSION 1U
#define BEET_EVENT_RECORD_VERSION 1U
#define BEET_PAIR_COUNT 8U
#define BEET_EVENT_RING_CAPACITY 1000U
#define BEET_DEVICE_ID_MAX_LEN 24U
#define BEET_MQTT_HOST_MAX_LEN 64U
#define BEET_MQTT_USER_MAX_LEN 32U
#define BEET_MQTT_PASSWORD_MAX_LEN 64U
#define BEET_MQTT_BASE_TOPIC_MAX_LEN 48U
#define BEET_OTA_URL_MAX_LEN 96U
#define BEET_BLOCK_DURATION_S (24U * 60U * 60U)
#define BEET_SCHEDULER_INTERVAL_S 7200U
#define BEET_MAX_ACTIVE_PUMPS 3U
#define BEET_SANITY_CHECK_DURATION_S 10U
#define BEET_MANUAL_QUEUE_TIMEOUT_S 30U
#define BEET_MAX_MANUAL_DURATION_S 1200U
#define BEET_SNAPSHOT_COALESCE_S 5U
#define BEET_IDLE_SLEEP_THRESHOLD_MV 3300U
#define BEET_DEEP_SLEEP_THRESHOLD_MV 3200U
#define BEET_DEEP_SLEEP_RESUME_MV 3250U
#define BEET_WATERING_ABORT_THRESHOLD_MV 3100U
#define BEET_INACTIVITY_SLEEP_TIMEOUT_S 300U
#define BEET_DEFAULT_DRY_MV 2765U
#define BEET_DEFAULT_WET_MV 900U
#define BEET_SENSOR_DRY_CLAMP_HEADROOM_MV 100U
#define BEET_SENSOR_INVALID_LOW_MARGIN_MV 400U
#define BEET_SENSOR_INVALID_HIGH_MARGIN_MV 200U
#define BEET_BATTERY_FILTER_WINDOW 4U
#define BEET_BATTERY_SPIKE_REJECT_MV 400U
#define BEET_BATTERY_OUTLIER_ACCEPT_COUNT 3U
#define BEET_WAKE_INDICATOR_PULSE_MS 120U

typedef enum {
    BEET_PAIR_STATE_IDLE = 0,
    BEET_PAIR_STATE_WAITING_FOR_SLOT = 1,
    BEET_PAIR_STATE_SANITY_CHECK = 2,
    BEET_PAIR_STATE_WATERING = 3,
    BEET_PAIR_STATE_BLOCKED = 4,
    BEET_PAIR_STATE_FAULT = 5,
    BEET_PAIR_STATE_DISABLED = 6,
    BEET_PAIR_STATE_MOISTURE_TEST = 7,
} beet_pair_state_t;

typedef enum {
    BEET_BATTERY_STATE_ACTIVE = 0,
    BEET_BATTERY_STATE_IDLE_LOW_POWER = 1,
    BEET_BATTERY_STATE_DEEP_LOW_BATTERY = 2,
    BEET_BATTERY_STATE_OTA_IN_PROGRESS = 3,
} beet_battery_state_t;

typedef enum {
    BEET_SLEEP_MODE_NONE = 0,
    BEET_SLEEP_MODE_LIGHT_IDLE = 1,
    BEET_SLEEP_MODE_DEEP_LOW_BATTERY = 2,
} beet_sleep_mode_t;

typedef enum {
    BEET_RUN_SOURCE_NONE = 0,
    BEET_RUN_SOURCE_AUTOMATIC = 1,
    BEET_RUN_SOURCE_MANUAL = 2,
    BEET_RUN_SOURCE_TEST = 3,
} beet_run_source_t;

typedef enum {
    BEET_CALIBRATION_SOURCE_DEFAULT = 0,
    BEET_CALIBRATION_SOURCE_USER = 1,
} beet_calibration_source_t;

typedef enum {
    BEET_BLOCK_REASON_NONE = 0,
    BEET_BLOCK_REASON_MOISTURE_RESPONSE_TEST_FAILED = 1,
    BEET_BLOCK_REASON_SENSOR_READING_INVALID = 2,
    BEET_BLOCK_REASON_LOW_BATTERY_ABORT = 3,
} beet_block_reason_t;

typedef enum {
    BEET_STOP_REASON_COMPLETED = 0,
    BEET_STOP_REASON_MANUAL_STOP = 1,
    BEET_STOP_REASON_LOW_BATTERY_ABORT = 2,
    BEET_STOP_REASON_SENSOR_SANITY_FAILURE = 3,
    BEET_STOP_REASON_SENSOR_INVALID_ABORT = 4,
    BEET_STOP_REASON_SYSTEM_ABORT = 5,
    BEET_STOP_REASON_IDLE_LOW_POWER_SLEEP = 6,
    BEET_STOP_REASON_DEEP_LOW_BATTERY_SLEEP = 7,
} beet_stop_reason_t;

typedef struct {
    uint16_t schema_version;
    char device_id[BEET_DEVICE_ID_MAX_LEN + 1U];
    uint8_t pair_count;
    uint32_t watering_interval_s;
    uint16_t idle_sleep_threshold_mv;
    uint16_t deep_sleep_threshold_mv;
    uint16_t deep_sleep_resume_mv;
    uint16_t watering_abort_threshold_mv;
    uint16_t inactivity_sleep_timeout_s;
    char mqtt_broker_host[BEET_MQTT_HOST_MAX_LEN + 1U];
    uint16_t mqtt_broker_port;
    char mqtt_username[BEET_MQTT_USER_MAX_LEN + 1U];
    char mqtt_password[BEET_MQTT_PASSWORD_MAX_LEN + 1U];
    char mqtt_base_topic[BEET_MQTT_BASE_TOPIC_MAX_LEN + 1U];
    char ota_base_url[BEET_OTA_URL_MAX_LEN + 1U];
    uint16_t flags;
} beet_app_config_t;

typedef struct {
    uint8_t pair_index;
    uint16_t dry_mv;
    uint16_t wet_mv;
    uint32_t calibrated_at_unix_s;
    beet_calibration_source_t source;
} beet_pair_calibration_t;

typedef struct {
    uint8_t pair_index;
    beet_pair_state_t pair_state;
    uint8_t last_moisture_pct;
    uint16_t last_sensor_mv;
    bool sensor_valid;
    bool enabled;
    beet_block_reason_t block_reason;
    uint32_t block_until_unix_s;
    uint32_t block_remaining_s;
    uint32_t active_run_id;
    beet_run_source_t active_run_source;
    uint32_t run_started_unix_s;
    uint16_t run_elapsed_s;
    uint16_t run_target_s;
    uint32_t next_check_due_unix_s;
    uint32_t next_check_due_in_s;
} beet_pair_runtime_snapshot_t;

BEET_PACKED_BEGIN
typedef struct BEET_PACKED {
    uint8_t schema_version;
    uint64_t seq_no;
    uint8_t pair_index;
    uint8_t trigger_source;
    uint32_t started_at_unix_s;
    uint32_t ended_at_unix_s;
    uint8_t time_valid;
    uint8_t moisture_before_pct;
    uint8_t moisture_after_pct;
    uint16_t sensor_before_mv;
    uint16_t sensor_after_mv;
    uint16_t requested_duration_s;
    uint16_t actual_duration_s;
    uint8_t stop_reason;
    uint8_t block_reason;
    uint16_t battery_start_mv;
    uint16_t battery_end_mv;
    uint8_t reserved[24];
    uint32_t crc32;
} beet_event_record_t;
BEET_PACKED_END

typedef struct {
    bool has_valid_records;
    uint64_t highest_valid_seq_no;
    uint16_t next_write_slot;
} beet_event_ring_state_t;

typedef struct {
    uint16_t schema_version;
    beet_sleep_mode_t last_sleep_mode;
    uint8_t deep_low_recovery_failures;
    uint8_t reserved0;
    uint32_t reserved1;
} beet_power_runtime_state_t;

void beet_default_app_config(beet_app_config_t *config);
void beet_default_calibration(uint8_t pair_index, beet_pair_calibration_t *calibration);
void beet_default_snapshot(uint8_t pair_index, beet_pair_runtime_snapshot_t *snapshot);
void beet_default_power_runtime_state(beet_power_runtime_state_t *state);
bool beet_is_valid_pair_index(uint8_t pair_index);
bool beet_is_valid_pair_state(beet_pair_state_t state);
bool beet_is_valid_battery_state(beet_battery_state_t state);
bool beet_is_valid_run_source(beet_run_source_t source);
bool beet_is_valid_block_reason(beet_block_reason_t reason);
bool beet_is_valid_stop_reason(beet_stop_reason_t reason);
bool beet_is_valid_sleep_mode(beet_sleep_mode_t mode);
uint16_t beet_correct_moisture_sensor_mv(uint16_t sensor_mv, uint16_t battery_mv);
bool beet_is_sensor_mv_plausible(uint16_t corrected_sensor_mv, uint16_t dry_mv, uint16_t wet_mv);
uint8_t beet_moisture_pct_from_mv(uint16_t dry_mv, uint16_t wet_mv, uint16_t sensor_mv);
uint8_t beet_battery_pct_from_mv(uint16_t battery_mv);
uint32_t beet_deep_low_recovery_interval_s(uint8_t failure_count);
uint16_t beet_automatic_duration_s(uint8_t moisture_pct);
uint16_t beet_manual_duration_s(uint8_t moisture_pct);
bool beet_is_valid_manual_duration_s(uint16_t duration_s);
bool beet_sanity_check_passed(uint8_t pre_run_pct, uint8_t post_run_pct);
beet_battery_state_t beet_classify_battery_state(
    uint16_t battery_mv,
    bool ota_in_progress,
    bool active_watering,
    uint32_t inactivity_s);
uint32_t beet_event_crc32(const beet_event_record_t *record);
bool beet_validate_event_record(const beet_event_record_t *record);
const char *beet_pair_state_name(beet_pair_state_t state);
const char *beet_battery_state_name(beet_battery_state_t state);
const char *beet_block_reason_name(beet_block_reason_t reason);
const char *beet_stop_reason_name(beet_stop_reason_t reason);

#endif
