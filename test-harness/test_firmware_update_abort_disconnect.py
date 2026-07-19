"""pytest entry for the firmware-update-abort-disconnect suite.

Thin wrapper around `Orchestrator.run("firmware_update_abort_disconnect")`.

Hardware-required. Preconditions:
- Flash old firmware (pinned_tag) to controller
- Uninstall app, install APKs, clear BLE bond
- App auto-connects (forced MaintenanceRequired)

Test sequence:
- Start firmware OTA on the Maintenance screen
- Assert Abort + Disconnect buttons visible on same screen
- Tap Abort to stop the update
- Tap Disconnect to return to connection gate
"""

from __future__ import annotations

import pytest

from harness.orchestrator import Orchestrator


def test_firmware_update_abort_disconnect(orchestrator: Orchestrator) -> None:
    """Run the firmware-update-abort-disconnect suite end-to-end."""
    result = orchestrator.run("firmware_update_abort_disconnect")
    if not result.passed:
        pytest.fail(
            f"firmware_update_abort_disconnect failed: {result.fail_reason}; "
            f"see {result.manifest_path}"
        )
