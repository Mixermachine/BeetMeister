# Repository Guidelines

## Project Structure & Module Organization

- `app/`: Android app (`:app`) written in Kotlin + Compose. Main code lives in `app/app/src/main`, unit tests in `app/app/src/test`, instrumentation tests in `app/app/src/androidTest`.
- `firmware/esp-idf/`: ESP-IDF project for the ESP32-S3 controller. Core logic is in `components/beet_firmware`, app entry is in `main/`, partition maps in `partitions/`.
- `firmware/tests/host/`: non-hardware firmware tests for pure logic, BLE JSON codecs, and event-ring helpers.
- `docs/`: normative specs and planning docs. Update these when behavior or contracts change.
- `hardware/`: wiring, Fritzing artifacts, and electrical documentation.
- `.agents/skills/`: repo-local Codex skills. Keep new skills here, not under `skills/`.

## Build, Test, and Development Commands

- Android app:
  - from `app/`, use the Gradle wrapper to build the debug APK (`:app:assembleDebug`)
  - from `app/`, run JVM unit tests (`:app:testDebugUnitTest`)
- Firmware:
  - use `$esp-idf-installation` in `.agents/skills/esp-idf-installation` for build, flash, monitor, and ESP-IDF repair workflows
  - firmware project root is `firmware/esp-idf`
- Non-hardware firmware tests:
  - use `scripts/dev/run-firmware-host-tests.ps1` for host-side validation
  - use `scripts/dev/run-firmware-qemu-smoke.ps1` for QEMU smoke when QEMU is installed

## Coding Style & Naming Conventions

- Use ASCII by default.
- Kotlin: standard Kotlin/Compose style, `UpperCamelCase` for composables/types, `lowerCamelCase` for functions/fields.
- C firmware: `snake_case` for functions/statics, `BEET_*` for constants/macros, short focused helpers, minimal comments.
- Keep JSON field names and BLE/MQTT contract strings aligned with the docs.

## Testing Guidelines

- Add app unit tests under `app/app/src/test` and firmware host tests under `firmware/tests/host`.
- Prefer pure logic extraction over hardware mocking when adding firmware tests.
- Validate firmware with `invoke-idf.ps1 build` before flashing.
- Do not treat bench diagnostics as a substitute for automated tests.

## Commit & Pull Request Guidelines

- Follow the existing history style: short imperative subject lines, e.g. `Add shared control interface and NimBLE transport`.
- Keep commits focused by subsystem.
- PRs should include:
  - what changed
  - affected areas (`app`, `firmware`, `docs`, `hardware`)
  - test/build evidence
  - screenshots for UI/OLED/Fritzing changes when relevant

## Repository Hygiene

- If you create a real repo file, add it to git tracking in the same task.
- Do not commit temporary files, build outputs, caches, or local machine config such as `local.properties`.
