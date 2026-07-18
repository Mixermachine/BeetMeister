"""pytest fixtures for the harness.

P3 only needs enough to run `pytest` against the dry-run target.
The thin per-suite pytest files (`test_fresh_install.py` etc.)
are written in P4.
"""

from __future__ import annotations

import sys
from pathlib import Path

import pytest

_HERE = Path(__file__).resolve().parent
if str(_HERE) not in sys.path:
    sys.path.insert(0, str(_HERE))

from harness.orchestrator import Orchestrator  # noqa: E402


@pytest.fixture(scope="session")
def orchestrator() -> Orchestrator:
    """Single Orchestrator instance per pytest session."""
    return Orchestrator()
