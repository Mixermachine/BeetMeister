# BeetMeister E2E Test Harness

Cross-platform (Win/Linux/Mac) orchestrator. Runs Kotlin robot-pattern
instrumentation tests under
`app/app/src/androidTest/java/de/aarondz/beetmeister/e2e/`.

## What lives here

- `harness/` — Python orchestrator (9 modules: adb, capture, config,
  controller_reset, firmware, logging_setup, orchestrator, run_folder,
  screenshots).
- `config.example.toml` — schema. Copy → `config.toml`. Edit.
- `requirements.txt` — deps.
- `runs/` — per-run evidence (git-ignored).
- `firmware_cache/` — cached `v0.3.0` build (git-ignored).
- `run.py` — `python run.py <suite>` entry.
- `conftest.py` + 3 `test_*.py` — pytest entry points.

## Install

```bash
python -m pip install -r test-harness/requirements.txt
```

Deps: `pyserial`, `pytest`, `tomli` (Python 3.10 `tomllib` fallback).

## Config

```bash
cp test-harness/config.example.toml test-harness/config.toml
```

Edit `config.toml` with absolute paths:

| Key | Required for | Description |
|-----|-------------|-------------|
| `[device].adb_serial` | All suites | First device auto-picked if empty |
| `[device].app_dir` | All suites | Path to `app/` with `gradlew` |
| `[device].gradlew` | All suites | Abs path to Gradle wrapper |
| `[controller].serial_port` | firmware_update | `COM4` (Win), `/dev/ttyUSB0` (Linux) |
| `[controller].ble_mac` | firmware_update | MAC for bond clear, `AA:BB:CC:DD:EE:FF` |
| `[env].esptool_exe` | fresh_install, firmware_update | Abs path to `esptool.py` in ESP-IDF |
| `[env].idf_py_exe` | firmware_update only | Abs path to `idf.py` in ESP-IDF |
| `[env].idf_python_exe` | fresh_install, firmware_update | ESP-IDF venv Python (has `cryptography`) |
| `[env].idf_env_script` | Optional | `export.bat`/`export.sh` for sourcing IDF env |

Orchestrator does **not** activate ESP-IDF env itself. Point
`[env].idf_python_exe` at the IDF venv Python (e.g.
`C:/Espressif/tools/python/v6.0/venv/Scripts/python.exe` Win,
`/opt/esp/python_env/idf6.0_py3.13_env/bin/python` Linux).

### Passkey authentication requirement

Automated E2E test runs require building controller firmware with the test passkey define:

```powershell
# From firmware/esp-idf:
powershell -ExecutionPolicy Bypass -File .\.agents\skills\esp-idf-installation\scripts\invoke-idf.ps1 -IdfArgs "-DBEET_BLE_TEST_PASSKEY=123456 build"
```

Or with plain `idf.py`:
```bash
idf.py -DBEET_BLE_TEST_PASSKEY=123456 build flash
```

This ensures the controller uses passkey `123456` during BLE pairing, matching the hardcoded value in `E2eConnectionFixture`. Production builds omit this define and display a random passkey on the OLED.

## Running suites

One `am instrument` call per `run.py` invocation:

```bash
python test-harness/run.py fresh_install       # ~20 s (2 tests)
python test-harness/run.py firmware_update     # ~340 s (1 test, real OTA)
python test-harness/run.py settings_update     # ~42 s (7 tests)
```

Exit 0 pass, non-zero fail. Console sparse (INFO). Full DEBUG in run dir
`harness-<ts>.log`.

### What each suite does

| Suite | Preconditions | Test |
|-------|--------------|------|
| `fresh_install` | Uninstall → esptool erase appcfg+events+sysevents → install APK | Scan → connect → assert 8 pair rows + controller info |
| `firmware_update` | Flash v0.3.0 → uninstall → install APK → clear BLE bond | Connect → assert old build label → OTA bundled fw → reconnect → assert new label |
| `settings_update` | Green install APK | Connect → 7 settings: set B → save → assert readback == B |

`firmware_update` builds v0.3.0 on demand from `git worktree`, caches
`.bin` under `firmware_cache/`. Subsequent runs skip build.

### pytest wrapper

```bash
pytest test-harness/                     # all three suites
pytest test-harness/test_fresh_install.py
pytest test-harness/test_firmware_update.py
pytest test-harness/test_settings.py
```

Each file = thin `Orchestrator.run(suite)` + `assert result.passed`.

## Run folder layout

```
runs/YYYYMMDD-HHMMSS-<suite>/
  manifest.json               # suite, timing, device/controller info, pass/fail,
                              #   build labels, per-test results
  dispatch-plan.json          # composed preconditions + am instrument cmd
  harness-<ts>.log            # full DEBUG orchestrator log
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

- **`manifest.json`**: authoritative pass/fail. `passed = true` only if
  smoke gate AND all E2E tests pass. `e2e_results` lists per-test
  pass/fail + durations.
- **`instrumentation-e2e.txt`**: raw `am instrument` stdout. Look for:
  - `OK (N tests)` — all passed.
  - `FAILURES!!!` + `Test failed to run to completion. Reason: ...` —
    crash/process death. Check logcat for stack.
  - Per-test `FAILED:` lines list which `@Test` failed.
- **`controller-serial.txt`**: boot log + OTA chain
  (`BEGIN_UPDATE → awaiting_data → transferring → verifying →
  UPDATE_COMPLETED → reboot`). For firmware_update confirm
  `UPDATE_COMPLETED` + new build label in post-reboot `maintenance_info OK`.
- **`android-logcat.txt`**: app events. For firmware_update look for
  `runMaintenanceUpdate terminal status` with `state=completed`.

## Prerequisites

### All suites

- Python 3.10+ with `pyserial`, `pytest`, `tomli`.
- `adb` on PATH (Android SDK platform-tools).
- Connected Android device (in `adb devices`).
- Controller powered on, advertising.
- **USB BLE Relay** connected to host, paired with phone. Used by
  `E2eConnectionFixture` for BLE pair during connect.

### fresh_install + settings_update

- `esptool.py` via `[env].esptool_exe` + `[env].idf_python_exe`.
- Controller serial port connected.

### firmware_update (additional)

- **ESP-IDF toolchain** on host: `idf.py` + venv Python via
  `[env].idf_py_exe` + `[env].idf_python_exe`.
- Controller serial port (`[controller].serial_port`).
- Git tag `v0.3.0` in repo (first run builds from worktree).
- **BLE MAC** (`[controller].ble_mac`) for bond clear after flashing old
  fw (prevents stale-key reconnect fails).

## OS notes

### Windows

✅ Validated. All 3 suites green. Needs:
- ESP-IDF v6.0 at `C:/esp/v6.0/esp-idf`.
- `[env].idf_python_exe` = IDF venv Python
  (`C:/Espressif/tools/python/v6.0/venv/Scripts/python.exe`).
- Serial port `COM4` (Device Manager → Ports).
- `config.toml` paths use `/` or double `\\`.

### Linux

✅ Code-level validated (modules import, os.sep='/', tomllib works,
pathlib resolves). Hardware validation needs bare-metal Linux + USB.

**WSL2 not suitable** — no USB access without `usbipd-win`; BLE timing unreliable. Use real Linux or VM with USB passthrough.

Needs:
- ESP-IDF at `/opt/esp/esp-idf` (or similar).
- `[env].idf_python_exe` =
  `/opt/esp/python_env/idf6.0_py3.13_env/bin/python`.
- Serial `/dev/ttyUSB0` or `/dev/ttyACM0`.
- User in `dialout` group (`sudo usermod -aG dialout $USER`).
- `adb` from Android SDK platform-tools.

## Troubleshooting

### BLE instability during OTA

**Symptom:** `firmware_update` times out (~420s) or app crashes during
post-OTA reconnect.

**Checks:**
1. `controller-serial.txt` — `UPDATE_COMPLETED` appear?
   - No: OTA never finished. Look for `reason=` codes (133 = connection
     timeout, 8 = link supervision timeout).
   - Yes: controller fine. Check app side.
2. `android-logcat.txt` — search `runMaintenanceUpdate terminal status`.
   - `state=completed`: app detected completion. Crash after this =
     post-OTA reconnect code path.
   - No terminal status: app never reached end of OTA loop. Check for
     `clearSession()` loops (app keeps reconnecting).
3. Post-OTA crash with `performMeasureAndLayout called during measure layout`:
   - Root cause: BLE callback thread fires `host.updateState()` during
     Compose layout → nested `measureAndLayout` on Android.
   - Known app race. Test robot catches transient Compose exceptions in
     `hasAnyTag()`, but process-level crash uncatchable. If recurs
     consistently, app `BeetGattSessionCoordinator.kt` BLE callbacks must
     defer `host.updateState` to main looper via
     `Handler(Looper.getMainLooper()).post{}`.
   - One-off: re-run. Transient.
4. BLE Relay disconnect/reconnect cycles:
   - Relay = USB device giving BLE between phone + controller. Drops on
     USB power mgmt or relay watchdog timeouts.
   - Drop during `E2eConnectionFixture.connectOnce()` → test fails.
     **Workaround:** re-plug relay before each run.

### Stale BLE bond after fw flash

**Symptom:** After `flash_old`, app reconnects but gets
`HCI_ERR_KEY_MISSING`, enters scan→connect→fail loop.

**Fix:** Set `[controller].ble_mac` in `config.toml`. Orchestrator runs
`clear_ble_bond_via_intent` before E2E test → calls
`BluetoothDevice.removeBond()` for controller MAC.

### Compose test timeout (420s) despite OTA completing

**Symptom:** `awaitReconnect` times out, but `controller-serial.txt`
shows `UPDATE_COMPLETED`, app shows connection gate (not nav).

**Root cause (fixed P5):** `hasAnyTag()` in `FirmwareUpdateRobot` used
merged Compose semantics tree. M3 `NavigationSuiteScaffold` only
includes **selected** nav item in merged tree. After OTA default = `Overview`,
so `SettingsNavItem` invisible.

**Fix:** `hasAnyTag()` now uses `useUnmergedTree = true`. Matches
`SettingsRobot.openSettings()` nav access.

### Cascade FAIL — BLE drops mid-suite

**Symptom:** `settings_update` passes 1-3 then fails 4-7, or all tests
after first fail show "nav_settings_item not found."

**Why:** All 7 settings tests share one `@Before` connect. BLE drops
mid-suite (transient interference) → connection lost → remaining tests
can't assert settings. Class reports FAIL.

**Intentional:** harness surfaces real BLE instability. Cascade >50% of
runs = BLE layer regression. <10% = re-run suite.

### Smoke gate fails

**Symptom:** "androidTest smoke failed; aborted before <suite>."

Smoke gate runs pure-UI `MaintenanceUpdateInstrumentationTest` (4 tests,
no hardware). On fail:
- Check `instrumentation-smoke.txt` for specific failure.
- Rebuild APKs: `cd app && gradlew assembleDebug assembleDebugAndroidTest`.
- Check test tags not broken (UI changes may move/remove tags smoke uses).

### Gradle build hangs

**Symptom:** `run.py` hangs at "Building APKs...", times out.

- Kill stuck Gradle daemons: `cd app && gradlew --stop`.
- Check lock files: delete `app/.gradle/`, retry.
- Increase `[orchestrator].gradle_build_timeout` if needed.

### Controller not advertising after flash

**Symptom:** After `flash_old` or `erase_region`, controller not in app
scan list.

- Controller takes 2-5 s post-boot to advertise. `E2eConnectionFixture`
  waits up to 30s.
- Still missing? Check `controller-serial.txt` boot log. Look for
  `BLE advertising started` or NimBLE init errors.
- Power-cycle controller, retry.

## File firmware bug vs. test/robot issue

**Firmware bug:**
- `UPDATE_COMPLETED` appears but new fw doesn't boot (stuck bootloader,
  `rst:0xc` loop).
- Controller returns `reason=133` or `reason=8` consistently every OTA.
- App gets `"awaiting_data"` after full upload → controller lost bytes.
- `"rebooting"` never received (v0.3.0 known issue — already fixed in
  bundled fw via app-side `fullImageAlreadyTransferred && idle` →
  `completed` resume path).

**Test/robot issue:**
- `ComposeTimeoutException` → robot didn't find UI node (check
  screenshot, tag spelling).
- `adb: device offline` → USB/adb server issue.
- `am instrument exited code 1` but logcat clean → instrumentation crash,
  not product bug.
- Gradle build fails / APK missing → build env issue.

