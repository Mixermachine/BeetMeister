"""pytest entry for the settings-update suite.

Thin wrapper around `Orchestrator.run("settings_update")` —
the orchestrator owns the pipeline (build, install, capture,
smoke gate, dispatch, screenshot pull, manifest). This file
only:

  1. Calls `orchestrator.run("settings_update")`.
  2. Asserts `result.passed` is True.
  3. Re-raises `result.fail_reason` as a pytest `AssertionError`
     so the user sees WHY the suite failed in the pytest output.

The orchestrator runs ONE `am instrument -e class
SettingsUpdateE2ETest` for the FULL test class so the
class-shared @Before connect fires once and all 7 @Test
siblings (wateringInterval / maxActivePumps / valveEnabled /
valveMoveDuration / valveSettleDelay / valveOpenHold /
pairName) share the connection. Per-test pass/fail is parsed
from `am instrument` stdout into the manifest's
`e2e_results[0].tests`.

Hardware-required. P5 territory; this file is the harness's
"this is how the suite is invoked" entry point.
"""

from __future__ import annotations

import pytest

from harness.orchestrator import Orchestrator


def test_settings_update(orchestrator: Orchestrator) -> None:
    """Run the settings-update suite end-to-end.

    P4 stop point (manual verification): `python test-harness/run.py
    --dry-run-dispatch settings_update` composes the dispatch plan
    (ONE `am instrument -e class SettingsUpdateE2ETest` call) without
    executing. This pytest entry is the real run path.
    """
    result = orchestrator.run("settings_update")
    if not result.passed:
        pytest.fail(
            f"settings_update failed: {result.fail_reason}; "
            f"see {result.manifest_path}"
        )
