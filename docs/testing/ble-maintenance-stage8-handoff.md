# BLE Maintenance Stage 8 Handoff

Last updated: 2026-06-16

## Current Objective

Continue Stage 8 real-device validation for BLE maintenance firmware updates.

The current task is not complete. Local builds and unit tests pass, but the bundled firmware install flow still needs a verified real-device run against the connected controller and Android phone.

## Current Hardware And Environment

- Repository: `C:\git\BeetMeister`
- Android device ADB serial: `RZCY51LB7BD`
- Controller serial port: likely `COM4`
- Android package: `de.aarondietz.beetmeister`
- Current shell: PowerShell
- The phone must be unlocked before UI automation or the Stage 8 runner can interact with the app.

## Code Changes In Progress

These files currently contain Stage 8 changes:

- `app/app/src/main/java/de/aarondietz/beetmeister/data/ble/BeetGattSessionCoordinator.kt`
- `scripts/dev/run-android-real-device-validation.ps1`

Current app-side changes:

- `waitForMaintenanceConnection()` now accepts an already connected settings session, not only `MaintenanceRequired`, so updates can start from the connected Settings screen.
- Diagnostic logs were added around `startMaintenanceUpdate`, coroutine/job state, `runMaintenanceUpdate`, session start/resume, control commands, and data upload.
- `kotlinx.coroutines.job` was imported for temporary job-state diagnostics.

Current script changes:

- `run-android-real-device-validation.ps1` grants `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` after installing the app.
- The script preflight now fails clearly if the Android device is locked.

The diagnostic logs should be reduced once the real-device failure point is understood.

## Verification Already Run

These commands passed during the current Stage 8 work:

```powershell
cd C:\git\BeetMeister\app
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest
```

The debug APK was installed successfully:

```powershell
adb -s RZCY51LB7BD install -r C:\git\BeetMeister\app\app\build\outputs\apk\debug\app-debug.apk
```

Existing warning: the Android build still reports deprecated Bluetooth API warnings. These were pre-existing/expected for this work.

## Real-Device Findings So Far

The app can launch on the Android phone and connect to the controller. UI dumps showed:

- `Connected controller`
- `Live BLE session`
- live battery/state data
- Settings tab reachable
- firmware update panel visible

One earlier bundled-install attempt reached the install click. Evidence is in:

```text
artifacts/stage8/bundled-install-attempt-logcat.txt
```

That log showed:

- `MaintenanceUpdatePanel: Install button clicked phase=Ready selected=dev/Bundled`
- `BeetAppViewModel: startMaintenanceUpdate()`
- `BeetRepository: startMaintenanceUpdate(selected=dev/Bundled, phase=Ready)`
- `BeetGattSession: startMaintenanceUpdate package=Bundled firmware=dev imageKind=bundled size=718784`

It did not show the deeper `runMaintenanceUpdate`, `sendMaintenanceControl`, or upload logs because those were added afterward.

After the deeper instrumentation was built and installed, the final scripted UI sequence was too fast and did not select bundled firmware before trying to install. The latest useful UI state was:

```text
artifacts/stage8/window_dump_settings6.xml
```

That dump showed the app connected and on Settings, with:

- `Use bundled firmware` visible
- `Choose custom image` visible
- `Install firmware` visible but disabled

## Immediate Next Steps

Use slow, verified ADB/UI steps rather than a fast fixed tap sequence.

1. Confirm the phone is unlocked.
2. Launch the app.
3. Confirm the app is connected to the controller.
4. Navigate to Settings if needed.
5. Tap `Use bundled firmware`.
6. Dump the UI and verify that the bundled firmware is selected and `Install firmware` is enabled.
7. Clear logcat.
8. Tap `Install firmware`.
9. Capture focused logcat for the firmware update tags.
10. Determine whether the update reaches `runMaintenanceUpdate`, sends `begin_update`, starts data upload, or fails earlier.

Useful commands:

```powershell
adb -s RZCY51LB7BD shell monkey -p de.aarondietz.beetmeister -c android.intent.category.LAUNCHER 1
adb -s RZCY51LB7BD shell uiautomator dump /sdcard/window_dump.xml
adb -s RZCY51LB7BD pull /sdcard/window_dump.xml C:\git\BeetMeister\artifacts\stage8\window_dump.xml
adb -s RZCY51LB7BD logcat -c
adb -s RZCY51LB7BD logcat -d -s MaintenanceUpdatePanel BeetAppViewModel BeetRepository BeetGattSession
```

Known approximate tap coordinates from previous dumps:

- Settings tab center: around `(954, 2090)`
- `Use bundled firmware`: around `(353, 1156)` or `(353, 1539)`, depending on scroll position
- `Install firmware`: around `(297, 1690)` or `(303, 1540)`, depending on scroll position

Prefer verifying the UI dump between taps instead of relying on these coordinates blindly.

## Stage 8 Runner

The runner is available and has been hardened, but the real install flow may still need manual ADB/UI interaction until the scenario is stable:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\dev\run-android-real-device-validation.ps1 -Serial RZCY51LB7BD -ControllerPort COM4 -ScenarioName bundled-success
```

Expected behavior:

- It grants BLE permissions after install.
- It fails early if the phone is locked.
- It writes evidence under `artifacts/stage8/...`.

Do not treat runner startup or local test success as proof of OTA success. The real controller update path still needs to complete and be observed.

## Investigation Branches

Use the new app logs to split the next diagnosis:

- If `scopeActive=false` or the update job is already cancelled, inspect repository/ViewModel lifecycle and `BeetRepository.close()`.
- If `runMaintenanceUpdate start` appears but `begin_update` is not sent, inspect the maintenance session transition.
- If `begin_update` is sent and times out, inspect firmware maintenance control/status indication handling.
- If upload starts and stalls, inspect `writeMaintenanceChunk`, GATT `onCharacteristicWrite`, and controller-side chunk handling.
- If the controller returns failure, map and surface the exact `failure_reason`.

## Artifact Policy

Generated Stage 8 artifacts under `artifacts/stage8/` are evidence and scratch output. Do not add them to git unless a specific artifact is intentionally selected for documentation or review.

This handoff file is a persistent repo document and should be tracked.

