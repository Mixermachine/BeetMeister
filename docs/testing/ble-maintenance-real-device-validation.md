# BLE Maintenance Real-Device Validation

## Purpose

This document is the execution checklist for Stage 8 of the BLE maintenance update plan. It is for validating the updater on a real Android phone and a real BeetMeister controller, with logs captured for later review.

## Prerequisites

- Android phone connected over USB with developer mode and USB debugging enabled
- Android phone unlocked, with the keyguard dismissed, before starting a live app/controller scenario
- `adb devices` shows exactly one target device, or the intended device serial is known
- BeetMeister controller powered and advertising nearby
- Debug firmware and Android app are built from the current worktree
- If rollback testing is planned, a deliberately broken candidate image is prepared ahead of time

## Automation Entry Point

Use:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\dev\run-android-real-device-validation.ps1
```

Useful variants:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\dev\run-android-real-device-validation.ps1 -Serial <deviceSerial>
powershell -ExecutionPolicy Bypass -File .\scripts\dev\run-android-real-device-validation.ps1 -ScenarioName bundled-success -ControllerPort COM4 -KeepLogcatRunning -KeepControllerCaptureRunning
powershell -ExecutionPolicy Bypass -File .\scripts\dev\run-android-real-device-validation.ps1 -KeepLogcatRunning
powershell -ExecutionPolicy Bypass -File .\scripts\dev\run-android-real-device-validation.ps1 -SkipInstrumentation -KeepLogcatRunning
```

What the script does:

- builds `:app:assembleDebug` and `:app:assembleDebugAndroidTest` unless `-SkipBuild` is used
- installs the app and Android test APK unless `-SkipInstall` is used
- checks that the Android device is not still sitting on the keyguard / `NotificationShade` overlay
- clears `logcat`
- starts `logcat` capture to an artifact directory
- optionally starts controller serial capture with `capture-bench-diagnostics.ps1` when `-ControllerPort` is provided
- runs the connected instrumentation class `de.aarondietz.beetmeister.ui.feature.connection.MaintenanceUpdateInstrumentationTest` unless `-SkipInstrumentation` is used
- writes `scenario-notes.md` into the artifact directory so the run has one place for pass/fail notes and exact build identifiers

Artifacts are written under `artifacts/stage8/<timestamp>/`.

Typical artifact set:

- `android-logcat.txt`
- `android-instrumentation.txt`
- `controller-serial.txt` when controller capture is enabled
- `scenario-notes.md`

## Instrumentation Scope

The connected instrumentation test currently covers the maintenance gate UI state on a real Android device:

- maintenance-required screen renders with stable controls
- bundled/custom image actions are visible
- selected custom image summary and warnings render correctly
- uploading phase shows progress and abort controls

This instrumentation does not replace the controller-in-the-loop scenarios below. It exists to prove the phone-side Stage 8 scaffolding is functioning on real Android hardware.

If the runner reports that the phone is still on the lockscreen or `NotificationShade`, unlock the phone manually and rerun the scenario. ADB can start the app process, but it cannot reliably dismiss a secure keyguard by itself.

## Manual Controller Scenarios

For each scenario:

- keep Android `logcat` capture running
- capture controller logs separately from the ESP32 monitor if available
- record pass/fail and notable timestamps in the validation notes

### 1. Bundled firmware success path

- Start from either:
  - a controller that requires the maintenance flow, or
  - a runtime-compatible controller and open `Settings -> Firmware update`
- Pair the phone and enter the maintenance update UI.
- Select the bundled firmware path and install.
- Verify upload completes, the controller reboots, and the app regains a healthy post-update state.

Expected:

- app shows progress, reboot messaging, and completion
- controller boots the new slot
- settings show the new firmware/build label
- configuration and event history remain intact

### 2. User abort during upload

- Start a maintenance update.
- Abort through the app while upload is active.

Expected:

- app surfaces an aborted/failed state clearly
- controller discards staged OTA data
- runtime restrictions clear after the session ends

### 3. Android app crash during upload

- Start a maintenance update.
- Force-stop the app from Android settings or with `adb shell am force-stop de.aarondietz.beetmeister`.
- Reopen the app and reconnect.

Expected:

- client-side upload state is not resumed blindly
- the app restarts the upload from zero
- controller-side status is consistent with the session rules

### 4. BLE disconnect followed by successful resume

- Start a maintenance update.
- Move out of range briefly or disable Bluetooth long enough to drop the link but not long enough to expire the session.
- Restore connectivity.

Expected:

- app reconnects and resumes from `next_offset`
- controller logs exactly one `update_reconnect` event for the successful resume
- update can still complete successfully

### 5. BLE disconnect followed by session expiry

- Start a maintenance update.
- Drop the BLE link for more than 15 minutes.
- Reconnect afterward.

Expected:

- controller reports `failed / update_session_expired`
- staged OTA data is discarded
- app restarts from zero instead of trying to continue
- runtime restrictions clear after expiry

### 6. Session invalidation by a newer updater

- Start an update attempt from one client.
- Begin a newer update attempt from another client before the first one resumes.
- Return to the first client.

Expected:

- older session is invalidated
- first client cannot continue from stale offset
- controller logs `update_invalidated`

### 7. Low-battery rejection

- Put the controller into a low-battery state that should reject updates.
- Attempt `begin_update`.

Expected:

- app shows a clear rejected-update message
- controller reports `update_low_battery`
- no OTA session starts

### 8. Busy or watering-active rejection

- Attempt an update while the controller is in a state that should block OTA.

Expected:

- app shows a clear rejected-update message
- controller reports the stable busy/watering failure reason

### 9. Custom image flow

- Choose a custom firmware image from the phone.
- Confirm that it is labeled as custom in the summary and later in settings.

Expected:

- manual file selection is always presented as `custom`
- warnings appear when appropriate
- install path still uses the same controller-side validation rules

### 10. Runtime protocol mismatch warning and override

- Select an image whose runtime protocol is not fully supported by the app.
- Continue after the warning.

Expected:

- app warns clearly before installation
- user can explicitly override
- controller does not reject solely for runtime-protocol mismatch

### 11. Rollback on failed first boot

- Install a candidate image designed to fail before confirmation.

Expected:

- controller boots candidate, fails startup, and rolls back to the previous confirmed slot
- previous configuration and history remain intact
- logs show the failed-first-boot path clearly enough to diagnose

## Evidence To Save

- Android `logcat`
- controller serial/monitor log
- instrumentation output file
- screenshots for user-visible warning/summary flows when helpful
- a concise scenario pass/fail record with exact firmware build labels and controller hardware revision
