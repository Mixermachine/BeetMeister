# BLE Maintenance Stage 8 Handoff

Last updated: 2026-06-18

## Current Objective

Continue Stage 8 real-device validation for BLE maintenance firmware updates.

The current task is NOT complete. The `begin_update` GATT write reaches the firmware (confirmed via test with simplified handler), but the maintenance status indication is not reliably delivered to the Android app, causing the update flow to fail.

## Current State (2026-06-18 Session Summary)

### What we proved

1. **200-byte BEGIN_UPDATE WORKS**: Reducing JSON from 275 to 200 bytes via short field names (`fv`, `bl`, `sz`, `sh`, `pi`, `hr`, `ai`, `ik` + data wrapper `"d"`) produces a write that fits in a single ATT packet. The simplified handler (no session setup, no OTA) confirms: indication received at 66ms, write callback status=0 at 70ms.

2. **Full session setup BREAKS the write**: Adding `beet_ble_clear_maintenance_session()` + `s_ble.maintenance_session.active = true` causes the BEGIN_UPDATE write callback to return status=133 after 10s (GATT_ERROR). The exact mechanism is still unclear — no `esp_ota_begin()` or `esp_ota_abort()` is called, yet the NimBLE host task appears unable to send the Write Response + indication.

3. **GATT write serialization bug fixed**: `sendMaintenanceControl` now properly synchronizes on both the indication AND the write callback before returning, preventing "Could not send command over BLE" errors when QUERY_STATUS and BEGIN_UPDATE are sent back-to-back.

### Changes applied (uncommitted)

#### Firmware (`beet_ble.c`)
- BEGIN_UPDATE handler: currently has session setup WITHOUT events and WITHOUT OTA begin (lazy OTA begin in `beet_ble_write_maintenance_data` on first chunk)
- Lazy OTA begin: `beet_ble_write_maintenance_data()` now calls `esp_ota_begin()` inline when `ota_handle_active` is false on first data chunk (checks `active` only, not `ota_handle_active`)
- Removed deferred OTA begin code from `beet_ble_service_maintenance_session()` entirely
- `beet_ble_service()` reordered: maintenance session processed AFTER state streaming

#### Firmware (`beet_ble_codec.c`)
- Short field names accepted: both `"firmware_version"` and `"fv"`, `"data"` and `"d"`, etc. (backward compatible)

#### Android (`BeetJsonCodec.kt`)
- `maintenanceBeginUpdate` emits short field names: `"d"` instead of `"data"`, `"fv"` instead of `"firmware_version"`, etc.

#### Android (`BeetGattSessionCoordinator.kt`)
- `sendMaintenanceControl`: now uses two deferred objects — `writeDeferred` (completed by `onCharacteristicWrite`) and `statusDeferred` (completed by `onCharacteristicChanged`). `withTimeout` wraps awaiting BOTH.
- `onCharacteristicWrite` for `maintenanceControl` UUID now completes `pendingCharacteristicWrite` (previously only logged)

### Root Cause Theory

`beet_ble_clear_maintenance_session()` — when `ota_handle_active` is true (leftover from prior session on the same boot cycle) — calls `esp_ota_abort()` which may trigger flash operations, blocking the NimBLE host task via shared SPI flash bus on ESP32-S3. Even when `ota_handle_active` is false, the `memset(&s_ble.maintenance_session, 0, ...)` zeros the entire session state, which might interact poorly with the NimBLE callback context.

### Remaining Work

1. **Determine why clear_maintenance_session breaks the write**: Isolate whether it's the `esp_ota_abort()` call, the `memset`, or some interaction with the BLE stack's connection state. Try calling `clear_maintenance_session` conditionally (only when there's an active OTA handle), or defer it.

2. **Test the lazy OTA begin path**: The BEGIN_UPDATE handler currently works with session setup but NO events. The first data chunk write should trigger `esp_ota_begin()` in the NimBLE callback, which will likely cause a disconnect again. The Android resume logic needs to be validated.

3. **Consider moving esp_ota_begin to a separate FreeRTOS task**: The fundamental issue is that `esp_ota_begin()` (flash erase) blocks both CPU cores on ESP32-S3 via the shared SPI flash bus. A dedicated task or IRAM-resident NimBLE code may be required.

4. **Update host tests**: `beet_ble_codec.c` parser changes need test coverage for short field names; `beet_ble.c` data handler needs tests for lazy OTA begin.

## Current Hardware And Environment

- Repository: `C:\git\BeetMeister`
- Android device ADB serial: `RZCY51LB7BD`
- Controller serial port: `COM4`
- Android version: 16 (API 36)
- Android package: `de.aarondietz.beetmeister`
- Current shell: PowerShell

## Summary of Changes Made (all uncommitted)

### Firmware (`firmware/esp-idf/components/beet_firmware/src/beet_ble.c`)

1. **Security**: Changed GATT characteristic flags from `_AUTHEN` to `_ENC` (encryption instead of authentication) for runtime service characteristics, then removed `_ENC` from maintenance characteristics entirely (the access callback still checks encryption via `beet_ble_require_encrypted`).
2. **MTU**: Changed `CONFIG_BT_NIMBLE_ATT_PREFERRED_MTU` from 256 to 512 in `sdkconfig`.
3. **JSON buffer**: Increased `BEET_BLE_MAINTENANCE_CONTROL_JSON_MAX_LEN` from 320 to 512.
4. **Connection params**: Added `ble_gap_update_params()` call on connect with supervision_timeout=1000 (10s), itvl_min=15, itvl_max=30.
5. **Deferred OTA begin**: `esp_ota_begin()` blocks the NimBLE host task for ~5s (flash erase). Moved it to a deferred flag processed in `beet_ble_service_maintenance_session()` (called from controller task). Added `deferred_ota_begin_pending` field to `beet_ble_maintenance_session_state_t`.
6. **Connection update logging**: Added graceful handler for `BLE_GAP_EVENT_CONN_UPDATE_REQ`/`BLE_GAP_EVENT_CONN_UPDATE`.
7. **Removed unused preferred conn params init code** (API not available in ESP-IDF v6.0 NimBLE).

### App (`app/app/src/main/java/de/aarondietz/beetmeister/data/ble/BeetGattSessionCoordinator.kt`)

1. **GATT stability**: Added `lastGattResetAt` tracking and `MAINTENANCE_GATT_STABILITY_MS=600` to prevent starting maintenance during GATT setup.
2. **MTU**: Changed `DESIRED_MTU` from 247 to 512.
3. **Write error handling**: REMOVED the `onCharacteristicWrite` error handler for maintenance control (was prematurely failing the deferred on status 133). Now only logs the status.
4. **QUERY_STATUS first**: Changed `startOrResumeMaintenanceSession` to always send QUERY_STATUS before BEGIN_UPDATE (instead of assuming "idle").

### App (`app/app/src/main/java/de/aarondietz/beetmeister/data/protocol/BeetJsonCodec.kt`)

1. **Reduced JSON size**: Dropped `runtime_protocol_version` (optional field) and shortened `asset_id` to `"fw.bin"` to reduce JSON from 331 to ~275 bytes.

### App (`app/app/src/main/res/values/strings.xml`)

1. Added `maintenance_control_write_failed` string resource.

### Firmware tests (`firmware/tests/host/support/`)

1. Updated test shim (`ble_test_shim.h`, `ble_test_stubs.c`) for `_ENC` flag values, `encrypted` field, and `BLE_ATT_ERR_INSUFFICIENT_ENC`.

### Build config (`firmware/esp-idf/sdkconfig`)

1. Changed `CONFIG_BT_NIMBLE_ATT_PREFERRED_MTU` and `CONFIG_NIMBLE_ATT_PREFERRED_MTU` from 256 to 512.

## Root Cause Analysis

The `begin_update` JSON (~275 bytes) write to the `maintenance_control` characteristic fails in a specific way:

1. **QUERY_STATUS** (21 bytes): Works. Write reaches firmware, status indication received by app.
2. **BEGIN_UPDATE** (275 bytes): Write reaches firmware (confirmed via simplified handler test), but **status indication is NOT received** by the Android app.
3. **Android GATT callback**: `onCharacteristicWrite` fires with status 133 (`GATT_ERROR`) after ~5 seconds, even though the write WAS processed by the firmware.
4. **Controller disconnect**: After the GATT error, the controller disconnects (status 8 = peer user termination).

With `WRITE_TYPE_NO_RESPONSE` (Write Command), the write callback fires immediately with status 0, but the status indication is still NOT received, and the controller still disconnects after ~5 seconds.

The firmware's `beet_ble_send_maintenance_status_indication()` returns ESP_OK (indication queued successfully), but the indication never reaches the Android app.

The deferred OTA begin (moving `esp_ota_begin()` out of the NimBLE callback) was implemented but not yet verified to work end-to-end because the status indication delivery issue blocks the flow before data upload begins.

## Remaining Work

The status indication delivery after BEGIN_UPDATE is the primary blocker. Possible investigation directions:

- Check if NimBLE GATT server's indication queue is stalled after processing a large write
- Try using `ble_gatts_indicate_custom()` with a small delay
- Investigate if ATT Indication confirmation is being lost due to Android BLE stack behavior on API 36
- Consider splitting the `begin_update` JSON into multiple small writes (using `cmd_chunk` pattern like `command_result`) to avoid the large-write issue entirely
- Try sending the status indication from a NimBLE callout/event rather than inline in the access callback

## Verification Evidence

- QUERY_STATUS (21 bytes): consistently works, status indication received
- BEGIN_UPDATE with simplified handler (no OTA): WORKS - status indication received, state update confirmed
- BEGIN_UPDATE with full handler (deferred OTA begin): status indication NOT received, times out after 10s
- The firmware does NOT crash during the attempt; it continues running normally after disconnect

## Artifact Policy

Generated Stage 8 artifacts under `artifacts/stage8/` are evidence and scratch output. Do not add them to git unless a specific artifact is intentionally selected for documentation or review.

This handoff file is a persistent repo document and should be tracked.
