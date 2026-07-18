# BeetMeister E2E Test Harness

Cross-platform (Windows / Linux / Mac) orchestrator for the
Kotlin robot-pattern instrumentation tests under
`app/app/src/androidTest/java/de/aarondietz/beetmeister/e2e/`.

Status: **Phase 6 — complete. All three suites pass green on Windows.**

> Full plan: `.pi/plans/2026-07-12-e2e-test-harness-md.md`.

## What lives here

- `harness/` — Python orchestrator (9 modules: adb, capture, config,
  controller_reset, firmware, logging_setup, orchestrator, run_folder,
  screenshots).
- `config.example.toml` — schema; copy to `config.toml` and edit.
- `requirements.txt` — `pyserial`, `pytest`, `tomli` (3.10 fallback).
- `runs/` — per-run evidence folders (git-ignored).
- `firmware_cache/` — cached `v0.3.0` build (git-ignored).
- `run.py` — `python run.py <suite>` entry point.
- `conftest.py` + `test_fresh_install.py` + `test_firmware_update.py` +
  `test_settings.py` — pytest entry points.

## Install

```bash
# From the repo root:
python -m pip install -r test-harness/requirements.txt
```

Dependencies: `pyserial`, `pytest`, `tomli` (for Python 3.10 `tomllib` fallback).

## Config

```bash
cp test-harness/config.example.toml test-harness/config.toml
```

Edit `config.toml` with absolute paths for your machine:

| Key | Required for | Description |
|-----|-------------|-------------|
| `[device].adb_serial` | All suites | First device auto-picked if empty |
| `[device].app_dir` | All suites | Path to `app/` with `gradlew` |
| `[device].gradlew` | All suites | Absolute path to Gradle wrapper |
| `[controller].serial_port` | firmware_update | `COM4` (Win), `/dev/ttyUSB0` (Linux) |
| `[controller].ble_mac` | firmware_update | MAC for bond clearing, `AA:BB:CC:DD:EE:FF` |
| `[env].esptool_exe` | fresh_install, firmware_update | Absolute path to `esptool.py` in ESP-IDF |
| `[env].idf_py_exe` | firmware_update only | Absolute path to `idf.py` in ESP-IDF |
| `[env].idf_python_exe` | fresh_install, firmware_update | ESP-IDF venv Python (has `cryptography`, etc.) |
| `[env].idf_env_script` | Optional | `export.bat`/`export.sh` for sourcing the IDF env |

The orchestrator does **not** activate the ESP-IDF environment itself.
Point `[env].idf_python_exe` at the venv Python inside your IDF install
(e.g. `C:/Espressif/tools/python/v6.0/venv/Scripts/python.exe` on Windows,
`/opt/esp/python_env/idf6.0_py3.13_env/bin/python` on Linux).

## Running suites

All three suites run **one `am instrument` call** per `run.py` invocation:

```bash
python test-harness/run.py fresh_install       # ~20 s (2 tests)
python test-harness/run.py firmware_update     # ~340 s (1 test, real OTA)
python test-harness/run.py settings_update     # ~42 s (7 tests)
```

Each suite exits 0 on pass, non-zero on failure. Console output is
sparse (INFO level); full DEBUG logs are in the run directory's
`harness-<ts>.log`.

### What each suite does

| Suite | Preconditions | Test |
|-------|--------------|------|
| `fresh_install` | Uninstall app → esptool erase appcfg+events+sysevents → install APK | Scan → connect → assert 8 pair rows + controller info (device_id, firmware, protocol) |
| `firmware_update` | Flash v0.3.0 → uninstall → install APK → clear BLE bond | Connect → assert old build label → OTA to bundled firmware → reconnect → assert new build label |
| `settings_update` | Green install APK | Connect once → for each of 7 settings: set B → save → assert readback == B |

The `firmware_update` suite builds v0.3.0 on demand from a `git worktree`
and caches the `.bin` under `firmware_cache/` (git-ignored). Subsequent
runs skip the build.

### pytest wrapper

```bash
pytest test-harness/                     # all three suites
pytest test-harness/test_fresh_install.py
pytest test-harness/test_firmware_update.py
pytest test-harness/test_settings.py
```

Each test file is a thin `Orchestrator.run(suite)` + `assert result.passed`.

## Run folder layout

```
runs/YYYYMMDD-HHMMSS-<suite>/
  manifest.json               # suite, timing, device/controller info, pass/fail,
                              #   build labels, per-test results
  dispatch-plan.json          # composed preconditions + am instrument command
  harness-<ts>.log            # full DEBUG-level orchestrator log
  android-logcat.txt          # continuous device logcat
  controller-serial.txt       # continuous controller serial (if port set)
  instrumentation-smoke.txt   # smoke gate stdout
  instrumentation-e2e.txt     # E2E test stdout
  screenshots/
    on-fail-<mtime>.png       # host screencap on failure
    final.png                 # host screencap on success
    freshInstall/             # Kotlin in-test step screenshots (per suite)
    firmwareUpdate/
    settingsUpdate/
```

## Interpreting results

- **`manifest.json`**: authoritative pass/fail per suite. `passed = true`
  only if smoke gate AND all E2E tests pass. `e2e_results` lists per-test
  pass/fail with durations.
- **`instrumentation-e2e.txt`**: raw `am instrument` stdout. Look for:
  - `OK (N tests)` — all passed.
  - `FAILURES!!!` + `Test failed to run to completion. Reason: ...` — crash
    or process death (check logcat for the stack trace).
  - Per-test `FAILED:` lines list which specific `@Test` failed.
- **`controller-serial.txt`**: controller boot log + OTA state chain
  (`BEGIN_UPDATE → awaiting_data → transferring → verifying →
  UPDATE_COMPLETED → reboot`). For firmware_update, confirm the
  `UPDATE_COMPLETED` event and the new firmware's build label in the
  post-reboot `maintenance_info OK` line.
- **`android-logcat.txt`**: app-side events. For firmware_update, look for
  `runMaintenanceUpdate terminal status` with `state=completed`.

## Prerequisites

### All suites

- Python 3.10+ with `pyserial`, `pytest`, `tomli`.
- `adb` on PATH (Android SDK platform-tools).
- Connected Android device (visible in `adb devices`).
- Controller powered on and advertising.
- **USB BLE Relay** connected to the host and paired with the phone
  (used by `E2eConnectionFixture` for BLE pairing during connect).

### fresh_install + settings_update

- `esptool.py` reachable via `[env].esptool_exe` + `[env].idf_python_exe`.
- Controller serial port connected.

### firmware_update (additional)

- **ESP-IDF toolchain** on the host: `idf.py` + its venv Python reachable
  via `[env].idf_py_exe` + `[env].idf_python_exe`.
- Controller serial port (`[controller].serial_port`).
- Git tag `v0.3.0` present in the repo (first run builds from a worktree).
- **BLE MAC** (`[controller].ble_mac`) for bond clearing after flashing
  old firmware (prevents stale-key reconnection failures).

## OS notes

### Windows

✅ Validated. All three suites pass green. Prerequisites:
- ESP-IDF v6.0 at `C:/esp/v6.0/esp-idf`.
- `[env].idf_python_exe` at the IDF venv Python
  (`C:/Espressif/tools/python/v6.0/venv/Scripts/python.exe`).
- Serial port as `COM4` (check Device Manager → Ports).
- Paths in `config.toml` use forward slashes or double backslashes.

### Linux

✅ Code-level validated (all modules import, os.sep='/', tomllib works,
pathlib resolves correctly). Hardware validation not yet executed —
requires a bare-metal Linux machine with USB access.

**WSL2 is not suitable** — it cannot access USB devices without
`usbipd-win`, and BLE timing through USB passthrough is unreliable.
Use a real Linux machine (or a VM with reliable USB passthrough).

Prerequisites:
- ESP-IDF at `/opt/esp/esp-idf` (or similar).
- `[env].idf_python_exe` at the IDF venv Python
  (`/opt/esp/python_env/idf6.0_py3.13_env/bin/python`).
- Serial port as `/dev/ttyUSB0` or `/dev/ttyACM0`.
- User in the `dialout` group (`sudo usermod -a -G dialout $USER`).
- Pathlib uses `/` natively — no path separator issues.
- `adb` from the Android SDK platform-tools.

### Mac

📝 Documented only (no Mac available for testing). Expected setup:
- Serial port as `/dev/cu.SLAB_USBtoUART` or `/dev/cu.usbserial-*`
  (Silabs CP210x USB-to-UART bridge).
- If `/dev/cu.*` does not appear: install the
  [Silabs CP210x VCP driver](https://www.silabs.com/developers/usb-to-uart-bridge-vcp-drivers).
- ESP-IDF at `~/esp/esp-idf` (or via Homebrew: `brew install esp-idf`).
- `[env].idf_python_exe` at the IDF venv Python (path varies by ESP-IDF
  version — check `~/esp/esp-idf/export.sh` for the venv location).
- `adb` via `brew install android-platform-tools` or Android Studio.

**Mac-only gaps** (not tested):
- Serial port path detection (`/dev/cu.*` vs `/dev/tty.*`).
- `esptool.py` flash timing (USB-to-UART latency may differ).
- `adb install` with M-series Macs (Rosetta or native).
- BLE Relay driver availability (check manufacturer docs).

If you validate any of these on Mac, please update this section.

## Troubleshooting

### BLE instability during OTA

**Symptom:** `firmware_update` test times out (~420s) or the app crashes
during post-OTA reconnection.

**Checks:**
1. `controller-serial.txt` — does `UPDATE_COMPLETED` appear?
   - If no: OTA transfer never finished. Look for `reason=` codes
     (133 = connection timeout, 8 = link supervision timeout).
   - If yes: the controller side is fine; check the app side.
2. `android-logcat.txt` — search for `runMaintenanceUpdate terminal status`.
   - If `state=completed`: the app detected the completed state. Any
     crash after this is in the post-OTA reconnection code path.
   - If no `terminal status`: the app never reached the end of the OTA
     loop. Check for `clearSession()` loops (app keeps reconnecting).
3. Post-OTA crash with `performMeasureAndLayout called during measure layout`:
   - Root cause: BLE callback thread fires `host.updateState()` during
     Compose layout, triggering a nested `measureAndLayout` on Android.
   - This is a known app-level race. The test robot catches transient
     Compose exceptions in `hasAnyTag()`, but a process-level crash
     cannot be caught. If it recurs consistently, the app's
     `BeetGattSessionCoordinator.kt` BLE callbacks need to defer
     `host.updateState` to the main looper via `Handler(Looper.getMainLooper()).post{}`.
   - One-time occurrences: re-run the suite. This crash is transient.
4. BLE Relay disconnect/reconnect cycles during the test:
   - The relay is a USB device that provides BLE connectivity between
     the phone and controller. It may disconnect if USB power management
     kicks in or if the relay firmware has watchdog timeouts.
   - If the relay drops during `E2eConnectionFixture.connectOnce()`,
     the test will fail. **Workaround:** re-plug the relay before each run.

### Stale BLE bond after firmware flash

**Symptom:** After `flash_old`, the app reconnects but gets
`HCI_ERR_KEY_MISSING` and enters a scan→connect→fail loop.

**Fix:** Set `[controller].ble_mac` in `config.toml`. The orchestrator
runs `clear_ble_bond_via_intent` before the E2E test, which calls
`BluetoothDevice.removeBond()` for the controller's MAC.

### Compose test timeout (420s) despite OTA completing

**Symptom:** `awaitReconnect` times out, but `controller-serial.txt` shows
`UPDATE_COMPLETED` and the app shows the connection gate (not nav).

**Root cause (fixed in P5):** `hasAnyTag()` in `FirmwareUpdateRobot` used
the merged Compose semantics tree. M3 `NavigationSuiteScaffold` only
includes the **selected** nav item in the merged tree. After OTA the
default selection is `Overview`, so `SettingsNavItem` was invisible.

**Fix:** `hasAnyTag()` now uses `useUnmergedTree = true`. This matches
how `SettingsRobot.openSettings()` accesses nav items.

### Cascade FAIL — BLE drops mid-suite

**Symptom:** `settings_update` passes tests 1-3 then fails 4-7, or all
tests after the first failure show "nav_settings_item not found."

**Explanation:** All 7 settings tests share one `@Before` connect. If BLE
drops mid-suite (e.g. a transient interference), the connection is lost
and remaining tests can't assert settings. The test class reports FAIL.

**This is intentional:** the harness surfaces genuine BLE instability.
If cascades happen consistently (>50% of runs), the BLE layer has a
regression. If they happen rarely (<10%), re-run the suite.

### Smoke gate fails

**Symptom:** "androidTest smoke failed; aborted before <suite>."

The smoke gate runs the pure-UI `MaintenanceUpdateInstrumentationTest`
(4 tests, no hardware). If it fails:
- Check `instrumentation-smoke.txt` for the specific failure.
- Rebuild APKs: `cd app && gradlew assembleDebug assembleDebugAndroidTest`.
- Check that no test tags are broken (new UI changes may have moved/removed
  tags the smoke test uses).

### Gradle build hangs

**Symptom:** `run.py` hangs at "Building APKs..." and eventually times out.

- Kill any stuck Gradle daemons: `cd app && gradlew --stop`.
- Check for lock files: delete `app/.gradle/` and retry.
- Increase `[orchestrator].gradle_build_timeout` if needed.

### Controller not advertising after flash

**Symptom:** After `flash_old` or `erase_region`, the controller doesn't
appear in the app's scan list.

- The controller takes 2-5 seconds after boot to start advertising.
  The `E2eConnectionFixture` waits up to 30s for the device to appear.
- If it still doesn't appear, check `controller-serial.txt` for the boot
  log. Look for `BLE advertising started` or NimBLE init errors.
- Power-cycle the controller and retry.

## When to file a firmware bug vs. test/robot issue

**File a firmware bug** when:
- `UPDATE_COMPLETED` appears but the new firmware doesn't boot (stuck
  in bootloader, `rst:0xc` loop).
- Controller returns `reason=133` or `reason=8` consistently on every
  OTA attempt.
- App gets `"awaiting_data"` after full upload → controller lost bytes.
- `"rebooting"` indication is never received (known issue on v0.3.0
  firmware — already fixed in the bundled firmware by the app-side
  `fullImageAlreadyTransferred && idle` → `completed` resume path).

**File a test/robot issue** when:
- Test fails with `ComposeTimeoutException` → robot didn't find the
  expected UI node (check screenshot, check tag spelling).
- Test fails with `adb: device offline` → USB cable/adb server issue.
- Test fails with `am instrument exited with code 1` but logcat shows
  clean app flow → instrumentation crash, not product bug.
- Gradle build fails or APK missing → build environment issue.

## Phase progress

| Phase | Status |
|-------|--------|
| P1 — test-tag foundation | ✅ |
| P2 — robots + E2E classes | ✅ |
| P3 — orchestrator core | ✅ |
| P4 — per-suite dispatch | ✅ |
| P5 — real-hardware stabilization | ✅ (all 3 suites green on Windows) |
| P6 — docs + cross-platform | ✅ |
