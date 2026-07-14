# P5.3 MTU Bug — Handoff Document

## Summary

The P5 firmware_update E2E test (P5.3) is blocked on a BLE MTU exchange
bug in the v0.3.0 controller firmware. The app's Android BLE stack
requests MTU 247 (the same MTU the HEAD firmware negotiates
successfully), but the v0.3.0 firmware's MTU exchange fails and the
firmware then terminates the connection. This is a **firmware-side
bug**, not a test-side bug.

The test-side fixes are complete and committed (see "Test-side status"
below). The remaining work is a firmware-side investigation and fix.

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

## Root cause hypothesis (firmware-side)

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
correctly set to 247 in v0.3.0. The issue is what happens *after*
the Android app sends the MTU Exchange Request.

Three possible failure modes to investigate (ranked by likelihood):

1. **NimBLE ATT MTU response not sent on the v0.3.0 NimBLE version
   the IDF 6.0 worktree resolves.** The HEAD worktree's NimBLE
   might have a newer point release that the v0.3.0 worktree's
   `idf.py set-target esp32s3` step doesn't trigger a re-resolution
   of. Check `firmware/esp-idf/dependencies.lock` for the resolved
   NimBLE version in the v0.3.0 worktree vs HEAD; if they differ,
   that's the most likely cause.

2. **L2CAP MTU / ACL buffer size misconfiguration.** NimBLE's
   effective ATT MTU is also bounded by
   `MYNEWT_VAL_BLE_L2CAP_MAX_SDU_MTU` and the ACL buffer pool size.
   If either is smaller than 247 in the v0.3.0 worktree's resolved
   NimBLE config, the MTU exchange might be rejected with
   `BLE_ATT_ERR_INVALID_PDU` (0x04) — but Android would report that
   as `status=4`, not `status=133`. This is less likely but worth
   checking `ble_att_svr_mtu` in the NimBLE host source for a
   "MTU too large" code path.

3. **Firmware application code terminates the connection on MTU
   event.** The v0.3.0 `BLE_GAP_EVENT_MTU` handler just logs and
   returns 0 — it doesn't call `ble_gap_terminate()`. But the
   NimBLE stack itself might be calling `ble_gap_terminate()`
   internally on a malformed MTU exchange. Check NimBLE's
   `ble_att_clt_mtu_cmd` and `ble_att_svr_mtu` for any
   "abort connection" path on MTU errors.

## Recommended investigation steps

1. **Resolve and compare the NimBLE versions.**
   - Check out the v0.3.0 tag in a worktree, run
     `idf.py set-target esp32s3` (no build), then
     `idf.py reconfigure` and read the resolved NimBLE version from
     `build/_deps/nimble-src/RELEASE_NOTES.md` or
     `idf_component_state`.
   - Do the same on HEAD.
   - If they differ, that's the root cause. Either bump v0.3.0's
     resolved NimBLE or backport the relevant fix.

2. **Enable verbose NimBLE ATT logging on the v0.3.0 firmware.**
   - In `sdkconfig.defaults`, set
     `CONFIG_BT_NIMBLE_LOG_LEVEL_DYNAMIC=y` and rebuild.
   - Or temporarily raise the NimBLE log level at runtime via
     `ble_hs_log_ctl_set(BLE_HS_LOG_CTRL_LVL, BLE_HS_LOG_DEBUG)`.
   - Re-run the test and capture the controller serial. Look for
     "MTU exchange request received" / "MTU response sent" log lines
     from NimBLE. If the response is never sent, the bug is in
     NimBLE. If the response is sent but Android doesn't see it,
     the bug is in the HCI/ACL transport.

3. **Test the MTU exchange in isolation with `gatttool` or
   `bluetoothctl`.**
   - From the Linux dev machine, pair with the v0.3.0 controller
     and try `gatttool --exchange-mtu=247` (or the
     `bluetoothctl` `mtu <handle> 247` equivalent).
   - If `gatttool` also fails the same way, the bug is confirmed on
     the firmware side independent of Android.
   - If `gatttool` succeeds, the bug is Android-specific and the
     app would need to retry the MTU exchange or fall back to 23.

4. **If the NimBLE versions differ:** the cleanest fix is to pin
   the v0.3.0 worktree to the same NimBLE version as HEAD by
   adding an explicit `idf_component.yml` dependency on the NimBLE
   version. This is the lowest-risk fix because it doesn't change
   any application code.

5. **If the NimBLE versions match but the bug persists:** the fix
   is a NimBLE-side patch. The patch needs to be backported from
   upstream NimBLE or written from scratch. The patch should
   ensure the MTU Exchange Response is always sent (even if the
   server's preferred MTU is smaller than the client's request)
   and the connection is never terminated on MTU exchange
   failure.

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
