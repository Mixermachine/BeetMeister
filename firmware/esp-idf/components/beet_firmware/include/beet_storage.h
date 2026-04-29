#ifndef BEET_STORAGE_H
#define BEET_STORAGE_H

#include "esp_err.h"
#include "beet_types.h"

esp_err_t beet_storage_init(void);
esp_err_t beet_storage_load_or_init(
    beet_app_config_t *config,
    beet_pair_calibration_t calibrations[BEET_PAIR_COUNT],
    beet_pair_runtime_snapshot_t snapshots[BEET_PAIR_COUNT],
    beet_power_runtime_state_t *power_state);
esp_err_t beet_storage_save_config(const beet_app_config_t *config);
esp_err_t beet_storage_save_calibration(const beet_pair_calibration_t *calibration);
esp_err_t beet_storage_save_snapshot(const beet_pair_runtime_snapshot_t *snapshot);
esp_err_t beet_storage_save_power_state(const beet_power_runtime_state_t *state);
esp_err_t beet_storage_scan_event_ring(beet_event_ring_state_t *state);
esp_err_t beet_storage_append_event(beet_event_ring_state_t *state, beet_event_record_t *record);
esp_err_t beet_storage_read_event_by_seq_no(uint32_t current_boot_id, uint64_t seq_no, beet_event_record_t *record);
esp_err_t beet_storage_summarize_events(uint32_t current_boot_id, uint16_t *event_count, uint32_t pair_totals_s[BEET_PAIR_COUNT]);
esp_err_t beet_storage_backfill_event_times(uint32_t current_boot_id, uint32_t boot_epoch_unix_s, uint16_t *updated_count);
esp_err_t beet_storage_scan_system_event_ring(beet_event_ring_state_t *state);
esp_err_t beet_storage_append_system_event(beet_event_ring_state_t *state, beet_system_event_record_t *record);
esp_err_t beet_storage_read_system_event_by_seq_no(uint32_t current_boot_id, uint64_t seq_no, beet_system_event_record_t *record);
esp_err_t beet_storage_summarize_system_events(uint32_t current_boot_id, uint16_t *event_count);
esp_err_t beet_storage_backfill_system_event_times(uint32_t current_boot_id, uint32_t boot_epoch_unix_s, uint16_t *updated_count);

#endif
