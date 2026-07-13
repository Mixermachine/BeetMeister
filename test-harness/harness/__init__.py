"""BeetMeister cross-platform E2E test harness.

Hybrid Python orchestrator (host) + Kotlin robot-pattern
instrumentation tests (androidTest). See
`.pi/plans/2026-07-12-e2e-test-harness-md.md` for the design.

Public surface:
- harness.config: config loading (Toml, absolute paths).
- harness.adb: thin adb wrapper (cross-platform, `-s` aware).
- harness.controller_reset: esptool partition erase (no BLE).
- harness.firmware: on-demand old-firmware build + flash.
- harness.partition_map: parse the ESP-IDF partitions CSV.
- harness.capture: logcat + serial subprocess wrappers.
- harness.run_folder: per-run evidence folder + manifest.
- harness.screenshots: host-initiated screencap.
- harness.orchestrator: `Orchestrator.run(suite, **kwargs)` pipeline owner.
"""

__version__ = "0.3.0"
