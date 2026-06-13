#include "beet_types.h"

#include <string.h>
#include "sdkconfig.h"
#include "esp_rom_crc.h"

BEET_STATIC_ASSERT(sizeof(beet_event_record_t) == 64U, "event record size must be 64 bytes");
BEET_STATIC_ASSERT(sizeof(beet_system_event_record_t) == 64U, "system event record size must be 64 bytes");

void beet_default_app_config(beet_app_config_t *config)
{
    memset(config, 0, sizeof(*config));
    config->schema_version = BEET_APP_CONFIG_SCHEMA_VERSION;
    strncpy(config->device_id, "beetmeister-01", sizeof(config->device_id) - 1U);
    config->pair_count = BEET_PAIR_COUNT;
    config->watering_interval_s = BEET_SCHEDULER_INTERVAL_S;
    config->idle_sleep_threshold_mv = BEET_IDLE_SLEEP_THRESHOLD_MV;
    config->deep_sleep_threshold_mv = BEET_DEEP_SLEEP_THRESHOLD_MV;
    config->deep_sleep_resume_mv = BEET_DEEP_SLEEP_RESUME_MV;
    config->watering_abort_threshold_mv = BEET_WATERING_ABORT_THRESHOLD_MV;
    config->inactivity_sleep_timeout_s = BEET_INACTIVITY_SLEEP_TIMEOUT_S;
    strncpy(config->mqtt_base_topic, "beetmeister", sizeof(config->mqtt_base_topic) - 1U);
    config->valve_enabled = false;
    config->valve_servo_min_pulse_us = BEET_VALVE_SERVO_MIN_PULSE_US;
    config->valve_servo_max_pulse_us = BEET_VALVE_SERVO_MAX_PULSE_US;
    config->valve_open_pulse_us = BEET_VALVE_OPEN_PULSE_US;
    config->valve_shut_pulse_us = BEET_VALVE_SHUT_PULSE_US;
    config->valve_move_duration_ms = BEET_VALVE_MOVE_DURATION_MS;
    config->valve_settle_delay_ms = BEET_VALVE_SETTLE_DELAY_MS;
    config->valve_open_hold_ms = BEET_VALVE_OPEN_HOLD_MS;
}

void beet_default_calibration(uint8_t pair_index, beet_pair_calibration_t *calibration)
{
    memset(calibration, 0, sizeof(*calibration));
    calibration->pair_index = pair_index;
    calibration->dry_mv = BEET_DEFAULT_DRY_MV;
    calibration->wet_mv = BEET_DEFAULT_WET_MV;
    calibration->source = BEET_CALIBRATION_SOURCE_DEFAULT;
}

void beet_default_snapshot(uint8_t pair_index, beet_pair_runtime_snapshot_t *snapshot)
{
    memset(snapshot, 0, sizeof(*snapshot));
    snapshot->pair_index = pair_index;
    snapshot->enabled = true;
    snapshot->pair_state = BEET_PAIR_STATE_IDLE;
    snapshot->block_reason = BEET_BLOCK_REASON_NONE;
    snapshot->active_run_source = BEET_RUN_SOURCE_NONE;
}

void beet_default_power_runtime_state(beet_power_runtime_state_t *state)
{
    memset(state, 0, sizeof(*state));
    state->schema_version = BEET_POWER_RUNTIME_STATE_SCHEMA_VERSION;
    state->last_sleep_mode = BEET_SLEEP_MODE_NONE;
    state->boot_counter = 0U;
}

bool beet_is_valid_pair_index(uint8_t pair_index)
{
    return pair_index >= 1U && pair_index <= BEET_PAIR_COUNT;
}

bool beet_is_valid_pair_state(beet_pair_state_t state)
{
    return state >= BEET_PAIR_STATE_IDLE && state <= BEET_PAIR_STATE_MOISTURE_TEST;
}

bool beet_is_valid_battery_state(beet_battery_state_t state)
{
    return state >= BEET_BATTERY_STATE_ACTIVE && state <= BEET_BATTERY_STATE_OTA_IN_PROGRESS;
}

bool beet_is_valid_run_source(beet_run_source_t source)
{
    return source >= BEET_RUN_SOURCE_NONE && source <= BEET_RUN_SOURCE_TEST;
}

bool beet_is_valid_block_reason(beet_block_reason_t reason)
{
    return reason >= BEET_BLOCK_REASON_NONE && reason <= BEET_BLOCK_REASON_LOW_BATTERY_ABORT;
}

bool beet_is_valid_stop_reason(beet_stop_reason_t reason)
{
    return reason >= BEET_STOP_REASON_COMPLETED && reason <= BEET_STOP_REASON_DEEP_LOW_BATTERY_SLEEP;
}

bool beet_is_valid_sleep_mode(beet_sleep_mode_t mode)
{
    return mode >= BEET_SLEEP_MODE_NONE && mode <= BEET_SLEEP_MODE_DEEP_LOW_BATTERY;
}

bool beet_is_valid_valve_state(beet_valve_state_t state)
{
    return state >= BEET_VALVE_STATE_CLOSED && state <= BEET_VALVE_STATE_FAULT;
}

bool beet_is_valid_valve_pulse_us(uint16_t pulse_us)
{
    return pulse_us >= BEET_VALVE_SERVO_MIN_PULSE_US && pulse_us <= BEET_VALVE_SERVO_MAX_PULSE_US;
}

bool beet_is_valid_valve_pulse_range(uint16_t min_pulse_us, uint16_t max_pulse_us)
{
    return beet_is_valid_valve_pulse_us(min_pulse_us) &&
        beet_is_valid_valve_pulse_us(max_pulse_us) &&
        min_pulse_us < max_pulse_us;
}

bool beet_is_valid_valve_move_duration_ms(uint16_t duration_ms)
{
    return duration_ms >= 100U && duration_ms <= 5000U;
}

bool beet_is_valid_valve_settle_delay_ms(uint16_t delay_ms)
{
    return delay_ms <= 5000U;
}

bool beet_is_valid_valve_open_hold_ms(uint16_t hold_ms)
{
    return hold_ms <= 10000U;
}

bool beet_is_valid_watering_interval_s(uint32_t interval_s)
{
    return interval_s >= BEET_MIN_WATERING_INTERVAL_S && interval_s <= BEET_MAX_WATERING_INTERVAL_S;
}

bool beet_is_valid_system_event_type(beet_system_event_type_t type)
{
    switch (type) {
    case BEET_SYSTEM_EVENT_STARTUP:
    case BEET_SYSTEM_EVENT_SLEEP:
    case BEET_SYSTEM_EVENT_BLE_CONNECT:
    case BEET_SYSTEM_EVENT_BLE_DISCONNECT:
    case BEET_SYSTEM_EVENT_BLE_BOND_SUCCESS:
    case BEET_SYSTEM_EVENT_BLE_BOND_FAILED:
    case BEET_SYSTEM_EVENT_BLE_BONDS_CLEARED:
    case BEET_SYSTEM_EVENT_VALVE_OPENED:
    case BEET_SYSTEM_EVENT_VALVE_CLOSED:
    case BEET_SYSTEM_EVENT_MQTT_CONNECT:
    case BEET_SYSTEM_EVENT_MQTT_DISCONNECT:
    case BEET_SYSTEM_EVENT_MQTT_PUBLISH_FAILED:
    case BEET_SYSTEM_EVENT_OTA_STARTED:
    case BEET_SYSTEM_EVENT_OTA_FAILED:
    case BEET_SYSTEM_EVENT_OTA_READY:
        return true;
    default:
        return false;
    }
}

const char *beet_valve_state_name(beet_valve_state_t state)
{
    switch (state) {
    case BEET_VALVE_STATE_CLOSED:
        return "CLOSED";
    case BEET_VALVE_STATE_OPENING:
        return "OPENING";
    case BEET_VALVE_STATE_OPEN:
        return "OPEN";
    case BEET_VALVE_STATE_CLOSING:
        return "CLOSING";
    case BEET_VALVE_STATE_FAULT:
        return "FAULT";
    default:
        return "UNKNOWN";
    }
}

uint16_t beet_correct_moisture_sensor_mv(uint16_t sensor_mv, uint16_t battery_mv)
{
    // These capacitive sensors keep a stable dry output until the sensor supply drops below a
    // measured knee. Below that point the output loses headroom almost 1:1 with the battery rail,
    // so add the missing headroom back before applying the dry/wet calibration defaults.
    if (battery_mv >= CONFIG_BEET_MOISTURE_SENSOR_SUPPLY_KNEE_MV) {
        return sensor_mv;
    }

    uint32_t corrected_mv = sensor_mv + (CONFIG_BEET_MOISTURE_SENSOR_SUPPLY_KNEE_MV - battery_mv);
    return corrected_mv > UINT16_MAX ? UINT16_MAX : (uint16_t)corrected_mv;
}

bool beet_is_sensor_mv_plausible(uint16_t corrected_sensor_mv, uint16_t dry_mv, uint16_t wet_mv)
{
    uint16_t min_valid_mv = (wet_mv > BEET_SENSOR_INVALID_LOW_MARGIN_MV) ?
        (uint16_t)(wet_mv - BEET_SENSOR_INVALID_LOW_MARGIN_MV) :
        0U;
    uint16_t max_valid_mv = (dry_mv <= (UINT16_MAX - BEET_SENSOR_INVALID_HIGH_MARGIN_MV)) ?
        (uint16_t)(dry_mv + BEET_SENSOR_INVALID_HIGH_MARGIN_MV) :
        UINT16_MAX;

    return corrected_sensor_mv >= min_valid_mv && corrected_sensor_mv <= max_valid_mv;
}

uint8_t beet_moisture_pct_from_mv(uint16_t dry_mv, uint16_t wet_mv, uint16_t sensor_mv)
{
    uint16_t dry_clamp_mv = (dry_mv <= (UINT16_MAX - BEET_SENSOR_DRY_CLAMP_HEADROOM_MV)) ?
        (uint16_t)(dry_mv + BEET_SENSOR_DRY_CLAMP_HEADROOM_MV) :
        UINT16_MAX;

    if (dry_mv <= wet_mv) {
        return 0U;
    }

    if (sensor_mv >= dry_clamp_mv) {
        return 0U;
    }

    if (sensor_mv <= wet_mv) {
        return 100U;
    }

    int32_t numerator = ((int32_t)dry_mv - (int32_t)sensor_mv) * 100;
    int32_t denominator = (int32_t)dry_mv - (int32_t)wet_mv;
    int32_t pct = (numerator + (denominator / 2)) / denominator;

    if (pct < 0) {
        return 0U;
    }
    if (pct > 100) {
        return 100U;
    }
    return (uint8_t)pct;
}

uint8_t beet_battery_pct_from_mv(uint16_t battery_mv)
{
    // Coarse single-cell LiFePO4 estimate for display only.
    // LiFePO4 has a long voltage plateau, so pack voltage alone is not a precise SOC measurement.
    // This table intentionally uses broad 10% bands and should not be treated as a true coulomb-counted SOC.
    if (battery_mv >= 3600U) {
        return 100U;
    }
    if (battery_mv >= 3500U) {
        return 90U;
    }
    if (battery_mv >= 3450U) {
        return 80U;
    }
    if (battery_mv >= 3400U) {
        return 70U;
    }
    if (battery_mv >= 3370U) {
        return 60U;
    }
    if (battery_mv >= 3340U) {
        return 50U;
    }
    if (battery_mv >= 3310U) {
        return 40U;
    }
    if (battery_mv >= 3280U) {
        return 30U;
    }
    if (battery_mv >= 3250U) {
        return 20U;
    }
    if (battery_mv >= 3220U) {
        return 10U;
    }
    return 0U;
}

uint32_t beet_deep_low_recovery_interval_s(uint8_t failure_count)
{
    if (failure_count < 3U) {
        return 3600U;
    }
    if (failure_count < 9U) {
        return 7200U;
    }
    return 14400U;
}

uint16_t beet_automatic_duration_s(uint8_t moisture_pct)
{
    if (moisture_pct >= 81U) {
        return 0U;
    }
    if (moisture_pct == 80U) {
        return 10U;
    }
    if (moisture_pct >= 70U) {
        return 60U;
    }
    if (moisture_pct >= 60U) {
        return 120U;
    }
    if (moisture_pct >= 50U) {
        return 180U;
    }
    return 240U;
}

uint16_t beet_manual_duration_s(uint8_t moisture_pct)
{
    uint16_t duration = beet_automatic_duration_s(moisture_pct);
    return duration == 0U ? 10U : duration;
}

bool beet_is_valid_manual_duration_s(uint16_t duration_s)
{
    return duration_s >= 1U && duration_s <= BEET_MAX_MANUAL_DURATION_S;
}

bool beet_sanity_check_passed(uint8_t pre_run_pct, uint8_t post_run_pct)
{
    return (int32_t)post_run_pct - (int32_t)pre_run_pct > 3;
}

beet_battery_state_t beet_classify_battery_state(
    uint16_t battery_mv,
    bool ota_in_progress,
    bool active_watering,
    uint32_t inactivity_s)
{
    if (ota_in_progress) {
        return BEET_BATTERY_STATE_OTA_IN_PROGRESS;
    }
    if (!active_watering && battery_mv <= BEET_DEEP_SLEEP_THRESHOLD_MV) {
        return BEET_BATTERY_STATE_DEEP_LOW_BATTERY;
    }
    if (!active_watering &&
        battery_mv < BEET_IDLE_SLEEP_THRESHOLD_MV &&
        inactivity_s >= BEET_INACTIVITY_SLEEP_TIMEOUT_S) {
        return BEET_BATTERY_STATE_IDLE_LOW_POWER;
    }
    return BEET_BATTERY_STATE_ACTIVE;
}

uint32_t beet_event_crc32(const beet_event_record_t *record)
{
    return esp_rom_crc32_le(
        0U,
        (const uint8_t *)record,
        sizeof(beet_event_record_t) - sizeof(record->crc32));
}

bool beet_validate_event_record(const beet_event_record_t *record)
{
    if (record->schema_version != BEET_EVENT_RECORD_VERSION) {
        return false;
    }
    if (!beet_is_valid_stop_reason((beet_stop_reason_t)record->stop_reason)) {
        return false;
    }
    if (!beet_is_valid_block_reason((beet_block_reason_t)record->block_reason)) {
        return false;
    }
    if (record->boot_id == 0U) {
        return false;
    }
    if (!beet_is_valid_pair_index(record->pair_index)) {
        return false;
    }
    if (!beet_is_valid_run_source((beet_run_source_t)record->trigger_source)) {
        return false;
    }
    if ((beet_stop_reason_t)record->stop_reason >= BEET_STOP_REASON_IDLE_LOW_POWER_SLEEP) {
        return false;
    }
    return beet_event_crc32(record) == record->crc32;
}

bool beet_event_record_is_visible(const beet_event_record_t *record, uint32_t current_boot_id)
{
    (void)current_boot_id;
    if (!beet_validate_event_record(record)) {
        return false;
    }
    return true;
}

uint32_t beet_system_event_crc32(const beet_system_event_record_t *record)
{
    return esp_rom_crc32_le(
        0U,
        (const uint8_t *)record,
        sizeof(beet_system_event_record_t) - sizeof(record->crc32));
}

bool beet_validate_system_event_record(const beet_system_event_record_t *record)
{
    if (record->schema_version != BEET_SYSTEM_EVENT_RECORD_VERSION) {
        return false;
    }
    if (record->boot_id == 0U) {
        return false;
    }
    if (!beet_is_valid_system_event_type((beet_system_event_type_t)record->event_type)) {
        return false;
    }
    return beet_system_event_crc32(record) == record->crc32;
}

bool beet_system_event_record_is_visible(const beet_system_event_record_t *record, uint32_t current_boot_id)
{
    (void)current_boot_id;
    if (!beet_validate_system_event_record(record)) {
        return false;
    }
    return true;
}

uint32_t beet_boot_epoch_crc32(const beet_boot_epoch_record_t *record)
{
    return esp_rom_crc32_le(
        0U,
        (const uint8_t *)record,
        sizeof(beet_boot_epoch_record_t) - sizeof(record->crc32));
}

bool beet_validate_boot_epoch_record(const beet_boot_epoch_record_t *record)
{
    if ((record->flags & 0x01U) == 0U) {
        return false;
    }
    if (record->boot_id == 0U || record->boot_epoch_unix_s == 0U) {
        return false;
    }
    return beet_boot_epoch_crc32(record) == record->crc32;
}

const char *beet_pair_state_name(beet_pair_state_t state)
{
    switch (state) {
    case BEET_PAIR_STATE_IDLE:
        return "IDLE";
    case BEET_PAIR_STATE_WAITING_FOR_SLOT:
        return "WAITING_FOR_SLOT";
    case BEET_PAIR_STATE_SANITY_CHECK:
        return "SANITY_CHECK";
    case BEET_PAIR_STATE_WATERING:
        return "WATERING";
    case BEET_PAIR_STATE_BLOCKED:
        return "BLOCKED";
    case BEET_PAIR_STATE_FAULT:
        return "FAULT";
    case BEET_PAIR_STATE_DISABLED:
        return "DISABLED";
    case BEET_PAIR_STATE_MOISTURE_TEST:
        return "MOISTURE_TEST";
    default:
        return "UNKNOWN";
    }
}

const char *beet_battery_state_name(beet_battery_state_t state)
{
    switch (state) {
    case BEET_BATTERY_STATE_ACTIVE:
        return "ACTIVE";
    case BEET_BATTERY_STATE_IDLE_LOW_POWER:
        return "IDLE_LOW_POWER";
    case BEET_BATTERY_STATE_DEEP_LOW_BATTERY:
        return "DEEP_LOW_BATTERY";
    case BEET_BATTERY_STATE_OTA_IN_PROGRESS:
        return "OTA_IN_PROGRESS";
    default:
        return "UNKNOWN";
    }
}

const char *beet_block_reason_name(beet_block_reason_t reason)
{
    switch (reason) {
    case BEET_BLOCK_REASON_NONE:
        return "NONE";
    case BEET_BLOCK_REASON_MOISTURE_RESPONSE_TEST_FAILED:
        return "MOISTURE_RESPONSE_TEST_FAILED";
    case BEET_BLOCK_REASON_SENSOR_READING_INVALID:
        return "SENSOR_READING_INVALID";
    case BEET_BLOCK_REASON_LOW_BATTERY_ABORT:
        return "LOW_BATTERY_ABORT";
    default:
        return "UNKNOWN";
    }
}

const char *beet_stop_reason_name(beet_stop_reason_t reason)
{
    switch (reason) {
    case BEET_STOP_REASON_COMPLETED:
        return "COMPLETED";
    case BEET_STOP_REASON_MANUAL_STOP:
        return "MANUAL_STOP";
    case BEET_STOP_REASON_LOW_BATTERY_ABORT:
        return "LOW_BATTERY_ABORT";
    case BEET_STOP_REASON_SENSOR_SANITY_FAILURE:
        return "SENSOR_SANITY_FAILURE";
    case BEET_STOP_REASON_SENSOR_INVALID_ABORT:
        return "SENSOR_INVALID_ABORT";
    case BEET_STOP_REASON_SYSTEM_ABORT:
        return "SYSTEM_ABORT";
    case BEET_STOP_REASON_IDLE_LOW_POWER_SLEEP:
        return "IDLE_LOW_POWER_SLEEP";
    case BEET_STOP_REASON_DEEP_LOW_BATTERY_SLEEP:
        return "DEEP_LOW_BATTERY_SLEEP";
    default:
        return "UNKNOWN";
    }
}

const char *beet_system_event_type_name(beet_system_event_type_t type)
{
    switch (type) {
    case BEET_SYSTEM_EVENT_STARTUP:
        return "STARTUP";
    case BEET_SYSTEM_EVENT_SLEEP:
        return "SLEEP";
    case BEET_SYSTEM_EVENT_BLE_CONNECT:
        return "BLE_CONNECT";
    case BEET_SYSTEM_EVENT_BLE_DISCONNECT:
        return "BLE_DISCONNECT";
    case BEET_SYSTEM_EVENT_BLE_BOND_SUCCESS:
        return "BLE_BOND_SUCCESS";
    case BEET_SYSTEM_EVENT_BLE_BOND_FAILED:
        return "BLE_BOND_FAILED";
    case BEET_SYSTEM_EVENT_BLE_BONDS_CLEARED:
        return "BLE_BONDS_CLEARED";
    case BEET_SYSTEM_EVENT_VALVE_OPENED:
        return "VALVE_OPENED";
    case BEET_SYSTEM_EVENT_VALVE_CLOSED:
        return "VALVE_CLOSED";
    case BEET_SYSTEM_EVENT_MQTT_CONNECT:
        return "MQTT_CONNECT";
    case BEET_SYSTEM_EVENT_MQTT_DISCONNECT:
        return "MQTT_DISCONNECT";
    case BEET_SYSTEM_EVENT_MQTT_PUBLISH_FAILED:
        return "MQTT_PUBLISH_FAILED";
    case BEET_SYSTEM_EVENT_OTA_STARTED:
        return "OTA_STARTED";
    case BEET_SYSTEM_EVENT_OTA_FAILED:
        return "OTA_FAILED";
    case BEET_SYSTEM_EVENT_OTA_READY:
        return "OTA_READY";
    default:
        return "UNKNOWN";
    }
}
