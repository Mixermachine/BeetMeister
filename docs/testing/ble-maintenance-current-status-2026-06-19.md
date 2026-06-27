# BLE Maintenance OTA Current Status - 2026-06-19

## Purpose

This file records the current implementation state for the Bluetooth firmware update work so another agent can continue without relying on conversation history.

The active goal is not complete yet. The implementation must still be tested on the real Android phone and controller until the full update flow is proven to work end to end.

## Current Task

Continue validating the BLE maintenance firmware update implementation on real hardware.

The immediate next task is to retest the real Android phone and ESP32-S3 controller after the latest firmware flash. The last completed hardware action was a successful direct flash to `COM4`; the live Android/controller stability and OTA flow were not retested after that flash yet.

Known hardware/tools:

- Android device: `RZCY51LB7BD`
- Controller serial port: `COM4`
- Firmware project: `firmware/esp-idf`
- Android project: `app`

## What Was Fixed

### Host-test stack corruption

Fixed `Runtime Check Failure #2 - Stack around the variable "image" was corrupted` in `firmware/tests/host/host_ble_transport_tests.c`.

Root cause:

- The test still allocated `uint8_t image[102]`.
- The generated metadata block had grown to `106` bytes.
- The test copied metadata at `image + 16`, requiring `122` bytes.
- The maintenance chunk buffer was also stale and too small for `8 + image_size`.

Fix:

- Updated the test image buffers to `16U + sizeof(g_beet_generated_metadata_block)`.
- Updated chunk buffers to `8U + 16U + sizeof(g_beet_generated_metadata_block)`.
- Updated the staged OTA test `begin_update` image size to `122`.
- Updated the staged OTA test SHA-256 to match the current generated test image.
- Updated staged OTA metadata fields from stale `dev` to current `bd88ef0-dirty`.

### Host-test metadata expectations

Fixed `firmware/tests/host/host_tests.c` so maintenance metadata tests match the generated metadata currently in `firmware/esp-idf/components/beet_firmware/include/beet_generated_metadata.h`.

Current expected firmware/build label in host tests:

- `bd88ef0-dirty`

### State-stream notification churn

A live Android/controller test showed the BLE link repeatedly disconnecting after about 30 seconds with Android/Bluetooth stack reason `8`, consistent with BLE connection timeout.

Before the firmware stream change:

- App-side automatic backlog sync was disabled, which removed the repeated `command_result` indication flood.
- The connection still timed out after about 30 seconds.
- Serial logs then showed the controller still publishing dense state-stream notifications on `att_handle=18`.

Firmware changes in `firmware/esp-idf/components/beet_firmware/src/beet_ble.c` reduce unnecessary runtime state notifications:

- Battery voltage jitter below `10 mV` is treated as unchanged.
- Sensor voltage jitter below `25 mV` is treated as unchanged.
- `next_check_in_s` is bucketed by `60` seconds for change detection.

The intent is to avoid publishing a new state frame for tiny ADC jitter or second-by-second countdown movement.

### State-stream host-test coverage

Added host regression coverage in `firmware/tests/host/host_ble_transport_tests.c`:

- `runtime_state_stream_suppresses_minor_jitter`
- `runtime_state_stream_notifies_meaningful_changes`

Added host-test support for state-stream subscription:

- `beet_ble_host_test_set_state_stream_subscription`
- Declaration in `firmware/tests/host/support/beet_ble_host_test.h`
- Implementation in `firmware/esp-idf/components/beet_firmware/src/beet_ble.c` under host-test code

### App-side runtime sync throttling around maintenance

In `app/app/src/main/java/de/aarondietz/beetmeister/data/ble/BeetGattSessionCoordinator.kt`, automatic background event sync was stopped from running immediately after initial connection and after maintenance cleanup.

Reason:

- The automatic post-connect history/event backlog sync created heavy `command_result` indication traffic.
- That traffic made BLE diagnosis harder and was not required for connecting or running OTA.
- Manual history/event refresh paths remain available through existing explicit calls.

The app still refreshes core runtime state after initial sync:

- valve config
- watering interval
- live state stream

### OTA/session fixes already present in the current worktree

The current worktree contains earlier fixes from this stage of work, including:

- deferred maintenance status indication handling from the NimBLE callback to the controller task
- GATT operation serialization in the app for write plus indication completion
- shorter maintenance JSON fields to avoid Android prepare-write fragmentation
- app-side detection when the selected firmware is already installed
- maintenance wake lock behavior
- reconnect/state handling around maintenance reboot
- user abort handling improvements
- bundled firmware asset and catalog updates

These were not all authored in the last mini-session, but they are part of the current uncommitted implementation state.

## Verified Evidence

### Host tests

Command:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/dev/run-firmware-host-tests.ps1
```

Result:

- Passed.
- `beet_host_tests` passed.
- `beet_ble_transport_host_tests` passed.
- The previous stack corruption no longer occurs.

### Firmware build

Command:

```powershell
powershell -ExecutionPolicy Bypass -File ..\..\.agents\skills\esp-idf-installation\scripts\invoke-idf.ps1 build
```

Workdir:

```text
firmware/esp-idf
```

Result:

- Passed.
- Generated `build/beetmeister.bin`.
- Binary size reported as approximately `0xb0290` bytes.
- Smallest app partition has about `89%` free.

### Firmware direct flash

Command:

```powershell
powershell -ExecutionPolicy Bypass -File ..\..\.agents\skills\esp-idf-installation\scripts\invoke-idf.ps1 -p COM4 flash
```

Workdir:

```text
firmware/esp-idf
```

Result:

- Passed.
- Controller detected as ESP32-S3 on `COM4`.
- Bootloader, partition table, OTA data, and app image were written.
- Written data hashes were verified.
- Device hard reset completed.

Important note:

- A previous flash attempt failed because `COM4` was busy due to a concurrent serial reader. Do not run `serial_reader.py` while flashing.

### Android app build and install before the latest firmware flash

Command:

```powershell
.\gradlew :app:assembleDebug
```

Workdir:

```text
app
```

Result:

- Passed.

Command:

```powershell
adb -s RZCY51LB7BD install -r app\app\build\outputs\apk\debug\app-debug.apk
```

Result:

- Passed.

This APK was installed before the latest direct controller flash. Reinstall only if app-side files change again.

## Current Implementation State

### Firmware

The maintenance service exists and current host tests cover:

- maintenance info read behavior
- query status behavior
- begin update behavior
- low battery rejection
- second begin update invalidating an existing session
- finish update failure when upload is incomplete
- runtime state stream suppression during maintenance
- maintenance session expiry after disconnect
- maintenance resume before expiry
- data write authentication checks
- upload and finish leading to reboot status
- reboot fallback without indication confirmation
- data offset mismatch rejection
- runtime state-stream jitter suppression
- runtime state-stream meaningful-change notification

The latest firmware image is flashed directly to the controller on `COM4`.

### Android app

The app contains the current BLE maintenance flow and had built/installed successfully before the latest firmware flash.

Current app behavior intentionally avoids automatic background event backlog sync immediately after connection. This was done to reduce BLE traffic during connection and maintenance validation.

The app still needs live verification after the latest controller flash.

### Documentation

The normative plan remains `docs/planning/ble-maintenance-update-plan.md`.

This file is a status/handoff document for the current implementation and verification state.

## Known Open Issues / Unknowns

### Live BLE stability after latest firmware flash is unverified

Before the latest firmware state-stream throttling was flashed, real hardware still disconnected after about 30 seconds with Android/Bluetooth reason `8`.

The controller has now been flashed with the throttling change, but the real Android/controller stability test has not been repeated after that flash.

Required evidence:

- Android app connects.
- Initial sync completes.
- BLE link remains connected for at least 60-90 seconds.
- No `onConnectionStateChange(status=8, newState=0)` appears in app logcat.
- Serial capture shows reduced state-stream notification churn.

### Full OTA update flow after latest fixes is unverified

The full bundled firmware update must still be rerun from the Android app after the latest firmware flash.

Required evidence:

- bundled firmware can be selected
- summary screen appears
- update starts
- upload reaches 100%
- controller enters rebooting state
- app handles expected reboot disconnect
- app reconnects without restarting the upload
- app reports installed/current firmware correctly
- selected already-installed firmware does not trigger a second upload loop

### Post-update reconnect/listing behavior needs confirmation

The user previously observed:

- firmware uploaded to 100%
- controller rebooted successfully
- app started uploading again
- later UI showed `BeetMeister GATT service is incomplete. Searching again`
- controller listing disappeared

The current app/session changes are intended to address that state problem, but this exact scenario still needs to be retested on real hardware.

### Event/history sync behavior after disabling automatic sync

Automatic event backlog sync was removed from post-connect and post-maintenance paths.

Manual event/history refresh should still work, but should be tested after OTA stability is proven.

Potential follow-up:

- reintroduce automatic sync later with a slower, explicitly rate-limited scheduler if product behavior requires it
- keep it manual if the app UX is acceptable

## Next Steps

1. Confirm the controller boots the newly flashed firmware.
2. Start the Android app on `RZCY51LB7BD`.
3. Connect to the controller.
4. Capture app logcat and controller serial for at least 90 seconds.
5. Verify whether the reason `8` disconnect is gone.
6. If stable, run the bundled firmware update from the app.
7. Capture the full OTA flow through reboot and reconnect.
8. Confirm the app does not restart upload after successful reboot.
9. Confirm already-installed firmware is labeled as installed and does not start a new transfer.
10. Run Android JVM tests after any app-side follow-up changes.
11. Update this file and the normative plan/spec docs if behavior or contracts change.

Recommended commands:

```powershell
adb -s RZCY51LB7BD shell am force-stop de.aarondietz.beetmeister
adb -s RZCY51LB7BD shell am start -n de.aarondietz.beetmeister/.MainActivity
adb -s RZCY51LB7BD logcat -c
python artifacts/stage8/serial_reader.py COM4 --max-seconds 90
adb -s RZCY51LB7BD logcat -d -s BeetGattSession:D BeetRepository:D BeetMaintenanceFlow:D MaintenanceUpdatePanel:D AndroidRuntime:E *:S
```

Do not run `serial_reader.py` while flashing because it holds `COM4`.

## Future Tasks Before Full Goal Is Achieved

### Required before completion

- Prove stable BLE connection on real phone/controller after the latest firmware flash.
- Prove full bundled OTA update from the Android app on real hardware.
- Prove expected reboot disconnect and reconnect behavior.
- Prove the app does not start a second upload after successful update.
- Prove user abort behavior on real hardware.
- Prove Bluetooth disconnect during update is handled and can recover or report correctly.
- Prove app crash/restart during update is handled as designed.
- Prove maintenance session expiry behavior on real hardware.
- Re-run firmware host tests after final firmware changes.
- Re-run Android unit tests after final app changes.
- Build final firmware and Android app artifacts.

### Important but secondary

- Decide whether automatic event/history sync should remain manual or return later with stronger throttling.
- Consider reducing normal state-stream frequency further if the phone still times out.
- Consider logging state-stream send counts or BLE notification failures at debug level for easier diagnosis.
- Clean or ignore temporary `artifacts/stage8` files before commit.
- Review docs/specs for any contract changes introduced by implementation.

## Current Risk Assessment

The host-side behavior is now in good shape, and the stack corruption is fixed.

The main remaining risk is live BLE behavior on the phone/controller. The previous failure was a real lower-level BLE timeout, not just an app UI issue. The latest firmware throttling directly targets the remaining observed traffic pattern, but it is only proven by host tests and a successful flash so far. It still needs the live 90-second stability check and full OTA rerun.

Do not mark the overall BLE OTA goal complete until the real-device scenarios above pass.
