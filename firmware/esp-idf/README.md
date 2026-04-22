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
- bench diagnostics that log parseable raw ADC, millivolt, and pair-state data
- automatic recovery from sensor-invalid faults when a valid reading returns
- optional relay self-test mode that cycles one relay at a time for bench validation

## Safety defaults

- Live pump actuation is disabled by default through `CONFIG_BEET_ENABLE_PUMP_OUTPUTS=n`.
- The flashed image will evaluate and log watering need, but it will not energize relays until that Kconfig flag is enabled explicitly.
- Relay self-test is also disabled by default and must be enabled explicitly through `CONFIG_BEET_ENABLE_RELAY_SELF_TEST`.

## Bench bring-up behavior

- Battery reads are filtered and large one-shot spikes are rejected before state transitions.
- Battery scaling uses a clean two-stage model:
  - measured divider factor on `GPIO2`
  - battery-path calibration factor
- Bench diagnostics are enabled by default and emit lines such as:
  - `bench battery raw=... sensed_mv=... divider_mv=... scaled_mv=... filtered_mv=...`
  - `bench pair=... relay_gpio=... moisture_gpio=... raw=... mv=... pct=...`
- If a pair faults because of an invalid sensor reading and later receives a valid reading again, the controller returns that pair to `IDLE` automatically.

## Build and flash

Use the project-local wrapper so the repo uses the installed ESP-IDF toolchain consistently on Windows:

```powershell
cd C:\git\BeetMeister\firmware\esp-idf
powershell -ExecutionPolicy Bypass -File ..\..\skills\esp-idf-installation\scripts\invoke-idf.ps1 build
powershell -ExecutionPolicy Bypass -File ..\..\skills\esp-idf-installation\scripts\invoke-idf.ps1 -p COM7 flash
```

For serial logs:

```powershell
powershell -ExecutionPolicy Bypass -File ..\..\skills\esp-idf-installation\scripts\invoke-idf.ps1 -p COM7 monitor
```

For a short bench-capture run with summary output:

```powershell
powershell -ExecutionPolicy Bypass -File ..\..\scripts\dev\capture-bench-diagnostics.ps1 -Port COM7 -DurationSeconds 15 -BenchOnly
```

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
