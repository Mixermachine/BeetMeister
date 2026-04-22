#include "beet_controller.h"

#include <inttypes.h>
#include <string.h>

#include "esp_check.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "sdkconfig.h"
#include "beet_board.h"
#include "beet_storage.h"
#include "beet_types.h"

static const char *TAG = "beet_controller";

typedef enum {
    BEET_RUN_PHASE_NONE = 0,
    BEET_RUN_PHASE_WAITING = 1,
    BEET_RUN_PHASE_SANITY_CHECK = 2,
    BEET_RUN_PHASE_WATERING = 3,
} beet_run_phase_t;

typedef struct {
    beet_run_phase_t phase;
    beet_run_source_t source;
    int64_t queue_entered_us;
    int64_t phase_started_us;
    uint16_t requested_duration_s;
    uint16_t remaining_duration_s;
    uint16_t delivered_duration_s;
    uint16_t battery_start_mv;
    uint8_t moisture_before_pct;
    uint16_t sensor_before_mv;
    uint32_t run_id;
} beet_pair_runtime_t;

typedef struct {
    bool valid;
    beet_board_sensor_sample_t sample;
} beet_sensor_diag_t;

typedef struct {
    beet_app_config_t config;
    beet_pair_calibration_t calibrations[BEET_PAIR_COUNT];
    beet_pair_runtime_snapshot_t snapshots[BEET_PAIR_COUNT];
    beet_pair_runtime_t runtimes[BEET_PAIR_COUNT];
    beet_sensor_diag_t sensor_diag[BEET_PAIR_COUNT];
    beet_event_ring_state_t event_ring;
    beet_battery_state_t battery_state;
    uint16_t battery_mv;
    beet_board_battery_sample_t battery_sample;
    bool battery_sample_valid;
    uint8_t battery_outlier_count;
    uint8_t active_pumps;
    uint32_t next_run_id;
    int64_t next_check_due_us;
    int64_t last_tick_us;
    int64_t last_status_log_us;
    int64_t last_bench_log_us;
    int64_t relay_test_phase_started_us;
    int64_t boot_time_us;
    uint8_t relay_test_pair;
    bool relay_test_active;
    bool snapshot_dirty[BEET_PAIR_COUNT];
    int64_t last_snapshot_flush_us[BEET_PAIR_COUNT];
} beet_controller_state_t;

static beet_controller_state_t s_state;
static TaskHandle_t s_controller_task;

static int64_t beet_now_us(void)
{
    return esp_timer_get_time();
}

static uint32_t beet_uptime_s(void)
{
    return (uint32_t)((beet_now_us() - s_state.boot_time_us) / 1000000LL);
}

static uint32_t beet_elapsed_s(int64_t since_us, int64_t now_us)
{
    if (since_us <= 0 || now_us <= since_us) {
        return 0U;
    }
    return (uint32_t)((now_us - since_us) / 1000000LL);
}

static uint32_t beet_abs_diff_u16(uint16_t a, uint16_t b)
{
    return (a > b) ? (uint32_t)(a - b) : (uint32_t)(b - a);
}

static bool beet_runtime_has_pump(const beet_pair_runtime_t *runtime)
{
    return runtime->phase == BEET_RUN_PHASE_SANITY_CHECK || runtime->phase == BEET_RUN_PHASE_WATERING;
}

static uint8_t beet_count_active_pumps(void)
{
    uint8_t active = 0;
    for (size_t i = 0; i < BEET_PAIR_COUNT; ++i) {
        if (beet_runtime_has_pump(&s_state.runtimes[i])) {
            ++active;
        }
    }
    return active;
}

static void beet_mark_snapshot_dirty(uint8_t pair_index)
{
    s_state.snapshot_dirty[pair_index - 1U] = true;
}

static uint16_t beet_runtime_elapsed_s(const beet_pair_runtime_t *runtime, int64_t now_us)
{
    uint16_t elapsed = runtime->delivered_duration_s;

    if (runtime->phase == BEET_RUN_PHASE_SANITY_CHECK) {
        uint32_t phase_elapsed = beet_elapsed_s(runtime->phase_started_us, now_us);
        if (phase_elapsed > BEET_SANITY_CHECK_DURATION_S) {
            phase_elapsed = BEET_SANITY_CHECK_DURATION_S;
        }
        elapsed = (uint16_t)phase_elapsed;
    } else if (runtime->phase == BEET_RUN_PHASE_WATERING) {
        uint32_t phase_elapsed = beet_elapsed_s(runtime->phase_started_us, now_us);
        if (phase_elapsed > runtime->remaining_duration_s) {
            phase_elapsed = runtime->remaining_duration_s;
        }
        elapsed = (uint16_t)(runtime->delivered_duration_s + phase_elapsed);
    }

    return elapsed;
}

static void beet_sync_snapshot_runtime(uint8_t pair_index, int64_t now_us)
{
    beet_pair_runtime_snapshot_t *snapshot = &s_state.snapshots[pair_index - 1U];
    const beet_pair_runtime_t *runtime = &s_state.runtimes[pair_index - 1U];

    if (runtime->phase == BEET_RUN_PHASE_SANITY_CHECK || runtime->phase == BEET_RUN_PHASE_WATERING) {
        snapshot->active_run_id = runtime->run_id;
        snapshot->active_run_source = runtime->source;
        snapshot->run_elapsed_s = beet_runtime_elapsed_s(runtime, now_us);
        snapshot->run_target_s = runtime->requested_duration_s;
    } else {
        snapshot->active_run_id = 0U;
        snapshot->active_run_source = BEET_RUN_SOURCE_NONE;
        snapshot->run_elapsed_s = 0U;
        snapshot->run_target_s = 0U;
    }
}

static void beet_update_next_check_fields(void)
{
    int64_t now_us = beet_now_us();
    uint32_t due_in_s = (s_state.next_check_due_us > now_us) ?
        (uint32_t)((s_state.next_check_due_us - now_us) / 1000000LL) :
        0U;

    for (uint8_t pair = 1U; pair <= BEET_PAIR_COUNT; ++pair) {
        beet_pair_runtime_snapshot_t *snapshot = &s_state.snapshots[pair - 1U];
        snapshot->next_check_due_unix_s = 0U;
        snapshot->next_check_due_in_s = due_in_s;
        beet_mark_snapshot_dirty(pair);
    }
}

static esp_err_t beet_flush_snapshot(uint8_t pair_index, bool force)
{
    int64_t now_us = beet_now_us();

    ESP_RETURN_ON_FALSE(beet_is_valid_pair_index(pair_index), ESP_ERR_INVALID_ARG, TAG, "invalid pair");
    if (!s_state.snapshot_dirty[pair_index - 1U]) {
        return ESP_OK;
    }
    if (!force &&
        s_state.last_snapshot_flush_us[pair_index - 1U] != 0 &&
        beet_elapsed_s(s_state.last_snapshot_flush_us[pair_index - 1U], now_us) < BEET_SNAPSHOT_COALESCE_S) {
        return ESP_OK;
    }

    beet_sync_snapshot_runtime(pair_index, now_us);
    ESP_RETURN_ON_ERROR(
        beet_storage_save_snapshot(&s_state.snapshots[pair_index - 1U]),
        TAG,
        "snapshot save failed");

    s_state.last_snapshot_flush_us[pair_index - 1U] = now_us;
    s_state.snapshot_dirty[pair_index - 1U] = false;
    return ESP_OK;
}

static void beet_flush_dirty_snapshots(bool force)
{
    for (uint8_t pair = 1U; pair <= BEET_PAIR_COUNT; ++pair) {
        if (beet_flush_snapshot(pair, force) != ESP_OK) {
            ESP_LOGW(TAG, "snapshot flush failed for pair %u", pair);
        }
    }
}

static void beet_clear_runtime(uint8_t pair_index)
{
    memset(&s_state.runtimes[pair_index - 1U], 0, sizeof(s_state.runtimes[pair_index - 1U]));
}

static void beet_controller_set_indicator(void)
{
    bool any_fault = false;
    for (size_t i = 0; i < BEET_PAIR_COUNT; ++i) {
        if (s_state.snapshots[i].pair_state == BEET_PAIR_STATE_FAULT) {
            any_fault = true;
            break;
        }
    }

    if (any_fault) {
        beet_board_set_indicator(BEET_BOARD_INDICATOR_FAULT);
        return;
    }

    switch (s_state.battery_state) {
    case BEET_BATTERY_STATE_ACTIVE:
        beet_board_set_indicator(BEET_BOARD_INDICATOR_ACTIVE);
        break;
    case BEET_BATTERY_STATE_IDLE_LOW_POWER:
        beet_board_set_indicator(BEET_BOARD_INDICATOR_IDLE_LOW);
        break;
    case BEET_BATTERY_STATE_DEEP_LOW_BATTERY:
        beet_board_set_indicator(BEET_BOARD_INDICATOR_DEEP_LOW);
        break;
    case BEET_BATTERY_STATE_OTA_IN_PROGRESS:
        beet_board_set_indicator(BEET_BOARD_INDICATOR_BOOTING);
        break;
    default:
        beet_board_set_indicator(BEET_BOARD_INDICATOR_FAULT);
        break;
    }
}

static bool beet_watering_allowed(void)
{
    return s_state.battery_mv >= s_state.config.watering_abort_threshold_mv &&
        s_state.battery_state != BEET_BATTERY_STATE_DEEP_LOW_BATTERY &&
        s_state.battery_state != BEET_BATTERY_STATE_OTA_IN_PROGRESS;
}

static void beet_refresh_battery(void)
{
    beet_board_battery_sample_t sample;

    if (beet_board_read_battery_sample(&sample) != ESP_OK) {
        ESP_LOGW(TAG, "battery read failed");
        return;
    }

    s_state.battery_sample = sample;
    s_state.battery_sample_valid = true;

    if (s_state.battery_mv == 0U) {
        s_state.battery_mv = sample.scaled_mv;
        s_state.battery_outlier_count = 0U;
    } else if (beet_abs_diff_u16(sample.scaled_mv, s_state.battery_mv) > BEET_BATTERY_SPIKE_REJECT_MV) {
        if (s_state.battery_outlier_count + 1U >= BEET_BATTERY_OUTLIER_ACCEPT_COUNT) {
            ESP_LOGW(
                TAG,
                "battery spike accepted after %u outliers: raw=%d sensed=%umV divider=%umV scaled=%umV previous=%umV",
                BEET_BATTERY_OUTLIER_ACCEPT_COUNT,
                sample.raw_avg,
                sample.sensed_mv,
                sample.divider_mv,
                sample.scaled_mv,
                s_state.battery_mv);
            s_state.battery_mv = sample.scaled_mv;
            s_state.battery_outlier_count = 0U;
        } else {
            s_state.battery_outlier_count++;
            ESP_LOGW(
                TAG,
                "battery spike rejected: raw=%d sensed=%umV divider=%umV scaled=%umV filtered=%umV streak=%u",
                sample.raw_avg,
                sample.sensed_mv,
                sample.divider_mv,
                sample.scaled_mv,
                s_state.battery_mv,
                s_state.battery_outlier_count);
        }
    } else {
        s_state.battery_mv = (uint16_t)(
            (((uint32_t)s_state.battery_mv * (BEET_BATTERY_FILTER_WINDOW - 1U)) + sample.scaled_mv) /
            BEET_BATTERY_FILTER_WINDOW);
        s_state.battery_outlier_count = 0U;
    }

    s_state.battery_state = beet_classify_battery_state(
        s_state.battery_mv,
        false,
        s_state.active_pumps > 0U,
        beet_uptime_s());
}

static void beet_log_event(
    uint8_t pair_index,
    const beet_pair_runtime_t *runtime,
    beet_stop_reason_t stop_reason,
    beet_block_reason_t block_reason)
{
    beet_event_record_t record;
    const beet_pair_runtime_snapshot_t *snapshot = &s_state.snapshots[pair_index - 1U];

    memset(&record, 0, sizeof(record));
    record.pair_index = pair_index;
    record.trigger_source = (uint8_t)runtime->source;
    record.time_valid = 0U;
    record.moisture_before_pct = runtime->moisture_before_pct;
    record.moisture_after_pct = snapshot->last_moisture_pct;
    record.sensor_before_mv = runtime->sensor_before_mv;
    record.sensor_after_mv = snapshot->last_sensor_mv;
    record.requested_duration_s = runtime->requested_duration_s;
    record.actual_duration_s = runtime->delivered_duration_s;
    record.stop_reason = (uint8_t)stop_reason;
    record.block_reason = (uint8_t)block_reason;
    record.battery_start_mv = runtime->battery_start_mv;
    record.battery_end_mv = s_state.battery_mv;

    if (beet_storage_append_event(&s_state.event_ring, &record) != ESP_OK) {
        ESP_LOGW(TAG, "event append failed for pair %u", pair_index);
        return;
    }

    ESP_LOGI(
        TAG,
        "event seq=%" PRIu64 " pair=%u stop=%s block=%s actual=%us requested=%us",
        record.seq_no,
        pair_index,
        beet_stop_reason_name((beet_stop_reason_t)record.stop_reason),
        beet_block_reason_name((beet_block_reason_t)record.block_reason),
        record.actual_duration_s,
        record.requested_duration_s);
}

static void beet_finish_pair_state(
    uint8_t pair_index,
    beet_pair_state_t final_state,
    beet_stop_reason_t stop_reason,
    beet_block_reason_t block_reason)
{
    beet_pair_runtime_snapshot_t *snapshot = &s_state.snapshots[pair_index - 1U];
    beet_pair_runtime_t runtime = s_state.runtimes[pair_index - 1U];

    if (beet_runtime_has_pump(&runtime)) {
        beet_board_set_relay(pair_index, false);
    }

    if (runtime.run_id != 0U && runtime.source != BEET_RUN_SOURCE_NONE && runtime.requested_duration_s > 0U) {
        if (runtime.phase == BEET_RUN_PHASE_SANITY_CHECK) {
            runtime.delivered_duration_s = beet_runtime_elapsed_s(&runtime, beet_now_us());
        } else if (runtime.phase == BEET_RUN_PHASE_WATERING) {
            runtime.delivered_duration_s = beet_runtime_elapsed_s(&runtime, beet_now_us());
        }
        beet_log_event(pair_index, &runtime, stop_reason, block_reason);
    }

    snapshot->pair_state = final_state;
    snapshot->block_reason = block_reason;
    snapshot->active_run_id = 0U;
    snapshot->active_run_source = BEET_RUN_SOURCE_NONE;
    snapshot->run_elapsed_s = 0U;
    snapshot->run_target_s = 0U;

    if (final_state == BEET_PAIR_STATE_BLOCKED) {
        snapshot->block_remaining_s = BEET_BLOCK_DURATION_S;
    } else {
        snapshot->block_remaining_s = 0U;
    }

    beet_clear_runtime(pair_index);
    beet_mark_snapshot_dirty(pair_index);
    beet_flush_snapshot(pair_index, true);
    s_state.active_pumps = beet_count_active_pumps();
}

static void beet_fault_pair(uint8_t pair_index, beet_block_reason_t reason)
{
    beet_pair_runtime_snapshot_t *snapshot = &s_state.snapshots[pair_index - 1U];

    if (beet_runtime_has_pump(&s_state.runtimes[pair_index - 1U])) {
        beet_finish_pair_state(pair_index, BEET_PAIR_STATE_FAULT, BEET_STOP_REASON_SYSTEM_ABORT, reason);
        return;
    }

    snapshot->pair_state = BEET_PAIR_STATE_FAULT;
    snapshot->sensor_valid = false;
    snapshot->block_reason = reason;
    beet_clear_runtime(pair_index);
    beet_mark_snapshot_dirty(pair_index);
    beet_flush_snapshot(pair_index, true);
}

static void beet_start_sanity_check(uint8_t pair_index, int64_t now_us)
{
    beet_pair_runtime_snapshot_t *snapshot = &s_state.snapshots[pair_index - 1U];
    beet_pair_runtime_t *runtime = &s_state.runtimes[pair_index - 1U];

    runtime->phase = BEET_RUN_PHASE_SANITY_CHECK;
    runtime->phase_started_us = now_us;
    runtime->queue_entered_us = 0;
    runtime->run_id = s_state.next_run_id++;
    if (runtime->run_id == 0U) {
        runtime->run_id = s_state.next_run_id++;
    }

    snapshot->pair_state = BEET_PAIR_STATE_SANITY_CHECK;
    snapshot->block_reason = BEET_BLOCK_REASON_NONE;
    snapshot->run_started_unix_s = 0U;
    beet_mark_snapshot_dirty(pair_index);

#if CONFIG_BEET_ENABLE_PUMP_OUTPUTS
    beet_board_set_relay(pair_index, true);
#endif

    beet_flush_snapshot(pair_index, true);
    s_state.active_pumps = beet_count_active_pumps();
    ESP_LOGI(TAG, "pair %u entered SANITY_CHECK for %us", pair_index, BEET_SANITY_CHECK_DURATION_S);
}

static void beet_start_watering_phase(uint8_t pair_index, int64_t now_us)
{
    beet_pair_runtime_snapshot_t *snapshot = &s_state.snapshots[pair_index - 1U];
    beet_pair_runtime_t *runtime = &s_state.runtimes[pair_index - 1U];

    runtime->phase = BEET_RUN_PHASE_WATERING;
    runtime->phase_started_us = now_us;
    runtime->delivered_duration_s = (uint16_t)(runtime->requested_duration_s - runtime->remaining_duration_s);
    snapshot->pair_state = BEET_PAIR_STATE_WATERING;
    beet_mark_snapshot_dirty(pair_index);

#if CONFIG_BEET_ENABLE_PUMP_OUTPUTS
    beet_board_set_relay(pair_index, true);
#endif

    beet_flush_snapshot(pair_index, true);
    s_state.active_pumps = beet_count_active_pumps();
    ESP_LOGI(TAG, "pair %u entered WATERING with %us remaining", pair_index, runtime->remaining_duration_s);
}

 #if CONFIG_BEET_ENABLE_PUMP_OUTPUTS
static void beet_queue_automatic_pair(uint8_t pair_index, uint16_t duration_s, int64_t now_us)
{
    beet_pair_runtime_snapshot_t *snapshot = &s_state.snapshots[pair_index - 1U];
    beet_pair_runtime_t *runtime = &s_state.runtimes[pair_index - 1U];

    memset(runtime, 0, sizeof(*runtime));
    runtime->phase = BEET_RUN_PHASE_WAITING;
    runtime->source = BEET_RUN_SOURCE_AUTOMATIC;
    runtime->queue_entered_us = now_us;
    runtime->requested_duration_s = duration_s;
    runtime->remaining_duration_s = (duration_s > BEET_SANITY_CHECK_DURATION_S) ?
        (uint16_t)(duration_s - BEET_SANITY_CHECK_DURATION_S) :
        0U;
    runtime->battery_start_mv = s_state.battery_mv;
    runtime->moisture_before_pct = snapshot->last_moisture_pct;
    runtime->sensor_before_mv = snapshot->last_sensor_mv;

    snapshot->pair_state = BEET_PAIR_STATE_WAITING_FOR_SLOT;
    snapshot->block_reason = BEET_BLOCK_REASON_NONE;
    beet_mark_snapshot_dirty(pair_index);
    beet_flush_snapshot(pair_index, true);

    ESP_LOGI(TAG, "pair %u queued for automatic watering (%us requested)", pair_index, duration_s);
}
#endif

static bool beet_pair_can_run_automatic(uint8_t pair_index)
{
    const beet_pair_runtime_snapshot_t *snapshot = &s_state.snapshots[pair_index - 1U];
    const beet_pair_runtime_t *runtime = &s_state.runtimes[pair_index - 1U];

    return runtime->phase == BEET_RUN_PHASE_NONE &&
        snapshot->pair_state == BEET_PAIR_STATE_IDLE &&
        snapshot->sensor_valid;
}

static void beet_service_waiting_pairs(int64_t now_us)
{
    while (s_state.active_pumps < BEET_MAX_ACTIVE_PUMPS) {
        uint8_t selected_pair = 0U;
        int64_t oldest_queue_us = 0;

        for (uint8_t pair = 1U; pair <= BEET_PAIR_COUNT; ++pair) {
            const beet_pair_runtime_t *runtime = &s_state.runtimes[pair - 1U];
            if (runtime->phase != BEET_RUN_PHASE_WAITING) {
                continue;
            }
            if (selected_pair == 0U || runtime->queue_entered_us < oldest_queue_us) {
                selected_pair = pair;
                oldest_queue_us = runtime->queue_entered_us;
            }
        }

        if (selected_pair == 0U) {
            return;
        }
        if (!beet_watering_allowed()) {
            ESP_LOGW(TAG, "pair %u queue released without start because battery policy forbids watering", selected_pair);
            beet_finish_pair_state(selected_pair, BEET_PAIR_STATE_IDLE, BEET_STOP_REASON_SYSTEM_ABORT, BEET_BLOCK_REASON_NONE);
            continue;
        }

        beet_start_sanity_check(selected_pair, now_us);
    }
}

static void beet_refresh_sensors(void)
{
    for (uint8_t pair = 1U; pair <= BEET_PAIR_COUNT; ++pair) {
        beet_pair_runtime_snapshot_t *snapshot = &s_state.snapshots[pair - 1U];
        beet_board_sensor_sample_t sample;
        bool read_ok = (beet_board_read_moisture_sample(pair, &sample) == ESP_OK);

        s_state.sensor_diag[pair - 1U].valid = read_ok;
        if (read_ok) {
            s_state.sensor_diag[pair - 1U].sample = sample;
        } else {
            memset(&s_state.sensor_diag[pair - 1U].sample, 0, sizeof(s_state.sensor_diag[pair - 1U].sample));
        }

        if (!read_ok || !beet_is_sensor_mv_plausible(sample.voltage_mv)) {
            if (snapshot->sensor_valid) {
                snapshot->sensor_valid = false;
                beet_mark_snapshot_dirty(pair);
            }

            if (s_state.runtimes[pair - 1U].phase == BEET_RUN_PHASE_NONE ||
                s_state.runtimes[pair - 1U].phase == BEET_RUN_PHASE_WAITING) {
                if (snapshot->pair_state != BEET_PAIR_STATE_BLOCKED) {
                    beet_fault_pair(pair, BEET_BLOCK_REASON_SENSOR_READING_INVALID);
                }
            }
            continue;
        }

        uint8_t moisture_pct = beet_moisture_pct_from_mv(
            s_state.calibrations[pair - 1U].dry_mv,
            s_state.calibrations[pair - 1U].wet_mv,
            sample.voltage_mv);

        if (!snapshot->sensor_valid ||
            snapshot->last_sensor_mv != sample.voltage_mv ||
            snapshot->last_moisture_pct != moisture_pct) {
            snapshot->last_sensor_mv = sample.voltage_mv;
            snapshot->last_moisture_pct = moisture_pct;
            snapshot->sensor_valid = true;
            beet_mark_snapshot_dirty(pair);
        }

        if (snapshot->pair_state == BEET_PAIR_STATE_FAULT &&
            snapshot->block_reason == BEET_BLOCK_REASON_SENSOR_READING_INVALID &&
            s_state.runtimes[pair - 1U].phase == BEET_RUN_PHASE_NONE) {
            snapshot->pair_state = BEET_PAIR_STATE_IDLE;
            snapshot->block_reason = BEET_BLOCK_REASON_NONE;
            beet_mark_snapshot_dirty(pair);
            ESP_LOGI(TAG, "pair %u recovered from sensor fault after valid reading", pair);
        }
    }
}

static void beet_restore_snapshots(void)
{
    for (uint8_t pair = 1U; pair <= BEET_PAIR_COUNT; ++pair) {
        beet_pair_runtime_snapshot_t *snapshot = &s_state.snapshots[pair - 1U];

        if (snapshot->active_run_id != 0U ||
            snapshot->pair_state == BEET_PAIR_STATE_WAITING_FOR_SLOT ||
            snapshot->pair_state == BEET_PAIR_STATE_SANITY_CHECK ||
            snapshot->pair_state == BEET_PAIR_STATE_WATERING) {
            snapshot->pair_state = BEET_PAIR_STATE_FAULT;
            snapshot->active_run_id = 0U;
            snapshot->active_run_source = BEET_RUN_SOURCE_NONE;
            snapshot->run_elapsed_s = 0U;
            snapshot->run_target_s = 0U;
            beet_mark_snapshot_dirty(pair);
            continue;
        }

        if (snapshot->block_reason != BEET_BLOCK_REASON_NONE && snapshot->block_remaining_s > 0U) {
            snapshot->pair_state = BEET_PAIR_STATE_BLOCKED;
            beet_mark_snapshot_dirty(pair);
            continue;
        }

        if (snapshot->pair_state != BEET_PAIR_STATE_FAULT) {
            snapshot->pair_state = BEET_PAIR_STATE_IDLE;
            snapshot->block_reason = BEET_BLOCK_REASON_NONE;
            snapshot->block_remaining_s = 0U;
            beet_mark_snapshot_dirty(pair);
        }
    }

    beet_flush_dirty_snapshots(true);
}

static void beet_log_status(void)
{
    int64_t due_in_s = (s_state.next_check_due_us - beet_now_us()) / 1000000LL;

    ESP_LOGI(
        TAG,
        "battery=%umV state=%s event_seq=%" PRIu64 " pumps=%u scheduler_due_in=%" PRIi64 "s",
        s_state.battery_mv,
        beet_battery_state_name(s_state.battery_state),
        s_state.event_ring.highest_valid_seq_no,
        s_state.active_pumps,
        due_in_s);

    for (uint8_t pair = 1U; pair <= BEET_PAIR_COUNT; ++pair) {
        const beet_pair_runtime_snapshot_t *snapshot = &s_state.snapshots[pair - 1U];
        const beet_pair_runtime_t *runtime = &s_state.runtimes[pair - 1U];
        ESP_LOGI(
            TAG,
            "pair=%u relay_gpio=%d moisture_gpio=%d state=%s moisture=%u%% sensor=%umV valid=%d block=%s run=%u elapsed=%us target=%us",
            pair,
            beet_board_relay_gpio(pair),
            beet_board_moisture_gpio(pair),
            beet_pair_state_name(snapshot->pair_state),
            snapshot->last_moisture_pct,
            snapshot->last_sensor_mv,
            snapshot->sensor_valid,
            beet_block_reason_name(snapshot->block_reason),
            runtime->run_id,
            beet_runtime_elapsed_s(runtime, beet_now_us()),
            runtime->requested_duration_s);
    }
}

#if CONFIG_BEET_ENABLE_BENCH_DIAGNOSTICS
static void beet_log_bench_diagnostics(void)
{
    ESP_LOGI(
        TAG,
        "bench battery raw=%d sensed_mv=%u divider_mv=%u scaled_mv=%u filtered_mv=%u battery_state=%s active_pumps=%u",
        s_state.battery_sample_valid ? s_state.battery_sample.raw_avg : -1,
        s_state.battery_sample_valid ? s_state.battery_sample.sensed_mv : 0U,
        s_state.battery_sample_valid ? s_state.battery_sample.divider_mv : 0U,
        s_state.battery_sample_valid ? s_state.battery_sample.scaled_mv : 0U,
        s_state.battery_mv,
        beet_battery_state_name(s_state.battery_state),
        s_state.active_pumps);

    for (uint8_t pair = 1U; pair <= BEET_PAIR_COUNT; ++pair) {
        const beet_pair_runtime_snapshot_t *snapshot = &s_state.snapshots[pair - 1U];
        const beet_sensor_diag_t *diag = &s_state.sensor_diag[pair - 1U];

        ESP_LOGI(
            TAG,
            "bench pair=%u relay_gpio=%d moisture_gpio=%d raw=%d mv=%u pct=%u sample_ok=%d sensor_valid=%d state=%s block=%s",
            pair,
            beet_board_relay_gpio(pair),
            beet_board_moisture_gpio(pair),
            diag->valid ? diag->sample.raw_avg : -1,
            diag->valid ? diag->sample.voltage_mv : 0U,
            snapshot->last_moisture_pct,
            diag->valid,
            snapshot->sensor_valid,
            beet_pair_state_name(snapshot->pair_state),
            beet_block_reason_name(snapshot->block_reason));
    }
}
#endif

#if CONFIG_BEET_ENABLE_RELAY_SELF_TEST
static bool beet_has_active_runtime(void)
{
    for (uint8_t pair = 1U; pair <= BEET_PAIR_COUNT; ++pair) {
        if (s_state.runtimes[pair - 1U].phase != BEET_RUN_PHASE_NONE) {
            return true;
        }
    }
    return false;
}

static void beet_service_relay_self_test(int64_t now_us)
{
    if (beet_has_active_runtime()) {
        if (s_state.relay_test_active) {
            beet_board_all_relays_off();
            s_state.relay_test_active = false;
        }
        return;
    }

    if (!s_state.relay_test_active) {
        s_state.relay_test_pair = 1U;
        s_state.relay_test_active = true;
        s_state.relay_test_phase_started_us = now_us;
        beet_board_all_relays_off();
        beet_board_set_relay(s_state.relay_test_pair, true);
        ESP_LOGW(
            TAG,
            "relay_self_test pair=%u gpio=%d enabled=1",
            s_state.relay_test_pair,
            beet_board_relay_gpio(s_state.relay_test_pair));
        return;
    }

    if ((now_us - s_state.relay_test_phase_started_us) < ((int64_t)CONFIG_BEET_RELAY_SELF_TEST_DWELL_MS * 1000LL)) {
        return;
    }

    beet_board_all_relays_off();
    ESP_LOGW(
        TAG,
        "relay_self_test pair=%u gpio=%d enabled=0",
        s_state.relay_test_pair,
        beet_board_relay_gpio(s_state.relay_test_pair));

    s_state.relay_test_pair++;
    if (s_state.relay_test_pair > BEET_PAIR_COUNT) {
        s_state.relay_test_pair = 1U;
    }

    s_state.relay_test_phase_started_us = now_us;
    beet_board_set_relay(s_state.relay_test_pair, true);
    ESP_LOGW(
        TAG,
        "relay_self_test pair=%u gpio=%d enabled=1",
        s_state.relay_test_pair,
        beet_board_relay_gpio(s_state.relay_test_pair));
}
#endif

static void beet_run_scheduler_cycle(int64_t now_us)
{
    ESP_LOGI(TAG, "running scheduler cycle");

    for (uint8_t pair = 1U; pair <= BEET_PAIR_COUNT; ++pair) {
        beet_pair_runtime_snapshot_t *snapshot = &s_state.snapshots[pair - 1U];

        if (!beet_pair_can_run_automatic(pair)) {
            continue;
        }

        uint16_t duration_s = beet_automatic_duration_s(snapshot->last_moisture_pct);
        if (duration_s == 0U) {
            snapshot->pair_state = BEET_PAIR_STATE_IDLE;
            beet_mark_snapshot_dirty(pair);
            continue;
        }

#if CONFIG_BEET_ENABLE_PUMP_OUTPUTS
        if (!beet_watering_allowed()) {
            ESP_LOGW(TAG, "pair %u skipped automatic start because battery policy forbids watering", pair);
            continue;
        }
        beet_queue_automatic_pair(pair, duration_s, now_us);
        beet_service_waiting_pairs(now_us);
#else
        ESP_LOGW(
            TAG,
            "pair %u needs watering for %us at %u%%, but live pump outputs are disabled in Kconfig",
            pair,
            duration_s,
            snapshot->last_moisture_pct);
#endif
    }

    s_state.next_check_due_us = now_us + ((int64_t)s_state.config.watering_interval_s * 1000000LL);
    beet_update_next_check_fields();
}

static void beet_tick_blocks(uint32_t elapsed_s)
{
    if (elapsed_s == 0U) {
        return;
    }

    for (uint8_t pair = 1U; pair <= BEET_PAIR_COUNT; ++pair) {
        beet_pair_runtime_snapshot_t *snapshot = &s_state.snapshots[pair - 1U];
        if (snapshot->pair_state != BEET_PAIR_STATE_BLOCKED || snapshot->block_remaining_s == 0U) {
            continue;
        }

        snapshot->block_remaining_s = (snapshot->block_remaining_s > elapsed_s) ?
            (snapshot->block_remaining_s - elapsed_s) :
            0U;
        beet_mark_snapshot_dirty(pair);

        if (snapshot->block_remaining_s == 0U) {
            snapshot->pair_state = BEET_PAIR_STATE_IDLE;
            snapshot->block_reason = BEET_BLOCK_REASON_NONE;
            beet_mark_snapshot_dirty(pair);
            ESP_LOGI(TAG, "pair %u block expired", pair);
        }
    }
}

static void beet_progress_runs(int64_t now_us)
{
    for (uint8_t pair = 1U; pair <= BEET_PAIR_COUNT; ++pair) {
        beet_pair_runtime_snapshot_t *snapshot = &s_state.snapshots[pair - 1U];
        beet_pair_runtime_t *runtime = &s_state.runtimes[pair - 1U];

        if (runtime->phase == BEET_RUN_PHASE_NONE || runtime->phase == BEET_RUN_PHASE_WAITING) {
            continue;
        }
        if (s_state.battery_mv < s_state.config.watering_abort_threshold_mv) {
            beet_finish_pair_state(pair, BEET_PAIR_STATE_FAULT, BEET_STOP_REASON_LOW_BATTERY_ABORT, BEET_BLOCK_REASON_NONE);
            continue;
        }
        if (!snapshot->sensor_valid) {
            beet_finish_pair_state(
                pair,
                BEET_PAIR_STATE_BLOCKED,
                BEET_STOP_REASON_SENSOR_INVALID_ABORT,
                BEET_BLOCK_REASON_SENSOR_READING_INVALID);
            continue;
        }

        if (runtime->phase == BEET_RUN_PHASE_SANITY_CHECK) {
            uint32_t phase_elapsed = beet_elapsed_s(runtime->phase_started_us, now_us);
            if (phase_elapsed < BEET_SANITY_CHECK_DURATION_S) {
                beet_mark_snapshot_dirty(pair);
                continue;
            }

#if CONFIG_BEET_ENABLE_PUMP_OUTPUTS
            beet_board_set_relay(pair, false);
#endif
            runtime->delivered_duration_s = BEET_SANITY_CHECK_DURATION_S;

            if (!beet_sanity_check_passed(runtime->moisture_before_pct, snapshot->last_moisture_pct)) {
                beet_finish_pair_state(
                    pair,
                    BEET_PAIR_STATE_BLOCKED,
                    BEET_STOP_REASON_SENSOR_SANITY_FAILURE,
                    BEET_BLOCK_REASON_SENSOR_DELTA_TOO_SMALL);
                continue;
            }

            if (runtime->remaining_duration_s == 0U) {
                beet_finish_pair_state(pair, BEET_PAIR_STATE_IDLE, BEET_STOP_REASON_COMPLETED, BEET_BLOCK_REASON_NONE);
                continue;
            }

            beet_start_watering_phase(pair, now_us);
            continue;
        }

        if (runtime->phase == BEET_RUN_PHASE_WATERING) {
            uint32_t phase_elapsed = beet_elapsed_s(runtime->phase_started_us, now_us);
            if (phase_elapsed < runtime->remaining_duration_s) {
                beet_mark_snapshot_dirty(pair);
                continue;
            }

            runtime->delivered_duration_s = runtime->requested_duration_s;
            beet_finish_pair_state(pair, BEET_PAIR_STATE_IDLE, BEET_STOP_REASON_COMPLETED, BEET_BLOCK_REASON_NONE);
        }
    }

    s_state.active_pumps = beet_count_active_pumps();
}

static void beet_controller_task(void *arg)
{
    (void)arg;

    while (1) {
        int64_t now_us = beet_now_us();
        uint32_t elapsed_s = beet_elapsed_s(s_state.last_tick_us, now_us);
        if (elapsed_s > 0U) {
            s_state.last_tick_us = now_us;
        }

        beet_refresh_battery();
        beet_refresh_sensors();
        beet_tick_blocks(elapsed_s);
        beet_progress_runs(now_us);
        beet_service_waiting_pairs(now_us);
#if CONFIG_BEET_ENABLE_RELAY_SELF_TEST
        beet_service_relay_self_test(now_us);
#endif
        beet_controller_set_indicator();

        if (now_us >= s_state.next_check_due_us &&
            s_state.battery_state != BEET_BATTERY_STATE_DEEP_LOW_BATTERY &&
            s_state.battery_state != BEET_BATTERY_STATE_OTA_IN_PROGRESS) {
            beet_run_scheduler_cycle(now_us);
        }

        beet_flush_dirty_snapshots(false);

        if (s_state.last_status_log_us == 0 ||
            (now_us - s_state.last_status_log_us) >= ((int64_t)CONFIG_BEET_STATUS_LOG_PERIOD_S * 1000000LL)) {
            beet_log_status();
            s_state.last_status_log_us = now_us;
        }

#if CONFIG_BEET_ENABLE_BENCH_DIAGNOSTICS
        if (s_state.last_bench_log_us == 0 ||
            (now_us - s_state.last_bench_log_us) >= ((int64_t)CONFIG_BEET_BENCH_DIAGNOSTIC_PERIOD_S * 1000000LL)) {
            beet_log_bench_diagnostics();
            s_state.last_bench_log_us = now_us;
        }
#endif

        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}

esp_err_t beet_controller_init(void)
{
    memset(&s_state, 0, sizeof(s_state));
    s_state.boot_time_us = beet_now_us();
    s_state.last_tick_us = s_state.boot_time_us;
    s_state.next_run_id = 1U;

    ESP_RETURN_ON_ERROR(beet_storage_init(), TAG, "storage init failed");
    ESP_RETURN_ON_ERROR(
        beet_storage_load_or_init(&s_state.config, s_state.calibrations, s_state.snapshots),
        TAG,
        "storage load failed");
    ESP_RETURN_ON_ERROR(beet_storage_scan_event_ring(&s_state.event_ring), TAG, "event scan failed");
    ESP_RETURN_ON_ERROR(beet_board_init(), TAG, "board init failed");

    beet_board_all_relays_off();
    beet_restore_snapshots();
    beet_refresh_battery();
    beet_refresh_sensors();
    s_state.active_pumps = beet_count_active_pumps();
    s_state.next_check_due_us = beet_now_us();
    beet_update_next_check_fields();
    beet_controller_set_indicator();
    beet_flush_dirty_snapshots(true);

    if (s_controller_task == NULL) {
        BaseType_t task_ok = xTaskCreate(
            beet_controller_task,
            "beet_ctrl",
            8192,
            NULL,
            5,
            &s_controller_task);
        ESP_RETURN_ON_FALSE(task_ok == pdPASS, ESP_ERR_NO_MEM, TAG, "controller task create failed");
    }

    return ESP_OK;
}
