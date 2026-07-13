"""Per-run evidence folder + manifest.

The orchestrator creates one folder per invocation of `run.py`.
The folder layout is fixed (see `.pi/plans/2026-07-12-e2e-test-harness-md.md`):

    runs/YYYYMMDD-HHMMSS-<suite>/
      manifest.json
      android-logcat.txt           (continuous logcat)
      controller-serial.txt        (continuous controller serial, if port set)
      instrumentation.txt          (am instrument stdout, written by orchestrator)
      screenshots/                 (host-initiated: on-fail, final)
      logcat.pid                   (capture subprocess pid, for diagnostics)
      controller-serial.pid

The manifest is seeded with start time + per-suite params; the
orchestrator calls `finalize(manifest, passed, …)` at the end to
stamp pass/fail + durations + per-test results.
"""

from __future__ import annotations

import json
import time
from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


@dataclass
class Manifest:
    """Per-run evidence manifest.

    Frozen-at-start fields (set by `seed`):
      suite, start_iso, start_epoch, device_serial, controller_port,
      pinned_firmware_tag, run_dir, extras

    Updated by `Orchestrator.run` as the run progresses:
      smoke_passed, smoke_duration_s, e2e_results
      build_label, firmware_version, runtime_protocol_version
      pass_, fail_reason
      end_iso, end_epoch
    """

    suite: str
    start_iso: str
    start_epoch: float
    device_serial: str
    controller_port: str
    pinned_firmware_tag: str
    run_dir: str
    extras: dict[str, Any] = field(default_factory=dict)

    smoke_passed: bool | None = None
    smoke_duration_s: float | None = None

    build_label: str | None = None
    firmware_version: str | None = None
    runtime_protocol_version: int | None = None

    e2e_results: list[dict[str, Any]] = field(default_factory=list)

    pass_: bool | None = None
    fail_reason: str | None = None

    end_iso: str | None = None
    end_epoch: float | None = None

    artifacts: dict[str, str] = field(default_factory=dict)


def _now() -> tuple[str, float]:
    """Return (ISO-8601 UTC, epoch-seconds)."""
    now = datetime.now(timezone.utc)
    return now.isoformat(timespec="seconds"), now.timestamp()


def _slug(suite: str) -> str:
    """Make a filesystem-safe slug from the suite name."""
    return "".join(c if c.isalnum() or c in "-_" else "-" for c in suite)


def create_run_folder(harness_dir: Path, suite: str) -> tuple[Path, Manifest]:
    """Create `runs/YYYYMMDD-HHMMSS-<suite>/` + `screenshots/` subdir.

    Returns `(run_dir, manifest_seed)`. The manifest is seeded
    but not yet written — call `write(manifest)` to flush.
    """
    iso, epoch = _now()
    ts = datetime.fromtimestamp(epoch, tz=timezone.utc).strftime("%Y%m%d-%H%M%S")
    runs_root = harness_dir / "runs"
    run_dir = runs_root / f"{ts}-{_slug(suite)}"
    run_dir.mkdir(parents=True, exist_ok=False)
    (run_dir / "screenshots").mkdir(exist_ok=False)

    manifest = Manifest(
        suite=suite,
        start_iso=iso,
        start_epoch=epoch,
        device_serial="",
        controller_port="",
        pinned_firmware_tag="",
        run_dir=str(run_dir),
    )
    return run_dir, manifest


def write(manifest: Manifest) -> Path:
    """Write the manifest to `<run_dir>/manifest.json` and return the path."""
    path = Path(manifest.run_dir) / "manifest.json"
    path.write_text(
        json.dumps(asdict(manifest), indent=2, sort_keys=True),
        encoding="utf-8",
    )
    return path


def finalize(
    manifest: Manifest,
    *,
    passed: bool,
    fail_reason: str | None = None,
) -> Path:
    """Stamp end time + pass/fail and persist.

    Idempotent: safe to call multiple times (last write wins).
    """
    iso, epoch = _now()
    manifest.end_iso = iso
    manifest.end_epoch = epoch
    manifest.pass_ = passed
    if fail_reason is not None:
        manifest.fail_reason = fail_reason
    return write(manifest)


def stamp_artifacts(manifest: Manifest, **paths: Path) -> None:
    """Record artifact paths in the manifest (relative to run_dir)."""
    run = Path(manifest.run_dir)
    for k, p in paths.items():
        if p is None:
            continue
        try:
            rel = str(p.resolve().relative_to(run))
        except ValueError:
            rel = str(p)
        manifest.artifacts[k] = rel
