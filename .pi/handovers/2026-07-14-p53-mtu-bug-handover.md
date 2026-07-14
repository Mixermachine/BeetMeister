# P5.3 MTU Bug — Handoff Document

## Summary (corrected — see "Correction" below)

**Original hypothesis (wrong):** the P5 firmware_update E2E test
(P5.3) is blocked on a BLE MTU exchange bug in the v0.3.0
controller firmware. The app's Android BLE stack requests MTU 247
(same MTU HEAD negotiates successfully), but v0.3.0's MTU exchange
fails and the firmware terminates the connection.

**Correction:** the v0.3.0 firmware is **not** the cause. The
proximate cause is a **phone-side BLE bond mismatch** between the
Android Bluetooth stack's stored link key (from a previous test
run with the same controller) and the controller's wiped NVS
(cleared by `flash_old`'s `erase_appcfg` precondition). Android
tries to encrypt the link with the stale key, the controller
rejects with `HCI_ERR_KEY_MISSING`, Android drops the link
(`HCI_ERR_PEER_USER` / `HCI_ERR_CONN_CAUSE_LOCAL_HOST`), and the
MTU Exchange Request is silently dropped. The test's BLE
auto-connect keeps retrying with the same stale key; the OS
pairing dialog never gets a clean window to show (it gets
generated, the link drops ~50 ms later, and the dialog is
dismissed by the stack before the test's `dismissBluetoothPairingDialog`
loop can find any of the "Pair" / "Pairing" / "OK" / "Allow"
button text). The test then times out at `gate.tapScan`
(30 s in `E2eConnectionFixture.connectOnce`).

**Fix:** test-side. The firmware_update dispatch now fires
the `clear_ble_bond` action of the app's test-only
`DebugActionActivity` (`adb shell am start -n
de.aarondietz.beetmeister/.debug.DebugActionActivity -a
de.aarondietz.beetmeister.debug.action.RUN --es action
clear_ble_bond --es mac <MAC>`) between `install_built_apks`
and the `am_instrument_firmware_update` step. The Activity
dispatches on the `action` extra; for `clear_ble_bond` it
calls `BluetoothDevice.removeBond(mac)` via reflection
(the method is `@hide @SystemApi`, not in the public SDK)
and finishes. The fresh "Just Works" pairing (firmware uses
`BLE_HS_IO_NO_INPUT_OUTPUT`) completes without an OS
dialog, the MTU exchange succeeds, and the test proceeds.

**Why a generic `DebugActionActivity` (not
`ClearBleBondActivity`):** the test orchestrator will need
more preconditions like this in the future (reset
maintenance state, force a disconnect, dump runtime config,
etc.). A single Activity with an `action` dispatch keeps
the manifest minimal (one filter, one class) and makes new
operations a one-`when`-arm change. The activity is in the
`.debug.*` package, the intent action is the namespaced
`de.aarondietz.beetmeister.debug.action.RUN`, and the
activity is exported only for the orchestrator's `am start`
to reach it.

**Why an Activity (not orchestrator-side `pm clear` / `cmd
bluetooth_manager remove-bond` / BroadcastReceiver):**

* `cmd bluetooth_manager remove-bond <MAC>` was removed from
  the Android 16 shell interface. The only remaining
  `bluetooth_manager` commands are enable/disable/enableBle/
  disableBle/wait-for-state. Confirmed on SM-A536B
  A536BXXSMGZE1 — the command returns `Unknown command:
  remove-bond` (rc=255).
* `pm clear com.android.bluetooth` returns `Success` (rc=0)
  but the bond is re-loaded from `/data/misc/bluetooth/`
  within ~1 s of the Bluetooth system app restarting. The
  WH-1000XM3 bond survives a `pm clear` and is briefly
  shown as "(No uuid)" before the UUIDs repopulate. Same
  SM-A536B device.
* `BluetoothDevice.removeBond()` is the only API that
  actually clears the persistent bond, but it is `@hide
  @SystemApi` and only available to system apps or via
  reflection.
* **Activity chosen over BroadcastReceiver** for explicit
  lifecycle: `am start` is synchronous and waits for the
  Activity to be bound to a window, so the orchestrator
  knows the action is done before the next step. A
  BroadcastReceiver can be silently dropped on devices in
  App Standby restricted buckets (Android 8+); an Activity
  launched via `am start` from the shell user is never
  dropped (shell is treated as foreground for delivery).
* The Activity is **production-safe**: it is in the
  `.debug.*` package, the intent action is the namespaced
  `de.aarondietz.beetmeister.debug.action.RUN`, and the
  Activity uses a translucent no-title theme +
  `excludeFromRecents` + `noHistory` so the user does not
  see a UI flash and the entry does not appear in the
  recents list. Worst-case misuse is a side effect of one
  of the registered actions (e.g. unpair a BLE peripheral)
  — a low-impact action the user can recover from by
  re-pairing.

## Correction

Investigation of `controller-serial.txt` (v0.3.0 boot log) +
`android-logcat.txt` (`runs/20260714-095903-firmware_update/`)
shows the firmware-side hypotheses in the original handover are
incorrect:

1. **NimBLE version mismatch (most likely, original):** ruled
   out. `git diff v0.3.0..HEAD -- firmware/esp-idf/` shows
   zero changes to `components/`, `main/`, `sdkconfig.*`, or
   `dependencies.lock` between the two tags. Both resolve NimBLE
   v1.6.0 from the same IDF v6.0.0 component.
2. **L2CAP MTU / ACL buffer misconfig (possible, original):**
   the build's `BT_NIMBLE_ACL_BUF_SIZE=255` and ATT MTU 247 fit
   comfortably in a 3-byte MTU Exchange Response, so this isn't
   the proximate cause.
3. **Firmware app code terminates on MTU (less likely, original):**
   the firmware's `BLE_GAP_EVENT_MTU` handler just logs and
   returns 0 (no `ble_gap_terminate`). The disconnect is initiated
   by the **phone** side after the encryption failure, not by the
   firmware.

The actual smoking gun is in the logcat at 11:56:38.946 (4 ms
after `onConnectionStateChange(status=0, newState=2)`):

```
I btm_acl: disconnect_acl: Disconnecting peer:xx:xx:xx:xx:0f:6a
           reason:HCI_ERR_PEER_USER
           comment:encryption_change_evt Encryption Failure
W bt_btm_sec: btm_sec_encrypt_change:
           Security Manager encryption change request
           hci_status:HCI_ERR_KEY_MISSING request:unencrypt
E bt_btm_sec: btm_sec_encryption_change_evt:
           Encryption failure 6, disconnecting 77
E bt_btm_sec: btm_sec_encrypt_change:
           xx:xx:xx:xx:0f:6a encrypt failure status 0x6
W bt_btm_sec: btm_sec_encrypt_change:
           Remote key missing - will report
```

`HCI_ERR_KEY_MISSING` (0x06) + `HCI_ERR_PEER_USER` (0x16) +
`HCI_ERR_CONN_CAUSE_LOCAL_HOST` (0x16) is the textbook Android
flow for "the remote device doesn't have the link key I was
going to use, so I'm tearing the link down before bothering
with MTU." It happens BEFORE the MTU Exchange Request
(`configureMTU() mtu: 247` is called at 11:56:38.948, 2 ms
after the disconnect starts). The MTU failure (`status=133
mtu=23`) is a **symptom** of the dropped link, not the cause.

## Symptom (from real-hardware logs)

Test run: `test-harness/runs/20260714-095903-firmware_update/`
(e2e dur 44.5 s, `tapScan` timed out at 30 s in `setUp`).

### Android logcat (`android-logcat.txt`)

```
11:56:38.942  BeetGattSession: onConnectionStateChange(status=0,    newState=2, address=3C:0F:02:D2:0F:6A)   # Connected (GATT_SUCCESS)
11:56:39.025  BeetGattSession: onMtuChanged         status=133  mtu=23                                   # MTU exchange FAILED
11:56:39.042  BeetGattSession: onConnectionStateChange(status=22,    newState=0, address=3C:0F:02:D2:0F:6A)   # Disconnected, reason=0x16
11:56:40.773  BeetGattSession: onConnectionStateChange(status=133,  newState=0, address=3C:0F:02:D2:0F:6A)   # Retry also failed
```

### Key facts from the logcat

- `onMtuChanged status=133` — Android `GATT_ERROR` (generic). Not a
  standard ATT error code (those are 0x01–0x13). 0x85 = 133 is
  Android's `GATT_ERROR` / NimBLE "operation failed" sentinel.
- `onMtuChanged mtu=23` — the negotiated MTU is the BLE spec default
  (23 bytes). The firmware never sent a valid MTU Exchange Response
  with 247.
- `onConnectionStateChange status=22` (0x16) on `STATE_DISCONNECTED`
  — HCI reason **"Connection terminated by peer user"**. The remote
  device (the controller) is the one terminating the link.

### Controller serial (`controller-serial.txt`)

The v0.3.0 controller boots and advertises normally, but there is
**no** log line for the MTU event from the firmware's `BLE_GAP_EVENT_MTU`
handler. The v0.3.0 firmware has the handler (see
`beet_ble.c:2730`), so the absence means NimBLE never delivered the
event to the application — the MTU Exchange Request was rejected or
dropped inside the NimBLE host stack.

The controller reports `mpv=1 rpv=15` and `build=dev-7827368` (its
BTMT `build_label`, which is the git commit the `v0.3.0` tag points
at — the tag's BTMT `build_label` is the commit hash, not the tag
name; see the "version.txt" note below).

### `version.txt` bug (separate, already fixed)

While investigating the MTU issue, a related read bug surfaced and
was fixed in commit `361433f` ("Fix P5 SUB #R29: version.txt
build_label from BTMT metadata"):

- **Bug:** the v0.3.0 cached `version.txt` reported
  `build_label=v0.3.0` (the tag name) when the BTMT metadata in the
  actual `.bin` contains `build_label=dev-7827368` (the commit the
  tag points at). The old `esptool image_info` fallback to
  `pinned_tag` produced the wrong value.
- **Fix:** the harness now parses the BTMT metadata block directly
  via `firmware/esp-idf/components/beet_firmware/tools/read_beet_metadata.py`
  and uses that as the source of truth for `build_label`,
  `firmware_version`, and `runtime_protocol_version`. The
  `esptool image_info` "Build version" field is used only as a
  fallback.
- **Impact:** the orchestrator now passes
  `expected_old_build_label=dev-7827368` to the Kotlin test instead
  of `v0.3.0`, matching what the controller actually reports.

This fix is independent of the MTU bug but was needed to get the
test to assert against the correct value.

## Root cause (corrected)

Both v0.3.0 and HEAD use ESP-IDF v6.0.0 (same NimBLE version). The
relevant BLE init code is essentially identical between the two:

```c
// firmware/esp-idf/components/beet_firmware/src/beet_ble.c  (v0.3.0 line 3131)
ble_hs_cfg.sm_io_cap = BLE_HS_IO_NO_INPUT_OUTPUT;
ble_hs_cfg.sm_bonding = 1;
// ... other sm_* fields ...

/*
 * MTU is FROZEN at 247. DO NOT INCREASE.
 * The device frame JSON fits within the 244-byte ATT payload budget
 * only at this MTU. Larger MTU on new firmware breaks backward
 * compatibility with older Android apps that request 247.
 */
ble_att_set_preferred_mtu(247);
```

The HEAD firmware has the same call. So the MTU *preference* is
correctly set to 247 in v0.3.0. The MTU exchange would work
correctly if the link weren't torn down 4 ms after connect by
the encryption failure on the phone side.

The actual root cause is **test-side, not firmware-side**: the
firmware_update dispatch flashes a different firmware image,
which clears the controller's NVS (and thus its stored BLE
long-term key). The phone still has a valid BLE bond record
with the old key (the OS-level bond cache survives
`adb uninstall` + reinstall of the app). The next BLE
auto-connect from the freshly-launched app triggers Android's
"encrypt with stored key" path; the controller rejects with
`HCI_ERR_KEY_MISSING`; Android drops the link with
`HCI_ERR_PEER_USER`; the MTU Exchange Request is dropped
silently. The OS pairing dialog (which would let the user
re-pair) appears too late, after the link is already down, and
the test's `dismissBluetoothPairingDialog()` (E2eConnectionFixture.kt:117)
runs against a dialog whose positive button text doesn't match
any of `["Pair", "Pairing", "OK", "Allow"]` on this Samsung
SM-A536B One UI build, so the dismiss loop never finds it and
the test times out at `gate.tapScan`.

## Fix (test-side)

The firmware_update dispatch now clears the phone-side BLE
bond as a precondition, between `install_built_apks` and the
`am_instrument_firmware_update` step. The fresh "Just Works"
pairing (`BLE_HS_IO_NO_INPUT_OUTPUT`) completes without an OS
dialog; the MTU exchange succeeds; the test proceeds.

Files changed:

- `app/app/src/main/AndroidManifest.xml` — new
  `<activity android:name=".debug.DebugActionActivity" ...>`
  with the `de.aarondietz.beetmeister.debug.action.RUN`
  intent filter. Translucent no-title theme +
  `excludeFromRecents` + `noHistory` so the user does not
  see a UI flash.
- `app/app/src/main/java/de/aarondietz/beetmeister/debug/DebugActionActivity.kt`
  — the Activity itself. Dispatches on the `--es action`
  extra to registered handlers; currently supports
  `clear_ble_bond` (requires `--es mac`). New actions are
  a new `when` arm. Production-safe (never launched by
  production code; class lives in `.debug.*`; intent
  action is namespaced under `.debug.*`; Activity uses
  translucent theme so no UI is shown to the user).
- `test-harness/harness/config.py` — new `ble_mac: str = ""`
  field on `ControllerConfig`.
- `test-harness/harness/adb.py` — new
  `Adb.clear_ble_bond_via_intent(mac, app_package)` method
  (runs the `am start` with the `clear_ble_bond` action).
- `test-harness/harness/orchestrator.py` — new
  `_do_clear_ble_bond_via_intent` action +
  `clear_ble_bond_via_intent` `DispatchStep` appended to the
  firmware_update preconditions (only when
  `controller.ble_mac` is set).
- `test-harness/config.example.toml` + `test-harness/config.toml`
  — new `ble_mac = "3C:0F:02:D2:0F:6A"` field under
  `[controller]`.

## What did NOT need changing

- The v0.3.0 firmware itself. It correctly rejects encryption
  with a missing key (this is the BLE spec's expected behavior).
- The NimBLE version. Both v0.3.0 and HEAD resolve NimBLE v1.6.0
  from the same IDF v6.0.0 component.
- The L2CAP MTU / ACL buffer config. `BT_NIMBLE_ACL_BUF_SIZE=255`
  and ATT MTU 247 fit comfortably in a 3-byte MTU Exchange
  Response.
- The firmware's `BLE_GAP_EVENT_MTU` handler. It just logs and
  returns 0 (no `ble_gap_terminate`). The disconnect originates
  from the phone side, not the firmware.
- The Kotlin test code. The existing test-side fixes
  (commits 1522582 through 05a39ff, listed below) are correct.
  No new Kotlin changes are needed; the dispatch precondition
  is sufficient.

## Test-side status (already done, for context)

## Test-side status (already done, for context)

The test-side fixes are complete and committed. The test can
reliably reach the BLE connection step on the v0.3.0 firmware and
fail with the MTU bug above. The fixes are:

- `05a39ff` SUB #R30: `postConnectVisible()` checks the
  `nav_settings_item` testTag (stable across BLE reconnects)
- `a30bf5f` SUB #R27: `hasText` matcher for `postConnectVisible()`
  (replaced fragile `onAllNodesWithText` with `useUnmergedTree`)
- `e43d6e9` SUB #R28: `assertCurrentFirmwareMatchesOldBuildLabel()`
  moved from `FirmwareUpdateRobot` to `SettingsRobot` and called
  BEFORE `openFirmwareUpdate()` in the test body
- `361433f` SUB #R29: `version.txt` `build_label` from BTMT
  metadata (matches what the controller reports)
- `fac73f4` SUB #R25 v3: OS Bluetooth pairing dialog dismissal
  via `UiDevice` with 10 s retry loop (the `uninstall` + reflash
  sequence in the firmware_update dispatch triggers the system
  pairing dialog, which blocks the app)

The BLE pairing dialog fix and the `mergeDescendants`-resilient
post-connect probe are both required because the firmware_update
dispatch does `adb uninstall` + reflash, which removes the phone-
side BLE bond and triggers the OS-level "Pair with beetmeister-01?"
dialog. The `useUnmergedTree=true` / `hasTestTag` approach was
needed because the post-connect probe runs during the BLE
auto-connect window when the merged `NavigationBarItem` semantics
are unstable.

## Files and line numbers for the firmware-side fix

- Firmware BLE init: `firmware/esp-idf/components/beet_firmware/src/beet_ble.c:3110-3140`
  - `ble_hs_cfg.*` setup
  - `ble_att_set_preferred_mtu(247)` at line 3131
- Firmware BLE gap event handler: `firmware/esp-idf/components/beet_firmware/src/beet_ble.c:2470-2520`
  - `BLE_GAP_EVENT_MTU` case (just logs, returns 0)
- App-side MTU request: `app/app/src/main/java/de/aarondietz/beetmeister/data/ble/BeetGattSessionCoordinator.kt:2250`
  - `gatt.requestMtu(DESIRED_MTU)` where `DESIRED_MTU = 247`
- App-side `onMtuChanged` handler: `BeetGattSessionCoordinator.kt:2286-2300`
  - Sets `negotiatedMtu` based on `status == GATT_SUCCESS`
  - Falls through to `discoverServices()` regardless of MTU result

## Environment / reproducer

- ESP-IDF v6.0 at `C:\esp\v6.0\esp-idf` (NimBLE via
  `components/bt/host/nimble/nimble/`)
- venv Python at `C:\Espressif\tools\python\v6.0\venv\Scripts\python.exe`
- Wrapper: `firmware/.agents/skills/esp-idf-installation/scripts/invoke-idf.ps1`
- Pinned tag: `v0.3.0` (commit `7827368`)
- Cached `.bin` at `test-harness/firmware_cache/beetmeister-v0.3.0.bin`
  (read BTMT metadata via
  `firmware/esp-idf/components/beet_firmware/tools/read_beet_metadata.py <bin>`)
- Device: SM-A536B (Android 16, serial `RZCT50CGLKA`)
- Controller: ESP32-S3 QFN56 on COM6 (FTDI USB Serial Port,
  VID:PID `0403:6001`, SER=`A5069RR4A`, MAC `3c:0f:02:d2:0f:68`)

To reproduce the MTU failure:

1. Flash the v0.3.0 firmware: `python test-harness/run.py firmware_update`
   (or manually flash
   `test-harness/firmware_cache/beetmeister-v0.3.0.bin` to the
   controller via esptool — see `test-harness/harness/firmware.py:flash_old`
   for the exact esptool invocation).
2. Launch the app on the device.
3. Watch the controller serial for any `BLE_GAP_EVENT_MTU` log line.
   None will appear (the event is not delivered to the application).
4. Watch `adb logcat` for `BeetGattSession: onMtuChanged status=133 mtu=23`
   followed by `onConnectionStateChange(status=22, newState=0, ...)`.

## Acceptance criteria for the fix

- v0.3.0 firmware responds to Android's MTU Exchange Request with a
  valid MTU Exchange Response (MTU ≥ 23, preferably 247).
- The connection does NOT terminate after the MTU exchange, even if
  the negotiated MTU is not 247.
- The P5.3 E2E test passes end-to-end on the v0.3.0 firmware with
  the existing test-side fixes (no further Kotlin changes needed).
- The fix is minimal and doesn't change the v0.3.0 BTMT
  `build_label` (which is `dev-7827368` and must remain so for the
  post-update assertion to work).

## Related context

- Plan: `.pi/plans/2026-07-12-e2e-test-harness-md.md` (P5 is the
  real-hardware validation phase)
- Latest P5 commit: `05a39ff` (SUB #R30)
- Branch: `test-harness`
- Working tree dirty: only
  `firmware/esp-idf/components/beet_firmware/include/beet_generated_metadata.h`
  (unrelated, regenerated by the build)
