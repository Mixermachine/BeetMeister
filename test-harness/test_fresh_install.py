"""pytest entry for the fresh-install suite.

Thin wrapper around `Orchestrator.run("fresh_install")` —
the orchestrator owns the pipeline (build, install, capture,
smoke gate, dispatch, screenshot pull, manifest). This file
only:

  1. Calls `orchestrator.run("fresh_install")`.
  2. Asserts `result.passed` is True.
  3. Re-raises `result.fail_reason` as a pytest `AssertionError`
     so the user sees WHY the suite failed in the pytest output.

Hardware-required. P5 territory; this file is the harness's
"this is how the suite is invoked" entry point so pytest
`--collect-only` lists the three suites and `pytest` runs them
via the same orchestrator the CLI uses.
"""

from __future__ import annotations

import pytest

from harness.orchestrator import Orchestrator


def test_fresh_install(orchestrator: Orchestrator) -> None:
    """Run the fresh-install suite end-to-end.

    P4 stop point (manual verification): `python test-harness/run.py
    --dry-run-dispatch fresh_install` composes the dispatch plan
    without executing. This pytest entry is the real run path.
    """
    result = orchestrator.run("fresh_install")
    if not result.passed:
        pytest.fail(
            f"fresh_install failed: {result.fail_reason}; "
            f"see {result.manifest_path}"
        )
