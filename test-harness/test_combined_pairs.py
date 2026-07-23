"""pytest entry for the combined-pairs suite.

Thin wrapper around `Orchestrator.run("combined_pairs")`.
Tests store/readback/clear of lead/follower sensor-sharing
configuration via BLE on a real ESP32-S3 controller.

Hardware-required. This file is the harness's entry point.
"""

from __future__ import annotations

import pytest

from harness.orchestrator import Orchestrator


def test_combined_pairs(orchestrator: Orchestrator) -> None:
    """Run the combined-pairs suite end-to-end."""
    result = orchestrator.run("combined_pairs")
    if not result.passed:
        pytest.fail(
            f"combined_pairs failed: {result.fail_reason}; "
            f"see {result.manifest_path}"
        )
