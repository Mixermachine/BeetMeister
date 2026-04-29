#include "beet_controller.h"

#include <inttypes.h>
#include <stdio.h>
#include <string.h>

#include "esp_check.h"
#include "esp_log.h"
#include "esp_sleep.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "sdkconfig.h"
#include "beet_board.h"
#include "beet_ble.h"
#include "beet_iface.h"
#include "beet_storage.h"
#include "beet_types.h"

static const char *TAG = "beet_controller";

#define BEET_DISPLAY_BLE_ACTIVITY_WINDOW_US (1500LL * 1000LL)
#define BEET_DISPLAY_BLE_WAVE_PERIOD_US (500LL * 1000LL)

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
    uint16_t corrected_mv;
} beet_sensor_diag_t;

typedef struct {
    beet_app_config_t config;
    beet_pair_calibration_t calibrations[BEET_PAIR_COUNT];
    beet_pair_runtime_snapshot_t snapshots[BEET_PAIR_COUNT];
    beet_pair_runtime_t runtimes[BEET_PAIR_COUNT];
    beet_sensor_diag_t sensor_diag[BEET_PAIR_COUNT];
    beet_event_ring_state_t event_ring;
    beet_power_runtime_state_t power_state;
    beet_battery_state_t battery_state;
    uint16_t battery_mv;
    uint32_t boot_id;
    uint32_t boot_epoch_unix_s;
    bool time_valid;
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
    int64_t last_activity_us;
    uint8_t relay_test_pair;
    bool relay_test_active;
    bool wake_led_pulse_pending;
    bool snapshot_dirty[BEET_PAIR_COUNT];
    int64_t last_snapshot_flush_us[BEET_PAIR_COUNT];
    beet_event_ring_state_t system_event_ring;
} beet_controller_state_t;

static beet_controller_state_t s_state;
static TaskHandle_t s_controller_task;

static esp_err_t beet_controller_save_power_state(void);
static void beet_controller_enter_idle_light_sleep(void);
static void beet_controller_enter_deep_low_battery_sleep(void);
static void beet_queue_run(uint8_t pair_index, beet_run_source_t source, uint16_t duration_s, int64_t now_us);
static bool beet_pair_can_start_manual(uint8_t pair_index, beet_iface_reason_t *reason);
static void beet_finish_pair_state(
    uint8_t pair_index,
    beet_pair_state_t final_state,
    beet_stop_reason_t stop_reason,
    beet_block_reason_t block_reason);
static void beet_log_sleep_event(beet_sleep_mode_t mode);
static void beet_log_system_event(
    beet_system_event_type_t type,
    uint16_t reason,
    const uint8_t peer_addr[6],
    uint8_t peer_addr_type,
    bool known_peer,
    uint32_t detail);
static void beet_on_ble_system_event(const beet_ble_system_event_t *event);
static void beet_disable_pair(uint8_t pair_index);
static void beet_enable_pair(uint8_t pair_index);
static void beet_log_ble_diag_status(const char *reason);
static bool beet_ble_should_be_enabled(void);
static void beet_controller_sync_boost_output(void);
static void beet_stop_relay_test(bool log_stop);
static esp_err_t beet_start_relay_test(uint8_t pair_index);
static uint32_t beet_current_uptime_s(void);
static uint32_t beet_resolve_unix_s(uint32_t uptime_s);
static esp_err_t beet_apply_time_update(uint32_t unix_s);

static int64_t beet_now_us(void)
{
    return esp_timer_get_time();
}

static uint32_t beet_wakeup_causes(void)
{
    return esp_sleep_get_wakeup_causes();
}

static bool beet_wakeup_has(uint32_t wake_causes, esp_sleep_source_t source)
{
    return (wake_causes & (1UL << source)) != 0U;
}

static uint32_t beet_elapsed_s(int64_t since_us, int64_t now_us)
{
    if (since_us <= 0 || now_us <= since_us) {
        return 0U;
    }
    return (uint32_t)((now_us - since_us) / 1000000LL);
}

static uint32_t beet_current_uptime_s(void)
{
    return beet_elapsed_s(s_state.boot_time_us, beet_now_us());
}

static uint32_t beet_resolve_unix_s(uint32_t uptime_s)
{
    return s_state.time_valid ? (s_state.boot_epoch_unix_s + uptime_s) : 0U;
}

static uint32_t beet_abs_diff_u16(uint16_t a, uint16_t b)
{
    return (a > b) ? (uint32_t)(a - b) : (uint32_t)(b - a);
}

static bool beet_runtime_has_pump(const beet_pair_runtime_t *runtime)
{
    return runtime->phase == BEET_RUN_PHASE_SANITY_CHECK || runtime->phase == BEET_RUN_PHASE_WATERING;
}

static bool beet_has_any_runtime(void)
{
    for (size_t i = 0; i < BEET_PAIR_COUNT; ++i) {
        if (s_state.runtimes[i].phase != BEET_RUN_PHASE_NONE) {
            return true;
        }
    }
    return false;
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

static void beet_mark_activity(int64_t now_us)
{
    s_state.last_activity_us = now_us;
}

static void beet_controller_sync_boost_output(void)
{
#if CONFIG_BEET_ENABLE_PUMP_OUTPUTS
    const bool should_enable = s_state.active_pumps > 0U;
#else
    const bool should_enable = false;
#endif

    if (beet_board_set_boost_enabled(should_enable) != ESP_OK) {
        ESP_LOGW(TAG, "boost output sync failed");
    }
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

static void beet_stop_relay_test(bool log_stop)
{
    if (!s_state.relay_test_active) {
        return;
    }

    beet_board_set_relay(s_state.relay_test_pair, false);
    s_state.relay_test_active = false;
    s_state.relay_test_pair = 0U;
    s_state.relay_test_phase_started_us = 0LL;
    beet_controller_sync_boost_output();

    if (log_stop) {
        ESP_LOGI(TAG, "relay test stopped");
    }
}

static esp_err_t beet_start_relay_test(uint8_t pair_index)
{
    ESP_RETURN_ON_FALSE(beet_is_valid_pair_index(pair_index), ESP_ERR_INVALID_ARG, TAG, "invalid relay test pair");

    if (s_state.relay_test_active && s_state.relay_test_pair != pair_index) {
        beet_stop_relay_test(false);
    }

    beet_board_all_relays_off();
    ESP_RETURN_ON_ERROR(beet_board_set_boost_enabled(false), TAG, "boost disable failed for relay test");
    ESP_RETURN_ON_ERROR(beet_board_set_relay(pair_index, true), TAG, "relay test start failed");

    s_state.relay_test_active = true;
    s_state.relay_test_pair = pair_index;
    s_state.relay_test_phase_started_us = beet_now_us();
    ESP_LOGI(TAG, "relay test started pair=%u gpio=%d boost=0", pair_index, beet_board_relay_gpio(pair_index));
    return ESP_OK;
}

static void beet_disable_pair(uint8_t pair_index)
{
    beet_pair_runtime_snapshot_t *snapshot = &s_state.snapshots[pair_index - 1U];
    const bool was_waiting = s_state.runtimes[pair_index - 1U].phase == BEET_RUN_PHASE_WAITING;

    if (s_state.relay_test_active && s_state.relay_test_pair == pair_index) {
        beet_stop_relay_test(false);
    }

    if (was_waiting) {
        beet_clear_runtime(pair_index);
    } else if (s_state.runtimes[pair_index - 1U].phase != BEET_RUN_PHASE_NONE) {
        beet_finish_pair_state(pair_index, BEET_PAIR_STATE_IDLE, BEET_STOP_REASON_SYSTEM_ABORT, BEET_BLOCK_REASON_NONE);
    }

    snapshot = &s_state.snapshots[pair_index - 1U];
    snapshot->enabled = false;
    snapshot->pair_state = BEET_PAIR_STATE_DISABLED;
    snapshot->block_reason = BEET_BLOCK_REASON_NONE;
    snapshot->block_until_unix_s = 0U;
    snapshot->block_remaining_s = 0U;
    snapshot->active_run_id = 0U;
    snapshot->active_run_source = BEET_RUN_SOURCE_NONE;
    snapshot->run_started_unix_s = 0U;
    snapshot->run_elapsed_s = 0U;
    snapshot->run_target_s = 0U;
    beet_mark_snapshot_dirty(pair_index);
    beet_flush_snapshot(pair_index, true);
    s_state.active_pumps = beet_count_active_pumps();
}

static void beet_enable_pair(uint8_t pair_index)
{
    beet_pair_runtime_snapshot_t *snapshot = &s_state.snapshots[pair_index - 1U];

    snapshot->enabled = true;
    snapshot->pair_state = BEET_PAIR_STATE_IDLE;
    snapshot->block_reason = BEET_BLOCK_REASON_NONE;
    snapshot->block_until_unix_s = 0U;
    snapshot->block_remaining_s = 0U;
    snapshot->active_run_id = 0U;
    snapshot->active_run_source = BEET_RUN_SOURCE_NONE;
    snapshot->run_started_unix_s = 0U;
    snapshot->run_elapsed_s = 0U;
    snapshot->run_target_s = 0U;
    beet_mark_snapshot_dirty(pair_index);
    beet_flush_snapshot(pair_index, true);
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

static void beet_controller_turn_indicator_off(void)
{
    beet_board_set_indicator((beet_board_indicator_t)-1);
}

static void beet_controller_stop_radios(void)
{
    ESP_LOGI(TAG, "requesting BLE disable for sleep preparation");
    beet_ble_set_enabled(false);
    beet_log_ble_diag_status("radios_stopped");
}

static void beet_controller_restore_radios(void)
{
    if (beet_ble_should_be_enabled()) {
        ESP_LOGI(
            TAG,
            "restoring BLE battery_state=%s battery_mv=%u force_enable=%d",
            beet_battery_state_name(s_state.battery_state),
            s_state.battery_mv,
            BEET_BLE_FORCE_ENABLE_DIAGNOSTICS);
        beet_ble_set_enabled(true);
        beet_log_ble_diag_status("radios_restored");
        return;
    }

    ESP_LOGI(
        TAG,
        "keeping BLE disabled battery_state=%s battery_mv=%u force_enable=%d",
        beet_battery_state_name(s_state.battery_state),
        s_state.battery_mv,
        BEET_BLE_FORCE_ENABLE_DIAGNOSTICS);
}

static void beet_controller_set_display_power(void)
{
    beet_ble_pairing_display_t pairing_display;
    bool display_enabled = s_state.battery_state != BEET_BATTERY_STATE_IDLE_LOW_POWER &&
        s_state.battery_state != BEET_BATTERY_STATE_DEEP_LOW_BATTERY;

    beet_ble_get_pairing_display(&pairing_display);
    if (pairing_display.active) {
        display_enabled = true;
    }

    beet_board_set_display_enabled(display_enabled);
}

static void beet_controller_pulse_indicator(void)
{
    if (!s_state.wake_led_pulse_pending) {
        return;
    }

    beet_controller_set_indicator();
    vTaskDelay(pdMS_TO_TICKS(BEET_WAKE_INDICATOR_PULSE_MS));
    beet_controller_turn_indicator_off();
    s_state.wake_led_pulse_pending = false;
}

static esp_err_t beet_controller_save_power_state(void)
{
    return beet_storage_save_power_state(&s_state.power_state);
}

static uint64_t beet_light_sleep_interval_us(int64_t now_us)
{
    int64_t due_us = s_state.next_check_due_us - now_us;
    if (due_us <= 0) {
        return 0U;
    }
    return (uint64_t)due_us;
}

static const char *beet_sleep_mode_name(beet_sleep_mode_t mode)
{
    switch (mode) {
    case BEET_SLEEP_MODE_LIGHT_IDLE:
        return "LIGHT_IDLE";
    case BEET_SLEEP_MODE_DEEP_LOW_BATTERY:
        return "DEEP_LOW_BATTERY";
    case BEET_SLEEP_MODE_NONE:
    default:
        return "NONE";
    }
}

static esp_err_t beet_controller_prepare_for_sleep(beet_sleep_mode_t mode)
{
    beet_stop_relay_test(false);
    beet_board_all_relays_off();
    beet_board_set_boost_enabled(false);
    beet_board_set_sensor_power_enabled(false);
    beet_flush_dirty_snapshots(true);
    s_state.power_state.last_sleep_mode = mode;
    ESP_RETURN_ON_ERROR(beet_controller_save_power_state(), TAG, "power state save failed");
    beet_controller_stop_radios();
    beet_board_set_display_enabled(false);
    beet_controller_turn_indicator_off();
    return ESP_OK;
}

static void beet_controller_restore_after_light_sleep(bool button_wake)
{
    int64_t now_us = beet_now_us();
    if (button_wake) {
        beet_mark_activity(now_us);
    }
    s_state.last_tick_us = now_us;
    s_state.power_state.last_sleep_mode = BEET_SLEEP_MODE_NONE;
    beet_controller_save_power_state();
    beet_controller_sync_boost_output();
    beet_controller_restore_radios();
    beet_board_set_display_enabled(true);
    s_state.wake_led_pulse_pending = true;
}

static void beet_controller_enter_idle_light_sleep(void)
{
    int64_t now_us = beet_now_us();
    uint64_t sleep_us = beet_light_sleep_interval_us(now_us);
    esp_err_t err;

    if (sleep_us == 0U || s_state.active_pumps > 0U) {
        return;
    }

    ESP_LOGI(TAG, "entering light sleep for %" PRIu64 " ms", sleep_us / 1000U);
    if (beet_controller_prepare_for_sleep(BEET_SLEEP_MODE_LIGHT_IDLE) != ESP_OK) {
        return;
    }

    esp_sleep_disable_wakeup_source(ESP_SLEEP_WAKEUP_ALL);
    err = esp_sleep_enable_timer_wakeup(sleep_us);
    if (err != ESP_OK) {
        ESP_LOGW(TAG, "light sleep timer wake setup failed: %s", esp_err_to_name(err));
        beet_controller_restore_after_light_sleep(false);
        return;
    }

    err = esp_sleep_enable_ext1_wakeup_io(1ULL << GPIO_NUM_13, ESP_EXT1_WAKEUP_ANY_LOW);
    if (err != ESP_OK) {
        ESP_LOGW(TAG, "light sleep button wake setup failed: %s", esp_err_to_name(err));
        esp_sleep_disable_wakeup_source(ESP_SLEEP_WAKEUP_ALL);
        beet_controller_restore_after_light_sleep(false);
        return;
    }

    beet_log_sleep_event(BEET_SLEEP_MODE_LIGHT_IDLE);
    err = esp_light_sleep_start();
    if (err != ESP_OK) {
        ESP_LOGW(TAG, "light sleep rejected: %s", esp_err_to_name(err));
    }

    esp_sleep_disable_wakeup_source(ESP_SLEEP_WAKEUP_ALL);
    beet_controller_restore_after_light_sleep(esp_sleep_get_ext1_wakeup_status() != 0U);
}

static void beet_controller_enter_deep_low_battery_sleep(void)
{
    uint32_t interval_s = beet_deep_low_recovery_interval_s(s_state.power_state.deep_low_recovery_failures);
    esp_err_t err;

    ESP_LOGI(
        TAG,
        "entering deep sleep for %us (deep_low_failures=%u)",
        interval_s,
        s_state.power_state.deep_low_recovery_failures);

    if (beet_controller_prepare_for_sleep(BEET_SLEEP_MODE_DEEP_LOW_BATTERY) != ESP_OK) {
        return;
    }

    esp_sleep_disable_wakeup_source(ESP_SLEEP_WAKEUP_ALL);
    err = esp_sleep_enable_timer_wakeup((uint64_t)interval_s * 1000000ULL);
    if (err != ESP_OK) {
        ESP_LOGW(TAG, "deep sleep timer wake setup failed: %s", esp_err_to_name(err));
        return;
    }

    beet_log_sleep_event(BEET_SLEEP_MODE_DEEP_LOW_BATTERY);
    esp_deep_sleep_start();
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
    bool had_battery_sample = s_state.battery_sample_valid;
    beet_battery_state_t previous_state = s_state.battery_state;
    uint16_t previous_mv = s_state.battery_mv;

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
        beet_elapsed_s(s_state.last_activity_us, beet_now_us()));

    if (!had_battery_sample || s_state.battery_state != previous_state) {
        ESP_LOGI(
            TAG,
            "battery classification previous=%s/%umV current=%s/%umV active_pumps=%u ble_should_enable=%d",
            beet_battery_state_name(previous_state),
            previous_mv,
            beet_battery_state_name(s_state.battery_state),
            s_state.battery_mv,
            s_state.active_pumps,
            beet_ble_should_be_enabled());
    }
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
    record.boot_id = s_state.boot_id;
    record.pair_index = pair_index;
    record.trigger_source = (uint8_t)runtime->source;
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
    record.started_uptime_s = beet_elapsed_s(s_state.boot_time_us, runtime->phase_started_us);
    record.ended_uptime_s = beet_elapsed_s(s_state.boot_time_us, beet_now_us());
    record.time_valid = s_state.time_valid ? 1U : 0U;
    record.started_at_unix_s = beet_resolve_unix_s(record.started_uptime_s);
    record.ended_at_unix_s = beet_resolve_unix_s(record.ended_uptime_s);

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

static void beet_log_sleep_event(beet_sleep_mode_t mode)
{
    const char *mode_name = beet_sleep_mode_name(mode);

    beet_log_system_event(
        BEET_SYSTEM_EVENT_SLEEP,
        (mode == BEET_SLEEP_MODE_LIGHT_IDLE) ?
            (uint16_t)BEET_STOP_REASON_IDLE_LOW_POWER_SLEEP :
            (uint16_t)BEET_STOP_REASON_DEEP_LOW_BATTERY_SLEEP,
        NULL,
        0U,
        false,
        0U);

    ESP_LOGI(
        TAG,
        "sleep event mode=%s battery=%umV",
        mode_name,
        s_state.battery_mv);
}

static void beet_log_system_event(
    beet_system_event_type_t type,
    uint16_t reason,
    const uint8_t peer_addr[6],
    uint8_t peer_addr_type,
    bool known_peer,
    uint32_t detail)
{
    beet_system_event_record_t record;

    memset(&record, 0, sizeof(record));
    record.boot_id = s_state.boot_id;
    record.event_type = (uint8_t)type;
    record.reason = reason;
    record.occurred_uptime_s = beet_current_uptime_s();
    record.occurred_unix_s = beet_resolve_unix_s(record.occurred_uptime_s);
    record.time_valid = s_state.time_valid ? 1U : 0U;
    record.battery_mv = s_state.battery_mv;
    if (peer_addr != NULL) {
        memcpy(record.peer_addr, peer_addr, sizeof(record.peer_addr));
        record.peer_addr_type = peer_addr_type;
    }
    record.known_peer = known_peer ? 1U : 0U;
    record.detail = detail;

    if (beet_storage_append_system_event(&s_state.system_event_ring, &record) != ESP_OK) {
        ESP_LOGW(TAG, "system event append failed type=%s reason=%u", beet_system_event_type_name(type), reason);
        return;
    }

    beet_ble_publish_system_event(&record);
    ESP_LOGI(
        TAG,
        "system event seq=%" PRIu64 " type=%s reason=%u battery=%umV uptime=%lus",
        record.seq_no,
        beet_system_event_type_name(type),
        reason,
        record.battery_mv,
        (unsigned long)record.occurred_uptime_s);
}

static void beet_on_ble_system_event(const beet_ble_system_event_t *event)
{
    if (event == NULL) {
        return;
    }
    beet_log_system_event(
        event->type,
        event->reason,
        event->peer_addr,
        event->peer_addr_type,
        event->known_peer,
        event->detail);
}

static esp_err_t beet_apply_time_update(uint32_t unix_s)
{
    uint16_t updated_watering = 0U;
    uint16_t updated_system = 0U;
    uint32_t current_uptime_s = beet_current_uptime_s();

    ESP_RETURN_ON_FALSE(unix_s >= current_uptime_s, ESP_ERR_INVALID_ARG, TAG, "unix time predates current boot uptime");

    s_state.boot_epoch_unix_s = unix_s - current_uptime_s;
    s_state.time_valid = true;

    ESP_RETURN_ON_ERROR(
        beet_storage_backfill_event_times(s_state.boot_id, s_state.boot_epoch_unix_s, &updated_watering),
        TAG,
        "watering event backfill failed");
    ESP_RETURN_ON_ERROR(
        beet_storage_backfill_system_event_times(s_state.boot_id, s_state.boot_epoch_unix_s, &updated_system),
        TAG,
        "system event backfill failed");

    ESP_LOGI(
        TAG,
        "time updated boot=%lu epoch=%lu backfilled watering=%u system=%u",
        (unsigned long)s_state.boot_id,
        (unsigned long)s_state.boot_epoch_unix_s,
        updated_watering,
        updated_system);
    return ESP_OK;
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
    beet_controller_sync_boost_output();
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

    snapshot->pair_state = runtime->source == BEET_RUN_SOURCE_TEST ?
        BEET_PAIR_STATE_MOISTURE_TEST :
        BEET_PAIR_STATE_SANITY_CHECK;
    snapshot->block_reason = BEET_BLOCK_REASON_NONE;
    snapshot->run_started_unix_s = 0U;
    beet_mark_snapshot_dirty(pair_index);

#if CONFIG_BEET_ENABLE_PUMP_OUTPUTS
    beet_board_set_relay(pair_index, true);
#endif

    beet_flush_snapshot(pair_index, true);
    s_state.active_pumps = beet_count_active_pumps();
    beet_controller_sync_boost_output();
    ESP_LOGI(
        TAG,
        "pair %u entered %s for %us",
        pair_index,
        runtime->source == BEET_RUN_SOURCE_TEST ? "MOISTURE_TEST" : "SANITY_CHECK",
        BEET_SANITY_CHECK_DURATION_S);
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
    beet_controller_sync_boost_output();
    ESP_LOGI(TAG, "pair %u entered WATERING with %us remaining", pair_index, runtime->remaining_duration_s);
}

static void beet_queue_run(uint8_t pair_index, beet_run_source_t source, uint16_t duration_s, int64_t now_us)
{
    beet_pair_runtime_snapshot_t *snapshot = &s_state.snapshots[pair_index - 1U];
    beet_pair_runtime_t *runtime = &s_state.runtimes[pair_index - 1U];

    memset(runtime, 0, sizeof(*runtime));
    runtime->phase = BEET_RUN_PHASE_WAITING;
    runtime->source = source;
    runtime->queue_entered_us = now_us;
    runtime->requested_duration_s = duration_s;
    runtime->remaining_duration_s = (source == BEET_RUN_SOURCE_AUTOMATIC && duration_s > BEET_SANITY_CHECK_DURATION_S) ?
        (uint16_t)(duration_s - BEET_SANITY_CHECK_DURATION_S) :
        duration_s;
    runtime->battery_start_mv = s_state.battery_mv;
    runtime->moisture_before_pct = snapshot->last_moisture_pct;
    runtime->sensor_before_mv = snapshot->last_sensor_mv;

    snapshot->pair_state = BEET_PAIR_STATE_WAITING_FOR_SLOT;
    snapshot->block_reason = BEET_BLOCK_REASON_NONE;
    beet_mark_snapshot_dirty(pair_index);
    beet_flush_snapshot(pair_index, true);

    ESP_LOGI(
        TAG,
        "pair %u queued for %s (%us requested)",
        pair_index,
        source == BEET_RUN_SOURCE_MANUAL ? "manual watering" :
            (source == BEET_RUN_SOURCE_TEST ? "moisture test" : "automatic watering"),
        duration_s);
}

static bool beet_pair_can_run_automatic(uint8_t pair_index)
{
    const beet_pair_runtime_snapshot_t *snapshot = &s_state.snapshots[pair_index - 1U];
    const beet_pair_runtime_t *runtime = &s_state.runtimes[pair_index - 1U];

    return snapshot->enabled &&
        runtime->phase == BEET_RUN_PHASE_NONE &&
        snapshot->pair_state == BEET_PAIR_STATE_IDLE &&
        snapshot->sensor_valid;
}

static void beet_service_waiting_pairs(int64_t now_us)
{
    for (uint8_t pair = 1U; pair <= BEET_PAIR_COUNT; ++pair) {
        const beet_pair_runtime_t *runtime = &s_state.runtimes[pair - 1U];
        if (runtime->phase != BEET_RUN_PHASE_WAITING || runtime->source != BEET_RUN_SOURCE_MANUAL) {
            continue;
        }
        if (beet_elapsed_s(runtime->queue_entered_us, now_us) >= BEET_MANUAL_QUEUE_TIMEOUT_S) {
            ESP_LOGW(TAG, "manual queue timeout for pair %u after %us", pair, BEET_MANUAL_QUEUE_TIMEOUT_S);
            beet_finish_pair_state(pair, BEET_PAIR_STATE_IDLE, BEET_STOP_REASON_SYSTEM_ABORT, BEET_BLOCK_REASON_NONE);
        }
    }

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

        if (s_state.runtimes[selected_pair - 1U].source == BEET_RUN_SOURCE_MANUAL) {
            beet_start_watering_phase(selected_pair, now_us);
        } else {
            beet_start_sanity_check(selected_pair, now_us);
        }
    }
}

static void beet_refresh_sensors(void)
{
    if (beet_board_set_sensor_power_enabled(true) != ESP_OK) {
        ESP_LOGW(TAG, "sensor power enable failed");
        return;
    }

    vTaskDelay(pdMS_TO_TICKS(CONFIG_BEET_SENSOR_POWER_SETTLE_MS));

    for (uint8_t pair = 1U; pair <= BEET_PAIR_COUNT; ++pair) {
        beet_pair_runtime_snapshot_t *snapshot = &s_state.snapshots[pair - 1U];
        const beet_pair_calibration_t *calibration = &s_state.calibrations[pair - 1U];
        beet_board_sensor_sample_t sample;
        bool read_ok = (beet_board_read_moisture_sample(pair, &sample) == ESP_OK);
        uint16_t corrected_mv = 0U;

        s_state.sensor_diag[pair - 1U].valid = read_ok;
        if (read_ok) {
            s_state.sensor_diag[pair - 1U].sample = sample;
            corrected_mv = beet_correct_moisture_sensor_mv(sample.voltage_mv, s_state.battery_mv);
            s_state.sensor_diag[pair - 1U].corrected_mv = corrected_mv;
        } else {
            memset(&s_state.sensor_diag[pair - 1U].sample, 0, sizeof(s_state.sensor_diag[pair - 1U].sample));
            s_state.sensor_diag[pair - 1U].corrected_mv = 0U;
        }

        if (!read_ok || !beet_is_sensor_mv_plausible(corrected_mv, calibration->dry_mv, calibration->wet_mv)) {
            if (snapshot->sensor_valid) {
                snapshot->sensor_valid = false;
                beet_mark_snapshot_dirty(pair);
            }

            if (!snapshot->enabled) {
                continue;
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
            calibration->dry_mv,
            calibration->wet_mv,
            corrected_mv);

        if (!snapshot->sensor_valid ||
            snapshot->last_sensor_mv != corrected_mv ||
            snapshot->last_moisture_pct != moisture_pct) {
            snapshot->last_sensor_mv = corrected_mv;
            snapshot->last_moisture_pct = moisture_pct;
            snapshot->sensor_valid = true;
            beet_mark_snapshot_dirty(pair);
        }

        if (snapshot->enabled &&
            snapshot->pair_state == BEET_PAIR_STATE_FAULT &&
            snapshot->block_reason == BEET_BLOCK_REASON_SENSOR_READING_INVALID &&
            s_state.runtimes[pair - 1U].phase == BEET_RUN_PHASE_NONE) {
            snapshot->pair_state = BEET_PAIR_STATE_IDLE;
            snapshot->block_reason = BEET_BLOCK_REASON_NONE;
            beet_mark_snapshot_dirty(pair);
            ESP_LOGI(TAG, "pair %u recovered from sensor fault after valid reading", pair);
        }
    }

    if (beet_board_set_sensor_power_enabled(false) != ESP_OK) {
        ESP_LOGW(TAG, "sensor power disable failed");
    }
}

static void beet_restore_snapshots(void)
{
    for (uint8_t pair = 1U; pair <= BEET_PAIR_COUNT; ++pair) {
        beet_pair_runtime_snapshot_t *snapshot = &s_state.snapshots[pair - 1U];

        // Older persisted snapshots predate the enabled flag. Those records load with enabled=false
        // but never used the DISABLED state, so treat them as enabled during migration.
        if (!snapshot->enabled && snapshot->pair_state != BEET_PAIR_STATE_DISABLED) {
            snapshot->enabled = true;
            beet_mark_snapshot_dirty(pair);
        }

        if (!snapshot->enabled) {
            snapshot->pair_state = BEET_PAIR_STATE_DISABLED;
            snapshot->block_reason = BEET_BLOCK_REASON_NONE;
            snapshot->block_until_unix_s = 0U;
            snapshot->block_remaining_s = 0U;
            snapshot->active_run_id = 0U;
            snapshot->active_run_source = BEET_RUN_SOURCE_NONE;
            snapshot->run_started_unix_s = 0U;
            snapshot->run_elapsed_s = 0U;
            snapshot->run_target_s = 0U;
            beet_mark_snapshot_dirty(pair);
            continue;
        }

        if (snapshot->active_run_id != 0U ||
            snapshot->pair_state == BEET_PAIR_STATE_WAITING_FOR_SLOT ||
            snapshot->pair_state == BEET_PAIR_STATE_SANITY_CHECK ||
            snapshot->pair_state == BEET_PAIR_STATE_MOISTURE_TEST ||
            snapshot->pair_state == BEET_PAIR_STATE_WATERING) {
            snapshot->pair_state = BEET_PAIR_STATE_IDLE;
            snapshot->active_run_id = 0U;
            snapshot->active_run_source = BEET_RUN_SOURCE_NONE;
            snapshot->run_elapsed_s = 0U;
            snapshot->run_target_s = 0U;
            snapshot->block_reason = BEET_BLOCK_REASON_NONE;
            snapshot->block_remaining_s = 0U;
            beet_mark_snapshot_dirty(pair);
            continue;
        }

        if (snapshot->pair_state == BEET_PAIR_STATE_FAULT &&
            snapshot->block_reason != BEET_BLOCK_REASON_SENSOR_READING_INVALID) {
            snapshot->pair_state = BEET_PAIR_STATE_IDLE;
            snapshot->block_reason = BEET_BLOCK_REASON_NONE;
            snapshot->block_remaining_s = 0U;
            beet_mark_snapshot_dirty(pair);
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

static void beet_controller_handle_boot_wakeup(void)
{
    uint32_t wake_causes = beet_wakeup_causes();
    bool timer_wake = beet_wakeup_has(wake_causes, ESP_SLEEP_WAKEUP_TIMER);

    if (timer_wake && s_state.power_state.last_sleep_mode == BEET_SLEEP_MODE_DEEP_LOW_BATTERY) {
        if (s_state.battery_mv <= s_state.config.deep_sleep_resume_mv &&
            s_state.power_state.deep_low_recovery_failures < UINT8_MAX) {
            s_state.power_state.deep_low_recovery_failures++;
        }
    } else if (s_state.battery_mv > s_state.config.deep_sleep_resume_mv) {
        s_state.power_state.deep_low_recovery_failures = 0U;
    }

    if (s_state.power_state.last_sleep_mode == BEET_SLEEP_MODE_DEEP_LOW_BATTERY &&
        s_state.battery_mv > s_state.config.deep_sleep_resume_mv) {
        s_state.power_state.last_sleep_mode = BEET_SLEEP_MODE_NONE;
        s_state.wake_led_pulse_pending = true;
        beet_mark_activity(beet_now_us());
        beet_controller_save_power_state();
        return;
    }

    if (s_state.power_state.last_sleep_mode == BEET_SLEEP_MODE_DEEP_LOW_BATTERY &&
        s_state.battery_mv <= s_state.config.deep_sleep_resume_mv &&
        s_state.active_pumps == 0U) {
        beet_controller_enter_deep_low_battery_sleep();
    }

    if (s_state.battery_state == BEET_BATTERY_STATE_DEEP_LOW_BATTERY && s_state.active_pumps == 0U) {
        beet_controller_enter_deep_low_battery_sleep();
    }

    s_state.power_state.last_sleep_mode = BEET_SLEEP_MODE_NONE;
    s_state.wake_led_pulse_pending = timer_wake;
    beet_controller_save_power_state();
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
    beet_log_ble_diag_status("periodic_status");

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

static bool beet_ble_should_be_enabled(void)
{
    if (BEET_BLE_FORCE_ENABLE_DIAGNOSTICS) {
        return true;
    }

    return s_state.battery_state != BEET_BATTERY_STATE_DEEP_LOW_BATTERY;
}

static void beet_log_ble_diag_status(const char *reason)
{
    beet_ble_diag_status_t ble_status;

    beet_ble_get_diag_status(&ble_status);
    ESP_LOGI(
        TAG,
        "ble diag %s initialized=%d host_synced=%d enabled=%d advertising=%d connected=%d bonded=%d own_addr_type=%u battery_state=%s battery_mv=%u force_enable=%d",
        reason,
        ble_status.initialized,
        ble_status.host_synced,
        ble_status.enabled,
        ble_status.advertising,
        ble_status.connected,
        ble_status.bonded,
        (unsigned)ble_status.own_addr_type,
        beet_battery_state_name(s_state.battery_state),
        s_state.battery_mv,
        BEET_BLE_FORCE_ENABLE_DIAGNOSTICS);
}

static const char *beet_display_battery_state_short(beet_battery_state_t state)
{
    switch (state) {
    case BEET_BATTERY_STATE_ACTIVE:
        return "ACT";
    case BEET_BATTERY_STATE_IDLE_LOW_POWER:
        return "LOW";
    case BEET_BATTERY_STATE_DEEP_LOW_BATTERY:
        return "DEEP";
    case BEET_BATTERY_STATE_OTA_IN_PROGRESS:
        return "OTA";
    default:
        return "UNK";
    }
}

static const char *beet_display_pair_state_short(beet_pair_state_t state)
{
    switch (state) {
    case BEET_PAIR_STATE_IDLE:
        return "IDLE";
    case BEET_PAIR_STATE_WAITING_FOR_SLOT:
        return "WAIT";
    case BEET_PAIR_STATE_SANITY_CHECK:
        return "SANI";
    case BEET_PAIR_STATE_WATERING:
        return "RUN";
    case BEET_PAIR_STATE_BLOCKED:
        return "BLKD";
    case BEET_PAIR_STATE_FAULT:
        return "FLT";
    case BEET_PAIR_STATE_DISABLED:
        return "OFF";
    case BEET_PAIR_STATE_MOISTURE_TEST:
        return "TEST";
    default:
        return "UNK";
    }
}

static void beet_update_display(void)
{
    beet_ble_diag_status_t ble_status;
    beet_ble_pairing_display_t pairing_display;
    beet_board_display_status_t display_status = { 0 };
    char lines[8][22];
    const char *line_ptrs[8];
    int64_t now_us = beet_now_us();
    int64_t due_us = s_state.next_check_due_us - now_us;
    uint32_t next_check_s = due_us > 0 ? (uint32_t)(due_us / 1000000LL) : 0U;
    unsigned int next_hours = (unsigned int)(next_check_s / 3600U);
    unsigned int next_minutes = (unsigned int)((next_check_s % 3600U) / 60U);
    unsigned int next_seconds = (unsigned int)(next_check_s % 60U);
    uint8_t battery_pct = beet_battery_pct_from_mv(s_state.battery_mv);
    if (next_hours > 99U) {
        next_hours = 99U;
    }
    for (size_t i = 0; i < 8; ++i) {
        memset(lines[i], 0, sizeof(lines[i]));
        line_ptrs[i] = lines[i];
    }

    beet_ble_get_pairing_display(&pairing_display);
    if (pairing_display.active) {
        beet_board_show_pairing_code(pairing_display.passkey, pairing_display.remaining_s);
        return;
    }

    beet_ble_get_diag_status(&ble_status);
    display_status.ble_connected = ble_status.connected;
    if (ble_status.connected && ble_status.last_activity_us > 0 &&
        (now_us - ble_status.last_activity_us) <= BEET_DISPLAY_BLE_ACTIVITY_WINDOW_US) {
        display_status.ble_wave_visible =
            ((now_us / BEET_DISPLAY_BLE_WAVE_PERIOD_US) % 2LL) == 0LL;
    }

    snprintf(lines[0], sizeof(lines[0]), "  BEETMEISTER");
    snprintf(
        lines[1],
        sizeof(lines[1]),
        "BAT %4uV %3u%% %s",
        s_state.battery_mv,
        battery_pct,
        beet_display_battery_state_short(s_state.battery_state));
    snprintf(
        lines[2],
        sizeof(lines[2]),
        "NEXT %02u:%02u:%02u",
        next_hours,
        next_minutes,
        next_seconds);

    snprintf(lines[3], sizeof(lines[3]), " ");

    for (uint8_t row = 0; row < 4U; ++row) {
        uint8_t left_pair = (uint8_t)(row + 1U);
        uint8_t right_pair = (uint8_t)(row + 5U);
        const beet_pair_runtime_snapshot_t *left = &s_state.snapshots[left_pair - 1U];
        const beet_pair_runtime_snapshot_t *right = &s_state.snapshots[right_pair - 1U];
        snprintf(
            lines[4U + row],
            sizeof(lines[4U + row]),
            "%u %3u %-4s\t%u %3u %-4s",
            left_pair,
            left->last_moisture_pct,
            beet_display_pair_state_short(left->pair_state),
            right_pair,
            right->last_moisture_pct,
            beet_display_pair_state_short(right->pair_state));
    }
    beet_board_update_display(line_ptrs, 8U, &display_status);
}

static bool beet_pair_can_start_manual(uint8_t pair_index, beet_iface_reason_t *reason)
{
    const beet_pair_runtime_snapshot_t *snapshot = &s_state.snapshots[pair_index - 1U];
    const beet_pair_runtime_t *runtime = &s_state.runtimes[pair_index - 1U];

    if (!snapshot->enabled) {
        *reason = BEET_IFACE_REASON_PAIR_DISABLED;
        return false;
    }
    if (runtime->phase != BEET_RUN_PHASE_NONE) {
        *reason = BEET_IFACE_REASON_ALREADY_ACTIVE;
        return false;
    }
    if (s_state.relay_test_active) {
        *reason = BEET_IFACE_REASON_ALREADY_ACTIVE;
        return false;
    }
    if (snapshot->pair_state == BEET_PAIR_STATE_BLOCKED) {
        *reason = BEET_IFACE_REASON_PAIR_BLOCKED;
        return false;
    }
    if (snapshot->pair_state == BEET_PAIR_STATE_FAULT) {
        *reason = snapshot->sensor_valid ? BEET_IFACE_REASON_PAIR_FAULTED : BEET_IFACE_REASON_SENSOR_INVALID;
        return false;
    }
    if (!snapshot->sensor_valid) {
        *reason = BEET_IFACE_REASON_SENSOR_INVALID;
        return false;
    }
    if (s_state.battery_state == BEET_BATTERY_STATE_OTA_IN_PROGRESS) {
        *reason = BEET_IFACE_REASON_OTA_IN_PROGRESS;
        return false;
    }
    if (!beet_watering_allowed()) {
        *reason = BEET_IFACE_REASON_LOW_BATTERY;
        return false;
    }
#if !CONFIG_BEET_ENABLE_PUMP_OUTPUTS
    *reason = BEET_IFACE_REASON_OUTPUTS_DISABLED;
    return false;
#endif

    *reason = BEET_IFACE_REASON_NONE;
    return true;
}

esp_err_t beet_iface_get_device_state(beet_iface_device_state_t *state)
{
    ESP_RETURN_ON_FALSE(state != NULL, ESP_ERR_INVALID_ARG, TAG, "state is null");

    memset(state, 0, sizeof(*state));
    memcpy(state->device_id, s_state.config.device_id, sizeof(state->device_id));
    state->battery_state = s_state.battery_state;
    state->battery_mv = s_state.battery_mv;
    state->time_valid = s_state.time_valid;
    state->boot_id = s_state.boot_id;
    state->next_check_in_s = (s_state.next_check_due_us > beet_now_us()) ?
        (uint32_t)((s_state.next_check_due_us - beet_now_us()) / 1000000LL) :
        0U;
    state->active_pumps = s_state.active_pumps;
    state->wifi_connected = false;
    state->mqtt_connected = false;
    state->uptime_s = beet_elapsed_s(s_state.boot_time_us, beet_now_us());
    return ESP_OK;
}

esp_err_t beet_iface_get_pair_state(uint8_t pair_index, beet_iface_pair_state_t *state)
{
    const beet_pair_runtime_snapshot_t *snapshot;
    const beet_pair_runtime_t *runtime;

    ESP_RETURN_ON_FALSE(beet_is_valid_pair_index(pair_index), ESP_ERR_INVALID_ARG, TAG, "invalid pair");
    ESP_RETURN_ON_FALSE(state != NULL, ESP_ERR_INVALID_ARG, TAG, "state is null");

    snapshot = &s_state.snapshots[pair_index - 1U];
    runtime = &s_state.runtimes[pair_index - 1U];

    memset(state, 0, sizeof(*state));
    state->pair_index = pair_index;
    state->pair_state = snapshot->pair_state;
    state->moisture_pct = snapshot->last_moisture_pct;
    state->sensor_mv = snapshot->last_sensor_mv;
    state->pump_active = beet_runtime_has_pump(runtime);
    state->remaining_s = state->pump_active ?
        (uint16_t)(runtime->requested_duration_s - beet_runtime_elapsed_s(runtime, beet_now_us())) :
        0U;
    state->blocked = snapshot->pair_state == BEET_PAIR_STATE_BLOCKED;
    state->block_reason = snapshot->block_reason;
    state->source = runtime->source;
    state->enabled = snapshot->enabled;
    state->sensor_valid = snapshot->sensor_valid;
    return ESP_OK;
}

esp_err_t beet_iface_get_all_pair_states(beet_iface_pair_state_t states[BEET_PAIR_COUNT])
{
    ESP_RETURN_ON_FALSE(states != NULL, ESP_ERR_INVALID_ARG, TAG, "states is null");
    for (uint8_t pair = 1U; pair <= BEET_PAIR_COUNT; ++pair) {
        ESP_RETURN_ON_ERROR(beet_iface_get_pair_state(pair, &states[pair - 1U]), TAG, "pair state read failed");
    }
    return ESP_OK;
}

uint64_t beet_iface_get_latest_event_seq_no(void)
{
    return s_state.event_ring.has_valid_records ? s_state.event_ring.highest_valid_seq_no : 0U;
}

uint64_t beet_iface_get_latest_system_event_seq_no(void)
{
    return s_state.system_event_ring.has_valid_records ? s_state.system_event_ring.highest_valid_seq_no : 0U;
}

esp_err_t beet_iface_get_event(uint64_t seq_no, beet_iface_event_t *event)
{
    ESP_RETURN_ON_FALSE(event != NULL, ESP_ERR_INVALID_ARG, TAG, "event is null");
    return beet_storage_read_event_by_seq_no(s_state.boot_id, seq_no, &event->record);
}

esp_err_t beet_iface_get_system_event(uint64_t seq_no, beet_iface_system_event_t *event)
{
    ESP_RETURN_ON_FALSE(event != NULL, ESP_ERR_INVALID_ARG, TAG, "system event is null");
    return beet_storage_read_system_event_by_seq_no(s_state.boot_id, seq_no, &event->record);
}

esp_err_t beet_iface_submit_command(
    const beet_iface_command_request_t *request,
    beet_iface_command_response_t *response)
{
    int64_t now_us = beet_now_us();

    ESP_RETURN_ON_FALSE(request != NULL, ESP_ERR_INVALID_ARG, TAG, "request is null");
    ESP_RETURN_ON_FALSE(response != NULL, ESP_ERR_INVALID_ARG, TAG, "response is null");

    memset(response, 0, sizeof(*response));
    response->command = request->command;
    response->pair_index = request->pair_index;
    response->status = BEET_IFACE_STATUS_REJECTED;
    response->reason = BEET_IFACE_REASON_UNSUPPORTED_COMMAND;

    switch (request->command) {
    case BEET_IFACE_COMMAND_MANUAL_START: {
        uint16_t duration_s;
        beet_iface_reason_t reject_reason = BEET_IFACE_REASON_NONE;

        if (!beet_is_valid_pair_index(request->pair_index)) {
            response->reason = BEET_IFACE_REASON_INVALID_PAIR;
            return ESP_OK;
        }
        if (request->has_duration_s) {
            if (!beet_is_valid_manual_duration_s(request->duration_s)) {
                response->reason = BEET_IFACE_REASON_INVALID_DURATION;
                return ESP_OK;
            }
            duration_s = request->duration_s;
        } else {
            duration_s = beet_manual_duration_s(s_state.snapshots[request->pair_index - 1U].last_moisture_pct);
        }
        if (!beet_pair_can_start_manual(request->pair_index, &reject_reason)) {
            response->reason = reject_reason;
            return ESP_OK;
        }

        beet_mark_activity(now_us);
        beet_queue_run(request->pair_index, BEET_RUN_SOURCE_MANUAL, duration_s, now_us);
        beet_service_waiting_pairs(now_us);

        response->status = BEET_IFACE_STATUS_ACCEPTED;
        response->accepted_duration_s = duration_s;
        response->reason = (s_state.runtimes[request->pair_index - 1U].phase == BEET_RUN_PHASE_WAITING) ?
            BEET_IFACE_REASON_QUEUED_WAITING_FOR_SLOT :
            BEET_IFACE_REASON_SLOT_ALLOCATED;
        return ESP_OK;
    }
    case BEET_IFACE_COMMAND_MANUAL_STOP:
        if (!beet_is_valid_pair_index(request->pair_index)) {
            response->reason = BEET_IFACE_REASON_INVALID_PAIR;
            return ESP_OK;
        }
        if (s_state.runtimes[request->pair_index - 1U].phase == BEET_RUN_PHASE_NONE) {
            response->status = BEET_IFACE_STATUS_ACCEPTED;
            response->reason = BEET_IFACE_REASON_ALREADY_STOPPED;
            return ESP_OK;
        }

        beet_mark_activity(now_us);
        beet_finish_pair_state(request->pair_index, BEET_PAIR_STATE_IDLE, BEET_STOP_REASON_MANUAL_STOP, BEET_BLOCK_REASON_NONE);
        response->status = BEET_IFACE_STATUS_ACCEPTED;
        response->reason = BEET_IFACE_REASON_STOPPED;
        return ESP_OK;

    case BEET_IFACE_COMMAND_RELAY_TEST_START:
        if (!beet_is_valid_pair_index(request->pair_index)) {
            response->reason = BEET_IFACE_REASON_INVALID_PAIR;
            return ESP_OK;
        }
        if (s_state.battery_state == BEET_BATTERY_STATE_OTA_IN_PROGRESS) {
            response->reason = BEET_IFACE_REASON_OTA_IN_PROGRESS;
            return ESP_OK;
        }
        if (s_state.battery_state != BEET_BATTERY_STATE_ACTIVE) {
            response->reason = BEET_IFACE_REASON_LOW_BATTERY;
            return ESP_OK;
        }
        if (beet_has_any_runtime()) {
            response->reason = BEET_IFACE_REASON_ALREADY_ACTIVE;
            return ESP_OK;
        }

        beet_mark_activity(now_us);
        ESP_RETURN_ON_ERROR(beet_start_relay_test(request->pair_index), TAG, "relay test start failed");
        response->status = BEET_IFACE_STATUS_ACCEPTED;
        response->reason = BEET_IFACE_REASON_RELAY_TEST_STARTED;
        return ESP_OK;

    case BEET_IFACE_COMMAND_RELAY_TEST_STOP:
        if (!beet_is_valid_pair_index(request->pair_index)) {
            response->reason = BEET_IFACE_REASON_INVALID_PAIR;
            return ESP_OK;
        }
        if (!s_state.relay_test_active || s_state.relay_test_pair != request->pair_index) {
            response->status = BEET_IFACE_STATUS_ACCEPTED;
            response->reason = BEET_IFACE_REASON_ALREADY_STOPPED;
            return ESP_OK;
        }

        beet_mark_activity(now_us);
        beet_stop_relay_test(true);
        response->status = BEET_IFACE_STATUS_ACCEPTED;
        response->reason = BEET_IFACE_REASON_RELAY_TEST_STOPPED;
        return ESP_OK;

    case BEET_IFACE_COMMAND_MOISTURE_TEST_START: {
        beet_iface_reason_t reject_reason = BEET_IFACE_REASON_NONE;

        if (!beet_is_valid_pair_index(request->pair_index)) {
            response->reason = BEET_IFACE_REASON_INVALID_PAIR;
            return ESP_OK;
        }
        if (!beet_pair_can_start_manual(request->pair_index, &reject_reason)) {
            response->reason = reject_reason;
            return ESP_OK;
        }
        if (beet_count_active_pumps() >= BEET_MAX_ACTIVE_PUMPS) {
            response->reason = BEET_IFACE_REASON_SLOT_UNAVAILABLE;
            return ESP_OK;
        }

        beet_mark_activity(now_us);
        beet_queue_run(request->pair_index, BEET_RUN_SOURCE_TEST, BEET_SANITY_CHECK_DURATION_S, now_us);
        beet_service_waiting_pairs(now_us);
        response->status = BEET_IFACE_STATUS_ACCEPTED;
        response->accepted_duration_s = BEET_SANITY_CHECK_DURATION_S;
        response->reason = BEET_IFACE_REASON_MOISTURE_TEST_STARTED;
        return ESP_OK;
    }

    case BEET_IFACE_COMMAND_RESET_BLOCK:
        if (!beet_is_valid_pair_index(request->pair_index)) {
            response->reason = BEET_IFACE_REASON_INVALID_PAIR;
            return ESP_OK;
        }
        if (s_state.snapshots[request->pair_index - 1U].pair_state != BEET_PAIR_STATE_BLOCKED &&
            s_state.snapshots[request->pair_index - 1U].pair_state != BEET_PAIR_STATE_FAULT) {
            response->status = BEET_IFACE_STATUS_ACCEPTED;
            response->reason = BEET_IFACE_REASON_NOT_BLOCKED;
            return ESP_OK;
        }

        beet_mark_activity(now_us);
        if (s_state.snapshots[request->pair_index - 1U].pair_state == BEET_PAIR_STATE_FAULT &&
            !s_state.snapshots[request->pair_index - 1U].sensor_valid) {
            response->status = BEET_IFACE_STATUS_REJECTED;
            response->reason = BEET_IFACE_REASON_SENSOR_INVALID;
            return ESP_OK;
        }

        s_state.snapshots[request->pair_index - 1U].block_reason =
            s_state.snapshots[request->pair_index - 1U].sensor_valid ?
            BEET_BLOCK_REASON_NONE :
            BEET_BLOCK_REASON_SENSOR_READING_INVALID;
        s_state.snapshots[request->pair_index - 1U].block_remaining_s = 0U;
        s_state.snapshots[request->pair_index - 1U].pair_state =
            s_state.snapshots[request->pair_index - 1U].sensor_valid ?
            BEET_PAIR_STATE_IDLE :
            BEET_PAIR_STATE_FAULT;
        beet_mark_snapshot_dirty(request->pair_index);
        beet_flush_snapshot(request->pair_index, true);
        response->status = BEET_IFACE_STATUS_ACCEPTED;
        response->reason = BEET_IFACE_REASON_BLOCK_RESET;
        return ESP_OK;

    case BEET_IFACE_COMMAND_STORE_CALIBRATION:
        if (!beet_is_valid_pair_index(request->pair_index)) {
            response->reason = BEET_IFACE_REASON_INVALID_PAIR;
            return ESP_OK;
        }
        if (request->dry_mv == 0U || request->wet_mv == 0U || request->dry_mv <= request->wet_mv) {
            response->reason = BEET_IFACE_REASON_INVALID_CALIBRATION;
            return ESP_OK;
        }

        beet_mark_activity(now_us);
        s_state.calibrations[request->pair_index - 1U].pair_index = request->pair_index;
        s_state.calibrations[request->pair_index - 1U].dry_mv = request->dry_mv;
        s_state.calibrations[request->pair_index - 1U].wet_mv = request->wet_mv;
        s_state.calibrations[request->pair_index - 1U].calibrated_at_unix_s = 0U;
        s_state.calibrations[request->pair_index - 1U].source = BEET_CALIBRATION_SOURCE_USER;
        ESP_RETURN_ON_ERROR(
            beet_storage_save_calibration(&s_state.calibrations[request->pair_index - 1U]),
            TAG,
            "calibration save failed");
        beet_refresh_sensors();
        beet_flush_dirty_snapshots(true);
        response->status = BEET_IFACE_STATUS_ACCEPTED;
        response->reason = BEET_IFACE_REASON_CALIBRATION_SAVED;
        return ESP_OK;

    case BEET_IFACE_COMMAND_CLEAR_BLE_BONDS:
        beet_mark_activity(now_us);
        beet_log_system_event(BEET_SYSTEM_EVENT_BLE_BONDS_CLEARED, 0U, NULL, 0U, false, 0U);
        response->status = BEET_IFACE_STATUS_ACCEPTED;
        response->reason = BEET_IFACE_REASON_NO_BONDS;
        return ESP_OK;

    case BEET_IFACE_COMMAND_GET_CALIBRATION:
        if (!beet_is_valid_pair_index(request->pair_index)) {
            response->reason = BEET_IFACE_REASON_INVALID_PAIR;
            return ESP_OK;
        }

        response->status = BEET_IFACE_STATUS_ACCEPTED;
        response->reason = BEET_IFACE_REASON_NONE;
        response->has_calibration = true;
        response->calibration = s_state.calibrations[request->pair_index - 1U];
        return ESP_OK;

    case BEET_IFACE_COMMAND_GET_HISTORY_SUMMARY:
    case BEET_IFACE_COMMAND_GET_WATERING_HISTORY_SUMMARY:
        response->status = BEET_IFACE_STATUS_ACCEPTED;
        response->reason = BEET_IFACE_REASON_NONE;
        response->has_history_summary = true;
        response->latest_event_seq_no = beet_iface_get_latest_event_seq_no();
        ESP_RETURN_ON_ERROR(
            beet_storage_summarize_events(s_state.boot_id, &response->event_count, response->pair_totals_s),
            TAG,
            "event summary failed");
        return ESP_OK;

    case BEET_IFACE_COMMAND_GET_EVENT:
    case BEET_IFACE_COMMAND_GET_WATERING_EVENT: {
        beet_iface_event_t event = { 0 };
        if (beet_iface_get_event(request->seq_no, &event) != ESP_OK) {
            response->reason = BEET_IFACE_REASON_EVENT_NOT_FOUND;
            return ESP_OK;
        }

        response->status = BEET_IFACE_STATUS_ACCEPTED;
        response->reason = BEET_IFACE_REASON_NONE;
        response->has_event = true;
        response->event = event.record;
        return ESP_OK;
    }

    case BEET_IFACE_COMMAND_GET_SYSTEM_HISTORY_SUMMARY:
        response->status = BEET_IFACE_STATUS_ACCEPTED;
        response->reason = BEET_IFACE_REASON_NONE;
        response->has_system_history_summary = true;
        response->latest_system_event_seq_no = beet_iface_get_latest_system_event_seq_no();
        ESP_RETURN_ON_ERROR(
            beet_storage_summarize_system_events(s_state.boot_id, &response->system_event_count),
            TAG,
            "system event summary failed");
        return ESP_OK;

    case BEET_IFACE_COMMAND_GET_SYSTEM_EVENT: {
        beet_iface_system_event_t event = { 0 };
        if (beet_iface_get_system_event(request->seq_no, &event) != ESP_OK) {
            response->reason = BEET_IFACE_REASON_EVENT_NOT_FOUND;
            return ESP_OK;
        }

        response->status = BEET_IFACE_STATUS_ACCEPTED;
        response->reason = BEET_IFACE_REASON_NONE;
        response->has_system_event = true;
        response->system_event = event.record;
        return ESP_OK;
    }

    case BEET_IFACE_COMMAND_SET_TIME:
        if (request->unix_s == 0U || request->unix_s > UINT32_MAX) {
            response->reason = BEET_IFACE_REASON_INVALID_TIME;
            return ESP_OK;
        }
        beet_mark_activity(now_us);
        if (beet_apply_time_update((uint32_t)request->unix_s) != ESP_OK) {
            response->reason = BEET_IFACE_REASON_INVALID_TIME;
            return ESP_OK;
        }
        response->status = BEET_IFACE_STATUS_ACCEPTED;
        response->reason = BEET_IFACE_REASON_TIME_UPDATED;
        return ESP_OK;

    case BEET_IFACE_COMMAND_DISABLE_PAIR:
        if (!beet_is_valid_pair_index(request->pair_index)) {
            response->reason = BEET_IFACE_REASON_INVALID_PAIR;
            return ESP_OK;
        }

        beet_mark_activity(now_us);
        beet_disable_pair(request->pair_index);
        response->status = BEET_IFACE_STATUS_ACCEPTED;
        response->reason = BEET_IFACE_REASON_PAIR_DISABLED;
        return ESP_OK;

    case BEET_IFACE_COMMAND_ENABLE_PAIR:
        if (!beet_is_valid_pair_index(request->pair_index)) {
            response->reason = BEET_IFACE_REASON_INVALID_PAIR;
            return ESP_OK;
        }

        beet_mark_activity(now_us);
        beet_enable_pair(request->pair_index);
        response->status = BEET_IFACE_STATUS_ACCEPTED;
        response->reason = BEET_IFACE_REASON_PAIR_ENABLED;
        return ESP_OK;

    case BEET_IFACE_COMMAND_START_OTA:
        response->reason = BEET_IFACE_REASON_UNSUPPORTED_COMMAND;
        return ESP_OK;

    default:
        response->reason = BEET_IFACE_REASON_UNSUPPORTED_COMMAND;
        return ESP_OK;
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
            "bench pair=%u relay_gpio=%d moisture_gpio=%d raw=%d mv=%u corrected_mv=%u pct=%u sample_ok=%d sensor_valid=%d state=%s block=%s",
            pair,
            beet_board_relay_gpio(pair),
            beet_board_moisture_gpio(pair),
            diag->valid ? diag->sample.raw_avg : -1,
            diag->valid ? diag->sample.voltage_mv : 0U,
            diag->valid ? diag->corrected_mv : 0U,
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
        beet_queue_run(pair, BEET_RUN_SOURCE_AUTOMATIC, duration_s, now_us);
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
            beet_finish_pair_state(
                pair,
                BEET_PAIR_STATE_FAULT,
                BEET_STOP_REASON_LOW_BATTERY_ABORT,
                BEET_BLOCK_REASON_LOW_BATTERY_ABORT);
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
                    BEET_BLOCK_REASON_MOISTURE_RESPONSE_TEST_FAILED);
                continue;
            }

            if (runtime->source == BEET_RUN_SOURCE_TEST) {
                beet_finish_pair_state(pair, BEET_PAIR_STATE_IDLE, BEET_STOP_REASON_COMPLETED, BEET_BLOCK_REASON_NONE);
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
    beet_controller_sync_boost_output();
}

static void beet_controller_task(void *arg)
{
    (void)arg;

    while (1) {
        beet_ble_diag_status_t ble_status;
        beet_ble_pairing_display_t pairing_display;
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
        beet_controller_set_display_power();
        beet_controller_pulse_indicator();
        beet_update_display();
        beet_ble_service();
        beet_ble_get_diag_status(&ble_status);
        beet_ble_get_pairing_display(&pairing_display);

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

        if (s_state.battery_state == BEET_BATTERY_STATE_DEEP_LOW_BATTERY &&
            s_state.active_pumps == 0U &&
            !s_state.relay_test_active) {
            beet_controller_enter_deep_low_battery_sleep();
        }

        if (s_state.battery_state == BEET_BATTERY_STATE_IDLE_LOW_POWER &&
            s_state.active_pumps == 0U &&
            !s_state.relay_test_active &&
            !ble_status.connected &&
            !pairing_display.active) {
            beet_controller_enter_idle_light_sleep();
            continue;
        }

        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}

esp_err_t beet_controller_init(void)
{
    memset(&s_state, 0, sizeof(s_state));
    s_state.boot_time_us = beet_now_us();
    s_state.last_tick_us = s_state.boot_time_us;
    s_state.last_activity_us = s_state.boot_time_us;
    s_state.next_run_id = 1U;

    ESP_RETURN_ON_ERROR(beet_storage_init(), TAG, "storage init failed");
    ESP_RETURN_ON_ERROR(
        beet_storage_load_or_init(&s_state.config, s_state.calibrations, s_state.snapshots, &s_state.power_state),
        TAG,
        "storage load failed");
    s_state.power_state.boot_counter += 1U;
    s_state.boot_id = s_state.power_state.boot_counter;
    s_state.time_valid = false;
    s_state.boot_epoch_unix_s = 0U;
    ESP_RETURN_ON_ERROR(beet_storage_save_power_state(&s_state.power_state), TAG, "boot counter save failed");
    ESP_RETURN_ON_ERROR(beet_storage_scan_event_ring(&s_state.event_ring), TAG, "event scan failed");
    ESP_RETURN_ON_ERROR(beet_storage_scan_system_event_ring(&s_state.system_event_ring), TAG, "system event scan failed");
    ESP_RETURN_ON_ERROR(beet_board_init(), TAG, "board init failed");

    beet_board_all_relays_off();
    beet_restore_snapshots();
    beet_refresh_battery();
    beet_refresh_sensors();
    s_state.active_pumps = beet_count_active_pumps();
    beet_controller_sync_boost_output();
    beet_controller_handle_boot_wakeup();
    s_state.next_check_due_us = beet_now_us();
    beet_update_next_check_fields();
    ESP_LOGI(
        TAG,
        "controller init BLE gate battery_state=%s battery_mv=%u force_enable=%d",
        beet_battery_state_name(s_state.battery_state),
        s_state.battery_mv,
        BEET_BLE_FORCE_ENABLE_DIAGNOSTICS);
    ESP_RETURN_ON_ERROR(beet_ble_init(s_state.config.device_id), TAG, "ble init failed");
    beet_ble_set_system_event_callback(beet_on_ble_system_event);
    beet_log_ble_diag_status("after_ble_init");
    beet_ble_set_enabled(beet_ble_should_be_enabled());
    beet_log_ble_diag_status("after_ble_gate");
    beet_controller_set_indicator();
    beet_controller_set_display_power();
    beet_flush_dirty_snapshots(true);
    beet_log_system_event(BEET_SYSTEM_EVENT_STARTUP, 0U, NULL, 0U, false, 0U);

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
