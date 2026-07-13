# BeetMeister E2E Test Harness

Cross-platform (Windows / Linux / Mac) orchestrator for the
Kotlin robot-pattern instrumentation tests under
`app/app/src/androidTest/java/de/aarondietz/beetmeister/e2e/`.

Status: **Phase 4 (per-suite dispatch wired; dry-runs green on
Windows; hardware runs are P5 territory).**

> Full plan: `.pi/plans/2026-07-12-e2e-test-harness-md.md`.
> P6 will expand this README (Install / Config / Run /
> Interpreting results / OS notes / Troubleshooting).

## What lives here

- `harness/` — the Python orchestrator package (9 modules).
- `config.example.toml` — schema; copy to `config.toml` and edit.
- `requirements.txt` — `pyserial`, `pytest`, `tomli` (3.10 fallback).
- `runs/` — per-run evidence folders (git-ignored).
- `firmware_cache/` — cached `v0.3.0` build (git-ignored).
- `run.py` — `python run.py <suite>` entry point.
- `conftest.py` + `test_fresh_install.py` + `test_firmware_update.py` +
  `test_settings.py` — pytest entry points (P4). Each test file is
  a thin wrapper around `Orchestrator.run(...)`.

## Three run modes

The CLI has three mutually exclusive execution modes:

| Mode | What it does | When to use |
|---|---|---|
| `--dry-run` | Build + install + capture + smoke gate only; stops before E2E class dispatch. | P3 verification: prove the pipeline works on a machine, no destructive action. |
| `--dry-run-dispatch` | Smoke gate + dispatch is COMPOSED (preconditions + am instrument command recorded in `dispatch-plan.json` + logged) but NOTHING is executed. | P4 verification: prove dispatch wiring is correct on a machine without hardware. The plan is also written in real runs for diagnostics. |
| (no flag) | Real run: preconditions execute + am instrument + screenshot pull. | P5 territory; may FAIL on hardware until stabilization is done. |

## Quick start

```bash
# from the repo root
python -m pip install -r test-harness/requirements.txt

cp test-harness/config.example.toml test-harness/config.toml
# edit config.toml: set [device].adb_serial + [controller].serial_port
# (and [env].esptool_exe + [env].idf_py_exe for the firmware-update suite)
```

### P3 stop point (smoke gate only)

```bash
python test-harness/run.py --dry-run fresh_install
```

Walks the full pipeline through the smoke gate
(`MaintenanceUpdateInstrumentationTest`, 4 pure-UI tests) and stops
before invoking the real `FreshInstallE2ETest`. Proves the build,
install, capture, and gate work end-to-end on this machine. **Exits
0** when the gate passes.

### P4 stop point (dispatch wired, no execution)

```bash
python test-harness/run.py --dry-run-dispatch fresh_install
python test-harness/run.py --dry-run-dispatch firmware_update
python test-harness/run.py --dry-run-dispatch settings_update
```

Runs the smoke gate + composes the full dispatch plan (preconditions
+ am instrument command) for the named suite. **Nothing is
executed** — no uninstall, no esptool erase, no firmware flash, no
am instrument. The plan is written to
`<run_dir>/dispatch-plan.json` for review; the run passes if the
plan assembled cleanly.

Use this mode to verify the P4 dispatch wiring on a machine without
hardware. Inspect the dispatch plan to confirm:
- The right `am instrument` class is targeted
  (`FreshInstallE2ETest` / `FirmwareUpdateE2ETest` /
  `SettingsUpdateE2ETest`).
- The right `-e` extras are passed (`beetRunE2e true` for all three;
  `expected_old_build_label` + `expected_new_build_label` for
  `firmware_update`).
- The right preconditions are listed (uninstall + erase for
  `fresh_install`; flash + uninstall for `firmware_update`;
  install-only for `settings_update`).
- The screenshot pull path matches the Kotlin `E2eScreenshotHelper`
  `testSlug` (`freshInstall` / `firmwareUpdate` / `settingsUpdate`).

### Real run (P5)

```bash
python test-harness/run.py fresh_install
python test-harness/run.py firmware_update
python test-harness/run.py settings_update
```

Actually executes the dispatch: preconditions + `am instrument` for
the E2E class + `adb pull` of the Kotlin in-test screenshots.
**Exits 0** only if the smoke gate + the E2E class both pass.

### pytest entry points

```bash
pytest test-harness/  # runs all three suites
pytest test-harness/test_fresh_install.py
pytest test-harness/test_firmware_update.py
pytest test-harness/test_settings.py
```

Each test file is a thin wrapper around `Orchestrator.run(suite)` +
`assert result.passed`. Reuses the `orchestrator` session-scoped
fixture from `conftest.py`. The session fixture is created once
per `pytest` invocation; per-test the fixture does NOT rebuild
state (the orchestrator is reset per-test via the run folder).

## Per-suite dispatch summary

| Suite | Preconditions (in order) | E2E class call |
|---|---|---|
| `fresh_install` | 1. `adb uninstall app`<br>2. esptool `erase_region appcfg`<br>3. esptool `erase_region events`<br>4. esptool `erase_region sysevents`<br>5. `install_apks(rebuild=False)` | ONE `am instrument -e beetRunE2e true -e class FreshInstallE2ETest` (2 @Tests) |
| `firmware_update` | 1. `firmware.flash_old(port)` (erase appcfg + otadata + write_flash bootloader + partition-table + app from `firmware_cache/`)<br>2. `adb uninstall app`<br>3. `install_apks(rebuild=False)` | ONE `am instrument -e beetRunE2e true -e expected_old_build_label <v0.3.0 from .bin metadata> -e expected_new_build_label <from bundled-firmware-stamp.json> -e class FirmwareUpdateE2ETest` (1 @Test, 600s timeout) |
| `settings_update` | 1. `install_apks(rebuild=False)` (start from a green install) | ONE `am instrument -e beetRunE2e true -e class SettingsUpdateE2ETest` (7 @Tests share one class-shared @Before connect) |

After `am instrument` exits, the orchestrator `adb pull`s the
Kotlin in-test screenshots from
`/sdcard/Android/data/<package>/files/e2e_screenshots/<slug>/`
into `<run_dir>/screenshots/<slug>/`.

## Run folder layout (per `Orchestrator.run()` invocation)

```
runs/YYYYMMDD-HHMMSS-<suite>/
  manifest.json             # suite, start/end, device serial, controller port,
                            # pinned tag, build labels, pass/fail, durations,
                            # per-suite params, e2e results, dispatch_plan_executed
  dispatch-plan.json        # the composed dispatch (preconditions + am
                            # instrument + screenshot pull), with
                            # executed=true/false per step. Always written
                            # in real runs (for diagnostics) and in
                            # --dry-run-dispatch mode.
  android-logcat.txt        # continuous, from artifacts/stage8/logcat_reader.py
  controller-serial.txt     # continuous, from artifacts/stage8/serial_reader.py (if [controller].serial_port set)
  instrumentation-smoke.txt # stdout from the smoke gate `am instrument`
  instrumentation-e2e.txt   # stdout from the E2E `am instrument`
  logcat.pid, controller-serial.pid   # capture subprocess PIDs
  screenshots/
    00-preconnect.png       # host: before connect (ad-hoc; currently
                            # captured by the orchestrator's pre-screenshot
                            # step if implemented, else empty)
    on-fail-<mtime>.png     # host: after am instrument exits with failure
    final.png               # host: after successful completion
    freshInstall/           # Kotlin in-test step shots for the fresh_install suite
    firmwareUpdate/         # …for the firmware_update suite
    settingsUpdate/         # …for the settings_update suite
```

## Prerequisites (all three suites)

- Python 3.10+ with `pyserial`, `pytest`, `tomli` (`pip install -r test-harness/requirements.txt`).
- `adb` on PATH (or set `BEET_HARNESS_ADB`). The Android SDK platform-tools.
- A connected Android device (adb sees it in `adb devices`).

### fresh_install + settings_update only

- `esptool.py` reachable. Set `[env].esptool_exe` to the absolute
  path inside the ESP-IDF env (e.g.
  `C:\esp\v6.0\esp-idf\components\esptool_py\esptool\esptool.py`).
  Optionally set `[env].idf_env_script` to source the env if `python`
  alone can't import esptool's deps.

### firmware_update (additional)

- ESP-IDF on the host: `idf.py` reachable. Set `[env].idf_py_exe`
  (e.g. `C:\esp\v6.0\esp-idf\tools\idf.py`). Same `idf_env_script`
  opt-in as above.
- A controller serial port: `[controller].serial_port = "COM4"`.
- A git tag for the pinned old firmware: `[firmware].pinned_tag`
  (default `v0.3.0`). First run will `git worktree add` the tag +
  build with `idf.py`; subsequent runs use the cached `.bin` under
  `firmware_cache/`.

## Where to file firmware issues

The harness surfaces a real regression as FAIL with a clear
`fail_reason` in the manifest. When a failure smells firmware
(BLE maintenance instability, controller reason codes 133/8, OTA
state chain broken), the harness reports it — don't try to "fix"
it from the test side. File a firmware bug.

## Phase progress

| Phase | Status | Commit |
|---|---|---|
| P1 — test-tag foundation | ✅ | `09b6426` |
| P2 — robots + E2E classes | ✅ | `a5ca7ed` + `7785795` |
| P3 — orchestrator core | ✅ | `17cdb19` + `2be4a47` + `58c83c7` |
| P4 — per-suite dispatch | ✅ | (this commit) |
| P5 — real-hardware stabilization | ⏸ | next phase |
| P6 — docs + cross-platform | ⏸ | after P5 |
