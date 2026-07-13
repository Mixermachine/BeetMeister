---
title: "Cross-Platform E2E Test Harness (Android + Controller)"
status: approved
type: feature
---

# Cross-Platform E2E Test Harness (Android + Controller)

## Status: APPROVED — implementation not yet started

## Locked Design Decisions

| Decision | Choice |
|---|---|
| Architecture | **Hybrid**: Kotlin robot-pattern Compose instrumentation tests + cross-platform Python+pytest orchestrator |
| Controller reset (fresh-install suite) | **esptool partition erase** of `appcfg`+`events`+`sysevents` (keeps running firmware + device_id; no app needed). Replaces the earlier "BLE factory_reset only" choice, which was circular (reset needs an installed app over BLE, but the suite starts with the app uninstalled). |
| Old firmware source | **Build from source on demand**: `git worktree add` tag `v0.3.0`, build with ESP-IDF on the host, cache the `.bin` locally (git-ignored). Requires ESP-IDF toolchain on the dev machine **only for the firmware-update suite**. |
| Settings coverage | **All writable settings, parametrized** |
| Harness location | `test-harness/` (top-level) |
| Run folder layout | `runs/YYYYMMDD-HHMMSS-<suite>/` with shared `android-logcat.txt` + `controller-serial.txt` at the root and a `screenshots/` subdir + `manifest.json` |
| Setting verification | **UI readback only** (Compose assertion after save). Watering-interval + max-active-pumps assert on the existing read-only current-value rows; the three valve-numeric settings (move/settle/hold) have no read-only row, so the robot types B → taps save → **pull-to-refresh the Settings screen** (BeetPullToRefreshBox re-fetches valveConfig → LaunchedEffect(valveConfig) repopulates the fields) → asserts the field text == B. No UI change. |
| "Visible data" pass criteria | **Overview pairs + Settings Controller Info** (state_stream + controller_info) |
| Expected new build label (firmware-update assert) | Parse the **generated** stamp `app/app/build/generated/bundledFirmware/main/bundled-firmware-stamp.json` (always fresh, git-ignored) AFTER the orchestrator builds the app APK. The stale root `bundled-firmware-stamp.json` is **not** the source of truth. Orchestrator passes `build_label` + `firmware_version` to the Kotlin test via `am instrument -e` extras |
| APK build responsibility | **Orchestrator builds** — the pipeline shells out to `gradlew assembleDebug assembleDebugAndroidTest` (cross-platform via the wrapper) before install + smoke gate. No pre-built-APK config path |
| ESP-IDF / esptool location | **Config paths** — `config.example.toml` holds absolute paths: `esptool_exe`, `idf_py_exe`, plus an `idf_env_script` (export.bat / export.sh) for opt-in activation. User configures once per machine. The orchestrator does NOT activate the IDF env itself |
| Test tags & node access | `onNodeWithTag` consistently + **debug-guarded `testTagsAsResourceId = true`** on the root composable so tags also surface as Android `resource-id` (uiautomator/UiAutomator2/python can find nodes). Guarded by `BuildConfig.DEBUG` so release builds are untouched |
| E2E test gating | Custom `@E2e` annotation + a **custom `AndroidJUnitRunner` subclass** registered in `build.gradle.kts` `testInstrumentationRunner`; the runner applies a `Filter` that skips `@E2e` tests unless runner arg `beetRunE2e=true` is passed. Orchestrator sends `-e beetRunE2e true -e package de.aarondietz.beetmeister.e2e …`. |
| Smoke gate | Run the pure-UI `MaintenanceUpdateInstrumentationTest` (NOT `MaintenanceUpdateLiveActivityInstrumentationTest`) as the first instrumentation step of every run; PASS gates the run, FAIL aborts the suite early with a clear message |
| Baud rate | 115200 |
| CI | Stays Ubuntu-only; the harness runs on dev machines, not in CI |
| OS support | Windows + Linux validated (all three suites green on both); Mac **documented-only** (no Mac available — `/dev/cu.*` path + driver steps + explicit Mac-only-gap markers; no untested Mac code paths) |
| P6 modify scope | **Orchestrator + config** — path handling, serial port detection, subprocess invocation, `config.example.toml`, README. App + firmware + Kotlin test code frozen from P5 |
| README troubleshooting | **Included** — Troubleshooting section covers BLE instability symptoms (reason 133/8, ~30s disconnect, BEGIN_UPDATE blocking), reading serial/logcat for reason codes, what a cascade-FAIL looks like, when to file a firmware bug vs. test/robot issue |
| Settings dispatch granularity | **Full class, 1 call** — one `am instrument -e class SettingsUpdateE2ETest` runs all 7 `@Test`s in a single instrumentation session so the class-shared `@Before` connect fires once (matches Phase 2). No per-case splitting |
| Old (v0.3.0) build label extraction | **Parse built `.bin` metadata** — after the worktree build, extract the old build_label from the generated `.bin`'s embedded metadata (esptool `image_info` or a small header parser). NOT stdout grep, NOT a synthesized version.txt |
| Screenshot ownership | **Hybrid** — in-test step screenshots (after-connect / before-save / after-readback) taken inside the Kotlin E2E tests via `UiAutomation.takeScreenshot()` writing PNG to external storage; on-failure + final screenshots host-initiated via `adb screencap` after `am instrument` exits |
| Result parsing | **Stdout now, XML later** — P4 parses `am instrument` stdout (`OK (N tests)` / `FAILURES!!!` / per-test `FAILED:`) for the manifest. An XML `RunListener` is deferred to P5 only if stabilization needs richer per-test reporting |
| P5 modify scope | **Everything except firmware** — agent may change robots, tests, fixture, `E2eScreenshotHelper`, orchestrator, AND app source. Firmware (v0.3.0 + current bundled) is frozen; firmware bugs are reported not fixed |
| appcfg before flashing old firmware | **Always erase** — `erase_region appcfg` once before `idf.py flash` of the v0.3.0 build, for deterministic clean controller state |
| Connect-cascade response (P5) | **Accept & report FAIL** — no retry added to `@Before`. A mid-suite BLE drop cascade is a genuine surfaced failure, not suppressed. Closes the Phase 2 deferral |
| P5 autonomy | **Pause after N=3 failures** — agent loops fix-rerun on a suite but pauses to ask the user after 3 consecutive failed iterations; does not pause on green except at the P5 exit gate |

---

## Goals

Fully automated, cross-platform (Windows/Linux/Mac) test harness that exercises the real Android app against the real ESP32-S3 controller. Each run produces a self-contained folder of evidence: screenshots, continuous ADB logcat, continuous controller serial output, instrumentation output, and a manifest.

Three test cases, all fully automatic:
1. **Fresh installation**: uninstall app, erase controller config partitions with esptool (no app needed), fresh app install, connect, assert controller data visible.
2. **Firmware update**: controller flashed with an older pinned firmware (tag `v0.3.0`); app performs a real OTA update to current bundled firmware; verify post-update health by asserting the build label changed to the expected bundled build label.
3. **Settings update**: class-shared `@Before` connect (one connect for the whole test class), then for each writable setting set a deterministic value B, save, assert UI readback == B (one-shot; no A, no round-trip, no baseline reset). Parametrized across all writable settings.

---

## Architecture (Hybrid)

```
Phase 1: Test-tag foundation (Android/Kotlin)      — no hardware
Phase 2: Kotlin robots + E2E classes (compile)     — no hardware  [depends on 1]
Phase 3: Python orchestrator core (host)           — can run smoke [depends on 1]
Phase 4: Per-suite wiring (host)                   — suite dispatch [depends on 2 + 3]
Phase 5: Real-hardware stabilization (host+device) — the hard phase [depends on 4]
Phase 6: Documentation + cross-platform (host)     — Linux + Mac   [depends on 5]
```

**Dependency arrows (explicit, no parallelism):** 1 → 2 → 3 → 4 → 5 → 6. Each phase has a demonstrable exit criterion (below), so the commit-after-phase boundary is meaningful. No phase is started before its dependency is complete.

**Phase exit criteria (demonstrable, not just "done"):**
- P1 exit: app + androidTest APK build; the EXISTING `MaintenanceUpdateInstrumentationTest` smoke still passes; new tags + `@E2e` gate + debug `testTagsAsResourceId` exist.
- P2 exit: E2E classes (`FreshInstallE2ETest`, `FirmwareUpdateE2ETest`, `SettingsUpdateE2ETest`) and all robots COMPILE against the P1 tags; not yet run on hardware.
- P3 exit: `python run.py --dry-run <suite>` walks the full pipeline through the smoke gate end-to-end (smoke actually runs and is parsed), stopping before the E2E class invocation. Suite dispatch is a skeleton, not wired per-suite.
- P4 exit: each suite's dispatch (fresh_install / firmware_update / settings_update parametrized) is implemented; each suite can be invoked and reaches the real E2E class with the right `-e` extras. May FAIL on hardware — that's P5's job.
- P5 exit: all three suites pass green on Windows + user confirms end-to-end. (Note: the project's "don't stop until confirmed on real hardware" rule is for feature implementation, not test-harness stabilization — bounded autonomy in P5 is correct.)
- P6 exit: README complete; suite validated on Linux; Mac best-effort documented.

**Rationale (from memory):** Chains die on plans >15 tasks / ~200 lines. So planner implements directly, committing per phase, rather than spawning a mega-chain. Each phase stays under ~9 tasks. Phase 5 (hardware stabilization) is split out as its own phase, not residue, because it is the single most failure-prone part and the project mandates it not be hand-waved.

### Layer split

**Kotlin instrumentation tests (androidTest)** — runs on the device via `adb shell am instrument`:
- Each test case is a Kotlin `@Test` using the **robot pattern**.
- Robots wrap Compose `ComposeTestRule` actions/finders behind intention-revealing methods (`SettingsRobot.toggleValveEnabled()`, `OverviewRobot.assertPairRowsRendered()`).
- UI interaction + UI verification happen here (test tags).
- Failures surface through instrumentation output + screenshots captured by the orchestrator.

**Python orchestrator (`test-harness/`)** — runs on the host:
- pytest-based runner (one pytest test per E2E case, or a parametrized suite).
- Owns the run folder, starts/stops logcat + serial capture BEFORE launching instrumentation, captures screenshots on failure (and at key steps), parses instrumentation exit status, writes manifest.json.
- Calls into the Android side via:
  - `adb uninstall` / `adb install -r`
  - `adb shell am instrument -w` (with `-e` extras for expected labels + the `beetRunE2e` flag)
  - `adb exec-out screencap -p` (with `adb pull` fallback)
- Calls into the controller for the fresh-install reset:
  - Uses **esptool directly** (`esptool.py erase_region <addr> <size>` for the `appcfg`, `events`, `sysevents` partitions) via subprocess. No BLE, no app needed; keeps the running firmware and `device_id` intact. Partition addresses read from `firmware/esp-idf/partitions/*.csv`.
  - (Earlier, BLE-driven `factory_reset` was considered, but it is circular: it requires an installed+connected app to send, while the suite begins with the app uninstalled. Dropped.)
- Builds (once, cached) and flashes the older firmware for the firmware-update case, and only for that case, via `idf.py -p <port> flash`.

---

## Reuse Map

| Existing asset | Reuse |
|---|---|
| `artifacts/stage8/serial_reader.py` | **Direct reuse** — copy into `test-harness/lib/capture/serial_reader.py` or import in-place. Already cross-platform, bounded (`--max-seconds`, `--idle-exit`). Orchestrator launches it as a subprocess per run, writing raw bytes to the run folder. |
| `artifacts/stage8/logcat_reader.py` | **Direct reuse** — same bounded pattern for logcat. |
| `scripts/dev/run-android-real-device-validation.ps1` | **Pattern reuse** — the ADB orchestration steps (uninstall/install/grant-permissions/am instrument) are translated to Python (cross-platform). |
| `scripts/dev/ble-connect-automation.py` | **Pattern reference only** — superseded by robot-pattern Kotlin tests; not called. |
| `run-android-real-device-validation.ps1` ADB serial handling | Ported to a small `Adb` Python wrapper with `-s <serial>` support and multi-device detection. |
| Kotlin `MaintenanceUpdateTestTags.kt` pattern | **Direct reuse** — extend the same `*TestTags` object pattern to Overview, Settings, ConnectionGate, PairDetail screens. |
| Kotlin `MaintenanceUpdateInstrumentationTest.kt` (pure-UI variant) | **Pattern reuse** + reused directly as the run smoke gate (NOT the `LiveActivity` variant) |
| Firmware host tests / CI | Unchanged. |
| esptool.py (ESP-IDF toolchain) | **Direct reuse** — `erase_region` for the fresh-install controller reset (firmware-update suite needs full `idf.py` for the old-firmware build); partition addresses from `firmware/esp-idf/partitions/*.csv` |

**New dev tooling dependency:** Python 3.10+, `pyserial`, `pytest`. Documented in `test-harness/README.md` with a `requirements.txt`. No repo-wide Python dependency added beyond this folder.

---

## Test Tags To Add (Phase 1)

Extend the `*TestTags` object pattern to these screens for stable element selection:

```
ui/feature/overview/OverviewTestTags.kt          — pair list, per-pair row/moisture/state/value, pull-refresh
ui/feature/settings/SettingsTestTags.kt          — controller_info card fields (device_id, firmware_version, protocol_version),
                                                    water interval card (current value + edit fields + save),
                                                    valve config card (valve_enabled switch + move/settle/hold fields + current-value readback nodes + save),
                                                    max active pumps card (current value + stepper + save)
ui/feature/connection/ConnectionGateTestTags.kt  — scan button, device card, connect button, connection status text
ui/feature/pairdetail/PairDetailTestTags.kt     — pair name field, rename button
```

Each file defines `internal object XxxTestTags { const val ... = "snake_case_tag" }`, mirroring the existing `MaintenanceUpdateTestTags.kt` convention. Composables apply `Modifier.testTag(XxxTestTags.Foo)` using these constants. **Additionally**, wrap the root app composable (`BeetMeisterApp`) with a debug-only `Modifier.semantics { testTagsAsResourceId = true }` guarded by `BuildConfig.DEBUG`, so every `testTag` also surfaces as an Android `resource-id` for uiautomator/UiAutomator2/python node finding. Release builds are untouched.

**Verified convention:** Kotlin tests use `onNodeWithTag(tag)` consistently (matches the existing `MaintenanceUpdateInstrumentationTest.kt`). `onNodeWithTag` works WITHOUT `testTagsAsResourceId` — the flag is only there to let non-Kotlin tools (python/ADB-uiautomator) find the same nodes. Tags must be applied to the stable outer card/row, not deeply nested spans.

**Phase 1 precondition (resolved blocker B4):** verified by reading `SettingsScreen.kt`:
- ✅ watering interval has a read-only current-value row (`settings_label_current_watering_interval`).
- ✅ max active pumps has a read-only current-value row (`settings_label_max_active_pumps_current`).
- ✅ valve_enabled has a read-only row in the Valve card (`settings_label_valve_enabled`, reads `deviceState.valveEnabled`).
- ❌ `move_duration_ms` / `settle_delay_ms` / `open_hold_ms` have NO read-only current-value node — only the editable `OutlinedTextField`.

**Resolution chosen: pull-refresh reload pattern (no UI change).** The Phase 2 robot for the three valve-numeric params will: type B → tap save → pull-to-refresh the Settings screen (triggers `BeetPullToRefreshBox`'s `onRefresh` → re-fetches `valveConfig` → `LaunchedEffect(valveConfig)` repopulates the field texts from server state) → assert the field text == B. This proves persistence without adding UI. The Settings card + BeetPullToRefreshBox MUST get test tags in Phase 1 so the robot can target the refresh gesture + the valve-config field texts.

Phase 1 task 1.3 does NOT add read-only rows; it only verifies the above and tags the existing nodes (current-value rows, valve-enabled row, valve-numeric fields, save button, the pull-to-refresh box).

---

## Kotlin Robot-Pattern Instrumentation Tests (Phase 2)

Location: `app/app/src/androidTest/java/de/aarondietz/beetmeister/e2e/` (under the dedicated `e2e` package, so the suite filter below can target it by package).

### Robots (one per screen)
```
robots/ConnectionGateRobot.kt   — tapScan(), assertDeviceVisible(name), tapConnect(), assertConnected()
robots/OverviewRobot.kt         — assertPairRowsRendered(count=8), assertPairMoistureNonEmpty(index), pullToRefresh()
robots/SettingsRobot.kt         — openSettings(), setWateringInterval(h,m), save(), assertCurrentWateringInterval(),
                                    toggleValveEnabled(), setValveMoveDuration(ms), saveValveConfig(),
                                    assertCurrentValveConfig(), setMaxActivePumps(n), saveMaxActivePumps(),
                                    assertCurrentMaxActivePumps(), openControllerInfo(), assertDeviceId/Firmware/Protocol()
robots/FirmwareUpdateRobot.kt   — openFirmwareUpdate(), useBundled(), assertSummaryShown(), tapInstall(),
                                    awaitCompletedOrRebooting(), awaitReconnect(), assertPostUpdateHealthy()
```

### Test classes
```
FreshInstallE2ETest.kt          — on-device: scan, connect, assert Overview 8 pairs + Settings Controller Info non-empty.
                                    App uninstall/install + controller erase handled by the orchestrator, not here.
FirmwareUpdateE2ETest.kt        — connect, open firmware update, install bundled, await completion/reboot+reconnect,
                                    assert Settings Controller Info build_label == expected (passed via -e extras from
                                    bundled-firmware-stamp.json).
SettingsUpdateE2ETest.kt        — parametrized: for each setting, set a deterministic B (one-shot), save, assert UI readback == B. Class-shared @Before connect.
```

**Caveat (recorded):** on-device tests can't assume app is freshly installed. The orchestrator handles uninstall/install as a precondition BEFORE launching each test class. The Kotlin tests assert only what happens on-device (scan→connect→assert).

### Parametrized settings (all writable, one @Test per case; one-shot, no round-trip, no baseline reset)
- valve_enabled toggle (B = false)
- valve move_duration_ms (B = 2000)
- valve settle_delay_ms (B = 500)
- valve open_hold_ms (B = 2000)
- watering_interval_s (B = 3600)
- max_active_pumps (B = 2)
- pair_name_rename (B = "e2e-renamed-N"; reached via Overview→tap pair Details→rename IconButton→dialog text→Save)

Each @Test sets the deterministic B → taps save → robot reads back the taggable current-value node (or, for the three valve-numeric, pulls-to-refresh then reads the field text) → Kotlin-asserts equality == B. **No serial/logcat scanning, no A, no return-to-A.** Controller retains B across the session — acceptable per the isolation decision.

### Test-runner classification (resolved confusion I3)
- `src/androidTest` does NOT run under `:app:testDebugUnitTest` (that runs `src/test`); CI only runs `testDebugUnitTest` + `assembleDebug`, so the E2E classes never run in CI already.
- Add a custom `@E2e` annotation in the androidTest source set. Configure the instrumentation runner so a NAIVE `connectedDebugAndroidTest` (or `am instrument` without an explicit filter) SKIPS `@E2e` tests by default — implemented via a small `AndroidJUnitRunner` subclass that respects a `beetRunE2e` runner arg, OR via gradle `testInstrumentationRunnerArguments` + package exclusion.
- The orchestrator explicitly requests E2E: `am instrument -e beetRunE2e true -e package de.aarondietz.beetmeister.e2e …` (or the equivalent the runner expects).
- Do NOT use `@Keep` (that's a ProGuard/R8 annotation, unrelated to test selection).

---

## Python Cross-Platform Orchestrator (Phase 3)

Location: `test-harness/`
```
test-harness/
  README.md
  requirements.txt              # pyserial, pytest
  harness/
    __init__.py
    config.py                   # DeviceConfig, ControllerConfig, service paths from env/config file
    adb.py                      # Adb(serial=None) wrapper: devices(), install, uninstall, grant perms,
                                #   am_instrument(class, args, extras), screencap(dest), launch_activity()
    capture.py                  # start_logcat(run_dir, serial)->Popen, start_serial(run_dir, port, baud)->Popen
                                #   reuses logcat_reader.py + serial_reader.py via subprocess
    run_folder.py               # create_run_folder(suite_name)->Path, writes manifest seed, scaffolding (screenshots/),
                                #   update manifest at end
    firmware.py                 # ensure_old_firmware_built(pinned_tag="v0.3.0"):
                                #   git worktree add -> build with idf.py -> cache .bin under firmware_cache/ (git-ignored)
                                #   returns build_label/version. flash_old(port) flashes via idf.py -p PORT flash.
    controller_reset.py         # erase_config_partitions(port): esptool.py erase_region <addr> <size> for
                                #   appcfg+events+sysevents (partition addresses from firmware/esp-idf/partitions/*.csv).
                                #   No BLE, no app required; keeps firmware + device_id.
    screenshots.py              # capture_screenshot(adb, dest); capture_on_failure via instrumentation fail callback
    android_build.py            # build_install(adb, config): gradle assembleDebug + assembleDebugAndroidTest,
                                #   adb install -r both APKs, grant BLE perms, unlock guard.
                                #   parse bundled-firmware-stamp.json -> expected build_label + firmware_version for the OTA test.
    orchestrator.py             # Orchestrator.run(suite): the single pipeline owner. pytest tests call this.
  conftest.py                   # pytest fixtures: env discovery -> (device serial, controller port),
                                #   shared run_folder fixture scoped per test.
  test_fresh_install.py         # pytest entry: calls Orchestrator.run("fresh_install")
  test_firmware_update.py       # pytest entry: calls Orchestrator.run("firmware_update")
  test_settings.py              # parametrized: pytest.mark.parametrize("setting_case", [...all setting keys...])
                                #   each calls Orchestrator.run("settings", case=...)
  run.py                        # `python run.py <suite>` convenience entry; delegates to Orchestrator.
  config.example.toml          # [device] serial=, [controller] port=, baud_rate=115200, [firmware] pinned_tag="v0.3.0"
```

**Ownership boundary (resolved I6):** `Orchestrator.run(suite, **kwargs)` is the single pipeline owner. It creates the run folder, starts captures, performs preconditions, invokes `am instrument` (including the smoke gate), captures screenshots, finalizes the manifest, and returns a result object. The pytest test files are thin — each just calls `Orchestrator.run(...)` and asserts the returned `result.passed == True`. `run.py` is a non-pytest convenience wrapper that also calls `Orchestrator.run(...)`. No duplicate orchestration logic.

### Run-folder contract (per run, created up-front)
```
runs/YYYYMMDD-HHMMSS-<suite>/
  manifest.json           # suite, start/end, device serial, controller port, params, pass/fail, durations, paths
  android-logcat.txt      # continuous, from logcat_reader.py (subprocess stdout)
  controller-serial.txt   # continuous, from serial_reader.py (subprocess stdout)
  instrumentation.txt     # stdout from `am instrument` (includes smoke + E2E classes)
  screenshots/
    00-preconnect.png            # host: before connect (screencap)
    01-connected.png             # Kotlin: after @Before connect (UiAutomation → adb pull)
    02-before-save-<test>.png    # Kotlin: before each settings save (UiAutomation → adb pull)
    03-after-readback-<test>.png # Kotlin: after each settings readback (UiAutomation → adb pull)
    on-fail-<mtime>.png          # host: after am instrument exits with failure (screencap)
    final.png                    # host: after successful completion (screencap)
  junit-report.xml        # pytest's junit
```

### Sequence per suite

```
1. Read config (device serial, controller port, pinned tag="v0.3.0", baud=115200).
2. create_run_folder() — mkdir -p the runs/<ts>-<suite>/ dir; seed manifest with start time; mkdir screenshots/.
3. start_logcat() + start_serial() as subprocess.Popen writing to the run dir.
4. build_install(): assembleDebug + assembleDebugAndroidTest; install + grant BLE perms; unlock guard.
   Parse bundled-firmware-stamp.json → expected_bundled_build_label, expected_bundled_firmware_version.
5. SMOKE GATE: am instrument -w -e class de.aarondietz.beetmeister.ui.feature.connection.MaintenanceUpdateInstrumentationTest
   (the pure-UI variant, NOT the LiveActivity one). Stream to instrumentation.txt. If this fails → mark the whole
   run FAILED with reason "androidTest smoke failed; aborted before <suite>", screenshot + logcat tail, stop captures, exit ≠ 0.
   Do NOT run the real E2E class on top of a broken test harness.
6. Suite precondition (varies):
   - fresh_install:      adb uninstall app;
                         controller_reset.erase_config_partitions(port)  # esptool erase_region; no app/BLE needed
                         adb install debug APK + grant perms.
   - firmware_update:    firmware.flash_old(port)  # idf.py -p PORT flash of the v0.3.0 build (cached after first build)
                         adb uninstall app; adb install debug APK + grant perms.
                         pre-update expected new build label = expected_bundled_build_label (from step 4).
   - settings_update:    adb uninstall app; adb install debug APK + grant perms (start from a green install).
7. am instrument -w -e beetRunE2e true -e package de.aarondietz.beetmeister.e2e -e class de.aarondietz.beetmeister.e2e.<SuiteE2ETest>
   (firmware_update also passes -e expected_old_build_label <v0.3.0 from .bin metadata> -e expected_new_build_label <from generated stamp>)
   → poll until instrumentation exits, stream to instrumentation.txt.
   - settings_update: ONE call runs the FULL SettingsUpdateE2ETest class (all 7 @Test siblings, one shared @Before connect) — matches Phase 2.
   - Per-test pass/fail parsed from stdout (OK / FAILURES / per-test FAILED lines) into the manifest.
   - In-test step screenshots (after-connect, before-save, after-readback) taken inside Kotlin via UiAutomation.takeScreenshot() → PNG to external storage. Host only needs to `adb pull` them to the run folder after `am instrument` exits.
8. On test failure (detected via instrumentation stdout), the orchestrator captures an **on-failure screenshot via `adb screencap`** + tail serial/logcat for the reporter. On success the orchestrator captures a **final screenshot**.
9. finalize manifest with durations and results; terminate logcat/serial subprocesses and wait for them to flush.
   Exit 0 only if smoke + E2E both pass.
```

### Cross-platform concerns
- Path handling uses `pathlib.Path` everywhere; no `\` literals.
- `adb` resolved via `shutil.which` with override env vars; the ADB wrapper passes `-s <serial>` only when set. `esptool.py` and `idf.py` resolved from **config paths** (`esptool_exe`, `idf_py_exe`) — NOT via `shutil.which`, because they live inside the ESP-IDF env which is not assumed to be on PATH.
- Serial port lookup via `serial.tools.list_ports` (cross-platform).
- ESP-IDF is required on the dev machine **only for the firmware-update suite** (to build v0.3.0 + to flash via `idf.py`). The fresh-install and settings suites need only `esptool.py` (shipped with ESP-IDF) for `erase_region`. The orchestrator does NOT bundle ESP-IDF setup — documented prerequisite in README; the smoke gate + fresh-install/settings suites do not require the full IDF.
- Screenshot capture uses `adb exec-out screencap -p > file` (binary stdout) — cross-platform; fall back to `adb shell screencap` + `adb pull` if exec-out is unsupported.
- Existing PowerShell build helpers (`flash.ps1`, `clean_build.ps1`, `invoke-idf.ps1`) are Windows-only; the harness shells out to `idf.py`/`esptool.py` directly so Linux/Mac work without porting those scripts.

---

## Old-Firmware Strategy (firmware-update suite) — build from source on demand

- `test-harness/firmware_cache/` (git-ignored) stores a built `beetmeister-v0.3.0.bin` + `version.txt` (`firmware_version`, `build_label`, `runtime_protocol_version`, `image_size`, `image_sha256`).
- `firmware.py.ensure_old_firmware_built(pinned_tag="v0.3.0")`:
  1. If the cached `.bin` exists for `pinned_tag`, use it.
  2. Otherwise: `git worktree add _build_old <pinned_tag>`, build with `idf.py build` (host ESP-IDF), copy the `.bin` + write `version.txt` into `firmware_cache/`, remove the worktree.
  3. `flash_old(port)`: `idf.py -p <port> flash` of the cached `.bin`. Preceded by **`erase_region appcfg` once** (Phase 5 decision: always erase appcfg before flashing old firmware, for deterministic clean state). Writes bootloader+partitions+app on top of the erased appcfg.
- The current "new" image bundled into the app comes from the regular app build (`app/app/build/generated/bundledFirmware/main/assets/firmware/beetmeister-rev_a-bundled.bin`), exactly what a user ships. **Note (recorded):** the **root** `bundled-firmware-stamp.json` currently has `runtime_protocol_version=9` while `config/protocol_versions.properties` is v15 — the harness reads the **generated** stamp (fresh on every build), NOT the root stamp, so the stale root value is a non-issue. The harness still asserts at build time that the app's `BEET_RUNTIME_PROTOCOL_VERSION` (BuildConfig) and the generated bundled firmware's metadata runtime protocol match `config/protocol_versions.properties`; if not, fail the suite early with a clear message rather than attempting an OTA that will be mis-identified.
- The test proves a real upgrade: pre-update build label (from v0.3.0) visible in Settings → different new build label (bundled) visible post-update.
- **Prerequisite (documented in README):** building v0.3.0 on the host requires the ESP-IDF toolchain. Only the firmware-update suite has this requirement; fresh-install/settings need only `esptool.py` (for erase_region).

---

## Test Cases (detailed acceptance)

### 1. Fresh installation (test_fresh_install.py)
**Preconditions:** app uninstalled from phone; controller `appcfg`+`events`+`sysevents` erased via esptool (running firmware + `device_id` preserved).
**Steps:**
1. Orchestrator: `adb uninstall de.aarondietz.beetmeister`.
2. Orchestrator: `controller_reset.erase_config_partitions(port)` — `esptool.py erase_region` for the three NVS partitions (addresses from `firmware/esp-idf/partitions/*.csv`). No BLE, no app needed. Controller keeps running firmware + `device_id`; advertising starts fresh on next boot.
3. Orchestrator: install debug APK + grant BLE perms.
4. Kotlin `FreshInstallE2ETest`: launchMainActivity; ConnectionGateRobot.tapScan; assertDeviceVisible("beetmeister-..."); tapConnect; assertConnected(timeout 60s).
5. OverviewRobot.assertPairRowsRendered(8); assertPairMoistureNonEmpty(0).
6. SettingsRobot.openSettings; assertControllerInfo shows device_id (non-empty), firmware_version (non-empty), protocol_version == BuildConfig.BEET_RUNTIME_PROTOCOL_VERSION.
**Pass:** smoke + E2E pass; screenshots captured at fail/finish; manifest marked PASS.

### 2. Firmware update (test_firmware_update.py)
**Preconditions:** controller flashed with older firmware (tag `v0.3.0`, built on the host); app freshly built with current bundled firmware.
**Steps:**
1. `firmware.ensure_old_firmware_built("v0.3.0")` → cached `.bin` + `version.txt` → `firmware.flash_old(port)`. Record old build label from `version.txt`.
2. Orchestrator parses the **generated** stamp `app/app/build/generated/bundledFirmware/main/bundled-firmware-stamp.json` → expected new `build_label` + `firmware_version`; asserts its `runtime_protocol_version` matches `config/protocol_versions.properties` (`v15`) before proceeding. (Root `bundled-firmware-stamp.json` is stale and is NOT the source of truth.)
3. Orchestrator: fresh install app + grant perms.
4. Kotlin `FirmwareUpdateE2ETest`: connect; SettingsRobot.openSettings; assert Current Firmware == old build label (from step 1, passed via `-e expected_old_build_label`); openFirmwareUpdate; useBundled; assertSummaryShowsCurrent; tapInstall; awaitRebootingThenReconnect(max 300s); post-update assert Settings Controller Info `build_label` == expected new (passed via `-e expected_new_build_label` from step 2).
5. Serial expected: BEGIN_UPDATE → awaiting_data → transferring (multiple) → verifying → rebooting → (reconnect) → controller_info.
**Pass:** firmware on controller reflects the new bundled `build_label` after update + reconnect; no `failure_reason` appeared in serial/logcat; screenshots captured.
**Caveat (recorded from docs):** the repo documented BLE maintenance instability (~30s disconnect, BEGIN_UPDATE large write blocking, reason 133/8); the orchestrator SHOULD use generous timeouts (≥300s) and let the firmware's built-in resume path retry. The real acceptance criterion is "completes without manual intervention". If a known instability breaks it, the harness reports FAIL rather than masking it — this is intentional (the harness's job is to surface regressions).

### 3. Settings update (test_settings.py, parametrized)
**Parametrized over all writable settings:** `valve_enabled`, `valve_move_duration_ms`, `valve_settle_delay_ms`, `valve_open_hold_ms`, `watering_interval_s`, `max_active_pumps`, `pair_name_rename`.
Each parameter is a separate `@Test` (sets a deterministic B, asserts readback == B; no A, no round-trip, no baseline reset).
**Dispatch granularity:** the orchestrator runs **ONE `am instrument -e class SettingsUpdateE2ETest`** that runs all 7 @Tests as siblings in a single instrumentation session, so the class-shared `@Before` connect fires once (matches Phase 2). Per-test pass/fail parsed from stdout into the manifest.
**Steps:** Orchestrator: green `install_apks()`; the test class's shared `@Before` connects once (tapScan→tapConnect→assertConnected). Each `@Test` `SettingsUpdateE2ETest.<param>`: SettingsRobot.openSettings (via nav-item tag); navigate to the right card; robot.set value B; tap save; robot reads back the taggable current-value node (or, for valve-numeric, pull-to-refresh + read the field text); Kotlin-assert equal to B.
**Pair name** is reached via Overview→tap pair→PairDetail→rename (not via the Settings screen); `SettingsRobot` delegates to a small PairDetail interaction.
For each setting only assert UI readback (no logcat/serial grep). **Step screenshots (before save, after readback) taken inside Kotlin via `UiAutomation.takeScreenshot()` → PNG to external storage → orchestrator `adb pull`s into the run folder after `am instrument` exits.**
**Pass:** for each setting param the readback matches the new value within timeout (default 30s, configurable).
**Phase 1 precondition:** confirm each settings card has a taggable current-value readback node (see resolved blocker B4 above).

---

## Phases & Tasks (Sequential Direct Implementation)

(Memory-driven: **planner implements directly, not via a chain**, committing after each PHASE. Subagents used only for read-only review of completed phases. No phase starts before its dependency is complete. Each phase stays under ~9 tasks. **The demoable exit criteria in the Architecture section above — not the task list — are the definition of phase completion; commit + pause at each phase boundary.**)

### Phase 1 — Test-tag foundation (Android/Kotlin, no hardware)
1.1. Audit existing `testTag`/`MaintenanceUpdateTestTags.kt` pattern to match conventions.
1.2. Add `OverviewTestTags.kt` + apply tags to OverviewScreen pair rows / moisture / state / value.
1.3. Add `SettingsTestTags.kt` + apply to Controller Info card fields (device_id, firmware_version, protocol_version), watering interval card (current-value row + edit fields + save), valve config card (valve_enabled switch + the three valve-numeric OutlinedTextFields + save), valve card valve_enabled read-only row, max active pumps card (current-value row + stepper +/- + save), AND the `BeetPullToRefreshBox` wrapping Settings (for the pull-refresh readback gesture). **B4 verified by reading SettingsScreen.kt: the three valve-numeric fields have no read-only row, so use the pull-refresh reload pattern (resolved above) — no read-only rows added.**
1.4. Add `ConnectionGateTestTags.kt` + apply to Scan button, Device card, Connect button, connection status.
1.5. Add `PairDetailTestTags.kt` + apply to pair name field, rename IconButton, rename dialog text field + Save button.
1.6. **Add `NavigationSuiteTestTags.kt` + apply tags to each `NavigationSuiteScaffold` nav item (Overview/Calibration/Events/Settings) in `BeetAppUi.kt`.** Required for deterministic Settings navigation in Phase 2 — the existing LiveActivity test uses fragile `onNodeWithText("Settings")` which double-matches on the Settings screen (nav-item label + screen title). Robots use `onNodeWithTag(NavigationSuiteTestTags.SettingsNavItem)`.
1.7. Add debug-only `testTagsAsResourceId = true` on `BeetMeisterApp` root composable (guarded by `BuildConfig.DEBUG`); verify with `adb shell uiautomator dump` that the tags appear as `resource-id` (once, against any attached device).
1.8. Add `@E2e` annotation (androidTest source set) + a custom `BeetE2eAwareJUnitRunner : AndroidJUnitRunner` that applies a `Filter` skipping `@E2e` tests unless runner arg `beetRunE2e=true` is passed; register it in `app/build.gradle.kts` `testInstrumentationRunner`. Orchestrator later sends `-e beetRunE2e true -e package de.aarondietz.beetmeister.e2e …`.
1.9. Build app + androidTest APK with new tags; run the existing pure-UI `MaintenanceUpdateInstrumentationTest` smoke; confirm green.
→ **P1 exit:** green build + green smoke; screen tags + nav-item tags + `@E2e` gate + `testTagsAsResourceId` exist. Commit + pause.

### Phase 2 — Kotlin robots + E2E test classes (compile-only, no hardware)
2.1. Set up `androidTest/.../e2e/robots/` package; add `ConnectionGateRobot.kt` (tapScan, assertDeviceVisible, tapConnect, assertConnected — built on `createAndroidComposeRule<MainActivity>`).
2.2. Add `OverviewRobot.kt` (assertPairRowsRendered, assertPairMoistureNonEmpty, pullToRefresh).
2.3. Add `SettingsRobot.kt` (navigate via `onNodeWithTag(NavigationSuiteTestTags.SettingsNavItem)`; controller-info getters — assertDeviceId/Firmware/Protocol non-empty; watering-interval type→save→assert on the read-only current-value row; max-active-pumps tap-stepper→save→assert on the read-only current-value row; **valve config type B in the three OutlinedTextFields→save→pull-to-refresh the Settings screen (BeetPullToRefreshBox)→wait for LaunchedEffect(valveConfig) repopulate→assert field text == B**; valve_enabled toggle→assert via the Valve-card read-only row; delegates pair-name rename to a small PairDetail interaction — tap pair Details→rename IconButton→dialog text→Save).
2.4. Add `FirmwareUpdateRobot.kt` (open firmware update via Settings ▸ Firmware update button, use bundled, install, await reboot+reconnect, post-update info reads `expected_old_build_label` + `expected_new_build_label` from `InstrumentationRegistry` extras).
2.5. Add a shared `E2eConnectionFixture` (androidTest) — **class-level `@Before` does tapScan→tapConnect→assertConnected ONCE** for the whole test class. All E2E test classes use it. **No `@After` disconnect, no settings-baseline reset** — Phase 5 will revisit if BLE drops cause cascades. Connect retry-on-failure is NOT included in P2; revisit in P5 once real-hardware behavior is known.
2.6. Add a shared `E2eScreenshotHelper` (androidTest/util) — thin wrapper over `UiAutomation.takeScreenshot()` writing a PNG to external storage with a deterministic name (e.g. `<test>-before-save-<ts>.png`). Used by the E2E test classes for in-test step screenshots (after-connect / before-save / after-readback). **Required by Phase 4's hybrid screenshot ownership model** — Kotlin owns in-test step shots; orchestrator `adb pull`s them after `am instrument` exits.
2.7. Add `FreshInstallE2ETest.kt` (uses the fixture; `@E2e` annotation).
2.8. Add `FirmwareUpdateE2ETest.kt` (`@E2e`; per-step `composeRule.waitUntil(timeoutMillis=30_000…60_000)` at Install → rebooting → reconnect → post-update-info steps, AND **overall `@Test(timeout = 600_000)` as a 10-minute hard backstop** so a stalled step still terminates; reads expected labels from `InstrumentationRegistry`).
2.9. Add `SettingsUpdateE2ETest.kt` parametrized across all setting cases. **One-shot: set a deterministic B → save → assert readback == B. No A, no round-trip, no baseline reset.** Deterministic B values: `watering_interval_s=3600`, `max_active_pumps=2`, `move_duration_ms=2000`, `settle_delay_ms=500`, `open_hold_ms=2000`, `valve_enabled=false` (toggle), `pair_name="e2e-renamed-N"`. Controller retains B across the session — acceptable per the isolation decision.
2.10. Compile androidTest APK; confirm all robots + E2E classes + the fixture + `E2eScreenshotHelper` compile against the P1 tags. Do NOT run on hardware (that's Phase 5).
→ **P2 exit:** E2E classes + robots + `E2eConnectionFixture` compile against P1 tags. Commit + pause. DEPENDS ON Phase 1.

### Phase 3 — Python orchestrator core (host, can run the smoke gate)
3.1. Create `test-harness/` skeleton: README (stub), `requirements.txt`, `harness/__init__.py`, config.py, `config.example.toml`, `.gitignore`. **`config.example.toml` defines absolute paths** for `esptool_exe`, `idf_py_exe`, optional `idf_env_script` (export.bat / export.sh), controller `serial_port`, `baud`, `adb_serial`, and the app + androidTest gradlew paths. User copies to `config.toml` (git-ignored).
3.2. `adb.py`: device detection, install (both app + androidTest APKs), uninstall, grant perms, `am_instrument(class, args, extras)` incl. `-e beetRunE2e true -e package …`, `screencap` (exec-out + pull fallback), `launch_activity`. **`install_apks()` runs `gradlew assembleDebug assembleDebugAndroidTest` first** (cross-platform via wrapper) then `adb install -r` both outputs — orchestrator is the APK builder of record.
3.3. `partition_map.py` (NEW explicit task): parse `firmware/esp-idf/partitions/*.csv` into name→(offset,size). Used by 3.4 and 3.5.
3.4. `controller_reset.py`: `erase_config_partitions(port)` using `esptool.py erase_region` with addresses from `partition_map.py`; no BLE/app.
3.5. `firmware.py`: `ensure_old_firmware_built("v0.3.0")` (worktree build via configured `idf_py_exe`, cache `.bin`+`version.txt`) + `flash_old(port)` via configured `idf_py_exe -p PORT flash`; assert firmware metadata before flashing; resolve `esptool.py`/`idf.py` from **config paths**, not PATH. **In P3 these functions are written + import-clean but NOT exercised** — the P3 dry-run targets `fresh_install` only (see 3.10). `firmware.py` is first actually-run in P4.
3.6. `capture.py`: reuse `logcat_reader.py` + `serial_reader.py` via subprocess; start/stop, write to run folder, bounded automatic stop.
3.7. `run_folder.py`: create `runs/<ts>-<suite>/` + subdirs (screenshots/, manifest seed); update manifest at end.
3.8. `screenshots.py`: `capture_screenshot(adb, dest)` + failure-callback hook.
3.9. `orchestrator.py`: `Orchestrator.run(suite, **kwargs)` — single pipeline owner: create run folder → start captures → `adb.install_apks()` (gradlew build + install) → **SMOKE GATE** (run pure-UI `MaintenanceUpdateInstrumentationTest` via `am_instrument`, parse pass/fail, abort on fail) → **suite dispatch skeleton** (a per-suite `if` block that P4 fills in) → finalize manifest → stop captures → return result object. In `build_install`, parse the **generated** stamp `app/app/build/generated/bundledFirmware/main/bundled-firmware-stamp.json` (fresh, git-ignored) for `build_label` + `firmware_version` + `runtime_protocol_version`; assert `runtime_protocol_version` matches `config/protocol_versions.properties` (`v15`) — fail early with a clear message on mismatch (the stale root stamp is ignored).
3.10. `run.py --dry-run fresh_install` (**P3 dry-run targets `fresh_install` only**): walks the fresh_install pipeline through gradlew build → install → smoke gate → controller-reset dispatch skeleton, stopping before the E2E class invocation. `firmware_update` and `settings_update` suites are NOT exercised in P3 (they ship in P4). This is what makes P3 demoable without P2.
→ **P3 exit:** `python run.py --dry-run fresh_install` walks the full pipeline + runs the smoke gate end-to-end. DEPENDS ON Phase 1. Commit + pause.

### Phase 4 — Per-suite wiring (host, suite dispatch)
4.1. `fresh_install` dispatch in orchestrator.py: adb uninstall → `controller_reset.erase_config_partitions(port)` → `install_apks()` (already built by P3 pipeline) → `am instrument -e beetRunE2e true -e class ...FreshInstallE2ETest`. Parse pass/fail from stdout.
4.2. `firmware_update` dispatch: `firmware.flash_old(port)` → uninstall → `install_apks()` → `am instrument -e beetRunE2e true -e expected_old_build_label … -e expected_new_build_label ...FirmwareUpdateE2ETest`. **Old build label extracted from the built v0.3.0 `.bin` metadata** (esptool `image_info` or a small header parser — NOT from stdout grep, NOT from a synthesized version.txt); new label from the P3 generated stamp.
4.3. `settings_update` dispatch: green `install_apks()` → **ONE `am instrument -e beetRunE2e true -e class ...SettingsUpdateE2ETest` runs the FULL test class in a single instrumentation session** so the class-shared `@Before` connect fires once and all 7 `@Test`s share it (matches Phase 2 decision). **No per-case splitting; the 7 settings tests run as siblings in one JVM session.** The manifest records per-test results from stdout.
4.4. pytest entry files (`conftest.py`, `test_fresh_install.py`, `test_firmware_update.py`, `test_settings.py`) as thin wrappers calling `Orchestrator.run(...)`; `run.py` as the non-pytest convenience wrapper (only one of them implements the suite list — no duplicate orchestration).
4.5. **Screenshot ownership is split hybrid:** in-test step screenshots (after-connect, before-save, after-readback) are taken **inside the Kotlin E2E tests** via `UiAutomation.takeScreenshot()` / writing PNG to external storage — precise per-step timing, no host polling. On-failure + final screenshots are **host-initiated**: the orchestrator `adb screencap`s after `am instrument` exits (detects failure from stdout) and on successful completion. `screenshots.py` from P3 only handles the host-initiated path; the Kotlin-internal path needs a small shared helper in the androidTest source set (added as part of P2 robot/test-class authoring).
4.6. Manifest: pass/fail, build labels, durations, used params, paths to artifacts; exit 0 only on full pass. **Per-test pass/fail parsed from `am instrument` stdout** (`OK (N tests)` / `FAILURES!!!` / per-test `FAILED:` lines) into the manifest. **No XML RunListener added in P4** — defer to P5 only if stabilization needs richer per-test reporting.
→ **P4 exit:** each suite is invokable and reaches the real E2E class with the correct `-e` extras + the right dispatch granularity (full-class settings, single-call fresh-install/firmware-update, both-labels OTA). May FAIL on hardware — that's P5. DEPENDS ON Phase 2 AND Phase 3. Commit + pause.

### Phase 5 — Real-hardware stabilization (host + device — the hard phase)
**Modify scope (P5):** agent may change **everything except firmware** — robots, test classes, `E2eConnectionFixture`, `E2eScreenshotHelper`, orchestrator (timing/retries/capture), AND app source (add test tags, fix Compose rendering, adjust reconnect logic). **Firmware is frozen**: pinned v0.3.0 (the 'old' image) AND the current bundled firmware are NOT modified. Any firmware bug found during P5 is reported, not fixed.
5.1. Run the fresh-install suite on Windows with the real controller+phone. Iterate: fix robot element-finding drift, serial/logcat timing, re-advertise guards, screenshot triggers, until the suite passes green + user confirms.
5.2. Run the settings-update suite (all params). Iterate until every param passes green on Windows.
5.3. Run the firmware-update suite: `ensure_old_firmware_built("v0.3.0")` → **`erase_region appcfg` once (always erase, per P5 decision)** → `flash_old(port)` → OTA via the app. Iterate through the known BLE maintenance instability (≥300s timeouts, resume path, reason 133/8) until the OTA completes without manual intervention + user confirms end-to-end.
5.4. **Connect-cascade policy (resolved P5):** if the class-shared `@Before` connect drops mid-suite and cascades, the suite **reports FAIL — no retry added to `@Before`**. A cascade is a genuine failure surfaced, not suppressed. (The Phase 2 deferral is now closed: no connect-retry in P2, and P5 also chooses no retry.)
5.5. Cross-check artifacts: each run folder has screenshots/, continuous logcat+serial, instrumentation.txt, manifest.json, junit-report.xml.
5.6. **Autonomy policy (P5):** the agent loops on a suite to fix-and-rerun, but **pauses to ask the user after N=3 consecutive failed iterations** of the same suite (bounded autonomy — prevents infinite spiral). Does NOT pause after a green suite except at the P5 exit gate.
→ **P5 exit:** all three suites pass green on Windows + user confirms end-to-end. (`bundled-firmware-stamp.json` runtime-protocol sanity check is also exercised here.) DEPENDS ON Phase 4. Commit + pause for user go/no-go before P6.
**Note:** the project's standing "don't stop until confirmed on real hardware" rule applies to *feature implementation*, not to test-harness stabilization — bounded autonomy (pause after N=3) here is correct and does NOT invoke that rule.

### Phase 6 — Documentation + cross-platform (host)
**Modify scope (P6):** agent may change **orchestrator code (path handling, serial port detection, subprocess invocation) + `config.example.toml` + README**. App, firmware, and Kotlin test code are **frozen** (already validated in P5) — do not re-destabilize already-green suites for a cross-platform fix.
6.1. Write the full `test-harness/README.md`. Sections: Install (`pip install -r requirements.txt`); Config (copy `config.example.toml` → `config.toml`, fill device serial / controller port / esptool_exe / idf_py_exe); Running each suite; Interpreting results; Prerequisites (ESP-IDF toolchain for **all three suites on Linux** — esptool for erase_region + idf.py for the firmware-update build/flash; on Windows the same prerequisites apply); OS notes (Windows + Linux validated, Mac documented-only). **PLUS a Troubleshooting section**: known BLE maintenance instability symptoms (~30s disconnect, reason 133/8, BEGIN_UPDATE large-write blocking), how to read serial/logcat for reason codes, what a cascade-FAIL looks like, when to file a firmware bug vs. a test/robot issue, how to read the manifest + artifacts.
6.2. **Validate all three suites on Linux** (same physical controller+phone, `/dev/ttyUSB*` or `/dev/ttyACM*`). Requires a Linux ESP-IDF installation (esptool + idf.py on Linux). Fix path/port/pathlib issues in orchestrator + config. **Target: green on all three suites on Linux** — same acceptance as Windows (P5) strengthened the orchestrator, so Linux should pass with only config/path fixes; if a suite fails the agent pauses to ask the user (same bounded-autonomy rule as P5, pause after N=3).
6.3. **Mac = document only** (no Mac available). README documents the expected `/dev/cu.*` serial port path + steps + prerequisites (esp-idf, CP210x/Silabs driver). Mac-only gaps are marked explicitly in the README (e.g. 'untested; if `/dev/cu.*` does not appear, install the Silabs CP210x driver'). The orchestrator does NOT add untested Mac-specific code paths in P6.
6.4. Confirm `.gitignore` (`runs/`, `firmware_cache/`, `__pycache__/`, `_build_old/`, `config.toml`) tracked correctly; only sources in git (per repo hygiene skill).
→ **P6 exit:** README complete (incl. Troubleshooting); **all three suites green on Linux**; Mac documented-only. DEPENDS ON Phase 5.

### Verification (overall)
- Phase 5 produces: fresh-install suite green on Windows; firmware-update suite completes a real OTA (post-update build label differs from pre-update; serial shows the full OTA state chain); every settings param passes (one-shot set-to-B; rerun sets B again — deterministic, not 'returns to prior state'); appcfg erased before flashing old firmware.
- Phase 6 produces: all three suites green on **Linux** (same physical controller+phone as P5); README with Install + Config + Run + Interpreting results + Prerequisites + OS notes + **Troubleshooting**; Mac documented-only (no Mac available) with `/dev/cu.*` path + driver steps + explicit Mac-only-gap markers.
- `.github/workflows/ci*.yml` untouched (suite is host-run; CI stays build/unit-only, no HW in CI).

### ⏸️ Pause markers (commit + pause at each)
- After Phase 1 — review test-tag coverage + the readback-node verification (B4) before robots are written.
- After Phase 2 — review robot APIs before the orchestrator wires to them.
- After Phase 3 — review `python run.py --dry-run` output before per-suite wiring.
- After Phase 4 — review suite dispatch before hardware runs.
- After Phase 5 — explicit user go/no-go before documentation + cross-platform (don't spend on docs/cross-OS until it's green on the reference OS).
- After Phase 6 — final confirmation.

---

## Risks & Mitigations (recorded)

1. **BLE maintenance instability** (~30s disconnect, BEGIN_UPDATE large write blocking, reasons 133/8) — documented in `docs/testing/ble-maintenance-stage8-handoff.md`. Mitigation: timeouts ≥300s and let the firmware resume path retry; harness reports FAIL on genuine regressions instead of suppressing.
2. **`bundled-firmware-stamp.json` is stale** (runtime_protocol_version=9 vs config v15) — harness performs a build-time sanity check and fails fast with a clear message.
3. **esptool erase needs the right partition addresses** — addresses are read from `firmware/esp-idf/partitions/*.csv` at runtime, not hardcoded; if the partition table changes, the CSV is the source of truth.
4. **v0.3.0 build requires ESP-IDF on the host** — documented prerequisite for the firmware-update suite only; cached after first build so subsequent runs skip the build.
5. **factory_reset no longer the reset path** — the earlier concern about bonds/device_id being wiped by `factory_reset` is moot; esptool `erase_region` preserves `device_id` (it lives outside the erased partitions) and clears bonds (in NVS), so the app must rescan/re-pair. ConnectionGateRobot.tapScan handles this with a re-advertising guard (10-30s).
6. **Chain/context exhaustion risk (memory)** — planner implements directly; no chain >6 tasks; commits after each stage so partial progress is recoverable.
7. **`testTag` not matching in tests** — use `onNodeWithTag` consistently (matches existing convention); apply tag to stable outer card/row, not nested spans. `testTagsAsResourceId` is a bonus for python access, not required for Kotlin tests.
8. **Maintenance protocol frozen** — harness sends no new wire formats; it only triggers the app's existing flow.
9. **Settings without a current-value readback node** — RESOLVED: the three valve-numeric settings (move/settle/hold) have no read-only current-value row. Phase 2's robot uses the pull-refresh reload pattern (type → save → pull-to-refresh → assert field == B). No UI change in Phase 1; only tags the existing editable fields + the BeetPullToRefreshBox so the robot can target them.

---

## Out of scope (explicit)
- CI hardware testing (CI stays build/unit).
- New BLE protocol commands (maintenance protocol is frozen).
- Multi-device / multi-controller testing.
- iOS testing.
- Replacing firmware host tests or CI workflows.
- Adding pytest as a repo-wide standard (only `test-harness/` uses it).

---

## Resolved open items (all confirmed 2026-07-12)
- Pinned old-firmware tag: **v0.3.0**.
- Baud rate: **115200**.
- CI: **Ubuntu-only, unchanged**; harness runs on dev machines only.
- Smoke gate in every run: **yes** — pure-UI `MaintenanceUpdateInstrumentationTest` (NOT the `LiveActivity` variant), as a gate that aborts the run on failure.
- OS support: Windows + Linux validated, **Mac best-effort**.
- Controller reset mechanism: **esptool partition erase** (supersedes the earlier "BLE factory_reset only" decision).
- Old firmware: **build from source on demand** (worktree + `idf.py`), cached locally.
- Expected post-update build label: **parsed from `bundled-firmware-stamp.json`** and passed to the Kotlin test via `-e` extras.
- Test tags: **`onNodeWithTag` + debug-guarded `testTagsAsResourceId = true`** (for python/uiautomator node access).
- E2E gating: **`@E2e` annotation + package-based runner exclusion** so a naive `connectedDebugAndroidTest`/`am instrument` skips E2E.
- Settings readback: **UI readback only**; one-shot set-to-B `@Test` per setting (no A, no round-trip; controller retains B across the session). Class-shared `@Before` connect.

## Remaining open item (decide during implementation, non-blocking)
- `firmware.flash_old(port)`: whether to `erase_region appcfg` once before flashing v0.3.0 (so leftover NVS from a prior run doesn't seed PC state). Tentative: yes, one-line, decided during Phase 5 hardware stabilization.