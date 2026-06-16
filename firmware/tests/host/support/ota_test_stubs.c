#include "esp_ota_ops.h"

#include <string.h>
#include <stdlib.h>

#include "ble_test_shim.h"

typedef struct {
    uint32_t state[8];
    uint64_t bit_count;
    uint8_t buffer[64];
    size_t buffer_len;
} sha256_ctx_t;

static const uint32_t k_sha256_round_constants[64] = {
    0x428a2f98U, 0x71374491U, 0xb5c0fbcfU, 0xe9b5dba5U,
    0x3956c25bU, 0x59f111f1U, 0x923f82a4U, 0xab1c5ed5U,
    0xd807aa98U, 0x12835b01U, 0x243185beU, 0x550c7dc3U,
    0x72be5d74U, 0x80deb1feU, 0x9bdc06a7U, 0xc19bf174U,
    0xe49b69c1U, 0xefbe4786U, 0x0fc19dc6U, 0x240ca1ccU,
    0x2de92c6fU, 0x4a7484aaU, 0x5cb0a9dcU, 0x76f988daU,
    0x983e5152U, 0xa831c66dU, 0xb00327c8U, 0xbf597fc7U,
    0xc6e00bf3U, 0xd5a79147U, 0x06ca6351U, 0x14292967U,
    0x27b70a85U, 0x2e1b2138U, 0x4d2c6dfcU, 0x53380d13U,
    0x650a7354U, 0x766a0abbU, 0x81c2c92eU, 0x92722c85U,
    0xa2bfe8a1U, 0xa81a664bU, 0xc24b8b70U, 0xc76c51a3U,
    0xd192e819U, 0xd6990624U, 0xf40e3585U, 0x106aa070U,
    0x19a4c116U, 0x1e376c08U, 0x2748774cU, 0x34b0bcb5U,
    0x391c0cb3U, 0x4ed8aa4aU, 0x5b9cca4fU, 0x682e6ff3U,
    0x748f82eeU, 0x78a5636fU, 0x84c87814U, 0x8cc70208U,
    0x90befffaU, 0xa4506cebU, 0xbef9a3f7U, 0xc67178f2U,
};

static const esp_partition_t s_ota_partition = {
    .label = "ota_0",
    .size = 0x600000U,
    .address = 0x200000U,
};

static uint8_t *s_ota_partition_bytes;
static size_t s_ota_partition_capacity;
static size_t s_ota_image_size;
static size_t s_ota_written_size;
static esp_ota_handle_t s_ota_handle = 1U;
static bool s_ota_active;
static int s_ota_end_result;
static const esp_partition_t *s_boot_partition;
static unsigned s_restart_count;

static uint32_t sha256_rotr(uint32_t value, unsigned shift)
{
    return (value >> shift) | (value << (32U - shift));
}

static void sha256_transform(sha256_ctx_t *ctx, const uint8_t block[64])
{
    uint32_t w[64];
    uint32_t a, b, c, d, e, f, g, h;

    for (size_t i = 0; i < 16U; ++i) {
        w[i] = ((uint32_t)block[i * 4U] << 24) |
            ((uint32_t)block[i * 4U + 1U] << 16) |
            ((uint32_t)block[i * 4U + 2U] << 8) |
            ((uint32_t)block[i * 4U + 3U]);
    }
    for (size_t i = 16U; i < 64U; ++i) {
        uint32_t s0 = sha256_rotr(w[i - 15U], 7U) ^ sha256_rotr(w[i - 15U], 18U) ^ (w[i - 15U] >> 3U);
        uint32_t s1 = sha256_rotr(w[i - 2U], 17U) ^ sha256_rotr(w[i - 2U], 19U) ^ (w[i - 2U] >> 10U);
        w[i] = w[i - 16U] + s0 + w[i - 7U] + s1;
    }

    a = ctx->state[0];
    b = ctx->state[1];
    c = ctx->state[2];
    d = ctx->state[3];
    e = ctx->state[4];
    f = ctx->state[5];
    g = ctx->state[6];
    h = ctx->state[7];

    for (size_t i = 0; i < 64U; ++i) {
        uint32_t s1 = sha256_rotr(e, 6U) ^ sha256_rotr(e, 11U) ^ sha256_rotr(e, 25U);
        uint32_t ch = (e & f) ^ ((~e) & g);
        uint32_t temp1 = h + s1 + ch + k_sha256_round_constants[i] + w[i];
        uint32_t s0 = sha256_rotr(a, 2U) ^ sha256_rotr(a, 13U) ^ sha256_rotr(a, 22U);
        uint32_t maj = (a & b) ^ (a & c) ^ (b & c);
        uint32_t temp2 = s0 + maj;

        h = g;
        g = f;
        f = e;
        e = d + temp1;
        d = c;
        c = b;
        b = a;
        a = temp1 + temp2;
    }

    ctx->state[0] += a;
    ctx->state[1] += b;
    ctx->state[2] += c;
    ctx->state[3] += d;
    ctx->state[4] += e;
    ctx->state[5] += f;
    ctx->state[6] += g;
    ctx->state[7] += h;
}

static void sha256_init(sha256_ctx_t *ctx)
{
    memset(ctx, 0, sizeof(*ctx));
    ctx->state[0] = 0x6a09e667U;
    ctx->state[1] = 0xbb67ae85U;
    ctx->state[2] = 0x3c6ef372U;
    ctx->state[3] = 0xa54ff53aU;
    ctx->state[4] = 0x510e527fU;
    ctx->state[5] = 0x9b05688cU;
    ctx->state[6] = 0x1f83d9abU;
    ctx->state[7] = 0x5be0cd19U;
}

static void sha256_update(sha256_ctx_t *ctx, const uint8_t *data, size_t len)
{
    if (data == NULL || len == 0U) {
        return;
    }

    ctx->bit_count += (uint64_t)len * 8ULL;
    while (len > 0U) {
        size_t copy_len = 64U - ctx->buffer_len;
        if (copy_len > len) {
            copy_len = len;
        }
        memcpy(ctx->buffer + ctx->buffer_len, data, copy_len);
        ctx->buffer_len += copy_len;
        data += copy_len;
        len -= copy_len;

        if (ctx->buffer_len == 64U) {
            sha256_transform(ctx, ctx->buffer);
            ctx->buffer_len = 0U;
        }
    }
}

static void sha256_final(sha256_ctx_t *ctx, uint8_t digest[32])
{
    uint8_t pad_byte = 0x80U;
    uint8_t zero = 0U;
    uint8_t length_bytes[8];

    for (size_t i = 0; i < 8U; ++i) {
        length_bytes[7U - i] = (uint8_t)(ctx->bit_count >> (i * 8U));
    }

    sha256_update(ctx, &pad_byte, 1U);
    while (ctx->buffer_len != 56U) {
        sha256_update(ctx, &zero, 1U);
    }
    sha256_update(ctx, length_bytes, sizeof(length_bytes));

    for (size_t i = 0; i < 8U; ++i) {
        digest[i * 4U] = (uint8_t)(ctx->state[i] >> 24U);
        digest[i * 4U + 1U] = (uint8_t)(ctx->state[i] >> 16U);
        digest[i * 4U + 2U] = (uint8_t)(ctx->state[i] >> 8U);
        digest[i * 4U + 3U] = (uint8_t)(ctx->state[i]);
    }
}

static esp_err_t ota_ensure_capacity(size_t required)
{
    uint8_t *grown;

    if (required <= s_ota_partition_capacity) {
        return ESP_OK;
    }

    grown = (uint8_t *)realloc(s_ota_partition_bytes, required);
    if (grown == NULL) {
        return ESP_ERR_NO_MEM;
    }
    memset(grown + s_ota_partition_capacity, 0, required - s_ota_partition_capacity);
    s_ota_partition_bytes = grown;
    s_ota_partition_capacity = required;
    return ESP_OK;
}

esp_err_t esp_ota_begin(const esp_partition_t *partition, size_t image_size, esp_ota_handle_t *out_handle)
{
    size_t required = 0U;

    if (partition == NULL || out_handle == NULL) {
        return ESP_ERR_INVALID_ARG;
    }

    required = (image_size == 0U || image_size == OTA_SIZE_UNKNOWN || image_size == OTA_WITH_SEQUENTIAL_WRITES) ?
        partition->size :
        image_size;
    if (required > partition->size) {
        return ESP_ERR_INVALID_SIZE;
    }
    if (ota_ensure_capacity(required) != ESP_OK) {
        return ESP_ERR_NO_MEM;
    }

    memset(s_ota_partition_bytes, 0, required);
    s_ota_image_size = image_size;
    s_ota_written_size = 0U;
    s_ota_active = true;
    *out_handle = s_ota_handle;
    return ESP_OK;
}

esp_err_t esp_ota_write(esp_ota_handle_t handle, const void *data, size_t size)
{
    if (!s_ota_active || handle != s_ota_handle || data == NULL) {
        return ESP_ERR_INVALID_ARG;
    }
    if (s_ota_written_size + size > s_ota_partition.size) {
        return ESP_ERR_INVALID_SIZE;
    }
    memcpy(s_ota_partition_bytes + s_ota_written_size, data, size);
    s_ota_written_size += size;
    return ESP_OK;
}

esp_err_t esp_ota_end(esp_ota_handle_t handle)
{
    if (!s_ota_active || handle != s_ota_handle) {
        return ESP_ERR_NOT_FOUND;
    }
    s_ota_active = false;
    return s_ota_end_result;
}

esp_err_t esp_ota_abort(esp_ota_handle_t handle)
{
    if (handle != s_ota_handle) {
        return ESP_ERR_NOT_FOUND;
    }
    s_ota_active = false;
    return ESP_OK;
}

esp_err_t esp_ota_set_boot_partition(const esp_partition_t *partition)
{
    if (partition == NULL) {
        return ESP_ERR_INVALID_ARG;
    }
    s_boot_partition = partition;
    return ESP_OK;
}

const esp_partition_t *esp_ota_get_next_update_partition(const esp_partition_t *start_from)
{
    (void)start_from;
    return &s_ota_partition;
}

esp_err_t esp_partition_read(const esp_partition_t *partition, size_t src_offset, void *dst, size_t size)
{
    if (partition == NULL || dst == NULL) {
        return ESP_ERR_INVALID_ARG;
    }
    if (src_offset + size > s_ota_written_size) {
        return ESP_ERR_INVALID_SIZE;
    }
    memcpy(dst, s_ota_partition_bytes + src_offset, size);
    return ESP_OK;
}

esp_err_t esp_partition_get_sha256(const esp_partition_t *partition, uint8_t *sha_256)
{
    sha256_ctx_t ctx;

    if (partition == NULL || sha_256 == NULL) {
        return ESP_ERR_INVALID_ARG;
    }

    sha256_init(&ctx);
    sha256_update(&ctx, s_ota_partition_bytes, s_ota_written_size);
    sha256_final(&ctx, sha_256);
    return ESP_OK;
}

esp_err_t esp_ota_get_partition_description(const esp_partition_t *partition, esp_app_desc_t *app_desc)
{
    (void)partition;
    if (app_desc == NULL) {
        return ESP_ERR_INVALID_ARG;
    }
    memset(app_desc, 0, sizeof(*app_desc));
    strncpy((char *)app_desc->version, "host-ota", sizeof(app_desc->version) - 1U);
    return ESP_OK;
}

void esp_restart(void)
{
    s_restart_count++;
}

void ble_host_test_set_ota_end_result(int rc)
{
    s_ota_end_result = rc;
}

unsigned ble_host_test_restart_count(void)
{
    return s_restart_count;
}

void ble_host_test_reset_ota_state(void)
{
    s_ota_image_size = 0U;
    s_ota_written_size = 0U;
    s_ota_active = false;
    s_ota_end_result = ESP_OK;
    s_boot_partition = NULL;
    s_restart_count = 0U;
    if (s_ota_partition_bytes != NULL && s_ota_partition_capacity > 0U) {
        memset(s_ota_partition_bytes, 0, s_ota_partition_capacity);
    }
}
