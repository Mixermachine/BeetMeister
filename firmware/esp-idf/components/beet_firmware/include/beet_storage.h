#ifndef BEET_STORAGE_H
#define BEET_STORAGE_H

#include "esp_err.h"
#include "beet_types.h"

esp_err_t beet_storage_init(void);
esp_err_t beet_storage_load_or_init(
    beet_app_config_t *config,
    beet_pair_calibration_t calibrations[BEET_PAIR_COUNT],
    beet_pair_runtime_snapshot_t snapshots[BEET_PAIR_COUNT]);
esp_err_t beet_storage_save_config(const beet_app_config_t *config);
esp_err_t beet_storage_save_calibration(const beet_pair_calibration_t *calibration);
esp_err_t beet_storage_save_snapshot(const beet_pair_runtime_snapshot_t *snapshot);
esp_err_t beet_storage_scan_event_ring(beet_event_ring_state_t *state);
esp_err_t beet_storage_append_event(beet_event_ring_state_t *state, beet_event_record_t *record);

#endif
