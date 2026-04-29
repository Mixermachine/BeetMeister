#include "beet_storage.h"

#include <stdio.h>
#include <string.h>

#include "esp_check.h"
#include "esp_log.h"
#include "nvs.h"
#include "nvs_flash.h"
#include "beet_event_ring.h"

static const char *TAG = "beet_storage";

static esp_err_t beet_open_namespace(
    const char *partition,
    const char *namespace_name,
    nvs_open_mode_t mode,
    nvs_handle_t *handle)
{
    return nvs_open_from_partition(partition, namespace_name, mode, handle);
}

static void beet_pair_key(char *buffer, size_t buffer_len, uint8_t pair_index)
{
    snprintf(buffer, buffer_len, "p%u", (unsigned int)pair_index);
}

static void beet_event_key(char *buffer, size_t buffer_len, uint16_t slot_index)
{
    snprintf(buffer, buffer_len, "s%03u", (unsigned int)slot_index);
}

static uint32_t beet_resolve_unix_from_uptime(uint32_t boot_epoch_unix_s, uint32_t uptime_s)
{
    return boot_epoch_unix_s + uptime_s;
}

static esp_err_t beet_save_blob(
    const char *partition,
    const char *namespace_name,
    const char *key,
    const void *value,
    size_t value_len)
{
    nvs_handle_t handle = 0;
    ESP_RETURN_ON_ERROR(beet_open_namespace(partition, namespace_name, NVS_READWRITE, &handle), TAG, "nvs open failed");
    esp_err_t err = nvs_set_blob(handle, key, value, value_len);
    if (err == ESP_OK) {
        err = nvs_commit(handle);
    }
    nvs_close(handle);
    return err;
}

esp_err_t beet_storage_init(void)
{
    esp_err_t err = nvs_flash_init();
    if (err == ESP_ERR_NVS_NO_FREE_PAGES || err == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_RETURN_ON_ERROR(nvs_flash_erase(), TAG, "default nvs erase failed");
        err = nvs_flash_init();
    }
    ESP_RETURN_ON_ERROR(err, TAG, "default nvs init failed");

    err = nvs_flash_init_partition("appcfg");
    if (err == ESP_ERR_NVS_NO_FREE_PAGES || err == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_RETURN_ON_ERROR(nvs_flash_erase_partition("appcfg"), TAG, "appcfg erase failed");
        err = nvs_flash_init_partition("appcfg");
    }
    ESP_RETURN_ON_ERROR(err, TAG, "appcfg init failed");

    err = nvs_flash_init_partition("events");
    if (err == ESP_ERR_NVS_NO_FREE_PAGES || err == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_RETURN_ON_ERROR(nvs_flash_erase_partition("events"), TAG, "events erase failed");
        err = nvs_flash_init_partition("events");
    }
    ESP_RETURN_ON_ERROR(err, TAG, "events init failed");

    err = nvs_flash_init_partition("sysevents");
    if (err == ESP_ERR_NVS_NO_FREE_PAGES || err == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_RETURN_ON_ERROR(nvs_flash_erase_partition("sysevents"), TAG, "sysevents erase failed");
        err = nvs_flash_init_partition("sysevents");
    }
    ESP_RETURN_ON_ERROR(err, TAG, "sysevents init failed");

    return ESP_OK;
}

esp_err_t beet_storage_save_config(const beet_app_config_t *config)
{
    return beet_save_blob("appcfg", "cfg", "app", config, sizeof(*config));
}

esp_err_t beet_storage_save_calibration(const beet_pair_calibration_t *calibration)
{
    char key[8];
    beet_pair_key(key, sizeof(key), calibration->pair_index);
    return beet_save_blob("appcfg", "cal", key, calibration, sizeof(*calibration));
}

esp_err_t beet_storage_save_snapshot(const beet_pair_runtime_snapshot_t *snapshot)
{
    char key[8];
    beet_pair_key(key, sizeof(key), snapshot->pair_index);
    return beet_save_blob("appcfg", "snap", key, snapshot, sizeof(*snapshot));
}

esp_err_t beet_storage_save_power_state(const beet_power_runtime_state_t *state)
{
    return beet_save_blob("appcfg", "pwr", "state", state, sizeof(*state));
}

static esp_err_t beet_load_config(beet_app_config_t *config, bool *was_initialized)
{
    nvs_handle_t handle = 0;
    ESP_RETURN_ON_ERROR(beet_open_namespace("appcfg", "cfg", NVS_READWRITE, &handle), TAG, "config namespace open failed");

    size_t required_size = sizeof(*config);
    esp_err_t err = nvs_get_blob(handle, "app", config, &required_size);
    if (err == ESP_ERR_NVS_NOT_FOUND || required_size != sizeof(*config) || config->schema_version != BEET_SCHEMA_VERSION) {
        beet_default_app_config(config);
        err = nvs_set_blob(handle, "app", config, sizeof(*config));
        if (err == ESP_OK) {
            err = nvs_commit(handle);
        }
        *was_initialized = (err == ESP_OK);
    } else {
        *was_initialized = false;
    }

    nvs_close(handle);
    return err;
}

static esp_err_t beet_load_pair_records(
    beet_pair_calibration_t calibrations[BEET_PAIR_COUNT],
    beet_pair_runtime_snapshot_t snapshots[BEET_PAIR_COUNT])
{
    nvs_handle_t cal_handle = 0;
    nvs_handle_t snap_handle = 0;

    ESP_RETURN_ON_ERROR(beet_open_namespace("appcfg", "cal", NVS_READWRITE, &cal_handle), TAG, "cal namespace open failed");
    ESP_RETURN_ON_ERROR(beet_open_namespace("appcfg", "snap", NVS_READWRITE, &snap_handle), TAG, "snap namespace open failed");

    for (uint8_t pair = 1U; pair <= BEET_PAIR_COUNT; ++pair) {
        char key[8];
        size_t required_size = 0;

        beet_pair_key(key, sizeof(key), pair);
        required_size = sizeof(beet_pair_calibration_t);
        esp_err_t err = nvs_get_blob(cal_handle, key, &calibrations[pair - 1U], &required_size);
        if (err == ESP_ERR_NVS_NOT_FOUND || required_size != sizeof(beet_pair_calibration_t)) {
            beet_default_calibration(pair, &calibrations[pair - 1U]);
            ESP_RETURN_ON_ERROR(
                nvs_set_blob(cal_handle, key, &calibrations[pair - 1U], sizeof(beet_pair_calibration_t)),
                TAG,
                "default calibration write failed");
        }

        required_size = sizeof(beet_pair_runtime_snapshot_t);
        err = nvs_get_blob(snap_handle, key, &snapshots[pair - 1U], &required_size);
        if (err == ESP_ERR_NVS_NOT_FOUND || required_size != sizeof(beet_pair_runtime_snapshot_t)) {
            beet_default_snapshot(pair, &snapshots[pair - 1U]);
            ESP_RETURN_ON_ERROR(
                nvs_set_blob(snap_handle, key, &snapshots[pair - 1U], sizeof(beet_pair_runtime_snapshot_t)),
                TAG,
                "default snapshot write failed");
        }
    }

    ESP_RETURN_ON_ERROR(nvs_commit(cal_handle), TAG, "cal commit failed");
    ESP_RETURN_ON_ERROR(nvs_commit(snap_handle), TAG, "snap commit failed");

    nvs_close(cal_handle);
    nvs_close(snap_handle);
    return ESP_OK;
}

static esp_err_t beet_load_power_state(beet_power_runtime_state_t *state)
{
    nvs_handle_t handle = 0;
    ESP_RETURN_ON_ERROR(beet_open_namespace("appcfg", "pwr", NVS_READWRITE, &handle), TAG, "power namespace open failed");

    size_t required_size = sizeof(*state);
    esp_err_t err = nvs_get_blob(handle, "state", state, &required_size);
    if (err == ESP_ERR_NVS_NOT_FOUND ||
        required_size != sizeof(*state) ||
        state->schema_version != BEET_SCHEMA_VERSION ||
        !beet_is_valid_sleep_mode(state->last_sleep_mode)) {
        beet_default_power_runtime_state(state);
        ESP_RETURN_ON_ERROR(
            nvs_set_blob(handle, "state", state, sizeof(*state)),
            TAG,
            "default power state write failed");
        ESP_RETURN_ON_ERROR(nvs_commit(handle), TAG, "power commit failed");
        nvs_close(handle);
        return ESP_OK;
    }

    nvs_close(handle);
    return ESP_OK;
}

esp_err_t beet_storage_load_or_init(
    beet_app_config_t *config,
    beet_pair_calibration_t calibrations[BEET_PAIR_COUNT],
    beet_pair_runtime_snapshot_t snapshots[BEET_PAIR_COUNT],
    beet_power_runtime_state_t *power_state)
{
    bool initialized_defaults = false;
    ESP_RETURN_ON_ERROR(beet_load_config(config, &initialized_defaults), TAG, "config load failed");
    ESP_RETURN_ON_ERROR(beet_load_pair_records(calibrations, snapshots), TAG, "pair records load failed");
    ESP_RETURN_ON_ERROR(beet_load_power_state(power_state), TAG, "power state load failed");

    if (initialized_defaults) {
        ESP_LOGI(TAG, "initialized default BeetMeister configuration");
    }
    return ESP_OK;
}

esp_err_t beet_storage_scan_event_ring(beet_event_ring_state_t *state)
{
    nvs_handle_t handle = 0;
    ESP_RETURN_ON_FALSE(state != NULL, ESP_ERR_INVALID_ARG, TAG, "state is null");
    ESP_RETURN_ON_ERROR(beet_open_namespace("events", "ring", NVS_READWRITE, &handle), TAG, "event namespace open failed");

    beet_event_ring_reset(state);

    for (uint16_t slot = 0U; slot < BEET_EVENT_RING_CAPACITY; ++slot) {
        beet_event_record_t record;
        size_t required_size = sizeof(record);
        char key[8];
        beet_event_key(key, sizeof(key), slot);

        esp_err_t err = nvs_get_blob(handle, key, &record, &required_size);
        if (err != ESP_OK || required_size != sizeof(record)) {
            continue;
        }
        beet_event_ring_accept_record(state, &record);
    }

    beet_event_ring_finalize(state);

    nvs_close(handle);
    return ESP_OK;
}

esp_err_t beet_storage_append_event(beet_event_ring_state_t *state, beet_event_record_t *record)
{
    nvs_handle_t handle = 0;
    char key[8];
    uint64_t next_seq = 1U;
    uint16_t slot = 1U;

    ESP_RETURN_ON_FALSE(state != NULL, ESP_ERR_INVALID_ARG, TAG, "state is null");
    ESP_RETURN_ON_FALSE(record != NULL, ESP_ERR_INVALID_ARG, TAG, "record is null");

    if (state->has_valid_records) {
        next_seq = state->highest_valid_seq_no + 1U;
        slot = (uint16_t)(next_seq % BEET_EVENT_RING_CAPACITY);
    }

    record->schema_version = BEET_EVENT_RECORD_VERSION;
    record->seq_no = next_seq;
    memset(record->reserved, 0, sizeof(record->reserved));
    record->crc32 = beet_event_crc32(record);

    ESP_RETURN_ON_ERROR(beet_open_namespace("events", "ring", NVS_READWRITE, &handle), TAG, "event namespace open failed");
    beet_event_key(key, sizeof(key), slot);
    ESP_RETURN_ON_ERROR(
        nvs_set_blob(handle, key, record, sizeof(*record)),
        TAG,
        "event write failed");
    ESP_RETURN_ON_ERROR(nvs_commit(handle), TAG, "event commit failed");
    nvs_close(handle);

    state->has_valid_records = true;
    state->highest_valid_seq_no = next_seq;
    state->next_write_slot = (uint16_t)((next_seq + 1U) % BEET_EVENT_RING_CAPACITY);
    return ESP_OK;
}

esp_err_t beet_storage_read_event_by_seq_no(uint32_t current_boot_id, uint64_t seq_no, beet_event_record_t *record)
{
    nvs_handle_t handle = 0;

    ESP_RETURN_ON_FALSE(record != NULL, ESP_ERR_INVALID_ARG, TAG, "record is null");
    ESP_RETURN_ON_ERROR(beet_open_namespace("events", "ring", NVS_READWRITE, &handle), TAG, "event namespace open failed");

    for (uint16_t slot = 0U; slot < BEET_EVENT_RING_CAPACITY; ++slot) {
        beet_event_record_t candidate;
        size_t required_size = sizeof(candidate);
        char key[8];

        beet_event_key(key, sizeof(key), slot);
        esp_err_t err = nvs_get_blob(handle, key, &candidate, &required_size);
        if (err != ESP_OK ||
            required_size != sizeof(candidate) ||
            !beet_event_record_is_visible(&candidate, current_boot_id)) {
            continue;
        }
        if (candidate.seq_no == seq_no) {
            *record = candidate;
            nvs_close(handle);
            return ESP_OK;
        }
    }

    nvs_close(handle);
    return ESP_ERR_NOT_FOUND;
}

esp_err_t beet_storage_summarize_events(uint32_t current_boot_id, uint16_t *event_count, uint32_t pair_totals_s[BEET_PAIR_COUNT])
{
    nvs_handle_t handle = 0;

    ESP_RETURN_ON_FALSE(event_count != NULL, ESP_ERR_INVALID_ARG, TAG, "event_count is null");
    ESP_RETURN_ON_FALSE(pair_totals_s != NULL, ESP_ERR_INVALID_ARG, TAG, "pair_totals_s is null");
    ESP_RETURN_ON_ERROR(beet_open_namespace("events", "ring", NVS_READWRITE, &handle), TAG, "event namespace open failed");

    *event_count = 0U;
    memset(pair_totals_s, 0, sizeof(uint32_t) * BEET_PAIR_COUNT);

    for (uint16_t slot = 0U; slot < BEET_EVENT_RING_CAPACITY; ++slot) {
        beet_event_record_t record;
        size_t required_size = sizeof(record);
        char key[8];

        beet_event_key(key, sizeof(key), slot);
        if (nvs_get_blob(handle, key, &record, &required_size) != ESP_OK ||
            required_size != sizeof(record) ||
            !beet_event_record_is_visible(&record, current_boot_id)) {
            continue;
        }

        beet_event_ring_accumulate_summary(&record, event_count, pair_totals_s);
    }

    nvs_close(handle);
    return ESP_OK;
}

esp_err_t beet_storage_backfill_event_times(uint32_t current_boot_id, uint32_t boot_epoch_unix_s, uint16_t *updated_count)
{
    nvs_handle_t handle = 0;
    bool changed = false;

    ESP_RETURN_ON_FALSE(updated_count != NULL, ESP_ERR_INVALID_ARG, TAG, "updated_count is null");
    ESP_RETURN_ON_ERROR(beet_open_namespace("events", "ring", NVS_READWRITE, &handle), TAG, "event namespace open failed");

    *updated_count = 0U;
    for (uint16_t slot = 0U; slot < BEET_EVENT_RING_CAPACITY; ++slot) {
        beet_event_record_t record;
        size_t required_size = sizeof(record);
        char key[8];

        beet_event_key(key, sizeof(key), slot);
        if (nvs_get_blob(handle, key, &record, &required_size) != ESP_OK ||
            required_size != sizeof(record) ||
            !beet_validate_event_record(&record) ||
            record.boot_id != current_boot_id ||
            record.time_valid != 0U) {
            continue;
        }

        record.started_at_unix_s = beet_resolve_unix_from_uptime(boot_epoch_unix_s, record.started_uptime_s);
        record.ended_at_unix_s = beet_resolve_unix_from_uptime(boot_epoch_unix_s, record.ended_uptime_s);
        record.time_valid = 1U;
        record.crc32 = beet_event_crc32(&record);
        ESP_RETURN_ON_ERROR(nvs_set_blob(handle, key, &record, sizeof(record)), TAG, "event backfill write failed");
        changed = true;
        (*updated_count)++;
    }

    if (changed) {
        ESP_RETURN_ON_ERROR(nvs_commit(handle), TAG, "event backfill commit failed");
    }
    nvs_close(handle);
    return ESP_OK;
}

esp_err_t beet_storage_scan_system_event_ring(beet_event_ring_state_t *state)
{
    nvs_handle_t handle = 0;
    ESP_RETURN_ON_FALSE(state != NULL, ESP_ERR_INVALID_ARG, TAG, "state is null");
    ESP_RETURN_ON_ERROR(beet_open_namespace("sysevents", "ring", NVS_READWRITE, &handle), TAG, "system event namespace open failed");

    beet_event_ring_reset(state);

    for (uint16_t slot = 0U; slot < BEET_SYSTEM_EVENT_RING_CAPACITY; ++slot) {
        beet_system_event_record_t record;
        size_t required_size = sizeof(record);
        char key[8];
        beet_event_key(key, sizeof(key), slot);

        esp_err_t err = nvs_get_blob(handle, key, &record, &required_size);
        if (err != ESP_OK || required_size != sizeof(record)) {
            continue;
        }
        beet_system_event_ring_accept_record(state, &record);
    }

    beet_system_event_ring_finalize(state);

    nvs_close(handle);
    return ESP_OK;
}

esp_err_t beet_storage_append_system_event(beet_event_ring_state_t *state, beet_system_event_record_t *record)
{
    nvs_handle_t handle = 0;
    char key[8];
    uint64_t next_seq = 1U;
    uint16_t slot = 1U;

    ESP_RETURN_ON_FALSE(state != NULL, ESP_ERR_INVALID_ARG, TAG, "state is null");
    ESP_RETURN_ON_FALSE(record != NULL, ESP_ERR_INVALID_ARG, TAG, "record is null");

    if (state->has_valid_records) {
        next_seq = state->highest_valid_seq_no + 1U;
        slot = (uint16_t)(next_seq % BEET_SYSTEM_EVENT_RING_CAPACITY);
    }

    record->schema_version = BEET_SYSTEM_EVENT_RECORD_VERSION;
    record->seq_no = next_seq;
    memset(record->reserved, 0, sizeof(record->reserved));
    record->crc32 = beet_system_event_crc32(record);

    ESP_RETURN_ON_ERROR(beet_open_namespace("sysevents", "ring", NVS_READWRITE, &handle), TAG, "system event namespace open failed");
    beet_event_key(key, sizeof(key), slot);
    ESP_RETURN_ON_ERROR(nvs_set_blob(handle, key, record, sizeof(*record)), TAG, "system event write failed");
    ESP_RETURN_ON_ERROR(nvs_commit(handle), TAG, "system event commit failed");
    nvs_close(handle);

    state->has_valid_records = true;
    state->highest_valid_seq_no = next_seq;
    state->next_write_slot = (uint16_t)((next_seq + 1U) % BEET_SYSTEM_EVENT_RING_CAPACITY);
    return ESP_OK;
}

esp_err_t beet_storage_read_system_event_by_seq_no(uint32_t current_boot_id, uint64_t seq_no, beet_system_event_record_t *record)
{
    nvs_handle_t handle = 0;

    ESP_RETURN_ON_FALSE(record != NULL, ESP_ERR_INVALID_ARG, TAG, "record is null");
    ESP_RETURN_ON_ERROR(beet_open_namespace("sysevents", "ring", NVS_READWRITE, &handle), TAG, "system event namespace open failed");

    for (uint16_t slot = 0U; slot < BEET_SYSTEM_EVENT_RING_CAPACITY; ++slot) {
        beet_system_event_record_t candidate;
        size_t required_size = sizeof(candidate);
        char key[8];

        beet_event_key(key, sizeof(key), slot);
        esp_err_t err = nvs_get_blob(handle, key, &candidate, &required_size);
        if (err != ESP_OK ||
            required_size != sizeof(candidate) ||
            !beet_system_event_record_is_visible(&candidate, current_boot_id)) {
            continue;
        }
        if (candidate.seq_no == seq_no) {
            *record = candidate;
            nvs_close(handle);
            return ESP_OK;
        }
    }

    nvs_close(handle);
    return ESP_ERR_NOT_FOUND;
}

esp_err_t beet_storage_summarize_system_events(uint32_t current_boot_id, uint16_t *event_count)
{
    nvs_handle_t handle = 0;

    ESP_RETURN_ON_FALSE(event_count != NULL, ESP_ERR_INVALID_ARG, TAG, "event_count is null");
    ESP_RETURN_ON_ERROR(beet_open_namespace("sysevents", "ring", NVS_READWRITE, &handle), TAG, "system event namespace open failed");

    *event_count = 0U;
    for (uint16_t slot = 0U; slot < BEET_SYSTEM_EVENT_RING_CAPACITY; ++slot) {
        beet_system_event_record_t record;
        size_t required_size = sizeof(record);
        char key[8];

        beet_event_key(key, sizeof(key), slot);
        if (nvs_get_blob(handle, key, &record, &required_size) != ESP_OK ||
            required_size != sizeof(record) ||
            !beet_system_event_record_is_visible(&record, current_boot_id)) {
            continue;
        }

        (*event_count)++;
    }

    nvs_close(handle);
    return ESP_OK;
}

esp_err_t beet_storage_backfill_system_event_times(uint32_t current_boot_id, uint32_t boot_epoch_unix_s, uint16_t *updated_count)
{
    nvs_handle_t handle = 0;
    bool changed = false;

    ESP_RETURN_ON_FALSE(updated_count != NULL, ESP_ERR_INVALID_ARG, TAG, "updated_count is null");
    ESP_RETURN_ON_ERROR(beet_open_namespace("sysevents", "ring", NVS_READWRITE, &handle), TAG, "system event namespace open failed");

    *updated_count = 0U;
    for (uint16_t slot = 0U; slot < BEET_SYSTEM_EVENT_RING_CAPACITY; ++slot) {
        beet_system_event_record_t record;
        size_t required_size = sizeof(record);
        char key[8];

        beet_event_key(key, sizeof(key), slot);
        if (nvs_get_blob(handle, key, &record, &required_size) != ESP_OK ||
            required_size != sizeof(record) ||
            !beet_validate_system_event_record(&record) ||
            record.boot_id != current_boot_id ||
            record.time_valid != 0U) {
            continue;
        }

        record.occurred_unix_s = beet_resolve_unix_from_uptime(boot_epoch_unix_s, record.occurred_uptime_s);
        record.time_valid = 1U;
        record.crc32 = beet_system_event_crc32(&record);
        ESP_RETURN_ON_ERROR(nvs_set_blob(handle, key, &record, sizeof(record)), TAG, "system event backfill write failed");
        changed = true;
        (*updated_count)++;
    }

    if (changed) {
        ESP_RETURN_ON_ERROR(nvs_commit(handle), TAG, "system event backfill commit failed");
    }
    nvs_close(handle);
    return ESP_OK;
}
