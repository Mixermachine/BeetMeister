#include "beet_types.h"

#include <string.h>
#include "sdkconfig.h"
#include "esp_rom_crc.h"

BEET_STATIC_ASSERT(sizeof(beet_event_record_t) == 64U, "event record size must be 64 bytes");

void beet_default_app_config(beet_app_config_t *config)
{
    memset(config, 0, sizeof(*config));
    config->schema_version = BEET_SCHEMA_VERSION;
    strncpy(config->device_id, "beetmeister-01", sizeof(config->device_id) - 1U);
    config->pair_count = BEET_PAIR_COUNT;
    config->watering_interval_s = BEET_SCHEDULER_INTERVAL_S;
    config->idle_sleep_threshold_mv = BEET_IDLE_SLEEP_THRESHOLD_MV;
    config->deep_sleep_threshold_mv = BEET_DEEP_SLEEP_THRESHOLD_MV;
    config->deep_sleep_resume_mv = BEET_DEEP_SLEEP_RESUME_MV;
    config->watering_abort_threshold_mv = BEET_WATERING_ABORT_THRESHOLD_MV;
    config->inactivity_sleep_timeout_s = BEET_INACTIVITY_SLEEP_TIMEOUT_S;
    strncpy(config->mqtt_base_topic, "beetmeister", sizeof(config->mqtt_base_topic) - 1U);
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
    state->schema_version = BEET_SCHEMA_VERSION;
    state->last_sleep_mode = BEET_SLEEP_MODE_NONE;
}

bool beet_is_valid_pair_index(uint8_t pair_index)
{
    return pair_index >= 1U && pair_index <= BEET_PAIR_COUNT;
}

bool beet_is_valid_pair_state(beet_pair_state_t state)
{
    return state >= BEET_PAIR_STATE_IDLE && state <= BEET_PAIR_STATE_DISABLED;
}

bool beet_is_valid_battery_state(beet_battery_state_t state)
{
    return state >= BEET_BATTERY_STATE_ACTIVE && state <= BEET_BATTERY_STATE_OTA_IN_PROGRESS;
}

bool beet_is_valid_run_source(beet_run_source_t source)
{
    return source >= BEET_RUN_SOURCE_NONE && source <= BEET_RUN_SOURCE_MANUAL;
}

bool beet_is_valid_block_reason(beet_block_reason_t reason)
{
    return reason >= BEET_BLOCK_REASON_NONE && reason <= BEET_BLOCK_REASON_SENSOR_READING_INVALID;
}

bool beet_is_valid_stop_reason(beet_stop_reason_t reason)
{
    return reason >= BEET_STOP_REASON_COMPLETED && reason <= BEET_STOP_REASON_SYSTEM_ABORT;
}

bool beet_is_valid_sleep_mode(beet_sleep_mode_t mode)
{
    return mode >= BEET_SLEEP_MODE_NONE && mode <= BEET_SLEEP_MODE_DEEP_LOW_BATTERY;
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
    if (!beet_is_valid_pair_index(record->pair_index)) {
        return false;
    }
    if (!beet_is_valid_run_source((beet_run_source_t)record->trigger_source)) {
        return false;
    }
    if (!beet_is_valid_stop_reason((beet_stop_reason_t)record->stop_reason)) {
        return false;
    }
    if (!beet_is_valid_block_reason((beet_block_reason_t)record->block_reason)) {
        return false;
    }
    return beet_event_crc32(record) == record->crc32;
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
    case BEET_BLOCK_REASON_SENSOR_DELTA_TOO_SMALL:
        return "SENSOR_DELTA_TOO_SMALL";
    case BEET_BLOCK_REASON_SENSOR_READING_INVALID:
        return "SENSOR_READING_INVALID";
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
    default:
        return "UNKNOWN";
    }
}
