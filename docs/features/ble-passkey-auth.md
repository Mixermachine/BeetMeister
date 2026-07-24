# Restore BLE Passkey Authentication

## Status: Implemented (v1)

## Background

The original BeetMeister firmware used **Passkey Display** for BLE bonding:
- `BLE_SM_IO_CAP_DISP_ONLY` — peripheral has a display
- `sm_mitm = 1` — MITM protection required
- `sm_sc = 1` — LE Secure Connections

This caused the controller to display a 6-digit random passkey on the OLED, and the user had to enter it on their phone to complete bonding. This provided a basic level of access control — only someone who could see the controller's display could pair.

## Change History

Commit `a7f4e36` ("BLE Firmware Update WIP", 2026-06-16) changed the SM configuration to:
- `BLE_HS_IO_NO_INPUT_OUTPUT` — no display, no keyboard
- `sm_mitm = 0` — no MITM protection

This switched bonding from Passkey Display to Just Works (no PIN, silent pairing). Any phone in BLE range could pair without any user confirmation.

## Implemented Changes

Passkey authentication was reinstated in July 2026:

### Firmware (`beet_ble.c`)
- Restored Security Manager config:
  ```c
  ble_hs_cfg.sm_io_cap = BLE_SM_IO_CAP_DISP_ONLY;
  ble_hs_cfg.sm_mitm = 1;
  ```
- Added GATT-layer encryption flags (`BLE_GATT_CHR_F_WRITE_ENC` / `BLE_GATT_CHR_F_READ_ENC`) to maintenance service characteristics (`maintenance_control`, `maintenance_status`, `maintenance_data`), matching the runtime service pattern guarded by `#if BEET_BLE_FORCE_ENABLE_DIAGNOSTICS`.
- `maintenance_info` remains unencrypted (`BLE_GATT_CHR_F_READ`) so clients can read maintenance capability details prior to bonding.
- Added compile-time `#ifdef BEET_BLE_TEST_PASSKEY` support in `BLE_GAP_EVENT_PASSKEY_ACTION` handler to allow deterministic passkeys in automated test environments while production builds generate random 6-digit passkeys via `esp_random()`.

### Security & Encryption Flag Rationale
- Standard bonding requirement: With `sm_mitm = 1`, every newly created bond requires Passkey Display authentication.
- GATT `_ENC` vs `_AUTHEN`: GATT characteristics use `_ENC` (encryption required). Because `sm_mitm = 1` forces authenticated pairing during bond creation, an encrypted link is inherently authenticated. `_ENC` flags enforce GATT-level encryption without redundant `_AUTHEN` constraints.

### Firmware (`beet_board.c`)
- `beet_board_show_pairing_code()` displays the 6-digit passkey on OLED.
- Display power is maintained during pairing window (30s timeout).

### Android App & E2E Testing
- Android OS presents standard system passkey entry prompt during pairing.
- Automated tests (`E2eConnectionFixture.kt` and `test-harness/`) use builds with `BEET_BLE_TEST_PASSKEY=123456` to automate PIN entry.

