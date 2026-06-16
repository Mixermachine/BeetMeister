#include "beet_maintenance.h"

#include <stddef.h>
#include <stdint.h>
#include <ctype.h>
#include <string.h>

#include "esp_rom_crc.h"
#include "beet_maintenance_tlv.h"
#include "beet_types.h"
#include "beet_generated_metadata.h"

#ifndef ESP_ERR_INVALID_SIZE
#define ESP_ERR_INVALID_SIZE ESP_ERR_INVALID_ARG
#endif

#ifndef ESP_ERR_INVALID_CRC
#define ESP_ERR_INVALID_CRC ESP_FAIL
#endif

#ifndef ESP_ERR_INVALID_RESPONSE
#define ESP_ERR_INVALID_RESPONSE ESP_FAIL
#endif

BEET_PACKED_BEGIN
typedef struct BEET_PACKED {
    uint32_t magic;
    uint16_t metadata_format_version;
    uint16_t total_length;
    uint32_t header_crc32;
} beet_maintenance_metadata_header_t;

typedef struct BEET_PACKED {
    uint16_t type;
    uint16_t length;
} beet_maintenance_tlv_entry_header_t;
BEET_PACKED_END

static bool beet_copy_tlv_string(
    const uint8_t *value,
    uint16_t value_len,
    char *out,
    size_t out_len)
{
    if (value == NULL || out == NULL || out_len == 0U || value_len == 0U || value_len >= out_len) {
        return false;
    }

    memcpy(out, value, value_len);
    out[value_len] = '\0';
    return true;
}

static bool beet_parse_u32_tlv(const uint8_t *value, uint16_t value_len, uint32_t *out)
{
    if (value == NULL || out == NULL || value_len != sizeof(uint32_t)) {
        return false;
    }

    *out = ((uint32_t)value[0]) |
        (((uint32_t)value[1]) << 8) |
        (((uint32_t)value[2]) << 16) |
        (((uint32_t)value[3]) << 24);
    return true;
}

const uint8_t *beet_maintenance_metadata_block(size_t *len_out)
{
    if (len_out != NULL) {
        *len_out = sizeof(g_beet_generated_metadata_block);
    }
    return g_beet_generated_metadata_block;
}

esp_err_t beet_maintenance_metadata_parse(
    const uint8_t *block,
    size_t block_len,
    beet_maintenance_image_metadata_t *metadata)
{
    const beet_maintenance_metadata_header_t *header;
    uint32_t header_crc;
    size_t offset;
    bool have_product_id = false;
    bool have_hardware_rev = false;
    bool have_firmware_version = false;
    bool have_build_label = false;
    bool have_maintenance_protocol_version = false;
    bool have_runtime_protocol_version = false;
    bool have_image_kind = false;

    if (block == NULL || metadata == NULL || block_len < sizeof(beet_maintenance_metadata_header_t)) {
        return ESP_ERR_INVALID_ARG;
    }

    header = (const beet_maintenance_metadata_header_t *)block;
    if (header->magic != BEET_MAINTENANCE_METADATA_MAGIC ||
        header->metadata_format_version != BEET_MAINTENANCE_METADATA_FORMAT_VERSION ||
        header->total_length > block_len ||
        header->total_length < sizeof(beet_maintenance_metadata_header_t)) {
        return ESP_ERR_INVALID_SIZE;
    }

    header_crc = esp_rom_crc32_le(
        0U,
        (const uint8_t *)header,
        offsetof(beet_maintenance_metadata_header_t, header_crc32));
    if (header_crc != header->header_crc32) {
        return ESP_ERR_INVALID_CRC;
    }

    memset(metadata, 0, sizeof(*metadata));
    offset = sizeof(*header);
    while (offset < header->total_length) {
        const beet_maintenance_tlv_entry_header_t *entry;
        const uint8_t *value;
        size_t next_offset;

        if ((header->total_length - offset) < sizeof(beet_maintenance_tlv_entry_header_t)) {
            return ESP_ERR_INVALID_SIZE;
        }

        entry = (const beet_maintenance_tlv_entry_header_t *)(block + offset);
        offset += sizeof(*entry);
        next_offset = offset + entry->length;
        if (next_offset > header->total_length) {
            return ESP_ERR_INVALID_SIZE;
        }

        value = block + offset;
        switch ((beet_maintenance_tlv_type_t)entry->type) {
        case BEET_MAINTENANCE_TLV_PRODUCT_ID:
            if (have_product_id ||
                !beet_copy_tlv_string(value, entry->length, metadata->product_id, sizeof(metadata->product_id))) {
                return ESP_ERR_INVALID_RESPONSE;
            }
            have_product_id = true;
            break;

        case BEET_MAINTENANCE_TLV_HARDWARE_REV:
            if (have_hardware_rev ||
                !beet_copy_tlv_string(value, entry->length, metadata->hardware_rev, sizeof(metadata->hardware_rev))) {
                return ESP_ERR_INVALID_RESPONSE;
            }
            have_hardware_rev = true;
            break;

        case BEET_MAINTENANCE_TLV_FIRMWARE_VERSION:
            if (have_firmware_version ||
                !beet_copy_tlv_string(value, entry->length, metadata->firmware_version, sizeof(metadata->firmware_version))) {
                return ESP_ERR_INVALID_RESPONSE;
            }
            have_firmware_version = true;
            break;

        case BEET_MAINTENANCE_TLV_BUILD_LABEL:
            if (have_build_label ||
                !beet_copy_tlv_string(value, entry->length, metadata->build_label, sizeof(metadata->build_label))) {
                return ESP_ERR_INVALID_RESPONSE;
            }
            have_build_label = true;
            break;

        case BEET_MAINTENANCE_TLV_MAINTENANCE_PROTOCOL_VERSION:
            if (have_maintenance_protocol_version ||
                !beet_parse_u32_tlv(value, entry->length, &metadata->maintenance_protocol_version)) {
                return ESP_ERR_INVALID_RESPONSE;
            }
            have_maintenance_protocol_version = true;
            break;

        case BEET_MAINTENANCE_TLV_RUNTIME_PROTOCOL_VERSION:
            if (have_runtime_protocol_version ||
                !beet_parse_u32_tlv(value, entry->length, &metadata->runtime_protocol_version)) {
                return ESP_ERR_INVALID_RESPONSE;
            }
            have_runtime_protocol_version = true;
            break;

        case BEET_MAINTENANCE_TLV_IMAGE_KIND: {
            char image_kind[BEET_MAINTENANCE_IMAGE_KIND_MAX_LEN + 1U];
            if (have_image_kind ||
                !beet_copy_tlv_string(value, entry->length, image_kind, sizeof(image_kind))) {
                return ESP_ERR_INVALID_RESPONSE;
            }
            metadata->image_kind = beet_maintenance_image_kind_from_name(image_kind);
            if (metadata->image_kind == BEET_MAINTENANCE_IMAGE_KIND_UNKNOWN) {
                return ESP_ERR_INVALID_RESPONSE;
            }
            have_image_kind = true;
            break;
        }

        case BEET_MAINTENANCE_TLV_COMPATIBLE_HARDWARE_REV:
            if (metadata->compatible_hardware_rev_count >= BEET_MAINTENANCE_COMPAT_REV_MAX_COUNT ||
                !beet_copy_tlv_string(
                    value,
                    entry->length,
                    metadata->compatible_hardware_revs[metadata->compatible_hardware_rev_count],
                    sizeof(metadata->compatible_hardware_revs[metadata->compatible_hardware_rev_count]))) {
                return ESP_ERR_INVALID_RESPONSE;
            }
            metadata->compatible_hardware_rev_count++;
            break;

        default:
            break;
        }

        offset = next_offset;
    }

    if (offset != header->total_length ||
        !have_product_id ||
        !have_hardware_rev ||
        !have_firmware_version ||
        !have_build_label ||
        !have_maintenance_protocol_version ||
        !have_runtime_protocol_version ||
        !have_image_kind ||
        metadata->compatible_hardware_rev_count == 0U) {
        return ESP_ERR_NOT_FOUND;
    }

    return ESP_OK;
}

esp_err_t beet_maintenance_get_info(beet_maintenance_info_t *info)
{
    beet_maintenance_image_metadata_t metadata;
    size_t metadata_len = 0U;
    const uint8_t *block = beet_maintenance_metadata_block(&metadata_len);
    esp_err_t err;

    if (info == NULL) {
        return ESP_ERR_INVALID_ARG;
    }

    err = beet_maintenance_metadata_parse(block, metadata_len, &metadata);
    if (err != ESP_OK) {
        return err;
    }

    memset(info, 0, sizeof(*info));
    memcpy(info->product_id, metadata.product_id, sizeof(info->product_id));
    memcpy(info->hardware_rev, metadata.hardware_rev, sizeof(info->hardware_rev));
    memcpy(info->firmware_version, metadata.firmware_version, sizeof(info->firmware_version));
    memcpy(info->build_label, metadata.build_label, sizeof(info->build_label));
    info->maintenance_protocol_version = metadata.maintenance_protocol_version;
    info->runtime_protocol_version = metadata.runtime_protocol_version;
    info->update_capable = true;
    info->image_kind = metadata.image_kind;
    return ESP_OK;
}

void beet_maintenance_fill_idle_status(beet_maintenance_status_t *status)
{
    if (status == NULL) {
        return;
    }

    memset(status, 0, sizeof(*status));
    status->state = BEET_MAINTENANCE_STATE_IDLE;
}

const char *beet_maintenance_state_name(beet_maintenance_state_t state)
{
    switch (state) {
    case BEET_MAINTENANCE_STATE_IDLE:
        return "idle";
    case BEET_MAINTENANCE_STATE_AWAITING_DATA:
        return "awaiting_data";
    case BEET_MAINTENANCE_STATE_TRANSFERRING:
        return "transferring";
    case BEET_MAINTENANCE_STATE_VERIFYING:
        return "verifying";
    case BEET_MAINTENANCE_STATE_REBOOTING:
        return "rebooting";
    case BEET_MAINTENANCE_STATE_COMPLETED:
        return "completed";
    case BEET_MAINTENANCE_STATE_FAILED:
        return "failed";
    default:
        return "unknown";
    }
}

const char *beet_maintenance_failure_reason_name(beet_maintenance_failure_reason_t reason)
{
    switch (reason) {
    case BEET_MAINTENANCE_FAILURE_NONE:
        return "none";
    case BEET_MAINTENANCE_FAILURE_UPDATE_SESSION_EXPIRED:
        return "update_session_expired";
    case BEET_MAINTENANCE_FAILURE_UPDATE_SESSION_INVALIDATED:
        return "update_session_invalidated";
    case BEET_MAINTENANCE_FAILURE_UPDATE_SESSION_NOT_FOUND:
        return "update_session_not_found";
    case BEET_MAINTENANCE_FAILURE_UPDATE_SESSION_MISMATCH:
        return "update_session_mismatch";
    case BEET_MAINTENANCE_FAILURE_UPDATE_ALREADY_ACTIVE:
        return "update_already_active";
    case BEET_MAINTENANCE_FAILURE_UPDATE_LOW_BATTERY:
        return "update_low_battery";
    case BEET_MAINTENANCE_FAILURE_UPDATE_WATERING_ACTIVE:
        return "update_watering_active";
    case BEET_MAINTENANCE_FAILURE_UPDATE_RUNTIME_BUSY:
        return "update_runtime_busy";
    case BEET_MAINTENANCE_FAILURE_UPDATE_INVALID_COMMAND:
        return "update_invalid_command";
    case BEET_MAINTENANCE_FAILURE_UPDATE_INVALID_METADATA:
        return "update_invalid_metadata";
    case BEET_MAINTENANCE_FAILURE_UPDATE_INVALID_OFFSET:
        return "update_invalid_offset";
    case BEET_MAINTENANCE_FAILURE_UPDATE_INVALID_CHUNK:
        return "update_invalid_chunk";
    case BEET_MAINTENANCE_FAILURE_UPDATE_UNKNOWN_PROTOCOL_VERSION:
        return "update_unknown_protocol_version";
    case BEET_MAINTENANCE_FAILURE_IMAGE_PRODUCT_MISMATCH:
        return "image_product_mismatch";
    case BEET_MAINTENANCE_FAILURE_IMAGE_HARDWARE_REVISION_INCOMPATIBLE:
        return "image_hardware_revision_incompatible";
    case BEET_MAINTENANCE_FAILURE_IMAGE_SHA256_MISMATCH:
        return "image_sha256_mismatch";
    case BEET_MAINTENANCE_FAILURE_IMAGE_UPLOAD_INCOMPLETE:
        return "image_upload_incomplete";
    case BEET_MAINTENANCE_FAILURE_IMAGE_SLOT_TOO_LARGE:
        return "image_slot_too_large";
    case BEET_MAINTENANCE_FAILURE_IMAGE_METADATA_MISSING:
        return "image_metadata_missing";
    case BEET_MAINTENANCE_FAILURE_IMAGE_METADATA_MALFORMED:
        return "image_metadata_malformed";
    case BEET_MAINTENANCE_FAILURE_UPDATE_INTERNAL_ERROR:
        return "update_internal_error";
    default:
        return "unknown";
    }
}

const char *beet_maintenance_image_kind_name(beet_maintenance_image_kind_t kind)
{
    switch (kind) {
    case BEET_MAINTENANCE_IMAGE_KIND_BUNDLED:
        return "bundled";
    case BEET_MAINTENANCE_IMAGE_KIND_CUSTOM:
        return "custom";
    case BEET_MAINTENANCE_IMAGE_KIND_UNKNOWN:
    default:
        return "unknown";
    }
}

beet_maintenance_image_kind_t beet_maintenance_image_kind_from_name(const char *name)
{
    if (name == NULL) {
        return BEET_MAINTENANCE_IMAGE_KIND_UNKNOWN;
    }
    if (strcmp(name, "bundled") == 0) {
        return BEET_MAINTENANCE_IMAGE_KIND_BUNDLED;
    }
    if (strcmp(name, "custom") == 0) {
        return BEET_MAINTENANCE_IMAGE_KIND_CUSTOM;
    }
    return BEET_MAINTENANCE_IMAGE_KIND_UNKNOWN;
}

bool beet_maintenance_is_valid_sha256_hex(const char *value)
{
    size_t len;

    if (value == NULL) {
        return false;
    }

    len = strlen(value);
    if (len != BEET_MAINTENANCE_SHA256_HEX_LEN) {
        return false;
    }

    for (size_t i = 0; i < len; ++i) {
        if (!isxdigit((unsigned char)value[i])) {
            return false;
        }
    }

    return true;
}
