# Restore BLE Passkey Authentication

## Status: Planned (not in v1)

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

This switched bonding from Passkey Display to Just Works (no PIN, silent pairing). Any phone in BLE range can now pair without any user confirmation.

## Needed Changes

To restore passkey authentication:

### Firmware (`beet_ble.c`)
```c
// Restore:
ble_hs_cfg.sm_io_cap = BLE_SM_IO_CAP_DISP_ONLY;
ble_hs_cfg.sm_mitm = 1;
// sm_sc already = 1, sm_bonding already = 1
```

### Firmware (`beet_board.c`)
- Ensure `beet_board_show_pairing_code()` displays the 6-digit passkey on OLED
- Ensure display is powered on during pairing (already handled by `beet_controller_set_display_power()`)

### Android App
- No app changes needed — Android OS handles the passkey entry dialog automatically during pairing
- But app should handle `BOND_NONE` after passkey rejection gracefully (already handled by `BeetScanBondCoordinator`)

### Testing
- Verify passkey appears on OLED within pairing timeout (30s)
- Verify passkey entry on phone completes bonding
- Verify wrong passkey entry rejects bonding
- Verify reconnection without re-pairing still works for bonded phones

### Dependencies
- OLED display must be connected and functional
- Future: physical button on GPIO13 for bond admission (separate feature)
