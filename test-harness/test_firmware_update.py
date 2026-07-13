"""pytest entry for the firmware-update suite.

Thin wrapper around `Orchestrator.run("firmware_update")` —
the orchestrator owns the pipeline (build, install, capture,
smoke gate, dispatch, screenshot pull, manifest). This file
only:

  1. Calls `orchestrator.run("firmware_update")`.
  2. Asserts `result.passed` is True.
  3. Re-raises `result.fail_reason` as a pytest `AssertionError`
     so the user sees WHY the suite failed in the pytest output.

Hardware-required. P5 territory; this file is the harness's
"this is how the suite is invoked" entry point.

Destructive: this suite flashes the controller with the pinned
old firmware (default v0.3.0) as a precondition. The on-device
Kotlin test then drives the real OTA back to the bundled image.
"""

from __future__ import annotations

import pytest

from harness.orchestrator import Orchestrator


def test_firmware_update(orchestrator: Orchestrator) -> None:
    """Run the firmware-update suite end-to-end.

    P4 stop point (manual verification): `python test-harness/run.py
    --dry-run-dispatch firmware_update` composes the dispatch plan
    (with `expected_old_build_label=v0.3.0` placeholder +
    `expected_new_build_label=<parsed from bundled-firmware-stamp>`)
    without executing. This pytest entry is the real run path.
    """
    result = orchestrator.run("firmware_update")
    if not result.passed:
        pytest.fail(
            f"firmware_update failed: {result.fail_reason}; "
            f"see {result.manifest_path}"
        )
