#ifndef BEET_MAINTENANCE_H
#define BEET_MAINTENANCE_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "esp_err.h"

#ifndef BEET_RUNTIME_PROTOCOL_VERSION
#error "BEET_RUNTIME_PROTOCOL_VERSION must be provided by the build configuration."
#endif

#ifndef BEET_MAINTENANCE_PROTOCOL_VERSION
#error "BEET_MAINTENANCE_PROTOCOL_VERSION must be provided by the build configuration."
#endif

#define BEET_MAINTENANCE_PRODUCT_ID_MAX_LEN 24U
#define BEET_MAINTENANCE_HARDWARE_REV_MAX_LEN 16U

/*
 * BEET_MAINTENANCE_FIRMWARE_VERSION_MAX_LEN is FROZEN at 32.
 *
 * DO NOT INCREASE THIS VALUE. Increasing it is a band-aid that masks the
 * real problem (too-long version strings produced by git describe on dirty
 * trees) and changes struct layouts across the codebase for no benefit.
 *
 * If a version string exceeds 32 characters:
 *   - CI builds: fix the CI tag format. CI always tags before build on a
 *     clean tree, so the tag name alone is embedded (max ~26 chars).
 *   - Dev builds: the CMake build system produces "dev-<short-hash>" (11
 *     chars) for dirty trees. This is always short enough.
 *   - As a last resort, the runtime parser (beet_copy_tlv_string) truncates
 *     overlong strings with a warning. The controller still boots and
 *     exposes a truncated version via BLE.
 *
 * Increasing MAX_LEN breaks the design contract, wastes RAM, and guarantees
 * the next naming-convention change will overflow again. Fix the source,
 * not the buffer.
 */
#define BEET_MAINTENANCE_FIRMWARE_VERSION_MAX_LEN 32U
#define BEET_MAINTENANCE_BUILD_LABEL_MAX_LEN 32U
#define BEET_MAINTENANCE_IMAGE_KIND_MAX_LEN 16U
#define BEET_MAINTENANCE_SHA256_HEX_LEN 64U
#define BEET_MAINTENANCE_ASSET_ID_MAX_LEN 64U
#define BEET_MAINTENANCE_COMPAT_REV_MAX_COUNT 8U

typedef enum {
    BEET_MAINTENANCE_STATE_IDLE = 0,
    BEET_MAINTENANCE_STATE_AWAITING_DATA = 1,
    BEET_MAINTENANCE_STATE_TRANSFERRING = 2,
    BEET_MAINTENANCE_STATE_VERIFYING = 3,
    BEET_MAINTENANCE_STATE_REBOOTING = 4,
    BEET_MAINTENANCE_STATE_COMPLETED = 5,
    BEET_MAINTENANCE_STATE_FAILED = 6,
} beet_maintenance_state_t;

typedef enum {
    BEET_MAINTENANCE_FAILURE_NONE = 0,
    BEET_MAINTENANCE_FAILURE_UPDATE_SESSION_EXPIRED = 1,
    BEET_MAINTENANCE_FAILURE_UPDATE_SESSION_INVALIDATED = 2,
    BEET_MAINTENANCE_FAILURE_UPDATE_SESSION_NOT_FOUND = 3,
    BEET_MAINTENANCE_FAILURE_UPDATE_SESSION_MISMATCH = 4,
    BEET_MAINTENANCE_FAILURE_UPDATE_ALREADY_ACTIVE = 5,
    BEET_MAINTENANCE_FAILURE_UPDATE_LOW_BATTERY = 6,
    BEET_MAINTENANCE_FAILURE_UPDATE_WATERING_ACTIVE = 7,
    BEET_MAINTENANCE_FAILURE_UPDATE_RUNTIME_BUSY = 8,
    BEET_MAINTENANCE_FAILURE_UPDATE_INVALID_COMMAND = 9,
    BEET_MAINTENANCE_FAILURE_UPDATE_INVALID_METADATA = 10,
    BEET_MAINTENANCE_FAILURE_UPDATE_INVALID_OFFSET = 11,
    BEET_MAINTENANCE_FAILURE_UPDATE_INVALID_CHUNK = 12,
    BEET_MAINTENANCE_FAILURE_UPDATE_UNKNOWN_PROTOCOL_VERSION = 13,
    BEET_MAINTENANCE_FAILURE_IMAGE_PRODUCT_MISMATCH = 14,
    BEET_MAINTENANCE_FAILURE_IMAGE_HARDWARE_REVISION_INCOMPATIBLE = 15,
    BEET_MAINTENANCE_FAILURE_IMAGE_SHA256_MISMATCH = 16,
    BEET_MAINTENANCE_FAILURE_IMAGE_UPLOAD_INCOMPLETE = 17,
    BEET_MAINTENANCE_FAILURE_IMAGE_SLOT_TOO_LARGE = 18,
    BEET_MAINTENANCE_FAILURE_IMAGE_METADATA_MISSING = 19,
    BEET_MAINTENANCE_FAILURE_IMAGE_METADATA_MALFORMED = 20,
    BEET_MAINTENANCE_FAILURE_UPDATE_INTERNAL_ERROR = 21,
} beet_maintenance_failure_reason_t;

typedef enum {
    BEET_MAINTENANCE_IMAGE_KIND_UNKNOWN = 0,
    BEET_MAINTENANCE_IMAGE_KIND_BUNDLED = 1,
    BEET_MAINTENANCE_IMAGE_KIND_CUSTOM = 2,
} beet_maintenance_image_kind_t;

typedef struct {
    char product_id[BEET_MAINTENANCE_PRODUCT_ID_MAX_LEN + 1U];
    char hardware_rev[BEET_MAINTENANCE_HARDWARE_REV_MAX_LEN + 1U];
    char firmware_version[BEET_MAINTENANCE_FIRMWARE_VERSION_MAX_LEN + 1U];
    char build_label[BEET_MAINTENANCE_BUILD_LABEL_MAX_LEN + 1U];
    uint32_t maintenance_protocol_version;
    uint32_t runtime_protocol_version;
    beet_maintenance_image_kind_t image_kind;
    uint8_t compatible_hardware_rev_count;
    char compatible_hardware_revs[BEET_MAINTENANCE_COMPAT_REV_MAX_COUNT][BEET_MAINTENANCE_HARDWARE_REV_MAX_LEN + 1U];
} beet_maintenance_image_metadata_t;

typedef struct {
    char product_id[BEET_MAINTENANCE_PRODUCT_ID_MAX_LEN + 1U];
    char hardware_rev[BEET_MAINTENANCE_HARDWARE_REV_MAX_LEN + 1U];
    char firmware_version[BEET_MAINTENANCE_FIRMWARE_VERSION_MAX_LEN + 1U];
    char build_label[BEET_MAINTENANCE_BUILD_LABEL_MAX_LEN + 1U];
    uint32_t maintenance_protocol_version;
    uint32_t runtime_protocol_version;
    bool update_capable;
    beet_maintenance_image_kind_t image_kind;
} beet_maintenance_info_t;

typedef struct {
    beet_maintenance_state_t state;
    bool has_session_id;
    uint32_t session_id;
    uint32_t next_offset;
    uint32_t bytes_received;
    uint32_t total_bytes;
    bool has_failure_reason;
    beet_maintenance_failure_reason_t failure_reason;
} beet_maintenance_status_t;

// WARNING: The maintenance protocol is intended to be effectively frozen.
// Do not rename these commands, repurpose them, or change their wire meaning
// without an explicit backward-compatible protocol migration.
typedef enum {
    BEET_MAINTENANCE_COMMAND_QUERY_STATUS = 0,
    BEET_MAINTENANCE_COMMAND_BEGIN_UPDATE = 1,
    BEET_MAINTENANCE_COMMAND_ABORT_UPDATE = 2,
    BEET_MAINTENANCE_COMMAND_FINISH_UPDATE = 3,
} beet_maintenance_command_t;

typedef struct {
    char firmware_version[BEET_MAINTENANCE_FIRMWARE_VERSION_MAX_LEN + 1U];
    char build_label[BEET_MAINTENANCE_BUILD_LABEL_MAX_LEN + 1U];
    uint32_t image_size;
    char image_sha256[BEET_MAINTENANCE_SHA256_HEX_LEN + 1U];
    char product_id[BEET_MAINTENANCE_PRODUCT_ID_MAX_LEN + 1U];
    uint8_t hardware_rev_count;
    char hardware_revs[BEET_MAINTENANCE_COMPAT_REV_MAX_COUNT][BEET_MAINTENANCE_HARDWARE_REV_MAX_LEN + 1U];
    bool has_runtime_protocol_version;
    uint32_t runtime_protocol_version;
    char asset_id[BEET_MAINTENANCE_ASSET_ID_MAX_LEN + 1U];
    beet_maintenance_image_kind_t image_kind;
} beet_maintenance_begin_update_request_t;

// WARNING: This request shape defines the firmware-side maintenance wire
// contract. Preserve backward compatibility for every shipped maintenance
// protocol version, including compact aliases parsed on the app side.
typedef struct {
    beet_maintenance_command_t command;
    beet_maintenance_begin_update_request_t begin_update;
} beet_maintenance_request_t;

typedef struct {
    bool active;
    beet_maintenance_state_t state;
    bool has_session_id;
    uint32_t session_id;
    uint32_t next_offset;
    uint32_t bytes_received;
    uint32_t total_bytes;
    bool has_failure_reason;
    beet_maintenance_failure_reason_t failure_reason;
} beet_maintenance_session_snapshot_t;

const uint8_t *beet_maintenance_metadata_block(size_t *len_out);
esp_err_t beet_maintenance_metadata_parse(
    const uint8_t *block,
    size_t block_len,
    beet_maintenance_image_metadata_t *metadata);
esp_err_t beet_maintenance_get_info(beet_maintenance_info_t *info);
void beet_maintenance_fill_idle_status(beet_maintenance_status_t *status);
const char *beet_maintenance_state_name(beet_maintenance_state_t state);
const char *beet_maintenance_failure_reason_name(beet_maintenance_failure_reason_t reason);
const char *beet_maintenance_image_kind_name(beet_maintenance_image_kind_t kind);
beet_maintenance_image_kind_t beet_maintenance_image_kind_from_name(const char *name);
bool beet_maintenance_is_valid_sha256_hex(const char *value);

#endif
