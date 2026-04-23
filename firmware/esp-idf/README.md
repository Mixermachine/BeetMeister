# BeetMeister Firmware

This directory is the ESP-IDF project root for the BeetMeister controller firmware.
It now contains the first real controller foundation rather than the temporary blink-only bring-up app.

## Current implementation slice

- `esp32s3` target with 16 MB flash and PSRAM defaults
- OTA-first custom partition table at [partitions/beetmeister.csv](C:/git/BeetMeister/firmware/esp-idf/partitions/beetmeister.csv:1)
- board mapping fixed to the documented 44-pin ESP32-S3 layout
- ADC1 moisture inputs for all 8 pairs plus battery sensing on `GPIO2`
- relay outputs default-off on boot
- persistent `appcfg` and `events` partitions backed by NVS
- 1000-entry event ring with CRC validation and sequence reconstruction
- controller task with battery refresh, moisture refresh, block countdown, scheduler deadline, and status LED updates
- automatic run state machine scaffolding with queueing, sanity-check flow, and event creation
- NimBLE-based BLE transport with the documented custom service and shared-interface-backed commands
- bench diagnostics that log parseable raw ADC, millivolt, and pair-state data
- automatic recovery from sensor-invalid faults when a valid reading returns
- optional relay self-test mode that cycles one relay at a time for bench validation

## BLE transport status

- BLE uses NimBLE, not Bluedroid.
- The firmware now advertises the documented BeetMeister custom service while awake and not in deep low-battery sleep.
- Implemented BLE characteristics:
  - `controller_info`
  - `state_stream`
  - `control_point`
  - `command_result`
- Implemented BLE commands:
  - `manual_start`
  - `manual_stop`
  - `reset_block`
  - `store_calibration`
- `manual_start` accepts an optional per-command `duration_s` override in the range `1..900`.
- The transport is built on the shared internal interface layer so the same command and state model can later be reused for MQTT.

## Safety defaults

- Live pump actuation is disabled by default through `CONFIG_BEET_ENABLE_PUMP_OUTPUTS=n`.
- The flashed image will evaluate and log watering need, but it will not energize relays until that Kconfig flag is enabled explicitly.
- Relay self-test is also disabled by default and must be enabled explicitly through `CONFIG_BEET_ENABLE_RELAY_SELF_TEST`.

## Bench bring-up behavior

- Battery reads are filtered and large one-shot spikes are rejected before state transitions.
- Each battery refresh now overlays multiple complete battery measurements before the controller
  applies spike rejection and filtering, which makes sleep-state transitions less sensitive to
  single noisy loop samples.
- Battery scaling uses a clean two-stage model:
  - measured divider factor on `GPIO2`
  - battery-path calibration factor
- Bench diagnostics are enabled by default and emit lines such as:
  - `bench battery raw=... sensed_mv=... divider_mv=... scaled_mv=... filtered_mv=...`
  - `bench pair=... relay_gpio=... moisture_gpio=... raw=... mv=... corrected_mv=... pct=...`
- Moisture conversion uses a sane default calibration for every pair even before app-side calibration exists.
- Moisture readings are corrected below the configured `moisture_sensor_supply_knee_mv` because these capacitive
  sensors lose output headroom as the battery rail drops; the corrected value is what the controller uses for
  percentage conversion and validity checks.
- If a pair faults because of an invalid sensor reading and later receives a valid reading again, the controller returns that pair to `IDLE` automatically.

## Build and flash

Use the project-local wrapper so the repo uses the installed ESP-IDF toolchain consistently on Windows:

```powershell
cd C:\git\BeetMeister\firmware\esp-idf
powershell -ExecutionPolicy Bypass -File ..\..\.agents\skills\esp-idf-installation\scripts\invoke-idf.ps1 build
powershell -ExecutionPolicy Bypass -File ..\..\.agents\skills\esp-idf-installation\scripts\invoke-idf.ps1 -p COM7 flash
```

For serial logs:

```powershell
powershell -ExecutionPolicy Bypass -File ..\..\.agents\skills\esp-idf-installation\scripts\invoke-idf.ps1 -p COM7 monitor
```

For a short bench-capture run with summary output:

```powershell
powershell -ExecutionPolicy Bypass -File ..\..\scripts\dev\capture-bench-diagnostics.ps1 -Port COM7 -DurationSeconds 15 -BenchOnly
```

## Non-hardware tests

Host-side tests now live under `firmware/tests/host`.

Run them from the repo root with:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\dev\run-firmware-host-tests.ps1
```

The host suite covers:

- pure controller logic from `beet_core.c`
- event-ring reconstruction helpers
- BLE JSON parsing and formatting
- shared interface name mapping

If the current shell does not provide a Windows MSVC/SDK linker environment, the runner falls back to compile-only validation of every host-test translation unit and prints that explicitly.

QEMU smoke scaffolding is also available:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\dev\run-firmware-qemu-smoke.ps1
```

This currently skips cleanly when `qemu-system-xtensa` is not installed.

## Project layout

- `main/`: app entry and project Kconfig
- `components/beet_firmware/`: controller, board, storage, and type definitions
- `partitions/`: custom partition map
- `tools/`: reserved for repo-local firmware tooling

## Next implementation steps

- validate the real battery divider on `GPIO2` against multimeter readings
- validate pair 1 moisture input on `GPIO10` with a controlled analog source
- expand the same measurement pass to the remaining moisture channels
- only then enable relay self-test and move to relay-path validation
