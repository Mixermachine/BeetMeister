# BeetMeister E2E Test Harness

Cross-platform (Windows / Linux / Mac) orchestrator for the
Kotlin robot-pattern instrumentation tests under
`app/app/src/androidTest/java/de/aarondietz/beetmeister/e2e/`.

Status: **Phase 3 (orchestrator core, fresh-install dry-run)**.

> Full plan: `.pi/plans/2026-07-12-e2e-test-harness-md.md`.
> README will be expanded in Phase 6 (Install / Config / Run /
> Interpreting results / OS notes / Troubleshooting).

## What lives here

- `harness/` — the Python orchestrator package.
- `config.example.toml` — schema; copy to `config.toml` and edit.
- `requirements.txt` — `pyserial`, `pytest`, `tomli` (3.10 fallback).
- `runs/` — per-run evidence folders (git-ignored).
- `firmware_cache/` — cached `v0.3.0` build (git-ignored).
- `run.py` — `python run.py <suite>` entry point.

## Quick start (P3 dry-run)

```bash
# from the repo root
python -m pip install -r test-harness/requirements.txt

cp test-harness/config.example.toml test-harness/config.toml
# edit config.toml: set [device].adb_serial + [controller].serial_port

python test-harness/run.py --dry-run fresh_install
```

The dry-run walks the full pipeline through the smoke gate
(`MaintenanceUpdateInstrumentationTest`, 4 pure-UI tests) and stops
before invoking the real `FreshInstallE2ETest`. It proves the build,
install, capture, and gate work end-to-end on this machine.
