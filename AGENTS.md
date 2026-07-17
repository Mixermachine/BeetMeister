# Rules

## Where stuff lives
- `app/`: Android (Kotlin+Compose). Code:`app/app/src/main`, unit:`app/app/src/test`, instr:`app/app/src/androidTest`.
- `firmware/esp-idf/`: ESP32-S3. Logic:`components/beet_firmware`, entry:`main/`, partitions:`partitions/`.
- `firmware/tests/host/`: pure-logic, BLE JSON codec, event-ring tests. No hardware.
- `docs/`: normative specs + planning docs. Update when behavior/contracts change.
- `hardware/`: wiring, Fritzing, electrical docs.
- `.agents/skills/`: repo-local Codex skills. Not `skills/`.

## Build & test
- Android: `app/` → `./gradlew :app:assembleDebug`, tests:`:app:testDebugUnitTest`
- Firmware: root=`firmware/esp-idf`. Use esp-idf-installation skill (build, flash, monitor, repair).
- Host tests: `scripts/dev/run-firmware-host-tests.ps1`
- QEMU smoke: `scripts/dev/run-firmware-qemu-smoke.ps1` (if QEMU installed)

## Style
- Kotlin: `UpperCamelCase` composables/types, `lowerCamelCase` fns/fields. Standard.
- C: `snake_case` fns/statics, `BEET_*` macros. Short helpers. Minimal comments.

## Protocol versions
- BLE runtime change (command, field, result shape, wire-behavior) → bump `runtime_protocol_version` in `config/protocol_versions.properties`.
- Maintenance wire change (command names, required fields, compact aliases, field meanings, status shapes, or other on-wire behavior) → user approval first.
- No protocol-surface merge or ship without version + docs update.

## Tests
- App unit → `app/app/src/test`. Firmware host → `firmware/tests/host`.
- Prefer pure-logic extraction over hardware mocking for firmware tests.
- `invoke-idf.ps1 build` before flash.

## Commits & PRs
- Short imperative subjects, follow existing history style (e.g. `Add shared control interface and NimBLE transport`). Focused by subsystem.
- PR body: what changed, affected areas (`app`, `firmware`, `docs`, `hardware`), test/build evidence, screenshots for UI/OLED/Fritzing when relevant.

## Hygiene
- No temp files, build outputs, caches, or local machine config (e.g. `local.properties`).

## Serial readers
- Never run `serial_reader.py` or similar stream readers without `--max-seconds` or `--idle-exit` — will hang session.
- Prefer `invoke-idf.ps1 monitor` / `idf.py monitor` (esp-idf-installation skill) over `serial_reader.py`.
